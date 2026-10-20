package com.heytesla.app

/** 실행 정책과 분리된 실차 시험 로그 전용 값. 저장 직전에도 다시 정제한다. */
data class FrunkVoiceRecognitionCandidate(
    val text: String,
    val confidence: Float?,
    val masked: Boolean = false,
    val truncated: Boolean = false,
)

data class FrunkVoiceRecognitionResult(
    val candidates: List<FrunkVoiceRecognitionCandidate>,
    val accepted: Boolean,
    val reason: String?,
    val candidatesTruncated: Boolean = false,
)

/** 명시 동의로 시작한 실차 세션의 유효 최종 callback에서만 사용한다. 일반 STT용이 아니다. */
internal object FrunkVoiceRecognition {
    const val EVENT = "FRUNK_VOICE_RECOGNITION_RESULT"
    const val MAX_CANDIDATES = 3
    const val MAX_TEXT_LENGTH = 160
    const val MASK = "[마스킹]"

    // 공백 단위 전체 토큰만 비교한다. 접미사·숫자·식별자·구두점이 붙으면 토큰 전체를 가린다.
    // 붙여 말한 문장도 아래에 명시한 고정 시험 문법만 보존하며 부분문자열을 추출하지 않는다.
    private val vocabulary = setOf(
        "헤이", "테슬라", "헤이테슬라", "프렁크", "열어줘", "프렁크열어줘", "헤이테슬라프렁크열어줘",
        "취소", "취소해", "취소해줘", "아니", "아니야", "안", "안돼", "말고",
        "하지", "마", "하지마", "열지", "열지마", "마세요", "열지마세요", "열지말아줘",
        "프렁크열지마", "헤이테슬라프렁크열지마", "트렁크", "트렁크열어줘", "헤이테슬라트렁크열어줘",
    )
    private val reasons = setOf(
        "FRUNK_VOICE_SOURCE_BLOCKED", "FRUNK_VOICE_GRAMMAR_BLOCKED", "FRUNK_VOICE_CANCELED",
        "FRUNK_VOICE_CONFIDENCE_BLOCKED", "FRUNK_VOICE_EXPIRED", "FRUNK_VOICE_FINAL_REQUIRED",
        "FRUNK_VOICE_READINESS_LOST", "FRUNK_VOICE_RECOGNITION_FAILED", "FRUNK_VOICE_CLEANUP_FAILED",
    )

    fun capture(candidates: List<String>?, scores: FloatArray?, accepted: Boolean,
        reason: String?): FrunkVoiceRecognitionResult = FrunkVoiceRecognitionResult(
        candidates = List(minOf(candidates?.size ?: 0, MAX_CANDIDATES)) { index ->
            sanitize(FrunkVoiceRecognitionCandidate(candidates!![index], scores?.getOrNull(index)))
        },
        accepted = accepted,
        reason = reason?.takeIf { it in reasons },
        candidatesTruncated = (candidates?.size ?: 0) > MAX_CANDIDATES,
    )

    /** 호출자의 flags를 신뢰하지 않고, 원문을 직접 받아도 저장 경계에서 전체 토큰을 검사한다. */
    private fun sanitize(candidate: FrunkVoiceRecognitionCandidate): FrunkVoiceRecognitionCandidate {
        val confidence = candidate.confidence?.takeIf { it.isFinite() && it in 0f..1f }
        val text = candidate.text
        if (text.length > MAX_TEXT_LENGTH) {
            // 앞부분만 자르면 긴 식별자 안의 허용 어휘가 노출되므로 문장 전체를 가린다.
            return candidate.copy(text = MASK, confidence = confidence, masked = true, truncated = true)
        }
        var masked = candidate.masked
        val safe = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index].isWhitespace()) { index++; continue }
            val start = index
            while (index < text.length && !text[index].isWhitespace()) index++
            val token = text.substring(start, index)
            val kept = token in vocabulary
            if (!kept) masked = true
            val replacement = if (kept) token else MASK
            val separator = if (safe.isEmpty()) 0 else 1
            if (safe.length + separator + replacement.length > MAX_TEXT_LENGTH) {
                return candidate.copy(text = MASK, confidence = confidence, masked = true, truncated = true)
            }
            if (separator != 0) safe.append(' ')
            safe.append(replacement)
        }
        return candidate.copy(text = safe.toString(), confidence = confidence, masked = masked)
    }

    /** FieldJson의 영속화 전용이다. 전용 이벤트 외의 첨부 차단은 FieldJson이 담당한다. */
    fun encode(result: FrunkVoiceRecognitionResult): String = buildString(1024) {
        append("{\"candidates\":[")
        for (index in 0 until minOf(result.candidates.size, MAX_CANDIDATES)) {
            if (index != 0) append(',')
            val safe = sanitize(result.candidates[index])
            append("{\"text\":").append(FieldJson.quote(safe.text))
            append(",\"confidence\":").append(safe.confidence?.toString() ?: "null")
            append(",\"masked\":").append(safe.masked)
            append(",\"truncated\":").append(safe.truncated).append('}')
        }
        append("],\"candidatesTruncated\":").append(result.candidatesTruncated || result.candidates.size > MAX_CANDIDATES)
        append(",\"policy\":{\"accepted\":").append(result.accepted)
        append(",\"reason\":").append(result.reason?.takeIf { it in reasons }?.let { FieldJson.quote(it) } ?: "null")
        append("}}")
    }
}
