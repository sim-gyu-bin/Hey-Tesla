package com.heytesla.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPcmRecognizerTest {
    private fun h(text: String, confidence: Float? = null) = SttHypothesis(text, confidence)

    @Test fun singleSegmentKeepsEngineOrderAndCapsHypotheses() {
        val combined = AndroidPcmRecognizer.combine(listOf(listOf(
            h("헤이 테슬라 브렁크 열어 줘", 0f), h(" "), h("헤이 테슬라 프렁크 열어 줘", 0f),
            h("해이 테슬라 브렁크 열어 줘", 0f), h("버려지는 넷째 후보", 0f),
        )))
        assertEquals(listOf("헤이 테슬라 브렁크 열어 줘", "헤이 테슬라 프렁크 열어 줘", "해이 테슬라 브렁크 열어 줘"),
            AndroidPcmRecognizer.texts(combined))
    }

    @Test fun segmentsCombineByRankSumThenEarlierSegmentRank() {
        val combined = AndroidPcmRecognizer.combine(listOf(
            listOf(h("헤이 테슬라 "), h("페이 테슬라")),
            listOf(h("브렁크 열어 줘"), h("프렁크 열어 줘")),
        ))
        assertEquals(listOf(
            "헤이 테슬라 브렁크 열어 줘",
            "헤이 테슬라 프렁크 열어 줘",
            "페이 테슬라 브렁크 열어 줘",
            "페이 테슬라 프렁크 열어 줘",
        ), AndroidPcmRecognizer.texts(combined))
    }

    @Test fun combinedScoreIsWeakestPartAndMissingPartMakesItUnavailable() {
        val combined = AndroidPcmRecognizer.combine(listOf(
            listOf(h("헤이 테슬라", 0.9f), h("페이 테슬라", null)),
            listOf(h("프렁크 열어 줘", 0.95f)),
        ))
        assertEquals(0.9f, combined[0].confidence)
        assertNull(combined[1].confidence)
    }

    @Test fun moreThanThreeSegmentsJoinOnlyTopHypotheses() {
        val combined = AndroidPcmRecognizer.combine(listOf("헤이", "테슬라", "프렁크", "열어 줘").map { word ->
            listOf(h(word, 0f), h("다른$word", 0f))
        })
        assertEquals(listOf("헤이 테슬라 프렁크 열어 줘"), AndroidPcmRecognizer.texts(combined))
        assertEquals(0f, combined.single().confidence)
    }

    @Test fun blankOrMissingSegmentsYieldNoCandidate() {
        assertTrue(AndroidPcmRecognizer.combine(emptyList()).isEmpty())
        assertTrue(AndroidPcmRecognizer.combine(listOf(emptyList(), listOf(h(" "), h("\n")))).isEmpty())
        assertEquals(listOf("프렁크 열어 줘"), AndroidPcmRecognizer.texts(
            AndroidPcmRecognizer.combine(listOf(emptyList(), listOf(h("프렁크 열어 줘"))))))
    }

    @Test fun scoreArrayIsAbsentOnlyWhenEveryCandidateLacksScore() {
        assertNull(AndroidPcmRecognizer.scores(listOf(h("a"), h("b"))))
        val partial = requireNotNull(AndroidPcmRecognizer.scores(listOf(h("a", 0.9f), h("b"), h("c", -1f))))
        assertEquals(0.9f, partial[0])
        assertTrue(partial[1].isNaN())
        assertEquals(-1f, partial[2])
        assertArrayEquals(floatArrayOf(0f, 0f), AndroidPcmRecognizer.scores(listOf(h("a", 0f), h("b", 0f))), 0f)
    }

    @Test fun observedS23HypothesesReachPolicyWithoutScoreGate() {
        val combined = AndroidPcmRecognizer.combine(listOf(listOf(
            h("헤이 테슬라 브렁크 열어 줘", 0f), h("헤이 테슬라 프렁크 열어 줘", 0f), h("해이 테슬라 브렁크 열어 줘", 0f),
        )))
        val gate = FrunkVoicePolicy(40_000, 0)
        assertTrue(gate.command(1_000, SpeechTrialMode.DIRECT_MIC, true,
            AndroidPcmRecognizer.texts(combined), AndroidPcmRecognizer.scores(combined)))
        assertNull(gate.acceptedConfidence)
    }
}
