package com.heytesla.app.diagnostics

import com.heytesla.app.logging.FieldJson

/**
 * 현재 접근 회차의 비민감 읽기 관측. 원문·주소·키·VIN은 담지 않는다.
 * 수신 시각은 monotonic, DRIVE 원 시각은 차량 응답의 wall 시각이며 서로 대체하지 않는다.
 * null은 미관측이다. BODY만 검증된 결과나 정리 실패는 전체 성공이 아니다.
 * RX count는 현재 op 누적이며 초기화 전 null, 초기화 후 실제 무수신은 0이다.
 * lastRx/lastFrame은 native 진입/완성 fragment 시각이다. responseQuery는 마지막 실제
 * protocol 처리 query로, BODY→DRIVE 전환 뒤 현재 query와 다를 수 있다.
 * connection/descriptor는 동일 op의 마지막 native 관측이다. 구독 callback 성공은 무선 완료 보증이 아니다.
 * writeDispatchQueued=true는 예약만 의미한다. 호출·봉인 후 false이며, 실제 호출 시각은 dispatch에만 남는다.
 */
data class TeslaApproachReadSummary(
    val candidate: Long? = null,
    val attempt: Long? = null,
    val stage: String = "IDLE",
    val query: String? = null,
    val outcome: String = "NOT_SENT",
    val reason: String? = null,
    val bodyVerified: Boolean = false,
    val driveVerified: Boolean = false,
    val bodyReceivedElapsedMs: Long? = null,
    val driveReceivedElapsedMs: Long? = null,
    val driveSourceWallMs: Long? = null,
    val gear: String? = null,
    val lockState: Int? = null,
    val frontTrunkState: Int? = null,
    val bodyVehicleSleepStatus: Int? = null,
    val localClosed: Boolean = false,
    val leaseRetained: Boolean = false,
    val writeSequence: Long? = null,
    val writeBusyRetryCount: Int? = null,
    val writeType: Int? = null,
    val txProperties: Int? = null,
    val negotiatedMtu: Int? = null,
    val frameLength: Int? = null,
    val chunkLength: Int? = null,
    val frameOffset: Int? = null,
    val writePendingBefore: Boolean? = null,
    val descriptorPendingBefore: Boolean? = null,
    val writeRequestedElapsedMs: Long? = null,
    val writeReturnedElapsedMs: Long? = null,
    val writeRequestStatus: Int? = null,
    val writeException: String? = null,
    val writeCallbackStatus: Int? = null,
    val writeCallbackElapsedMs: Long? = null,
    val rxNotificationCount: Long? = null,
    val rxFrameCount: Long? = null,
    val rxProtocolCount: Long? = null,
    val lastRxElapsedMs: Long? = null,
    val lastFrameElapsedMs: Long? = null,
    val responseDisposition: String? = null,
    val responseQuery: String? = null,
    val gattConnectionStatus: Int? = null,
    val gattConnectionState: Int? = null,
    val gattConnectionElapsedMs: Long? = null,
    val gattDescriptorRequestStatus: Int? = null,
    val gattDescriptorCallbackStatus: Int? = null,
    val gattDescriptorCallbackElapsedMs: Long? = null,
    val writeDispatchQueued: Boolean? = null,
    val writeDispatchElapsedMs: Long? = null,
    val responseExpectedDomain: Int? = null,
    val responseFromPresent: Boolean? = null,
    val responseFromKind: String? = null,
    val responseFromDomain: Int? = null,
    val responseToPresent: Boolean? = null,
    val responseToKind: String? = null,
    val responsePayloadKind: String? = null,
    val responseDomainMismatchReason: String? = null,
    val advertisementConnectable: Boolean? = null,
    val gattConnectIssuedElapsedMs: Long? = null,
    val gattConnectReturnedElapsedMs: Long? = null,
    // 이 handle을 생성한 connectGatt의 인자다. RECONNECT의 connect()에는 autoConnect 인자가 없다.
    val gattAutoConnect: Boolean? = null,
    val gattConnectAction: String? = null,
    // CREATE: 반환 handle 존재, RECONNECT: 기존 handle.connect() 반환값. 연결/인증 성공이 아니다.
    val gattConnectAccepted: Boolean? = null,
    val advertisementAgeAtConnectMs: Long? = null,
    val gattTransport: String? = null,
    val connectSettleStartedElapsedMs: Long? = null,
    val connectSettleCompletedElapsedMs: Long? = null,
    val responseRequestUuidPresent: Boolean? = null,
    val responseRequestUuidMatches: Boolean? = null,
    val responseToRouteMatches: Boolean? = null,
    val responseSignedStatusPresent: Boolean? = null,
    val responseSignedMessageFault: Int? = null,
    val responseOperationStatus: Int? = null,
)

/** enum/고정 사유만 저장한다. 불명 문자열·음수 시각·유효하지 않은 회차는 해당 필드만 null이다. */
internal object TeslaApproachReadSummaryJson {
    private val stages = setOf(
        "IDLE", "PAUSING_SCAN", "PREPARING_KEY", "CONNECTING", "DISCOVERING",
        "NEGOTIATING_MTU", "SUBSCRIBING", "HANDSHAKING", "READING_STATUS",
        "CLEANING_UP", "RESUMING_SCAN", "COMPLETE", "BLOCKED", "CANCELED",
        "TIMED_OUT", "FAILED", "CLEANUP_FAILED",
    )
    private val queries = setOf("BODY_STATUS", "DRIVE_STATE")
    private val outcomes = setOf("NOT_SENT", "PENDING", "CANCELED", "FAILED", "UNKNOWN", "VERIFIED_STATUS", "PARTIAL")
    private val gears = setOf("P", "R", "N", "D", "UNKNOWN")
    private val writeExceptions = setOf(
        "SECURITY_EXCEPTION", "ILLEGAL_ARGUMENT_EXCEPTION", "ILLEGAL_STATE_EXCEPTION",
        "REMOTE_EXCEPTION", "RUNTIME_EXCEPTION", "OTHER_EXCEPTION",
    )
    private val responseDispositions = setOf(
        "DOMAIN_MISMATCH", "ROUTE_MISMATCH", "REQUEST_UUID_MISMATCH", "ACK_WAITING",
        "SESSION_INFO_INVALID", "SESSION_INFO_AUTH_REJECTED", "SESSION_READY",
        "STATUS_VERIFIED", "STATUS_INCOMPLETE", "RESPONSE_REJECTED",
        "RESPONSE_INVALID", "RESPONSE_WAITING",
        "FIRA_REQUEST", "FIRA_STOP", "FIRA_UNRECOGNIZED", "FIRA_INVALID",
    )
    private val destinationKinds = setOf("DOMAIN", "ROUTING_ADDRESS", "UNSET", "UNKNOWN")
    private val payloadKinds = setOf("SESSION_INFO", "SESSION_INFO_REQUEST", "PROTOBUF_MESSAGE_AS_BYTES", "UNSET", "UNKNOWN")
    private val domainMismatchReasons = setOf("FROM_MISSING", "FROM_NOT_DOMAIN", "DOMAIN_DIFFERENT")

    fun encode(summary: TeslaApproachReadSummary): String = buildString(2048) {
        append("{\"candidate\":").append(summary.candidate?.takeIf { it > 0 })
        append(",\"attempt\":").append(summary.attempt?.takeIf { it > 0 })
        append(",\"stage\":").append(quoted(summary.stage.takeIf { it in stages }))
        append(",\"query\":").append(quoted(summary.query?.takeIf { it in queries }))
        append(",\"outcome\":").append(quoted(summary.outcome.takeIf { it in outcomes }))
        append(",\"reason\":").append(quoted(summary.reason?.takeIf(BleTrialSummaryJson::isAllowedReason)))
        append(",\"bodyVerified\":").append(summary.bodyVerified)
        append(",\"driveVerified\":").append(summary.driveVerified)
        append(",\"bodyReceivedElapsedMs\":").append(summary.bodyReceivedElapsedMs?.takeIf { it >= 0 })
        append(",\"driveReceivedElapsedMs\":").append(summary.driveReceivedElapsedMs?.takeIf { it >= 0 })
        append(",\"driveSourceWallMs\":").append(summary.driveSourceWallMs?.takeIf { it >= 0 })
        append(",\"gear\":").append(quoted(summary.gear?.takeIf { it in gears }))
        append(",\"lockState\":").append(summary.lockState)
        append(",\"frontTrunkState\":").append(summary.frontTrunkState)
        append(",\"bodyVehicleSleepStatus\":").append(summary.bodyVehicleSleepStatus?.takeIf {
            summary.bodyVerified && summary.bodyReceivedElapsedMs?.let { at -> at >= 0 } == true && it >= 0
        })
        append(",\"localClosed\":").append(summary.localClosed)
        append(",\"leaseRetained\":").append(summary.leaseRetained)
        append(",\"writeSequence\":").append(summary.writeSequence?.takeIf { it > 0 })
        append(",\"writeBusyRetryCount\":").append(summary.writeBusyRetryCount?.takeIf { it in 0..2 })
        append(",\"writeType\":").append(summary.writeType)
        append(",\"txProperties\":").append(summary.txProperties)
        append(",\"negotiatedMtu\":").append(summary.negotiatedMtu?.takeIf { it in 23..517 })
        append(",\"frameLength\":").append(summary.frameLength?.takeIf { it >= 0 })
        append(",\"chunkLength\":").append(summary.chunkLength?.takeIf { it >= 0 })
        append(",\"frameOffset\":").append(summary.frameOffset?.takeIf { it >= 0 })
        append(",\"writePendingBefore\":").append(summary.writePendingBefore)
        append(",\"descriptorPendingBefore\":").append(summary.descriptorPendingBefore)
        append(",\"writeRequestedElapsedMs\":").append(summary.writeRequestedElapsedMs?.takeIf { it >= 0 })
        append(",\"writeReturnedElapsedMs\":").append(summary.writeReturnedElapsedMs?.takeIf { it >= 0 })
        append(",\"writeRequestStatus\":").append(summary.writeRequestStatus)
        append(",\"writeException\":").append(quoted(summary.writeException?.takeIf { it in writeExceptions }))
        append(",\"writeCallbackStatus\":").append(summary.writeCallbackStatus)
        append(",\"writeCallbackElapsedMs\":").append(summary.writeCallbackElapsedMs?.takeIf { it >= 0 })
        append(",\"rxNotificationCount\":").append(summary.rxNotificationCount?.takeIf { it >= 0 })
        append(",\"rxFrameCount\":").append(summary.rxFrameCount?.takeIf { it >= 0 })
        append(",\"rxProtocolCount\":").append(summary.rxProtocolCount?.takeIf { it >= 0 })
        append(",\"lastRxElapsedMs\":").append(summary.lastRxElapsedMs?.takeIf { it >= 0 })
        append(",\"lastFrameElapsedMs\":").append(summary.lastFrameElapsedMs?.takeIf { it >= 0 })
        append(",\"responseDisposition\":").append(quoted(summary.responseDisposition?.takeIf { it in responseDispositions }))
        append(",\"responseQuery\":").append(quoted(summary.responseQuery?.takeIf { it in queries }))
        append(",\"gattConnectionStatus\":").append(summary.gattConnectionStatus)
        append(",\"gattConnectionState\":").append(summary.gattConnectionState)
        append(",\"gattConnectionElapsedMs\":").append(summary.gattConnectionElapsedMs?.takeIf { it >= 0 })
        append(",\"gattDescriptorRequestStatus\":").append(summary.gattDescriptorRequestStatus)
        append(",\"gattDescriptorCallbackStatus\":").append(summary.gattDescriptorCallbackStatus)
        append(",\"gattDescriptorCallbackElapsedMs\":").append(summary.gattDescriptorCallbackElapsedMs?.takeIf { it >= 0 })
        append(",\"writeDispatchQueued\":").append(summary.writeDispatchQueued)
        append(",\"writeDispatchElapsedMs\":").append(summary.writeDispatchElapsedMs?.takeIf { it >= 0 })
        append(",\"responseExpectedDomain\":").append(summary.responseExpectedDomain)
        append(",\"responseFromPresent\":").append(summary.responseFromPresent)
        append(",\"responseFromKind\":").append(quoted(summary.responseFromKind?.takeIf { it in destinationKinds }))
        append(",\"responseFromDomain\":").append(summary.responseFromDomain)
        append(",\"responseToPresent\":").append(summary.responseToPresent)
        append(",\"responseToKind\":").append(quoted(summary.responseToKind?.takeIf { it in destinationKinds }))
        append(",\"responsePayloadKind\":").append(quoted(summary.responsePayloadKind?.takeIf { it in payloadKinds }))
        append(",\"responseDomainMismatchReason\":").append(quoted(summary.responseDomainMismatchReason?.takeIf { it in domainMismatchReasons }))
        append(",\"advertisementConnectable\":").append(summary.advertisementConnectable)
        append(",\"gattConnectIssuedElapsedMs\":").append(summary.gattConnectIssuedElapsedMs?.takeIf { it >= 0 })
        append(",\"gattConnectReturnedElapsedMs\":").append(summary.gattConnectReturnedElapsedMs?.takeIf { it >= 0 })
        append(",\"gattAutoConnect\":").append(summary.gattAutoConnect)
        append(",\"gattConnectAction\":").append(quoted(summary.gattConnectAction?.takeIf { it == "CREATE" || it == "RECONNECT" }))
        append(",\"gattConnectAccepted\":").append(summary.gattConnectAccepted)
        append(",\"advertisementAgeAtConnectMs\":").append(summary.advertisementAgeAtConnectMs?.takeIf { it >= 0 })
        append(",\"gattTransport\":").append(quoted(summary.gattTransport?.takeIf { it == "NATIVE" }))
        append(",\"connectSettleStartedElapsedMs\":").append(summary.connectSettleStartedElapsedMs?.takeIf { it >= 0 })
        append(",\"connectSettleCompletedElapsedMs\":").append(summary.connectSettleCompletedElapsedMs?.takeIf { it >= 0 })
        append(",\"responseRequestUuidPresent\":").append(summary.responseRequestUuidPresent)
        append(",\"responseRequestUuidMatches\":").append(summary.responseRequestUuidMatches.takeIf { summary.responseRequestUuidPresent == true })
        append(",\"responseToRouteMatches\":").append(summary.responseToRouteMatches.takeIf { summary.responseToPresent == true })
        append(",\"responseSignedStatusPresent\":").append(summary.responseSignedStatusPresent)
        append(",\"responseSignedMessageFault\":").append(summary.responseSignedMessageFault.takeIf { summary.responseSignedStatusPresent == true })
        append(",\"responseOperationStatus\":").append(summary.responseOperationStatus.takeIf { summary.responseSignedStatusPresent == true })
        append('}')
    }

    private fun quoted(value: String?): String = value?.let(FieldJson::quote) ?: "null"
}
