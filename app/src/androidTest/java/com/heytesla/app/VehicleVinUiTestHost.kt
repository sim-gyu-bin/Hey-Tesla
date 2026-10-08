package com.heytesla.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.platform.app.InstrumentationRegistry
import com.heytesla.app.ui.AppActions
import com.heytesla.app.ui.HeyTeslaApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.heytesla.app.ui.bleFieldBusy
import java.io.File

/** instrumentation 안에서만 사용하는 저장소·키 alias. production VIN/키/설정은 쓰지 않는다. */
internal class VehicleVinUiTestHost {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as DiagnosticApp
    val vinFile = File(app.noBackupFilesDir, "vehicle-vin-tests/ui-regression.enc")
    val store = VehicleVinStore(app, vinFile, "heytesla.vin.test.ui-regression.v1")
    lateinit var runtime: DiagnosticRuntime
        private set
    lateinit var activity: MainActivity
        private set
    var startCount = 0
        private set
    var associateCount = 0
        private set
    var foreignBlocked: String? = null
    private val revision = MutableStateFlow(0)
    val state = MutableStateFlow(DiagnosticState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun launch(): MainActivity {
        activity = instrumentation.startActivitySync(
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        instrumentation.runOnMainSync {
            runtime = DiagnosticRuntime(app, store, TeslaBleKeyStore(app, "heytesla.key.test.ui-regression.v1"))
            runtime.activityVisible = true
            // 실제 runtime 저장·복원 결과를 전달하되 권한·association은 UI 소비값만 격리한다.
            scope.launch {
                runtime.state.collect { snapshot ->
                    state.value = snapshot.copy(bluetoothPermission = true, bluetoothScanPermission = true, associationCount = 0)
                }
            }
            installContent()
        }
        await { runtime.state.value.vehicleVin.status != VehicleVinStatus.LOADING && state.value.vehicleVin == runtime.state.value.vehicleVin }
        return activity
    }

    fun invalidate() { revision.value += 1 }

    private fun createActions(): AppActions = AppActions(
            requestPermission = { forbidden() }, openSystemSettings = { forbidden() },
            saveVehicleVin = runtime::saveVehicleVin,
            vehicleVinChangeBlockedReason = {
                foreignBlocked ?: if (state.value.bleFieldBusy()) "VIN_CHANGE_BLOCKED_ACTIVE_WORK" else runtime.vehicleVinChangeBlockedReason()
            },
            associateVehicle = { associateCount++; forbidden() },
            setApproachEnabled = { forbidden() }, setAutomaticMicrophone = { forbidden() }, observeVehicle = { forbidden() },
            refresh = { invalidate() }, startMicrophone = { forbidden() }, stopSession = { forbidden() },
            querySupport = { forbidden() }, cancelSupport = { forbidden() }, queryUwbSupport = { forbidden() },
            cancelUwbSupport = { forbidden() }, startTrial = { forbidden() }, finishCapture = { forbidden() }, cancelTrial = { forbidden() },
            startBle = { startCount++; forbidden() },
            cancelBle = { runtime.stopBleFieldTrial() }, bleFieldBlockedReason = { foreignBlocked },
            startTeslaKey = { _, _ -> startCount++; forbidden() },
            cancelTeslaKey = { runtime.cancelTeslaKey() },
            teslaKeyBlockedReason = { foreignBlocked ?: runtime.teslaKeyBlockedReason() },
            leaveDiagnostics = { runtime.cancelTeslaKey("DIAGNOSTICS_HIDDEN") },
        )

    fun installContent() {
        activity.setContent {
            val snapshot by state.collectAsState()
            val key by runtime.teslaKeyState.collectAsState()
            val ble by runtime.bleProbeState.collectAsState()
            val marker by revision.collectAsState()
            val actions = androidx.compose.runtime.remember(snapshot, key, ble, marker) { createActions() }
            HeyTeslaApp(snapshot, SpeechProbeState(), SpeechTrialState(), ble, UwbProbeState(), key, actions)
        }
    }

    fun seedRegisteredVin() {
        // 합성 VIN은 이 test-owned 암호화 저장소에만 저장한다.
        instrumentation.runOnMainSync { runtime.saveVehicleVin("00000000000000000") }
        await { runtime.state.value.vehicleVin.status == VehicleVinStatus.READY && state.value.vehicleVin == runtime.state.value.vehicleVin }
    }

    fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var complete = false
            instrumentation.runOnMainSync { complete = predicate() }
            if (complete) return
            SystemClock.sleep(25)
        }
        throw AssertionError("격리 VIN consumer 상태 전이를 확인할 수 없습니다")
    }

    fun close() {
        instrumentation.runOnMainSync {
            if (!::runtime.isInitialized || !::activity.isInitialized) return@runOnMainSync
            runtime.activityVisible = false
            runtime.cancelTeslaKey("TEST_HOST_CLOSED")
            scope.cancel()
            activity.finish()
        }
    }

    private fun forbidden(): Nothing = throw AssertionError("VIN consumer 시험에서 실제 권한·라디오·오디오 작업을 호출할 수 없습니다")
}
