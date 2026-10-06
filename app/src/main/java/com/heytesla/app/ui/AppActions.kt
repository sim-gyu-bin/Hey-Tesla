package com.heytesla.app.ui

import com.heytesla.app.BleFieldConfig
import com.heytesla.app.SpeechTrialMode
import com.heytesla.app.BleProbeState
import com.heytesla.app.BleProbeStatus
import com.heytesla.app.DiagnosticState
import com.heytesla.app.FieldLogHealth

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
    val queryUwbSupport: () -> Unit,
    val cancelUwbSupport: () -> Unit,
    /** 네 입력 모두 동일 STT→로컬 dry-run→입력 해제→오프라인 TTS 진단을 시작한다. */
    val startTrial: (SpeechTrialMode) -> Unit,
    val finishCapture: () -> Unit,
    /** 인식·정리·TTS 어느 단계든 동일 소유권을 취소한다. 정리 불명 예약은 풀지 않는다. */
    val cancelTrial: () -> Unit,
    val startBle: (BleFieldConfig) -> Unit,
    val cancelBle: () -> Unit,
    val bleFieldBlockedReason: (BleFieldConfig) -> String?,
    val startTeslaKey: (String, Boolean) -> Unit,
    val cancelTeslaKey: () -> Unit,
    val teslaKeyBlockedReason: () -> String?,
    val leaveDiagnostics: () -> Unit,
)

internal fun DiagnosticState.bleFieldBusy(): Boolean =
    bleFieldTrialActive || bleFieldTrialStarting || bleFieldTrialStopping

internal fun bleFieldStatus(state: DiagnosticState, ble: BleProbeState): String = when {
    ble.status == BleProbeStatus.CLEANUP_FAILED -> "정리 실패 · 예약 유지 · 재시작 필요"
    state.bleFieldTrialStopping -> "주말 BLE 반복 시험 정리 중"
    state.bleFieldTrialStarting -> "주말 BLE 반복 시험 시작 중"
    state.bleFieldTrialActive && ble.status == BleProbeStatus.CLEANING_UP -> "당회차 연결 정리 중"
    state.bleFieldTrialActive && ble.active -> "주말 BLE 반복 시험 · 당회차 진단 중"
    state.bleFieldTrialActive && state.bleFieldScanFailure != null -> "보조 스캔 실패 · 코드 ${state.bleFieldScanFailure}"
    state.bleFieldTrialActive && state.bleFieldConfig.observationOnly -> "감지만 모드 · 후보 관찰 중 · GATT 수행 안 함"
    state.bleFieldTrialActive && state.bleFieldTrialWaitingForDeparture -> "이탈 신호 후 다음 후보 대기"
    state.bleFieldTrialActive -> "근접 신호 대기"
    state.bleFieldTrialStopReason != null -> "주말 BLE 반복 시험 중지됨"
    else -> "주말 BLE 반복 시험 꺼짐"
}

internal fun bleFieldLogStatus(state: DiagnosticState): String {
    val health = when (state.fieldLog.health) {
        FieldLogHealth.OK -> "기록 중"
        FieldLogHealth.LIMIT_REACHED -> "상한 도달 · 기록 중단"
        FieldLogHealth.QUEUE_OVERFLOW -> "큐 초과 · 기록 중단"
        FieldLogHealth.WRITE_FAILED -> "쓰기 실패 · 기록 중단"
    }
    return "$health · ${state.fieldLog.fileBytes} / ${state.fieldLog.limitBytes} bytes"
}
