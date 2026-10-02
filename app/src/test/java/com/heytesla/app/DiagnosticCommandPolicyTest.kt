package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test

class DiagnosticCommandPolicyTest {
    private class CountingGateway(private val result: DiagnosticCommandResult = DiagnosticCommandResult.PROCESSED) : VehicleGateway {
        var calls = 0
        override fun dryRunFrunkOpen(): DiagnosticCommandResult { calls++; return result }
    }

    @Test fun repeatedFinalCandidateExecutesOnlyOnce() {
        val policy = DiagnosticCommandPolicy(100)
        val gateway = CountingGateway()
        assertEquals(DiagnosticCommandResult.PROCESSED, policy.decide(1, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway))
        policy.decide(2, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway)
        policy.decide(3, true, SpeechCommandDecision.COMMAND_CANCELED, gateway)
        assertEquals(1, gateway.calls)
        assertEquals(DiagnosticCommandResult.PROCESSED, policy.result)
    }

    @Test fun deadlineIsExclusiveAndExpiryCannotBeRetried() {
        val before = CountingGateway()
        assertEquals(DiagnosticCommandResult.PROCESSED,
            DiagnosticCommandPolicy(100).decide(99, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, before))
        assertEquals(1, before.calls)
        for (now in listOf(100L, 101L)) {
            val policy = DiagnosticCommandPolicy(100)
            val gateway = CountingGateway()
            assertEquals(DiagnosticCommandResult.EXPIRED, policy.decide(now, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway))
            policy.decide(99, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway)
            assertEquals(0, gateway.calls)
            assertEquals(DiagnosticCommandResult.EXPIRED, policy.result)
        }
    }

    @Test fun canceledSessionRejectsLateCandidate() {
        val policy = DiagnosticCommandPolicy(100)
        val gateway = CountingGateway()
        policy.cancel()
        assertEquals(DiagnosticCommandResult.CANCELED, policy.decide(2, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway))
        assertEquals(0, gateway.calls)
    }

    @Test fun unknownResultIsNeverRetried() {
        val policy = DiagnosticCommandPolicy(100)
        val gateway = CountingGateway(DiagnosticCommandResult.UNKNOWN)
        assertEquals(DiagnosticCommandResult.UNKNOWN, policy.decide(1, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway))
        policy.cancel()
        policy.decide(2, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway)
        assertEquals(1, gateway.calls)
        assertEquals(DiagnosticCommandResult.UNKNOWN, policy.result)
    }

    @Test fun exceptionAfterAttemptRemainsUnknownAndIsNotRetried() {
        val policy = DiagnosticCommandPolicy(100)
        var calls = 0
        val gateway = VehicleGateway { calls++; throw IllegalStateException() }
        assertEquals(DiagnosticCommandResult.UNKNOWN, policy.decide(1, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway))
        policy.decide(2, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway)
        assertEquals(1, calls)
    }

    @Test fun everyNonCandidateAndMissingFinalNeverExecutes() {
        val rejected = SpeechCommandDecision.entries.filter { it != SpeechCommandDecision.FRUNK_OPEN_CANDIDATE } + null
        for (decision in rejected) {
            val policy = DiagnosticCommandPolicy(100)
            val gateway = CountingGateway()
            assertEquals(DiagnosticCommandResult.REJECTED, policy.decide(1, true, decision, gateway))
            policy.decide(2, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway)
            assertEquals(0, gateway.calls)
        }
        val gateway = CountingGateway()
        assertEquals(DiagnosticCommandResult.REJECTED,
            DiagnosticCommandPolicy(100).decide(1, false, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway))
        assertEquals(0, gateway.calls)
    }

    @Test fun cancelAndExpiryAfterLocalProcessingDoNotClaimItWasNotExecuted() {
        val policy = DiagnosticCommandPolicy(100)
        val gateway = CountingGateway()
        policy.decide(1, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway)
        policy.cancel()
        assertEquals(DiagnosticCommandResult.PROCESSED, policy.decide(100, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway))
        assertEquals(1, gateway.calls)
    }

    @Test fun reentrantFinalDuringGatewayCallCannotStartAnotherAttempt() {
        val policy = DiagnosticCommandPolicy(100)
        val nested = CountingGateway()
        val gateway = VehicleGateway {
            assertEquals(DiagnosticCommandResult.UNKNOWN, policy.decide(2, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, nested))
            DiagnosticCommandResult.PROCESSED
        }
        assertEquals(DiagnosticCommandResult.PROCESSED, policy.decide(1, true, SpeechCommandDecision.FRUNK_OPEN_CANDIDATE, gateway))
        assertEquals(0, nested.calls)
    }
}
