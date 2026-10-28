package com.heytesla.app.vehicle.ble

import com.heytesla.app.diagnostics.TeslaApproachReadSummary

/** 해당 native 제출 객체의 첫 callback만 붙인다. source/TX/pending 검사는 호출자가 수행한다. */
internal fun completedNativeWriteObservation(
    lastActual: TeslaApproachReadSummary?,
    submitted: TeslaApproachReadSummary,
    callbackStatus: Int,
    callbackElapsedMs: Long,
): TeslaApproachReadSummary? {
    if (lastActual !== submitted || submitted.writeDispatchQueued != false ||
        submitted.writeRequestStatus != 0 || submitted.writeCallbackElapsedMs != null) return null
    return submitted.copy(
        writeCallbackStatus = callbackStatus,
        writeCallbackElapsedMs = callbackElapsedMs,
    )
}

/** 봉인된 예약은 마지막 실제 쓰기를 지우지 않는다. 실제 쓰기가 없으면 미제출 관측만 보존한다. */
internal fun sealedWriteObservation(
    lastActual: TeslaApproachReadSummary?,
    queued: TeslaApproachReadSummary?,
): TeslaApproachReadSummary? {
    val observed = lastActual ?: queued ?: return null
    return if (observed.writeDispatchQueued == false) observed else observed.copy(writeDispatchQueued = false)
}
