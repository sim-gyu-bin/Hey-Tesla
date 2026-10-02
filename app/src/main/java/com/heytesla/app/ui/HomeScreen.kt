package com.heytesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.heytesla.app.BleProbeState
import com.heytesla.app.BleProbeStatus
import com.heytesla.app.DiagnosticState
import com.heytesla.app.SpeechProbeState
import com.heytesla.app.SpeechProbeStatus
import com.heytesla.app.SpeechTrialState

@Composable
internal fun HomeScreen(
    state: DiagnosticState,
    support: SpeechProbeState,
    trial: SpeechTrialState,
    ble: BleProbeState,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onStopSession: () -> Unit,
) {
    val restartRequired = trial.reason?.contains("RESTART_REQUIRED") == true ||
        ble.status == BleProbeStatus.CLEANUP_FAILED || (state.bleDiagnosticActive && !ble.active)
    val processing = state.sessionId != null || state.speechDiagnosticActive || trial.active
    val status: String
    val nextAction: () -> Unit
    val nextLabel: String
    when {
        ble.status == BleProbeStatus.CLEANUP_FAILED || (state.bleDiagnosticActive && !ble.active) -> {
            status = "BLE 정리·예약 해제가 확인되지 않았어요. 앱을 다시 시작해 주세요."
            nextAction = onOpenDiagnostics
            nextLabel = "진단 상태 보기"
        }
        ble.active -> {
            status = if (ble.status == BleProbeStatus.CLEANING_UP) {
                "BLE 연결을 정리하고 있어요."
            } else {
                "BLE 연결 진단이 진행 중이에요."
            }
            nextAction = onStopSession
            nextLabel = "BLE 연결 진단 취소"
        }
        restartRequired -> {
            status = "오디오 정리에 실패했어요. 앱을 다시 시작해 주세요."
            nextAction = onOpenDiagnostics
            nextLabel = "개발자 진단 보기"
        }
        processing && state.speechDiagnosticActive && !trial.active -> {
            status = "오디오 사용 종료를 기다리고 있어요."
            nextAction = onOpenDiagnostics
            nextLabel = "진단 상태 보기"
        }
        processing -> {
            status = if (trial.active) "음성 진단을 처리하고 있어요." else "마이크 진단이 진행 중이에요."
            nextAction = onStopSession
            nextLabel = "진행 중인 세션 중지"
        }
        state.observationStartPending -> {
            status = "접근 관찰 서비스를 시작하고 있어요."
            nextAction = onOpenDiagnostics
            nextLabel = "접근 진단 보기"
        }
        state.observationServiceRunning -> {
            status = when {
                !state.observing -> "관찰 서비스 실행 중 · 관찰 요청 확인이 필요해요."
                state.present -> "근접 신호를 확인했어요. 차량 제어는 연결되지 않았어요."
                else -> "접근을 관찰하고 있어요. 차량 감지는 아직 확인되지 않았어요."
            }
            nextAction = onOpenDiagnostics
            nextLabel = "접근 진단 보기"
        }
        state.associations.isEmpty() -> {
            status = "차량 등록이 필요해요."
            nextAction = onOpenSettings
            nextLabel = "차량 등록 열기"
        }
        !state.microphonePermission || !state.bluetoothPermission -> {
            status = "마이크 또는 Bluetooth 권한이 필요해요."
            nextAction = onOpenSettings
            nextLabel = "권한 설정 열기"
        }
        !state.bluetooth -> {
            status = "Bluetooth 설정을 확인해 주세요."
            nextAction = onOpenSettings
            nextLabel = "설정 확인"
        }
        !state.assistant -> {
            status = "자동 마이크 진단에는 기본 비서 설정이 필요해요."
            nextAction = onOpenSettings
            nextLabel = "설정 확인"
        }
        else -> {
            status = "음성 기능을 개발 중이에요."
            nextAction = onOpenDiagnostics
            nextLabel = "개발자 진단 보기"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Model Y",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "주니퍼 · 대상 차종",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "개발 중 · 차량 제어 미연결",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = status,
                style = MaterialTheme.typography.headlineSmall,
                color = if (restartRequired) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )
            Button(
                onClick = nextAction,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(nextLabel)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "준비 상태",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DetailRow(
                label = "차량 등록",
                value = if (state.associations.isEmpty()) "등록 필요" else "등록됨",
            )
            DetailRow(label = "권한", value = permissionSummary(state))
            DetailRow(label = "한국어 모델", value = supportSummary(support))
        }
    }
}

private fun permissionSummary(state: DiagnosticState): String = when {
    !state.microphonePermission || !state.bluetoothPermission -> "권한 필요"
    !state.notificationPermission -> "알림 권한 확인"
    else -> "허용됨"
}

private fun supportSummary(support: SpeechProbeState): String = when {
    support.metadata?.koKrInstalled == true -> "모델 확인됨"
    support.status == SpeechProbeStatus.IDLE -> "확인 필요"
    support.status == SpeechProbeStatus.RUNNING -> "확인 중"
    else -> "확인 필요"
}
