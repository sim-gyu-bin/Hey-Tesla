package com.heytesla.app.vehicle.detection

import com.heytesla.app.vehicle.ble.BleProbeState
import com.heytesla.app.vehicle.ble.BleProbeStatus

import org.junit.Assert.*
import org.junit.Test

class BleFieldTrialPolicyTest {
    @Test fun delayedAdvertisementBeforeDepartureCutoffCannotCreateAnotherServiceSession() {
        for (timerRan in listOf(false, true)) {
            var time = 1_000L
            val p = BleFieldTrialPolicy({ time })
            p.scanAvailabilityChanged(true)
            val attempt = checkNotNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
            p.finished(attempt, BleProbeState(status = BleProbeStatus.BLOCKED,
                reason = "RANGING_PERMISSION_REQUIRED", localClosed = true), true)
            time = 12_000
            if (timerRan) p.tick()
            // 10초 기한보다 앞선 실제 수신값: 전달이 늦었을 뿐 차량 이탈·재접근이 아니다.
            assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, 10_500).startAttempt)
            assertTrue(p.present)
            assertEquals(1L, p.candidateCount)
            assertEquals(1L, p.attemptCount)
            time = 21_000
            p.tick()
            assertFalse(p.present)
            assertEquals(2L, p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        }
    }

    @Test fun serviceUwbRequiresFreshAdvertisementFromCurrentRunningScan() {
        var time = 20_000L
        val p = BleFieldTrialPolicy({ time })
        assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        p.scanAvailabilityChanged(true)
        for (signal in BleEvidenceSignal.entries.filter { it != BleEvidenceSignal.FILTERED_SCAN }) {
            assertNull(p.signal(signal, time).startAttempt)
        }
        for (received in listOf(0L, time - 1, time + 1)) {
            assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, received).startAttempt)
        }
        assertEquals(1L, p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        repeat(10) { assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt) }
        assertEquals(1L, p.acceptedAdvertisementCount)
        assertEquals(1L, p.candidateCount)
    }

    @Test fun serviceUwbPauseAndResumeCannotBecomeDepartureOrRetry() {
        var time = 1_000L
        val p = BleFieldTrialPolicy({ time })
        p.scanAvailabilityChanged(true)
        val first = checkNotNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        p.scanAvailabilityChanged(false)
        time += 5_000
        p.finished(first, BleProbeState(status = BleProbeStatus.FAILED,
            reason = "GATT_CONNECT_FAILED", localClosed = true), true)
        time += 3_600_000
        assertNull(p.tick().startAttempt)
        assertNull(p.nextWakeAt)
        assertTrue(p.present)
        assertTrue(p.waitingForDeparture)
        p.scanAvailabilityChanged(true)
        assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time - 1).startAttempt)
        repeat(20) {
            time += 1_000
            for (signal in BleEvidenceSignal.entries) assertNull(p.signal(signal, time).startAttempt)
        }
        assertEquals(1L, p.attemptCount)
        time += BleFieldTrialPolicy.READ_START_FRESH_MS
        assertNull(p.tick().startAttempt)
        assertFalse(p.present)
        time++
        assertEquals(2L, p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        p.finished(first, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
        assertEquals(2L, p.currentAttempt)
        assertEquals(2L, p.candidateCount)
    }

    @Test fun serviceUwbOnlyObservedScanTimeCountsTowardDeparture() {
        var time = 1_000L
        val p = BleFieldTrialPolicy({ time })
        p.scanAvailabilityChanged(true)
        val attempt = checkNotNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        p.scanAvailabilityChanged(false)
        p.finished(attempt, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
        p.scanAvailabilityChanged(true)
        time += 4_000
        p.scanAvailabilityChanged(false)
        time += 60_000
        p.tick()
        assertTrue(p.present)
        p.scanAvailabilityChanged(true)
        time += 5_999
        p.tick()
        assertTrue(p.present)
        time++
        p.tick()
        assertFalse(p.present)
        assertEquals(1L, p.attemptCount)
    }

    @Test fun serviceInitialBudgetEndsUnlessCurrentOwnerEntersRanging() {
        for (ranging in listOf(false, true)) {
            var time = 1_000L
            val p = BleFieldTrialPolicy({ time })
            p.scanAvailabilityChanged(true)
            val attempt = checkNotNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
            p.scanAvailabilityChanged(false)
            p.serviceRanging(attempt + 1)
            assertEquals(181_000L, p.attemptDeadline)
            if (ranging) p.serviceRanging(attempt)
            time = 181_000
            assertEquals(if (ranging) null else attempt, p.tick().cancelAttempt)
            if (ranging) {
                time += 3_600_000
                assertNull(p.tick().cancelAttempt)
                assertEquals(attempt, p.currentAttempt)
            }
            p.stop()
            assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
            p.finished(attempt, BleProbeState(status = BleProbeStatus.CANCELED, localClosed = true), true)
            assertNull(p.currentAttempt)
            assertFalse(p.accepting)
        }
    }

    @Test fun serviceCleanupFailureRetainsExactAttemptDespiteLateCloseOrScanResume() {
        var time = 1_000L
        val p = BleFieldTrialPolicy({ time })
        p.scanAvailabilityChanged(true)
        val attempt = checkNotNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        p.scanAvailabilityChanged(false)
        p.finished(attempt, BleProbeState(status = BleProbeStatus.CLEANUP_FAILED), false)
        assertTrue(p.leaseRetained)
        assertFalse(p.accepting)
        p.finished(attempt, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
        p.scanAvailabilityChanged(true)
        time += 3_600_000
        for (signal in BleEvidenceSignal.entries) assertNull(p.signal(signal, time).startAttempt)
        assertNull(p.tick().startAttempt)
        assertEquals(attempt, p.currentAttempt)
        assertEquals(1L, p.attemptCount)
    }

}
