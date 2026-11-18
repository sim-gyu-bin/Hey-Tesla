package com.heytesla.app.vehicle.protocol

import com.google.protobuf.ByteString
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec
import org.junit.Assert.*
import org.junit.Test

/** Generic read/authentication observation regressions; no raw packet content is exposed. */
class TeslaBleProtocolObservationTest {
    @Test fun headerCorrelationNeverMixesCurrentAndPreviousRouteOrUuid() {
        TeslaBleProtocolTest.VehicleFixture().use { fixture ->
            val previous = fixture.start()
            val current = parseSend(fixture.conversation.receive(fixture.handshake(previous).toByteArray()))
            val cases = listOf(
                fixture.helloAck(current) to TeslaBleRxRequestCorrelation.CURRENT,
                fixture.helloAck(previous) to TeslaBleRxRequestCorrelation.PREVIOUS,
                fixture.helloAck(current).toBuilder().setRequestUuid(previous.uuid).build() to TeslaBleRxRequestCorrelation.UNMATCHED,
                fixture.helloAck(previous).toBuilder().setRequestUuid(current.uuid).build() to TeslaBleRxRequestCorrelation.UNMATCHED,
                fixture.helloAck(current).toBuilder().clearRequestUuid().build() to TeslaBleRxRequestCorrelation.CURRENT,
                fixture.helloAck(previous).toBuilder().clearRequestUuid().build() to TeslaBleRxRequestCorrelation.PREVIOUS,
                fixture.helloAck(current).toBuilder().clearToDestination().build() to TeslaBleRxRequestCorrelation.UNMATCHED,
            )
            for ((message, correlation) in cases) {
                assertTrue(fixture.conversation.receive(message.toByteArray()) is TeslaBleStep.Waiting)
                val observed = fixture.responseObservations.last()
                assertEquals(correlation, observed.requestCorrelation)
                assertEquals(TeslaBleRxAead.NOT_ATTEMPTED, observed.aead)
                assertNull(observed.bodySummary)
            }
            assertTrue(fixture.conversation.receive(fixture.status(current).toByteArray()) is TeslaBleStep.Complete)
            assertEquals(TeslaBleRxAead.VERIFIED, fixture.responseObservations.last().aead)
        }
    }

    @Test fun currentCorrelationIsNotAeadVerificationAndReplayHasIndependentEvidence() {
        for (tamper in listOf(false, true)) TeslaBleProtocolTest.VehicleFixture().use { fixture ->
            val request = fixture.authenticate()
            val valid = fixture.encryptResponse(request, Vcsec.FromVCSECMessage.getDefaultInstance(), 8)
            val response = if (tamper) valid.toBuilder().setSignatureData(valid.signatureData.toBuilder()
                .setAESGCMResponseData(valid.signatureData.getAESGCMResponseData().toBuilder()
                    .setTag(ByteString.copyFrom(ByteArray(16))))).build() else valid
            if (!tamper) {
                assertTrue(fixture.conversation.receive(valid.toByteArray()) is TeslaBleStep.Waiting)
                assertEquals(TeslaBleRxAead.VERIFIED, fixture.responseObservations.last().aead)
            }
            assertTrue(fixture.conversation.receive(response.toByteArray()) is TeslaBleStep.Failed)
            val observed = fixture.responseObservations.last()
            assertEquals(TeslaBleRxRequestCorrelation.CURRENT, observed.requestCorrelation)
            assertEquals(if (tamper) TeslaBleRxAead.FAILED else TeslaBleRxAead.NOT_ATTEMPTED, observed.aead)
            assertNull(observed.bodySummary)
        }
    }

    @Test fun receiveResetDoesNotMutateEarlierAuthenticationOrCryptoObservation() {
        TeslaBleProtocolTest.VehicleFixture().use { fixture ->
            val hello = fixture.start()
            fixture.conversation.receive(fixture.helloAck(hello).toByteArray())
            val ack = fixture.responseObservations.last()
            assertEquals(TeslaBleAuthEvidence.ACKNOWLEDGED, ack.authEvidence)
            val request = parseSend(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            assertNull(fixture.responseObservations.last().authEvidence)
            val waiting = fixture.encryptResponse(request, Vcsec.FromVCSECMessage.getDefaultInstance(), 7)
            assertTrue(fixture.conversation.receive(waiting.toByteArray()) is TeslaBleStep.Waiting)
            val authenticated = fixture.responseObservations.last()
            assertEquals(TeslaBleRxAead.VERIFIED, authenticated.aead)
            assertTrue(fixture.conversation.receive(byteArrayOf(0x80.toByte())) is TeslaBleStep.Failed)
            val malformed = fixture.responseObservations.last()
            assertNull(malformed.requestCorrelation)
            assertNull(malformed.inputKind)
            assertNull(malformed.authEvidence)
            assertEquals(TeslaBleRxAead.NOT_ATTEMPTED, malformed.aead)
            assertEquals(TeslaBleAuthEvidence.ACKNOWLEDGED, ack.authEvidence)
            assertEquals(TeslaBleRxAead.VERIFIED, authenticated.aead)
        }
    }

    @Test fun identityRotationAndTerminalPathsEraseBothOwnedGenerations() {
        for (terminal in listOf("close", "failure", "complete")) TeslaBleProtocolTest.VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val oldRoute = checkNotNull(identityBytes(fixture.conversation, "route"))
            val oldUuid = checkNotNull(identityBytes(fixture.conversation, "requestUuid"))
            val request = parseSend(fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            assertSame(oldRoute, identityBytes(fixture.conversation, "previousRoute"))
            assertSame(oldUuid, identityBytes(fixture.conversation, "previousRequestUuid"))
            val route = checkNotNull(identityBytes(fixture.conversation, "route"))
            val uuid = checkNotNull(identityBytes(fixture.conversation, "requestUuid"))
            when (terminal) {
                "close" -> fixture.conversation.close()
                "failure" -> assertTrue(fixture.conversation.receive(byteArrayOf(0x80.toByte())) is TeslaBleStep.Failed)
                else -> assertTrue(fixture.conversation.receive(fixture.status(request).toByteArray()) is TeslaBleStep.Complete)
            }
            for (bytes in listOf(oldRoute, oldUuid, route, uuid)) assertTrue(bytes.all { it == 0.toByte() })
            assertNull(identityBytes(fixture.conversation, "previousRoute"))
            assertNull(identityBytes(fixture.conversation, "previousRequestUuid"))
            assertTrue(fixture.conversation.receive(fixture.status(request).toByteArray()) is TeslaBleStep.Failed)
        }
    }

    @Test fun observerlessReadDoesNotRetainPreviousDiagnosticIdentity() {
        TeslaBleProtocolTest.VehicleFixture().use { fixture ->
            TeslaBleConversation(fixture.client, fixture.vin, now = { fixture.clock }).use { conversation ->
                val hello = parseSend(conversation.start(false))
                parseSend(conversation.receive(fixture.handshake(hello).toByteArray()))
                assertNull(identityBytes(conversation, "previousRoute"))
                assertNull(identityBytes(conversation, "previousRequestUuid"))
            }
        }
    }

    @Test fun reentrantObserverFailureNeverReturnsProducedSendOrPrivateExceptionText() {
        TeslaBleProtocolTest.VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val failure = IllegalStateException("synthetic observer cancellation")
            fixture.responseObserver = {
                fixture.conversation.close()
                throw failure
            }
            var delivered: TeslaBleStep? = null
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                delivered = fixture.conversation.receive(fixture.handshake(hello).toByteArray())
            })
            assertNull(delivered)
            fixture.responseObserver = null
            assertTrue(fixture.conversation.receive(byteArrayOf()) is TeslaBleStep.Failed)
        }
    }

    private fun parseSend(step: TeslaBleStep): UniversalMessage.RoutableMessage {
        assertTrue(step is TeslaBleStep.Send)
        val payload = (step as TeslaBleStep.Send).payload
        return try { UniversalMessage.RoutableMessage.parseFrom(payload) } finally { payload.fill(0) }
    }
    private fun identityBytes(conversation: TeslaBleConversation, name: String): ByteArray? =
        TeslaBleConversation::class.java.getDeclaredField(name).run { isAccessible = true; get(conversation) as ByteArray? }
}
