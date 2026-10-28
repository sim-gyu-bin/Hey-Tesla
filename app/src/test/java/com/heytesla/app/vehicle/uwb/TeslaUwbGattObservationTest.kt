package com.heytesla.app.vehicle.uwb

import com.heytesla.app.vehicle.ble.BleProbeStatus
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.key.discardUnconsumedTeslaBleStep
import com.heytesla.app.vehicle.protocol.FiraTestSession
import com.heytesla.app.vehicle.protocol.TeslaBleFrameDecoder
import com.heytesla.app.vehicle.protocol.TeslaBleFrameException
import com.heytesla.app.vehicle.protocol.TeslaBleProtocolTest
import com.heytesla.app.vehicle.protocol.TeslaBlePurpose
import com.heytesla.app.vehicle.protocol.TeslaBleResponseObservation
import com.heytesla.app.vehicle.protocol.TeslaBleRxAead
import com.heytesla.app.vehicle.protocol.TeslaBleRxFiraKind
import com.heytesla.app.vehicle.protocol.TeslaBleRxInputKind
import com.heytesla.app.vehicle.protocol.TeslaBleRxStep
import com.heytesla.app.vehicle.protocol.TeslaBleStep
import com.heytesla.app.vehicle.protocol.TeslaFiraMessage
import com.heytesla.app.vehicle.protocol.TeslaFiraProtocol
import com.heytesla.app.vehicle.protocol.firaTestRequest

import org.junit.Assert.*
import org.junit.Test

class TeslaUwbGattObservationTest {
    @Test fun terminalResultUsesObservedConnectionProfileCccdRxAndActualRelativeElapsedTimes() {
        val observation = TeslaUwbGattObservation(
            stage = TeslaKeyStage.TIMED_OUT, event = TeslaUwbGattEvent.LOCAL_CLOSED,
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
        val observation = TeslaUwbGattObservation(
            stage = TeslaKeyStage.CLEANUP_FAILED, event = TeslaUwbGattEvent.CLEANUP_FAILED,
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
        val observation = TeslaUwbGattObservation(
            stage = TeslaKeyStage.CANCELED, event = TeslaUwbGattEvent.LOCAL_CLOSED,
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
        val unsupported = TeslaUwbGattObservation(TeslaKeyStage.PREPARING_KEY, TeslaUwbGattEvent.STAGE,
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

    @Test fun queuedFramesUseActualConsumerReceiptTimesAndFragmentsPreserveLastProtocolResult() {
        TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB, serviceOwned = true).use { fixture ->
            val hello = fixture.start()
            val decoder = TeslaBleFrameDecoder()
            val handshake = framed(fixture.handshake(hello).toByteArray())
            val request = firaTestRequest(listOf(FiraTestSession(1)))
            var observation = serviceObservation()
            var pending: TeslaBleResponseObservation? = null
            fixture.responseObserver = { pending = it }
            val queued = mutableListOf<ByteArray>()
            try {
                val first = decoder.append(handshake.copyOfRange(0, 1))
                observation = observation.copy(rxNotificationCount = 1)
                    .withDecoderResult(first.size, decoder.hasIncompleteFrame(), false)
                assertEquals(0L, observation.rxFrameCount)
                assertEquals(true, observation.rxDecoderIncomplete)
                val second = decoder.append(handshake.copyOfRange(1, handshake.size - 1))
                observation = observation.copy(rxNotificationCount = 2)
                    .withDecoderResult(second.size, decoder.hasIncompleteFrame(), false)
                assertTrue(second.isEmpty())
                fixture.clock += 3
                val receivedAt = fixture.clock
                queued += decoder.append(handshake.copyOfRange(handshake.size - 1, handshake.size) + framed(request))
                observation = observation.copy(rxNotificationCount = 3, lastRxElapsedMs = receivedAt)
                    .withDecoderResult(queued.size, decoder.hasIncompleteFrame(), false)
                assertEquals(2L, observation.rxFrameCount)
                assertEquals(0L, observation.rxProtocolCount) // TX 대기 중에는 decoder 산출만 있으며 receive를 호출하지 않았다.
                assertEquals(2, observation.rxNotificationFrameCount)

                fixture.clock += 150
                observation = observation.beginningProtocol(receivedAt)
                pending = null
                assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(queued[0], receivedAt))
                queued[0].fill(0)
                observation = observation.withProtocolResult(fixture.clock, pending)
                assertEquals(1L, observation.rxProtocolCount)
                assertEquals(TeslaBleRxStep.UWB_READY, observation.rxResponse?.step)
                assertEquals("SESSION_READY", observation.rxResponse?.disposition)
                assertEquals(receivedAt, observation.rxProtocolReceivedElapsedMs)
                assertEquals(receivedAt + 150, observation.rxProtocolObservedElapsedMs)
                assertNotNull(observation.rxResponse?.fromDomain)

                fixture.clock += 25
                observation = observation.beginningProtocol(receivedAt)
                assertNull(observation.rxResponse)
                assertNull(observation.rxProtocolObservedElapsedMs)
                pending = null
                val next = fixture.conversation.receive(queued[1], receivedAt) as TeslaBleStep.UwbInput
                next.message.close()
                queued[1].fill(0)
                observation = observation.withProtocolResult(fixture.clock, pending)
                assertEquals(2L, observation.rxProtocolCount)
                assertEquals(TeslaBleRxStep.UWB_INPUT, observation.rxResponse?.step)
                assertEquals(TeslaBleRxInputKind.RAW_FIRA, observation.rxResponse?.inputKind)
                assertEquals(TeslaBleRxFiraKind.REQUEST, observation.rxResponse?.firaKind)
                assertEquals(TeslaBleRxAead.NOT_ATTEMPTED, observation.rxResponse?.aead)
                assertNull(observation.rxResponse?.fromDomain) // 직전 인증 envelope를 raw 입력에 이월하지 않는다.
                val lastResponse = observation.rxResponse
                val lastObservedAt = observation.rxProtocolObservedElapsedMs
                fixture.clock += 10
                val fragment = decoder.append(byteArrayOf(0))
                observation = observation.copy(rxNotificationCount = 4, lastRxElapsedMs = fixture.clock)
                    .withDecoderResult(fragment.size, decoder.hasIncompleteFrame(), false)
                val terminal = observation.copy(stage = TeslaKeyStage.TIMED_OUT, event = TeslaUwbGattEvent.LOCAL_CLOSED,
                    observedElapsedMs = fixture.clock + 1)
                assertEquals(2L, terminal.rxFrameCount)
                assertEquals(2L, terminal.rxProtocolCount)
                assertEquals(0, terminal.rxNotificationFrameCount)
                assertEquals(true, terminal.rxDecoderIncomplete)
                assertSame(lastResponse, terminal.rxResponse)
                assertEquals(receivedAt, terminal.rxProtocolReceivedElapsedMs)
                assertEquals(lastObservedAt, terminal.rxProtocolObservedElapsedMs)
                assertTrue(queued.all { frame -> frame.all { it == 0.toByte() } })
            } finally {
                fixture.responseObserver = null
                queued.forEach { it.fill(0) }
                handshake.fill(0)
                request.fill(0)
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
        TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB, serviceOwned = true).use { fixture ->
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

    @Test fun canceledConsumerWipesActualReturnedFiraAndSendOwnershipWithoutTransmitting() {
        TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB, serviceOwned = true).use { fixture ->
            val hello = fixture.start()
            assertEquals(TeslaBleStep.UwbReady, fixture.conversation.receive(fixture.handshake(hello).toByteArray()))
            val raw = firaTestRequest(listOf(FiraTestSession(1, mode = 2, vehicleSts = ByteArray(16) { 7 })))
            fixture.responseObserver = { fixture.conversation.close() }
            try {
                val next = fixture.conversation.receive(raw) as TeslaBleStep.UwbInput
                val session = (next.message as TeslaFiraMessage.Request).sessions.single()
                assertTrue(session.addresses.any { it != 0.toByte() })
                assertTrue(session.vehicleSts.any { it != 0.toByte() })
                discardUnconsumedTeslaBleStep(next) // 실제 probe의 allowed=false/관측 예외 소비 경계와 같은 helper.
                assertTrue(session.addresses.all { it == 0.toByte() })
                assertTrue(session.vehicleSts.all { it == 0.toByte() })
                val afterClose = TeslaFiraProtocol.capabilities()
                assertTrue(fixture.conversation.sendFira(afterClose) is TeslaBleStep.Failed)
                assertTrue(afterClose.all { it == 0.toByte() })
            } finally {
                raw.fill(0)
                fixture.responseObserver = null
            }
        }
        TeslaBleProtocolTest.VehicleFixture(purpose = TeslaBlePurpose.UWB).use { fixture ->
            val next = fixture.conversation.start(false) as TeslaBleStep.Send
            assertTrue(next.payload.any { it != 0.toByte() })
            discardUnconsumedTeslaBleStep(next)
            assertTrue(next.payload.all { it == 0.toByte() })
        }
    }

    private fun serviceObservation() = TeslaUwbGattObservation(
        TeslaKeyStage.HANDSHAKING, TeslaUwbGattEvent.STAGE, 10_000L, 10_000L, 10_000L,
        rxFrameCount = 0L, rxProtocolCount = 0L,
    )

    private fun framed(payload: ByteArray) =
        byteArrayOf((payload.size ushr 8).toByte(), payload.size.toByte()) + payload
}
