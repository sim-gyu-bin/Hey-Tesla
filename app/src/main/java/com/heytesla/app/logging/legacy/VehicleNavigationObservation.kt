package com.heytesla.app.logging.legacy

/** Value-free evidence only. No coordinate, heading, native timestamp, identifier or free text fits here. */
data class VehicleNavigationObservation(
    val kind: VehicleNavigationObservationKind,
    val reason: VehicleNavigationObservationReason? = null,
    val candidate: Long? = null,
    val attempt: Long? = null,
    val fineLocationGranted: Boolean? = null,
    val backgroundLocationGranted: Boolean? = null,
    val locationEnabled: Boolean? = null,
    val foregroundArmed: Boolean? = null,
    val collecting: Boolean? = null,
    val locationUpdatesRegistered: Boolean? = null,
    val headingSensorRegistered: Boolean? = null,
    val cleanupSucceeded: Boolean? = null,
    val status: VehicleNavigationObservationStatus? = null,
    val requestUuidMatches: Boolean? = null,
    val toRouteMatches: Boolean? = null,
    val disposition: VehicleNavigationRxDisposition? = null,
    val vehicleFixPresent: Boolean? = null,
    val authEvidence: VehicleNavigationAuthEvidence? = null,
) {
    companion object {
        const val EVENT = "VEHICLE_NAVIGATION_OBSERVATION"
    }
}

enum class VehicleNavigationObservationKind {
    ADMISSION, FOREGROUND_ARMED, FOREGROUND_STOPPED, SOURCE_STARTED, SOURCE_STOPPED,
    PHONE_LOCATION_FIRST_VALID, HEADING_FIRST_VALID, PHONE_INPUT_UNAVAILABLE, DISPLAY_STATUS_CHANGED,
    SIDECAR_STARTED, SIDECAR_SKIPPED, HANDSHAKE_TX_COMPLETE, QUERY_TX_COMPLETE,
    HANDSHAKE_ACCEPTED, SIDECAR_RX, RESULT, TERMINAL,
}

enum class VehicleNavigationObservationReason {
    UNKNOWN,
    LOCATION_PERMISSION_REQUIRED, LOCATION_DISABLED, LOCATION_UNAVAILABLE,
    HEADING_UNSUPPORTED, HEADING_UNRELIABLE, SOURCE_START_FAILED, CLEANUP_FAILED,
    FOREGROUND_START_FAILED, PRIMARY_NOT_AUTHENTICATED, COORDINATES_NOT_WANTED,
    CONVERSATION_UNAVAILABLE, OPERATION_DEADLINE, OWNER_ENDED, SESSION_CLOSED,
    ATTEMPT_ENDED, VEHICLE_FIX_EXPIRED, CLOCK_INVALID, OPERATION_ENDED_BEFORE_START,
    VEHICLE_FIX_TIME_INVALID, LOCATION_STATE_TIME_INVALID,
    LOCATION_PROTOCOL_INVALID, LOCATION_AUTHENTICATION_REJECTED, LOCATION_OWNER_UNAVAILABLE,
    LOCATION_TIMEOUT, LOCATION_FIX_TIME_MISSING, LOCATION_FIX_TIME_OVERFLOW, LOCATION_FIX_TIME_FUTURE,
    LOCATION_RECEIPT_TIME_INVALID, LOCATION_COORDINATES_MISSING, LOCATION_ESTIMATED_INVALID,
    LOCATION_ESTIMATED_VALIDITY_MISSING, LOCATION_NATIVE_PAIR_INCOMPLETE,
    LOCATION_COORDINATE_SYSTEM_UNSUPPORTED, LOCATION_NATIVE_UNSUPPORTED, LOCATION_COORDINATES_INVALID,
    LOCATION_PAIR_INCOMPLETE, LOCATION_RAW_COORDINATES_INVALID, LOCATION_RAW_PAIR_INCOMPLETE,
    LOCATION_STATE_MISSING, LOCATION_REQUEST_REJECTED;

    companion object {
        /** Unknown protocol text is discarded, never carried into a log or a presentation. */
        fun fromCode(code: String?): VehicleNavigationObservationReason? =
            if (code == null) null else entries.firstOrNull { it.name == code } ?: UNKNOWN
    }
}

/** Mirrors presentation status, not the presentation's distance, angle, age or accuracy values. */
enum class VehicleNavigationObservationStatus {
    UNKNOWN, OFF, START_REQUIRED, STARTING, LOCATION_PERMISSION_REQUIRED, LOCATION_DISABLED,
    LOCATION_UNAVAILABLE, HEADING_UNSUPPORTED, HEADING_UNRELIABLE, FOREGROUND_FAILED, CLEANUP_FAILED,
    VEHICLE_LOCATION_UNAVAILABLE, WAITING_FOR_VEHICLE_LOCATION, WAITING_FOR_PHONE_LOCATION,
    VEHICLE_LOCATION_STALE, PHONE_LOCATION_STALE, PHONE_LOCATION_INACCURATE, HEADING_UNAVAILABLE,
    VEHICLE_LOCATION_TIME_INVALID,
    HEADING_STALE, NEARBY, DISTANCE_ONLY, DIRECTION_AVAILABLE, CLOCK_INVALID, CLOCK_SAMPLE_DELAYED,
}

enum class VehicleNavigationRxDisposition {
    UNKNOWN, RESPONSE_INVALID, RESPONSE_WAITING, RESPONSE_REJECTED, RESPONSE_BEFORE_START, ACK_WAITING,
    SESSION_INFO_INVALID, SESSION_INFO_AUTH_REJECTED, SESSION_READY, DOMAIN_MISMATCH,
    REQUEST_UUID_MISMATCH, ROUTE_MISMATCH, STATUS_VERIFIED, STATUS_INCOMPLETE;

    companion object {
        fun fromCode(code: String?): VehicleNavigationRxDisposition =
            entries.firstOrNull { it.name == code } ?: UNKNOWN
    }
}

/** The same evidence names as the protocol; none denotes physical success or key ownership. */
enum class VehicleNavigationAuthEvidence {
    UNKNOWN, ACKNOWLEDGED, REQUEST_REJECTED, KEY_NOT_PAIRED_REPORTED, OPERATION_REJECTED,
    UNEXPECTED_PAYLOAD, SESSION_STATUS_UNSUPPORTED, SIGNATURE_MISSING, TAG_MISSING,
    TAG_LENGTH_INVALID, PARAMETERS_INVALID, HMAC_MISMATCH, SESSION_INFO_INVALID;

    companion object {
        fun fromCode(code: String?): VehicleNavigationAuthEvidence? =
            if (code == null) null else entries.firstOrNull { it.name == code } ?: UNKNOWN
    }
}

/** Typed enums and Booleans only; invalid existing candidate/attempt numbers remain unknown. */
object VehicleNavigationObservationJson {
    fun encode(value: VehicleNavigationObservation,
        disposition: VehicleNavigationRxDisposition? = value.disposition,
        reason: VehicleNavigationObservationReason? = value.reason,
        status: VehicleNavigationObservationStatus? = value.status): String = buildString(640) {
        append("{\"kind\":").appendEnumValue(value.kind)
        append(",\"reason\":").appendEnumValue(reason)
        append(",\"candidate\":").append(value.candidate?.takeIf { it >= 0L }?.toString() ?: "null")
        append(",\"attempt\":").append(value.attempt?.takeIf { it >= 0L }?.toString() ?: "null")
        append(",\"fineLocationGranted\":").append(value.fineLocationGranted)
        append(",\"backgroundLocationGranted\":").append(value.backgroundLocationGranted)
        append(",\"locationEnabled\":").append(value.locationEnabled)
        append(",\"foregroundArmed\":").append(value.foregroundArmed)
        append(",\"collecting\":").append(value.collecting)
        append(",\"locationUpdatesRegistered\":").append(value.locationUpdatesRegistered)
        append(",\"headingSensorRegistered\":").append(value.headingSensorRegistered)
        append(",\"cleanupSucceeded\":").append(value.cleanupSucceeded)
        append(",\"status\":").appendEnumValue(status)
        append(",\"requestUuidMatches\":").append(value.requestUuidMatches)
        append(",\"toRouteMatches\":").append(value.toRouteMatches)
        append(",\"disposition\":").appendEnumValue(disposition)
        append(",\"vehicleFixPresent\":").append(value.vehicleFixPresent)
        append(",\"authEvidence\":").appendEnumValue(value.authEvidence)
        append('}')
    }

    private fun StringBuilder.appendEnumValue(value: Enum<*>?): StringBuilder =
        if (value == null) append("null") else append('"').append(value.name).append('"')
}
