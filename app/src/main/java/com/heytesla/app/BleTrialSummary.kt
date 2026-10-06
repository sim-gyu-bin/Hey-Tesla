package com.heytesla.app

/** 서비스 실행 내 한 회차의 비민감 증거. 원문·주소·수신 bytes를 보관하지 않는다. */
data class BleTrialSummary(
    val attempt: Long,
    val status: String,
    val reason: String? = null,
    val gattStatus: Int? = null,
    val elapsedMs: Long,
    val connected: Boolean,
    val serviceFound: Boolean,
    val txFound: Boolean,
    val rxFound: Boolean,
    val subscriptionConfirmed: Boolean,
    val remoteUnsubscribeConfirmed: Boolean,
    val disconnectConfirmed: Boolean,
    val localClosed: Boolean,
    val notificationCount: Int,
    val leaseRetained: Boolean,
    val interactive: Boolean = false,
    val deviceLocked: Boolean = false,
    val batteryPercent: Int? = null,
    val phaseElapsedMs: Long? = null,
    val primaryGattStatus: Int? = null,
    val cleanupGattStatus: Int? = null,
    val firstRxElapsedMs: Long? = null,
    val backgroundConnect: Boolean = false,
)

/** 문자열은 고정 enum/사유만 기록한다. 불명 값은 그 필드만 null이며 SDK status는 변환하지 않는다. */
internal object BleTrialSummaryJson {
    private val statuses = setOf(
        "IDLE", "BLOCKED", "CONNECTING", "DISCOVERING", "SUBSCRIBING", "OBSERVING",
        "CLEANING_UP", "COMPLETE", "CANCELED", "TIMED_OUT", "FAILED", "CLEANUP_FAILED",
    )
    private val reasons = setOf(
        "INTERNAL_FAILURE", "SESSION_EXPIRED", "CONNECT_REQUEST_FAILED", "USER_CANCELED",
        "SUBSCRIPTION_OBSERVED", "CONNECTION_LOST", "CONNECTION_FAILED", "DISCOVERY_REQUEST_FAILED",
        "DISCOVERY_FAILED", "PROFILE_READ_FAILED", "TESLA_SERVICE_MISSING", "TESLA_TX_MISSING",
        "TESLA_RX_MISSING", "RX_SUBSCRIPTION_UNSUPPORTED", "RX_CCCD_MISSING", "LOCAL_SUBSCRIPTION_FAILED",
        "SUBSCRIPTION_REQUEST_FAILED", "SUBSCRIPTION_FAILED", "LOCAL_CLOSE_FAILED_RESTART_REQUIRED",
        "CONNECTING_TIMEOUT", "DISCOVERING_TIMEOUT", "SUBSCRIBING_TIMEOUT", "OBSERVING_TIMEOUT",
        "LEASE_UNAVAILABLE", "LEASE_LOST", "VISIBLE_UI_REQUIRED", "BLUETOOTH_PERMISSION_REQUIRED",
        "BLUETOOTH_PERMISSION_REVOKED", "BLUETOOTH_UNAVAILABLE", "BLUETOOTH_OFF",
        "SINGLE_ASSOCIATION_REQUIRED", "ASSOCIATION_ADDRESS_UNAVAILABLE", "ASSOCIATION_RESOLVE_FAILED",
        "ASSOCIATION_REMOVED", "DIAGNOSTIC_EXCLUSIVITY_LOST", "PREREQUISITE_READ_FAILED",
        "USER_STOP", "DISABLED", "DEPARTED", "BLE_FIELD_NOTIFICATION_STOP",
        "FIELD_OWNER_NOT_ARMED", "OBSERVATION_ACTIVE", "FIELD_MARKER_UNAVAILABLE", "FIELD_LOG_UNHEALTHY",
        "FIELD_MARKER_WRITE_FAILED", "CDM_UNSUPPORTED", "CDM_UNAVAILABLE", "ASSOCIATION_REQUIRED",
        "MULTIPLE_ASSOCIATIONS_UNSUPPORTED", "OBSERVATION_BLUETOOTH_PERMISSION_REQUIRED",
        "OBSERVATION_NOTIFICATION_PERMISSION_REQUIRED", "OBSERVATION_NOTIFICATIONS_BLOCKED",
        "OBSERVATION_FGS_START_TIMEOUT", "OBSERVATION_FGS_SECURITY_DENIED",
        "OBSERVATION_FGS_BACKGROUND_START_DENIED", "OBSERVATION_FGS_START_FAILED",
        "OBSERVE_SECURITY_DENIED", "OBSERVE_UNAVAILABLE", "OBSERVATION_SERVICE_DESTROYED",
        "BLUETOOTH_SCAN_PERMISSION_REQUIRED", "BLE_SCAN_ADDRESS_UNAVAILABLE",
        "BLE_SCAN_PERMISSION_REQUIRED", "BLE_SCAN_PERMISSION_REVOKED", "BLE_SCAN_NO_OFFLOADED_FILTER",
        "BLE_SCAN_UNAVAILABLE", "BLE_SCAN_START_FAILED", "BLE_SCAN_STOP_FAILED", "BLE_SCAN_CALLBACK_FAILED",
    )

    fun isAllowedReason(reason: String): Boolean = reason in reasons

    fun encode(summary: BleTrialSummary): String = buildString(768) {
        append("{\"attempt\":").append(summary.attempt)
        append(",\"status\":").append(quoted(summary.status.takeIf { it in statuses }))
        append(",\"reason\":").append(quoted(summary.reason?.takeIf { it in reasons }))
        append(",\"gattStatus\":").append(summary.gattStatus?.toString() ?: "null")
        append(",\"primaryGattStatus\":").append(summary.primaryGattStatus?.toString() ?: "null")
        append(",\"cleanupGattStatus\":").append(summary.cleanupGattStatus?.toString() ?: "null")
        append(",\"firstRxElapsedMs\":").append(summary.firstRxElapsedMs?.coerceAtLeast(0)?.toString() ?: "null")
        append(",\"backgroundConnect\":").append(summary.backgroundConnect)
        append(",\"elapsedMs\":").append(summary.elapsedMs)
        append(",\"connected\":").append(summary.connected)
        append(",\"serviceFound\":").append(summary.serviceFound)
        append(",\"txFound\":").append(summary.txFound)
        append(",\"rxFound\":").append(summary.rxFound)
        append(",\"subscriptionConfirmed\":").append(summary.subscriptionConfirmed)
        append(",\"remoteUnsubscribeConfirmed\":").append(summary.remoteUnsubscribeConfirmed)
        append(",\"disconnectConfirmed\":").append(summary.disconnectConfirmed)
        append(",\"localClosed\":").append(summary.localClosed)
        append(",\"notificationCount\":").append(summary.notificationCount)
        append(",\"leaseRetained\":").append(summary.leaseRetained)
        append(",\"interactive\":").append(summary.interactive)
        append(",\"deviceLocked\":").append(summary.deviceLocked)
        append(",\"batteryPercent\":").append(summary.batteryPercent?.takeIf { it in 0..100 }?.toString() ?: "null")
        append(",\"phaseElapsedMs\":").append(summary.phaseElapsedMs?.toString() ?: "null")
        append('}')
    }

    private fun quoted(value: String?): String = value?.let(FieldJson::quote) ?: "null"
}
