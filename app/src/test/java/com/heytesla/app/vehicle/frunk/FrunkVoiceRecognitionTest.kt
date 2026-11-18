package com.heytesla.app.vehicle.frunk

import com.heytesla.app.speech.SpeechTrialMode

import org.junit.Assert.*
import org.junit.Test

class FrunkVoiceRecognitionTest {
    private fun capture(text: String, score: Float? = 0.9f): FrunkVoiceRecognitionCandidate =
        FrunkVoiceRecognition.capture(listOf(text), score?.let { floatArrayOf(it) },
            accepted = false, reason = "FRUNK_VOICE_GRAMMAR_BLOCKED").candidates.single()

    @Test fun commandCancelNegationAndTrunkConfusionRemainDistinguishable() {
        listOf("헤이 테슬라 프렁크 열어줘", "헤이테슬라프렁크열어줘", "취소",
            "헤이테슬라 프렁크 열지마", "헤이 테슬라 트렁크 열어줘",
            "프렁크 열어줘 말고 취소해줘", "하지 마", "안돼").forEach { text ->
            val candidate = capture(text)
            assertEquals(text, candidate.text)
            assertFalse(candidate.masked)
            assertFalse(candidate.truncated)
        }
    }

    @Test fun identifiersAndKnownWordsEmbeddedInLargerTokensAreEntirelyMasked() {
        listOf("가상사용자", "010-0000-0000", "123", "SYNTHETIC-ID", "테슬라@example.invalid",
            "김프렁크", "프렁크님", "프렁크123", "헤이테슬라프렁크열어줘개인",
            "token=프렁크", "프렁크\u200B열어줘", "열어줘.", "[프렁크]", "프렁크,가상사용자").forEach { token ->
            val candidate = capture(token)
            assertEquals(FrunkVoiceRecognition.MASK, candidate.text)
            assertTrue(candidate.masked)
            assertFalse(candidate.truncated)
        }
        val mixed = capture("헤이 테슬라 가상사용자 프렁크 열어줘 010-0000-0000")
        assertEquals("헤이 테슬라 [마스킹] 프렁크 열어줘 [마스킹]", mixed.text)
        assertTrue(mixed.masked)
        assertEquals(0.9f, mixed.confidence)
    }

    @Test fun whitespaceIsCanonicalizedWithoutPersistingControlCharacters() {
        val candidate = capture("\t헤이\n테슬라\r\n프렁크\u00a0열어줘  ")
        assertEquals("헤이 테슬라 프렁크 열어줘", candidate.text)
        assertFalse(candidate.masked)
        assertEquals("", capture(" \n\t").text)
    }

    @Test fun lengthBoundNeverExposesPrefixOfOversizedTokenAndAlsoBoundsMaskExpansion() {
        val atLimit = "프렁크" + "가".repeat(FrunkVoiceRecognition.MAX_TEXT_LENGTH - 3)
        assertEquals(FrunkVoiceRecognition.MASK, capture(atLimit).text)
        assertFalse(capture(atLimit).truncated)
        val oversized = capture(atLimit + "가")
        assertEquals(FrunkVoiceRecognition.MASK, oversized.text)
        assertTrue(oversized.masked)
        assertTrue(oversized.truncated)
        val safeAtLimit = "안 ".repeat(FrunkVoiceRecognition.MAX_TEXT_LENGTH / 2)
        assertEquals(safeAtLimit.trimEnd(), capture(safeAtLimit).text)
        assertFalse(capture(safeAtLimit).truncated)
        val expanded = capture("가 ".repeat(40))
        assertEquals(FrunkVoiceRecognition.MASK, expanded.text)
        assertTrue(expanded.truncated)
    }

    @Test fun candidateLimitAndConfidenceUnknownDoNotMergeAlternativesOrInventScores() {
        val result = FrunkVoiceRecognition.capture(
            listOf("프렁크", "트렁크", "취소", "가상사용자"), floatArrayOf(0.9f, Float.NaN),
            accepted = false, reason = "FRUNK_VOICE_GRAMMAR_BLOCKED")
        assertEquals(listOf("프렁크", "트렁크", "취소"), result.candidates.map { it.text })
        assertEquals(listOf(0.9f, null, null), result.candidates.map { it.confidence })
        assertTrue(result.candidatesTruncated)
        listOf(null, -1f, 1.01f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY).forEach {
            assertNull(capture("취소", it).confidence)
        }
        listOf(0f, 0.85f, 1f).forEach { assertEquals(it, capture("프렁크", it).confidence) }
        val empty = FrunkVoiceRecognition.capture(null, null, false, "FRUNK_VOICE_GRAMMAR_BLOCKED")
        assertTrue(empty.candidates.isEmpty())
        assertFalse(empty.candidatesTruncated)
    }

    @Test fun originalCandidatesDeterminePolicyEvenWhenLogMasksUnknownWords() {
        val original = listOf("헤이 테슬라 가상사용자 프렁크 열어줘")
        val scores = floatArrayOf(0.99f)
        val gate = FrunkVoicePolicy(40_000, 0)
        val accepted = gate.command(1_000, SpeechTrialMode.DIRECT_MIC, true, original, scores)
        val result = FrunkVoiceRecognition.capture(original, scores, accepted, gate.reason)
        assertFalse(result.accepted)
        assertEquals("FRUNK_VOICE_GRAMMAR_BLOCKED", result.reason)
        assertEquals("헤이 테슬라 [마스킹] 프렁크 열어줘", result.candidates.single().text)
        assertFalse(gate.takeForDispatch(1_001, true, cleanupConfirmed = true))
    }
}
