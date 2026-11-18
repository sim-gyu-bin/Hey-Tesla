package com.heytesla.app.speech.product

import com.heytesla.app.vehicle.frunk.FrunkVoicePolicy

/** 제품 상태에는 PCM·인식 원문·차량 식별자를 넣지 않는다. */
enum class ProductVoiceStage {
    IDLE, PREPARING, LISTENING, RECOGNIZING, STOPPING, COMMAND_READY, STOPPED, BLOCKED, FAILED,
}

data class ProductVoiceState(
    val stage: ProductVoiceStage = ProductVoiceStage.IDLE,
    val reason: String? = null,
)

/** gate의 소진과 차량 전송은 runtime만 수행한다. false인 정리 결과는 예약 반환 허가가 아니다. */
internal data class ProductVoiceCompletion(
    val gate: FrunkVoicePolicy?,
    val microphoneReleased: Boolean,
    val cleanupConfirmed: Boolean,
    val reason: String?,
)
