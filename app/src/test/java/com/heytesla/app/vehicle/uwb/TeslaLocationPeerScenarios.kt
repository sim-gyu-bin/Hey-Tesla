package com.heytesla.app.vehicle.uwb

import com.heytesla.app.vehicle.navigation.NavigationPoint

import com.heytesla.app.peer.FiraWire
import com.heytesla.app.peer.LocationWire
import com.heytesla.app.peer.OfficialTeslaPeer
import com.tesla.generated.universalmessage.UniversalMessage

/** All scenarios drive the real framed consumer, not just a location parser or a second connection. */
internal object TeslaLocationPeerScenarios {
    private fun fix() = LocationWire.Fix(nativeLatitude = 0f, nativeLongitude = 0f,
        nativeType = 2, estimatedValid = true)
    private fun request(id: Long = 1) = FiraWire.SessionRequest(id, byteArrayOf(1, 2), stsMode = 1)
    private fun VehiclePeerHarness.locationRequest() = requests.single { it.locationQuery }
    private fun VehiclePeerHarness.reply(value: LocationWire.Fix?, options: OfficialTeslaPeer.ResponseOptions = OfficialTeslaPeer.ResponseOptions()) {
        inject(peer.encryptedResponse(locationRequest().id, LocationWire.response(value), options)); settle()
    }
    private fun VehiclePeerHarness.assertPrimaryAlive() {
        check(!session.sealed && stopCount == 0 && peerRejections.isEmpty())
    }
    private fun VehiclePeerHarness.locationHandshake() = handshakes.single { it.domain == 3 }
    private fun mutate(bytes: ByteArray, action: (UniversalMessage.RoutableMessage.Builder) -> Unit): ByteArray {
        val builder = UniversalMessage.RoutableMessage.parseFrom(bytes.copyOfRange(2, bytes.size)).toBuilder()
        action(builder)
        return OfficialTeslaPeer.frame(builder.build().toByteArray())
    }

    val cases: List<Pair<String, () -> Unit>> = buildList {
        add("location-domain3-exclusive-read-independent-key-counter-and-explicit-zero" to {
            VehiclePeerHarness(coordinatesRequested = true).use { h ->
                h.start()
                check(h.handshakes.map { it.domain } == listOf(2, 3))
                check(h.requests.map { it.domain } == listOf(2, 3)) { "primary capabilities did not precede location" }
                val primary = h.requests.single { it.domain == 2 }
                val location = h.locationRequest()
                check(primary.counter == 1L && location.counter == 1L)
                check(location.fira == null && location.deviceInfo == null && location.flags == 2L)
                check(LocationWire.isExclusiveLocationRequest(location.plaintext))
                check(!primary.route.contentEquals(location.route) && !primary.uuid.contentEquals(location.uuid))
                val stage = h.uwbState.stage
                val deadline = h.session.deadline
                val stageDeadline = h.session.stageDeadline
                h.reply(LocationWire.Fix(nativeLatitude = 0f, nativeLongitude = 0f, nativeType = 2,
                    estimatedValid = true, stateSeconds = 1_799_999_900L, stateNanos = 123_000_000))
                val result = h.coordinateResults.single()
                val observed = checkNotNull(result.observation)
                check(result.reason == null && observed.point == NavigationPoint(0.0, 0.0) && !observed.isRaw)
                check(observed.fixEpochMs == 1_800_000_000_000L && observed.stateEpochMs == 1_799_999_900_123L)
                check(observed.receivedAtElapsedMs == h.clock.elapsed)
                check(h.uwbState.stage == stage && h.session.deadline == deadline && h.session.stageDeadline == stageDeadline)
                check(h.uwbState.validDistanceCount == 0L && h.platform.natives.isEmpty())
                h.request(listOf(request()), form = "encrypted")
                check(h.platform.natives.single().starts == 1)
                h.assertPrimaryAlive()
            }
        })
        add("location-presence-coordinate-family-validity-and-wgs-selection" to {
            class Case(val value: LocationWire.Fix, val expected: NavigationPoint?, val raw: Boolean = false)
            val cases = listOf(
                Case(LocationWire.Fix(latitude = 3f, longitude = 4f, nativeLatitude = 0f, nativeLongitude = 0f,
                    nativeType = 2, estimatedValid = true), NavigationPoint(0.0, 0.0)),
                Case(LocationWire.Fix(nativeLatitude = 3f, nativeLongitude = 4f, nativeType = 2,
                    estimatedValid = false, rawLatitude = 0f, rawLongitude = 0f), NavigationPoint(0.0, 0.0), true),
                Case(LocationWire.Fix(nativeLatitude = 0f, nativeLongitude = 0f, nativeType = 1,
                    latitude = 3f, longitude = 4f, estimatedValid = true), null),
                Case(LocationWire.Fix(nativeLatitude = 0f, nativeType = 2, latitude = 3f,
                    longitude = 4f, estimatedValid = true), null),
                Case(LocationWire.Fix(latitude = 0f, longitude = 0f, estimatedValid = true), NavigationPoint(0.0, 0.0)),
                Case(LocationWire.Fix(latitude = 0f, estimatedValid = true), null),
                Case(LocationWire.Fix(estimatedValid = true), null),
                Case(LocationWire.Fix(latitude = Float.NaN, longitude = 0f, estimatedValid = true), null),
                Case(LocationWire.Fix(latitude = 91f, longitude = 0f, estimatedValid = true), null),
                Case(LocationWire.Fix(correctedLatitude = 0f, correctedLongitude = 0f, estimatedValid = true), null),
                Case(LocationWire.Fix(nativeLatitude = 0f, nativeLongitude = 0f, nativeType = 2), null),
                Case(LocationWire.Fix(rawLatitude = 0f, rawLongitude = 0f), NavigationPoint(0.0, 0.0), true),
                Case(LocationWire.Fix(nativeLatitude = 0f, rawLongitude = 0f, estimatedValid = false), null),
                Case(LocationWire.Fix(nativeLatitude = 0f, nativeLongitude = 0f, nativeType = 2,
                    nativeSupported = false, estimatedValid = true), null),
                Case(LocationWire.Fix(nativeLatitude = 0f, nativeLongitude = 0f, estimatedValid = false), null),
                Case(LocationWire.Fix(nativeLatitude = 0f, nativeLongitude = 0f, estimatedValid = true), null),
            )
            for (case in cases) VehiclePeerHarness(coordinatesRequested = true).use { h ->
                h.start(); h.reply(case.value)
                val result = h.coordinateResults.single()
                check(result.observation?.point == case.expected)
                if (case.expected == null) check(result.reason?.startsWith("LOCATION_") == true)
                else check(result.reason == null && result.observation?.isRaw == case.raw)
                h.assertPrimaryAlive()
            }
        })
        add("location-gps-seconds-missing-unsigned-overflow-future-and-state-time-separation" to {
            val invalid = listOf(
                null to "LOCATION_FIX_TIME_MISSING",
                -1L to "LOCATION_FIX_TIME_OVERFLOW",
                Long.MAX_VALUE to "LOCATION_FIX_TIME_OVERFLOW",
                1_800_000_003L to "LOCATION_FIX_TIME_FUTURE",
            )
            for ((seconds, reason) in invalid) VehiclePeerHarness(coordinatesRequested = true).use { h ->
                h.start(); h.reply(LocationWire.Fix(rawLatitude = 0f, rawLongitude = 0f, gpsSeconds = seconds,
                    stateSeconds = 1_800_000_000L))
                check(h.coordinateResults.single().let { it.observation == null && it.reason == reason })
                h.assertPrimaryAlive()
            }
            for (seconds in listOf(0L, 1_799_999_000L, 1_800_000_002L)) VehiclePeerHarness(coordinatesRequested = true).use { h ->
                h.start()
                val frame = h.peer.encryptedResponse(h.locationRequest().id, LocationWire.response(LocationWire.Fix(
                    rawLatitude = 0f, rawLongitude = 0f, gpsSeconds = seconds,
                    stateSeconds = 253_402_300_800L)))
                h.inject(frame.copyOfRange(0, 3))
                h.clock.elapsed += 50
                h.inject(frame.copyOfRange(3, frame.size)); h.settle()
                val observed = checkNotNull(h.coordinateResults.single().observation)
                check(observed.fixEpochMs == seconds * 1_000L && observed.stateEpochMs == null)
                check(observed.receivedAtElapsedMs == h.clock.elapsed)
                h.assertPrimaryAlive() // An old authenticated GPS fix is not relabelled as fresh receipt time.
            }
        })
        add("location-exact-domain-route-uuid-and-handshake-replay-isolation" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoLocationHandshake = false), coordinatesRequested = true).use { h ->
                h.start()
                val hello = h.peer.sessionInfoResponse(h.locationHandshake().contextId, h.clock.elapsed)
                val primaryBeforeHello = h.observations.size
                val consumedBeforeHello = checkNotNull(h.session.gattObservation?.rxProtocolCount)
                h.inject(mutate(hello) { it.clearRequestUuid() }); h.settle()
                check(h.requests.none { it.domain == 3 })
                check(h.observations.size == primaryBeforeHello)
                check(h.session.gattObservation?.rxProtocolCount == consumedBeforeHello + 1)
                check(h.gattObservations.last { it.event == TeslaUwbGattEvent.PROTOCOL }.rxResponse == null)
                h.inject(hello); h.settle()
                h.inject(hello); h.settle() // Old handshake identity cannot stand in for the new query identity.
                val query = h.locationRequest()
                for (options in listOf(
                    OfficialTeslaPeer.ResponseOptions(domain = 2),
                    OfficialTeslaPeer.ResponseOptions(route = ByteArray(16) { 7 }),
                    OfficialTeslaPeer.ResponseOptions(requestUuid = ByteArray(16) { 8 }),
                    OfficialTeslaPeer.ResponseOptions(requestUuid = byteArrayOf()),
                )) {
                    val primaryBefore = h.observations.size
                    val consumedBefore = checkNotNull(h.session.gattObservation?.rxProtocolCount)
                    h.reply(fix(), options)
                    check(h.observations.size == primaryBefore + if (options.domain == 2) 1 else 0)
                    check(h.session.gattObservation?.rxProtocolCount == consumedBefore + 1)
                    if (options.domain != 2) {
                        check(h.gattObservations.last { it.event == TeslaUwbGattEvent.PROTOCOL }.rxResponse == null)
                    }
                    check(h.platform.natives.isEmpty() && h.responses().isEmpty())
                    check(h.coordinateResults.isEmpty())
                    h.assertPrimaryAlive()
                }
                val response = h.peer.encryptedResponse(query.id, LocationWire.response(fix()))
                val primaryBeforeResult = h.observations.size
                val consumedBeforeResult = checkNotNull(h.session.gattObservation?.rxProtocolCount)
                val framesBeforeResult = checkNotNull(h.session.gattObservation?.rxFrameCount)
                h.inject(response); h.inject(response); h.settle()
                check(h.coordinateResults.size == 1 && h.coordinateResults.single().observation != null)
                check(h.observations.size == primaryBeforeResult)
                check(h.session.gattObservation?.rxProtocolCount == consumedBeforeResult + 1)
                check(h.session.gattObservation?.rxFrameCount == framesBeforeResult + 2)
                check(h.gattObservations.last { it.event == TeslaUwbGattEvent.PROTOCOL }.rxResponse == null)
                check(h.platform.natives.isEmpty() && h.responses().isEmpty())
                h.request(listOf(request()), form = "encrypted")
                check(h.platform.natives.single().starts == 1)
                h.assertPrimaryAlive()
            }
        })
        add("location-bad-aead-closes-only-sidecar-and-late-valid-response-is-dropped" to {
            VehiclePeerHarness(coordinatesRequested = true).use { h ->
                h.start()
                h.reply(fix(), OfficialTeslaPeer.ResponseOptions(tagTransform = { tag ->
                    tag.apply { this[0] = (this[0].toInt() xor 1).toByte() }
                }))
                check(h.coordinateResults.single().let { it.observation == null && it.reason == "LOCATION_PROTOCOL_INVALID" })
                val consumedBeforeLate = h.session.gattObservation?.rxProtocolCount
                val primaryBeforeLate = h.observations.size
                val framesBeforeLate = checkNotNull(h.session.gattObservation?.rxFrameCount)
                h.reply(fix())
                check(h.coordinateResults.size == 1)
                check(h.observations.size == primaryBeforeLate && h.session.gattObservation?.rxProtocolCount == consumedBeforeLate)
                check(h.session.gattObservation?.rxFrameCount == framesBeforeLate + 1)
                check(h.platform.natives.isEmpty() && h.responses().isEmpty())
                h.request(listOf(request()), form = "encrypted")
                check(h.platform.natives.single().starts == 1)
                h.assertPrimaryAlive()
            }
        })
        add("location-rejection-missing-state-and-no-response-never-finish-uwb" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoLocationHandshake = false), coordinatesRequested = true).use { h ->
                h.start()
                h.inject(h.peer.sessionInfoResponse(h.locationHandshake().contextId, h.clock.elapsed,
                    OfficialTeslaPeer.SessionInfoOptions(status = 1))); h.settle()
                check(h.coordinateResults.single().reason == "LOCATION_AUTHENTICATION_REJECTED")
                check(h.requests.none { it.domain == 3 })
                h.request(listOf(request())); h.assertPrimaryAlive()
            }
            for (response in listOf(LocationWire.response(null), LocationWire.response(fix(), result = 1))) {
                VehiclePeerHarness(coordinatesRequested = true).use { h ->
                    h.start(); h.inject(h.peer.encryptedResponse(h.locationRequest().id, response)); h.settle()
                    check(h.coordinateResults.single().let { it.observation == null && it.reason in
                        setOf("LOCATION_STATE_MISSING", "LOCATION_REQUEST_REJECTED") })
                    h.request(listOf(request())); h.assertPrimaryAlive()
                }
            }
            for (handshakeResponse in listOf(false, true)) VehiclePeerHarness(
                OfficialTeslaPeer(autoLocationHandshake = handshakeResponse), coordinatesRequested = true).use { h ->
                h.start(); h.request(listOf(request()))
                h.platform.natives.single().result(); h.settle()
                val deadline = h.session.deadline
                val stageDeadline = h.session.stageDeadline
                h.clock.elapsed += 9_000
                h.platform.natives.single().result(); h.settle()
                h.clock.elapsed += 6_000
                check(h.session.checkReadiness()); h.settle()
                check(h.coordinateResults.single().reason == "LOCATION_TIMEOUT")
                check(h.session.deadline == deadline && h.session.stageDeadline == stageDeadline)
                check(h.uwbState.validDistanceCount == 2L)
                h.assertPrimaryAlive()
            }
        })
        add("location-fragment-arbitration-prioritizes-primary-stop-before-frame-completion" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoLocationHandshake = false), coordinatesRequested = true).use { h ->
                h.start(); h.session.negotiatedMtu(23); h.autoCallbacks = false
                val hello = h.peer.sessionInfoResponse(h.locationHandshake().contextId, h.clock.elapsed)
                val incoming = hello + h.peer.rawFira(FiraWire.request(listOf(request(), request(2))))
                h.inject(incoming); h.settle()
                h.inject(h.peer.rawFira(FiraWire.stop(listOf(1)))); h.settle()
                h.completeAll()
                val primary = h.requests.filter { it.domain == 2 }
                check(primary.mapNotNull { it.fira }.let { it[0] is FiraWire.ClientPayload.Capabilities &&
                    it[1] is FiraWire.ClientPayload.Response && it[2] is FiraWire.ClientPayload.Stopped })
                check(h.requests.last().locationQuery) { "location fragments bypassed a pending primary stopped frame" }
                check(h.platform.natives.size == 1) { "stopped endpoint started across the drain-before-complete boundary" }
                h.reply(fix()); h.assertPrimaryAlive()
            }
        })
        add("location-timeout-drains-issued-partial-frame-without-retry-or-fira-corruption" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoLocationHandshake = false), coordinatesRequested = true).use { h ->
                h.start()
                h.clock.elapsed += 14_990
                h.session.negotiatedMtu(23); h.autoCallbacks = false
                h.inject(h.peer.sessionInfoResponse(h.locationHandshake().contextId, h.clock.elapsed)); h.settle()
                check(h.requests.none { it.domain == 3 } && h.session.writePump.writePending)
                h.clock.elapsed += 20
                check(h.completeOne()) // Expire after native owner/pump release, before offset advancement.
                check(h.coordinateResults.single().reason == "LOCATION_TIMEOUT")
                h.inject(h.peer.rawFira(FiraWire.request(listOf(request())))); h.settle()
                h.completeAll()
                check(h.locationRequest().locationQuery && h.handshakes.count { it.domain == 3 } == 1)
                check(h.responses().size == 1 && h.platform.natives.single().starts == 1)
                h.reply(fix())
                check(h.coordinateResults.size == 1)
                h.assertPrimaryAlive()
            }
        })
        add("location-source-admission-delays-one-attempt-and-cannot-revive-closed-owner" to {
            VehiclePeerHarness(coordinatesRequested = true).use { h ->
                h.coordinatesAllowed = false
                h.start()
                check(h.handshakes.none { it.domain == 3 })
                h.coordinatesAllowed = true
                check(h.session.checkReadiness()); h.settle()
                check(h.locationRequest().locationQuery)
                h.coordinatesAllowed = false
                check(h.session.checkReadiness())
                h.reply(fix())
                check(h.coordinateResults.isEmpty())
                h.coordinatesAllowed = true
                check(h.session.checkReadiness()); h.settle()
                check(h.handshakes.count { it.domain == 3 } == 1)
                h.autoCallbacks = false
                h.request(listOf(request()))
                h.ownerAllowed = false
                check(!h.session.checkReadiness())
                val count = h.requests.size
                h.completeAll(); h.reply(fix())
                h.coordinatesAllowed = true; h.ownerAllowed = true
                check(!h.session.checkReadiness())
                check(h.requests.size == count && h.coordinateResults.isEmpty())
            }
            VehiclePeerHarness(serviceOwned = false, coordinatesRequested = true).use { h ->
                h.start()
                check(h.handshakes.none { it.domain == 3 })
            }
        })
        add("location-unissued-queued-query-expires-without-transmitting-or-sealing-primary" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoLocationHandshake = false), coordinatesRequested = true).use { h ->
                h.start()
                h.clock.elapsed += 14_990
                h.inject(h.peer.sessionInfoResponse(h.locationHandshake().contextId, h.clock.elapsed))
                check(h.session.writePump.queued && h.owner.pending == null)
                h.clock.elapsed += 20
                check(h.session.checkReadiness()); h.settle()
                check(h.coordinateResults.single().reason == "LOCATION_TIMEOUT")
                check(h.requests.none { it.domain == 3 } && h.owner.pending == null)
                check(!h.session.writePump.queued && !h.session.writePump.writePending)
                h.request(listOf(request()), form = "encrypted")
                check(h.platform.natives.single().starts == 1)
                h.assertPrimaryAlive()
            }
        })
        add("location-busy-unaccepted-query-cancels-without-borrowing-next-primary-native-request" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoLocationHandshake = false), coordinatesRequested = true).use { h ->
                h.start()
                h.clock.elapsed += 14_990
                h.sdkStatus = 201
                h.inject(h.peer.sessionInfoResponse(h.locationHandshake().contextId, h.clock.elapsed)); h.settle()
                check(h.session.writePump.queued && h.owner.pending == null)
                h.clock.elapsed += 20
                check(h.session.checkReadiness())
                h.sdkStatus = 0
                h.request(listOf(request()), form = "encrypted")
                h.clock.elapsed += 100; h.settle()
                check(h.requests.none { it.domain == 3 } && h.owner.pending == null)
                check(h.coordinateResults.single().reason == "LOCATION_TIMEOUT")
                check(h.responses().size == 1 && h.platform.natives.single().starts == 1)
                h.assertPrimaryAlive()
            }
        })
        add("location-busy-sdk-return-cancellation-does-not-fail-primary" to {
            for (cause in listOf("deadline", "source-owner")) VehiclePeerHarness(
                OfficialTeslaPeer(autoLocationHandshake = false), coordinatesRequested = true).use { h ->
                h.start()
                h.clock.elapsed += 14_990
                h.sdkStatus = 201
                h.beforeWriteResult = {
                    h.beforeWriteResult = null
                    check(h.owner.pending == null)
                    if (cause == "deadline") h.clock.elapsed += 20 else h.coordinatesAllowed = false
                }
                h.inject(h.peer.sessionInfoResponse(h.locationHandshake().contextId, h.clock.elapsed)); h.settle()
                check(h.session.writePump.idle && h.owner.pending == null)
                check(h.requests.none { it.domain == 3 })
                if (cause == "deadline") check(h.coordinateResults.single().reason == "LOCATION_TIMEOUT")
                else check(h.coordinateResults.isEmpty())
                h.sdkStatus = 0
                h.coordinatesAllowed = true
                h.request(listOf(request()), form = "encrypted")
                check(h.responses().size == 1 && h.platform.natives.single().starts == 1)
                check(h.handshakes.count { it.domain == 3 } == 1)
                h.assertPrimaryAlive()
            }
        })
        add("location-running-busy-task-detects-expiry-without-sealing-primary" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoLocationHandshake = false), coordinatesRequested = true).use { h ->
                h.start()
                h.clock.elapsed += 14_990
                h.sdkStatus = 201
                h.inject(h.peer.sessionInfoResponse(h.locationHandshake().contextId, h.clock.elapsed)); h.settle()
                check(h.session.writePump.queued && h.owner.pending == null)
                h.clock.elapsed += 100
                h.sdkStatus = 0
                h.settle() // BUSY task runs before the next owner watchdog; its allowed() first detects expiry.
                check(h.session.writePump.idle && h.coordinateResults.single().reason == "LOCATION_TIMEOUT")
                check(h.requests.none { it.domain == 3 } && h.owner.pending == null)
                h.request(listOf(request()), form = "encrypted")
                check(h.responses().size == 1 && h.platform.natives.single().starts == 1)
                h.assertPrimaryAlive()
            }
        })
        add("location-expiry-between-native-begin-and-pump-issue-does-not-orphan-native-owner" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoLocationHandshake = false), coordinatesRequested = true).use { h ->
                h.start()
                h.clock.elapsed += 14_990
                h.session.negotiatedMtu(23)
                h.beforeWriteIssued = {
                    h.beforeWriteIssued = null
                    check(h.owner.pending != null)
                    h.clock.elapsed += 20
                    check(h.session.checkReadiness())
                    check(h.owner.pending != null && h.session.writePump.submitting)
                }
                h.inject(h.peer.sessionInfoResponse(h.locationHandshake().contextId, h.clock.elapsed)); h.settle()
                check(h.coordinateResults.single().reason == "LOCATION_TIMEOUT")
                check(h.locationRequest().locationQuery && h.owner.pending == null)
                check(!h.session.writePump.queued && !h.session.writePump.writePending)
                h.reply(fix())
                check(h.coordinateResults.size == 1)
                h.request(listOf(request()), form = "encrypted")
                check(h.platform.natives.single().starts == 1)
                h.assertPrimaryAlive()
            }
        })
        add("location-sdk-write-rejection-remains-a-shared-transport-failure" to {
            for (sdkStatus in listOf(1, 201)) VehiclePeerHarness(
                OfficialTeslaPeer(autoLocationHandshake = false), coordinatesRequested = true).use { h ->
                h.start()
                h.sdkStatus = sdkStatus
                h.inject(h.peer.sessionInfoResponse(h.locationHandshake().contextId, h.clock.elapsed)); h.settle()
                if (sdkStatus == 201) repeat(2) { h.clock.elapsed += 100; h.settle() }
                check(h.session.sealed && h.stopCount == 1 && h.stopReason == "GATT_WRITE_REJECTED")
                check(h.coordinateResults.isEmpty() && h.requests.none { it.domain == 3 })
            }
        })
        add("location-close-seals-both-contexts-and-pending-fragments-without-late-result" to {
            VehiclePeerHarness(OfficialTeslaPeer(autoLocationHandshake = false), coordinatesRequested = true).use { h ->
                h.start(); h.session.negotiatedMtu(23); h.autoCallbacks = false
                h.inject(h.peer.sessionInfoResponse(h.locationHandshake().contextId, h.clock.elapsed)); h.settle()
                check(h.session.writePump.writePending)
                h.session.cancel()
                h.completeAll()
                check(h.session.sealed && h.cleanupFailed == false && h.coordinateResults.isEmpty())
                check(h.requests.none { it.domain == 3 })
                val count = h.handshakes.size
                h.clock.elapsed += 15_000; h.settle()
                check(h.handshakes.size == count && !h.session.checkReadiness())
            }
        })
    }
}
