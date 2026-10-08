package com.heytesla.app

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** RAM 광고/transport만 사용한다. 실라디오 스캔, GATT/TX, 마이크, UWB, 실차 승인은 발급하지 않는다. */
@RunWith(AndroidJUnit4::class)
class BleNameDetectionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val runtime get() = (context.applicationContext as DiagnosticApp).runtime
    private val firstName get() = requireNotNull(TeslaBleAdvertisement.localName("00000000000000000"))
    private val secondName get() = requireNotNull(TeslaBleAdvertisement.localName("5YJ3E1EA7KF000000"))

    @Test fun nameDeliveryAcceptsDifferentAddressesAndOnlyTheNewestExactMatchingBatchSample() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            val delivery = requireNotNull(h.transport.active)
            val filter = h.transport.filters.single()
            val match = result("AA:BB:CC:DD:EE:02", firstName, 900, -55)
            val wrong = result("AA:BB:CC:DD:EE:01", secondName, 999, -20)
            val missing = result("AA:BB:CC:DD:EE:01", null, 999, -20)
            val noRecord = result("AA:BB:CC:DD:EE:01", null, 999, -20, includeRecord = false)
            assertTrue(filter.matches(match))
            assertFalse(filter.matches(wrong))
            assertFalse(filter.matches(missing))
            assertFalse(filter.matches(noRecord))
            delivery.results(listOf(match, wrong, missing, noRecord))
            assertEquals(1, h.samples.size)
            assertTrue(h.samples.single().first)
            delivery.results(listOf(
                result("AA:BB:CC:DD:EE:03", firstName, 950, -60), wrong,
                result("AA:BB:CC:DD:EE:04", firstName, 980, -70), missing,
                result("AA:BB:CC:DD:EE:05", firstName, 970, -65),
            ))
            assertEquals(2, h.samples.size)
            assertEquals(980L, h.samples.last().received)
            assertEquals(-70, h.samples.last().rssi)
            assertFalse(h.samples.last().first)
            assertEquals(1L, h.policy.candidateCount)
            assertNoSessions(h)
        } finally { h.scanner.stop() }
    }

    @Test fun addressModeStillRejectsOtherAddressesEvenWhenTheAdvertisedNameMatches() = onMain {
        val h = Harness(BleFieldConfig.IMPROVED.copy(observationOnly = true))
        try {
            h.start(null)
            val delivery = requireNotNull(h.transport.active)
            val filter = h.transport.filters.single()
            val selected = result("AA:BB:CC:DD:EE:01", null, 900, -60)
            val other = result("AA:BB:CC:DD:EE:02", firstName, 950, -50)
            assertTrue(filter.matches(selected))
            assertFalse(filter.matches(other))
            delivery.results(listOf(other))
            assertTrue(h.samples.isEmpty())
            delivery.results(listOf(selected, other))
            assertEquals(900L, h.samples.single().received)
            assertNoSessions(h)
        } finally { h.scanner.stop() }
    }

    @Test fun permissionDenialAndRevocationSealDeliveriesAndAllowOnlyAnExplicitNewTarget() = onMain {
        val h = Harness()
        try {
            h.permission = false
            h.start(firstName)
            assertEquals(BleSupplementalScanner.FAILURE_PERMISSION, h.failures.single().second)
            assertEquals(BleEvidenceGate.PERMISSION, h.evidence.single().gate)
            assertEquals(0, h.transport.starts)
            h.permission = true
            h.start(firstName)
            val old = requireNotNull(h.transport.active)
            h.permission = false
            old.results(listOf(result(null, firstName, 900, -60)))
            assertFalse(h.scanner.running)
            assertNull(h.transport.active)
            assertTrue(h.samples.isEmpty())
            assertEquals(2, h.failures.size)
            h.permission = true
            h.start(secondName)
            val current = requireNotNull(h.transport.active)
            old.results(listOf(result(null, firstName, 950, -50)))
            old.failure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            current.results(listOf(result(null, firstName, 950, -50)))
            assertTrue(h.samples.isEmpty())
            current.results(listOf(result(null, secondName, 960, -55)))
            assertTrue(h.samples.single().first)
            assertEquals(2, h.failures.size)
            assertNoSessions(h)
        } finally { h.scanner.stop() }
    }

    @Test fun pauseAndRearmKeepThePeriodButDiscardOldGenerationsAndClosedWindows() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            val old = requireNotNull(h.transport.active)
            h.scanner.pause()
            old.results(listOf(result(null, firstName, h.time, -60)))
            old.failure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            h.scanner.resume()
            assertFalse(h.scanner.running)
            assertEquals(1, h.transport.starts)
            h.scanner.pause()
            h.time += BleSupplementalScanner.PERIOD_MS
            h.scanner.resume()
            val current = requireNotNull(h.transport.active)
            old.results(listOf(result(null, firstName, h.time, -50)))
            old.failure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            assertTrue(h.samples.isEmpty())
            current.results(listOf(result(null, firstName, h.time, -60)))
            assertEquals(1, h.samples.size)
            h.time += BleSupplementalScanner.WINDOW_MS
            current.results(listOf(result(null, firstName, h.time, -55)))
            current.malformed()
            current.failure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            assertEquals(1, h.samples.size)
            assertTrue(h.failures.isEmpty())
            h.scanner.stop()
            h.start(secondName)
            val rearmed = requireNotNull(h.transport.active)
            current.results(listOf(result(null, secondName, h.time, -55)))
            current.failure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            rearmed.results(listOf(result(null, firstName, h.time, -55)))
            assertEquals(1, h.samples.size)
            rearmed.results(listOf(result(null, secondName, h.time, -65)))
            assertEquals(2, h.samples.size)
            assertTrue(h.samples.last().first)
            assertTrue(h.failures.isEmpty())
            assertNoSessions(h)
        } finally { h.scanner.stop() }
    }

    @Test fun periodicWindowsEmitPairedEvidenceAndNeverScanDuringThePeriodGap() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            val old = requireNotNull(h.transport.active)
            h.fireTimerAfter(BleSupplementalScanner.WINDOW_MS)
            assertNull(h.transport.active)
            assertFalse(h.scanner.running)
            assertEquals(1, h.transport.starts)
            old.results(listOf(result(null, firstName, h.time, -40)))
            assertTrue(h.samples.isEmpty())
            h.fireTimerAfter(BleSupplementalScanner.PERIOD_MS - BleSupplementalScanner.WINDOW_MS)
            assertTrue(h.scanner.running)
            assertEquals(2, h.transport.starts)
            h.scanner.stop()
            assertEquals(listOf(BleEvidenceKind.SCAN_STARTED, BleEvidenceKind.SCAN_STOPPED,
                BleEvidenceKind.SCAN_STARTED, BleEvidenceKind.SCAN_STOPPED), h.evidence.map { it.kind })
            assertTrue(h.transport.stopIdentities.all { it in h.transport.startIdentities })
        } finally { h.scanner.stop() }
    }

    @Test fun expiredDeliveryStopsOnlyItsCurrentRegistrationAndLeavesRestartToThePeriodTimer() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            val old = requireNotNull(h.transport.active)
            h.time += BleSupplementalScanner.PERIOD_MS * 2
            old.results(listOf(result(null, firstName, h.time - 1, -40)))
            old.failure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            old.malformed()
            assertTrue(h.samples.isEmpty())
            assertTrue(h.failures.isEmpty())
            assertEquals(1, h.transport.starts)
            assertNull(h.transport.active)
            assertFalse(h.scanner.running)
            assertSame(old, h.transport.stopIdentities.single())
            h.fireTimerAfter(0)
            val current = requireNotNull(h.transport.active)
            assertEquals(2, h.transport.starts)
            old.results(listOf(result(null, firstName, h.time, -30)))
            old.failure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            assertSame(current, h.transport.active)
            assertEquals(1, h.transport.stopIdentities.size)
        } finally { h.scanner.stop() }
    }

    @Test fun expiredDeliveryCleanupThrowRetainsTheHandleAndSealsPeriodRestart() = onMain {
        val h = Harness()
        h.start(firstName)
        val old = requireNotNull(h.transport.active)
        h.transport.stopFails = true
        h.time += BleSupplementalScanner.WINDOW_MS
        old.results(listOf(result(null, firstName, h.time - 1, -40)))
        assertTrue(h.samples.isEmpty())
        assertSame(old, h.transport.active)
        assertEquals("BLE_SCAN_STOP_FAILED" to BleSupplementalScanner.FAILURE_STOP, h.failures.single())
        assertNull(h.timer)
        h.transport.stopFails = false
        h.scanner.stop()
        h.start(secondName)
        assertEquals(1, h.transport.starts)
        assertNoSessions(h)
    }

    @Test fun startEvidenceCannotResurrectASynchronouslyStoppedScanner() = onMain {
        val h = Harness()
        h.evidenceAction = { event -> if (event.kind == BleEvidenceKind.SCAN_STARTED) h.scanner.stop() }
        h.start(firstName)
        assertFalse(h.scanner.running)
        assertNull(h.transport.active)
        assertEquals(listOf(false), h.runningChanges)
        assertNull(h.timer)
        assertEquals(listOf(BleEvidenceKind.SCAN_STARTED, BleEvidenceKind.SCAN_STOPPED), h.evidence.map { it.kind })
        assertNoSessions(h)
    }

    @Test fun startEvidenceRearmCannotPublishTheOldWindowAsRunning() = onMain {
        val h = Harness()
        var rearmed = false
        h.evidenceAction = { event ->
            if (event.kind == BleEvidenceKind.SCAN_STARTED && !rearmed) {
                rearmed = true
                h.scanner.stop()
                h.start(secondName)
            }
        }
        try {
            h.start(firstName)
            assertEquals(listOf(false, true), h.runningChanges)
            assertEquals(2, h.transport.starts)
            requireNotNull(h.transport.active).results(listOf(result(null, firstName, h.time, -60)))
            assertTrue(h.samples.isEmpty())
            requireNotNull(h.transport.active).results(listOf(result(null, secondName, h.time, -55)))
            assertTrue(h.samples.single().first)
            assertNoSessions(h)
        } finally { h.scanner.stop() }
    }

    @Test fun malformedDeliveryStopsTheWindowAndCannotFailAnExplicitlyRearmedSession() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            val old = requireNotNull(h.transport.active)
            old.malformed()
            assertEquals("BLE_SCAN_DELIVERY_FAILED" to BleSupplementalScanner.FAILURE_DELIVERY, h.failures.single())
            assertNull(h.transport.active)
            assertNull(h.timer)
            h.start(secondName)
            old.malformed()
            assertEquals(1, h.failures.size)
            requireNotNull(h.transport.active).results(listOf(result(null, secondName, h.time, -55)))
            assertTrue(h.samples.single().first)
            assertNoSessions(h)
        } finally { h.scanner.stop() }
    }

    @Test fun deliveryAlreadyQueuedOnMainCannotSurviveStopOrRearm() {
        lateinit var h: Harness
        onMain {
            h = Harness()
            try {
                h.start(firstName)
                val old = requireNotNull(h.transport.active)
                val sample = result(null, firstName, h.time, -60)
                val worker = Thread { old.results(listOf(sample)) }
                worker.start()
                worker.join()
                h.scanner.stop()
                h.start(secondName)
            } catch (failure: Throwable) {
                h.scanner.stop()
                throw failure
            }
        }
        instrumentation.waitForIdleSync()
        onMain {
            try {
                assertTrue(h.samples.isEmpty())
                requireNotNull(h.transport.active).results(listOf(result(null, secondName, h.time, -55)))
                assertEquals(1, h.samples.size)
                assertNoSessions(h)
            } finally { h.scanner.stop() }
        }
    }

    @Test fun scannerRejectsUnsafeNameOptionsAndMissingTargetWithoutTransportIo() = onMain {
        val unsafe = Harness(BleFieldConfig.VEHICLE_NAME_DETECTION.copy(observationOnly = false))
        try {
            unsafe.start(firstName)
            assertEquals("BLE_NAME_DETECTION_OPTIONS_INVALID", unsafe.failures.single().first)
            assertEquals(0, unsafe.transport.prepares)
            assertEquals(0, unsafe.transport.starts)
            assertFalse(unsafe.scanner.running)
            assertNoSessions(unsafe)
        } finally { unsafe.scanner.stop() }
        val missing = Harness()
        try {
            missing.start(null)
            assertEquals(BleSupplementalScanner.FAILURE_NAME, missing.failures.single().second)
            assertEquals(0, missing.transport.starts)
            assertNoSessions(missing)
        } finally { missing.scanner.stop() }
    }

    @Test fun unsupportedFilteringAndBatchingNeverFallBackToAnotherScan() = onMain {
        val h = Harness()
        try {
            h.transport.offloaded = false
            h.start(firstName)
            assertEquals(BleSupplementalScanner.FAILURE_OFFLOADED_FILTER, h.failures.last().second)
            assertEquals(BleEvidenceGate.NO_OFFLOADED_FILTER, h.evidence.last().gate)
            h.transport.offloaded = true
            h.transport.batching = false
            h.start(firstName)
            assertEquals(BleSupplementalScanner.FAILURE_OFFLOADED_BATCH, h.failures.last().second)
            assertEquals(0, h.transport.starts)
            assertFalse(h.evidence.any { it.kind == BleEvidenceKind.SCAN_STARTED })
            h.transport.batching = true
            h.transport.available = false
            h.start(firstName)
            assertEquals(BleSupplementalScanner.FAILURE_UNAVAILABLE, h.failures.last().second)
            assertEquals(0, h.transport.starts)
            assertNoSessions(h)
        } finally { h.scanner.stop() }
    }

    @Test fun synchronousStartReturnAndThrowFailuresRetireTheSameSessionBeforeExplicitRestart() = onMain {
        val h = Harness()
        try {
            h.transport.startError = ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
            h.start(firstName)
            val returned = requireNotNull(h.transport.lastSession)
            assertEquals(h.transport.startError, h.failures.last().second)
            assertFalse(h.scanner.running)
            assertNull(h.transport.active)
            assertSame(returned, h.transport.stopIdentities.last())
            assertFalse(h.evidence.any { it.kind == BleEvidenceKind.SCAN_STARTED })
            h.transport.startError = 0
            h.transport.startFails = true
            h.start(firstName)
            val thrown = requireNotNull(h.transport.lastSession)
            assertEquals(BleSupplementalScanner.FAILURE_START, h.failures.last().second)
            assertNull(h.transport.active)
            h.transport.startFails = false
            h.start(secondName)
            returned.results(listOf(result(null, firstName, h.time, -60)))
            returned.failure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            thrown.results(listOf(result(null, firstName, h.time, -60)))
            thrown.failure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            assertTrue(h.samples.isEmpty())
            requireNotNull(h.transport.active).results(listOf(result(null, secondName, h.time, -60)))
            assertEquals(1, h.samples.size)
            assertEquals(2, h.failures.size)
            assertNoSessions(h)
        } finally { h.scanner.stop() }
    }

    @Test fun asynchronousFailureAllowsRestartButCleanupThrowKeepsTheHandleAndSealsScanning() = onMain {
        val h = Harness()
        try {
            h.start(firstName)
            val failed = requireNotNull(h.transport.active)
            failed.failure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            assertEquals(ScanCallback.SCAN_FAILED_INTERNAL_ERROR, h.failures.last().second)
            assertFalse(h.scanner.running)
            h.start(secondName)
            val stopped = requireNotNull(h.transport.active)
            h.transport.stopFails = true
            h.scanner.stop()
            assertEquals(BleSupplementalScanner.FAILURE_STOP, h.failures.last().second)
            assertTrue(h.evidence.any {
                it.kind == BleEvidenceKind.SCAN_STOPPED && it.accepted == false && it.gate == BleEvidenceGate.UNAVAILABLE
            })
            assertFalse(h.scanner.running)
            assertSame(stopped, h.transport.active)
            h.transport.stopFails = false
            val starts = h.transport.starts
            val stops = h.transport.stopIdentities.size
            h.start(firstName)
            h.scanner.pause()
            h.scanner.resume()
            h.scanner.stop()
            assertEquals(starts, h.transport.starts)
            assertEquals(stops, h.transport.stopIdentities.size)
            stopped.results(listOf(result(null, secondName, h.time, -50)))
            stopped.failure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            assertTrue(h.samples.isEmpty())
            assertEquals(2, h.failures.size)
            assertNoSessions(h)
        } finally { h.scanner.stop() }
    }

    @Test fun cleanupThrowDuringFailedStartCannotBeHiddenByTheOriginalStartError() = onMain {
        val h = Harness()
        h.transport.startError = ScanCallback.SCAN_FAILED_INTERNAL_ERROR
        h.transport.stopFails = true
        h.start(firstName)
        assertEquals("BLE_SCAN_STOP_FAILED" to BleSupplementalScanner.FAILURE_STOP, h.failures.single())
        assertSame(h.transport.lastSession, h.transport.active)
        h.transport.stopFails = false
        val starts = h.transport.starts
        h.scanner.stop()
        h.start(secondName)
        assertEquals(starts, h.transport.starts)
        assertNoSessions(h)
    }

    @Test fun orphanCleanupFailureSealsTheScannerBeforeAnyRegistration() = onMain {
        val h = Harness()
        h.transport.prepareFails = true
        h.start(firstName)
        assertEquals("BLE_SCAN_ORPHAN_CLEANUP_FAILED" to BleSupplementalScanner.FAILURE_STOP, h.failures.single())
        h.transport.prepareFails = false
        h.scanner.stop()
        h.start(secondName)
        assertEquals(1, h.transport.prepares)
        assertEquals(0, h.transport.starts)
        assertNoSessions(h)
    }

    @Test fun runtimeRejectsUnsafeNameModeBeforeAnyFieldOrAudioLeaseCanBeCreated() = onMain {
        assertFalse(runtime.state.value.bleFieldTrialActive)
        assertFalse(runtime.state.value.bleFieldTrialStarting)
        assertNull(runtime.policy.current)
        val detection = BleFieldConfig.VEHICLE_NAME_DETECTION
        for (unsafe in listOf(detection.copy(observationOnly = false), detection.copy(supplementalScan = false),
            detection.copy(btAssist = true), detection.copy(backgroundConnect = true), detection.copy(retryEnabled = true))) {
            runtime.startBleFieldTrial(unsafe)
            assertEquals("BLE_NAME_DETECTION_OPTIONS_INVALID", runtime.state.value.bleFieldTrialStopReason)
            assertFalse(runtime.state.value.bleFieldTrialStarting)
            assertFalse(runtime.state.value.bleFieldTrialActive)
            assertFalse(runtime.state.value.bleDiagnosticActive)
            assertFalse(runtime.state.value.automaticMicrophoneEnabled)
            assertNull(runtime.policy.current)
            assertNull(runtime.microphone)
            assertFalse(runtime.uwbSupportActive)
            assertFalse(runtime.teslaKeyReserved())
        }
    }

    private fun assertNoSessions(h: Harness) {
        assertEquals(0L, h.policy.attemptCount)
        assertNull(h.policy.currentAttempt)
        assertFalse(h.policy.localGattActive)
        assertTrue(h.decisions.all { it.startAttempt == null && it.cancelAttempt == null })
        assertNull(runtime.policy.current)
        assertNull(runtime.microphone)
        assertFalse(runtime.state.value.bleDiagnosticActive)
        assertFalse(runtime.uwbSupportActive)
        assertFalse(runtime.teslaKeyReserved())
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    private inner class Harness(val config: BleFieldConfig = BleFieldConfig.VEHICLE_NAME_DETECTION) {
        var time = 1_000L
        var permission = true
        var timer: Runnable? = null
        val transport = RamTransport()
        val evidence = mutableListOf<BleEventEvidence>()
        var evidenceAction: (BleEventEvidence) -> Unit = {}
        val runningChanges = mutableListOf<Boolean>()
        val samples = mutableListOf<Sample>()
        val failures = mutableListOf<Pair<String, Int>>()
        val decisions = mutableListOf<BleFieldTrialPolicy.Decision>()
        val policy = BleFieldTrialPolicy(config, { time })
        val scanner = BleSupplementalScanner(context, runtime,
            onMatch = { received, rssi, first ->
                samples += Sample(received, rssi, first)
                decisions += policy.signal(BleEvidenceSignal.FILTERED_SCAN, received)
            },
            onRunning = { runningChanges += it },
            onFailure = { reason, code -> failures += reason to code },
            transport = transport,
            now = { time },
            scanPermission = { permission },
            evidence = { evidence += it; evidenceAction(it) },
            postDelayed = { task, _ -> timer = task },
            removeCallbacks = { task -> if (timer === task) timer = null },
        )
        fun start(name: String?) = scanner.start(1, config, name)
        fun fireTimerAfter(delta: Long) {
            time += delta
            val task = requireNotNull(timer)
            timer = null
            task.run()
        }
    }

    private class Sample(val received: Long, val rssi: Int, val first: Boolean)

    /** 단지 계측의 IO 경계. 앱에는 fake scanner/fallback을 설치하지 않는다. */
    private class RamTransport : BleScanTransport {
        var active: BleScanSession? = null
        var lastSession: BleScanSession? = null
        var filters: List<ScanFilter> = emptyList()
        val startIdentities = mutableListOf<BleScanSession>()
        val stopIdentities = mutableListOf<BleScanSession>()
        var prepares = 0
        var starts = 0
        var available = true
        var offloaded = true
        var batching = true
        var prepareFails = false
        var startFails = false
        var startError = 0
        var stopFails = false
        override fun prepare() {
            prepares++
            if (prepareFails) throw BleScanCleanupException()
        }
        override fun associationAddress(associationId: Int): String? =
            if (associationId == 1) "AA:BB:CC:DD:EE:01" else null
        override fun isEnabled(): Boolean = available
        override fun supportsOffloadedFiltering(): Boolean = offloaded
        override fun supportsOffloadedBatching(): Boolean = batching
        override fun start(filters: List<ScanFilter>, settings: ScanSettings, session: BleScanSession): Int {
            check(filters.size == 1)
            check(settings.scanMode == ScanSettings.SCAN_MODE_LOW_POWER)
            check(settings.reportDelayMillis == BleSupplementalScanner.REPORT_DELAY_MS)
            lastSession = session
            this.filters = filters
            active = session
            startIdentities += session
            starts++
            if (startFails) throw IllegalStateException("Synthetic start failure")
            return startError
        }
        override fun stop(session: BleScanSession) {
            check(active === session)
            stopIdentities += session
            if (stopFails) throw IllegalStateException("Synthetic stop failure")
            active = null
            filters = emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun result(address: String?, name: String?, at: Long, rssi: Int, includeRecord: Boolean = true): ScanResult {
        // getRemoteDevice는 RAM 주소 객체 생성뿐이며 connect/scan을 호출하지 않는다.
        val device = address?.let {
            requireNotNull(context.getSystemService(BluetoothManager::class.java).adapter).getRemoteDevice(it)
        }
        val template = ScanResult(device, null, rssi, at * 1_000_000L)
        if (!includeRecord) return template
        val payload = name?.toByteArray(Charsets.US_ASCII)
        val record = if (payload == null) byteArrayOf(2, 1, 6, 0)
            else byteArrayOf((payload.size + 1).toByte(), 9) + payload + byteArrayOf(0)
        val source = Parcel.obtain()
        val synthetic = Parcel.obtain()
        try {
            // public Parcelable 소비 경계로 광고 AD type 0x09를 파싱한다. hidden API reflection은 없다.
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
