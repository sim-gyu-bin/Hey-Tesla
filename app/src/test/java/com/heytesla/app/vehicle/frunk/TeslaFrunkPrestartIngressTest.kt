package com.heytesla.app.vehicle.frunk

import com.google.protobuf.ByteString
import com.heytesla.app.vehicle.protocol.TeslaBleProtocolTest.FrunkFixture
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.heytesla.app.vehicle.protocol.TeslaBleStep
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec
import org.junit.Assert.*
import org.junit.Test

class TeslaFrunkPrestartIngressTest {
    @Test fun prestartAcrossDriveActionAndBodyNeverCreatesAuthorizationFallbackOrSuccess() {
        FrunkFixture().use { h ->
            val acceptedAt = h.policy.commandAcceptedAtElapsedMs
            val deadline = h.policy.deadline
            val driveHello = send(h.sequence.start())
            assertSame(TeslaBleStep.Ignored, h.sequence.receive(h.wire.handshake(driveHello).toByteArray(), acceptedAt - 1))
            assertFalse(h.policy.driveVerified)
            assertNotNull(h.policy.authorizeActuator())
            val drive = send(h.receive(h.wire.handshake(driveHello)))
            val park = h.wire.driveResponse(drive, h.wire.driveState(timestamp = h.sourceTime()))
            assertSame(TeslaBleStep.Ignored, h.sequence.receive(park.toByteArray(), acceptedAt - 1))
            assertFalse(h.policy.driveVerified)
            assertNotNull(h.policy.authorizeActuator())
            h.wire.clock++
            val actionStarted = h.wire.clock
            val actionHello = send(h.receive(park))
            assertTrue(h.policy.driveVerified)
            assertFalse(h.policy.mayHaveSent)
            // This callback belongs to the preceding DRIVE, not merely before the entire sequence.
            assertSame(TeslaBleStep.Ignored, h.sequence.receive(park.toByteArray(), actionStarted - 1))
            val actionAuth = h.wire.handshake(actionHello)
            assertSame(TeslaBleStep.Ignored, h.sequence.receive(actionAuth.toByteArray(), actionStarted - 1))
            assertFalse(h.sequence.awaitingActionReply)
            assertFalse(h.sequence.responseUnknown)
            assertFalse(h.policy.commandSent)
            val actionStep = h.receive(actionAuth) as TeslaBleStep.Send
            assertTrue(actionStep.actuator)
            val action = send(actionStep)
            h.completeAction(action)
            assertTrue(h.sequence.awaitingActionReply)
            val ack = h.wire.encryptResponse(action, Vcsec.FromVCSECMessage.getDefaultInstance(), 8)
            for (payload in listOf(byteArrayOf(0x80.toByte()), ack.toByteArray())) {
                assertSame(TeslaBleStep.Ignored, h.sequence.receive(payload, actionStarted - 1))
                assertTrue(h.sequence.awaitingActionReply)
                assertFalse(h.sequence.responseUnknown)
                assertFalse(h.policy.commandAcknowledged)
                assertFalse(h.policy.openObserved)
                assertNotNull(h.policy.beginWrite(0, action.serializedSize + 2))
            }
            h.wire.clock++
            val bodyStarted = h.wire.clock
            val bodyHello = send(h.receive(ack))
            assertFalse(h.sequence.awaitingActionReply)
            assertTrue(h.policy.commandAcknowledged)
            assertSame(TeslaBleStep.Ignored, h.sequence.receive(ack.toByteArray(), bodyStarted - 1))
            assertSame(TeslaBleStep.Ignored, h.sequence.receive(h.wire.handshake(bodyHello).toByteArray(), bodyStarted - 1))
            assertFalse(h.policy.openObserved)
            val bodyStep = h.receive(h.wire.handshake(bodyHello)) as TeslaBleStep.Send
            assertFalse(bodyStep.actuator)
            val body = send(bodyStep)
            assertEquals(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS,
                h.wire.decryptRequest(body).informationRequest.informationRequestType)
            val open = h.wire.status(body, frontTrunk = 1)
            assertSame(TeslaBleStep.Ignored, h.sequence.receive(open.toByteArray(), bodyStarted - 1))
            assertFalse(h.policy.openObserved)
            assertEquals(TeslaFrunkResult.UNKNOWN, h.policy.result)
            assertTrue(h.receive(open) is TeslaBleStep.Complete)
            assertEquals(TeslaFrunkResult.OPEN_OBSERVED, h.policy.result)
            assertEquals(acceptedAt, h.policy.commandAcceptedAtElapsedMs)
            assertEquals(deadline, h.policy.deadline)
            assertNotNull(h.policy.beginWrite(0, action.serializedSize + 2))
            assertTrue(h.sequence.start() is TeslaBleStep.Failed)
        }
    }

    @Test fun ignoredFramesCannotRefreshTheOriginalParkReceiptOrConsent() {
        FrunkFixture().use { h ->
            val drive = h.driveRequest()
            h.wire.clock++
            val parkAt = h.wire.clock
            val actionHello = send(h.receive(h.wire.driveResponse(drive,
                h.wire.driveState(timestamp = h.sourceTime()))))
            val deadline = h.policy.deadline
            for (offset in listOf(1L, 4_999L, 5_000L)) {
                h.wire.clock = parkAt + offset
                assertSame(TeslaBleStep.Ignored,
                    h.sequence.receive(h.wire.handshake(actionHello).toByteArray(), parkAt - 1))
                assertEquals(deadline, h.policy.deadline)
                assertFalse(h.policy.mayHaveSent)
            }
            h.wire.clock = parkAt + 5_001L
            assertTrue(h.receive(h.wire.handshake(actionHello)) is TeslaBleStep.Failed)
            assertFalse(h.policy.mayHaveSent)
            assertEquals(TeslaFrunkResult.NOT_SENT, h.policy.result)
        }
    }

    @Test fun ignoredActionFailuresDoNotStartBodyButCurrentFailuresStillAllowOnlyOneReadObservation() {
        for (shape in listOf("malformed", "bad-aead")) FrunkFixture().use { h ->
            val action = h.action()
            val actionStarted = h.wire.clock
            h.completeAction(action)
            val bad = if (shape == "malformed") byteArrayOf(0x80.toByte()) else {
                val ack = h.wire.encryptResponse(action, Vcsec.FromVCSECMessage.getDefaultInstance(), 8)
                ack.toBuilder().setSignatureData(ack.signatureData.toBuilder().setAESGCMResponseData(
                    ack.signatureData.getAESGCMResponseData().toBuilder().setTag(ByteString.copyFrom(ByteArray(16)))))
                    .build().toByteArray()
            }
            assertSame(TeslaBleStep.Ignored, h.sequence.receive(bad, actionStarted - 1))
            assertTrue(h.sequence.awaitingActionReply)
            assertFalse(h.sequence.responseUnknown)
            assertFalse(h.policy.commandAcknowledged)
            assertEquals(TeslaFrunkResult.UNKNOWN, h.policy.result)
            h.wire.clock++
            val bodyHello = send(h.sequence.receive(bad, h.wire.clock))
            assertTrue(bodyHello.hasSessionInfoRequest())
            assertTrue(h.sequence.responseUnknown)
            assertFalse(h.sequence.awaitingActionReply)
            assertTrue(h.sequence.observeAfterMissingReply() is TeslaBleStep.Failed)
            val bodyStep = h.receive(h.wire.handshake(bodyHello)) as TeslaBleStep.Send
            assertFalse(bodyStep.actuator)
            val body = send(bodyStep)
            assertTrue(h.receive(h.wire.status(body, frontTrunk = 1)) is TeslaBleStep.Complete)
            assertTrue(h.policy.openObserved)
            assertEquals(TeslaFrunkResult.UNKNOWN, h.policy.result)
            assertNotNull(h.policy.beginWrite(0, action.serializedSize + 2))
        }
    }

    @Test fun consentExpiryAndCancellationStillRejectPrestartInputWithoutNewBodyOrActuator() {
        for (cancel in listOf(false, true)) FrunkFixture().use { h ->
            val action = h.action()
            h.completeAction(action)
            val receivedAt = h.wire.clock - 1
            if (cancel) h.policy.cancel() else h.wire.clock = h.policy.deadline
            val next = h.sequence.receive(byteArrayOf(0x80.toByte()), receivedAt)
            assertTrue(next is TeslaBleStep.Failed)
            assertFalse(h.sequence.responseUnknown)
            assertFalse(h.policy.commandAcknowledged)
            assertFalse(h.policy.openObserved)
            assertEquals(TeslaFrunkResult.UNKNOWN, h.policy.result)
            assertEquals(TeslaBleQuery.BODY_STATUS, h.sequence.query)
            assertNotNull(h.policy.beginWrite(0, action.serializedSize + 2))
        }
    }

    private companion object {
        fun send(step: TeslaBleStep): UniversalMessage.RoutableMessage {
            assertTrue(step is TeslaBleStep.Send)
            val bytes = (step as TeslaBleStep.Send).payload
            return try { UniversalMessage.RoutableMessage.parseFrom(bytes) } finally { bytes.fill(0) }
        }
    }
}
