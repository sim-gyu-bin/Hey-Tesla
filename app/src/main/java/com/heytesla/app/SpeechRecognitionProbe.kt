package com.heytesla.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class SpeechTrialMode(val label: String) {
    DIRECT_MIC("온디바이스 직접 마이크"), BUFFERED_PCM("RAM PCM 인계"),
    TTS_PCM("합성 한국어 파일 PCM"), SILENT_PCM("무음 PCM 대조")
}

internal enum class SpeechTrialStatus(val label: String) {
    IDLE("대기"), STARTING("준비 중"), CAPTURING("캡처 중"), RECOGNIZING("인식 중"),
    COMPLETE("최종 결과 수신"), FAILED("실패"), CANCELED("취소됨"), TIMED_OUT("시간 초과"), CLEANING("자원 정리 중")
}

internal data class SpeechTrialState(
    val status: SpeechTrialStatus = SpeechTrialStatus.IDLE,
    val mode: SpeechTrialMode? = null,
    val active: Boolean = false,
    val reason: String? = null,
    val samples: Long = 0,
    val pcmBytesWritten: Int = 0,
    val finalReceived: Boolean = false,
    val phraseMatched: Boolean? = null,
    val confidence: Float? = null,
    val elapsedMs: Long? = null,
)

/** 원문은 콜백 안에서만 비교한다. 단일 세션은 worker와 RAM 정리까지 lease를 소유한다. */
internal class SpeechRecognitionProbe(
    context: Context,
    private val state: MutableStateFlow<SpeechTrialState>,
    private val runtime: DiagnosticRuntime,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Trial? = null
    private var restartRequired = false

    private class Trial(val mode: SpeechTrialMode, val started: Long) {
        val canceled = AtomicBoolean(false)
        val finishCapture = AtomicBoolean(false)
        val stopWriter = AtomicBoolean(false)
        val captureFailure = AtomicReference<String?>(null)
        val cleanupFailures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val releaseUncertain = AtomicBoolean(false)
        val result = CompletableDeferred<Outcome>()
        var cleaning = false
        var recognizer: SpeechRecognizer? = null
        var pcm: ByteArray? = null
        var size = 0
        var written = 0
        var pipe: Array<ParcelFileDescriptor>? = null
        var writer: Job? = null
        var segmentCount = 0
        var segmentCharacters = 0
        var segmentsMatch = true
        var segmentConfidence: Float? = null
    }

    private data class Outcome(
        val status: SpeechTrialStatus,
        val reason: String,
        val finalReceived: Boolean = false,
        val matched: Boolean? = null,
        val confidence: Float? = null,
    )

    fun start(mode: SpeechTrialMode, koKrInstalled: Boolean) {
        mainThread()
        if (restartRequired) { runtime.event("SPEECH_TRIAL_RESTART_REQUIRED"); return }
        if (current != null) { runtime.event("SPEECH_TRIAL_BUSY"); return }
        val rejected = when {
            !koKrInstalled -> "KO_KR_NOT_INSTALLED"
            !runtime.granted(Manifest.permission.RECORD_AUDIO) -> "MIC_PERMISSION_REQUIRED"
            !onDeviceAvailable() -> "ON_DEVICE_UNAVAILABLE"
            !runtime.acquireSpeechDiagnostic() -> "LEASE_UNAVAILABLE"
            else -> null
        }
        if (rejected != null) {
            state.value = SpeechTrialState(status = SpeechTrialStatus.FAILED, mode = mode, reason = rejected)
            runtime.event("SPEECH_TRIAL_$rejected")
            return
        }
        val trial = Trial(mode, runtime.now())
        current = trial
        state.value = SpeechTrialState(status = SpeechTrialStatus.STARTING, mode = mode, active = true)
        runtime.event("SPEECH_TRIAL_START_${mode.name}")
        scope.launch { runTrial(trial) }
    }

    fun finishCapture() {
        mainThread()
        val trial = current ?: return
        if (!accepts(trial)) return
        if (trial.mode == SpeechTrialMode.BUFFERED_PCM) {
            trial.finishCapture.set(true)
        } else if (state.value.status == SpeechTrialStatus.CAPTURING) {
            try {
                trial.recognizer?.stopListening()
                publish(trial, SpeechTrialStatus.RECOGNIZING)
            } catch (_: Exception) { trial.result.complete(failure("STOP_FAILED")) }
        }
    }

    fun cancel() {
        mainThread()
        val trial = current ?: return
        // 먼저 콜백을 무효화한다. worker와 버퍼는 runTrial의 finally만 정리한다.
        trial.canceled.set(true)
        trial.stopWriter.set(true)
        state.value = state.value.copy(status = SpeechTrialStatus.CLEANING)
        trial.result.complete(Outcome(SpeechTrialStatus.CANCELED, "USER_CANCELED"))
    }

    private suspend fun runTrial(trial: Trial) {
        var outcome = failure("INTERNAL_FAILURE")
        try {
            if (trial.mode == SpeechTrialMode.BUFFERED_PCM) {
                trial.pcm = ByteArray(MAX_BYTES)
                val captureError = withContext(Dispatchers.IO) { capture(trial) }
                if (captureError != null) trial.result.complete(failure(captureError))
                if (trial.size == 0) trial.result.complete(failure("EMPTY_PCM"))
            }
            if (trial.mode == SpeechTrialMode.TTS_PCM || trial.mode == SpeechTrialMode.SILENT_PCM) {
                val fixtureError = withContext(Dispatchers.IO) { loadFixture(trial) }
                if (fixtureError != null) trial.result.complete(failure(fixtureError))
            }
            trial.captureFailure.get()?.let { trial.result.complete(failure(it)) }
            if (accepts(trial)) state.value = state.value.copy(samples = trial.size / 2L)
            if (accepts(trial) && !trial.result.isCompleted) {
                if (!runtime.activityVisible || !runtime.granted(Manifest.permission.RECORD_AUDIO)) {
                    trial.result.complete(failure("MIC_PREREQUISITE_LOST"))
                } else {
                    recognize(trial)
                }
            }
            outcome = withTimeoutOrNull(LIMIT_MS) {
                while (!trial.result.isCompleted) {
                    if (!runtime.activityVisible || !runtime.granted(Manifest.permission.RECORD_AUDIO)) {
                        trial.result.complete(failure("MIC_PREREQUISITE_LOST"))
                    }
                    delay(25)
                }
                trial.result.await()
            } ?: Outcome(SpeechTrialStatus.TIMED_OUT, "RECOGNITION_TIMEOUT")
        } catch (_: Exception) {
            outcome = failure("API_FAILURE")
        } finally {
            trial.cleaning = true
            trial.stopWriter.set(true)
            state.value = state.value.copy(status = SpeechTrialStatus.CLEANING)
            try { trial.recognizer?.cancel() }
            catch (_: Exception) { trial.cleanupFailures.add("RECOGNIZER_CANCEL_FAILED") }
            try { trial.recognizer?.destroy() }
            catch (_: Exception) {
                trial.cleanupFailures.add("RECOGNIZER_DESTROY_FAILED")
                trial.releaseUncertain.set(true)
            }
            trial.recognizer = null
            // 비차단 FD 쓰기만 허용하므로 join은 main을 막지 않고 유한하게 끝난다.
            trial.writer?.join()
            withContext(Dispatchers.IO) {
                trial.pipe?.forEachIndexed { index, descriptor ->
                    try { descriptor.close() }
                    catch (_: Exception) {
                        trial.cleanupFailures.add(if (index == 0) "PCM_READ_FD_CLOSE_FAILED" else "PCM_WRITE_FD_CLOSE_FAILED")
                        trial.releaseUncertain.set(true)
                    }
                }
                trial.pcm?.fill(0)
            }
            trial.pipe = null
            trial.pcm = null
            if (trial.canceled.get()) outcome = Outcome(SpeechTrialStatus.CANCELED, "USER_CANCELED")
            if (trial.cleanupFailures.isNotEmpty()) {
                val disposition = if (trial.releaseUncertain.get()) "RESTART_REQUIRED" else "RESOURCES_RELEASED"
                outcome = outcome.copy(
                    status = SpeechTrialStatus.FAILED,
                    reason = trial.cleanupFailures.distinct().joinToString("_AND_") + "_$disposition",
                )
            }
            state.value = SpeechTrialState(
                status = outcome.status, mode = trial.mode, reason = outcome.reason,
                samples = trial.size / 2L, pcmBytesWritten = trial.written,
                finalReceived = outcome.finalReceived, phraseMatched = outcome.matched,
                confidence = outcome.confidence, elapsedMs = runtime.now() - trial.started,
            )
            current = null
            restartRequired = trial.releaseUncertain.get()
            if (!trial.releaseUncertain.get()) runtime.releaseSpeechDiagnostic()
            runtime.event("SPEECH_TRIAL_${outcome.reason}_${trial.mode.name}")
        }
    }

    private fun loadFixture(trial: Trial): String? {
        var input: java.io.InputStream? = null
        return try {
            if (trial.canceled.get()) return "USER_CANCELED"
            val pcm = ByteArray(MAX_BYTES)
            trial.pcm = pcm
            val stream = app.resources.openRawResource(R.raw.speech_trial_ko)
            input = stream
            trial.size = SpeechPcmFixture.read(stream, pcm) { trial.canceled.get() }
            if (trial.mode == SpeechTrialMode.SILENT_PCM) pcm.fill(0, 0, trial.size)
            null
        } catch (_: Exception) {
            "PCM_FIXTURE_LOAD_FAILED"
        } finally {
            try { input?.close() }
            catch (_: Exception) {
                trial.cleanupFailures.add("PCM_FIXTURE_CLOSE_FAILED")
                trial.releaseUncertain.set(true)
                trial.result.complete(failure("PCM_FIXTURE_CLOSE_FAILED"))
            }
        }
    }

    private suspend fun capture(trial: Trial): String? {
        var record: AudioRecord? = null
        var observer: AudioManager.AudioRecordingCallback? = null
        var error: String? = null
        try {
            if (trial.canceled.get()) return "USER_CANCELED"
            if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return "MIC_PERMISSION_REVOKED"
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return "AUDIO_FORMAT_UNAVAILABLE"
            val audio = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, 6_400)).build()
            record = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            val audioSessionId = audio.audioSessionId
            val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if (configs.any { it.clientAudioSessionId == audioSessionId && it.isClientSilenced }) {
                        trial.captureFailure.compareAndSet(null, "INPUT_SILENCED")
                        if (accepts(trial)) trial.result.complete(failure("INPUT_SILENCED"))
                    }
                }
            }
            observer = callback
            audio.registerAudioRecordingCallback(app.mainExecutor, callback)
            if (trial.canceled.get()) return "USER_CANCELED"
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            val deadline = runtime.now() + LIMIT_MS
            withContext(Dispatchers.Main.immediate) { publish(trial, SpeechTrialStatus.CAPTURING) }
            val pcm = checkNotNull(trial.pcm)
            var lastReport = runtime.now()
            while (!trial.canceled.get() && !trial.finishCapture.get() && trial.size < MAX_BYTES && runtime.now() < deadline) {
                if (!runtime.granted(Manifest.permission.RECORD_AUDIO)) { error = "MIC_PERMISSION_REVOKED"; break }
                if (audio.activeRecordingConfiguration?.isClientSilenced == true) trial.captureFailure.compareAndSet(null, "INPUT_SILENCED")
                trial.captureFailure.get()?.let { error = it }
                if (error != null) break
                val count = audio.read(pcm, trial.size, minOf(3_200, MAX_BYTES - trial.size), AudioRecord.READ_NON_BLOCKING)
                if (count < 0 || count % 2 != 0) { error = "AUDIO_READ_FAILED"; break }
                trial.size += count
                val now = runtime.now()
                if (now - lastReport >= 250) {
                    withContext(Dispatchers.Main.immediate) {
                        if (accepts(trial)) state.value = state.value.copy(samples = trial.size / 2L)
                    }
                    lastReport = now
                }
                delay(10)
            }
            if (!runtime.granted(Manifest.permission.RECORD_AUDIO)) error = "MIC_PERMISSION_REVOKED"
            if (audio.activeRecordingConfiguration?.isClientSilenced == true) error = "INPUT_SILENCED"
        } catch (_: SecurityException) {
            error = "MIC_PERMISSION_REVOKED"
        } catch (_: Exception) {
            error = "AUDIO_CAPTURE_FAILED"
        } finally {
            val audio = record
            if (audio != null) {
                try { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() }
                catch (_: Exception) {
                    error = "AUDIO_STOP_FAILED"
                    trial.cleanupFailures.add("AUDIO_STOP_FAILED")
                }
                try { observer?.let { audio.unregisterAudioRecordingCallback(it) } }
                catch (_: Exception) {
                    error = "AUDIO_CALLBACK_RELEASE_FAILED"
                    trial.cleanupFailures.add("AUDIO_CALLBACK_RELEASE_FAILED")
                }
                try { audio.release() }
                catch (_: Exception) {
                    error = "AUDIO_RELEASE_FAILED"
                    trial.cleanupFailures.add("AUDIO_RELEASE_FAILED")
                    trial.releaseUncertain.set(true)
                }
            }
        }
        return trial.captureFailure.get() ?: error
    }

    private fun recognize(trial: Trial) {
        mainThread()
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        trial.recognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                publish(trial, if (trial.mode == SpeechTrialMode.DIRECT_MIC) SpeechTrialStatus.CAPTURING else SpeechTrialStatus.RECOGNIZING)
            }
            override fun onBeginningOfSpeech() { }
            override fun onRmsChanged(rmsdB: Float) { }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() { publish(trial, SpeechTrialStatus.RECOGNIZING) }
            override fun onError(error: Int) {
                if (accepts(trial)) trial.result.complete(failure("RECOGNIZER_ERROR_$error"))
            }
            override fun onResults(results: Bundle?) {
                if (!accepts(trial)) return
                val first = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (first.isNullOrBlank()) { finishSegments(); return }
                val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
                    ?.takeIf { it.isFinite() && it in 0f..1f }
                trial.result.complete(Outcome(SpeechTrialStatus.COMPLETE, "FINAL_RECEIVED", true,
                    normalize(first) == EXPECTED_TEXT, confidence))
            }
            override fun onSegmentResults(segmentResults: Bundle) {
                if (!accepts(trial)) return
                val first = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (first.isNullOrBlank()) return
                val normalized = normalize(first)
                // 원문은 보관하지 않고 순서·길이가 일치하는지만 누적한다.
                trial.segmentsMatch = trial.segmentsMatch &&
                    normalized.length <= EXPECTED_TEXT.length - trial.segmentCharacters &&
                    EXPECTED_TEXT.regionMatches(trial.segmentCharacters, normalized, 0, normalized.length)
                trial.segmentCharacters = minOf(EXPECTED_TEXT.length + 1, trial.segmentCharacters + normalized.length)
                trial.segmentCount++
                trial.segmentConfidence = if (trial.segmentCount == 1) {
                    segmentResults.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
                        ?.takeIf { it.isFinite() && it in 0f..1f }
                } else null // 서로 다른 구간의 점수를 문장 전체 신뢰도로 합성하지 않는다.
            }
            override fun onEndOfSegmentedSession() = finishSegments()
            private fun finishSegments() {
                if (!accepts(trial)) return
                if (trial.segmentCount == 0) {
                    trial.result.complete(failure("NO_FINAL_RESULT"))
                } else {
                    trial.result.complete(Outcome(
                        SpeechTrialStatus.COMPLETE, "SEGMENTS_RECEIVED_${trial.segmentCount}", true,
                        trial.segmentsMatch && trial.segmentCharacters == EXPECTED_TEXT.length,
                        trial.segmentConfidence,
                    ))
                }
            }
            override fun onPartialResults(partialResults: Bundle?) { }
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        if (trial.mode != SpeechTrialMode.DIRECT_MIC) {
            val pipe = ParcelFileDescriptor.createPipe()
            trial.pipe = pipe
            val fd = pipe[1].fileDescriptor
            Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
        }
        publish(trial, SpeechTrialStatus.STARTING)
        recognizer.startListening(intent)
        if (trial.mode != SpeechTrialMode.DIRECT_MIC) {
            trial.writer = scope.launch(Dispatchers.IO) {
                val descriptor = checkNotNull(trial.pipe)[1]
                val deadline = runtime.now() + LIMIT_MS
                try {
                    val pcm = checkNotNull(trial.pcm)
                    while (!trial.stopWriter.get() && !trial.canceled.get() && trial.written < trial.size && runtime.now() < deadline) {
                        try {
                            val count = Os.write(descriptor.fileDescriptor, pcm, trial.written, minOf(4_096, trial.size - trial.written))
                            trial.written += count
                            withContext(Dispatchers.Main.immediate) {
                                if (accepts(trial)) state.value = state.value.copy(pcmBytesWritten = trial.written)
                            }
                            if (count == 0) delay(10)
                        } catch (e: ErrnoException) {
                            if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) throw e
                            delay(10)
                        }
                    }
                } catch (_: Exception) {
                    trial.result.complete(failure("PCM_WRITE_FAILED"))
                } finally {
                    try { descriptor.close() }
                    catch (_: Exception) {
                        trial.cleanupFailures.add("PCM_WRITE_FD_CLOSE_FAILED")
                        // close() 재호출이 성공해도 최초 close의 실제 해제를 증명하지 못한다.
                        trial.releaseUncertain.set(true)
                    }
                }
            }
        }
    }

    private fun publish(trial: Trial, status: SpeechTrialStatus) {
        if (accepts(trial)) state.value = state.value.copy(status = status)
    }

    private fun accepts(trial: Trial) = current === trial && !trial.cleaning && !trial.canceled.get() && !trial.result.isCompleted
    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun onDeviceAvailable() = try { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) } catch (_: Exception) { false }
    private fun failure(reason: String) = Outcome(SpeechTrialStatus.FAILED, reason)
    private fun normalize(text: String): String = text.filterNot {
        it.isWhitespace() || Character.getType(it) in PUNCTUATION_TYPES
    }

    private companion object {
        const val RATE = 16_000
        const val LIMIT_MS = 10_000L
        const val MAX_BYTES = 320_000
        const val EXPECTED_TEXT = "헤이테슬라프렁크열어줘"
        val PUNCTUATION_TYPES = setOf(
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
        )
    }
}
