package com.heytesla.app

import android.app.PendingIntent
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/** Android PI identity는 실제 API, journal/라디오는 RAM IO. 실제 scan/GATT/FGS를 시작하지 않는다. */
@RunWith(AndroidJUnit4::class)
class BlePendingIntentScanDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val filters = listOf(ScanFilter.Builder().setDeviceName("RAM_ONLY_SCAN_TEST").build())
    private val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .setReportDelay(BleSupplementalScanner.REPORT_DELAY_MS).build()

    @Test fun mutableExplicitHandleStaysValidUntilStopAndRetiredIdentityCannotContaminateRearm() = onMain {
        val h = Harness()
        val old = Session()
        h.registry.prepare()
        h.registry.start(filters, settings, old.session)
        val oldHandle = h.io.started.single()
        try {
            assertFalse(oldHandle.isImmutable)
            assertTrue(oldHandle.isBroadcast)
            assertEquals(context.packageName, oldHandle.creatorPackage)
            assertEquals(oldHandle, lookup(old.session.token))
            h.registry.receive(delivery(old.session.token, ScanCallback.SCAN_FAILED_INTERNAL_ERROR))
            assertEquals(listOf(ScanCallback.SCAN_FAILED_INTERNAL_ERROR), old.errors)
            h.io.beforeStop = { handle -> assertEquals(handle, lookup(old.session.token)) }
            h.registry.stop(old.session)
            assertEquals(oldHandle, h.io.stopped.single())
            assertNull(lookup(old.session.token))
            assertNull(h.journal.token)
            h.io.beforeStop = {}
            val current = Session()
            h.registry.prepare()
            h.registry.start(filters, settings, current.session)
            try {
                assertNotEquals(oldHandle, h.io.started.last())
                h.registry.receive(delivery(old.session.token, ScanCallback.SCAN_FAILED_INTERNAL_ERROR))
                assertTrue(current.errors.isEmpty())
                assertEquals(1, h.io.stopped.size)
                assertEquals(current.session.token, h.journal.token)
                h.registry.receive(delivery(current.session.token, ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES))
                assertEquals(listOf(ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES), current.errors)
            } finally { h.registry.stop(current.session) }
        } finally {
            // radio IO 없는 테스트 handle만 정리한다. production journal에는 접근하지 않는다.
            lookup(old.session.token)?.cancel()
        }
    }

    @Test fun forgedActionPackageComponentAndExtraTokenCannotDeliverOrStopTheActiveSession() = onMain {
        val h = Harness()
        val active = Session()
        h.registry.start(filters, settings, active.session)
        try {
            val token = active.session.token
            val forged = listOf(
                delivery(token, 3).setAction("com.heytesla.app.FORGED"),
                delivery(token, 3).setPackage("com.example.foreign"),
                delivery(token, 3).setComponent(ComponentName("com.example.foreign", "ForeignReceiver")),
                delivery(token, 3).setIdentifier(UUID.randomUUID().toString()),
                delivery(token, 3).setIdentifier(null).putExtra("generation", token),
                delivery(token, 3).setIdentifier("not-a-generation"),
            )
            forged.forEach(h.registry::receive)
            assertTrue(active.errors.isEmpty())
            assertEquals(0, active.malformed)
            assertTrue(h.io.stopped.isEmpty())
            assertEquals(token, h.journal.token)
            assertEquals(h.io.started.single(), lookup(token))
            h.registry.receive(delivery(token, ScanCallback.SCAN_FAILED_INTERNAL_ERROR))
            assertEquals(listOf(ScanCallback.SCAN_FAILED_INTERNAL_ERROR), active.errors)
        } finally { h.registry.stop(active.session) }
    }

    @Test fun installedReceiverIsNotExportedToForeignUidBroadcasts() {
        // 실제 설치된 receiver의 UID 경계를 검사한다. shell/기기 상태/실라디오는 조작하지 않는다.
        @Suppress("DEPRECATION")
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, BleScanReceiver::class.java), 0)
        assertFalse("외부 UID는 명시 receiver에 broadcast할 수 없어야 한다", receiver.exported)
        assertEquals(context.applicationInfo.uid, receiver.applicationInfo.uid)
    }

    @Test fun processRecreationCleansTheSamePendingIdentityBeforeAnyNewExplicitStart() = onMain {
        val h = Harness()
        val old = Session()
        h.registry.start(filters, settings, old.session)
        val oldHandle = h.io.started.single()
        val recoveredIo = RamIo()
        val recovered = BlePendingScanRegistry(context, h.journal, recoveredIo)
        recovered.onCleanupFailure = { fail("정상 고아 정리는 실패 evidence를 발급하지 않는다") }
        recoveredIo.beforeStop = { handle ->
            assertEquals(oldHandle, handle)
            assertEquals(oldHandle, lookup(old.session.token))
        }
        recovered.prepare()
        assertEquals(listOf(oldHandle), recoveredIo.stopped)
        assertTrue(recoveredIo.started.isEmpty())
        assertTrue(old.errors.isEmpty())
        assertNull(h.journal.token)
        assertNull(lookup(old.session.token))
        recoveredIo.beforeStop = {}
        val current = Session()
        recovered.start(filters, settings, current.session)
        try {
            recovered.receive(delivery(old.session.token, ScanCallback.SCAN_FAILED_INTERNAL_ERROR))
            assertTrue(current.errors.isEmpty())
            assertEquals(current.session.token, h.journal.token)
            assertEquals(1, recoveredIo.stopped.size)
        } finally { recovered.stop(current.session) }
    }

    @Test fun orphanDeliveryOnlyCleansItsKnownRegistrationAndNeverRestoresTheSession() = onMain {
        val h = Harness()
        val old = Session()
        h.registry.start(filters, settings, old.session)
        val handle = h.io.started.single()
        val recoveredIo = RamIo()
        val recovered = BlePendingScanRegistry(context, h.journal, recoveredIo)
        recovered.receive(delivery(UUID.randomUUID().toString(), ScanCallback.SCAN_FAILED_INTERNAL_ERROR))
        assertTrue(recoveredIo.stopped.isEmpty())
        assertEquals(old.session.token, h.journal.token)
        recovered.receive(delivery(old.session.token, ScanCallback.SCAN_FAILED_INTERNAL_ERROR))
        assertEquals(listOf(handle), recoveredIo.stopped)
        assertTrue(recoveredIo.started.isEmpty())
        assertTrue(old.errors.isEmpty())
        assertNull(h.journal.token)
        assertNull(lookup(old.session.token))
        recovered.receive(delivery(old.session.token, ScanCallback.SCAN_FAILED_INTERNAL_ERROR))
        assertEquals(1, recoveredIo.stopped.size)
    }

    @Test fun orphanCleanupThrowKeepsTheHandleAndMarkerAndSealsFurtherStarts() = onMain {
        val h = Harness()
        val old = Session()
        h.registry.start(filters, settings, old.session)
        val handle = h.io.started.single()
        val recoveredIo = RamIo().apply { stopFails = true }
        val recovered = BlePendingScanRegistry(context, h.journal, recoveredIo)
        var failures = 0
        recovered.onCleanupFailure = { failures++ }
        try {
            recovered.receive(delivery(old.session.token, ScanCallback.SCAN_FAILED_INTERNAL_ERROR))
            assertEquals(1, failures)
            assertEquals(old.session.token, h.journal.token)
            assertEquals(handle, lookup(old.session.token))
            recoveredIo.stopFails = false
            expectCleanupFailure { recovered.prepare() }
            expectCleanupFailure { recovered.start(filters, settings, Session().session) }
            recovered.receive(delivery(old.session.token, ScanCallback.SCAN_FAILED_INTERNAL_ERROR))
            assertEquals(1, recoveredIo.stopped.size)
            assertTrue(recoveredIo.started.isEmpty())
            assertTrue(old.errors.isEmpty())
            assertEquals(1, failures)
        } finally { handle.cancel() }
    }

    @Test fun activeStopThrowDoesNotCancelOrReplaceItsPendingHandle() = onMain {
        val h = Harness()
        val current = Session()
        h.registry.start(filters, settings, current.session)
        val handle = h.io.started.single()
        h.io.stopFails = true
        try {
            expectCleanupFailure { h.registry.stop(current.session) }
            assertEquals(handle, lookup(current.session.token))
            assertEquals(current.session.token, h.journal.token)
            h.io.stopFails = false
            expectCleanupFailure { h.registry.stop(current.session) }
            expectCleanupFailure { h.registry.prepare() }
            expectCleanupFailure { h.registry.start(filters, settings, Session().session) }
            h.registry.receive(delivery(current.session.token, ScanCallback.SCAN_FAILED_INTERNAL_ERROR))
            assertTrue(current.errors.isEmpty())
            assertEquals(1, h.io.stopped.size)
            assertEquals(1, h.io.started.size)
            assertEquals(1, h.cleanupFailures)
        } finally { handle.cancel() }
    }

    @Test fun failedDurableTokenWriteCannotReachScanIoAndSealsTheOwner() = onMain {
        val h = Harness()
        val current = Session()
        h.journal.writeFails = true
        expectCleanupFailure { h.registry.start(filters, settings, current.session) }
        assertTrue(h.io.started.isEmpty())
        assertTrue(h.io.stopped.isEmpty())
        assertNull(lookup(current.session.token))
        assertEquals(current.session.token, h.journal.token)
        h.journal.writeFails = false
        expectCleanupFailure { h.registry.prepare() }
        expectCleanupFailure { h.registry.start(filters, settings, Session().session) }
        assertEquals(1, h.cleanupFailures)
        assertTrue(h.io.started.isEmpty())
    }

    @Test fun unreadableJournalFailsClosedWithoutStartingOrGuessingAScanIdentity() = onMain {
        val h = Harness()
        h.journal.readFails = true
        expectCleanupFailure { h.registry.prepare() }
        h.journal.readFails = false
        expectCleanupFailure { h.registry.start(filters, settings, Session().session) }
        assertTrue(h.io.started.isEmpty())
        assertTrue(h.io.stopped.isEmpty())
        assertEquals(1, h.cleanupFailures)
    }

    @Test fun markerClearThrowAfterRadioStopIsNotReportedAsSuccessfulCleanup() = onMain {
        val h = Harness()
        val current = Session()
        h.registry.start(filters, settings, current.session)
        val handle = h.io.started.single()
        h.journal.writeFails = true
        expectCleanupFailure { h.registry.stop(current.session) }
        assertEquals(listOf(handle), h.io.stopped)
        assertNull(lookup(current.session.token))
        assertEquals(1, h.cleanupFailures)
        expectCleanupFailure { h.registry.start(filters, settings, Session().session) }
        assertEquals(1, h.io.started.size)
    }

    private fun delivery(token: String, error: Int): Intent =
        BlePendingScanRegistry.intent(context, token).putExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, error)

    private fun lookup(token: String): PendingIntent? = PendingIntent.getBroadcast(context,
        BlePendingScanRegistry.REQUEST_CODE, BlePendingScanRegistry.intent(context, token),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_NO_CREATE)

    private fun expectCleanupFailure(action: () -> Unit) {
        try {
            action()
            fail("정리 실패는 성공으로 반환하면 안 된다")
        } catch (_: BleScanCleanupException) {
            // 소비자에게 전달되는 sticky 정리 실패만 허용한다.
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    private inner class Harness {
        val journal = RamJournal()
        val io = RamIo()
        var cleanupFailures = 0
        val registry = BlePendingScanRegistry(context, journal, io).also { owner ->
            owner.onCleanupFailure = { cleanupFailures++ }
        }
    }

    private class Session {
        val errors = mutableListOf<Int>()
        var malformed = 0
        val session = BleScanSession(
            onResults = { _, error -> if (error != 0) errors += error },
            onMalformed = { malformed++ },
        )
    }

    private class RamJournal : BleScanTokenJournal {
        var token: String? = null
        var readFails = false
        var writeFails = false
        override fun read(): String? {
            if (readFails) throw IOException("Synthetic journal read failure")
            return token
        }
        override fun write(token: String?) {
            this.token = token
            if (writeFails) throw IOException("Synthetic journal sync failure")
        }
    }

    private class RamIo : BlePendingScanIo {
        val started = mutableListOf<PendingIntent>()
        val stopped = mutableListOf<PendingIntent>()
        var stopFails = false
        var beforeStop: (PendingIntent) -> Unit = {}
        override fun start(filters: List<ScanFilter>, settings: ScanSettings, pendingIntent: PendingIntent): Int {
            started += pendingIntent
            return 0
        }
        override fun stop(pendingIntent: PendingIntent) {
            stopped += pendingIntent
            beforeStop(pendingIntent)
            if (stopFails) throw IllegalStateException("Synthetic stop failure")
        }
    }
}
