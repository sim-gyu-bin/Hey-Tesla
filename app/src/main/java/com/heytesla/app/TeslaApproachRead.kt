package com.heytesla.app

import android.bluetooth.BluetoothDevice

/** 현재 광고에서만 얻는 RAM 대상. Intent/로그/toString에 기기·VIN을 전달하지 않는다. */
internal class TeslaApproachReadRequest(
    val owner: ObservationService,
    val fieldToken: String,
    val candidate: Long,
    val attempt: Long,
    val associationId: Int,
    device: BluetoothDevice,
    val sourceTimestampNanos: Long,
    val deadline: Long,
    val advertisementConnectable: Boolean? = null,
) {
    var device: BluetoothDevice? = device
        private set
    val sourceElapsedMs: Long get() = sourceTimestampNanos / 1_000_000L
    fun clear() { device = null }
    override fun toString(): String = "TeslaApproachReadRequest(redacted)"
}

/** 단일 연결의 BODY → DRIVE 대화. 세션/route/UUID/epoch를 재사용하지 않는다. */
internal class TeslaApproachReadSequence(
    private var credential: TeslaBleCredential?,
    vin: ByteArray,
    candidate: Long,
    attempt: Long,
    private val now: () -> Long,
    private val verified: (String, TeslaApproachReadSummary) -> Unit,
) : AutoCloseable {
    private val vin = vin // 호출자가 회차 끝까지 소유하는 RAM snapshot. 불필요한 두 번째 복사 없음.
    var query = TeslaBleQuery.BODY_STATUS
        private set
    var summary = TeslaApproachReadSummary(candidate = candidate, attempt = attempt,
        stage = "PREPARING_KEY", query = query.name, outcome = "PENDING", leaseRetained = true,
        rxNotificationCount = 0L, rxFrameCount = 0L, rxProtocolCount = 0L)
        private set
    private var conversation: TeslaBleConversation? = null
    private var closed = false
    var cleanupFailed = false
        private set

    fun start(): TeslaBleStep {
        check(!closed && conversation == null)
        conversation = newConversation()
        return checkNotNull(conversation).start(false)
    }

    fun recordRxNotification(receivedAt: Long) {
        check(!closed)
        summary = summary.copy(rxNotificationCount = checkNotNull(summary.rxNotificationCount) + 1L,
            lastRxElapsedMs = receivedAt)
    }

    fun recordRxFrame(receivedAt: Long) {
        check(!closed)
        summary = summary.copy(rxFrameCount = checkNotNull(summary.rxFrameCount) + 1L,
            lastFrameElapsedMs = receivedAt)
    }

    private fun newConversation(): TeslaBleConversation =
        TeslaBleConversation(checkNotNull(credential), vin, query,
            onResponseObservation = { observation ->
                summary = summary.copy(responseDisposition = observation.disposition, responseQuery = query.name,
                    responseExpectedDomain = observation.expectedDomain,
                    responseFromPresent = observation.fromPresent, responseFromKind = observation.fromKind,
                    responseFromDomain = observation.fromDomain, responseToPresent = observation.toPresent,
                    responseToKind = observation.toKind, responsePayloadKind = observation.payloadKind,
                    responseDomainMismatchReason = observation.domainMismatchReason,
                    responseRequestUuidPresent = observation.requestUuidPresent,
                    responseRequestUuidMatches = observation.requestUuidMatches,
                    responseToRouteMatches = observation.toRouteMatches,
                    responseSignedStatusPresent = observation.signedStatusPresent,
                    responseSignedMessageFault = observation.signedMessageFault,
                    responseOperationStatus = observation.operationStatus)
            }, now = now)

    fun receive(payload: ByteArray, receivedAt: Long): TeslaBleStep {
        check(!closed)
        val active = checkNotNull(conversation)
        summary = summary.copy(rxProtocolCount = checkNotNull(summary.rxProtocolCount) + 1L)
        val step = active.receive(payload, receivedAt)
        if (step !is TeslaBleStep.Complete) return step
        if (query == TeslaBleQuery.BODY_STATUS) {
            summary = summary.copy(bodyVerified = true, bodyReceivedElapsedMs = receivedAt,
                lockState = step.status.lockState, frontTrunkState = step.status.frontTrunkState,
                bodyVehicleSleepStatus = step.status.bodyVehicleSleepStatus)
            verified("TESLA_APPROACH_BODY_VERIFIED", summary)
            // BODY의 무결성 검증 결과는 이후 DRIVE/close 실패에도 보존한다.
            try { checkNotNull(conversation).close() } catch (failure: Exception) {
                cleanupFailed = true
                throw failure
            }
            conversation = null
            query = TeslaBleQuery.DRIVE_STATE
            summary = summary.copy(query = query.name, stage = "HANDSHAKING")
            conversation = newConversation()
            return checkNotNull(conversation).start(false)
        }
        val drive = checkNotNull(step.status.driveState)
        summary = summary.copy(driveVerified = true, driveReceivedElapsedMs = drive.receivedAtElapsedRealtime,
            driveSourceWallMs = drive.sourceTimestampEpochMillis, gear = drive.gear.name)
        verified("TESLA_APPROACH_DRIVE_VERIFIED", summary)
        return step
    }

    fun snapshot(stage: String, outcome: String, reason: String?, localClosed: Boolean = false,
        leaseRetained: Boolean = true): TeslaApproachReadSummary {
        summary = summary.copy(stage = stage, query = query.name, outcome = outcome, reason = reason,
            localClosed = localClosed, leaseRetained = leaseRetained)
        return summary
    }

    override fun close() {
        if (closed) return
        // close 예외는 호출자에서 latch한다. 성공 전 closed로 바꾸지 않는다.
        try { conversation?.close() } finally {
            credential = null
            vin.fill(0)
        }
        conversation = null
        closed = true
    }
}
