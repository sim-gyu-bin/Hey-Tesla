package com.heytesla.app.vehicle.detection

import com.heytesla.app.AndroidBleScanTransport
import com.heytesla.app.DiagnosticRuntime

import android.Manifest
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.os.Looper

/** AltBeacon만 scan을 등록한다. 앱은 RAM 대상·정책 전달·세대 게이트만 소유한다. */
internal class BleSupplementalScanner(
    context: android.content.Context,
    private val runtime: DiagnosticRuntime,
    private val onMatch: (ScanResult, Boolean) -> Unit,
    private val onRunning: (Boolean) -> Unit,
    private val onFailure: (String, Int) -> Unit,
    foregroundNotification: android.app.Notification? = null,
    private val transport: BleScanTransport = AndroidBleScanTransport(context, runtime, foregroundNotification),
    private val scanPermission: () -> Boolean = { runtime.granted(Manifest.permission.BLUETOOTH_SCAN) },
    private val evidence: (BleEventEvidence) -> Unit = runtime::recordBleEvidence,
) {
    private var target: BleScanTarget? = null
    private var enabled = false
    private var paused = false
    private var generation = 0L
    private var session: BleScanSession? = null
    private var started = false
    private var stopping = false
    private var cleanupFailed = false
    private var hasMatch = false
    private var pausedAction: (() -> Unit)? = null
    private var pendingFailure: Pair<String, Int>? = null
    private var stoppedAction: ((Boolean) -> Unit)? = null

    val running: Boolean get() = started && !paused && !stopping && !cleanupFailed

    fun start(advertisedName: String?) {
        mainThread()
        if (enabled || stopping || cleanupFailed) return
        try {
            transport.prepare()
            if (!scanPermission()) { fail("BLE_SCAN_PERMISSION_REQUIRED", FAILURE_PERMISSION); return }
            if (advertisedName == null) { fail("BLE_SCAN_NAME_UNAVAILABLE", FAILURE_NAME); return }
            target = BleScanTarget.vehicleName(advertisedName)
            enabled = true
            paused = false
            hasMatch = false
            begin()
        } catch (_: BleScanCleanupException) {
            cleanupFailed = true
            fail("BLE_SCAN_ORPHAN_CLEANUP_FAILED", FAILURE_STOP)
        } catch (_: SecurityException) {
            fail("BLE_SCAN_PERMISSION_REQUIRED", FAILURE_PERMISSION)
        } catch (_: Exception) {
            fail("BLE_SCAN_START_FAILED", FAILURE_START)
        }
    }

    /** GATT는 실제 native stop 무예외 반환 뒤에만 발급한다. 실패 시 action을 호출하지 않는다. */
    fun pause(onPaused: () -> Unit = {}) {
        mainThread()
        if (!enabled || cleanupFailed) return
        paused = true
        pausedAction = onPaused
        if (!stopping) stopCurrent(release = false)
    }

    fun resume() {
        mainThread()
        if (!enabled || !paused || cleanupFailed) return
        paused = false
        pausedAction = null
        if (!stopping) begin()
    }

    fun stop(onStopped: ((Boolean) -> Unit)? = null) {
        mainThread()
        if (onStopped != null) stoppedAction = onStopped
        enabled = false
        paused = false
        pausedAction = null
        target?.clear()
        target = null
        if (cleanupFailed) {
            val action = stoppedAction
            stoppedAction = null
            action?.invoke(false)
            return
        }
        if (!stopping && !cleanupFailed) stopCurrent(release = true)
    }

    /** 기간 1100/30000ms·대기 0은 AltBeacon 소유다. 앱은 권한·FGS 준비만 확인한다. */
    fun reconcile() {
        mainThread()
        if (!enabled || paused || stopping || cleanupFailed) return
        if (!scanPermission()) { fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION); return }
        transport.blockedReason()?.let { fail(it, FAILURE_UNAVAILABLE) }
    }

    private fun begin() {
        if (!enabled || paused || stopping || cleanupFailed || session != null) return
        if (!scanPermission()) { fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION); return }
        transport.blockedReason()?.let { fail(it, FAILURE_UNAVAILABLE); return }
        val selected = target ?: return
        val filters = selected.configure { listOf(ScanFilter.Builder().setDeviceName(it).build()) } ?: return
        val identity = ++generation
        lateinit var delivery: BleScanSession
        delivery = BleScanSession(
            onStarted = { dispatch {
                if (!owns(identity, delivery) || started) return@dispatch
                started = true
                evidence(BleEventEvidence(BleEvidenceKind.SCAN_STARTED, scanRunning = true))
                if (owns(identity, delivery)) onRunning(true)
            } },
            onResult = { result -> dispatch { consumeResult(identity, delivery, selected, result) } },
            onResults = { results -> dispatch { consume(identity, delivery, selected, results) } },
            onFailure = { reason, code -> dispatch { if (owns(identity, delivery)) fail(reason, code) } },
        )
        session = delivery
        try { transport.start(filters, delivery) }
        catch (_: SecurityException) { if (owns(identity, delivery)) fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION) }
        catch (_: Exception) { if (owns(identity, delivery)) fail("BLE_SCAN_START_FAILED", FAILURE_START) }
    }

    private fun owns(identity: Long, source: BleScanSession): Boolean =
        enabled && !paused && !stopping && !cleanupFailed && generation == identity && session === source

    private fun consume(identity: Long, source: BleScanSession, selected: BleScanTarget, results: List<ScanResult>) {
        if (!owns(identity, source)) return
        if (!scanPermission()) { fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION); return }
        try {
            val latest = selected.latestMatching(results,
                advertisedName = { it.scanRecord?.deviceName },
                timestamp = { it.timestampNanos },
            ) ?: return
            deliver(latest)
        } catch (_: SecurityException) { fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION) }
        catch (_: Exception) { fail("BLE_SCAN_DELIVERY_FAILED", FAILURE_DELIVERY) }
    }

    private fun consumeResult(identity: Long, source: BleScanSession, selected: BleScanTarget, result: ScanResult) {
        if (!owns(identity, source)) return
        if (!scanPermission()) { fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION); return }
        try {
            if (selected.matches(result.scanRecord?.deviceName)) deliver(result)
        } catch (_: SecurityException) { fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION) }
        catch (_: Exception) { fail("BLE_SCAN_DELIVERY_FAILED", FAILURE_DELIVERY) }
    }

    private fun deliver(result: ScanResult) {
        val first = !hasMatch
        hasMatch = true
        // 광고 자체의 원시 단조시각이다. 앱 윈도우 만료·처리시각 대체는 없다.
        onMatch(result, first)
    }

    private fun stopCurrent(release: Boolean) {
        if (cleanupFailed || stopping) return
        generation++
        val old = session
        started = false
        stopping = true
        fun complete(nativeStopped: Boolean) {
            dispatch {
                if (!stopping || session !== old) return@dispatch
                var success = nativeStopped
                var released = release
                if (success && !enabled && !released) {
                    try { transport.release(); released = true } catch (_: Exception) { success = false }
                }
                if (!success) cleanupFailed = true else session = null
                onRunning(false)
                if (old != null || !success) evidence(BleEventEvidence(BleEvidenceKind.SCAN_STOPPED,
                    gate = if (success) null else BleEvidenceGate.UNAVAILABLE, accepted = success, scanRunning = false))
                // 정리 콜백의 동기 stop도 서비스 해제 결과를 확인한 뒤 완료한다.
                if (success && !enabled && !released) {
                    try { transport.release() } catch (_: Exception) {
                        success = false
                        cleanupFailed = true
                        session = old
                    }
                }
                stopping = false
                if (!success) {
                    enabled = false
                    pausedAction = null
                    target?.clear()
                    target = null
                    pendingFailure = "BLE_SCAN_STOP_FAILED" to FAILURE_STOP
                }
                val failure = pendingFailure
                pendingFailure = null
                val action = stoppedAction
                stoppedAction = null
                if (failure != null) {
                    evidence(BleEventEvidence(BleEvidenceKind.SCAN_FAILED,
                        gate = if (failure.second == FAILURE_PERMISSION) BleEvidenceGate.PERMISSION else BleEvidenceGate.UNAVAILABLE,
                        scanFailureCode = failure.second, accepted = false, scanRunning = false))
                    onFailure(failure.first, failure.second)
                } else if (enabled && paused && success) {
                    val pausedCallback = pausedAction
                    pausedAction = null
                    pausedCallback?.invoke()
                } else if (enabled && !paused && success) begin()
                action?.invoke(success)
            }
        }
        try {
            if (old == null) {
                if (release) transport.release()
                complete(true)
            } else transport.stop(old, release, ::complete)
        } catch (_: Exception) { complete(false) }
    }

    private fun fail(reason: String, code: Int) {
        enabled = false
        paused = false
        pausedAction = null
        target?.clear()
        target = null
        pendingFailure = reason to code
        if (cleanupFailed && session == null) {
            pendingFailure = null
            evidence(BleEventEvidence(BleEvidenceKind.SCAN_FAILED, gate = BleEvidenceGate.UNAVAILABLE,
                scanFailureCode = code, accepted = false, scanRunning = false))
            onFailure(reason, code)
        } else if (!stopping) stopCurrent(release = true)
    }

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == runtime.handler.looper) action() else runtime.handler.post { action() }
    }
    private fun mainThread() = check(Looper.myLooper() == runtime.handler.looper)

    companion object {
        const val FAILURE_PERMISSION = -2
        const val FAILURE_UNAVAILABLE = -4
        const val FAILURE_START = -5
        const val FAILURE_STOP = -6
        const val FAILURE_NAME = -7
        const val FAILURE_DELIVERY = -9
    }
}

internal class BleScanSession(
    private val onStarted: () -> Unit,
    private val onResult: (ScanResult) -> Unit,
    private val onResults: (List<ScanResult>) -> Unit,
    private val onFailure: (String, Int) -> Unit,
) {
    fun started() = onStarted()
    fun result(result: ScanResult) = onResult(result)
    fun results(results: List<ScanResult>) = onResults(results)
    fun failure(code: Int, reason: String = "BLE_SCAN_CALLBACK_FAILED") = onFailure(reason, code)
    fun malformed() = onFailure("BLE_SCAN_DELIVERY_FAILED", BleSupplementalScanner.FAILURE_DELIVERY)
}

internal interface BleScanTransport {
    fun prepare()
    fun blockedReason(): String?
    fun start(filters: List<ScanFilter>, session: BleScanSession)
    fun stop(session: BleScanSession, release: Boolean, complete: (Boolean) -> Unit)
    fun release()
}

internal class BleScanCleanupException : IllegalStateException()
