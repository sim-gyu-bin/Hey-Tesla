package com.heytesla.app.speech.product

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class ProductCaptureResult(
    val failure: String?,
    val microphoneReleased: Boolean,
    val cleanupConfirmed: Boolean,
)

/** 하나의 AudioRecord로 무기한 idle을 기다린다. 반환 시에는 callback·AudioRecord 해제를 모두 시도했다. */
internal object ProductMicCapture {
    suspend fun capture(
        context: Context,
        audioManager: AudioManager?,
        utterance: ProductUtteranceBuffer,
        now: () -> Long,
        stopped: AtomicReference<String?>,
        onListening: suspend () -> Unit,
    ): ProductCaptureResult = withContext(Dispatchers.IO) {
        var record: AudioRecord? = null
        var callback: AudioManager.AudioRecordingCallback? = null
        var callbackMayBeRegistered = false
        val callbackActive = AtomicBoolean(true)
        var microphoneReleased = true
        var cleanupConfirmed = true
        var failure: String? = null
        val chunk = ByteArray(ProductUtteranceBuffer.CHUNK_BYTES)
        try {
            safetyReason(context, audioManager)?.let { stopped.compareAndSet(null, it) }
            if (stopped.get() == null) {
                val minimum = AudioRecord.getMinBufferSize(ProductUtteranceBuffer.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                microphoneReleased = false
                val audio = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(ProductUtteranceBuffer.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum, 6_400))
                    .build()
                record = audio
                check(audio.state == AudioRecord.STATE_INITIALIZED)
                val sessionId = audio.audioSessionId
                val observer = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if (callbackActive.get() &&
                            configs.any { it.clientAudioSessionId == sessionId && it.isClientSilenced }) {
                            stopped.compareAndSet(null, "INPUT_SILENCED")
                        }
                    }
                }
                callback = observer
                callbackMayBeRegistered = true
                audio.registerAudioRecordingCallback(context.mainExecutor, observer)
                safetyReason(context, audioManager)?.let { stopped.compareAndSet(null, it) }
                if (stopped.get() == null) {
                    audio.startRecording()
                    check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    if (audio.activeRecordingConfiguration?.isClientSilenced == true) {
                        stopped.compareAndSet(null, "INPUT_SILENCED")
                    }
                    var lastConfigurationAt = now()
                    if (stopped.get() == null) onListening()
                    while (stopped.get() == null) {
                        val observedAt = now()
                        // callback이 우선이다. 보조 Binder 조회는 Main 안전 watchdog과 같은 최대 10Hz다.
                        if (observedAt - lastConfigurationAt >= CONFIGURATION_POLL_MS) {
                            lastConfigurationAt = observedAt
                            if (audio.activeRecordingConfiguration?.isClientSilenced == true) {
                                stopped.compareAndSet(null, "INPUT_SILENCED")
                            }
                        }
                        if (stopped.get() != null) break
                        when (utterance.checkTime(observedAt)) {
                            ProductUtteranceBuffer.Status.TRUNCATED -> {
                                failure = "PRODUCT_VOICE_UTTERANCE_TRUNCATED"
                                break
                            }
                            ProductUtteranceBuffer.Status.CLOCK_REVERSED -> {
                                failure = "PRODUCT_VOICE_CLOCK_REVERSED"
                                break
                            }
                            else -> Unit
                        }
                        val count = audio.read(chunk, 0, chunk.size, AudioRecord.READ_NON_BLOCKING)
                        if (count < 0 || count % 2 != 0) {
                            failure = "AUDIO_READ_FAILED"
                            break
                        }
                        val status = utterance.append(chunk, count, now())
                        chunk.fill(0)
                        when (status) {
                            ProductUtteranceBuffer.Status.COMPLETE -> break
                            ProductUtteranceBuffer.Status.TRUNCATED -> {
                                failure = "PRODUCT_VOICE_UTTERANCE_TRUNCATED"
                                break
                            }
                            ProductUtteranceBuffer.Status.CLOCK_REVERSED -> {
                                failure = "PRODUCT_VOICE_CLOCK_REVERSED"
                                break
                            }
                            else -> delay(10)
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            failure = "MIC_PERMISSION_REVOKED"
        } catch (_: Exception) {
            failure = "AUDIO_CAPTURE_FAILED"
        } finally {
            callbackActive.set(false)
            chunk.fill(0)
            val audio = record
            if (audio != null) {
                try {
                    if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
                } catch (_: Exception) {
                    cleanupConfirmed = false
                }
                if (callbackMayBeRegistered && callback != null) {
                    try { audio.unregisterAudioRecordingCallback(callback) } catch (_: Exception) {
                        cleanupConfirmed = false
                    }
                }
                try {
                    audio.release()
                    microphoneReleased = true
                } catch (_: Exception) {
                    microphoneReleased = false
                }
            }
        }
        ProductCaptureResult(stopped.get() ?: failure, microphoneReleased,
            cleanupConfirmed && microphoneReleased)
    }

    /** 통화 권한 없이 OS 오디오 mode만 관측한다. 통화/통신/벨소리 모드는 모두 중단한다. */
    fun safetyReason(context: Context, audioManager: AudioManager?): String? = try {
        when {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "MIC_PERMISSION_REVOKED"
            audioManager?.mode != AudioManager.MODE_NORMAL ->
                "PRODUCT_VOICE_AUDIO_MODE_BLOCKED"
            else -> null
        }
    } catch (_: Exception) {
        "PRODUCT_VOICE_AUDIO_STATE_UNKNOWN"
    }

    private const val CONFIGURATION_POLL_MS = 100L
}
