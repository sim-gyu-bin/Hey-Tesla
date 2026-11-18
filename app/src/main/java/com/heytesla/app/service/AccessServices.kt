package com.heytesla.app

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.ComponentName
import android.os.Looper
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
    private var ready = false
    private var terminal = false

    override fun onReady() {
        super.onReady()
        if (terminal) return
        ready = true
        val runtime = (application as DiagnosticApp).runtime
        runtime.assistant = this
        runtime.event("ASSISTANT_READY")
        runtime.productAssistantReady(this)
    }

    internal fun productVoiceAvailable(): Boolean = ready && !terminal &&
        (application as DiagnosticApp).runtime.assistant === this && try {
            VoiceInteractionService.isActiveService(this, ComponentName(this, AssistantService::class.java))
        } catch (_: Exception) { false }

    fun startProductVoice(leaseId: Long): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return productVoiceAvailable() && ProductMicrophoneService.start(this, leaseId)
    }

    private fun unavailable(reason: String) {
        if (terminal) return
        // terminal부터 봉인한다. 같은 인스턴스의 late onReady가 제품 마이크를 다시 시작하지 못한다.
        terminal = true
        ready = false
        val runtime = (application as DiagnosticApp).runtime
        val wasCurrent = runtime.assistant === this
        runtime.productAssistantUnavailable(this)
        if (wasCurrent) {
            if (runtime.assistant === this) runtime.assistant = null
            runtime.stop(reason)
        }
    }

    override fun onShutdown() {
        unavailable("ASSISTANT_CHANGED")
        super.onShutdown()
    }

    override fun onDestroy() {
        unavailable("ASSISTANT_DESTROYED")
        super.onDestroy()
    }
}

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onCreateContentView() = TextView(context).apply {
            text = "Hey Tesla\n차량 연결·앱 키 인증·신선한 P와 음성 동의가 있을 때 대기합니다.\n현재 상태와 설정은 앱에서 확인하세요."
            setPadding(32, 32, 32, 32)
        }
    }
}
