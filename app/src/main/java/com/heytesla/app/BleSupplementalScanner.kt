package com.heytesla.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Looper

/** 등록 주소 하나만 RAM에서 필터링한다. 무필터 스캔·이름/주소/패킷 영속 기록은 없다. */
@SuppressLint("MissingPermission")
internal class BleSupplementalScanner(
    context: Context,
    private val runtime: DiagnosticRuntime,
    private val onMatch: (Long, Int, Boolean) -> Unit,
    private val onRunning: (Boolean) -> Unit,
    private val onFailure: (String, Int) -> Unit,
) {
    private val bluetooth = context.applicationContext.getSystemService(BluetoothManager::class.java)
    private var scanner: BluetoothLeScanner? = null
    private var filters: List<ScanFilter>? = null
    private val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .setReportDelay(0).build()
    private var enabled = false
    private var paused = false
    private var generation = 0L
    private var callback: ScanCallback? = null
    private var timer: Runnable? = null
    private var nextStartAt = 0L
    private var windowEndsAt = 0L
    private var everStarted = false
    private var resumePending = false
    private var hasMatch = false

    val running: Boolean get() = callback != null

    fun start(associationId: Int) {
        mainThread()
        if (enabled) return
        try {
            if (!runtime.granted(Manifest.permission.BLUETOOTH_SCAN)) {
                fail("BLE_SCAN_PERMISSION_REQUIRED", FAILURE_PERMISSION, BleEvidenceGate.PERMISSION)
                return
            }
            val selected = runtime.cdm?.myAssociations?.singleOrNull { !it.isSelfManaged }
            val address = selected?.takeIf { it.id == associationId }?.deviceMacAddress
            if (address == null) {
                fail("BLE_SCAN_ADDRESS_UNAVAILABLE", FAILURE_ADDRESS, BleEvidenceGate.UNAVAILABLE)
                return
            }
            filters = listOf(ScanFilter.Builder().setDeviceAddress(address.toString().uppercase(java.util.Locale.ROOT)).build())
            enabled = true
            nextStartAt = runtime.now()
            everStarted = false
            resumePending = false
            hasMatch = false
            cycle()
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
        resumePending = true
        cycle()
    }

    fun stop() {
        mainThread()
        val finalStop = enabled && !running
        enabled = false
        paused = false
        clearTimer()
        stopCurrent()
        if (finalStop) {
            runtime.recordBleEvidence(BleEventEvidence(BleEvidenceKind.SCAN_STOPPED, scanRunning = false))
        }
        filters = null
    }

    private fun cycle() {
        if (!enabled || paused) return
        if (runtime.now() < nextStartAt) {
            schedule(nextStartAt)
            return
        }
        if (!runtime.granted(Manifest.permission.BLUETOOTH_SCAN)) {
            fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION, BleEvidenceGate.PERMISSION)
            return
        }
        val adapter = try { bluetooth?.adapter } catch (_: SecurityException) {
            fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION, BleEvidenceGate.PERMISSION)
            return
        } catch (_: Exception) {
            fail("BLE_SCAN_UNAVAILABLE", FAILURE_UNAVAILABLE, BleEvidenceGate.UNAVAILABLE)
            return
        }
        try {
            if (adapter == null || !adapter.isEnabled) {
                fail("BLE_SCAN_UNAVAILABLE", FAILURE_UNAVAILABLE, BleEvidenceGate.UNAVAILABLE)
                return
            }
            if (!adapter.isOffloadedFilteringSupported) {
                fail("BLE_SCAN_NO_OFFLOADED_FILTER", FAILURE_OFFLOADED_FILTER, BleEvidenceGate.NO_OFFLOADED_FILTER)
                return
            }
            val owner = adapter.bluetoothLeScanner
            val selectedFilters = filters
            if (owner == null || selectedFilters == null) {
                fail("BLE_SCAN_UNAVAILABLE", FAILURE_UNAVAILABLE, BleEvidenceGate.UNAVAILABLE)
                return
            }
            scanner = owner
            val identity = ++generation
            val listener = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    match(identity, this, result.timestampNanos / 1_000_000L, result.rssi)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    // 제조사 batch 순서를 신뢰하지 않고 실제 관측 시각이 가장 최근인 한 개만 소비한다.
                    var latest: ScanResult? = null
                    for (result in results) {
                        if (latest == null || result.timestampNanos > latest.timestampNanos) latest = result
                    }
                    latest?.let { match(identity, this, it.timestampNanos / 1_000_000L, it.rssi) }
                }

                override fun onScanFailed(errorCode: Int) {
                    dispatch {
                        if (generation == identity && callback === this) {
                            fail("BLE_SCAN_CALLBACK_FAILED", errorCode, BleEvidenceGate.UNAVAILABLE)
                        }
                    }
                }
            }
            callback = listener
            val startedAt = runtime.now()
            nextStartAt = startedAt + PERIOD_MS
            windowEndsAt = startedAt + WINDOW_MS
            owner.startScan(selectedFilters, settings, listener)
            if (!enabled || paused || callback !== listener) return
            onRunning(true)
            if (!everStarted || resumePending) {
                runtime.recordBleEvidence(BleEventEvidence(BleEvidenceKind.SCAN_STARTED, scanRunning = true))
            }
            everStarted = true
            resumePending = false
            schedule(windowEndsAt)
        } catch (_: SecurityException) {
            fail("BLE_SCAN_PERMISSION_REVOKED", FAILURE_PERMISSION, BleEvidenceGate.PERMISSION)
        } catch (_: Exception) {
            fail("BLE_SCAN_START_FAILED", FAILURE_START, BleEvidenceGate.UNAVAILABLE)
        }
    }

    private fun match(identity: Long, source: ScanCallback, received: Long, rssi: Int) {
        if (Looper.myLooper() == runtime.handler.looper) consumeMatch(identity, source, received, rssi)
        else runtime.handler.post { consumeMatch(identity, source, received, rssi) }
    }

    private fun consumeMatch(identity: Long, source: ScanCallback, received: Long, rssi: Int) {
        if (!enabled || paused || generation != identity || callback !== source ||
            runtime.now() >= windowEndsAt
        ) return
        val firstMatch = !hasMatch
        hasMatch = true
        onMatch(received, rssi, firstMatch)
    }

    /** Main handler에 스캔 종료 또는 다음 시작 중 하나만 둔다. pause/stop은 callback generation도 폐기한다. */
    private fun schedule(at: Long) {
        clearTimer()
        val identity = generation
        val task = Runnable {
            timer = null
            if (!enabled || paused || generation != identity) return@Runnable
            if (running && !stopCurrent(recordEvidence = false)) return@Runnable
            cycle()
        }
        timer = task
        runtime.handler.postDelayed(task, (at - runtime.now()).coerceAtLeast(0))
    }

    private fun clearTimer() {
        timer?.let { runtime.handler.removeCallbacks(it) }
        timer = null
    }

    private fun stopCurrent(reportFailure: Boolean = true, recordEvidence: Boolean = true): Boolean {
        generation++
        val old = callback
        callback = null
        val owner = scanner
        scanner = null
        if (old == null) return true
        var stopped = true
        try { checkNotNull(owner).stopScan(old) } catch (_: Exception) { stopped = false }
        onRunning(false)
        if (recordEvidence || !stopped) {
            runtime.recordBleEvidence(BleEventEvidence(BleEvidenceKind.SCAN_STOPPED,
                gate = if (stopped) null else BleEvidenceGate.UNAVAILABLE, accepted = stopped, scanRunning = false))
        }
        if (!stopped && reportFailure) {
            fail("BLE_SCAN_STOP_FAILED", FAILURE_STOP, BleEvidenceGate.UNAVAILABLE)
        }
        return stopped
    }
    private fun fail(reason: String, code: Int, gate: BleEvidenceGate) {
        enabled = false
        paused = false
        clearTimer()
        // 실패 callback도 폐기한 뒤 중지를 알린다. 실패한 stop을 무한 재호출하지 않는다.
        stopCurrent(reportFailure = false)
        filters = null
        runtime.recordBleEvidence(BleEventEvidence(BleEvidenceKind.SCAN_FAILED,
            gate = gate, scanFailureCode = code, accepted = false, scanRunning = false))
        onFailure(reason, code)
    }
    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == runtime.handler.looper) action() else runtime.handler.post { action() }
    }

    private fun mainThread() = check(Looper.myLooper() == runtime.handler.looper)

    companion object {
        const val WINDOW_MS = 40_000L
        const val PERIOD_MS = 120_000L
        const val FAILURE_ADDRESS = -1
        const val FAILURE_PERMISSION = -2
        const val FAILURE_OFFLOADED_FILTER = -3
        const val FAILURE_UNAVAILABLE = -4
        const val FAILURE_START = -5
        const val FAILURE_STOP = -6
    }
}
