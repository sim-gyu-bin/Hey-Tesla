package com.heytesla.app.speech

/**
 * 온디바이스 STT 한 문장에서 뽑은 진단용 명령 판정. **실행 허가가 아니며 실제 차량 전송과 무관하다.**
 *
 * - [WAKE_MISSING]: 문장 시작에 정확한 호출어가 없다(유사 발음·문장 중간 호출어·빈 결과 포함).
 * - [COMMAND_MISSING]: 호출어만 있고 뒤에 명령이 없다.
 * - [COMMAND_UNSUPPORTED]: 호출어 뒤에 허용 명령이 아닌 다른 문장·회상·추가글이 있다.
 * - [COMMAND_CANCELED]: 호출어 뒤 문장에 '취소'·'열지마' 부정 표시가 있다.
 * - [FRUNK_OPEN_CANDIDATE]: 정확한 허용 명령 하나뿐이다. 여전히 전송하지 않는 후보다.
 *
 * 이름은 영속 로그 `commandDecision` 키의 allowlist로도 쓰이므로 임의로 바꾸지 않는다.
 */
enum class SpeechCommandDecision {
    WAKE_MISSING, COMMAND_MISSING, COMMAND_UNSUPPORTED, COMMAND_CANCELED, FRUNK_OPEN_CANDIDATE
}

/**
 * 규칙 기반 문장 판정. Android에 의존하지 않는 순수 Kotlin이라 JVM 테스트에서 그대로 돌린다.
 *
 * 호출어는 **문장 시작의 [SpeechCommandPhrases.displayWakeWord]와 정확히 일치**해야 한다.
 * 공백·구두점 차이는 정규화로 흡수하지만 유사 발음·문장 중간 문구·추측은 거절한다.
 * 호출어 뒤 허용 명령도 [SpeechCommandPhrases.displayCommand] 하나뿐이며 뒤에 글자가 더 붙으면 허용으로 승격하지 않는다.
 *
 * 판정에는 인식 원문을 담지 않으므로 호출부가 결과를 그대로 영속화해도 원문은 새지 않는다.
 */
internal object SpeechCommandParser {
    /** 정규화 문자 수 상한. 넘으면 부분 채택·보충 없이 판정을 거절한다. */
    const val MAX_NORMALIZED_LENGTH = 128

    private val CANCEL_MARKERS = listOf("취소", "열지마")

    /** 공백과 구두점만 제거한다. 글자·숫자는 그대로 두어 유사 발음을 합치지 않는다. */
    fun normalize(text: String): String = text.filterNot {
        it.isWhitespace() || Character.getType(it) in PUNCTUATION_TYPES
    }

    /** 한 문장 진단. 상한을 넘으면 `null`(판정 없음)이다. */
    fun decide(sentence: String): SpeechCommandDecision? {
        val normalized = normalize(sentence)
        return if (normalized.length > MAX_NORMALIZED_LENGTH) null else decideNormalized(normalized)
    }

    /** 이미 [normalize]한 인식 후보 문자열을 판정한다. */
    fun decideNormalized(normalized: String): SpeechCommandDecision {
        val wakeWord = SpeechCommandPhrases.normalizedWakeWord
        if (!normalized.startsWith(wakeWord)) return SpeechCommandDecision.WAKE_MISSING
        if (CANCEL_MARKERS.any { normalized.indexOf(it, wakeWord.length) >= 0 }) return SpeechCommandDecision.COMMAND_CANCELED
        if (normalized.length == wakeWord.length) return SpeechCommandDecision.COMMAND_MISSING
        val command = SpeechCommandPhrases.normalizedCommand
        return if (normalized.length == wakeWord.length + command.length &&
            normalized.regionMatches(wakeWord.length, command, 0, command.length)) {
            SpeechCommandDecision.FRUNK_OPEN_CANDIDATE
        } else {
            SpeechCommandDecision.COMMAND_UNSUPPORTED
        }
    }

    private val PUNCTUATION_TYPES = setOf(
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
    )
}
