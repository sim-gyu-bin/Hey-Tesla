package com.heytesla.app

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

/**
 * 진단 전용 한국어 오프라인 zipformer(KsponSpeech) 그림자 인식기.
 * 회차마다 APK asset에서 모델을 올리고 끝나면 해제한다. 네트워크·마이크를 쓰지 않으며 호출자 PCM만 읽는다.
 */
internal object SherpaShadowRecognizer {
    private const val DIR = "zipformer-ko"
    private const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
    private const val DECODER = "decoder-epoch-99-avg-1.int8.onnx"
    private const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
    private const val TOKENS = "tokens.txt"
    private const val BPE_VOCAB = "bpe.vocab"
    private const val SAMPLE_RATE = 16_000

    /**
     * PCM16 LE mono 16 kHz [pcm]의 앞 [size] 바이트를 인식한다. 블로킹이므로 Main 밖에서 부른다.
     * 네이티브 코드는 빠진 asset을 만나면 프로세스를 끝내므로 생성 전에 이름을 모두 확인한다.
     */
    fun decode(assets: AssetManager, pcm: ByteArray, size: Int): ShadowTranscript {
        val present = try {
            assets.list(DIR)?.toSet().orEmpty()
        } catch (_: Exception) {
            emptySet()
        }
        if (!present.containsAll(listOf(ENCODER, DECODER, JOINER, TOKENS, BPE_VOCAB))) {
            return ShadowTranscript(null, "SHADOW_MODEL_MISSING", null, null)
        }
        val samples = FloatArray(size.coerceIn(0, pcm.size) / 2)
        for (i in samples.indices) {
            val low = pcm[2 * i].toInt() and 0xff
            val high = pcm[2 * i + 1].toInt()
            samples[i] = ((high shl 8) or low) / 32768f
        }
        var recognizer: OfflineRecognizer? = null
        var stream: OfflineStream? = null
        try {
            val loadStarted = SystemClock.elapsedRealtime()
            recognizer = try {
                OfflineRecognizer(assets, config())
            } catch (_: Exception) {
                return ShadowTranscript(null, "SHADOW_LOAD_FAILED", null, null)
            } catch (_: LinkageError) {
                return ShadowTranscript(null, "SHADOW_LOAD_FAILED", null, null)
            }
            val loadMs = SystemClock.elapsedRealtime() - loadStarted
            val decodeStarted = SystemClock.elapsedRealtime()
            return try {
                val created = recognizer.createStream(SpeechCommandPhrases.sherpaHotwords)
                stream = created
                created.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(created)
                val text = recognizer.getResult(created).text
                ShadowTranscript(text, null, loadMs, SystemClock.elapsedRealtime() - decodeStarted)
            } catch (_: Exception) {
                ShadowTranscript(null, "SHADOW_DECODE_FAILED", loadMs, null)
            }
        } finally {
            samples.fill(0f)
            stream?.release()
            recognizer?.release()
        }
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
