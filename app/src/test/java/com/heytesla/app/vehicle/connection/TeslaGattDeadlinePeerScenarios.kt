package com.heytesla.app.vehicle.connection

import com.heytesla.app.peer.OfficialTeslaPeer
import com.heytesla.app.vehicle.key.TeslaBleKeySession
import com.heytesla.app.vehicle.key.TeslaKeyProbeState
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.protocol.TeslaBleQuery

internal object TeslaGattDeadlinePeerScenarios {
    private data class Order(val name: String, val operation: Long, val stage: Long, val cause: TeslaGattDeadlineCause)
    val cases: List<Pair<String, () -> Unit>> = buildList {
        val orders = listOf(
            Order("operation-first", 3_000, 7_000, TeslaGattDeadlineCause.OPERATION),
            Order("stage-first", 7_000, 3_000, TeslaGattDeadlineCause.PROTOCOL_STAGE),
            Order("write-first", 8_000, 7_000, TeslaGattDeadlineCause.WRITE),
            Order("operation-stage-tie", 3_000, 3_000, TeslaGattDeadlineCause.OPERATION),
            Order("operation-write-tie", 5_000, 7_000, TeslaGattDeadlineCause.OPERATION),
            Order("write-stage-tie", 8_000, 5_000, TeslaGattDeadlineCause.WRITE),
            Order("all-tie", 5_000, 5_000, TeslaGattDeadlineCause.OPERATION),
        )
        for (order in orders) for (late in listOf(false, true)) {
            add("gatt-deadline-${order.name}-${if (late) "all-overdue" else "exact-boundary"}" to {
                VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                    h.autoCallbacks = false; h.start()
                    val start = h.clock.elapsed
                    check(h.session.writePump.writePending)
                    h.session.deadline = start + order.operation
                    h.session.setStageDeadline(start + order.stage)
                    val first = minOf(h.session.deadline, h.session.stageDeadline, h.session.writeDeadline)
                    h.clock.elapsed = first - 1
                    check(h.session.checkReadiness())
                    h.clock.elapsed = if (late) start + maxOf(order.operation, order.stage, 5_000) + 1 else first
                    check(!h.session.checkReadiness())
                    h.completeAll(); h.settle()
                    check(h.session.sealed && h.stopCount == 1 && h.stopStage == TeslaKeyStage.TIMED_OUT)
                    check(h.gattObservations.last().deadlineCause == order.cause)
                    check(h.gattObservations.last().event == TeslaGattEvent.LOCAL_CLOSED)
                    h.session.expireDeadline(TeslaGattDeadlineCause.OPERATION)
                    h.session.close { check(!it) }
                    check(h.stopCount == 1 && h.requests.isEmpty())
                }
            })
        }
        for (loss in listOf("off", "owner", "permission")) {
            add("$loss-at-deadline-keeps-original-owner-reason-and-no-invented-timeout" to {
                VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                    h.start(); h.clock.elapsed = h.session.stageDeadline
                    h.stoppingObserver = { h.session.expireDeadline(TeslaGattDeadlineCause.OPERATION) }
                    when (loss) {
                        "off" -> h.session.cancel(reason = "SERVICE_OFF")
                        "owner" -> { h.ownerAllowed = false; check(!h.session.checkReadiness()) }
                        else -> { h.bluetoothPermission = false; check(!h.session.checkReadiness()) }
                    }
                    check(h.session.sealed && h.stopCount == 1 && h.cleanupFailed == false)
                    check(h.gattObservations.all { it.deadlineCause == null })
                }
            })
        }
        add("fresh-park-watchdog-runs-without-rx-and-prevents-command" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive(); h.advance(2_000)
                h.advance(3_001)
                check(h.session.sealed && h.stopReason == "CONNECTION_P_RECEIPT_STALE")
                check(h.parkStates.last().second == h.stopReason && !h.command())
            }
        })
        add("elapsed-clock-reversal-does-not-extend-monitor-or-command" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive(); h.advance(1_000)
                h.clock.elapsed--
                check(!h.session.checkReadiness())
                check(h.stopReason == "CONNECTION_TIME_REVERSED" && !h.command())
            }
        })
        add("accepted-command-keeps-original-consent-deadline" to {
            VehiclePeerHarness().use { h ->
                h.startDrive(); h.replyDrive(); check(h.command()); h.settle()
                val original = h.session.deadline
                h.replyDrive()
                check(h.requests.last().kind == OfficialTeslaPeer.RequestKind.FRUNK)
                // A late scheduler wake must not fabricate a BODY fallback beyond the consent window.
                h.clock.elapsed = original; h.wallEpochMs += 40_000
                check(!h.session.checkReadiness())
                check(h.session.deadline == original && h.stopReason == "FRUNK_COMMAND_EXPIRED")
                check(h.requests.count { it.kind == OfficialTeslaPeer.RequestKind.BODY } == 1)
            }
        })
        add("manual-deadline-without-gatt-observer-still-terminates-once" to {
            val clock = PeerClock()
            var stops = 0
            var closed = false
            var keyState = TeslaKeyProbeState(stage = TeslaKeyStage.CONNECTING)
            lateinit var session: TeslaBleKeySession
            session = TeslaBleKeySession(deadline = clock.elapsed + 180_000,
                register = false, query = TeslaBleQuery.BODY_STATUS, now = { clock.elapsed },
                post = clock::post, postDelayed = clock::postDelayed, remove = clock::remove,
                listener = object : TeslaBleKeySession.Listener {
                    override var state: TeslaKeyProbeState
                        get() = keyState
                        set(value) { keyState = value }
                    override fun allowed() = true
                    override fun onStop(stage: TeslaKeyStage, reason: String?) {
                        stops++; check(stage == TeslaKeyStage.TIMED_OUT && reason == "DEADLINE_EXPIRED")
                        session.close { closed = !it }
                    }
                }, transport = { null })
            session.watch(); clock.elapsed = session.deadline; clock.runReady()
            check(session.sealed && closed && stops == 1 && session.gattObservation == null)
            session.expireDeadline(TeslaGattDeadlineCause.WRITE)
            check(stops == 1)
        })
    }
}
