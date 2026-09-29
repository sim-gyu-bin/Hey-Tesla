package com.heytesla.app.ui

import com.heytesla.app.SpeechTrialMode

/** UI events only; the Activity and existing runtime retain ownership of side effects. */
internal class AppActions(
    val requestPermission: (String) -> Unit,
    val openSystemSettings: (String) -> Unit,
    val associateVehicle: (String) -> Unit,
    val setApproachEnabled: (Boolean) -> Unit,
    val setAutomaticMicrophone: (Boolean) -> Unit,
    val observeVehicle: () -> Unit,
    val refresh: () -> Unit,
    val startMicrophone: () -> Unit,
    val stopSession: () -> Unit,
    val querySupport: () -> Unit,
    val cancelSupport: () -> Unit,
    val startTrial: (SpeechTrialMode) -> Unit,
    val finishCapture: () -> Unit,
    val cancelTrial: () -> Unit,
    val leaveDiagnostics: () -> Unit,
)
