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
            setApproachEnabled = runtime::setEnabled,
            setAutomaticMicrophone = runtime::setAutomaticMicrophone,
            observeVehicle = runtime::startObserving,
            refresh = runtime::refresh,
            startMicrophone = { runtime.manualStart(this) },
            stopSession = {
                speechSupportProbe.cancel()
                speechRecognitionProbe.cancel()
                runtime.stop("USER_STOP")
            },
            querySupport = {
                if (!speechTrialState.value.active && !runtime.state.value.speechDiagnosticActive) {
                    speechSupportProbe.query()
                }
            },
            cancelSupport = { speechSupportProbe.cancel() },
            startTrial = { mode ->
                val support = speechSupportState.value
                speechSupportProbe.cancel()
                speechRecognitionProbe.start(
                    mode,
                    support.status == SpeechProbeStatus.COMPLETE && support.metadata?.koKrInstalled == true,
                )
            },
            finishCapture = { speechRecognitionProbe.finishCapture() },
            cancelTrial = { speechRecognitionProbe.cancel() },
            leaveDiagnostics = ::leaveDiagnostics,
        )
        setContent {
            val state by runtime.state.collectAsState()
            val support by speechSupport.collectAsState()
            val trial by speechTrial.collectAsState()
            HeyTeslaApp(state, support, trial, actions)
        }
    }

    override fun onResume() { super.onResume(); runtime.activityVisible = true; runtime.refresh(); runtime.event("UI_RESUMED") }
    override fun onPause() {
        speechSupportProbe.cancel()
        runtime.activityVisible = false
        speechRecognitionProbe.cancel()
        if (runtime.policy.current?.let { !it.automatic && !it.diagnostic } == true) runtime.stop("MANUAL_UI_HIDDEN")
        runtime.event("UI_PAUSED")
        super.onPause()
    }

    override fun onDestroy() {
        speechSupportProbe.cancel()
        speechRecognitionProbe.cancel()
        super.onDestroy()
    }

    private fun leaveDiagnostics() {
        speechSupportProbe.cancel()
        speechRecognitionProbe.cancel()
        // Navigation within this Activity does not invoke onPause.
        if (runtime.policy.current?.let { !it.automatic && !it.diagnostic } == true) runtime.stop("MANUAL_DIAGNOSTICS_HIDDEN")
    }

    private fun settings(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { runtime.event("SETTINGS_SCREEN_UNAVAILABLE") }
    }

    private fun associate(input: String) {
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
