package com.heytesla.app

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.regex.Pattern
import com.heytesla.app.ui.AppActions
import com.heytesla.app.ui.HeyTeslaApp

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
    private val vehicleChooserPending = MutableStateFlow(false)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        speechSupportProbe = SpeechSupportProbe(applicationContext, speechSupportState) { runtime.event(it) }
        uwbSupportProbe = UwbSupportProbe(applicationContext, uwbSupportState, runtime)
        speechRecognitionProbe = SpeechRecognitionProbe(applicationContext, speechTrialState, runtime)
        setContent {
            val state by runtime.state.collectAsState()
            val support by speechSupport.collectAsState()
            val trial by speechTrial.collectAsState()
            val ble by runtime.bleProbeState.collectAsState()
            val uwb by uwbSupportState.collectAsState()
            val teslaKey by runtime.teslaKeyState.collectAsState()
            val chooserPending by vehicleChooserPending.collectAsState()
            // 상태 기반 callback 게이트가 Compose의 동일 인자 건너뛰기에 가려지지 않게 한다.
            val actions = androidx.compose.runtime.remember(state, support, trial, uwb, chooserPending) {
                createActions()
            }
            HeyTeslaApp(state, support, trial, ble, uwb, teslaKey, actions)
        }
    }

    private fun createActions(): AppActions = AppActions(
            requestPermission = { permission -> permissionRequest.launch(arrayOf(permission)) },
            openSystemSettings = { action ->
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
            setApproachEnabled = { enabled ->
                if (!bleFieldBusy() && (!enabled || cancelSupportForStart())) runtime.setEnabled(enabled)
            },
            setAutomaticMicrophone = { if (cancelSupportForStart()) runtime.setAutomaticMicrophone(it) },
            observeVehicle = { if (cancelSupportForStart()) runtime.startObserving() },
            refresh = runtime::refresh,
            startMicrophone = { if (cancelSupportForStart()) runtime.manualStart(this) },
            stopSession = {
                uwbSupportProbe.cancel()
                speechSupportProbe.cancel()
                speechRecognitionProbe.cancel()
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
            startBle = ::startBle,
            cancelBle = { runtime.stopBleFieldTrial() },
            bleFieldBlockedReason = runtime::bleFieldTrialBlockedReason,
            startTeslaKey = ::startTeslaKey,
            cancelTeslaKey = { runtime.cancelTeslaKey() },
            teslaKeyBlockedReason = runtime::teslaKeyBlockedReason,
            leaveDiagnostics = ::leaveDiagnostics,
    )

    override fun onResume() { super.onResume(); runtime.activityVisible = true; runtime.refresh(); runtime.event("UI_RESUMED") }
    override fun onPause() {
        runtime.activityVisible = false
        runtime.cancelTeslaKey("UI_PAUSED")
        uwbSupportProbe.cancel()
        speechSupportProbe.cancel()
        // Visibility is revoked before cancellation to block reentrant starts.
        speechRecognitionProbe.cancel()
        if (runtime.policy.current?.let { !it.automatic && !it.diagnostic } == true) runtime.stop("MANUAL_UI_HIDDEN")
        runtime.event("UI_PAUSED")
        super.onPause()
    }

    override fun onDestroy() {
        runtime.activityVisible = false
        runtime.cancelTeslaKey("UI_DESTROYED")
        uwbSupportProbe.cancel()
        speechSupportProbe.cancel()
        speechRecognitionProbe.cancel()
        super.onDestroy()
    }

    private fun leaveDiagnostics() {
        runtime.cancelTeslaKey("DIAGNOSTICS_HIDDEN")
        uwbSupportProbe.cancel()
        speechSupportProbe.cancel()
        speechRecognitionProbe.cancel()
        // Navigation within this Activity does not invoke onPause.
        if (runtime.policy.current?.let { !it.automatic && !it.diagnostic } == true) runtime.stop("MANUAL_DIAGNOSTICS_HIDDEN")
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
        if (!runtime.activityVisible || runtime.teslaKeyReserved() || runtime.bleProbeState.value.active || state.bleDiagnosticActive || bleFieldBusy() ||
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


    private fun startBle(config: BleFieldConfig) {
        if (!uwbSupportProbe.cancel()) return
        speechSupportProbe.cancel()
        if (speechSupportState.value.reason == "DESTROY_FAILED") {
            runtime.event("BLE_SUPPORT_CLEANUP_BLOCKED")
            return
        }
        runtime.startBleFieldTrial(config)
    }

    private fun bleFieldBusy(): Boolean = runtime.state.value.let {
        it.bleFieldTrialActive || it.bleFieldTrialStarting || it.bleFieldTrialStopping
    }

    private fun cancelSupportForStart(): Boolean {
        if (runtime.diagnosticCleanupUncertain) return false
        if (!uwbSupportProbe.cancel()) return false
        speechSupportProbe.cancel()
        if (runtime.teslaKeyReserved()) return false
        if (bleFieldBusy()) return false
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
                    if (runtime.state.value.enabled) runtime.startObserving()
                }
                override fun onFailure(error: CharSequence?) { setVehicleChooserPending(false); runtime.event("ASSOCIATION_FAILED") }
                override fun onFailure(errorCode: Int, error: CharSequence?) { setVehicleChooserPending(false); runtime.event("ASSOCIATION_FAILED_CODE_$errorCode") }
            }) ?: run { setVehicleChooserPending(false); runtime.event("CDM_UNAVAILABLE") }
        } catch (_: SecurityException) { setVehicleChooserPending(false); runtime.event("ASSOCIATION_SECURITY_DENIED") }
        catch (_: Exception) { setVehicleChooserPending(false); runtime.event("ASSOCIATION_REQUEST_FAILED") }
    }
}
