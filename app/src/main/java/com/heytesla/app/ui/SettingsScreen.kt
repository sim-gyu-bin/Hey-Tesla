package com.heytesla.app.ui

import android.Manifest
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.heytesla.app.BuildConfig
import com.heytesla.app.DiagnosticState
import com.heytesla.app.vehicle.uwb.TeslaUwbProbeState
import com.heytesla.app.vehicle.key.VehicleVinState
import com.heytesla.app.vehicle.key.VehicleVinStatus
import com.heytesla.app.vehicle.navigation.VehicleNavigationPresentation
import com.heytesla.app.vehicle.navigation.VehicleNavigationStatus

private enum class SettingsDetail { VEHICLE, PERMISSIONS, ASSISTANT }

@Composable
internal fun SettingsScreen(
    state: DiagnosticState,
    actions: AppActions,
    onOpenDiagnostics: () -> Unit,
    onOpenKeyRegistration: () -> Unit,
    uwbRanging: TeslaUwbProbeState = TeslaUwbProbeState(),
    navigation: VehicleNavigationPresentation = VehicleNavigationPresentation(VehicleNavigationStatus.OFF),
) {
    var expanded by remember { mutableStateOf<SettingsDetail?>(null) }
    val appSettings = Settings.ACTION_APPLICATION_DETAILS_SETTINGS

    fun toggle(detail: SettingsDetail) {
        expanded = if (expanded == detail) null else detail
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        AppSection(title = "초기 준비") {
            AppRow(
                title = "차량 등록",
                subtitle = if (state.associationCount == 0) "시스템 등록 필요" else "시스템 등록됨",
                symbol = AppSymbol.VEHICLE,
                expanded = expanded == SettingsDetail.VEHICLE,
                onClick = { toggle(SettingsDetail.VEHICLE) },
            )
            if (expanded == SettingsDetail.VEHICLE) {
                VehicleDetail(
                    registered = state.associationCount > 0,
                    vinState = state.vehicleVin,
                    blocked = actions.vehicleVinChangeBlockedReason(),
                    onOpenVinRegistration = onOpenDiagnostics,
                    onAssociate = actions.associateVehicle,
                )
            }
            AppRow(
                title = "차량에 앱 키 등록",
                subtitle = if (state.teslaKeyCleanupFailed) {
                    "정리 불명 · 앱 프로세스 재시작 필요"
                } else {
                    "시스템 등록과 별개 · 키카드로 직접 승인"
                },
                symbol = AppSymbol.VEHICLE,
                onClick = onOpenKeyRegistration,
            )
            AppRow(
                title = "권한",
                subtitle = permissionMenuSummary(state, uwbRanging.rangingPermission),
                symbol = AppSymbol.PERMISSIONS,
                expanded = expanded == SettingsDetail.PERMISSIONS,
                onClick = { toggle(SettingsDetail.PERMISSIONS) },
            )
            if (expanded == SettingsDetail.PERMISSIONS) {
                PermissionDetail(state, actions, uwbRanging.rangingPermission)
            }
            AppRow(
                title = "위치 안내 재개",
                subtitle = vehicleNavigationText(navigation),
                symbol = AppSymbol.PERMISSIONS,
                enabled = state.bleFieldTrialActive && state.observationServiceRunning &&
                    !state.bleFieldTrialStopping && (navigation.status == VehicleNavigationStatus.START_REQUIRED ||
                    navigation.status == VehicleNavigationStatus.FOREGROUND_FAILED),
                onClick = actions.resumeVehicleNavigation,
            )
            AppRow(
                title = "기본 비서",
                subtitle = if (state.assistant) "기존 비서를 대체 중" else "현재 기본 비서 아님",
                symbol = AppSymbol.ASSISTANT,
                expanded = expanded == SettingsDetail.ASSISTANT,
                onClick = { toggle(SettingsDetail.ASSISTANT) },
            )
            if (expanded == SettingsDetail.ASSISTANT) {
                AssistantDetail(state.assistant, actions)
            }
        }

        AppSection(title = "앱 정보") {
            DetailRow(
                label = "앱 버전",
                value = "${BuildConfig.VERSION_NAME} (빌드 ${BuildConfig.VERSION_CODE})",
            )
            AppRow(
                title = "앱 권한 설정",
                subtitle = "허용한 권한 · 앱 정보",
                symbol = AppSymbol.INFO,
                onClick = { actions.openSystemSettings(appSettings) },
            )
        }

        AppSection(title = "개발자") {
            if (state.teslaKeyCleanupFailed) {
                Text("차량 연결 정리 불명 · 예약 유지 · 앱 프로세스 재시작 필요", color = MaterialTheme.colorScheme.error)
            } else if (state.teslaKeyDiagnosticActive) {
                Text(if (uwbRanging.serviceOwned) "서비스 UWB 작업 중 · 홈에서 서비스를 끌 수 있어요."
                    else "차량 키 진단 진행·정리 중 · 다른 진단 차단")
                OutlinedButton(
                    onClick = if (uwbRanging.serviceOwned) {
                        { actions.setVehicleServiceEnabled(false) }
                    } else actions.cancelTeslaKey,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (uwbRanging.serviceOwned) "서비스 끄기" else "차량 키 진단 취소")
                }
            }
            AppRow(
                title = "개발자 진단",
                subtitle = "수동 음성·마이크 · UWB 상세·수동 측정 · 차량 키·상태 조회 · 이벤트",
                symbol = AppSymbol.DIAGNOSTICS,
                onClick = onOpenDiagnostics,
            )
        }
    }
}

@Composable
private fun VehicleDetail(
    registered: Boolean,
    vinState: VehicleVinState,
    blocked: String?,
    onOpenVinRegistration: () -> Unit,
    onAssociate: () -> Unit,
) {
    Text(vehicleVinConsumerLabel(vinState))
    TextButton(onClick = onOpenVinRegistration, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("개발자 진단에서 공통 VIN 등록·변경")
    }
    if (registered) {
        Text(
            text = "차량 등록됨",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        return
    }
    Text(
        text = "시스템 기기 선택 화면에서 차량 등록을 직접 승인합니다.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onAssociate,
        enabled = vinState.status == VehicleVinStatus.READY && blocked == null,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text("차량 선택 시작")
    }
    Text(
        text = "명시적으로 등록한 공통 VIN으로 차량을 선택합니다. 시스템 등록은 앱 키 인증·차량 제어 가능을 뜻하지 않습니다.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    blocked?.let { Text(vehicleVinBlockedLabel(it), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun PermissionDetail(
    state: DiagnosticState,
    actions: AppActions,
    rangingPermission: Boolean?,
) {
    PermissionRow(
        title = "수동 음성·마이크 진단",
        granted = state.microphonePermission,
        requestPermission = Manifest.permission.RECORD_AUDIO,
        actions = actions,
    )
    PermissionRow(
        title = "Bluetooth 연결 및 상태",
        granted = state.bluetoothPermission,
        requestPermission = Manifest.permission.BLUETOOTH_CONNECT,
        actions = actions,
    )
    PermissionRow(
        title = "Bluetooth 차량 감지 스캔",
        granted = state.bluetoothScanPermission,
        requestPermission = Manifest.permission.BLUETOOTH_SCAN,
        actions = actions,
    )
    PermissionRow(
        title = "차량 감지·좌표 안내의 정확한 위치",
        granted = state.locationPermission,
        requestPermission = Manifest.permission.ACCESS_FINE_LOCATION,
        actions = actions,
    )
    AppRow(
        title = "항상 위치 허용 (선택)",
        subtitle = if (state.backgroundLocationPermission) "허용됨 · 서비스 복원 때 새 위치 안내 시작 가능"
            else "미허용 · 재부팅 후 좌표 안내만 앱에서 재개",
        symbol = AppSymbol.PERMISSIONS,
        onClick = { actions.openSystemSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) },
    )
    RowNote("원하면 시스템 앱 권한 → 위치에서 ‘항상 허용’을 직접 선택하세요. 필수 권한이 아니며, 권한을 바꾸는 것만으로 꺼진 서비스나 위치 안내를 시작하지 않습니다.")
    PermissionRow(
        title = "UWB 거리 측정",
        granted = rangingPermission,
        requestPermission = Manifest.permission.RANGING,
        actions = actions,
    )
    RowNote("서비스 ON에서 차량 감지 후 UWB 자동 측정에 사용합니다. 권한은 이 항목에서 직접 승인하며, 별도 방향·센서 권한은 요청하지 않습니다.")
    PermissionRow(
        title = "서비스·진단 알림",
        granted = state.notificationPermission,
        requestPermission = Manifest.permission.POST_NOTIFICATIONS,
        actions = actions,
    )
    AppRow(
        title = "Bluetooth 설정",
        subtitle = if (state.bluetooth) "켜져 있음" else "꺼져 있거나 상태 확인 불가",
        symbol = AppSymbol.BLUETOOTH,
        onClick = { actions.openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS) },
    )
    AppRow(
        title = "시스템 위치 서비스",
        subtitle = if (state.locationEnabled) "켜짐 · 차량 감지 준비됨" else "꺼짐 · 차량 감지 시작 불가",
        symbol = AppSymbol.PERMISSIONS,
        onClick = { actions.openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
    )
    RowNote("휴대전화 위치·나침반은 차량 접근 회차에서 수집하고, 종료 후에는 유효한 차량 좌표의 안내 기한 안에서만 유지합니다. 좌표는 RAM 전용이며 저장·로그·클라우드 전송하지 않습니다. 항상 위치 미허용이어도 앱에서 시작한 안내는 잠금 후 유지할 수 있고, 재부팅 후에는 앱에서 재개해야 합니다.")
}

@Composable
private fun AssistantDetail(assistant: Boolean, actions: AppActions) {
    AppRow(
        title = "기본 디지털 비서 설정",
        subtitle = if (assistant) "기존 비서를 대체 중" else "현재 기본 비서 아님",
        symbol = AppSymbol.ASSISTANT,
        onClick = { actions.openSystemSettings(Settings.ACTION_VOICE_INPUT_SETTINGS) },
    )
    RowNote(
        if (assistant) {
            "일반 비서 인식은 제공하지 않으며 차량 감지·UWB 서비스에 필수도 아닙니다. 필요하면 이전 비서를 직접 복원하세요."
        } else {
            "선택하면 기존 비서를 대체합니다. 일반 비서 인식은 제공하지 않으며 차량 감지·UWB 서비스에 필수도 아닙니다. 이후 이전 비서를 직접 복원하세요."
        },
    )
}

@Composable
private fun RowNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean?,
    requestPermission: String,
    actions: AppActions,
) {
    AppRow(
        title = title,
        subtitle = when (granted) {
            true -> "허용됨"
            false -> "허용 필요 · 직접 승인"
            null -> "권한 확인 전 · 직접 확인"
        },
        symbol = AppSymbol.PERMISSIONS,
        onClick = {
            if (granted == true) actions.openSystemSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            else actions.requestPermission(requestPermission)
        },
    )
}

private fun permissionMenuSummary(state: DiagnosticState, rangingPermission: Boolean?): String {
    val granted = (if (state.microphonePermission) 1 else 0) +
        (if (state.bluetoothPermission) 1 else 0) +
        (if (state.bluetoothScanPermission) 1 else 0) +
        (if (state.locationPermission) 1 else 0) +
        (if (state.notificationPermission) 1 else 0) +
        (if (rangingPermission == true) 1 else 0)
    return when (granted) {
        6 -> "모두 허용됨"
        0 -> "허용 필요"
        else -> "허용 $granted/6"
    }
}
