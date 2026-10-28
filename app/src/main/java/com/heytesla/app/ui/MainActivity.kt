package com.heytesla.app

import com.heytesla.app.speech.SpeechProbeState
import com.heytesla.app.speech.SpeechProbeStatus
import com.heytesla.app.speech.SpeechRecognitionProbe
import com.heytesla.app.speech.SpeechSupportProbe
import com.heytesla.app.speech.SpeechTrialState
import com.heytesla.app.speech.SpeechTrialStatus
import com.heytesla.app.vehicle.detection.TeslaBleAdvertisement
import com.heytesla.app.vehicle.frunk.FrunkVoiceProbe
import com.heytesla.app.vehicle.key.VehicleVinStatus
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.heytesla.app.vehicle.uwb.TeslaUwbProbeState
import com.heytesla.app.vehicle.uwb.TeslaUwbStage
import com.heytesla.app.vehicle.uwb.UwbProbeState
import com.heytesla.app.vehicle.uwb.UwbProbeStatus
import com.heytesla.app.vehicle.uwb.UwbSupportProbe

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.regex.Pattern
import com.heytesla.app.ui.AppActions
import com.heytesla.app.ui.HeyTeslaApp
import com.heytesla.app.ui.bleFieldBusy

class MainActivity : ComponentActivity() {
    private val runtime get() = (application as DiagnosticApp).runtime
    private val speechSupportState = MutableStateFlow(SpeechProbeState())
    private val speechSupport = speechSupportState.asStateFlow()
    private lateinit var speechSupportProbe: SpeechSupportProbe
    private val uwbSupportState = MutableStateFlow(UwbProbeState())
    private lateinit var uwbSupportProbe: UwbSupportProbe
    private val speechTrialState = MutableStateFlow(SpeechTrialState())
    private val speechTrial = speechTrialState.asStateFlow()
    private lateinit var speechRecognitionProbe: SpeechRecognitionProbe
    private lateinit var frunkVoiceProbe: FrunkVoiceProbe
    private val vehicleChooserPending = MutableStateFlow(false)
    private var permissionRequestInFlight = false
    private var permissionReturnPending = false
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRequestInFlight = false
        permissionReturnPending = !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        runtime.refresh()
        runtime.event("RUNTIME_PERMISSION_RESULT")
    }
    private val chooser = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        setVehicleChooserPending(false)
        runtime.event(if (result.resultCode == RESULT_OK) "CHOOSER_APPROVED" else "CHOOSER_CANCELED_OR_FAILED")
        runtime.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionRequestInFlight = savedInstanceState?.getBoolean("permissionRequestInFlight") ?: false
        permissionReturnPending = savedInstanceState?.getBoolean("permissionReturnPending") ?: false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        speechSupportProbe = SpeechSupportProbe(applicationContext, speechSupportState) { runtime.event(it) }
        uwbSupportProbe = UwbSupportProbe(applicationContext, uwbSupportState, runtime)
        speechRecognitionProbe = SpeechRecognitionProbe(applicationContext, speechTrialState, runtime)
        frunkVoiceProbe = FrunkVoiceProbe(applicationContext, runtime)
        setContent {
            val state by runtime.state.collectAsState()
            val support by speechSupport.collectAsState()
            val trial by speechTrial.collectAsState()
            val ble by runtime.bleProbeState.collectAsState()
            val uwb by uwbSupportState.collectAsState()
            val teslaKey by runtime.teslaKeyState.collectAsState()
            val uwbRanging by runtime.teslaUwbState.collectAsState()
            val navigation by runtime.vehicleNavigation.collectAsState()
            val chooserPending by vehicleChooserPending.collectAsState()
            // 상태 기반 callback 게이트가 Compose의 동일 인자 건너뛰기에 가려지지 않게 한다.
            val actions = androidx.compose.runtime.remember(state, support, trial, uwb, chooserPending) {
                createActions()
            }
            HeyTeslaApp(state, support, trial, ble, uwb, teslaKey, actions,
                uwbRanging = uwbRanging, navigation = navigation)
        }
    }

    private fun createActions(): AppActions = AppActions(
            requestPermission = { permission ->
                permissionRequestInFlight = true
                permissionRequest.launch(
                    if (permission == android.Manifest.permission.ACCESS_FINE_LOCATION)
                        arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, permission)
                    else arrayOf(permission),
                )
            },
            openSystemSettings = { action ->
                if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) permissionReturnPending = true
                settings(Intent(action).apply {
                    if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                        data = "package:$packageName".toUri()
                    }
                })
            },
            saveVehicleVin = { input ->
                if (vehicleVinChangeBlockedReason() == null) runtime.saveVehicleVin(input)
                else runtime.event("VEHICLE_VIN_CHANGE_BLOCKED")
            },
            vehicleVinChangeBlockedReason = ::vehicleVinChangeBlockedReason,
            associateVehicle = ::associate,
            setVehicleServiceEnabled = ::setVehicleServiceEnabled,
            vehicleServiceBlockedReason = ::vehicleServiceBlockedReason,
            resumeVehicleNavigation = runtime::resumeVehicleNavigation,
            refresh = runtime::refresh,
            startMicrophone = { if (cancelSupportForStart()) runtime.manualStart(this) },
            stopSession = {
                uwbSupportProbe.cancel()
                speechSupportProbe.cancel()
                speechRecognitionProbe.cancel()
                frunkVoiceProbe.cancel()
                runtime.stop("USER_STOP")
            },
            querySupport = ::querySupport,
            cancelSupport = { speechSupportProbe.cancel() },
            queryUwbSupport = ::queryUwbSupport,
            cancelUwbSupport = { uwbSupportProbe.cancel() },
            startTrial = { mode ->
                val support = speechSupportState.value
                if (cancelSupportForStart()) {
                    speechRecognitionProbe.start(
                        mode,
                        support.status == SpeechProbeStatus.COMPLETE && support.metadata?.koKrInstalled == true,
                    )
                }
            },
            finishCapture = { speechRecognitionProbe.finishCapture() },
            cancelTrial = { speechRecognitionProbe.cancel() },
            startTeslaKey = ::startTeslaKey,
            cancelTeslaKey = { runtime.cancelTeslaKey() },
            teslaKeyBlockedReason = runtime::teslaKeyBlockedReason,
            leaveDiagnostics = ::leaveDiagnostics,
            startUwbRanging = ::startUwbRanging,
            cancelUwbRanging = { runtime.cancelTeslaKey() },
            uwbRangingBlockedReason = runtime::uwbRangingBlockedReason,
            frunkVoice = com.heytesla.app.ui.FrunkVoiceActions(
                start = { consent ->
                    // 실차 시험은 다른 진단을 자동 중지하지 않는다. 명시적으로 정리한 뒤에만 입장한다.
                    val support = speechSupportState.value
                    if (support.status != SpeechProbeStatus.RUNNING &&
                        support.reason != "DESTROY_FAILED" && !runtime.uwbSupportActive &&
                        !runtime.uwbSupportCleanupFailed
                    ) frunkVoiceProbe.start(consent,
                        support.status == SpeechProbeStatus.COMPLETE && support.metadata?.koKrInstalled == true)
                },
                cancel = { frunkVoiceProbe.cancel() },
                visible = runtime::setFrunkVoiceVisible,
                blockedReason = {
                    when {
                        speechSupportState.value.status == SpeechProbeStatus.RUNNING -> "SPEECH_SUPPORT_RUNNING"
                        speechSupportState.value.reason == "DESTROY_FAILED" -> "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED"
                        else -> runtime.frunkVoiceBlockedReason()
                    }
                },
            ),
    )

    override fun onResume() {
        super.onResume()
        runtime.activityVisible = true
        org.altbeacon.beacon.BeaconManager.getInstanceForApplication(applicationContext).setBackgroundMode(false)
        runtime.refresh()
        // A permission dialog return refreshes gates, but must not restart a stopped service.
        // An already-live ON service may consume a new advertisement under the newly granted gate.
        val returningFromPermission = permissionRequestInFlight || permissionReturnPending
        permissionReturnPending = false
        if (!returningFromPermission) runtime.restoreBleDetection()
        runtime.event("UI_RESUMED")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permissionRequestInFlight", permissionRequestInFlight)
        outState.putBoolean("permissionReturnPending", permissionReturnPending)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        runtime.activityVisible = false
        runtime.setFrunkVoiceVisible(false)
        frunkVoiceProbe.cancel()
        org.altbeacon.beacon.BeaconManager.getInstanceForApplication(applicationContext).setBackgroundMode(true)
        runtime.cancelTeslaKey("UI_PAUSED")
        uwbSupportProbe.cancel()
        speechSupportProbe.cancel()
        // Visibility is revoked before cancellation to block reentrant starts.
        speechRecognitionProbe.cancel()
        if (runtime.policy.current?.let { !it.diagnostic } == true) runtime.stop("MANUAL_UI_HIDDEN")
        runtime.event("UI_PAUSED")
        super.onPause()
    }

    override fun onDestroy() {
        runtime.activityVisible = false
        runtime.setFrunkVoiceVisible(false)
        frunkVoiceProbe.cancel()
        runtime.cancelTeslaKey("UI_DESTROYED")
        uwbSupportProbe.cancel()
        speechSupportProbe.cancel()
        speechRecognitionProbe.cancel()
        super.onDestroy()
    }

    private fun leaveDiagnostics() {
        runtime.setFrunkVoiceVisible(false)
        frunkVoiceProbe.cancel()
        runtime.cancelTeslaKey("DIAGNOSTICS_HIDDEN")
        uwbSupportProbe.cancel()
        speechSupportProbe.cancel()
        speechRecognitionProbe.cancel()
        // Navigation within this Activity does not invoke onPause.
        if (runtime.policy.current?.let { !it.diagnostic } == true) runtime.stop("MANUAL_DIAGNOSTICS_HIDDEN")
    }

    private fun querySupport() {
        if (runtime.diagnosticCleanupUncertain) {
            speechSupportState.value = SpeechProbeState(
                status = SpeechProbeStatus.FAILED,
                reason = "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED",
            )
            return
        }
        // 지원 조회는 Activity 소유이며 runtime lease가 없으므로 저장 중 역방향 진입도 막는다.
        if (runtime.state.value.vehicleVin.status == VehicleVinStatus.SAVING) {
            runtime.event("SPEECH_SUPPORT_VIN_SAVING_BLOCKED")
            return
        }
        if (!uwbSupportProbe.cancel()) return
        runtime.refresh()
        val state = runtime.state.value
        if (speechSupportState.value.reason == "DESTROY_FAILED") return
        if (!runtime.activityVisible || runtime.teslaKeyReserved() || runtime.bleProbeState.value.active || runtime.state.value.bleFieldBusy() ||
            speechTrialState.value.active || state.speechDiagnosticActive ||
            runtime.policy.current != null || runtime.microphone != null ||
            state.enabled || state.observing || state.observationStartPending ||
            state.observationServiceRunning
        ) {
            if (speechSupportState.value.status != SpeechProbeStatus.RUNNING) {
                speechSupportState.value = SpeechProbeState(
                    status = SpeechProbeStatus.FAILED,
                    reason = "DIAGNOSTIC_BUSY",
                )
            }
            runtime.event("SPEECH_SUPPORT_DIAGNOSTIC_BUSY")
            return
        }
        speechSupportProbe.query()
    }

    private fun queryUwbSupport() {
        speechSupportProbe.cancel()
        if (speechSupportState.value.reason == "DESTROY_FAILED") {
            runtime.event("UWB_SUPPORT_OTHER_DIAGNOSTIC_BLOCKED")
            return
        }
        runtime.refresh()
        uwbSupportProbe.query()
    }
    /** 화면 버튼만 진입한다. exported Intent·재생성·onResume에서는 시작하지 않는다. */
    private fun startTeslaKey(register: Boolean, query: TeslaBleQuery) {
        if (!uwbSupportProbe.cancel()) return
        speechSupportProbe.cancel()
        if (speechSupportState.value.reason == "DESTROY_FAILED") {
            runtime.event("TESLA_KEY_START_BLOCKED")
            return
        }
        runtime.startTeslaKey(register, query)
    }

    private fun startUwbRanging() {
        if (!uwbSupportProbe.cancel()) return
        speechSupportProbe.cancel()
        if (speechSupportState.value.reason == "DESTROY_FAILED") {
            if (!runtime.teslaKeyReserved()) runtime.teslaUwbState.value = TeslaUwbProbeState(
                stage = TeslaUwbStage.BLOCKED,
                rangingPermission = runtime.teslaUwbState.value.rangingPermission,
                reason = "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED",
            )
            runtime.event("TESLA_UWB_BLOCKED")
            return
        }
        runtime.startUwbRanging()
    }


    private fun setVehicleServiceEnabled(enabled: Boolean) {
        // OFF는 권한·오디오 정리·BLE 예약 게이트와 무관하게 항상 런타임에 전달한다.
        if (!enabled) {
            runtime.setVehicleServiceEnabled(false)
            return
        }
        if (!uwbSupportProbe.cancel()) return
        speechSupportProbe.cancel()
        if (speechSupportState.value.reason == "DESTROY_FAILED") {
            runtime.event("BLE_SUPPORT_CLEANUP_BLOCKED")
            return
        }
        runtime.setVehicleServiceEnabled(true)
    }

    private fun vehicleServiceBlockedReason(): String? = when {
        speechSupportState.value.reason == "DESTROY_FAILED" ||
            speechSupportState.value.reason == "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" ->
            "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED"
        else -> runtime.vehicleServiceBlockedReason()
    }

    private fun cancelSupportForStart(): Boolean {
        if (runtime.diagnosticCleanupUncertain) return false
        if (!uwbSupportProbe.cancel()) return false
        speechSupportProbe.cancel()
        if (runtime.teslaKeyReserved()) return false
        if (runtime.state.value.bleFieldBusy()) return false
        if (speechSupportState.value.reason != "DESTROY_FAILED") return true
        runtime.event("SPEECH_SUPPORT_CLEANUP_BLOCKED")
        return false
    }
    private fun vehicleVinChangeBlockedReason(): String? = when {
        vehicleChooserPending.value || runtime.vehicleChooserPending -> "VIN_CHANGE_BLOCKED_VEHICLE_CHOOSER"
        speechSupportState.value.reason == "DESTROY_FAILED" -> "SPEECH_SUPPORT_CLEANUP_FAILED"
        speechSupportState.value.status == SpeechProbeStatus.RUNNING -> "SPEECH_SUPPORT_RUNNING"
        uwbSupportState.value.status == UwbProbeStatus.CLEANUP_FAILED -> "UWB_SUPPORT_CLEANUP_FAILED"
        uwbSupportState.value.status == UwbProbeStatus.RUNNING -> "UWB_SUPPORT_RUNNING"
        speechTrialState.value.responseCleanupFailed -> "SPEECH_TRIAL_CLEANUP_FAILED"
        speechTrialState.value.active || speechTrialState.value.status == SpeechTrialStatus.CLEANING -> "SPEECH_TRIAL_RUNNING"
        else -> runtime.vehicleVinChangeBlockedReason()
    }

    private fun setVehicleChooserPending(pending: Boolean) {
        runtime.vehicleChooserPending = pending
        vehicleChooserPending.value = pending
    }

    private fun settings(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { runtime.event("SETTINGS_SCREEN_UNAVAILABLE") }
    }

    private fun associate() {
        if (vehicleVinChangeBlockedReason() != null) { runtime.event("VEHICLE_CHOOSER_BLOCKED"); return }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) { runtime.event("CDM_UNSUPPORTED"); return }
        val vin = runtime.vehicleVinSnapshot() ?: run { runtime.event("VEHICLE_VIN_NOT_READY"); return }
        val name = TeslaBleAdvertisement.localName(vin)
            ?: run { runtime.event("VIN_FORMAT_INVALID"); return }
        val request = AssociationRequest.Builder().addDeviceFilter(
            BluetoothLeDeviceFilter.Builder().setNamePattern(Pattern.compile("^${Pattern.quote(name)}$")).build()
        ).setSingleDevice(false).build()
        if (runtime.cdm == null) { runtime.event("CDM_UNAVAILABLE"); return }
        setVehicleChooserPending(true)
        try {
            runtime.cdm?.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    try { chooser.launch(IntentSenderRequest.Builder(intentSender).build()) }
                    catch (_: Exception) { setVehicleChooserPending(false); runtime.event("CHOOSER_LAUNCH_FAILED") }
                }
                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    setVehicleChooserPending(false)
                    runtime.event("ASSOCIATION_CREATED")
                    runtime.refresh()
                }
                override fun onFailure(error: CharSequence?) { setVehicleChooserPending(false); runtime.event("ASSOCIATION_FAILED") }
                override fun onFailure(errorCode: Int, error: CharSequence?) { setVehicleChooserPending(false); runtime.event("ASSOCIATION_FAILED_CODE_$errorCode") }
            }) ?: run { setVehicleChooserPending(false); runtime.event("CDM_UNAVAILABLE") }
        } catch (_: SecurityException) { setVehicleChooserPending(false); runtime.event("ASSOCIATION_SECURITY_DENIED") }
        catch (_: Exception) { setVehicleChooserPending(false); runtime.event("ASSOCIATION_REQUEST_FAILED") }
    }
}
