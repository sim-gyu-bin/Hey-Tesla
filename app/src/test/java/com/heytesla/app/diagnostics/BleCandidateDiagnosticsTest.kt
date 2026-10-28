package com.heytesla.app.diagnostics

import com.heytesla.app.vehicle.ble.BleProbeState
import com.heytesla.app.vehicle.ble.BleProbeStatus
import com.heytesla.app.vehicle.detection.BleEvidenceSignal
import com.heytesla.app.vehicle.detection.BleFieldTrialPolicy

import org.junit.Assert.*
import org.junit.Test

/** 실제 정책·단조시계·집계 timer의 안전 경계. 기기/native 수신이나 인증 성공을 모사하지 않는다. */
class BleCandidateDiagnosticsTest {
    @Test fun rawRepeatsSealOneServiceAttemptUntilActualExpiryThenSecondRawCreatesANewCandidate() {
        var time = 0L
        val policy = BleFieldTrialPolicy({ time }).apply { scanAvailabilityChanged(true) }
        time = 1_000
        val empty = policy.diagnostics(null)
        assertNull(empty.candidate)
        assertNull(empty.scanRunning)
        assertNull(empty.lastAcceptedReceivedElapsedMs)
        assertEquals(0L, empty.acceptedAdvertisementCount)

        val first = checkNotNull(policy.signal(BleEvidenceSignal.FILTERED_SCAN, 900).startAttempt)
        time = 1_500
        assertNull(policy.signal(BleEvidenceSignal.FILTERED_SCAN, 1_200).startAttempt)
        val reading = policy.diagnostics(false)
        assertTrue(reading.readInFlight)
        assertFalse(reading.waitingForDeparture)
        assertEquals(2L, reading.acceptedAdvertisementCount)
        assertEquals(2L, reading.candidateAdvertisementCount)
        assertEquals(0L, reading.suppressedReadCount)
        assertNull(reading.expiresElapsedMs) // active GATT의 스캔 중단은 부재가 아니다.

        time = 2_000
        policy.finished(first, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
        time = 2_300
        assertNull(policy.signal(BleEvidenceSignal.FILTERED_SCAN, 2_100).startAttempt)
        val waiting = policy.diagnostics(true)
        assertFalse(waiting.readInFlight)
        assertTrue(waiting.waitingForDeparture)
        assertEquals(1L, waiting.candidate)
        assertEquals(3L, waiting.candidateAdvertisementCount)
        assertEquals(1L, waiting.suppressedReadCount)
        assertEquals(2_100L, waiting.lastAcceptedReceivedElapsedMs)
        assertEquals(2_300L, waiting.lastAcceptedDeliveredElapsedMs)
        assertEquals(12_100L, waiting.expiresElapsedMs)
        time = 3_000
        policy.signal(BleEvidenceSignal.CDM_BLE_APPEARED, time)
        policy.signal(BleEvidenceSignal.BT_CONNECTED, time)
        assertEquals(12_100L, policy.diagnostics(true).expiresElapsedMs)
        assertEquals(3L, policy.diagnostics(true).acceptedAdvertisementCount)

        time = 17_600 // 실제 expiry timer가 5500ms 늦게 실행된다.
        val beforeTick = policy.diagnostics(true)
        assertTrue(beforeTick.candidatePresent)
        assertEquals(12_100L, beforeTick.expiresElapsedMs)
        assertNull(beforeTick.lastExpiredElapsedMs)
        assertEquals(1L, policy.attemptCount)
        assertNull(policy.tick().startAttempt)
        val expired = policy.diagnostics(true)
        assertFalse(expired.candidatePresent)
        assertNull(expired.expiresElapsedMs)
        assertEquals(17_600L, expired.lastExpiredElapsedMs)
        assertEquals(5_500L, expired.lastExpiryDelayMs)

        assertEquals(2L, policy.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        val second = policy.diagnostics(true)
        assertEquals(2L, second.candidate)
        assertEquals(4L, second.acceptedAdvertisementCount)
        assertEquals(1L, second.candidateAdvertisementCount)
        assertEquals(1L, second.suppressedReadCount)
        assertNull(second.expiresElapsedMs)
        assertEquals(5_500L, second.lastExpiryDelayMs)
    }

    @Test fun duplicateReorderedFutureNegativeAndOverTenSecondAdsCannotChangeReadDiagnosticsOrExpiry() {
        var time = 20_000L
        val policy = BleFieldTrialPolicy({ time }).apply { scanAvailabilityChanged(true) }
        // 스캔이 실제 시작된 이후 수신된 광고만 현재 서비스 입장에 사용할 수 있다.
        time = 21_000
        val attempt = checkNotNull(policy.signal(BleEvidenceSignal.FILTERED_SCAN, 20_000).startAttempt)
        policy.finished(attempt, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
        val before = policy.diagnostics(true)
        val wake = policy.nextWakeAt
        for (received in listOf(20_000L, 19_999L, 21_001L, -1L, 10_999L)) {
            assertNull(policy.signal(BleEvidenceSignal.FILTERED_SCAN, received).startAttempt)
            assertEquals(before, policy.diagnostics(true))
            assertEquals(wake, policy.nextWakeAt)
        }
        time = 29_999
        assertNull(policy.signal(BleEvidenceSignal.FILTERED_SCAN, 21_001).startAttempt)
        val boundary = policy.diagnostics(true)
        assertEquals(2L, boundary.acceptedAdvertisementCount)
        assertEquals(1L, boundary.suppressedReadCount)
        assertEquals(21_001L, boundary.lastAcceptedReceivedElapsedMs)
        assertEquals(29_999L, boundary.lastAcceptedDeliveredElapsedMs)
        assertEquals(31_001L, boundary.expiresElapsedMs)
        assertEquals(1L, policy.attemptCount)
    }

    @Test fun rawCanObserveLateExpiryBeforeNewCandidateWithoutTimerOrReadReplay() {
        var time = 1_000L
        val policy = BleFieldTrialPolicy({ time }).apply { scanAvailabilityChanged(true) }
        val attempt = checkNotNull(policy.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        policy.finished(attempt, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
        time = 16_000
        assertTrue(policy.diagnostics(true).candidatePresent) // getter는 만료를 실행하지 않는다.
        assertEquals(2L, policy.signal(BleEvidenceSignal.FILTERED_SCAN, 15_000).startAttempt)
        val next = policy.diagnostics(true)
        assertEquals(16_000L, next.lastExpiredElapsedMs)
        assertEquals(5_000L, next.lastExpiryDelayMs)
        assertEquals(15_000L, next.lastAcceptedReceivedElapsedMs)
        assertNull(next.expiresElapsedMs)
        assertEquals(1L, next.candidateAdvertisementCount)
        assertEquals(0L, next.suppressedReadCount)
    }


    @Test fun delayedAggregatePreservesLastSeenAndCannotExpireCandidateOrRestartRead() {
        val h = PublisherHarness()
        val attempt = checkNotNull(h.policy.signal(BleEvidenceSignal.FILTERED_SCAN, h.time).startAttempt)
        h.policy.finished(attempt, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
        h.publisher.flush()
        h.time = 5_000
        h.advertisement(4_800)
        h.time = 10_000
        h.advertisement(9_900)
        h.time = 15_000
        h.advertisement(14_700)
        val tick = h.timers.single().task
        h.time = 25_000 // dirty timer는 11000에 예약됐지만 늦게 전달된다.
        h.deliverTimer(tick)
        val aggregate = h.rows.last()
        assertEquals(listOf(1_000L, 25_000L), h.rows.map { it.observedElapsedMs })
        assertEquals(4L, aggregate.acceptedAdvertisementCount)
        assertEquals(3L, aggregate.suppressedReadCount)
        assertEquals(14_700L, aggregate.lastAcceptedReceivedElapsedMs)
        assertEquals(15_000L, aggregate.lastAcceptedDeliveredElapsedMs)
        assertEquals(24_700L, aggregate.expiresElapsedMs)
        assertEquals(1L, h.policy.attemptCount)
        assertTrue(h.policy.waitingForDeparture)
        assertTrue(h.timers.isEmpty()) // clean 집계는 polling을 계속하지 않는다.

        h.time = 80_000
        h.publisher.flush()
        assertTrue(h.rows.last().candidatePresent)
        assertNull(h.rows.last().lastExpiredElapsedMs)
        h.policy.tick()
        h.publisher.flush()
        assertFalse(h.rows.last().candidatePresent)
        assertEquals(h.time - checkNotNull(aggregate.expiresElapsedMs), h.rows.last().lastExpiryDelayMs)
        assertEquals(14_700L, h.rows.last().lastAcceptedReceivedElapsedMs)
    }

    @Test fun repeatedAdsHaveAtLeastTenSecondLogSpacingAndCanceledTickCannotFlushEarly() {
        val h = PublisherHarness()
        val attempt = checkNotNull(h.policy.signal(BleEvidenceSignal.FILTERED_SCAN, h.time).startAttempt)
        h.policy.finished(attempt, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
        h.publisher.flush()
        h.time = 2_000
        h.advertisement(h.time)
        val canceled = h.timers.single().task
        h.time = 3_000
        h.publisher.flush() // 강제 전이는 기존 dirty timer를 취소한다.
        h.time = 4_000
        h.advertisement(h.time)
        canceled.run() // 이미 취소된 callback이 늦게 도착하더라도 13000 이전 집계는 없다.
        assertEquals(listOf(1_000L, 3_000L), h.rows.map { it.observedElapsedMs })
        assertEquals(13_000L, h.timers.single().at)
        for (time in 5_000L..12_000L step 1_000L) {
            h.time = time
            h.advertisement(time)
            assertEquals(13_000L, h.timers.single().at)
        }
        h.time = 13_000
        h.deliverTimer(h.timers.single().task)
        assertEquals(listOf(1_000L, 3_000L, 13_000L), h.rows.map { it.observedElapsedMs })
        assertEquals(11L, h.rows.last().acceptedAdvertisementCount)
        assertEquals(12_000L, h.rows.last().lastAcceptedReceivedElapsedMs)
        h.time = 14_000
        h.advertisement(h.time)
        assertSame(canceled, h.timers.single().task)
        assertEquals(23_000L, h.timers.single().at)
    }

    @Test fun offCancelsAggregateAndNewGenerationRejectsOldTicksAndForcedFlushes() {
        val h = PublisherHarness()
        h.policy.signal(BleEvidenceSignal.FILTERED_SCAN, h.time)
        h.publisher.flush()
        h.time = 2_000
        h.advertisement(h.time)
        val late = h.timers.single().task
        h.policy.stop()
        h.publisher.stop()
        h.publisher.flush() // OFF 본문은 현재 read/scan 정리 상태를 마지막으로 관측한다.
        val terminal = h.rows.toList()
        assertTrue(h.timers.isEmpty())
        h.time = 20_000
        late.run()
        h.publisher.markDirty()
        assertEquals(terminal, h.rows)
        assertNull(h.policy.signal(BleEvidenceSignal.FILTERED_SCAN, h.time).startAttempt)

        h.current = false // token/policy 세대가 교체됐다.
        h.publisher.flush()
        late.run()
        assertEquals(terminal, h.rows)
        val next = PublisherHarness()
        val fresh = next.policy.diagnostics(null)
        assertNull(fresh.candidate)
        assertNull(fresh.lastAcceptedReceivedElapsedMs)
        assertNull(fresh.lastExpiredElapsedMs)
        assertEquals(0L, fresh.acceptedAdvertisementCount)
    }

    @Test fun recordingExceptionDoesNotStopPolicyOrUnsealSameCandidateRead() {
        val h = PublisherHarness()
        h.failRecord = true
        val attempt = checkNotNull(h.policy.signal(BleEvidenceSignal.FILTERED_SCAN, h.time).startAttempt)
        h.publisher.flush()
        assertTrue(h.policy.accepting)
        assertEquals(attempt, h.policy.currentAttempt)
        h.policy.finished(attempt, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
        h.publisher.flush()
        h.time = 2_000
        assertNull(h.advertisement(h.time).startAttempt)
        h.time = 11_000
        h.deliverTimer(h.timers.single().task)
        assertTrue(h.policy.accepting)
        assertEquals(1L, h.policy.attemptCount)
        assertTrue(h.policy.waitingForDeparture)
        assertTrue(h.rows.isEmpty())
        h.failRecord = false
        h.time = checkNotNull(h.policy.expiresElapsedMs) - 1
        val lastReceived = h.time
        assertNull(h.advertisement(h.time).startAttempt)
        h.time = 21_000
        h.deliverTimer(h.timers.single().task)
        assertEquals(3L, h.rows.single().acceptedAdvertisementCount)
        assertEquals(2L, h.rows.single().suppressedReadCount)
        assertEquals(lastReceived, h.rows.single().lastAcceptedReceivedElapsedMs)
        assertEquals(1L, h.policy.attemptCount)
    }
    @Test fun ownershipLossWithoutStopRejectsPendingAggregateAndDoesNotTransferOldCandidate() {
        val old = PublisherHarness()
        old.policy.signal(BleEvidenceSignal.FILTERED_SCAN, old.time)
        old.publisher.flush()
        old.time = 2_000
        old.advertisement(old.time)
        val late = old.timers.single().task
        val before = old.rows.toList()
        old.current = false
        old.time = 11_000
        old.deliverTimer(late)
        old.publisher.flush()
        old.publisher.markDirty()
        assertEquals(before, old.rows)
        assertTrue(old.timers.isEmpty())

        val next = PublisherHarness()
        next.publisher.flush()
        assertFalse(next.rows.single().candidatePresent)
        assertFalse(next.rows.single().readInFlight)
        assertNull(next.rows.single().lastAcceptedReceivedElapsedMs)
        assertNull(next.rows.single().lastExpiryDelayMs)
        assertEquals(0L, next.rows.single().acceptedAdvertisementCount)
        assertEquals(1L, next.policy.signal(BleEvidenceSignal.FILTERED_SCAN, next.time).startAttempt)
        assertEquals(1L, old.policy.attemptCount)
    }


    @Test fun jsonDistinguishesZeroFromUnknownAndRejectsInvalidOrFutureHistoricalScalars() {
        val unknown = BleCandidateDiagnosticsJson.encode(BleCandidateDiagnostics())
        assertTrue(unknown.contains("\"acceptedAdvertisementCount\":0"))
        assertTrue(unknown.contains("\"scanRunning\":null"))
        assertTrue(unknown.contains("\"lastAcceptedReceivedElapsedMs\":null"))
        val snapshot = BleCandidateDiagnostics(candidate = 1, observedElapsedMs = 20,
            candidatePresent = true, acceptedAdvertisementCount = 2, candidateAdvertisementCount = 2,
            suppressedReadCount = 0, lastAcceptedReceivedElapsedMs = 0, lastAcceptedDeliveredElapsedMs = 1,
            expiresElapsedMs = 180_000, lastExpiredElapsedMs = 10, lastExpiryDelayMs = 0)
        val valid = BleCandidateDiagnosticsJson.encode(snapshot)
        assertTrue(valid.contains("\"lastAcceptedReceivedElapsedMs\":0"))
        assertTrue(valid.contains("\"lastExpiryDelayMs\":0"))
        assertTrue(valid.contains("\"expiresElapsedMs\":180000"))
        val invalid = BleCandidateDiagnosticsJson.encode(snapshot.copy(candidate = 0,
            candidatePresent = false, acceptedAdvertisementCount = -1, candidateAdvertisementCount = -2,
            suppressedReadCount = -3, lastAcceptedReceivedElapsedMs = 21, lastAcceptedDeliveredElapsedMs = 22,
            lastExpiredElapsedMs = 23, lastExpiryDelayMs = -1))
        for (key in listOf("candidate", "acceptedAdvertisementCount", "candidateAdvertisementCount", "suppressedReadCount",
            "lastAcceptedReceivedElapsedMs", "lastAcceptedDeliveredElapsedMs", "expiresElapsedMs", "lastExpiredElapsedMs",
            "lastExpiryDelayMs")) assertTrue(key, invalid.contains("\"$key\":null"))
        val unordered = BleCandidateDiagnosticsJson.encode(snapshot.copy(lastAcceptedReceivedElapsedMs = 2))
        assertTrue(unordered.contains("\"lastAcceptedReceivedElapsedMs\":null"))
        val missingClock = BleCandidateDiagnosticsJson.encode(snapshot.copy(observedElapsedMs = -1))
        assertTrue(missingClock.contains("\"observedElapsedMs\":null"))
        assertTrue(missingClock.contains("\"expiresElapsedMs\":null"))
    }

    private class PublisherHarness {
        data class Timer(val task: Runnable, val at: Long)
        var time = 1_000L
        var current = true
        var failRecord = false
        val timers = mutableListOf<Timer>()
        val rows = mutableListOf<BleCandidateDiagnostics>()
        val policy = BleFieldTrialPolicy({ time }).apply { scanAvailabilityChanged(true) }
        val publisher = BleCandidateDiagnosticsPublisher(
            now = { time },
            postDelayed = { task, delay -> timers += Timer(task, time + delay) },
            removeCallbacks = { task -> timers.removeAll { it.task === task } },
            isCurrent = { current },
            snapshot = { policy.diagnostics(true) },
            publish = { value ->
                if (failRecord) error("진단 기록 실패")
                rows += value
            },
        )

        fun deliverTimer(task: Runnable) {
            // Handler는 실행할 메시지를 큐에서 꺼낸 뒤 Runnable을 호출한다.
            timers.removeAll { it.task === task }
            task.run()
        }

        fun advertisement(received: Long): BleFieldTrialPolicy.Decision {
            val before = policy.acceptedAdvertisementCount
            val decision = policy.signal(BleEvidenceSignal.FILTERED_SCAN, received)
            if (before != policy.acceptedAdvertisementCount) publisher.markDirty()
            return decision
        }
    }
}
