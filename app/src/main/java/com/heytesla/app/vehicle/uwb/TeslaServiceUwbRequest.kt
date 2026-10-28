package com.heytesla.app.vehicle.uwb

import com.heytesla.app.ObservationService
import com.heytesla.app.vehicle.detection.BleFieldTrialPolicy

import android.bluetooth.BluetoothDevice

/** 실제 서비스의 이번 광고·회차에만 묶인 RAM 소유권. BODY/DRIVE 읽기 요청이 아니다. */
internal class TeslaServiceUwbRequest(
    val owner: ObservationService,
    val fieldToken: String,
    val candidate: Long,
    val attempt: Long,
    val associationId: Int,
    device: BluetoothDevice,
    val sourceTimestampNanos: Long,
    val deadline: Long,
) {
    var device: BluetoothDevice? = device
        private set
    var ranging = false
        private set
    val sourceElapsedMs: Long get() = sourceTimestampNanos / 1_000_000L

    fun markRanging() { ranging = true }
    fun clear() { device = null }
    override fun toString(): String = "TeslaServiceUwbRequest(redacted)"
}

/** SDK 접수값은 재시도마다 초기화된다. 광고 입장은 실제 반환 handle 소유 뒤에만 단조 전이한다. */
internal class TeslaServiceUwbGattAdmission {
    var requireFresh: Boolean = true
        private set

    fun returnedHandleOwned(returned: Any?, owned: Any?) {
        if (returned != null && returned === owned) requireFresh = false
    }
}

/** 서비스 입장·진행에서 공유하는 정책. admission은 freshness만 면제하고 다른 경계는 유지한다. */
internal inline fun serviceUwbReadinessReason(
    ownerArmed: () -> Boolean,
    scanReady: () -> Boolean,
    initialBudgetExpired: () -> Boolean,
    deviceAvailable: () -> Boolean,
    sourceTimestampNanos: Long,
    now: () -> Long,
    requireFresh: Boolean,
    rangingPermission: () -> Boolean,
    associationMatches: () -> Boolean,
    targetReason: () -> String?,
    fieldReason: () -> String?,
): String? {
    if (!ownerArmed()) return "FIELD_OWNER_NOT_ARMED"
    if (!scanReady()) return "BLE_SCAN_FGS_NOT_RUNNING"
    if (initialBudgetExpired()) return "DEADLINE_EXPIRED"
    if (!deviceAvailable() || sourceTimestampNanos <= 0) return "SCAN_DEVICE_UNAVAILABLE"
    if (requireFresh) {
        val sourceElapsedMs = sourceTimestampNanos / 1_000_000L
        val observedNow = now()
        if (sourceElapsedMs > observedNow ||
            observedNow - sourceElapsedMs > BleFieldTrialPolicy.READ_START_FRESH_MS
        ) return "SCAN_RESULT_STALE"
    }
    if (!rangingPermission()) return "RANGING_PERMISSION_REQUIRED"
    if (!associationMatches()) return "ASSOCIATION_CHANGED"
    targetReason()?.let { return it }
    return fieldReason()
}
