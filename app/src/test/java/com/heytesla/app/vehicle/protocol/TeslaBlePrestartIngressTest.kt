package com.heytesla.app.vehicle.protocol

import com.google.protobuf.ByteString
import com.heytesla.app.vehicle.protocol.TeslaBleProtocolTest.VehicleFixture
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec
import org.junit.Assert.*
import org.junit.Test

class TeslaBlePrestartIngressTest {
    private enum class Mode { CARD, READ_ONLY, FRUNK, WAKE, DRIVE }

    private class Fixture(val mode: Mode) : AutoCloseable {
        val wire = VehicleFixture(query = if (mode == Mode.DRIVE) TeslaBleQuery.DRIVE_STATE else TeslaBleQuery.BODY_STATUS)
        val observations = ArrayList<TeslaBleResponseObservation>()
        var authorizationCalls = 0
        val conversation = TeslaBleConversation(wire.client, wire.vin, wire.query,
            purpose = when (mode) {
                Mode.FRUNK -> TeslaBlePurpose.FRUNK_OPEN
                Mode.WAKE -> TeslaBlePurpose.VEHICLE_WAKE
                else -> TeslaBlePurpose.READ_ONLY
            }, authorizeFrunk = if (mode == Mode.FRUNK) ({ authorizationCalls++; null }) else null,
            frunkDeadline = (wire.clock + 60_000).takeIf { mode == Mode.FRUNK },
            authorizeWake = if (mode == Mode.WAKE) ({ authorizationCalls++; null }) else null,
            wakeDeadline = (wire.clock + 60_000).takeIf { mode == Mode.WAKE },
            onResponseObservation = { observations.add(it) }, now = { wire.clock })
        val startedAt = wire.clock
        fun start() = send(conversation.start(mode == Mode.CARD))
        override fun close() { conversation.close(); wire.close() }
    }

    @Test fun prestartClassificationPrecedesAllPayloadInterpretationInEveryPurpose() {
        for (mode in Mode.entries) Fixture(mode).use { f ->
            val hello = f.start()
            val payloads = listOf(
                byteArrayOf(0x80.toByte()),
                f.wire.vehicleStatus(1, 0).toByteArray(),
                byteArrayOf(0x18, 0x01),
                byteArrayOf(0x08, 0x02),
                f.wire.baseResponse(hello).setProtobufMessageAsBytes(ByteString.copyFrom(byteArrayOf(0x08, 0x01)))
                    .build().toByteArray(),
                f.wire.handshake(hello).toByteArray(),
                registrationSuccess(),
            )
            for (payload in payloads) {
                assertSame(mode.name, TeslaBleStep.Ignored, f.conversation.receive(payload, f.startedAt - 1))
                assertIgnored(f.observations.last())
                assertEquals(0, f.authorizationCalls)
                assertFalse(f.conversation.registrationReported)
            }
            // No first-payload or shape exception: only a normally timed response may transition.
            if (mode == Mode.CARD) {
                val next = send(f.conversation.receive(registrationSuccess(), f.startedAt))
                assertTrue(next.hasSessionInfoRequest())
                assertTrue(f.conversation.registrationReported)
            } else {
                val accepted = f.conversation.receive(f.wire.handshake(hello).toByteArray(), f.startedAt)
                assertTrue(accepted is TeslaBleStep.Send)
                accepted as TeslaBleStep.Send
                assertEquals(mode == Mode.FRUNK, accepted.actuator)
                assertEquals(mode == Mode.WAKE, accepted.wake)
                accepted.payload.fill(0)
                assertEquals("SESSION_READY", f.observations.last().disposition)
                assertEquals(if (mode == Mode.FRUNK || mode == Mode.WAKE) 1 else 0, f.authorizationCalls)
            }
        }
    }

    @Test fun repeatedPrestartFramesCannotRefreshResponseOrWholeConversationBudget() {
        for (mode in Mode.entries) Fixture(mode).use { f ->
            val hello = f.start()
            val timeout = if (mode == Mode.CARD) 180_000L else 15_000L
            for (offset in listOf(1L, timeout / 2, timeout - 1)) {
                f.wire.clock = f.startedAt + offset
                assertSame(TeslaBleStep.Ignored, f.conversation.receive(byteArrayOf(0x08, 0x02), f.startedAt - 1))
            }
            f.wire.clock = f.startedAt + timeout
            assertFailed(f.conversation.receive(byteArrayOf(0x08, 0x02), f.startedAt - 1))
            assertEquals(TeslaBleRxStep.FAILED, f.observations.last().step)
            assertFailed(f.conversation.receive(f.wire.handshake(hello).toByteArray()))
            assertEquals(0, f.authorizationCalls)
        }
    }

    @Test fun invalidTimeSizeAndLifecycleGuardsRemainStricterThanPrestartClassification() {
        for (condition in listOf("negative", "future", "rollback", "expired", "oversize", "new", "closed", "terminal")) {
            Fixture(Mode.READ_ONLY).use { f ->
                val hello = if (condition == "new") null else f.start()
                when (condition) {
                    "rollback" -> {
                        f.wire.clock++
                        assertSame(TeslaBleStep.Ignored, f.conversation.receive(byteArrayOf(0x80.toByte()), f.startedAt - 1))
                        f.wire.clock--
                    }
                    "expired" -> f.wire.clock += 15_000
                    "closed" -> f.conversation.close()
                    "terminal" -> assertFailed(f.conversation.receive(byteArrayOf(0x80.toByte())))
                }
                val receivedAt = when (condition) {
                    "negative" -> -1L
                    "future" -> f.wire.clock + 1
                    else -> f.startedAt - 1
                }
                val payload = if (condition == "oversize") ByteArray(TESLA_BLE_MAX_FRAME_BYTES + 1)
                    else hello?.let { f.wire.handshake(it).toByteArray() } ?: byteArrayOf(0x80.toByte())
                assertFailed(f.conversation.receive(payload, receivedAt))
                assertEquals(condition, TeslaBleRxStep.FAILED, f.observations.last().step)
                assertNotEquals("RESPONSE_BEFORE_START", f.observations.last().disposition)
            }
        }
    }

    @Test fun normallyTimedMalformedFramesStillFailAfterIgnoredInput() {
        for (mode in Mode.entries) Fixture(mode).use { f ->
            f.start()
            val malformed = byteArrayOf(0x80.toByte())
            assertSame(TeslaBleStep.Ignored, f.conversation.receive(malformed, f.startedAt - 1))
            assertFailed(f.conversation.receive(malformed, f.startedAt))
            assertEquals(TeslaBleRxStep.FAILED, f.observations.last().step)
            assertEquals(0, f.authorizationCalls)
        }
    }

    @Test fun ignoredEncryptedStatusDoesNotConsumeReplayCounterOrChangeAuthenticationRequirements() {
        VehicleFixture().use { f ->
            val startedAt = f.clock
            val request = f.authenticate()
            val response = f.status(request, counter = 19).toByteArray()
            assertSame(TeslaBleStep.Ignored, f.conversation.receive(response, startedAt - 1))
            assertIgnored(f.responseObservations.last())
            val accepted = f.conversation.receive(response, startedAt)
            assertTrue(accepted is TeslaBleStep.Complete)
            assertEquals(1, (accepted as TeslaBleStep.Complete).status.lockState)
        }
        VehicleFixture().use { f ->
            val startedAt = f.clock
            val request = f.authenticate()
            val body = Vcsec.FromVCSECMessage.getDefaultInstance()
            val response = f.encryptResponse(request, body, counter = 19).toByteArray()
            assertSame(TeslaBleStep.Ignored, f.conversation.receive(response, startedAt - 1))
            assertTrue(f.conversation.receive(response, startedAt) is TeslaBleStep.Waiting)
            assertFailed(f.conversation.receive(response, startedAt))
        }
    }

    @Test fun ignoredInputCannotHideNormallyTimedHmacRouteUuidOrAeadFailures() {
        for (binding in listOf("hmac", "route", "uuid")) VehicleFixture().use { f ->
            val hello = f.start()
            val good = f.handshake(hello)
            assertSame(TeslaBleStep.Ignored, f.conversation.receive(good.toByteArray(), f.clock - 1))
            val bad = good.toBuilder().apply {
                when (binding) {
                    "hmac" -> signatureData = signatureData.toBuilder().setSessionInfoTag(
                        signatureData.sessionInfoTag.toBuilder().setTag(ByteString.copyFrom(ByteArray(32)))).build()
                    "route" -> toDestination = toDestination.toBuilder()
                        .setRoutingAddress(ByteString.copyFrom(ByteArray(16) { 7 })).build()
                    else -> requestUuid = ByteString.copyFrom(ByteArray(16) { 7 })
                }
            }.build()
            val result = f.conversation.receive(bad.toByteArray())
            assertTrue(result is TeslaBleStep.Waiting)
            if (binding == "hmac") assertEquals(TeslaBleAuthEvidence.HMAC_MISMATCH,
                (result as TeslaBleStep.Waiting).authEvidence)
            else assertEquals(if (binding == "route") "ROUTE_MISMATCH" else "REQUEST_UUID_MISMATCH",
                f.responseObservations.last().disposition)
            assertTrue(f.conversation.receive(good.toByteArray()) is TeslaBleStep.Send)
        }
        VehicleFixture().use { f ->
            val startedAt = f.clock
            val request = f.authenticate()
            val response = f.status(request).toBuilder().apply {
                signatureData = signatureData.toBuilder().setAESGCMResponseData(
                    signatureData.getAESGCMResponseData().toBuilder().setTag(ByteString.copyFrom(ByteArray(16)))).build()
            }.build().toByteArray()
            assertSame(TeslaBleStep.Ignored, f.conversation.receive(response, startedAt - 1))
            assertFailed(f.conversation.receive(response))
            assertEquals(TeslaBleRxAead.FAILED, f.responseObservations.last().aead)
        }
    }

    private companion object {
        fun send(step: TeslaBleStep): UniversalMessage.RoutableMessage {
            assertTrue(step is TeslaBleStep.Send)
            val payload = (step as TeslaBleStep.Send).payload
            return try { UniversalMessage.RoutableMessage.parseFrom(payload) } finally { payload.fill(0) }
        }
        fun registrationSuccess(): ByteArray = Vcsec.FromVCSECMessage.newBuilder().setCommandStatus(
            Vcsec.CommandStatus.newBuilder().setOperationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_OK)
                .setWhitelistOperationStatus(Vcsec.WhitelistOperation_status.newBuilder()
                    .setOperationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_OK))).build().toByteArray()
        fun assertFailed(step: TeslaBleStep) { assertTrue(step is TeslaBleStep.Failed) }
        fun assertIgnored(observation: TeslaBleResponseObservation) {
            assertEquals(TeslaBleRxStep.IGNORED, observation.step)
            assertEquals("RESPONSE_BEFORE_START", observation.disposition)
            assertEquals(TeslaBleRxAead.NOT_ATTEMPTED, observation.aead)
            assertNull(observation.authEvidence)
            assertNull(observation.inputKind)
            assertNull(observation.bodySummary)
            assertNull(observation.fromPresent)
            assertNull(observation.toPresent)
            assertNull(observation.requestCorrelation)
        }
    }
}
