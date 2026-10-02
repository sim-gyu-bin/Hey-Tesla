package com.heytesla.app.ui

import com.heytesla.app.SpeechTrialMode

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
    /** 네 입력 모두 동일 STT→로컬 dry-run→입력 해제→오프라인 TTS 진단을 시작한다. */
    val startTrial: (SpeechTrialMode) -> Unit,
    val finishCapture: () -> Unit,
    /** 인식·정리·TTS 어느 단계든 동일 소유권을 취소한다. 정리 불명 예약은 풀지 않는다. */
    val cancelTrial: () -> Unit,
    val startBle: () -> Unit,
    val cancelBle: () -> Unit,
    val leaveDiagnostics: () -> Unit,
)
