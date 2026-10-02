package com.heytesla.app

/**
 * 시험 한 회차의 종료 요약. 회차를 끝내는 이벤트 한 줄에만 붙고 시작·게이트·다른 사건에는 붙지 않는다.
 *
 * 모드·상태·사유 이름, 수치, 로컬 dry-run·TTS enum, 입력·출력·포커스 정리 확인만 담는다.
 * PCM·인식 원문·후보 문장·예외 메시지·비밀값을 담을 필드는 없다. 문자열은 저장 직전에
 * [SpeechTrialSummaryJson]이 고정 allowlist로 다시 검사하므로 임의 원문이 파일로 새지 않는다.
 *
 * [phraseMatched]와 [confidence]는 모르는 값을 성공값으로 채우지 않기 위해 nullable로 둔다.
 * [elapsedMs]는 시험을 시작한 회차에만 값이 있고 시작 전 거절에는 null이다.
 *
 * [decision]은 차량 허가가 아닌 최종 인식 판정이다. 최종 없는 회차·시작 전 거절에는 null이다.
 * 처리 뒤 취소·만료가 생겨도 [commandResult]는 관측한 로컬 처리·UNKNOWN을 보존하며 재전송하지 않는다.
 */
data class SpeechTrialSummary(
    val mode: String,
    val status: String,
    val reason: String,
    val finalReceived: Boolean,
    val phraseMatched: Boolean?,
    val confidence: Float?,
    val elapsedMs: Long?,
    val samples: Long,
    val pcmBytesWritten: Int,
    val audioLeaseRetained: Boolean,
    val decision: SpeechCommandDecision? = null,
    val commandResult: DiagnosticCommandResult = DiagnosticCommandResult.NOT_ATTEMPTED,
    val responseStatus: SpeechResponseStatus = SpeechResponseStatus.NOT_STARTED,
    val responseReason: SpeechResponseReason? = null,
    val inputReleased: Boolean? = null,
    val outputReleased: Boolean? = null,
    val audioFocusReleased: Boolean? = null,
    val offlineVoiceSelected: Boolean = false,
    val responseCleanupFailed: Boolean = false,
)

/**
 * `speechTrial` 객체 인코딩. 키 순서를 고정하고 한 줄 안에서만 쓴다.
 *
 * - 모드·사유는 [FieldEvents]의 이벤트 code allowlist와 같은 목록으로 검사한다. 목록 밖 문자열은 그 값만 `null`이 된다.
 * - 상태는 [SpeechTrialStatus] 이름만 허용한다.
 * - 판정은 [SpeechCommandDecision] 이름만 허용한다. 그래야 영속 enum 목록과 새 원문 필드가 갈라지지 않는다.
 * - 신뢰도는 유한하고 0..1일 때만 숫자로 쓰고 그 밖(범위 밖·NaN·무한)은 `null`로 낮춘다.
 *
 * 즉 값이 이상하면 그 필드만 버리고 회차 행 자체는 남긴다.
 */
internal object SpeechTrialSummaryJson {
    private val statusNames = SpeechTrialStatus.entries.map { it.name }.toSet()
    private val decisionNames = SpeechCommandDecision.entries.map { it.name }.toSet()

    fun encode(summary: SpeechTrialSummary): String = buildString(768) {
        append("{\"mode\":").append(quoted(summary.mode.takeIf { FieldEvents.isAllowed("SPEECH_TRIAL_START_$it") }))
        append(",\"status\":").append(quoted(summary.status.takeIf { it in statusNames }))
        append(",\"reason\":").append(quoted(reasonOrNull(summary.reason, summary.mode)))
        append(",\"finalReceived\":").append(boolean(summary.finalReceived))
        append(",\"phraseMatched\":").append(boolean(summary.phraseMatched))
        append(",\"confidence\":").append(confidenceOrNull(summary.confidence))
        append(",\"elapsedMs\":").append(summary.elapsedMs?.toString() ?: "null")
        append(",\"samples\":").append(summary.samples)
        append(",\"pcmBytesWritten\":").append(summary.pcmBytesWritten)
        append(",\"audioLeaseRetained\":").append(boolean(summary.audioLeaseRetained))
        append(",\"commandDecision\":").append(quoted(summary.decision?.name?.takeIf { it in decisionNames }))
        append(",\"dryRunResult\":").append(FieldJson.quote(summary.commandResult.name))
        append(",\"speechResponseStatus\":").append(FieldJson.quote(summary.responseStatus.name))
        append(",\"speechResponseReason\":").append(quoted(summary.responseReason?.name))
        append(",\"inputReleased\":").append(boolean(summary.inputReleased))
        append(",\"outputReleased\":").append(boolean(summary.outputReleased))
        append(",\"audioFocusReleased\":").append(boolean(summary.audioFocusReleased))
        append(",\"offlineVoiceSelected\":").append(boolean(summary.offlineVoiceSelected))
        append(",\"speechResponseCleanupFailed\":").append(boolean(summary.responseCleanupFailed))
        append('}')
    }

    /**
     * 거절 형태 `SPEECH_TRIAL_<사유>` code나 결과 형태 `SPEECH_TRIAL_<사유>_<모드>` code가 허용될 때만 사유로 쓴다.
     * 두 형태 모두 [FieldEvents]가 판정하므로 모드·사유 목록이 두 곳으로 갈라지지 않는다.
     */
    private fun reasonOrNull(reason: String, mode: String): String? = reason.takeIf {
        FieldEvents.isAllowed("SPEECH_TRIAL_$it") || FieldEvents.isAllowed("SPEECH_TRIAL_${it}_$mode")
    }

    private fun confidenceOrNull(value: Float?): String =
        value?.takeIf { it.isFinite() && it in 0f..1f }?.toString() ?: "null"

    private fun quoted(value: String?) = value?.let { FieldJson.quote(it) } ?: "null"

    private fun boolean(value: Boolean?) = when (value) {
        true -> "true"
        false -> "false"
        null -> "null"
    }
}
