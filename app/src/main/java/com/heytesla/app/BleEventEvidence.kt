package com.heytesla.app

enum class BleEvidenceKind {
    PRESENCE_RECEIVED, PRESENCE_DISPATCHED, PRESENCE_REJECTED,
    CANDIDATE_SIGNAL, CANDIDATE_MERGED, CANDIDATE_EXPIRED, SIGNAL_SHADOWED, SIGNAL_SELF_SUPPRESSED,
    SCAN_STARTED, SCAN_STOPPED, SCAN_FAILED, SCAN_MATCH,
    RETRY_SCHEDULED, RETRY_SUPPRESSED,
    GATT_REQUEST, GATT_CALLBACK, GATT_CALLBACK_IGNORED, GATT_FIRST_RX,
}

enum class BleEvidenceSignal { CDM_BLE_APPEARED, CDM_BLE_DISAPPEARED, BT_CONNECTED, BT_DISCONNECTED, FILTERED_SCAN }

enum class BleEvidenceAction {
    CONNECT, DISCOVER, PROFILE, NOTIFICATION_ON, NOTIFICATION_OFF, CCCD_ON, CCCD_OFF, CCCD_READ,
    DISCONNECT, CLOSE, CONNECTION_CALLBACK, SERVICES_CALLBACK, DESCRIPTOR_WRITE_CALLBACK,
    DESCRIPTOR_READ_CALLBACK, RX_CALLBACK,
}

enum class BleEvidenceGate {
    UNARMED, STOPPING, WRONG_ASSOCIATION, NOT_OBSERVING, OWNER_MISSING, UNSUPPORTED,
    DUPLICATE, LOCAL_GATT, RECENT_LOCAL_GATT, COOLDOWN, STALE, PERMISSION, UNAVAILABLE,
    NO_OFFLOADED_FILTER, BUDGET, SUCCESS, BLOCKED, CLEANUP_FAILED, OBSERVATION_ONLY,
}

enum class BleEvidenceResult { ACCEPTED, REJECTED, COMPLETED, EXCEPTION }
enum class BleEvidencePhase { CONNECTING, DISCOVERING, SUBSCRIBING, OBSERVING, CLEANING_UP }

/** 고정 enum과 수치만 기록한다. 주소·이름·association ID·패킷 원문 필드가 없다. */
data class BleEventEvidence(
    val kind: BleEvidenceKind,
    val signal: BleEvidenceSignal? = null,
    val action: BleEvidenceAction? = null,
    val gate: BleEvidenceGate? = null,
    val result: BleEvidenceResult? = null,
    val phase: BleEvidencePhase? = null,
    val candidate: Long? = null,
    val attempt: Long? = null,
    val receivedElapsedMs: Long? = null,
    val queueDelayMs: Long? = null,
    val accepted: Boolean? = null,
    val gattStatus: Int? = null,
    val scanFailureCode: Int? = null,
    val rssi: Int? = null,
    val localGattActive: Boolean? = null,
    val localGattRecent: Boolean? = null,
    val scanRunning: Boolean? = null,
) {
    internal fun encode(): String = buildString(384) {
        append("{\"kind\":").append(FieldJson.quote(kind.name))
        fun field(name: String, value: String?) { append(",\"").append(name).append("\":").append(value ?: "null") }
        field("signal", signal?.name?.let(FieldJson::quote))
        field("action", action?.name?.let(FieldJson::quote))
        field("gate", gate?.name?.let(FieldJson::quote))
        field("result", result?.name?.let(FieldJson::quote))
        field("phase", phase?.name?.let(FieldJson::quote))
        field("candidate", candidate?.toString())
        field("attempt", attempt?.toString())
        field("receivedElapsedMs", receivedElapsedMs?.toString())
        field("queueDelayMs", queueDelayMs?.coerceAtLeast(0)?.toString())
        field("accepted", accepted?.toString())
        field("gattStatus", gattStatus?.toString())
        field("scanFailureCode", scanFailureCode?.toString())
        field("rssi", rssi?.takeIf { it in -127..20 }?.toString())
        field("localGattActive", localGattActive?.toString())
        field("localGattRecent", localGattRecent?.toString())
        field("scanRunning", scanRunning?.toString())
        append('}')
    }
}
