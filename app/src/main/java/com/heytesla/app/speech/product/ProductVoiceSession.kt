package com.heytesla.app.speech.product

import com.heytesla.app.speech.KoreanSpeechCleanup
import com.heytesla.app.speech.KoreanSpeechFailure
import com.heytesla.app.speech.KoreanSpeechStreamResult
import com.heytesla.app.speech.SpeechTrialMode
import com.heytesla.app.vehicle.frunk.FrunkVoicePolicy
import java.util.concurrent.atomic.AtomicReference

/** Main 직렬 정책. IO는 stopReason만 봉인하며 실제 준비·stream·owner 해제는 호출자가 보고한다. */
internal class ProductVoiceSession {
    enum class Decision { WAIT, ACCEPTED, STOP }
    private enum class AudioPhase { IDLE, CAPTURING, CAPTURED, RECOGNIZING, RECOGNIZED, ACCEPTED }
    private var audioPhase = AudioPhase.IDLE
    val stopReason = AtomicReference<String?>(null)
    private var started = false
    private var completed = false
    private var preparationStarted = false
    private var preparationPending = false
    private var recognizerReady = false
    private var closeReported = false
    private var captureClean = true
    private var gate: FrunkVoicePolicy? = null
    var microphoneReleased = true
        private set
    var streamCleanupConfirmed = true
        private set
    var recognizerCleanupConfirmed = true
        private set
    val cleanupConfirmed: Boolean
        get() = microphoneReleased && captureClean && streamCleanupConfirmed && recognizerCleanupConfirmed
    val canCapture: Boolean
        get() = started && !completed && audioPhase == AudioPhase.IDLE && stopReason.get() == null &&
            recognizerReady && microphoneReleased && captureClean && streamCleanupConfirmed

    fun start(): Boolean {
        if (started || completed || stopReason.get() != null) return false
        started = true
        return true
    }

    fun stop(reason: String) {
        stopReason.compareAndSet(null, reason)
        gate?.cancel(requireNotNull(stopReason.get()))
    }

    /** load 호출 전부터 알 수 없는 native 소유권을 추적한다. */
    fun beginPreparation(): Boolean {
        if (!started || completed || preparationStarted || stopReason.get() != null) return false
        preparationStarted = true
        preparationPending = true
        recognizerCleanupConfirmed = false
        return true
    }

    fun preparationFinished(prepared: Boolean, clean: Boolean, failure: KoreanSpeechFailure?) {
        if (completed || !preparationPending) return
        preparationPending = false
        recognizerReady = prepared && failure == null
        recognizerCleanupConfirmed = clean && !prepared
        if (failure != null) stop(failureReason(failure))
        if (!recognizerReady && !recognizerCleanupConfirmed) stop(CLEANUP_FAILED)
        if (!recognizerReady && failure == null) stop("PRODUCT_VOICE_MODEL_LOAD_FAILED")
    }

    fun beginCapture(): Boolean {
        if (!canCapture) return false
        audioPhase = AudioPhase.CAPTURING
        microphoneReleased = false
        captureClean = false
        return true
    }

    fun captureFinished(released: Boolean, clean: Boolean) {
        if (completed || audioPhase != AudioPhase.CAPTURING) return
        audioPhase = AudioPhase.CAPTURED
        microphoneReleased = released
        captureClean = released && clean
        if (!captureClean) stop(CLEANUP_FAILED)
    }

    fun beginRecognition(): Boolean {
        if (completed || audioPhase != AudioPhase.CAPTURED || stopReason.get() != null || !recognizerReady ||
            !microphoneReleased || !captureClean || !streamCleanupConfirmed) return false
        audioPhase = AudioPhase.RECOGNIZING
        streamCleanupConfirmed = false
        return true
    }

    /** per-stream 완료일 뿐 recognizer full cleanup은 아니다. 원문·합성 점수는 외부로 내보내지 않는다. */
    fun acceptRecognition(result: KoreanSpeechStreamResult, expectedBytes: Int, startedAt: Long, now: Long): Decision {
        if (completed || audioPhase != AudioPhase.RECOGNIZING) return Decision.STOP
        audioPhase = AudioPhase.RECOGNIZED
        streamCleanupConfirmed = result.streamCleanupConfirmed
        val failure = result.failure?.let(::failureReason) ?: when {
            expectedBytes <= 0 || expectedBytes % 2 != 0 || result.consumedBytes != expectedBytes ->
                "PRODUCT_VOICE_PCM_INCOMPLETE"
            !result.decodeCompleted || result.text == null -> "PRODUCT_VOICE_DECODE_FAILED"
            else -> null
        }
        if (!streamCleanupConfirmed) stop(CLEANUP_FAILED)
        if (failure != null) stop(failure)
        if (stopReason.get() != null) return Decision.STOP
        val policy = FrunkVoicePolicy(Long.MAX_VALUE, startedAt)
        gate = policy
        if (policy.command(now, SpeechTrialMode.DIRECT_MIC, true, listOf(requireNotNull(result.text)), null)) {
            audioPhase = AudioPhase.ACCEPTED
            return Decision.ACCEPTED
        }
        return if (policy.reason == "FRUNK_VOICE_GRAMMAR_BLOCKED" ||
            policy.reason == "FRUNK_VOICE_CONFIDENCE_BLOCKED") {
            gate = null
            audioPhase = AudioPhase.IDLE
            Decision.WAIT
        } else {
            stop(policy.reason ?: "PRODUCT_VOICE_RECOGNITION_FAILED")
            Decision.STOP
        }
    }

    /** decode 반환·per-stream 정리 소비 후 owner의 실제 close 결과만 보고한다. */
    fun recognizerClosed(result: KoreanSpeechCleanup) {
        if (completed || closeReported || preparationPending || audioPhase == AudioPhase.RECOGNIZING) return
        closeReported = true
        recognizerReady = false
        streamCleanupConfirmed = streamCleanupConfirmed && result.streamCleanupConfirmed
        recognizerCleanupConfirmed = result.recognizerCleanupConfirmed
        if (!cleanupConfirmed) stop(CLEANUP_FAILED)
    }

    /** engine을 보유한 WAIT도 반환은 금지한다. 중복 완료·늦은 성공은 gate를 재발급하지 않는다. */
    fun complete(now: Long): ProductVoiceCompletion? {
        if (completed) return null
        completed = true
        if (!cleanupConfirmed) stop(CLEANUP_FAILED)
        val accepted = gate?.takeIf {
            stopReason.get() == null && it.phase == FrunkVoicePolicy.Phase.ACCEPTED && it.validTime(now)
        }
        if (accepted == null && stopReason.get() == null) stop(gate?.reason ?: "PRODUCT_VOICE_STOPPED")
        return ProductVoiceCompletion(accepted, microphoneReleased, cleanupConfirmed,
            if (!cleanupConfirmed) CLEANUP_FAILED else stopReason.get())
    }

    private fun failureReason(failure: KoreanSpeechFailure): String = when (failure) {
        KoreanSpeechFailure.MODEL_MISSING -> "PRODUCT_VOICE_MODEL_MISSING"
        KoreanSpeechFailure.LOAD_FAILED -> "PRODUCT_VOICE_MODEL_LOAD_FAILED"
        KoreanSpeechFailure.DECODE_FAILED -> "PRODUCT_VOICE_DECODE_FAILED"
        KoreanSpeechFailure.INVALID_PCM -> "PRODUCT_VOICE_PCM_INCOMPLETE"
        KoreanSpeechFailure.CANCELED -> "PRODUCT_VOICE_STOPPED"
        KoreanSpeechFailure.CLEANUP_FAILED -> CLEANUP_FAILED
    }

    companion object {
        const val CLEANUP_FAILED = "PRODUCT_VOICE_CLEANUP_FAILED_RESTART_REQUIRED"
    }
}
