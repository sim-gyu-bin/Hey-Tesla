package com.heytesla.app.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.heytesla.app.vehicle.ble.BleProbeState
import com.heytesla.app.DiagnosticState
import com.heytesla.app.speech.SpeechProbeState
import com.heytesla.app.speech.SpeechTrialState
import com.heytesla.app.vehicle.uwb.UwbProbeState
import com.heytesla.app.vehicle.key.TeslaKeyProbeState
import com.heytesla.app.vehicle.uwb.TeslaUwbProbeState
import com.heytesla.app.vehicle.uwb.TeslaUwbStage
import com.heytesla.app.vehicle.navigation.VehicleNavigationPresentation
import com.heytesla.app.vehicle.navigation.VehicleNavigationStatus
import kotlinx.coroutines.delay

private enum class Destination { HOME, SETTINGS, DIAGNOSTICS, KEY_REGISTRATION }

@Composable
internal fun HeyTeslaApp(
    state: DiagnosticState,
    support: SpeechProbeState,
    trial: SpeechTrialState,
    ble: BleProbeState,
    uwb: UwbProbeState,
    teslaKey: TeslaKeyProbeState,
    actions: AppActions,
    uwbRanging: TeslaUwbProbeState = TeslaUwbProbeState(),
    navigation: VehicleNavigationPresentation = VehicleNavigationPresentation(VehicleNavigationStatus.OFF),
) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    val nowElapsedMs = uwbPresentationTime(uwbRanging)

    fun navigate(next: Destination) {
        if (destination == next) return
        if (destination == Destination.DIAGNOSTICS || destination == Destination.KEY_REGISTRATION) {
            actions.leaveDiagnostics()
        }
        destination = next
    }

    fun back() = navigate(
        when (destination) {
            Destination.DIAGNOSTICS -> Destination.SETTINGS
            Destination.KEY_REGISTRATION -> Destination.SETTINGS
            else -> Destination.HOME
        },
    )

    BackHandler(enabled = destination != Destination.HOME) { back() }

    HeyTeslaTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (destination != Destination.HOME) {
                        TextButton(onClick = { back() }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("뒤로")
                        }
                    }
                    Text(
                        text = when (destination) {
                            Destination.HOME -> "Hey Tesla"
                            Destination.SETTINGS -> "설정"
                            Destination.DIAGNOSTICS -> "개발자 진단"
                            Destination.KEY_REGISTRATION -> "차량 키 등록"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    if (destination == Destination.HOME) {
                        TextButton(
                            onClick = { navigate(Destination.SETTINGS) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("설정") }
                    } else if (destination == Destination.DIAGNOSTICS || destination == Destination.KEY_REGISTRATION) {
                        TextButton(
                            onClick = { navigate(Destination.HOME) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("홈") }
                    }
                }
                if (state.teslaKeyCleanupFailed &&
                    destination != Destination.DIAGNOSTICS &&
                    destination != Destination.KEY_REGISTRATION
                ) {
                    Text(
                        "차량 연결 정리 불명 · 새 작업 차단 · 앱 프로세스 재시작 필요",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                if (uwbRanging.stage == TeslaUwbStage.CLEANUP_FAILED &&
                    destination != Destination.DIAGNOSTICS
                ) {
                    Text(
                        "차량 UWB 종료 불명 · 새 작업 차단 · 앱 프로세스 재시작 필요",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                Box(Modifier.weight(1f)) {
                    when (destination) {
                        Destination.HOME -> HomeScreen(
                            state = state,
                            trial = trial,
                            ble = ble,
                            onOpenSettings = { navigate(Destination.SETTINGS) },
                            onOpenDiagnostics = { navigate(Destination.DIAGNOSTICS) },
                            onStopSession = actions.stopSession,
                            onSetServiceEnabled = actions.setVehicleServiceEnabled,
                            serviceBlockedReason = actions.vehicleServiceBlockedReason(),
                            uwbRanging = uwbRanging,
                            nowElapsedMs = nowElapsedMs,
                            navigation = navigation,
                            onResumeNavigation = actions.resumeVehicleNavigation,
                        )
                        Destination.SETTINGS -> SettingsScreen(
                            state = state,
                            actions = actions,
                            onOpenDiagnostics = { navigate(Destination.DIAGNOSTICS) },
                            onOpenKeyRegistration = { navigate(Destination.KEY_REGISTRATION) },
                            uwbRanging = uwbRanging,
                            navigation = navigation,
                        )
                        Destination.DIAGNOSTICS -> DiagnosticsScreen(
                            state, support, trial, uwb, teslaKey, actions,
                            uwbRanging = uwbRanging, nowElapsedMs = nowElapsedMs,
                        )
                        Destination.KEY_REGISTRATION -> TeslaKeyRegistrationScreen(
                            state, support, teslaKey, actions, serviceOwned = uwbRanging.serviceOwned,
                        )
                    }
                }
            }
        }
    }
}

/** Expire visible samples without polling or waiting for another ranging callback. */
@Composable
private fun uwbPresentationTime(probe: TeslaUwbProbeState): Long {
    var lastExpiryElapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(probe.stage, probe.samples, probe.livePeerIndices) {
        if (probe.stage != TeslaUwbStage.RANGING) return@LaunchedEffect
        while (true) {
            val now = SystemClock.elapsedRealtime()
            var untilExpiryMs = Long.MAX_VALUE
            for (sample in probe.samples) {
                if (teslaUwbSampleIsRecent(probe, sample, now)) {
                    untilExpiryMs = minOf(untilExpiryMs,
                        VEHICLE_UWB_SAMPLE_TTL_MS - (now - sample.receivedAtElapsedMs) + 1)
                }
            }
            if (untilExpiryMs == Long.MAX_VALUE) return@LaunchedEffect
            delay(untilExpiryMs)
            lastExpiryElapsedMs = SystemClock.elapsedRealtime()
        }
    }
    return maxOf(lastExpiryElapsedMs, SystemClock.elapsedRealtime())
}
