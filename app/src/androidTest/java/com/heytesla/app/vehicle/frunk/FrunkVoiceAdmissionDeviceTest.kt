package com.heytesla.app.vehicle.frunk

import com.heytesla.app.DiagnosticApp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 runtime 입장 차단 검증. 마이크·차량 연결·차량 명령을 발급하지 않는다. */
@RunWith(AndroidJUnit4::class)
class FrunkVoiceAdmissionDeviceTest {
    @Test fun noConsentOrMissingModelCannotReserveAudioOrIssueVehicleCommand() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val app = instrumentation.targetContext.applicationContext as DiagnosticApp
            val runtime = app.runtime
            val probe = FrunkVoiceProbe(app, runtime)
            for ((consent, model, reason) in listOf(
                Triple(false, true, "FRUNK_VOICE_CONSENT_REQUIRED"),
                Triple(true, false, "FRUNK_VOICE_MODEL_REQUIRED"),
            )) {
                probe.start(consent, model)
                assertEquals(FrunkVoiceStage.STOPPED, runtime.state.value.frunkVoice.stage)
                assertEquals(reason, runtime.state.value.frunkVoice.reason)
                assertFalse(runtime.state.value.frunkVoice.coreStarted)
                assertFalse(runtime.state.value.speechDiagnosticActive)
                assertFalse(runtime.teslaKeyReserved())
                assertFalse(runtime.teslaKeyState.value.frunkCommandSent)
                probe.cancel()
            }
        }
    }
}
