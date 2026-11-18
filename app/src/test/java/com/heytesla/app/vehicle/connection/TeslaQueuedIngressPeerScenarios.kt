package com.heytesla.app.vehicle.connection

import com.heytesla.app.peer.OfficialTeslaPeer
import com.heytesla.app.vehicle.protocol.TeslaBleRxAead
import com.heytesla.app.vehicle.protocol.TeslaGear

internal object TeslaQueuedIngressPeerScenarios {
    val cases: List<Pair<String, () -> Unit>> = listOf(
        "queued-drive-response-keeps-ingress-time-until-write-completion" to {
            VehiclePeerHarness().use { h ->
                h.start()
                h.autoCallbacks = false
                h.replyBody(open = false); h.completeOne()
                check(h.requests.size == 2 && h.session.writePump.writePending)
                val received = h.clock.elapsed
                h.replyDrive()
                check(h.parkStates.isEmpty())
                h.clock.elapsed += 1_500; h.wallEpochMs += 1_500
                h.completeAll()
                check(h.parkStates.single().first?.receivedAtElapsedRealtime == received)
                val protocol = h.gattObservations.last { it.event == TeslaGattEvent.PROTOCOL }
                check(protocol.rxProtocolReceivedElapsedMs == received)
                check(protocol.rxProtocolObservedElapsedMs == h.clock.elapsed)
                h.advance(3_501)
                check(h.session.sealed && h.stopReason == "CONNECTION_P_RECEIPT_STALE")
            }
        },
        "command-during-queued-monitor-write-drains-original-query-before-fresh-drive" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive()
                h.autoCallbacks = false; h.advance(2_000); h.completeOne()
                check(h.session.writePump.writePending && h.requests.size == 3)
                check(h.command())
                h.replyDrive()
                check(h.requests.size == 3)
                h.completeAll()
                check(h.requests.size == 4 && h.requests.last().kind == OfficialTeslaPeer.RequestKind.DRIVE)
                check(h.requests.none { it.kind == OfficialTeslaPeer.RequestKind.FRUNK })
            }
        },
        "queued-drive-aead-failure-stops-pending-command-without-action" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive(); h.autoCallbacks = false
                h.advance(2_000); h.completeOne()
                check(h.command())
                h.replyDrive(options = OfficialTeslaPeer.ResponseOptions(tagTransform = { tag ->
                    tag.apply { this[0] = (this[0].toInt() xor 1).toByte() }
                }))
                check(h.parkStates.size == 1)
                h.completeAll()
                check(h.session.sealed && h.observations.last().aead == TeslaBleRxAead.FAILED)
                check(h.requests.size == 3 && h.cleanupFailed == false)
            }
        },
        "coalesced-nonpark-then-park-cannot-revive-sealed-session" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive(); h.advance(2_000)
                val id = h.requests.last().id
                val first = h.peer.encryptedResponse(id, h.driveBody(TeslaGear.D))
                val second = h.peer.encryptedResponse(id, h.driveBody())
                val combined = first + second
                h.inject(combined); h.settle()
                check(h.session.sealed && h.parkStates.last().first?.gear == TeslaGear.D)
                check(h.parkStates.count { it.second == null } == 1)
                check(h.stopCount == 1 && h.cleanupFailed == false)
                combined.fill(0); first.fill(0); second.fill(0)
            }
        },
        "busy-unaccepted-write-retries-once-without-duplicate-query-or-new-deadline" to {
            VehiclePeerHarness().use { h ->
                h.sdkStatus = 201; h.start()
                check(h.handshakes.isEmpty() && h.session.writePump.queued)
                val deadline = h.session.deadline
                val stageDeadline = h.session.stageDeadline
                h.sdkStatus = 0; h.advance(100)
                check(h.handshakes.size == 1 && h.requests.size == 1)
                check(h.session.deadline == deadline && h.session.stageDeadline == stageDeadline + 100)
                h.finishBodyPreflight()
                h.replyDrive()
                check(h.parkStates.single().second == null)
            }
        },
        "queued-off-erases-owned-notification-and-late-callback-never-authenticates" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                h.autoCallbacks = false; h.start()
                val notification = h.peer.sessionInfoResponse(h.handshakes.single().contextId, h.clock.elapsed)
                val write = h.owner.pending
                h.session.notify(notification, h.clock.elapsed)
                check(notification.all { it == 0.toByte() })
                check(h.observations.isEmpty())
                h.session.cancel(reason = "SERVICE_OFF")
                h.completeAll(); h.session.completeWrite(write, 0, h.clock.elapsed)
                check(h.session.sealed && h.requests.isEmpty() && h.stopCount == 1)
                check(h.parkStates.none { it.second == null } && h.cleanupFailed == false)
            }
        },
        "queued-protocol-observer-cancellation-discards-produced-request" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                h.start()
                h.responseObserver = { if (it.disposition == "SESSION_READY") h.session.cancel(reason = "SERVICE_OFF") }
                h.inject(h.peer.sessionInfoResponse(h.handshakes.single().contextId, h.clock.elapsed)); h.settle()
                check(h.session.sealed && h.requests.isEmpty() && h.cleanupFailed == false)
            }
        },
    )
}
