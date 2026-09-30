package com.heytesla.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.companion.ObservingDevicePresenceRequest
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** 사용자 ON 요청의 FGS·CDM 소유자. 오디오를 열거나 종료된 요청을 복원하지 않는다. */
class ObservationService : Service() {
    private val runtime get() = (application as DiagnosticApp).runtime
    private var requestId: String? = null
    private var lastStartId = 0
    internal var observedAssociationId: Int? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val token = intent?.getStringExtra(REQUEST_ID)
        if (intent?.action == ACTION_STOP) {
            if (token != null && runtime.ownsObservation(this, token)) {
                runtime.stopObservation("OBSERVATION_NOTIFICATION_STOP", token)
            } else if (requestId == null) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (token != null && token == requestId && runtime.ownsObservation(this, token)) {
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || token == null || !runtime.acceptsObservationRequest(token)) {
            // 지연된 startForegroundService도 handshake만 충족한다. 관찰·동의는 복원하지 않는다.
            if (requestId == null) {
                try { promote(token ?: "unarmed") } catch (_: Exception) { }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            runtime.event("OBSERVATION_STALE_START_IGNORED")
            return START_NOT_STICKY
        }

        requestId = token
        try {
            promote(token)
            runtime.refresh()
            if (!runtime.acceptsObservationRequest(token)) {
                finishObserving()
                return START_NOT_STICKY
            }
            runtime.observationBlockedReason()?.let { fail(it, token); return START_NOT_STICKY }
            if (!runtime.observationStarted(this, token)) {
                finishObserving()
                return START_NOT_STICKY
            }
            startObserving()
        } catch (_: SecurityException) {
            fail("OBSERVATION_FGS_SECURITY_DENIED", token)
        } catch (_: android.app.ForegroundServiceStartNotAllowedException) {
            fail("OBSERVATION_FGS_BACKGROUND_START_DENIED", token)
        } catch (_: Exception) {
            fail("OBSERVATION_FGS_START_FAILED", token)
        }
        return START_NOT_STICKY
    }

    private fun promote(token: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "등록 차량 접근 관찰", NotificationManager.IMPORTANCE_LOW),
        )
        // identifier가 다른 이전 시험의 알림 종료 intent는 새 요청을 종료할 수 없다.
        val stopIntent = Intent(this, ObservationService::class.java)
            .setAction(ACTION_STOP).setIdentifier(token).putExtra(REQUEST_ID, token)
        val stop = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("등록 차량 접근 관찰 서비스")
            .setContentText("실제 BLE 감지는 별도 확인 · 자동 마이크는 별도 동의")
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "관찰 즉시 종료", stop).build())
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    internal fun startObserving() {
        runtime.refresh()
        val token = requestId ?: return
        if (!runtime.ownsObservation(this, token) || observedAssociationId != null) return
        runtime.observationBlockedReason()?.let { fail(it, token); return }
        val id = runtime.state.value.associations.single()
        try {
            // 요청 도중 예외가 나도 같은 대상에 해제를 시도한다. 수락 상태는 반환 뒤에만 켠다.
            observedAssociationId = id
            runtime.cdm?.startObservingDevicePresence(
                ObservingDevicePresenceRequest.Builder().setAssociationId(id).build(),
            ) ?: error("CDM")
            runtime.observationAccepted(this, token)
        } catch (_: SecurityException) {
            fail("OBSERVE_SECURITY_DENIED", token)
        } catch (_: Exception) {
            fail("OBSERVE_UNAVAILABLE", token)
        }
    }

    private fun fail(reason: String, token: String) {
        runtime.stopObservation(reason, token)
        finishObserving()
    }

    internal fun finishObserving() {
        val id = observedAssociationId
        observedAssociationId = null
        requestId = null
        if (id != null) {
            var failed = false
            try {
                runtime.cdm?.stopObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(id).build(),
                ) ?: error("CDM")
            } catch (_: Exception) {
                failed = true
            }
            runtime.event(if (failed) "OBSERVE_STOP_FAILED_LOCAL_GATE_CLOSED" else "OBSERVE_STOPPED")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (lastStartId != 0) stopSelf(lastStartId) else stopSelf()
    }

    override fun onDestroy() {
        val token = requestId
        if (token != null && runtime.ownsObservation(this, token)) {
            runtime.stopObservation("OBSERVATION_SERVICE_DESTROYED", token)
        }
        finishObserving()
        super.onDestroy()
    }

    companion object {
        internal const val CHANNEL = "vehicle_observation"
        internal const val ACTION_START = "com.heytesla.app.START_OBSERVATION"
        internal const val ACTION_STOP = "com.heytesla.app.STOP_OBSERVATION"
        internal const val REQUEST_ID = "observation_request_id"
        private const val NOTIFICATION_ID = 101
    }
}
