package com.heytesla.app

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
 * 호출어는 **문장 시작의 정확한 '헤이 테슬라'** 만 허용한다. 공백·구두점 차이는 정규화로 흡수하지만
 * 유사 발음·문장 중간 문구·추측은 거절한다. 호출어 뒤 허용 명령도 정확한 '프렁크 열어줘' 하나뿐이며
 * 그 뒤에 글자가 더 붙으면 허용으로 승격하지 않는다.
 *
 * 판정에는 인식 원문을 담지 않으므로 호출부가 결과를 그대로 영속화해도 원문은 새지 않는다.
 */
internal object SpeechCommandParser {
    const val WAKE_WORD = "헤이테슬라"
    const val FRUNK_COMMAND = "프렁크열어줘"

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

    /** 이미 [normalize]한 문자열 판정. 조각 누적 경로가 쓴다. */
    fun decideNormalized(normalized: String): SpeechCommandDecision {
        if (!normalized.startsWith(WAKE_WORD)) return SpeechCommandDecision.WAKE_MISSING
        if (CANCEL_MARKERS.any { normalized.indexOf(it, WAKE_WORD.length) >= 0 }) return SpeechCommandDecision.COMMAND_CANCELED
        if (normalized.length == WAKE_WORD.length) return SpeechCommandDecision.COMMAND_MISSING
        return if (normalized.length == WAKE_WORD.length + FRUNK_COMMAND.length &&
            normalized.regionMatches(WAKE_WORD.length, FRUNK_COMMAND, 0, FRUNK_COMMAND.length)) {
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

/**
 * `onSegmentResults`가 이미 [SpeechCommandParser.normalize]한 조각을 순서대로 이어 붙이는 상한 있는 누적기.
 *
 * 정규화 문자 기준 [maxLength]를 넘는 조각은 **넣지 않고** [overflowed]를 세운 뒤 이후 판정을 `null`로
 * 거절한다. 부분 채택·보충은 하지 않는다. 누적 값은 정규화 문자열이므로 그대로 RAM에 남아 있고,
 * 상한을 넘지 않게 유지되며 회차 정리([clear])에서 비운다. 인식 원문·`confidence`는 담지 않는다.
 *
 * RecognitionListener 콜백은 메인 스레드 한 곳에서만 닥치므로 동기화하지 않는다.
 */
internal class SpeechCommandAccumulator(
    private val maxLength: Int = SpeechCommandParser.MAX_NORMALIZED_LENGTH,
) {
    private val builder = StringBuilder()

    var overflowed: Boolean = false
        private set

    /** 이미 정규화한 조각을 붙인다. 호출부가 다시 정규화하지 않도록 정규화는 호출부 한 곳에서만 한다. */
    fun appendNormalized(normalized: String) {
        if (overflowed) return
        if (builder.length + normalized.length > maxLength) {
            overflowed = true
            return
        }
        builder.append(normalized)
    }

    /** 상한을 넘지 않았으면 누적 문장을 판정하고, 넘었으면 판정 없음(`null`)이다. */
    fun decisionOrNull(): SpeechCommandDecision? =
        if (overflowed) null else SpeechCommandParser.decideNormalized(builder.toString())

    /** 회차 정리 뒤 다음 회차로 값이 새지 않게 비운다. */
    fun clear() {
        builder.setLength(0)
        overflowed = false
    }
}
