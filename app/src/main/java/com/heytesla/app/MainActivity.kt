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
import java.security.MessageDigest
import java.util.Locale
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
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        runtime.refresh()
        runtime.event("RUNTIME_PERMISSION_RESULT")
    }
    private val chooser = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
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
        val actions = AppActions(
            requestPermission = { permission -> permissionRequest.launch(arrayOf(permission)) },
            openSystemSettings = { action ->
                settings(Intent(action).apply {
                    if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                        data = "package:$packageName".toUri()
                    }
                })
            },
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
        setContent {
            val state by runtime.state.collectAsState()
            val support by speechSupport.collectAsState()
            val trial by speechTrial.collectAsState()
            val ble by runtime.bleProbeState.collectAsState()
            val uwb by uwbSupportState.collectAsState()
            val teslaKey by runtime.teslaKeyState.collectAsState()
            HeyTeslaApp(state, support, trial, ble, uwb, teslaKey, actions)
        }
    }

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
    private fun startTeslaKey(input: String, register: Boolean) {
        if (!uwbSupportProbe.cancel()) return
        speechSupportProbe.cancel()
        if (speechSupportState.value.reason == "DESTROY_FAILED") {
            runtime.event("TESLA_KEY_START_BLOCKED")
            return
        }
        runtime.startTeslaKey(input, register)
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
        if (!uwbSupportProbe.cancel()) return false
        speechSupportProbe.cancel()
        if (runtime.teslaKeyReserved()) return false
        if (bleFieldBusy()) return false
        if (speechSupportState.value.reason != "DESTROY_FAILED") return true
        runtime.event("SPEECH_SUPPORT_CLEANUP_BLOCKED")
        return false
    }

    private fun settings(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { runtime.event("SETTINGS_SCREEN_UNAVAILABLE") }
    }

    private fun associate(input: String) {
        if (runtime.teslaKeyReserved()) { runtime.event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) { runtime.event("CDM_UNSUPPORTED"); return }
        val normalized = input.trim().uppercase(Locale.ROOT)
        if (!normalized.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) { runtime.event("VIN_FORMAT_INVALID"); return }
        val bytes = normalized.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        bytes.fill(0)
        val name = buildString {
            append('S')
            for (index in 0 until 8) {
                val value = digest[index].toInt() and 255
                append("0123456789abcdef"[value ushr 4])
                append("0123456789abcdef"[value and 15])
            }
            append('C')
        }
        digest.fill(0)
        val request = AssociationRequest.Builder().addDeviceFilter(
            BluetoothLeDeviceFilter.Builder().setNamePattern(Pattern.compile("^${Pattern.quote(name)}$")).build()
        ).setSingleDevice(false).build()
        try {
            runtime.cdm?.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    try { chooser.launch(IntentSenderRequest.Builder(intentSender).build()) }
                    catch (_: Exception) { runtime.event("CHOOSER_LAUNCH_FAILED") }
                }
                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    runtime.event("ASSOCIATION_CREATED")
                    runtime.refresh()
                    if (runtime.state.value.enabled) runtime.startObserving()
                }
                override fun onFailure(error: CharSequence?) { runtime.event("ASSOCIATION_FAILED") }
                override fun onFailure(errorCode: Int, error: CharSequence?) { runtime.event("ASSOCIATION_FAILED_CODE_$errorCode") }
            }) ?: runtime.event("CDM_UNAVAILABLE")
        } catch (_: SecurityException) { runtime.event("ASSOCIATION_SECURITY_DENIED") }
        catch (_: Exception) { runtime.event("ASSOCIATION_REQUEST_FAILED") }
    }
}
