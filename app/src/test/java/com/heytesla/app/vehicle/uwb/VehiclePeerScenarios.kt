package com.heytesla.app.vehicle.uwb

import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.protocol.TeslaBleResponseObservation
import com.heytesla.app.vehicle.protocol.TeslaBleRxAead
import com.heytesla.app.vehicle.protocol.TeslaBleRxBodyField
import com.heytesla.app.vehicle.protocol.TeslaBleRxBodyStatus
import com.heytesla.app.vehicle.protocol.TeslaBleRxBodySummary
import com.heytesla.app.vehicle.protocol.TeslaBleRxFiraKind
import com.heytesla.app.vehicle.protocol.TeslaBleRxRequestCorrelation
import com.heytesla.app.vehicle.protocol.TeslaBleRxStep
import com.heytesla.app.vehicle.protocol.TeslaFiraProtocol
import com.heytesla.app.vehicle.protocol.TeslaUwbAppBatteryState
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceBatteryState
import com.heytesla.app.vehicle.protocol.TeslaUwbAppThermalState
import com.heytesla.app.vehicle.protocol.TeslaUwbAvailability

import com.google.protobuf.ByteString
import com.heytesla.app.peer.DeviceInfoWire
import com.heytesla.app.peer.FiraWire
import com.heytesla.app.peer.OfficialTeslaPeer
import com.tesla.generated.universalmessage.UniversalMessage
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Executed both by JUnit and the standalone host command; no BLE/RF/device access. */
internal object VehiclePeerScenarios {
    private fun request(id: Long = 1, mode: Int = 1, peers: Int = 1) = FiraWire.SessionRequest(
        id, ByteArray(peers * 2) { (it + 1).toByte() }, stsMode = mode)
    private fun VehiclePeerHarness.ready() {
        start()
        check(requests.single().fira is FiraWire.ClientPayload.Capabilities)
        check(platform.natives.isEmpty()) { "capabilities is not permission to start ranging" }
    }
    private fun VehiclePeerHarness.plan(id: Long = 1, form: String = "raw") {
        ready(); request(listOf(request(id)), form)
        check(responses().single().sessions.single().requestSessionId == id)
    }
    private fun mutate(bytes: ByteArray, change: (UniversalMessage.RoutableMessage.Builder) -> Unit): ByteArray {
        val builder = UniversalMessage.RoutableMessage.parseFrom(bytes.copyOfRange(2, bytes.size)).toBuilder()
        change(builder)
        val body = builder.build().toByteArray()
        return byteArrayOf((body.size ushr 8).toByte(), body.size.toByte()) + body
    }
    private fun VehiclePeerHarness.injectBody(body: ByteArray, form: String) {
        val current = requests.last()
        val frame = when (form) {
            "raw" -> peer.rawFira(body)
            "wrapped" -> peer.wrappedFira(body, route = current.route, requestUuid = current.uuid)
            "encrypted" -> peer.encryptedResponse(current.id, body)
            else -> error("unknown form")
        }
        inject(frame); settle()
    }
    private fun VehiclePeerHarness.noRanging() {
        check(platform.natives.isEmpty() && responses().isEmpty())
        check(uwbState.validDistanceCount == 0L && uwbState.samples.isEmpty())
    }
    private fun VehiclePeerHarness.rotateGeneratedRequest() {
        val before = requests.size
        // Exercise the real client request-generation API; generation is not SDK/vehicle acceptance.
        session.sendFira(TeslaFiraProtocol.capabilities()) {}
        settle()
        check(requests.size == before + 1)
        noRanging()
    }
    private fun VehiclePeerHarness.lastProtocol() =
        checkNotNull(gattObservations.last { it.event == TeslaUwbGattEvent.PROTOCOL }.rxResponse)
    private fun assertSummary(observed: TeslaBleResponseObservation, bytes: Int,
        status: TeslaBleRxBodyStatus, fields: List<TeslaBleRxBodyField>) {
        check(observed.bodySummary == TeslaBleRxBodySummary(bytes, status, fields))
    }
    private fun assertNoPrivateOutput(marker: String, action: () -> Unit) {
        val output = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        PrintStream(output, true, "UTF-8").use { capture ->
            try {
                System.setOut(capture); System.setErr(capture)
                action()
            } finally {
                System.setOut(previousOut); System.setErr(previousErr)
            }
        }
        check(!output.toString("UTF-8").contains(marker)) { "private diagnostic text escaped" }
    }
    private fun assertDeviceInfoModel(h: VehiclePeerHarness, info: DeviceInfoWire.Response) {
        val expectedModel = MessageDigest.getInstance("SHA-256")
            .digest(h.deviceInfoSource.modelName.toByteArray(Charsets.UTF_8))
        try { check(info.modelSha256.contentEquals(expectedModel)) { "model digest did not come from source" } }
        finally { expectedModel.fill(0) }
        fun suffix(value: String): Long {
            val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
            return try { ByteBuffer.wrap(digest, digest.size - 4, 4).int.toLong() and 0xffff_ffffL }
            finally { digest.fill(0) }
        }
        check(info.os == 1L)
        val version = info.version
        check(version.fields == (1..6).toSet())
        check(version.osVersion == 35L && version.brandHash == suffix(h.deviceInfoSource.brand) &&
            version.incrementalHash == suffix(h.deviceInfoSource.incremental))
        check(listOf(version.appMajor, version.appMinor, version.appPatch) == listOf(7L, 18L, 29L)) {
            "source app version was replaced by a default or another application's version"
        }
    }
    val cases: List<Pair<String, () -> Unit>> = buildList {
        addAll(TeslaLocationPeerScenarios.cases)
        add("device-info-all-wire-forms-use-source-and-independent-aes-peer" to {
            for (form in listOf("raw", "wrapped", "encrypted")) VehiclePeerHarness().use { h ->
                h.ready()
                val capabilities = h.requests.single()
                val deadline = h.session.deadline
                val stageDeadline = h.session.stageDeadline
                h.deviceInfo(0, form)
                check(h.deviceInfoSource.snapshotRequests.isEmpty() && h.requests.size == 1)
                h.deviceInfo(1, form)
                val info = h.deviceInfos().single()
                assertDeviceInfoModel(h, info)
                check(info.availability == 1L && info.fields == setOf(1, 2, 3, 4)) {
                    "GET_MODEL included battery/batch/auth/control fields"
                }
                check(h.deviceInfoSource.snapshotRequests == listOf(false))
                check(h.deviceInfoSource.subscriptions == 0 && h.deviceInfoSource.activeSubscriptions == 0)
                val response = h.requests.last()
                check(response.deviceInfo === info && response.fira == null && response.domain == 2)
                check(response.counter == capabilities.counter + 1 && response.expiresAt == capabilities.expiresAt)
                check(h.peerRejections.isEmpty() && h.session.deadline == deadline && h.session.stageDeadline == stageDeadline)
                h.noRanging()
                // The response is a real new authenticated client request, not a local codec result:
                // the independent peer can bind the next encrypted FiRa input to its request hash.
                h.request(listOf(request()), form = "encrypted")
                check(h.responses().size == 1 && h.platform.natives.single().starts == 1)
                check(h.uwbState.validDistanceCount == 0L)
            }
        })
        add("device-info-onchange-snapshots-once-subscribes-and-sends-real-changes" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.deviceInfo(2)
                val source = h.deviceInfoSource
                val first = h.deviceInfos().single()
                assertDeviceInfoModel(h, first)
                check(first.fields == setOf(1, 2, 3, 4, 6, 7, 8, 9))
                check(first.batteryPercent == 63L && first.batteryState == 1L && first.thermal == 4L && first.lowPower)
                for (form in listOf("wrapped", "encrypted")) h.deviceInfo(2, form)
                check(source.snapshotRequests == listOf(true, true, true) && h.deviceInfos().size == 3)
                check(source.subscriptions == 1 && source.activeSubscriptions == 1)
                source.emit(); source.emit(); h.settle()
                check(h.deviceInfos().size == 3) { "unchanged broadcast produced another response" }
                source.battery = TeslaUwbAppDeviceBatteryState(17, TeslaUwbAppBatteryState.CHARGING,
                    TeslaUwbAppThermalState.SEVERE, false)
                source.emit(); h.settle()
                val changed = h.deviceInfos().last()
                check(h.deviceInfos().size == 4 && changed.batteryPercent == 17L &&
                    changed.batteryState == 2L && changed.thermal == 6L && !changed.lowPower)
                check(9 !in changed.fields)
                source.availability = TeslaUwbAvailability.UNKNOWN
                source.battery = TeslaUwbAppDeviceBatteryState(0, TeslaUwbAppBatteryState.UNKNOWN,
                    TeslaUwbAppThermalState.UNKNOWN, false)
                source.emit(); h.settle()
                val defaults = h.deviceInfos().last()
                check(h.deviceInfos().size == 5 && defaults.availability == 0L &&
                    defaults.batteryPercent == 0L && defaults.batteryState == 0L && defaults.thermal == 0L)
                check(defaults.fields == setOf(1, 2, 4)) { "proto3 defaults were explicitly serialized" }
                val snapshots = source.snapshotRequests.size
                h.deviceInfo(0, "encrypted")
                check(source.snapshotRequests.size == snapshots && h.deviceInfos().size == 5 &&
                    source.activeSubscriptions == 1) { "NONE changed an existing ONCHANGE subscription" }
                source.battery = TeslaUwbAppDeviceBatteryState(100, TeslaUwbAppBatteryState.FULL,
                    TeslaUwbAppThermalState.NOMINAL, false)
                source.emit(); h.settle()
                check(h.deviceInfos().size == 6 && h.deviceInfos().last().batteryState == 3L)
                check(source.subscriptions == 1 && h.peerRejections.isEmpty())
                h.noRanging()
            }
        })
        add("device-info-invalid-or-conflicting-dispatch-never-consults-source" to {
            val cases = listOf(
                "raw" to DeviceInfoWire.request(3),
                "wrapped" to DeviceInfoWire.wrongWireRequest(),
                "encrypted" to DeviceInfoWire.truncatedRequest(),
                "raw" to (DeviceInfoWire.request(1) + DeviceInfoWire.request(1)),
                "encrypted" to (DeviceInfoWire.request(1) + DeviceInfoWire.request(2)),
                "wrapped" to (DeviceInfoWire.request(1) + FiraWire.request(listOf(request()))),
                "raw" to (DeviceInfoWire.request(2) + FiraWire.stop(listOf(1))),
                "encrypted" to (DeviceInfoWire.request(1) + DeviceInfoWire.authenticationRequest(2)),
            )
            for ((form, body) in cases) VehiclePeerHarness().use { h ->
                h.ready(); h.injectBody(body, form)
                check(h.session.sealed && h.stopCount == 1 && h.requests.size == 1)
                check(h.deviceInfoSource.snapshotRequests.isEmpty() && h.deviceInfoSource.subscriptions == 0)
                check(h.deviceInfoSource.closed && h.deviceInfos().isEmpty())
                h.noRanging()
            }
        })
        add("device-info-wrapped-binding-does-not-inherit-fira-plaintext-exceptions" to {
            for (mismatch in listOf("route", "uuid", "domain")) VehiclePeerHarness().use { h ->
                h.ready()
                val current = h.requests.single()
                val response = h.peer.wrappedFira(DeviceInfoWire.request(1),
                    domain = if (mismatch == "domain") 3 else 2,
                    route = if (mismatch == "route") ByteArray(16) { 7 } else current.route,
                    requestUuid = if (mismatch == "uuid") ByteArray(16) { 7 } else current.uuid)
                h.inject(response); h.settle()
                check(!h.session.sealed && h.deviceInfoSource.snapshotRequests.isEmpty() && h.requests.size == 1)
                h.deviceInfo(1, "wrapped")
                check(h.deviceInfos().size == 1)
                h.noRanging()
            }
            // Route omission must not erase an explicitly supplied UUID's binding. The two
            // accepted legacy forms still reach the real source/subscription and encrypted TX.
            for (binding in listOf("wrong", "matching", "empty")) VehiclePeerHarness().use { h ->
                h.ready()
                val current = h.requests.single()
                val uuid = when (binding) {
                    "wrong" -> ByteArray(16) { 7 }
                    "matching" -> current.uuid
                    else -> byteArrayOf()
                }
                val response = h.peer.wrappedFira(DeviceInfoWire.request(2),
                    requestUuid = uuid, envelopeMutation = { it.clearToDestination() })
                h.inject(response); h.settle()
                val source = h.deviceInfoSource
                check(!h.session.sealed)
                if (binding == "wrong") {
                    check(h.requests.size == 1 && h.deviceInfos().isEmpty())
                    check(source.snapshotRequests.isEmpty() && source.subscriptions == 0 &&
                        source.activeSubscriptions == 0) { "missing route bypassed an explicit UUID mismatch" }
                } else {
                    check(h.requests.size == 2 && h.deviceInfos().size == 1)
                    check(source.snapshotRequests == listOf(true) && source.subscriptions == 1 &&
                        source.activeSubscriptions == 1) { "accepted route-less request never reached the source" }
                    check(h.deviceInfos().single().batteryPercent == 63L && h.peerRejections.isEmpty())
                }
                h.noRanging()
            }
        })
        add("authentication-unlock-drive-requests-never-become-device-info-or-grants" to {
            for (level in 1L..2L) VehiclePeerHarness().use { h ->
                h.ready()
                h.injectBody(DeviceInfoWire.authenticationRequest(level), if (level == 1L) "raw" else "encrypted")
                check(!h.session.sealed && h.requests.size == 1 && h.deviceInfos().isEmpty())
                check(h.deviceInfoSource.snapshotRequests.isEmpty() && h.deviceInfoSource.subscriptions == 0)
                h.deviceInfo(1, "encrypted")
                check(h.deviceInfos().single().fields == setOf(1, 2, 3, 4))
                h.noRanging()
            }
        })
        add("device-info-request-queued-behind-fira-preserves-original-frame-completion" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.autoCallbacks = false; h.session.negotiatedMtu(23)
                h.request(listOf(request()))
                h.deviceInfo(1)
                check(h.deviceInfoSource.snapshotRequests.isEmpty() && h.platform.natives.isEmpty())
                var chunks = 0
                while (h.responses().isEmpty()) {
                    check(chunks++ < 100 && h.completeOne())
                    check(h.deviceInfoSource.snapshotRequests.isEmpty() && h.platform.natives.isEmpty())
                }
                // Last FiRa chunk has reached the peer, but its native write callback is still pending.
                check(h.deviceInfos().isEmpty() && h.completeOne())
                check(h.platform.natives.single().starts == 1 && h.deviceInfoSource.snapshotRequests == listOf(false))
                check(h.deviceInfos().isEmpty()) { "metadata bypassed MTU-fragmented TX" }
                h.completeAll()
                check(h.deviceInfos().size == 1 && h.responses().size == 1 &&
                    h.platform.natives.single().starts == 1 && !h.session.sealed)
                check(h.requests.last().counter > h.requests.first { it.fira is FiraWire.ClientPayload.Response }.counter)
            }
        })
        add("device-info-source-events-coalesce-behind-fira-without-stealing-completion" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.deviceInfo(2); h.autoCallbacks = false; h.session.negotiatedMtu(23)
                h.request(listOf(request()))
                val source = h.deviceInfoSource
                for (percent in listOf(41, 9)) {
                    source.battery = source.battery.copy(percent = percent)
                    source.emit(); h.settle()
                }
                check(h.deviceInfos().size == 1 && h.platform.natives.isEmpty())
                var chunks = 0
                while (h.responses().isEmpty()) check(chunks++ < 100 && h.completeOne())
                check(h.platform.natives.isEmpty() && h.completeOne())
                check(h.platform.natives.single().starts == 1 && h.deviceInfos().size == 1)
                h.completeAll()
                check(h.deviceInfos().size == 2 && h.deviceInfos().last().batteryPercent == 9L)
                check(h.platform.natives.single().starts == 1 && source.subscriptions == 1)
            }
        })
        for (termination in listOf("seal", "off", "owner-denied", "permission", "close")) {
            add("device-info-$termination-releases-subscription-and-rejects-late-events" to {
                VehiclePeerHarness().use { h ->
                    h.plan(); h.deviceInfo(2)
                    val native = h.platform.natives.single()
                    val source = h.deviceInfoSource
                    val staleEvent = source.captureChanged()
                    h.autoCallbacks = false; h.session.negotiatedMtu(23)
                    source.battery = source.battery.copy(percent = 51)
                    source.emit(); h.settle()
                    check(h.deviceInfos().size == 1) { "metadata frame unexpectedly completed without callbacks" }
                    source.battery = source.battery.copy(percent = 39)
                    source.emit() // A second owner-queue event is still pending at the terminal boundary.
                    when (termination) {
                        "seal" -> check(h.session.seal())
                        "off" -> { h.ownerAllowed = false; h.session.cancel() }
                        "owner-denied" -> { h.ownerAllowed = false; check(!h.session.checkReadiness()) }
                        "permission" -> { h.platform.permission = false; native.result() }
                        "close" -> h.session.close { check(!it) }
                    }
                    h.settle()
                    check(h.session.sealed && source.closed && source.activeSubscriptions == 0)
                    if (termination == "permission") check(h.stopReason == "RANGING_PERMISSION_REQUIRED")
                    val beforeLateState = h.uwbState
                    val writes = h.requests.size
                    val snapshots = source.snapshotRequests.size
                    staleEvent(); staleEvent(); source.emit()
                    native.result(); h.inject(h.peer.rawFira(DeviceInfoWire.request(2)))
                    h.completeAll(); h.settle()
                    check(h.requests.size == writes && h.deviceInfos().size == 1 &&
                        source.snapshotRequests.size == snapshots && source.subscriptions == 1)
                    check(h.uwbState.validDistanceCount == 0L && native.starts == 1)
                    check(h.uwbState.samples == beforeLateState.samples &&
                        h.uwbState.lastReceivedSample == beforeLateState.lastReceivedSample &&
                        h.uwbState.reason == beforeLateState.reason && h.uwbState.livePeerIndices.isEmpty()) {
                        "sealed listener published a late ranging transform"
                    }
                    if (termination == "seal" || termination == "owner-denied") {
                        h.session.close { check(!it) }; h.settle()
                    }
                }
            })
        }
        add("device-info-replies-do-not-refresh-first-fira-request-deadline" to {
            VehiclePeerHarness().use { h ->
                h.ready()
                val deadline = h.session.deadline
                val stageDeadline = h.session.stageDeadline
                h.clock.elapsed += 29_000; h.deviceInfo(2)
                h.clock.elapsed += 999
                h.deviceInfoSource.battery = h.deviceInfoSource.battery.copy(percent = 12)
                h.deviceInfoSource.emit(); h.settle()
                check(h.deviceInfos().size == 2 && !h.session.sealed)
                check(h.session.deadline == deadline && h.session.stageDeadline == stageDeadline)
                h.noRanging()
                h.clock.elapsed++; h.session.checkReadiness(); h.settle()
                check(h.session.sealed && h.stopStage == TeslaKeyStage.TIMED_OUT &&
                    h.deviceInfoSource.activeSubscriptions == 0)
            }
        })
        add("device-info-replies-do-not-refresh-first-distance-receive-deadline" to {
            VehiclePeerHarness().use { h ->
                h.plan(); h.deviceInfo(2)
                val deadline = h.session.deadline
                val stageDeadline = h.session.stageDeadline
                h.clock.elapsed += 59_000
                h.deviceInfoSource.battery = h.deviceInfoSource.battery.copy(percent = 12)
                h.deviceInfoSource.emit(); h.settle()
                h.clock.elapsed += 999; h.deviceInfo(1, "encrypted")
                check(h.deviceInfos().size == 3 && !h.session.sealed && h.uwbState.validDistanceCount == 0L)
                check(h.session.deadline == deadline && h.session.stageDeadline == stageDeadline)
                h.clock.elapsed++; h.session.checkReadiness(); h.settle()
                check(h.session.sealed && h.stopStage == TeslaKeyStage.TIMED_OUT &&
                    h.stopReason == "NO_DISTANCE_RECEIVED")
                check(h.platform.natives.single().closes == 1 && h.deviceInfoSource.activeSubscriptions == 0)
                check(h.requests.count { it.fira is FiraWire.ClientPayload.Stopped } == 1)
            }
        })
        add("all-five-peers-stop-at-thirteen-seconds-awaits-native-close-and-one-stopped-tx" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.request(listOf(request(peers = 5)))
                val native = h.platform.natives.single()
                check(h.uwbState.startedPeerCount == 5 && h.uwbState.validDistanceCount == 0L)
                h.platform.closeCompletes = false
                h.clock.elapsed += 13_000
                repeat(4) { index ->
                    native.callback.onStopped(native.peerIds[index], TeslaUwbNativeRanging.UWB); h.settle()
                    check(native.closes == 0 && h.stopCount == 0 && !h.session.sealed)
                    check(h.requests.none { it.fira is FiraWire.ClientPayload.Stopped })
                }
                native.callback.onStopped(native.peerIds[4], TeslaUwbNativeRanging.UWB); h.settle()
                check(native.closes == 1 && h.stopCount == 0 && h.cleanupFailed == null && !h.session.sealed)
                check(h.requests.none { it.fira is FiraWire.ClientPayload.Stopped })
                h.autoCallbacks = false
                native.callback.onClosed(4); h.settle()
                check(h.uwbState.closedReason == 4 && h.requests.count { it.fira is FiraWire.ClientPayload.Stopped } == 1)
                check(!h.session.sealed && h.stopCount == 0) { "SDK acceptance was treated as stopped frame completion" }
                h.completeAll()
                check(h.session.sealed && h.stopCount == 1 && h.stopStage == TeslaKeyStage.FAILED &&
                    h.stopReason == "NO_DISTANCE_RECEIVED" && h.cleanupFailed == false)
                check(h.uwbState.validDistanceCount == 0L && h.uwbState.samples.isEmpty())
                val stopped = h.requests.mapNotNull { it.fira as? FiraWire.ClientPayload.Stopped }.single()
                check(stopped.endpoints == listOf(1))
            }
        })
        for (form in listOf("raw", "wrapped", "encrypted")) add("authenticated-$form-request-response" to {
            VehiclePeerHarness().use { h ->
                h.plan(form = form)
                val response = h.responses().single()
                val data = checkNotNull(response.sessions.single().data)
                val native = h.platform.natives.single()
                check(native.starts == 1)
                check(native.parameters.localAddress.contentEquals(data.localAddress))
                check(native.parameters.sessionKeyInfo.contentEquals(data.sts))
                check((native.parameters.firaSessionId.toLong() and 0xffffffffL) == data.firaSessionId)
                check(native.parameters.channel.toLong() == data.channel)
                check(native.parameters.preambleIndex.toLong() == data.preamble)
                check(response.epochSeconds == 1_800_000_000L && !response.keyIdPresent)
                check(h.uwbState.validDistanceCount == 0L) { "opened/started must not fabricate distance" }
                native.result(); h.settle()
                check(h.uwbState.validDistanceCount == 1L && h.uwbState.samples.single().meters == 2.5)
            }
        })
        add("uint32-request-ids-are-not-stop-enums" to {
            VehiclePeerHarness().use { h ->
                h.ready()
                val ids = listOf(0L, 11L, 12L, 0x7fffffffL, 0x80000000L, 0xffffffffL)
                h.request(ids.map { request(it) })
                check(h.responses().single().sessions.map { it.requestSessionId } == ids)
                check(h.platform.natives.size == ids.size)
                check(h.platform.natives.all { it.starts == 1 })
            }
        })
        add("static-provisioned-individual-sts-and-config" to {
            for ((mode, size, config) in listOf(Triple(1, 8, 2), Triple(2, 16, 4), Triple(3, 16, 5))) {
                VehiclePeerHarness().use { h ->
                    h.ready(); h.request(listOf(request(mode = mode)))
                    val data = checkNotNull(h.responses().single().sessions.single().data)
                    val native = h.platform.natives.single()
                    check(data.sts.size == size && native.parameters.frameworkConfigId == config)
                    check(native.parameters.peerAddresses.single().contentEquals(byteArrayOf(1, 2)))
                }
            }
        })
        add("every-fira-frame-split-preserves-one-request" to {
            val payload = FiraWire.request(listOf(request()))
            val framedSize = payload.size + 2
            for (split in 0..framedSize) VehiclePeerHarness().use { h ->
                h.ready(); h.request(listOf(request()), split = split)
                check(h.responses().size == 1 && h.platform.natives.single().starts == 1)
            }
        })
        add("queued-stop-before-response-completion-never-starts-rf" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.autoCallbacks = false
                h.request(listOf(request()))
                check(h.platform.natives.isEmpty())
                h.stop(listOf(1))
                check(h.platform.natives.isEmpty())
                h.completeAll()
                check(h.platform.natives.sumOf { it.starts } == 0) { "queued stop lost to response completion" }
                check(h.requests.any { (it.fira as? FiraWire.ClientPayload.Stopped)?.endpoints == listOf(1) })
            }
        })
        add("coalesced-request-stop-never-starts-rf" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.autoCallbacks = false
                h.inject(h.peer.rawFira(FiraWire.request(listOf(request()))) + h.peer.rawFira(FiraWire.stop(listOf(1))))
                h.settle(); h.completeAll()
                check(h.platform.natives.sumOf { it.starts } == 0)
            }
        })
        add("sdk-acceptance-is-not-frame-completion" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.autoCallbacks = false; h.request(listOf(request()))
                check(h.responses().size == 1 && h.platform.natives.isEmpty())
                h.completeAll(); check(h.platform.natives.single().starts == 1)
            }
        })
        add("callback-failure-never-starts-rf" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.callbackStatus = 133; h.request(listOf(request()))
                check(h.session.sealed && h.platform.natives.isEmpty() && h.stopCount == 1)
            }
        })
        add("busy-not-accepted-retries-within-original-deadline" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.sdkStatus = 201; h.request(listOf(request()))
                check(h.platform.natives.isEmpty() && h.responses().isEmpty())
                h.sdkStatus = 0; h.clock.elapsed += 100; h.settle()
                check(h.platform.natives.single().starts == 1)
            }
        })
        add("missing-write-callback-times-out-without-rf" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.autoCallbacks = false; h.request(listOf(request()))
                h.clock.elapsed += 5_000; h.session.checkReadiness(); h.settle()
                check(h.session.sealed && h.stopStage == TeslaKeyStage.TIMED_OUT && h.platform.natives.isEmpty())
                h.completeAll(); check(h.stopCount == 1 && h.platform.natives.isEmpty())
            }
        })
        add("exact-zero-stop-preserves-other-endpoint" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.request(listOf(request(0), request(11)))
                val survivor = h.platform.natives[1]
                h.stop(listOf(0), packed = true)
                check(h.platform.natives[0].closes == 1 && survivor.closes == 0)
                survivor.result(); h.settle()
                check(h.uwbState.validDistanceCount == 1L)
                check(h.requests.any { (it.fira as? FiraWire.ClientPayload.Stopped)?.endpoints == listOf(0) })
            }
        })
        add("stopped-peer-late-distance-cannot-revive" to {
            VehiclePeerHarness().use { h ->
                h.plan(); val native = h.platform.natives.single(); h.stop(listOf(1))
                native.result(); h.settle()
                check(h.uwbState.validDistanceCount == 0L && h.stopCount == 1)
            }
        })
        for (ids in listOf(emptyList(), listOf(9), listOf(1, 9), listOf(12))) {
            add("own-policy-invalid-stop-${ids.joinToString("-").ifEmpty { "empty" }}" to {
                VehiclePeerHarness().use { h ->
                    h.plan(); val before = h.requests.size; h.stop(ids)
                    check(h.session.sealed && h.platform.natives.single().closes == 1)
                    check(h.requests.size == before) { "invalid stop must not send stopped" }
                }
            })
        }
        add("own-policy-duplicate-request-ids-fail-before-response" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.request(listOf(request(0), request(0)))
                check(h.session.sealed && h.responses().isEmpty() && h.platform.natives.isEmpty())
            }
        })
        add("invalid-results-do-not-create-distance-or-extend-window" to {
            VehiclePeerHarness().use { h ->
                h.plan(); val native = h.platform.natives.single()
                native.result(meters = Double.NaN); native.result(meters = -1.0)
                native.result(confidence = 99); native.result(id = UUID.randomUUID())
                native.result(technology = 99); h.settle()
                check(h.uwbState.validDistanceCount == 0L && h.uwbState.invalidResultCount == 5L)
                h.clock.elapsed += 60_000; h.session.checkReadiness(); h.settle()
                check(h.session.sealed)
            }
        })
        add("service-valid-distance-switches-to-silence-deadline" to {
            VehiclePeerHarness().use { h ->
                h.plan(); val native = h.platform.natives.single(); native.result(); h.settle()
                h.clock.elapsed += 9_999; h.session.checkReadiness(); check(!h.session.sealed)
                native.result(); h.settle(); h.clock.elapsed += 10_000
                h.session.checkReadiness(); h.settle(); check(h.session.sealed)
            }
        })
        add("manual-distance-does-not-extend-fixed-window" to {
            VehiclePeerHarness(serviceOwned = false).use { h ->
                h.plan(); h.clock.elapsed += 59_999
                h.platform.natives.single().result(); h.settle()
                h.clock.elapsed++; h.session.checkReadiness(); h.settle(); check(h.session.sealed)
            }
        })
        add("owner-off-seals-pending-write-and-late-callback" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.autoCallbacks = false; h.request(listOf(request()))
                h.ownerAllowed = false; h.session.cancel(); h.completeAll()
                check(h.stopCount == 1 && h.platform.natives.isEmpty() && h.cleanupFailed == false)
            }
        })
        add("native-close-return-is-not-cleanup-completion" to {
            VehiclePeerHarness().use { h ->
                h.plan(); h.platform.closeCompletes = false; h.session.cancel(); h.settle()
                check(h.cleanupFailed == null)
                h.platform.natives.single().callback.onClosed(0); h.settle()
                check(h.cleanupFailed == false && h.stopCount == 1)
            }
        })
        add("close-timeout-remains-failed-after-late-onClosed" to {
            VehiclePeerHarness().use { h ->
                h.plan(); h.platform.closeCompletes = false; h.session.cancel(); h.settle()
                h.clock.elapsed += 5_000; h.settle(); check(h.cleanupFailed == true)
                h.platform.natives.single().callback.onClosed(0); h.settle()
                check(h.cleanupFailed == true && h.stopCount == 1)
            }
        })
        add("gatt-close-failure-retains-cleanup-failure" to {
            VehiclePeerHarness().use { h ->
                h.plan(); h.gattCloses = false; h.session.cancel(); h.settle()
                check(h.cleanupFailed == true)
            }
        })
        add("unregister-failure-retains-cleanup-failure" to {
            VehiclePeerHarness().use { h ->
                h.plan(); h.platform.unregisterThrows = true; h.session.cancel(); h.settle()
                check(h.cleanupFailed == true)
            }
        })
        add("negotiation-timeout-not-extended-by-unrecognized-rx" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.clock.elapsed += 29_999
                val deadline = h.session.deadline
                val stageDeadline = h.session.stageDeadline
                h.inject(h.peer.rawFira(byteArrayOf(8, 1))); h.settle()
                val unknown = h.lastProtocol()
                assertSummary(unknown, 2, TeslaBleRxBodyStatus.COMPLETE, listOf(TeslaBleRxBodyField(1, 0, 1)))
                check(unknown.requestCorrelation == null)
                check(!h.session.sealed && h.session.deadline == deadline && h.session.stageDeadline == stageDeadline)
                h.noRanging()
                h.clock.elapsed++; h.session.checkReadiness(); h.settle()
                check(h.session.sealed && h.stopStage == TeslaKeyStage.TIMED_OUT)
            }
        })
        add("malformed-framing-poisons-and-seals-consumer" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.inject(byteArrayOf(0x10, 0x01)); h.settle()
                check(h.session.sealed && h.stopReason == "RX_FRAME_INVALID")
                h.inject(h.peer.rawFira(FiraWire.request(listOf(request())))); h.settle()
                check(h.platform.natives.isEmpty())
            }
        })
        for (mutation in listOf("route", "domain", "uuid", "tag", "ciphertext")) {
            add("encrypted-response-rejects-$mutation" to {
                VehiclePeerHarness().use { h ->
                    h.ready()
                    val primaryBefore = h.observations.size
                    val consumedBefore = h.gattObservations.count { it.event == TeslaUwbGattEvent.PROTOCOL }
                    val response = h.peer.encryptedResponse(h.requests.last().id, FiraWire.request(listOf(request())))
                    h.inject(mutate(response) { b -> when (mutation) {
                        "route" -> b.toDestination = b.toDestination.toBuilder().setRoutingAddress(ByteString.copyFrom(ByteArray(16) { 7 })).build()
                        "domain" -> b.fromDestination = b.fromDestination.toBuilder().setDomainValue(3).build()
                        "uuid" -> b.requestUuid = ByteString.copyFrom(ByteArray(16) { 7 })
                        "tag" -> b.signatureData = b.signatureData.toBuilder().setAESGCMResponseData(
                            b.signatureData.getAESGCMResponseData().toBuilder().setTag(ByteString.copyFrom(ByteArray(16)))).build()
                        "ciphertext" -> b.protobufMessageAsBytes = ByteString.copyFrom(byteArrayOf(1, 2, 3))
                    } })
                    h.settle(); check(h.platform.natives.isEmpty() && h.responses().isEmpty())
                    check(h.observations.size == primaryBefore + 1)
                    check(h.gattObservations.count { it.event == TeslaUwbGattEvent.PROTOCOL } == consumedBefore + 1)
                    val observed = h.lastProtocol()
                    check(observed.bodySummary == null && observed.firaKind == null)
                    check(observed.requestCorrelation == if (mutation in listOf("tag", "ciphertext"))
                        TeslaBleRxRequestCorrelation.CURRENT else TeslaBleRxRequestCorrelation.UNMATCHED)
                    check(observed.aead == if (mutation in listOf("tag", "ciphertext"))
                        TeslaBleRxAead.FAILED else TeslaBleRxAead.NOT_ATTEMPTED)
                    if (mutation == "domain") check(observed.disposition == "DOMAIN_MISMATCH" && observed.fromDomain == 3)
                    check(h.uwbState.validDistanceCount == 0L)
                }
            })
        }
        add("multi-peer-raw-results-retain-peer-identity" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.request(listOf(request(peers = 2)))
                val native = h.platform.natives.single()
                check(native.parameters.peerAddresses.map { it.toList() } == listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 4)))
                native.result(index = 1, meters = 4.0)
                native.result(index = 0, meters = 1.0); h.settle()
                check(h.uwbState.samples.associate { it.peerIndex to it.meters } == mapOf(0 to 1.0, 1 to 4.0))
                native.callback.onStopped(native.peerIds[0], TeslaUwbNativeRanging.UWB); h.settle()
                native.result(index = 0, meters = 8.0); native.result(index = 1, meters = 3.0); h.settle()
                check(h.uwbState.livePeerIndices == setOf(1))
                check(h.uwbState.lastReceivedSample?.peerIndex == 1)
            }
        })
        add("tx-waiting-rx-queue-overflow-fails-closed" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.autoCallbacks = false; h.request(listOf(request()))
                repeat(9) { h.inject(h.peer.rawFira(byteArrayOf(8, 1))) }
                h.settle(); h.completeAll()
                check(h.session.sealed && h.platform.natives.isEmpty() && h.stopCount == 1)
            }
        })
        add("seventeen-coalesced-frames-fail-closed" to {
            VehiclePeerHarness().use { h ->
                h.ready()
                val frame = h.peer.rawFira(byteArrayOf(8, 1))
                h.inject(ByteArray(0).let { empty -> (1..17).fold(empty) { bytes, _ -> bytes + frame } })
                h.settle()
                check(h.session.sealed && h.platform.natives.isEmpty())
            }
        })
        add("hmac-mismatch-does-not-authorize-capabilities" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                h.start()
                val reply = h.peer.sessionInfoResponse(h.handshakes.single().contextId, h.clock.elapsed,
                    OfficialTeslaPeer.SessionInfoOptions(tagTransform = { tag -> tag.apply { this[0] = (this[0].toInt() xor 1).toByte() } }))
                h.inject(reply); h.settle()
                check(h.requests.isEmpty() && h.platform.natives.isEmpty())
                h.clock.elapsed += 15_000; h.session.checkReadiness(); h.settle()
                check(h.session.sealed)
            }
        })
        add("session-clock-includes-known-consumer-queue-delay" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                h.start()
                val receivedAt = h.clock.elapsed
                val reply = h.peer.sessionInfoResponse(h.handshakes.single().contextId, receivedAt)
                h.clock.elapsed += 5_000
                h.session.notify(reply, receivedAt); h.settle()
                check(h.peerRejections.isEmpty()) { "independent peer rejected delayed request: ${h.peerRejections}" }
                check(h.requests.single().fira is FiraWire.ClientPayload.Capabilities) {
                    "delayed handshake did not produce capabilities"
                }
                check(h.requests.single().expiresAt == 115L) { "known queue delay shortened request lifetime" }
                check(!h.session.sealed)
            }
        })
        add("old-request-response-cannot-be-rebound-to-new-request" to {
            VehiclePeerHarness().use { h ->
                h.ready()
                val old = h.requests.single()
                h.request(listOf(request()))
                val current = h.requests.last()
                val delayed = h.peer.encryptedResponse(old.id, FiraWire.stop(listOf(1)),
                    OfficialTeslaPeer.ResponseOptions(route = current.route, requestUuid = current.uuid))
                h.inject(delayed); h.settle()
                check(h.session.sealed && h.platform.natives.single().closes == 1) {
                    "old request hash must not authenticate a new route"
                }
            }
        })
        add("aead-replay-cannot-start-a-second-session" to {
            VehiclePeerHarness().use { h ->
                h.ready()
                val response = h.peer.encryptedResponse(h.requests.last().id, byteArrayOf(8, 1))
                h.inject(response); h.settle(); h.inject(response); h.settle()
                check(h.session.sealed && h.platform.natives.isEmpty())
            }
        })
        add("empty-body-is-observed-but-header-only-ack-is-not-body-or-authentication" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoHandshake = false)).use { h ->
                h.start()
                val reply = h.peer.sessionInfoResponse(h.handshakes.single().contextId, h.clock.elapsed)
                val ack = mutate(reply) { b ->
                    b.clearSessionInfo(); b.clearSignatureData()
                    b.setSignedMessageStatus(UniversalMessage.MessageStatus.getDefaultInstance())
                }
                h.inject(ack); h.settle()
                val waiting = h.lastProtocol()
                check(waiting.step == TeslaBleRxStep.WAITING && waiting.disposition == "ACK_WAITING")
                check(waiting.aead == TeslaBleRxAead.NOT_ATTEMPTED && waiting.bodySummary == null)
                check(waiting.requestCorrelation == TeslaBleRxRequestCorrelation.CURRENT)
                check(h.requests.isEmpty()); h.noRanging()
                h.inject(reply); h.settle()
                check(h.requests.single().fira is FiraWire.ClientPayload.Capabilities)
                check(h.lastProtocol().bodySummary == null)
                for (form in listOf("wrapped", "encrypted")) {
                    h.injectBody(byteArrayOf(), form)
                    val empty = h.lastProtocol()
                    check(empty.step == TeslaBleRxStep.WAITING && empty.firaKind == TeslaBleRxFiraKind.UNRECOGNIZED)
                    check(empty.requestCorrelation == TeslaBleRxRequestCorrelation.CURRENT)
                    check(empty.aead == if (form == "encrypted") TeslaBleRxAead.VERIFIED else TeslaBleRxAead.NOT_ATTEMPTED)
                    assertSummary(empty, 0, TeslaBleRxBodyStatus.COMPLETE, emptyList())
                    h.noRanging()
                }
            }
        })
        for (form in listOf("raw", "wrapped", "encrypted")) {
            add("unknown-$form-body-retains-only-field-wire-length-shape" to {
                VehiclePeerHarness().use { h ->
                    h.ready()
                    val markerA = "SYNTHETIC-PRIVATE-A".toByteArray(Charsets.US_ASCII)
                    val markerB = "SYNTHETIC-PRIVATE-B".toByteArray(Charsets.US_ASCII)
                    fun body(marker: ByteArray, fill: Byte) =
                        byteArrayOf(0xe8.toByte(), 3, 0xac.toByte(), 2, 0xf1.toByte(), 3) +
                            ByteArray(8) { fill } + byteArrayOf(0xfa.toByte(), 3, marker.size.toByte()) +
                            marker + byteArrayOf(0x85.toByte(), 4) + ByteArray(4) { fill }
                    val fields = listOf(TeslaBleRxBodyField(61, 0, 2), TeslaBleRxBodyField(62, 1, 8),
                        TeslaBleRxBodyField(63, 2, markerA.size), TeslaBleRxBodyField(64, 5, 4))
                    val firstBody = body(markerA, 17)
                    h.injectBody(firstBody, form)
                    val first = h.lastProtocol()
                    check(first.step == TeslaBleRxStep.WAITING && first.firaKind == TeslaBleRxFiraKind.UNRECOGNIZED)
                    check(first.aead == if (form == "encrypted") TeslaBleRxAead.VERIFIED else TeslaBleRxAead.NOT_ATTEMPTED)
                    check(first.requestCorrelation == if (form == "raw") null else TeslaBleRxRequestCorrelation.CURRENT)
                    assertSummary(first, 23 + markerA.size, TeslaBleRxBodyStatus.COMPLETE, fields)
                    h.injectBody(body(markerB, 99), form)
                    check(h.lastProtocol().bodySummary == first.bodySummary) { "values must not affect shape" }
                    for (marker in listOf(String(markerA, Charsets.US_ASCII), String(markerB, Charsets.US_ASCII))) {
                        check(!h.observations.toString().contains(marker))
                        check(!h.gattObservations.toString().contains(marker))
                    }
                    check(first.bodySummary?.fields == fields) { "later receives mutated an earlier snapshot" }
                    h.noRanging()
                }
            })
        }
        for (count in listOf(8, 9)) {
            add("unknown-body-$count-fields-${if (count == 8) "complete" else "truncated"}-at-observation-budget" to {
                VehiclePeerHarness().use { h ->
                    h.ready()
                    val body = ByteArray(count * 3) { index -> when (index % 3) {
                        0 -> 0xe8.toByte()
                        1 -> 3
                        else -> 1
                    } }
                    h.injectBody(body, "encrypted")
                    val observed = h.lastProtocol()
                    check(observed.step == TeslaBleRxStep.WAITING && observed.aead == TeslaBleRxAead.VERIFIED)
                    assertSummary(observed, count * 3,
                        if (count == 8) TeslaBleRxBodyStatus.COMPLETE else TeslaBleRxBodyStatus.TRUNCATED,
                        List(8) { TeslaBleRxBodyField(61, 0, 1) })
                    h.noRanging()
                }
            })
        }
        add("unknown-body-groups-are-unsupported-without-recording-group-or-nested-values" to {
            VehiclePeerHarness().use { h ->
                h.ready()
                h.injectBody(byteArrayOf(0xe8.toByte(), 3, 1, 0xf3.toByte(), 3, 0xf4.toByte(), 3), "wrapped")
                val observed = h.lastProtocol()
                check(observed.step == TeslaBleRxStep.WAITING && observed.firaKind == TeslaBleRxFiraKind.UNRECOGNIZED)
                assertSummary(observed, 7, TeslaBleRxBodyStatus.UNSUPPORTED,
                    listOf(TeslaBleRxBodyField(61, 0, 1)))
                h.noRanging()
            }
        })
        add("malformed-decoded-body-records-safe-prefix-and-keeps-original-failure" to {
            VehiclePeerHarness().use { h ->
                h.ready()
                h.injectBody(byteArrayOf(0xe8.toByte(), 3, 1, 0xfa.toByte(), 3, 4, 1), "encrypted")
                val invalid = h.lastProtocol()
                check(invalid.step == TeslaBleRxStep.FAILED && invalid.firaKind == TeslaBleRxFiraKind.INVALID)
                check(invalid.aead == TeslaBleRxAead.VERIFIED)
                check(invalid.requestCorrelation == TeslaBleRxRequestCorrelation.CURRENT)
                assertSummary(invalid, 7, TeslaBleRxBodyStatus.MALFORMED,
                    listOf(TeslaBleRxBodyField(61, 0, 1)))
                check(h.session.sealed && h.stopCount == 1)
                h.noRanging()
            }
        })
        add("unknown-body-route-exclusion-raw-and-outer-malformed-reset-gatt-observations" to {
            VehiclePeerHarness().use { h ->
                h.ready()
                val body = byteArrayOf(0xe8.toByte(), 3, 1)
                h.injectBody(body, "encrypted")
                val prior = h.lastProtocol()
                val rejected = h.peer.encryptedResponse(h.requests.last().id, body,
                    OfficialTeslaPeer.ResponseOptions(route = ByteArray(16) { 7 }))
                h.inject(rejected); h.settle()
                val route = h.lastProtocol()
                check(route.step == TeslaBleRxStep.WAITING && route.disposition == "ROUTE_MISMATCH")
                check(route.aead == TeslaBleRxAead.NOT_ATTEMPTED && route.bodySummary == null)
                check(route.requestCorrelation == TeslaBleRxRequestCorrelation.UNMATCHED)
                h.injectBody(byteArrayOf(0xfa.toByte(), 3, 0), "raw")
                val raw = h.lastProtocol()
                check(raw.requestCorrelation == null && raw.aead == TeslaBleRxAead.NOT_ATTEMPTED)
                assertSummary(raw, 3, TeslaBleRxBodyStatus.COMPLETE, listOf(TeslaBleRxBodyField(63, 2, 0)))
                h.inject(h.peer.rawFira(byteArrayOf(0x80.toByte()))); h.settle()
                val malformed = h.lastProtocol()
                check(malformed.step == TeslaBleRxStep.FAILED && malformed.inputKind == null)
                check(malformed.bodySummary == null && malformed.requestCorrelation == null && malformed.firaKind == null)
                assertSummary(prior, 3, TeslaBleRxBodyStatus.COMPLETE, listOf(TeslaBleRxBodyField(61, 0, 1)))
                check(prior.requestCorrelation == TeslaBleRxRequestCorrelation.CURRENT)
                h.noRanging()
            }
        })
        for (identity in listOf("current", "previous", "current-route-previous-uuid",
            "previous-route-current-uuid", "cross-domain", "current-omitted-vcsec-uuid",
            "previous-omitted-vcsec-uuid", "two-generations-old")) {
            add("generated-request-header-correlation-$identity-does-not-relax-acceptance" to {
                VehiclePeerHarness().use { h ->
                    h.ready()
                    val old = h.requests.single()
                    h.rotateGeneratedRequest()
                    if (identity == "two-generations-old") h.rotateGeneratedRequest()
                    val current = h.requests.last()
                    val context = if (identity in listOf("previous", "previous-omitted-vcsec-uuid",
                        "two-generations-old")) old else current
                    val options = when (identity) {
                        "current-route-previous-uuid" -> OfficialTeslaPeer.ResponseOptions(requestUuid = old.uuid)
                        "previous-route-current-uuid" -> OfficialTeslaPeer.ResponseOptions(route = old.route)
                        "cross-domain" -> OfficialTeslaPeer.ResponseOptions(domain = 3)
                        "current-omitted-vcsec-uuid", "previous-omitted-vcsec-uuid" ->
                            OfficialTeslaPeer.ResponseOptions(requestUuid = byteArrayOf())
                        else -> OfficialTeslaPeer.ResponseOptions()
                    }
                    val primaryBefore = h.observations.size
                    val consumedBefore = h.gattObservations.count { it.event == TeslaUwbGattEvent.PROTOCOL }
                    h.inject(h.peer.encryptedResponse(context.id, byteArrayOf(0xe8.toByte(), 3, 1), options))
                    h.settle()
                    check(h.observations.size == primaryBefore + 1)
                    check(h.gattObservations.count { it.event == TeslaUwbGattEvent.PROTOCOL } == consumedBefore + 1)
                    val observed = h.lastProtocol()
                    val acceptedCurrent = identity == "current" || identity == "current-omitted-vcsec-uuid"
                    val previous = identity == "previous" || identity == "previous-omitted-vcsec-uuid"
                    check(observed.requestCorrelation == when {
                        acceptedCurrent -> TeslaBleRxRequestCorrelation.CURRENT
                        previous -> TeslaBleRxRequestCorrelation.PREVIOUS
                        else -> TeslaBleRxRequestCorrelation.UNMATCHED
                    })
                    check(observed.step == TeslaBleRxStep.WAITING && !h.session.sealed)
                    if (identity == "cross-domain") check(observed.disposition == "DOMAIN_MISMATCH" && observed.fromDomain == 3)
                    if (acceptedCurrent) {
                        check(observed.aead == TeslaBleRxAead.VERIFIED)
                        assertSummary(observed, 3, TeslaBleRxBodyStatus.COMPLETE,
                            listOf(TeslaBleRxBodyField(61, 0, 1)))
                    } else {
                        check(observed.aead == TeslaBleRxAead.NOT_ATTEMPTED && observed.bodySummary == null)
                        check(observed.firaKind == null)
                        check(observed.disposition == when (identity) {
                            "cross-domain" -> "DOMAIN_MISMATCH"
                            "current-route-previous-uuid" -> "REQUEST_UUID_MISMATCH"
                            else -> "ROUTE_MISMATCH"
                        })
                    }
                    h.noRanging()
                }
            })
        }
        add("wrapped-previous-header-is-observation-only-and-plaintext-acceptance-is-unchanged" to {
            VehiclePeerHarness().use { h ->
                h.ready()
                val old = h.requests.single()
                h.rotateGeneratedRequest()
                h.inject(h.peer.wrappedFira(byteArrayOf(0xe8.toByte(), 3, 1),
                    route = old.route, requestUuid = old.uuid)); h.settle()
                val observed = h.lastProtocol()
                check(observed.requestCorrelation == TeslaBleRxRequestCorrelation.PREVIOUS)
                check(observed.step == TeslaBleRxStep.WAITING && observed.aead == TeslaBleRxAead.NOT_ATTEMPTED)
                assertSummary(observed, 3, TeslaBleRxBodyStatus.COMPLETE,
                    listOf(TeslaBleRxBodyField(61, 0, 1)))
                h.noRanging()
            }
        })
        add("actual-gatt-consumer-fragment-protocol-and-terminal-snapshots-have-separate-denominators" to {
            VehiclePeerHarness().use { h ->
                h.ready()
                h.injectBody(byteArrayOf(0xe8.toByte(), 3, 1), "encrypted")
                val previous = h.lastProtocol()
                val before = h.gattObservations.size
                h.inject(h.peer.rawFira(byteArrayOf(0xfa.toByte(), 3, 0)), split = 1); h.settle()
                val events = h.gattObservations.drop(before)
                val notifications = events.filter { it.event == TeslaUwbGattEvent.RX }
                check(notifications.map { it.rxNotificationCount } == listOf(3L, 4L))
                check(notifications.map { it.rxNotificationFrameCount } == listOf(0, 1))
                check(notifications.map { it.rxFrameCount } == listOf(2L, 3L))
                check(notifications.all { it.rxProtocolCount == 2L && it.rxResponse == previous })
                val protocol = events.single { it.event == TeslaUwbGattEvent.PROTOCOL }
                check(protocol.rxNotificationCount == 4L && protocol.rxFrameCount == 3L && protocol.rxProtocolCount == 3L)
                check(protocol.rxProtocolReceivedElapsedMs == h.clock.elapsed)
                check(protocol.rxProtocolObservedElapsedMs == h.clock.elapsed)
                check(protocol.rxResponse == h.observations.last())
                check(protocol.rxResponse?.requestCorrelation == null)
                assertSummary(checkNotNull(protocol.rxResponse), 3, TeslaBleRxBodyStatus.COMPLETE,
                    listOf(TeslaBleRxBodyField(63, 2, 0)))
                h.session.cancel(); h.settle()
                val terminal = h.gattObservations.last()
                check(terminal.event == TeslaUwbGattEvent.LOCAL_CLOSED && terminal.rxResponse == protocol.rxResponse)
                check(terminal.rxProtocolCount == 3L && terminal.rxFrameCount == 3L && terminal.rxNotificationCount == 4L)
                check(h.gattObservations.filter { it.event == TeslaUwbGattEvent.PROTOCOL }
                    .map { it.rxProtocolCount } == listOf(1L, 2L, 3L))
                h.noRanging()
            }
        })
        add("observer-absent-keeps-production-consumer-and-no-response-diagnostics" to {
            VehiclePeerHarness(observeResponses = false).use { h ->
                h.ready()
                h.rotateGeneratedRequest()
                h.injectBody(byteArrayOf(0xe8.toByte(), 3, 1), "encrypted")
                check(h.observations.isEmpty())
                check(h.gattObservations.all { it.rxResponse == null })
                check(h.gattObservations.last { it.event == TeslaUwbGattEvent.PROTOCOL }.rxProtocolCount == 2L)
                check(!h.session.sealed); h.noRanging()
            }
        })
        add("queued-protocol-uses-receipt-time-and-counts-only-actual-consumption" to {
            VehiclePeerHarness().use { h ->
                h.ready(); h.autoCallbacks = false
                h.rotateGeneratedRequest()
                val receivedAt = h.clock.elapsed
                val current = h.requests.last()
                h.inject(h.peer.encryptedResponse(current.id, byteArrayOf(0xe8.toByte(), 3, 1)))
                h.settle()
                val queued = h.gattObservations.last { it.event == TeslaUwbGattEvent.RX }
                check(queued.rxFrameCount == 2L && queued.rxProtocolCount == 1L)
                check(queued.rxResponse?.bodySummary == null)
                check(h.observations.size == 1 && h.session.pendingRxResponse == null)
                h.clock.elapsed += 500
                h.completeAll()
                val consumed = h.gattObservations.last { it.event == TeslaUwbGattEvent.PROTOCOL }
                check(consumed.rxProtocolCount == 2L && consumed.rxFrameCount == 2L)
                check(consumed.rxProtocolReceivedElapsedMs == receivedAt)
                check(consumed.rxProtocolObservedElapsedMs == receivedAt + 500)
                check(consumed.rxResponse?.requestCorrelation == TeslaBleRxRequestCorrelation.CURRENT)
                assertSummary(checkNotNull(consumed.rxResponse), 3, TeslaBleRxBodyStatus.COMPLETE,
                    listOf(TeslaBleRxBodyField(61, 0, 1)))
                check(h.session.pendingRxResponse == null)
                h.noRanging()
            }
        })
        for (size in listOf(4096, 4097)) {
            add("raw-unknown-$size-byte-frame-keeps-existing-decoder-cap" to {
                VehiclePeerHarness().use { h ->
                    h.ready()
                    val body = byteArrayOf(0xfa.toByte(), 3, 0xfc.toByte(), 0x1f) + ByteArray(4092)
                    h.inject(h.peer.rawFira(if (size == 4096) body else body + byteArrayOf(0)))
                    h.settle()
                    if (size == 4096) {
                        val observed = h.lastProtocol()
                        check(observed.step == TeslaBleRxStep.WAITING && observed.requestCorrelation == null)
                        assertSummary(observed, 4096, TeslaBleRxBodyStatus.COMPLETE,
                            listOf(TeslaBleRxBodyField(63, 2, 4092)))
                        check(!h.session.sealed)
                    } else {
                        val rejected = h.gattObservations.last { it.event == TeslaUwbGattEvent.RX }
                        check(rejected.rxDecoderFailed == true && rejected.rxProtocolCount == 1L)
                        check(rejected.rxNotificationFrameCount == null && rejected.rxFrameCount == 1L)
                        check(h.session.sealed && h.stopReason == "RX_FRAME_INVALID" && h.observations.size == 1)
                    }
                    h.noRanging()
                }
            })
        }
        for (failure in listOf("response-observer", "gatt-listener")) {
            add("$failure-failure-discards-produced-input-without-private-exception-logging-or-rf" to {
                VehiclePeerHarness().use { h ->
                    h.ready()
                    val marker = "SYNTHETIC-PRIVATE-EXCEPTION-MARKER"
                    if (failure == "response-observer") h.responseObserver = { throw IllegalStateException(marker) }
                    else h.gattObserver = { if (it.event == TeslaUwbGattEvent.PROTOCOL) throw IllegalStateException(marker) }
                    assertNoPrivateOutput(marker) { h.request(listOf(request())) }
                    check(h.session.sealed && h.stopCount == 1 && h.stopReason == "PROTOCOL_RESPONSE_INVALID")
                    if (failure == "response-observer") {
                        check(h.gattObservations.last { it.event == TeslaUwbGattEvent.PROTOCOL }.rxResponse == null)
                    }
                    check(!h.observations.toString().contains(marker) && !h.gattObservations.toString().contains(marker))
                    h.noRanging()
                }
            })
        }
    }
}

internal object VehiclePeerSimulator {
    @JvmStatic fun main(args: Array<String>) {
        val selected = VehiclePeerScenarios.cases.filter { args.isEmpty() || it.first in args }
        require(selected.isNotEmpty()) { "no matching scenario" }
        var passed = 0
        var failed = 0
        for ((name, run) in selected) {
            try {
                run(); passed++
                println("PASS $name")
            } catch (failure: Exception) {
                failed++
                println("FAIL $name type=${failure.javaClass.simpleName}")
            }
        }
        check(failed == 0) { "VEHICLE_PEER_SMOKE_FAILED passed=$passed failed=$failed" }
        println("VEHICLE_PEER_SMOKE_OK scenarios=$passed deviceAccess=false rf=false")
    }
}
