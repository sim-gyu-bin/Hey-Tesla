package com.heytesla.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow

internal enum class SpeechProbeStatus(val label: String) {
    IDLE("아직 조회하지 않음"), RUNNING("메타데이터 조회 중 · 최대 10초"),
    PERMISSION_REQUIRED("마이크 권한 필요 · 이 조회는 권한을 요청하지 않습니다"),
    UNAVAILABLE("시스템 온디바이스 인식 서비스 없음"),
    COMPLETE("메타데이터 응답 수신 · 실제 인식 성공 아님"),
    FAILED("조회 실패 · 한국어 지원 여부 미확인"),
    TIMED_OUT("10초 시간 초과 · 재시도 가능"), CANCELED("조회 취소 · 재시도 가능"),
}

internal data class SpeechProbeState(
    val status: SpeechProbeStatus = SpeechProbeStatus.IDLE,
    val available: Boolean? = null,
    val metadata: SpeechSupportMetadata? = null,
    val reason: String? = null,
)

/** Main-thread-only metadata query. Never acquires audio ownership or starts recognition. */
internal class SpeechSupportProbe(
    context: Context,
    private val state: MutableStateFlow<SpeechProbeState>,
    private val event: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var activeRequest: Long? = null
    private var recognizer: SpeechRecognizer? = null
    private var timeout: Runnable? = null

    fun query() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (activeRequest != null) return
        val id = ++generation
        activeRequest = id
        state.value = SpeechProbeState(status = SpeechProbeStatus.RUNNING)
        event("SPEECH_SUPPORT_REQUESTED")
        try {
            if (!hasPermission()) {
                finish(id, SpeechProbeStatus.PERMISSION_REQUIRED, "PERMISSION_REQUIRED")
                return
            }
            val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            state.value = state.value.copy(available = available)
            if (!available) {
                finish(id, SpeechProbeStatus.UNAVAILABLE, "ON_DEVICE_UNAVAILABLE")
                return
            }
            val instance = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            recognizer = instance
            instance.setRecognitionListener(listener(id))
            if (activeRequest != id) return
            val deadline = Runnable { finish(id, SpeechProbeStatus.TIMED_OUT, "TIMEOUT") }
            timeout = deadline
            handler.postDelayed(deadline, 10_000L)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            instance.checkRecognitionSupport(intent, appContext.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (activeRequest != id) return
                    try {
                        if (!hasPermission()) {
                            finish(id, SpeechProbeStatus.PERMISSION_REQUIRED, "PERMISSION_CHANGED")
                            return
                        }
                        val metadata = SpeechSupportMetadata(
                            installed = SpeechSupportPolicy.classify(support.installedOnDeviceLanguages),
                            pending = SpeechSupportPolicy.classify(support.pendingOnDeviceLanguages),
                            downloadable = SpeechSupportPolicy.classify(support.supportedOnDeviceLanguages),
                            online = SpeechSupportPolicy.classify(support.onlineLanguages),
                        )
                        finish(id, SpeechProbeStatus.COMPLETE, "METADATA_RECEIVED", metadata)
                    } catch (_: SecurityException) {
                        finish(id, SpeechProbeStatus.PERMISSION_REQUIRED, "SECURITY_DENIED")
                    } catch (_: Exception) {
                        finish(id, SpeechProbeStatus.FAILED, "METADATA_FAILED")
                    }
                }

                override fun onError(error: Int) = speechError(id, "SUPPORT_ERROR", error)
            })
        } catch (_: SecurityException) {
            finish(id, SpeechProbeStatus.PERMISSION_REQUIRED, "SECURITY_DENIED")
        } catch (_: UnsupportedOperationException) {
            finish(id, SpeechProbeStatus.FAILED, "ON_DEVICE_API_UNAVAILABLE")
        } catch (_: Exception) {
            finish(id, SpeechProbeStatus.FAILED, "REQUEST_FAILED")
        }
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        activeRequest?.let { finish(it, SpeechProbeStatus.CANCELED, "CANCELED") }
    }

    private fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun speechError(id: Long, source: String, error: Int) {
        finish(id, if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            SpeechProbeStatus.PERMISSION_REQUIRED
        } else SpeechProbeStatus.FAILED, "${source}_$error")
    }

    private fun finish(
        id: Long,
        status: SpeechProbeStatus,
        reason: String,
        metadata: SpeechSupportMetadata? = null,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (activeRequest != id) return
        // Invalidate before destroy: destruction may cause reentrant or queued callbacks.
        activeRequest = null
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        val instance = recognizer
        recognizer = null
        var cleanupFailed = false
        try { instance?.destroy() } catch (_: Exception) { cleanupFailed = true }
        state.value = state.value.copy(
            status = if (cleanupFailed) SpeechProbeStatus.FAILED else status,
            metadata = if (cleanupFailed) null else metadata,
            reason = if (cleanupFailed) "DESTROY_FAILED" else reason,
        )
        event("SPEECH_SUPPORT_${state.value.reason}")
    }

    private fun listener(id: Long) = object : RecognitionListener {
        private fun unexpected() = finish(id, SpeechProbeStatus.FAILED, "UNEXPECTED_RECOGNITION_EVENT")
        override fun onError(error: Int) = speechError(id, "RECOGNIZER_ERROR", error)
        override fun onReadyForSpeech(params: Bundle?) = unexpected()
        override fun onBeginningOfSpeech() = unexpected()
        override fun onRmsChanged(rmsdB: Float) = unexpected()
        override fun onBufferReceived(buffer: ByteArray?) = unexpected()
        override fun onEndOfSpeech() = unexpected()
        override fun onResults(results: Bundle?) = unexpected()
        override fun onPartialResults(partialResults: Bundle?) = unexpected()
        override fun onEvent(eventType: Int, params: Bundle?) = unexpected()
        override fun onSegmentResults(segmentResults: Bundle) = unexpected()
        override fun onEndOfSegmentedSession() = unexpected()
        override fun onLanguageDetection(results: Bundle) = unexpected()
    }
}
