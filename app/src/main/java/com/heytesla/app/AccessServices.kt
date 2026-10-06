package com.heytesla.app

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.widget.TextView

class PresenceService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val received = SystemClock.elapsedRealtime()
        (application as DiagnosticApp).runtime.presence(event.associationId, event.event, received)
    }
}

class AssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        (application as DiagnosticApp).runtime.assistant = this
        (application as DiagnosticApp).runtime.event("ASSISTANT_READY")
    }

    fun startApproach(session: SessionPolicy.Session) {
        val runtime = (application as DiagnosticApp).runtime
        if (!runtime.assistantActive()) {
            runtime.stop("ASSISTANT_NOT_ACTIVE")
            return
        }
        runtime.launchMicrophone(this, session)
    }

    override fun onShutdown() {
        val runtime = (application as DiagnosticApp).runtime
        if (runtime.assistant === this) {
            runtime.assistant = null
            runtime.stop("ASSISTANT_CHANGED")
        }
        super.onShutdown()
    }

    override fun onDestroy() {
        val runtime = (application as DiagnosticApp).runtime
        if (runtime.assistant === this) {
            runtime.assistant = null
            runtime.stop("ASSISTANT_DESTROYED")
        }
        super.onDestroy()
    }
}

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onCreateContentView() = TextView(context).apply {
            text = "Hey Tesla 로컬 진단\n실차 명령 없음 · 호출어/STT 미구현\n마이크 자동 시작은 등록 차량의 실제 BLE 출현에서만 시도합니다."
            setPadding(32, 32, 32, 32)
        }
    }
}
