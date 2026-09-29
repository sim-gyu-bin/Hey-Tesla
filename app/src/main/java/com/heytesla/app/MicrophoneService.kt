package com.heytesla.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.IBinder
import kotlin.math.sqrt

class MicrophoneService : Service() {
    private val runtime get() = (application as DiagnosticApp).runtime
    private var capture: Capture? = null
    private var runningId: Long? = null
    private var tick: Runnable? = null
    private var callback: AudioManager.AudioRecordingCallback? = null

    private class Capture(val record: AudioRecord) {
        val lock = Any()
        @Volatile var closed = false
        fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                try { record.stop() } catch (_: IllegalStateException) { }
                finally { record.release() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runtime.stop("NOTIFICATION_STOP")
            finishCapture()
            return START_NOT_STICKY
        }
        val id = intent?.getLongExtra("session_id", -1) ?: -1
        val session = runtime.policy.current
        if (session == null || session.id != id) {
            // A delayed start must still satisfy the FGS handshake, then terminate without audio.
            try { promote() } catch (_: Exception) { }
            if (runningId == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            return START_NOT_STICKY
        }
        if (runningId != null) return START_NOT_STICKY
        runningId = id
        runtime.microphone = this
        try {
            promote()
            if (session.automatic && !runtime.automaticAllowed()) {
                runtime.stop("AUTO_PREREQUISITE_LOST")
                return START_NOT_STICKY
            }
            if (!session.automatic && !runtime.activityVisible) {
                runtime.stop("MANUAL_UI_HIDDEN")
                return START_NOT_STICKY
            }
            openAudio(id)
            val watchdog = object : Runnable {
                override fun run() {
                    if (!runtime.policy.accepts(id)) return
                    val reason = when {
                        runtime.policy.expired(id, runtime.now()) -> "SESSION_EXPIRED"
                        !runtime.granted(Manifest.permission.RECORD_AUDIO) -> "MIC_PERMISSION_REVOKED"
                        session.automatic && !runtime.automaticAllowed() -> "AUTO_PREREQUISITE_LOST"
                        !session.automatic && !runtime.activityVisible -> "MANUAL_UI_HIDDEN"
                        else -> null
                    }
                    if (reason != null) runtime.stop(reason) else runtime.handler.postDelayed(this, 250)
                }
            }
            tick = watchdog
            runtime.handler.post(watchdog)
        } catch (_: SecurityException) { runtime.stop("MIC_SECURITY_WHILE_IN_USE_DENIED") }
        catch (_: android.app.ForegroundServiceStartNotAllowedException) { runtime.stop("MIC_FGS_START_DENIED") }
        catch (_: Exception) { runtime.stop("AUDIO_INITIALIZATION_FAILED") }
        return START_NOT_STICKY
    }

    private fun promote() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "로컬 마이크 진단", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(this, 1, Intent(this, MicrophoneService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("로컬 마이크 진단 중")
            .setContentText("STT 없음 · 오디오 저장 없음 · 눌러 상태 확인")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "즉시 종료", stop).build())
            .build()
        startForeground(100, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun openAudio(id: Long) {
        val deadline = runtime.policy.current?.takeIf { it.id == id }?.deadline ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runtime.stop("MIC_PERMISSION_REVOKED")
            return
        }
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, 6_400))
            .build()
        val owner = Capture(record)
        capture = owner
        check(record.state == AudioRecord.STATE_INITIALIZED)
        val audioSessionId = record.audioSessionId
        val observer = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                if (!runtime.policy.accepts(id)) return
                configs.firstOrNull { it.clientAudioSessionId == audioSessionId }?.let { runtime.silenced(id, it.isClientSilenced) }
            }
        }
        callback = observer
        record.registerAudioRecordingCallback(mainExecutor, observer)
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        record.activeRecordingConfiguration?.let { runtime.silenced(id, it.isClientSilenced) }
        if (owner.closed) return
        runtime.event("AUDIO_RECORD_STARTED_NOT_RECOGNITION")
        Thread({
            val buffer = ShortArray(1_600)
            var total = 0L
            var sum = 0.0
            var window = 0L
            var lastReport = runtime.now()
            try {
                while (!owner.closed) {
                    if (runtime.now() >= deadline) {
                        owner.close()
                        runtime.handler.post { if (runtime.policy.accepts(id)) runtime.stop("SESSION_EXPIRED") }
                        break
                    }
                    val count = synchronized(owner.lock) {
                        if (owner.closed) 0 else record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    }
                    if (count < 0) {
                        runtime.handler.post { if (runtime.policy.accepts(id)) runtime.stop("AUDIO_READ_ERROR") }
                        break
                    }
                    for (i in 0 until count) {
                        val sample = buffer[i].toDouble() / 32768.0
                        sum += sample * sample
                        buffer[i] = 0
                    }
                    total += count
                    window += count
                    val now = runtime.now()
                    if (now - lastReport >= 250) {
                        val level = if (window == 0L) 0.0 else sqrt(sum / window)
                        val captured = total
                        runtime.handler.post { runtime.samples(id, captured, level) }
                        sum = 0.0
                        window = 0
                        lastReport = now
                    }
                    Thread.sleep(20)
                }
            } catch (_: Exception) {
                runtime.handler.post { if (runtime.policy.accepts(id)) runtime.stop("AUDIO_WORKER_FAILED") }
            } finally {
                buffer.fill(0)
                owner.close()
            }
        }, "local-audio-summary").start()
    }

    fun finishCapture() {
        tick?.let { runtime.handler.removeCallbacks(it) }
        tick = null
        val owner = capture
        val observer = callback
        callback = null
        if (owner != null) {
            if (observer != null) try { owner.record.unregisterAudioRecordingCallback(observer) } catch (_: Exception) { }
            owner.close()
        }
        capture = null
        runningId = null
        if (runtime.microphone === this) runtime.microphone = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val id = runningId
        if (id != null && runtime.policy.accepts(id)) runtime.stop("MIC_SERVICE_DESTROYED")
        finishCapture()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "local_microphone"
        private const val ACTION_STOP = "com.heytesla.app.STOP_MICROPHONE"
    }
}
