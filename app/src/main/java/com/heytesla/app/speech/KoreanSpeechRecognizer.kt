package com.heytesla.app.speech

import android.content.res.AssetManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

/**
 * sherpa-onnx 그림자 인식 한 회차. 차량 제어·명령 판정에 쓰지 않는다.
 * [text]는 화면 RAM 표시 전용이며 로그·요약·이벤트·파일에 넣지 않는다.
 */
internal data class ShadowTranscript(
    val text: String?,
    val failure: String?,
    val loadMs: Long?,
    val decodeMs: Long?,
    /** 정규화 원문이 기대 문장과 같은지. 원문이 비었으면 null. */
    val matched: Boolean? = null,
    val decision: SpeechCommandDecision? = null,
) {
    /** 원문을 Android 결과와 같은 정규화·문법 판정에 넣은 비교값. 비었거나 너무 긴 원문은 판정하지 않는다. */
    fun judged(expected: String): ShadowTranscript {
        val normalized = text?.let(SpeechCommandParser::normalize).orEmpty()
        if (normalized.isEmpty()) return this
        return copy(
            matched = normalized == expected,
            decision = normalized.takeIf { it.length <= SpeechCommandParser.MAX_NORMALIZED_LENGTH }
                ?.let(SpeechCommandParser::decideNormalized),
        )
    }
}

internal enum class KoreanSpeechFailure {
    INVALID_PCM, MODEL_MISSING, LOAD_FAILED, DECODE_FAILED, CANCELED, CLEANUP_FAILED,
}

/** 원문은 호출자의 RAM에서만 사용한다. 완료·정리는 JNI 호출과 두 자원 해제의 실제 관측이다. */
internal data class KoreanSpeechResult(
    val text: String?,
    val failure: KoreanSpeechFailure?,
    val loadMs: Long?,
    val decodeMs: Long?,
    val consumedBytes: Int,
    val decodeCompleted: Boolean,
    val cleanupConfirmed: Boolean,
) {
    fun shadowTranscript() = ShadowTranscript(text, failure?.let { "SHADOW_${it.name}" }, loadMs, decodeMs)
}

/**
 * 제품·비교진단이 공유하는 한국어 오프라인 zipformer(KsponSpeech) 인식기.
 * 제품은 한 활성 lease 안에서만 prepared owner를 재사용한다. standalone decode는 매번 완전히 닫는다.
 */
internal object KoreanSpeechRecognizer {
    private const val DIR = "zipformer-ko"
    private const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
    private const val DECODER = "decoder-epoch-99-avg-1.int8.onnx"
    private const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
    private const val TOKENS = "tokens.txt"
    private const val BPE_VOCAB = "bpe.vocab"
    private const val SAMPLE_RATE = 16_000

    /** 모델·stream의 실제 생성/해제를 관측하는 공통 JNI 주입 경계. */
    interface Backend {
        fun modelsAvailable(): Boolean
        fun load(): Recognizer
    }

    interface Recognizer {
        fun createStream(hotwords: String): Stream
        fun decode(stream: Stream)
        fun text(stream: Stream): String
        fun release()
    }

    interface Stream {
        fun accept(samples: FloatArray, sampleRate: Int)
        fun release()
    }

    /**
     * PCM16 LE mono 16 kHz [pcm]의 앞 [size] 바이트를 인식한다. Main 밖에서 호출한다.
     * 취소는 native를 강제 중단하지 않는다. JNI 반환과 정리를 기다린 뒤 원문을 폐기한다.
     */
    fun decode(
        assets: AssetManager,
        pcm: ByteArray,
        size: Int,
        canceled: () -> Boolean = { false },
    ): KoreanSpeechResult = decode(pcm, size, AssetBackend(assets), SystemClock::elapsedRealtime, canceled)

    fun decode(
        pcm: ByteArray,
        size: Int,
        backend: Backend,
        now: () -> Long,
        canceled: () -> Boolean = { false },
    ): KoreanSpeechResult {
        if (size <= 0 || size > pcm.size || size % 2 != 0) {
            return KoreanSpeechResult(null, KoreanSpeechFailure.INVALID_PCM, null, null, 0, false, true)
        }
        val preparation = prepare(backend, now, canceled)
        val owner = preparation.owner ?: return KoreanSpeechResult(
            null, preparation.failure, preparation.loadMs, null, 0, false,
            preparation.recognizerCleanupConfirmed,
        )
        lateinit var cleanup: KoreanSpeechCleanup
        val result = try {
            owner.decode(pcm, size, canceled)
        } finally {
            cleanup = owner.close()
        }
        val clean = cleanup.streamCleanupConfirmed && cleanup.recognizerCleanupConfirmed
        val failure = when {
            canceled() -> KoreanSpeechFailure.CANCELED
            result.failure != null -> result.failure
            !clean -> KoreanSpeechFailure.CLEANUP_FAILED
            else -> null
        }
        return KoreanSpeechResult(
            text = result.text.takeIf { failure == null && clean },
            failure = failure,
            loadMs = preparation.loadMs,
            decodeMs = result.decodeMs,
            consumedBytes = result.consumedBytes,
            decodeCompleted = result.decodeCompleted,
            cleanupConfirmed = clean,
        )
    }

    /** Main 밖에서 준비한다. 생성 중 취소도 실제 JNI 반환·해제까지 기다린다. */
    fun prepare(
        assets: AssetManager,
        canceled: () -> Boolean = { false },
    ): KoreanSpeechPreparation = prepare(AssetBackend(assets), SystemClock::elapsedRealtime, canceled)

    fun prepare(
        backend: Backend,
        now: () -> Long,
        canceled: () -> Boolean = { false },
    ): KoreanSpeechPreparation {
        if (canceled()) return KoreanSpeechPreparation(null, KoreanSpeechFailure.CANCELED, null, true)
        val present = try {
            backend.modelsAvailable()
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (!present) return KoreanSpeechPreparation(null, KoreanSpeechFailure.MODEL_MISSING, null, true)
        if (canceled()) return KoreanSpeechPreparation(null, KoreanSpeechFailure.CANCELED, null, true)
        val started = now()
        val recognizer = try {
            backend.load()
        } catch (_: Exception) {
            return KoreanSpeechPreparation(null, KoreanSpeechFailure.LOAD_FAILED, now() - started, false)
        } catch (_: LinkageError) {
            return KoreanSpeechPreparation(null, KoreanSpeechFailure.LOAD_FAILED, now() - started, false)
        }
        val loadMs = now() - started
        val owner = PreparedKoreanSpeechRecognizer(recognizer, now)
        if (canceled()) {
            val cleanup = owner.close()
            return KoreanSpeechPreparation(null, KoreanSpeechFailure.CANCELED, loadMs,
                cleanup.recognizerCleanupConfirmed)
        }
        return KoreanSpeechPreparation(owner, null, loadMs, false)
    }

    private class AssetBackend(private val assets: AssetManager) : Backend {
        override fun modelsAvailable(): Boolean {
            val present = assets.list(DIR)?.toSet().orEmpty()
            return present.containsAll(listOf(ENCODER, DECODER, JOINER, TOKENS, BPE_VOCAB))
        }

        override fun load(): Recognizer = NativeRecognizer(OfflineRecognizer(assets, config()))
    }

    private class NativeRecognizer(private val recognizer: OfflineRecognizer) : Recognizer {
        override fun createStream(hotwords: String): Stream = NativeStream(recognizer.createStream(hotwords))
        override fun decode(stream: Stream) = recognizer.decode((stream as NativeStream).stream)
        override fun text(stream: Stream): String = recognizer.getResult((stream as NativeStream).stream).text
        override fun release() = recognizer.release()
    }

    private class NativeStream(val stream: OfflineStream) : Stream {
        override fun accept(samples: FloatArray, sampleRate: Int) = stream.acceptWaveform(samples, sampleRate)
        override fun release() = stream.release()
    }

    private fun config() = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "$DIR/$ENCODER",
                decoder = "$DIR/$DECODER",
                joiner = "$DIR/$JOINER",
            ),
            tokens = "$DIR/$TOKENS",
            numThreads = 2,
            provider = "cpu",
            modelType = "transducer",
            modelingUnit = "bpe",
            bpeVocab = "$DIR/$BPE_VOCAB",
        ),
        decodingMethod = "modified_beam_search",
        maxActivePaths = 16,
        hotwordsScore = 3.0f,
    )
}
