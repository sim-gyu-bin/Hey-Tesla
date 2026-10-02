package com.heytesla.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** COMPLETE는 일치하는 utterance ID의 onDone과 출력 해제가 모두 확인된 경우뿐이다. */
enum class SpeechResponseStatus { NOT_STARTED, INITIALIZING, SPEAKING, COMPLETE, FAILED, CANCELED, EXPIRED, SKIPPED }
enum class SpeechResponseReason {
    INIT_FAILED, INIT_TIMEOUT, KOREAN_UNAVAILABLE, OFFLINE_VOICE_UNAVAILABLE, VOICE_SELECTION_FAILED,
    FOCUS_DENIED, FOCUS_LOST, SPEAK_FAILED, PLAYBACK_FAILED, PLAYBACK_STOPPED, PLAYBACK_TIMEOUT,
    API_FAILURE, OUTPUT_CONFIGURATION_FAILED, USER_CANCELED, SESSION_EXPIRED, INPUT_NOT_RELEASED, CLEANUP_FAILED,
}

internal data class SpeechResponseOutcome(
    val status: SpeechResponseStatus = SpeechResponseStatus.NOT_STARTED,
    val reason: SpeechResponseReason? = null,
    val offlineVoiceSelected: Boolean = false,
    val outputReleased: Boolean = true,
    val audioFocusReleased: Boolean = true,
    val cleanupFailed: Boolean = false,
) {
    val releaseUncertain: Boolean get() = !outputReleased || !audioFocusReleased
}

/** 입력이 해제된 진단 소유자만 호출한다. TTS 동안 인식기를 새로 만들거나 입력을 재개하지 않는다. */
internal class SpeechResponse(context: Context, private val utteranceId: String) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var canceled = false
    private val initialized = CompletableDeferred<SpeechResponseReason?>()
    private val playback = CompletableDeferred<SpeechResponseReason?>()

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        canceled = true
        initialized.complete(SpeechResponseReason.USER_CANCELED)
        playback.complete(SpeechResponseReason.USER_CANCELED)
    }

    suspend fun speak(
        result: DiagnosticCommandResult,
        interruption: () -> SpeechResponseReason?,
        publish: (SpeechResponseStatus) -> Unit,
    ): SpeechResponseOutcome {
        check(Looper.myLooper() == Looper.getMainLooper())
        var outcome = SpeechResponseOutcome(SpeechResponseStatus.FAILED, SpeechResponseReason.API_FAILURE)
        var offlineVoice = false
        var audio: AudioManager? = null
        var focus: AudioFocusRequest? = null
        var focusHeld = false
        var outputReleased = true
        var focusReleased = true
        var cleanupFailed = false
        var constructionStarted = false
        suspend fun play(): SpeechResponseOutcome {
            interrupted(interruption)?.let { return disposition(it) }
            publish(SpeechResponseStatus.INITIALIZING)
            // onInit 호출이 생성자 반환보다 빨라도 engine 배정 후 Main에서 처리한다.
            constructionStarted = true
            val tts = TextToSpeech(app) { status ->
                handler.post { initialized.complete(if (status == TextToSpeech.SUCCESS) null else SpeechResponseReason.INIT_FAILED) }
            }
            engine = tts
            await(initialized, INIT_MS, SpeechResponseReason.INIT_TIMEOUT, interruption)?.let { return disposition(it) }
            if (tts.setLanguage(Locale.KOREA) < TextToSpeech.LANG_AVAILABLE) {
                return disposition(SpeechResponseReason.KOREAN_UNAVAILABLE)
            }
            var selected: android.speech.tts.Voice? = null
            for (voice in tts.voices.orEmpty()) {
                if (!voice.isNetworkConnectionRequired && voice.locale.language == Locale.KOREAN.language &&
                    voice.locale.country == Locale.KOREA.country &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty() &&
                    (selected == null || voice.name < selected.name)
                ) selected = voice
            }
            val voice = selected ?: return disposition(SpeechResponseReason.OFFLINE_VOICE_UNAVAILABLE)
            if (tts.setVoice(voice) != TextToSpeech.SUCCESS ||
                tts.voice?.let { !it.isNetworkConnectionRequired && it.locale.language == "ko" && it.locale.country == "KR" } != true
            ) return disposition(SpeechResponseReason.VOICE_SELECTION_FAILED)
            offlineVoice = true
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            if (tts.setAudioAttributes(attributes) != TextToSpeech.SUCCESS) return disposition(SpeechResponseReason.OUTPUT_CONFIGURATION_FAILED)
            val listenerResult = tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { }
                override fun onDone(id: String?) = finish(id, null)
                override fun onError(id: String?) = finish(id, SpeechResponseReason.PLAYBACK_FAILED)
                override fun onError(id: String?, errorCode: Int) = finish(id, SpeechResponseReason.PLAYBACK_FAILED)
                override fun onStop(id: String?, interrupted: Boolean) = finish(id, SpeechResponseReason.PLAYBACK_STOPPED)
                private fun finish(id: String?, reason: SpeechResponseReason?) {
                    if (id == utteranceId) handler.post { playback.complete(reason) }
                }
            })
            if (listenerResult != TextToSpeech.SUCCESS) return disposition(SpeechResponseReason.OUTPUT_CONFIGURATION_FAILED)
            val manager = app.getSystemService(AudioManager::class.java)
                ?: return disposition(SpeechResponseReason.FOCUS_DENIED)
            audio = manager
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener({ change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        playback.complete(SpeechResponseReason.FOCUS_LOST)
                    }
                }, handler).build()
            focus = request
            // 획득 API가 예외를 던져도 포커스가 남았을 수 있으므로 finally에서 반납한다.
            focusHeld = true
            if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                return disposition(SpeechResponseReason.FOCUS_DENIED)
            }
            interrupted(interruption)?.let { return disposition(it) }
            publish(SpeechResponseStatus.SPEAKING)
            if (tts.speak(message(result), TextToSpeech.QUEUE_FLUSH, null, utteranceId) != TextToSpeech.SUCCESS) {
                return disposition(SpeechResponseReason.SPEAK_FAILED)
            }
            val reason = await(playback, PLAY_MS, SpeechResponseReason.PLAYBACK_TIMEOUT, interruption)
            return if (reason == null) SpeechResponseOutcome(SpeechResponseStatus.COMPLETE) else disposition(reason)
        }
        try {
            outcome = play()
        } catch (_: Exception) {
            outcome = disposition(SpeechResponseReason.API_FAILURE)
        } finally {
            val tts = engine
            if (tts != null) {
                try { if (tts.stop() != TextToSpeech.SUCCESS) cleanupFailed = true }
                catch (_: Exception) { cleanupFailed = true }
                try { tts.shutdown() }
                catch (_: Exception) { cleanupFailed = true; outputReleased = false }
            }
            if (tts == null && constructionStarted) {
                outputReleased = false
                cleanupFailed = true
            }
            engine = null
            if (focusHeld) {
                try {
                    if (audio?.abandonAudioFocusRequest(checkNotNull(focus)) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        cleanupFailed = true
                        focusReleased = false
                    }
                } catch (_: Exception) { cleanupFailed = true; focusReleased = false }
            }
            outcome = outcome.copy(
                offlineVoiceSelected = offlineVoice,
                outputReleased = outputReleased,
                audioFocusReleased = focusReleased,
                cleanupFailed = cleanupFailed,
            )
            if (cleanupFailed) outcome = outcome.copy(
                status = SpeechResponseStatus.FAILED,
                reason = outcome.reason ?: SpeechResponseReason.CLEANUP_FAILED,
            )
        }
        return outcome
    }

    private suspend fun await(
        deferred: CompletableDeferred<SpeechResponseReason?>,
        timeout: Long,
        timeoutReason: SpeechResponseReason,
        interruption: () -> SpeechResponseReason?,
    ): SpeechResponseReason? {
        // Wrapper로 성공(null)과 timeout을 구분한다.
        val completed = withTimeoutOrNull(timeout) {
            while (!deferred.isCompleted) {
                interrupted(interruption)?.let { return@withTimeoutOrNull PhaseResult(it) }
                delay(25)
            }
            PhaseResult(interrupted(interruption) ?: deferred.await())
        }
        return if (completed == null) timeoutReason else completed.reason
    }

    private data class PhaseResult(val reason: SpeechResponseReason?)

    private fun interrupted(interruption: () -> SpeechResponseReason?) =
        if (canceled) SpeechResponseReason.USER_CANCELED else interruption()

    private fun disposition(reason: SpeechResponseReason) = SpeechResponseOutcome(
        when (reason) {
            SpeechResponseReason.USER_CANCELED -> SpeechResponseStatus.CANCELED
            SpeechResponseReason.SESSION_EXPIRED -> SpeechResponseStatus.EXPIRED
            else -> SpeechResponseStatus.FAILED
        }, reason,
    )

    private fun message(result: DiagnosticCommandResult) = when (result) {
        DiagnosticCommandResult.PROCESSED -> "프렁크 열기 요청을 로컬 진단으로 처리했습니다. 차량에는 전송하지 않았습니다."
        DiagnosticCommandResult.UNKNOWN -> "로컬 진단 결과를 확인하지 못했습니다. 다시 실행하지 않습니다. 차량에는 전송하지 않았습니다."
        DiagnosticCommandResult.REJECTED -> "진단 명령을 거절했습니다. 차량에는 전송하지 않았습니다."
        else -> "진단을 종료했습니다. 차량에는 전송하지 않았습니다."
    }

    private companion object {
        const val INIT_MS = 5_000L
        const val PLAY_MS = 10_000L
    }
}
