package com.heytesla.app.vehicle.detection

/** 현재 서비스의 고정 로그 메타데이터. 실행 옵션을 선택하거나 복원하지 않는다. */
object BleFieldConfig {
    const val observationOnly = false
    const val supplementalScan = true
    const val btAssist = false
    const val backgroundConnect = false
    const val retryEnabled = false
    const val advertisedNameFilter = true
    const val authenticatedReadOnly = false
}
