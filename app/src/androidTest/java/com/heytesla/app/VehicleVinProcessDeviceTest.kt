package com.heytesla.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 부모가 phase=prepare 실행→앱 프로세스만 종료→별도 instrumentation phase=restore 실행한다.
 * 입력은 바이너리 안의 합성값뿐이며 argv/로그/프로세스 간 평문 전달은 없다. 결과는 Boolean assertion뿐이다.
 * 기본 전체 계측에서는 phase가 없으므로 건너뛴다. fixture 암호문과 독립 alias는 삭제하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class VehicleVinProcessDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val file get() = File(context.noBackupFilesDir, "vehicle-vin-tests/native-process-restart.enc")
    private fun store() = VehicleVinStore(context, file, "heytesla.vin.test.native-process-restart.v1")

    @Test fun prepareFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("vehicleVinProcessPhase") == "prepare")
        val saved = store().save(SYNTHETIC)
        assertTrue(saved.masked == "*************0000")
        assertTrue(file.isFile)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(SYNTHETIC))
    }

    @Test fun restoreFixtureInANewProcess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("vehicleVinProcessPhase") == "restore")
        val restored = store().restore()
        assertTrue(restored != null)
        assertTrue(restored?.masked == "*************0000")
        assertTrue(restored?.snapshot()?.let(TeslaBleAdvertisement::localName) == TeslaBleAdvertisement.localName(SYNTHETIC))
        assertFalse(restored.toString().contains(SYNTHETIC))
        val runtime = (context.applicationContext as DiagnosticApp).runtime
        val state = runtime.state.value
        assertFalse(state.enabled)
        assertFalse(state.observationStartPending)
        assertFalse(state.observationServiceRunning)
        assertFalse(state.bleFieldTrialStarting)
        assertFalse(state.bleFieldTrialActive)
        assertFalse(runtime.teslaKeyReserved())
        assertNull(runtime.policy.current)
        assertNull(runtime.microphone)
    }

    private companion object {
        const val SYNTHETIC = "5YJ3E1EA7KF000000"
    }
}
