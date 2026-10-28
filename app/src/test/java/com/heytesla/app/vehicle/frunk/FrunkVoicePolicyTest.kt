package com.heytesla.app.vehicle.frunk

import com.heytesla.app.speech.SpeechTrialMode

import org.junit.Assert.*
import org.junit.Test

/** 실차 호출 없이 승인·취소·전송 입장의 소비 경계를 검증한다. */
class FrunkVoicePolicyTest {
    private fun policy(deadline: Long = 40_000L) = FrunkVoicePolicy(deadline, 0L)
    private fun command(gate: FrunkVoicePolicy, now: Long = 1_000L,
        source: SpeechTrialMode = SpeechTrialMode.DIRECT_MIC, final: Boolean = true,
        text: String = "헤이 테슬라 프렁크 열어줘", score: Float? = 0.95f): Boolean =
        gate.command(now, source, final, listOf(text), score?.let { floatArrayOf(it) })
    private fun accepted(): FrunkVoicePolicy = policy().also {
        assertTrue(command(it))
    }

    @Test fun exactFinalCommandAuthorizesOneDispatchAfterAudioRelease() {
        val gate = accepted()
        assertEquals(FrunkVoicePolicy.Phase.ACCEPTED, gate.phase)
        assertEquals(1_000L, gate.commandAcceptedAtElapsedMs)
        assertTrue(gate.takeForDispatch(1_001, inputReleased = true, cleanupConfirmed = true))
        assertEquals(FrunkVoicePolicy.Phase.DISPATCHED, gate.phase)
    }

    @Test fun partialCommandNeverAuthorizesButFinalCommandCan() {
        val gate = policy()
        assertFalse(command(gate, final = false))
        assertNull(gate.commandAcceptedAtElapsedMs)
        assertFalse(gate.takeForDispatch(1_001, true, cleanupConfirmed = true))
        assertTrue(command(gate, now = 2_000))
        assertTrue(gate.takeForDispatch(2_001, true, cleanupConfirmed = true))
    }

    @Test fun dispatchBeforeCommandCannotAuthorize() {
        val gate = policy()
        assertFalse(gate.takeForDispatch(1_000, true, cleanupConfirmed = true))
        assertNull(gate.commandAcceptedAtElapsedMs)
        assertTrue(command(gate, now = 3_000))
        assertTrue(gate.takeForDispatch(3_001, true, cleanupConfirmed = true))
    }

    @Test fun everyNonDirectInputIsRejected() {
        listOf(SpeechTrialMode.TTS_PCM, SpeechTrialMode.SILENT_PCM, SpeechTrialMode.BUFFERED_PCM).forEach { source ->
            val gate = policy()
            assertFalse(command(gate, source = source))
            assertFalse(gate.takeForDispatch(2_001, true, cleanupConfirmed = true))
            assertEquals("FRUNK_VOICE_SOURCE_BLOCKED", gate.reason)
        }
    }

    @Test fun observedScoreMustBeFiniteAndAtLeastThreshold() {
        listOf(0.849f, 1.01f).forEach { score ->
            val gate = policy()
            assertFalse(command(gate, score = score))
            assertFalse(gate.takeForDispatch(2_001, true, cleanupConfirmed = true))
            assertEquals("FRUNK_VOICE_CONFIDENCE_BLOCKED", gate.reason)
        }
        val boundary = policy()
        assertTrue(command(boundary, score = FrunkVoicePolicy.MIN_CONFIDENCE))
        assertEquals(FrunkVoicePolicy.MIN_CONFIDENCE, boundary.acceptedConfidence)
        assertTrue(boundary.takeForDispatch(2_001, true, cleanupConfirmed = true))
    }

    @Test fun unavailableScoreSkipsGateButIsReportedAsUnavailable() {
        listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { score ->
            val gate = policy()
            assertTrue(command(gate, score = score))
            assertNull(gate.acceptedConfidence)
            assertTrue(gate.takeForDispatch(2_001, true, cleanupConfirmed = true))
        }
    }

    @Test fun anyExactHypothesisMatchesButItsOwnObservedScoreDecides() {
        val lower = policy()
        assertTrue(lower.command(1_000, SpeechTrialMode.DIRECT_MIC, true,
            listOf("해이 테슬라 브렁크 열어 줘", "헤이 테슬라 프렁크 열어 줘"), floatArrayOf(0f, 0f)))
        assertNull(lower.acceptedConfidence)
        val scored = policy()
        assertFalse(scored.command(1_000, SpeechTrialMode.DIRECT_MIC, true,
            listOf("해이 테슬라 브렁크 열어 줘", "헤이 테슬라 프렁크 열어 줘"), floatArrayOf(0.99f, 0.5f)))
        assertEquals("FRUNK_VOICE_CONFIDENCE_BLOCKED", scored.reason)
        val passing = policy()
        assertTrue(passing.command(1_000, SpeechTrialMode.DIRECT_MIC, true,
            listOf("해이 테슬라 브렁크 열어 줘", "헤이 테슬라 프렁크 열어 줘"), floatArrayOf(0.5f, 0.9f)))
        assertEquals(0.9f, passing.acceptedConfidence)
    }

    @Test fun cancelWordInAnyHypothesisStopsBeforeMatch() {
        listOf("헤이테슬라프렁크열지마", "헤이 테슬라 프렁크 열어 줘 취소").forEach { lower ->
            val gate = policy()
            assertFalse(gate.command(1_000, SpeechTrialMode.DIRECT_MIC, true,
                listOf("헤이테슬라프렁크열어줘", lower), floatArrayOf(0.99f, 0.99f)))
            assertEquals("FRUNK_VOICE_CANCELED", gate.reason)
            assertFalse(gate.takeForDispatch(1_001, true, cleanupConfirmed = true))
        }
    }

    @Test fun malformedObservedScoresAndMissingCandidatesCannotAuthorize() {
        listOf(floatArrayOf(0.99f, 0.99f), floatArrayOf(0.99f, 0.99f, 0.99f)).forEach { scores ->
            val gate = policy()
            assertFalse(gate.command(1_000, SpeechTrialMode.DIRECT_MIC, true,
                listOf("헤이테슬라프렁크열어줘"), scores))
            assertEquals("FRUNK_VOICE_CONFIDENCE_BLOCKED", gate.reason)
            assertFalse(gate.takeForDispatch(2_001, true, cleanupConfirmed = true))
        }
        listOf(null, emptyList<String>()).forEach { candidates ->
            val missing = policy()
            assertFalse(missing.command(1_000, SpeechTrialMode.DIRECT_MIC, true, candidates, floatArrayOf(0.99f)))
            assertEquals("FRUNK_VOICE_GRAMMAR_BLOCKED", missing.reason)
            assertFalse(missing.takeForDispatch(1_001, true, cleanupConfirmed = true))
        }
    }

    @Test fun observedAcousticVariantsAreNotPhoneticallyMerged() {
        listOf("페이 테슬라 프렁크 열어 줘", "해이 테슬라 프렁크 열어 줘", "헤이 테슬라 브렁크 열어 줘",
            "헤이 테슬라 프랑크 열어 줘", "헤이 테슬라 트렁크 열어 줘", "a 테슬라 프렁크 열어 줘").forEach { text ->
            val gate = policy()
            assertFalse(command(gate, text = text, score = 0f))
            assertEquals("FRUNK_VOICE_GRAMMAR_BLOCKED", gate.reason)
        }
    }

    @Test fun fullGrammarRejectsNegationRecallExtraWordsAndConfirmation() {
        listOf("프렁크 열어줘", "아까 헤이테슬라 프렁크 열어줘", "헤이테슬라 프렁크 열지마",
            "헤이테슬라 프렁크 열어줘 취소", "헤이테슬라 프렁크 열어줘요",
            "확인", "네", "확인해", "확인 취소", "확인하지 마", "아까 확인", "열어줘", "").forEach { text ->
            val gate = policy()
            assertFalse(command(gate, text = text))
            assertFalse(gate.takeForDispatch(2_001, true, cleanupConfirmed = true))
        }
    }

    @Test fun spokenCancelDoesNotNeedExecutionConfidence() {
        val gate = policy()
        assertFalse(command(gate, text = "취소", score = null))
        assertEquals("FRUNK_VOICE_CANCELED", gate.reason)
        assertFalse(command(gate, now = 2_001))
        assertFalse(gate.takeForDispatch(2_002, true, cleanupConfirmed = true))
    }

    @Test fun cancelAtEveryPreDispatchPhaseSealsLateResultsAndResume() {
        repeat(2) { phase ->
            val gate = policy()
            if (phase >= 1) assertTrue(command(gate))
            gate.cancel()
            assertFalse(command(gate, now = 3_000))
            assertFalse(gate.takeForDispatch(3_002, true, cleanupConfirmed = true))
            assertEquals(FrunkVoicePolicy.Phase.STOPPED, gate.phase)
        }
    }

    @Test fun duplicateCommandDoesNotExtendHandoffDeadline() {
        val gate = accepted()
        assertFalse(command(gate, now = 5_999))
        assertEquals(1_000L, gate.commandAcceptedAtElapsedMs)
        assertFalse(gate.takeForDispatch(6_000, true, cleanupConfirmed = true))
        assertEquals("FRUNK_VOICE_EXPIRED", gate.reason)
        assertFalse(gate.takeForDispatch(9_001, true, cleanupConfirmed = true))
    }

    @Test fun commandSessionAndHandoffDeadlinesRejectExactBoundary() {
        val commandExpired = policy()
        assertFalse(command(commandExpired, now = FrunkVoicePolicy.COMMAND_MS))
        assertFalse(commandExpired.takeForDispatch(10_001, true, cleanupConfirmed = true))
        val sessionExpired = policy(deadline = 2_000)
        assertTrue(command(sessionExpired))
        assertFalse(sessionExpired.takeForDispatch(2_000, true, cleanupConfirmed = true))
        val handoffExpired = accepted()
        assertFalse(handoffExpired.takeForDispatch(1_000 + FrunkVoicePolicy.HANDOFF_MS, true, cleanupConfirmed = true))
        assertEquals("FRUNK_VOICE_EXPIRED", handoffExpired.reason)
        val beforeBoundary = accepted()
        assertTrue(beforeBoundary.takeForDispatch(1_000 + FrunkVoicePolicy.HANDOFF_MS - 1, true, cleanupConfirmed = true))
    }

    @Test fun microphoneAndRecognizerCleanupMustBothBeConfirmedBeforeDispatch() {
        for ((inputReleased, cleanupConfirmed) in listOf(
            false to true, false to false, false to null, true to false, true to null,
        )) {
            val gate = accepted()
            assertFalse(gate.takeForDispatch(2_001, inputReleased, cleanupConfirmed))
            assertEquals(FrunkVoicePolicy.Phase.STOPPED, gate.phase)
            assertEquals("FRUNK_VOICE_CLEANUP_FAILED", gate.reason)
            assertEquals(1_000L, gate.commandAcceptedAtElapsedMs)
            assertFalse(gate.takeForDispatch(2_002, inputReleased = true, cleanupConfirmed = true))
        }
    }

    @Test fun cleanupUncertaintyDoesNotRewriteAnEarlierCancelOrPermitResume() {
        val gate = accepted()
        gate.cancel()
        assertFalse(gate.takeForDispatch(2_001, inputReleased = true, cleanupConfirmed = null))
        assertEquals("FRUNK_VOICE_CANCELED", gate.reason)
        assertFalse(gate.takeForDispatch(2_002, inputReleased = true, cleanupConfirmed = true))
    }

    @Test fun clockRegressionCannotBeRetried() {
        val clock = accepted()
        assertFalse(clock.takeForDispatch(999, true, cleanupConfirmed = true))
        assertFalse(clock.takeForDispatch(2_001, true, cleanupConfirmed = true))
    }

    @Test fun dispatchIsOnceOnlyAndPostDispatchCancelDoesNotRewriteItAsUnsent() {
        val gate = accepted()
        assertTrue(gate.takeForDispatch(2_001, true, cleanupConfirmed = true))
        gate.cancel()
        assertFalse(gate.takeForDispatch(2_002, true, cleanupConfirmed = true))
        assertFalse(command(gate, now = 2_003))
        assertEquals(FrunkVoicePolicy.Phase.DISPATCHED, gate.phase)
        assertEquals(1_000L, gate.commandAcceptedAtElapsedMs)
        val recreated = policy()
        assertFalse(recreated.takeForDispatch(2_005, true, cleanupConfirmed = true))
    }
}
