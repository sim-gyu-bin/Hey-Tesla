package com.heytesla.app.vehicle.connection

import com.heytesla.app.peer.OfficialTeslaPeer
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.protocol.TeslaBleRxAead
import com.heytesla.app.vehicle.protocol.TeslaBleRxStep
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.tesla.generated.vcsec.Vcsec

internal object TeslaPrestartIngressPeerScenarios {
    private fun VehiclePeerHarness.hello() = peer.sessionInfoResponse(handshakes.single().contextId, clock.elapsed)
    private fun VehiclePeerHarness.ignored() {
        val observed = observations.last()
        check(observed.step == TeslaBleRxStep.IGNORED && observed.disposition == "RESPONSE_BEFORE_START")
        check(observed.aead == TeslaBleRxAead.NOT_ATTEMPTED && observed.authEvidence == null)
        check(observed.inputKind == null && observed.requestCorrelation == null)
        check(!session.sealed && parkStates.isEmpty())
    }
    val cases: List<Pair<String, () -> Unit>> = buildList {
        for (partial in listOf(false, true)) for (shape in listOf("malformed", "status", "unknown")) {
            add("prestart-${if (partial) "partial" else "queued"}-$shape-cannot-authenticate" to {
                VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                    h.autoCallbacks = false; h.session.negotiatedMtu(23)
                    h.session.watch(); h.session.start()
                    if (partial) h.settle()
                    val body = when (shape) {
                        "malformed" -> byteArrayOf(0x80.toByte())
                        "status" -> Vcsec.FromVCSECMessage.newBuilder().setVehicleStatus(Vcsec.VehicleStatus.newBuilder()
                            .setVehicleLockStateValue(1)).build().toByteArray()
                        else -> byteArrayOf(0x08, 0x01)
                    }
                    val deadline = h.session.stageDeadline
                    h.inject(OfficialTeslaPeer.frame(body), split = 1, receivedAtElapsedMs = h.clock.elapsed - 1)
                    check(h.observations.isEmpty())
                    h.settle(); h.completeAll(); h.ignored()
                    check(h.requests.isEmpty() && h.session.stageDeadline == deadline)
                    h.inject(h.hello()); h.settle(); h.completeAll()
                    h.finishBodyPreflight(); h.replyDrive()
                    check(h.parkStates.single().second == null && h.peerRejections.isEmpty())
                }
            })
        }
        add("prestart-valid-hmac-before-final-write-callback-is-not-authentication" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                h.autoCallbacks = false; h.start()
                val hello = h.hello()
                h.inject(hello, receivedAtElapsedMs = h.clock.elapsed - 1)
                check(h.observations.isEmpty())
                h.completeAll(); h.ignored()
                check(h.requests.isEmpty())
                h.inject(hello); h.settle(); h.completeAll()
                h.finishBodyPreflight(); h.replyDrive()
                check(h.parkStates.single().second == null)
            }
        })
        for (phase in listOf("hello", "drive")) {
            add("prestart-$phase-repeat-cannot-postpone-watchdog" to {
                VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = phase == "drive")).use { h ->
                    val received = h.clock.elapsed - 1
                    if (phase == "drive") h.startDrive() else h.start()
                    val stageDeadline = h.session.stageDeadline
                    val writes = h.requests.size
                    for (time in listOf(h.clock.elapsed + 1, stageDeadline - 1)) {
                        h.clock.elapsed = time
                        h.inject(OfficialTeslaPeer.frame(byteArrayOf(0x80.toByte())), receivedAtElapsedMs = received)
                        h.settle(); h.ignored()
                        check(h.session.stageDeadline == stageDeadline && h.requests.size == writes)
                    }
                    h.clock.elapsed = stageDeadline + 250; h.settle()
                    check(h.session.sealed && h.stopStage == TeslaKeyStage.TIMED_OUT && h.stopCount == 1)
                    check(h.cleanupFailed == false)
                }
            })
        }
        for (received in listOf(-1L, 1_001L)) {
            add("prestart-invalid-ingress-$received-is-rejected-before-ignore" to {
                VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                    h.start(); h.inject(h.hello(), receivedAtElapsedMs = received)
                    check(h.session.sealed && h.observations.single().step == TeslaBleRxStep.FAILED)
                    check(h.requests.isEmpty())
                }
            })
        }
        add("prestart-wrong-native-owner-or-generation-never-enters-consumer" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                h.start(); val hello = h.hello()
                val observations = h.gattObservations.size
                h.injectFrom(Any(), hello)
                h.injectFrom(checkNotNull(h.owner.handle), hello, capturedGeneration = h.owner.connectionGeneration - 1)
                check(h.observations.isEmpty() && h.gattObservations.size == observations)
                h.inject(hello); h.settle(); h.finishBodyPreflight(); h.replyDrive()
                check(h.parkStates.single().second == null)
            }
        })
        add("prestart-encrypted-drive-does-not-reserve-response-counter" to {
            VehiclePeerHarness().use { h ->
                h.startDrive()
                val response = h.peer.encryptedResponse(h.driveRequests.single().id, h.driveBody(),
                    OfficialTeslaPeer.ResponseOptions(counter = 17))
                h.inject(response, receivedAtElapsedMs = h.clock.elapsed - 1); h.settle(); h.ignored()
                h.inject(response); h.settle()
                check(h.parkStates.single().second == null)
            }
        })
        add("prestart-encrypted-body-ignore-still-preserves-same-request-replay-rejection" to {
            VehiclePeerHarness(serviceOwned = false, query = TeslaBleQuery.BODY_STATUS).use { h ->
                h.start()
                val response = h.peer.encryptedResponse(h.requests.single().id, byteArrayOf(),
                    OfficialTeslaPeer.ResponseOptions(counter = 23))
                h.inject(response, receivedAtElapsedMs = h.clock.elapsed - 1); h.settle(); h.ignored()
                h.inject(response); h.settle()
                check(!h.session.sealed && h.observations.last().step == TeslaBleRxStep.WAITING)
                h.inject(response); h.settle()
                check(h.session.sealed && h.observations.last().step == TeslaBleRxStep.FAILED)
            }
        })
    }
}
