package com.heytesla.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import org.altbeacon.beacon.BeaconConsumer
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.Identifier
import org.altbeacon.beacon.Region
import org.altbeacon.beacon.Settings
import org.altbeacon.beacon.logging.LogManager
import org.altbeacon.beacon.logging.Loggers
import org.altbeacon.beacon.service.BeaconService
import org.altbeacon.beacon.service.scanner.RawScanCallback

/** 이전 버전 PI의 정리 전용 receiver. 새 scan/FGS/GATT/정책 전달을 발급하지 않는다. */
class BleScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? DiagnosticApp ?: return
        cleanupOrphanedScans(context, app.runtime)
    }

    companion object {
        @SuppressLint("MissingPermission")
        internal fun cleanupOrphanedScans(context: Context, runtime: DiagnosticRuntime): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper())
            try {
                val file = File(context.noBackupFilesDir, "ble_pending_scan_token")
                val token = try {
                    FileInputStream(file).use { input ->
                        val bytes = ByteArray(37)
                        var count = 0
                        while (count < bytes.size) {
                            val n = input.read(bytes, count, bytes.size - count)
                            if (n == -1) break
                            count += n
                        }
                        check(count == 0 || count == 36)
                        if (count == 0) null else String(bytes, 0, count, Charsets.US_ASCII)
                    }
                } catch (failure: java.io.FileNotFoundException) {
                    if (file.exists()) throw failure
                    null
                } ?: return true
                check(token.length == 36 && UUID.fromString(token).toString() == token)
                val intent = Intent(context, BleScanReceiver::class.java).setPackage(context.packageName)
                    .setAction("com.heytesla.app.BLE_SCAN_DELIVERY").setIdentifier(token)
                val handle = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
                val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
                checkNotNull(scanner).stopScan(handle)
                // 사용자 기존 marker도 보존한다. 파일 삭제·journal 비움·새 native 등록은 없다.
                return true
            } catch (_: Exception) {
                runtime.recordBleEvidence(BleEventEvidence(BleEvidenceKind.SCAN_FAILED,
                    gate = BleEvidenceGate.UNAVAILABLE, scanFailureCode = BleSupplementalScanner.FAILURE_STOP,
                    accepted = false, scanRunning = false))
                return false
            }
        }
    }
}

/** 실제 IO는 AltBeacon BeaconService만 소유한다. parser/simulator로 차량 광고를 위장하지 않는다. */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
internal class AndroidBleScanTransport(
    context: Context,
    private val runtime: DiagnosticRuntime,
    private val notification: Notification? = null,
) : BleScanTransport {
    private val context = context.applicationContext
    private val manager = BeaconManager.getInstanceForApplication(this.context)
    private val handler: Handler get() = runtime.handler
    private var consumer: BeaconConsumer? = null
    private var connected = false
    private var active: BleScanSession? = null
    private var ranged = false
    private var nativeAttempted = false
    private var stopCompletion: ((Boolean) -> Unit)? = null
    private var releaseAfterStop = false
    private var stopTimeout: Runnable? = null
    private var startTimeout: Runnable? = null
    private var serviceBindingIssued = false
    private var ownsManager = false
    // 스캔 수명만 유지하는 실제 Region. 광고를 Beacon으로 변환하거나 ranging 결과를 사용하지 않는다.
    private val region = Region("heytesla-raw-observation", emptyList<Identifier>())

    override fun prepare() {
        check(consumer == null && manager.rawScanCallback == null && !manager.isAnyConsumerBound)
        if (!BleScanReceiver.cleanupOrphanedScans(context, runtime)) throw BleScanCleanupException()
        ownsManager = true
        LogManager.setLogger(Loggers.empty()) // 주소·광고 byte 등 upstream 원시 로그를 영속 로그에 남기지 않는다.
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(ObservationService.CHANNEL,
            "등록 차량 접근 관찰", NotificationManager.IMPORTANCE_LOW))
        val foreground = notification ?: Notification.Builder(context, ObservationService.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("등록 차량 BLE 감지")
            .setContentText("관찰 전용 · 차량 권한 아님").setOngoing(true).build()
        // 스캔 기간·무선 모드는 기본값을 유지하고 화면 전환과 무관하게 대기 공백만 제거한다.
        manager.adjustSettings(Settings(regionStatePersistenceEnabled = false,
            scanPeriods = Settings.ScanPeriods(
                foregroundBetweenScanPeriodMillis = 0,
                backgroundBetweenScanPeriodMillis = 0),
            scanStrategy = Settings.ForegroundServiceScanStrategy(foreground, ObservationService.ALTBEACON_NOTIFICATION_ID)))
    }

    override fun associationAddress(associationId: Int): String? =
        runtime.cdm?.myAssociations?.singleOrNull { !it.isSelfManaged }
            ?.takeIf { it.id == associationId }?.deviceMacAddress?.toString()

    override fun blockedReason(): String? = when {
        !runtime.granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "BLE_SCAN_LOCATION_PERMISSION_REQUIRED"
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true -> "BLE_SCAN_LOCATION_SERVICES_DISABLED"
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true -> "BLE_SCAN_UNAVAILABLE"
        manager.foregroundServiceStartFailed() -> "BLE_SCAN_FGS_FALLBACK"
        connected && nativeAttempted && !foregroundObserved() -> "BLE_SCAN_FGS_NOT_RUNNING"
        else -> null
    }

    override fun start(filters: List<ScanFilter>, session: BleScanSession) {
        check(active == null && stopCompletion == null)
        active = session
        nativeAttempted = false
        manager.setRawScanFilters(filters)
        manager.setRawScanCallback(object : RawScanCallback {
            override fun onScanResult(result: ScanResult) {
                // session은 등록 당시 인스턴스다. 이전 native delivery를 새 세대에 귀속시키지 않는다.
                session.result(result)
            }
            override fun onBatchScanResults(results: List<ScanResult>) = session.results(results)
            override fun onScanStarted() = dispatch {
                if (active !== session || stopCompletion != null) return@dispatch
                clearStartTimeout()
                nativeAttempted = true
                val blocked = blockedReason()
                if (blocked != null) session.failure(BleSupplementalScanner.FAILURE_UNAVAILABLE, blocked)
                else session.started()
            }
            override fun onScanFailed(errorCode: Int) = dispatch {
                if (active !== session) return@dispatch
                clearStartTimeout()
                if (stopCompletion != null) finishStop(false)
                else session.failure(errorCode, when (errorCode) {
                    BleSupplementalScanner.FAILURE_PERMISSION -> "BLE_SCAN_PERMISSION_REVOKED"
                    BleSupplementalScanner.FAILURE_UNAVAILABLE -> "BLE_SCAN_UNAVAILABLE"
                    BleSupplementalScanner.FAILURE_START -> "BLE_SCAN_START_FAILED"
                    else -> "BLE_SCAN_CALLBACK_FAILED"
                })
            }
            override fun onScanStopped(success: Boolean) = dispatch {
                if (active === session && stopCompletion != null) finishStop(success)
                else if (active === session && !success) session.failure(BleSupplementalScanner.FAILURE_STOP, "BLE_SCAN_STOP_FAILED")
            }
        })
        if (!connected) {
            val timeout = Runnable {
                if (active === session && !connected && stopCompletion == null) {
                    session.failure(BleSupplementalScanner.FAILURE_START, "BLE_SCAN_START_FAILED")
                }
            }
            startTimeout = timeout
            handler.postDelayed(timeout, BIND_TIMEOUT_MS)
        }
        if (connected) startRanging(session)
        else if (consumer == null) {
            val owner = object : BeaconConsumer {
                override fun getApplicationContext(): Context = context
                override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean =
                    context.bindService(intent, connection, flags).also { serviceBindingIssued = it }
                override fun unbindService(connection: ServiceConnection) {
                    if (serviceBindingIssued) context.unbindService(connection)
                    serviceBindingIssued = false
                }
                override fun onBeaconServiceConnect() {
                    if (consumer !== this) return
                    clearStartTimeout()
                    connected = true
                    active?.let(::startRanging)
                }
            }
            consumer = owner
            manager.bind(owner)
            if (manager.foregroundServiceStartFailed()) session.failure(BleSupplementalScanner.FAILURE_UNAVAILABLE, "BLE_SCAN_FGS_FALLBACK")
        }
    }

    private fun startRanging(session: BleScanSession) {
        if (active !== session || stopCompletion != null) return
        blockedReason()?.let { session.failure(BleSupplementalScanner.FAILURE_UNAVAILABLE, it); return }
        if (!foregroundObserved()) {
            session.failure(BleSupplementalScanner.FAILURE_UNAVAILABLE, "BLE_SCAN_FGS_NOT_RUNNING")
            return
        }
        ranged = true
        manager.startRangingBeaconsInRegion(region)
    }

    override fun stop(session: BleScanSession, release: Boolean, complete: (Boolean) -> Unit) {
        check(active === session && stopCompletion == null)
        clearStartTimeout()
        stopCompletion = complete
        releaseAfterStop = release
        if (!ranged) { finishStop(true); return }
        // native onScanStopped hook가 실제 동일 callback stopScan 반환을 확인한다.
        val timeout = Runnable { if (active === session && stopCompletion != null) finishStop(false) }
        stopTimeout = timeout
        handler.postDelayed(timeout, STOP_ACK_TIMEOUT_MS)
        manager.stopRangingBeaconsInRegion(region)
        ranged = false
    }

    private fun finishStop(success: Boolean) {
        val callback = stopCompletion ?: return
        stopTimeout?.let(handler::removeCallbacks)
        stopTimeout = null
        if (success) {
            active = null
            nativeAttempted = false
            manager.setRawScanCallback(null)
            if (releaseAfterStop) {
                try { release() } catch (_: Exception) {
                    stopCompletion = null
                    callback(false)
                    return
                }
            }
        }
        stopCompletion = null
        callback(success)
    }

    override fun release() {
        if (!ownsManager) return
        check(active == null && !ranged)
        clearStartTimeout()
        consumer?.let { manager.unbind(it) }
        consumer = null
        connected = false
        manager.setRawScanCallback(null)
        manager.setRawScanFilters(null)
        ownsManager = false
    }

    private fun clearStartTimeout() {
        startTimeout?.let(handler::removeCallbacks)
        startTimeout = null
    }

    private fun foregroundObserved(): Boolean = foregroundRunning(context)

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }

    companion object {
        private const val STOP_ACK_TIMEOUT_MS = 10_000L
        private const val BIND_TIMEOUT_MS = 10_000L
        @Suppress("DEPRECATION")
        fun foregroundRunning(context: Context): Boolean = context.getSystemService(ActivityManager::class.java)
            ?.getRunningServices(Int.MAX_VALUE)
            ?.any { it.service.className == BeaconService::class.java.name && it.foreground } == true
    }
}
