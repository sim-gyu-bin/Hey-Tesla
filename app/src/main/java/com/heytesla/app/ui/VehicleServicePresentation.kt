package com.heytesla.app.ui

import com.heytesla.app.DiagnosticState
import com.heytesla.app.speech.product.ProductVoiceStage
import com.heytesla.app.speech.product.ProductVoiceState
import com.heytesla.app.speech.product.ProductVoiceSession
import com.heytesla.app.vehicle.connection.TeslaConnectionStage
import com.heytesla.app.vehicle.protocol.TeslaGear

internal enum class VehicleServiceStatus {
    RESTART_REQUIRED, STOPPING, OFF, FAILED, STARTING, WAITING, CONSENT_REQUIRED,
    CONNECTING, AUTHENTICATING, PARK_REQUIRED, ASSISTANT_REQUIRED, MICROPHONE_REQUIRED,
    VOICE_PREPARING, LISTENING, RECOGNIZING, COMMAND_READY, EXECUTING, BLOCKED, STOPPED,
    ASSISTANT_NOT_READY, VOICE_PREFERENCE_FAILED, CONNECTION_FAILED, VOICE_FAILED,
    CHECKING_VEHICLE, WAKING_VEHICLE,
}

internal enum class ProductFrunkResult { NONE, SENT, ACKNOWLEDGED, OPEN_REPORTED }

internal data class VehicleServicePresentation(
    val kind: VehicleServiceStatus,
    val text: String,
    val frunkResult: ProductFrunkResult = ProductFrunkResult.NONE,
    val voicePreferenceFailed: Boolean = false,
) {
    val error: Boolean get() = voicePreferenceFailed || kind == VehicleServiceStatus.RESTART_REQUIRED ||
        kind == VehicleServiceStatus.FAILED || kind == VehicleServiceStatus.BLOCKED ||
        kind == VehicleServiceStatus.CONNECTION_FAILED || kind == VehicleServiceStatus.VOICE_FAILED
}

internal fun productCleanupUncertain(state: DiagnosticState): Boolean =
    state.teslaKeyCleanupFailed ||
        state.bleFieldTrialStopReason?.contains("RESTART_REQUIRED") == true ||
        state.vehicleConnection.reason?.contains("RESTART_REQUIRED") == true ||
        state.productVoice.reason?.contains("RESTART_REQUIRED") == true

/** Presentation never grants a microphone or command lease, nor reuses historical P after OFF. */
internal fun vehicleServicePresentation(state: DiagnosticState): VehicleServicePresentation {
    val connection = state.vehicleConnection
    val voice = state.productVoice
    // Only this operation's evidence is supplied by the backend; PREPARING starts with empty evidence.
    val frunkResult = when {
        connection.stage == TeslaConnectionStage.PREPARING -> ProductFrunkResult.NONE
        connection.frunkOpenObserved -> ProductFrunkResult.OPEN_REPORTED
        connection.frunkCommandAcknowledged -> ProductFrunkResult.ACKNOWLEDGED
        connection.frunkCommandSent -> ProductFrunkResult.SENT
        else -> ProductFrunkResult.NONE
    }
    val preferenceFailed = state.productVoiceConsentSaveFailed || voice.reason == "VOICE_PREFERENCE_FAILED"
    fun status(kind: VehicleServiceStatus, text: String): VehicleServicePresentation {
        val resultText = when (frunkResult) {
            ProductFrunkResult.NONE -> null
            ProductFrunkResult.SENT -> "이번 회차 프렁크 요청 전송 · 차량 ACK·열림 상태 확인 안 됨"
            ProductFrunkResult.ACKNOWLEDGED -> "이번 회차 프렁크 요청 ACK 수신 · 열림 상태 확인 안 됨"
            ProductFrunkResult.OPEN_REPORTED -> "이번 회차 차량 BODY 응답에서 프렁크 열림 보고 수신 · 물리적 완료 보장 아님"
        }
        val preferenceText = if (preferenceFailed) {
            "음성 동의 설정 저장 실패 · 철회 요청은 현재 음성·명령을 중지하지만 저장 상태는 다음 실행 전 재확인 필요"
        } else null
        return VehicleServicePresentation(
            kind = kind,
            text = if (resultText == null && preferenceText == null) text else buildString {
                append(text)
                if (resultText != null) append('\n').append(resultText)
                if (preferenceText != null) append('\n').append(preferenceText)
            },
            frunkResult = frunkResult,
            voicePreferenceFailed = preferenceFailed,
        )
    }
    if (productCleanupUncertain(state)) return status(VehicleServiceStatus.RESTART_REQUIRED,
        "자원 정리 불명 · 새 작업 차단 · 앱 프로세스 재시작 필요")
    if (state.bleFieldTrialStopping) return status(VehicleServiceStatus.STOPPING, "서비스 중지 중 · 연결·오디오 종료 확인 중")
    if (!state.bleDetectionEnabled) return status(VehicleServiceStatus.OFF, "서비스 OFF · 차량 감지·음성 대기 사용 안 함")
    if (voice.stage == ProductVoiceStage.STOPPING) return status(VehicleServiceStatus.STOPPING, "음성 중지 중 · 오디오 해제 확인 중")
    if (state.bleFieldScanFailure != null) return status(VehicleServiceStatus.FAILED, "서비스 오류 · 차량 감지 스캔 실패")
    if (state.bleFieldTrialStarting || state.observationStartPending) return status(VehicleServiceStatus.STARTING, "서비스 시작 중 · 실행 확인 전")
    if (!state.bleFieldTrialActive || !state.observationServiceRunning) return status(
        if (state.bleFieldTrialStopReason != null) VehicleServiceStatus.FAILED else VehicleServiceStatus.STOPPED,
        "서비스 실행 안 됨 · 설정 확인 후 앱에서 끄고 다시 켜 주세요",
    )
    if (voice.stage == ProductVoiceStage.FAILED ||
        (voice.stage == ProductVoiceStage.STOPPED &&
            (connection.stage == TeslaConnectionStage.STOPPED || connection.stage == TeslaConnectionStage.FAILED) &&
            connection.reason == voice.reason)
    ) {
        val voiceFailureText = when (voice.reason) {
            "PRODUCT_VOICE_MODEL_MISSING" -> "앱 내 음성 모델 누락 · 음성 명령 중지"
            "PRODUCT_VOICE_MODEL_LOAD_FAILED" -> "앱 내 음성 모델 로드 실패 · 음성 명령 중지"
            "PRODUCT_VOICE_DECODE_FAILED" -> "로컬 음성 인식 실패 · 음성 명령 중지"
            "PRODUCT_VOICE_UTTERANCE_TRUNCATED" -> "발화 길이 초과 · 음성 명령 중지"
            "PRODUCT_VOICE_UTTERANCE_INCOMPLETE", "PRODUCT_VOICE_PCM_INCOMPLETE" ->
                "음성 입력 불완전 · 음성 명령 중지"
            "AUDIO_READ_FAILED", "AUDIO_CAPTURE_FAILED", "PRODUCT_VOICE_FAILED",
            "PRODUCT_VOICE_CLOCK_REVERSED", "PRODUCT_VOICE_DIAGNOSTIC_FAILED",
            "PRODUCT_VOICE_STATE_FAILED" -> "음성 처리 실패 · 음성 명령 중지"
            else -> null
        }
        if (voiceFailureText != null) return status(VehicleServiceStatus.VOICE_FAILED, voiceFailureText)
    }
    if (connection.stage == TeslaConnectionStage.FAILED) return status(
        VehicleServiceStatus.CONNECTION_FAILED, "차량 연결 오류 · 음성 명령 중지",
    )
    if (voice.stage == ProductVoiceStage.FAILED) {
        return status(VehicleServiceStatus.VOICE_FAILED, "음성 오류 · 음성 명령 중지")
    }
    if (preferenceFailed) return status(VehicleServiceStatus.VOICE_PREFERENCE_FAILED, "음성 동의 저장 상태 확인 필요")
    if (connection.stage == TeslaConnectionStage.BLOCKED || voice.stage == ProductVoiceStage.BLOCKED) {
        val reason = if (connection.stage == TeslaConnectionStage.BLOCKED) connection.reason else voice.reason
        return when (reason) {
            "PRODUCT_VOICE_CONSENT_REQUIRED" -> status(VehicleServiceStatus.CONSENT_REQUIRED,
                "음성 프렁크 동의 필요 · 마이크 대기 안 함")
            "DEFAULT_ASSISTANT_REQUIRED" -> status(VehicleServiceStatus.ASSISTANT_REQUIRED,
                "기본 비서 설정에서 Hey Tesla를 직접 선택해 주세요 · 음성 대기 안 함")
            "ASSISTANT_NOT_READY" -> status(VehicleServiceStatus.ASSISTANT_NOT_READY,
                "기본 비서 서비스 준비 안 됨 · 음성 대기 안 함")
            "MIC_PERMISSION_REQUIRED" -> status(VehicleServiceStatus.MICROPHONE_REQUIRED,
                "마이크 권한 필요 · 설정에서 직접 허용해 주세요")
            else -> status(VehicleServiceStatus.BLOCKED, "음성 명령 차단 · 연결·권한·차량 상태 확인 필요")
        }
    }
    if (connection.stage == TeslaConnectionStage.EXECUTING) return status(VehicleServiceStatus.EXECUTING,
        "프렁크 요청 처리 중 · P 재확인 · 물리적 열림 확인 전")
    if (!state.productVoiceConsent) return status(VehicleServiceStatus.CONSENT_REQUIRED, "서비스 ON · 음성 프렁크 동의 필요 · 마이크 대기 안 함")
    when (connection.stage) {
        TeslaConnectionStage.PREPARING, TeslaConnectionStage.CONNECTING ->
            return status(VehicleServiceStatus.CONNECTING, "차량 연결 중 · 인증·P 확인 전")
        TeslaConnectionStage.CHECKING_VEHICLE ->
            return status(VehicleServiceStatus.CHECKING_VEHICLE, "차량 인증·수면 상태 확인 중 · 마이크 사용 안 함")
        TeslaConnectionStage.WAKING_VEHICLE ->
            return status(VehicleServiceStatus.WAKING_VEHICLE, "차량 깨우기 준비·응답 확인 중 · P 확인 전")
        TeslaConnectionStage.AUTHENTICATING ->
            return status(VehicleServiceStatus.AUTHENTICATING, "차량 앱 키 인증 중 · 음성 대기 전")
        TeslaConnectionStage.STOPPED -> return status(VehicleServiceStatus.STOPPED, "차량 연결 종료 · 음성 명령 중지")
        TeslaConnectionStage.IDLE -> return status(VehicleServiceStatus.WAITING, "서비스 ON · 차량 신호 대기 · 마이크 사용 안 함")
        else -> Unit
    }
    if (connection.stage != TeslaConnectionStage.MONITORING || !connection.parkFresh || connection.gear != TeslaGear.P) {
        return status(VehicleServiceStatus.PARK_REQUIRED, "신선한 P 확인 안 됨 · 음성 명령 차단")
    }
    if (!state.assistant) return status(VehicleServiceStatus.ASSISTANT_REQUIRED, "앱 키 인증·P 확인 · 기본 비서 직접 선택 필요")
    if (!state.microphonePermission) return status(VehicleServiceStatus.MICROPHONE_REQUIRED, "앱 키 인증·P 확인 · 마이크 권한 필요")
    return when (voice.stage) {
        ProductVoiceStage.LISTENING -> status(VehicleServiceStatus.LISTENING,
            "앱 키 인증·P 확인 · ${productVoiceListeningText(state.productVoiceRecognitionOutcome)}")
        ProductVoiceStage.RECOGNIZING -> status(VehicleServiceStatus.RECOGNIZING, "음성 인식 중 · 마이크 수집 종료 · 차량 실행 전")
        ProductVoiceStage.COMMAND_READY -> status(VehicleServiceStatus.COMMAND_READY, "명령 인식 · 자원 해제·P 재확인 후 단발 요청")
        ProductVoiceStage.PREPARING -> status(VehicleServiceStatus.VOICE_PREPARING, "앱 키 인증·P 확인 · 음성 준비 중")
        ProductVoiceStage.STOPPED -> status(VehicleServiceStatus.STOPPED, "음성 회차 종료 · 새 차량 접근 대기")
        else -> status(VehicleServiceStatus.WAITING, "앱 키 인증·P 확인 · 마이크 대기 시작 안 됨")
    }
}

internal fun vehicleServiceNotificationText(state: DiagnosticState): String = vehicleServicePresentation(state).text

/** 이 값은 판정 코드만 읽는다. 인식 원문이나 이전 결과 목록은 상태바의 입력이 아니다. */
private fun productVoiceListeningText(outcome: String?): String = when (outcome) {
    "GRAMMAR_NO_MATCH" -> "명령 불일치 · 음성 대기"
    "EMPTY" -> "음성 확인 안 됨 · 음성 대기"
    else -> "마이크 음성 대기 중"
}

internal fun productMicrophoneNotificationText(voice: ProductVoiceState, outcome: String?): String =
    when (voice.stage) {
        ProductVoiceStage.LISTENING -> "차량 P 확인 중 · ${productVoiceListeningText(outcome)} · 녹음 저장 안 함"
        ProductVoiceStage.RECOGNIZING -> "마이크 해제됨 · 기기에서 음성 인식 중"
        ProductVoiceStage.STOPPING -> "음성 입력 종료 · 자원 해제 확인 중"
        ProductVoiceStage.COMMAND_READY -> "음성 명령 확인 · 차량 요청 전"
        ProductVoiceStage.FAILED -> if (voice.reason == ProductVoiceSession.CLEANUP_FAILED)
            "오디오 정리 불명 · 재시작 필요" else "음성 처리 실패 · 종료 중"
        ProductVoiceStage.STOPPED, ProductVoiceStage.BLOCKED -> "음성 대기 중단"
        ProductVoiceStage.IDLE, ProductVoiceStage.PREPARING -> "음성 입력 준비 중"
    }
