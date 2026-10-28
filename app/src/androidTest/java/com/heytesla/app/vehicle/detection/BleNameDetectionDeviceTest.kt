package com.heytesla.app.vehicle.detection

import com.heytesla.app.DiagnosticApp
import com.heytesla.app.vehicle.ble.BleProbeState
import com.heytesla.app.vehicle.ble.BleProbeStatus

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.service.scanner.RawScanCallback

/** RAM 광고/transport만 사용한다. 실제 scan/GATT/TX/마이크/키/차량 승인을 발급하지 않는다. */
@RunWith(AndroidJUnit4::class)
class BleNameDetectionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val runtime get() = (context.applicationContext as DiagnosticApp).runtime
    private val firstName get() = requireNotNull(TeslaBleAdvertisement.localName("00000000000000000"))
    private val secondName get() = requireNotNull(TeslaBleAdvertisement.localName("5YJ3E1EA7KF000000"))

    @Test fun serviceScanPreservesRawDeviceAndTimestampWhilePolicyRejectsStaleFutureAndDuplicateAds() = onMain {
        val h = Harness()
        try {
            h.time = 20_000
            h.start(firstName)
            val delivery = requireNotNull(h.transport.active)
            delivery.result(result("AA:BB:CC:DD:EE:02", firstName, h.time - 10_001, -50))
            delivery.result(result("AA:BB:CC:DD:EE:02", firstName, h.time + 1, -50))
            assertEquals(2, h.samples.size) // scanner는 원 수신을 보존하고 정책이 입장을 결정한다.
            assertEquals(0L, h.policy.attemptCount)
            val actual = result("AA:BB:CC:DD:EE:03", firstName, h.time, -60)
            delivery.result(actual)
            assertEquals(1L, h.policy.attemptCount)
            assertSame(actual, h.samples.last().source)
            assertSame(actual.device, h.samples.last().device)
            assertEquals(h.time, h.samples.last().received)
            delivery.result(actual)
            delivery.result(result("AA:BB:CC:DD:EE:04", firstName, h.time - 1, -50))
            delivery.result(result("AA:BB:CC:DD:EE:04", firstName, ++h.time, -55))
            assertEquals(6, h.samples.size)
            assertEquals(2L, h.policy.acceptedAdvertisementCount)
            delivery.result(result(null, firstName, h.time, -50))
            assertNull(h.samples.last().device) // CDM association 주소를 대신 끼우지 않는다.
            assertEquals(1L, h.policy.attemptCount)
            assertNoRuntimeSessions(h, 1, 1)
        } finally { h.scanner.stop() }
    }

    @Test fun servicePauseRearmRejectsOldGenerationAndPolicyRejectsOldDuplicateAcrossNativeAcknowledgement() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            val old = requireNotNull(h.transport.active)
            val first = result("AA:BB:CC:DD:EE:02", firstName, h.time, -60)
            old.result(first)
            h.transport.deferStop = true
            var paused = false
            h.scanner.pause { paused = true }
            assertFalse(paused)
            old.result(result("AA:BB:CC:DD:EE:03", firstName, ++h.time, -55))
            h.transport.finishStop(true)
            assertTrue(paused)
            h.scanner.resume()
            val current = requireNotNull(h.transport.active)
            old.result(result("AA:BB:CC:DD:EE:03", firstName, ++h.time, -55))
            current.result(first)
            assertEquals(2, h.samples.size)
            assertEquals(1L, h.policy.acceptedAdvertisementCount)
            current.result(result("AA:BB:CC:DD:EE:04", firstName, ++h.time, -65))
            assertEquals(3, h.samples.size)
            assertEquals(2L, h.policy.acceptedAdvertisementCount)
            assertEquals(1L, h.policy.attemptCount)
        } finally { h.transport.deferStop = false; h.scanner.stop() }
    }


    @Test fun nameDeliveryAcceptsRotatingAddressesAndSelectsNewestExactBatchMatch() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            val delivery = requireNotNull(h.transport.active)
            val match = result("AA:BB:CC:DD:EE:02", firstName, h.time, -55)
            val wrong = result("AA:BB:CC:DD:EE:01", secondName, h.time, -20)
            val missing = result(null, null, 999, -20)
            val filter = h.transport.filters.single()
            assertTrue(filter.matches(match))
            assertFalse(filter.matches(wrong))
            assertFalse(filter.matches(missing))
            delivery.results(listOf(match, wrong, missing))
            h.time = 1_100
            delivery.results(listOf(result("AA:BB:CC:DD:EE:03", firstName, 1_050, -60), wrong,
                result("AA:BB:CC:DD:EE:04", firstName, 1_080, -70),
                result("AA:BB:CC:DD:EE:05", firstName, 1_070, -65)))
            assertEquals(listOf(1_000L, 1_080L), h.samples.map { it.received })
            assertEquals(-70, h.samples.last().rssi)
            assertTrue(h.samples.first().first)
            assertFalse(h.samples.last().first)
            assertEquals(1L, h.policy.candidateCount)
            assertNoRuntimeSessions(h, 1, 1)
        } finally { h.scanner.stop() }
    }


    @Test fun registrationRequestIsNotRunningAndStaleStartedCannotPublishAfterPauseOrRearm() = onMain {
        val h = Harness()
        h.transport.confirmStart = false
        try {
            h.start(firstName)
            val old = requireNotNull(h.transport.active)
            assertFalse(h.scanner.running)
            assertFalse(h.evidence.any { it.kind == BleEvidenceKind.SCAN_STARTED })
            old.started()
            assertTrue(h.scanner.running)
            h.scanner.pause()
            old.started()
            assertFalse(h.scanner.running)
            h.scanner.resume()
            val current = requireNotNull(h.transport.active)
            old.started()
            assertFalse(h.scanner.running)
            current.started()
            assertTrue(h.scanner.running)
            old.failure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            assertTrue(h.failures.isEmpty())
        } finally { h.scanner.stop() }
    }

    @Test fun elapsedTimeDoesNotExpireTheSessionAndPolicyUsesOriginalAdvertisementTimestamp() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            val delivery = requireNotNull(h.transport.active)
            h.time = 600_000L
            h.scanner.reconcile()
            delivery.result(result(null, firstName, h.time - 100, -60))
            assertEquals(599_900L, h.samples.single().received)
            assertEquals(1L, h.policy.candidateCount)
            assertEquals(1, h.transport.starts)
            assertSame(delivery, h.transport.active)
            assertNoRuntimeSessions(h, 1, 1)
            h.finishAttempt()
            h.time = 609_999L
            h.policy.tick()
            assertTrue(h.policy.present)
            h.time = 610_000L
            h.policy.tick()
            assertFalse(h.policy.present)
            delivery.result(result("AA:BB:CC:DD:EE:03", firstName, h.time, -60))
            assertEquals(2L, h.policy.candidateCount)
            assertTrue(h.policy.present)
            assertEquals(1, h.transport.starts) // 후보 만료가 스캐너 재등록을 유발하지 않는다.
        } finally { h.scanner.stop() }
    }

    @Test fun staleDuplicateAndOutOfOrderAdvertisementsCannotInventANewCandidateOrExtendFromProcessingTime() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            val delivery = requireNotNull(h.transport.active)
            h.time = 300_000L
            val staleAt = h.time - BleFieldTrialPolicy.READ_START_FRESH_MS - 1
            delivery.result(result(null, firstName, staleAt, -50))
            assertEquals(staleAt, h.samples.single().received)
            assertEquals(0L, h.policy.candidateCount)
            delivery.result(result(null, firstName, 299_900, -60))
            h.finishAttempt()
            val expiry = h.policy.nextWakeAt
            h.time += 10
            delivery.result(result(null, firstName, 299_900, -55))
            delivery.result(result(null, firstName, 299_850, -65))
            assertEquals(1L, h.policy.candidateCount)
            assertEquals(expiry, h.policy.nextWakeAt)
            assertEquals(listOf(staleAt, 299_900L, 299_900L, 299_850L), h.samples.map { it.received })
            assertNoRuntimeSessions(h, 1, null)
        } finally { h.scanner.stop() }
    }

    @Test fun pauseWaitsForNativeCleanupAndResumeCannotOverlapTheOldRegistration() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            val old = requireNotNull(h.transport.active)
            h.transport.deferStop = true
            var gattMayStart = false
            h.scanner.pause { gattMayStart = true }
            assertFalse(gattMayStart)
            assertFalse(h.scanner.running)
            old.result(result(null, firstName, h.time, -60))
            assertTrue(h.samples.isEmpty())
            h.transport.finishStop(true)
            assertTrue(gattMayStart)
            assertNull(h.transport.active)
            h.scanner.resume()
            assertEquals(2, h.transport.starts)
            val current = requireNotNull(h.transport.active)
            old.result(result(null, firstName, h.time, -55))
            old.malformed()
            old.failure(3)
            assertTrue(h.samples.isEmpty())
            current.result(result(null, firstName, h.time, -65))
            assertEquals(1, h.samples.size)
            assertTrue(h.failures.isEmpty())
            assertNoRuntimeSessions(h, 1, 1)
        } finally { h.transport.deferStop = false; h.scanner.stop() }
    }

    @Test fun resumeDuringCleanupOnlyRearmsAfterAcknowledgementAndCancelsPendingGattAction() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            h.transport.deferStop = true
            var gattMayStart = false
            h.scanner.pause { gattMayStart = true }
            h.scanner.resume()
            assertEquals(1, h.transport.starts)
            h.transport.finishStop(true)
            assertFalse(gattMayStart)
            assertEquals(2, h.transport.starts)
            assertTrue(h.scanner.running)
        } finally { h.transport.deferStop = false; h.scanner.stop() }
    }

    @Test fun failedPauseSealsGattAndFutureScanOwnershipWithoutRetiringTheFailedHandle() = onMain {
        val h = Harness()
        h.start(firstName)
        val old = requireNotNull(h.transport.active)
        h.transport.deferStop = true
        var gattMayStart = false
        h.scanner.pause { gattMayStart = true }
        h.transport.finishStop(false)
        assertFalse(gattMayStart)
        assertSame(old, h.transport.active)
        assertEquals("BLE_SCAN_STOP_FAILED" to BleSupplementalScanner.FAILURE_STOP, h.failures.single())
        h.scanner.resume()
        h.scanner.stop()
        h.start(secondName)
        old.started()
        old.result(result(null, firstName, h.time, -50))
        assertEquals(1, h.transport.starts)
        assertFalse(h.scanner.running)
        assertTrue(h.samples.isEmpty())
        assertNoRuntimeSessions(h)
    }

    @Test fun stopWaitsForCleanupAndLateDeliveriesCannotContaminateANewUserTarget() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            val old = requireNotNull(h.transport.active)
            h.transport.deferStop = true
            var stopped: Boolean? = null
            h.scanner.stop { stopped = it }
            assertNull(stopped)
            old.result(result(null, firstName, 900, -60))
            h.transport.finishStop(true)
            assertEquals(true, stopped)
            h.start(secondName)
            old.result(result(null, secondName, 950, -50))
            requireNotNull(h.transport.active).result(result(null, secondName, 960, -55))
            assertEquals(960L, h.samples.single().received)
            assertTrue(h.samples.single().first)
        } finally { h.transport.deferStop = false; h.scanner.stop() }
    }

    @Test fun permissionRevocationAndFgsLimitFailWithoutRetryOrFalseRunning() = onMain {
        val h = Harness()
        try {
            h.permission = false
            h.start(firstName)
            assertEquals(BleSupplementalScanner.FAILURE_PERMISSION, h.failures.last().second)
            assertEquals(0, h.transport.starts)
            h.permission = true
            h.transport.blocked = "BLE_SCAN_FGS_FALLBACK"
            h.start(firstName)
            assertEquals("BLE_SCAN_FGS_FALLBACK", h.failures.last().first)
            assertFalse(h.scanner.running)
            assertEquals(0, h.transport.starts)
            h.transport.blocked = null
            h.start(firstName)
            val old = requireNotNull(h.transport.active)
            h.permission = false
            old.result(result(null, firstName, 900, -60))
            assertEquals(BleSupplementalScanner.FAILURE_PERMISSION, h.failures.last().second)
            assertFalse(h.scanner.running)
            assertTrue(h.samples.isEmpty())
            h.permission = true
            h.scanner.reconcile()
            assertEquals(1, h.transport.starts)
            assertNoRuntimeSessions(h)
        } finally { h.scanner.stop() }
    }

    @Test fun startEvidenceCannotResurrectASynchronouslyStoppedScanner() = onMain {
        val h = Harness()
        h.evidenceAction = { if (it.kind == BleEvidenceKind.SCAN_STARTED) h.scanner.stop() }
        h.start(firstName)
        assertFalse(h.scanner.running)
        assertNull(h.transport.active)
        assertFalse(h.runningChanges.any { it })
        assertNoRuntimeSessions(h)
    }

    @Test fun queuedWorkerDeliveryCannotSurviveStopAndRearm() {
        lateinit var h: Harness
        onMain {
            h = Harness()
            h.start(firstName)
            val old = requireNotNull(h.transport.active)
            val sample = result(null, firstName, h.time, -60)
            val worker = Thread { old.result(sample) }
            worker.start()
            worker.join()
            h.scanner.stop()
            h.start(secondName)
        }
        instrumentation.waitForIdleSync()
        onMain {
            try {
                assertTrue(h.samples.isEmpty())
                requireNotNull(h.transport.active).result(result(null, secondName, h.time, -55))
                assertEquals(1, h.samples.size)
                assertNoRuntimeSessions(h, 1, 1)
            } finally { h.scanner.stop() }
        }
    }

    @Test fun rejectedSecondOwnerCannotEraseTheExistingAltBeaconCallbackOrFilters() = onMain {
        val manager = BeaconManager.getInstanceForApplication(context)
        assertNull(manager.rawScanCallback)
        assertFalse(manager.isAnyConsumerBound)
        val owner = object : RawScanCallback {
            override fun onScanResult(result: ScanResult) = Unit
            override fun onBatchScanResults(results: List<ScanResult>) = Unit
            override fun onScanStarted() = Unit
            override fun onScanFailed(errorCode: Int) = Unit
            override fun onScanStopped(success: Boolean) = Unit
        }
        val filter = ScanFilter.Builder().setDeviceName(firstName).build()
        manager.setRawScanCallback(owner)
        manager.setRawScanFilters(listOf(filter))
        val failures = mutableListOf<Int>()
        val scanner = BleSupplementalScanner(context, runtime,
            onMatch = { _, _ -> fail("다른 소유자의 광고를 소비하면 안 된다") },
            onRunning = { assertFalse(it) },
            onFailure = { _, code -> failures += code })
        try {
            scanner.start(firstName)
            assertEquals(listOf(BleSupplementalScanner.FAILURE_START), failures)
            scanner.stop()
            assertSame(owner, manager.rawScanCallback)
            assertEquals(listOf(filter), manager.rawScanFilters)
        } finally {
            manager.setRawScanCallback(null)
            manager.setRawScanFilters(null)
        }
    }

    @Test fun terminalStopDuringPendingPauseCannotReportSuccessWhenServiceReleaseFails() = onMain {
        val h = Harness()
        h.start(firstName)
        h.transport.deferStop = true
        var gattMayStart = false
        h.scanner.pause { gattMayStart = true }
        var stopped: Boolean? = null
        h.scanner.stop { stopped = it }
        h.transport.releaseFails = true
        h.transport.finishStop(true)
        assertEquals(false, stopped)
        assertFalse(gattMayStart)
        assertEquals(BleSupplementalScanner.FAILURE_STOP, h.failures.single().second)
        h.start(secondName)
        assertEquals(1, h.transport.starts)
        assertFalse(h.scanner.running)
    }


    @Test fun scannerRejectsMissingTargetBeforeNativeRegistration() = onMain {
        val missing = Harness()
        missing.start(null)
        assertEquals(BleSupplementalScanner.FAILURE_NAME, missing.failures.single().second)
        assertEquals(0, missing.transport.starts)
        assertNoRuntimeSessions(missing)
    }


    private fun assertNoRuntimeSessions(h: Harness, attempts: Long = 0, currentAttempt: Long? = null) {
        assertEquals(attempts, h.policy.attemptCount)
        assertEquals(currentAttempt, h.policy.currentAttempt)
        assertEquals(currentAttempt != null, h.policy.localGattActive)
        assertEquals(attempts.toInt(), h.decisions.count { it.startAttempt != null })
        assertTrue(h.decisions.all { it.cancelAttempt == null })
        assertNull(runtime.policy.current)
        assertNull(runtime.microphone)
        assertFalse(runtime.uwbSupportActive)
        assertFalse(runtime.teslaKeyReserved())
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    private inner class Harness {
        var time = 1_000L
        var permission = true
        val transport = RamTransport()
        val evidence = mutableListOf<BleEventEvidence>()
        var evidenceAction: (BleEventEvidence) -> Unit = {}
        val runningChanges = mutableListOf<Boolean>()
        val samples = mutableListOf<Sample>()
        val failures = mutableListOf<Pair<String, Int>>()
        val decisions = mutableListOf<BleFieldTrialPolicy.Decision>()
        val policy = BleFieldTrialPolicy({ time })
        val scanner = BleSupplementalScanner(context, runtime,
            onMatch = { result, first ->
                val received = result.timestampNanos / 1_000_000L
                samples += Sample(received, result.rssi, first, result.device, result)
                decisions += policy.signal(BleEvidenceSignal.FILTERED_SCAN, received)
            },
            onRunning = { runningChanges += it; policy.scanAvailabilityChanged(it) },
            onFailure = { reason, code -> failures += reason to code },
            transport = transport,
            scanPermission = { permission },
            evidence = { evidence += it; evidenceAction(it) },
        )
        fun start(name: String?) = scanner.start(name)
        fun finishAttempt() {
            policy.currentAttempt?.let { attempt ->
                policy.finished(attempt, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
            }
        }
    }

    private class Sample(val received: Long, val rssi: Int, val first: Boolean,
        val device: BluetoothDevice?, val source: ScanResult)

    private class RamTransport : BleScanTransport {
        var active: BleScanSession? = null
        var filters: List<ScanFilter> = emptyList()
        var prepares = 0
        var starts = 0
        var confirmStart = true
        var deferStop = false
        var blocked: String? = null
        var releaseFails = false
        private var completion: ((Boolean) -> Unit)? = null
        override fun prepare() { prepares++ }
        override fun blockedReason(): String? = blocked
        override fun start(filters: List<ScanFilter>, session: BleScanSession) {
            check(active == null)
            this.filters = filters
            active = session
            starts++
            if (confirmStart) session.started()
        }
        override fun stop(session: BleScanSession, release: Boolean, complete: (Boolean) -> Unit) {
            check(active === session && completion == null)
            completion = complete
            if (!deferStop) finishStop(true)
        }
        fun finishStop(success: Boolean) {
            val callback = requireNotNull(completion)
            completion = null
            if (success) active = null
            callback(success)
        }
        override fun release() {
            check(active == null)
            if (releaseFails) throw IllegalStateException("RAM service 해제 실패")
        }
    }

    @Suppress("DEPRECATION")
    private fun result(address: String?, name: String?, at: Long, rssi: Int): ScanResult {
        val device = address?.let {
            requireNotNull(context.getSystemService(BluetoothManager::class.java).adapter).getRemoteDevice(it)
        }
        val template = ScanResult(device, null, rssi, at * 1_000_000L)
        val payload = name?.toByteArray(Charsets.US_ASCII)
        val record = if (payload == null) byteArrayOf(2, 1, 6, 0)
            else byteArrayOf((payload.size + 1).toByte(), 9) + payload + byteArrayOf(0)
        val source = Parcel.obtain()
        val synthetic = Parcel.obtain()
        try {
            template.writeToParcel(source, 0)
            source.setDataPosition(0)
            if (source.readInt() == 1) BluetoothDevice.CREATOR.createFromParcel(source)
            val recordAt = source.dataPosition()
            check(source.readInt() == 0)
            val tailAt = source.dataPosition()
            synthetic.appendFrom(source, 0, recordAt)
            synthetic.writeInt(1)
            synthetic.writeByteArray(record)
            synthetic.appendFrom(source, tailAt, source.dataSize() - tailAt)
            synthetic.setDataPosition(0)
            return ScanResult.CREATOR.createFromParcel(synthetic)
        } finally {
            payload?.fill(0)
            record.fill(0)
            source.recycle()
            synthetic.recycle()
        }
    }
}
