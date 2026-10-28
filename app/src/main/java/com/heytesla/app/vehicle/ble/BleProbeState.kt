package com.heytesla.app.vehicle.ble

internal enum class BleProbeStatus {
    IDLE, BLOCKED, CONNECTING, DISCOVERING, SUBSCRIBING, OBSERVING, CLEANING_UP,
    COMPLETE, CANCELED, TIMED_OUT, FAILED, CLEANUP_FAILED,
}

internal data class BleProbeState(
    val status: BleProbeStatus = BleProbeStatus.IDLE,
    val reason: String? = null,
    val connected: Boolean = false,
    val serviceFound: Boolean = false,
    val txFound: Boolean = false,
    val rxFound: Boolean = false,
    val subscriptionConfirmed: Boolean = false,
    val remoteUnsubscribeConfirmed: Boolean = false,
    val disconnectConfirmed: Boolean = false,
    val localClosed: Boolean = false,
    val notificationCount: Int = 0,
    val elapsedMs: Long = 0,
    val gattStatus: Int? = null,
    /** 마지막 전이 때 끝난 단계 또는 현재 단계의 실제 monotonic 경과 시간. */
    val phaseElapsedMs: Long = 0,
    val primaryGattStatus: Int? = null,
    val cleanupGattStatus: Int? = null,
    val firstRxElapsedMs: Long? = null,
    val backgroundConnect: Boolean = false,
) {
    val active: Boolean get() = when (status) {
        BleProbeStatus.CONNECTING, BleProbeStatus.DISCOVERING, BleProbeStatus.SUBSCRIBING,
        BleProbeStatus.OBSERVING, BleProbeStatus.CLEANING_UP -> true
        else -> false
    }
}
