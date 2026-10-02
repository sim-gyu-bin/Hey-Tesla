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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.heytesla.app.BleProbeState
import com.heytesla.app.BleProbeStatus
import com.heytesla.app.DiagnosticState

private enum class SettingsDetail { VEHICLE, PERMISSIONS, ASSISTANT }

@Composable
internal fun SettingsScreen(
    state: DiagnosticState,
    ble: BleProbeState,
    actions: AppActions,
    onOpenDiagnostics: () -> Unit,
) {
    // VIN은 이 화면의 메모리에만 둔다. 상세를 닫거나 다른 상세로 옮기면 즉시 비운다.
    var vin by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<SettingsDetail?>(null) }
    val appSettings = Settings.ACTION_APPLICATION_DETAILS_SETTINGS

    fun toggle(detail: SettingsDetail) {
        expanded = if (expanded == detail) null else detail
        vin = ""
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
                subtitle = if (state.associations.isEmpty()) "등록 필요" else "등록됨",
                symbol = AppSymbol.VEHICLE,
                expanded = expanded == SettingsDetail.VEHICLE,
                onClick = { toggle(SettingsDetail.VEHICLE) },
            )
            if (expanded == SettingsDetail.VEHICLE) {
                VehicleDetail(
                    registered = state.associations.isNotEmpty(),
                    vin = vin,
                    onVinChange = { vin = it.take(17) },
                    onAssociate = {
                        val input = vin
                        vin = ""
                        actions.associateVehicle(input)
                    },
                )
            }
            AppRow(
                title = "권한",
                subtitle = permissionMenuSummary(state),
                symbol = AppSymbol.PERMISSIONS,
                expanded = expanded == SettingsDetail.PERMISSIONS,
                onClick = { toggle(SettingsDetail.PERMISSIONS) },
            )
            if (expanded == SettingsDetail.PERMISSIONS) {
                PermissionDetail(state, actions)
            }
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
            AppRow(
                title = "앱 권한 설정",
                subtitle = "허용한 권한 · 앱 정보",
                symbol = AppSymbol.INFO,
                onClick = { actions.openSystemSettings(appSettings) },
            )
        }

        AppSection(title = "개발자") {
            if (ble.status == BleProbeStatus.CLEANUP_FAILED || (state.bleDiagnosticActive && !ble.active)) {
                Text(
                    "BLE 정리·예약 해제 미확인 · 앱 재시작 필요",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (ble.active) {
                Text(
                    if (ble.status == BleProbeStatus.CLEANING_UP) "BLE 연결 정리 중" else "BLE 연결 진단 중",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = actions.cancelBle,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("BLE 연결 진단 취소") }
            }
            AppRow(
                title = "개발자 진단",
                subtitle = "인식·마이크 시험 · 이벤트",
                symbol = AppSymbol.DIAGNOSTICS,
                onClick = onOpenDiagnostics,
            )
        }
    }
}

@Composable
private fun VehicleDetail(
    registered: Boolean,
    vin: String,
    onVinChange: (String) -> Unit,
    onAssociate: () -> Unit,
) {
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
    OutlinedTextField(
        value = vin,
        onValueChange = onVinChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("차량 VIN · 메모리 전용") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
    )
    Button(
        onClick = onAssociate,
        enabled = vin.isNotBlank(),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text("차량 선택 시작")
    }
    Text(
        text = "VIN은 이 화면의 메모리에만 보관하며, 제출 직전에 비웁니다.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionDetail(
    state: DiagnosticState,
    actions: AppActions,
) {
    PermissionRow(
        title = "마이크",
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
        title = "진단 알림",
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
        subtitle = "CDM 검색 조건일 수 있음",
        symbol = AppSymbol.PERMISSIONS,
        onClick = { actions.openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
    )
    RowNote("앱은 위치를 수집하거나 위치 권한을 요청하지 않습니다.")
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
            "일반 비서 인식은 제공하지 않고 시험에 필수도 아닙니다. 필요하면 이전 비서를 직접 복원하세요."
        } else {
            "선택하면 기존 비서를 대체합니다. 일반 비서 인식은 제공하지 않고 시험에 필수도 아닙니다. 이후 이전 비서를 직접 복원하세요."
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
    granted: Boolean,
    requestPermission: String,
    actions: AppActions,
) {
    AppRow(
        title = title,
        subtitle = if (granted) "허용됨" else "허용 필요 · 직접 승인",
        symbol = AppSymbol.PERMISSIONS,
        onClick = {
            if (granted) actions.openSystemSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            else actions.requestPermission(requestPermission)
        },
    )
}

private fun permissionMenuSummary(state: DiagnosticState): String {
    val granted = listOf(
        state.microphonePermission,
        state.bluetoothPermission,
        state.notificationPermission,
    ).count { it }
    return when (granted) {
        3 -> "모두 허용됨"
        0 -> "허용 필요"
        else -> "허용 $granted/3"
    }
}
