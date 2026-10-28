package com.heytesla.app.vehicle.protocol

import com.heytesla.app.vehicle.frunk.TeslaFrunkPolicy
import com.heytesla.app.vehicle.frunk.TeslaFrunkResult
import com.heytesla.app.vehicle.frunk.TeslaFrunkSequence
import com.heytesla.app.vehicle.key.TeslaBleCredential
import com.heytesla.app.vehicle.key.p256PublicKeyBytes
import com.heytesla.app.vehicle.key.p256PublicKeyFromBytes

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.google.protobuf.Timestamp
import com.tesla.generated.carserver.common.Common
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.keys.Keys
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 소스만 추가. 테스트 키는 실행 시 JVM JCA로만 생성하며 저장/출력/차량 등록하지 않는다. */
class TeslaBleProtocolTest {
    @Test fun serviceRangingContinuationRequiresAuthenticatedUwbPurpose() {
        for (purpose in TeslaBlePurpose.entries) {
            VehicleFixture(purpose = purpose, serviceOwned = true).use { fixture ->
                try {
                    fixture.conversation.continueServiceRanging()
                    fail("unstarted operation must not remove its deadline")
                } catch (_: IllegalStateException) { }
            }
        }
        VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = fixture.start()
            assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            try {
                fixture.conversation.continueServiceRanging()
                fail("manual UWB must retain its deadline")
            } catch (_: IllegalStateException) { }
        }
    }

    @Test fun serviceInitialAuthenticationAndOobKeepOriginalTimeoutUntilValidRanging() {
        for (serviceOwned in listOf(false, true)) {
            VehicleFixture(purpose = TeslaBlePurpose.UWB, serviceOwned = serviceOwned).use { fixture ->
                val hello = fixture.start()
                assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
                fixture.clock += 180_000
                assertFailed(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
            }
            VehicleFixture(purpose = TeslaBlePurpose.UWB, serviceOwned = serviceOwned).use { fixture ->
                val hello = fixture.start()
                assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
                fixture.clock += 180_000
                assertFailed(fixture.conversation.receive(firaTestRequest(listOf(FiraTestSession(1)))))
            }
        }
    }

    @Test fun serviceRangingContinuesAuthenticatedFiraPastInitialBudgetButCloseStillSeals() {
        VehicleFixture(purpose = TeslaBlePurpose.UWB, serviceOwned = true).use { fixture ->
            val hello = fixture.start()
            assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            fixture.conversation.continueServiceRanging()
            fixture.clock += 3_600_000
            val stop = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.stopped(listOf(1))))
            assertTrue(stop.signatureData.hasAESGCMPersonalizedData())
            val input = fixture.conversation.receive(firaTestRequest(listOf(FiraTestSession(1))))
            assertTrue(input is TeslaBleStep.UwbInput)
            (input as TeslaBleStep.UwbInput).message.close()
            fixture.conversation.close()
            assertFailed(fixture.conversation.sendFira(TeslaFiraProtocol.stopped(listOf(1))))
        }
    }

    @Test fun firaRequestBeforeSessionAuthenticationCannotAdvanceOrReleaseSts() {
        VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = fixture.start()
            val input = firaTestRequest(listOf(FiraTestSession(1)))
            assertWaiting(TeslaBlePhase.HANDSHAKING, null, fixture.conversation.receive(input))
            assertWaiting(TeslaBlePhase.HANDSHAKING, null, fixture.conversation.receive(
                fixture.baseResponse(hello).setProtobufMessageAsBytes(ByteString.copyFrom(input)).build().toByteArray()))
            assertFailed(fixture.conversation.sendFira(byteArrayOf(1, 2, 3)))
        }
        VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            assertFailed(fixture.conversation.start(true)) // UWB can never register/create a key.
        }
        VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = fixture.start()
            val missingTag = fixture.handshake(hello).toBuilder().clearSignatureData().build()
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.SIGNATURE_MISSING,
                fixture.conversation.receive(missingTag.toByteArray()))
            val secretBody = byteArrayOf(12, 34, 56)
            assertFailed(fixture.conversation.sendFira(secretBody))
            assertTrue(secretBody.all { it == 0.toByte() })
        }
    }

    @Test fun firaUnavailablePrivateKeyCannotAuthenticateOrTransmitStsAndExceptionTextIsDiscarded() {
        VehicleFixture().use { fixture ->
            val missingPrivateKey = object : TeslaBleCredential {
                override fun publicKeyBytes(): ByteArray = fixture.client.publicKeyBytes()
                override fun deriveSessionKey(vehiclePublicKey: ByteArray): ByteArray =
                    throw IllegalStateException("TEST_PRIVATE_KEY_MATERIAL")
            }
            TeslaBleConversation(missingPrivateKey, fixture.vin, purpose = TeslaBlePurpose.UWB,
                now = { fixture.clock }).use { conversation ->
                val hello = parseSend(conversation.start(false))
                assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.SESSION_INFO_INVALID,
                    conversation.receive(fixture.handshake(hello).toByteArray()))
                val plaintext = byteArrayOf(77, 88, 99)
                val result = conversation.sendFira(plaintext) as TeslaBleStep.Failed
                assertFalse(result.reason.contains("TEST_PRIVATE_KEY_MATERIAL"))
                assertTrue(plaintext.all { it == 0.toByte() })
            }
        }
    }

    @Test fun firaUsesExistingSessionAeadForCapabilitiesStsAndStopWithoutClaimingAckSuccess() {
        VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = fixture.start()
            assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            val caps = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
            val capsPlaintext = fixture.decryptRequestPayload(caps)
            val capInput = com.google.protobuf.CodedInputStream.newInstance(capsPlaintext)
            assertEquals((58 shl 3) or 2, capInput.readTag())
            assertEquals(0, capInput.readByteArray().size)
            assertTrue(capInput.isAtEnd)
            capsPlaintext.fill(0)
            val raw = firaTestRequest(listOf(FiraTestSession(0xffff_ffffL, mode = 2, peers = byteArrayOf(3, 4, 5, 6))))
            val step = fixture.conversation.receive(raw) as TeslaBleStep.UwbInput
            val request = step.message as TeslaFiraMessage.Request
            assertEquals(0xffff_ffffL, request.sessions.single().requestSessionId)
            request.use { r ->
                TeslaFiraNegotiation(TeslaFiraCapabilities(true, true, listOf(5), listOf(9),
                    listOf(4), listOf(2), listOf(3))).use { negotiation ->
                    val plans = negotiation.prepare(r)
                    assertEquals(0xffff_ffffL, plans.single().requestSessionId)
                    val plaintext = TeslaFiraProtocol.response(plans, 1_792_360_988L)
                    val expected = plaintext.copyOf()
                    val response = parseSend(fixture.conversation.sendFira(plaintext))
                    assertTrue(plaintext.all { it == 0.toByte() })
                    assertTrue(response.signatureData.hasAESGCMPersonalizedData())
                    assertNotEquals(0, response.flags and (1 shl UniversalMessage.Flags.FLAG_ENCRYPT_RESPONSE_VALUE))
                    assertFalse(expected.contentEquals(response.protobufMessageAsBytes.toByteArray()))
                    val decrypted = fixture.decryptRequestPayload(response)
                    assertArrayEquals(expected, decrypted)
                    expected.fill(0); decrypted.fill(0)
                    assertWaiting(TeslaBlePhase.READING_STATUS, null,
                        fixture.conversation.receive(fixture.helloAck(response).toByteArray()))
                    val stop = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.stopped(listOf(0, 11))))
                    assertTrue(stop.signatureData.hasAESGCMPersonalizedData())
                    assertTrue(stop.signatureData.getAESGCMPersonalizedData().counter >
                        response.signatureData.getAESGCMPersonalizedData().counter)
                    assertNotEquals(response.uuid, stop.uuid)
                }
            }
        }
    }

    @Test fun firaPlaintextRouteIsNegotiationOnlyAndEncryptedInputStillRequiresResponseAead() {
        for (wrapped in listOf(false, true)) {
            VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
                val hello = fixture.start()
                assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
                val raw = firaTestRequest(listOf(FiraTestSession(1)))
                val input = if (wrapped) fixture.baseResponse(hello)
                    .setProtobufMessageAsBytes(ByteString.copyFrom(raw)).build().toByteArray() else raw
                (fixture.conversation.receive(input) as TeslaBleStep.UwbInput).message.close()
                val wrongDomain = fixture.baseResponse(hello)
                    .setFromDestination(UniversalMessage.Destination.newBuilder()
                        .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT))
                    .setProtobufMessageAsBytes(ByteString.copyFrom(raw)).build()
                assertWaiting(TeslaBlePhase.READING_STATUS, null,
                    fixture.conversation.receive(wrongDomain.toByteArray()))
                val cap = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
                val encrypted = fixture.encryptResponse(cap, Vcsec.FromVCSECMessage.parseFrom(raw), 8)
                (fixture.conversation.receive(encrypted.toByteArray()) as TeslaBleStep.UwbInput).message.close()
                val tampered = encrypted.toBuilder().setSignatureData(encrypted.signatureData.toBuilder()
                    .setAESGCMResponseData(encrypted.signatureData.getAESGCMResponseData().toBuilder()
                        .setCounter(9).setTag(ByteString.copyFrom(ByteArray(16))))).build()
                assertFailed(fixture.conversation.receive(tampered.toByteArray()))
            }
        }
    }

    @Test fun firaFreshRequestsMayReuseResponseCounterButSameRequestReplayFails() {
        VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = fixture.start()
            assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            val payload = Vcsec.FromVCSECMessage.parseFrom(firaTestRequest(listOf(FiraTestSession(1))))
            val capabilities = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
            val first = fixture.encryptResponse(capabilities, payload, counter = 8)
            (fixture.conversation.receive(first.toByteArray()) as TeslaBleStep.UwbInput).message.close()

            val stopped = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.stopped(listOf(1))))
            assertNotEquals(capabilities.uuid, stopped.uuid)
            assertNotEquals(capabilities.fromDestination, stopped.fromDestination)
            val second = fixture.encryptResponse(stopped, payload, counter = 8)
            (fixture.conversation.receive(second.toByteArray()) as TeslaBleStep.UwbInput).message.close()
            assertFailed(fixture.conversation.receive(second.toByteArray()))
            val afterReplay = TeslaFiraProtocol.stopped(listOf(1))
            assertFailed(fixture.conversation.sendFira(afterReplay))
            assertTrue(afterReplay.all { it == 0.toByte() })
        }
    }

    @Test fun firaOldResponseCannotCrossFreshRequestEvenWithRewrittenCorrelation() {
        VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = fixture.start()
            assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            val capabilities = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
            val payload = Vcsec.FromVCSECMessage.parseFrom(firaTestRequest(listOf(FiraTestSession(1))))
            val first = fixture.encryptResponse(capabilities, payload, counter = 8)
            (fixture.conversation.receive(first.toByteArray()) as TeslaBleStep.UwbInput).message.close()

            val stopped = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.stopped(listOf(1))))
            assertWaiting(TeslaBlePhase.READING_STATUS, null, fixture.conversation.receive(first.toByteArray()))
            val rerouted = first.toBuilder().setToDestination(stopped.fromDestination)
                .setRequestUuid(stopped.uuid).build()
            assertFailed(fixture.conversation.receive(rerouted.toByteArray()))
        }
    }

    @Test fun firaCloseAndExpiredSessionCannotEncryptFurtherSts() {
        for (close in listOf(false, true)) {
            VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
                val hello = fixture.start()
                assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
                if (close) fixture.conversation.close() else fixture.clock += 180_000L
                val sensitive = byteArrayOf(22, 33, 44)
                assertFailed(fixture.conversation.sendFira(sensitive))
                assertTrue(sensitive.all { it == 0.toByte() })
            }
        }
    }

    @Test fun v62FiraObservationMatchesRawWrappedAndEncryptedConsumers() {
        val bodies = listOf(
            firaTestRequest(listOf(FiraTestSession(1, mode = 2, vehicleSts = ByteArray(16) { 7 }))) to
                TeslaBleRxFiraKind.REQUEST,
            firaTestMessage { writeByteArray(48, firaTestMessage { writeEnum(1, 3) }) } to TeslaBleRxFiraKind.STOP,
            firaTestMessage { writeUInt32(61, 7) } to TeslaBleRxFiraKind.UNRECOGNIZED,
            firaTestMessage { writeByteArray(54, byteArrayOf(0x80.toByte())) } to TeslaBleRxFiraKind.INVALID,
        )
        for (inputKind in listOf(TeslaBleRxInputKind.RAW_FIRA,
            TeslaBleRxInputKind.WRAPPED_PLAINTEXT, TeslaBleRxInputKind.ENCRYPTED_RESPONSE)
        ) {
            for ((body, firaKind) in bodies) {
                VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
                    val hello = fixture.start()
                    assertEquals(TeslaBleStep.UwbReady,
                        fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
                    val capabilities = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
                    val wire = when (inputKind) {
                        TeslaBleRxInputKind.RAW_FIRA -> body
                        TeslaBleRxInputKind.WRAPPED_PLAINTEXT -> fixture.baseResponse(capabilities)
                            .setProtobufMessageAsBytes(ByteString.copyFrom(body)).build().toByteArray()
                        TeslaBleRxInputKind.ENCRYPTED_RESPONSE -> fixture.encryptResponse(capabilities,
                            Vcsec.FromVCSECMessage.parseFrom(body), 8).toByteArray()
                        else -> error("not a FiRa transport")
                    }
                    val step = fixture.conversation.receive(wire)
                    val expectedStep = when (firaKind) {
                        TeslaBleRxFiraKind.REQUEST -> {
                            val input = step as TeslaBleStep.UwbInput
                            (input.message as TeslaFiraMessage.Request).use { request ->
                                assertEquals(1L, request.sessions.single().requestSessionId)
                                assertTrue(request.sessions.single().vehicleSts.any { it != 0.toByte() })
                            }
                            assertTrue((input.message as TeslaFiraMessage.Request).sessions.single().vehicleSts
                                .all { it == 0.toByte() })
                            TeslaBleRxStep.UWB_INPUT
                        }
                        TeslaBleRxFiraKind.STOP -> {
                            (step as TeslaBleStep.UwbInput).message.use { stop ->
                                assertEquals(listOf(3), (stop as TeslaFiraMessage.Stop).endpoints)
                            }
                            TeslaBleRxStep.UWB_INPUT
                        }
                        TeslaBleRxFiraKind.UNRECOGNIZED -> {
                            assertWaiting(TeslaBlePhase.READING_STATUS, null, step)
                            TeslaBleRxStep.WAITING
                        }
                        TeslaBleRxFiraKind.INVALID -> {
                            assertFailed(step)
                            val pending = TeslaFiraProtocol.capabilities()
                            assertFailed(fixture.conversation.sendFira(pending))
                            assertTrue(pending.all { it == 0.toByte() })
                            TeslaBleRxStep.FAILED
                        }
                    }
                    val observed = fixture.responseObservations.last()
                    assertRx(observed, expectedStep, inputKind,
                        if (inputKind == TeslaBleRxInputKind.ENCRYPTED_RESPONSE) TeslaBleRxAead.VERIFIED
                        else TeslaBleRxAead.NOT_ATTEMPTED, firaKind, "FIRA_${firaKind.name}")
                    assertNull(observed.authEvidence)
                    if (inputKind == TeslaBleRxInputKind.RAW_FIRA) {
                        assertNoEnvelope(observed)
                    } else {
                        assertEquals(true, observed.fromPresent)
                        assertEquals(2, observed.fromDomain)
                        assertEquals("PROTOBUF_MESSAGE_AS_BYTES", observed.payloadKind)
                        assertHeaderCorrelation(observed, true, true, true)
                    }
                }
            }
        }
        bodies.forEach { it.first.fill(0) }
    }

    @Test fun v62WrappedFiraReportsOnlyExistingDomainAndPayloadGates() {
        VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = fixture.start()
            assertEquals(TeslaBleStep.UwbReady,
                fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            val raw = firaTestRequest(listOf(FiraTestSession(1)))
            val valid = fixture.baseResponse(hello).setProtobufMessageAsBytes(ByteString.copyFrom(raw)).build()
            val exclusions = listOf(
                valid.toBuilder().clearFromDestination().build() to "FROM_MISSING",
                valid.toBuilder().setFromDestination(UniversalMessage.Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(byteArrayOf(1)))).build() to "FROM_NOT_DOMAIN",
                valid.toBuilder().setFromDestination(UniversalMessage.Destination.newBuilder()
                    .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)).build() to "DOMAIN_DIFFERENT",
                valid.toBuilder().setFromDestination(UniversalMessage.Destination.newBuilder()
                    .setDomainValue(123)).build() to "DOMAIN_DIFFERENT",
            )
            for ((excluded, reason) in exclusions) {
                assertWaiting(TeslaBlePhase.READING_STATUS, null,
                    fixture.conversation.receive(excluded.toByteArray()))
                val observed = fixture.responseObservations.last()
                assertRx(observed, TeslaBleRxStep.WAITING, TeslaBleRxInputKind.WRAPPED_PLAINTEXT,
                    TeslaBleRxAead.NOT_ATTEMPTED, null, "DOMAIN_MISMATCH")
                assertEquals(reason, observed.domainMismatchReason)
                assertEquals(excluded.hasFromDestination(), observed.fromPresent)
                assertHeaderCorrelation(observed, true, true, true)
            }
            assertWaiting(TeslaBlePhase.READING_STATUS, null,
                fixture.conversation.receive(valid.toBuilder().clearProtobufMessageAsBytes().build().toByteArray()))
            val wrongPayload = fixture.responseObservations.last()
            assertRx(wrongPayload, TeslaBleRxStep.WAITING, TeslaBleRxInputKind.WRAPPED_PLAINTEXT,
                TeslaBleRxAead.NOT_ATTEMPTED, null, "RESPONSE_WAITING")
            assertEquals("UNSET", wrongPayload.payloadKind)
            assertNull(wrongPayload.domainMismatchReason)
            // Plaintext has no route/UUID acceptance gate: report comparisons without inventing authentication.
            val uncorrelated = valid.toBuilder().setRequestUuid(ByteString.copyFrom(byteArrayOf(2)))
                .setToDestination(UniversalMessage.Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(byteArrayOf(3)))).build()
            (fixture.conversation.receive(uncorrelated.toByteArray()) as TeslaBleStep.UwbInput).message.close()
            val observed = fixture.responseObservations.last()
            assertRx(observed, TeslaBleRxStep.UWB_INPUT, TeslaBleRxInputKind.WRAPPED_PLAINTEXT,
                TeslaBleRxAead.NOT_ATTEMPTED, TeslaBleRxFiraKind.REQUEST, "FIRA_REQUEST")
            assertHeaderCorrelation(observed, true, false, false)
            assertNull(observed.authEvidence)
            raw.fill(0)
        }
    }

    @Test fun v62EncryptedFiraCorrelationReplayAndTagKeepIndependentAeadEvidence() {
        VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = fixture.start()
            assertEquals(TeslaBleStep.UwbReady,
                fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            val capabilities = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
            val payload = Vcsec.FromVCSECMessage.parseFrom(firaTestRequest(listOf(FiraTestSession(1))))
            val valid = fixture.encryptResponse(capabilities, payload, 8)
            val exclusions = listOf(
                valid.toBuilder().clearFromDestination().build() to "DOMAIN_MISMATCH",
                valid.toBuilder().setFromDestination(UniversalMessage.Destination.newBuilder()
                    .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)).build() to "DOMAIN_MISMATCH",
                valid.toBuilder().clearToDestination().build() to "ROUTE_MISMATCH",
                valid.toBuilder().setToDestination(UniversalMessage.Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(byteArrayOf(1)))).build() to "ROUTE_MISMATCH",
                valid.toBuilder().setRequestUuid(ByteString.copyFrom(byteArrayOf(2))).build() to "REQUEST_UUID_MISMATCH",
            )
            for ((excluded, disposition) in exclusions) {
                assertWaiting(TeslaBlePhase.READING_STATUS, null,
                    fixture.conversation.receive(excluded.toByteArray()))
                val observed = fixture.responseObservations.last()
                assertRx(observed, TeslaBleRxStep.WAITING, TeslaBleRxInputKind.ENCRYPTED_RESPONSE,
                    TeslaBleRxAead.NOT_ATTEMPTED, null, disposition)
                assertEquals(true, observed.signedStatusPresent)
                if (disposition == "REQUEST_UUID_MISMATCH") assertHeaderCorrelation(observed, true, false, true)
            }
            // The domain2 omitted-UUID exception is unchanged and still must pass AEAD.
            val omittedUuid = valid.toBuilder().clearRequestUuid().build()
            (fixture.conversation.receive(omittedUuid.toByteArray()) as TeslaBleStep.UwbInput).message.close()
            val accepted = fixture.responseObservations.last()
            assertRx(accepted, TeslaBleRxStep.UWB_INPUT, TeslaBleRxInputKind.ENCRYPTED_RESPONSE,
                TeslaBleRxAead.VERIFIED, TeslaBleRxFiraKind.REQUEST, "FIRA_REQUEST")
            assertHeaderCorrelation(accepted, false, null, true)
            assertFailed(fixture.conversation.receive(valid.toByteArray()))
            assertRx(fixture.responseObservations.last(), TeslaBleRxStep.FAILED,
                TeslaBleRxInputKind.ENCRYPTED_RESPONSE, TeslaBleRxAead.NOT_ATTEMPTED, null, "RESPONSE_REJECTED")
        }
        for (invalidTagSize in listOf(false, true)) {
            VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
                val hello = fixture.start()
                assertEquals(TeslaBleStep.UwbReady,
                    fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
                val capabilities = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
                val valid = fixture.encryptResponse(capabilities,
                    Vcsec.FromVCSECMessage.parseFrom(firaTestRequest(listOf(FiraTestSession(1)))), 8)
                val tag = valid.signatureData.getAESGCMResponseData().tag.toByteArray()
                tag[0] = (tag[0].toInt() xor 1).toByte()
                val tampered = valid.toBuilder().setSignatureData(valid.signatureData.toBuilder()
                    .setAESGCMResponseData(valid.signatureData.getAESGCMResponseData().toBuilder()
                        .setTag(ByteString.copyFrom(if (invalidTagSize) tag.copyOf(15) else tag)))).build()
                assertFailed(fixture.conversation.receive(tampered.toByteArray()))
                assertRx(fixture.responseObservations.last(), TeslaBleRxStep.FAILED,
                    TeslaBleRxInputKind.ENCRYPTED_RESPONSE,
                    if (invalidTagSize) TeslaBleRxAead.NOT_ATTEMPTED else TeslaBleRxAead.FAILED,
                    null, "RESPONSE_INVALID")
                tag.fill(0)
            }
        }
        VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = fixture.start()
            assertEquals(TeslaBleStep.UwbReady,
                fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            val capabilities = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
            val rejected = fixture.encryptResponse(capabilities,
                Vcsec.FromVCSECMessage.getDefaultInstance(), 8, fault = 1)
            assertFailed(fixture.conversation.receive(rejected.toByteArray()))
            val observed = fixture.responseObservations.last()
            assertRx(observed, TeslaBleRxStep.FAILED, TeslaBleRxInputKind.ENCRYPTED_RESPONSE,
                TeslaBleRxAead.VERIFIED, null, "RESPONSE_REJECTED")
            assertEquals(1, observed.signedMessageFault)
            assertNull(observed.authEvidence)
        }
    }

    @Test fun v62EachReceiveClearsPriorEnvelopeCryptoFiraAndAuthMetadata() {
        VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = fixture.start()
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.ACKNOWLEDGED,
                fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
            assertEquals(TeslaBleAuthEvidence.ACKNOWLEDGED, fixture.responseObservations.last().authEvidence)
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.SIGNATURE_MISSING,
                fixture.conversation.receive(fixture.handshake(hello).toBuilder().clearSignatureData().build().toByteArray()))
            assertRx(fixture.responseObservations.last(), TeslaBleRxStep.WAITING,
                TeslaBleRxInputKind.ROUTABLE_MESSAGE, TeslaBleRxAead.NOT_ATTEMPTED, null, "SESSION_INFO_AUTH_REJECTED")
            assertEquals(TeslaBleAuthEvidence.SIGNATURE_MISSING, fixture.responseObservations.last().authEvidence)
            assertEquals(TeslaBleStep.UwbReady,
                fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            assertRx(fixture.responseObservations.last(), TeslaBleRxStep.UWB_READY,
                TeslaBleRxInputKind.ROUTABLE_MESSAGE, TeslaBleRxAead.NOT_ATTEMPTED, null, "SESSION_READY")
            assertNull(fixture.responseObservations.last().authEvidence)
            val capabilities = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
            val request = firaTestRequest(listOf(FiraTestSession(1)))
            (fixture.conversation.receive(fixture.encryptResponse(capabilities,
                Vcsec.FromVCSECMessage.parseFrom(request), 8).toByteArray()) as TeslaBleStep.UwbInput).message.close()
            assertEquals(TeslaBleRxAead.VERIFIED, fixture.responseObservations.last().aead)
            val stop = firaTestMessage { writeByteArray(48, firaTestMessage { writeEnum(1, 3) }) }
            (fixture.conversation.receive(stop) as TeslaBleStep.UwbInput).message.close()
            val raw = fixture.responseObservations.last()
            assertRx(raw, TeslaBleRxStep.UWB_INPUT, TeslaBleRxInputKind.RAW_FIRA,
                TeslaBleRxAead.NOT_ATTEMPTED, TeslaBleRxFiraKind.STOP, "FIRA_STOP")
            assertNoEnvelope(raw)
            assertNull(raw.authEvidence)
            assertWaiting(TeslaBlePhase.READING_STATUS, null,
                fixture.conversation.receive(firaTestMessage { writeUInt32(61, 7) }))
            val unknown = fixture.responseObservations.last()
            assertRx(unknown, TeslaBleRxStep.WAITING, TeslaBleRxInputKind.RAW_FIRA,
                TeslaBleRxAead.NOT_ATTEMPTED, TeslaBleRxFiraKind.UNRECOGNIZED, "FIRA_UNRECOGNIZED")
            assertNoEnvelope(unknown)
            assertFailed(fixture.conversation.receive(byteArrayOf(0x80.toByte())))
            assertEquals(TeslaBleResponseObservation("RESPONSE_INVALID", 2,
                step = TeslaBleRxStep.FAILED, aead = TeslaBleRxAead.NOT_ATTEMPTED),
                fixture.responseObservations.last())
            // An immutable earlier observation survives subsequent reset and terminal cleanup.
            assertEquals(TeslaBleRxFiraKind.STOP, raw.firaKind)
            assertEquals(TeslaBleRxAead.VERIFIED, fixture.responseObservations
                .first { it.inputKind == TeslaBleRxInputKind.ENCRYPTED_RESPONSE }.aead)
            request.fill(0); stop.fill(0)
        }
    }

    @Test fun v62ObserverReentrantCancellationCannotDeliverAProducedFiraStep() {
        for (encrypted in listOf(false, true)) {
            VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
                val hello = fixture.start()
                assertEquals(TeslaBleStep.UwbReady,
                    fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
                val capabilities = parseSend(fixture.conversation.sendFira(TeslaFiraProtocol.capabilities()))
                val request = firaTestRequest(listOf(FiraTestSession(1)))
                val input = if (encrypted) fixture.encryptResponse(capabilities,
                    Vcsec.FromVCSECMessage.parseFrom(request), 8).toByteArray() else request
                val cancellation = IllegalStateException("observer cancellation")
                var outer: TeslaBleResponseObservation? = null
                fixture.responseObserver = { observed ->
                    fixture.responseObserver = null
                    outer = observed
                    assertFailed(fixture.conversation.receive(byteArrayOf(0x80.toByte())))
                    fixture.conversation.close()
                    throw cancellation
                }
                var delivered: TeslaBleStep? = null
                val thrown = assertThrows(IllegalStateException::class.java) {
                    delivered = fixture.conversation.receive(input)
                }
                assertSame(cancellation, thrown)
                assertNull(delivered)
                assertRx(checkNotNull(outer), TeslaBleRxStep.UWB_INPUT,
                    if (encrypted) TeslaBleRxInputKind.ENCRYPTED_RESPONSE else TeslaBleRxInputKind.RAW_FIRA,
                    if (encrypted) TeslaBleRxAead.VERIFIED else TeslaBleRxAead.NOT_ATTEMPTED,
                    TeslaBleRxFiraKind.REQUEST, "FIRA_REQUEST")
                val pending = TeslaFiraProtocol.capabilities()
                assertFailed(fixture.conversation.sendFira(pending))
                assertTrue(pending.all { it == 0.toByte() })
                assertFailed(fixture.conversation.receive(request))
                assertNull(fixture.responseObservations.last().inputKind)
                assertNull(fixture.responseObservations.last().firaKind)
                request.fill(0); input.fill(0)
            }
        }
    }

    @Test fun v62ActualReadStepsSeparateHmacEvidenceFromResponseAead() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.ACKNOWLEDGED,
                fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
            assertRx(fixture.responseObservations.last(), TeslaBleRxStep.WAITING,
                TeslaBleRxInputKind.ROUTABLE_MESSAGE, TeslaBleRxAead.NOT_ATTEMPTED, null, "ACK_WAITING")
            assertEquals(TeslaBleAuthEvidence.ACKNOWLEDGED, fixture.responseObservations.last().authEvidence)
            val request = parseSend(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            assertEquals(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS,
                fixture.decryptRequest(request).informationRequest.informationRequestType)
            assertRx(fixture.responseObservations.last(), TeslaBleRxStep.SEND,
                TeslaBleRxInputKind.ROUTABLE_MESSAGE, TeslaBleRxAead.NOT_ATTEMPTED, null, "SESSION_READY")
            assertNull(fixture.responseObservations.last().authEvidence)
            val completed = fixture.conversation.receive(fixture.status(request).toByteArray()) as TeslaBleStep.Complete
            assertEquals(TeslaReadOnlyStatus(1, 0, bodyVehicleSleepStatus = 0), completed.status)
            assertRx(fixture.responseObservations.last(), TeslaBleRxStep.COMPLETE,
                TeslaBleRxInputKind.ENCRYPTED_RESPONSE, TeslaBleRxAead.VERIFIED, null, "STATUS_VERIFIED")
            assertNull(fixture.responseObservations.last().authEvidence)
        }
        VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val hello = fixture.start()
            val fault = UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID_VALUE
            assertEvidence(TeslaBleAuthEvidence.KEY_NOT_PAIRED_REPORTED,
                fixture.conversation.receive(fixture.helloAck(hello, fault = fault).toByteArray()))
            val observed = fixture.responseObservations.last()
            assertRx(observed, TeslaBleRxStep.FAILED, TeslaBleRxInputKind.ROUTABLE_MESSAGE,
                TeslaBleRxAead.NOT_ATTEMPTED, null, "RESPONSE_REJECTED")
            assertEquals(TeslaBleAuthEvidence.KEY_NOT_PAIRED_REPORTED, observed.authEvidence)
            assertEquals(fault, observed.signedMessageFault)
            val pending = TeslaFiraProtocol.capabilities()
            assertFailed(fixture.conversation.sendFira(pending))
            assertTrue(pending.all { it == 0.toByte() })
        }
    }

    @Test fun frunkUsesFreshDriveThenPublicClosureContractAndSeparateFreshBody() {
        FrunkFixture().use { h ->
            val action = h.action()
            assertEquals(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY, action.toDestination.domain)
            val payload = h.wire.decryptRequest(action)
            assertEquals(Vcsec.UnsignedMessage.SubMessageCase.CLOSUREMOVEREQUEST, payload.subMessageCase)
            assertEquals(Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE, payload.closureMoveRequest.frontTrunk)
            assertEquals(0, payload.closureMoveRequest.rearTrunkValue)
            h.completeAction(action)
            assertEquals(TeslaFrunkResult.UNKNOWN, h.policy.result)
            val bodyHello = parseSend(h.receive(h.wire.encryptResponse(action,
                Vcsec.FromVCSECMessage.getDefaultInstance(), 8)))
            assertTrue(bodyHello.hasSessionInfoRequest())
            assertNotEquals(action.uuid, bodyHello.uuid)
            assertNotEquals(action.fromDestination.routingAddress, bodyHello.fromDestination.routingAddress)
            assertTrue(h.policy.commandAcknowledged)
            assertFalse(h.policy.openObserved)
            val bodyRequest = parseSend(h.receive(h.wire.handshake(bodyHello)))
            assertEquals(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS,
                h.wire.decryptRequest(bodyRequest).informationRequest.informationRequestType)
            // 명령 응답의 OPEN을 후속 BODY 응답으로 재사용하지 않는다.
            assertWaiting(TeslaBlePhase.READING_STATUS, null, h.receive(h.wire.status(action, frontTrunk = 1)))
            assertFalse(h.policy.openObserved)
            val complete = h.receive(h.wire.status(bodyRequest, frontTrunk = 1)) as TeslaBleStep.Complete
            assertEquals(1, complete.status.frontTrunkState)
            assertTrue(h.policy.openObserved)
            assertEquals(TeslaFrunkResult.OPEN_OBSERVED, h.policy.result)
            assertFailed(h.sequence.start())
            assertNotNull(h.policy.beginWrite(0, action.serializedSize + 2))
        }
    }

    @Test fun frunkNeverPromotesSdkOrUnsignedAckOrUnknownBodyToOpenSuccess() {
        for (authenticatedAck in listOf(false, true)) {
            for (closure in listOf(null, 0, 1, 2, 3, 5, 99)) {
                FrunkFixture().use { h ->
                    val action = h.action()
                    h.completeAction(action)
                    val response = if (authenticatedAck) h.wire.encryptResponse(action,
                        Vcsec.FromVCSECMessage.getDefaultInstance(), 8) else h.wire.helloAck(action)
                    val bodyHello = parseSend(h.receive(response))
                    assertEquals(authenticatedAck, h.policy.commandAcknowledged)
                    assertFalse(h.policy.openObserved)
                    val bodyRequest = parseSend(h.receive(h.wire.handshake(bodyHello)))
                    val complete = h.receive(h.wire.status(bodyRequest, frontTrunk = closure)) as TeslaBleStep.Complete
                    assertEquals(closure?.takeIf { it in 0..6 }, complete.status.frontTrunkState)
                    assertEquals(closure == 1, h.policy.openObserved)
                    assertEquals(if (authenticatedAck && closure == 1) TeslaFrunkResult.OPEN_OBSERVED
                        else TeslaFrunkResult.UNKNOWN, h.policy.result)
                }
            }
        }
    }

    @Test fun frunkRequiresCurrentExplicitDrivePAndOriginalSourceTime() {
        for (drive in listOf<(FrunkFixture) -> Vehicle.DriveState>(
            { it.wire.driveState(timestamp = null) },
            { it.wire.driveState(shift(TeslaGear.UNKNOWN), it.sourceTime()) },
            { it.wire.driveState(shift(TeslaGear.D), it.sourceTime()) },
            { it.wire.driveState(timestamp = timestamp((it.wall - 10_001) / 1_000, 0)) },
            { it.wire.driveState(timestamp = timestamp((it.wall + 3_000) / 1_000, 0)) },
        )) {
            FrunkFixture().use { h ->
                val request = h.driveRequest()
                assertFailed(h.receive(h.wire.driveResponse(request, drive(h))))
                assertFalse(h.policy.driveVerified)
                assertFalse(h.policy.mayHaveSent)
            }
        }
    }

    @Test fun frunkRechecksParkBeforeEncryptingActionAndCapsWireExpiryToOriginalConsent() {
        FrunkFixture().use { h ->
            val request = h.driveRequest()
            val actionHello = parseSend(h.receive(h.wire.driveResponse(request,
                h.wire.driveState(timestamp = h.sourceTime()))))
            h.wire.clock += 5_001
            assertFailed(h.receive(h.wire.handshake(actionHello)))
            assertFalse(h.policy.commandSent)
        }
        FrunkFixture().use { h ->
            h.wire.clock += 37_000
            val action = h.action()
            val ttl = action.signatureData.getAESGCMPersonalizedData().expiresAt.toLong() - 100L
            assertTrue(ttl > 0)
            assertTrue(ttl * 1_000 <= h.policy.deadline - h.wire.clock)
        }
    }

    @Test fun frunkCommandStatusOkAndWaitAreNotTerminalAckAndErrorIsExplicitRejection() {
        FrunkFixture().use { h ->
            val action = h.action()
            h.completeAction(action)
            for ((index, operation) in listOf(Vcsec.OperationStatus_E.OPERATIONSTATUS_OK,
                Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT).withIndex()) {
                assertWaiting(TeslaBlePhase.READING_STATUS, null, h.receive(h.wire.encryptResponse(action,
                    genericRegistrationStatus(operation), 8 + index)))
                assertFalse(h.policy.commandAcknowledged)
                assertFalse(h.policy.openObserved)
            }
            assertFailed(h.receive(h.wire.encryptResponse(action,
                genericRegistrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR), 10)))
            assertTrue(h.policy.commandRejected)
            assertEquals(TeslaFrunkResult.REJECTED, h.policy.result)
            assertNotNull(h.policy.beginWrite(0, 100))
        }
    }

    @Test fun frunkWrongCorrelationCannotAcknowledgeAndInvalidAeadOnlyStartsReadObservation() {
        for (invalidCase in 0..3) {
            FrunkFixture().use { h ->
                val action = h.action()
                h.completeAction(action)
                val ack = h.wire.encryptResponse(action, Vcsec.FromVCSECMessage.getDefaultInstance(), 8)
                val invalid = ack.toBuilder().apply {
                    when (invalidCase) {
                        0 -> setFromDestination(UniversalMessage.Destination.newBuilder()
                            .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT))
                        1 -> setToDestination(UniversalMessage.Destination.newBuilder()
                            .setRoutingAddress(ByteString.copyFrom(ByteArray(16))))
                        2 -> setRequestUuid(ByteString.copyFrom(ByteArray(16)))
                        3 -> setSignatureData(ack.signatureData.toBuilder().setAESGCMResponseData(
                            ack.signatureData.getAESGCMResponseData().toBuilder().setTag(ByteString.copyFrom(ByteArray(16)))))
                    }
                }.build()
                val step = h.receive(invalid)
                if (invalidCase == 3) {
                    assertTrue(parseSend(step).hasSessionInfoRequest())
                    assertTrue(h.sequence.responseUnknown)
                } else assertWaiting(TeslaBlePhase.READING_STATUS, null, step)
                assertFalse(h.policy.commandAcknowledged)
                assertFalse(h.policy.openObserved)
                assertEquals(TeslaFrunkResult.UNKNOWN, h.policy.result)
                assertNotNull(h.policy.beginWrite(0, action.serializedSize + 2))
            }
        }
    }

    @Test fun frunkMissingActionReplyOnlyStartsOneFreshReadAndCancellationPreventsIt() {
        FrunkFixture().use { h ->
            val action = h.action()
            h.completeAction(action)
            h.wire.clock += 10_000
            val bodyHello = parseSend(h.sequence.observeAfterMissingReply())
            assertTrue(bodyHello.hasSessionInfoRequest())
            assertFalse(h.policy.commandAcknowledged)
            assertFailed(h.sequence.observeAfterMissingReply())
            val body = parseSend(h.receive(h.wire.handshake(bodyHello)))
            assertTrue(h.receive(h.wire.status(body, frontTrunk = 1)) is TeslaBleStep.Complete)
            assertTrue(h.policy.openObserved)
            assertEquals(TeslaFrunkResult.UNKNOWN, h.policy.result)
        }
        FrunkFixture().use { h ->
            val action = h.action()
            h.completeAction(action)
            h.policy.cancel()
            assertFailed(h.sequence.observeAfterMissingReply())
            assertFailed(h.receive(h.wire.encryptResponse(action, Vcsec.FromVCSECMessage.getDefaultInstance(), 8)))
            assertEquals(TeslaFrunkResult.UNKNOWN, h.policy.result)
        }
    }

    @Test fun bodySleepRequiresAuthenticatedCorrelatedBodyAndPreservesFutureCodes() {
        for (code in listOf(0, 1, 2, 99, Int.MAX_VALUE)) {
            for (invalid in 0..4) {
                VehicleFixture().use { fixture ->
                    val request = fixture.authenticate()
                    val body = Vcsec.FromVCSECMessage.newBuilder().setVehicleStatus(
                        Vcsec.VehicleStatus.newBuilder().setVehicleSleepStatusValue(code)).build()
                    val response = fixture.encryptResponse(request, body, 8)
                    val rejected = response.toBuilder()
                    when (invalid) {
                        0 -> rejected.clearSignatureData()
                        1 -> rejected.setFromDestination(UniversalMessage.Destination.newBuilder()
                            .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT))
                        2 -> rejected.setToDestination(UniversalMessage.Destination.newBuilder()
                            .setRoutingAddress(ByteString.copyFrom(ByteArray(16))))
                        3 -> rejected.setRequestUuid(ByteString.copyFrom(ByteArray(16)))
                        4 -> rejected.setProtobufMessageAsBytes(ByteString.copyFrom(byteArrayOf(1)))
                    }
                    assertFalse(fixture.conversation.receive(rejected.build().toByteArray()) is TeslaBleStep.Complete)
                }
            }
            VehicleFixture().use { fixture ->
                val request = fixture.authenticate()
                val body = Vcsec.FromVCSECMessage.newBuilder().setVehicleStatus(
                    Vcsec.VehicleStatus.newBuilder().setVehicleSleepStatusValue(code)).build()
                val result = fixture.conversation.receive(fixture.encryptResponse(request, body, 8).toByteArray())
                    as TeslaBleStep.Complete
                assertEquals(code, result.status.bodyVehicleSleepStatus)
            }
        }
    }


    @Test fun encryptedBodyAfterAckStillNeedsAeadEvenWhenSignedStatusIsAbsent() {
        for (tampered in listOf(false, true)) {
            VehicleFixture().use { fixture ->
                val request = fixture.authenticate()
                assertWaiting(TeslaBlePhase.READING_STATUS, null,
                    fixture.conversation.receive(fixture.helloAck(request).toByteArray()))
                val response = fixture.status(request).toBuilder().clearSignedMessageStatus().apply {
                    if (tampered) setFlags(2) // AAD 변경은 status 부재로 면제되지 않는다.
                }.build()
                val result = fixture.conversation.receive(response.toByteArray())
                if (tampered) assertFailed(result) else {
                    assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0, bodyVehicleSleepStatus = 0)), result)
                }
                val observed = fixture.responseObservations.last()
                assertEquals(false, observed.signedStatusPresent)
                assertNull(observed.signedMessageFault)
                assertNull(observed.operationStatus)
            }
        }
    }

    @Test fun nominalBodyAckWaitsWithoutCompletingAndRequiresFollowingEncryptedBody() {
        for (omitUuid in listOf(false, true)) {
            VehicleFixture().use { fixture ->
                val request = fixture.authenticate()
                val ack = fixture.helloAck(request).toBuilder().apply {
                    if (omitUuid) clearRequestUuid()
                }.build()
                repeat(2) {
                    fixture.clock += 100
                    assertWaiting(TeslaBlePhase.READING_STATUS, null,
                        fixture.conversation.receive(ack.toByteArray()))
                    val observed = fixture.responseObservations.last()
                    assertEquals("ACK_WAITING", observed.disposition)
                    assertEquals(true, observed.signedStatusPresent)
                    assertEquals(0, observed.signedMessageFault)
                    assertEquals(0, observed.operationStatus)
                    assertHeaderCorrelation(observed, !omitUuid, if (omitUuid) null else true, true)
                }
                // ACK는 request hash·counter·identity를 바꾸지 않는다.
                assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0, bodyVehicleSleepStatus = 0)),
                    fixture.conversation.receive(fixture.status(request).toByteArray()))
                assertEquals("STATUS_VERIFIED", fixture.responseObservations.last().disposition)
            }
        }
    }

    @Test fun bodyErrorHeadersAreRejectedWithoutClaimingAuthenticatedFailure() {
        val faults = listOf(
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID_VALUE,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES_VALUE,
            777, -1,
        )
        val operations = listOf(
            UniversalMessage.OperationStatus_E.OPERATIONSTATUS_WAIT_VALUE,
            UniversalMessage.OperationStatus_E.OPERATIONSTATUS_ERROR_VALUE,
            777, -1,
        )
        for ((fault, operation) in faults.map { it to 0 } + operations.map { 0 to it }) {
            // payload 유무와 관계없이 오류 header는 BODY 완료·정상 ACK가 아니다.
            for (encrypted in listOf(false, true)) {
                VehicleFixture().use { fixture ->
                    val request = fixture.authenticate()
                    val response = if (encrypted) {
                        fixture.encryptResponse(request, fixture.vehicleStatus(1, 0), 8, fault = fault)
                            .toBuilder().setSignedMessageStatus(UniversalMessage.MessageStatus.newBuilder()
                                .setSignedMessageFaultValue(fault).setOperationStatusValue(operation)).build()
                    } else fixture.helloAck(request, operation, fault)
                    val failure = fixture.conversation.receive(response.toByteArray())
                    assertFailed(failure)
                    assertNull((failure as TeslaBleStep.Failed).authEvidence)
                    val observed = fixture.responseObservations.last()
                    assertEquals("RESPONSE_REJECTED", observed.disposition)
                    assertEquals(true, observed.signedStatusPresent)
                    assertEquals(fault, observed.signedMessageFault)
                    assertEquals(operation, observed.operationStatus)
                    assertFailed(fixture.conversation.receive(fixture.status(request).toByteArray()))
                }
            }
        }
    }

    @Test fun bodyAckCannotBypassDomainRouteOrUuidMatching() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val ack = fixture.helloAck(request)
            val wrongDomain = ack.toBuilder().setFromDestination(UniversalMessage.Destination.newBuilder()
                .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT))
                .setToDestination(UniversalMessage.Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(ByteArray(16))))
                .setRequestUuid(ByteString.copyFrom(ByteArray(16))).build()
            val wrongRoute = ack.toBuilder().setToDestination(wrongDomain.toDestination)
                .setRequestUuid(wrongDomain.requestUuid).build()
            val wrongUuid = ack.toBuilder().setRequestUuid(wrongDomain.requestUuid).build()
            for ((invalid, disposition) in listOf(
                wrongDomain to "DOMAIN_MISMATCH", wrongRoute to "ROUTE_MISMATCH",
                wrongUuid to "REQUEST_UUID_MISMATCH",
            )) {
                assertWaiting(TeslaBlePhase.READING_STATUS, null,
                    fixture.conversation.receive(invalid.toByteArray()))
                assertEquals(disposition, fixture.responseObservations.last().disposition)
            }
            assertWaiting(TeslaBlePhase.READING_STATUS, null,
                fixture.conversation.receive(ack.toByteArray()))
            assertEquals("ACK_WAITING", fixture.responseObservations.last().disposition)
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0, bodyVehicleSleepStatus = 0)),
                fixture.conversation.receive(fixture.status(request).toByteArray()))
        }
    }

    @Test fun repeatedBodyAcksCannotExtendDeadlineAndInvalidReceiveClearsSignedStatus() {
        for (expired in listOf(false, true)) {
            VehicleFixture().use { fixture ->
                val request = fixture.authenticate()
                fixture.clock += 10_000
                assertWaiting(TeslaBlePhase.READING_STATUS, null,
                    fixture.conversation.receive(fixture.helloAck(request).toByteArray()))
                fixture.clock += 4_999
                assertWaiting(TeslaBlePhase.READING_STATUS, null,
                    fixture.conversation.receive(fixture.helloAck(request).toByteArray()))
                if (expired) fixture.clock++
                assertFailed(fixture.conversation.receive(if (expired) fixture.status(request).toByteArray()
                    else byteArrayOf(0x80.toByte())))
                assertEquals(TeslaBleResponseObservation("RESPONSE_INVALID", 2,
                    step = TeslaBleRxStep.FAILED, aead = TeslaBleRxAead.NOT_ATTEMPTED),
                    fixture.responseObservations.last())
            }
        }
    }

    @Test fun absentStatusWrongPayloadAndInvalidSignatureNeverBecomeBodyAckOrSuccess() {
        for (invalidCase in 0..6) {
            VehicleFixture().use { fixture ->
                val request = fixture.authenticate()
                val ack = fixture.helloAck(request)
                val encrypted = fixture.status(request)
                val invalid = when (invalidCase) {
                    0 -> fixture.baseResponse(request).build()
                    1 -> ack.toBuilder().setSessionInfo(ByteString.EMPTY).build()
                    2 -> ack.toBuilder().setProtobufMessageAsBytes(
                        fixture.vehicleStatus(1, 0).toByteString()).build()
                    3 -> ack.toBuilder().setSignatureData(Signatures.SignatureData.getDefaultInstance()).build()
                    4 -> ack.toBuilder().setSignatureData(encrypted.signatureData).build()
                    5 -> encrypted.toBuilder().setProtobufMessageAsBytes(ByteString.copyFrom(
                        encrypted.protobufMessageAsBytes.toByteArray().apply {
                            this[0] = (this[0].toInt() xor 1).toByte()
                        })).build()
                    else -> encrypted.toBuilder().setSignatureData(encrypted.signatureData.toBuilder()
                        .setAESGCMResponseData(encrypted.signatureData.getAESGCMResponseData().toBuilder()
                            .setNonce(ByteString.copyFrom(ByteArray(11))))).build()
                }
                assertWaiting(TeslaBlePhase.READING_STATUS, null,
                    fixture.conversation.receive(ack.toByteArray()))
                assertFailed(fixture.conversation.receive(invalid.toByteArray()))
                assertNotEquals("ACK_WAITING", fixture.responseObservations.last().disposition)
                if (invalidCase == 0) {
                    assertEquals(false, fixture.responseObservations.last().signedStatusPresent)
                    assertNull(fixture.responseObservations.last().signedMessageFault)
                    assertNull(fixture.responseObservations.last().operationStatus)
                }
            }
        }
    }


    @Test fun bodyAckCannotMakeReplayedPayloadValidInFreshConversation() {
        VehicleFixture().use { fixture ->
            val original = fixture.authenticate()
            val replay = fixture.status(original)
            TeslaBleConversation(fixture.client, fixture.vin) { fixture.clock }.use { replacement ->
                val hello = parseSend(replacement.start(false))
                val current = parseSend(replacement.receive(fixture.handshake(hello).toByteArray()))
                assertWaiting(TeslaBlePhase.READING_STATUS, null,
                    replacement.receive(fixture.helloAck(current).toByteArray()))
                assertFailed(replacement.receive(replay.toBuilder().setToDestination(current.fromDestination)
                    .setRequestUuid(current.uuid).build().toByteArray()))
            }
        }
    }

    @Test fun responseCallbackReentryKeepsOuterObservationAndPropagatesCallbackFailure() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            var outer: TeslaBleResponseObservation? = null
            fixture.responseObserver = { observed ->
                fixture.responseObserver = null
                outer = observed
                assertFailed(fixture.conversation.receive(byteArrayOf(0x80.toByte())))
                assertEquals(TeslaBleResponseObservation("RESPONSE_INVALID", 2,
                    step = TeslaBleRxStep.FAILED, aead = TeslaBleRxAead.NOT_ATTEMPTED),
                    fixture.responseObservations.last())
                assertEquals("ACK_WAITING", observed.disposition)
                assertEquals("UNSET", observed.payloadKind)
                assertHeaderCorrelation(observed, true, true, true)
            }
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.ACKNOWLEDGED,
                fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
            assertEquals("ROUTING_ADDRESS", checkNotNull(outer).toKind)
        }
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val failure = IllegalStateException("callback")
            fixture.responseObserver = { throw failure }
            val thrown = assertThrows(IllegalStateException::class.java) {
                fixture.conversation.receive(fixture.handshake(hello).toByteArray())
            }
            assertSame(failure, thrown)
            assertEquals("SESSION_READY", fixture.responseObservations.last().disposition)
            assertHeaderCorrelation(fixture.responseObservations.last(), true, true, true)
        }
    }

    @Test fun domainMismatchDetailsPreserveUntrustedRawHeaderWithoutAdvancingHandshake() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val hello = fixture.start()
            val session = fixture.handshake(hello)
            val routing = UniversalMessage.Destination.newBuilder()
                .setRoutingAddress(ByteString.copyFrom(byteArrayOf(1))).build()
            val cases = listOf(
                Triple(session.toBuilder().clearFromDestination().build(), "FROM_MISSING", null),
                Triple(session.toBuilder().setFromDestination(routing).build(), "FROM_NOT_DOMAIN", null),
                Triple(session.toBuilder().setFromDestination(UniversalMessage.Destination.newBuilder()
                    .setDomainValue(2)).build(), "DOMAIN_DIFFERENT", 2),
                Triple(session.toBuilder().setFromDestination(UniversalMessage.Destination.newBuilder()
                    .setDomainValue(777)).build(), "DOMAIN_DIFFERENT", 777),
                Triple(session.toBuilder().setFromDestination(UniversalMessage.Destination.newBuilder()
                    .setDomainValue(0)).build(), "DOMAIN_DIFFERENT", 0),
                Triple(session.toBuilder().setFromDestination(UniversalMessage.Destination.getDefaultInstance())
                    .build(), "FROM_NOT_DOMAIN", null),
            )
            for ((response, reason, actual) in cases) {
                assertWaiting(TeslaBlePhase.HANDSHAKING, null,
                    fixture.conversation.receive(response.toByteArray()))
                val observed = fixture.responseObservations.last()
                assertEquals("DOMAIN_MISMATCH", observed.disposition)
                assertEquals(3, observed.expectedDomain)
                assertEquals(reason, observed.domainMismatchReason)
                assertEquals(actual, observed.fromDomain)
                assertEquals(response.hasFromDestination(), observed.fromPresent)
                assertEquals(when (reason) {
                    "FROM_MISSING" -> null
                    "FROM_NOT_DOMAIN" -> if (response.fromDestination == routing) "ROUTING_ADDRESS" else "UNSET"
                    else -> "DOMAIN"
                }, observed.fromKind)
                assertEquals(true, observed.toPresent)
                assertEquals("ROUTING_ADDRESS", observed.toKind)
                assertEquals("SESSION_INFO", observed.payloadKind)
                assertHeaderCorrelation(observed, true, true, true)
            }
            parseSend(fixture.conversation.receive(session.toByteArray()))
            assertEquals("SESSION_READY", fixture.responseObservations.last().disposition)
            assertNull(fixture.responseObservations.last().domainMismatchReason)
            assertHeaderCorrelation(fixture.responseObservations.last(), true, true, true)
        }
    }

    @Test fun domainMismatchCorrelationDistinguishesMissingHeadersFromUnequalHeaders() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val hello = fixture.start()
            val response = fixture.handshake(hello).toBuilder()
                .setFromDestination(UniversalMessage.Destination.newBuilder()
                    .setDomain(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY)).build()
            val differentUuid = hello.uuid.toByteArray().apply {
                this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
            }
            val differentRoute = hello.fromDestination.routingAddress.toByteArray().apply {
                this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
            }
            val cases = listOf(
                response.toBuilder().clearRequestUuid().build() to Triple(false, null, true),
                response.toBuilder().setRequestUuid(ByteString.copyFrom(differentUuid)).build() to
                    Triple(true, false, true),
                response.toBuilder().setRequestUuid(ByteString.copyFrom(byteArrayOf(1))).build() to
                    Triple(true, false, true),
                response.toBuilder().clearToDestination().build() to Triple(true, true, null),
                response.toBuilder().setToDestination(UniversalMessage.Destination.newBuilder()
                    .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)).build() to Triple(true, true, false),
                response.toBuilder().setToDestination(UniversalMessage.Destination.getDefaultInstance())
                    .build() to Triple(true, true, false),
                response.toBuilder().setToDestination(UniversalMessage.Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(differentRoute))).build() to Triple(true, true, false),
                response.toBuilder().setToDestination(UniversalMessage.Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(byteArrayOf(1)))).build() to Triple(true, true, false),
            )
            for ((invalid, expected) in cases) {
                assertWaiting(TeslaBlePhase.HANDSHAKING, null,
                    fixture.conversation.receive(invalid.toByteArray()))
                val observed = fixture.responseObservations.last()
                assertEquals("DOMAIN_MISMATCH", observed.disposition)
                assertHeaderCorrelation(observed, expected.first, expected.second, expected.third)
            }
            parseSend(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            assertHeaderCorrelation(fixture.responseObservations.last(), true, true, true)
        }
    }

    @Test fun registrationHeaderCannotClaimCorrelationBeforeRequestIdentityIsIssued() {
        VehicleFixture().use { fixture ->
            assertTrue(fixture.conversation.start(true) is TeslaBleStep.Send)
            val response = UniversalMessage.RoutableMessage.newBuilder()
                .setFromDestination(UniversalMessage.Destination.newBuilder()
                    .setDomain(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY))
                .setToDestination(UniversalMessage.Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(byteArrayOf(1))))
                .setRequestUuid(ByteString.copyFrom(byteArrayOf(1)))
                .setProtobufMessageAsBytes(Vcsec.FromVCSECMessage.getDefaultInstance().toByteString()).build()
            assertWaiting(TeslaBlePhase.WAITING_FOR_CARD, null,
                fixture.conversation.receive(response.toByteArray()))
            assertFalse(fixture.conversation.registrationReported)
            assertHeaderCorrelation(fixture.responseObservations.last(), true, null, null)
            val hello = parseSend(fixture.conversation.receive(
                registrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_OK, 0).toByteArray()))
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.ACKNOWLEDGED,
                fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
            assertHeaderCorrelation(fixture.responseObservations.last(), true, true, true)
        }
    }

    @Test fun malformedAndExpiredReceiveDoNotReusePreviousHeader() {
        for (expired in listOf(false, true)) {
            VehicleFixture().use { fixture ->
                val hello = fixture.start()
                assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.ACKNOWLEDGED,
                    fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
                assertEquals("UNSET", fixture.responseObservations.last().payloadKind)
                assertHeaderCorrelation(fixture.responseObservations.last(), true, true, true)
                if (expired) fixture.clock += 15_000
                assertFailed(fixture.conversation.receive(
                    if (expired) fixture.handshake(hello).toByteArray() else byteArrayOf(0x80.toByte())))
                assertEquals(TeslaBleResponseObservation("RESPONSE_INVALID", 2,
                    step = TeslaBleRxStep.FAILED, aead = TeslaBleRxAead.NOT_ATTEMPTED),
                    fixture.responseObservations.last())
            }
        }
    }





    @Test fun registrationReportThenInvalidTagDoesNotDiscardFollowingAuthenticatedStatus() {
        VehicleFixture().use { fixture ->
            fixture.conversation.start(true)
            val hello = parseSend(fixture.conversation.receive(
                registrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_OK, 0).toByteArray()))
            assertTrue(fixture.conversation.registrationReported)
            val info = fixture.sessionInfo(SESSION_INFO_OK)
            val tag = fixture.sessionInfoTag(info, hello)
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.TAG_LENGTH_INVALID,
                fixture.conversation.receive(fixture.sessionInfoResponse(hello, info, tag.copyOf(16)).toByteArray()))
            val request = parseSend(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            assertEquals(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS,
                fixture.decryptRequest(request).informationRequest.informationRequestType)
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0, bodyVehicleSleepStatus = 0)),
                fixture.conversation.receive(fixture.status(request).toByteArray()))
        }
    }

    @Test fun authenticHandshakeProducesOnlyEncryptedReadOnlyStatusRequest() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val decoded = fixture.decryptRequest(request)
            assertEquals(Vcsec.UnsignedMessage.SubMessageCase.INFORMATIONREQUEST, decoded.subMessageCase)
            assertEquals(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS,
                decoded.informationRequest.informationRequestType)
            val signature = request.signatureData.getAESGCMPersonalizedData()
            assertTrue((signature.counter.toLong() and 0xffff_ffffL) > 6L)
            assertTrue(signature.expiresAt in 101..130)
            val result = fixture.conversation.receive(fixture.status(request, lock = 1, frontTrunk = 0).toByteArray())
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0, bodyVehicleSleepStatus = 0)), result)
        }
    }

    @Test fun infotainmentHandshakeProducesOnlyEncryptedGetDriveState() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val hello = fixture.start()
            assertEquals(UniversalMessage.Domain.DOMAIN_INFOTAINMENT, hello.toDestination.domain)
            assertTrue(hello.hasSessionInfoRequest())
            val request = parseSend(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            assertEquals(UniversalMessage.Domain.DOMAIN_INFOTAINMENT, request.toDestination.domain)
            assertTrue(request.signatureData.hasAESGCMPersonalizedData())
            assertEquals(1 shl UniversalMessage.Flags.FLAG_ENCRYPT_RESPONSE_VALUE, request.flags)
            assertEquals(CarServer.Action.newBuilder().setVehicleAction(
                CarServer.VehicleAction.newBuilder().setGetVehicleData(
                    CarServer.GetVehicleData.newBuilder().setGetDriveState(CarServer.GetDriveState.getDefaultInstance()),
                ),
            ).build(), fixture.decryptDriveRequest(request))
            fixture.clock += 80
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(driveState =
                TeslaDriveState(TeslaGear.P, null, fixture.clock))),
                fixture.conversation.receive(fixture.driveResponse(request).toByteArray()))
        }
    }

    @Test fun authenticatedDriveGearIsNeverForcedToPark() {
        for (gear in listOf(TeslaGear.P, TeslaGear.R, TeslaGear.N, TeslaGear.D)) {
            VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
                val request = fixture.authenticate()
                val drive = fixture.driveState(shift(gear))
                val complete = fixture.conversation.receive(fixture.driveResponse(request, drive).toByteArray())
                assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(driveState =
                    TeslaDriveState(gear, null, fixture.clock))), complete)
                val status = (complete as TeslaBleStep.Complete).status
                assertNull(status.lockState)
                assertNull(status.frontTrunkState)
            }
        }
    }

    @Test fun missingInvalidSnaAndUnknownShiftNeverBecomePark() {
        val unknown = Vehicle.ShiftState.parseFrom(byteArrayOf(0x3a, 0)) // 미래 oneof field 7
        for (shift in listOf(
            null,
            Vehicle.ShiftState.getDefaultInstance(),
            Vehicle.ShiftState.newBuilder().setInvalid(Common.Void.getDefaultInstance()).build(),
            Vehicle.ShiftState.newBuilder().setSNA(Common.Void.getDefaultInstance()).build(),
            unknown,
        )) {
            VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
                val request = fixture.authenticate()
                assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(driveState =
                    TeslaDriveState(TeslaGear.UNKNOWN, null, fixture.clock))),
                    fixture.conversation.receive(fixture.driveResponse(request, fixture.driveState(shift)).toByteArray()))
            }
        }
    }

    @Test fun driveTimestampsPreserveSourcePresenceAndCanonicalRange() {
        val valid = listOf(
            null to null,
            Timestamp.getDefaultInstance() to 0L,
            timestamp(1_791_356_400L, 987_654_321) to 1_791_356_400_987L,
            timestamp(-1, 500_000_000) to -500L,
            timestamp(-62_135_596_800L, 0) to -62_135_596_800_000L,
            timestamp(253_402_300_799L, 999_999_999) to 253_402_300_799_999L,
        )
        val invalid = listOf(
            timestamp(-62_135_596_801L, 0),
            timestamp(253_402_300_800L, 0),
            timestamp(Long.MIN_VALUE, 0),
            timestamp(Long.MAX_VALUE, 0),
            timestamp(100, -1),
            timestamp(100, 1_000_000_000),
        ).map { it to null }
        for ((source, expected) in valid + invalid) {
            VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
                val request = fixture.authenticate()
                val response = fixture.driveResponse(request, fixture.driveState(timestamp = source))
                val complete = fixture.conversation.receive(response.toByteArray()) as TeslaBleStep.Complete
                assertEquals(expected, complete.status.driveState?.sourceTimestampEpochMillis)
                assertEquals(TeslaGear.P, complete.status.driveState?.gear)
                assertEquals(fixture.clock, complete.status.driveState?.receivedAtElapsedRealtime)
            }
        }
    }

    @Test fun driveReceiptUsesFinalFrameArrivalNotDelayedProcessingClock() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val request = fixture.authenticate()
            val stream = framed(fixture.driveResponse(request).toByteArray())
            val decoder = TeslaBleFrameDecoder()
            assertTrue(decoder.append(stream.copyOfRange(0, stream.size - 1)).isEmpty())
            fixture.clock += 200
            val receivedAt = fixture.clock
            val payload = decoder.append(stream.copyOfRange(stream.size - 1, stream.size)).single()
            fixture.clock += 3_000 // Main 큐/쓰기 완료 뒤 처리
            val complete = fixture.conversation.receive(payload, receivedAt) as TeslaBleStep.Complete
            assertEquals(receivedAt, complete.status.driveState?.receivedAtElapsedRealtime)
            assertNull(complete.status.driveState?.sourceTimestampEpochMillis)
        }
    }

    @Test fun oldReceiptNeverExtendsDriveResponseDeadlineAndImpossibleReceiptFails() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val request = fixture.authenticate()
            val response = fixture.driveResponse(request)
            val receivedAt = fixture.clock + 1
            fixture.clock += 15_000
            assertFailed(fixture.conversation.receive(response.toByteArray(), receivedAt))
        }
        for (receipt in listOf(9_999L, 10_001L)) {
            VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
                val request = fixture.authenticate()
                assertFailed(fixture.conversation.receive(fixture.driveResponse(request).toByteArray(), receipt))
            }
        }
    }

    @Test fun driveQueryCannotRegisterOrReuseBodyStatusAsDriveEvidence() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            assertFailed(fixture.conversation.start(true))
            assertFalse(fixture.conversation.registrationReported)
            assertFailed(fixture.conversation.start(false))
        }
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val request = fixture.authenticate()
            assertFailed(fixture.conversation.receive(fixture.status(request).toByteArray()))
        }
    }

    @Test fun infotainmentRequiresExactUuidForHelloAndEncryptedResponse() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val hello = fixture.start()
            val session = fixture.handshake(hello)
            for (invalid in listOf(
                session.toBuilder().clearRequestUuid().build(),
                session.toBuilder().setRequestUuid(ByteString.copyFrom(ByteArray(16))).build(),
                session.toBuilder().setRequestUuid(ByteString.copyFrom(byteArrayOf(1))).build(),
            )) {
                assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.HANDSHAKING),
                    fixture.conversation.receive(invalid.toByteArray()))
                assertEquals("REQUEST_UUID_MISMATCH", fixture.responseObservations.last().disposition)
                assertHeaderCorrelation(fixture.responseObservations.last(), !invalid.requestUuid.isEmpty,
                    if (invalid.requestUuid.isEmpty) null else false, true)
            }
            val request = parseSend(fixture.conversation.receive(session.toByteArray()))
            assertEquals("SESSION_READY", fixture.responseObservations.last().disposition)
            assertHeaderCorrelation(fixture.responseObservations.last(), true, true, true)
            val authentic = fixture.driveResponse(request)
            for (invalid in listOf(
                authentic.toBuilder().clearRequestUuid().build(),
                authentic.toBuilder().setRequestUuid(ByteString.copyFrom(ByteArray(16))).build(),
                authentic.toBuilder().setRequestUuid(hello.uuid).build(),
            )) {
                assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS),
                    fixture.conversation.receive(invalid.toByteArray()))
                assertEquals("REQUEST_UUID_MISMATCH", fixture.responseObservations.last().disposition)
                assertHeaderCorrelation(fixture.responseObservations.last(), !invalid.requestUuid.isEmpty,
                    if (invalid.requestUuid.isEmpty) null else false, true)
            }
            assertTrue(fixture.conversation.receive(authentic.toByteArray()) is TeslaBleStep.Complete)
            assertEquals("STATUS_VERIFIED", fixture.responseObservations.last().disposition)
            assertHeaderCorrelation(fixture.responseObservations.last(), true, true, true)
        }
    }

    @Test fun foreignDomainOrRouteNeverFeedsInfotainmentHandshakeOrDriveParser() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val hello = fixture.start()
            val session = fixture.handshake(hello)
            val wrongSource = UniversalMessage.Destination.newBuilder()
                .setDomain(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY)
            val wrongRoute = UniversalMessage.Destination.newBuilder()
                .setRoutingAddress(ByteString.copyFrom(ByteArray(16)))
            for ((invalid, disposition) in listOf(
                session.toBuilder().setFromDestination(wrongSource).build() to "DOMAIN_MISMATCH",
                session.toBuilder().setToDestination(wrongRoute).build() to "ROUTE_MISMATCH",
            )) {
                assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.HANDSHAKING),
                    fixture.conversation.receive(invalid.toByteArray()))
                assertEquals(disposition, fixture.responseObservations.last().disposition)
            }
            val request = parseSend(fixture.conversation.receive(session.toByteArray()))
            assertEquals("SESSION_READY", fixture.responseObservations.last().disposition)
            val authentic = fixture.driveResponse(request)
            for ((invalid, disposition) in listOf(
                authentic.toBuilder().setFromDestination(wrongSource).build() to "DOMAIN_MISMATCH",
                authentic.toBuilder().setToDestination(wrongRoute).build() to "ROUTE_MISMATCH",
            )) {
                assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS),
                    fixture.conversation.receive(invalid.toByteArray()))
                assertEquals(disposition, fixture.responseObservations.last().disposition)
            }
            assertTrue(fixture.conversation.receive(authentic.toByteArray()) is TeslaBleStep.Complete)
            assertEquals("STATUS_VERIFIED", fixture.responseObservations.last().disposition)
        }
    }

    @Test fun unauthenticatedInfotainmentSessionCannotSendDriveRequest() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val hello = fixture.start()
            val info = fixture.sessionInfo(SESSION_INFO_OK)
            val tag = fixture.sessionInfoTag(info, hello).apply { this[0] = (this[0].toInt() xor 1).toByte() }
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.HMAC_MISMATCH,
                fixture.conversation.receive(fixture.sessionInfoResponse(hello, info, tag).toByteArray()))
            assertEquals("SESSION_INFO_AUTH_REJECTED", fixture.responseObservations.last().disposition)
            assertHeaderCorrelation(fixture.responseObservations.last(), true, true, true)
            val request = parseSend(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            assertEquals("SESSION_READY", fixture.responseObservations.last().disposition)
            fixture.decryptDriveRequest(request)
        }
    }

    @Test fun infotainmentRejectedSessionNeverExtendsDeadlineAndCounterCannotRollOver() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val hello = fixture.start()
            val info = fixture.sessionInfo(SESSION_INFO_OK)
            val shortTag = fixture.sessionInfoTag(info, hello).copyOf(16)
            fixture.clock += 14_999
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.TAG_LENGTH_INVALID,
                fixture.conversation.receive(fixture.sessionInfoResponse(hello, info, shortTag).toByteArray()))
            fixture.clock++
            assertFailed(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
        }
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val hello = fixture.start()
            assertFailed(fixture.conversation.receive(fixture.handshake(hello, counter = -1).toByteArray()))
        }
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val hello = fixture.start()
            fixture.clock--
            assertFailed(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
        }
    }

    @Test fun malformedDriveNonceOrChangedCounterAndFaultCannotPassAead() {
        for (tamper in 0..2) {
            VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
                val request = fixture.authenticate()
                val response = fixture.driveResponse(request)
                val signature = response.signatureData.getAESGCMResponseData().toBuilder()
                val invalid = when (tamper) {
                    0 -> response.toBuilder().setSignatureData(response.signatureData.toBuilder()
                        .setAESGCMResponseData(signature.setNonce(ByteString.copyFrom(ByteArray(11))))).build()
                    1 -> response.toBuilder().setSignatureData(response.signatureData.toBuilder()
                        .setAESGCMResponseData(signature.setCounter(9))).build()
                    else -> response.toBuilder().setSignedMessageStatus(UniversalMessage.MessageStatus.newBuilder()
                        .setSignedMessageFaultValue(UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES_VALUE)).build()
                }
                assertFailed(fixture.conversation.receive(invalid.toByteArray()))
            }
        }
    }

    @Test fun plaintextOrTamperedDriveResponseNeverCompletes() {
        for (tamper in 0..5) {
            VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
                val request = fixture.authenticate()
                val response = fixture.driveResponse(request)
                val invalid = when (tamper) {
                    0 -> fixture.baseResponse(request).setProtobufMessageAsBytes(fixture.carResponse().toByteString()).build()
                    1 -> response.toBuilder().setProtobufMessageAsBytes(ByteString.copyFrom(
                        response.protobufMessageAsBytes.toByteArray().apply { this[0] = (this[0].toInt() xor 1).toByte() },
                    )).build()
                    2 -> response.toBuilder().setFlags(2).build()
                    3 -> fixture.encryptResponse(request, fixture.carResponse(), 8, requestHash = ByteArray(17))
                    4 -> fixture.encryptResponse(request, fixture.carResponse(), 8, authenticatedDomain = 2)
                    else -> response.toBuilder().setSignatureData(response.signatureData.toBuilder().setAESGCMResponseData(
                        response.signatureData.getAESGCMResponseData().toBuilder().setTag(ByteString.copyFrom(ByteArray(16))),
                    )).build()
                }
                assertFailed(fixture.conversation.receive(invalid.toByteArray()))
                assertHeaderCorrelation(fixture.responseObservations.last(), true, true, true)
            }
        }
    }

    @Test fun driveActionErrorOrUnknownResultNeverLeaksCarReasonOrCompletes() {
        val privateReason = "vehicle-private-error-payload"
        for (result in listOf(1, 99)) {
            VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
                val request = fixture.authenticate()
                val payload = fixture.carResponse(result = result, reason = privateReason)
                val failure = fixture.conversation.receive(fixture.encryptResponse(request, payload, 8).toByteArray())
                assertFailed(failure)
                assertFalse((failure as TeslaBleStep.Failed).reason.contains(privateReason))
            }
        }
    }

    @Test fun omittedActionStatusKeepsOfficialDefaultOkButStillRequiresAuthenticatedDriveData() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val request = fixture.authenticate()
            val payload = fixture.carResponse(fixture.driveState(shift(TeslaGear.R)), result = null)
            assertFalse(payload.hasActionStatus())
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(driveState =
                TeslaDriveState(TeslaGear.R, null, fixture.clock))),
                fixture.conversation.receive(fixture.encryptResponse(request, payload, 8).toByteArray()))
        }
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val request = fixture.authenticate()
            assertFailed(fixture.conversation.receive(fixture.encryptResponse(
                request, fixture.carResponse(drive = null, result = null), 8,
            ).toByteArray()))
        }
    }

    @Test fun missingVehicleDataOrDriveStateIsFailureNotEmptyVerifiedStatus() {
        for (payload in listOf(
            CarServer.Response.newBuilder().setActionStatus(CarServer.ActionStatus.getDefaultInstance()).build(),
            CarServer.Response.newBuilder().setActionStatus(CarServer.ActionStatus.getDefaultInstance())
                .setVehicleData(Vehicle.VehicleData.getDefaultInstance()).build(),
        )) {
            VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
                val request = fixture.authenticate()
                assertFailed(fixture.conversation.receive(fixture.encryptResponse(request, payload, 8).toByteArray()))
            }
        }
    }

    @Test fun authenticatedInfotainmentFaultIsNotDriveSuccess() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val request = fixture.authenticate()
            val fault = fixture.encryptResponse(request, fixture.carResponse(), 8,
                fault = UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES_VALUE)
            assertFailed(fixture.conversation.receive(fault.toByteArray()))
        }
    }

    @Test fun freshInfotainmentHandshakeAndRequestHashRejectReplayWithSharedEcdhKey() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val firstHello = fixture.start()
            val firstSession = fixture.handshake(firstHello)
            val firstRequest = parseSend(fixture.conversation.receive(firstSession.toByteArray()))
            val firstResponse = fixture.driveResponse(firstRequest)
            TeslaBleConversation(fixture.client, fixture.vin, TeslaBleQuery.DRIVE_STATE) { fixture.clock }.use { fresh ->
                val secondHello = parseSend(fresh.start(false))
                assertNotEquals(firstHello.uuid, secondHello.uuid)
                val replaySession = firstSession.toBuilder().setToDestination(secondHello.fromDestination)
                    .setRequestUuid(secondHello.uuid).build()
                assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.HMAC_MISMATCH,
                    fresh.receive(replaySession.toByteArray()))
                val secondRequest = parseSend(fresh.receive(fixture.handshake(secondHello).toByteArray()))
                fixture.decryptDriveRequest(secondRequest)
                val replay = firstResponse.toBuilder().setToDestination(secondRequest.fromDestination)
                    .setRequestUuid(secondRequest.uuid).build()
                assertFailed(fresh.receive(replay.toByteArray()))
            }
        }
    }

    @Test fun driveCompletionIsOneShotAndDoesNotIncludeBodyStatus() {
        VehicleFixture(TeslaBleQuery.DRIVE_STATE).use { fixture ->
            val request = fixture.authenticate()
            val response = fixture.driveResponse(request, fixture.driveState(shift(TeslaGear.R)))
            val complete = fixture.conversation.receive(response.toByteArray()) as TeslaBleStep.Complete
            assertEquals(TeslaGear.R, complete.status.driveState?.gear)
            assertNull(complete.status.lockState)
            assertNull(complete.status.frontTrunkState)
            assertFailed(fixture.conversation.receive(response.toByteArray()))
        }
    }

    @Test fun changedSessionClockWithOriginalHmacCannotAuthorizeStatus() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val authentic = fixture.handshake(hello)
            val info = Signatures.SessionInfo.parseFrom(authentic.sessionInfo).toBuilder().setClockTime(500).build()
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.HMAC_MISMATCH,
                fixture.conversation.receive(authentic.toBuilder().setSessionInfo(info.toByteString()).build().toByteArray()))
            val request = parseSend(fixture.conversation.receive(authentic.toByteArray()))
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0, bodyVehicleSleepStatus = 0)),
                fixture.conversation.receive(fixture.status(request).toByteArray()))
        }
    }

    @Test fun changedSessionHmacCannotAuthorizeStatus() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val response = fixture.handshake(hello)
            val changed = response.signatureData.sessionInfoTag.tag.toByteArray().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            val invalid = response.toBuilder().setSignatureData(response.signatureData.toBuilder()
                .setSessionInfoTag(Signatures.HMAC_Signature_Data.newBuilder().setTag(ByteString.copyFrom(changed)))).build()
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.HMAC_MISMATCH,
                fixture.conversation.receive(invalid.toByteArray()))
        }
    }

    @Test fun replayedHandshakeCannotAuthorizeEvenWhenRouteAndOptionalRequestUuidAreReplaced() {
        VehicleFixture().use { fixture ->
            val firstHello = fixture.start()
            val firstResponse = fixture.handshake(firstHello)
            val replacement = TeslaBleConversation(fixture.client, fixture.vin) { fixture.clock }
            replacement.use {
                val secondHello = parseSend(replacement.start(false))
                val replay = firstResponse.toBuilder().setToDestination(secondHello.fromDestination).clearRequestUuid().build()
                assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.HMAC_MISMATCH,
                    replacement.receive(replay.toByteArray()))
            }
        }
    }

    @Test fun vcsecMayOmitRequestUuidButSessionChallengeStillMustVerify() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val request = parseSend(fixture.conversation.receive(fixture.handshake(hello).toBuilder().clearRequestUuid().build().toByteArray()))
            assertHeaderCorrelation(fixture.responseObservations.last(), false, null, true)
            val response = fixture.status(request).toBuilder().clearRequestUuid().build()
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0, bodyVehicleSleepStatus = 0)), fixture.conversation.receive(response.toByteArray()))
            assertHeaderCorrelation(fixture.responseObservations.last(), false, null, true)
        }
    }

    @Test fun foreignDomainRouteAndRequestUuidCannotCompleteCurrentRequest() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val authentic = fixture.status(request)
            val otherDomain = authentic.toBuilder().setFromDestination(UniversalMessage.Destination.newBuilder()
                .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)).build()
            val otherRoute = authentic.toBuilder().setToDestination(UniversalMessage.Destination.newBuilder()
                .setRoutingAddress(ByteString.copyFrom(ByteArray(16)))).build()
            val otherUuid = authentic.toBuilder().setRequestUuid(ByteString.copyFrom(ByteArray(16))).build()
            for (crossed in listOf(otherDomain, otherRoute, otherUuid)) {
                assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS), fixture.conversation.receive(crossed.toByteArray()))
            }
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0, bodyVehicleSleepStatus = 0)), fixture.conversation.receive(authentic.toByteArray()))
        }
    }

    @Test fun plaintextStatusIsNeverAcceptedAsProbeSuccess() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val response = fixture.baseResponse(request)
                .setProtobufMessageAsBytes(fixture.vehicleStatus(1, 0).toByteString()).build()
            assertFailed(fixture.conversation.receive(response.toByteArray()))
        }
    }

    @Test fun changedCiphertextFailsAuthentication() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val response = fixture.status(request)
            val ciphertext = response.protobufMessageAsBytes.toByteArray().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            val invalid = response.toBuilder().setProtobufMessageAsBytes(ByteString.copyFrom(ciphertext)).build()
            assertFailed(fixture.conversation.receive(invalid.toByteArray()))
        }
    }

    @Test fun changedAuthenticatedResponseFlagsFailAuthentication() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val invalid = fixture.status(request).toBuilder().setFlags(2).build()
            assertFailed(fixture.conversation.receive(invalid.toByteArray()))
        }
    }

    @Test fun wrongRequestHashFailsEvenWithCorrectKeyRouteAndUuid() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val invalid = fixture.encryptResponse(request, fixture.vehicleStatus(1, 0), counter = 8,
                requestHash = ByteArray(17))
            assertFailed(fixture.conversation.receive(invalid.toByteArray()))
        }
    }

    @Test fun replayedResponseCannotCrossFreshRequestsSharingTheSameEcdhKey() {
        VehicleFixture().use { fixture ->
            val first = fixture.authenticate()
            val firstResponse = fixture.status(first)
            val replacement = TeslaBleConversation(fixture.client, fixture.vin) { fixture.clock }
            replacement.use {
                val secondHello = parseSend(replacement.start(false))
                val second = parseSend(replacement.receive(fixture.handshake(secondHello).toByteArray()))
                val replay = firstResponse.toBuilder().setToDestination(second.fromDestination)
                    .setRequestUuid(second.uuid).build()
                assertFailed(replacement.receive(replay.toByteArray()))
            }
        }
    }

    @Test fun duplicateAuthenticatedCounterCannotBecomeStatusSuccess() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val wait = fixture.encryptResponse(request, waitingResponse(), counter = 8)
            assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS), fixture.conversation.receive(wait.toByteArray()))
            assertFailed(fixture.conversation.receive(fixture.status(request, counter = 8).toByteArray()))
        }
    }

    @Test fun uniqueResponseCountersMayArriveOutOfOrderButCompletionIsOneShot() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.READING_STATUS),
                fixture.conversation.receive(fixture.encryptResponse(request, waitingResponse(), counter = 9).toByteArray()))
            val response = fixture.status(request, counter = 8)
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0, bodyVehicleSleepStatus = 0)), fixture.conversation.receive(response.toByteArray()))
            assertFailed(fixture.conversation.receive(response.toByteArray()))
        }
    }

    @Test fun authenticatedFaultIsFailureNotStatusSuccess() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val fault = fixture.encryptResponse(request, fixture.vehicleStatus(1, 0), counter = 8,
                fault = UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES_VALUE)
            assertFailed(fixture.conversation.receive(fault.toByteArray()))
        }
    }

    @Test fun nominalHelloAckKeepsWaitingAndStillAcceptsFollowingSignedSessionInfo() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            repeat(2) {
                assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.ACKNOWLEDGED,
                    fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
                assertEquals("ACK_WAITING", fixture.responseObservations.last().disposition)
                assertHeaderCorrelation(fixture.responseObservations.last(), true, true, true)
            }
            // ACK는 route/request_uuid 상관관계 identity를 회전시키지 않는다.
            val request = parseSend(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            assertEquals("SESSION_READY", fixture.responseObservations.last().disposition)
            assertHeaderCorrelation(fixture.responseObservations.last(), true, true, true)
            val decoded = fixture.decryptRequest(request)
            assertEquals(Vcsec.UnsignedMessage.SubMessageCase.INFORMATIONREQUEST, decoded.subMessageCase)
            assertEquals(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS,
                decoded.informationRequest.informationRequestType)
            // SESSION_READY의 관측은 HELLO 기준. 새 STATUS identity에서는 지난 ACK/session을 버린다.
            for (late in listOf(fixture.helloAck(hello), fixture.handshake(hello))) {
                assertWaiting(TeslaBlePhase.READING_STATUS, null,
                    fixture.conversation.receive(late.toByteArray()))
                assertEquals("ROUTE_MISMATCH", fixture.responseObservations.last().disposition)
                assertHeaderCorrelation(fixture.responseObservations.last(), true, false, false)
            }
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0, bodyVehicleSleepStatus = 0)),
                fixture.conversation.receive(fixture.status(request).toByteArray()))
            assertHeaderCorrelation(fixture.responseObservations.last(), true, true, true)
        }
    }

    @Test fun repeatedNominalHelloAcksNeverExtendTheHandshakeDeadline() {
        for (query in TeslaBleQuery.entries) {
            VehicleFixture(query).use { fixture ->
                val hello = fixture.start()
                fixture.clock += 14_999
                assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.ACKNOWLEDGED,
                    fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
                assertEquals("ACK_WAITING", fixture.responseObservations.last().disposition)
                // ACK가 phase 시작 시각을 갱신했다면 25_000ms에도 계속 대기했을 것이다.
                fixture.clock += 1
                assertFailed(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
                assertEquals("RESPONSE_INVALID", fixture.responseObservations.last().disposition)
            }
            VehicleFixture(query).use { fixture ->
                val hello = fixture.start()
                fixture.clock += 10_000
                assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.ACKNOWLEDGED,
                    fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
                assertEquals("ACK_WAITING", fixture.responseObservations.last().disposition)
                // 같은 ACK를 반복해도 대기 유예가 갱신되지 않는다.
                fixture.clock += 10_000
                assertFailed(fixture.conversation.receive(fixture.helloAck(hello).toByteArray()))
                assertEquals("RESPONSE_INVALID", fixture.responseObservations.last().disposition)
            }
        }
    }

    @Test fun helloMessageFaultIsRejectedBeforeAckOrSessionInfoJudgement() {
        for ((fault, evidence) in listOf(
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID_VALUE to
                TeslaBleAuthEvidence.KEY_NOT_PAIRED_REPORTED,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INACTIVE_KEY_VALUE to
                TeslaBleAuthEvidence.REQUEST_REJECTED,
            UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_REQUIRES_RESPONSE_ENCRYPTION_VALUE to
                TeslaBleAuthEvidence.REQUEST_REJECTED,
        )) {
            // fault가 있으면 OperationStatus OK를 포함한 ACK 모양도 대기가 아니다.
            assertHelloRejected(evidence) { fixture, hello -> fixture.helloAck(hello, fault = fault) }
        }
        // 서명이 유효한 session info가 함께 와도 fault가 우선한다.
        assertHelloRejected(TeslaBleAuthEvidence.REQUEST_REJECTED) { fixture, hello ->
            val info = fixture.sessionInfo(SESSION_INFO_OK)
            fixture.sessionInfoResponse(hello, info, fixture.sessionInfoTag(info, hello)).toBuilder()
                .setSignedMessageStatus(UniversalMessage.MessageStatus.newBuilder()
                    .setSignedMessageFaultValue(UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INACTIVE_KEY_VALUE))
                .build()
        }
    }

    @Test fun helloAckShapeWithNonOkOperationStatusOrOtherPayloadIsRejected() {
        for (operation in listOf(
            UniversalMessage.OperationStatus_E.OPERATIONSTATUS_WAIT_VALUE,
            UniversalMessage.OperationStatus_E.OPERATIONSTATUS_ERROR_VALUE,
            7,
        )) {
            assertHelloRejected(TeslaBleAuthEvidence.OPERATION_REJECTED) { fixture, hello ->
                fixture.helloAck(hello, operation = operation)
            }
        }
        // payload도 signedMessageStatus도 없는 응답과, session info 대신 다른 payload는 대기가 아니다.
        assertHelloRejected(TeslaBleAuthEvidence.UNEXPECTED_PAYLOAD) { fixture, hello ->
            fixture.baseResponse(hello).build()
        }
        assertHelloRejected(TeslaBleAuthEvidence.UNEXPECTED_PAYLOAD) { fixture, hello ->
            fixture.baseResponse(hello).setProtobufMessageAsBytes(fixture.vehicleStatus(1, 0).toByteString()).build()
        }
    }

    @Test fun keyNotOnWhitelistSessionInfoFailsAsReportedMissingKeyEvenWithoutSignature() {
        for (signed in listOf(false, true)) {
            // status 1은 인증 전 응답의 미등록 보고이며, 서명이 없어도 인증 성공으로 취급하지 않는다.
            assertHelloRejected(TeslaBleAuthEvidence.KEY_NOT_PAIRED_REPORTED) { fixture, hello ->
                val info = fixture.sessionInfo(Signatures.Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST_VALUE)
                fixture.sessionInfoResponse(hello, info, if (signed) fixture.sessionInfoTag(info, hello) else null)
            }
        }
    }

    @Test fun unknownSessionInfoStatusIsUnsupportedRatherThanReportedMissingKey() {
        for (status in listOf(2, 7)) {
            // 서명/HMAC이 유효해도 알 수 없는 status를 미등록 보고로 오인하지 않는다.
            assertHelloRejected(TeslaBleAuthEvidence.SESSION_STATUS_UNSUPPORTED) { fixture, hello ->
                val info = fixture.sessionInfo(status)
                fixture.sessionInfoResponse(hello, info, fixture.sessionInfoTag(info, hello))
            }
        }
    }

    @Test fun invalidSignedSessionInfoBoundaryNeverSendsStatusRequest() {
        assertHelloDiscarded(TeslaBleAuthEvidence.SESSION_INFO_INVALID) { fixture, hello ->
            fixture.baseResponse(hello).setSessionInfo(ByteString.copyFrom(byteArrayOf(0x80.toByte()))).build()
        }
        assertHelloDiscarded(TeslaBleAuthEvidence.SIGNATURE_MISSING) { fixture, hello ->
            fixture.sessionInfoResponse(hello, fixture.sessionInfo(SESSION_INFO_OK), null)
        }
        assertHelloDiscarded(TeslaBleAuthEvidence.TAG_MISSING) { fixture, hello ->
            fixture.baseResponse(hello).setSessionInfo(ByteString.copyFrom(fixture.sessionInfo(SESSION_INFO_OK)))
                .setSignatureData(Signatures.SignatureData.getDefaultInstance()).build()
        }
        for (length in listOf(0, 1, 16, 31, 33, 64)) {
            assertHelloDiscarded(TeslaBleAuthEvidence.TAG_LENGTH_INVALID) { fixture, hello ->
                val info = fixture.sessionInfo(SESSION_INFO_OK)
                fixture.sessionInfoResponse(hello, info, fixture.sessionInfoTag(info, hello).copyOf(length))
            }
        }
        for (epochBytes in listOf(15, 17)) {
            assertHelloDiscarded(TeslaBleAuthEvidence.PARAMETERS_INVALID) { fixture, hello ->
                val info = fixture.sessionInfo(SESSION_INFO_OK, epochBytes = epochBytes)
                fixture.sessionInfoResponse(hello, info, fixture.sessionInfoTag(info, hello))
            }
        }
        assertHelloDiscarded(TeslaBleAuthEvidence.PARAMETERS_INVALID) { fixture, hello ->
            val info = fixture.sessionInfo(SESSION_INFO_OK, publicKeyBytes = 64)
            fixture.sessionInfoResponse(hello, info, fixture.sessionInfoTag(info, hello))
        }
        assertHelloDiscarded(TeslaBleAuthEvidence.HMAC_MISMATCH) { fixture, hello ->
            fixture.sessionInfoResponse(hello, fixture.sessionInfo(SESSION_INFO_OK), ByteArray(32))
        }
    }

    @Test fun repeatedInvalidSessionTagsDoNotExtendHandshakeDeadline() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val invalid = fixture.sessionInfoResponse(hello, fixture.sessionInfo(SESSION_INFO_OK), ByteArray(16))
            fixture.clock += 14_999
            assertWaiting(TeslaBlePhase.HANDSHAKING, TeslaBleAuthEvidence.TAG_LENGTH_INVALID,
                fixture.conversation.receive(invalid.toByteArray()))
            fixture.clock++
            assertFailed(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
        }
    }

    @Test fun missingAndUnknownStatusFieldsRemainUnknownRatherThanEnumZero() {
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val absent = fixture.vehicleStatus(null, null)
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(bodyVehicleSleepStatus = 0)),
                fixture.conversation.receive(fixture.encryptResponse(request, absent, 8).toByteArray()))
        }
        VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val unknown = fixture.vehicleStatus(99, 99)
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(bodyVehicleSleepStatus = 0)),
                fixture.conversation.receive(fixture.encryptResponse(request, unknown, 8).toByteArray()))
        }
    }

    @Test fun legacyRegistrationCannotTreatEmptyGenericOrCardWaitAsTerminalSuccess() {
        VehicleFixture().use { fixture ->
            val wire = (fixture.conversation.start(true) as TeslaBleStep.Send).payload
            val envelope = Vcsec.ToVCSECMessage.parseFrom(wire)
            val request = Vcsec.UnsignedMessage.parseFrom(envelope.signedMessage.protobufMessageAsBytes)
            assertEquals(Vcsec.SignatureType.SIGNATURE_TYPE_PRESENT_KEY, envelope.signedMessage.signatureType)
            assertEquals(Keys.Role.ROLE_DRIVER, request.whitelistOperation.addKeyToWhitelistAndAddPermissions.keyRole)
            assertEquals(Vcsec.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE, request.whitelistOperation.metadataForKey.keyFormFactor)
            for (response in listOf(
                Vcsec.FromVCSECMessage.getDefaultInstance(),
                genericRegistrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_OK),
                genericRegistrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR),
                genericRegistrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT),
                registrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT, 0),
            )) {
                assertEquals(TeslaBleStep.Waiting(TeslaBlePhase.WAITING_FOR_CARD),
                    fixture.conversation.receive(response.toByteArray()))
                assertFalse(fixture.conversation.registrationReported)
            }
            val next = fixture.conversation.receive(registrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_OK, 0).toByteArray())
            assertTrue(next is TeslaBleStep.Send && next.phase == TeslaBlePhase.HANDSHAKING)
            assertTrue(fixture.conversation.registrationReported)
            // legacy terminal 보고 뒤에도 새 인증 검증 없이 Complete를 반환하지 않는다.
            assertFailed(fixture.conversation.receive(byteArrayOf(0)))
            assertTrue(fixture.conversation.registrationReported)
        }
    }

    @Test fun duplicateKeyRegistrationIsRejectedRatherThanClaimedPaired() {
        VehicleFixture().use { fixture ->
            fixture.conversation.start(true)
            val refused = registrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR,
                Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_ATTEMPTING_TO_ADD_KEY_THAT_IS_ALREADY_ON_THE_WHITELIST_VALUE)
            assertFailed(fixture.conversation.receive(refused.toByteArray()))
            assertFalse(fixture.conversation.registrationReported)
        }
    }

    @Test fun contradictoryRegistrationErrorCannotBecomeSuccess() {
        VehicleFixture().use { fixture ->
            fixture.conversation.start(true)
            assertFailed(fixture.conversation.receive(registrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR, 0).toByteArray()))
            assertFalse(fixture.conversation.registrationReported)
        }
    }

    @Test fun delayedHandshakeAndBackwardClockFailWithoutSendingStatus() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val response = fixture.handshake(hello)
            fixture.clock += 180_001
            assertFailed(fixture.conversation.receive(response.toByteArray()))
        }
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            fixture.clock--
            assertFailed(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
        }
    }

    @Test fun counterRolloverAndClosedConversationCannotTransmit() {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            assertFailed(fixture.conversation.receive(fixture.handshake(hello, counter = -1).toByteArray()))
        }
        VehicleFixture().use { fixture ->
            fixture.start()
            fixture.conversation.close()
            assertFailed(fixture.conversation.start(false))
            assertFailed(fixture.conversation.receive(byteArrayOf(1)))
        }
    }

    @Test fun fragmentedAndCoalescedFramesPreserveBoundariesAtEverySplit() {
        val first = byteArrayOf(1, 2, 3, 4, 5)
        val second = byteArrayOf(6, 7, 8)
        val stream = framed(first) + framed(second)
        for (split in 1 until stream.size) {
            val decoder = TeslaBleFrameDecoder()
            val frames = decoder.append(stream.copyOfRange(0, split)) + decoder.append(stream.copyOfRange(split, stream.size))
            assertEquals(2, frames.size)
            assertArrayEquals(first, frames[0])
            assertArrayEquals(second, frames[1])
        }
    }

    @Test fun invalidFramePoisonsDecoderUntilExplicitClearAndDiscardsPartialBytes() {
        val decoder = TeslaBleFrameDecoder()
        assertTrue(decoder.append(byteArrayOf(0, 3, 1)).isEmpty())
        decoder.clear()
        assertThrows(TeslaBleFrameException::class.java) { decoder.append(byteArrayOf(0x10, 0x01)) }
        assertThrows(TeslaBleFrameException::class.java) { decoder.append(framed(byteArrayOf(2))) }
        decoder.clear()
        assertArrayEquals(byteArrayOf(2), decoder.append(framed(byteArrayOf(2))).single())
        decoder.clear()
        val frames = decoder.append(framed(byteArrayOf()) + framed(byteArrayOf(3)))
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(), frames[0])
        assertArrayEquals(byteArrayOf(3), frames[1])
    }

    @Test fun v62IncompleteFrameObservationIsReadOnlyAcrossFragmentsCompletionPoisonAndClear() {
        val decoder = TeslaBleFrameDecoder()
        assertFalse(decoder.hasIncompleteFrame())
        assertTrue(decoder.append(byteArrayOf(0)).isEmpty())
        repeat(2) { assertTrue(decoder.hasIncompleteFrame()) }
        assertTrue(decoder.append(byteArrayOf(3, 1)).isEmpty())
        assertTrue(decoder.hasIncompleteFrame())
        assertTrue(decoder.append(byteArrayOf()).isEmpty())
        assertTrue(decoder.hasIncompleteFrame())
        assertArrayEquals(byteArrayOf(1, 2, 3), decoder.append(byteArrayOf(2, 3)).single())
        assertFalse(decoder.hasIncompleteFrame())
        val completeAndTail = decoder.append(framed(byteArrayOf(4)) + byteArrayOf(0))
        assertArrayEquals(byteArrayOf(4), completeAndTail.single())
        assertTrue(decoder.hasIncompleteFrame())
        assertArrayEquals(byteArrayOf(), decoder.append(byteArrayOf(0)).single())
        assertFalse(decoder.hasIncompleteFrame())
        assertTrue(decoder.append(byteArrayOf(0)).isEmpty())
        decoder.clear()
        assertFalse(decoder.hasIncompleteFrame())
        assertThrows(TeslaBleFrameException::class.java) { decoder.append(byteArrayOf(0x10, 0x01)) }
        assertFalse(decoder.hasIncompleteFrame())
        assertThrows(TeslaBleFrameException::class.java) { decoder.append(framed(byteArrayOf(5))) }
        assertFalse(decoder.hasIncompleteFrame())
        decoder.clear()
        assertArrayEquals(byteArrayOf(5), decoder.append(framed(byteArrayOf(5))).single())
        assertFalse(decoder.hasIncompleteFrame())
    }

    /**
     * HELLO 응답이 typed evidence로 실패하고, 상태 GET을 보내지 않으며, 대화를 종결시키는지 검사한다.
     */
    private fun assertHelloRejected(
        expected: TeslaBleAuthEvidence,
        build: (VehicleFixture, UniversalMessage.RoutableMessage) -> UniversalMessage.RoutableMessage,
    ) {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val step = fixture.conversation.receive(build(fixture, hello).toByteArray())
            assertEvidence(expected, step)
            assertFalse("상태 GET을 보내면 안 된다", step is TeslaBleStep.Send)
            assertFailed(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
        }
    }

    private fun assertHelloDiscarded(
        expected: TeslaBleAuthEvidence,
        build: (VehicleFixture, UniversalMessage.RoutableMessage) -> UniversalMessage.RoutableMessage,
    ) {
        VehicleFixture().use { fixture ->
            val hello = fixture.start()
            assertWaiting(TeslaBlePhase.HANDSHAKING, expected,
                fixture.conversation.receive(build(fixture, hello).toByteArray()))
            assertEquals(when (expected) {
                TeslaBleAuthEvidence.SESSION_INFO_INVALID, TeslaBleAuthEvidence.PARAMETERS_INVALID -> "SESSION_INFO_INVALID"
                else -> "SESSION_INFO_AUTH_REJECTED"
            }, fixture.responseObservations.last().disposition)
            val request = parseSend(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            assertEquals("SESSION_READY", fixture.responseObservations.last().disposition)
            assertEquals(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS,
                fixture.decryptRequest(request).informationRequest.informationRequestType)
            assertEquals(TeslaBleStep.Complete(TeslaReadOnlyStatus(1, 0, bodyVehicleSleepStatus = 0)),
                fixture.conversation.receive(fixture.status(request).toByteArray()))
        }
    }

    private class FrunkFixture : AutoCloseable {
        val wire = VehicleFixture()
        var wall = 1_792_360_988_423L
        val policy = TeslaFrunkPolicy(wire.clock, { wire.clock }, { wall })
        val sequence = TeslaFrunkSequence(wire.client, wire.vin.copyOf(), policy) { wire.clock }
        fun receive(response: UniversalMessage.RoutableMessage): TeslaBleStep =
            sequence.receive(response.toByteArray(), wire.clock)
        fun sourceTime(): Timestamp = timestamp(wall / 1_000, (wall % 1_000).toInt() * 1_000_000)
        fun driveRequest(): UniversalMessage.RoutableMessage {
            val hello = parseSend(sequence.start())
            assertEquals(UniversalMessage.Domain.DOMAIN_INFOTAINMENT, hello.toDestination.domain)
            val request = parseSend(receive(wire.handshake(hello)))
            assertTrue(wire.decryptDriveRequest(request).vehicleAction.getVehicleData.hasGetDriveState())
            return request
        }
        fun action(): UniversalMessage.RoutableMessage {
            val drive = driveRequest()
            val hello = parseSend(receive(wire.driveResponse(drive, wire.driveState(timestamp = sourceTime()))))
            val step = receive(wire.handshake(hello))
            assertTrue((step as TeslaBleStep.Send).actuator)
            return parseSend(step)
        }
        fun completeAction(action: UniversalMessage.RoutableMessage) {
            assertNull(policy.beginWrite(0, action.serializedSize + 2))
            policy.recordSdkResult(0)
            assertTrue(policy.recordWriteCallback(0, true))
        }
        override fun close() {
            sequence.close()
            wire.close()
        }
    }

    internal class VehicleFixture(val query: TeslaBleQuery = TeslaBleQuery.BODY_STATUS,
        purpose: TeslaBlePurpose = TeslaBlePurpose.READ_ONLY, serviceOwned: Boolean = false) : AutoCloseable {
        val vin = ByteArray(17) { '0'.code.toByte() }
        val client = JvmCredential(newPair())
        private val vehicle = newPair()
        private val sessionKey = derive(vehicle, client.publicKeyBytes())
        var clock = 10_000L
        val responseObservations = mutableListOf<TeslaBleResponseObservation>()
        var responseObserver: ((TeslaBleResponseObservation) -> Unit)? = null
        val conversation = TeslaBleConversation(client, vin, query, purpose = purpose, serviceOwned = serviceOwned,
            onResponseObservation = { responseObservations += it; responseObserver?.invoke(it) }) { clock }
        private val epoch = ByteArray(16) { 7 }

        fun start(): UniversalMessage.RoutableMessage = parseSend(conversation.start(false))

        fun authenticate(): UniversalMessage.RoutableMessage {
            val hello = start()
            return parseSend(conversation.receive(handshake(hello).toByteArray()))
        }

        fun handshake(request: UniversalMessage.RoutableMessage, counter: Int = 6): UniversalMessage.RoutableMessage {
            val info = sessionInfo(SESSION_INFO_OK, counter)
            return sessionInfoResponse(request, info, sessionInfoTag(info, request))
        }

        /** payload 없는 명목 ACK. fault/operation 값만 바꿔 차량 응답 모양을 재현한다. */
        fun helloAck(request: UniversalMessage.RoutableMessage, operation: Int = 0, fault: Int = 0): UniversalMessage.RoutableMessage =
            baseResponse(request).setSignedMessageStatus(UniversalMessage.MessageStatus.newBuilder()
                .setOperationStatusValue(operation).setSignedMessageFaultValue(fault)).build()

        fun sessionInfo(status: Int, counter: Int = 6, epochBytes: Int = 16, publicKeyBytes: Int = 65): ByteArray =
            Signatures.SessionInfo.newBuilder().setCounter(counter).setClockTime(100)
                .setPublicKey(ByteString.copyFrom(p256PublicKeyBytes(vehicle.public as ECPublicKey).copyOf(publicKeyBytes)))
                .setEpoch(ByteString.copyFrom(ByteArray(epochBytes) { epoch[it % epoch.size] }))
                .setStatusValue(status).build().toByteArray()

        fun sessionInfoTag(info: ByteArray, request: UniversalMessage.RoutableMessage): ByteArray {
            val derived = testHmac(sessionKey, "session info".toByteArray(Charsets.US_ASCII))
            val metadata = tlv(0 to byteArrayOf(6), 2 to vin, 6 to request.uuid.toByteArray())
            val tag = testHmac(derived, metadata, info)
            derived.fill(0)
            return tag
        }

        fun sessionInfoResponse(request: UniversalMessage.RoutableMessage, info: ByteArray,
            tag: ByteArray?): UniversalMessage.RoutableMessage {
            val builder = baseResponse(request).setSessionInfo(ByteString.copyFrom(info))
            if (tag != null) builder.setSignatureData(Signatures.SignatureData.newBuilder()
                .setSessionInfoTag(Signatures.HMAC_Signature_Data.newBuilder().setTag(ByteString.copyFrom(tag))))
            return builder.build()
        }

        fun decryptRequest(request: UniversalMessage.RoutableMessage): Vcsec.UnsignedMessage {
            val plaintext = decryptRequestPayload(request)
            return try { Vcsec.UnsignedMessage.parseFrom(plaintext) } finally { plaintext.fill(0) }
        }

        fun decryptDriveRequest(request: UniversalMessage.RoutableMessage): CarServer.Action {
            assertEquals(UniversalMessage.Domain.DOMAIN_INFOTAINMENT, request.toDestination.domain)
            val plaintext = decryptRequestPayload(request)
            return try { CarServer.Action.parseFrom(plaintext) } finally { plaintext.fill(0) }
        }

        fun decryptRequestPayload(request: UniversalMessage.RoutableMessage): ByteArray {
            assertEquals(16, request.uuid.size())
            assertEquals(16, request.fromDestination.routingAddress.size())
            val signature = request.signatureData.getAESGCMPersonalizedData()
            val metadata = tlv(0 to byteArrayOf(5), 1 to byteArrayOf(request.toDestination.domainValue.toByte()), 2 to vin,
                3 to signature.epoch.toByteArray(), 4 to uint(signature.expiresAt),
                5 to uint(signature.counter), 7 to uint(request.flags))
            return crypt(Cipher.DECRYPT_MODE, sessionKey, signature.nonce.toByteArray(), metadata,
                request.protobufMessageAsBytes.toByteArray() + signature.tag.toByteArray())
        }

        fun status(request: UniversalMessage.RoutableMessage, lock: Int? = 1, frontTrunk: Int? = 0,
            counter: Int = 8): UniversalMessage.RoutableMessage {
            val status = vehicleStatus(lock, frontTrunk)
            return if (request.flags and (1 shl UniversalMessage.Flags.FLAG_ENCRYPT_RESPONSE_VALUE) != 0) {
                encryptResponse(request, status, counter)
            } else {
                baseResponse(request).setProtobufMessageAsBytes(status.toByteString()).build()
            }
        }

        fun vehicleStatus(lock: Int?, frontTrunk: Int?): Vcsec.FromVCSECMessage {
            val status = Vcsec.VehicleStatus.newBuilder()
            if (lock != null) status.setVehicleLockStateValue(lock)
            if (frontTrunk != null) status.setClosureStatuses(Vcsec.ClosureStatuses.newBuilder().setFrontTrunkValue(frontTrunk))
            return Vcsec.FromVCSECMessage.newBuilder().setVehicleStatus(status).build()
        }

        fun driveState(shiftState: Vehicle.ShiftState? = shift(TeslaGear.P), timestamp: Timestamp? = null): Vehicle.DriveState {
            val drive = Vehicle.DriveState.newBuilder()
            if (shiftState != null) drive.setShiftState(shiftState)
            if (timestamp != null) drive.setTimestamp(timestamp)
            return drive.build()
        }

        fun carResponse(drive: Vehicle.DriveState? = driveState(), result: Int? = 0, reason: String? = null): CarServer.Response {
            val response = CarServer.Response.newBuilder()
            if (result != null) {
                val actionStatus = CarServer.ActionStatus.newBuilder().setResultValue(result)
                if (reason != null) actionStatus.setResultReason(CarServer.ResultReason.newBuilder().setPlainText(reason))
                response.setActionStatus(actionStatus)
            }
            if (drive != null) response.setVehicleData(Vehicle.VehicleData.newBuilder().setDriveState(drive))
            return response.build()
        }

        fun driveResponse(request: UniversalMessage.RoutableMessage, drive: Vehicle.DriveState = driveState(),
            counter: Int = 8): UniversalMessage.RoutableMessage =
            encryptResponse(request, carResponse(drive), counter)

        fun baseResponse(request: UniversalMessage.RoutableMessage): UniversalMessage.RoutableMessage.Builder =
            UniversalMessage.RoutableMessage.newBuilder().setFromDestination(request.toDestination)
                .setToDestination(request.fromDestination).setRequestUuid(request.uuid)

        fun encryptResponse(request: UniversalMessage.RoutableMessage, payload: MessageLite,
            counter: Int, requestHash: ByteArray = byteArrayOf(5) + request.signatureData.getAESGCMPersonalizedData().tag.toByteArray(),
            fault: Int = 0, authenticatedDomain: Int = request.toDestination.domainValue): UniversalMessage.RoutableMessage {
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            val metadata = tlv(0 to byteArrayOf(9), 1 to byteArrayOf(authenticatedDomain.toByte()), 2 to vin,
                5 to uint(counter), 7 to uint(0), 8 to requestHash, 9 to uint(fault))
            val encrypted = crypt(Cipher.ENCRYPT_MODE, sessionKey, nonce, metadata, payload.toByteArray())
            return baseResponse(request).setProtobufMessageAsBytes(ByteString.copyFrom(encrypted, 0, encrypted.size - 16))
                .setSignatureData(Signatures.SignatureData.newBuilder().setAESGCMResponseData(
                    Signatures.AES_GCM_Response_Signature_Data.newBuilder().setNonce(ByteString.copyFrom(nonce))
                        .setCounter(counter).setTag(ByteString.copyFrom(encrypted, encrypted.size - 16, 16))))
                .setSignedMessageStatus(UniversalMessage.MessageStatus.newBuilder().setSignedMessageFaultValue(fault))
                .build()
        }

        override fun close() {
            conversation.close()
            sessionKey.fill(0)
            vin.fill(0)
        }
    }

    internal class JvmCredential(private val pair: KeyPair) : TeslaBleCredential {
        override fun publicKeyBytes(): ByteArray = p256PublicKeyBytes(pair.public as ECPublicKey)
        override fun deriveSessionKey(vehiclePublicKey: ByteArray): ByteArray = derive(pair, vehiclePublicKey)
    }

    private companion object {
        const val SESSION_INFO_OK = Signatures.Session_Info_Status.SESSION_INFO_STATUS_OK_VALUE
        fun timestamp(seconds: Long, nanos: Int): Timestamp = Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build()
        fun shift(gear: TeslaGear): Vehicle.ShiftState = Vehicle.ShiftState.newBuilder().apply {
            when (gear) {
                TeslaGear.P -> setP(Common.Void.getDefaultInstance())
                TeslaGear.R -> setR(Common.Void.getDefaultInstance())
                TeslaGear.N -> setN(Common.Void.getDefaultInstance())
                TeslaGear.D -> setD(Common.Void.getDefaultInstance())
                TeslaGear.UNKNOWN -> setSNA(Common.Void.getDefaultInstance())
            }
        }.build()
        fun assertRx(observed: TeslaBleResponseObservation, step: TeslaBleRxStep,
            inputKind: TeslaBleRxInputKind, aead: TeslaBleRxAead, firaKind: TeslaBleRxFiraKind?,
            disposition: String) {
            assertEquals(step, observed.step)
            assertEquals(inputKind, observed.inputKind)
            assertEquals(aead, observed.aead)
            assertEquals(firaKind, observed.firaKind)
            assertEquals(disposition, observed.disposition)
        }
        fun assertNoEnvelope(observed: TeslaBleResponseObservation) {
            assertNull(observed.fromPresent)
            assertNull(observed.fromKind)
            assertNull(observed.fromDomain)
            assertNull(observed.toPresent)
            assertNull(observed.toKind)
            assertNull(observed.payloadKind)
            assertNull(observed.domainMismatchReason)
            assertHeaderCorrelation(observed, null, null, null)
            assertNull(observed.signedStatusPresent)
            assertNull(observed.signedMessageFault)
            assertNull(observed.operationStatus)
        }
        fun assertHeaderCorrelation(observed: TeslaBleResponseObservation, uuidPresent: Boolean?,
            uuidMatches: Boolean?, routeMatches: Boolean?) {
            assertEquals(uuidPresent, observed.requestUuidPresent)
            assertEquals(uuidMatches, observed.requestUuidMatches)
            assertEquals(routeMatches, observed.toRouteMatches)
        }
        fun assertFailed(step: TeslaBleStep) { assertTrue(step is TeslaBleStep.Failed) }
        /** typed evidence까지 검사한다. 실패 이유 문구에는 결속하지 않는다. */
        fun assertEvidence(expected: TeslaBleAuthEvidence, step: TeslaBleStep) {
            assertTrue("expected Failed but was $step", step is TeslaBleStep.Failed)
            assertEquals(expected, (step as TeslaBleStep.Failed).authEvidence)
        }
        fun assertWaiting(phase: TeslaBlePhase, evidence: TeslaBleAuthEvidence?, step: TeslaBleStep) {
            assertTrue("expected Waiting($phase) but was $step", step is TeslaBleStep.Waiting)
            step as TeslaBleStep.Waiting
            assertEquals(phase, step.phase)
            assertEquals(evidence, step.authEvidence)
        }
        fun parseSend(step: TeslaBleStep): UniversalMessage.RoutableMessage {
            assertTrue(step is TeslaBleStep.Send)
            return UniversalMessage.RoutableMessage.parseFrom((step as TeslaBleStep.Send).payload)
        }
        fun newPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        fun derive(pair: KeyPair, peer: ByteArray): ByteArray {
            val secret = KeyAgreement.getInstance("ECDH").run {
                init(pair.private)
                doPhase(p256PublicKeyFromBytes(peer), true)
                generateSecret()
            }
            val digest = MessageDigest.getInstance("SHA-1").digest(secret)
            secret.fill(0)
            return digest.copyOf(16).also { digest.fill(0) }
        }
        fun testHmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach(::update)
            doFinal()
        }
        fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, metadata: ByteArray, payload: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(MessageDigest.getInstance("SHA-256").digest(metadata))
                doFinal(payload)
            }
        fun uint(value: Int): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte())
        fun tlv(vararg fields: Pair<Int, ByteArray>): ByteArray = ByteArrayOutputStream().run {
            for ((tag, value) in fields) { write(tag); write(value.size); write(value) }
            write(255)
            toByteArray()
        }
        fun genericRegistrationStatus(operation: Vcsec.OperationStatus_E): Vcsec.FromVCSECMessage =
            Vcsec.FromVCSECMessage.newBuilder().setCommandStatus(Vcsec.CommandStatus.newBuilder().setOperationStatus(operation)).build()
        fun registrationStatus(operation: Vcsec.OperationStatus_E, information: Int): Vcsec.FromVCSECMessage =
            Vcsec.FromVCSECMessage.newBuilder().setCommandStatus(Vcsec.CommandStatus.newBuilder()
                .setOperationStatus(operation).setWhitelistOperationStatus(Vcsec.WhitelistOperation_status.newBuilder()
                    .setOperationStatus(operation).setWhitelistOperationInformationValue(information))).build()
        fun waitingResponse(): Vcsec.FromVCSECMessage = genericRegistrationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT)
        fun framed(payload: ByteArray): ByteArray = byteArrayOf((payload.size ushr 8).toByte(), payload.size.toByte()) + payload
    }
}
