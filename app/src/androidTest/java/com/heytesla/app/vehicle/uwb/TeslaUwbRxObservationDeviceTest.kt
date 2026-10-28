package com.heytesla.app.vehicle.uwb

import com.heytesla.app.vehicle.ble.TeslaNativeGattOwner
import com.heytesla.app.vehicle.ble.TeslaNativeGattRequestKind
import com.heytesla.app.vehicle.ble.TeslaNativeGattStage
import com.heytesla.app.vehicle.key.TeslaBleCredential
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.key.discardUnconsumedTeslaBleStep
import com.heytesla.app.vehicle.key.p256PublicKeyBytes
import com.heytesla.app.vehicle.key.p256PublicKeyFromBytes
import com.heytesla.app.vehicle.protocol.TeslaBleAuthEvidence
import com.heytesla.app.vehicle.protocol.TeslaBleConversation
import com.heytesla.app.vehicle.protocol.TeslaBleFrameDecoder
import com.heytesla.app.vehicle.protocol.TeslaBlePurpose
import com.heytesla.app.vehicle.protocol.TeslaBleResponseObservation
import com.heytesla.app.vehicle.protocol.TeslaBleRxAead
import com.heytesla.app.vehicle.protocol.TeslaBleRxStep
import com.heytesla.app.vehicle.protocol.TeslaBleStep

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tesla.generated.universalmessage.UniversalMessage
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 독립 JCA key·conversation·decoder·owner만 사용한다. runtime/저장 ON/키 저장소/BLE/UWB RF는 열지 않는다. */
@RunWith(AndroidJUnit4::class)
class TeslaUwbRxObservationDeviceTest {
    @Test fun fragmentedAndCoalescedInputWaitsForActualConsumptionAndSealedOwnerRejectsLateCallback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val pair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val credential = object : TeslaBleCredential {
                override fun publicKeyBytes() = p256PublicKeyBytes(pair.public as ECPublicKey)
                override fun deriveSessionKey(vehiclePublicKey: ByteArray): ByteArray {
                    val secret = KeyAgreement.getInstance("ECDH").run {
                        init(pair.private)
                        doPhase(p256PublicKeyFromBytes(vehiclePublicKey), true)
                        generateSecret()
                    }
                    val digest = try { MessageDigest.getInstance("SHA-1").digest(secret) }
                        finally { secret.fill(0) }
                    return try { digest.copyOf(16) } finally { digest.fill(0) }
                }
            }
            val handle = Any()
            val owner = TeslaNativeGattOwner<Any>()
            assertTrue(owner.beginConnect())
            owner.attach(handle)
            val generation = owner.connectionGeneration
            assertTrue(owner.connected(handle, generation))
            for (kind in listOf(TeslaNativeGattRequestKind.SERVICES, TeslaNativeGattRequestKind.MTU,
                TeslaNativeGattRequestKind.CCCD)) {
                val request = checkNotNull(owner.beginRequest(kind, 10_000L))
                assertTrue(owner.recordRequestResult(request, 0))
                assertTrue(owner.completeRequest(handle, request, kind, 10_000L))
            }
            assertTrue(owner.ready())
            var clock = 10_000L
            var pending: TeslaBleResponseObservation? = null
            var observation = TeslaUwbGattObservation(TeslaKeyStage.HANDSHAKING, TeslaUwbGattEvent.STAGE,
                clock, clock, clock, rxFrameCount = 0L, rxProtocolCount = 0L)
            val vin = ByteArray(17) { '0'.code.toByte() }
            TeslaBleConversation(credential, vin, purpose = TeslaBlePurpose.UWB, serviceOwned = true,
                onResponseObservation = { pending = it }, now = { clock }).use { conversation ->
                val send = conversation.start(false) as TeslaBleStep.Send
                val hello = UniversalMessage.RoutableMessage.parseFrom(send.payload)
                discardUnconsumedTeslaBleStep(send)
                val ack = UniversalMessage.RoutableMessage.newBuilder()
                    .setFromDestination(hello.toDestination).setToDestination(hello.fromDestination)
                    .setRequestUuid(hello.uuid)
                    .setSignedMessageStatus(UniversalMessage.MessageStatus.newBuilder()
                        .setOperationStatusValue(0).setSignedMessageFaultValue(0))
                    .build().toByteArray()
                val framed = byteArrayOf((ack.size ushr 8).toByte(), ack.size.toByte()) + ack
                val stream = framed + framed
                val decoder = TeslaBleFrameDecoder()
                val completed = mutableListOf<ByteArray>()
                try {
                    for (notification in listOf(stream.copyOfRange(0, 1), stream.copyOfRange(1, stream.size))) {
                        try {
                            assertTrue(owner.accepts(handle, generation))
                            assertEquals(TeslaNativeGattStage.READY, owner.stage)
                            val frames = decoder.append(notification)
                            completed += frames
                            observation = observation.copy(rxNotificationCount = observation.rxNotificationCount + 1)
                                .withDecoderResult(frames.size, decoder.hasIncompleteFrame(), false)
                            if (completed.isEmpty()) assertEquals(true, observation.rxDecoderIncomplete)
                        } finally { notification.fill(0) }
                    }
                    assertEquals(2L, observation.rxNotificationCount)
                    assertEquals(2L, observation.rxFrameCount)
                    assertEquals(0L, observation.rxProtocolCount)
                    val receivedAt = clock
                    clock += 150
                    for (frame in completed) {
                        observation = observation.beginningProtocol(receivedAt)
                        pending = null
                        val result = try { conversation.receive(frame, receivedAt) }
                            finally { frame.fill(0) }
                        observation = observation.withProtocolResult(clock, pending)
                        assertTrue(result is TeslaBleStep.Waiting)
                        assertEquals(TeslaBleRxStep.WAITING, observation.rxResponse?.step)
                        assertEquals(TeslaBleAuthEvidence.ACKNOWLEDGED, observation.rxResponse?.authEvidence)
                        assertEquals(TeslaBleRxAead.NOT_ATTEMPTED, observation.rxResponse?.aead)
                        assertEquals(receivedAt, observation.rxProtocolReceivedElapsedMs)
                        assertEquals(clock, observation.rxProtocolObservedElapsedMs)
                        discardUnconsumedTeslaBleStep(result)
                    }
                    assertEquals(2L, observation.rxProtocolCount)
                    assertTrue(completed.all { frame -> frame.all { it == 0.toByte() } })
                    val terminal = observation.copy(stage = TeslaKeyStage.CANCELED,
                        event = TeslaUwbGattEvent.LOCAL_CLOSED)
                    owner.seal()
                    conversation.close()
                    decoder.clear()
                    val late = stream.copyOf()
                    try {
                        // native adapter가 쓰는 실제 owner/generation gate. 거절된 바이트는 decoder 분모 밖이다.
                        assertFalse(owner.accepts(handle, generation))
                        assertEquals(2L, terminal.rxNotificationCount)
                        assertEquals(2L, terminal.rxFrameCount)
                        assertEquals(2L, terminal.rxProtocolCount)
                        assertSame(observation.rxResponse, terminal.rxResponse)
                    } finally { late.fill(0) }
                    assertTrue(late.all { it == 0.toByte() })
                } finally {
                    completed.forEach { it.fill(0) }
                    decoder.clear()
                    owner.seal()
                    ack.fill(0)
                    framed.fill(0)
                    stream.fill(0)
                }
            }
            vin.fill(0)
        }
    }
}
