package com.heytesla.app.ui

import com.heytesla.app.speech.SpeechTrialMode
import com.heytesla.app.DiagnosticState
import com.heytesla.app.vehicle.protocol.TeslaBleQuery

/** UI events only; the Activity and existing runtime retain ownership of side effects. */
internal class AppActions(
    val requestPermission: (String) -> Unit,
    val openSystemSettings: (String) -> Unit,
    val saveVehicleVin: (String) -> Unit,
    val vehicleVinChangeBlockedReason: () -> String?,
    val associateVehicle: () -> Unit,
    val setVehicleServiceEnabled: (Boolean) -> Unit,
    val vehicleServiceBlockedReason: () -> String?,
    val resumeVehicleNavigation: () -> Unit,
    val refresh: () -> Unit,
    val startMicrophone: () -> Unit,
    val stopSession: () -> Unit,
    val querySupport: () -> Unit,
    val cancelSupport: () -> Unit,
    val queryUwbSupport: () -> Unit,
    val cancelUwbSupport: () -> Unit,
    /** 네 입력 모두 동일 STT→로컬 dry-run→입력 해제→오프라인 TTS 진단을 시작한다. 마이크 입력은 녹음·원문을 기본 저장한다. */
    val startTrial: (SpeechTrialMode) -> Unit,
    val finishCapture: () -> Unit,
    /** 인식·정리·TTS 어느 단계든 동일 소유권을 취소한다. 정리 불명 예약은 풀지 않는다. */
    val cancelTrial: () -> Unit,
    val startTeslaKey: (Boolean, TeslaBleQuery) -> Unit,
    val cancelTeslaKey: () -> Unit,
    val teslaKeyBlockedReason: () -> String?,
    val leaveDiagnostics: () -> Unit,
    /** 실차 동작을 허용하지 않는 계측 화면에는 이 capability를 제공하지 않는다. */
    val frunkVoice: FrunkVoiceActions? = null,
    /** 수동 UWB 거리 진단 capability. 미제공 UI에는 실제 실행 버튼을 활성화하지 않는다. */
    val startUwbRanging: (() -> Unit)? = null,
    val cancelUwbRanging: (() -> Unit)? = null,
    val uwbRangingBlockedReason: (() -> String?)? = null,
)

internal class FrunkVoiceActions(
    val start: (consent: Boolean) -> Unit,
    val cancel: () -> Unit,
    val visible: (Boolean) -> Unit,
    val blockedReason: () -> String?,
)

internal fun DiagnosticState.bleFieldBusy(): Boolean =
    bleFieldTrialActive || bleFieldTrialStarting || bleFieldTrialStopping
