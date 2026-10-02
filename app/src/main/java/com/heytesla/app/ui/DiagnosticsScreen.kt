package com.heytesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.heytesla.app.BleProbeState
import com.heytesla.app.BleProbeStatus
import com.heytesla.app.DiagnosticState
import com.heytesla.app.DiagnosticCommandResult
import com.heytesla.app.FieldLogFailureCode
import com.heytesla.app.FieldLogHealth
import com.heytesla.app.FieldLogStatus
import com.heytesla.app.SpeechCommandDecision
import com.heytesla.app.SpeechProbeState
import com.heytesla.app.SpeechProbeStatus
import com.heytesla.app.SpeechResponseStatus
import com.heytesla.app.SpeechTrialMode
import com.heytesla.app.SpeechTrialState
import com.heytesla.app.SpeechTrialStatus
import java.util.Locale

private enum class DiagnosticDestination(val title: String) {
    SPEECH("음성"),
    BLE("BLE 연결"),
    MICROPHONE("마이크"),
    APPROACH("접근"),
    EVENTS("이벤트"),
}

private val trialModes = listOf(
    SpeechTrialMode.TTS_PCM,
    SpeechTrialMode.SILENT_PCM,
    SpeechTrialMode.DIRECT_MIC,
    SpeechTrialMode.BUFFERED_PCM,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiagnosticsScreen(
    state: DiagnosticState,
    support: SpeechProbeState,
    trial: SpeechTrialState,
    ble: BleProbeState,
    actions: AppActions,
) {
    var destination by remember { mutableStateOf(DiagnosticDestination.SPEECH) }

    Column(modifier = Modifier.fillMaxSize()) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            DiagnosticDestination.entries.forEach { item ->
                val selected = destination == item
                val indicatorColor = MaterialTheme.colorScheme.secondary
                Text(
                    text = item.title,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = {
                                if (destination != item) {
                                    actions.leaveDiagnostics()
                                    destination = item
                                }
                            },
                        )
                        .drawBehind {
                            if (selected) {
                                val y = size.height - 2.dp.toPx()
                                drawLine(
                                    indicatorColor,
                                    Offset(12.dp.toPx(), y),
                                    Offset(size.width - 12.dp.toPx(), y),
                                    strokeWidth = 2.dp.toPx(),
                                )
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (ble.active || trial.active || state.sessionId != null || support.status == SpeechProbeStatus.RUNNING) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (trial.status == SpeechTrialStatus.CAPTURING) {
                    OutlinedButton(
                        onClick = actions.finishCapture,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text("입력 종료") }
                }
                OutlinedButton(
                    onClick = when {
                        ble.active -> actions.cancelBle
                        trial.active -> actions.cancelTrial
                        state.sessionId != null -> actions.stopSession
                        else -> actions.cancelSupport
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(when {
                        ble.active -> "BLE 연결 진단 취소"
                        trial.active -> "음성 진단 취소"
                        state.sessionId != null -> "진행 중인 세션 중지"
                        else -> "지원 조회 취소"
                    })
                }
            }
        }
        key(destination) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                when (destination) {
                    DiagnosticDestination.SPEECH -> SpeechDiagnostics(state, support, trial, actions)
                    DiagnosticDestination.BLE -> BleDiagnostics(state, support, ble, actions)
                    DiagnosticDestination.MICROPHONE -> MicrophoneDiagnostics(state, actions)
                    DiagnosticDestination.APPROACH -> ApproachDiagnostics(state, actions)
                    DiagnosticDestination.EVENTS -> EventDiagnostics(state, actions)
                }
            }
        }
    }
}

@Composable
private fun BleDiagnostics(
    state: DiagnosticState,
    support: SpeechProbeState,
    ble: BleProbeState,
    actions: AppActions,
) {
    var detailsExpanded by remember { mutableStateOf(false) }
    val blocked = when {
        ble.status == BleProbeStatus.CLEANUP_FAILED ->
            "로컬 GATT 정리 실패 · 예약 유지 · 앱 재시작 필요"
        support.reason == "DESTROY_FAILED" ->
            "지원 조회 정리 실패 · 앱 재시작 필요"
        state.bleDiagnosticActive && !ble.active ->
            "BLE 예약 해제 미확인 · 앱 재시작 필요"
        state.associations.size != 1 ->
            "설정에서 차량을 정확히 한 대 등록하세요."
        !state.bluetoothPermission -> "설정에서 Bluetooth 연결 권한을 허용하세요."
        !state.bluetooth -> "Bluetooth를 켜세요."
        state.enabled || state.observing || state.observationStartPending || state.observationServiceRunning ->
            "접근·관찰 진단을 먼저 중지하세요."
        state.speechDiagnosticActive -> "음성 진단을 먼저 취소하세요."
        state.sessionId != null -> "진행 중인 마이크·진단을 먼저 중지하세요."
        else -> null
    }
    AppSection("BLE 연결 진단") {
        DetailRow("현재 단계", bleStatusLabel(ble.status))
        if (ble.status == BleProbeStatus.CLEANUP_FAILED) {
            Text(
                "로컬 GATT 정리 실패 · 예약 유지 · 앱 재시작 필요",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ble.reason?.let { DetailRow("막힘·진단 코드", it) }
        Button(
            onClick = actions.startBle,
            enabled = !ble.active && !state.bleDiagnosticActive &&
                ble.status != BleProbeStatus.CLEANUP_FAILED && support.reason != "DESTROY_FAILED",
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("BLE 연결 진단 시작") }
        blocked?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (support.status == SpeechProbeStatus.RUNNING) {
            Text("시작하면 진행 중 지원 조회를 취소합니다.", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "등록된 차량만 사용합니다. 마이크 권한·새 스캔·VIN 재입력은 필요하지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "RX 구독 확인은 차량 인증이나 제어 권한이 아닙니다. 차량 명령은 전송하지 않습니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        AppDisclosure(
            expanded = detailsExpanded,
            expandLabel = "연결·정리 증거 보기",
            collapseLabel = "연결·정리 증거 접기",
            onToggle = { detailsExpanded = !detailsExpanded },
        ) {
            DetailRow("GATT 연결 콜백", bleEvidence(ble.connected))
            DetailRow("Tesla 서비스", bleEvidence(ble.serviceFound))
            DetailRow("TX characteristic", bleEvidence(ble.txFound))
            DetailRow("RX characteristic", bleEvidence(ble.rxFound))
            DetailRow("RX 구독 완료 콜백", bleEvidence(ble.subscriptionConfirmed))
            DetailRow("원격 구독 해제 (CCCD 읽기)", bleEvidence(ble.remoteUnsubscribeConfirmed))
            DetailRow("연결 끊김 콜백", bleEvidence(ble.disconnectConfirmed))
            DetailRow("로컬 close 호출", if (ble.localClosed) "호출 성공" else "성공 미확인")
            DetailRow("수신 횟수", ble.notificationCount.toString())
            DetailRow("경과", "${ble.elapsedMs} ms")
            DetailRow("BLE 예약", if (state.bleDiagnosticActive) "유지 중" else "없음")
            Text(
                "원격 해제는 CCCD 값 0을 읽어 확인합니다. 로컬 close 성공은 원격 해제·끊김 확인을 대신하지 않습니다. TX 쓰기·키 등록·인증은 수행하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun bleEvidence(confirmed: Boolean) = if (confirmed) "확인됨" else "미확인"

private fun bleStatusLabel(status: BleProbeStatus): String = when (status) {
    BleProbeStatus.IDLE -> "시험 전"
    BleProbeStatus.BLOCKED -> "시작 조건 미충족"
    BleProbeStatus.CONNECTING -> "GATT 연결 중"
    BleProbeStatus.DISCOVERING -> "서비스·TX/RX 확인 중"
    BleProbeStatus.SUBSCRIBING -> "RX 구독 완료 대기"
    BleProbeStatus.OBSERVING -> "RX 구독 확인 · 짧은 관찰 중"
    BleProbeStatus.CLEANING_UP -> "구독 해제·연결 정리 중"
    BleProbeStatus.COMPLETE -> "진단 종료 · 개별 증거는 상세 확인"
    BleProbeStatus.CANCELED -> "진단 취소"
    BleProbeStatus.TIMED_OUT -> "시간 초과"
    BleProbeStatus.FAILED -> "진단 실패"
    BleProbeStatus.CLEANUP_FAILED -> "로컬 정리 실패 · 재시작 필요"
}

private fun supportQueryBlockedReason(
    state: DiagnosticState,
    support: SpeechProbeState,
    trial: SpeechTrialState,
): String? = when {
    support.reason == "DESTROY_FAILED" -> "지원 조회 정리 실패 · 앱 재시작 필요"
    state.bleDiagnosticActive -> "BLE 연결 진단·정리 중에는 지원 조회할 수 없습니다."
    trial.active || state.speechDiagnosticActive -> "음성 진단을 먼저 취소하세요."
    state.sessionId != null -> "진행 중인 마이크·진단을 먼저 중지하세요."
    state.enabled || state.observing || state.observationStartPending || state.observationServiceRunning ->
        "접근·관찰 진단을 먼저 중지하세요."
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeechDiagnostics(
    state: DiagnosticState,
    support: SpeechProbeState,
    trial: SpeechTrialState,
    actions: AppActions,
) {
    var selectedMode by remember { mutableStateOf(SpeechTrialMode.TTS_PCM) }
    var supportDetailsExpanded by remember { mutableStateOf(false) }
    var conditionsExpanded by remember { mutableStateOf(false) }
    val queryBlocked = supportQueryBlockedReason(state, support, trial)
    val canQuery = support.status != SpeechProbeStatus.RUNNING && queryBlocked == null
    val canStartTrial = support.status == SpeechProbeStatus.COMPLETE &&
        support.metadata?.koKrInstalled == true &&
        state.microphonePermission &&
        !trial.active &&
        !state.speechDiagnosticActive &&
        !state.bleDiagnosticActive &&
        state.sessionId == null &&
        !state.enabled

    AppSection("온디바이스 한국어 지원") {
        DetailRow("현재 상태", support.status.label)
        TextButton(
            onClick = if (support.status == SpeechProbeStatus.RUNNING) actions.cancelSupport else actions.querySupport,
            enabled = support.status == SpeechProbeStatus.RUNNING || canQuery,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(if (support.status == SpeechProbeStatus.RUNNING) "지원 조회 취소" else "지원 확인")
        }
        queryBlocked?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        AppDisclosure(
            expanded = supportDetailsExpanded,
            expandLabel = "지원 상세 보기",
            collapseLabel = "지원 상세 접기",
            onToggle = { supportDetailsExpanded = !supportDetailsExpanded },
        ) {
            SupportDetails(support)
        }
    }

    AppSection("입력 선택") {
        FlowRow(
            modifier = Modifier.selectableGroup(),
            maxItemsInEachRow = 2,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            trialModes.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton },
                    onClick = { selectedMode = mode },
                    enabled = !trial.active,
                    label = { Text(modeChipLabel(mode)) },
                )
            }
        }
        Text("시험 문장: 헤이 테슬라 프렁크 열어줘", style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = { actions.startTrial(selectedMode) },
            enabled = canStartTrial,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("선택한 입력으로 진단 시작")
        }
        if (!canStartTrial && !trial.active) {
            Text(trialStartBlockedReason(state, support, trial), style = MaterialTheme.typography.bodySmall)
        }
        Text("선택만으로 시험을 시작하지 않으며 실제 차량 명령은 전송하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        AppDisclosure(
            expanded = conditionsExpanded,
            expandLabel = "입력 조건 보기",
            collapseLabel = "입력 조건 접기",
            onToggle = { conditionsExpanded = !conditionsExpanded },
        ) {
            Text(selectedModeDescription(selectedMode), style = MaterialTheme.typography.bodySmall)
        }
    }

    SpeechResult(trial, state.speechDiagnosticActive)
}

@Composable
private fun ColumnScope.SupportDetails(support: SpeechProbeState) {
    DetailRow("온디바이스 서비스", when (support.available) { true -> "있음"; false -> "없음"; null -> "미확인" })
    support.metadata?.let { metadata ->
        DetailRow("ko-KR 설치 완료", reported(metadata.koKrInstalled))
        DetailRow("ko-KR 다운로드 대기", reported(metadata.pending.exactKoKr))
        DetailRow("ko-KR 다운로드 가능·미설치", reported(metadata.downloadable.exactKoKr))
        DetailRow("ko-KR 온라인", reported(metadata.online.exactKoKr))
        DetailRow("다른 한국어 태그 설치/대기", "${reported(metadata.installed.otherKorean)} / ${reported(metadata.pending.otherKorean)}")
        DetailRow("다른 한국어 태그 다운로드/온라인", "${reported(metadata.downloadable.otherKorean)} / ${reported(metadata.online.otherKorean)}")
    }
    support.reason?.let { DetailRow("진단 코드", it) }
    Text(
        "대기·다운로드 가능·온라인은 설치 완료가 아닙니다. ko-KR 설치 보고도 실제 인식·PCM 입력·신뢰도 합격을 뜻하지 않습니다. 네트워크 차단 실측은 별도입니다.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SpeechResult(trial: SpeechTrialState, speechDiagnosticActive: Boolean) {
    var detailsExpanded by remember { mutableStateOf(false) }

    AppSection("결과 요약") {
        if (trial.status == SpeechTrialStatus.IDLE) {
            Text("시험 전입니다. 입력을 선택한 뒤 시작하세요.", style = MaterialTheme.typography.bodyMedium)
        } else {
            DetailRow("최종 결과 수신", if (trial.finalReceived) "예" else "아니오")
            DetailRow("시험 문장 일치", when (trial.phraseMatched) { true -> "예"; false -> "아니오"; null -> "미판정" })
            DetailRow("호출어·명령 판정", commandDecisionLabel(trial.decision))
            DetailRow("첫 결과 신뢰도", trial.confidence?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "미제공·유효하지 않음")
            DetailRow("진단 상태", trial.status.label)
            DetailRow("로컬 dry-run", dryRunResultLabel(trial.commandResult))
            DetailRow("음성 안내", speechResponseLabel(trial.responseStatus))
            Text("차량 전송 안 함", style = MaterialTheme.typography.bodyMedium)
            Text("신뢰도는 기록만 하며 이번 진단 판정에서 제외합니다. 명령 후보는 차량 실행 허가가 아닙니다.", style = MaterialTheme.typography.bodySmall)
            AppDisclosure(
                expanded = detailsExpanded,
                expandLabel = "결과 상세 보기",
                collapseLabel = "결과 상세 접기",
                onToggle = { detailsExpanded = !detailsExpanded },
            ) {
                DetailRow("입력 경로", trial.mode?.label ?: "미선택")
                DetailRow("진단 코드", trial.reason ?: "없음")
                DetailRow("PCM 입력 샘플", trial.samples.toString())
                DetailRow("PCM 전송 바이트", "${trial.pcmBytesWritten} · 소비 확인 아님")
                DetailRow("진단 경과", trial.elapsedMs?.let { "$it ms" } ?: "미완료")
                DetailRow("오디오 예약", speechDiagnosticActive.toString())
                DetailRow("입력 해제 확인", if (trial.inputReleased) "확인됨" else "미확인")
                DetailRow("한국어 오프라인 voice", if (trial.offlineVoiceSelected) "선택 확인됨" else "선택되지 않음")
                DetailRow("출력 해제 확인", when {
                    trial.outputReleased -> "해제됨·출력 없음"
                    trial.active -> "사용 중 · 정리 전"
                    else -> "해제 불명 · 재시작 필요"
                })
                DetailRow("오디오 포커스 반납", when {
                    trial.audioFocusReleased -> "반납됨·획득 없음"
                    trial.active -> "사용 중 · 정리 전"
                    else -> "반납 불명 · 재시작 필요"
                })
                trial.responseReason?.let { DetailRow("음성 안내 코드", it.name) }
                if (trial.responseCleanupFailed) DetailRow("음성 출력 정리", "정리 API 실패 기록 있음")
                Text(
                    "무음 ERROR7은 인식 결과 없음일 수 있지만 성공 상태로 바꾸지 않습니다. 지원 조회, 최종 결과, 문장 일치, 외부 PCM 소비, 네트워크 차단 실측은 서로 다른 증거입니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MicrophoneDiagnostics(state: DiagnosticState, actions: AppActions) {
    var detailsExpanded by remember { mutableStateOf(false) }

    AppSection("제한 마이크 시험") {
        Button(
            onClick = actions.startMicrophone,
            shape = MaterialTheme.shapes.medium,
            enabled = state.sessionId == null && !state.speechDiagnosticActive && !state.bleDiagnosticActive,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("수동 마이크 10초 시험 시작")
        }
        if (state.bleDiagnosticActive) {
            Text("BLE 연결 진단·정리가 끝나야 마이크 시험을 시작할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = actions.stopSession,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("진단 세션 즉시 종료")
        }
        DetailRow("세션", state.session)
        DetailRow("최근 종료 사유", state.stopReason)
        Text("자동 접근 합격과 별개이며 화면을 벗어나면 종료합니다. 샘플 0은 입력 성공이 아니고 silenced=true이면 즉시 중단합니다.", style = MaterialTheme.typography.bodySmall)
        AppDisclosure(
            expanded = detailsExpanded,
            expandLabel = "측정 상세 보기",
            collapseLabel = "측정 상세 접기",
            onToggle = { detailsExpanded = !detailsExpanded },
        ) {
            DetailRow("세션 ID", state.sessionId?.toString() ?: "없음")
            DetailRow("종료 예정 단조시간", state.deadline?.let { "$it ms" } ?: "없음")
            DetailRow("실제 읽은 샘플", state.samples.toString())
            DetailRow("RMS (0~1)", String.format(Locale.ROOT, "%.5f", state.rms))
            DetailRow("silenced", state.silenced?.toString() ?: "구성 콜백 미확인")
        }
    }
}

@Composable
private fun ApproachDiagnostics(state: DiagnosticState, actions: AppActions) {
    var detailsExpanded by remember { mutableStateOf(false) }

    AppSection("이번 프로세스의 접근 진단") {
        Button(
            onClick = { actions.setApproachEnabled(!(state.enabled || state.observationStartPending)) },
            shape = MaterialTheme.shapes.medium,
            enabled = (!state.speechDiagnosticActive && !state.bleDiagnosticActive) ||
                state.enabled || state.observationStartPending,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(if (state.enabled || state.observationStartPending) "접근 관찰 중지" else "접근 관찰 시작")
        }
        if (state.bleDiagnosticActive) {
            Text("BLE 연결 진단·정리가 끝나야 접근 관찰을 시작할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = actions.observeVehicle,
            shape = MaterialTheme.shapes.medium,
            enabled = state.enabled && state.observationServiceRunning,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("CDM 관찰 요청 / 재시도")
        }
        DetailRow("현재 프로세스 활성", state.enabled.toString())
        DetailRow("관찰 서비스", when {
            state.observationServiceRunning -> "실행 중 · 지속 알림 표시"
            state.observationStartPending -> "시작 요청 중"
            else -> "꺼짐"
        })
        DetailRow("시험 모드", if (state.automaticMicrophoneEnabled) "자동 마이크 허용 · 최대 90초" else "관찰 전용 · 마이크 시작 안 함")
        DetailRow("관찰 요청 수락", state.observing.toString())
        DetailRow("현재 감지", state.present.toString())
        DetailRow("출현 횟수 (프로세스 누적)", state.appearedCount.toString())
        DetailRow("이탈 횟수 (프로세스 누적)", state.disappearedCount.toString())
        DetailRow("최근 출현", state.lastAppeared ?: "없음")
        DetailRow("최근 이탈", state.lastDisappeared ?: "없음")
        DetailRow("최근 종료 사유", state.stopReason)
        Text(
            "화면을 벗어나도 관찰 서비스가 실행되는 동안 접근을 관찰합니다. 앱이나 지속 알림에서 종료할 수 있습니다. 관찰 시작은 차량 감지·마이크 시작이 아닙니다. 차량에서 떨어진 곳에서 시작하세요. 횟수는 중복 콜백을 포함한 프로세스 누적이며 실차 제어는 없습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        AppDisclosure(
            expanded = detailsExpanded,
            expandLabel = "수명·조건 상세 보기",
            collapseLabel = "수명·조건 상세 접기",
            onToggle = { detailsExpanded = !detailsExpanded },
        ) {
            DetailRow("등록 차량 수", state.associations.size.toString())
            DetailRow("저장된 사용자 선호", state.savedPreference.toString())
            Text(
                "관찰 서비스도 시스템 종료를 막지는 못합니다. 프로세스 재시작 시 실행·자동 마이크 동의는 OFF이며 과거 감지를 복원하지 않습니다. 자동 마이크에 별도 동의한 경우에만 새 BLE 출현 뒤 1.5초 debounce와 기본 비서·권한 검사를 거쳐 최대 90초 캡처합니다. 종료 뒤 실제 이탈과 30초 cooldown이 필요하며 BT 재연결로 연장하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    AppSection("자동 마이크 (별도 동의)") {
        OutlinedButton(
            onClick = { actions.setAutomaticMicrophone(!state.automaticMicrophoneEnabled) },
            shape = MaterialTheme.shapes.medium,
            enabled = !state.enabled && !state.observing && !state.observationStartPending &&
                !state.observationServiceRunning && state.sessionId == null &&
                !state.speechDiagnosticActive && !state.bleDiagnosticActive,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(if (state.automaticMicrophoneEnabled) "자동 마이크 끄기" else "자동 마이크 켜기 · 최대 90초 캡처 허용")
        }
        DetailRow("자동 마이크 동의", state.automaticMicrophoneEnabled.toString())
        DetailRow("기본 비서", if (state.assistant) "활성" else "비활성")
        DetailRow("마이크 권한", if (state.microphonePermission) "허용됨" else "필요")
        DetailRow("Bluetooth", if (state.bluetooth) "켜짐" else "꺼짐")
        Text(automaticMicrophoneState(state), style = MaterialTheme.typography.bodySmall)
        Text(
            "오늘 접근·이탈 시험은 기본값인 관찰 전용으로 진행하세요. 자동 마이크 동의는 진단 시작 전에만 바꿀 수 있고, 프로세스 재시작이나 진단 비활성화 시 꺼집니다. 켜도 실차 명령은 전송하지 않으며 기본 비서·마이크 권한·Bluetooth·관찰 수락이 모두 충족될 때만 새 출현 뒤 최대 90초 로컬 캡처를 시작합니다.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    AppSection("실차 시험 로그") {
        DetailRow("기록 상태", fieldLogStateLabel(state.fieldLog))
        if (state.fieldLog.failed) DetailRow("실패 원인", fieldLogFailureLabel(state.fieldLog))
        DetailRow("크기 / 상한", "${mebibytes(state.fieldLog.fileBytes)} / ${mebibytes(state.fieldLog.limitBytes)}")
        DetailRow("대기 기록", "${state.fieldLog.pending}건")
        Text(
            "이 시험의 사건은 앱 전용 저장소에 남습니다. 실패해도 안전 게이트와 RAM 진단은 계속 동작합니다. 경로와 회수 방법은 이벤트 탭에서 확인하세요.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun automaticMicrophoneState(state: DiagnosticState) = when {
    state.bleDiagnosticActive -> "BLE 연결 진단·정리가 끝나야 모드를 바꿀 수 있습니다."
    state.enabled || state.observing -> "모드 변경 전 접근 진단을 비활성화하세요."
    state.sessionId != null -> "진행 중인 캡처가 끝나야 모드를 바꿀 수 있습니다 · 세션 즉시 종료를 사용하세요."
    state.speechDiagnosticActive -> "음성 진단 오디오 예약이 남아 있어 모드를 바꿀 수 없습니다."
    !state.assistant -> "켜도 기본 비서가 비활성이면 캡처를 시작하지 않습니다."
    !state.microphonePermission -> "켜도 마이크 권한이 없으면 캡처를 시작하지 않습니다."
    !state.bluetooth -> "켜도 Bluetooth가 꺼져 있으면 캡처를 시작하지 않습니다."
    !state.observing -> "켜도 CDM 관찰 요청이 수락되지 않으면 캡처를 시작하지 않습니다."
    else -> "진단을 시작하기 전에만 모드를 변경할 수 있습니다."
}

@Composable
private fun EventDiagnostics(state: DiagnosticState, actions: AppActions) {
    AppSection("최근 비민감 이벤트") {
        OutlinedButton(
            onClick = actions.refresh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("상태 새로고침")
        }
        Text("RAM 최근 32건입니다. 프로세스가 끝나면 사라지며 영속 기록은 아래 실차 시험 로그 파일에 남습니다.", style = MaterialTheme.typography.bodySmall)
        if (state.events.isEmpty()) {
            Text("표시할 RAM 이벤트가 없습니다.", style = MaterialTheme.typography.bodyMedium)
        } else {
            state.events.forEach { event ->
                Text(event, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    AppSection("실차 시험 로그 파일") {
        val status = state.fieldLog
        DetailRow("기록 상태", fieldLogStateLabel(status))
        if (status.failed) DetailRow("실패 원인", fieldLogFailureLabel(status))
        DetailRow("저장 위치", status.path)
        DetailRow("크기 / 상한", "${mebibytes(status.fileBytes)} / ${mebibytes(status.limitBytes)}")
        DetailRow("이번 프로세스 확정 기록", "${status.written}건")
        DetailRow("대기 기록", "${status.pending}건")
        DetailRow("유실 기록", "${status.dropped}건")
        DetailRow("거부 코드", "${status.rejected}건")
        Text(
            "한 줄에 사건 하나인 JSONL입니다. 앱 프로세스가 끝나도 파일은 남고 새 실행은 기존 내용 뒤에 이어 씁니다. 자동 삭제·회전·덮어쓰기는 하지 않으며 상한에 도달하면 기록만 멈추고 위 상태가 이번 프로세스 동안 계속 표시됩니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "사용자가 USB 디버깅을 승인한 PC에서 회수하세요. 일반 USB 파일 전송에는 보이지 않습니다. 디버그 빌드 명령: adb -d exec-out run-as com.heytesla.app cat no_backup/field-diagnostics/events.jsonl. 마지막 줄이 잘렸으면 손상 행으로 구분하고 원본은 보존하세요.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "자동 삭제와 삭제 버튼은 없습니다. 정리가 필요하면 파일을 회수한 뒤 별도 승인하여 처리하세요. 앱 데이터 초기화는 등록도 지우므로 사용하지 마세요.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun fieldLogStateLabel(status: FieldLogStatus) = when (status.health) {
    FieldLogHealth.OK -> "기록 중"
    FieldLogHealth.LIMIT_REACHED -> "상한 도달 · 기록 중단"
    FieldLogHealth.QUEUE_OVERFLOW -> "대기 큐 초과 · 기록 중단"
    FieldLogHealth.WRITE_FAILED -> "쓰기 실패 · 기록 중단"
}

private fun fieldLogFailureLabel(status: FieldLogStatus) = when (status.failureCode) {
    FieldLogFailureCode.OPEN_FAILED -> "파일을 열 수 없음 · OPEN_FAILED"
    FieldLogFailureCode.WRITE_FAILED -> "파일 쓰기 실패 · WRITE_FAILED"
    FieldLogFailureCode.SYNC_FAILED -> "디스크 동기화 실패 · SYNC_FAILED"
    FieldLogFailureCode.LIMIT_REACHED -> "2 MiB 상한 도달 · LIMIT_REACHED"
    FieldLogFailureCode.QUEUE_OVERFLOW -> "대기 큐 초과 · QUEUE_OVERFLOW"
    else -> "알 수 없는 실패"
}

private fun mebibytes(bytes: Long) = String.format(Locale.US, "%.2f MiB", bytes / 1048576.0)

private fun reported(value: Boolean) = if (value) "보고됨" else "보고되지 않음"

/** 문장 판정을 화면 문구로만 옮긴다. 어느 값도 실제 전송·합격으로 표시하지 않는다. */
private fun commandDecisionLabel(decision: SpeechCommandDecision?) = when (decision) {
    null -> "확인 안 됨 · 판정 없음"
    SpeechCommandDecision.WAKE_MISSING -> "호출어 없음"
    SpeechCommandDecision.COMMAND_MISSING -> "호출어 확인 · 명령 없음"
    SpeechCommandDecision.COMMAND_UNSUPPORTED -> "호출어 확인 · 허용 외"
    SpeechCommandDecision.COMMAND_CANCELED -> "호출어 확인 · 취소·부정 거절"
    SpeechCommandDecision.FRUNK_OPEN_CANDIDATE -> "호출어 확인 · 명령 후보 (전송 안 함)"
}

private fun dryRunResultLabel(result: DiagnosticCommandResult) = when (result) {
    DiagnosticCommandResult.NOT_ATTEMPTED -> "처리 전"
    DiagnosticCommandResult.REJECTED -> "거절 · 실행 안 함"
    DiagnosticCommandResult.CANCELED -> "취소 · 실행 안 함"
    DiagnosticCommandResult.EXPIRED -> "만료 · 실행 안 함"
    DiagnosticCommandResult.PROCESSED -> "로컬 처리됨 · 차량 승인 아님"
    DiagnosticCommandResult.UNKNOWN -> "결과 불명 · 재시도 안 함"
}

private fun speechResponseLabel(status: SpeechResponseStatus) = when (status) {
    SpeechResponseStatus.NOT_STARTED -> "시작 전"
    SpeechResponseStatus.INITIALIZING -> "한국어 오프라인 음성 준비 중"
    SpeechResponseStatus.SPEAKING -> "음성 안내 중"
    SpeechResponseStatus.COMPLETE -> "음성 안내 완료"
    SpeechResponseStatus.FAILED -> "음성 안내 실패"
    SpeechResponseStatus.CANCELED -> "음성 안내 취소됨"
    SpeechResponseStatus.EXPIRED -> "만료로 음성 안내 중단"
    SpeechResponseStatus.SKIPPED -> "입력 해제 미확인 · 안내 안 함"
}

private fun modeChipLabel(mode: SpeechTrialMode) = when (mode) {
    SpeechTrialMode.TTS_PCM -> "합성 음성"
    SpeechTrialMode.SILENT_PCM -> "무음 대조"
    SpeechTrialMode.DIRECT_MIC -> "직접 마이크"
    SpeechTrialMode.BUFFERED_PCM -> "녹음 후 PCM"
}

private fun selectedModeDescription(mode: SpeechTrialMode) = when (mode) {
    SpeechTrialMode.TTS_PCM -> "발화 없이 합성 파일을 인식하고 입력 해제 후 결과를 음성 안내합니다. 일부 인식기는 자체 마이크를 열 수 있어요."
    SpeechTrialMode.SILENT_PCM -> "같은 길이의 무음 입력입니다. 후보가 없으면 거절하고 입력 해제 후 음성 안내합니다. 일부 인식기는 자체 마이크를 열 수 있어요."
    SpeechTrialMode.DIRECT_MIC -> "시스템 마이크로 최대 10초 인식합니다. 캡처 중 시험 문장을 말해 주세요."
    SpeechTrialMode.BUFFERED_PCM -> "최대 10초 녹음한 뒤 마이크를 해제하고 인식합니다. 캡처 중 말하고 인계 중에는 조용히 기다리세요. 일부 인식기는 자체 마이크를 열 수 있어요."
}

private fun trialStartBlockedReason(
    state: DiagnosticState,
    support: SpeechProbeState,
    trial: SpeechTrialState,
) = when {
    trial.reason?.contains("RESTART_REQUIRED") == true -> "자원 정리 실패가 남아 앱 재시작이 필요합니다"
    support.reason == "DESTROY_FAILED" -> "지원 조회 정리 실패 · 앱 재시작 필요"
    state.bleDiagnosticActive -> "BLE 연결 진단·정리가 끝나야 음성 시험을 시작할 수 있습니다"
    trial.active -> "진행 중인 음성 시험을 먼저 취소하거나 종료하세요"
    state.speechDiagnosticActive -> "음성 진단 오디오 예약이 남아 있습니다 · 다른 진단의 종료 여부를 확인하세요"
    state.sessionId != null -> "기존 마이크 세션이 점유 중입니다 · 즉시 종료 후 다시 시도하세요"
    state.enabled -> "접근 진단을 먼저 비활성화하세요"
    support.status != SpeechProbeStatus.COMPLETE -> "먼저 지원 조회를 완료하세요"
    support.metadata?.koKrInstalled != true -> "정확한 ko-KR 설치 보고가 필요합니다"
    !state.microphonePermission -> "마이크 권한이 필요합니다"
    else -> "현재 시작할 수 없습니다"
}
