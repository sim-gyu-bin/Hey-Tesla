package com.heytesla.app.vehicle.connection

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import com.heytesla.app.peer.OfficialTeslaPeer
import com.heytesla.app.speech.product.ProductVoiceSession
import com.heytesla.app.vehicle.key.TeslaBleKeySession
import com.heytesla.app.vehicle.protocol.TeslaBlePhase
import com.heytesla.app.vehicle.protocol.TeslaBleRxAead
import com.heytesla.app.vehicle.protocol.TeslaBleRxStep
import com.heytesla.app.vehicle.protocol.TeslaGear
import com.heytesla.app.vehicle.key.TeslaKeyOutcome
import com.tesla.generated.carserver.common.Common
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec

/** Real production session/consumer + independent authenticated wire peer; no device or microphone. */
internal object TeslaConnectionWakePeerScenarios {
    private val BODY = OfficialTeslaPeer.RequestKind.BODY
    private val WAKE = OfficialTeslaPeer.RequestKind.WAKE
    private val DRIVE = OfficialTeslaPeer.RequestKind.DRIVE
    private val FRUNK = OfficialTeslaPeer.RequestKind.FRUNK

    /** Same admission boundary as the product parent: no capture admission before fresh explicit P. */
    private class VoiceAdmission(h: VehiclePeerHarness) {
        val voice = ProductVoiceSession()
        var captures = 0
            private set
        init {
            h.parkObserver = { drive, reason ->
                if (drive != null && reason == null && captures == 0) {
                    check(voice.start() && voice.beginPreparation())
                    // 이 peer는 실제 모델 대신 준비 완료 경계를 주입한다. fresh P 전에는 준비하지 않는다.
                    voice.preparationFinished(prepared = true, clean = false, failure = null)
                    check(voice.beginCapture())
                    captures++
                }
            }
            h.stoppingObserver = voice::stop
        }
    }

    private fun noControl(h: VehiclePeerHarness, voice: VoiceAdmission) {
        check(voice.captures == 0 && h.parkStates.none { it.second == null })
        check(!h.command() && h.requests.none { it.kind == FRUNK })
        check(h.session.frunkPolicy?.commandSent != true)
    }

    private fun VehiclePeerHarness.wakeWrites() = sdkWrites.filter {
        it.preparation == TeslaConnectionPreparationPhase.WAKE && it.phase == TeslaBlePhase.READING_STATUS
    }

    /** The independent BE16/AEAD peer accepted one complete command, not one command per chunk. */
    private fun assertMtu23WakeFrame(h: VehiclePeerHarness, busyCount: Int = 0) {
        val attempts = h.wakeWrites()
        val accepted = attempts.filter { it.status == 0 }
        check(accepted.size > 1 && attempts.all { it.bytes.size in 1..20 })
        check(attempts.count { it.status == 201 } == busyCount)
        check(attempts.all { it.status == 0 || it.status == 201 })
        check(accepted.dropLast(1).all { it.bytes.size == 20 })
        val first = accepted.first().bytes
        val framedSize = ((first[0].toInt() and 255) shl 8) + (first[1].toInt() and 255) + 2
        check(accepted.sumOf { it.bytes.size } == framedSize)
        check(h.requests.count { it.kind == WAKE } == 1 && h.wakeFactoryCalls == 1)
        check(h.handshakes.count { it.domain == 2 } == 2 && h.peerRejections.isEmpty())
    }

    private fun beginAsleep(h: VehiclePeerHarness) {
        h.start()
        check(h.requests.single().kind == BODY && h.handshakes.single().domain == 2)
        check(h.session.preparationPhase == TeslaConnectionPreparationPhase.BODY)
        h.replyBody(open = false, sleepStatus = 2)
        check(h.requests.map { it.kind } == listOf(BODY, WAKE))
        check(h.wakeFactoryCalls == 1 && h.session.preparationPhase == TeslaConnectionPreparationPhase.WAKE)
    }

    private fun lossyHelloHarness() =
        VehiclePeerHarness(peer = OfficialTeslaPeer(autoHandshake = false, allowFrunk = true, allowWake = true))

    private fun VehiclePeerHarness.retransmits() = events.count { it == "TESLA_CONNECTION_SESSION_RETRANSMIT" }
    private fun VehiclePeerHarness.driveHellos() = handshakes.filter { it.domain == 3 }

    /** Manual-handshake peer: BODY and WAKE domain2 HELLOs answered, post-wake domain3 HELLO left unanswered. */
    private fun beginWakeWithLostDriveHello(h: VehiclePeerHarness) {
        h.start(); h.answerHandshake()
        check(h.requests.single().kind == BODY)
        h.replyBody(open = false, sleepStatus = 2)
        h.answerHandshake()
        check(h.requests.map { it.kind } == listOf(BODY, WAKE) && h.wakeFactoryCalls == 1)
        h.replyWake()
        check(h.handshakes.map { it.domain } == listOf(2, 2, 3) && h.requests.size == 2)
        check(h.driveRetransmitFlags == listOf(true) && h.retransmits() == 0)
        check(h.session.stageDeadline == h.clock.elapsed + TeslaBleKeySession.SESSION_RETRANSMIT_WINDOW_MS)
    }
    private val invalidShift = Vehicle.ShiftState.newBuilder().setInvalid(Common.Void.getDefaultInstance()).build()
    private val parkShift = Vehicle.ShiftState.newBuilder().setP(Common.Void.getDefaultInstance()).build()
    private fun VehiclePeerHarness.driveRetries() = events.count { it == "TESLA_CONNECTION_DRIVE_RETRY" }

    private fun beginInitialDrive(h: VehiclePeerHarness, nominal: Boolean = false) {
        beginAsleep(h)
        if (nominal) { h.inject(h.peer.nominalWakeResponse(h.requests.last().id)); h.settle() }
        else h.replyWake()
        check(h.requests.map { it.kind } == listOf(BODY, WAKE, DRIVE))
    }

    private fun retryStillPreparing(h: VehiclePeerHarness, voice: VoiceAdmission) {
        check(!h.session.sealed && h.keyState.outcome == TeslaKeyOutcome.PENDING)
        check(h.session.preparationPhase == TeslaConnectionPreparationPhase.DRIVE)
        noControl(h, voice)
    }

    val cases: List<Pair<String, () -> Unit>> = buildList {
        for ((sleep, name) in listOf(1 to "awake", 0 to "unknown")) {
            add("wake-preflight-$name-skips-wake-and-still-requires-fresh-explicit-park" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    h.start()
                    check(h.requests.single().kind == BODY)
                    noControl(h, voice)
                    h.replyBody(open = false, sleepStatus = sleep)
                    check(h.requests.map { it.kind } == listOf(BODY, DRIVE))
                    check(h.wakeFactoryCalls == 0 && h.wakeWrites().isEmpty())
                    check(h.events.count { it == "TESLA_CONNECTION_BODY_VERIFIED" } == 1)
                    check(h.events.count { it == "TESLA_CONNECTION_WAKE_SKIPPED_${name.uppercase()}" } == 1)
                    noControl(h, voice)
                    h.replyDrive()
                    check(voice.captures == 1 && !h.session.sealed)
                    h.session.cancel(reason = "SERVICE_OFF")
                }
            })
        }
        add("wake-preflight-asleep-single-authenticated-wake-new-drive-before-voice-and-frunk" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                val handle = checkNotNull(h.owner.handle)
                val generation = h.owner.connectionGeneration
                beginAsleep(h)
                noControl(h, voice)
                val body = h.requests[0]
                val wake = h.requests[1]
                check(body.domain == 2 && wake.domain == 2 && wake.counter > body.counter)
                check(!body.route.contentEquals(wake.route) && !body.uuid.contentEquals(wake.uuid))
                check(h.wakeWrites().single().status == 0)
                check("TESLA_CONNECTION_WAKE_ACKNOWLEDGED" !in h.events)
                h.replyWake()
                check(h.requests.map { it.kind } == listOf(BODY, WAKE, DRIVE))
                check(h.handshakes.map { it.domain } == listOf(2, 2, 3))
                check(h.events.count { it == "TESLA_CONNECTION_WAKE_ACKNOWLEDGED" } == 1)
                val drive = h.requests.last()
                check(drive.domain == 3 && !wake.route.contentEquals(drive.route) && !wake.uuid.contentEquals(drive.uuid))
                noControl(h, voice)
                h.replyDrive()
                check(voice.captures == 1 && !h.session.sealed)
                check(h.owner.handle === handle && h.owner.connectionGeneration == generation)
                h.advance(TeslaParkMonitor.POLL_MS); h.replyDrive()
                check(h.requests.map { it.kind } == listOf(BODY, WAKE, DRIVE, DRIVE))
                check(voice.captures == 1 && h.wakeFactoryCalls == 1)
                // The production voice gate is stopped before the normal command handoff.
                voice.voice.captureFinished(released = true, clean = true)
                voice.voice.stop("PRODUCT_VOICE_STOPPED")
                check(h.command()); h.settle(); h.replyDrive()
                check(h.requests.last().kind == FRUNK)
                h.inject(h.peer.encryptedResponse(h.requests.last().id, byteArrayOf())); h.settle(); h.replyBody()
                check(h.session.frunkPolicy?.openObserved == true)
                check(h.requests.map { it.kind } == listOf(BODY, WAKE, DRIVE, DRIVE, DRIVE, FRUNK, BODY))
                check(h.wakeFactoryCalls == 1 && h.requests.count { it.kind == WAKE } == 1)
            }
        })
        for ((gear, sourceKind) in listOf(TeslaGear.D to "fresh", TeslaGear.UNKNOWN to "fresh", TeslaGear.P to "stale")) {
            add("wake-ack-followed-by-$gear-$sourceKind-never-admits-voice-or-frunk" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    beginAsleep(h); h.replyWake()
                    h.replyDrive(gear, source = h.wallEpochMs - if (sourceKind == "stale") 10_001 else 0)
                    check(h.session.sealed && h.stopCount == 1)
                    check(h.wakeFactoryCalls == 1 && h.requests.count { it.kind == WAKE } == 1)
                    noControl(h, voice)
                }
            })
        }
        add("wake-ack-followed-by-stale-park-ingress-never-admits-voice-or-frunk" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginAsleep(h); h.replyWake()
                val received = h.clock.elapsed
                h.clock.elapsed += 5_001; h.wallEpochMs += 5_001
                h.replyDrive(receivedAt = received)
                check(h.session.sealed && h.stopReason == "CONNECTION_P_RECEIPT_STALE")
                check(h.requests.count { it.kind == WAKE } == 1 && h.wakeFactoryCalls == 1)
                noControl(h, voice)
            }
        })
        for (kind in listOf("tag", "request-hash", "fault")) {
            add("wake-body-$kind-authentication-failure-never-creates-wake-or-drive" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    h.start()
                    val options = when (kind) {
                        "tag" -> OfficialTeslaPeer.ResponseOptions(tagTransform = { it.apply { this[0] = (this[0].toInt() xor 1).toByte() } })
                        "request-hash" -> OfficialTeslaPeer.ResponseOptions(requestHash = ByteArray(17) { 9 })
                        else -> OfficialTeslaPeer.ResponseOptions(fault = 1)
                    }
                    h.replyBody(sleepStatus = 2, options = options)
                    check(h.session.sealed && h.wakeFactoryCalls == 0 && h.requests.single().kind == BODY)
                    noControl(h, voice)
                }
            })
        }
        for (kind in listOf("negative", "future", "expired")) {
            add("wake-body-$kind-original-ingress-never-authorizes-wake" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    h.start()
                    val received = when (kind) {
                        "negative" -> -1L
                        "future" -> h.clock.elapsed + 1
                        else -> h.clock.elapsed.also { h.clock.elapsed += 5_001; h.wallEpochMs += 5_001 }
                    }
                    h.replyBody(sleepStatus = 2, receivedAt = received)
                    check(h.session.sealed && h.wakeFactoryCalls == 0 && h.requests.single().kind == BODY)
                    noControl(h, voice)
                }
            })
        }
        for (boundary in listOf("body-verified", "wake-started", "wake-key-derivation", "wake-authenticated", "sdk-write")) {
            for (loss in listOf("off", "owner", "permission", "expired")) {
                add("wake-$loss-at-$boundary-seals-before-wake-sdk-actuator" to {
                    VehiclePeerHarness().use { h ->
                        val voice = VoiceAdmission(h)
                        var revoked = false
                        fun revoke() {
                            if (revoked) return
                            revoked = true
                            when (loss) {
                                "off" -> h.session.cancel(reason = "SERVICE_OFF")
                                "owner" -> h.ownerAllowed = false
                                "permission" -> h.bluetoothPermission = false
                                else -> { h.clock.elapsed += 5_001; h.wallEpochMs += 5_001 }
                            }
                        }
                        h.eventObserver = { event ->
                            if (boundary == "body-verified" && event == "TESLA_CONNECTION_BODY_VERIFIED" ||
                                boundary == "wake-started" && event == "TESLA_CONNECTION_WAKE_STARTED") revoke()
                        }
                        h.responseObserver = {
                            if (boundary == "wake-authenticated" && h.session.preparationPhase == TeslaConnectionPreparationPhase.WAKE &&
                                it.disposition == "SESSION_READY") revoke()
                        }
                        h.sessionKeyObserver = {
                            if (boundary == "wake-key-derivation" &&
                                h.session.preparationPhase == TeslaConnectionPreparationPhase.WAKE) revoke()
                        }
                        h.beforeWriteIssued = {
                            if (boundary == "sdk-write" && h.session.preparationPhase == TeslaConnectionPreparationPhase.WAKE &&
                                h.keyState.phase == TeslaBlePhase.READING_STATUS) revoke()
                        }
                        h.start(); h.replyBody(sleepStatus = 2)
                        check(revoked && h.session.sealed && h.stopCount == 1)
                        check(h.requests.none { it.kind == WAKE || it.kind == DRIVE } && h.wakeWrites().isEmpty())
                        check(h.wakeFactoryCalls == if (boundary == "body-verified") 0 else 1)
                        noControl(h, voice)
                    }
                })
            }
        }
        add("wake-sdk201-reissues-only-identical-unaccepted-frame-without-new-factory-or-deadline" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                h.start()
                val overall = h.session.deadline
                h.responseObserver = {
                    if (h.session.preparationPhase == TeslaConnectionPreparationPhase.WAKE && it.disposition == "SESSION_READY") h.sdkStatus = 201
                }
                h.replyBody(sleepStatus = 2)
                val first = h.wakeWrites().single()
                check(first.status == 201 && h.requests.map { it.kind } == listOf(BODY))
                check(h.wakeFactoryCalls == 1 && h.session.writePump.queued)
                val deadline = h.session.stageDeadline
                h.sdkStatus = 0; h.advance(100)
                val attempts = h.wakeWrites()
                check(attempts.size == 2 && attempts[1].status == 0 && first.bytes.contentEquals(attempts[1].bytes))
                check(h.requests.map { it.kind } == listOf(BODY, WAKE) && h.wakeFactoryCalls == 1)
                check(h.session.deadline == overall && h.session.stageDeadline == deadline)
                h.advance(300)
                check(h.wakeWrites().size == 2 && h.requests.count { it.kind == WAKE } == 1)
                noControl(h, voice)
                h.replyWake(); noControl(h, voice)
            }
        })
        for (reply in listOf("rejected", "unconfirmed", "payload", "unknown-status", "signature", "tag", "timeout")) {
            add("wake-$reply-never-retries-or-starts-drive-voice-frunk" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    beginAsleep(h)
                    val wake = h.requests.last()
                    when (reply) {
                        "rejected" -> h.replyWake(OfficialTeslaPeer.ResponseOptions(
                            operationStatus = UniversalMessage.OperationStatus_E.OPERATIONSTATUS_ERROR_VALUE))
                        "unconfirmed" -> { h.inject(h.peer.unconfirmedWakeResponse(wake.id)); h.settle() }
                        "payload" -> {
                            h.inject(h.peer.nominalWakeResponse(wake.id) {
                                it.protobufMessageAsBytes = ByteString.copyFrom(byteArrayOf(0x08, 0x01))
                            }); h.settle()
                        }
                        "unknown-status" -> {
                            h.inject(h.peer.nominalWakeResponse(wake.id) {
                                it.signedMessageStatus = it.signedMessageStatus.toBuilder().setOperationStatusValue(7).build()
                            }); h.settle()
                        }
                        "signature" -> {
                            h.inject(h.peer.nominalWakeResponse(wake.id) {
                                it.signatureData = com.tesla.generated.signatures.Signatures.SignatureData.getDefaultInstance()
                            }); h.settle()
                        }
                        "tag" -> h.replyWake(OfficialTeslaPeer.ResponseOptions(tagTransform = { it.apply { this[0] = (this[0].toInt() xor 1).toByte() } }))
                        else -> h.advance(15_001)
                    }
                    check(h.session.sealed && h.stopCount == 1)
                    if (reply == "rejected") check("TESLA_CONNECTION_WAKE_REJECTED" in h.events)
                    if (reply in listOf("unconfirmed", "payload", "unknown-status")) {
                        check("TESLA_CONNECTION_WAKE_UNCONFIRMED" in h.events)
                    }
                    check("TESLA_CONNECTION_WAKE_NOMINAL_ACK" !in h.events)
                    if (reply == "tag") check(h.observations.last().aead == TeslaBleRxAead.FAILED)
                    h.inject(h.peer.wakeResponse(wake.id)); h.completeAll(); h.advance(20_000)
                    check(h.wakeFactoryCalls == 1 && h.requests.map { it.kind } == listOf(BODY, WAKE))
                    noControl(h, voice)
                }
            })
        }
        for ((result, gear) in listOf("fresh-P" to TeslaGear.P, "D" to TeslaGear.D,
            "UNKNOWN" to TeslaGear.UNKNOWN, "stale-P" to TeslaGear.P, "unauthenticated-P" to TeslaGear.P,
            "omitted-uuid-P" to TeslaGear.P)) {
            add("wake-nominal-ack-read-only-new-drive-$result-never-promotes-untrusted-ack-to-park" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    h.session.negotiatedMtu(23)
                    beginAsleep(h)
                    assertMtu23WakeFrame(h)
                    noControl(h, voice)
                    val wake = h.requests.last()
                    val nominal = h.peer.nominalWakeResponse(wake.id) {
                        if (result == "omitted-uuid-P") it.clearRequestUuid()
                    }
                    h.inject(nominal); h.settle()
                    check(!h.session.sealed && h.session.preparationPhase == TeslaConnectionPreparationPhase.DRIVE)
                    check(h.requests.map { it.kind } == listOf(BODY, WAKE, DRIVE))
                    check(h.requests.last().domain == 3 && !wake.route.contentEquals(h.requests.last().route) &&
                        !wake.uuid.contentEquals(h.requests.last().uuid))
                    check(h.events.count { it == "TESLA_CONNECTION_WAKE_NOMINAL_ACK" } == 1)
                    check("TESLA_CONNECTION_WAKE_ACKNOWLEDGED" !in h.events)
                    check("TESLA_CONNECTION_WAKE_UNCONFIRMED" !in h.events)
                    check(h.observations.any { it.step == TeslaBleRxStep.WAKE_RESULT &&
                        it.disposition == "WAKE_NOMINAL_ACK" && it.aead == TeslaBleRxAead.NOT_ATTEMPTED })
                    noControl(h, voice)
                    h.inject(nominal); h.settle()
                    check(h.events.count { it == "TESLA_CONNECTION_WAKE_NOMINAL_ACK" } == 1)
                    check(h.wakeFactoryCalls == 1 && h.requests.count { it.kind == WAKE } == 1)
                    noControl(h, voice)
                    if (result == "omitted-uuid-P") {
                        check(h.observations.any { it.disposition == "WAKE_NOMINAL_ACK" &&
                            it.requestUuidPresent == false && it.requestUuidMatches == null && it.toRouteMatches == true })
                        val driveStage = h.session.stageDeadline
                        h.replyDrive(options = OfficialTeslaPeer.ResponseOptions(envelopeMutation = { it.clearRequestUuid() }))
                        check(!h.session.sealed && h.session.stageDeadline == driveStage)
                        noControl(h, voice)
                    }
                    h.replyDrive(gear, source = h.wallEpochMs - if (result == "stale-P") 10_001 else 0,
                        options = if (result == "unauthenticated-P") OfficialTeslaPeer.ResponseOptions(
                            tagTransform = { it.apply { this[0] = (this[0].toInt() xor 1).toByte() } })
                        else OfficialTeslaPeer.ResponseOptions())
                    if (result == "fresh-P" || result == "omitted-uuid-P") {
                        check(voice.captures == 1 && h.parkStates.single().first?.gear == TeslaGear.P)
                        check(h.parkStates.single().second == null && !h.session.sealed)
                        check(h.requests.none { it.kind == FRUNK })
                    } else {
                        check(h.session.sealed && h.stopCount == 1)
                        noControl(h, voice)
                    }
                    nominal.fill(0)
                }
            })
        }
        add("wake-nominal-ack-off-at-event-cannot-start-independent-drive-or-revive-control" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginAsleep(h)
                h.eventObserver = {
                    if (it == "TESLA_CONNECTION_WAKE_NOMINAL_ACK") h.session.cancel(reason = "SERVICE_OFF")
                }
                h.inject(h.peer.nominalWakeResponse(h.requests.last().id)); h.settle()
                check(h.session.sealed && h.stopReason == "SERVICE_OFF" && h.stopCount == 1)
                check(h.events.count { it == "TESLA_CONNECTION_WAKE_NOMINAL_ACK" } == 1)
                check("TESLA_CONNECTION_WAKE_ACKNOWLEDGED" !in h.events)
                check(h.requests.map { it.kind } == listOf(BODY, WAKE) && h.wakeFactoryCalls == 1)
                noControl(h, voice)
                h.inject(h.peer.wakeResponse(h.requests.last().id)); h.completeAll(); h.advance(20_000)
                check(h.requests.map { it.kind } == listOf(BODY, WAKE) && h.wakeFactoryCalls == 1)
                noControl(h, voice)
            }
        })
        for (header in listOf("route", "uuid", "domain", "wait")) {
            add("wake-nominal-$header-cannot-be-nominal-result-or-postpone-original-stage" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    beginAsleep(h)
                    val stage = h.session.stageDeadline
                    val wake = h.requests.last()
                    val excluded = h.peer.nominalWakeResponse(wake.id) { message ->
                        when (header) {
                            "route" -> message.toDestination = UniversalMessage.Destination.newBuilder()
                                .setRoutingAddress(ByteString.copyFrom(ByteArray(16) { 7 })).build()
                            "uuid" -> message.requestUuid = ByteString.copyFrom(ByteArray(16) { 7 })
                            "domain" -> message.fromDestination = UniversalMessage.Destination.newBuilder().setDomainValue(3).build()
                            else -> message.signedMessageStatus = message.signedMessageStatus.toBuilder()
                                .setOperationStatusValue(UniversalMessage.OperationStatus_E.OPERATIONSTATUS_WAIT_VALUE).build()
                        }
                    }
                    h.inject(excluded); h.settle()
                    check(!h.session.sealed && h.session.stageDeadline == stage)
                    check(h.requests.map { it.kind } == listOf(BODY, WAKE) && h.wakeFactoryCalls == 1)
                    check("TESLA_CONNECTION_WAKE_NOMINAL_ACK" !in h.events)
                    check("TESLA_CONNECTION_WAKE_ACKNOWLEDGED" !in h.events)
                    noControl(h, voice)
                    h.inject(h.peer.nominalWakeResponse(wake.id)); h.settle()
                    check(h.requests.map { it.kind } == listOf(BODY, WAKE, DRIVE))
                    check(h.events.count { it == "TESLA_CONNECTION_WAKE_NOMINAL_ACK" } == 1)
                    noControl(h, voice)
                    excluded.fill(0)
                }
            })
        }
        add("wake-wait-and-command-status-do-not-renew-original-stage-or-create-another-wake" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginAsleep(h)
                val deadline = h.session.stageDeadline
                h.replyWake(OfficialTeslaPeer.ResponseOptions(
                    operationStatus = UniversalMessage.OperationStatus_E.OPERATIONSTATUS_WAIT_VALUE))
                check(!h.session.sealed && h.session.stageDeadline == deadline)
                val pending = Vcsec.FromVCSECMessage.newBuilder().setCommandStatus(Vcsec.CommandStatus.newBuilder()
                    .setOperationStatusValue(0)).build().toByteArray()
                h.inject(h.peer.encryptedResponse(h.requests.last().id, pending)); h.settle(); pending.fill(0)
                check(!h.session.sealed && h.session.stageDeadline == deadline)
                check(h.requests.map { it.kind } == listOf(BODY, WAKE) && h.wakeFactoryCalls == 1)
                noControl(h, voice)
                h.replyWake()
                check(h.requests.last().kind == DRIVE)
                noControl(h, voice)
            }
        })
        for (reroute in listOf(false, true)) {
            add("wake-old-body-${if (reroute) "rewritten-binding" else "old-route"}-cannot-ack-or-replay-wake" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    h.start()
                    val old = h.peer.bodyStatusResponse(h.requests.single().id, 2)
                    h.inject(old); h.settle()
                    val wake = h.requests.last()
                    check(wake.kind == WAKE)
                    val replay = if (!reroute) old else OfficialTeslaPeer.frame(
                        UniversalMessage.RoutableMessage.parseFrom(old.copyOfRange(2, old.size)).toBuilder()
                            .setToDestination(UniversalMessage.Destination.newBuilder().setRoutingAddress(ByteString.copyFrom(wake.route)))
                            .setRequestUuid(ByteString.copyFrom(wake.uuid)).build().toByteArray())
                    h.inject(replay); h.settle()
                    check("TESLA_CONNECTION_WAKE_ACKNOWLEDGED" !in h.events && h.requests.none { it.kind == DRIVE })
                    check(h.requests.count { it.kind == WAKE } == 1 && h.wakeFactoryCalls == 1)
                    if (reroute) check(h.session.sealed && h.observations.last().aead == TeslaBleRxAead.FAILED)
                    else {
                        check(!h.session.sealed && h.observations.last().aead == TeslaBleRxAead.NOT_ATTEMPTED)
                        h.replyWake(); check(h.requests.last().kind == DRIVE)
                    }
                    noControl(h, voice)
                    old.fill(0); if (replay !== old) replay.fill(0)
                }
            })
        }
        add("wake-ack-replay-after-new-drive-cannot-arm-voice-or-create-another-wake" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginAsleep(h)
                val ack = h.peer.wakeResponse(h.requests.last().id)
                h.inject(ack); h.settle()
                check(h.observations.any { it.step == TeslaBleRxStep.WAKE_RESULT && it.disposition == "WAKE_ACKNOWLEDGED" })
                h.inject(ack); h.settle()
                check(h.events.count { it == "TESLA_CONNECTION_WAKE_ACKNOWLEDGED" } == 1)
                check(h.requests.map { it.kind } == listOf(BODY, WAKE, DRIVE) && h.wakeFactoryCalls == 1)
                noControl(h, voice)
                h.replyDrive(TeslaGear.D)
                check(h.session.sealed); noControl(h, voice)
                ack.fill(0)
            }
        })
        add("wake-unknown-body-and-unknown-drive-never-admit-voice-or-actuator" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                h.start(); h.replyBody(sleepStatus = 0)
                check(h.requests.map { it.kind } == listOf(BODY, DRIVE) && h.wakeFactoryCalls == 0)
                h.replyDrive(TeslaGear.UNKNOWN)
                check(h.session.sealed && h.wakeWrites().isEmpty())
                noControl(h, voice)
            }
        })
        add("wake-same-request-encrypted-wait-counter-replay-fails-without-new-wake-or-drive" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginAsleep(h)
                val wait = h.peer.wakeResponse(h.requests.last().id, OfficialTeslaPeer.ResponseOptions(
                    operationStatus = UniversalMessage.OperationStatus_E.OPERATIONSTATUS_WAIT_VALUE))
                h.inject(wait); h.settle()
                check(!h.session.sealed && h.observations.last().step == TeslaBleRxStep.WAITING)
                h.inject(wait); h.settle()
                check(h.session.sealed && h.stopCount == 1)
                check(h.requests.map { it.kind } == listOf(BODY, WAKE) && h.wakeFactoryCalls == 1)
                noControl(h, voice)
                wait.fill(0)
            }
        })
        add("wake-mtu23-all-chunks-complete-one-command-before-ack-and-new-drive-park" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                h.session.negotiatedMtu(23)
                beginAsleep(h)
                assertMtu23WakeFrame(h)
                val chunks = h.wakeWrites().size
                check(h.events.count { it == "TESLA_CONNECTION_WAKE_STARTED" } == 1)
                check("TESLA_CONNECTION_WAKE_ACKNOWLEDGED" !in h.events)
                noControl(h, voice)
                h.replyWake()
                check(h.requests.map { it.kind } == listOf(BODY, WAKE, DRIVE))
                check(h.events.count { it == "TESLA_CONNECTION_WAKE_ACKNOWLEDGED" } == 1)
                check(h.session.preparationPhase == TeslaConnectionPreparationPhase.DRIVE)
                noControl(h, voice)
                h.replyDrive()
                check(voice.captures == 1 && h.parkStates.single().first?.gear == TeslaGear.P)
                check(h.parkStates.single().second == null && h.requests.none { it.kind == FRUNK })
                h.advance(TeslaParkMonitor.POLL_MS); h.replyDrive()
                check(h.requests.map { it.kind } == listOf(BODY, WAKE, DRIVE, DRIVE))
                check(h.wakeWrites().size == chunks && h.wakeFactoryCalls == 1)
                check(voice.captures == 1 && !h.session.sealed)
            }
        })
        for (loss in listOf("off", "receipt-stale")) {
            add("wake-mtu23-$loss-before-second-chunk-stops-partial-frame-without-recreation" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    h.session.negotiatedMtu(23)
                    if (loss == "receipt-stale") h.eventObserver = {
                        if (it == "TESLA_CONNECTION_BODY_VERIFIED") {
                            // Leave a full one-second wire TTL, then expire only BODY freshness at chunk two.
                            h.clock.elapsed += 4_000; h.wallEpochMs += 4_000
                        }
                    }
                    var chunkBoundary = 0
                    h.beforeWriteIssued = {
                        if (h.session.preparationPhase == TeslaConnectionPreparationPhase.WAKE &&
                            h.keyState.phase == TeslaBlePhase.READING_STATUS) {
                            chunkBoundary++
                            if (chunkBoundary == 2) {
                                if (loss == "off") h.session.cancel(reason = "SERVICE_OFF")
                                else { h.clock.elapsed += 1_001; h.wallEpochMs += 1_001 }
                            }
                        }
                    }
                    h.start(); h.replyBody(sleepStatus = 2)
                    check(chunkBoundary == 2 && h.session.sealed && h.stopCount == 1)
                    check(h.stopReason == if (loss == "off") "SERVICE_OFF" else "CONNECTION_BODY_RECEIPT_STALE")
                    val accepted = h.wakeWrites().single()
                    check(accepted.status == 0 && accepted.bytes.size == 20)
                    check(h.requests.map { it.kind } == listOf(BODY) && h.wakeFactoryCalls == 1)
                    check("TESLA_CONNECTION_WAKE_ACKNOWLEDGED" !in h.events)
                    noControl(h, voice)
                    h.completeAll(); h.advance(20_000)
                    check(chunkBoundary == 2 && h.wakeWrites().size == 1 && h.wakeFactoryCalls == 1)
                    check(h.requests.map { it.kind } == listOf(BODY))
                    noControl(h, voice)
                }
            })
        }
        add("wake-mtu23-sdk201-on-second-chunk-reissues-identical-chunk-not-accepted-prefix-or-frame" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                h.session.negotiatedMtu(23)
                var chunkBoundary = 0
                h.beforeWriteIssued = {
                    if (h.session.preparationPhase == TeslaConnectionPreparationPhase.WAKE &&
                        h.keyState.phase == TeslaBlePhase.READING_STATUS) {
                        chunkBoundary++
                        if (chunkBoundary == 2) h.sdkStatus = 201
                    }
                }
                h.start()
                val overall = h.session.deadline
                h.replyBody(sleepStatus = 2)
                val prefix = h.wakeWrites()
                check(chunkBoundary == 2 && prefix.size == 2)
                check(prefix[0].status == 0 && prefix[1].status == 201)
                check(prefix.all { it.bytes.size == 20 })
                check(h.requests.map { it.kind } == listOf(BODY) && h.wakeFactoryCalls == 1)
                check(h.session.writePump.queued && !h.session.sealed)
                val stage = h.session.stageDeadline
                noControl(h, voice)
                h.sdkStatus = 0; h.advance(100)
                val complete = h.wakeWrites()
                check(complete.size > 3 && complete[2].status == 0)
                check(prefix[1].bytes.contentEquals(complete[2].bytes))
                check(prefix[0].bytes.contentEquals(complete[0].bytes))
                assertMtu23WakeFrame(h, busyCount = 1)
                check(h.session.deadline == overall && h.session.stageDeadline == stage)
                check(h.requests.map { it.kind } == listOf(BODY, WAKE))
                check("TESLA_CONNECTION_WAKE_ACKNOWLEDGED" !in h.events)
                noControl(h, voice)
                val completedChunks = complete.size
                h.advance(300)
                check(h.wakeWrites().size == completedChunks && h.wakeFactoryCalls == 1)
                h.replyWake()
                check(h.requests.map { it.kind } == listOf(BODY, WAKE, DRIVE))
                noControl(h, voice)
                h.replyDrive()
                check(voice.captures == 1 && h.parkStates.single().first?.gear == TeslaGear.P)
                check(h.parkStates.single().second == null && h.requests.none { it.kind == FRUNK })
                check(h.wakeWrites().size == completedChunks && h.wakeFactoryCalls == 1)
            }
        })
        add("wake-multichunk-owner-loss-after-first-accepted-chunk-cannot-finish-command" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                h.start()
                h.responseObserver = {
                    if (h.session.preparationPhase == TeslaConnectionPreparationPhase.WAKE &&
                        it.disposition == "SESSION_READY") h.session.negotiatedMtu(23)
                }
                h.beforeWriteResult = {
                    if (h.session.preparationPhase == TeslaConnectionPreparationPhase.WAKE &&
                        h.keyState.phase == TeslaBlePhase.READING_STATUS) h.ownerAllowed = false
                }
                h.replyBody(sleepStatus = 2)
                check(h.session.sealed && h.stopCount == 1 && h.wakeFactoryCalls == 1)
                check(h.wakeWrites().single().status == 0)
                check(h.requests.map { it.kind } == listOf(BODY))
                h.completeAll(); h.advance(20_000)
                check(h.wakeWrites().size == 1)
                noControl(h, voice)
            }
        })
        add("wake-reentry-requires-new-request-and-fresh-authenticated-asleep-body" to {
            VehiclePeerHarness().use { first ->
                val firstVoice = VoiceAdmission(first)
                beginAsleep(first)
                val oldAck = first.peer.wakeResponse(first.requests.last().id)
                first.session.cancel(reason = "SERVICE_OFF")
                first.inject(oldAck); first.settle()
                check(first.requests.count { it.kind == WAKE } == 1)
                noControl(first, firstVoice)
                VehiclePeerHarness().use { second ->
                    val secondVoice = VoiceAdmission(second)
                    second.start()
                    second.inject(oldAck); second.settle()
                    check(second.wakeFactoryCalls == 0 && second.requests.single().kind == BODY)
                    noControl(second, secondVoice)
                    second.replyBody(sleepStatus = 2)
                    check(second.requests.map { it.kind } == listOf(BODY, WAKE) && second.wakeFactoryCalls == 1)
                    noControl(second, secondVoice)
                }
                oldAck.fill(0)
            }
        })
        add("wake-ack-lost-domain3-hello-retransmits-same-request-then-original-reply-reaches-fresh-park" to {
            lossyHelloHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginWakeWithLostDriveHello(h)
                repeat(4) { h.advance(TeslaBleKeySession.SESSION_RETRANSMIT_INTERVAL_MS) }
                check(!h.session.sealed && h.retransmits() == 4 && h.driveHellos().size == 5)
                check(h.peerRejections.isEmpty())
                noControl(h, voice)
                // The first HELLO's reply authenticates: every resend kept the original request identity.
                h.inject(h.peer.sessionInfoResponse(h.driveHellos().first().contextId, h.clock.elapsed)); h.settle()
                check(h.requests.map { it.kind } == listOf(BODY, WAKE, DRIVE) && !h.session.sealed)
                noControl(h, voice)
                h.inject(h.peer.sessionInfoResponse(h.driveHellos().last().contextId, h.clock.elapsed)); h.settle()
                check(!h.session.sealed && h.requests.size == 3)
                h.advance(5_000)
                check(h.retransmits() == 4 && h.driveHellos().size == 5)
                h.replyDrive()
                check(voice.captures == 1 && !h.session.sealed)
                check(h.requests.count { it.kind == WAKE } == 1 && h.wakeFactoryCalls == 1)
                check(h.requests.none { it.kind == FRUNK })
                h.session.cancel(reason = "SERVICE_OFF")
            }
        })
        add("wake-ack-domain3-hello-retransmit-cap-then-window-timeout-without-second-wake" to {
            lossyHelloHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginWakeWithLostDriveHello(h)
                repeat(9) { h.advance(TeslaBleKeySession.SESSION_RETRANSMIT_INTERVAL_MS) }
                check(!h.session.sealed)
                check(h.retransmits() == TeslaBleKeySession.MAX_SESSION_RETRANSMITS)
                check(h.driveHellos().size == TeslaBleKeySession.MAX_SESSION_RETRANSMITS + 1)
                h.advance(TeslaBleKeySession.SESSION_RETRANSMIT_INTERVAL_MS); h.advance(250)
                check(h.session.sealed && h.stopCount == 1)
                h.advance(30_000)
                check(h.retransmits() == TeslaBleKeySession.MAX_SESSION_RETRANSMITS)
                check(h.requests.map { it.kind } == listOf(BODY, WAKE) && h.wakeFactoryCalls == 1)
                noControl(h, voice)
            }
        })
        add("awake-preflight-drive-hello-never-retransmits-and-keeps-protocol-deadline" to {
            lossyHelloHarness().use { h ->
                val voice = VoiceAdmission(h)
                h.start(); h.answerHandshake()
                h.replyBody(open = false, sleepStatus = 1)
                check(h.driveRetransmitFlags == listOf(false) && h.driveHellos().size == 1)
                repeat(3) { h.advance(TeslaBleKeySession.SESSION_RETRANSMIT_INTERVAL_MS) }
                check(!h.session.sealed && h.retransmits() == 0 && h.driveHellos().size == 1)
                h.advance(TeslaBleKeySession.SESSION_RETRANSMIT_INTERVAL_MS); h.advance(250)
                check(h.session.sealed && h.retransmits() == 0 && h.driveHellos().size == 1)
                check(h.wakeFactoryCalls == 0 && h.requests.single().kind == BODY)
                noControl(h, voice)
            }
        })
        add("cancel-during-post-wake-hello-window-stops-retransmits" to {
            lossyHelloHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginWakeWithLostDriveHello(h)
                h.advance(TeslaBleKeySession.SESSION_RETRANSMIT_INTERVAL_MS)
                check(h.retransmits() == 1 && h.driveHellos().size == 2)
                h.session.cancel(reason = "SERVICE_OFF")
                h.advance(30_000)
                check(h.retransmits() == 1 && h.driveHellos().size == 2 && h.wakeFactoryCalls == 1)
                noControl(h, voice)
            }
        })
        for (nominal in listOf(false, true)) for (observe in listOf(false, true)) {
            add("initial-invalid-${if (nominal) "nominal" else "ack"}-observer-$observe-new-authentication-before-fresh-park" to {
                VehiclePeerHarness(observeResponses = observe).use { h ->
                    val voice = VoiceAdmission(h)
                    val handle = h.owner.handle
                    val generation = h.owner.connectionGeneration
                    beginInitialDrive(h, nominal)
                    val first = h.requests.last()
                    val firstHello = h.driveHellos().single()
                    val global = h.session.deadline
                    h.replyDriveShift(invalidShift)
                    retryStillPreparing(h, voice)
                    check(h.driveRetries() == 0 && h.driveRequests.size == 1)
                    h.advance(1_999)
                    check(h.driveRetries() == 0 && h.driveRequests.size == 1)
                    h.advance(1)
                    val second = h.driveRequests.last()
                    check(h.driveRetries() == 1 && h.driveRequests.size == 2)
                    check(h.driveHellos().last().contextId != firstHello.contextId)
                    check(second.domain == 3 && second.counter > first.counter)
                    check(!second.route.contentEquals(first.route) && !second.uuid.contentEquals(first.uuid))
                    check(!second.requestHash.contentEquals(first.requestHash))
                    check(h.driveRetransmitFlags == listOf(true, false) && h.session.deadline == global)
                    check(h.owner.handle === handle && h.owner.connectionGeneration == generation)
                    retryStillPreparing(h, voice)
                    // 폐기한 최초 응답은 새 인증 결과·P로 사용할 수 없다.
                    h.replyDrive(request = first)
                    retryStillPreparing(h, voice)
                    h.replyDrive()
                    check(voice.captures == 1 && !h.session.sealed)
                    check(h.parkStates.single().first?.gear == TeslaGear.P && h.parkStates.single().second == null)
                    check(h.requests.count { it.kind == BODY } == 1 && h.requests.count { it.kind == WAKE } == 1)
                    check(h.requests.none { it.kind == FRUNK } && h.wakeFactoryCalls == 1)
                    if (!observe) check(h.observations.isEmpty())
                }
            })
        }
        add("initial-invalid-four-total-reads-cap-without-park-voice-or-second-wake" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginInitialDrive(h)
                repeat(3) { retry ->
                    h.replyDriveShift(invalidShift)
                    retryStillPreparing(h, voice)
                    check(h.driveRetries() == retry)
                    h.advance(2_000)
                    check(h.driveRetries() == retry + 1 && h.driveRequests.size == retry + 2)
                }
                val late = h.peer.encryptedResponse(h.requests.last().id, h.driveBody())
                h.replyDriveShift(invalidShift)
                check(h.session.sealed && h.stopReason == "CONNECTION_EXPLICIT_P_REQUIRED" && h.stopCount == 1)
                noControl(h, voice)
                h.inject(late); h.completeAll(); h.advance(30_000); late.fill(0)
                check(h.driveRequests.size == 4 && h.driveRetries() == 3 && h.wakeFactoryCalls == 1)
                check(h.driveRetransmitFlags == listOf(true, false, false, false))
                noControl(h, voice)
            }
        })
        for (sourceKind in listOf("absent", "nanos", "non-positive", "stale", "future")) {
            add("initial-invalid-$sourceKind-source-permits-only-new-read-and-bad-park-still-stops" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    beginInitialDrive(h)
                    fun timestamp(): Timestamp? = when (sourceKind) {
                        "absent" -> null
                        "nanos" -> Timestamp.newBuilder().setSeconds(h.wallEpochMs / 1_000).setNanos(1_000_000_000).build()
                        "non-positive" -> Timestamp.getDefaultInstance()
                        else -> {
                            val source = h.wallEpochMs + if (sourceKind == "stale") -10_001 else 2_001
                            Timestamp.newBuilder().setSeconds(source / 1_000)
                                .setNanos((source % 1_000).toInt() * 1_000_000).build()
                        }
                    }
                    h.replyDriveShift(invalidShift, timestamp())
                    retryStillPreparing(h, voice)
                    h.advance(2_000)
                    check(h.driveRetries() == 1 && h.driveRequests.size == 2)
                    h.replyDriveShift(parkShift, timestamp())
                    check(h.session.sealed && h.stopCount == 1)
                    check(h.stopReason == if (sourceKind in listOf("absent", "nanos"))
                        "CONNECTION_P_SOURCE_TIME_REQUIRED" else "CONNECTION_P_SOURCE_TIME_STALE")
                    noControl(h, voice)
                }
            })
        }
        for ((name, shift) in listOf(
            "absent" to null, "empty" to Vehicle.ShiftState.getDefaultInstance(),
            "sna" to Vehicle.ShiftState.newBuilder().setSNA(Common.Void.getDefaultInstance()).build(),
            "unrecognized" to Vehicle.ShiftState.parseFrom(byteArrayOf(0x3a, 0)),
            "wrong-wire" to Vehicle.ShiftState.parseFrom(byteArrayOf(0x10, 1)),
            "D" to Vehicle.ShiftState.newBuilder().setD(Common.Void.getDefaultInstance()).build(),
            "R" to Vehicle.ShiftState.newBuilder().setR(Common.Void.getDefaultInstance()).build(),
            "N" to Vehicle.ShiftState.newBuilder().setN(Common.Void.getDefaultInstance()).build(),
        )) {
            add("initial-$name-is-not-explicit-invalid-and-stops-without-new-read" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    beginInitialDrive(h)
                    h.replyDriveShift(shift)
                    check(h.session.sealed && h.stopReason == "CONNECTION_EXPLICIT_P_REQUIRED")
                    h.advance(3_000)
                    check(h.driveRetries() == 0 && h.driveRequests.size == 1)
                    noControl(h, voice)
                }
            })
        }
        for (preflight in listOf("awake", "unknown", "manual")) {
            add("initial-invalid-$preflight-has-no-wake-retry-eligibility" to {
                VehiclePeerHarness(serviceOwned = preflight != "manual").use { h ->
                    val voice = VoiceAdmission(h)
                    if (preflight == "manual") h.start()
                    else { h.start(); h.replyBody(sleepStatus = if (preflight == "awake") 1 else 0) }
                    h.replyDriveShift(invalidShift)
                    check(h.session.sealed && h.driveRetries() == 0 && h.driveRequests.size == 1)
                    check(h.wakeFactoryCalls == 0 && h.requests.none { it.kind == WAKE || it.kind == FRUNK })
                    noControl(h, voice)
                }
            })
        }
        for (path in listOf("monitor", "command", "command-drain")) {
            add("initial-invalid-eligibility-permanently-revoked-after-fresh-park-$path" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    beginInitialDrive(h)
                    h.replyDriveShift(invalidShift); h.advance(2_000); h.replyDrive()
                    check(voice.captures == 1)
                    voice.voice.captureFinished(released = true, clean = true)
                    voice.voice.stop("PRODUCT_VOICE_STOPPED")
                    if (path == "command") { check(h.command()); h.settle() }
                    else {
                        h.advance(2_000)
                        if (path == "command-drain") check(h.command())
                    }
                    h.replyDriveShift(invalidShift)
                    check(h.session.sealed && h.stopCount == 1)
                    check(h.requests.none { it.kind == FRUNK } && h.driveRetries() == 1 && h.wakeFactoryCalls == 1)
                    h.advance(30_000)
                    check(h.driveRetries() == 1 && h.requests.none { it.kind == FRUNK })
                    check(voice.voice.stopReason.get() != null && !voice.voice.beginCapture())
                }
            })
        }
        for (boundary in listOf("waiting", "event", "authenticated-rx", "drain", "sdk-issued", "sdk201")) {
            for (loss in listOf("off", "cancel", "close", "owner", "permission", "bluetooth", "consent", "clock")) {
                add("initial-invalid-$loss-at-$boundary-seals-retry-and-late-park" to {
                    VehiclePeerHarness().use { h ->
                        val voice = VoiceAdmission(h)
                        beginInitialDrive(h)
                        val initial = h.requests.last()
                        val late = h.peer.encryptedResponse(initial.id, h.driveBody())
                        var revoked = false
                        fun revoke() {
                            if (revoked) return
                            revoked = true
                            when (loss) {
                                "off" -> h.session.cancel(reason = "SERVICE_OFF")
                                "cancel" -> h.session.cancel()
                                "close" -> h.session.close {}
                                "owner" -> h.ownerAllowed = false
                                "permission" -> h.bluetoothPermission = false
                                "bluetooth" -> h.bluetoothEnabled = false
                                "consent" -> h.productConsent = false
                                else -> h.clock.elapsed--
                            }
                        }
                        if (boundary == "authenticated-rx") h.responseObserver = {
                            if (it.driveShiftState == "INVALID") revoke()
                        }
                        if (boundary == "event") h.eventObserver = {
                            if (it == "TESLA_CONNECTION_DRIVE_RETRY") revoke()
                        }
                        if (boundary == "sdk-issued") h.beforeWriteIssued = { revoke() }
                        if (boundary == "drain") {
                            h.replyDriveShift(invalidShift)
                            h.autoCallbacks = false; h.advance(2_000)
                            check(h.completeOne()) // 새 HELLO 완료 후 READ write callback은 계속 보류한다.
                            h.replyDriveShift(invalidShift)
                            check(h.driveRetries() == 1 && h.owner.pending != null)
                            revoke(); h.completeAll()
                        } else {
                            h.replyDriveShift(invalidShift)
                            if (boundary == "waiting") { revoke(); h.session.checkReadiness() }
                            if (boundary == "sdk201") {
                                h.sdkStatus = 201
                                h.advance(2_000)
                                check(h.session.writePump.queued)
                                revoke()
                            }
                        }
                        if (boundary != "drain" && boundary != "sdk201") h.advance(2_000)
                        h.session.checkReadiness()
                        check(revoked && h.session.sealed)
                        val writes = h.sdkWrites.size
                        val reads = h.driveRequests.size
                        h.inject(late); h.completeAll(); h.advance(30_000); late.fill(0)
                        check(h.sdkWrites.size == writes && h.driveRequests.size == reads)
                        check(h.wakeFactoryCalls == 1 && h.requests.none { it.kind == FRUNK })
                        noControl(h, voice)
                    }
                })
            }
        }
        add("initial-invalid-retry-event-exception-seals-before-conversation-or-new-write" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginInitialDrive(h)
                h.replyDriveShift(invalidShift)
                val writes = h.sdkWrites.size
                h.eventObserver = { if (it == "TESLA_CONNECTION_DRIVE_RETRY") error("synthetic callback failure") }
                h.advance(2_000)
                check(h.session.sealed && h.stopCount == 1 && h.driveRetries() == 1)
                check(h.sdkWrites.size == writes && h.driveRequests.size == 1)
                noControl(h, voice)
            }
        })
        add("initial-invalid-scheduler-rejection-stops-without-emitting-issued-retry" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginInitialDrive(h)
                h.clock.accepting = false
                h.replyDriveShift(invalidShift)
                check(h.session.sealed && h.stopReason == "GATT_CALLBACK_FAILED" && h.stopCount == 1)
                check(h.driveRetries() == 0 && h.driveRequests.size == 1)
                h.clock.accepting = true
                h.advance(30_000)
                check(h.driveRetries() == 0 && h.driveRequests.size == 1)
                noControl(h, voice)
            }
        })
        add("initial-invalid-sdk201-reissues-identical-new-hello-without-consuming-another-read" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginInitialDrive(h)
                h.replyDriveShift(invalidShift)
                h.sdkStatus = 201
                h.advance(2_000)
                val busy = h.sdkWrites.last()
                check(busy.status == 201 && h.driveRetries() == 1 && h.driveRequests.size == 1)
                val stageDeadline = h.session.stageDeadline
                h.sdkStatus = 0; h.advance(100)
                val accepted = h.sdkWrites.first { it !== busy && it.bytes.contentEquals(busy.bytes) }
                check(accepted.status == 0 && h.driveRequests.size == 2 && h.driveRetries() == 1)
                check(h.session.stageDeadline == h.clock.elapsed + 10_000)
                check(stageDeadline <= h.session.stageDeadline)
                retryStillPreparing(h, voice)
                h.replyDrive()
                check(voice.captures == 1 && h.wakeFactoryCalls == 1 && h.driveRetries() == 1)
            }
        })
        add("initial-invalid-new-hello-keeps-ten-second-stage-without-rearming-same-request-retransmits" to {
            lossyHelloHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginWakeWithLostDriveHello(h); h.answerHandshake()
                h.replyDriveShift(invalidShift); h.advance(2_000)
                check(h.driveRetries() == 1 && h.driveHellos().size == 2)
                check(h.driveRetransmitFlags == listOf(true, false))
                val stage = h.session.stageDeadline
                repeat(3) { h.advance(2_500) }
                check(!h.session.sealed && h.retransmits() == 0 && h.driveHellos().size == 2)
                check(h.session.stageDeadline == stage)
                h.advance(2_500)
                check(h.session.sealed && h.stopReason == "DEADLINE_EXPIRED")
                check(h.gattObservations.last().deadlineCause == TeslaGattDeadlineCause.PROTOCOL_STAGE)
                noControl(h, voice)
            }
        })
        add("initial-invalid-original-global-exact-boundary-preempts-reserved-retry" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginAsleep(h)
                h.session.deadline = h.clock.elapsed + 2_000
                h.replyWake(); h.replyDriveShift(invalidShift)
                h.advance(2_000)
                check(h.session.sealed && h.stopReason == "DEADLINE_EXPIRED")
                check(h.gattObservations.last().deadlineCause == TeslaGattDeadlineCause.OPERATION)
                check(h.driveRetries() == 0 && h.driveRequests.size == 1)
                noControl(h, voice)
            }
        })
        add("initial-invalid-original-write-deadline-preempts-preparation-window" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginInitialDrive(h)
                h.replyDriveShift(invalidShift)
                h.autoCallbacks = false; h.advance(2_000)
                check(h.owner.pending != null && h.driveRetries() == 1)
                h.advance(5_000)
                check(h.session.sealed && h.stopReason == "DEADLINE_EXPIRED")
                check(h.gattObservations.last().deadlineCause == TeslaGattDeadlineCause.WRITE)
                val writes = h.sdkWrites.size
                h.completeAll(); h.advance(30_000)
                check(h.sdkWrites.size == writes)
                noControl(h, voice)
            }
        })
        for (offset in listOf(-1L, 0L, 1L)) {
            add("initial-invalid-fixed-wake-window-park-at-25seconds-offset-$offset" to {
                lossyHelloHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    beginWakeWithLostDriveHello(h)
                    val windowEnd = h.clock.elapsed + 25_000
                    h.clock.elapsed += 18_000; h.wallEpochMs += 18_000
                    h.answerHandshake()
                    h.replyDriveShift(invalidShift)
                    h.advance(2_000); h.answerHandshake()
                    check(h.driveRequests.size == 2 && h.driveRetries() == 1)
                    retryStillPreparing(h, voice)
                    val late = h.peer.encryptedResponse(h.requests.last().id, h.driveBody())
                    val jump = windowEnd + offset - h.clock.elapsed
                    h.clock.elapsed += jump; h.wallEpochMs += jump
                    h.replyDrive()
                    if (offset < 0) check(!h.session.sealed && voice.captures == 1)
                    else {
                        check(h.session.sealed && h.stopReason == "CONNECTION_EXPLICIT_P_REQUIRED")
                        noControl(h, voice)
                        h.inject(late); h.completeAll(); h.advance(30_000)
                        check(h.driveRequests.size == 2 && h.driveRetries() == 1)
                        noControl(h, voice)
                    }
                    late.fill(0)
                }
            })
        }
        add("initial-invalid-fixed-window-exact-boundary-refuses-reserved-new-hello" to {
            lossyHelloHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginWakeWithLostDriveHello(h)
                h.clock.elapsed += 18_000; h.wallEpochMs += 18_000
                h.answerHandshake(); h.advance(5_000)
                h.replyDriveShift(invalidShift)
                retryStillPreparing(h, voice)
                h.advance(2_000)
                check(h.session.sealed && h.stopReason == "CONNECTION_EXPLICIT_P_REQUIRED")
                check(h.driveRequests.size == 1 && h.driveRetries() == 0 && h.driveHellos().size == 1)
                noControl(h, voice)
            }
        })
        add("initial-invalid-pending-read-callback-delays-consumption-and-two-second-new-read" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginAsleep(h)
                h.autoCallbacks = false; h.replyWake()
                check(h.completeOne()) // domain3 HELLO 완료 뒤 READ callback을 보류한다.
                check(h.driveRequests.size == 1 && h.owner.pending != null)
                h.replyDriveShift(invalidShift)
                check(h.driveRetries() == 0 && h.session.conversation != null)
                noControl(h, voice)
                h.clock.elapsed += 3_000; h.wallEpochMs += 3_000
                h.completeAll()
                retryStillPreparing(h, voice)
                check(h.session.conversation == null && h.driveRetries() == 0)
                h.advance(1_999); check(h.driveRetries() == 0)
                h.advance(1); h.completeAll()
                check(h.driveRetries() == 1 && h.driveRequests.size == 2)
                retryStillPreparing(h, voice)
                h.replyDrive()
                check(!h.session.sealed && voice.captures == 1 && h.wakeFactoryCalls == 1)
            }
        })
        add("initial-invalid-retry-park-retains-original-ingress-and-stale-receipt-stops" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginInitialDrive(h)
                h.replyDriveShift(invalidShift); h.advance(2_000)
                val ingress = h.clock.elapsed
                h.clock.elapsed += 5_001; h.wallEpochMs += 5_001
                h.replyDrive(receivedAt = ingress)
                check(h.session.sealed && h.stopReason == "CONNECTION_P_RECEIPT_STALE")
                check(h.driveRetries() == 1 && h.driveRequests.size == 2)
                noControl(h, voice)
            }
        })
        add("initial-invalid-queued-park-before-window-is-rejected-when-drained-at-window" to {
            lossyHelloHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginWakeWithLostDriveHello(h)
                val windowEnd = h.clock.elapsed + 25_000
                h.clock.elapsed += 18_000; h.wallEpochMs += 18_000
                h.answerHandshake(); h.replyDriveShift(invalidShift)
                h.advance(2_000)
                h.advance(1_000) // 준비창이 READ write의 5초 기한보다 먼저 끝나도록 응답을 늦춘다.
                h.autoCallbacks = false; h.answerHandshake()
                check(h.driveRequests.size == 2 && h.owner.pending != null)
                h.replyDrive()
                retryStillPreparing(h, voice)
                val remaining = windowEnd - h.clock.elapsed
                h.clock.elapsed += remaining; h.wallEpochMs += remaining
                h.completeAll()
                check(h.session.sealed && h.stopReason == "CONNECTION_EXPLICIT_P_REQUIRED")
                check(h.driveRetries() == 1 && h.requests.none { it.kind == FRUNK })
                noControl(h, voice)
            }
        })
        for (kind in listOf("tag", "request-hash", "fault", "uuid", "route", "domain", "unsigned")) {
            add("initial-invalid-$kind-cannot-trigger-new-read-from-unverified-header" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    beginInitialDrive(h)
                    val stage = h.session.stageDeadline
                    val options = when (kind) {
                        "tag" -> OfficialTeslaPeer.ResponseOptions(tagTransform = {
                            it.apply { this[0] = (this[0].toInt() xor 1).toByte() }
                        })
                        "request-hash" -> OfficialTeslaPeer.ResponseOptions(requestHash = ByteArray(17) { 9 })
                        "fault" -> OfficialTeslaPeer.ResponseOptions(fault = 1)
                        "uuid" -> OfficialTeslaPeer.ResponseOptions(requestUuid = ByteArray(16) { 7 })
                        "route" -> OfficialTeslaPeer.ResponseOptions(route = ByteArray(16) { 7 })
                        "domain" -> OfficialTeslaPeer.ResponseOptions(domain = 2)
                        else -> OfficialTeslaPeer.ResponseOptions(envelopeMutation = { it.clearSignatureData() })
                    }
                    h.replyDriveShift(invalidShift, options = options)
                    check(h.driveRetries() == 0 && h.driveRequests.size == 1)
                    noControl(h, voice)
                    if (kind in listOf("uuid", "route", "domain")) {
                        check(!h.session.sealed && h.session.stageDeadline == stage)
                        h.replyDriveShift(invalidShift)
                        retryStillPreparing(h, voice)
                        h.advance(2_000)
                        check(h.driveRetries() == 1 && h.driveRequests.size == 2)
                        noControl(h, voice)
                    } else {
                        check(h.session.sealed && h.stopCount == 1)
                        h.advance(3_000)
                        check(h.driveRetries() == 0)
                    }
                }
            })
        }
        for (nominal in listOf(false, true)) {
            add("initial-invalid-${if (nominal) "nominal" else "ack"}-callback-delay-cannot-move-wake-window" to {
                lossyHelloHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    h.start(); h.answerHandshake(); h.replyBody(sleepStatus = 2); h.answerHandshake()
                    val windowEnd = h.clock.elapsed + 25_000
                    h.eventObserver = {
                        if (it == if (nominal) "TESLA_CONNECTION_WAKE_NOMINAL_ACK" else "TESLA_CONNECTION_WAKE_ACKNOWLEDGED") {
                            h.clock.elapsed += 4_000; h.wallEpochMs += 4_000
                        }
                    }
                    if (nominal) { h.inject(h.peer.nominalWakeResponse(h.requests.last().id)); h.settle() }
                    else h.replyWake()
                    h.clock.elapsed += 14_000; h.wallEpochMs += 14_000
                    h.answerHandshake(); h.replyDriveShift(invalidShift)
                    h.advance(2_000); h.answerHandshake()
                    check(h.driveRetries() == 1)
                    val remaining = windowEnd - h.clock.elapsed
                    h.clock.elapsed += remaining; h.wallEpochMs += remaining
                    h.replyDrive()
                    check(h.session.sealed && h.stopReason == "CONNECTION_EXPLICIT_P_REQUIRED")
                    noControl(h, voice)
                }
            })
        }
        add("initial-invalid-coalesced-old-park-cannot-skip-independent-new-read" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginInitialDrive(h)
                val request = h.requests.last()
                val invalidBody = com.tesla.generated.carserver.server.CarServer.Response.newBuilder()
                    .setVehicleData(Vehicle.VehicleData.newBuilder().setDriveState(
                        Vehicle.DriveState.newBuilder().setShiftState(invalidShift))).build().toByteArray()
                val invalid = h.peer.encryptedResponse(request.id, invalidBody)
                invalidBody.fill(0)
                val parkBody = h.driveBody()
                val oldPark = h.peer.encryptedResponse(request.id, parkBody)
                parkBody.fill(0)
                val coalesced = invalid + oldPark
                h.inject(coalesced); h.settle()
                coalesced.fill(0); invalid.fill(0); oldPark.fill(0)
                retryStillPreparing(h, voice)
                check(h.driveRequests.size == 1 && h.driveRetries() == 0)
                h.advance(2_000)
                retryStillPreparing(h, voice)
                check(h.driveRequests.size == 2 && h.driveRetries() == 1)
                h.replyDrive()
                check(!h.session.sealed && voice.captures == 1 && h.wakeFactoryCalls == 1)
            }
        })
        add("initial-invalid-original-global-exact-boundary-rejects-new-read-park" to {
            VehiclePeerHarness().use { h ->
                val voice = VoiceAdmission(h)
                beginAsleep(h)
                h.session.deadline = h.clock.elapsed + 8_000
                h.replyWake(); h.replyDriveShift(invalidShift); h.advance(2_000)
                val remaining = h.session.deadline - h.clock.elapsed
                h.clock.elapsed += remaining; h.wallEpochMs += remaining
                h.replyDrive()
                check(h.session.sealed && h.stopReason == "DEADLINE_EXPIRED")
                check(h.gattObservations.last().deadlineCause == TeslaGattDeadlineCause.OPERATION)
                check(h.driveRetries() == 1 && h.driveRequests.size == 2)
                noControl(h, voice)
            }
        })
        for (invalid in listOf(false, true)) {
            add("initial-invalid-new-read-protocol-exact-boundary-rejects-${if (invalid) "invalid" else "park"}" to {
                VehiclePeerHarness().use { h ->
                    val voice = VoiceAdmission(h)
                    beginInitialDrive(h)
                    h.replyDriveShift(invalidShift); h.advance(2_000)
                    h.clock.elapsed += 10_000; h.wallEpochMs += 10_000
                    if (invalid) h.replyDriveShift(invalidShift) else h.replyDrive()
                    check(h.session.sealed && h.stopReason == "DEADLINE_EXPIRED")
                    check(h.gattObservations.last().deadlineCause == TeslaGattDeadlineCause.PROTOCOL_STAGE)
                    check(h.driveRequests.size == 2 && h.driveRetries() == 1)
                    noControl(h, voice)
                }
            })
        }
    }
}
