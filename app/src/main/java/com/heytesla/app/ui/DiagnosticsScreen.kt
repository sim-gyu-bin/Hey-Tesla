package com.heytesla.app.ui

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import com.heytesla.app.DiagnosticState
import com.heytesla.app.diagnostics.DiagnosticCommandResult
import com.heytesla.app.logging.FieldLogFailureCode
import com.heytesla.app.logging.FieldLogHealth
import com.heytesla.app.logging.FieldLogStatus
import com.heytesla.app.vehicle.frunk.FrunkVoiceStep
import com.heytesla.app.vehicle.frunk.FrunkVoicePolicy
import com.heytesla.app.vehicle.frunk.frunkVoiceReasonLabel
import com.heytesla.app.vehicle.frunk.progressStep
import com.heytesla.app.speech.SpeechCommandDecision
import com.heytesla.app.speech.SpeechCommandPhrases
import com.heytesla.app.speech.SpeechProbeState
import com.heytesla.app.speech.SpeechProbeStatus
import com.heytesla.app.speech.SpeechResponseStatus
import com.heytesla.app.speech.SpeechTrialMode
import com.heytesla.app.speech.SpeechTrialState
import com.heytesla.app.speech.SpeechTrialStatus
import com.heytesla.app.speech.ShadowTranscript
import com.heytesla.app.speech.SttTranscript
import com.heytesla.app.vehicle.uwb.UwbProbeState
import com.heytesla.app.vehicle.uwb.UwbProbeStatus
import com.heytesla.app.vehicle.protocol.TeslaBlePhase
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.heytesla.app.vehicle.protocol.TeslaDriveState
import com.heytesla.app.vehicle.protocol.TeslaGear
import com.heytesla.app.vehicle.key.TeslaKeyOutcome
import com.heytesla.app.vehicle.key.TeslaKeyProbeState
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.uwb.TeslaUwbProbeState
import com.heytesla.app.vehicle.uwb.TeslaUwbStage
import com.heytesla.app.vehicle.key.VehicleVinStatus
import java.util.Locale

private enum class DiagnosticDestination(val title: String) {
    SPEECH("음성"),
    VEHICLE_KEY("차량 키"),
    UWB("UWB"),
    MICROPHONE("마이크"),
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
    uwb: UwbProbeState,
    teslaKey: TeslaKeyProbeState,
    actions: AppActions,
    uwbRanging: TeslaUwbProbeState = TeslaUwbProbeState(),
    nowElapsedMs: Long = 0L,
) {
    var destination by remember { mutableStateOf(DiagnosticDestination.SPEECH) }

    Column(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
    ) {
        key(destination) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                VehicleVinSection(state.vehicleVin, actions)
            }
        }
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
        if (uwb.status == UwbProbeStatus.CLEANUP_FAILED) {
            Text(
                "UWB 등록 해제 불명 · 새 진단 차단 · 앱 프로세스 재시작 필요",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        if (uwbRanging.stage == TeslaUwbStage.CLEANUP_FAILED) {
            Text(
                "차량 UWB 종료 불명 · 새 작업 차단 · 앱 프로세스 재시작 필요",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        if (uwbRanging.active) {
            OutlinedButton(
                onClick = if (uwbRanging.serviceOwned) {
                    { actions.setVehicleServiceEnabled(false) }
                } else {
                    { actions.cancelUwbRanging?.invoke() }
                },
                enabled = uwbRanging.serviceOwned || actions.cancelUwbRanging != null,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(if (uwbRanging.serviceOwned) "서비스 끄기" else "수동 UWB 거리 진단 정지") }
            if (!uwbRanging.serviceOwned && actions.cancelUwbRanging == null) {
                Text(
                    "UWB 거리 진단 정지 기능 제공 없음",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
        if (state.teslaKeyCleanupFailed) {
            Text(
                "차량 연결 정리 불명 · 예약 유지 · 앱 프로세스 재시작 필요",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        if (!state.frunkVoice.active && ((teslaKey.active && !uwbRanging.serviceOwned) || trial.active || state.sessionId != null || support.status == SpeechProbeStatus.RUNNING || uwb.status == UwbProbeStatus.RUNNING)) {
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
                        teslaKey.active && !uwbRanging.serviceOwned -> actions.cancelTeslaKey
                        uwb.status == UwbProbeStatus.RUNNING -> actions.cancelUwbSupport
                        trial.active -> actions.cancelTrial
                        state.sessionId != null -> actions.stopSession
                        else -> actions.cancelSupport
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(when {
                        teslaKey.active && !uwbRanging.serviceOwned -> "차량 키 진단 취소"
                        uwb.status == UwbProbeStatus.RUNNING -> "UWB 지원 조회 취소"
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
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                when (destination) {
                    DiagnosticDestination.SPEECH -> SpeechDiagnostics(state, support, trial, actions)
                    DiagnosticDestination.UWB -> UwbDiagnostics(state, support, trial, uwb, actions, uwbRanging, nowElapsedMs)
                    DiagnosticDestination.VEHICLE_KEY -> TeslaKeyDiagnostics(state, support, teslaKey, actions, uwbRanging.serviceOwned)
                    DiagnosticDestination.MICROPHONE -> MicrophoneDiagnostics(state, actions)
                    DiagnosticDestination.EVENTS -> EventDiagnostics(state, actions)
                }
            }
        }
    }
}

/**
 * 차량 키 흐름의 진입 목적. 상태·전송·취소·정리 로직은 공유하고 화면 문구와 노출 행동만 달라진다.
 * REGISTRATION은 설정의 차량에 앱 키 등록, DIAGNOSTIC은 개발자 진단의 차량 키 탭이다.
 */
private enum class TeslaKeyFlow { REGISTRATION, DIAGNOSTIC }

/** 설정 → 차량에 앱 키 등록. 개발자 진단과 같은 상태·취소·정리 경로를 쓴다. */
@Composable
internal fun TeslaKeyRegistrationScreen(
    state: DiagnosticState,
    support: SpeechProbeState,
    teslaKey: TeslaKeyProbeState,
    actions: AppActions,
    serviceOwned: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        VehicleVinSection(state.vehicleVin, actions)
        TeslaKeyWorkflow(state, support, teslaKey, actions, TeslaKeyFlow.REGISTRATION, serviceOwned)
    }
}

@Composable
private fun TeslaKeyDiagnostics(
    state: DiagnosticState,
    support: SpeechProbeState,
    probe: TeslaKeyProbeState,
    actions: AppActions,
    serviceOwned: Boolean,
) {
    TeslaKeyWorkflow(state, support, probe, actions, TeslaKeyFlow.DIAGNOSTIC, serviceOwned)
}

/** 두 진입이 공유하는 구현. 순서는 대상·상태 → 행동 → 상세이며 시작 버튼 위에는 큰 상태와 승인 안내만 둔다. */
@Composable
private fun TeslaKeyWorkflow(
    state: DiagnosticState,
    support: SpeechProbeState,
    probe: TeslaKeyProbeState,
    actions: AppActions,
    flow: TeslaKeyFlow,
    serviceOwned: Boolean,
) {
    if (serviceOwned && (state.bleFieldBusy() || state.teslaKeyDiagnosticActive)) {
        AppSection("서비스 UWB 작업") {
            Text("차량 감지·UWB 서비스가 실행 중입니다. 키 등록·수동 상태 조회는 서비스를 끈 뒤 직접 시작하세요.")
            Text("서비스의 거리·정리 상태는 UWB 상세에서 확인할 수 있어요.")
            OutlinedButton(
                onClick = { actions.setVehicleServiceEnabled(false) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("서비스 끄기") }
        }
        return
    }
    val registration = flow == TeslaKeyFlow.REGISTRATION
    var registrationConsent by remember(state.vehicleVin) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) {
                registrationConsent = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            registrationConsent = false
        }
    }
    val blocked = when {
        state.teslaKeyCleanupFailed || probe.stage == TeslaKeyStage.CLEANUP_FAILED -> teslaKeyBlockedLabel("LOCAL_CLEANUP_FAILED_RESTART_REQUIRED")
        support.reason == "DESTROY_FAILED" || support.reason == "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" -> "진단 자원 정리 실패 · 앱 프로세스 재시작 필요"
        // 신규 실행의 admission 검사다. 진행 중인 자기 예약은 시작 오류가 아니다.
        probe.active || state.teslaKeyDiagnosticActive -> null
        else -> actions.teslaKeyBlockedReason()?.let(::teslaKeyBlockedLabel)
    }
    val canStart = blocked == null && !probe.active && !state.teslaKeyDiagnosticActive &&
        state.vehicleVin.status == VehicleVinStatus.READY
    fun submit(register: Boolean, query: TeslaBleQuery = TeslaBleQuery.BODY_STATUS) {
        if (!canStart || (register && !registrationConsent)) return
        registrationConsent = false
        actions.startTeslaKey(register, query)
    }

    AppSection(if (registration) "차량에 앱 키 등록" else "수동 차량 키 진단") {
        if (registration && probe.stage == TeslaKeyStage.IDLE) {
            Text("차량에 Hey Tesla 앱 키를 추가합니다. 시작한 뒤 차량 화면의 키 추가 안내에서 키카드로 직접 승인하세요.")
        }
        TeslaKeyResult(probe, state.teslaKeyCleanupFailed, flow)
        if (probe.registrationReported && probe.outcome != TeslaKeyOutcome.VERIFIED_STATUS) {
            Text(
                "등록 보고는 받았지만 앱 키 확인(세션 인증·암호화 상태 조회)은 완료되지 않았습니다. 영구 등록을 뜻하지 않습니다.",
                color = AppWarningColor,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (probe.registrationUncertain) {
            Text("키 추가 요청이 차량에 도착했을 수 있습니다. 취소·오류가 등록 철회를 보장하지 않으므로 차량 키 목록을 직접 확인하세요.", color = MaterialTheme.colorScheme.error)
        }
        if (probe.outcome == TeslaKeyOutcome.VERIFIED_STATUS) {
            if (probe.query == TeslaBleQuery.DRIVE_STATE) {
                TeslaDriveObservation(probe.status?.driveState)
            } else {
                DetailRow("잠금 상태", teslaLockStateLabel(probe.status?.lockState))
                DetailRow("프렁크 상태", teslaFrontTrunkStateLabel(probe.status?.frontTrunkState))
                Text("검증된 응답 시점의 차량 보고입니다. 이후 상태 변화나 물리 완료를 보장하지 않습니다.")
            }
        }
    }

    AppSection("등록된 차량 · 명시적 실행") {
        Text(vehicleVinConsumerLabel(state.vehicleVin))
        Text("화면 이탈·HOME·잠금·차량 키 진단 이동은 진행을 취소하며 자동 재개하지 않습니다.")
        if (!state.bluetoothPermission) {
            OutlinedButton(
                onClick = { actions.requestPermission(Manifest.permission.BLUETOOTH_CONNECT) },
                enabled = !state.teslaKeyDiagnosticActive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Bluetooth 연결 권한 요청") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(
                checked = registrationConsent,
                onCheckedChange = { registrationConsent = it },
                enabled = canStart,
                modifier = Modifier.semantics { contentDescription = "우리 앱 키 추가와 차량 키카드 직접 승인에 동의" },
            )
            Text(
                if (registration) {
                    "우리 앱 키를 내 차량에 추가하는 데 동의합니다. 차량 화면에서 키카드로 직접 승인하며 기존 키를 제거하거나 변경하지 않습니다."
                } else {
                    "우리 앱의 키를 새로 추가하는 시험에 동의합니다. 차량에서 키카드로 직접 승인하며 기존 키를 제거하거나 변경하지 않습니다."
                },
            )
        }
        Button(
            onClick = { submit(true) },
            enabled = canStart && registrationConsent,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(if (registration) "차량에 앱 키 등록 시작" else "키 추가 요청 · 카드 승인 후 상태 1회 조회")
        }
        if (!registration) {
            OutlinedButton(
                onClick = { submit(false) },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("우리 앱 키로 인증·상태 1회 조회") }
            OutlinedButton(
                onClick = { submit(false, TeslaBleQuery.DRIVE_STATE) },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("주차 기어 상태 1회 조회") }
            Text("기존 앱 키로 기어만 한 번 읽습니다. 지난 관측은 차량 감지나 프렁크 실행 허가가 아닙니다.", color = AppWarningColor)
        }
        TeslaKeyResult(probe, state.teslaKeyCleanupFailed, flow)
        if (probe.active) {
            OutlinedButton(onClick = actions.cancelTeslaKey, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("차량 키 진단 취소")
            }
        }
        blocked?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { error(message) })
        }
        Text(
            if (registration) {
                "등록 보고 뒤에만 인증과 읽기 전용 상태를 한 번 확인합니다. 재시도·자동 재등록·차량 조작은 하지 않습니다."
            } else {
                "같은 앱 키를 재사용합니다. 키 추가는 등록 완료 보고 뒤에만 인증·잠금/프렁크 상태를 조회하고, 별도 조회는 선택한 상태만 한 번 읽습니다. 재시도·차량 조작은 하지 않습니다."
            },
        )
    }
    AppDisclosure(
        expanded = expanded,
        expandLabel = "비민감 진단 상세 보기",
        collapseLabel = "진단 상세 접기",
        onToggle = { expanded = !expanded },
    ) {
        DetailRow("선택한 작업", when {
            probe.stage == TeslaKeyStage.IDLE -> "시작 전"
            probe.registrationRequested -> "키 추가 · 카드 승인 후 인증·상태 한 회"
            probe.query == TeslaBleQuery.DRIVE_STATE -> "별도 인증·주차 기어 상태 한 회"
            else -> "별도 인증·잠금/프렁크 상태 한 회"
        })
        DetailRow("등록 보고", if (probe.registrationReported) "차량의 등록 완료 응답 수신 · 영구 등록 상태와 별개" else "등록 완료 응답 미수신")
        DetailRow("인증·상태 조회", when (probe.outcome) {
            TeslaKeyOutcome.VERIFIED_STATUS -> "인증 검증 후 암호화된 읽기 전용 상태 수신"
            TeslaKeyOutcome.UNKNOWN -> "전송 후 결과 불명 · 자동 재전송 안 함"
            TeslaKeyOutcome.PENDING -> "응답 대기 · 아직 검증 완료 아님"
            TeslaKeyOutcome.CANCELED -> "전송 전에 취소"
            TeslaKeyOutcome.FAILED -> "검증 완료 안 됨"
            TeslaKeyOutcome.NOT_SENT -> "전송 전"
        })
        DetailRow("프로토콜 단계", when (probe.phase) {
            TeslaBlePhase.WAITING_FOR_CARD -> "차량 카드 승인 대기"
            TeslaBlePhase.HANDSHAKING -> "신선한 세션 인증 검증 중"
            TeslaBlePhase.READING_STATUS -> if (probe.query == TeslaBleQuery.DRIVE_STATE) {
                "암호화된 주차 기어 응답 검증 중"
            } else {
                "암호화된 잠금/프렁크 GET_STATUS 응답 검증 중"
            }
            null -> "프로토콜 시작 전"
        })
        DetailRow("우리 앱 키 준비", if (probe.credentialReady) "이번 실행에서 키 준비됨 · 등록·인증 성공과 별개" else "이번 실행에서 미확인")
        DetailRow("RX 구독", if (probe.subscriptionConfirmed) "구독 확인 · 차량 인증과 별개" else "미확인")
        DetailRow("전송 시도", if (probe.transmissionAttempted) "있음 · 요청 승인/물리 완료와 별개" else "없음")
        DetailRow("로컬 close", if (probe.localClosed) "로컬 정리 확인 · 원격 해제와 별개" else "미확인")
        val drive = probe.status?.driveState
        if (probe.query == TeslaBleQuery.DRIVE_STATE && drive != null) {
            DetailRow("기어 원본 시각 · epoch ms", drive.sourceTimestampEpochMillis?.toString() ?: "응답에 없음")
            DetailRow("기어 수신 시각 · elapsed ms", drive.receivedAtElapsedRealtime.toString())
        }
        Text("기어 조회는 지난 관측만 표시하며 현재 P·차량 감지·제어 준비 여부를 판정하지 않습니다. 프렁크 열기·잠금·주행·UWB 명령은 전송하지 않습니다.")
        Text("전송·응답 대기는 전체 최대 180초입니다. 키 준비·정리의 해제 불명은 예약을 유지합니다. VIN·주소·키·응답 원문은 상세나 로그에 표시하지 않습니다.")
    }
}

/** 수신한 기어를 표시할 뿐, 이 일회성 조회로 현재 주차나 제어 권한을 판정하지 않는다. */
@Composable
private fun TeslaDriveObservation(drive: TeslaDriveState?) {
    val gear = drive?.gear ?: TeslaGear.UNKNOWN
    DetailRow("관측 기어", when (gear) {
        TeslaGear.P -> "P · 지난 조회에서 관측"
        TeslaGear.R, TeslaGear.N, TeslaGear.D -> "${gear.name} · P 아님"
        TeslaGear.UNKNOWN -> "UNKNOWN · 확인 안 됨"
    })
    val hasSourceTime = drive?.sourceTimestampEpochMillis != null
    Text(
        if (hasSourceTime) "차량 원본 시각 있음 · 현재 상태의 신선도를 보장하지 않습니다."
        else "차량 원본 시각 없음 · 현재 P 여부와 신선도 확인 안 됨",
        color = if (hasSourceTime && gear == TeslaGear.P) MaterialTheme.colorScheme.onSurfaceVariant else AppWarningColor,
    )
    Text("지난 조회의 기어 관측입니다. 차량 감지나 프렁크 실행 허가가 아니며 제어 허가에 재사용하지 않습니다.", color = AppWarningColor)
}

/** 공식 VCSEC 상태 enum을 표현한다. null은 enum 0(잠금 해제/닫힘)과 구분한다. */
private fun teslaLockStateLabel(value: Int?): String = when (value) {
    null -> "응답에 없음 · 확인 안 됨"
    0 -> "잠금 해제 보고"
    1 -> "잠금 보고"
    2 -> "내부 잠금 보고"
    3 -> "선택적 잠금 해제 보고"
    else -> "알 수 없는 차량 상태"
}

private fun teslaFrontTrunkStateLabel(value: Int?): String = when (value) {
    null -> "응답에 없음 · 확인 안 됨"
    0 -> "닫힘 보고"
    1 -> "열림 보고"
    2 -> "부분 열림 보고"
    3 -> "차량이 상태 불명 보고"
    4 -> "래치 해제 실패 보고"
    5 -> "여는 중 보고"
    6 -> "닫는 중 보고"
    else -> "알 수 없는 차량 상태"
}

private fun teslaKeyBlockedLabel(reason: String): String = when (reason) {
    "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" -> "로컬 정리 불명 · 예약 유지 · 앱 프로세스 재시작 필요"
    "VISIBLE_UI_REQUIRED" -> "표시 중인 화면에서만 시작할 수 있습니다."
    "UNLOCKED_UI_REQUIRED" -> "휴대전화 잠금을 직접 해제하세요."
    "BLUETOOTH_PERMISSION_REQUIRED" -> "Bluetooth 연결 권한이 필요합니다."
    "BLUETOOTH_OFF" -> "Bluetooth를 직접 켜세요."
    "SINGLE_ASSOCIATION_REQUIRED" -> "설정에서 차량 한 대의 시스템 등록이 필요합니다."
    else -> "다른 관찰·오디오·BLE·UWB 진단을 먼저 종료하세요."
}

/**
 * 차량이 보고한 사유 코드를 화면 문구로만 옮긴다. 상태·전송·판정은 바뀌지 않는다.
 * 매핑되지 않은 코드는 감추지 않고 그대로 보여 준다.
 */
private fun teslaKeyReasonText(reason: String): String = when (reason) {
    "USER_STOP" -> "사용자가 중단했습니다."
    "DIAGNOSTICS_HIDDEN" -> "화면을 벗어나 중단했습니다."
    "DEADLINE_EXPIRED" -> "제한 시간이 지나 중단했습니다."
    "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED",
    "VISIBLE_UI_REQUIRED",
    "UNLOCKED_UI_REQUIRED",
    "BLUETOOTH_PERMISSION_REQUIRED",
    "BLUETOOTH_OFF",
    "SINGLE_ASSOCIATION_REQUIRED" -> teslaKeyBlockedLabel(reason)
    else -> reason
}

@Composable
private fun TeslaKeyResult(probe: TeslaKeyProbeState, cleanupFailed: Boolean, flow: TeslaKeyFlow) {
    val registration = flow == TeslaKeyFlow.REGISTRATION
    val driveQuery = probe.query == TeslaBleQuery.DRIVE_STATE
    val drive = probe.status?.driveState
    val cleanupError = cleanupFailed || probe.stage == TeslaKeyStage.CLEANUP_FAILED
    val verified = probe.stage == TeslaKeyStage.COMPLETE && probe.outcome == TeslaKeyOutcome.VERIFIED_STATUS
    val canceled = probe.stage == TeslaKeyStage.CANCELED || probe.outcome == TeslaKeyOutcome.CANCELED
    val waitingForCard = probe.active && probe.stage == TeslaKeyStage.WAITING_FOR_CARD
    val checkingKey = probe.active &&
        (probe.stage == TeslaKeyStage.HANDSHAKING || probe.stage == TeslaKeyStage.READING_STATUS)
    val title = when {
        cleanupError -> "정리 실패"
        waitingForCard -> "키카드 승인 대기"
        checkingKey -> if (driveQuery) "주차 기어 조회 중" else "앱 키 확인 중"
        probe.stage == TeslaKeyStage.FAILED -> when {
            registration && probe.registrationReported -> "등록 보고 수신 · 앱 키 확인 실패"
            registration -> "등록 확인 실패"
            else -> "조회 실패"
        }
        probe.stage == TeslaKeyStage.TIMED_OUT -> when {
            registration && probe.registrationReported -> "등록 보고 수신 · 앱 키 확인 시간 초과"
            registration -> "등록 시간 초과"
            else -> "조회 시간 초과"
        }
        probe.stage == TeslaKeyStage.BLOCKED -> if (registration) "등록 시작 불가" else "조회 시작 불가"
        probe.stage == TeslaKeyStage.CLEANING_UP -> "자원 정리 중"
        probe.active -> when {
            registration -> "등록 준비 중"
            driveQuery -> "주차 기어 조회 진행 중"
            else -> "잠금·프렁크 조회 진행 중"
        }
        canceled -> if (registration) "등록 취소" else "조회 취소"
        verified -> when {
            driveQuery -> when (drive?.gear ?: TeslaGear.UNKNOWN) {
                TeslaGear.P -> if (drive?.sourceTimestampEpochMillis != null) "P 관측 · 지난 조회" else "P 관측 · 현재 P 확인 안 됨"
                TeslaGear.R, TeslaGear.N, TeslaGear.D -> "${drive?.gear?.name} 관측 · P 아님"
                TeslaGear.UNKNOWN -> "주차 기어 확인 안 됨"
            }
            registration && probe.registrationReported -> "등록 보고 수신 · 상태 확인됨"
            registration -> "앱 키 확인됨"
            else -> "조회 성공"
        }
        probe.stage == TeslaKeyStage.IDLE -> if (registration) "등록 전" else "조회 전"
        else -> if (registration) "등록 종료 · 결과 확인 안 됨" else "조회 종료 · 결과 확인 안 됨"
    }
    val color = when {
        cleanupError -> MaterialTheme.colorScheme.error
        waitingForCard -> MaterialTheme.colorScheme.primary
        probe.active -> MaterialTheme.colorScheme.secondary
        canceled -> AppWarningColor
        verified && driveQuery -> if (drive?.gear == TeslaGear.P && drive.sourceTimestampEpochMillis != null) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            AppWarningColor
        }
        verified -> AppSuccessColor
        probe.stage == TeslaKeyStage.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        color = color,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Text(
        when {
            cleanupError -> "전송은 끝났지만 자원 정리를 확인하지 못했습니다. 앱 프로세스 재시작이 필요합니다."
            waitingForCard -> "차량 화면의 키 추가 안내를 확인하고 NFC 키카드로 직접 승인하세요. 앱은 승인을 대신하지 않습니다."
            checkingKey -> when {
                driveQuery -> "앱 키로 인증한 뒤 주차 기어만 한 번 조회하고 있습니다. 차량 감지·프렁크 제어는 하지 않습니다."
                probe.registrationRequested -> "카드 승인 뒤 앱 키로 잠금·프렁크 상태를 확인하고 있습니다."
                else -> "앱 키로 잠금·프렁크 상태를 확인하고 있습니다."
            }
            probe.active -> teslaKeyStageLabel(probe.stage)
            verified -> when {
                driveQuery -> "인증을 검증하고 암호화된 기어 응답을 받았습니다. 지난 관측이며 현재 P 확인·차량 감지·프렁크 실행 허가가 아닙니다."
                registration && probe.registrationReported ->
                    "차량이 등록 완료 응답을 보냈고, 인증을 검증해 암호화된 차량 상태를 받았습니다. 영구 등록 유지나 이후 차량 동작을 보장하지 않습니다."
                registration -> "인증을 검증하고 암호화된 차량 상태를 받았습니다. 차량의 등록 완료 응답은 확인되지 않았습니다."
                else -> "인증을 검증하고 암호화된 차량 상태를 받았습니다. 조회가 끝났습니다."
            }
            probe.stage == TeslaKeyStage.IDLE ->
                if (registration) "아직 등록을 시작하지 않았습니다." else "아직 조회를 시작하지 않았습니다."
            registration && probe.registrationReported ->
                "차량의 등록 완료 응답은 받았지만 앱 키 확인은 끝나지 않았습니다. 이번 실행은 종료되어 더 기다릴 필요 없습니다."
            probe.outcome == TeslaKeyOutcome.UNKNOWN ->
                if (registration) {
                    "등록이 끝났습니다. 전송 후 결과는 확인되지 않았으며 더 기다려도 자동 재시도하지 않습니다."
                } else {
                    "조회가 끝났습니다. 전송 후 결과는 확인되지 않았으며 더 기다려도 자동 재시도하지 않습니다."
                }
            registration -> "등록이 끝났습니다. 더 기다릴 필요 없습니다. 차량의 등록 승인과 앱 키 확인은 확인되지 않았습니다."
            else -> "조회가 끝났습니다. 더 기다릴 필요 없습니다. 인증·상태 조회 성공은 확인되지 않았습니다."
        },
        color = color,
    )
    probe.reason?.let { Text(teslaKeyReasonText(it), color = color) }
}

private fun teslaKeyStageLabel(stage: TeslaKeyStage): String = when (stage) {
    TeslaKeyStage.IDLE -> "시험 전"
    TeslaKeyStage.PREPARING_KEY -> "우리 앱 키 준비 중"
    TeslaKeyStage.CONNECTING -> "차량 직접 연결 중"
    TeslaKeyStage.DISCOVERING -> "Tesla 서비스·TX/RX·CCCD 확인 중"
    TeslaKeyStage.NEGOTIATING_MTU -> "전송 크기 협상 중"
    TeslaKeyStage.SUBSCRIBING -> "RX 구독 완료 대기"
    TeslaKeyStage.WAITING_FOR_CARD -> "차량 키카드 승인 대기"
    TeslaKeyStage.HANDSHAKING -> "차량 세션 인증 검증 중"
    TeslaKeyStage.READING_STATUS -> "암호화된 읽기 전용 상태 대기"
    TeslaKeyStage.CLEANING_UP -> "전송 봉인 · 자원 정리 중"
    TeslaKeyStage.COMPLETE -> "검증된 상태 조회 종료"
    TeslaKeyStage.BLOCKED -> "시작 조건 미충족"
    TeslaKeyStage.CANCELED -> "취소됨 · 아래 전송 결과 확인"
    TeslaKeyStage.TIMED_OUT -> "시간 초과 · 아래 전송 결과 확인"
    TeslaKeyStage.FAILED -> "오류 · 아래 전송 결과 확인"
    TeslaKeyStage.CLEANUP_FAILED -> "정리 불명 · 예약 유지 · 앱 프로세스 재시작 필요"
}



@Composable
private fun UwbDiagnostics(
    state: DiagnosticState,
    support: SpeechProbeState,
    trial: SpeechTrialState,
    uwb: UwbProbeState,
    actions: AppActions,
    uwbRanging: TeslaUwbProbeState,
    nowElapsedMs: Long,
) {
    var expanded by remember { mutableStateOf(false) }
    val blocked = when {
        uwb.status == UwbProbeStatus.CLEANUP_FAILED -> "등록 해제 불명 · 앱 프로세스 재시작 필요"
        uwbRanging.stage == TeslaUwbStage.CLEANUP_FAILED -> "차량 UWB 종료 불명 · 앱 프로세스 재시작 필요"
        uwbRanging.active -> {
            if (uwbRanging.serviceOwned) "홈에서 서비스를 먼저 꺼 주세요."
            else "수동 UWB 거리 진단을 먼저 정지하세요."
        }
        else -> supportQueryBlockedReason(state, support, trial)
    }
    fun permission(value: Boolean?): String = when (value) {
        true -> "허용됨"
        false -> "미승인 · 이 지원 조회를 차단하지 않음"
        null -> "미조회"
    }
    fun supported(value: Boolean?): String = when (value) {
        true -> "지원 보고"
        false -> "미지원 보고"
        null -> "capability 미수신"
    }
    UwbRangingDiagnostics(uwbRanging, actions, nowElapsedMs)
    AppSection("UWB 사전 지원 진단") {
        DetailRow("현재 상태", uwb.status.label)
        TextButton(
            onClick = if (uwb.status == UwbProbeStatus.RUNNING) actions.cancelUwbSupport else actions.queryUwbSupport,
            enabled = uwb.status == UwbProbeStatus.RUNNING || blocked == null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(if (uwb.status == UwbProbeStatus.RUNNING) "UWB 조회 취소" else "UWB 지원 확인")
        }
        blocked?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        DetailRow("가용 상태", uwb.metadata?.availabilityLabel ?: "응답 미수신")
        Text("이 지원 조회는 메타데이터만 확인합니다. 가용 보고는 실제 거리 수신·차량 근접·폰키 인증 성공의 증거가 아닙니다.")
        AppDisclosure(
            expanded = expanded,
            expandLabel = "UWB 상세 보기",
            collapseLabel = "UWB 상세 접기",
            onToggle = { expanded = !expanded },
        ) {
            DetailRow("backend", "Android 16 framework (RangingManager) · Tesla 공식 GMS 경로와 별개")
            DetailRow("hardware.uwb feature", when (uwb.hardwareFeature) {
                true -> "선언됨 · framework 가용과 별개"
                false -> "선언 안 됨"
                null -> "미조회"
            })
            DetailRow("framework backend", when (uwb.frameworkAvailable) {
                true -> "서비스 조회됨"
                false -> "서비스 없음 · 하드웨어/GMS 미지원으로 단정하지 않음"
                null -> "미조회"
            })
            DetailRow("RANGING 권한", permission(uwb.rangingPermission))
            DetailRow("UWB_RANGING 권한", permission(uwb.uwbRangingPermission))
            Text("이 지원 조회는 거리 세션을 열거나 권한을 요청하지 않습니다. 자동 측정은 홈 서비스 ON, 수동 진단은 별도 시작으로 구분합니다.")
            val metadata = uwb.metadata
            DetailRow("capability", if (metadata?.distance == null) "미수신" else "공개 capability 응답 수신")
            DetailRow("거리 측정 지원", supported(metadata?.distance))
            DetailRow("방위각 지원", supported(metadata?.azimuth))
            DetailRow("고도각 지원", supported(metadata?.elevation))
            DetailRow("백그라운드 지원", supported(metadata?.background))
            DetailRow("최소 측정 간격", metadata?.minimumIntervalMs?.let { "$it ms" } ?: "미수신")
            DetailRow("채널", metadata?.channels?.joinToString() ?: "미수신")
            DetailRow("config IDs", metadata?.configIds?.joinToString() ?: "미수신")
            DetailRow("preamble indexes", metadata?.preambleIndexes?.joinToString() ?: "미수신")
            DetailRow("slot duration (SDK enum)", metadata?.slotDurations?.joinToString() ?: "미수신")
            DetailRow("update rates (SDK enum)", metadata?.updateRates?.joinToString() ?: "미수신")
            DetailRow("종료 근거", uwb.reason ?: "미조회")
            Text("조회 결과는 RAM에만 유지합니다. ENABLED는 거리 준비·차량 인증 성공의 증거가 아닙니다.")
        }
    }
}

@Composable
private fun UwbRangingDiagnostics(probe: TeslaUwbProbeState, actions: AppActions, nowElapsedMs: Long) {
    var expanded by remember { mutableStateOf(false) }
    val capabilityProvided = actions.startUwbRanging != null &&
        actions.cancelUwbRanging != null && actions.uwbRangingBlockedReason != null
    val blocked = when {
        probe.stage == TeslaUwbStage.CLEANUP_FAILED -> "종료 불명 · 앱 프로세스 재시작 필요"
        !capabilityProvided -> "이 화면에는 UWB 거리 진단 기능이 제공되지 않습니다."
        probe.active -> null
        else -> actions.uwbRangingBlockedReason.invoke()?.let(::uwbRangingReasonLabel)
    }
    val stageColor = when (probe.stage) {
        TeslaUwbStage.FAILED, TeslaUwbStage.TIMED_OUT,
        TeslaUwbStage.BLOCKED, TeslaUwbStage.CLEANUP_FAILED -> MaterialTheme.colorScheme.error
        TeslaUwbStage.CANCELED -> AppWarningColor
        else -> MaterialTheme.colorScheme.onSurface
    }
    AppSection(if (probe.serviceOwned) "서비스 UWB 측정 상태" else "수동 UWB 거리 진단") {
        Text(
            vehicleUwbNotificationText(probe, nowElapsedMs) ?: probe.stage.label,
            style = MaterialTheme.typography.titleMedium,
            color = stageColor,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            if (probe.serviceOwned) "홈 서비스가 차량 감지 후 자동 측정합니다. 등록된 앱 키만 사용하며 마이크·프렁크는 실행하지 않습니다."
            else "서비스 OFF 상태에서 직접 시작하는 수동 진단입니다. 등록된 앱 키만 사용하며 키 신규 등록·프렁크·마이크는 실행하지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "360° 방향 검증 단계입니다. 거리와 같은 표본의 방위각·고도각 원값과 각 축 신뢰도를 순환 로그에 기록합니다. 회전 화살표는 아직 표시하지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        DetailRow("RANGING 권한", when (probe.rangingPermission) {
            true -> "허용 확인됨"
            false -> "허용 안 됨"
            null -> "확인 전"
        })
        if (probe.rangingPermission != true) {
            Text("설정 → 권한에서 UWB 거리 측정 권한을 직접 요청하세요.", color = AppWarningColor)
        }
        if (probe.active) {
            OutlinedButton(
                onClick = if (probe.serviceOwned) {
                    { actions.setVehicleServiceEnabled(false) }
                } else {
                    { actions.cancelUwbRanging?.invoke() }
                },
                enabled = probe.serviceOwned || actions.cancelUwbRanging != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(if (probe.serviceOwned) "서비스 끄기" else "수동 거리 진단 정지") }
        } else {
            Button(
                onClick = { actions.startUwbRanging?.invoke() },
                enabled = capabilityProvided && blocked == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text("수동 거리 진단 시작") }
        }
        blocked?.let { Text(it, color = AppWarningColor) }
        probe.reason?.let { Text(uwbRangingReasonLabel(it), color = stageColor) }
        DetailRow("framework 세션 열림", if (probe.backendOpened) "onOpened 수신" else "미관측")
        DetailRow("측정 시작 보고", "${probe.startedPeerCount} / ${probe.peerCount} peer")
        Text("onOpened·onStarted는 세션·시작 보고이며 실제 거리 수신이 아닙니다.",
            style = MaterialTheme.typography.bodySmall)
        if (probe.samples.isEmpty()) {
            Text("측정값 미수신", style = MaterialTheme.typography.titleMedium)
        } else {
            Text(
                "peer별 수신 기록 · 현재 위치 아님",
                style = MaterialTheme.typography.titleMedium,
            )
            probe.samples.forEach { sample ->
                val recent = teslaUwbSampleIsRecent(probe, sample, nowElapsedMs)
                DetailRow(
                    "peer ${sample.peerIndex + 1} · ${if (recent) "최근 2초 내 수신" else "과거 수신"}",
                    "${sample.meters} m · 거리 confidence 원값 ${sample.confidence}",
                )
            }
            Text(
                "실제 측정 중인 peer의 callback이 2초 이내인 경우에만 최근 수신으로 표시합니다. 최근 거리도 현재 위치·차량 중심·제어 허가를 보장하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        DetailRow("유효 거리 수신 횟수", probe.validDistanceCount.toString())
        DetailRow("무효 결과 수신 횟수", probe.invalidResultCount.toString())
        AppDisclosure(
            expanded = expanded,
            expandLabel = "거리 진단 상세 보기",
            collapseLabel = "거리 진단 상세 접기",
            onToggle = { expanded = !expanded },
        ) {
            Text("서비스 자동 측정과 수동 진단 모두 자동 마이크·거리 임계·차량 제어에 연결하지 않습니다.",
                style = MaterialTheme.typography.bodySmall)
            Text(
                if (probe.serviceOwned) "서비스 소유 측정은 화면 이탈·HOME·잠금만으로 중지하지 않습니다. 홈 서비스 OFF로 감지와 측정을 함께 종료합니다."
                else "수동 진단은 화면 이탈·HOME·잠금 시 종료를 요청하며 자동 재개하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("화면 결과는 RAM에 유지하고 비민감 거리·각도 원값·축별 신뢰도·지원 여부·수신 시각·종료 계측은 기존 순환 로그에 기록합니다.",
                style = MaterialTheme.typography.bodySmall)
            DetailRow("이번 세션 방위각 지원", angleCapabilityLabel(probe.azimuthSupported))
            DetailRow("이번 세션 고도각 지원", angleCapabilityLabel(probe.elevationSupported))
            Text("각도 raw는 단위·방향 의미 미확정이며 confidence도 품질 의미 미확정인 SDK 원값입니다. 도/라디안 변환·화살표·절대 방위로 해석하지 않습니다.",
                style = MaterialTheme.typography.bodySmall)
            Text("정차한 차량 앞 한 자리에서 휴대폰을 세로로 세워 후면을 차량 쪽으로 향하게 하세요. 몸과 휴대폰을 함께 오른쪽으로 한 바퀴 돌며 90°마다 5초씩 멈추고, 시작 시각을 기억해 두세요. 이 절차는 각도 수신 검증이며 실제 방향 정확도를 보장하지 않습니다.",
                style = MaterialTheme.typography.bodySmall)
            probe.samples.forEach { sample ->
                DetailRow("peer ${sample.peerIndex + 1} framework timestamp 원값",
                    "${sample.frameworkTimestampMillis} ms · 시계 기준 미확인")
                DetailRow("peer ${sample.peerIndex + 1} callback 수신 elapsed",
                    "${sample.receivedAtElapsedMs} ms")
                DetailRow("peer ${sample.peerIndex + 1} 방위각 수신 기록",
                    vehicleUwbAngleStatus(probe.azimuthSupported, sample.azimuthRaw))
                DetailRow("방위각 raw · 단위·방향 의미 미확정",
                    sample.azimuthRaw?.toString() ?: "미수신 또는 무효")
                DetailRow("방위각 confidence · 품질 의미 미확정",
                    sample.azimuthConfidence?.toString() ?: "미수신 또는 무효")
                DetailRow("peer ${sample.peerIndex + 1} 고도각 수신 기록",
                    vehicleUwbAngleStatus(probe.elevationSupported, sample.elevationRaw))
                DetailRow("고도각 raw · 단위·방향 의미 미확정",
                    sample.elevationRaw?.toString() ?: "미수신 또는 무효")
                DetailRow("고도각 confidence · 품질 의미 미확정",
                    sample.elevationConfidence?.toString() ?: "미수신 또는 무효")
            }
            DetailRow("종료 사유", probe.reason?.let(::uwbRangingReasonLabel) ?: "미수신")
            DetailRow("framework onClosed 사유 원값", probe.closedReason?.toString() ?: "미수신")
            DetailRow("framework onOpenFailed 사유 원값", probe.openFailedReason?.toString() ?: "미수신")
            Text("VIN·주소·STS는 표시하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun angleCapabilityLabel(supported: Boolean?): String = when (supported) {
    true -> "지원 보고 · 실제 수신과 별개"
    false -> "미지원 보고"
    null -> "이번 세션 지원 미확인"
}

private fun uwbRangingReasonLabel(reason: String): String = when (reason) {
    "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" -> "진단 종료 불명 · 예약 유지 · 앱 프로세스 재시작 필요"
    "VISIBLE_UI_REQUIRED" -> "이 진단 화면을 표시한 상태에서만 시작할 수 있습니다."
    "UNLOCKED_UI_REQUIRED" -> "휴대전화 잠금을 직접 해제하세요."
    "RANGING_PERMISSION_REQUIRED" -> "RANGING 거리 측정 권한을 먼저 허용하세요."
    "BLUETOOTH_PERMISSION_REQUIRED" -> "설정에서 Bluetooth 연결 권한을 먼저 허용하세요."
    "BLUETOOTH_OFF" -> "Bluetooth를 직접 켜세요."
    "SINGLE_ASSOCIATION_REQUIRED" -> "설정에서 차량 한 대의 시스템 등록이 필요합니다."
    "LEASE_UNAVAILABLE" -> "홈의 서비스 사용을 OFF로 하고 다른 진단을 종료하세요."
    "EXISTING_KEY_UNAVAILABLE" -> "기존 앱 키를 읽지 못했습니다. 새 키를 자동 생성하거나 등록하지 않았습니다."
    "NO_DISTANCE_RECEIVED" -> "이번 측정에서 유효 거리값을 받지 못했습니다."
    "DISTANCE_RECEIVE_TIMEOUT" -> "유효 거리 callback 수신이 중단되어 측정을 종료했습니다."
    "CAPABILITIES_UNSPECIFIED" -> "차량용 지원 비트 의미는 미확정이며, 공식 앱에서 관측한 빈 지원 응답으로 협상을 시도합니다."
    "UWB_NOT_ENABLED" -> "UWB가 비활성 상태입니다. 휴대전화 UWB 설정을 확인하세요."
    "CONFIG_UNSUPPORTED" -> "차량 요청의 STS 방식·peer 구성과 휴대전화 지원 설정이 맞지 않습니다."
    "NATIVE_OPEN_FAILED" -> "framework가 UWB 세션을 열지 못했습니다. 상세의 onOpenFailed 원값을 확인하세요."
    "NATIVE_CLOSE_TIMEOUT", "NATIVE_CLEANUP_FAILED" -> "framework 종료를 확인하지 못했습니다. 기존 예약을 유지하며 새 진단을 차단합니다."
    else -> reason
}

private fun supportQueryBlockedReason(
    state: DiagnosticState,
    support: SpeechProbeState,
    trial: SpeechTrialState,
): String? = when {
    support.reason == "DESTROY_FAILED" || support.reason == "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" -> "진단 자원 정리 실패 · 앱 프로세스 재시작 필요"
    state.vehicleVin.status == VehicleVinStatus.SAVING -> "VIN 저장이 끝난 뒤 지원 조회를 시작하세요."
    state.teslaKeyDiagnosticActive -> "차량 키 진단·정리가 끝나야 다른 진단을 시작할 수 있습니다."
    state.bleFieldBusy() -> "홈에서 차량 감지·UWB 서비스를 먼저 꺼 주세요."
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
    val owner = LocalLifecycleOwner.current
    val currentActions by rememberUpdatedState(actions)
    var resumed by remember(owner) { mutableStateOf(false) }
    var autoQueryAttempted by remember(owner) { mutableStateOf(false) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY -> {
                    resumed = false
                    autoQueryAttempted = false
                    currentActions.cancelSupport()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            currentActions.cancelSupport()
        }
    }
    // 화면 표시·기존 진단 종료 뒤 한 번만 메타데이터를 조회한다. 실패/취소는 자동 반복하지 않는다.
    LaunchedEffect(resumed, queryBlocked, state.microphonePermission) {
        if (resumed && queryBlocked == null &&
            (!autoQueryAttempted ||
                (support.status == SpeechProbeStatus.PERMISSION_REQUIRED && state.microphonePermission))
        ) {
            autoQueryAttempted = true
            currentActions.querySupport()
        }
    }
    val canStartTrial = support.status == SpeechProbeStatus.COMPLETE &&
        support.metadata?.koKrInstalled == true &&
        state.microphonePermission &&
        !trial.active &&
        !state.speechDiagnosticActive &&
        !state.teslaKeyDiagnosticActive &&
        !state.bleFieldBusy() &&
        state.sessionId == null &&
        !state.enabled

    actions.frunkVoice?.let { FrunkVoiceDiagnostics(state, support, it) }

    AppSection("온디바이스 한국어 지원") {
        DetailRow("현재 상태", support.status.label)
        Text("화면 진입 시 자동 확인합니다. 마이크 녹음이나 차량 제어는 시작하지 않습니다.",
            style = MaterialTheme.typography.bodySmall)
        if (support.status != SpeechProbeStatus.COMPLETE || support.metadata?.koKrInstalled != true) {
            TextButton(
                onClick = if (support.status == SpeechProbeStatus.RUNNING) actions.cancelSupport else actions.querySupport,
                enabled = support.status == SpeechProbeStatus.RUNNING || canQuery,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (support.status == SpeechProbeStatus.RUNNING) "지원 조회 취소" else "지원 조회 다시 시도")
            }
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

    AppSection("차량 미전송 · dry-run 입력 선택") {
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
        Text("시험 문장: ${SpeechCommandPhrases.displaySentence}", style = MaterialTheme.typography.bodyMedium)
        Text("앱이 입력을 먼저 캡처한 뒤 같은 PCM을 Android 온디바이스 SpeechRecognizer(ko-KR)로 인식합니다. 실시간 스트리밍 인식이 아닙니다.",
            style = MaterialTheme.typography.bodySmall)
        Text("인식 후보(최대 3개) 중 하나가 시험 문장과 정확히 같으면 일치로 봅니다. 어느 후보든 취소·열지마가 있으면 취소로 판정합니다. 신뢰도는 기록만 합니다.",
            style = MaterialTheme.typography.bodySmall)
        Text(
            if (selectedMode == SpeechTrialMode.DIRECT_MIC || selectedMode == SpeechTrialMode.BUFFERED_PCM) {
                "시험 녹음·인식 원문은 기본 저장합니다. 앱 내부 백업 제외 영역에 보관하며 ADB로만 회수합니다."
            } else {
                "합성·무음 파일 입력은 녹음·인식 원문을 저장하지 않습니다."
            },
            style = MaterialTheme.typography.bodySmall,
        )
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

/** 명시적 실차 capability가 있는 생산 화면에만 노출한다. 동의는 저장·복원하지 않는다. */
@Composable
internal fun FrunkVoiceDiagnostics(
    state: DiagnosticState,
    support: SpeechProbeState,
    actions: FrunkVoiceActions,
) {
    val voice = state.frunkVoice
    var consent by remember(state.vehicleVin, state.associationCount) { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> actions.visible(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY -> {
                    consent = false
                    actions.visible(false)
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        actions.visible(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            consent = false
            actions.visible(false)
            owner.lifecycle.removeObserver(observer)
        }
    }
    val blocked = actions.blockedReason()
    val modelReady = support.status == SpeechProbeStatus.COMPLETE && support.metadata?.koKrInstalled == true
    val idle = !voice.active && !state.speechDiagnosticActive && !state.teslaKeyDiagnosticActive
    val canStart = idle && blocked == null && modelReady && state.frunkVoiceVisible

    AppSection("수동 실차 음성 시험 · 프렁크 1회") {
        Text("화면을 켜고 잠금을 해제한 실차 시험입니다. 주변 사람·재생 음성도 인식될 수 있으므로 차량 앞 안전을 직접 확인하세요.",
            color = AppWarningColor)
        Text("“${SpeechCommandPhrases.displaySentence}”가 인식되면 추가 확인 없이 안전 조건을 검사해 개방을 요청합니다.")
        Text("마이크 입력을 먼저 캡처하고 말이 끝나면 입력을 해제한 뒤 Android 온디바이스 SpeechRecognizer(ko-KR)로 인식합니다. 인식기가 점수를 제공하지 않으면(0.0 등) 문장 일치만으로 진행합니다.",
            color = AppWarningColor)
        val step = voice.progressStep()
        Text(step.label, style = MaterialTheme.typography.titleMedium,
            color = when (step) {
                FrunkVoiceStep.CLEANUP_FAILED, FrunkVoiceStep.FAILED,
                FrunkVoiceStep.UNKNOWN, FrunkVoiceStep.TIMED_OUT -> MaterialTheme.colorScheme.error
                FrunkVoiceStep.COMPLETE -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        // 종료·실패 시에도 어디까지 실제로 갔는지 남긴다. 완료는 아래 관측 행이 근거를 보여준다.
        if (!voice.active && step != FrunkVoiceStep.COMPLETE && step != FrunkVoiceStep.IDLE &&
            step != FrunkVoiceStep.COMMAND && step != FrunkVoiceStep.CLEANING
        ) {
            voice.lastCoreStep?.let { DetailRow("마지막 실제 단계", it.label) }
        }
        val reason = voice.reason ?: when (step) {
            // 관측 사유가 비어도 결과 불명을 성공으로 보이게 두지 않는다.
            FrunkVoiceStep.UNKNOWN -> "열림 보고를 확인하지 못했습니다."
            FrunkVoiceStep.CLEANUP_FAILED -> "정리 결과를 확인하지 못했습니다."
            else -> null
        }
        reason?.let {
            Text(frunkVoiceReasonLabel(it), style = MaterialTheme.typography.bodySmall, color = AppWarningColor)
        }
        if (voice.coreStarted) {
            DetailRow("명령 요청", if (voice.frunkCommandRequested) "core에서 실차 요청 접수" else "요청 접수 미관측")
            DetailRow("신선한 P", if (voice.frunkFreshParkVerified) "이번 회차에서 확인됨" else "확인 전 · 전송 근거 아님")
            DetailRow("요청 전송", when {
                voice.frunkCommandSent -> "SDK 접수됨 · 차량 응답 대기"
                voice.active -> "전송 미관측 · 같은 회차의 신선한 P·권한 검사 중"
                voice.reason == "FRUNK_VOICE_CORE_UNKNOWN" -> "결과 불명 · 전송되었을 수 있음"
                else -> "전송 미관측"
            })
            DetailRow("차량 응답", if (voice.frunkCommandAcknowledged) "명령 ACK 확인됨" else "ACK 미확인")
            DetailRow("열림 관측", if (voice.frunkOpenObserved) "후속 BODY에서 프렁크 열림 보고" else "미관측")
            Text("SDK 접수·차량 ACK·BODY 열림 보고·물리 개방은 서로 다른 단계입니다. 열림 보고만으로 물리 개방 완료를 표시하지 않습니다.",
                style = MaterialTheme.typography.bodySmall)
            if (voice.cancelRequested || voice.frunkCommandSent) {
                Text("전송 후 취소는 차량 동작을 철회하지 못할 수 있습니다. 결과 불명에서는 자동 재전송하지 않습니다.", color = AppWarningColor)
            }
        }
        if (voice.active) {
            OutlinedButton(onClick = actions.cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (voice.frunkCommandSent) "결과 관측 중단 · 전송 철회 불가" else "진행 취소")
            }
        } else {
            Text("시험 녹음·인식 원문은 기본 저장합니다. 앱 내부 백업 제외 영역에 보관하며 ADB로만 회수합니다.",
                style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = canStart,
                    modifier = Modifier.semantics { contentDescription = "실제 차량 프렁크 1회 시험과 마스킹 인식 로그 저장 동의" })
                Text("프렁크 1회 시험과 마스킹한 인식 문장·신뢰도·차단 사유의 앱 내부 순환 저장에 동의합니다.", modifier = Modifier.weight(1f))
            }
            Button(
                onClick = {
                    if (canStart && consent) {
                        consent = false
                        actions.start(true)
                    }
                },
                enabled = canStart && consent,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("실차 음성 시험 시작") }
            if (!canStart) {
                Text(when {
                    blocked == "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" -> "정리 불명 · 새 시험 차단 · 앱 프로세스 재시작 필요"
                    !idle -> "기존 진단·정리가 끝난 뒤 시작하세요."
                    blocked == "SPEECH_SUPPORT_RUNNING" -> "지원 조회가 끝난 뒤 시작하세요."
                    blocked == "DETECTION_MUST_STOP" -> "감지는 제품 음성과 미통합입니다. 기존 중지 버튼으로 감지를 정상 중지한 뒤 시작하세요."
                    blocked != null -> teslaKeyBlockedLabel(blocked)
                    !modelReady -> "온디바이스 한국어 지원 확인을 먼저 완료하세요."
                    else -> "음성 진단 화면을 표시한 상태에서만 시작할 수 있습니다."
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("명령 10초 안에 인식까지 끝나야 합니다 · 후보(n-best) 중 하나가 정확히 일치해야 하며 어느 후보든 취소·열지마가 있으면 차단합니다 · 실제 점수가 오면 ${FrunkVoicePolicy.MIN_CONFIDENCE} 이상이어야 하고, 점수 미제공(0.0 등)은 기록 후 점수 조건을 생략합니다 · 인식기 실패는 일부 결과가 있어도 차단합니다.",
            style = MaterialTheme.typography.bodySmall)
        Text("명령이 인정된 시점부터 최대 40초의 기존 작업 기한이 적용됩니다. 남은 시간을 채우는 가짜 진행 없이 실제 관측된 단계만 표시합니다.",
            style = MaterialTheme.typography.bodySmall)
        voice.commandConfidence?.let { DetailRow("명령 신뢰도", it.toString()) }
        voice.transcript?.let { transcript ->
            DetailRow("인식 판정", if (voice.transcriptAccepted) "명령 문장 인정 · 차량 실행 허가 아님"
                else "차단 · " + (voice.transcriptReason?.let(::frunkVoiceReasonLabel) ?: "사유 미기록"))
            TranscriptRows(transcript)
        }
        voice.recordingId?.let { DetailRow("저장 녹음 ID", it) }
        if (voice.recordingFailure) Text("녹음 또는 원문 결과 저장 실패 · ADB 회수 시 확인 필요", color = AppWarningColor)
        Text("HOME·잠금·화면 이탈은 신규 전송을 취소합니다. 화면 복귀·회전으로 다시 시작하지 않습니다. 잠금·주머니 동작은 미지원입니다.",
            style = MaterialTheme.typography.bodySmall)
    }
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
            DetailRow("인식 신뢰도", trial.confidence?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "미제공(0.0 등) · 기록만 함")
            trial.transcript?.let { TranscriptRows(it) }
            trial.shadow?.let { ShadowRows(it) }
            DetailRow("진단 상태", trial.status.label)
            trial.recordingId?.let { DetailRow("저장 녹음 ID", it) }
            if (trial.recordingFailure) Text("녹음 또는 원문 결과 저장 실패", color = AppWarningColor)
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

/** 사용자가 시작한 시험의 n-best 원문을 정규화·마스킹 없이 RAM 상태 그대로 보여 준다. */
@Composable
private fun TranscriptRows(transcript: SttTranscript) {
    if (transcript.candidates.isEmpty()) {
        DetailRow("인식 후보", "후보 없음")
    } else {
        transcript.candidates.forEachIndexed { index, candidate ->
            DetailRow("후보 ${index + 1}", "${transcriptText(candidate.text)} · ${transcriptScoreLabel(candidate.confidence)}")
        }
        if (transcript.total > transcript.candidates.size) {
            DetailRow("표시 후보", "전체 ${transcript.total}개 중 ${transcript.candidates.size}개")
        }
    }
    Text("인식 원문은 화면에만 표시합니다 · RAM 전용 · 다음 같은 시험 시작·앱 프로세스 종료 시 지움 · 주변 대화도 표시될 수 있음 · 차량 실행 허가 아님",
        style = MaterialTheme.typography.bodySmall)
}

private fun transcriptText(text: String) = if (text.isEmpty()) "(빈 문자열)" else "\"$text\""

/** 같은 PCM을 sherpa-onnx로 다시 인식한 그림자 결과. 위 판정·dry-run과 무관한 비교용 RAM 상태다. */
@Composable
private fun ShadowRows(shadow: ShadowTranscript) {
    DetailRow("실험용 로컬 인식", "sherpa-onnx · 명령 판정·차량 제어에 사용 안 함")
    DetailRow("로컬 인식 원문", when {
        shadow.failure != null -> "결과 없음 · ${shadow.failure}"
        else -> shadow.text?.let(::transcriptText) ?: "결과 없음"
    })
    DetailRow("로컬 시험 문장 일치", when (shadow.matched) { true -> "예"; false -> "아니오"; null -> "미판정" })
    DetailRow("로컬 호출어·명령 판정", commandDecisionLabel(shadow.decision))
    DetailRow("로컬 처리 시간", "모델 로드 ${shadowMs(shadow.loadMs)} · 인식 ${shadowMs(shadow.decodeMs)}")
    Text("로컬 인식 원문도 화면에만 표시합니다 · RAM 전용 · 위 판정·dry-run·저장 녹음 결과에 반영 안 함",
        style = MaterialTheme.typography.bodySmall)
}

private fun shadowMs(ms: Long?) = ms?.let { "$it ms" } ?: "측정 안 됨"

/** 양수 유한값만 실제 점수다. 0.0·-1·NaN 같은 원값은 숨기지 않고 미제공으로 표시한다. */
private fun transcriptScoreLabel(score: Float?) = when {
    score == null -> "점수 없음"
    score.isFinite() && score > 0f -> String.format(Locale.ROOT, "점수 %.4f", score)
    else -> "점수 미제공(원값 $score)"
}

@Composable
private fun MicrophoneDiagnostics(state: DiagnosticState, actions: AppActions) {
    var detailsExpanded by remember { mutableStateOf(false) }

    AppSection("제한 마이크 시험") {
        Button(
            onClick = actions.startMicrophone,
            shape = MaterialTheme.shapes.medium,
            enabled = state.sessionId == null && !state.speechDiagnosticActive && !state.teslaKeyDiagnosticActive && !state.bleFieldBusy(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("수동 마이크 10초 시험 시작")
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
        Text("RAM 최근 32건입니다. 프로세스가 끝나면 사라지며 영속 기록은 아래 서비스·진단 로그 파일에 남습니다.", style = MaterialTheme.typography.bodySmall)
        if (state.events.isEmpty()) {
            Text("표시할 RAM 이벤트가 없습니다.", style = MaterialTheme.typography.bodyMedium)
        } else {
            state.events.forEach { event ->
                Text(event, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    AppSection("서비스·진단 로그 파일") {
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
            "새 JSONL 로그는 전체 용량 상한 안에서 순환 보관합니다. 로그 실패는 감지를 중지시키지 않으며 기록 유실 상태를 별도로 표시합니다. 이전 events.jsonl은 변경하지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "USB 디버깅을 승인한 PC에서 새 로그를 회수하세요: adb -d exec-out run-as com.heytesla.app cat no_backup/field-diagnostics/events-v2.jsonl. 회전 파일도 함께 회수하세요. 손상 행과 기록 유실을 구분하세요.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "새 로그만 용량 제한에 따라 자동 정리합니다. 기존 로그·차량 키·설정은 보존합니다. 앱 데이터 초기화는 사용하지 마세요.",
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
    SpeechTrialMode.TTS_PCM -> "발화 없이 합성 파일을 Android 온디바이스 STT로 인식하고 입력 해제 후 결과를 음성 안내합니다."
    SpeechTrialMode.SILENT_PCM -> "같은 길이의 무음 입력입니다. 후보가 없으면 거절하고 입력 해제 후 음성 안내합니다."
    SpeechTrialMode.DIRECT_MIC -> "앱이 최대 10초 PCM을 캡처한 뒤 마이크를 해제하고 Android 온디바이스 STT로 인식합니다. 캡처 중 시험 문장을 말해 주세요."
    SpeechTrialMode.BUFFERED_PCM -> "앱이 최대 10초 PCM을 캡처한 뒤 마이크를 해제하고 Android 온디바이스 STT로 인식합니다. 캡처 중 말하고 인계 중에는 조용히 기다리세요."
}

private fun trialStartBlockedReason(
    state: DiagnosticState,
    support: SpeechProbeState,
    trial: SpeechTrialState,
) = when {
    trial.reason?.contains("RESTART_REQUIRED") == true -> "자원 정리 실패가 남아 앱 재시작이 필요합니다"
    support.reason == "DESTROY_FAILED" || support.reason == "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" -> "진단 자원 정리 실패 · 앱 프로세스 재시작 필요"
    state.teslaKeyDiagnosticActive -> "차량 키 진단·정리가 끝나야 음성 시험을 시작할 수 있습니다"
    state.bleFieldBusy() -> "홈에서 차량 감지 서비스를 먼저 꺼 주세요"
    trial.active -> "진행 중인 음성 시험을 먼저 취소하거나 종료하세요"
    state.speechDiagnosticActive -> "음성 진단 오디오 예약이 남아 있습니다 · 다른 진단의 종료 여부를 확인하세요"
    state.sessionId != null -> "기존 마이크 세션이 점유 중입니다 · 즉시 종료 후 다시 시도하세요"
    state.enabled -> "접근 진단을 먼저 비활성화하세요"
    support.status != SpeechProbeStatus.COMPLETE -> "먼저 지원 조회를 완료하세요"
    support.metadata?.koKrInstalled != true -> "정확한 ko-KR 설치 보고가 필요합니다"
    !state.microphonePermission -> "마이크 권한이 필요합니다"
    else -> "현재 시작할 수 없습니다"
}
