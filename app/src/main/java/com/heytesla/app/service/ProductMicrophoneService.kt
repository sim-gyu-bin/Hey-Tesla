package com.heytesla.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import com.heytesla.app.speech.product.ProductVoiceCompletion
import com.heytesla.app.speech.product.ProductVoiceController
import com.heytesla.app.speech.product.ProductVoiceDiagnostic
import com.heytesla.app.speech.product.ProductVoiceSession
import com.heytesla.app.speech.product.ProductVoiceStage
import com.heytesla.app.speech.product.ProductVoiceState
import com.heytesla.app.ui.productMicrophoneNotificationText

/** 재생 가능한 Intent는 권한이 아니다. 선택/ready 기본 비서가 발급한 RAM ticket과 runtime lease가 모두 필요하다. */
class ProductMicrophoneService : Service() {
    private val runtime get() = (application as DiagnosticApp).runtime
    private var leaseId: Long? = null
    private var assistantOwner: AssistantService? = null
    private var controller: ProductVoiceController? = null
    private var sealedReason: String? = null
    private var reported = false
    private var destroyed = false
    private var foreground = false
    private var currentState = ProductVoiceState(ProductVoiceStage.PREPARING)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_LEASE, -1L) ?: -1L
        if (intent?.action == ACTION_STOP) {
            if (leaseId == id) {
                stopProduct("PRODUCT_VOICE_NOTIFICATION_STOP")
                runtime.setVehicleServiceEnabled(false)
            } else if (leaseId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        // 활성/정리불명 인스턴스의 중복 start는 기존 소유권을 교체하거나 종료시키지 않는다.
        if (leaseId != null) return START_NOT_STICKY
        val ticket = pending
        if (destroyed || intent?.action != ACTION_START || flags != 0 || ticket == null ||
            ticket.leaseId != id || ticket.consumed
        ) {
            // 무효 전달은 승격/녹음하지 않고 해당 시작 요청을 종료한다. sticky 복원은 없다.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ticket.consumed = true
        leaseId = id
        assistantOwner = ticket.assistant
        try {
            val admitted = runtime.productVoiceServiceStarted(id, this)
            val blocked = if (!admitted) "PRODUCT_VOICE_SERVICE_REJECTED" else allowedReason()
            if (blocked != null) {
                finish(ProductVoiceCompletion(null, true, true, blocked))
                return START_NOT_STICKY
            }
            promote()
            val voice = ProductVoiceController(this, runtime::now, ::allowedReason,
                onState = ::changed, onDiagnostic = ::diagnosed, onFinished = ::finish)
            controller = voice
            if (sealedReason != null) voice.stop(requireNotNull(sealedReason))
            voice.start()
        } catch (_: SecurityException) {
            stopBeforeCapture("PRODUCT_VOICE_MIC_FGS_DENIED")
        } catch (_: android.app.ForegroundServiceStartNotAllowedException) {
            stopBeforeCapture("PRODUCT_VOICE_FGS_START_DENIED")
        } catch (_: Exception) {
            stopBeforeCapture("PRODUCT_VOICE_SERVICE_FAILED")
        }
        return START_NOT_STICKY
    }

    /** generic runtime.stop은 fieldTrial에서 돌아오므로 제품 경로는 반드시 이 봉인을 사용한다. */
    fun stopProduct(reason: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (sealedReason == null) sealedReason = reason
        controller?.stop(requireNotNull(sealedReason))
    }

    private fun stopBeforeCapture(reason: String) {
        stopProduct(reason)
        if (controller == null) finish(ProductVoiceCompletion(null, true, true, reason))
    }

    private fun allowedReason(): String? {
        sealedReason?.let { return it }
        if (destroyed) return "PRODUCT_VOICE_SERVICE_DESTROYED"
        if (assistantOwner?.productVoiceAvailable() != true) return "PRODUCT_VOICE_ASSISTANT_UNAVAILABLE"
        val id = leaseId ?: return "PRODUCT_VOICE_OWNER_LOST"
        return runtime.productVoiceAllowed(id)
    }

    private fun changed(state: ProductVoiceState) {
        if (reported) return
        currentState = state
        leaseId?.let { runtime.productVoiceChanged(it, state) }
        updateNotification()
    }

    private fun updateNotification() {
        if (foreground) {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            } catch (_: Exception) {
                stopProduct("PRODUCT_VOICE_NOTIFICATION_FAILED")
            }
        }
    }

    private fun diagnosed(value: ProductVoiceDiagnostic, text: String?, reason: String?) {
        if (reported) return
        val blocked = try { allowedReason() }
            catch (_: Exception) { "PRODUCT_VOICE_READINESS_UNKNOWN" }
            catch (_: LinkageError) { "PRODUCT_VOICE_READINESS_UNKNOWN" }
        val normal = value.outcome == "EMPTY" || value.outcome == "GRAMMAR_NO_MATCH" || value.outcome == "ACCEPTED"
        // controller의 마지막 검사 직후 owner가 사라진 경우도 원문 대신 실제 봉인 사유만 남긴다.
        val observation = if (normal && blocked != null) value.copy(outcome = "SEALED") else value
        leaseId?.let { runtime.productVoiceDiagnosed(it, this, observation,
            text.takeIf { normal && blocked == null }, if (normal && blocked != null) blocked else reason) }
        updateNotification()
    }

    private fun finish(completion: ProductVoiceCompletion) {
        if (reported) return
        val id = leaseId ?: return
        reported = true
        var final = completion
        // 부모 callback은 곧바로 gate를 소진할 수 있다. FGS/알림 정리까지 callback 이전에 확정한다.
        if (completion.microphoneReleased && completion.cleanupConfirmed) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
                stopSelf()
            } catch (_: Exception) {
                final = completion.copy(gate = null, cleanupConfirmed = false,
                    reason = ProductVoiceSession.CLEANUP_FAILED)
                completion.gate?.cancel(ProductVoiceSession.CLEANUP_FAILED)
            }
        }
        if (!final.microphoneReleased || !final.cleanupConfirmed) {
            final.gate?.cancel(ProductVoiceSession.CLEANUP_FAILED)
            final = final.copy(gate = null, cleanupConfirmed = false, reason = ProductVoiceSession.CLEANUP_FAILED)
            currentState = ProductVoiceState(ProductVoiceStage.FAILED, ProductVoiceSession.CLEANUP_FAILED)
            runtime.productVoiceChanged(id, currentState)
            if (foreground) {
                try {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                } catch (_: Exception) { /* 기존 FGS와 consumed ticket을 유지한다. */ }
            }
        } else {
            if (pending?.leaseId == id) pending = null
            controller = null
        }
        // cleanup불명에서는 controller·consumed ticket·runtime 예약을 보존한다. 재생/재입장하지 않는다.
        runtime.productVoiceFinished(id, this, final)
    }

    private fun promote() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "차량 연결 음성", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        foreground = true
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 4,
            Intent(this, ProductMicrophoneService::class.java).setAction(ACTION_STOP)
                .setIdentifier(leaseId.toString()).putExtra(EXTRA_LEASE, leaseId ?: -1L),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_VOICE_RESULTS)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = productMicrophoneNotificationText(currentState, runtime.state.value.productVoiceRecognitionOutcome)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vehicle_notification)
            .setContentTitle("Hey Tesla 연결 음성")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "서비스 끄기", stop).build())
            .build()
    }

    override fun onDestroy() {
        destroyed = true
        stopProduct("PRODUCT_VOICE_SERVICE_DESTROYED")
        // capture/recognizer 소유자가 끝날 때까지 완료를 조작하거나 scope를 취소하지 않는다.
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "product_microphone"
        private const val NOTIFICATION_ID = 104
        private const val ACTION_START = "com.heytesla.app.START_PRODUCT_VOICE"
        private const val ACTION_STOP = "com.heytesla.app.STOP_PRODUCT_VOICE"
        private const val EXTRA_LEASE = "product_voice_lease"
        private class StartTicket(val leaseId: Long, val assistant: AssistantService, var consumed: Boolean = false)
        private var pending: StartTicket? = null

        /** timeout/attach 전 stop만 폐기한다. 이미 소비된 ticket의 정리불명은 절대로 풀지 않는다. */
        internal fun abandonPending(leaseId: Long) {
            check(Looper.myLooper() == Looper.getMainLooper())
            val ticket = pending
            if (ticket != null && ticket.leaseId == leaseId && !ticket.consumed) pending = null
        }

        /** 백그라운드 FGS 예외의 주체인 selected/ready VoiceInteractionService 인스턴스에서만 발급한다. */
        fun start(context: Context, leaseId: Long): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper())
            val assistant = context as? AssistantService ?: return false
            if (leaseId < 0 || pending != null || !assistant.productVoiceAvailable()) return false
            val runtime = (assistant.application as DiagnosticApp).runtime
            if (runtime.productVoiceAllowed(leaseId) != null) return false
            val ticket = StartTicket(leaseId, assistant)
            pending = ticket
            return try {
                val started = assistant.startForegroundService(Intent(assistant, ProductMicrophoneService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_LEASE, leaseId)) != null
                if (!started && pending === ticket) pending = null
                started
            } catch (_: Exception) {
                if (pending === ticket) pending = null
                false
            }
        }
    }
}
