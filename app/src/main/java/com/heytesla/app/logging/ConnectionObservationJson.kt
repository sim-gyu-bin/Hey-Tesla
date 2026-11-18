package com.heytesla.app.logging

import com.heytesla.app.diagnostics.TeslaApproachReadSummary
import com.heytesla.app.vehicle.connection.TeslaGattObservation
import com.heytesla.app.vehicle.protocol.TeslaBleResponseObservation
import com.heytesla.app.vehicle.protocol.TeslaBleRxAead
import com.heytesla.app.vehicle.protocol.TeslaBleRxInputKind
import com.heytesla.app.vehicle.protocol.TeslaBleRxStep

/** 제품 연결의 값 없는 관측만 투영한다. 역사 UWB 키·본문·차량 절대 시각은 재사용하지 않는다. */
internal object ConnectionObservationJson {
    private val dispositions = setOf(
        "DOMAIN_MISMATCH", "ROUTE_MISMATCH", "REQUEST_UUID_MISMATCH", "ACK_WAITING",
        "SESSION_INFO_INVALID", "SESSION_INFO_AUTH_REJECTED", "SESSION_READY",
        "STATUS_VERIFIED", "STATUS_INCOMPLETE", "RESPONSE_REJECTED", "RESPONSE_INVALID",
        "RESPONSE_WAITING", "RESPONSE_BEFORE_START", "FIRA_REQUEST", "FIRA_STOP",
        "FIRA_UNRECOGNIZED", "FIRA_INVALID",
    )
    private val actionDispositionsV35 = setOf(
        "WAKE_ACKNOWLEDGED", "WAKE_NOMINAL_ACK", "WAKE_UNCONFIRMED",
        "FRUNK_ACKNOWLEDGED", "FRUNK_UNCONFIRMED",
    )
    private val destinationKinds = setOf("DOMAIN", "ROUTING_ADDRESS", "UNSET", "UNKNOWN")
    private val payloadKinds = setOf("SESSION_INFO", "SESSION_INFO_REQUEST", "PROTOBUF_MESSAGE_AS_BYTES", "UNSET", "UNKNOWN")
    private val mismatchReasons = setOf("FROM_MISSING", "FROM_NOT_DOMAIN", "DOMAIN_DIFFERENT")
    private val driveShiftStates = setOf("ABSENT", "EMPTY", "INVALID", "P", "R", "N", "D", "SNA", "UNRECOGNIZED")
    private val driveTimestampStatuses = setOf("ABSENT", "INVALID", "NON_POSITIVE", "VALID")
    private val writeExceptions = setOf("SECURITY_EXCEPTION", "ILLEGAL_ARGUMENT_EXCEPTION",
        "ILLEGAL_STATE_EXCEPTION", "REMOTE_EXCEPTION", "RUNTIME_EXCEPTION", "OTHER_EXCEPTION")

    fun appendTo(out: StringBuilder, observation: TeslaGattObservation, elapsedMs: Long,
                 version: Int = FIELD_LOG_RECORD_VERSION) = with(out) {
        val operation = observation.operationStartedElapsedMs.takeIf { it in 0L..elapsedMs }
        val observed = time(observation.observedElapsedMs, operation, elapsedMs)
        val issued = time(observation.connectIssuedElapsedMs, operation, observed)
        val firstRx = time(observation.firstRxElapsedMs, operation, observed)
        val protocolReceived = time(observation.rxProtocolReceivedElapsedMs, operation, observed)
        append("{\"stage\":").append(quoted(observation.stage.name))
        append(",\"event\":").append(quoted(observation.event.name))
        append(",\"deadlineCause\":").append(quoted(observation.deadlineCause?.name))
        append(",\"operationStartedElapsedMs\":").append(operation)
        append(",\"stageStartedElapsedMs\":").append(time(observation.stageStartedElapsedMs, operation, observed))
        append(",\"observedElapsedMs\":").append(observed)
        append(",\"connectIssuedElapsedMs\":").append(issued)
        append(",\"connectReturnedElapsedMs\":").append(time(observation.connectReturnedElapsedMs, issued ?: operation, observed))
        append(",\"autoConnect\":").append(observation.autoConnect)
        append(",\"connectAction\":").append(quoted(observation.connectAction?.takeIf { it == "CREATE" || it == "RECONNECT" }))
        append(",\"connectAccepted\":").append(observation.connectAccepted)
        append(",\"advertisementAgeAtConnectMs\":").append(observation.advertisementAgeAtConnectMs?.takeIf { it in 0L..elapsedMs })
        append(",\"connectionStatus\":").append(observation.connectionStatus)
        append(",\"connectionState\":").append(observation.connectionState)
        append(",\"connectionCallbackElapsedMs\":").append(time(observation.connectionCallbackElapsedMs, operation, observed))
        append(",\"descriptorRequestStatus\":").append(observation.descriptorRequestStatus)
        append(",\"descriptorCallbackStatus\":").append(observation.descriptorCallbackStatus)
        append(",\"descriptorCallbackElapsedMs\":").append(time(observation.descriptorCallbackElapsedMs, operation, observed))
        append(",\"firstRxElapsedMs\":").append(firstRx)
        append(",\"lastRxElapsedMs\":").append(time(observation.lastRxElapsedMs, firstRx ?: operation, observed))
        append(",\"connectedObserved\":").append(observation.connectedObserved)
        append(",\"serviceFound\":").append(observation.serviceFound)
        append(",\"txFound\":").append(observation.txFound)
        append(",\"rxFound\":").append(observation.rxFound)
        append(",\"subscriptionConfirmed\":").append(observation.subscriptionConfirmed)
        append(",\"rxNotificationCount\":").append(observation.rxNotificationCount.takeIf { it >= 0 })
        append(",\"rxFrameCount\":").append(observation.rxFrameCount?.takeIf { it >= 0 })
        append(",\"rxProtocolCount\":").append(observation.rxProtocolCount?.takeIf { it >= 0 })
        append(",\"rxNotificationFrameCount\":").append(observation.rxNotificationFrameCount?.takeIf { it in 0..16 })
        append(",\"rxDecoderIncomplete\":").append(observation.rxDecoderIncomplete)
        append(",\"rxDecoderFailed\":").append(observation.rxDecoderFailed)
        append(",\"rxProtocolReceivedElapsedMs\":").append(protocolReceived)
        append(",\"rxProtocolObservedElapsedMs\":").append(time(observation.rxProtocolObservedElapsedMs, protocolReceived ?: operation, observed))
        append(",\"rxResponse\":")
        val response = observation.rxResponse
        if (response == null) append("null") else appendResponse(this, response, version)
        append(",\"write\":")
        val write = observation.write
        if (write == null) append("null") else appendWrite(this, write, operation, observed)
        append('}')
        Unit
    }

    private fun appendResponse(out: StringBuilder, response: TeslaBleResponseObservation, version: Int) = with(out) {
        append("{\"disposition\":").append(quoted(response.disposition.takeIf {
            it in dispositions || (version >= 35 && it in actionDispositionsV35)
        }))
        append(",\"expectedDomain\":").append(response.expectedDomain)
        append(",\"fromPresent\":").append(response.fromPresent)
        append(",\"fromKind\":").append(quoted(response.fromKind?.takeIf { it in destinationKinds }))
        append(",\"fromDomain\":").append(response.fromDomain)
        append(",\"toPresent\":").append(response.toPresent)
        append(",\"toKind\":").append(quoted(response.toKind?.takeIf { it in destinationKinds }))
        append(",\"payloadKind\":").append(quoted(response.payloadKind?.takeIf { it in payloadKinds }))
        append(",\"domainMismatchReason\":").append(quoted(response.domainMismatchReason?.takeIf { it in mismatchReasons }))
        if (version >= 36) {
            append(",\"domainMismatchVcsecSleepStatus\":").append(response.domainMismatchVcsecSleepStatus
                ?.takeIf { it in 0..2 && response.domainMismatchReason == "DOMAIN_DIFFERENT" })
        }
        append(",\"requestUuidPresent\":").append(response.requestUuidPresent)
        append(",\"requestUuidMatches\":").append(response.requestUuidMatches.takeIf { response.requestUuidPresent == true })
        append(",\"toRouteMatches\":").append(response.toRouteMatches.takeIf { response.toPresent == true })
        append(",\"signedStatusPresent\":").append(response.signedStatusPresent)
        append(",\"signedMessageFault\":").append(response.signedMessageFault.takeIf { response.signedStatusPresent == true })
        append(",\"operationStatus\":").append(response.operationStatus.takeIf { response.signedStatusPresent == true })
        append(",\"step\":").append(quoted(response.step?.name?.takeUnless { version < 35 && it == "WAKE_RESULT" }))
        append(",\"inputKind\":").append(quoted(response.inputKind?.name))
        append(",\"aead\":").append(quoted(response.aead?.name))
        append(",\"authEvidence\":").append(quoted(response.authEvidence?.name))
        if (version >= 37) {
            val verifiedDrive = response.expectedDomain == 3 &&
                response.fromPresent == true && response.fromKind == "DOMAIN" && response.fromDomain == 3 &&
                response.toPresent == true && response.toKind == "ROUTING_ADDRESS" &&
                response.requestUuidPresent == true && response.requestUuidMatches == true && response.toRouteMatches == true &&
                response.inputKind == TeslaBleRxInputKind.ENCRYPTED_RESPONSE && response.aead == TeslaBleRxAead.VERIFIED &&
                response.disposition == "STATUS_VERIFIED" && response.step == TeslaBleRxStep.COMPLETE
            append(",\"driveShiftState\":").append(quoted(response.driveShiftState
                ?.takeIf { verifiedDrive && it in driveShiftStates }))
            append(",\"driveTimestampStatus\":").append(quoted(response.driveTimestampStatus
                ?.takeIf { verifiedDrive && it in driveTimestampStatuses }))
        }
        append('}')
        Unit
    }

    private fun appendWrite(out: StringBuilder, write: TeslaApproachReadSummary, operation: Long?, observed: Long?) = with(out) {
        val requested = time(write.writeRequestedElapsedMs, operation, observed)
        val returned = time(write.writeReturnedElapsedMs, requested ?: operation, observed)
        append("{\"writeSequence\":").append(write.writeSequence?.takeIf { it > 0 })
        append(",\"writeBusyRetryCount\":").append(write.writeBusyRetryCount?.takeIf { it in 0..2 })
        append(",\"writeType\":").append(write.writeType)
        append(",\"writePendingBefore\":").append(write.writePendingBefore)
        append(",\"writeRequestedElapsedMs\":").append(requested)
        append(",\"writeReturnedElapsedMs\":").append(returned)
        append(",\"writeRequestStatus\":").append(write.writeRequestStatus)
        append(",\"writeException\":").append(quoted(write.writeException?.takeIf { it in writeExceptions }))
        append(",\"writeCallbackStatus\":").append(write.writeCallbackStatus)
        append(",\"writeCallbackElapsedMs\":").append(time(write.writeCallbackElapsedMs, returned ?: requested ?: operation, observed))
        append(",\"writeDispatchQueued\":").append(write.writeDispatchQueued)
        append(",\"writeDispatchElapsedMs\":").append(time(write.writeDispatchElapsedMs, requested ?: operation, observed))
        append('}')
        Unit
    }

    private fun time(value: Long?, lower: Long?, upper: Long?): Long? =
        value?.takeIf { lower != null && upper != null && it >= lower && it <= upper }

    private fun quoted(value: String?): String = value?.let(FieldJson::quote) ?: "null"
}
