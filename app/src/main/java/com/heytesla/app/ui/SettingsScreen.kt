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
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.heytesla.app.vehicle.key.VehicleVinState
import com.heytesla.app.vehicle.key.VehicleVinStatus

private enum class SettingsDetail { VEHICLE, PERMISSIONS, ASSISTANT }

@Composable
internal fun SettingsScreen(
    state: DiagnosticState,
    actions: AppActions,
    onOpenDiagnostics: () -> Unit,
    onOpenKeyRegistration: () -> Unit,
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
            ProductVoiceConsentSection(state, actions.setProductVoiceConsent)
        }

        AppSection(title = "서비스 상태") {
            val presentation = vehicleServicePresentation(state)
            Text(presentation.text,
                color = if (presentation.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (state.bleDetectionEnabled || state.bleFieldBusy()) {
                OutlinedButton(
                    onClick = { actions.setVehicleServiceEnabled(false) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("서비스 끄기") }
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
            if (productCleanupUncertain(state)) {
                Text("차량 연결 정리 불명 · 예약 유지 · 앱 프로세스 재시작 필요", color = MaterialTheme.colorScheme.error)
            } else if (state.teslaKeyDiagnosticActive) {
                Text(if (state.bleDetectionEnabled || state.bleFieldBusy()) "서비스 차량 연결 중 · 홈에서 서비스를 끌 수 있어요."
                    else "차량 키 진단 진행·정리 중 · 다른 진단 차단")
                OutlinedButton(
                    onClick = if (state.bleDetectionEnabled || state.bleFieldBusy()) {
                        { actions.setVehicleServiceEnabled(false) }
                    } else actions.cancelTeslaKey,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (state.bleDetectionEnabled || state.bleFieldBusy()) "서비스 끄기" else "차량 키 진단 취소")
                }
            }
            AppRow(
                title = "개발자 진단",
                subtitle = "수동 음성·마이크 · 차량 키·상태 조회 · 이벤트",
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
) {
    PermissionRow(
        title = "음성 대기·수동 마이크 진단",
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
        title = "Bluetooth 차량 감지의 정확한 위치",
        granted = state.locationPermission,
        requestPermission = Manifest.permission.ACCESS_FINE_LOCATION,
        actions = actions,
    )
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
    RowNote("정확한 위치 권한은 Bluetooth 차량 감지에 사용합니다. 좌표·거리·방향 안내는 제공하지 않습니다.")
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
            "연결 기반 음성 대기에 사용합니다. 일반 비서 인식은 제공하지 않습니다. 필요하면 시스템 설정에서 이전 비서를 직접 복원하세요."
        } else {
            "음성 대기에는 Hey Tesla를 직접 선택해야 하며 기존 비서를 대체합니다. 선택만으로 음성 프렁크에 동의한 것은 아닙니다. 일반 비서 인식은 제공하지 않습니다."
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

private fun permissionMenuSummary(state: DiagnosticState): String {
    val granted = (if (state.microphonePermission) 1 else 0) +
        (if (state.bluetoothPermission) 1 else 0) +
        (if (state.bluetoothScanPermission) 1 else 0) +
        (if (state.locationPermission) 1 else 0) +
        (if (state.notificationPermission) 1 else 0)
    return when (granted) {
        5 -> "모두 허용됨"
        0 -> "허용 필요"
        else -> "허용 $granted/5"
    }
}

/** Opening a dialog, restoring ON, or selecting the assistant never grants consent. */
@Composable
internal fun ProductVoiceConsentSection(state: DiagnosticState, onSetConsent: (Boolean) -> Unit) {
    var showConsent by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) showConsent = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Text(
        if (state.productVoiceConsent) "음성 프렁크 동의됨 · 실제 대기 상태는 별도 확인"
        else "음성 프렁크 미동의 · 서비스 ON만으로 마이크를 켜지 않아요.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "인증된 수면 상태가 확인되면 차량 접근 회차당 깨우기를 1회 시도합니다. 차량 전력 사용이 늘 수 있으며, 새 P 확인 전에는 마이크를 켜지 않습니다.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.productVoiceConsentSaveFailed) {
        Text(
            "음성 동의 설정을 저장하지 못했어요. 철회 요청으로 현재 음성·명령은 중지됐지만, 다음 실행 전 저장 상태를 다시 확인해 주세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    OutlinedButton(
        onClick = { if (state.productVoiceConsent) onSetConsent(false) else showConsent = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(if (state.productVoiceConsent) "음성 동의 철회·중지" else "음성 프렁크 사용 안내")
    }
    if (showConsent && !state.productVoiceConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("음성 프렁크 사용 동의") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("마이크 권한과 기본 비서 직접 선택 후, 차량 연결·앱 키 인증·신선한 P가 확인되는 동안 마이크로 음성을 대기합니다. 잠금·주머니 안에서도 대기할 수 있습니다.")
                    Text("인증된 차량 상태가 수면 중이면 접근 회차당 BLE 깨우기를 1회 시도합니다. 상태 미확인에는 깨우지 않으며 차량 전력 사용이 늘 수 있습니다. 깨움 응답만으로 기상이나 P를 확정하지 않습니다.")
                    Text("정확한 호출어·프렁크 명령이 인정되면 오디오를 해제하고 P를 다시 확인한 뒤 한 번의 프렁크 열기 요청을 보냅니다. 인식이나 요청 전송은 실제 열림 완료가 아닙니다.")
                    Text("음성 인식은 화자 인증이 아닙니다. 주변 사람의 말이나 재생음도 명령으로 인식될 수 있습니다.")
                    Text("D·R·N·기어 불명·상태 만료·연결 해제를 확인하면 음성을 중지합니다. 주행 상태는 통신으로 조회하므로 변경 감지에 지연이 있으며 즉시 중지를 보장하지 않습니다.")
                    Text("개발 확인을 위해 제품 음성의 인식 텍스트와 종료 결과를 앱 내부 백업 제외 영역에 자동 보관하고 홈의 최근 음성 결과에 표시합니다. 주변 대화가 포함될 수 있습니다. 제품 음성 녹음·일반 로그 원문 기록·업로드·자동 삭제는 하지 않습니다. 직접 시작하는 개발자 음성 진단의 저장 정책과는 별개입니다.")
                    Text("동의는 다음 새 차량 접근부터 적용됩니다. 현재 접근을 자동으로 다시 시작하지 않습니다. 서비스 OFF 또는 동의 철회는 언제든 가능합니다.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showConsent = false; onSetConsent(true) }) { Text("동의하고 사용") }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) { Text("동의하지 않음") }
            },
        )
    }
}
