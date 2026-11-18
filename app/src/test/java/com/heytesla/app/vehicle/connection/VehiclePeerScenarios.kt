package com.heytesla.app.vehicle.connection

import com.heytesla.app.peer.OfficialTeslaPeer
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.protocol.TeslaGear

/** Actual framed consumer, native owner, write pump and independent authenticated wire peer. */
internal object VehiclePeerScenarios {
    val cases: List<Pair<String, () -> Unit>> = buildList {
        addAll(TeslaQueuedIngressPeerScenarios.cases)
        addAll(TeslaPrestartIngressPeerScenarios.cases)
        addAll(TeslaGattDeadlinePeerScenarios.cases)
        addAll(TeslaConnectionSecurityPeerScenarios.cases)
        addAll(TeslaProductVoicePeerScenarios.cases)
        addAll(TeslaConnectionWakePeerScenarios.cases)
        addAll(TeslaFrunkReceivePeerScenarios.cases)
        add("command-publication-failure-seals-consumed-command-and-cleans-before-action" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive()
                h.executingObserver = { error("synthetic command publication failure") }
                check(!h.command())
                check(h.session.sealed && h.stopCount == 1 && h.cleanupFailed == false)
                check(h.stopReason == "CONNECTION_PROTOCOL_START_FAILED")
                check(!h.command() && h.requests.none { it.kind == OfficialTeslaPeer.RequestKind.FRUNK })
            }
        })
        add("fresh-park-notifies-only-after-authenticated-drive-and-polls-after-two-seconds" to {
            VehiclePeerHarness().use { h ->
                h.startDrive()
                check(h.requests.map { it.kind } == listOf(OfficialTeslaPeer.RequestKind.BODY, OfficialTeslaPeer.RequestKind.DRIVE))
                check(h.parkStates.isEmpty() && !h.command())
                h.replyDrive()
                check(h.parkStates.single().first?.gear == TeslaGear.P && h.parkStates.single().second == null)
                check(h.session.deadline == Long.MAX_VALUE)
                h.advance(1_999)
                check(h.driveRequests.size == 1)
                h.advance(1)
                check(h.driveRequests.size == 2 && h.handshakes.size == 3)
                check(!h.driveRequests[0].route.contentEquals(h.driveRequests[1].route))
                check(!h.driveRequests[0].uuid.contentEquals(h.driveRequests[1].uuid))
                check(h.driveRequests[1].counter > h.driveRequests[0].counter)
            }
        })
        add("long-connection-has-no-fixed-total-timeout-but-each-park-is-new" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive()
                repeat(310) {
                    h.advance(TeslaParkMonitor.POLL_MS)
                    h.replyDrive()
                    check(!h.session.sealed && h.session.deadline == Long.MAX_VALUE)
                }
                check(h.clock.elapsed > 600_000 && h.parkStates.size == 311)
                check(h.handshakes.size == h.requests.size)
            }
        })
        for (gear in listOf(TeslaGear.D, TeslaGear.R, TeslaGear.N, TeslaGear.UNKNOWN)) {
            add("authenticated-$gear-seals-before-parent-stop-and-native-cleanup" to {
                VehiclePeerHarness().use { h ->
                    h.startDrive(); h.replyDrive(); h.advance(2_000)
                    h.stoppingObserver = {
                        check(h.session.sealed && h.cleanupFailed == null)
                        check(!h.command())
                    }
                    h.replyDrive(gear)
                    check(h.session.sealed && h.stopCount == 1 && h.cleanupFailed == false)
                    check(h.parkStates.last().first?.gear == gear && h.parkStates.last().second != null)
                    check(h.transitions.takeLast(2) == listOf("stopping", "close"))
                    check(h.requests.none { it.kind == OfficialTeslaPeer.RequestKind.FRUNK })
                }
            })
        }
        for (kind in listOf("missing", "old", "future")) {
            add("park-source-$kind-never-arms-voice" to {
                VehiclePeerHarness().use { h ->
                    h.startDrive()
                    val source = when (kind) {
                        "missing" -> null
                        "old" -> h.wallEpochMs - 10_001
                        else -> h.wallEpochMs + 2_001
                    }
                    h.replyDrive(source = source)
                    check(h.session.sealed && h.parkStates.none { it.second == null })
                    check(!h.command())
                }
            })
        }
        add("park-source-can-expire-before-receipt-and-watchdog-does-not-renew-it" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive(source = h.wallEpochMs - 9_900)
                h.advance(250)
                check(h.session.sealed && h.stopReason == "CONNECTION_P_SOURCE_TIME_STALE")
                check(h.parkStates.last().second == h.stopReason)
            }
        })
        add("late-valid-drive-cannot-revive-expired-park" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive(); h.advance(2_000)
                val query = h.requests.last()
                h.clock.elapsed += 3_001
                h.wallEpochMs += 3_001
                h.replyDrive(request = query, receivedAt = h.clock.elapsed - 1)
                check(h.session.sealed && h.stopReason == "CONNECTION_P_RECEIPT_STALE")
                check(h.parkStates.count { it.second == null } == 1)
            }
        })
        add("command-handoff-waits-for-outstanding-query-and-rechecks-drive-before-one-action" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive(); h.advance(2_000)
                val pending = h.requests.last()
                check(h.command()); check(!h.command()); h.settle()
                check(h.requests.size == 3 && h.driveRequests.size == 2 &&
                    h.requests.first().kind == OfficialTeslaPeer.RequestKind.BODY)
                h.replyDrive(request = pending)
                check(h.requests.size == 4 && h.requests.last().kind == OfficialTeslaPeer.RequestKind.DRIVE)
                val commandDeadline = h.session.deadline
                h.replyDrive()
                check(h.requests.last().kind == OfficialTeslaPeer.RequestKind.FRUNK)
                check(h.session.frunkPolicy?.commandFrameComplete == true)
                h.inject(h.peer.encryptedResponse(h.requests.last().id, byteArrayOf())); h.settle()
                check(h.requests.last().kind == OfficialTeslaPeer.RequestKind.BODY)
                h.replyBody()
                check(h.stopStage == TeslaKeyStage.COMPLETE && h.session.frunkPolicy?.openObserved == true)
                check(h.requests.count { it.kind == OfficialTeslaPeer.RequestKind.FRUNK } == 1)
                check(h.session.deadline == commandDeadline && !h.command())
            }
        })
        for (pending in listOf(false, true)) {
            add("command-handoff-nonpark-${if (pending) "drained-monitor" else "fresh-command-query"}-sends-no-action" to {
                VehiclePeerHarness().use { h ->
                    h.startDrive(); h.replyDrive()
                    if (pending) h.advance(2_000)
                    check(h.command()); h.settle()
                    h.replyDrive(TeslaGear.D)
                    check(h.session.sealed && h.stopCount == 1)
                    check(h.requests.none { it.kind == OfficialTeslaPeer.RequestKind.FRUNK })
                }
            })
        }
        add("command-is-not-a-new-gatt-and-stale-action-handshake-cannot-send" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false, allowFrunk = true)).use { h ->
                fun hello() {
                    h.inject(h.peer.sessionInfoResponse(h.handshakes.last().contextId, h.clock.elapsed)); h.settle()
                }
                h.start(); hello(); h.finishBodyPreflight(); h.replyDrive()
                val handle = h.owner.handle
                check(h.command()); h.settle(); hello(); h.replyDrive()
                check(h.handshakes.last().domain == 2)
                h.advance(5_001)
                hello()
                check(h.session.sealed && h.requests.none { it.kind == OfficialTeslaPeer.RequestKind.FRUNK })
                check(h.cleanupFailed == false && handle != null)
            }
        })
        add("command-actuator-rechecks-park-at-sdk-boundary" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive(); check(h.command()); h.settle()
                // Stop at the callback boundary that queued the actuator; the next SDK call is still pending.
                var aged = false
                h.responseObserver = null
                h.beforeWriteIssued = {
                    if (h.session.frunkPolicy?.mayHaveSent == true && !aged) {
                        aged = true
                        h.clock.elapsed += 5_001
                        h.wallEpochMs += 5_001
                    }
                }
                h.replyDrive()
                check(aged && h.session.sealed)
                check(h.requests.none { it.kind == OfficialTeslaPeer.RequestKind.FRUNK })
            }
        })
        for (failed in listOf(false, true)) {
            add("connection-stop-cleanup-${if (failed) "uncertain-retained" else "confirmed"}-late-rx-cannot-rearm" to {
                VehiclePeerHarness().use { h ->
                    h.startDrive(); h.replyDrive(); h.advance(2_000)
                    val pending = h.requests.last()
                    val late = h.peer.encryptedResponse(pending.id, h.driveBody())
                    h.gattCloses = !failed
                    h.session.cancel(reason = "SERVICE_OFF")
                    val count = h.parkStates.size
                    h.inject(late); h.completeAll(); h.advance(20_000)
                    check(h.stopCount == 1 && h.cleanupFailed == failed && h.parkStates.size == count)
                    check((h.owner.handle != null) == failed)
                    check(!h.command())
                    h.session.close { check(it == failed) }
                }
            })
        }
        add("park-callback-reentrant-off-cannot-schedule-next-query" to {
            VehiclePeerHarness().use { h ->
                h.startDrive()
                h.parkObserver = { _, reason -> if (reason == null) h.session.cancel(reason = "SERVICE_OFF") }
                h.replyDrive(); h.advance(2_000)
                check(h.session.sealed && h.stopCount == 1 && h.requests.size == 2)
            }
        })
        add("stop-callback-exception-still-closes-gatt-and-retains-uncertain-cleanup" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive()
                h.stoppingObserver = { error("synthetic callback failure") }
                h.session.cancel(reason = "SERVICE_OFF")
                check(h.stopCount == 1 && h.cleanupFailed == true && h.owner.handle == null)
                check(h.parkStates.last().second == "SERVICE_OFF")
            }
        })
    }
}

internal object VehiclePeerSimulator {
    @JvmStatic fun main(args: Array<String>) {
        val selected = VehiclePeerScenarios.cases.filter { args.isEmpty() || it.first in args }
        require(selected.isNotEmpty()) { "no matching scenario" }
        var passed = 0
        var failed = 0
        for ((name, run) in selected) {
            try { run(); passed++; println("PASS $name") }
            catch (failure: Throwable) {
                failed++
                println("FAIL $name type=${failure.javaClass.simpleName}")
            }
        }
        check(failed == 0) { "VEHICLE_PEER_SMOKE_FAILED passed=$passed failed=$failed" }
        println("VEHICLE_PEER_SMOKE_OK scenarios=$passed deviceAccess=false rf=false " +
            "syntheticFinalText=true actualStt=false actualMicrophone=false")
    }
}
