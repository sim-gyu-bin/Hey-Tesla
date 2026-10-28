package com.heytesla.app.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Android STT 후보 하나. confidence는 엔진 원값(0.0·-1 포함)이고 배열이 없으면 null이다. */
data class SttHypothesis(val text: String, val confidence: Float?)

/**
 * 사용자가 시작한 시험의 인식 원문 n-best 앞쪽 [MAX]개와 전체 후보 수. 화면 표시 전용 RAM 상태다.
 * 이벤트·필드 로그·파일·복원에는 쓰지 않으며 다음 시험 시작이나 프로세스 종료로 사라진다.
 */
data class SttTranscript(val candidates: List<SttHypothesis>, val total: Int) {
    companion object {
        const val MAX = 3
        fun of(all: List<SttHypothesis>) = SttTranscript(all.take(MAX), all.size)
    }
}

/** segments는 원문이다. 로그에는 마스킹 경로만 쓰고, 화면에는 [SttTranscript]로만 RAM 표시한다. */
internal class AndroidPcmResult(
    val segments: List<List<SttHypothesis>>,
    val failure: String?,
    val acceptedBytes: Int,
    val cleanupFailures: List<String>,
    val releaseUncertain: Boolean,
) {
    val candidates: List<SttHypothesis> by lazy { AndroidPcmRecognizer.combine(segments) }
}

/**
 * 앱이 이미 녹음한 16 kHz mono PCM16을 Android 온디바이스 SpeechRecognizer(ko-KR)에 pipe로 넘긴다.
 * 인식기는 마이크를 열지 않는다. 비분할 pipe 세션은 결과가 비어 와서 segmented session만 쓴다.
 */
internal class AndroidPcmRecognizer(context: Context) {
    private val app = context.applicationContext

    suspend fun recognize(pcm: ByteArray, size: Int, timeoutMs: Long, canceled: () -> Boolean): AndroidPcmResult =
        withContext(Dispatchers.Main.immediate) {
            val segments = mutableListOf<List<SttHypothesis>>()
            val done = CompletableDeferred<String?>()
            val cleanupFailures = Collections.synchronizedList(mutableListOf<String>())
            val releaseUncertain = AtomicBoolean(false)
            val written = AtomicInteger(0)
            val writeCloseAttempted = AtomicBoolean(false)
            var active = true
            var recognizer: SpeechRecognizer? = null
            var pipe: Array<ParcelFileDescriptor>? = null
            var writer: Job? = null
            var failure: String? = null
            try {
                val created = ParcelFileDescriptor.createPipe()
                pipe = created
                val fd = created[1].fileDescriptor
                Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
                val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                recognizer = engine
                engine.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { }
                    override fun onBeginningOfSpeech() { }
                    override fun onRmsChanged(rmsdB: Float) { }
                    override fun onBufferReceived(buffer: ByteArray?) { }
                    override fun onEndOfSpeech() { }
                    override fun onPartialResults(partialResults: Bundle?) { }
                    override fun onEvent(eventType: Int, params: Bundle?) { }
                    override fun onError(error: Int) {
                        if (active) done.complete("RECOGNIZER_ERROR_$error")
                    }
                    override fun onResults(results: Bundle?) {
                        if (!active) return
                        hypotheses(results).takeIf { it.isNotEmpty() }?.let(segments::add)
                        done.complete(null)
                    }
                    override fun onSegmentResults(segmentResults: Bundle) {
                        if (active) hypotheses(segmentResults).takeIf { it.isNotEmpty() }?.let(segments::add)
                    }
                    override fun onEndOfSegmentedSession() {
                        if (active) done.complete(null)
                    }
                })
                engine.startListening(intent(created[0]))
                writer = launch(Dispatchers.IO) {
                    val descriptor = created[1]
                    try {
                        while (written.get() < size) {
                            try {
                                val count = Os.write(descriptor.fileDescriptor, pcm, written.get(),
                                    minOf(WRITE_BYTES, size - written.get()))
                                written.addAndGet(count)
                                if (count == 0) delay(WRITE_RETRY_MS)
                            } catch (e: ErrnoException) {
                                if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) throw e
                                delay(WRITE_RETRY_MS)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        done.complete("PCM_WRITE_FAILED")
                    } finally {
                        // 쓰기 끝을 닫아야 인식기가 EOF를 보고 segmented session을 끝낸다.
                        writeCloseAttempted.set(true)
                        try { descriptor.close() } catch (_: Exception) {
                            cleanupFailures.add("PCM_WRITE_FD_CLOSE_FAILED")
                            releaseUncertain.set(true)
                        }
                    }
                }
                val end = withTimeoutOrNull(timeoutMs) {
                    while (!done.isCompleted) {
                        if (canceled()) done.complete("USER_CANCELED") else delay(CANCEL_POLL_MS)
                    }
                    End(done.await())
                }
                failure = when {
                    end == null -> "RECOGNITION_TIMEOUT"
                    end.failure != null -> end.failure
                    segments.isEmpty() -> "NO_FINAL_RESULT"
                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure = "API_FAILURE"
            } finally {
                active = false
                withContext(NonCancellable) {
                    recognizer?.let { engine ->
                        try { engine.cancel() } catch (_: Exception) { cleanupFailures.add("RECOGNIZER_CANCEL_FAILED") }
                        try { engine.destroy() } catch (_: Exception) {
                            cleanupFailures.add("RECOGNIZER_DESTROY_FAILED")
                            releaseUncertain.set(true)
                        }
                    }
                    // O_NONBLOCK write만 하므로 취소된 writer는 다음 반복에서 바로 끝난다.
                    // 시작 전에 취소된 writer는 본문 finally를 타지 않으므로 여기서 쓰기 끝을 닫는다.
                    writer?.cancelAndJoin()
                    pipe?.let { ends ->
                        if (writeCloseAttempted.compareAndSet(false, true)) {
                            closeEnd(ends[1], "PCM_WRITE_FD_CLOSE_FAILED", cleanupFailures, releaseUncertain)
                        }
                        closeEnd(ends[0], "PCM_READ_FD_CLOSE_FAILED", cleanupFailures, releaseUncertain)
                    }
                }
            }
            AndroidPcmResult(segments.toList(), failure, written.get(), cleanupFailures.toList(), releaseUncertain.get())
        }

    private fun intent(source: ParcelFileDescriptor) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_HYPOTHESES)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
        .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SpeechMicCapture.RATE)

    private class End(val failure: String?)

    companion object {
        /** 엔진은 EXTRA_MAX_RESULTS를 무시하고 2~3개를 돌려준다. 조합 폭을 고정하려고 segment당 3개만 쓴다. */
        const val MAX_HYPOTHESES = 3
        const val MAX_COMBINED_SEGMENTS = 3
        private const val WRITE_BYTES = 4_096
        private const val WRITE_RETRY_MS = 10L
        private const val CANCEL_POLL_MS = 25L

        /**
         * segment별 n-best를 문장 후보로 조합한다. 순위 합이 작은 조합이 앞이고 같으면 앞 segment 순위가 우선한다.
         * 점수는 구성 후보의 최솟값이며 하나라도 없으면 null이다. segment가 3개를 넘으면 각 1순위만 잇는다.
         */
        fun combine(segments: List<List<SttHypothesis>>): List<SttHypothesis> {
            val usable = segments.map { segment -> segment.filter { it.text.isNotBlank() }.take(MAX_HYPOTHESES) }
                .filter { it.isNotEmpty() }
            if (usable.isEmpty()) return emptyList()
            val choices = if (usable.size > MAX_COMBINED_SEGMENTS) usable.map { listOf(it.first()) } else usable
            var ranks = listOf(emptyList<Int>())
            for (segment in choices) ranks = ranks.flatMap { prefix -> segment.indices.map { prefix + it } }
            return ranks.sortedBy { it.sum() }.map { picked ->
                val parts = picked.mapIndexed { index, rank -> choices[index][rank] }
                val scores = parts.map { it.confidence }
                SttHypothesis(parts.joinToString(" ") { it.text.trim() },
                    if (scores.any { it == null }) null else scores.minOf { requireNotNull(it) })
            }
        }

        fun texts(candidates: List<SttHypothesis>): List<String> = candidates.map { it.text }

        /** 정책 입력용. 모든 후보에 점수가 없으면 null, 일부만 없으면 그 자리를 NaN(차단)으로 둔다. */
        fun scores(candidates: List<SttHypothesis>): FloatArray? =
            if (candidates.all { it.confidence == null }) null
            else FloatArray(candidates.size) { candidates[it].confidence ?: Float.NaN }

        private fun hypotheses(bundle: Bundle?): List<SttHypothesis> {
            val texts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return emptyList()
            val scores = bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            return texts.take(MAX_HYPOTHESES).mapIndexedNotNull { index, text ->
                text?.takeIf { it.isNotBlank() }?.let { SttHypothesis(it, scores?.getOrNull(index)) }
            }
        }

        private fun closeEnd(end: ParcelFileDescriptor, code: String,
            failures: MutableList<String>, uncertain: AtomicBoolean) {
            try { end.close() } catch (_: Exception) {
                failures.add(code)
                // close() 재호출이 성공해도 최초 close의 실제 해제를 증명하지 못한다.
                uncertain.set(true)
            }
        }
    }
}
