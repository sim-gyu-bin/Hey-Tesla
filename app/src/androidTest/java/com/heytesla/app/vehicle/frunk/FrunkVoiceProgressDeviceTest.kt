package com.heytesla.app.vehicle.frunk

import com.heytesla.app.DiagnosticApp
import com.heytesla.app.DiagnosticState
import com.heytesla.app.MainActivity
import com.heytesla.app.speech.SpeechProbeState
import com.heytesla.app.vehicle.key.TeslaKeyOutcome
import com.heytesla.app.vehicle.key.TeslaKeyStage

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heytesla.app.ui.FrunkVoiceActions
import com.heytesla.app.ui.FrunkVoiceDiagnostics
import com.heytesla.app.ui.HeyTeslaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 Compose 표면에 합성 RAM 상태만 전달한다. 차량·마이크·사용자 저장소는 호출하지 않는다. */
@RunWith(AndroidJUnit4::class)
class FrunkVoiceProgressDeviceTest {
    @Test fun progressChangesImmediatelyAndFailureKeepsLastObservedLocationWithoutInventingOpen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as DiagnosticApp
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val voice = MutableStateFlow(FrunkVoiceState(stage = FrunkVoiceStage.CORE, active = true,
            coreStarted = true, coreStage = TeslaKeyStage.CONNECTING))
        var cancels = 0
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    val snapshot by voice.collectAsState()
                    HeyTeslaTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            FrunkVoiceDiagnostics(DiagnosticState(frunkVoice = snapshot), SpeechProbeState(),
                                FrunkVoiceActions(start = { error("실차 시작 금지") }, cancel = { cancels++ },
                                    visible = {}, blockedReason = { "합성 화면 검증" }))
                        }
                    }
                }
            }
            fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString() == text) return node
                for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
                return null
            }
            fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isScrollable) return node
                for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
                return null
            }
            fun awaitText(text: String): AccessibilityNodeInfo {
                instrumentation.waitForIdleSync()
                automation.clearCache()
                repeat(8) {
                    scrollable(automation.rootInActiveWindow)
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    instrumentation.waitForIdleSync()
                    automation.clearCache()
                }
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < deadline) {
                    instrumentation.waitForIdleSync()
                    automation.clearCache()
                    find(automation.rootInActiveWindow, text)?.let { return it }
                    scrollable(automation.rootInActiveWindow)
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(25)
                }
                throw AssertionError("진행 표면 미관측: $text")
            }
            fun show(next: FrunkVoiceState) {
                instrumentation.runOnMainSync { voice.value = next }
                awaitText(next.progressStep().label)
            }
            awaitText(FrunkVoiceStep.CONNECTING.label)
            val reading = voice.value.copy(coreStage = TeslaKeyStage.READING_STATUS)
            show(reading)
            show(reading.copy(frunkFreshParkVerified = true))
            val sent = reading.copy(frunkFreshParkVerified = true, frunkCommandSent = true)
            show(sent)
            val ack = sent.copy(frunkCommandAcknowledged = true)
            show(ack)
            awaitText("미관측")
            var cancel = awaitText("결과 관측 중단 · 전송 철회 불가")
            while (!cancel.isClickable && cancel.parent != null) cancel = cancel.parent
            assertTrue(cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(1, cancels)
            show(ack.copy(stage = FrunkVoiceStage.COMPLETE, active = false, coreStage = TeslaKeyStage.FAILED,
                coreOutcome = TeslaKeyOutcome.UNKNOWN, reason = "FRUNK_OPEN_NOT_OBSERVED", lastCoreStep = FrunkVoiceStep.ACK))
            awaitText("마지막 실제 단계")
            awaitText(FrunkVoiceStep.ACK.label)
            awaitText(frunkVoiceReasonLabel("FRUNK_OPEN_NOT_OBSERVED"))
            automation.clearCache()
            assertNull(find(automation.rootInActiveWindow, FrunkVoiceStep.COMPLETE.label))
            show(ack.copy(stage = FrunkVoiceStage.COMPLETE, active = false, coreStage = TeslaKeyStage.COMPLETE,
                frunkOpenObserved = true))
            show(ack.copy(stage = FrunkVoiceStage.COMPLETE, active = false, coreStage = TeslaKeyStage.CLEANUP_FAILED,
                frunkOpenObserved = true))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
