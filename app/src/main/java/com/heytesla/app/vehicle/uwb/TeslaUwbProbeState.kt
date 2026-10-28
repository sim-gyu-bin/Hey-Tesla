package com.heytesla.app.vehicle.uwb

internal enum class TeslaUwbStage(val label: String) {
    IDLE("아직 측정하지 않음"),
    PREPARING("UWB 지원·등록 키 준비 중"),
    AUTHENTICATING("차량 키 인증 중"),
    WAITING_FOR_VEHICLE("차량 UWB 협상 요청 대기 중"),
    STARTING("UWB 측정 시작 중 · 거리 미수신"),
    RANGING("UWB 측정 중"),
    CLEANING_UP("BLE·UWB 종료 확인 중"),
    COMPLETE("측정 종료"),
    CANCELED("측정 취소"),
    FAILED("측정 실패"),
    TIMED_OUT("측정 시간 종료"),
    BLOCKED("측정 시작 차단"),
    CLEANUP_FAILED("종료 불명 · 앱 프로세스 재시작 필요"),
}

/** 주소·VIN·STS는 포함하지 않는다. samples는 peer마다 마지막 유효 수신값이며 RAM에만 둔다. */
internal data class TeslaUwbProbeState(
    val stage: TeslaUwbStage = TeslaUwbStage.IDLE,
    val rangingPermission: Boolean? = null,
    val backendOpened: Boolean = false,
    val peerCount: Int = 0,
    val startedPeerCount: Int = 0,
    val validDistanceCount: Long = 0,
    val invalidResultCount: Long = 0,
    val samples: List<TeslaUwbDistance> = emptyList(),
    /** 같은 elapsed millisecond에 여러 peer가 응답해도 이번 이벤트의 샘플을 정확히 보존한다. */
    val lastReceivedSample: TeslaUwbDistance? = null,
    val reason: String? = null,
    val closedReason: Int? = null,
    val openFailedReason: Int? = null,
    val serviceOwned: Boolean = false,
    val azimuthSupported: Boolean? = null,
    val elevationSupported: Boolean? = null,
    /** Only peers still eligible for live display; samples remain as historical RAM evidence. */
    val livePeerIndices: Set<Int> = emptySet(),
    /** 서비스 BLE lifecycle 근거. 거리·capability 갱신과 종료 뒤에도 같은 작업의 관측을 보존한다. */
    val gatt: TeslaUwbGattObservation? = null,
) {
    val active: Boolean get() = when (stage) {
        TeslaUwbStage.PREPARING, TeslaUwbStage.AUTHENTICATING,
        TeslaUwbStage.WAITING_FOR_VEHICLE, TeslaUwbStage.STARTING,
        TeslaUwbStage.RANGING, TeslaUwbStage.CLEANING_UP -> true
        else -> false
    }
}
