package com.heytesla.app.speech

import org.junit.Assert.*
import org.junit.Test

/**
 * 규칙 기반 문장 판정의 경계 동작을 검증한다.
 *
 * 여기서 나온 값은 진단 표시용이며 실제 차량 전송 판단이 아니다. 순수 JVM 코드라 계측 없이 돈다.
 */
class SpeechCommandDecisionTest {
    @Test fun punctuationAndWhitespaceDoNotChangeCommandDecision() {
        assertEquals(SpeechCommandDecision.FRUNK_OPEN_CANDIDATE,
            SpeechCommandParser.decide(" 헤이, 테슬라! 프렁크 열어줘. "))
        assertEquals(SpeechCommandDecision.COMMAND_UNSUPPORTED,
            SpeechCommandParser.decide("헤이 테슬라1 프렁크 열어줘"))
    }

    @Test fun exactWakeWordAndCommandIsCandidateOnly() {
        assertEquals(
            SpeechCommandDecision.FRUNK_OPEN_CANDIDATE,
            SpeechCommandParser.decide("헤이 테슬라 프렁크 열어줘"),
        )
    }

    @Test fun missingOrMidSentenceWakeWordIsRejected() {
        assertEquals(SpeechCommandDecision.WAKE_MISSING, SpeechCommandParser.decide("프렁크 열어줘"))
        assertEquals(SpeechCommandDecision.WAKE_MISSING, SpeechCommandParser.decide("헤이태진아 프렁크 열어줘"))
        assertEquals(SpeechCommandDecision.WAKE_MISSING, SpeechCommandParser.decide("오늘 헤이테슬라 열어줘"))
        // 회상·추측 문장은 호출어가 문장 시작이 아니면 명령으로 승격하지 않는다.
        assertEquals(SpeechCommandDecision.WAKE_MISSING, SpeechCommandParser.decide("아까 프렁크 열어달라고 했잖아"))
        assertEquals(SpeechCommandDecision.WAKE_MISSING, SpeechCommandParser.decide(""))
    }

    @Test fun wakeWordWithoutCommandIsMissing() {
        assertEquals(SpeechCommandDecision.COMMAND_MISSING, SpeechCommandParser.decide("헤이테슬라"))
        assertEquals(SpeechCommandDecision.COMMAND_MISSING, SpeechCommandParser.decide("헤이 테슬라 ..."))
    }

    @Test fun unsupportedCommandAfterWakeWordIsRejected() {
        assertEquals(SpeechCommandDecision.COMMAND_UNSUPPORTED, SpeechCommandParser.decide("헤이테슬라 문 열어줘"))
        // 허용 명령 뒤에 글자가 더 붙으면 부분 채택하지 않는다.
        assertEquals(SpeechCommandDecision.COMMAND_UNSUPPORTED, SpeechCommandParser.decide("헤이테슬라프렁크열어줘요"))
        assertEquals(SpeechCommandDecision.COMMAND_UNSUPPORTED, SpeechCommandParser.decide("헤이테슬라프렁크열어"))
    }

    @Test fun cancelMarkerBeatsCommand() {
        assertEquals(SpeechCommandDecision.COMMAND_CANCELED, SpeechCommandParser.decide("헤이테슬라 프렁크 열지마"))
        assertEquals(SpeechCommandDecision.COMMAND_CANCELED, SpeechCommandParser.decide("헤이테슬라 취소"))
        assertEquals(SpeechCommandDecision.COMMAND_CANCELED, SpeechCommandParser.decide("헤이테슬라프렁크열어줘취소"))
    }

    @Test fun overLengthSentenceHasNoDecision() {
        val tooLong = SpeechCommandPhrases.normalizedWakeWord + "가".repeat(SpeechCommandParser.MAX_NORMALIZED_LENGTH)
        assertNull(SpeechCommandParser.decide(tooLong))
    }
}
