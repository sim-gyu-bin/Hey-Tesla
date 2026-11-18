package com.heytesla.app.vehicle.connection

import com.heytesla.app.peer.OfficialTeslaPeer
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.protocol.TeslaBleRxAead
import com.heytesla.app.vehicle.protocol.TeslaBleRxStep

/** Production DRIVE → ACTION → BODY receives, with replies authenticated by the independent peer. */
internal object TeslaFrunkReceivePeerScenarios {
    private val BODY = OfficialTeslaPeer.RequestKind.BODY
    private val DRIVE = OfficialTeslaPeer.RequestKind.DRIVE
    private val FRUNK = OfficialTeslaPeer.RequestKind.FRUNK

    private fun beginCommand(h: VehiclePeerHarness): Long {
        h.startDrive()
        h.replyDrive()
        check(h.command())
        h.settle()
        check(h.requests.map { it.kind } == listOf(BODY, DRIVE, DRIVE))
        return h.session.deadline
    }

    private fun beginAction(h: VehiclePeerHarness): Long {
        val deadline = beginCommand(h)
        h.replyDrive()
        check(h.requests.map { it.kind } == listOf(BODY, DRIVE, DRIVE, FRUNK))
        check(h.session.frunkPolicy?.commandFrameComplete == true)
        return deadline
    }

    private fun replyAction(h: VehiclePeerHarness) {
        val reply = h.peer.encryptedResponse(h.requests.last { it.kind == FRUNK }.id, byteArrayOf())
        try { h.inject(reply); h.settle() } finally { reply.fill(0) }
    }

    private fun protocol(h: VehiclePeerHarness, count: Long, domain: Int, step: TeslaBleRxStep,
                         disposition: String, aead: TeslaBleRxAead): TeslaGattObservation {
        val observed = h.gattObservations.last { it.event == TeslaGattEvent.PROTOCOL }
        check(observed.rxProtocolCount == count)
        val response = checkNotNull(observed.rxResponse)
        check(response.expectedDomain == domain && response.fromDomain == domain)
        check(response.step == step && response.disposition == disposition && response.aead == aead)
        check(response.requestUuidMatches == true && response.toRouteMatches == true)
        check(observed.rxProtocolReceivedElapsedMs == h.clock.elapsed)
        check(observed.rxProtocolObservedElapsedMs == h.clock.elapsed)
        check(h.session.pendingRxResponse == null)
        return observed
    }

    val cases: List<Pair<String, () -> Unit>> = buildList {
        add("frunk-receive-counts-and-preserves-action-ack-before-final-domain2-body" to {
            VehiclePeerHarness().use { h ->
                val deadline = beginCommand(h)
                protocol(h, 5, 3, TeslaBleRxStep.SEND, "SESSION_READY", TeslaBleRxAead.NOT_ATTEMPTED)
                h.replyDrive()
                check(h.requests.last().kind == FRUNK)
                protocol(h, 7, 2, TeslaBleRxStep.SEND, "SESSION_READY", TeslaBleRxAead.NOT_ATTEMPTED)
                replyAction(h)
                val action = h.gattObservations.single {
                    it.event == TeslaGattEvent.PROTOCOL && it.rxResponse?.step == TeslaBleRxStep.FRUNK_RESULT
                }
                check(action.rxProtocolCount == 8L)
                val actionResponse = checkNotNull(action.rxResponse)
                check(actionResponse.expectedDomain == 2 && actionResponse.fromDomain == 2)
                check(actionResponse.disposition == "FRUNK_ACKNOWLEDGED")
                check(actionResponse.aead == TeslaBleRxAead.VERIFIED)
                check(actionResponse.requestUuidMatches == true && actionResponse.toRouteMatches == true)
                check(h.session.frunkPolicy?.commandAcknowledged == true)
                check(h.session.frunkPolicy?.openObserved == false && !h.session.sealed)
                check(h.requests.last().kind == BODY)
                protocol(h, 9, 2, TeslaBleRxStep.SEND, "SESSION_READY", TeslaBleRxAead.NOT_ATTEMPTED)
                h.replyBody()
                val terminal = protocol(h, 10, 2, TeslaBleRxStep.COMPLETE, "STATUS_VERIFIED", TeslaBleRxAead.VERIFIED)
                check(h.gattObservations.filter { it.event == TeslaGattEvent.PROTOCOL }
                    .map { it.rxProtocolCount } == (1L..10L).toList())
                check(h.gattObservations.filter { it.event == TeslaGattEvent.PROTOCOL }
                    .map { it.rxResponse?.expectedDomain } == listOf(2, 2, 3, 3, 3, 3, 2, 2, 2, 2))
                check(h.requests.map { it.kind } == listOf(BODY, DRIVE, DRIVE, FRUNK, BODY))
                check(h.handshakes.map { it.domain } == listOf(2, 3, 3, 2, 2))
                check(h.stopStage == TeslaKeyStage.COMPLETE && h.stopCount == 1 && h.cleanupFailed == false)
                check(h.session.frunkPolicy?.openObserved == true && h.keyState.status?.frontTrunkState == 1)
                check(h.session.gattObservation?.rxResponse == terminal.rxResponse)
                check(h.session.deadline == deadline && !h.command() && h.peerRejections.isEmpty())
            }
        })
        add("frunk-receive-queued-action-ack-keeps-ingress-until-actual-consumption" to {
            VehiclePeerHarness().use { h ->
                val deadline = beginCommand(h)
                h.autoCallbacks = false
                h.replyDrive()
                check(h.completeOne()) // ACTION hello completes; its authenticated reply queues the actuator.
                check(h.requests.last().kind == FRUNK && h.session.writePump.writePending)
                val received = h.clock.elapsed
                replyAction(h)
                check(h.session.frunkPolicy?.commandAcknowledged == false)
                check(h.session.gattObservation?.rxFrameCount == 8L)
                check(h.session.gattObservation?.rxProtocolCount == 7L)
                h.clock.elapsed += 1_500
                h.wallEpochMs += 1_500
                h.completeAll()
                val action = h.gattObservations.single {
                    it.event == TeslaGattEvent.PROTOCOL && it.rxResponse?.step == TeslaBleRxStep.FRUNK_RESULT
                }
                check(action.rxProtocolCount == 8L && action.rxProtocolReceivedElapsedMs == received)
                check(action.rxProtocolObservedElapsedMs == h.clock.elapsed)
                val actionResponse = checkNotNull(action.rxResponse)
                check(actionResponse.disposition == "FRUNK_ACKNOWLEDGED" &&
                    actionResponse.aead == TeslaBleRxAead.VERIFIED)
                check(h.session.frunkPolicy?.commandAcknowledged == true && h.requests.last().kind == BODY)
                h.replyBody()
                protocol(h, 10, 2, TeslaBleRxStep.COMPLETE, "STATUS_VERIFIED", TeslaBleRxAead.VERIFIED)
                check(h.stopStage == TeslaKeyStage.COMPLETE && h.session.deadline == deadline)
                check(h.requests.map { it.kind } == listOf(BODY, DRIVE, DRIVE, FRUNK, BODY))
            }
        })
        add("frunk-receive-late-drive-and-action-replies-cannot-replace-current-body-judgment" to {
            VehiclePeerHarness().use { h ->
                val deadline = beginAction(h)
                h.replyDrive(request = h.driveRequests.first())
                val lateDrive = h.gattObservations.last { it.event == TeslaGattEvent.PROTOCOL }
                check(lateDrive.rxProtocolCount == 8L)
                val lateDriveResponse = checkNotNull(lateDrive.rxResponse)
                check(lateDriveResponse.expectedDomain == 2 && lateDriveResponse.fromDomain == 3)
                check(lateDriveResponse.disposition == "DOMAIN_MISMATCH" && lateDriveResponse.step == TeslaBleRxStep.WAITING)
                check(lateDriveResponse.aead == TeslaBleRxAead.NOT_ATTEMPTED)
                check(h.session.frunkPolicy?.commandAcknowledged == false && !h.session.sealed)
                replyAction(h)
                replyAction(h) // Old ACTION route is now being presented to the new BODY conversation.
                val lateAction = h.gattObservations.last { it.event == TeslaGattEvent.PROTOCOL }
                check(lateAction.rxProtocolCount == 11L)
                val lateActionResponse = checkNotNull(lateAction.rxResponse)
                check(lateActionResponse.expectedDomain == 2 && lateActionResponse.fromDomain == 2)
                check(lateActionResponse.disposition == "ROUTE_MISMATCH" && lateActionResponse.step == TeslaBleRxStep.WAITING)
                check(lateActionResponse.aead == TeslaBleRxAead.NOT_ATTEMPTED)
                check(h.session.frunkPolicy?.commandAcknowledged == true && h.session.frunkPolicy?.openObserved == false)
                h.replyBody()
                protocol(h, 12, 2, TeslaBleRxStep.COMPLETE, "STATUS_VERIFIED", TeslaBleRxAead.VERIFIED)
                val closed = h.session.gattObservation
                val eventCount = h.gattObservations.size
                replyAction(h)
                h.replyBody()
                check(h.session.gattObservation == closed && h.gattObservations.size == eventCount)
                check(h.stopCount == 1 && h.stopStage == TeslaKeyStage.COMPLETE && !h.command())
                check(h.session.deadline == deadline && h.requests.count { it.kind == FRUNK } == 1)
            }
        })
        for (boundary in listOf("drive-handshake", "action-ack")) {
            add("frunk-receive-$boundary-observer-exception-clears-result-and-seals-once" to {
                VehiclePeerHarness().use { h ->
                    val deadline: Long
                    var invoked = false
                    if (boundary == "drive-handshake") {
                        h.startDrive()
                        h.replyDrive()
                        deadline = h.clock.elapsed + 40_000
                    } else {
                        deadline = beginAction(h)
                    }
                    val received = h.clock.elapsed
                    h.responseObserver = { response ->
                        if (boundary == "drive-handshake" && response.disposition == "SESSION_READY" ||
                            boundary == "action-ack" && response.step == TeslaBleRxStep.FRUNK_RESULT) {
                            invoked = true
                            h.clock.elapsed += 7
                            h.wallEpochMs += 7
                            error("synthetic frunk response observer failure")
                        }
                    }
                    if (boundary == "drive-handshake") {
                        check(h.command())
                        h.settle()
                    } else replyAction(h)
                    check(invoked && h.session.sealed && h.stopCount == 1 && h.cleanupFailed == false)
                    check(h.stopStage == TeslaKeyStage.FAILED && h.stopReason == "PROTOCOL_RESPONSE_INVALID")
                    val failed = h.gattObservations.last { it.event == TeslaGattEvent.PROTOCOL }
                    check(failed.rxProtocolCount == if (boundary == "drive-handshake") 5L else 8L)
                    check(failed.rxProtocolReceivedElapsedMs == received && failed.rxProtocolObservedElapsedMs == received + 7)
                    check(failed.rxResponse == null && h.session.pendingRxResponse == null)
                    check(h.observations.last().disposition == if (boundary == "drive-handshake")
                        "SESSION_READY" else "FRUNK_ACKNOWLEDGED")
                    check(h.session.frunkPolicy?.commandAcknowledged == false)
                    check(h.requests.map { it.kind } == if (boundary == "drive-handshake")
                        listOf(BODY, DRIVE) else listOf(BODY, DRIVE, DRIVE, FRUNK))
                    val closed = h.session.gattObservation
                    val late = h.peer.sessionInfoResponse(h.handshakes.last().contextId, h.clock.elapsed)
                    try { h.inject(late); h.completeAll(); h.advance(20_000) } finally { late.fill(0) }
                    check(h.session.gattObservation == closed && h.stopCount == 1 && !h.command())
                    check(h.session.deadline == deadline)
                }
            })
        }
        for (failure in listOf(false, true)) {
            add("frunk-receive-action-protocol-${if (failure) "exception" else "reentrant-off"}-retains-ack-without-body-write" to {
                VehiclePeerHarness().use { h ->
                    val deadline = beginAction(h)
                    var invoked = false
                    h.gattObserver = { observed ->
                        if (observed.event == TeslaGattEvent.PROTOCOL &&
                            observed.rxResponse?.step == TeslaBleRxStep.FRUNK_RESULT) {
                            invoked = true
                            if (failure) error("synthetic frunk protocol observer failure")
                            h.session.cancel(reason = "SERVICE_OFF")
                        }
                    }
                    replyAction(h)
                    check(invoked && h.session.sealed && h.stopCount == 1 && h.cleanupFailed == false)
                    protocol(h, 8, 2, TeslaBleRxStep.FRUNK_RESULT, "FRUNK_ACKNOWLEDGED", TeslaBleRxAead.VERIFIED)
                    check(h.stopStage == if (failure) TeslaKeyStage.FAILED else TeslaKeyStage.CANCELED)
                    check(h.stopReason == if (failure) "PROTOCOL_RESPONSE_INVALID" else "SERVICE_OFF")
                    check(h.session.frunkPolicy?.commandAcknowledged == true && h.session.frunkPolicy?.openObserved == false)
                    check(h.requests.map { it.kind } == listOf(BODY, DRIVE, DRIVE, FRUNK))
                    val closed = h.session.gattObservation
                    replyAction(h)
                    h.completeAll()
                    h.advance(20_000)
                    check(h.session.gattObservation == closed && h.stopCount == 1 && !h.command())
                    check(h.session.deadline == deadline && h.peerRejections.isEmpty())
                }
            })
        }
        add("frunk-receive-without-response-observer-counts-consumption-without-inventing-judgment" to {
            VehiclePeerHarness(observeResponses = false).use { h ->
                val deadline = beginAction(h)
                replyAction(h)
                h.replyBody()
                val protocols = h.gattObservations.filter { it.event == TeslaGattEvent.PROTOCOL }
                check(protocols.map { it.rxProtocolCount } == (1L..10L).toList())
                check(protocols.all { it.rxResponse == null &&
                    it.rxProtocolReceivedElapsedMs == h.clock.elapsed && it.rxProtocolObservedElapsedMs == h.clock.elapsed })
                check(h.observations.isEmpty() && h.session.pendingRxResponse == null)
                check(h.stopStage == TeslaKeyStage.COMPLETE && h.stopCount == 1 && h.cleanupFailed == false)
                check(h.session.frunkPolicy?.commandAcknowledged == true && h.session.frunkPolicy?.openObserved == true)
                check(h.session.deadline == deadline && h.requests.map { it.kind } == listOf(BODY, DRIVE, DRIVE, FRUNK, BODY))
            }
        })
    }
}
