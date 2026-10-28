package com.heytesla.app.vehicle.protocol

import com.google.protobuf.ByteString
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec
import org.junit.Assert.*
import org.junit.Test

/** 기존 JVM fixture로 생산 경로를 회귀한다. 독립 peer·실차 본문 재현·SDK 송신 증거가 아니다. */
class TeslaBleProtocolObservationTest {
    @Test fun unrecognizedAndActualDecodeFailureSummariesFollowAllThreeBodyTransports() {
        val cases = listOf(
            BodyCase(byteArrayOf(), TeslaBleRxFiraKind.UNRECOGNIZED, TeslaBleRxBodyStatus.COMPLETE, emptyList()),
            BodyCase(firaTestMessage { writeUInt32(61, 7) }, TeslaBleRxFiraKind.UNRECOGNIZED,
                TeslaBleRxBodyStatus.COMPLETE, listOf(TeslaBleRxBodyField(61, 0, 1))),
            BodyCase(firaTestMessage { writeByteArray(54, byteArrayOf(0x80.toByte())) },
                TeslaBleRxFiraKind.INVALID, TeslaBleRxBodyStatus.COMPLETE,
                listOf(TeslaBleRxBodyField(54, 2, 1))),
            BodyCase(firaTestMessage { repeat(9) { writeUInt32(61, 7) } },
                TeslaBleRxFiraKind.UNRECOGNIZED, TeslaBleRxBodyStatus.TRUNCATED,
                List(8) { TeslaBleRxBodyField(61, 0, 1) }),
            BodyCase(firaTestMessage { writeTag(61, 3); writeUInt32(62, 7); writeTag(61, 4) },
                TeslaBleRxFiraKind.UNRECOGNIZED, TeslaBleRxBodyStatus.UNSUPPORTED, emptyList()),
        )
        for (kind in transports) for (case in cases) {
            TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
                ready(fixture)
                val request = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
                val step = fixture.conversation.receive(wire(fixture, request, case.body, kind))
                assertTrue(if (case.firaKind == TeslaBleRxFiraKind.INVALID) step is TeslaBleStep.Failed
                    else step is TeslaBleStep.Waiting)
                val observed = fixture.responseObservations.last()
                assertEquals(kind, observed.inputKind)
                assertEquals(case.firaKind, observed.firaKind)
                assertEquals(TeslaBleRxBodySummary(case.body.size, case.status, case.fields), observed.bodySummary)
                assertEquals(if (kind == TeslaBleRxInputKind.RAW_FIRA) null
                    else TeslaBleRxRequestCorrelation.CURRENT, observed.requestCorrelation)
                assertEquals(if (kind == TeslaBleRxInputKind.ENCRYPTED_RESPONSE) TeslaBleRxAead.VERIFIED
                    else TeslaBleRxAead.NOT_ATTEMPTED, observed.aead)
                assertNull(observed.authEvidence)
            }
        }
    }

    @Test fun normalRequestAndStopNeverCreateBodySummary() {
        val bodies = listOf(firaTestRequest(listOf(FiraTestSession(1))),
            firaTestMessage { writeByteArray(48, firaTestMessage { writeEnum(1, 3) }) })
        for (kind in transports) for (body in bodies) {
            TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
                ready(fixture)
                val request = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
                val step = fixture.conversation.receive(wire(fixture, request, body, kind)) as TeslaBleStep.UwbInput
                step.message.close()
                assertNull(fixture.responseObservations.last().bodySummary)
                assertEquals(TeslaBleRxStep.UWB_INPUT, fixture.responseObservations.last().step)
            }
        }
    }

    @Test fun actualMalformedBodyIsSummarizedButPreBodyParseFailureIsNot() {
        TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = ready(fixture)
            val body = byteArrayOf(0x08, 0x80.toByte())
            val wrapped = fixture.baseResponse(hello).setProtobufMessageAsBytes(ByteString.copyFrom(body)).build()
            assertTrue(fixture.conversation.receive(wrapped.toByteArray()) is TeslaBleStep.Failed)
            val observed = fixture.responseObservations.last()
            assertEquals(TeslaBleRxFiraKind.INVALID, observed.firaKind)
            assertEquals(TeslaBleRxBodySummary(2, TeslaBleRxBodyStatus.MALFORMED, emptyList()), observed.bodySummary)
            assertEquals(TeslaBleRxRequestCorrelation.CURRENT, observed.requestCorrelation)
        }
        TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            ready(fixture)
            assertTrue(fixture.conversation.receive(byteArrayOf(0x80.toByte())) is TeslaBleStep.Failed)
            val observed = fixture.responseObservations.last()
            assertNull(observed.bodySummary)
            assertNull(observed.requestCorrelation)
            assertNull(observed.firaKind)
        }
    }

    @Test fun headerCorrelationUsesOneGenerationWithoutMixingDomainRouteOrUuid() {
        TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = fixture.start()
            val emptyIdentity = fixture.helloAck(hello).toBuilder().clearRequestUuid()
                .setToDestination(routeDestination(ByteString.EMPTY)).build()
            assertTrue(fixture.conversation.receive(emptyIdentity.toByteArray()) is TeslaBleStep.Waiting)
            assertEquals(TeslaBleRxRequestCorrelation.UNMATCHED, fixture.responseObservations.last().requestCorrelation)
            assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            val previous = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
            val current = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
            val body = firaTestMessage { writeUInt32(61, 7) }
            fun response(request: UniversalMessage.RoutableMessage) = fixture.baseResponse(request)
                .setProtobufMessageAsBytes(ByteString.copyFrom(body))
            val cases = listOf(
                response(current).build() to TeslaBleRxRequestCorrelation.CURRENT,
                response(previous).build() to TeslaBleRxRequestCorrelation.PREVIOUS,
                response(hello).build() to TeslaBleRxRequestCorrelation.UNMATCHED,
                response(current).setRequestUuid(previous.uuid).build() to TeslaBleRxRequestCorrelation.UNMATCHED,
                response(previous).setRequestUuid(current.uuid).build() to TeslaBleRxRequestCorrelation.UNMATCHED,
                response(current).clearRequestUuid().build() to TeslaBleRxRequestCorrelation.CURRENT,
                response(previous).clearRequestUuid().build() to TeslaBleRxRequestCorrelation.PREVIOUS,
                response(current).clearToDestination().build() to TeslaBleRxRequestCorrelation.UNMATCHED,
            )
            for ((message, expected) in cases) {
                // Plaintext has no route/UUID acceptance gate; diagnostics must not introduce one.
                assertTrue(fixture.conversation.receive(message.toByteArray()) is TeslaBleStep.Waiting)
                val observed = fixture.responseObservations.last()
                assertEquals(expected, observed.requestCorrelation)
                assertEquals(TeslaBleRxFiraKind.UNRECOGNIZED, observed.firaKind)
                assertNotNull(observed.bodySummary)
            }
            val wrongDomain = response(current).setFromDestination(UniversalMessage.Destination.newBuilder()
                .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)).build()
            assertTrue(fixture.conversation.receive(wrongDomain.toByteArray()) is TeslaBleStep.Waiting)
            val excluded = fixture.responseObservations.last()
            assertEquals(TeslaBleRxRequestCorrelation.UNMATCHED, excluded.requestCorrelation)
            assertNull(excluded.bodySummary)
            assertEquals("DOMAIN_MISMATCH", excluded.disposition)
            val wrongSourceKind = response(current).setFromDestination(routeDestination(current.fromDestination.routingAddress))
                .build()
            assertTrue(fixture.conversation.receive(wrongSourceKind.toByteArray()) is TeslaBleStep.Waiting)
            assertEquals(TeslaBleRxRequestCorrelation.UNMATCHED, fixture.responseObservations.last().requestCorrelation)
            assertNull(fixture.responseObservations.last().bodySummary)
        }
    }

    @Test fun previousEncryptedIdentityIsOnlyDiagnosticAndNeverAcceptedOrDecrypted() {
        TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            ready(fixture)
            val previous = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
            val oldResponse = fixture.encryptResponse(previous,
                Vcsec.FromVCSECMessage.parseFrom(firaTestMessage { writeUInt32(61, 7) }), 8)
            val current = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
            assertTrue(fixture.conversation.receive(oldResponse.toByteArray()) is TeslaBleStep.Waiting)
            var observed = fixture.responseObservations.last()
            assertEquals(TeslaBleRxRequestCorrelation.PREVIOUS, observed.requestCorrelation)
            assertEquals("ROUTE_MISMATCH", observed.disposition)
            assertEquals(TeslaBleRxAead.NOT_ATTEMPTED, observed.aead)
            assertNull(observed.bodySummary)
            val mixed = oldResponse.toBuilder().setToDestination(current.fromDestination).build()
            assertTrue(fixture.conversation.receive(mixed.toByteArray()) is TeslaBleStep.Waiting)
            observed = fixture.responseObservations.last()
            assertEquals(TeslaBleRxRequestCorrelation.UNMATCHED, observed.requestCorrelation)
            assertEquals("REQUEST_UUID_MISMATCH", observed.disposition)
            assertEquals(TeslaBleRxAead.NOT_ATTEMPTED, observed.aead)
            assertNull(observed.bodySummary)
            parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
            assertTrue(fixture.conversation.receive(oldResponse.toByteArray()) is TeslaBleStep.Waiting)
            assertEquals(TeslaBleRxRequestCorrelation.UNMATCHED, fixture.responseObservations.last().requestCorrelation)
        }
    }

    @Test fun currentHeaderDoesNotMeanAeadVerificationAndReplayResetsSummary() {
        for (tamper in listOf(false, true)) {
            TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
                ready(fixture)
                val request = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
                val valid = fixture.encryptResponse(request,
                    Vcsec.FromVCSECMessage.parseFrom(firaTestMessage { writeUInt32(61, 7) }), 8)
                val message = if (tamper) {
                    val signature = valid.signatureData.getAESGCMResponseData()
                    val tag = signature.tag.toByteArray()
                    tag[0] = (tag[0].toInt() xor 1).toByte()
                    valid.toBuilder().setSignatureData(valid.signatureData.toBuilder()
                        .setAESGCMResponseData(signature.toBuilder().setTag(ByteString.copyFrom(tag)))).build()
                        .also { tag.fill(0) }
                } else valid
                if (!tamper) {
                    assertTrue(fixture.conversation.receive(valid.toByteArray()) is TeslaBleStep.Waiting)
                    assertNotNull(fixture.responseObservations.last().bodySummary)
                }
                assertTrue(fixture.conversation.receive(message.toByteArray()) is TeslaBleStep.Failed)
                val observed = fixture.responseObservations.last()
                assertEquals(TeslaBleRxRequestCorrelation.CURRENT, observed.requestCorrelation)
                assertEquals(if (tamper) TeslaBleRxAead.FAILED else TeslaBleRxAead.NOT_ATTEMPTED, observed.aead)
                assertNull(observed.bodySummary)
                assertNull(observed.firaKind)
            }
        }
    }

    @Test fun everyReceiveResetsSummaryAndCorrelationWithoutMutatingEarlierMetadata() {
        TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = ready(fixture)
            val marker = "SECRET_MARKER_STS_VIN_ROUTE_UUID".toByteArray(Charsets.US_ASCII)
            val body = firaTestMessage { writeByteArray(61, marker) }
            val wrapped = fixture.baseResponse(hello).setProtobufMessageAsBytes(ByteString.copyFrom(body)).build()
            assertTrue(fixture.conversation.receive(wrapped.toByteArray()) is TeslaBleStep.Waiting)
            val first = fixture.responseObservations.last()
            assertEquals(TeslaBleRxRequestCorrelation.CURRENT, first.requestCorrelation)
            assertEquals(listOf(TeslaBleRxBodyField(61, 2, marker.size)), first.bodySummary?.fields)
            assertFalse(first.toString().contains("SECRET_MARKER"))
            assertTrue(fixture.conversation.receive(body) is TeslaBleStep.Waiting)
            assertNotNull(fixture.responseObservations.last().bodySummary)
            assertNull(fixture.responseObservations.last().requestCorrelation)
            val noPayload = fixture.baseResponse(hello).build()
            assertTrue(fixture.conversation.receive(noPayload.toByteArray()) is TeslaBleStep.Waiting)
            assertNull(fixture.responseObservations.last().bodySummary)
            assertEquals(TeslaBleRxRequestCorrelation.CURRENT, fixture.responseObservations.last().requestCorrelation)
            val request = fixture.conversation.receive(firaTestRequest(listOf(FiraTestSession(1)))) as TeslaBleStep.UwbInput
            request.message.close()
            assertNull(fixture.responseObservations.last().bodySummary)
            assertNull(fixture.responseObservations.last().requestCorrelation)
            assertTrue(fixture.conversation.receive(byteArrayOf(0x80.toByte())) is TeslaBleStep.Failed)
            assertNull(fixture.responseObservations.last().bodySummary)
            assertNull(fixture.responseObservations.last().requestCorrelation)
            body.fill(0)
            marker.fill(0)
            assertEquals(listOf(TeslaBleRxBodyField(61, 2, marker.size)), first.bodySummary?.fields)
            assertEquals(TeslaBleRxRequestCorrelation.CURRENT, first.requestCorrelation)
        }
    }

    @Test fun generatedIdentityRotationWipesOlderArraysAndCloseOrFailureErasesBothGenerations() {
        for (close in listOf(false, true)) {
            TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
                val hello = ready(fixture)
                val helloRoute = requireNotNull(identityBytes(fixture.conversation, "route"))
                val helloUuid = requireNotNull(identityBytes(fixture.conversation, "requestUuid"))
                parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
                assertSame(helloRoute, identityBytes(fixture.conversation, "previousRoute"))
                assertSame(helloUuid, identityBytes(fixture.conversation, "previousRequestUuid"))
                val firstRoute = requireNotNull(identityBytes(fixture.conversation, "route"))
                val firstUuid = requireNotNull(identityBytes(fixture.conversation, "requestUuid"))
                parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
                assertTrue(helloRoute.all { it == 0.toByte() })
                assertTrue(helloUuid.all { it == 0.toByte() })
                assertSame(firstRoute, identityBytes(fixture.conversation, "previousRoute"))
                assertSame(firstUuid, identityBytes(fixture.conversation, "previousRequestUuid"))
                val currentRoute = requireNotNull(identityBytes(fixture.conversation, "route"))
                val currentUuid = requireNotNull(identityBytes(fixture.conversation, "requestUuid"))
                if (close) fixture.conversation.close()
                else assertTrue(fixture.conversation.receive(byteArrayOf(0x80.toByte())) is TeslaBleStep.Failed)
                for (bytes in listOf(firstRoute, firstUuid, currentRoute, currentUuid)) {
                    assertTrue(bytes.all { it == 0.toByte() })
                }
                assertNull(identityBytes(fixture.conversation, "previousRoute"))
                assertNull(identityBytes(fixture.conversation, "previousRequestUuid"))
                assertTrue(fixture.conversation.receive(fixture.baseResponse(hello).build().toByteArray()) is TeslaBleStep.Failed)
                assertNull(fixture.responseObservations.last().bodySummary)
                assertNull(fixture.responseObservations.last().requestCorrelation)
            }
        }
    }

    @Test fun nonUwbAndObserverlessConversationsDoNotRetainDiagnosticIdentity() {
        TeslaBleProtocolTest.VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            assertTrue(fixture.conversation.receive(fixture.status(request).toByteArray()) is TeslaBleStep.Complete)
            assertTrue(fixture.responseObservations.all { it.bodySummary == null && it.requestCorrelation == null })
            assertNull(identityBytes(fixture.conversation, "previousRoute"))
            assertNull(identityBytes(fixture.conversation, "previousRequestUuid"))
        }
        TeslaBleProtocolTest.VehicleFixture().use { fixture ->
            TeslaBleConversation(fixture.client, fixture.vin, purpose = TeslaBlePurpose.UWB,
                onResponseObservation = null, now = { fixture.clock }).use { conversation ->
                val hello = parseSend(conversation.start(false))
                assertEquals(TeslaBleStep.UwbReady, conversation.receive(fixture.handshake(hello).toByteArray()))
                repeat(3) {
                    parseSend(conversation.sendFira(TeslaFiraProtocol.capabilities()))
                    assertNull(identityBytes(conversation, "previousRoute"))
                    assertNull(identityBytes(conversation, "previousRequestUuid"))
                }
                assertTrue(conversation.receive(firaTestMessage { writeUInt32(61, 7) }) is TeslaBleStep.Waiting)
            }
        }
    }

    private data class BodyCase(val body: ByteArray, val firaKind: TeslaBleRxFiraKind,
        val status: TeslaBleRxBodyStatus, val fields: List<TeslaBleRxBodyField>)

    private fun ready(fixture: TeslaBleProtocolTest.VehicleFixture): UniversalMessage.RoutableMessage {
        val hello = fixture.start()
        assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
        assertNull(fixture.responseObservations.last().bodySummary)
        assertEquals(TeslaBleRxRequestCorrelation.CURRENT, fixture.responseObservations.last().requestCorrelation)
        return hello
    }

    private fun wire(fixture: TeslaBleProtocolTest.VehicleFixture, request: UniversalMessage.RoutableMessage,
        body: ByteArray, kind: TeslaBleRxInputKind): ByteArray = when (kind) {
        TeslaBleRxInputKind.RAW_FIRA -> body
        TeslaBleRxInputKind.WRAPPED_PLAINTEXT -> fixture.baseResponse(request)
            .setProtobufMessageAsBytes(ByteString.copyFrom(body)).build().toByteArray()
        TeslaBleRxInputKind.ENCRYPTED_RESPONSE -> fixture.encryptResponse(request,
            Vcsec.FromVCSECMessage.parseFrom(body), 8).toByteArray()
        else -> error("not a FiRa transport")
    }

    private fun parseSend(step: TeslaBleStep): UniversalMessage.RoutableMessage {
        assertTrue(step is TeslaBleStep.Send)
        return try { UniversalMessage.RoutableMessage.parseFrom((step as TeslaBleStep.Send).payload) }
            finally { (step as TeslaBleStep.Send).payload.fill(0) }
    }

    private fun routeDestination(route: ByteString): UniversalMessage.Destination =
        UniversalMessage.Destination.newBuilder().setRoutingAddress(route).build()

    private fun identityBytes(conversation: TeslaBleConversation, name: String): ByteArray? =
        TeslaBleConversation::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(conversation) as ByteArray?
        }

    private companion object {
        val transports = listOf(TeslaBleRxInputKind.RAW_FIRA, TeslaBleRxInputKind.WRAPPED_PLAINTEXT,
            TeslaBleRxInputKind.ENCRYPTED_RESPONSE)
    }
}
