package com.heytesla.app.logging

import com.heytesla.app.speech.product.ProductVoiceDiagnostic

/** 제품 음성 수치만 투영한다. 정리 미확인을 정상 결과로 기록하지 않는다. */
internal object ProductVoiceDiagnosticJson {
    const val EVENT = "PRODUCT_VOICE_DIAGNOSTIC"
    private val outcomes = setOf("PREPARED", "EMPTY", "GRAMMAR_NO_MATCH", "ACCEPTED", "FAILURE", "SEALED", "CLOSED")

    fun appendTo(out: StringBuilder, diagnostic: ProductVoiceDiagnostic) = with(out) {
        val captureOutcome = diagnostic.outcome != "PREPARED" && diagnostic.outcome != "CLOSED"
        val utterance = diagnostic.utteranceBytes?.takeIf { captureOutcome && it in 0..320_000 && it % 2 == 0 }
        val audioClean = diagnostic.microphoneReleased == true && diagnostic.streamCleanupConfirmed == true
        val outcome = diagnostic.outcome.takeIf {
            it in outcomes && when (it) {
                "EMPTY", "GRAMMAR_NO_MATCH" -> audioClean
                "ACCEPTED" -> audioClean && diagnostic.recognizerCleanupConfirmed == true
                "CLOSED" -> diagnostic.recognizerCleanupConfirmed == true
                else -> true
            }
        }
        append("{\"outcome\":").append(outcome?.let(FieldJson::quote) ?: "null")
        append(",\"utteranceBytes\":").append(utterance)
        append(",\"voicedBytes\":").append(pcmBytes(diagnostic.voicedBytes, utterance))
        append(",\"suppressedOnsets\":").append(diagnostic.suppressedOnsets?.takeIf { captureOutcome && it >= 0 })
        append(",\"silenceBytesAtEnd\":").append(pcmBytes(diagnostic.silenceBytesAtEnd, utterance))
        append(",\"captureMs\":").append(diagnostic.captureMs?.takeIf { captureOutcome && it in 0L..86_400_000L })
        append(",\"loadMs\":").append(duration(diagnostic.loadMs))
        append(",\"decodeMs\":").append(diagnostic.decodeMs?.takeIf { captureOutcome && it in 0L..600_000L })
        append(",\"cleanupMs\":").append(duration(diagnostic.cleanupMs))
        append(",\"modelReused\":").append(diagnostic.modelReused.takeIf { captureOutcome })
        append(",\"microphoneReleased\":").append(diagnostic.microphoneReleased)
        append(",\"streamCleanupConfirmed\":").append(diagnostic.streamCleanupConfirmed)
        append(",\"recognizerCleanupConfirmed\":").append(diagnostic.recognizerCleanupConfirmed)
        append('}')
        Unit
    }

    private fun pcmBytes(value: Int?, utterance: Int?): Int? =
        value?.takeIf { utterance != null && it in 0..utterance && it % 2 == 0 }

    private fun duration(value: Long?): Long? = value?.takeIf { it in 0L..600_000L }
}
