package com.heytesla.app.speech

/** 준비 성공은 native owner 보유이며 full cleanup 성공이 아니다. */
internal data class KoreanSpeechPreparation(
    val owner: PreparedKoreanSpeechRecognizer?,
    val failure: KoreanSpeechFailure?,
    val loadMs: Long?,
    val recognizerCleanupConfirmed: Boolean,
)

/** 한 stream만 닫힌 결과. standalone KoreanSpeechResult의 full cleanup과 혼용하지 않는다. */
internal data class KoreanSpeechStreamResult(
    val text: String?,
    val failure: KoreanSpeechFailure?,
    val decodeMs: Long?,
    val cleanupMs: Long?,
    val consumedBytes: Int,
    val decodeCompleted: Boolean,
    val streamCleanupConfirmed: Boolean,
    val modelReused: Boolean,
)

internal data class KoreanSpeechCleanup(
    val streamCleanupConfirmed: Boolean,
    val recognizerCleanupConfirmed: Boolean,
    val cleanupMs: Long,
)

/**
 * 한 제품 lease의 순차 stream owner. 전역 cache나 수동 인식과 공유하지 않는다.
 * decode와 close는 같은 잠금을 사용하여 실행 중 JNI를 다른 스레드에서 release하지 않는다.
 * 취소는 원문만 봉인하고 실제 JNI 반환과 stream finally를 기다린다.
 */
internal class PreparedKoreanSpeechRecognizer internal constructor(
    private val recognizer: KoreanSpeechRecognizer.Recognizer,
    private val now: () -> Long,
) {
    private var streamClean = true
    private var decoded = false
    private var closed: KoreanSpeechCleanup? = null

    @Synchronized
    fun decode(pcm: ByteArray, size: Int, canceled: () -> Boolean = { false }): KoreanSpeechStreamResult {
        fun rejected(failure: KoreanSpeechFailure) = KoreanSpeechStreamResult(
            null, failure, null, null, 0, false, streamClean, false,
        )
        if (closed != null || !streamClean) return rejected(KoreanSpeechFailure.CLEANUP_FAILED)
        if (size <= 0 || size > pcm.size || size % 2 != 0) return rejected(KoreanSpeechFailure.INVALID_PCM)
        if (canceled()) return rejected(KoreanSpeechFailure.CANCELED)
        val samples = FloatArray(size / 2)
        for (i in samples.indices) {
            val low = pcm[2 * i].toInt() and 0xff
            val high = pcm[2 * i + 1].toInt()
            samples[i] = ((high shl 8) or low) / 32768f
        }
        var stream: KoreanSpeechRecognizer.Stream? = null
        var creatingStream = false
        var text: String? = null
        var failure: KoreanSpeechFailure? = null
        var consumedBytes = 0
        var decodeCompleted = false
        var modelReused = false
        val started = now()
        var decodeMs: Long? = null
        var cleanupMs: Long? = null
        try {
            if (canceled()) {
                failure = KoreanSpeechFailure.CANCELED
            } else {
                creatingStream = true
                val created = recognizer.createStream(SpeechCommandPhrases.sherpaHotwords)
                stream = created
                creatingStream = false
                created.accept(samples, 16_000)
                consumedBytes = size
                if (canceled()) {
                    failure = KoreanSpeechFailure.CANCELED
                } else {
                    modelReused = decoded
                    decoded = true
                    recognizer.decode(created)
                    decodeCompleted = true
                    if (canceled()) failure = KoreanSpeechFailure.CANCELED else text = recognizer.text(created)
                }
            }
        } catch (_: Exception) {
            failure = KoreanSpeechFailure.DECODE_FAILED
            if (creatingStream) streamClean = false
        } catch (_: LinkageError) {
            failure = KoreanSpeechFailure.DECODE_FAILED
            if (creatingStream) streamClean = false
        } finally {
            decodeMs = now() - started
            val cleanupStarted = now()
            samples.fill(0f)
            try {
                stream?.release()
            } catch (_: Exception) {
                streamClean = false
            } catch (_: LinkageError) {
                streamClean = false
            }
            cleanupMs = now() - cleanupStarted
        }
        if (!streamClean && failure == null) failure = KoreanSpeechFailure.CLEANUP_FAILED
        if (canceled()) failure = KoreanSpeechFailure.CANCELED
        return KoreanSpeechStreamResult(text.takeIf { failure == null && streamClean }, failure,
            decodeMs, cleanupMs, consumedBytes, decodeCompleted, streamClean, modelReused)
    }

    /** 각 release는 한 번만 시도한다. 실패 뒤 중복 close가 불명을 성공으로 덮지 않는다. */
    @Synchronized
    fun close(): KoreanSpeechCleanup {
        closed?.let { return it }
        val started = now()
        var recognizerClean = true
        try {
            recognizer.release()
        } catch (_: Exception) {
            recognizerClean = false
        } catch (_: LinkageError) {
            recognizerClean = false
        }
        return KoreanSpeechCleanup(streamClean, recognizerClean, now() - started).also { closed = it }
    }
}
