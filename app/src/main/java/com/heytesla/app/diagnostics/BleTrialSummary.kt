package com.heytesla.app.diagnostics

import com.heytesla.app.logging.FieldJson

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
        "VIN_FORMAT_INVALID", "BLE_NAME_DETECTION_OPTIONS_INVALID", "BLE_SCAN_NAME_UNAVAILABLE",
        "BLE_SCAN_NO_OFFLOADED_BATCH", "BLE_SCAN_PENDING_INTENT_FAILED",
        "BLE_SCAN_DELIVERY_FAILED", "BLE_SCAN_ORPHAN_CLEANUP_FAILED",
        "BLE_LOCATION_PERMISSION_REQUIRED", "BLE_LOCATION_DISABLED",
        "BLE_SCAN_LOCATION_PERMISSION_REQUIRED", "BLE_SCAN_LOCATION_SERVICES_DISABLED",
        "BLE_SCAN_FGS_FALLBACK", "BLE_SCAN_FGS_NOT_RUNNING",
        "BLE_DETECTION_PREFERENCE_FAILED", "BLE_DETECTION_USER_STOP_REQUIRES_START",
        "BLE_AUTHENTICATED_READ_OPTIONS_INVALID", "FRESH_SCAN_REQUIRED", "SCAN_RESULT_STALE",
        "SCAN_DEVICE_UNAVAILABLE", "SCAN_PAUSE_FAILED", "SCAN_RESUME_FAILED",
        "FIELD_OWNER_LOST", "APPROACH_EXPIRED", "CANDIDATE_WITHDRAWN", "ATTEMPT_SUPERSEDED",
        "EXISTING_KEY_UNAVAILABLE", "EXISTING_KEY_INVALID", "KEY_PREPARATION_FAILED",
        "BODY_STATUS_VERIFIED", "DRIVE_STATE_VERIFIED", "AUTHENTICATED_READ_COMPLETE",
        "BODY_READ_FAILED", "DRIVE_READ_FAILED", "BODY_READ_TIMEOUT", "DRIVE_READ_TIMEOUT",
        "READ_DEADLINE_EXPIRED", "DEADLINE_EXPIRED", "AUTHENTICATION_FAILED", "AUTHENTICATION_TIMEOUT",
        "ASSOCIATION_CHANGED", "READINESS_CHECK_FAILED",
        "GATT_CONNECT_REJECTED", "GATT_CONNECT_PERMISSION_DENIED", "GATT_CONNECT_FAILED",
        "GATT_CONNECTION_LOST", "GATT_DISCOVERY_REJECTED", "GATT_DISCOVERY_FAILED",
        "TESLA_GATT_PROFILE_UNSUPPORTED", "GATT_MTU_REJECTED", "GATT_MTU_FAILED",
        "GATT_NOTIFICATION_REJECTED", "GATT_SUBSCRIPTION_REJECTED", "GATT_SUBSCRIPTION_FAILED",
        "PROTOCOL_START_FAILED", "GATT_WRITE_FAILED", "GATT_WRITE_REJECTED",
        "RX_FRAME_INVALID", "RX_BUFFER_LIMIT", "GATT_CALLBACK_FAILED", "TX_FRAME_INVALID",
        "PROTOCOL_RESPONSE_INVALID", "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED",
        "VIN_LOADING", "VIN_NOT_REGISTERED", "VIN_SAVING", "VIN_RESTORE_FAILED", "VIN_SAVE_FAILED",
        "GATT_WRITE_EXCEPTION",
        "GATT_SERVICES_INVALIDATED",
        "BLE_FIELD_WAKE_LOCK_UNAVAILABLE",
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
