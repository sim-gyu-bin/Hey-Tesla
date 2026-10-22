package com.heytesla.app

/** 실차 시험의 보수적 로컬 정책. 엔진·화자 인증 정확도를 검증한 임계값이 아니다. */
internal class FrunkVoicePolicy(private val deadline: Long, private val startedAtElapsedMs: Long) {
    enum class Phase { COMMAND, ACCEPTED, DISPATCHED, STOPPED }
    var phase = Phase.COMMAND
        private set
    var reason: String? = null
        private set
    var commandAcceptedAtElapsedMs: Long? = null
        private set
    /** 채택 후보의 실제 점수. 엔진이 점수를 주지 않았으면(0.0·-1·없음) null이다. */
    var acceptedConfidence: Float? = null
        private set

    /**
     * Android STT n-best 중 하나라도 전체 문법과 정확히 같으면 채택한다. 어느 후보든 취소어가 있으면 먼저 멈춘다.
     * 점수가 실제로 제공되면 채택 후보가 [MIN_CONFIDENCE] 이상이어야 하고, 제공되지 않으면 게이트를 생략한다.
     */
    fun command(now: Long, source: SpeechTrialMode, final: Boolean,
        candidates: List<String>?, confidence: FloatArray?): Boolean {
        if (phase != Phase.COMMAND) return false
        if (!validTime(now)) return false
        if (source != SpeechTrialMode.DIRECT_MIC) return reject("FRUNK_VOICE_SOURCE_BLOCKED")
        if (!final) return false
        if (candidates.isNullOrEmpty()) return reject("FRUNK_VOICE_GRAMMAR_BLOCKED")
        // 전체 허용 문법만 비교한다. 공백·구두점 외의 부정·추가어는 제거하지 않는다.
        val normalized = candidates.map(SpeechCommandParser::normalize)
        if (normalized.any { text -> CANCEL_WORDS.any(text::contains) }) return reject("FRUNK_VOICE_CANCELED")
        val index = candidates.indices.firstOrNull {
            candidates[it].length <= SpeechCommandParser.MAX_NORMALIZED_LENGTH &&
                normalized[it] == SpeechCommandPhrases.normalizedSentence
        } ?: return reject("FRUNK_VOICE_GRAMMAR_BLOCKED")
        val score = if (confidence != null && scoresAvailable(confidence)) {
            if (confidence.size != candidates.size) return reject("FRUNK_VOICE_CONFIDENCE_BLOCKED")
            confidence[index].takeIf { it.isFinite() && it in MIN_CONFIDENCE..1f }
                ?: return reject("FRUNK_VOICE_CONFIDENCE_BLOCKED")
        } else null
        acceptedConfidence = score
        commandAcceptedAtElapsedMs = now
        phase = Phase.ACCEPTED
        return true
    }


    /** 오디오 정상 반환과 마지막 게이트 검사 뒤, core 호출 전에 한 번만 소진한다. */
    fun takeForDispatch(now: Long, inputReleased: Boolean): Boolean {
        if (phase != Phase.ACCEPTED) return false
        if (!inputReleased) return reject("FRUNK_VOICE_CLEANUP_FAILED")
        if (!validTime(now)) return false
        phase = Phase.DISPATCHED
        return true
    }

    fun validTime(now: Long): Boolean {
        if (phase == Phase.STOPPED || phase == Phase.DISPATCHED) return false
        if (now < startedAtElapsedMs || now >= deadline ||
            (phase == Phase.COMMAND && now - startedAtElapsedMs >= COMMAND_MS) ||
            (phase == Phase.ACCEPTED && commandAcceptedAtElapsedMs?.let {
                now < it || now - it >= HANDOFF_MS
            } != false)
        ) return reject("FRUNK_VOICE_EXPIRED")
        return true
    }

    fun cancel(code: String = "FRUNK_VOICE_CANCELED") {
        if (phase != Phase.DISPATCHED && phase != Phase.STOPPED) reject(code)
    }

    private fun reject(code: String): Boolean {
        phase = Phase.STOPPED
        reason = code
        return false
    }

    companion object {
        const val MIN_CONFIDENCE = 0.85f
        const val COMMAND_MS = 10_000L
        const val HANDOFF_MS = 5_000L
        private val CANCEL_WORDS = listOf("취소", "열지마")

        /** CONFIDENCE_SCORES는 선택 값이며 미제공은 -1, 이 기기 온디바이스 엔진은 항상 0.0을 준다. 양수 유한값만 실제 점수로 본다. */
        fun scoresAvailable(scores: FloatArray?): Boolean = scores != null && scores.any { it.isFinite() && it > 0f }
    }
}

/** 원문·VIN·association·오디오는 담지 않는 RAM 화면 상태. */
enum class FrunkVoiceStage(val label: String) {
    IDLE("실차 시험 대기"), COMMAND("명령을 말하세요"),
    CLEANING("마이크 해제 확인 중"), CORE("실차 요청 진행 중"), COMPLETE("실차 회차 종료"),
    STOPPED("미전송 종료"), CLEANUP_FAILED("오디오 정리 불명 · 재시작 필요"),
}

/**
 * 화면에 보여줄 실제 진행 위치. 경과 시간이 아니라 core가 관측한 phase·응답으로만 전진한다.
 * 음성 → 연결·인증 → 차량 상태 → 신선한 P → 전송 접수(SDK) → 차량 ACK → BODY 열림 보고 순서다.
 * SDK 접수·차량 ACK·열림 보고·물리 개방은 서로 다른 사건이므로 하나로 합치지 않는다.
 */
enum class FrunkVoiceStep(val label: String) {
    IDLE("실차 시험 대기"),
    COMMAND("음성 명령 대기"),
    CLEANING("마이크 해제 확인"),
    STARTING("실차 처리 시작"),
    KEY_PREPARING("앱 키 준비"),
    CONNECTING("차량 BLE 연결"),
    LINKING("BLE 서비스 준비"),
    CARD_WAITING("카드 승인 대기"),
    AUTHENTICATING("차량 인증"),
    READING_STATE("차량 상태·P 확인"),
    FRESH_P("신선한 P 확인"),
    COMMAND_SENT("개방 요청 접수 · 차량 응답 대기"),
    ACK("차량 명령 응답 확인"),
    LINK_CLEANUP("연결 정리"),
    COMPLETE("열림 보고 확인"),
    BLOCKED("안전 조건 미충족"),
    FAILED("실패"),
    UNKNOWN("결과 불명"),
    TIMED_OUT("시간 초과"),
    STOPPED("시험 중단"),
    CLEANUP_FAILED("정리 불명 · 재시작 필요"),
}

data class FrunkVoiceState(
    val stage: FrunkVoiceStage = FrunkVoiceStage.IDLE,
    val active: Boolean = false,
    val reason: String? = null,
    val commandConfidence: Float? = null,
    val recordingId: String? = null,
    val recordingFailure: Boolean = false,
    val inputReleased: Boolean = true,
    val coreStarted: Boolean = false,
    val cancelRequested: Boolean = false,
    val frunkCommandRequested: Boolean = false,
    val frunkCommandSent: Boolean = false,
    val frunkCommandAcknowledged: Boolean = false,
    val frunkOpenObserved: Boolean = false,
    val frunkFreshParkVerified: Boolean = false,
    val coreStage: TeslaKeyStage? = null,
    val coreOutcome: TeslaKeyOutcome? = null,
    /** active였던 마지막 실제 위치. 종료·실패 시 어디까지 갔는지 보여준다. */
    val lastCoreStep: FrunkVoiceStep? = null,
    /** 이번 회차 인식 원문 후보. 화면 표시 전용 RAM 상태이며 다음 시험 시작 시 새 상태로 지워진다. */
    val transcript: SttTranscript? = null,
    /** transcript 판정 시점의 정책 결과. 인정도 차량 실행 허가가 아니며, 차단이면 사유를 함께 둔다. */
    val transcriptAccepted: Boolean = false,
    val transcriptReason: String? = null,
)

/** 종료 뒤에도 마지막 실제 단계와 사유를 남긴다. 성공 표시는 관측된 단계에서만 나온다. */
internal fun FrunkVoiceState.progressStep(): FrunkVoiceStep = when {
    stage == FrunkVoiceStage.CLEANUP_FAILED -> FrunkVoiceStep.CLEANUP_FAILED
    reason == "FRUNK_VOICE_CORE_UNKNOWN" -> FrunkVoiceStep.UNKNOWN
    stage == FrunkVoiceStage.IDLE -> FrunkVoiceStep.IDLE
    stage == FrunkVoiceStage.COMMAND -> FrunkVoiceStep.COMMAND
    stage == FrunkVoiceStage.CLEANING -> FrunkVoiceStep.CLEANING
    stage == FrunkVoiceStage.STOPPED -> FrunkVoiceStep.STOPPED
    !coreStarted -> FrunkVoiceStep.STARTING
    coreStage == TeslaKeyStage.CLEANUP_FAILED -> FrunkVoiceStep.CLEANUP_FAILED
    coreOutcome == TeslaKeyOutcome.UNKNOWN -> FrunkVoiceStep.UNKNOWN
    coreStage == TeslaKeyStage.TIMED_OUT -> FrunkVoiceStep.TIMED_OUT
    coreStage == TeslaKeyStage.BLOCKED -> FrunkVoiceStep.BLOCKED
    coreStage == TeslaKeyStage.CANCELED -> FrunkVoiceStep.STOPPED
    coreStage == TeslaKeyStage.FAILED -> FrunkVoiceStep.FAILED
    coreStage == TeslaKeyStage.COMPLETE -> if (frunkCommandAcknowledged && frunkOpenObserved) FrunkVoiceStep.COMPLETE else FrunkVoiceStep.UNKNOWN
    else -> activeCoreStep()
}

/** active 중 core phase와 실제 응답만으로 계산한 현재 위치. */
internal fun FrunkVoiceState.activeCoreStep(): FrunkVoiceStep = when (coreStage) {
    null, TeslaKeyStage.IDLE -> FrunkVoiceStep.STARTING
    TeslaKeyStage.PREPARING_KEY -> FrunkVoiceStep.KEY_PREPARING
    TeslaKeyStage.CONNECTING -> FrunkVoiceStep.CONNECTING
    TeslaKeyStage.DISCOVERING, TeslaKeyStage.NEGOTIATING_MTU, TeslaKeyStage.SUBSCRIBING -> FrunkVoiceStep.LINKING
    TeslaKeyStage.WAITING_FOR_CARD -> FrunkVoiceStep.CARD_WAITING
    TeslaKeyStage.HANDSHAKING -> FrunkVoiceStep.AUTHENTICATING
    TeslaKeyStage.READING_STATUS -> when {
        frunkCommandAcknowledged -> FrunkVoiceStep.ACK
        frunkCommandSent -> FrunkVoiceStep.COMMAND_SENT
        frunkFreshParkVerified -> FrunkVoiceStep.FRESH_P
        else -> FrunkVoiceStep.READING_STATE
    }
    TeslaKeyStage.CLEANING_UP -> FrunkVoiceStep.LINK_CLEANUP
    else -> FrunkVoiceStep.STARTING
}

/** 원시 코드를 사용자 문장으로 바꾼다. 모르는 코드는 숨기지 않고 그대로 남긴다. */
internal fun frunkVoiceReasonLabel(code: String): String = when (code) {
    "FRUNK_VOICE_CONSENT_REQUIRED" -> "시험 동의가 없어 시작하지 않았습니다."
    "FRUNK_VOICE_MODEL_REQUIRED" -> "온디바이스 한국어 음성 인식을 확인하지 못했습니다."
    "FRUNK_VOICE_ADMISSION_BLOCKED" -> "다른 진단·정리 상태 때문에 시작할 수 없습니다."
    "FRUNK_VOICE_SOURCE_BLOCKED" -> "마이크 외 입력은 차단했습니다."
    "FRUNK_VOICE_GRAMMAR_BLOCKED", "FRUNK_VOICE_FINAL_REQUIRED" -> "최종 전체 문법 미충족 · 실차 요청 미전송"
    "FRUNK_VOICE_CONFIDENCE_BLOCKED" -> "신뢰도가 낮거나 확인되지 않아 전송하지 않았습니다."
    "FRUNK_VOICE_CANCELED" -> "취소됨 · 실차 요청 미전송"
    "FRUNK_VOICE_EXPIRED" -> "명령·전송 기한 만료 · 실차 요청 미전송"
    "FRUNK_VOICE_READINESS_LOST" -> "시험 중 안전 조건이 바뀌어 중단했습니다."
    "FRUNK_VOICE_RECOGNITION_FAILED" -> "음성 인식을 완료하지 못했습니다."
    "FRUNK_VOICE_HANDOFF_BLOCKED" -> "차량 단계로 넘기지 못했습니다."
    "FRUNK_VOICE_CLEANUP_FAILED" -> "마이크 정리 불명 · 예약 유지 · 앱 프로세스 재시작 필요"
    "FRUNK_VOICE_CORE_UNKNOWN" -> "실차 처리 결과 불명 · 재전송 차단 · 정리 확인 전 재시작 필요"
    "FRUNK_CANCELED" -> "요청이 취소되었습니다."
    "FRUNK_TIME_REVERSED" -> "시간 관측이 역행해 중단했습니다."
    "FRUNK_DRIVE_ALREADY_CONSUMED" -> "이번 회차의 신선한 P는 이미 사용되었습니다."
    "FRUNK_EXPLICIT_P_REQUIRED" -> "차량 기어가 P로 보고되지 않았습니다."
    "FRUNK_P_RECEIPT_STALE" -> "P 상태 수신이 오래되어 신선하지 않습니다."
    "FRUNK_P_SOURCE_TIME_REQUIRED" -> "P 상태의 시각 정보가 없습니다."
    "FRUNK_P_SOURCE_TIME_STALE" -> "P 상태의 차량·폰 시각 차이가 허용 범위를 넘었습니다."
    "FRUNK_FRESH_P_REQUIRED" -> "개방 요청 전 신선한 P를 확인하지 못했습니다."
    "FRUNK_COMMAND_ALREADY_CONSUMED" -> "명령이 이미 전송되어 재사용할 수 없습니다."
    "FRUNK_DUPLICATE_WRITE" -> "명령 쓰기 중복 또는 순서 불일치를 차단했습니다."
    "FRUNK_COMMAND_INVALID" -> "명령 시각 정보가 유효하지 않습니다."
    "FRUNK_COMMAND_EXPIRED" -> "명령 유효 시간이 만료되었습니다."
    "FRUNK_OPEN_NOT_OBSERVED" -> "차량이 프렁크 열림을 보고하지 않았습니다."
    "FRUNK_ACTION_RESPONSE_UNKNOWN" -> "차량 명령 응답을 확인하지 못했습니다."
    "FRUNK_ACTION_REJECTED" -> "차량이 개방 명령을 거부했습니다."
    "FRUNK_PROTOCOL_RESPONSE_INVALID" -> "차량 응답 형식이 예상과 다릅니다."
    "FRUNK_VIN_SNAPSHOT_CHANGED" -> "대상 VIN이 바뀌어 중단했습니다."
    "FRUNK_PURPOSE_INVALID" -> "요청 목적 조합이 허용되지 않습니다."
    "FRUNK_SEQUENCE_ALREADY_STARTED", "FRUNK_SEQUENCE_INACTIVE", "FRUNK_COMMAND_NOT_WRITTEN" ->
        "차량 명령 순서가 유효하지 않습니다."
    "GATT_CONNECT_FAILED" -> "차량 BLE 연결에 실패했습니다."
    "GATT_CONNECT_PERMISSION_DENIED" -> "BLE 권한이 거부되었습니다."
    "GATT_CONNECTION_LOST" -> "차량 연결을 유지하지 못했습니다."
    "GATT_DISCOVERY_FAILED", "GATT_DISCOVERY_REJECTED" -> "차량 서비스 검색에 실패했습니다."
    "GATT_MTU_FAILED", "GATT_MTU_REJECTED" -> "BLE MTU 협상에 실패했습니다."
    "GATT_SUBSCRIPTION_FAILED", "GATT_SUBSCRIPTION_REJECTED", "GATT_NOTIFICATION_REJECTED" ->
        "차량 알림 구독에 실패했습니다."
    "GATT_WRITE_FAILED", "GATT_WRITE_REJECTED", "GATT_WRITE_EXCEPTION" -> "차량 명령 쓰기에 실패했습니다."
    "GATT_CALLBACK_FAILED" -> "BLE 콜백 처리에 실패했습니다."
    "GATT_SERVICES_INVALIDATED" -> "차량 서비스가 무효화되었습니다."
    "BLUETOOTH_UNAVAILABLE" -> "BLE 어댑터를 사용할 수 없습니다."
    "BLUETOOTH_PERMISSION_REQUIRED" -> "BLE 권한이 필요합니다."
    "SINGLE_ASSOCIATION_REQUIRED" -> "등록된 차량이 정확히 하나가 아닙니다."
    "ASSOCIATION_ADDRESS_UNAVAILABLE" -> "차량 주소를 확인할 수 없습니다."
    "ASSOCIATION_RESOLVE_FAILED" -> "차량 연결 정보 확인에 실패했습니다."
    "READINESS_CHECK_FAILED" -> "안전 조건 확인에 실패했습니다."
    "KEY_PREPARATION_FAILED" -> "앱 키 준비에 실패했습니다."
    "EXISTING_KEY_UNAVAILABLE" -> "등록된 앱 키를 사용할 수 없습니다."
    "VIN_FORMAT_INVALID" -> "VIN 형식이 유효하지 않습니다."
    "VIN_NOT_REGISTERED" -> "등록된 VIN이 없습니다."
    "DEADLINE_EXPIRED" -> "최대 40초 작업 기한이 만료되었습니다."
    "TX_FRAME_INVALID" -> "전송 프레임 생성에 실패했습니다."
    "RX_FRAME_INVALID" -> "차량 응답 프레임을 해석하지 못했습니다."
    "RX_BUFFER_LIMIT" -> "차량 응답 버퍼 한도를 넘었습니다."
    "PROTOCOL_START_FAILED", "PROTOCOL_RESPONSE_INVALID" -> "차량 프로토콜 진행에 실패했습니다."
    "AUTHENTICATION_FAILED" -> "차량 인증에 실패했습니다."
    "TESLA_GATT_PROFILE_UNSUPPORTED" -> "차량 BLE 프로필을 지원하지 않습니다."
    "VISIBLE_UI_REQUIRED" -> "화면 표시와 잠금 해제가 필요합니다."
    "USER_STOP" -> "사용자가 중단했습니다."
    else -> code
}
