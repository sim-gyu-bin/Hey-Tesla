package com.heytesla.app.vehicle.protocol

import com.google.protobuf.ByteString
import com.heytesla.app.vehicle.protocol.TeslaBleProtocolTest.VehicleFixture
import com.tesla.generated.errors.Errors
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec
import org.junit.Assert.*
import org.junit.Test

class TeslaBleWakeTest {
    private class WakeFixture(val deadlineOffset: Long = 5_000L) : AutoCloseable {
        val wire = VehicleFixture()
        val startedAt = wire.clock
        val deadline = startedAt + deadlineOffset
        val observations = ArrayList<TeslaBleResponseObservation>()
        var authorizationCalls = 0
        var authorize: () -> String? = { null }
        val conversation = TeslaBleConversation(wire.client, wire.vin,
            purpose = TeslaBlePurpose.VEHICLE_WAKE,
            authorizeWake = { authorizationCalls++; authorize() }, wakeDeadline = deadline,
            onResponseObservation = { observations.add(it) }, now = { wire.clock })

        fun start(): UniversalMessage.RoutableMessage {
            val step = conversation.start(false) as TeslaBleStep.Send
            assertFalse(step.actuator)
            assertFalse(step.wake)
            return send(step)
        }
        fun receive(message: UniversalMessage.RoutableMessage, receivedAt: Long = wire.clock): TeslaBleStep =
            conversation.receive(message.toByteArray(), receivedAt)
        fun action(): UniversalMessage.RoutableMessage = authenticate(start())
        fun authenticate(hello: UniversalMessage.RoutableMessage): UniversalMessage.RoutableMessage {
            val step = receive(wire.handshake(hello)) as TeslaBleStep.Send
            assertFalse(step.actuator)
            assertTrue(step.wake)
            return send(step)
        }
        fun encrypted(request: UniversalMessage.RoutableMessage,
            body: Vcsec.FromVCSECMessage = Vcsec.FromVCSECMessage.getDefaultInstance(), counter: Int = 8,
            fault: Int = 0): UniversalMessage.RoutableMessage = wire.encryptResponse(request, body, counter, fault = fault)
        override fun close() { conversation.close(); wire.close() }
    }

    @Test fun wakeRejectsRegistrationOtherDomainMissingAuthorizationAndExpiredPurposeBeforeHello() {
        for (invalid in 0..7) VehicleFixture().use { wire ->
            var authorizationCalls = 0
            TeslaBleConversation(wire.client, wire.vin,
                query = if (invalid == 1) TeslaBleQuery.DRIVE_STATE else TeslaBleQuery.BODY_STATUS,
                purpose = TeslaBlePurpose.VEHICLE_WAKE,
                authorizeFrunk = if (invalid == 6) ({ null }) else null,
                frunkDeadline = if (invalid == 7) wire.clock + 5_000 else null,
                authorizeWake = if (invalid == 2) null else ({ authorizationCalls++; null }),
                wakeDeadline = when (invalid) {
                    3 -> null
                    4 -> -1L
                    5 -> wire.clock
                    else -> wire.clock + 5_000
                }, now = { wire.clock }).use { conversation ->
                assertTrue(conversation.start(invalid == 0) is TeslaBleStep.Failed)
                assertEquals(0, authorizationCalls)
                assertFalse(conversation.registrationReported)
                assertTrue(conversation.start(false) is TeslaBleStep.Failed)
            }
        }
    }

    @Test fun purposeKeepsReadAndFrunkFramesSeparateFromWakeAndFlagsAreExclusive() {
        assertThrows(IllegalArgumentException::class.java) {
            TeslaBleStep.Send(byteArrayOf(), TeslaBlePhase.READING_STATUS, actuator = true, wake = true)
        }
        for (purpose in listOf(TeslaBlePurpose.READ_ONLY, TeslaBlePurpose.FRUNK_OPEN)) VehicleFixture().use { wire ->
            var wakeAuthorizationCalls = 0
            TeslaBleConversation(wire.client, wire.vin, purpose = purpose,
                authorizeFrunk = { null }, frunkDeadline = wire.clock + 5_000,
                authorizeWake = { wakeAuthorizationCalls++; null }, wakeDeadline = wire.clock + 5_000,
                now = { wire.clock }).use { conversation ->
                val hello = send(conversation.start(false))
                val step = conversation.receive(wire.handshake(hello).toByteArray()) as TeslaBleStep.Send
                assertFalse(step.wake)
                assertEquals(purpose == TeslaBlePurpose.FRUNK_OPEN, step.actuator)
                val request = wire.decryptRequest(send(step))
                assertFalse(request.hasRKEAction())
                assertEquals(purpose == TeslaBlePurpose.FRUNK_OPEN, request.hasClosureMoveRequest())
                assertEquals(purpose == TeslaBlePurpose.READ_ONLY, request.hasInformationRequest())
                assertEquals(0, wakeAuthorizationCalls)
            }
        }
    }

    @Test fun verifiedFreshHelloProducesOnlyEncryptedRke30WithNewIdentityAndCounter() {
        WakeFixture().use { h ->
            val hello = h.start()
            assertTrue(hello.hasSessionInfoRequest())
            assertEquals(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY, hello.toDestination.domain)
            assertEquals(0, h.authorizationCalls)
            val action = h.authenticate(hello)
            assertEquals(1, h.authorizationCalls)
            assertEquals(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY, action.toDestination.domain)
            assertNotEquals(hello.uuid, action.uuid)
            assertNotEquals(hello.fromDestination.routingAddress, action.fromDestination.routingAddress)
            assertEquals(7, action.signatureData.getAESGCMPersonalizedData().counter)
            assertEquals(1 shl UniversalMessage.Flags.FLAG_ENCRYPT_RESPONSE_VALUE, action.flags)
            val unsigned = h.wire.decryptRequest(action)
            assertEquals(Vcsec.UnsignedMessage.newBuilder()
                .setRKEAction(Vcsec.RKEAction_E.RKE_ACTION_WAKE_VEHICLE).build(), unsigned)
            assertEquals(30, unsigned.getRKEActionValue())
            assertEquals(TeslaBleRxStep.SEND, h.observations.last().step)
            assertEquals("SESSION_READY", h.observations.last().disposition)
        }
    }

    @Test fun wakeAuthorizationDenialOrExceptionCannotProduceControlOrRetry() {
        for (throws in listOf(false, true)) WakeFixture().use { h ->
            val hello = h.start()
            h.authorize = {
                if (throws) throw IllegalStateException("TEST_PRIVATE_AUTHORIZATION_DETAIL")
                "WAKE_NOT_AUTHORIZED"
            }
            val step = h.receive(h.wire.handshake(hello))
            assertTrue(step is TeslaBleStep.Failed)
            assertFalse((step as TeslaBleStep.Failed).reason.contains("TEST_PRIVATE_AUTHORIZATION_DETAIL"))
            assertEquals(1, h.authorizationCalls)
            assertTrue(h.receive(h.wire.handshake(hello)) is TeslaBleStep.Failed)
            assertTrue(h.conversation.start(false) is TeslaBleStep.Failed)
            assertEquals(1, h.authorizationCalls)
        }
    }

    @Test fun authorizationRechecksCurrentTimeAndCannotExtendDeadlineOrUseSubsecondTtl() {
        for (offset in listOf(4_001L, 5_000L, -1L)) WakeFixture().use { h ->
            val hello = h.start()
            h.authorize = { h.wire.clock = h.startedAt + offset; null }
            assertTrue(h.receive(h.wire.handshake(hello)) is TeslaBleStep.Failed)
            assertEquals(1, h.authorizationCalls)
            assertEquals(TeslaBleRxStep.FAILED, h.observations.last().step)
            assertEquals("RESPONSE_REJECTED", h.observations.last().disposition)
        }
        WakeFixture().use { h ->
            val hello = h.start()
            h.wire.clock = h.deadline
            assertTrue(h.receive(h.wire.handshake(hello), h.startedAt) is TeslaBleStep.Failed)
            assertEquals(1, h.authorizationCalls)
        }
    }

    @Test fun wakeWireExpiryIsBoundedByOriginalDeadlineAndOriginalHelloIngress() {
        WakeFixture().use { h ->
            val hello = h.start()
            val receivedAt = h.startedAt + 500
            h.wire.clock = h.startedAt + 1_249
            h.authorize = { h.wire.clock += 600; null }
            val action = send(h.receive(h.wire.handshake(hello), receivedAt))
            val expires = action.signatureData.getAESGCMPersonalizedData().expiresAt.toLong()
            val wireExpiryAt = receivedAt + (expires - 100L) * 1_000L
            assertTrue(wireExpiryAt > h.wire.clock)
            assertTrue(wireExpiryAt <= h.deadline)
            assertEquals(104L, expires)
            assertEquals(1, h.authorizationCalls)
        }
    }

    @Test fun wakeRequiresCurrentHelloHmacRouteAndUuidBeforeAuthorization() {
        for (invalid in listOf("hmac", "route", "uuid")) WakeFixture().use { h ->
            val hello = h.start()
            val valid = h.wire.handshake(hello)
            val response = valid.toBuilder().apply {
                when (invalid) {
                    "hmac" -> setSignatureData(valid.signatureData.toBuilder().setSessionInfoTag(
                        Signatures.HMAC_Signature_Data.newBuilder().setTag(ByteString.copyFrom(ByteArray(32)))))
                    "route" -> setToDestination(UniversalMessage.Destination.newBuilder()
                        .setRoutingAddress(ByteString.copyFrom(ByteArray(16))))
                    else -> setRequestUuid(ByteString.copyFrom(ByteArray(16)))
                }
            }.build()
            val waiting = h.receive(response)
            assertTrue(waiting is TeslaBleStep.Waiting)
            assertEquals(0, h.authorizationCalls)
            if (invalid == "hmac") assertEquals(TeslaBleAuthEvidence.HMAC_MISMATCH,
                (waiting as TeslaBleStep.Waiting).authEvidence)
            h.authenticate(hello)
            assertEquals(1, h.authorizationCalls)
        }
    }

    @Test fun wakeRejectsInvalidIngressAndIgnoresPrestartFramesWithoutAuthorizing() {
        for (invalidTime in listOf(-1L, 1L)) WakeFixture().use { h ->
            val hello = h.start()
            val receivedAt = if (invalidTime < 0) -1L else h.wire.clock + invalidTime
            assertTrue(h.receive(h.wire.handshake(hello), receivedAt) is TeslaBleStep.Failed)
            assertEquals(0, h.authorizationCalls)
        }
        WakeFixture().use { h ->
            val hello = h.start()
            assertSame(TeslaBleStep.Ignored, h.receive(h.wire.handshake(hello), h.startedAt - 1))
            assertEquals(0, h.authorizationCalls)
            assertEquals("RESPONSE_BEFORE_START", h.observations.last().disposition)
            h.authenticate(hello)
            assertEquals(1, h.authorizationCalls)
        }
        WakeFixture().use { h ->
            val hello = h.start()
            h.wire.clock += 15_000
            assertTrue(h.receive(h.wire.handshake(hello), h.startedAt) is TeslaBleStep.Failed)
            assertEquals(0, h.authorizationCalls)
        }
    }

    @Test fun helloNominalAckWaitsButExactPlaintextWakeAckHasDistinctUnauthenticatedTerminal() {
        WakeFixture().use { h ->
            val hello = h.start()
            assertTrue(h.receive(h.wire.helloAck(hello)) is TeslaBleStep.Waiting)
            assertEquals(0, h.authorizationCalls)
            val action = h.authenticate(hello)
            assertEquals(TeslaBleStep.WakeResult(TeslaWakeReply.NOMINAL_ACK), h.receive(h.wire.helloAck(action)))
            assertWakeObservation(h, TeslaBleRxAead.NOT_ATTEMPTED, "WAKE_NOMINAL_ACK")
            assertTrue(h.receive(h.encrypted(action)) is TeslaBleStep.Failed)
            assertEquals(1, h.authorizationCalls)
        }
    }

    @Test fun unsignedDefaultUnknownStatusOrAnyPayloadCannotBecomeNominalWakeAck() {
        for (invalid in 0..4) WakeFixture().use { h ->
            val action = h.action()
            val response = when (invalid) {
                0 -> h.wire.baseResponse(action).build()
                1 -> h.wire.helloAck(action, operation = 99)
                2 -> h.wire.helloAck(action).toBuilder()
                    .setProtobufMessageAsBytes(ByteString.EMPTY).build()
                3 -> h.wire.helloAck(action).toBuilder()
                    .setProtobufMessageAsBytes(ByteString.copyFrom(byteArrayOf(0x08, 0x01))).build()
                else -> h.wire.helloAck(action).toBuilder().setSessionInfo(ByteString.EMPTY).build()
            }
            assertEquals(TeslaBleStep.WakeResult(TeslaWakeReply.UNCONFIRMED), h.receive(response))
            assertWakeObservation(h, TeslaBleRxAead.NOT_ATTEMPTED, "WAKE_UNCONFIRMED")
            assertTrue(h.receive(h.wire.helloAck(action)) is TeslaBleStep.Failed)
        }
    }

    @Test fun signatureDataCannotTurnUnauthenticatedNominalShapeIntoNominalWakeAck() {
        WakeFixture().use { h ->
            val action = h.action()
            val response = h.wire.helloAck(action).toBuilder().setSignatureData(
                Signatures.SignatureData.newBuilder().setSessionInfoTag(
                    Signatures.HMAC_Signature_Data.newBuilder().setTag(ByteString.copyFrom(ByteArray(32))))).build()
            assertTrue(h.receive(response) is TeslaBleStep.Failed)
            assertEquals(TeslaBleRxAead.NOT_ATTEMPTED, h.observations.last().aead)
            assertEquals(TeslaBleRxStep.FAILED, h.observations.last().step)
        }
    }

    @Test fun nominalWakeAckStillRequiresCurrentRouteUuidAndDomain() {
        WakeFixture().use { h ->
            val hello = h.start()
            val action = h.authenticate(hello)
            assertTrue(h.receive(h.wire.helloAck(hello)) is TeslaBleStep.Waiting)
            val ack = h.wire.helloAck(action)
            for (invalid in 0..2) {
                val crossed = ack.toBuilder().apply {
                    when (invalid) {
                        0 -> setFromDestination(UniversalMessage.Destination.newBuilder()
                            .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT))
                        1 -> setToDestination(UniversalMessage.Destination.newBuilder()
                            .setRoutingAddress(ByteString.copyFrom(ByteArray(16))))
                        else -> setRequestUuid(ByteString.copyFrom(ByteArray(16)))
                    }
                }.build()
                assertTrue(h.receive(crossed) is TeslaBleStep.Waiting)
                assertEquals(TeslaBleRxAead.NOT_ATTEMPTED, h.observations.last().aead)
            }
            assertEquals(TeslaBleStep.WakeResult(TeslaWakeReply.NOMINAL_ACK), h.receive(ack))
            assertWakeObservation(h, TeslaBleRxAead.NOT_ATTEMPTED, "WAKE_NOMINAL_ACK")
            assertEquals(1, h.authorizationCalls)
        }
    }

    @Test fun nominalWakeAckPreservesDomainTwoOmittedUuidExceptionOnCurrentRoute() {
        WakeFixture().use { h ->
            val action = h.action()
            val ack = h.wire.helloAck(action).toBuilder().clearRequestUuid().build()
            assertEquals(TeslaBleStep.WakeResult(TeslaWakeReply.NOMINAL_ACK), h.receive(ack))
            assertWakeObservation(h, TeslaBleRxAead.NOT_ATTEMPTED, "WAKE_NOMINAL_ACK")
            assertEquals(false, h.observations.last().requestUuidPresent)
            assertEquals(true, h.observations.last().toRouteMatches)
            assertEquals(TeslaBleRxRequestCorrelation.CURRENT, h.observations.last().requestCorrelation)
        }
    }

    @Test fun onlyAeadVerifiedTerminalAcknowledgesWakeAndCannotBeReused() {
        WakeFixture().use { h ->
            val action = h.action()
            val ack = h.encrypted(action)
            assertEquals(TeslaBleStep.WakeResult(TeslaWakeReply.ACKNOWLEDGED), h.receive(ack))
            assertWakeObservation(h, TeslaBleRxAead.VERIFIED, "WAKE_ACKNOWLEDGED")
            assertTrue(h.receive(ack) is TeslaBleStep.Failed)
            assertTrue(h.conversation.start(false) is TeslaBleStep.Failed)
            assertEquals(1, h.authorizationCalls)
        }
    }

    @Test fun encryptedCommandStatusAndEnvelopeWaitAreNotTerminalAndKeepOriginalResponseBudget() {
        WakeFixture().use { h ->
            val action = h.action()
            for ((index, operation) in listOf(Vcsec.OperationStatus_E.OPERATIONSTATUS_OK,
                Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT).withIndex()) {
                assertTrue(h.receive(h.encrypted(action, commandStatus(operation), 8 + index)) is TeslaBleStep.Waiting)
            }
            val waiting = h.encrypted(action, counter = 10).toBuilder().setSignedMessageStatus(
                UniversalMessage.MessageStatus.newBuilder()
                    .setOperationStatus(UniversalMessage.OperationStatus_E.OPERATIONSTATUS_WAIT)).build()
            assertTrue(h.receive(waiting) is TeslaBleStep.Waiting)
            assertEquals(TeslaBleRxAead.VERIFIED, h.observations.last().aead)
            // 송신 허용 TTL은 새 송신에만 적용한다. 이미 보낸 명령의 응답 대기를 5초로 줄이지 않는다.
            h.wire.clock = h.deadline + 1
            assertEquals(TeslaBleStep.WakeResult(TeslaWakeReply.ACKNOWLEDGED), h.receive(h.encrypted(action, counter = 11)))
            assertEquals(1, h.authorizationCalls)
        }
        WakeFixture().use { h ->
            val action = h.action()
            for ((index, offset) in listOf(1_000L, 14_999L).withIndex()) {
                h.wire.clock = h.startedAt + offset
                assertTrue(h.receive(h.encrypted(action,
                    commandStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT), 8 + index)) is TeslaBleStep.Waiting)
            }
            h.wire.clock = h.startedAt + 15_000
            assertTrue(h.receive(h.encrypted(action, counter = 10), h.startedAt + 14_999) is TeslaBleStep.Failed)
            assertEquals(1, h.authorizationCalls)
        }
    }

    @Test fun explicitErrorsProduceRejectedTypedWakeResultNotAcknowledgement() {
        for (error in 0..5) WakeFixture().use { h ->
            val action = h.action()
            val response = when (error) {
                0 -> h.encrypted(action, commandStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR))
                1 -> h.encrypted(action, Vcsec.FromVCSECMessage.newBuilder().setNominalError(
                    Errors.NominalError.getDefaultInstance()).build())
                2 -> h.encrypted(action, Vcsec.FromVCSECMessage.newBuilder().setCommandStatus(
                    Vcsec.CommandStatus.newBuilder().setSignedMessageStatus(
                        Vcsec.SignedMessage_status.newBuilder().setSignedMessageInformationValue(1))).build())
                3 -> h.encrypted(action, fault = UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES_VALUE)
                4 -> h.wire.helloAck(action, operation = UniversalMessage.OperationStatus_E.OPERATIONSTATUS_ERROR_VALUE)
                else -> h.wire.helloAck(action, fault = UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES_VALUE)
            }
            assertEquals(TeslaBleStep.WakeResult(TeslaWakeReply.REJECTED), h.receive(response))
            assertWakeObservation(h, if (error < 4) TeslaBleRxAead.VERIFIED else TeslaBleRxAead.NOT_ATTEMPTED,
                "RESPONSE_REJECTED")
        }
    }

    @Test fun currentRouteUuidAndDomainAreRequiredForWakeReplyAndPreviousHelloCannotCompleteIt() {
        WakeFixture().use { h ->
            val hello = h.start()
            val action = h.authenticate(hello)
            for (old in listOf(h.wire.helloAck(hello), h.wire.handshake(hello))) {
                assertTrue(h.receive(old) is TeslaBleStep.Waiting)
                assertEquals(TeslaBleRxRequestCorrelation.PREVIOUS, h.observations.last().requestCorrelation)
            }
            val ack = h.encrypted(action)
            for (invalid in 0..2) {
                val crossed = ack.toBuilder().apply {
                    when (invalid) {
                        0 -> setFromDestination(UniversalMessage.Destination.newBuilder()
                            .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT))
                        1 -> setToDestination(UniversalMessage.Destination.newBuilder()
                            .setRoutingAddress(ByteString.copyFrom(ByteArray(16))))
                        else -> setRequestUuid(ByteString.copyFrom(ByteArray(16)))
                    }
                }.build()
                assertTrue(h.receive(crossed) is TeslaBleStep.Waiting)
                assertEquals(TeslaBleRxAead.NOT_ATTEMPTED, h.observations.last().aead)
            }
            assertEquals(TeslaBleStep.WakeResult(TeslaWakeReply.ACKNOWLEDGED), h.receive(ack))
            assertEquals(1, h.authorizationCalls)
        }
    }

    @Test fun wakeRetainsDomainTwoOmittedUuidExceptionOnlyWithAuthenticatedCurrentRoute() {
        WakeFixture().use { h ->
            val action = h.action()
            val ack = h.encrypted(action).toBuilder().clearRequestUuid().build()
            assertEquals(TeslaBleStep.WakeResult(TeslaWakeReply.ACKNOWLEDGED), h.receive(ack))
            assertEquals(TeslaBleRxAead.VERIFIED, h.observations.last().aead)
            assertEquals(false, h.observations.last().requestUuidPresent)
        }
    }

    @Test fun invalidWakeAeadRequestHashOrAuthenticatedDomainFailsWithoutTypedAckOrResend() {
        for (invalid in 0..2) WakeFixture().use { h ->
            val action = h.action()
            val response = when (invalid) {
                0 -> h.encrypted(action).let { good -> good.toBuilder().setSignatureData(
                    good.signatureData.toBuilder().setAESGCMResponseData(
                        good.signatureData.getAESGCMResponseData().toBuilder()
                            .setTag(ByteString.copyFrom(ByteArray(16))))).build() }
                1 -> h.wire.encryptResponse(action, Vcsec.FromVCSECMessage.getDefaultInstance(), 8,
                    requestHash = ByteArray(17))
                else -> h.wire.encryptResponse(action, Vcsec.FromVCSECMessage.getDefaultInstance(), 8,
                    authenticatedDomain = UniversalMessage.Domain.DOMAIN_INFOTAINMENT_VALUE)
            }
            assertTrue(h.receive(response) is TeslaBleStep.Failed)
            assertEquals(TeslaBleRxAead.FAILED, h.observations.last().aead)
            assertEquals(TeslaBleRxStep.FAILED, h.observations.last().step)
            assertTrue(h.receive(h.encrypted(action)) is TeslaBleStep.Failed)
            assertEquals(1, h.authorizationCalls)
        }
    }

    @Test fun authenticatedWaitingResponseConsumesCounterAndReplayCannotAcknowledge() {
        WakeFixture().use { h ->
            val action = h.action()
            assertTrue(h.receive(h.encrypted(action,
                commandStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT), 19)) is TeslaBleStep.Waiting)
            assertEquals(TeslaBleRxAead.VERIFIED, h.observations.last().aead)
            assertTrue(h.receive(h.encrypted(action, counter = 19)) is TeslaBleStep.Failed)
            assertEquals(TeslaBleRxAead.NOT_ATTEMPTED, h.observations.last().aead)
            assertEquals("RESPONSE_REJECTED", h.observations.last().disposition)
            assertEquals(1, h.authorizationCalls)
        }
    }

    @Test fun bodyUsesActualAuthenticatedIngressNotHelloIngressOrProcessingTime() {
        for (sleep in listOf(0, 1, 2, 99)) VehicleFixture().use { wire ->
            val hello = wire.start()
            val helloIngress = wire.clock + 20
            wire.clock += 3_000
            val request = send(wire.conversation.receive(wire.handshake(hello).toByteArray(), helloIngress))
            val body = Vcsec.FromVCSECMessage.newBuilder().setVehicleStatus(
                Vcsec.VehicleStatus.newBuilder().setVehicleSleepStatusValue(sleep)).build()
            val bodyIngress = wire.clock + 50
            wire.clock += 1_000
            val completed = wire.conversation.receive(wire.encryptResponse(request, body, 8).toByteArray(), bodyIngress)
                as TeslaBleStep.Complete
            assertEquals(bodyIngress, completed.status.bodyReceivedAtElapsedRealtime)
            assertEquals(sleep, completed.status.bodyVehicleSleepStatus)
            assertNull(completed.status.driveState)
        }
        VehicleFixture(query = TeslaBleQuery.DRIVE_STATE).use { wire ->
            val request = wire.authenticate()
            val completed = wire.conversation.receive(wire.driveResponse(request).toByteArray()) as TeslaBleStep.Complete
            assertNull(completed.status.bodyReceivedAtElapsedRealtime)
            assertNull(completed.status.bodyVehicleSleepStatus)
        }
    }

    private companion object {
        fun send(step: TeslaBleStep): UniversalMessage.RoutableMessage {
            assertTrue(step is TeslaBleStep.Send)
            val payload = (step as TeslaBleStep.Send).payload
            return try { UniversalMessage.RoutableMessage.parseFrom(payload) } finally { payload.fill(0) }
        }
        fun commandStatus(operation: Vcsec.OperationStatus_E): Vcsec.FromVCSECMessage =
            Vcsec.FromVCSECMessage.newBuilder().setCommandStatus(Vcsec.CommandStatus.newBuilder()
                .setOperationStatus(operation)).build()
        fun assertWakeObservation(h: WakeFixture, aead: TeslaBleRxAead, disposition: String) {
            val observed = h.observations.last()
            assertEquals(TeslaBleRxStep.WAKE_RESULT, observed.step)
            assertEquals(aead, observed.aead)
            assertEquals(disposition, observed.disposition)
            assertEquals(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY_VALUE, observed.expectedDomain)
        }
    }
}
