package com.heytesla.app.speech.product

/** 원문·PCM·식별자 없는 제품 음성 관측. 차량·마이크·명령 허가의 근거가 아니다. */
data class ProductVoiceDiagnostic(
    val outcome: String,
    val utteranceBytes: Int? = null,
    val voicedBytes: Int? = null,
    val suppressedOnsets: Int? = null,
    val silenceBytesAtEnd: Int? = null,
    val captureMs: Long? = null,
    val loadMs: Long? = null,
    val decodeMs: Long? = null,
    val cleanupMs: Long? = null,
    val modelReused: Boolean? = null,
    val microphoneReleased: Boolean? = null,
    val streamCleanupConfirmed: Boolean? = null,
    val recognizerCleanupConfirmed: Boolean? = null,
)
