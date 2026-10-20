package com.heytesla.app

/** 마지막 쓰기 예약/제출만 병합한다. receiver의 native 연결·CCCD·RX 누적·부분 결과를 보존한다. */
internal fun TeslaApproachReadSummary.withWriteObservation(write: TeslaApproachReadSummary?): TeslaApproachReadSummary {
    if (write == null) return this
    return copy(
        writeSequence = write.writeSequence, writeType = write.writeType,
        txProperties = write.txProperties, negotiatedMtu = write.negotiatedMtu,
        frameLength = write.frameLength, chunkLength = write.chunkLength, frameOffset = write.frameOffset,
        writePendingBefore = write.writePendingBefore, descriptorPendingBefore = write.descriptorPendingBefore,
        writeRequestedElapsedMs = write.writeRequestedElapsedMs, writeReturnedElapsedMs = write.writeReturnedElapsedMs,
        writeRequestStatus = write.writeRequestStatus, writeException = write.writeException,
        writeCallbackStatus = write.writeCallbackStatus, writeCallbackElapsedMs = write.writeCallbackElapsedMs,
        writeDispatchQueued = write.writeDispatchQueued, writeDispatchElapsedMs = write.writeDispatchElapsedMs,
        writeBusyRetryCount = write.writeBusyRetryCount,
    )
}

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
