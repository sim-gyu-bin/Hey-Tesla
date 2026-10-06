package com.heytesla.app

/** 사용자가 시작 전에 선택하는 RAM 전용 비교 조건. 실행 중 변경하거나 자동 복원하지 않는다. */
data class BleFieldConfig(
    val observationOnly: Boolean = false,
    val supplementalScan: Boolean = false,
    val btAssist: Boolean = false,
    val backgroundConnect: Boolean = false,
    val retryEnabled: Boolean = false,
) {
    companion object {
        val BASELINE = BleFieldConfig()
        val IMPROVED = BleFieldConfig(supplementalScan = true, btAssist = true, retryEnabled = true)
    }
}
