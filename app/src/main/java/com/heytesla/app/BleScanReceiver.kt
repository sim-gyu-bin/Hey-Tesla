package com.heytesla.app

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** 비공개 명시 receiver. 고아 delivery는 정리만 하며 시험·FGS·GATT를 시작하지 않는다. */
class BleScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        shared(context).receive(intent)
    }

    companion object {
        private var registry: BlePendingScanRegistry? = null

        private fun shared(context: Context): BlePendingScanRegistry {
            check(Looper.myLooper() == Looper.getMainLooper())
            return registry ?: BlePendingScanRegistry(context.applicationContext,
                BleScanTokenFile(context.applicationContext), AndroidPendingScanIo(context.applicationContext),
            ).also { registry = it }
        }

        /** Application.onCreate의 runtime 생성 뒤 Main에서 호출. 복원은 정리만, 새 scan은 명시 start만 한다. */
        internal fun cleanupOrphanedScans(context: Context, runtime: DiagnosticRuntime): Boolean {
            val owner = transport(context, runtime)
            return try {
                owner.prepare()
                true
            } catch (_: BleScanCleanupException) {
                false
            }
        }

        internal fun transport(context: Context, runtime: DiagnosticRuntime): BlePendingScanRegistry =
            shared(context).also { owner ->
                owner.onCleanupFailure = {
                    // 기존 runtime 정리 불명 gate를 봉인한다. target/intent/예외 원문은 기록하지 않는다.
                    runtime.recordBleEvidence(BleEventEvidence(BleEvidenceKind.SCAN_FAILED,
                        gate = BleEvidenceGate.UNAVAILABLE, scanFailureCode = BleSupplementalScanner.FAILURE_STOP,
                        accepted = false, scanRunning = false))
                }
            }
    }
}

/** 영속 상태는 비민감 단일 무작위 scan token뿐이다. 앱 설정/차량 대상은 저장하지 않는다. */
internal interface BleScanTokenJournal {
    fun read(): String?
    fun write(token: String?)
}

private class BleScanTokenFile(context: Context) : BleScanTokenJournal {
    private val file = File(context.noBackupFilesDir, "ble_pending_scan_token")

    override fun read(): String? {
        val value = try {
            FileInputStream(file).use { input ->
                val bytes = ByteArray(37)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read == -1) break
                    count += read
                }
                check(count == 0 || count == 36)
                if (count == 0) null else String(bytes, 0, count, Charsets.US_ASCII)
            }
        } catch (failure: java.io.FileNotFoundException) {
            // 존재하는 marker를 읽을 수 없으면 정리 불명이다. 단순 최초 실행만 빈 journal이다.
            if (file.exists()) throw failure
            null
        }
        check(value == null || BlePendingScanRegistry.validToken(value))
        return value
    }

    override fun write(token: String?) {
        check(token == null || BlePendingScanRegistry.validToken(token))
        // 교체 중 프로세스가 죽어 부분 token이 남으면 다음 시작은 fail closed한다.
        // scan IO는 write/flush/fd.sync가 모두 반환된 뒤에만 실행된다.
        FileOutputStream(file, false).use { output ->
            if (token != null) output.write(token.toByteArray(Charsets.US_ASCII))
            output.flush()
            output.fd.sync()
        }
    }
}

/** 테스트는 이 IO만 RAM으로 교체한다. production에는 fake/fallback을 설치하지 않는다. */
internal interface BlePendingScanIo {
    fun start(filters: List<ScanFilter>, settings: ScanSettings, pendingIntent: PendingIntent): Int
    fun stop(pendingIntent: PendingIntent)
}

@SuppressLint("MissingPermission")
private class AndroidPendingScanIo(context: Context) : BlePendingScanIo {
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    private var scanner: BluetoothLeScanner? = null

    override fun start(filters: List<ScanFilter>, settings: ScanSettings, pendingIntent: PendingIntent): Int {
        val owner = bluetooth?.adapter?.bluetoothLeScanner ?: return BleSupplementalScanner.FAILURE_UNAVAILABLE
        scanner = owner
        return owner.startScan(filters, settings, pendingIntent)
    }

    override fun stop(pendingIntent: PendingIntent) {
        // 프로세스 재생성에는 저장한 handle identity로 현재 scanner에 정리를 요청한다.
        val owner = scanner ?: bluetooth?.adapter?.bluetoothLeScanner
        // 공개 SDK의 void 무예외 반환만 관측한다. Binder/라디오 해제 ACK를 받았다는 뜻은 아니다.
        checkNotNull(owner).stopScan(pendingIntent)
        scanner = null
    }
}

/** Main 직렬 소유자. extras 갱신/고정 slot 재사용 없이 window마다 PI identity를 새로 발급한다. */
internal class BlePendingScanRegistry(
    context: Context,
    private val journal: BleScanTokenJournal,
    private val io: BlePendingScanIo,
) {
    // 프로세스 수명의 registry는 Activity/Service context를 보관하지 않는다.
    private val context = context.applicationContext as Application
    private class Active(val session: BleScanSession, val pendingIntent: PendingIntent) {
        var sealed = false
    }
    private var active: Active? = null
    private var cleanupFailed = false
    internal var onCleanupFailure: () -> Unit = { Log.e("BleScanReceiver", "BLE_SCAN_ORPHAN_CLEANUP_FAILED") }

    fun prepare() {
        mainThread()
        if (cleanupFailed) throw BleScanCleanupException()
        // 별도 scanner가 기존 RAM 소유자의 scan을 고아로 취급해서 정리하지 못하게 한다.
        if (active != null) throw BleScanCleanupException()
        try {
            journal.read()?.let { cleanOrphan(it) }
        } catch (_: Exception) {
            sealCleanupFailure()
            throw BleScanCleanupException()
        }
    }

    fun start(filters: List<ScanFilter>, settings: ScanSettings, session: BleScanSession): Int {
        mainThread()
        if (cleanupFailed) throw BleScanCleanupException()
        check(active == null)
        check(filters.size == 1)
        val pendingIntent = pendingIntent(session.token)
        try {
            check(journal.read() == null)
            // start 호출보다 먼저 durable marker를 남겨 어느 crash 지점도 새 scan으로 복원하지 않는다.
            journal.write(session.token)
        } catch (_: Exception) {
            // 아직 scan IO 전이다. 이 handle로 등록된 scan은 없으므로 취소가 안전하다.
            pendingIntent.cancel()
            sealCleanupFailure()
            throw BleScanCleanupException()
        }
        active = Active(session, pendingIntent)
        return io.start(filters, settings, pendingIntent)
    }

    fun stop(session: BleScanSession) {
        mainThread()
        if (cleanupFailed) throw BleScanCleanupException()
        val old = active ?: return
        check(old.session === session)
        old.sealed = true
        try {
            // stopScan에 동일 handle을 먼저 전달한다. 예외면 cancel/marker 비움/소유자 교체를 하지 않는다.
            io.stop(old.pendingIntent)
            old.pendingIntent.cancel()
            check(journal.read() == old.session.token)
            journal.write(null)
            active = null
        } catch (_: Exception) {
            sealCleanupFailure()
            throw BleScanCleanupException()
        }
    }

    fun receive(intent: Intent) {
        mainThread()
        val token = tokenOf(intent) ?: return
        if (cleanupFailed) return
        val current = active
        if (current != null && current.session.token == token) {
            if (current.sealed) return
            // RAM 소유자 확인 이후에만 Android가 덧붙인 extras를 해석한다.
            try {
                val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
                if (error != 0) current.session.failure(error)
                else {
                    val results = intent.getParcelableArrayListExtra(
                        BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java,
                    )
                    if (results == null) current.session.malformed() else current.session.results(results)
                }
            } catch (_: Exception) {
                current.session.malformed()
            }
            return
        }
        // 불명/위조 token 및 이미 정리한 세대는 어떤 scan도 정리하지 않는다.
        try {
            if (journal.read() == token) cleanOrphan(token)
        } catch (_: Exception) {
            sealCleanupFailure()
        }
    }

    private fun cleanOrphan(token: String) {
        check(active == null)
        val handle = pendingIntent(token)
        io.stop(handle)
        handle.cancel()
        check(journal.read() == token)
        journal.write(null)
    }

    private fun pendingIntent(token: String): PendingIntent =
        PendingIntent.getBroadcast(context, REQUEST_CODE, intent(context, token), PendingIntent.FLAG_MUTABLE)

    private fun tokenOf(source: Intent): String? {
        if (source.action != ACTION || source.component != ComponentName(context, BleScanReceiver::class.java) ||
            source.`package` != context.packageName || source.data != null || source.categories != null
        ) return null
        return source.identifier?.takeIf(::validToken)
    }

    private fun sealCleanupFailure() {
        if (cleanupFailed) return
        cleanupFailed = true
        active?.sealed = true
        onCleanupFailure()
    }

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    companion object {
        const val ACTION = "com.heytesla.app.BLE_SCAN_DELIVERY"
        const val REQUEST_CODE = 0

        internal fun intent(context: Context, token: String): Intent {
            check(validToken(token))
            return Intent(context, BleScanReceiver::class.java).setPackage(context.packageName)
                .setAction(ACTION).setIdentifier(token)
        }

        internal fun validToken(token: String): Boolean = try {
            token.length == 36 && UUID.fromString(token).toString() == token
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

@SuppressLint("MissingPermission")
internal class AndroidBleScanTransport(context: Context, private val runtime: DiagnosticRuntime) : BleScanTransport {
    private val bluetooth = context.applicationContext.getSystemService(BluetoothManager::class.java)
    private val owner = BleScanReceiver.transport(context, runtime)

    override fun prepare() = owner.prepare()
    override fun associationAddress(associationId: Int): String? =
        runtime.cdm?.myAssociations?.singleOrNull { !it.isSelfManaged }
            ?.takeIf { it.id == associationId }?.deviceMacAddress?.toString()
    override fun isEnabled(): Boolean = bluetooth?.adapter?.isEnabled == true
    override fun supportsOffloadedFiltering(): Boolean = bluetooth?.adapter?.isOffloadedFilteringSupported == true
    override fun supportsOffloadedBatching(): Boolean = bluetooth?.adapter?.isOffloadedScanBatchingSupported == true
    override fun start(filters: List<ScanFilter>, settings: ScanSettings, session: BleScanSession): Int =
        owner.start(filters, settings, session)
    override fun stop(session: BleScanSession) = owner.stop(session)
}
