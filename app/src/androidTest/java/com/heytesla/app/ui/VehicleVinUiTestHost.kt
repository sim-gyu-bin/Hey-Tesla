package com.heytesla.app.ui

import com.heytesla.app.BleDetectionPreference
import com.heytesla.app.DiagnosticApp
import com.heytesla.app.DiagnosticRuntime
import com.heytesla.app.DiagnosticState
import com.heytesla.app.MainActivity
import com.heytesla.app.speech.SpeechProbeState
import com.heytesla.app.speech.SpeechSupportProbe
import com.heytesla.app.speech.SpeechTrialState
import com.heytesla.app.vehicle.key.TeslaBleKeyStore
import com.heytesla.app.vehicle.key.VehicleVinStatus
import com.heytesla.app.vehicle.key.VehicleVinStore
import com.heytesla.app.vehicle.navigation.VehicleNavigationPresentation
import com.heytesla.app.vehicle.navigation.VehicleNavigationStatus
import com.heytesla.app.vehicle.navigation.VehicleNavigationTracker
import com.heytesla.app.vehicle.uwb.UwbProbeState

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** instrumentation 안에서만 사용하는 저장소·키 alias. production VIN/키/설정은 쓰지 않는다. */
internal class VehicleVinUiTestHost(
    private val initiallyEnabledService: Boolean = false,
    isolatedVinStore: Boolean = false,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as DiagnosticApp
    private val markerName = "vehicle_vin_ui_test_${UUID.randomUUID()}"
    val servicePreferenceName = "${markerName}_activation"
    private val vinFixtureName = if (initiallyEnabledService || isolatedVinStore) markerName else "ui-regression"
    val vinFile = File(app.noBackupFilesDir, "vehicle-vin-tests/$vinFixtureName.enc")
    val store = VehicleVinStore(app, vinFile,
        if (initiallyEnabledService || isolatedVinStore) "heytesla.vin.test.$markerName.v1" else "heytesla.vin.test.ui-regression.v1")
    lateinit var runtime: DiagnosticRuntime
        private set
    lateinit var activity: MainActivity
        private set
    var startCount = 0
        private set
    var associateCount = 0
        private set
    var navigationResumeCount = 0
        private set
    var foreignBlocked: String? = null
    private val revision = MutableStateFlow(0)
    val state = MutableStateFlow(DiagnosticState())
    private val navigation = MutableStateFlow(VehicleNavigationPresentation(VehicleNavigationStatus.OFF))
    private var navigationTracker: VehicleNavigationTracker? = null
    private var navigationTick: Runnable? = null
    private val supportState = MutableStateFlow(SpeechProbeState())
    private val supportProbe = SpeechSupportProbe(app, supportState) { event ->
        android.util.Log.i("VehicleVinUiTestHost", event)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var contentReady = false

    fun launch(): MainActivity {
        activity = instrumentation.startActivitySync(
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        instrumentation.runOnMainSync {
            if (initiallyEnabledService) check(BleDetectionPreference(app, servicePreferenceName).setEnabled(true))
            runtime = DiagnosticRuntime(app, store,
                TeslaBleKeyStore(app, "heytesla.key.test.ui-regression.v1"), markerName)
            runtime.activityVisible = true
            // 실제 runtime 저장·복원 결과를 전달하되 권한·association은 UI 소비값만 격리한다.
            scope.launch {
                runtime.state.collect { snapshot ->
                    state.value = snapshot.copy(bluetoothPermission = true, bluetoothScanPermission = true,
                        locationPermission = true, locationEnabled = true, associationCount = 0)
                }
            }
            scope.launch {
                runtime.vehicleNavigation.collect { if (navigationTracker == null) navigation.value = it }
            }
            installContent()
        }
        await { contentReady && runtime.state.value.vehicleVin.status != VehicleVinStatus.LOADING &&
            state.value.vehicleVin == runtime.state.value.vehicleVin }
        instrumentation.waitForIdleSync()
        return activity
    }

    /** Only real tracker projections enter the UI fixture; tests cannot set presentation DTOs. */
    fun projectNavigation(tracker: VehicleNavigationTracker) {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        navigationTick?.let(runtime.handler::removeCallbacks)
        navigationTracker = tracker
        val tick = object : Runnable {
            override fun run() {
                if (navigationTracker !== tracker) return
                val elapsedBefore = SystemClock.elapsedRealtime()
                val epoch = System.currentTimeMillis()
                val elapsedAfter = SystemClock.elapsedRealtime()
                navigation.value = tracker.project(elapsedBefore, epoch, elapsedAfter)
                runtime.handler.postDelayed(this, 1_000L)
            }
        }
        navigationTick = tick
        tick.run()
    }

    fun invalidate() { revision.value += 1 }

    private fun createActions(): AppActions = AppActions(
            requestPermission = { forbidden() }, openSystemSettings = { forbidden() },
            saveVehicleVin = runtime::saveVehicleVin,
            vehicleVinChangeBlockedReason = {
                foreignBlocked ?: if (state.value.bleFieldBusy()) "VIN_CHANGE_BLOCKED_ACTIVE_WORK" else runtime.vehicleVinChangeBlockedReason()
            },
            associateVehicle = { associateCount++; forbidden() },
            setVehicleServiceEnabled = { enabled ->
                if (enabled) { startCount++; forbidden() }
                else runtime.setVehicleServiceEnabled(false)
            },
            vehicleServiceBlockedReason = { foreignBlocked ?: runtime.vehicleServiceBlockedReason() },
            resumeVehicleNavigation = { navigationResumeCount++; runtime.resumeVehicleNavigation() },
            refresh = { invalidate() }, startMicrophone = { forbidden() }, stopSession = { forbidden() },
            querySupport = supportProbe::query, cancelSupport = { supportProbe.cancel() }, queryUwbSupport = { forbidden() },
            cancelUwbSupport = { forbidden() }, startTrial = { forbidden() }, finishCapture = { forbidden() }, cancelTrial = { forbidden() },
            startTeslaKey = { _, _ -> startCount++; forbidden() },
            cancelTeslaKey = { runtime.cancelTeslaKey() },
            teslaKeyBlockedReason = { foreignBlocked ?: runtime.teslaKeyBlockedReason() },
            leaveDiagnostics = {
                supportProbe.cancel()
                runtime.cancelTeslaKey("DIAGNOSTICS_HIDDEN")
            },
        )

    fun installContent() {
        contentReady = false
        activity.setContent {
            val snapshot by state.collectAsState()
            val key by runtime.teslaKeyState.collectAsState()
            val ble by runtime.bleProbeState.collectAsState()
            val support by supportState.collectAsState()
            val marker by revision.collectAsState()
            val uwbRanging by runtime.teslaUwbState.collectAsState()
            val navigationPresentation by navigation.collectAsState()
            val actions = androidx.compose.runtime.remember(snapshot, key, ble, marker) { createActions() }
            HeyTeslaApp(snapshot, support, SpeechTrialState(), ble, UwbProbeState(), key, actions,
                uwbRanging = uwbRanging, navigation = navigationPresentation)
            SideEffect { contentReady = true }
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
            supportProbe.cancel()
            scope.cancel()
            navigationTick?.let(runtime.handler::removeCallbacks)
            navigationTick = null
            navigationTracker?.clear()
            navigationTracker = null
            activity.finish()
        }
    }

    private fun forbidden(): Nothing = throw AssertionError("VIN consumer 시험에서 실제 권한·라디오·오디오 작업을 호출할 수 없습니다")
}
