package com.heytesla.app.vehicle.frunk

import com.heytesla.app.DiagnosticApp
import com.heytesla.app.DiagnosticRuntime
import com.heytesla.app.app.SessionPolicy
import com.heytesla.app.speech.AndroidPcmRecognizer
import com.heytesla.app.speech.AndroidPcmResult
import com.heytesla.app.speech.SpeechTrialMode
import com.heytesla.app.speech.SttHypothesis
import com.heytesla.app.vehicle.key.TeslaBleKeyStore
import com.heytesla.app.vehicle.key.VehicleVinStore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 마지막 handoff 경계. 인식 결과만 구성하며 마이크·인식기·차량 core·생산 저장소는 열지 않는다. */
@RunWith(AndroidJUnit4::class)
class FrunkVoiceCleanupDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DiagnosticApp

    @Test fun exactFinalRecognitionWithReleasedMicCannotDispatchAfterRecognizerOrPipeCleanupFailure() {
        for ((failures, uncertain) in listOf(
            listOf("RECOGNIZER_CANCEL_FAILED") to false,
            listOf("RECOGNIZER_DESTROY_FAILED") to true,
            listOf("PCM_READ_FD_CLOSE_FAILED") to true,
            listOf("PCM_WRITE_FD_CLOSE_FAILED") to true,
            emptyList<String>() to true,
        )) {
            val recognized = AndroidPcmResult(
                segments = listOf(listOf(SttHypothesis("헤이 테슬라 프렁크 열어줘", 0f))),
                failure = null,
                acceptedBytes = 2,
                cleanupFailures = failures,
                releaseUncertain = uncertain,
            )
            instrumentation.runOnMainSync {
                val fixture = reservedRuntime(recognized)
                assertNull(recognized.failure)
                assertNull(fixture.gate.acceptedConfidence)
                assertFalse(fixture.runtime.finishFrunkVoiceAudio(fixture.session.id,
                    inputReleased = true,
                    cleanupConfirmed = recognized.cleanupFailures.isEmpty() && !recognized.releaseUncertain))
                assertCleanupRetained(fixture)
                // 뒤늦은 정상 정리 주장도 봉인된 gate·전역 latch를 되돌릴 수 없다.
                assertFalse(fixture.runtime.finishFrunkVoiceAudio(fixture.session.id,
                    inputReleased = true, cleanupConfirmed = true))
                assertCleanupRetained(fixture)
                assertReentryBlocked(fixture)
            }
            assertNull(recognized.failure)
        }
    }

    @Test fun unobservedCleanupCannotReturnSpeechLeaseOrEnterCore() {
        val recognized = AndroidPcmResult(
            segments = listOf(listOf(SttHypothesis("헤이 테슬라 프렁크 열어줘", null))),
            failure = null, acceptedBytes = 2, cleanupFailures = emptyList(), releaseUncertain = false,
        )
        instrumentation.runOnMainSync {
            val fixture = reservedRuntime(recognized)
            assertFalse(fixture.runtime.finishFrunkVoiceAudio(fixture.session.id,
                inputReleased = true, cleanupConfirmed = null))
            assertCleanupRetained(fixture)
            assertReentryBlocked(fixture)
        }
    }

    private data class Fixture(
        val runtime: DiagnosticRuntime,
        val session: SessionPolicy.Session,
        val gate: FrunkVoicePolicy,
    )

    /** 기존 독립 runtime 생성자를 쓰고, 장치 권한·association 변경 없이 획득 완료 lease만 구성한다. */
    private fun reservedRuntime(recognized: AndroidPcmResult): Fixture {
        val id = UUID.randomUUID().toString()
        val runtime = DiagnosticRuntime(app,
            VehicleVinStore(app, File(app.noBackupFilesDir, "vehicle-vin-tests/frunk-cleanup-$id.enc"),
                "heytesla.vin.test.frunk-cleanup.$id"),
            TeslaBleKeyStore(app, "heytesla.frunk.cleanup.key.$id"),
            "frunk_voice_cleanup_test_$id")
        runtime.activityVisible = true
        val startedAt = runtime.now()
        val session = requireNotNull(runtime.policy.startDiagnostic(startedAt))
        val gate = FrunkVoicePolicy(session.deadline, startedAt)
        assertTrue(gate.command(runtime.now(), SpeechTrialMode.DIRECT_MIC, final = true,
            candidates = AndroidPcmRecognizer.texts(recognized.candidates),
            confidence = AndroidPcmRecognizer.scores(recognized.candidates)))
        assertEquals(FrunkVoicePolicy.Phase.ACCEPTED, gate.phase)
        val speechId = DiagnosticRuntime::class.java.getDeclaredField("speechDiagnosticId").apply {
            isAccessible = true
        }
        speechId.set(runtime, session.id)
        val leaseField = DiagnosticRuntime::class.java.getDeclaredField("frunkVoiceLease").apply {
            isAccessible = true
        }
        val constructor = leaseField.type.getDeclaredConstructor(java.lang.Long.TYPE, String::class.java,
            java.lang.Integer.TYPE, FrunkVoicePolicy::class.java, java.lang.Long.TYPE).apply {
            isAccessible = true
        }
        leaseField.set(runtime, constructor.newInstance(session.id, "5YJ3E1EA7KF000000", 1, gate, session.deadline))
        runtime.update { it.copy(speechDiagnosticActive = true,
            frunkVoice = FrunkVoiceState(stage = FrunkVoiceStage.CLEANING, active = true,
                inputReleased = true)) }
        return Fixture(runtime, session, gate)
    }

    private fun assertCleanupRetained(fixture: Fixture) {
        val runtime = fixture.runtime
        assertEquals(FrunkVoicePolicy.Phase.STOPPED, fixture.gate.phase)
        assertEquals("FRUNK_VOICE_CLEANUP_FAILED", fixture.gate.reason)
        assertEquals(fixture.session.id, runtime.policy.current?.id)
        assertTrue(runtime.state.value.speechDiagnosticActive)
        val speechId = DiagnosticRuntime::class.java.getDeclaredField("speechDiagnosticId").apply {
            isAccessible = true
        }
        assertEquals(fixture.session.id, speechId.get(runtime))
        assertEquals(FrunkVoiceStage.CLEANUP_FAILED, runtime.state.value.frunkVoice.stage)
        assertEquals(FrunkVoiceStep.CLEANUP_FAILED, runtime.state.value.frunkVoice.progressStep())
        assertEquals("FRUNK_VOICE_CLEANUP_FAILED", runtime.state.value.frunkVoice.reason)
        assertTrue(runtime.state.value.frunkVoice.inputReleased)
        assertFalse(runtime.state.value.frunkVoice.active)
        assertFalse(runtime.state.value.frunkVoice.coreStarted)
        assertFalse(runtime.teslaKeyReserved())
        assertFalse(runtime.teslaKeyState.value.transmissionAttempted)
        assertFalse(runtime.teslaKeyState.value.frunkCommandSent)
        assertFalse(runtime.state.value.events.any { it.contains("FRUNK_VOICE_CORE_STARTED") })
        assertTrue(runtime.diagnosticCleanupUncertain)
    }

    private fun assertReentryBlocked(fixture: Fixture) {
        val runtime = fixture.runtime
        runtime.cancelFrunkVoice()
        runtime.activityVisible = false
        runtime.activityVisible = true
        runtime.setFrunkVoiceVisible(false)
        runtime.setFrunkVoiceVisible(true)
        FrunkVoiceProbe(app, runtime).start(consent = true, koKrInstalled = true)
        assertEquals(FrunkVoiceStage.CLEANUP_FAILED, runtime.state.value.frunkVoice.stage)
        assertEquals(fixture.session.id, runtime.policy.current?.id)
        assertTrue(runtime.state.value.speechDiagnosticActive)
        assertTrue(runtime.diagnosticCleanupUncertain)
        assertNull(runtime.acquireSpeechDiagnostic {})
        assertNull(runtime.acquireTeslaKey())
        assertFalse(runtime.acquireUwbSupport())
    }
}
