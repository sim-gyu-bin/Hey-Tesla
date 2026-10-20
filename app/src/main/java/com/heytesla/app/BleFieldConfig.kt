package com.heytesla.app

enum class BleScanFilterMode { ASSOCIATION_ADDRESS, VEHICLE_NAME }

/** 사용자가 시작 전에 선택하는 RAM 전용 비교 조건. 실행 중 변경하거나 자동 복원하지 않는다. */
data class BleFieldConfig(
    val observationOnly: Boolean = false,
    val supplementalScan: Boolean = false,
    val btAssist: Boolean = false,
    val backgroundConnect: Boolean = false,
    val retryEnabled: Boolean = false,
    val scanFilterMode: BleScanFilterMode = BleScanFilterMode.ASSOCIATION_ADDRESS,
    val authenticatedReadOnly: Boolean = false,
) {
    /** 승인된 두 이름 모드만 허용한다. 잘못된 조합을 더 안전한 값으로 조용히 보정하지 않는다. */
    internal fun safetyBlockedReason(): String? = when {
        authenticatedReadOnly && (scanFilterMode != BleScanFilterMode.VEHICLE_NAME ||
            observationOnly || !supplementalScan || btAssist || backgroundConnect || retryEnabled) ->
            "BLE_AUTHENTICATED_READ_OPTIONS_INVALID"
        scanFilterMode == BleScanFilterMode.VEHICLE_NAME && !authenticatedReadOnly &&
            (!observationOnly || !supplementalScan || btAssist || backgroundConnect || retryEnabled) ->
            "BLE_NAME_DETECTION_OPTIONS_INVALID"
        else -> null
    }

    companion object {
        val BASELINE = BleFieldConfig()
        val IMPROVED = BleFieldConfig(supplementalScan = true, btAssist = true, retryEnabled = true)
        val VEHICLE_NAME_DETECTION = BleFieldConfig(
            observationOnly = true,
            supplementalScan = true,
            scanFilterMode = BleScanFilterMode.VEHICLE_NAME,
        )
        val VEHICLE_NAME_AUTHENTICATED_READ = BleFieldConfig(
            supplementalScan = true,
            scanFilterMode = BleScanFilterMode.VEHICLE_NAME,
            authenticatedReadOnly = true,
        )
    }
}
