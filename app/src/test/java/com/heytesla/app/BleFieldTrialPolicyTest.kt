package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test

class BleFieldTrialPolicyTest {
    @Test fun authenticatedRetryRequiresCloseCooldownAndNewAdvertisementAndKeepsCandidateBudget() {
        var time = 1_000L
        val p = BleFieldTrialPolicy(BleFieldConfig.VEHICLE_NAME_AUTHENTICATED_READ, { time })
        assertEquals(1L, p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        time = 15_000
        assertNull(p.tick().cancelAttempt)
        assertTrue(p.present)
        assertNull(p.expiresElapsedMs)
        p.finished(1, BleProbeState(status = BleProbeStatus.FAILED,
            reason = "GATT_WRITE_FAILED", localClosed = true), true)
        assertFalse(p.waitingForDeparture)
        assertEquals(25_000L, p.nextWakeAt)
        time = 18_499
        assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        time = 18_500
        repeat(3) { assertNull(p.tick().startAttempt) }
        for (received in listOf(15_000L, 18_499L, 18_498L, 18_501L, 8_499L)) {
            assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, received).startAttempt)
        }
        assertEquals(2L, p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        assertEquals(1L, p.candidateCount)
        assertEquals(41_000L, p.attemptDeadline)
        p.finished(1, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
        assertEquals(2L, p.currentAttempt)
        time++
        p.finished(2, BleProbeState(status = BleProbeStatus.FAILED,
            reason = "GATT_WRITE_FAILED", localClosed = true), true)
        assertTrue(p.waitingForDeparture)
        time += 3_500
        assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        assertEquals(2L, p.attemptCount)
    }

    @Test fun authenticatedTerminalFailuresAndInsufficientBudgetNeverRetrySameCandidate() {
        for (state in listOf(
            BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true),
            BleProbeState(status = BleProbeStatus.BLOCKED, localClosed = true),
            BleProbeState(status = BleProbeStatus.CANCELED, localClosed = true),
            BleProbeState(status = BleProbeStatus.FAILED, reason = "PROTOCOL_RESPONSE_INVALID", localClosed = true),
            BleProbeState(status = BleProbeStatus.FAILED, reason = "GATT_CONNECT_PERMISSION_DENIED", localClosed = true),
        )) {
            var time = 1_000L
            val p = BleFieldTrialPolicy(BleFieldConfig.VEHICLE_NAME_AUTHENTICATED_READ, { time })
            p.signal(BleEvidenceSignal.FILTERED_SCAN, time)
            p.finished(1, state, true)
            repeat(9) {
                time += 1_000
                assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
            }
            assertTrue(p.waitingForDeparture)
        }
        var time = 1_000L
        val p = BleFieldTrialPolicy(BleFieldConfig.VEHICLE_NAME_AUTHENTICATED_READ, { time })
        p.signal(BleEvidenceSignal.FILTERED_SCAN, time)
        time = 39_000
        p.finished(1, BleProbeState(status = BleProbeStatus.TIMED_OUT,
            reason = "DEADLINE_EXPIRED", localClosed = true), true)
        assertTrue(p.waitingForDeparture)
        time++
        assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
    }

    @Test fun authenticatedUnknownCloseAndOffCannotRetry() {
        for (closed in listOf(false, true)) {
            var time = 1_000L
            val p = BleFieldTrialPolicy(BleFieldConfig.VEHICLE_NAME_AUTHENTICATED_READ, { time })
            p.signal(BleEvidenceSignal.FILTERED_SCAN, time)
            if (closed) p.stop()
            p.finished(1, BleProbeState(status = BleProbeStatus.FAILED,
                reason = "GATT_CONNECT_FAILED", localClosed = closed), true)
            time += 4_000
            assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
            assertFalse(p.accepting)
        }
    }
    @Test fun authenticatedRamPortTimeoutKeepsLeaseThroughCleanupAndCloseFailureCannotRearm() {
        val h = Consumer(BleFieldConfig.VEHICLE_NAME_AUTHENTICATED_READ)
        h.signal(BleEvidenceSignal.FILTERED_SCAN)
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        assertEquals(1, h.ports.size)
        h.advanceBy(BleGattSession.CONNECT_MS)
        assertEquals(BleProbeStatus.CLEANING_UP, h.trial.state.status)
        assertTrue(h.released.isEmpty())
        assertEquals(1L, h.policy.currentAttempt)
        h.port.failClose = true
        h.advanceBy(BleGattSession.CLEANUP_MS)
        assertEquals(BleProbeStatus.CLEANUP_FAILED, h.trial.state.status)
        assertTrue(h.released.isEmpty())
        assertTrue(h.policy.leaseRetained)
        h.advanceBy(BleFieldTrialPolicy.SIGNAL_FRESH_MS)
        h.signal(BleEvidenceSignal.FILTERED_SCAN)
        assertEquals(1, h.ports.size)
    }

    @Test fun authenticatedApproachStartsOnlyOnceFromFreshScanAndCdmBtCannotExtendOrRearm() {
        var time = 1_000L
        val p = BleFieldTrialPolicy(BleFieldConfig.VEHICLE_NAME_AUTHENTICATED_READ, { time })
        for (signal in listOf(BleEvidenceSignal.CDM_BLE_APPEARED, BleEvidenceSignal.BT_CONNECTED)) {
            assertNull(p.signal(signal, time).startAttempt)
        }
        assertFalse(p.present)
        assertEquals(1L, p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        p.finished(1, BleProbeState(status = BleProbeStatus.FAILED, localClosed = true), true)
        repeat(10) {
            time += 1_000
            assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        }
        val expiry = p.nextWakeAt
        assertEquals(21_000L, expiry)
        time += 1_000
        p.signal(BleEvidenceSignal.CDM_BLE_APPEARED, time)
        p.signal(BleEvidenceSignal.BT_CONNECTED, time)
        assertEquals(expiry, p.nextWakeAt)
        assertFalse(p.cdmPresent)
        time = 20_999L
        assertNull(p.tick().startAttempt)
        assertTrue(p.present)
        assertEquals(1L, p.attemptCount)
        time = checkNotNull(expiry)
        assertNull(p.tick().startAttempt)
        assertFalse(p.present)
        p.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED, time)
        assertFalse(p.present)
        assertEquals(2L, p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
    }

    @Test fun authenticatedApproachRejectsStaleFutureDuplicateAndReorderedScanAfterExpiry() {
        var time = 20_000L
        val p = BleFieldTrialPolicy(BleFieldConfig.VEHICLE_NAME_AUTHENTICATED_READ, { time })
        assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, 9_999).startAttempt)
        assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time + 1).startAttempt)
        assertEquals(1L, p.signal(BleEvidenceSignal.FILTERED_SCAN, time - 10_000).startAttempt)
        p.finished(1, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
        val expiry = p.nextWakeAt
        assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, 10_000).startAttempt)
        assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, 9_999).startAttempt)
        assertEquals(expiry, p.nextWakeAt)
        time = checkNotNull(expiry)
        p.tick()
        assertNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, 10_000).startAttempt)
        assertFalse(p.present)
        assertEquals(2L, p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
    }

    @Test fun pauseAcquisitionIsBoundedAndDeadlineCancelDoesNotReturnLeaseOrRetry() {
        var time = 100L
        val p = BleFieldTrialPolicy(BleFieldConfig.VEHICLE_NAME_AUTHENTICATED_READ, { time })
        val attempt = checkNotNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, time).startAttempt)
        assertEquals(40_100L, p.attemptDeadline)
        assertEquals(p.attemptDeadline, p.nextWakeAt)
        time = 40_100
        assertEquals(attempt, p.tick().cancelAttempt)
        assertNull(p.tick().cancelAttempt)
        assertEquals(attempt, p.currentAttempt)
        assertTrue(p.localGattActive)
        p.finished(attempt, BleProbeState(status = BleProbeStatus.TIMED_OUT, localClosed = true), true)
        assertNull(p.currentAttempt)
        assertFalse(p.localGattActive)
        assertNull(p.tick().startAttempt)
        assertTrue(p.waitingForDeparture)
    }

    @Test fun authenticatedCleanupFailureLatchesCandidateAndSuppressesEveryLaterConsumerSignal() {
        val p = BleFieldTrialPolicy(BleFieldConfig.VEHICLE_NAME_AUTHENTICATED_READ, { 1_000 })
        val attempt = checkNotNull(p.signal(BleEvidenceSignal.FILTERED_SCAN, 1_000).startAttempt)
        p.finished(attempt, BleProbeState(status = BleProbeStatus.CLEANUP_FAILED), false)
        assertTrue(p.leaseRetained)
        assertFalse(p.accepting)
        for (signal in BleEvidenceSignal.entries) assertNull(p.signal(signal, 1_000).startAttempt)
        assertNull(p.tick().startAttempt)
        p.finished(attempt, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true)
        assertEquals(attempt, p.currentAttempt)
        assertTrue(p.leaseRetained)
    }

    @Test fun advertisedNameDetectionMergesCdmAndScanButNeverSchedulesGattOrBtAssist() {
        val h = Consumer(BleFieldConfig.VEHICLE_NAME_DETECTION)
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        assertEquals(0L, h.policy.candidateCount)
        h.signal(BleEvidenceSignal.FILTERED_SCAN)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        repeat(100) { h.signal(BleEvidenceSignal.FILTERED_SCAN) }
        assertEquals(1L, h.policy.candidateCount)
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
        h.advanceBy(BleFieldTrialPolicy.SIGNAL_FRESH_MS)
        assertFalse(h.policy.present)
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        assertFalse(h.policy.present)
        h.signal(BleEvidenceSignal.FILTERED_SCAN)
        assertEquals(2L, h.policy.candidateCount)
        assertEquals(0L, h.policy.attemptCount)
        assertNull(h.policy.currentAttempt)
        assertFalse(h.policy.localGattActive)
        assertTrue(h.ports.isEmpty())
        h.policy.stop()
        h.signal(BleEvidenceSignal.FILTERED_SCAN)
        h.advanceBy(BleFieldTrialPolicy.SIGNAL_FRESH_MS)
        assertEquals(2L, h.policy.candidateCount)
        assertEquals(0L, h.policy.attemptCount)
        assertNull(h.policy.nextWakeAt)
    }

    @Test fun baselineKeepsRealCdmEdgesAndDoesNotUseBtOrScanAsTriggers() {
        val h = Consumer(BleFieldConfig.BASELINE)
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        h.signal(BleEvidenceSignal.FILTERED_SCAN)
        assertEquals(0L, h.policy.attemptCount)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.complete()
        assertTrue(h.policy.waitingForDeparture)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        assertEquals(1L, h.policy.attemptCount)
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
        assertFalse(h.policy.present)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        assertEquals(2L, h.policy.attemptCount)
        h.failConnection()
        h.advanceBy(2_000)
        assertEquals(2L, h.policy.attemptCount)
        assertEquals(2L, h.policy.completedCount)
    }

    @Test fun baselineQueuesReappearanceOnlyUntilActualCloseAndLatestDepartureWins() {
        val h = Consumer(BleFieldConfig.BASELINE)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        val first = h.trial
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
        assertEquals(BleProbeStatus.CLEANING_UP, first.state.status)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        assertEquals(1L, h.policy.attemptCount)
        h.advanceBy(BleGattSession.CLEANUP_MS)
        assertEquals(listOf(1L), h.released)
        assertEquals(2L, h.policy.currentAttempt)
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
        h.advanceBy(BleGattSession.CLEANUP_MS)
        assertEquals(2L, h.policy.attemptCount)
        assertFalse(h.policy.present)
        assertNull(h.policy.currentAttempt)
    }

    @Test fun improvedMergesBtCdmAndFilteredScanIntoOneCandidateAndOneSuccessfulTrial() {
        val h = Consumer(BleFieldConfig.IMPROVED)
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        repeat(100) { h.signal(BleEvidenceSignal.FILTERED_SCAN) }
        h.complete()
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        h.advanceBy(2_000)
        assertEquals(1L, h.policy.candidateCount)
        assertEquals(1L, h.policy.attemptCount)
        assertEquals(1, h.evidence.count { it.kind == BleEvidenceKind.CANDIDATE_SIGNAL })
        assertTrue(h.evidence.any { it.kind == BleEvidenceKind.RETRY_SUPPRESSED && it.gate == BleEvidenceGate.SUCCESS })
    }

    @Test fun improvedCdmFlappingKeepsCandidateUntilGraceButDoesNotRearmFromTimer() {
        val h = Consumer(BleFieldConfig.IMPROVED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.complete()
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
        h.advanceBy(29_999)
        assertTrue(h.policy.present)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
        h.advanceBy(29_999)
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED) // 중복 이탈은 grace를 연장하지 않는다.
        h.advanceBy(1)
        assertFalse(h.policy.present)
        assertEquals(1L, h.policy.attemptCount)
        assertNull(h.policy.nextWakeAt)
        h.advanceBy(180_000)
        assertEquals(1L, h.policy.candidateCount)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        assertEquals(2L, h.policy.candidateCount)
        assertEquals(2L, h.policy.attemptCount)
    }

    @Test fun observationOnlyConsumesMergedSignalsWithoutCreatingGattTrials() {
        val h = Consumer(BleFieldConfig.IMPROVED.copy(observationOnly = true))
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.signal(BleEvidenceSignal.FILTERED_SCAN)
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
        h.advanceBy(200_000)
        assertTrue(h.policy.present) // 확인된 외부 연결이 유지되는 동안 이탈 확정 금지.
        h.signal(BleEvidenceSignal.BT_DISCONNECTED)
        h.advanceBy(30_000)
        assertFalse(h.policy.present)
        assertEquals(1L, h.policy.candidateCount)
        assertEquals(0L, h.policy.attemptCount)
        assertTrue(h.ports.isEmpty())
        assertFalse(h.policy.localGattActive)
    }

    @Test fun filteredScanExpiresAtFreshnessLimitAndOldPositiveSignalsCannotCreateCandidates() {
        val h = Consumer(BleFieldConfig.IMPROVED.copy(observationOnly = true))
        h.signal(BleEvidenceSignal.FILTERED_SCAN)
        h.advanceBy(59_999)
        assertTrue(h.policy.present)
        h.advanceBy(1)
        assertFalse(h.policy.present)
        h.advanceBy(1)
        h.signal(BleEvidenceSignal.FILTERED_SCAN, received = 0)
        h.signal(BleEvidenceSignal.BT_CONNECTED, received = 0)
        assertEquals(1L, h.policy.candidateCount)
        h.signal(BleEvidenceSignal.FILTERED_SCAN)
        assertEquals(2L, h.policy.candidateCount)
        assertEquals(0L, h.policy.attemptCount)
    }

    @Test fun ownBtSignalsCannotStartOrRearmBeforeOrAfterClose() {
        val h = Consumer(BleFieldConfig.IMPROVED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        h.signal(BleEvidenceSignal.BT_DISCONNECTED)
        h.complete()
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        h.advanceBy(15_000)
        h.signal(BleEvidenceSignal.BT_CONNECTED, received = 1_000) // 늦게 dispatch된 로컬 양성 신호.
        h.advanceBy(15_000)
        assertFalse(h.policy.present)
        assertEquals(1L, h.policy.attemptCount)
        assertEquals(1L, h.policy.candidateCount)
        assertTrue(h.evidence.count { it.kind == BleEvidenceKind.SIGNAL_SELF_SUPPRESSED } >= 4)
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        assertEquals(2L, h.policy.candidateCount)
    }

    @Test fun disconnectDuringOwnCloseWithdrawsExternalBtWithoutImmediateDepartureOrLoop() {
        val h = Consumer(BleFieldConfig.IMPROVED)
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        h.signal(BleEvidenceSignal.BT_DISCONNECTED)
        h.complete()
        h.advanceBy(14_999)
        assertTrue(h.policy.present)
        h.advanceBy(1)
        assertTrue(h.policy.present) // local 15초만 끝나도 withdrawal grace 30초가 남는다.
        h.advanceBy(12_000)
        assertFalse(h.policy.present)
        assertEquals(1L, h.policy.candidateCount)
        assertEquals(1L, h.policy.attemptCount)
        assertFalse(h.evidence.any { it.kind == BleEvidenceKind.CANDIDATE_SIGNAL &&
            it.signal == BleEvidenceSignal.BT_DISCONNECTED })
        h.advanceBy(200_000)
        assertEquals(1L, h.policy.attemptCount)
    }

    @Test fun withdrawalOfBtDoesNotDiscardFreshCdmOrScanEvidence() {
        val h = Consumer(BleFieldConfig.IMPROVED)
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.signal(BleEvidenceSignal.FILTERED_SCAN)
        h.signal(BleEvidenceSignal.BT_DISCONNECTED)
        h.complete()
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
        h.advanceBy(30_000)
        assertTrue(h.policy.present)
        h.advanceTo(60_000)
        assertFalse(h.policy.present)
        assertEquals(1L, h.policy.candidateCount)
        assertEquals(1L, h.policy.attemptCount)
    }

    @Test fun retryWaitsForRealCloseThenTwoSecondsAndKeepsOriginalBudget() {
        val h = Consumer(BleFieldConfig.IMPROVED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        val first = h.trial
        h.advanceTo(8_000)
        assertEquals(BleProbeStatus.CLEANING_UP, first.state.status)
        h.advanceTo(10_000)
        assertEquals(1L, h.policy.attemptCount)
        assertTrue(h.released.isEmpty())
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.advanceTo(12_000)
        assertEquals(listOf(1L), h.released)
        assertNull(h.policy.currentAttempt)
        h.advanceTo(13_999)
        assertEquals(1L, h.policy.attemptCount)
        h.advanceTo(14_000)
        assertEquals(2L, h.policy.currentAttempt)
        assertEquals(40_000L, h.policy.attemptDeadline)
        assertTrue(h.ports.all { it.connectCalls == 1 })
        h.failConnection()
        h.advanceBy(2_000)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        assertEquals(2L, h.policy.attemptCount)
        assertEquals(1L, h.policy.candidateCount)
    }

    @Test fun exhaustedBudgetCannotBeResetByRepeatedSignalsOrLateTimer() {
        val h = Consumer(BleFieldConfig.IMPROVED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.advanceTo(39_000) // 지연된 watchdog도 원래 총 예산으로 정리한다.
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.advanceTo(40_000)
        h.advanceTo(42_000)
        assertEquals(1L, h.policy.attemptCount)
        assertEquals(listOf(1L), h.released)
        assertNull(h.policy.nextWakeAt)
        assertTrue(h.evidence.any { it.kind == BleEvidenceKind.RETRY_SUPPRESSED && it.gate == BleEvidenceGate.BUDGET })
    }

    @Test fun stopDuringCleanupAndRetryDelaySealsEveryFutureTrigger() {
        for (duringCleanup in listOf(true, false)) {
            val h = Consumer(BleFieldConfig.IMPROVED)
            h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
            if (duringCleanup) h.advanceTo(8_000) else h.failConnection()
            h.stop()
            h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
            h.signal(BleEvidenceSignal.BT_CONNECTED)
            h.signal(BleEvidenceSignal.FILTERED_SCAN)
            h.advanceBy(40_000)
            assertFalse(h.policy.accepting)
            assertNull(h.policy.nextWakeAt)
            assertNull(h.policy.currentAttempt)
            assertEquals(1L, h.policy.attemptCount)
        }
    }

    @Test fun failedCloseRetainsOwnerAndSealsRetryReappearanceAndLateCompletion() {
        for (config in listOf(BleFieldConfig.BASELINE, BleFieldConfig.IMPROVED)) {
            val h = Consumer(config)
            h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
            val old = h.trial
            h.port.failClose = true
            h.failConnection()
            assertTrue(h.policy.leaseRetained)
            assertFalse(h.policy.accepting)
            h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
            h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
            h.advanceBy(200_000)
            h.consume(h.policy.finished(old.token, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true))
            assertEquals(1L, h.policy.currentAttempt)
            assertEquals(0L, h.policy.completedCount)
            assertTrue(h.released.isEmpty())
            assertNull(h.policy.nextWakeAt)
        }
    }

    @Test fun oldTrialCallbacksAndCompletionCannotOwnRetryOrScheduleThirdAttempt() {
        val h = Consumer(BleFieldConfig.IMPROVED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        val old = h.trial
        val oldPort = h.port
        h.failConnection()
        h.advanceBy(2_000)
        old.connection(old.token, oldPort, true, true)
        old.services(old.token, oldPort, true)
        old.descriptor(old.token, oldPort, true, true)
        h.consume(h.policy.finished(old.token, BleProbeState(status = BleProbeStatus.COMPLETE, localClosed = true), true))
        assertEquals(2L, h.policy.currentAttempt)
        assertEquals(BleProbeStatus.CONNECTING, h.trial.state.status)
        assertEquals(1L, h.policy.completedCount)
        h.failConnection()
        h.advanceBy(2_000)
        assertEquals(2L, h.policy.attemptCount)
        assertEquals(2L, h.policy.completedCount)
    }

    @Test fun missingProfileBlockedAndUnsupportedNeverRetry() {
        val h = Consumer(BleFieldConfig.IMPROVED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.port.profile = BleGattProfile(false, false, false, false, null)
        h.trial.connection(h.trial.token, h.port, true, true)
        h.trial.services(h.trial.token, h.port, true)
        h.advanceBy(BleGattSession.CLEANUP_MS)
        h.advanceBy(2_000)
        assertEquals(1L, h.policy.attemptCount)
        for (state in listOf(BleProbeState(status = BleProbeStatus.BLOCKED, reason = "LEASE_UNAVAILABLE"),
            BleProbeState(status = BleProbeStatus.FAILED, reason = "RX_SUBSCRIPTION_UNSUPPORTED"))) {
            var time = 0L
            val p = BleFieldTrialPolicy(BleFieldConfig.IMPROVED, { time })
            val attempt = checkNotNull(p.signal(BleEvidenceSignal.CDM_BLE_APPEARED, time).startAttempt)
            p.finished(attempt, state, true)
            time = 2_000
            assertNull(p.tick().startAttempt)
            assertEquals(1L, p.attemptCount)
        }
    }

    @Test fun eachRetryableGattFailureAndTimeoutUsesTheSameCloseThenDelayConsumer() {
        for (failure in listOf("CONNECT_REQUEST_FAILED", "CONNECTION_FAILED", "CONNECTION_LOST",
            "DISCOVERY_FAILED", "SUBSCRIPTION_FAILED", "CONNECTING_TIMEOUT", "DISCOVERING_TIMEOUT",
            "SUBSCRIBING_TIMEOUT")) {
            val h = Consumer(BleFieldConfig.IMPROVED, connectAccepted = failure != "CONNECT_REQUEST_FAILED")
            h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
            when (failure) {
                "CONNECTION_FAILED" -> h.failConnection()
                "CONNECTION_LOST" -> {
                    h.trial.connection(h.trial.token, h.port, true, true)
                    h.trial.connection(h.trial.token, h.port, true, false)
                }
                "DISCOVERY_FAILED" -> {
                    h.trial.connection(h.trial.token, h.port, true, true)
                    h.trial.services(h.trial.token, h.port, false)
                }
                "SUBSCRIPTION_FAILED" -> {
                    h.trial.connection(h.trial.token, h.port, true, true)
                    h.trial.services(h.trial.token, h.port, true)
                    h.trial.descriptor(h.trial.token, h.port, true, false)
                }
                "CONNECTING_TIMEOUT" -> h.advanceBy(BleGattSession.CONNECT_MS)
                "DISCOVERING_TIMEOUT" -> {
                    h.trial.connection(h.trial.token, h.port, true, true)
                    h.advanceBy(BleGattSession.DISCOVER_MS)
                }
                "SUBSCRIBING_TIMEOUT" -> {
                    h.trial.connection(h.trial.token, h.port, true, true)
                    h.trial.services(h.trial.token, h.port, true)
                    h.advanceBy(BleGattSession.SUBSCRIBE_MS)
                }
            }
            if (h.trial.state.status == BleProbeStatus.CLEANING_UP) h.advanceBy(BleGattSession.CLEANUP_MS)
            assertEquals(failure, h.trial.state.reason)
            assertEquals(listOf(1L), h.released)
            h.advanceBy(1_999)
            assertEquals(1L, h.policy.attemptCount)
            h.advanceBy(1)
            assertEquals(2L, h.policy.attemptCount)
            assertEquals(40_000L, h.policy.attemptDeadline)
        }
    }

    @Test fun lostPositiveSourceDuringGraceCannotIssueRetryFromTimer() {
        val h = Consumer(BleFieldConfig.IMPROVED)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        h.failConnection()
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
        h.advanceBy(2_000)
        assertTrue(h.policy.present)
        assertNull(h.policy.currentAttempt)
        assertEquals(1L, h.policy.attemptCount)
        assertTrue(h.evidence.any { it.kind == BleEvidenceKind.RETRY_SUPPRESSED && it.gate == BleEvidenceGate.UNAVAILABLE })
        h.advanceBy(28_000)
        assertFalse(h.policy.present)
        assertEquals(1L, h.policy.attemptCount)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        assertEquals(2L, h.policy.candidateCount)
        assertEquals(2L, h.policy.attemptCount)
    }

    @Test fun baselineObservationOnlyPreservesImmediateDepartureWithoutGattOrBtAssist() {
        val h = Consumer(BleFieldConfig.BASELINE.copy(observationOnly = true))
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        assertEquals(0L, h.policy.candidateCount)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        assertTrue(h.policy.present)
        h.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED)
        assertFalse(h.policy.present)
        h.advanceBy(40_000)
        assertEquals(0L, h.policy.attemptCount)
        assertTrue(h.ports.isEmpty())
        assertFalse(h.policy.localGattActive)
    }

    @Test fun evidenceFailureStoppingCandidateCannotIssueGattOrRearm() {
        val h = Consumer(BleFieldConfig.IMPROVED, onEvidence = { consumer, event ->
            if (event.kind == BleEvidenceKind.CANDIDATE_SIGNAL) consumer.stop()
        })
        h.signal(BleEvidenceSignal.BT_CONNECTED)
        assertFalse(h.policy.accepting)
        assertEquals(0L, h.policy.attemptCount)
        assertNull(h.policy.currentAttempt)
        assertTrue(h.ports.isEmpty())
        h.advanceBy(200_000)
        h.signal(BleEvidenceSignal.CDM_BLE_APPEARED)
        assertEquals(0L, h.policy.attemptCount)
        assertTrue(h.ports.isEmpty())
    }

    /** 정책의 명령을 실제 GATT 세션이 소비한다. 예약 반환은 fake port의 close 성공 뒤에만 일어난다. */
    private class Consumer(config: BleFieldConfig, private val connectAccepted: Boolean = true,
        private val onEvidence: ((Consumer, BleEventEvidence) -> Unit)? = null) {
        var time = 0L
        val evidence = mutableListOf<BleEventEvidence>()
        val policy = BleFieldTrialPolicy(config, { time }) { event ->
            evidence += event
            onEvidence?.invoke(this, event)
        }
        val ports = mutableListOf<Port>()
        val released = mutableListOf<Long>()
        lateinit var trial: BleGattSession
        val port: Port get() = ports.last()

        fun signal(signal: BleEvidenceSignal, received: Long = time) = consume(policy.signal(signal, received))

        fun consume(decision: BleFieldTrialPolicy.Decision) {
            decision.cancelAttempt?.let { if (::trial.isInitialized && trial.token == it) trial.cancel("DEPARTED") }
            decision.startAttempt?.let { attempt ->
                val owner = Port(connectAccepted || attempt > 1)
                ports += owner
                trial = BleGattSession(attempt, checkNotNull(policy.attemptDeadline), owner, { time }, {},
                    release = { released += attempt },
                    finishedCallback = { state ->
                        if (state.localClosed) assertTrue(released.contains(attempt))
                        consume(policy.finished(attempt, state, state.localClosed))
                    })
                trial.start()
            }
        }

        fun advanceTo(at: Long) {
            check(at >= time)
            time = at
            if (::trial.isInitialized) trial.tick()
            consume(policy.tick())
        }

        fun advanceBy(ms: Long) = advanceTo(time + ms)

        fun failConnection() = trial.connection(trial.token, port, false, false, 133)

        fun complete() {
            trial.connection(trial.token, port, true, true)
            trial.services(trial.token, port, true)
            trial.descriptor(trial.token, port, true, true)
            advanceBy(BleGattSession.OBSERVE_MS)
            trial.descriptor(trial.token, port, false, true)
            trial.connection(trial.token, port, true, false)
            assertEquals(BleProbeStatus.COMPLETE, trial.state.status)
        }

        fun stop() {
            policy.stop()?.let { if (::trial.isInitialized && trial.token == it) trial.cancel("USER_STOP") }
        }
    }

    private class Port(private val connectAccepted: Boolean) : BleGattPort {
        var failClose = false
        var connectCalls = 0
        var profile = BleGattProfile(true, true, true, true, BleSubscriptionMode.NOTIFY)
        override fun connect(): Boolean { connectCalls++; return connectAccepted }
        override fun discoverServices() = true
        override fun profile() = profile
        override fun setNotifications(enabled: Boolean) = true
        override fun writeSubscription(enabled: Boolean) = true
        override fun disconnect() {}
        override fun close() { if (failClose) error("단말 close 실패") }
    }
}
