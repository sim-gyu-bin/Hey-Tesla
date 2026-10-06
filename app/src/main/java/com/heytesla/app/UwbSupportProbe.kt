package com.heytesla.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.ranging.RangingCapabilities
import android.ranging.RangingManager
import kotlinx.coroutines.flow.MutableStateFlow

internal enum class UwbProbeStatus(val label: String) {
    IDLE("아직 조회하지 않음"), RUNNING("UWB 지원 조회 중 · 최대 10초"),
    COMPLETE("지원 응답 수신 · 거리 측정·인증 성공 아님"),
    UNAVAILABLE("Android 16 framework backend 없음 · 하드웨어/GMS 미지원 판정 아님"),
    FAILED("조회 실패 · 지원 여부 미확인"), TIMED_OUT("10초 시간 초과"),
    CANCELED("조회 취소"), CLEANUP_FAILED("등록 해제 불명 · 앱 프로세스 재시작 필요"),
}

internal data class UwbSupportMetadata(
    val availability: Int?,
    val distance: Boolean?, val azimuth: Boolean?, val elevation: Boolean?, val background: Boolean?,
    val minimumIntervalMs: Long?, val channels: List<Int>?, val configIds: List<Int>?,
    val preambleIndexes: List<Int>?, val slotDurations: List<Int>?, val updateRates: List<Int>?,
) {
    val availabilityLabel: String get() = when (availability) {
        0 -> "NOT_SUPPORTED · framework 보고"
        1 -> "DISABLED_USER · 사용자 설정으로 비활성"
        2 -> "DISABLED_REGULATORY · 규제로 비활성"
        3 -> "ENABLED · framework 가용 · 차량 근접/거리 준비 아님"
        4 -> "DISABLED_USER_RESTRICTIONS · 사용자 제한"
        else -> "UNKNOWN · 원값 ${availability ?: "미수신"}"
    }
}

internal data class UwbProbeState(
    val status: UwbProbeStatus = UwbProbeStatus.IDLE,
    val hardwareFeature: Boolean? = null,
    val frameworkAvailable: Boolean? = null,
    val rangingPermission: Boolean? = null,
    val uwbRangingPermission: Boolean? = null,
    val metadata: UwbSupportMetadata? = null,
    val reason: String? = null,
)

/** Main 직렬 경계. 응답 처리도 큐에 넣어 register 호출 중 unregister 재진입을 막는다. */
internal class UwbSupportProbe(
    context: Context,
    private val state: MutableStateFlow<UwbProbeState>,
    private val runtime: DiagnosticRuntime,
) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var active: Long? = null
    private var manager: RangingManager? = null
    private var callback: RangingManager.RangingCapabilitiesCallback? = null
    private var registrationAttempted = false
    private var registrationConfirmed = false
    private var timeout: Runnable? = null
    private var deadline = 0L
    private var finishing = false

    init {
        if (runtime.uwbSupportCleanupFailed) state.value = UwbProbeState(status = UwbProbeStatus.CLEANUP_FAILED, reason = "CLEANUP_FAILED")
    }

    fun query() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (active != null || finishing || runtime.uwbSupportCleanupFailed) return
        if (!runtime.acquireUwbSupport()) {
            state.value = UwbProbeState(status = UwbProbeStatus.FAILED, reason = "DIAGNOSTIC_BUSY")
            runtime.event("UWB_SUPPORT_DIAGNOSTIC_BUSY")
            return
        }
        val id = ++generation
        active = id
        deadline = SystemClock.elapsedRealtime() + 10_000L
        state.value = UwbProbeState(status = UwbProbeStatus.RUNNING)
        runtime.event("UWB_SUPPORT_REQUESTED")
        if (active != id) return
        try {
            val backend = context.getSystemService(RangingManager::class.java)
            manager = backend
            state.value = state.value.copy(
                hardwareFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB),
                frameworkAvailable = backend != null,
                rangingPermission = granted("android.permission.RANGING"),
                uwbRangingPermission = granted("android.permission.UWB_RANGING"),
            )
            if (active != id) return
            if (backend == null) {
                finish(id, UwbProbeStatus.UNAVAILABLE, "BACKEND_UNAVAILABLE")
                return
            }
            val listener = object : RangingManager.RangingCapabilitiesCallback {
                override fun onRangingCapabilities(capabilities: RangingCapabilities) {
                    handler.post { receive(id, capabilities) }
                }
            }
            callback = listener
            val expiry = Runnable { finish(id, UwbProbeStatus.TIMED_OUT, "TIMEOUT") }
            timeout = expiry
            if (!handler.postDelayed(expiry, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))) {
                finish(id, UwbProbeStatus.FAILED, "REQUEST_FAILED")
                return
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                finish(id, UwbProbeStatus.TIMED_OUT, "TIMEOUT")
                return
            }
            registrationAttempted = true
            backend.registerCapabilitiesCallback(context.mainExecutor, listener)
            registrationConfirmed = true
        } catch (_: Exception) {
            // register may have changed native ownership before throwing; still attempt unregister.
            finish(id, UwbProbeStatus.FAILED, "REQUEST_FAILED")
        }
    }

    fun cancel(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        active?.let { finish(it, UwbProbeStatus.CANCELED, "CANCELED") }
        return !finishing && !runtime.uwbSupportCleanupFailed && !runtime.uwbSupportActive
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun receive(id: Long, capabilities: RangingCapabilities) {
        if (active != id) return
        if (SystemClock.elapsedRealtime() >= deadline) {
            finish(id, UwbProbeStatus.TIMED_OUT, "TIMEOUT")
            return
        }
        try {
            val cap = capabilities.uwbCapabilities
            val metadata = UwbSupportMetadata(
                availability = capabilities.technologyAvailability[RangingManager.UWB],
                distance = cap?.isDistanceMeasurementSupported,
                azimuth = cap?.isAzimuthalAngleSupported,
                elevation = cap?.isElevationAngleSupported,
                background = cap?.isBackgroundRangingSupported,
                minimumIntervalMs = cap?.minimumRangingInterval?.toMillis(),
                channels = cap?.supportedChannels, configIds = cap?.supportedConfigIds,
                preambleIndexes = cap?.supportedPreambleIndexes,
                slotDurations = cap?.supportedSlotDurations, updateRates = cap?.supportedRangingUpdateRates,
            )
            finish(id, UwbProbeStatus.COMPLETE, "METADATA_RECEIVED", metadata)
        } catch (_: Exception) { finish(id, UwbProbeStatus.FAILED, "METADATA_FAILED") }
    }

    private fun finish(id: Long, status: UwbProbeStatus, reason: String, metadata: UwbSupportMetadata? = null) {
        if (active != id || finishing) return
        finishing = true
        active = null
        timeout?.let(handler::removeCallbacks)
        timeout = null
        // SDK unregister may skip native teardown after a partial register failure.
        // A normal return in that case does not establish released ownership.
        var failed = registrationAttempted && !registrationConfirmed
        if (registrationAttempted) {
            try {
                val backend = checkNotNull(manager)
                backend.unregisterCapabilitiesCallback(checkNotNull(callback))
            } catch (_: Exception) { failed = true }
        }
        manager = null
        callback = null
        registrationAttempted = false
        registrationConfirmed = false
        // Runtime retains the failed reservation across Activity recreation.
        runtime.releaseUwbSupport(failed)
        state.value = state.value.copy(
            status = if (failed) UwbProbeStatus.CLEANUP_FAILED else status,
            reason = if (failed) "CLEANUP_FAILED" else reason,
            metadata = if (failed) null else metadata,
        )
        runtime.event("UWB_SUPPORT_${if (failed) "CLEANUP_FAILED" else reason}")
        finishing = false
    }
}
