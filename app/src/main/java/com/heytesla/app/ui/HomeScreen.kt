package com.heytesla.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.heytesla.app.vehicle.ble.BleProbeState
import com.heytesla.app.vehicle.ble.BleProbeStatus
import com.heytesla.app.DiagnosticState
import com.heytesla.app.speech.SpeechTrialState
import com.heytesla.app.vehicle.uwb.TeslaUwbProbeState
import com.heytesla.app.vehicle.uwb.TeslaUwbStage
import com.heytesla.app.vehicle.key.VehicleVinStatus
import com.heytesla.app.vehicle.navigation.VehicleNavigationPresentation
import com.heytesla.app.vehicle.navigation.VehicleNavigationStatus

@Composable
internal fun HomeScreen(
    state: DiagnosticState,
    trial: SpeechTrialState,
    ble: BleProbeState,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onStopSession: () -> Unit,
    onSetServiceEnabled: (Boolean) -> Unit,
    serviceBlockedReason: String?,
    uwbRanging: TeslaUwbProbeState = TeslaUwbProbeState(),
    nowElapsedMs: Long = 0L,
    navigation: VehicleNavigationPresentation = VehicleNavigationPresentation(VehicleNavigationStatus.OFF),
    onResumeNavigation: () -> Unit,
) {
    val cleanupFailed = ble.status == BleProbeStatus.CLEANUP_FAILED ||
        (uwbRanging.serviceOwned && uwbRanging.stage == TeslaUwbStage.CLEANUP_FAILED) ||
        state.bleFieldTrialStopReason == "LOCAL_CLOSE_FAILED_RESTART_REQUIRED" ||
        navigation.status == VehicleNavigationStatus.CLEANUP_FAILED
    val serviceFailure = state.bleFieldTrialStopReason?.takeUnless {
        it == "USER_STOP" || it == "DISABLED" || it == "BLE_FIELD_NOTIFICATION_STOP"
    }
    val scanFailed = state.bleFieldScanFailure != null &&
        (state.bleDetectionEnabled || state.bleFieldTrialActive)
    val serviceUwbText = if (state.bleFieldTrialActive && state.observationServiceRunning) {
        vehicleUwbNotificationText(uwbRanging, nowElapsedMs)
    } else null
    val hasServiceError = cleanupFailed || scanFailed ||
        (!state.bleFieldTrialActive && serviceFailure != null) ||
        (serviceUwbText != null && (uwbRanging.stage == TeslaUwbStage.FAILED ||
            uwbRanging.stage == TeslaUwbStage.BLOCKED || uwbRanging.stage == TeslaUwbStage.TIMED_OUT))
    val status = when {
        cleanupFailed -> "서비스 오류 · 연결 정리가 확인되지 않았어요. 앱을 다시 시작해 주세요."
        state.bleFieldTrialStopping -> "서비스 중지 중 · 감지·UWB 종료를 기다리고 있어요."
        scanFailed -> "서비스 오류 · 차량 감지 스캔에 실패했어요."
        state.bleFieldTrialActive && state.bleFieldScanRecoveryAt != null ->
            "서비스 복원 대기 · OS 스캔 제한에서 복구를 기다리고 있어요."
        state.bleFieldTrialStarting || (state.bleDetectionEnabled && state.observationStartPending) ->
            "서비스 시작 중 · 아직 감시가 시작되지 않았어요."
        !state.bleFieldTrialActive && (serviceFailure == "OBSERVATION_FGS_BACKGROUND_START_DENIED" ||
            serviceFailure == "BLE_DETECTION_USER_STOP_REQUIRES_START") ->
            "OS 시작 제한 · 감시 실행 안 됨 · 끈 뒤 앱 화면에서 다시 켜 주세요."
        !state.bleFieldTrialActive && serviceFailure != null ->
            "서비스 실행 안 됨 · ${vehicleServiceReasonLabel(serviceFailure)}"
        serviceUwbText != null -> if (hasServiceError) serviceUwbText else "서비스 ON · 차량 안내 중"
        state.bleFieldTrialActive && state.observationServiceRunning && state.bleFieldScanRunning ->
            if (state.present) "차량 신호 확인 · UWB 자동 측정을 준비하고 있어요."
            else "서비스 ON · 차량 신호를 기다리고 있어요."
        state.bleFieldTrialActive && state.observationServiceRunning ->
            "서비스 ON · 감지 스캔은 아직 실행 확인 전이에요."
        state.bleDetectionEnabled -> "서비스 복원 대기 · 감시가 실행되지 않고 있어요."
        else -> "서비스 OFF · 차량 감지·UWB 자동 측정을 사용하지 않아요."
    }
    val startBlocked = serviceBlockedReason.takeIf { !state.bleDetectionEnabled }
    val audioCleanupFailed = trial.reason?.contains("RESTART_REQUIRED") == true
    val processing = state.sessionId != null || state.speechDiagnosticActive || trial.active

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Model Y", style = MaterialTheme.typography.headlineLarge)
            Text("주니퍼 · 대상 차종", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (state.vehicleVin.status == VehicleVinStatus.READY) vehicleVinConsumerLabel(state.vehicleVin)
                else "차량 VIN 등록이 필요해요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(
                        value = state.bleDetectionEnabled,
                        // 저장된 ON은 권한 상실·시작 실패·정리 중에도 항상 끌 수 있다.
                        enabled = state.bleDetectionEnabled || startBlocked == null,
                        role = Role.Switch,
                        onValueChange = onSetServiceEnabled,
                    )
                    .semantics { contentDescription = "서비스 사용" },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("서비스 사용", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Switch(
                    checked = state.bleDetectionEnabled,
                    onCheckedChange = null,
                    enabled = state.bleDetectionEnabled || startBlocked == null,
                )
            }
            Text(
                status,
                style = MaterialTheme.typography.headlineSmall,
                color = if (hasServiceError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    if (hasServiceError) error(status)
                },
            )
            if (state.bleFieldTrialActive && state.observationServiceRunning && !state.bleFieldTrialStopping &&
                !cleanupFailed && !scanFailed
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val arrow = vehicleNavigationArrow(navigation)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (arrow != null) {
                            val color = MaterialTheme.colorScheme.onBackground
                            Canvas(Modifier.size(48.dp).rotate(arrow.toFloat()).semantics {
                                contentDescription = "좌표 안내 나침반 · 폰 상단 기준"
                            }) {
                                val x = size.width / 2f
                                val tip = size.height * 0.15f
                                val wing = size.height * 0.4f
                                drawLine(color, Offset(x, size.height * 0.85f), Offset(x, tip), strokeWidth = 4f)
                                drawLine(color, Offset(x, tip), Offset(size.width * 0.25f, wing), strokeWidth = 4f)
                                drawLine(color, Offset(x, tip), Offset(size.width * 0.75f, wing), strokeWidth = 4f)
                            }
                        }
                        Text(vehicleNavigationText(navigation), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(serviceUwbText ?: "UWB 실측 미수신",
                        style = MaterialTheme.typography.bodyLarge)
                    if (navigation.status == VehicleNavigationStatus.START_REQUIRED ||
                        navigation.status == VehicleNavigationStatus.FOREGROUND_FAILED
                    ) {
                        OutlinedButton(onClick = onResumeNavigation, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("위치 안내 재개")
                        }
                    }
                }
            }
            Text(
                "차량 신호 감지 후 기존 앱 키로 좌표를 읽고 휴대전화 위치·나침반으로 안내하며, UWB 실측은 별도로 표시해요. 차량 좌표 수신은 실차 확인 전이에요. 자동 마이크·차량 제어는 연결되지 않았어요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            startBlocked?.let {
                Text("시작할 수 없어요 · ${vehicleServiceReasonLabel(it)}",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (startBlocked != null || serviceFailure != null) "서비스 설정 확인" else "차량·권한 설정")
            }
        }

        if (audioCleanupFailed || processing || cleanupFailed) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when {
                        audioCleanupFailed -> "오디오 정리에 실패했어요. 앱을 다시 시작해 주세요."
                        cleanupFailed -> "연결 정리 실패로 새 진단이 차단되어 있어요."
                        state.speechDiagnosticActive && !trial.active -> "오디오 사용 종료를 기다리고 있어요."
                        else -> "마이크·음성 진단이 진행 중이에요."
                    },
                    color = if (audioCleanupFailed || cleanupFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (processing && !audioCleanupFailed) {
                    OutlinedButton(onClick = onStopSession, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("진행 중인 세션 중지")
                    }
                }
                TextButton(onClick = onOpenDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("개발자 진단 보기")
                }
            }
        }
    }
}

private fun vehicleServiceReasonLabel(reason: String): String = when (reason) {
    "VIN_LOADING" -> "차량 VIN을 복원하고 있어요."
    "VIN_NOT_REGISTERED", "VIN_NOT_READY" -> "설정에서 차량 VIN을 등록해 주세요."
    "VIN_SAVING" -> "차량 VIN 저장이 끝나야 해요."
    "VIN_LOAD_FAILED", "VIN_SAVE_FAILED", "VIN_STORE_FAILED" -> "차량 VIN 저장 상태를 설정에서 확인해 주세요."
    "ASSOCIATION_REQUIRED" -> "설정에서 차량을 등록해 주세요."
    "MULTIPLE_ASSOCIATIONS_UNSUPPORTED" -> "시스템 차량 등록이 하나여야 해요."
    "BLUETOOTH_OFF" -> "설정에서 Bluetooth를 켜 주세요."
    "OBSERVATION_BLUETOOTH_PERMISSION_REQUIRED" -> "설정에서 Bluetooth 연결 권한을 허용해 주세요."
    "BLUETOOTH_SCAN_PERMISSION_REQUIRED" -> "설정에서 Bluetooth 스캔 권한을 허용해 주세요."
    "RANGING_PERMISSION_REQUIRED" -> "설정에서 UWB 거리 측정 권한을 직접 허용해 주세요."
    "BLE_LOCATION_PERMISSION_REQUIRED" -> "설정에서 정확한 위치 권한을 허용해 주세요."
    "BLE_LOCATION_DISABLED" -> "설정에서 시스템 위치 서비스를 켜 주세요."
    "OBSERVATION_NOTIFICATION_PERMISSION_REQUIRED", "OBSERVATION_NOTIFICATIONS_BLOCKED" ->
        "설정에서 서비스 알림을 허용해 주세요."
    "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED", "LOCAL_CLOSE_FAILED_RESTART_REQUIRED" ->
        "자원 정리 실패로 앱 재시작이 필요해요."
    "LEASE_UNAVAILABLE", "OBSERVATION_ACTIVE", "SPEECH_SUPPORT_RUNNING" ->
        "진행 중인 진단을 먼저 종료해 주세요."
    "VISIBLE_UI_REQUIRED" -> "앱 화면에서 직접 켜 주세요."
    "BLE_DETECTION_USER_STOP_REQUIRES_START" -> "OS에서 중지된 서비스예요. 끈 뒤 앱 화면에서 다시 켜 주세요."
    "OBSERVATION_FGS_BACKGROUND_START_DENIED" -> "OS가 백그라운드 시작을 제한했어요. 끈 뒤 앱 화면에서 다시 켜 주세요."
    "OBSERVATION_FGS_START_FAILED", "OBSERVATION_FGS_START_TIMEOUT", "OBSERVATION_FGS_SECURITY_DENIED" ->
        "서비스 시작에 실패했어요. 설정을 확인한 뒤 다시 켜 주세요."
    "BLE_DETECTION_PREFERENCE_FAILED" -> "서비스 사용 설정을 저장하지 못했어요."
    "CDM_UNSUPPORTED", "CDM_UNAVAILABLE" -> "이 기기에서 차량 등록 서비스를 사용할 수 없어요."
    else -> "설정 확인이 필요해요 ($reason)."
}
