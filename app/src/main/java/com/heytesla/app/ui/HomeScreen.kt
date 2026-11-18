package com.heytesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.heytesla.app.speech.product.ProductVoiceTranscriptRecord
import com.heytesla.app.vehicle.key.VehicleVinStatus
import com.heytesla.app.vehicle.connection.TeslaConnectionStage
import com.heytesla.app.vehicle.protocol.TeslaGear
import java.text.DateFormat
import java.util.Date

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
    onSetProductVoiceConsent: (Boolean) -> Unit,
) {
    val cleanupFailed = ble.status == BleProbeStatus.CLEANUP_FAILED || productCleanupUncertain(state)
    val serviceFailure = state.bleFieldTrialStopReason
    val presentation = vehicleServicePresentation(state)
    val hasServiceError = cleanupFailed || presentation.error
    val status = if (cleanupFailed) "자원 정리 불명 · 앱 프로세스 재시작 필요" else presentation.text
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
            RecentProductVoiceResults(state)
            if (state.bleDetectionEnabled && state.bleFieldTrialActive && state.observationServiceRunning &&
                !state.bleFieldTrialStopping && !cleanupFailed
            ) {
                DetailRow("차량 연결", when (state.vehicleConnection.stage) {
                    TeslaConnectionStage.IDLE -> "연결 전"
                    TeslaConnectionStage.PREPARING -> "등록 앱 키 준비 중"
                    TeslaConnectionStage.CONNECTING -> "연결 중 · 인증 전"
                    TeslaConnectionStage.CHECKING_VEHICLE -> "앱 키 인증·수면 상태 확인 중"
                    TeslaConnectionStage.WAKING_VEHICLE -> "조건부 깨우기 준비·응답 확인 중"
                    TeslaConnectionStage.AUTHENTICATING -> "앱 키 인증 중"
                    TeslaConnectionStage.MONITORING -> "앱 키 인증 후 주행 상태 확인 중"
                    TeslaConnectionStage.EXECUTING -> "프렁크 요청 처리 중"
                    TeslaConnectionStage.STOPPED -> "종료"
                    TeslaConnectionStage.BLOCKED -> "차단"
                    TeslaConnectionStage.FAILED -> "실패"
                })
                DetailRow("주차 상태", if (
                    state.vehicleConnection.stage == TeslaConnectionStage.MONITORING &&
                    state.vehicleConnection.gear == TeslaGear.P && state.vehicleConnection.parkFresh
                ) "신선한 P 확인 · 통신 조회 시점 기준" else "신선한 P 확인 안 됨 · 과거 상태로 허용하지 않음")
            }
            ProductVoiceConsentSection(state, onSetProductVoiceConsent)
            Text(
                "인증된 차량 연결과 신선한 P가 확인되는 동안만 음성을 대기해요. D·R·N·기어 불명·정보 만료·연결 해제 시 음성을 중지해요. 주행 상태 조회에는 통신 지연이 있어 즉시 감지를 보장하지 않아요.",
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

/** 복원된 결과는 표시만 한다. 이 영역에는 음성·차량 실행 콜백을 전달하지 않는다. */
@Composable
private fun RecentProductVoiceResults(state: DiagnosticState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("최근 음성 결과", style = MaterialTheme.typography.titleLarge)
        Text(
            "개발 확인용으로 인식 텍스트·종료 결과를 앱 내부에 자동 보관해요. 녹음·업로드는 하지 않아요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.productVoiceResultsLoading) {
            Text("저장된 음성 결과를 불러오는 중이에요.",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else if (state.productVoiceResults.isEmpty() && state.productVoiceResultsReadFailure == null) {
            Text("아직 음성 결과가 없어요.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.productVoiceResultsReadFailure?.let { failure ->
            Text(
                when (failure) {
                    "LIMIT_REACHED" -> "음성 결과 읽기 실패 · 보관 용량 한도로 이전 결과를 불러오지 못했어요."
                    "INVALID_RECORD" -> "음성 결과 읽기 실패 · 일부 보관 파일을 확인할 수 없어요."
                    else -> "음성 결과 읽기 실패 · 이전 결과를 불러오지 못했어요."
                },
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.productTranscriptWriteFailure?.let { failure ->
            Text(
                "음성 결과 저장 실패 · 이번 실행에서 일부 결과를 보관하지 못했어요. " +
                    when (failure) {
                        "LIMIT_REACHED" -> "보관 용량 한도에 도달했어요. 기존 파일은 자동 삭제하지 않아요."
                        "QUEUE_FULL" -> "저장 대기열 한도로 누락된 결과가 있어요."
                        "INVALID_RECORD" -> "보관 항목을 확인하지 못했어요."
                        else -> "파일 보관 중 오류가 있었어요."
                    } + " 일부 결과는 앱 종료 후 남지 않을 수 있어요.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.productVoiceResults.take(5).forEach { record ->
            ProductVoiceResult(record)
        }
        Text(
            "이번 실행 파일 저장 ${state.productTranscriptSavedCount}건 · 인식 판정은 명령 전송·차량 동작 완료가 아니에요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProductVoiceResult(record: ProductVoiceTranscriptRecord) {
    val timestamp = remember(record.wallTimeMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(record.wallTimeMs))
    }
    val failed = record.diagnostic.outcome == "FAILURE" || record.diagnostic.outcome == "SEALED"
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("시각: $timestamp", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            when (record.diagnostic.outcome) {
                "EMPTY" -> "빈 인식 결과 · 명령 불일치"
                "GRAMMAR_NO_MATCH" -> "명령 불일치"
                "ACCEPTED" -> "명령 문장 인정 · 차량 실행 결과 아님"
                "SEALED" -> "음성 회차 중단 · 명령 실행 허가 아님"
                "FAILURE" -> "음성 처리 실패 · 명령 실행 허가 아님"
                else -> "인식 판정 확인 안 됨"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            when {
                record.text == null && record.reason == "PRODUCT_VOICE_UTTERANCE_TRUNCATED" ->
                    "인식 전 발화 잘림 · 원문 없음"
                record.text == null -> "인식 원문 없음 · 종료 결과만 보관"
                record.text.isEmpty() -> "인식 원문: (빈 결과)"
                else -> record.text
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("사유: ${record.reason ?: "기존 기록 · 사유 없음"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
