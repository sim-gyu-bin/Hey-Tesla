package com.heytesla.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Looper
import java.util.UUID

/** 주소 또는 공식 광고명 하나만 RAM에서 exact 필터링한다. 무필터 스캔·식별자 기록은 없다. */
@SuppressLint("MissingPermission")
internal class BleSupplementalScanner(
    context: Context,
    private val runtime: DiagnosticRuntime,
    private val onMatch: (Long, Int, Boolean) -> Unit,
    private val onRunning: (Boolean) -> Unit,
    private val onFailure: (String, Int) -> Unit,
    private val transport: BleScanTransport = AndroidBleScanTransport(context, runtime),
    private val now: () -> Long = runtime::now,
    private val scanPermission: () -> Boolean = { runtime.granted(Manifest.permission.BLUETOOTH_SCAN) },
    private val evidence: (BleEventEvidence) -> Unit = runtime::recordBleEvidence,
    private val postDelayed: (Runnable, Long) -> Unit = { task, delay -> runtime.handler.postDelayed(task, delay); Unit },
    private val removeCallbacks: (Runnable) -> Unit = { runtime.handler.removeCallbacks(it) },
) {
    private var target: BleScanTarget? = null
    private var filters: List<ScanFilter>? = null
    private val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .setReportDelay(REPORT_DELAY_MS).build()
    private var enabled = false
    private var paused = false
    private var generation = 0L
    private var session: BleScanSession? = null
    private var windowStarted = false
    private var timer: Runnable? = null
    private var nextStartAt = 0L
    private var windowEndsAt = 0L
    private var hasMatch = false
    private var cleanupFailed = false

    val running: Boolean get() = session != null && windowStarted && !cleanupFailed

    fun start(associationId: Int, config: BleFieldConfig, advertisedName: String? = null) {
        mainThread()
        if (enabled || cleanupFailed) return
        config.safetyBlockedReason()?.let {
            fail(it, FAILURE_UNAVAILABLE, BleEvidenceGate.UNAVAILABLE)
            return
        }
        try {
            // 새 사용자 시작 전에만 고아 등록을 정리한다. 등록 복원/자동 scan 시작은 없다.
            transport.prepare()
            if (!scanPermission()) {
                fail("BLE_SCAN_PERMISSION_REQUIRED", FAILURE_PERMISSION, BleEvidenceGate.PERMISSION)
                return
            }
            val selectedTarget = when (config.scanFilterMode) {
                BleScanFilterMode.ASSOCIATION_ADDRESS -> {
                    val address = transport.associationAddress(associationId)
                    if (address == null) {
                        fail("BLE_SCAN_ADDRESS_UNAVAILABLE", FAILURE_ADDRESS, BleEvidenceGate.UNAVAILABLE)
                        return
                    }
                    BleScanTarget.associationAddress(address)
                }
                BleScanFilterMode.VEHICLE_NAME -> {
                    if (advertisedName == null) {
                        fail("BLE_SCAN_NAME_UNAVAILABLE", FAILURE_NAME, BleEvidenceGate.UNAVAILABLE)
                        return
                    }
                    BleScanTarget.vehicleName(advertisedName)
                }
            }
            target = selectedTarget
            filters = listOf(checkNotNull(selectedTarget.configure(
                address = { ScanFilter.Builder().setDeviceAddress(it).build() },
                name = { ScanFilter.Builder().setDeviceName(it).build() },
            )))
            enabled = true
            nextStartAt = now()
            hasMatch = false
            cycle()
        } catch (_: BleScanCleanupException) {
            cleanupFailed = true
            fail("BLE_SCAN_ORPHAN_CLEANUP_FAILED", FAILURE_STOP, BleEvidenceGate.UNAVAILABLE)
        } catch (_: SecurityException) {
            fail("BLE_SCAN_PERMISSION_REQUIRED", FAILURE_PERMISSION, BleEvidenceGate.PERMISSION)
        } catch (_: Exception) {
            fail("BLE_SCAN_UNAVAILABLE", FAILURE_UNAVAILABLE, BleEvidenceGate.UNAVAILABLE)
        }
    }

    fun pause() {
        mainThread()
        if (!enabled || paused) return
        paused = true
        clearTimer()
        stopCurrent()
    }

    fun resume() {
        mainThread()
        if (!enabled || !paused) return
        paused = false
        cycle()
    }

    fun stop() {
        mainThread()
        enabled = false
        paused = false
        clearTimer()
        target?.clear()
        target = null
        stopCurrent()
        filters = null
    }

    private fun cycle() {
        if (!enabled || paused || cleanupFailed) return
        if (now() < nextStartAt) {
            schedule(nextStartAt)
            return
        }
        if (!scanPermission()) {
            fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION, BleEvidenceGate.PERMISSION)
            return
        }
        try {
            if (!transport.isEnabled()) {
                fail("BLE_SCAN_UNAVAILABLE", FAILURE_UNAVAILABLE, BleEvidenceGate.UNAVAILABLE)
                return
            }
            if (!transport.supportsOffloadedFiltering()) {
                fail("BLE_SCAN_NO_OFFLOADED_FILTER", FAILURE_OFFLOADED_FILTER, BleEvidenceGate.NO_OFFLOADED_FILTER)
                return
            }
            if (!transport.supportsOffloadedBatching()) {
                fail("BLE_SCAN_NO_OFFLOADED_BATCH", FAILURE_OFFLOADED_BATCH, BleEvidenceGate.UNAVAILABLE)
                return
            }
            val selectedFilters = filters
            val selectedTarget = target
            if (selectedTarget == null || selectedFilters == null) {
                fail("BLE_SCAN_UNAVAILABLE", FAILURE_UNAVAILABLE, BleEvidenceGate.UNAVAILABLE)
                return
            }
            val identity = ++generation
            lateinit var delivery: BleScanSession
            delivery = BleScanSession(
                onResults = { results, error -> dispatch { consume(identity, delivery, selectedTarget, results, error) } },
                onMalformed = { dispatch {
                    if (accepts(identity, delivery)) {
                        fail("BLE_SCAN_DELIVERY_FAILED", FAILURE_DELIVERY, BleEvidenceGate.UNAVAILABLE)
                    }
                } },
            )
            session = delivery
            val startedAt = now()
            nextStartAt = startedAt + PERIOD_MS
            windowEndsAt = startedAt + WINDOW_MS
            val error = transport.start(selectedFilters, settings, delivery)
            if (error != 0) {
                fail("BLE_SCAN_PENDING_INTENT_FAILED", error, BleEvidenceGate.UNAVAILABLE)
                return
            }
            if (!enabled || paused || session !== delivery) return
            windowStarted = true
            evidence(BleEventEvidence(BleEvidenceKind.SCAN_STARTED, scanRunning = true))
            // evidence는 로그 실패 등으로 동기 stop/rearm을 유발할 수 있다.
            if (!accepts(identity, delivery)) return
            onRunning(true)
            if (enabled && !paused && session === delivery) schedule(windowEndsAt)
        } catch (_: SecurityException) {
            fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION, BleEvidenceGate.PERMISSION)
        } catch (_: Exception) {
            fail("BLE_SCAN_START_FAILED", FAILURE_START, BleEvidenceGate.UNAVAILABLE)
        }
    }

    private fun accepts(identity: Long, source: BleScanSession): Boolean {
        if (!enabled || paused || cleanupFailed || !windowStarted ||
            generation != identity || session !== source
        ) return false
        if (now() < windowEndsAt) return true
        // 절전 뒤 늦은 delivery가 깨어나면 현재 만료 등록을 즉시 닫는다.
        // 새 scan은 여기서 시작하지 않고 기존 nextStartAt 기준 Main timer에만 맡긴다.
        if (stopCurrent() && enabled && !paused) schedule(nextStartAt)
        return false
    }

    private fun consume(identity: Long, source: BleScanSession, selectedTarget: BleScanTarget,
        results: List<ScanResult>, error: Int,
    ) {
        // 늦은 배치/오류는 타깃 접근·권한 검사·정책 전달보다 먼저 폐기한다.
        if (!accepts(identity, source)) return
        if (!scanPermission()) {
            fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION, BleEvidenceGate.PERMISSION)
            return
        }
        if (error != 0) {
            fail("BLE_SCAN_PENDING_INTENT_FAILED", error, BleEvidenceGate.UNAVAILABLE)
            return
        }
        try {
            val latest = selectedTarget.latestMatching(
                results,
                address = { if (selectedTarget.mode == BleScanFilterMode.ASSOCIATION_ADDRESS) it.device.address else null },
                advertisedName = { if (selectedTarget.mode == BleScanFilterMode.VEHICLE_NAME) it.scanRecord?.deviceName else null },
                timestamp = { it.timestampNanos },
            ) ?: return
            val firstMatch = !hasMatch
            hasMatch = true
            onMatch(latest.timestampNanos / 1_000_000L, latest.rssi, firstMatch)
        } catch (_: SecurityException) {
            fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION, BleEvidenceGate.PERMISSION)
        } catch (_: Exception) {
            fail("BLE_SCAN_DELIVERY_FAILED", FAILURE_DELIVERY, BleEvidenceGate.UNAVAILABLE)
        }
    }

    /** Main의 uptime timer는 절전 중 elapsed 40초 종료를 보장하지 않는다. 수신 gate는 elapsed 기준이다. */
    private fun schedule(at: Long) {
        clearTimer()
        val identity = generation
        val task = Runnable {
            timer = null
            if (!enabled || paused || generation != identity) return@Runnable
            if (running && !stopCurrent()) return@Runnable
            cycle()
        }
        timer = task
        postDelayed(task, (at - now()).coerceAtLeast(0))
    }

    private fun clearTimer() {
        timer?.let(removeCallbacks)
        timer = null
    }

    private fun stopCurrent(reportFailure: Boolean = true): Boolean {
        if (cleanupFailed) return false
        generation++
        val old = session ?: return true
        var stopped = true
        try { transport.stop(old) } catch (_: Exception) { stopped = false }
        if (stopped) session = null else cleanupFailed = true
        onRunning(false)
        if (windowStarted || !stopped) {
            evidence(BleEventEvidence(BleEvidenceKind.SCAN_STOPPED,
                gate = if (stopped) null else BleEvidenceGate.UNAVAILABLE, accepted = stopped, scanRunning = false))
        }
        windowStarted = false
        if (!stopped && reportFailure) {
            fail("BLE_SCAN_STOP_FAILED", FAILURE_STOP, BleEvidenceGate.UNAVAILABLE)
        }
        return stopped
    }

    private fun fail(reason: String, code: Int, gate: BleEvidenceGate) {
        enabled = false
        paused = false
        clearTimer()
        target?.clear()
        target = null
        // 정리 예외를 원래 start/delivery 실패 뒤에 숨기지 않는다. 같은 handle은 유지한다.
        val stopped = stopCurrent(reportFailure = false)
        filters = null
        val cleanupFailure = !stopped
        val finalCode = if (cleanupFailure) FAILURE_STOP else code
        val finalReason = if (cleanupFailure && reason != "BLE_SCAN_ORPHAN_CLEANUP_FAILED") "BLE_SCAN_STOP_FAILED" else reason
        evidence(BleEventEvidence(BleEvidenceKind.SCAN_FAILED,
            gate = if (cleanupFailure) BleEvidenceGate.UNAVAILABLE else gate,
            scanFailureCode = finalCode, accepted = false, scanRunning = false))
        onFailure(finalReason, finalCode)
    }

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == runtime.handler.looper) action() else runtime.handler.post { action() }
    }

    private fun mainThread() = check(Looper.myLooper() == runtime.handler.looper)

    companion object {
        const val WINDOW_MS = 40_000L
        const val PERIOD_MS = 120_000L
        const val REPORT_DELAY_MS = 5_000L
        const val FAILURE_ADDRESS = -1
        const val FAILURE_PERMISSION = -2
        const val FAILURE_OFFLOADED_FILTER = -3
        const val FAILURE_UNAVAILABLE = -4
        const val FAILURE_START = -5
        const val FAILURE_STOP = -6
        const val FAILURE_NAME = -7
        const val FAILURE_OFFLOADED_BATCH = -8
        const val FAILURE_DELIVERY = -9
    }
}

/** 세션 token에는 타깃이 없다. receiver와 scanner가 동일 RAM 세션을 소유할 때만 소비한다. */
internal class BleScanSession(
    private val onResults: (List<ScanResult>, Int) -> Unit,
    private val onMalformed: () -> Unit,
) {
    val token: String = UUID.randomUUID().toString()
    fun results(results: List<ScanResult>) = onResults(results, 0)
    fun failure(error: Int) = onResults(emptyList(), error)
    fun malformed() = onMalformed()
}

/** 실제 Android 스캔 IO 한 경계. 계측의 RAM transport는 test 파일 안에서만 구현한다. */
internal interface BleScanTransport {
    fun prepare()
    fun associationAddress(associationId: Int): String?
    fun isEnabled(): Boolean
    fun supportsOffloadedFiltering(): Boolean
    fun supportsOffloadedBatching(): Boolean
    fun start(filters: List<ScanFilter>, settings: ScanSettings, session: BleScanSession): Int
    fun stop(session: BleScanSession)
}

internal class BleScanCleanupException : IllegalStateException()
