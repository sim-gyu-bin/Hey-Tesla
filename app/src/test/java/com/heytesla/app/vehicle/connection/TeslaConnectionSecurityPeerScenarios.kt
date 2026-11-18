package com.heytesla.app.vehicle.connection

import com.google.protobuf.ByteString
import com.heytesla.app.peer.OfficialTeslaPeer
import com.heytesla.app.vehicle.protocol.TeslaBleAuthEvidence
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.heytesla.app.vehicle.protocol.TeslaBleRxAead
import com.heytesla.app.vehicle.protocol.TeslaBleRxStep
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec

internal object TeslaConnectionSecurityPeerScenarios {
    private fun rewrite(frame: ByteArray, change: (UniversalMessage.RoutableMessage.Builder) -> Unit): ByteArray {
        val builder = UniversalMessage.RoutableMessage.parseFrom(frame.copyOfRange(2, frame.size)).toBuilder()
        change(builder)
        return OfficialTeslaPeer.frame(builder.build().toByteArray())
    }
    val cases: List<Pair<String, () -> Unit>> = buildList {
        for (kind in listOf("hmac", "missing-tag", "short-tag", "invalid-point", "route", "uuid", "domain")) {
            add("connection-handshake-$kind-cannot-authenticate-or-renew-deadline" to {
                VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                    h.start()
                    val context = h.handshakes.single().contextId
                    val before = h.session.stageDeadline
                    val options = when (kind) {
                        "hmac" -> OfficialTeslaPeer.SessionInfoOptions(tagTransform = { tag ->
                            tag.apply { this[0] = (this[0].toInt() xor 1).toByte() }
                        })
                        "short-tag" -> OfficialTeslaPeer.SessionInfoOptions(tagTransform = { it.copyOf(31) })
                        "invalid-point" -> OfficialTeslaPeer.SessionInfoOptions(publicKey = ByteArray(65))
                        else -> OfficialTeslaPeer.SessionInfoOptions(envelopeMutation = { message ->
                            when (kind) {
                                "missing-tag" -> message.clearSignatureData()
                                "route" -> message.toDestination = message.toDestination.toBuilder()
                                    .setRoutingAddress(ByteString.copyFrom(ByteArray(16) { 9 })).build()
                                "uuid" -> message.requestUuid = ByteString.copyFrom(ByteArray(16) { 9 })
                                "domain" -> message.fromDestination = UniversalMessage.Destination.newBuilder().setDomainValue(3).build()
                            }
                        })
                    }
                    h.inject(h.peer.sessionInfoResponse(context, h.clock.elapsed, options)); h.settle()
                    check(h.requests.isEmpty() && h.parkStates.isEmpty() && !h.command())
                    check(h.session.stageDeadline == before && !h.session.sealed)
                    h.inject(h.peer.sessionInfoResponse(context, h.clock.elapsed)); h.settle()
                    h.finishBodyPreflight(); h.replyDrive()
                    check(h.parkStates.single().second == null)
                }
            })
        }
        for (kind in listOf("route", "uuid", "omitted-uuid", "domain", "missing-source", "missing-destination")) {
            add("connection-drive-$kind-header-never-bypasses-aead-binding" to {
                VehiclePeerHarness().use { h ->
                    h.startDrive()
                    val original = h.peer.encryptedResponse(h.driveRequests.single().id, h.driveBody())
                    val altered = rewrite(original) { message ->
                        when (kind) {
                            "route" -> message.toDestination = message.toDestination.toBuilder()
                                .setRoutingAddress(ByteString.copyFrom(ByteArray(16) { 7 })).build()
                            "uuid" -> message.requestUuid = ByteString.copyFrom(ByteArray(16) { 7 })
                            "omitted-uuid" -> message.clearRequestUuid()
                            "domain" -> message.fromDestination = UniversalMessage.Destination.newBuilder().setDomainValue(2).build()
                            "missing-source" -> message.clearFromDestination()
                            else -> message.clearToDestination()
                        }
                    }
                    val deadline = h.session.stageDeadline
                    h.inject(altered); h.settle()
                    check(h.parkStates.isEmpty() && !h.session.sealed && h.session.stageDeadline == deadline)
                    check(h.observations.last().aead == TeslaBleRxAead.NOT_ATTEMPTED)
                    h.inject(original); h.settle()
                    check(h.parkStates.single().second == null)
                }
            })
        }
        for (kind in listOf("tag", "short-tag", "ciphertext", "request-hash", "flags", "fault")) {
            add("connection-drive-$kind-corruption-never-arms-voice" to {
                VehiclePeerHarness().use { h ->
                    h.startDrive()
                    val flip: (ByteArray) -> ByteArray = { it.apply { this[0] = (this[0].toInt() xor 1).toByte() } }
                    val options = when (kind) {
                        "tag" -> OfficialTeslaPeer.ResponseOptions(tagTransform = flip)
                        "short-tag" -> OfficialTeslaPeer.ResponseOptions(tagTransform = { it.copyOf(15) })
                        "ciphertext" -> OfficialTeslaPeer.ResponseOptions(ciphertextTransform = flip)
                        "request-hash" -> OfficialTeslaPeer.ResponseOptions(requestHash = ByteArray(17) { 7 })
                        "flags" -> OfficialTeslaPeer.ResponseOptions(envelopeMutation = { it.flags = 1 })
                        else -> OfficialTeslaPeer.ResponseOptions(envelopeMutation = {
                            it.signedMessageStatus = it.signedMessageStatus.toBuilder().setSignedMessageFaultValue(1).build()
                        })
                    }
                    h.replyDrive(options = options)
                    check(h.session.sealed && h.parkStates.none { it.second == null } && !h.command())
                    check(h.observations.last().aead == if (kind == "short-tag")
                        TeslaBleRxAead.NOT_ATTEMPTED else TeslaBleRxAead.FAILED)
                }
            })
        }
        add("connection-encrypted-fault-is-verified-but-not-park" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive(options = OfficialTeslaPeer.ResponseOptions(fault = 1))
                check(h.session.sealed && h.parkStates.none { it.second == null })
                check(h.observations.last().aead == TeslaBleRxAead.VERIFIED)
            }
        })
        add("connection-response-counter-is-scoped-to-new-request-not-reused-context" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive(options = OfficialTeslaPeer.ResponseOptions(counter = 8))
                h.advance(2_000); h.replyDrive(options = OfficialTeslaPeer.ResponseOptions(counter = 8))
                check(h.parkStates.size == 2 && h.parkStates.all { it.second == null })
                check(h.driveRequests[0].id != h.driveRequests[1].id &&
                    h.driveRequests[0].counter < h.driveRequests[1].counter)
            }
        })
        for (reroute in listOf(false, true)) {
            add("connection-old-response-${if (reroute) "rewritten-binding" else "old-route"}-cannot-refresh-park" to {
                VehiclePeerHarness().use { h ->
                    h.startDrive()
                    val old = h.peer.encryptedResponse(h.requests.last().id, h.driveBody())
                    h.inject(old); h.settle(); h.advance(2_000)
                    val current = h.requests.last()
                    val response = if (!reroute) old else rewrite(old) {
                        it.toDestination = UniversalMessage.Destination.newBuilder()
                            .setRoutingAddress(ByteString.copyFrom(current.route)).build()
                        it.requestUuid = ByteString.copyFrom(current.uuid)
                    }
                    h.inject(response); h.settle()
                    check(h.parkStates.count { it.second == null } == 1)
                    if (reroute) check(h.session.sealed && h.observations.last().aead == TeslaBleRxAead.FAILED)
                    else {
                        check(!h.session.sealed && h.observations.last().aead == TeslaBleRxAead.NOT_ATTEMPTED)
                        h.replyDrive(); check(h.parkStates.count { it.second == null } == 2)
                    }
                }
            })
        }
        add("manual-body-omitted-uuid-exception-still-requires-authenticated-response" to {
            VehiclePeerHarness(serviceOwned = false, query = TeslaBleQuery.BODY_STATUS).use { h ->
                h.start()
                val body = Vcsec.FromVCSECMessage.newBuilder().setVehicleStatus(Vcsec.VehicleStatus.newBuilder()
                    .setVehicleLockStateValue(1)).build().toByteArray()
                val frame = h.peer.encryptedResponse(h.requests.single().id, body,
                    OfficialTeslaPeer.ResponseOptions(envelopeMutation = { it.clearRequestUuid() }))
                h.inject(frame); h.settle()
                check(h.keyState.status?.lockState == 1 && h.cleanupFailed == false)
                check(h.observations.last().aead == TeslaBleRxAead.VERIFIED)
            }
        })
        for (kind in listOf("counter", "clock")) {
            add("connection-exhausted-$kind-cannot-create-a-request" to {
                val peer = if (kind == "counter") OfficialTeslaPeer(initialCounter = 0xffff_ffffL)
                    else OfficialTeslaPeer(initialClockSeconds = 0xffff_ffffL)
                VehiclePeerHarness(peer).use { h ->
                    h.start()
                    check(h.session.sealed && h.requests.isEmpty() && h.parkStates.none { it.second == null })
                }
            })
        }
        add("connection-coalesced-frame-cap-rejects-before-authentication" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                h.start()
                val hello = h.peer.sessionInfoResponse(h.handshakes.single().contextId, h.clock.elapsed)
                val burst = ByteArray(hello.size * 9)
                repeat(9) { hello.copyInto(burst, it * hello.size) }
                h.inject(burst); h.settle()
                check(h.session.sealed && h.stopReason == "RX_BUFFER_LIMIT" && h.requests.isEmpty())
            }
        })
        add("connection-posted-observer-exception-cannot-deliver-authentication-send" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                h.start()
                h.responseObserver = { error("synthetic observer failure") }
                h.inject(h.peer.sessionInfoResponse(h.handshakes.single().contextId, h.clock.elapsed)); h.settle()
                check(h.session.sealed && h.requests.isEmpty() && h.cleanupFailed == false)
            }
        })
    }
}
