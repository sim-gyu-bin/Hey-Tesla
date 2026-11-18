package com.heytesla.app.vehicle.connection

import com.heytesla.app.vehicle.ble.BleProbeStatus
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.key.discardUnconsumedTeslaBleStep
import com.heytesla.app.vehicle.protocol.TeslaBleFrameDecoder
import com.heytesla.app.vehicle.protocol.TeslaBleFrameException
import com.heytesla.app.vehicle.protocol.TeslaBleProtocolTest
import com.heytesla.app.vehicle.protocol.TeslaBleResponseObservation
import com.heytesla.app.vehicle.protocol.TeslaBleRxAead
import com.heytesla.app.vehicle.protocol.TeslaBleRxStep
import com.heytesla.app.vehicle.protocol.TeslaBleStep

import org.junit.Assert.*
import org.junit.Test

class TeslaGattObservationTest {
    @Test fun terminalResultUsesObservedConnectionProfileCccdRxAndActualRelativeElapsedTimes() {
        val observation = TeslaGattObservation(
            stage = TeslaKeyStage.TIMED_OUT, event = TeslaGattEvent.LOCAL_CLOSED,
            operationStartedElapsedMs = 5_000L, stageStartedElapsedMs = 19_500L, observedElapsedMs = 19_500L,
            connectIssuedElapsedMs = 5_100L, connectReturnedElapsedMs = 5_103L,
            autoConnect = true, connectAction = "CREATE", connectAccepted = true,
            connectionStatus = 8, connectionState = 0, connectionCallbackElapsedMs = 18_000L,
            descriptorRequestStatus = 0, descriptorCallbackStatus = 0, descriptorCallbackElapsedMs = 6_000L,
            firstRxElapsedMs = 6_100L, lastRxElapsedMs = 17_500L,
            connectedObserved = true, serviceFound = true, txFound = true, rxFound = true,
            subscriptionConfirmed = true, rxNotificationCount = 4,
        )
        val result = observation.terminalBleState(BleProbeStatus.TIMED_OUT, "DEADLINE_EXPIRED",
            localClosed = true, gattStatus = 8, primaryGattStatus = 133, phaseElapsedMs = 1_500L)
        assertEquals(BleProbeStatus.TIMED_OUT, result.status)
        assertEquals("DEADLINE_EXPIRED", result.reason)
        assertTrue(result.connected) // 현재는 끊겼어도 이번 작업의 실제 성공 연결을 잃지 않는다.
        assertTrue(result.serviceFound)
        assertTrue(result.txFound)
        assertTrue(result.rxFound)
        assertTrue(result.subscriptionConfirmed)
        assertEquals(4, result.notificationCount)
        assertEquals(14_500L, result.elapsedMs)
        assertEquals(1_100L, result.firstRxElapsedMs)
        assertEquals(1_500L, result.phaseElapsedMs)
        assertEquals(8, result.gattStatus)
        assertEquals(133, result.primaryGattStatus) // 재접속 이전 원인은 최신 callback과 별도다.
        assertNull(result.cleanupGattStatus)
        assertTrue(result.localClosed)
        assertTrue(result.backgroundConnect)
        assertFalse(result.remoteUnsubscribeConfirmed)
    }

    @Test fun noCallbackNoRxAndNoSdkReturnRemainUnobservedEvenWhenCleanupFails() {
        val observation = TeslaGattObservation(
            stage = TeslaKeyStage.CLEANUP_FAILED, event = TeslaGattEvent.CLEANUP_FAILED,
            operationStartedElapsedMs = 20_000L, stageStartedElapsedMs = 20_090L, observedElapsedMs = 20_090L,
            connectIssuedElapsedMs = 20_005L, autoConnect = true, connectAction = "CREATE",
        )
        val result = observation.terminalBleState(BleProbeStatus.CLEANUP_FAILED,
            "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED", localClosed = false,
            gattStatus = null, primaryGattStatus = null, phaseElapsedMs = 20L)
        assertNull(observation.connectAccepted)
        assertNull(observation.connectReturnedElapsedMs)
        assertNull(observation.connectionStatus)
        assertNull(observation.connectionCallbackElapsedMs)
        assertNull(observation.descriptorRequestStatus)
        assertNull(observation.descriptorCallbackStatus)
        assertNull(observation.descriptorCallbackElapsedMs)
        assertNull(observation.firstRxElapsedMs)
        assertNull(observation.lastRxElapsedMs)
        assertNull(result.firstRxElapsedMs)
        assertNull(result.gattStatus)
        assertNull(result.primaryGattStatus)
        assertFalse(result.connected)
        assertFalse(result.serviceFound)
        assertFalse(result.subscriptionConfirmed)
        assertEquals(0, result.notificationCount)
        assertEquals(90L, result.elapsedMs)
        assertFalse(result.localClosed)
    }

    @Test fun longOperationRxCountCannotOverflowLegacyIntSummary() {
        val observation = TeslaGattObservation(
            stage = TeslaKeyStage.CANCELED, event = TeslaGattEvent.LOCAL_CLOSED,
            operationStartedElapsedMs = 1_000L, stageStartedElapsedMs = 50_000L, observedElapsedMs = 50_000L,
            connectedObserved = true, rxNotificationCount = Int.MAX_VALUE.toLong() + 1L,
        )
        val result = observation.terminalBleState(BleProbeStatus.CANCELED, "USER_STOP",
            localClosed = true, gattStatus = null, primaryGattStatus = null, phaseElapsedMs = 5L)
        assertEquals(Int.MAX_VALUE, result.notificationCount)
        assertEquals(Int.MAX_VALUE.toLong() + 1L, observation.rxNotificationCount)
        assertEquals(49_000L, result.elapsedMs)
    }

    @Test fun unsupportedReceiveObservationRemainsNullWhileServiceZeroCanSaturateWithoutOverflow() {
        val unsupported = TeslaGattObservation(TeslaKeyStage.PREPARING_KEY, TeslaGattEvent.STAGE,
            1_000L, 1_000L, 1_000L)
        assertNull(unsupported.rxFrameCount)
        assertNull(unsupported.rxProtocolCount)
        val service = serviceObservation()
        assertEquals(0L, service.rxFrameCount)
        assertEquals(0L, service.rxProtocolCount)
        assertNull(service.rxNotificationFrameCount)
        assertNull(service.rxDecoderIncomplete)
        assertNull(service.rxDecoderFailed)
        assertNull(service.rxResponse)
        val saturated = service.copy(rxFrameCount = Long.MAX_VALUE - 1L, rxProtocolCount = Long.MAX_VALUE)
            .withDecoderResult(2, false, false).beginningProtocol(10_001L)
        assertEquals(Long.MAX_VALUE, saturated.rxFrameCount)
        assertEquals(Long.MAX_VALUE, saturated.rxProtocolCount)
        assertEquals(2, saturated.rxNotificationFrameCount)
        assertNull(saturated.rxResponse)
    }

    @Test fun productionDecoderCountsCompletedFramesAtEverySplitWithoutClaimingProtocolConsumption() {
        val stream = framed(byteArrayOf(1, 2, 3)) + framed(byteArrayOf(4, 5))
        for (split in 1 until stream.size) {
            val decoder = TeslaBleFrameDecoder()
            var observation = serviceObservation()
            val frames = mutableListOf<ByteArray>()
            try {
                for (notification in listOf(stream.copyOfRange(0, split), stream.copyOfRange(split, stream.size))) {
                    try {
                        val completed = decoder.append(notification)
                        frames += completed
                        observation = observation.copy(rxNotificationCount = observation.rxNotificationCount + 1)
                            .withDecoderResult(completed.size, decoder.hasIncompleteFrame(), false)
                    } finally { notification.fill(0) }
                }
                assertEquals(2L, observation.rxNotificationCount)
                assertEquals(2L, observation.rxFrameCount)
                assertEquals(0L, observation.rxProtocolCount)
                assertEquals(false, observation.rxDecoderIncomplete)
                assertEquals(false, observation.rxDecoderFailed)
                assertNull(observation.rxProtocolReceivedElapsedMs)
                assertArrayEquals(byteArrayOf(1, 2, 3), frames[0])
                assertArrayEquals(byteArrayOf(4, 5), frames[1])
            } finally {
                frames.forEach { it.fill(0) }
                decoder.clear()
            }
        }
    }


    @Test fun decoderExceptionDoesNotInventReturnedFramesOrDiscardLastProtocolEvidence() {
        val decoder = TeslaBleFrameDecoder()
        val previous = TeslaBleResponseObservation("ACK_WAITING", 2)
        var observation = serviceObservation().beginningProtocol(10_000L).withProtocolResult(10_005L, previous)
        assertThrows(TeslaBleFrameException::class.java) { decoder.append(byteArrayOf(0x10, 0x01)) }
        observation = observation.withDecoderResult(null, decoder.hasIncompleteFrame(), true)
        assertEquals(0L, observation.rxFrameCount)
        assertEquals(1L, observation.rxProtocolCount)
        assertNull(observation.rxNotificationFrameCount)
        assertEquals(false, observation.rxDecoderIncomplete)
        assertEquals(true, observation.rxDecoderFailed)
        assertSame(previous, observation.rxResponse)
        assertEquals(10_000L, observation.rxProtocolReceivedElapsedMs)
        assertEquals(10_005L, observation.rxProtocolObservedElapsedMs)
        decoder.clear()
    }

    @Test fun nextCallClearsPreviousResponseAndObserverExceptionHasNoNormalReturnResult() {
        TeslaBleProtocolTest.VehicleFixture().use { fixture ->
            val hello = fixture.start()
            val previous = TeslaBleResponseObservation("SESSION_READY", 2)
            var observation = serviceObservation().beginningProtocol(fixture.clock)
                .withProtocolResult(fixture.clock, previous)
            fixture.clock += 10
            observation = observation.beginningProtocol(fixture.clock)
            assertEquals(2L, observation.rxProtocolCount)
            assertNull(observation.rxResponse)
            assertNull(observation.rxProtocolObservedElapsedMs)
            fixture.responseObserver = { throw IllegalStateException("observer") }
            val frame = fixture.helloAck(hello).toByteArray()
            try {
                assertThrows(IllegalStateException::class.java) {
                    fixture.conversation.receive(frame, fixture.clock)
                }
                observation = observation.withProtocolResult(fixture.clock, null)
                assertNull(observation.rxResponse)
                assertEquals(fixture.clock, observation.rxProtocolObservedElapsedMs)
            } finally {
                frame.fill(0)
                fixture.responseObserver = null
            }
        }
    }

    @Test fun canceledConsumerWipesSendOwnershipWithoutTransmitting() {
        TeslaBleProtocolTest.VehicleFixture().use { fixture ->
            val next = fixture.conversation.start(false) as TeslaBleStep.Send
            assertTrue(next.payload.any { it != 0.toByte() })
            discardUnconsumedTeslaBleStep(next)
            assertTrue(next.payload.all { it == 0.toByte() })
        }
    }

    private fun serviceObservation() = TeslaGattObservation(
        TeslaKeyStage.HANDSHAKING, TeslaGattEvent.STAGE, 10_000L, 10_000L, 10_000L,
        rxFrameCount = 0L, rxProtocolCount = 0L,
    )

    private fun framed(payload: ByteArray) =
        byteArrayOf((payload.size ushr 8).toByte(), payload.size.toByte()) + payload
}
