package com.heytesla.app.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class SpeechMicCaptureResult(
    val bytes: Int,
    val failure: String?,
    val cleanupFailures: List<String>,
    val releaseUncertain: Boolean,
)

/**
 * 16 kHz mono PCM16을 제한된 RAM 버퍼에만 기록하고, 반환 전에 AudioRecord를 해제한다.
 * 호출자는 반환 뒤에만 decode를 시작하며, 이 객체는 오디오를 저장하거나 전송하지 않는다.
 * 실패 사유는 고정 비민감 코드이고, 무음 자동 종료는 호출자가 켤 때만 동작한다.
 */
internal object SpeechMicCapture {
    const val RATE = 16_000
    const val MAX_BYTES = 320_000

    /** 이 RMS 이상이면 말소리로 본다. VOICE_RECOGNITION 전처리 후에도 조용한 차량에서 넘는 보수적 값. */
    private const val VOICE_RMS = 1_000

    /** 말소리가 시작된 뒤 이만큼 조용하면 발화가 끝난 것으로 본다. 기기 검증 전 휴리스틱이다. */
    const val ENDPOINT_SILENCE_MS = 1_000L

    private const val CHUNK_BYTES = 3_200
    private const val REPORT_MS = 250L
    private const val LOOP_MS = 10L

    suspend fun capture(
        context: Context,
        pcm: ByteArray,
        deadlineElapsed: Long,
        canceled: AtomicBoolean,
        finish: AtomicBoolean?,
        stopReason: () -> String?,
        silenceStopMs: Long,
        progress: suspend (Int) -> Unit,
    ): SpeechMicCaptureResult = withContext(Dispatchers.IO) {
        val cleanup = ConcurrentLinkedQueue<String>()
        var releaseUncertain = false
        var bytes = 0
        var failure: String? = null
        var record: AudioRecord? = null
        var callbackRegistered = false
        var callback: AudioManager.AudioRecordingCallback? = null
        val silenced = AtomicBoolean(false)
        try {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                failure = "MIC_PERMISSION_REVOKED"
            } else {
                val minimum = AudioRecord.getMinBufferSize(
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minimum <= 0) {
                    failure = "AUDIO_FORMAT_UNAVAILABLE"
                } else {
                    val audio = AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        )
                        .setBufferSizeInBytes(maxOf(minimum, 6_400))
                        .build()
                    record = audio
                    if (audio.state != AudioRecord.STATE_INITIALIZED) {
                        failure = "AUDIO_CAPTURE_FAILED"
                    } else {
                        val sessionId = audio.audioSessionId
                        val recordingCallback = object : AudioManager.AudioRecordingCallback() {
                            override fun onRecordingConfigChanged(
                                configs: MutableList<AudioRecordingConfiguration>,
                            ) {
                                if (configs.any {
                                        it.clientAudioSessionId == sessionId && it.isClientSilenced
                                    }
                                ) silenced.set(true)
                            }
                        }
                        callback = recordingCallback
                        if (!register(context, audio, recordingCallback)) {
                            failure = "AUDIO_CAPTURE_FAILED"
                        } else {
                            callbackRegistered = true
                            try {
                                audio.startRecording()
                            } catch (_: Exception) {
                                failure = "AUDIO_CAPTURE_FAILED"
                            }
                            if (failure == null) {
                                var lastReport = SystemClock.elapsedRealtime()
                                var voiced = false
                                var lastVoiceAt = lastReport
                                while (true) {
                                    stopReason()?.let { if (failure == null) failure = it }
                                    if (failure == null && canceled.get()) failure = "USER_CANCELED"
                                    if (failure == null && context.checkSelfPermission(
                                            Manifest.permission.RECORD_AUDIO
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) failure = "MIC_PERMISSION_REVOKED"
                                    if (failure != null) break
                                    if (finish?.get() == true || bytes >= pcm.size ||
                                        SystemClock.elapsedRealtime() >= deadlineElapsed
                                    ) break
                                    if (silenced.get() ||
                                        audio.activeRecordingConfiguration?.isClientSilenced == true
                                    ) {
                                        failure = "INPUT_SILENCED"
                                        break
                                    }
                                    val count = audio.read(
                                        pcm,
                                        bytes,
                                        minOf(CHUNK_BYTES, pcm.size - bytes),
                                        AudioRecord.READ_NON_BLOCKING,
                                    )
                                    if (count < 0 || count % 2 != 0) {
                                        failure = "AUDIO_READ_FAILED"
                                        break
                                    }
                                    if (count == 0) {
                                        delay(LOOP_MS)
                                        continue
                                    }
                                    bytes += count
                                    val now = SystemClock.elapsedRealtime()
                                    if (silenceStopMs > 0) {
                                        if (rootMeanSquare(pcm, bytes - count, count) >= VOICE_RMS) {
                                            voiced = true
                                            lastVoiceAt = now
                                        } else if (voiced && now - lastVoiceAt >= silenceStopMs) {
                                            break
                                        }
                                    }
                                    if (now - lastReport >= REPORT_MS) {
                                        lastReport = now
                                        progress(bytes)
                                    }
                                    delay(LOOP_MS)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            failure = "MIC_PERMISSION_REVOKED"
        } catch (_: Exception) {
            if (failure == null) failure = "AUDIO_CAPTURE_FAILED"
        } finally {
            val audio = record
            if (audio != null) {
                if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try {
                        audio.stop()
                    } catch (_: Exception) {
                        cleanup.add("AUDIO_STOP_FAILED")
                    }
                }
                if (callbackRegistered && callback != null) {
                    try {
                        audio.unregisterAudioRecordingCallback(callback)
                    } catch (_: Exception) {
                        cleanup.add("AUDIO_CALLBACK_RELEASE_FAILED")
                    }
                }
                try {
                    audio.release()
                } catch (_: Exception) {
                    cleanup.add("AUDIO_RELEASE_FAILED")
                    releaseUncertain = true
                }
            }
        }
        SpeechMicCaptureResult(bytes, failure, cleanup.toList(), releaseUncertain)
    }

    private fun register(
        context: Context,
        audio: AudioRecord,
        callback: AudioManager.AudioRecordingCallback,
    ): Boolean = try {
        audio.registerAudioRecordingCallback(context.mainExecutor, callback)
        true
    } catch (_: Exception) {
        false
    }

    private fun rootMeanSquare(pcm: ByteArray, offset: Int, count: Int): Int {
        val end = offset + (count and 1.inv())
        if (end <= offset) return 0
        var sum = 0L
        var index = offset
        while (index < end) {
            val value = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xFF)).toShort().toInt()
            sum += value.toLong() * value
            index += 2
        }
        val samples = (end - offset) / 2
        return if (samples <= 0) 0 else kotlin.math.sqrt(sum.toDouble() / samples).toInt()
    }
}
