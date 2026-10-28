package com.heytesla.app.vehicle.uwb

import com.heytesla.app.ui.vehicleUwbRecentSample
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.protocol.TeslaFiraCapabilities
import com.heytesla.app.vehicle.protocol.TeslaFiraMessage
import com.heytesla.app.vehicle.protocol.TeslaFiraRequest

import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

/**
 * 실제 production ranging/negotiation 상태 머신을 새 platform port 경계에서 실행한다.
 * Android/RF/부모 peer harness 없이 callback queue, native 종료 확인, 등록 정산과 byte 소유권을 검증한다.
 * fake는 플랫폼의 요청/콜백만 제공하고 production의 endpoint/정리 상태를 대신 구현하지 않는다.
 */
class TeslaUwbNativeLifecycleTest {
    @Test fun failuresBeforeNativeStartEraseOwnedBytesAndCompleteWithoutClaimingNativeTeardown() {
        val cases = listOf(
            Fault.PREPARE_THROW to "PARAMETER_BUILD_FAILED",
            Fault.MANAGER_MISSING to "MANAGER_UNAVAILABLE",
            Fault.MANAGER_THROW to "MANAGER_LOOKUP_FAILED",
            Fault.CREATE_NULL to "SESSION_CREATE_NULL",
            Fault.CREATE_THROW to "SESSION_CREATE_FAILED",
        )
        for ((fault, reason) in cases) {
            val h = RangingFixture()
            h.native.fault = fault
            val source = copyParameters(h.parameters)
            h.start()
            assertEquals(fault.name, listOf(reason), h.listener.failures)
            assertEquals(0, h.native.starts)
            assertEquals(0, h.native.closeRequests)
            assertWiped(checkNotNull(h.native.parameters))
            assertParameterBytes(source, h.parameters)
            val completed = ArrayList<Boolean>()
            h.ranging.close { completed.add(it) }
            assertEquals(listOf(false), completed)
            if (h.native.hasCallback) {
                h.native.callback.onOpened()
                h.native.callback.onClosed(91)
                h.queue.runQueued()
                assertEquals(0, h.listener.opened)
                assertTrue(h.listener.closedReasons.isEmpty())
                assertEquals(listOf(reason), h.listener.failures)
            }
        }
    }

    @Test fun initialPermissionDenialNeverPassesSensitiveParametersToTheNativePort() {
        val h = RangingFixture()
        val source = copyParameters(h.parameters)
        h.native.permission = false
        h.start()
        assertEquals(listOf("RANGING_PERMISSION_DENIED"), h.listener.failures)
        assertNull(h.native.parameters)
        assertEquals(0, h.native.starts)
        assertEquals(0, h.native.closeRequests)
        val completed = ArrayList<Boolean>()
        h.ranging.close { completed.add(it) }
        assertEquals(listOf(false), completed)
        assertParameterBytes(source, h.parameters)
    }

    @Test fun permissionRevokedWhileCreateReturnsPreventsNativeStartAndErasesPreparedBytes() {
        val h = RangingFixture()
        h.native.onCreate = { h.native.permission = false }
        h.start()
        assertEquals(listOf("RANGING_PERMISSION_DENIED"), h.listener.failures)
        assertEquals(0, h.native.starts)
        assertEquals(0, h.native.closeRequests)
        assertWiped(checkNotNull(h.native.parameters))
        val completed = ArrayList<Boolean>()
        h.ranging.close { completed.add(it) }
        assertEquals(listOf(false), completed)
    }

    @Test fun bothStartExceptionKindsLatchCleanupFailureEvenAfterAConfirmedLateClose() {
        for ((fault, reason) in listOf(
            Fault.START_THROW to "START_FAILED",
            Fault.START_SECURITY to "START_PERMISSION_DENIED",
        )) {
            val h = RangingFixture()
            h.native.fault = fault
            h.native.onStart = { h.native.callback.onOpened() }
            h.start()
            assertEquals(listOf(reason), h.listener.failures)
            assertEquals(0, h.listener.opened)
            assertEquals(1, h.native.closeRequests)
            val completed = ArrayList<Boolean>()
            h.ranging.close { completed.add(it) }
            h.queue.runQueued()
            assertTrue(completed.isEmpty())
            assertEquals(0, h.listener.opened)
            assertParameterBytes(h.parameters, checkNotNull(h.native.parameters))
            h.confirmClose(27)
            assertEquals(listOf(true), completed)
            assertEquals(listOf(27), h.listener.closedReasons)
            assertWiped(checkNotNull(h.native.parameters))
            h.ranging.close { completed.add(it) }
            assertEquals(listOf(true, true), completed)
        }
    }

    @Test fun synchronousCreateStartAndCloseCallbacksNeverDeliverInline() {
        val h = RangingFixture()
        h.native.onCreate = { h.native.callback.onOpened() }
        h.native.onStart = {
            h.native.callback.onStarted(h.native.peerIds[1], TeslaUwbNativeRanging.UWB)
            h.native.result(peerIndex = 1, meters = 2.75)
            h.native.callback.onStopped(h.native.peerIds[1], TeslaUwbNativeRanging.UWB)
        }
        h.start()
        assertTrue(h.listener.events.isEmpty())
        h.queue.runQueued()
        assertEquals(listOf("opened", "started:1", "distance:1", "stopped:1"), h.listener.events)
        assertEquals(2.75, h.listener.samples.single().meters, 0.0)
        h.native.onClose = { h.native.callback.onClosed(42) }
        val completed = ArrayList<Boolean>()
        h.ranging.close { completed.add(it) }
        assertTrue(completed.isEmpty())
        assertTrue(h.listener.closedReasons.isEmpty())
        h.queue.runQueued()
        assertEquals(listOf(false), completed)
        assertEquals(listOf(42), h.listener.closedReasons)
        assertWiped(checkNotNull(h.native.parameters))
    }

    @Test fun synchronousOpenFailureDuringCreateIsConsumedOnlyAfterStartReturns() {
        val h = RangingFixture()
        h.native.onCreate = { h.native.callback.onOpenFailed(73) }
        h.start()
        assertTrue(h.listener.events.isEmpty())
        assertEquals(1, h.native.starts)
        h.queue.runQueued()
        assertEquals(listOf("OPEN_FAILED_73"), h.listener.failures)
        assertEquals(1, h.native.closeRequests)
        val completed = ArrayList<Boolean>()
        h.ranging.close { completed.add(it) }
        assertTrue(completed.isEmpty())
        h.confirmClose()
        assertEquals(listOf(false), completed)
    }

    @Test fun permissionIsCheckedAtQueuedStartedResultAndStoppedDispatchRatherThanReceipt() {
        for (kind in listOf("started", "result", "stopped")) {
            val h = RangingFixture()
            h.start()
            h.native.callback.onOpened()
            h.queue.runQueued()
            when (kind) {
                "started" -> h.native.callback.onStarted(h.native.peerIds[0], TeslaUwbNativeRanging.UWB)
                "result" -> h.native.result()
                "stopped" -> h.native.callback.onStopped(h.native.peerIds[0], TeslaUwbNativeRanging.UWB)
            }
            h.queue.elapse(10_000L)
            h.native.permission = false
            assertTrue(h.listener.failures.isEmpty())
            h.queue.runQueued()
            assertEquals(kind, listOf("RANGING_PERMISSION_DENIED"), h.listener.failures)
            assertTrue(h.listener.started.isEmpty())
            assertTrue(h.listener.samples.isEmpty())
            assertTrue(h.listener.stopped.isEmpty())
            val completed = ArrayList<Boolean>()
            h.ranging.close { completed.add(it) }
            assertTrue(completed.isEmpty())
            h.confirmClose()
            assertEquals(listOf(false), completed)
        }
    }

    @Test fun nativeReceivesIndependentBytesAndOnlyConfirmedCloseErasesItsOwnedCopies() {
        val h = RangingFixture()
        val expected = copyParameters(h.parameters)
        h.start()
        val owned = checkNotNull(h.native.parameters)
        assertNotSame(h.parameters.localAddress, owned.localAddress)
        assertNotSame(h.parameters.sessionKeyInfo, owned.sessionKeyInfo)
        h.parameters.peerAddresses.indices.forEach {
            assertNotSame(h.parameters.peerAddresses[it], owned.peerAddresses[it])
        }
        h.parameters.localAddress.fill(61)
        h.parameters.sessionKeyInfo.fill(62)
        h.parameters.peerAddresses.forEach { it.fill(63) }
        val callerAfterMutation = copyParameters(h.parameters)
        assertParameterBytes(expected, owned)
        val completed = ArrayList<Boolean>()
        h.ranging.close { completed.add(it) }
        assertTrue(completed.isEmpty())
        assertParameterBytes(expected, owned)
        h.confirmClose()
        assertEquals(listOf(false), completed)
        assertWiped(owned)
        assertParameterBytes(callerAfterMutation, h.parameters)
    }

    @Test fun queuedResultsRetainCallbackReceiptTimeAndUnmodifiedFrameworkTimestamp() {
        for (delay in listOf(2_000L, 10_000L)) {
            val h = RangingFixture()
            h.start()
            val receivedAt = h.queue.now
            val frameworkTimestamp = -987_654L
            h.native.callback.onResults(h.native.peerIds[1], TeslaUwbNativeRanging.UWB,
                3.125, 2, frameworkTimestamp, null, null, null, null)
            assertTrue(h.listener.samples.isEmpty())
            h.queue.elapse(delay)
            h.queue.runQueued()
            val sample = h.listener.samples.single()
            assertEquals(1, sample.peerIndex)
            assertEquals(receivedAt, sample.receivedAtElapsedMs)
            assertEquals(receivedAt + delay, h.queue.now)
            assertTrue(sample.receivedAtElapsedMs < h.queue.now)
            assertEquals(frameworkTimestamp, sample.frameworkTimestampMillis)
            h.ranging.close { assertFalse(it) }
            h.confirmClose()
        }
    }

    @Test fun delayedResultsPreservePeerCallbackOrderAndDuplicateOutOfOrderSourceTimestamps() {
        val h = RangingFixture()
        h.start()
        val receivedAt = h.queue.now
        h.native.callback.onResults(h.native.peerIds[1], TeslaUwbNativeRanging.UWB,
            3.0, 2, 900L, null, null, null, null)
        h.queue.elapse(250L)
        h.native.callback.onResults(h.native.peerIds[0], TeslaUwbNativeRanging.UWB,
            4.0, 1, 100L, null, null, null, null)
        h.queue.elapse(250L)
        h.native.callback.onResults(h.native.peerIds[0], TeslaUwbNativeRanging.UWB,
            4.5, 1, 100L, null, null, null, null)
        h.queue.elapse(10_000L)
        h.queue.runQueued()
        assertEquals(listOf("distance:1", "distance:0", "distance:0"), h.listener.events)
        assertEquals(listOf(3.0, 4.0, 4.5), h.listener.samples.map { it.meters })
        assertEquals(listOf(receivedAt, receivedAt + 250L, receivedAt + 500L),
            h.listener.samples.map { it.receivedAtElapsedMs })
        assertEquals(listOf(900L, 100L, 100L), h.listener.samples.map { it.frameworkTimestampMillis })
        assertTrue(h.listener.invalid.isEmpty())
        h.ranging.close { assertFalse(it) }
        h.confirmClose()
    }

    @Test fun peerMappingAndIndependentAngleValidationAreVisibleThroughActualRanging() {
        val h = RangingFixture()
        h.start()
        h.native.callback.onResults(h.native.peerIds[1], TeslaUwbNativeRanging.UWB,
            3.125, 2, 987_654L, -2.5, 1, 0.75, 99)
        h.native.callback.onResults(h.native.peerIds[0], TeslaUwbNativeRanging.UWB,
            4.5, 1, 123L, Double.NaN, null, null, null)
        assertTrue(h.listener.samples.isEmpty())
        h.queue.runQueued()
        val first = h.listener.samples[0]
        assertEquals(1, first.peerIndex)
        assertEquals(3.125, first.meters, 0.0)
        assertEquals(2, first.confidence)
        assertEquals(-2.5, checkNotNull(first.azimuthRaw), 0.0)
        assertEquals(1, first.azimuthConfidence)
        assertEquals(0.75, checkNotNull(first.elevationRaw), 0.0)
        assertNull(first.elevationConfidence)
        val next = h.listener.samples[1]
        assertEquals(0, next.peerIndex)
        assertNull(next.azimuthRaw)
        assertNull(next.azimuthConfidence)
        assertNull(next.elevationRaw)
        assertNull(next.elevationConfidence)
        h.ranging.close { assertFalse(it) }
        h.confirmClose()
    }

    @Test fun unknownPeerAndInvalidResultNeverReachTheDistanceConsumer() {
        val h = RangingFixture()
        h.start()
        h.native.callback.onStarted(UUID(0L, 19L), TeslaUwbNativeRanging.UWB)
        h.native.callback.onResults(h.native.peerIds[0], TeslaUwbNativeRanging.UWB,
            Double.NaN, 2, 31L, null, null, null, null)
        h.native.callback.onStopped(h.native.peerIds[0], TeslaUwbNativeRanging.UWB + 1)
        assertTrue(h.listener.invalid.isEmpty())
        h.queue.runQueued()
        assertEquals(listOf("UNKNOWN_PEER", "INVALID_DISTANCE", "UNEXPECTED_TECHNOLOGY"), h.listener.invalid)
        assertTrue(h.listener.samples.isEmpty())
        assertTrue(h.listener.started.isEmpty())
        assertTrue(h.listener.stopped.isEmpty())
        h.ranging.close { assertFalse(it) }
        h.confirmClose()
    }

    @Test fun closeReturnIsNotCompletionAndReentrantThrowingCompletionsAreSettledExactlyOnce() {
        val h = RangingFixture()
        h.start()
        val completed = ArrayList<String>()
        h.ranging.close { failed ->
            completed.add("first:$failed")
            h.ranging.close { completed.add("reentrant:$it") }
            throw IllegalStateException("consumer completion failure")
        }
        h.ranging.close { completed.add("second:$it") }
        assertEquals(1, h.native.closeRequests)
        assertTrue(completed.isEmpty())
        h.native.callback.onClosed(16)
        h.native.callback.onClosed(17)
        assertTrue(completed.isEmpty())
        h.queue.runQueued()
        assertEquals(listOf("first:false", "reentrant:false", "second:false"), completed)
        assertEquals(listOf(16), h.listener.closedReasons)
        h.ranging.close { completed.add("after:$it") }
        assertEquals(listOf("first:false", "reentrant:false", "second:false", "after:false"), completed)
        h.queue.advanceBy(5_000L)
        assertTrue(h.listener.failures.isEmpty())
    }

    @Test fun fiveSecondCloseTimeoutErasesBytesLatchesFailureAndSealsEveryLateCallback() {
        val h = RangingFixture()
        h.start()
        val completed = ArrayList<Boolean>()
        h.ranging.close { completed.add(it) }
        h.queue.advanceBy(4_999L)
        assertTrue(completed.isEmpty())
        assertTrue(h.listener.failures.isEmpty())
        h.queue.advanceBy(1L)
        assertEquals(listOf(true), completed)
        assertEquals(listOf("CLOSE_TIMEOUT"), h.listener.failures)
        assertWiped(checkNotNull(h.native.parameters))
        val events = h.listener.events.toList()
        h.native.callback.onOpened()
        h.native.callback.onStarted(h.native.peerIds[0], TeslaUwbNativeRanging.UWB)
        h.native.result()
        h.native.callback.onStopped(h.native.peerIds[0], TeslaUwbNativeRanging.UWB)
        h.native.callback.onOpenFailed(29)
        h.native.callback.onClosed(30)
        h.native.callback.onClosed(31)
        h.queue.runQueued()
        assertEquals(events, h.listener.events)
        h.ranging.close { completed.add(it) }
        assertEquals(listOf(true, true), completed)
    }

    @Test fun closeCallbackAtElapsedDeadlineCannotWinByRunningBeforeTheDelayedTimeoutTask() {
        val h = RangingFixture()
        h.start()
        val completed = ArrayList<Boolean>()
        h.ranging.close { completed.add(it) }
        // Model elapsed time advancing while the owner queue does not dispatch delayed tasks.
        h.queue.elapse(5_000L)
        h.native.callback.onClosed(81)
        h.queue.runQueued()
        assertEquals(listOf(true), completed)
        assertEquals(listOf(81), h.listener.closedReasons)
        h.queue.runDue()
        assertTrue(h.listener.failures.isEmpty())
    }

    @Test fun closeExceptionRemainsLatchedEvenWhenOnClosedWasSynchronouslyQueuedFirst() {
        val h = RangingFixture()
        h.start()
        h.native.onClose = { h.native.callback.onClosed(55) }
        h.native.closeThrows = true
        val completed = ArrayList<Boolean>()
        h.ranging.close { completed.add(it) }
        assertTrue(completed.isEmpty())
        h.queue.runQueued()
        assertEquals(listOf(true), completed)
        assertEquals(listOf(55), h.listener.closedReasons)
        h.native.callback.onClosed(56)
        h.queue.runQueued()
        h.ranging.close { completed.add(it) }
        assertEquals(listOf(true, true), completed)
        assertEquals(listOf(55), h.listener.closedReasons)
        assertWiped(checkNotNull(h.native.parameters))
    }

    @Test fun rejectedCallbackPostThrowsToNativeWithoutInlineDelivery() {
        val h = RangingFixture()
        h.start()
        h.queue.rejectPosts = true
        expectThrows<RejectedExecutionException> { h.native.result() }
        assertTrue(h.listener.events.isEmpty())
        h.queue.rejectPosts = false
        val completed = ArrayList<Boolean>()
        h.ranging.close { completed.add(it) }
        assertTrue(completed.isEmpty())
        h.confirmClose()
        assertEquals(listOf(false), completed)
        assertTrue(h.listener.samples.isEmpty())
    }

    @Test fun rejectedSynchronousStartCallbackCannotBecomeAnOpenedOrCleanSession() {
        val h = RangingFixture()
        h.native.onStart = { h.native.callback.onOpened() }
        h.queue.rejectPosts = true
        h.start()
        assertEquals(listOf("START_FAILED"), h.listener.failures)
        assertEquals(0, h.listener.opened)
        val completed = ArrayList<Boolean>()
        h.ranging.close { completed.add(it) }
        assertTrue(completed.isEmpty())
        h.queue.rejectPosts = false
        h.confirmClose()
        assertEquals(listOf(true), completed)
    }

    @Test fun rejectedSynchronousCloseCallbackCannotConvertCloseReturnIntoCompletion() {
        val h = RangingFixture()
        h.start()
        h.native.onClose = { h.native.callback.onClosed(65) }
        h.queue.rejectPosts = true
        val completed = ArrayList<Boolean>()
        h.ranging.close { completed.add(it) }
        assertTrue(completed.isEmpty())
        assertTrue(h.listener.closedReasons.isEmpty())
        h.queue.rejectPosts = false
        h.queue.advanceBy(5_000L)
        assertEquals(listOf(true), completed)
        assertEquals(listOf("CLOSE_TIMEOUT"), h.listener.failures)
        assertWiped(checkNotNull(h.native.parameters))
    }

    @Test fun rejectedCloseTimeoutSchedulingCompletesAsFailedRatherThanWaitingForever() {
        val h = RangingFixture()
        h.start()
        h.queue.rejectDelays = true
        val completed = ArrayList<Boolean>()
        h.ranging.close { completed.add(it) }
        assertEquals(1, h.native.closeRequests)
        assertEquals(listOf(true), completed)
        assertWiped(checkNotNull(h.native.parameters))
        h.native.callback.onClosed(66)
        h.queue.runQueued()
        h.ranging.close { completed.add(it) }
        assertEquals(listOf(true, true), completed)
        assertTrue(h.listener.closedReasons.isEmpty())
    }

    @Test fun synchronousCapabilitiesAreQueuedBeforeNegotiationCanSendOrCreateNative() {
        val h = NegotiationFixture()
        h.platform.onRegister = { h.platform.emit(Result.success(capabilitySnapshot())) }
        h.coordinator.prepare()
        assertEquals(0, h.ready)
        assertNull(h.state.azimuthSupported)
        assertTrue(h.frames.isEmpty())
        assertTrue(h.platform.natives.isEmpty())
        h.queue.runQueued()
        assertEquals(1, h.ready)
        assertEquals(true, h.state.azimuthSupported)
        assertEquals(false, h.state.elevationSupported)
        h.coordinator.authenticated()
        assertEquals(TeslaUwbStage.WAITING_FOR_VEHICLE, h.state.stage)
        assertTrue(h.platform.natives.isEmpty())
        h.coordinator.close { h.cleanup.add(it) }
        assertEquals(listOf(false), h.cleanup)
        assertFalse(h.platform.registrationLive)
    }

    @Test fun capabilityPreparationAndTimeoutQueueFailuresDoNotAttemptRegistration() {
        for (mode in listOf("unavailable", "prepareThrow", "delayRejected")) {
            val h = NegotiationFixture()
            when (mode) {
                "unavailable" -> h.platform.available = false
                "prepareThrow" -> h.platform.prepareThrows = true
                "delayRejected" -> h.queue.rejectDelays = true
            }
            h.coordinator.prepare()
            val reason = if (mode == "unavailable") "BACKEND_UNAVAILABLE" else "CAPABILITIES_REQUEST_FAILED"
            assertEquals(listOf(TeslaKeyStage.FAILED to reason), h.finishes)
            assertEquals(listOf(false), h.cleanup)
            assertFalse(h.platform.registrationLive)
            assertEquals(0, h.platform.unregisterCalls)
            assertEquals(0, h.ready)
            assertTrue(h.platform.natives.isEmpty())
        }
    }

    @Test fun cancellationDuringRegistrationWaitsForItsReturnBeforeUnregisterAndCompletion() {
        for (registerThrows in listOf(false, true)) for (unregisterThrows in listOf(false, true)) {
            val h = NegotiationFixture()
            h.platform.registerThrows = registerThrows
            h.platform.unregisterThrows = unregisterThrows
            val failed = registerThrows || unregisterThrows
            h.platform.onRegister = {
                h.coordinator.close { h.cleanup.add(it) }
                h.coordinator.close { h.cleanup.add(it) }
                assertTrue(h.cleanup.isEmpty())
                assertEquals(0, h.platform.unregisterCalls)
                h.platform.emit(Result.success(capabilitySnapshot()))
            }
            h.coordinator.prepare()
            assertFalse(h.platform.unregisteredWhileRegistering)
            assertEquals(unregisterThrows, h.platform.registrationLive)
            assertEquals(listOf(failed, failed), h.cleanup)
            assertEquals(1, h.platform.unregisterCalls)
            h.queue.runQueued()
            assertEquals(0, h.ready)
            assertTrue(h.finishes.isEmpty())
            assertTrue(h.frames.isEmpty())
            h.coordinator.close { h.cleanup.add(it) }
            assertEquals(listOf(failed, failed, failed), h.cleanup)
            assertEquals(1, h.platform.unregisterCalls)
        }
    }

    @Test fun registrationThrowAfterAQueuedSuccessStillFailsAndUnregistersTheAttemptExactlyOnce() {
        for (emitBeforeThrow in listOf(false, true)) for (unregisterThrows in listOf(false, true)) {
            val h = NegotiationFixture()
            h.platform.registerThrows = true
            h.platform.unregisterThrows = unregisterThrows
            h.platform.onRegister = {
                if (emitBeforeThrow) h.platform.emit(Result.success(capabilitySnapshot()))
            }
            h.coordinator.prepare()
            assertEquals(listOf(TeslaKeyStage.FAILED to "CAPABILITIES_REQUEST_FAILED"), h.finishes)
            assertEquals(listOf(true), h.cleanup)
            assertFalse(h.platform.unregisteredWhileRegistering)
            assertEquals(unregisterThrows, h.platform.registrationLive)
            assertEquals(1, h.platform.unregisterCalls)
            h.queue.runQueued()
            assertEquals(0, h.ready)
            assertTrue(h.frames.isEmpty())
            h.coordinator.close { h.cleanup.add(it) }
            assertEquals(listOf(true, true), h.cleanup)
            assertEquals(1, h.platform.unregisterCalls)
        }
    }

    @Test fun unregisterExceptionIsLatchedAndLateCapabilitiesCannotReviveCancelledNegotiation() {
        val h = NegotiationFixture()
        h.coordinator.prepare()
        h.platform.unregisterThrows = true
        h.coordinator.close { h.cleanup.add(it) }
        assertEquals(listOf(true), h.cleanup)
        assertTrue(h.platform.registrationLive)
        h.platform.emit(Result.success(capabilitySnapshot()))
        h.platform.emit(Result.failure(IllegalStateException("late platform failure")))
        h.queue.runQueued()
        assertEquals(0, h.ready)
        assertTrue(h.finishes.isEmpty())
        assertTrue(h.frames.isEmpty())
        h.coordinator.close { h.cleanup.add(it) }
        assertEquals(listOf(true, true), h.cleanup)
        assertEquals(1, h.platform.unregisterCalls)
    }

    @Test fun cancellationAfterCapabilityReceiptButBeforeDispatchSuppressesTheQueuedReadiness() {
        val h = NegotiationFixture()
        h.coordinator.prepare()
        h.platform.emit(Result.success(capabilitySnapshot()))
        h.coordinator.close { h.cleanup.add(it) }
        assertEquals(listOf(false), h.cleanup)
        h.queue.runQueued()
        h.queue.advanceBy(10_000L)
        assertEquals(0, h.ready)
        assertTrue(h.finishes.isEmpty())
        assertFalse(h.platform.registrationLive)
        assertTrue(h.platform.natives.isEmpty())
    }

    @Test fun rejectedCapabilityCallbackPostIsLatchedAndReadinessWatchdogSettlesCleanup() {
        val h = NegotiationFixture()
        h.coordinator.prepare()
        h.queue.rejectPosts = true
        h.platform.emit(Result.success(capabilitySnapshot()))
        assertTrue(h.finishes.isEmpty())
        assertTrue(h.cleanup.isEmpty())
        assertEquals(0, h.ready)
        h.queue.rejectPosts = false
        h.coordinator.checkReadiness()
        assertEquals(listOf(TeslaKeyStage.FAILED to "CAPABILITIES_CALLBACK_FAILED"), h.finishes)
        assertEquals(listOf(true), h.cleanup)
        assertFalse(h.platform.registrationLive)
        h.queue.advanceBy(10_000L)
        assertEquals(1, h.finishes.size)
        assertEquals(0, h.ready)
    }

    @Test fun failedCapabilityResultIsQueuedAndStillUnregistersASuccessfulRegistration() {
        val h = NegotiationFixture()
        h.platform.onRegister = { h.platform.emit(Result.failure(IllegalStateException("capability failure"))) }
        h.coordinator.prepare()
        assertTrue(h.finishes.isEmpty())
        assertTrue(h.cleanup.isEmpty())
        h.queue.runQueued()
        assertEquals(listOf(TeslaKeyStage.FAILED to "CAPABILITIES_INVALID"), h.finishes)
        assertEquals(listOf(false), h.cleanup)
        assertFalse(h.platform.registrationLive)
        assertEquals(0, h.ready)
    }

    @Test fun capabilitiesAtTheElapsedDeadlineCannotBecomeReadyBeforeTheTimerDispatches() {
        val h = NegotiationFixture()
        h.coordinator.prepare()
        h.queue.elapse(10_000L)
        h.platform.emit(Result.success(capabilitySnapshot()))
        h.queue.runQueued()
        assertEquals(0, h.ready)
        assertEquals(listOf(TeslaKeyStage.TIMED_OUT to "CAPABILITIES_TIMEOUT"), h.finishes)
        assertEquals(listOf(false), h.cleanup)
        assertFalse(h.platform.registrationLive)
        h.queue.runDue()
        assertEquals(1, h.finishes.size)
    }

    @Test fun actualNegotiationStartsActualRangingOnlyAfterResponseAndWaitsForEveryNativeClose() {
        val h = NegotiationFixture()
        h.prepareReady()
        val requests = h.request(byteArrayOf(17, 18, 19, 20), byteArrayOf(21, 22))
        requests.forEach { assertTrue(it.addresses.all { byte -> byte == 0.toByte() }) }
        assertEquals(TeslaUwbStage.STARTING, h.state.stage)
        assertFalse(h.state.backendOpened)
        assertTrue(h.platform.natives.isEmpty())
        h.frames.single().complete()
        val first = h.platform.natives[0]
        val second = h.platform.natives[1]
        assertArrayEquals(byteArrayOf(17, 18), checkNotNull(first.parameters).peerAddresses[0])
        assertArrayEquals(byteArrayOf(19, 20), checkNotNull(first.parameters).peerAddresses[1])
        assertArrayEquals(byteArrayOf(21, 22), checkNotNull(second.parameters).peerAddresses.single())
        assertTrue(checkNotNull(first.parameters).angleOfArrivalNeeded)
        first.callback.onOpened()
        first.callback.onStarted(first.peerIds[1], TeslaUwbNativeRanging.UWB)
        first.result(peerIndex = 1, meters = 1.5)
        second.callback.onOpened()
        second.result(meters = 4.25)
        assertFalse(h.state.backendOpened)
        assertEquals(0L, h.state.validDistanceCount)
        h.queue.runQueued()
        assertTrue(h.state.backendOpened)
        assertEquals(TeslaUwbStage.RANGING, h.state.stage)
        assertEquals(setOf(1, 2), h.state.livePeerIndices)
        assertEquals(listOf(1, 2), h.state.samples.map { it.peerIndex })
        assertEquals(4.25, checkNotNull(h.state.lastReceivedSample).meters, 0.0)
        val historicalSamples = h.state.samples.toList()
        h.coordinator.close { h.cleanup.add(it) }
        assertTrue(h.state.livePeerIndices.isEmpty())
        assertTrue(h.cleanup.isEmpty())
        assertFalse(h.platform.registrationLive)
        assertEquals(1, h.frames.size) // Cancel must not produce a new stopped frame.
        first.callback.onClosed(61)
        h.queue.runQueued()
        assertTrue(h.cleanup.isEmpty())
        assertWiped(checkNotNull(first.parameters))
        assertArrayEquals(byteArrayOf(21, 22), checkNotNull(second.parameters).peerAddresses.single())
        second.callback.onClosed(62)
        h.queue.runQueued()
        assertEquals(listOf(false), h.cleanup)
        assertWiped(checkNotNull(second.parameters))
        first.result(meters = 99.0)
        second.callback.onClosed(63)
        h.platform.emit(Result.success(capabilitySnapshot()))
        h.queue.runQueued()
        assertEquals(historicalSamples, h.state.samples)
        assertTrue(h.state.livePeerIndices.isEmpty())
        assertEquals(62, h.state.closedReason)
        assertEquals(1, h.ready)
        assertEquals(listOf(false), h.cleanup)
        assertTrue(h.finishes.isEmpty())
    }

    @Test fun actualNegotiationMapsNativeAdmissionAndStartFailuresAndPreservesCleanupUncertainty() {
        for (fault in listOf(Fault.PREPARE_THROW, Fault.MANAGER_MISSING, Fault.MANAGER_THROW,
            Fault.CREATE_NULL, Fault.CREATE_THROW, Fault.START_THROW, Fault.START_SECURITY)) {
            val h = NegotiationFixture()
            h.platform.nativeFactory = { Native().apply { this.fault = fault } }
            h.prepareReady()
            h.request(byteArrayOf(33, 34))
            h.frames.single().complete()
            val native = h.platform.natives.single()
            val startAttempted = fault == Fault.START_THROW || fault == Fault.START_SECURITY
            val reason = if (startAttempted) "NATIVE_START_FAILED" else "NATIVE_BACKEND_FAILED"
            assertEquals(fault.name, listOf(TeslaKeyStage.FAILED to reason), h.finishes)
            assertTrue(h.state.livePeerIndices.isEmpty())
            assertFalse(h.platform.registrationLive)
            if (startAttempted) {
                assertTrue(h.cleanup.isEmpty())
                native.callback.onClosed(72)
                h.queue.runQueued()
                assertEquals(listOf(true), h.cleanup)
            } else {
                assertEquals(listOf(false), h.cleanup)
            }
            assertWiped(checkNotNull(native.parameters))
            assertEquals(1, h.frames.size)
        }
    }

    @Test fun actualServiceUsesDelayedReceiptForNotificationFreshnessAndSilenceDeadline() {
        for (delay in listOf(2_000L, 2_001L, 10_000L)) {
            val h = NegotiationFixture(serviceOwned = true)
            h.prepareReady()
            h.request(byteArrayOf(35, 36))
            h.frames.single().complete()
            val native = h.platform.natives.single()
            val receivedAt = h.queue.now
            native.result(meters = 0.75)
            h.queue.elapse(delay)
            h.queue.runQueued()
            val sample = checkNotNull(h.state.lastReceivedSample)
            assertEquals(receivedAt, sample.receivedAtElapsedMs)
            assertEquals(246_813L, sample.frameworkTimestampMillis)
            assertTrue(sample.receivedAtElapsedMs < h.queue.now)
            assertEquals(1L, h.state.validDistanceCount)
            if (delay == 2_000L) {
                assertEquals(sample, vehicleUwbRecentSample(h.state, h.queue.now))
            } else {
                assertNull(vehicleUwbRecentSample(h.state, h.queue.now))
            }
            assertEquals(0, native.closeRequests)
            if (delay < 10_000L) {
                h.queue.advanceBy(10_000L - delay - 1L)
                assertEquals(0, native.closeRequests)
                assertNull(vehicleUwbRecentSample(h.state, h.queue.now))
                h.queue.advanceBy(1L)
            } else {
                // The first receipt is inside the initial 60-second window, but its
                // receipt-based silence deadline is already due; dispatch grants no extra time.
                h.queue.runDue()
            }
            assertEquals(receivedAt + 10_000L, h.queue.now)
            assertEquals(1, native.closeRequests)
            assertEquals(TeslaUwbStage.CLEANING_UP, h.state.stage)
            assertEquals("DISTANCE_RECEIVE_TIMEOUT", h.state.reason)
            assertTrue(h.state.livePeerIndices.isEmpty())
            native.result(meters = 99.0)
            h.queue.runQueued()
            assertEquals(1L, h.state.validDistanceCount)
            assertEquals(sample, h.state.lastReceivedSample)
            assertNull(vehicleUwbRecentSample(h.state, h.queue.now))
            native.callback.onClosed(74)
            h.queue.runQueued()
            assertEquals(2, h.frames.size)
            h.frames.last().complete()
            assertEquals(listOf(TeslaKeyStage.TIMED_OUT to "DISTANCE_RECEIVE_TIMEOUT"), h.finishes)
            assertEquals(listOf(false), h.cleanup)
        }
    }

    @Test fun actualServiceCloseBeforeDispatchSealsQueuedAndLateDistances() {
        val h = NegotiationFixture(serviceOwned = true)
        h.prepareReady()
        h.request(byteArrayOf(35, 36))
        h.frames.single().complete()
        val native = h.platform.natives.single()
        native.result(meters = 0.75)
        h.queue.elapse(2_000L)
        h.coordinator.close { h.cleanup.add(it) }
        h.queue.runQueued()
        assertEquals(0L, h.state.validDistanceCount)
        assertNull(h.state.lastReceivedSample)
        assertTrue(h.state.samples.isEmpty())
        assertTrue(h.state.livePeerIndices.isEmpty())
        assertNull(vehicleUwbRecentSample(h.state, h.queue.now))
        assertTrue(h.cleanup.isEmpty())
        native.callback.onClosed(74)
        h.queue.runQueued()
        native.result(meters = 99.0)
        h.queue.advanceBy(10_000L)
        assertEquals(0L, h.state.validDistanceCount)
        assertNull(h.state.lastReceivedSample)
        assertTrue(h.state.livePeerIndices.isEmpty())
        assertNull(vehicleUwbRecentSample(h.state, h.queue.now))
        assertEquals(1, native.closeRequests)
        assertEquals(1, h.frames.size)
        assertEquals(listOf(false), h.cleanup)
        assertTrue(h.finishes.isEmpty())
    }

    @Test fun actualServiceExpiredWindowRejectsQueuedDistanceBeforeTimeoutTaskDispatch() {
        for (hasDistance in listOf(false, true)) {
            val h = NegotiationFixture(serviceOwned = true)
            h.prepareReady()
            h.request(byteArrayOf(35, 36))
            h.frames.single().complete()
            val native = h.platform.natives.single()
            if (hasDistance) {
                native.result(meters = 1.25)
                h.queue.runQueued()
            }
            val historicalSamples = h.state.samples.toList()
            val lastSample = h.state.lastReceivedSample
            val validCount = h.state.validDistanceCount
            val deadlineDelay = if (hasDistance) 10_000L else 60_000L
            h.queue.elapse(1_000L)
            native.result(meters = 99.0)
            h.queue.elapse(deadlineDelay - 1_000L)
            // Immediate callbacks run before delayed tasks in this fixture. The production
            // coordinator must check elapsed expiry itself instead of reviving the window.
            h.queue.runQueued()
            val reason = if (hasDistance) "DISTANCE_RECEIVE_TIMEOUT" else "NO_DISTANCE_RECEIVED"
            assertEquals(TeslaUwbStage.CLEANING_UP, h.state.stage)
            assertEquals(reason, h.state.reason)
            assertEquals(1, native.closeRequests)
            assertEquals(validCount, h.state.validDistanceCount)
            assertEquals(historicalSamples, h.state.samples)
            assertEquals(lastSample, h.state.lastReceivedSample)
            assertTrue(h.state.livePeerIndices.isEmpty())
            assertNull(vehicleUwbRecentSample(h.state, h.queue.now))
            native.result(meters = 88.0)
            h.queue.runDue()
            assertEquals(validCount, h.state.validDistanceCount)
            assertEquals(lastSample, h.state.lastReceivedSample)
            native.callback.onClosed(75)
            h.queue.runQueued()
            assertEquals(2, h.frames.size)
            h.frames.last().complete()
            assertEquals(listOf(TeslaKeyStage.TIMED_OUT to reason), h.finishes)
            assertEquals(listOf(false), h.cleanup)
            assertNull(vehicleUwbRecentSample(h.state, h.queue.now))
        }
    }

    @Test fun actualNegotiationReportsRevokedPermissionWithoutConsumingTheQueuedDistance() {
        val h = NegotiationFixture(serviceOwned = true)
        h.prepareReady()
        h.request(byteArrayOf(35, 36))
        h.frames.single().complete()
        val native = h.platform.natives.single()
        native.result(meters = 0.75)
        h.queue.elapse(10_000L)
        native.permission = false
        h.queue.runQueued()
        assertEquals(listOf(TeslaKeyStage.FAILED to "RANGING_PERMISSION_REQUIRED"), h.finishes)
        assertEquals(0L, h.state.validDistanceCount)
        assertNull(h.state.lastReceivedSample)
        assertTrue(h.state.livePeerIndices.isEmpty())
        assertTrue(h.cleanup.isEmpty())
        assertNull(vehicleUwbRecentSample(h.state, h.queue.now))
        native.permission = true
        native.result(meters = 99.0)
        h.queue.runQueued()
        assertEquals(0L, h.state.validDistanceCount)
        assertNull(h.state.lastReceivedSample)
        assertNull(vehicleUwbRecentSample(h.state, h.queue.now))
        native.callback.onClosed(73)
        h.queue.runQueued()
        assertEquals(listOf(false), h.cleanup)
        assertWiped(checkNotNull(native.parameters))
        native.result(meters = 88.0)
        h.queue.advanceBy(10_000L)
        assertEquals(0L, h.state.validDistanceCount)
        assertNull(h.state.lastReceivedSample)
        assertNull(vehicleUwbRecentSample(h.state, h.queue.now))
        assertEquals(1, native.closeRequests)
        assertEquals(1, h.finishes.size)
    }

    @Test fun actualNegotiationCancelKeepsCloseExceptionAndTimeoutFailuresLatched() {
        for (mode in listOf("closeThrow", "timeout")) {
            val h = NegotiationFixture()
            h.prepareReady()
            h.request(byteArrayOf(37, 38))
            h.frames.single().complete()
            val native = h.platform.natives.single()
            native.closeThrows = mode == "closeThrow"
            h.coordinator.close { h.cleanup.add(it) }
            assertTrue(h.cleanup.isEmpty())
            if (mode == "closeThrow") {
                native.callback.onClosed(74)
                h.queue.runQueued()
            } else {
                h.queue.advanceBy(4_999L)
                assertTrue(h.cleanup.isEmpty())
                h.queue.advanceBy(1L)
            }
            assertEquals(listOf(true), h.cleanup)
            assertWiped(checkNotNull(native.parameters))
            native.callback.onClosed(75)
            h.queue.runQueued()
            h.coordinator.close { h.cleanup.add(it) }
            assertEquals(listOf(true, true), h.cleanup)
            assertTrue(h.finishes.isEmpty())
            assertEquals(1, h.frames.size)
        }
    }

    private enum class Fault {
        NONE, PREPARE_THROW, MANAGER_MISSING, MANAGER_THROW, CREATE_NULL, CREATE_THROW,
        START_THROW, START_SECURITY,
    }

    /** Immediate posts stay queued; elapsed time can move independently of delayed dispatch. */
    private class ManualScheduler : TeslaUwbScheduler {
        private class Delayed(val due: Long, val task: Runnable)
        private val owner = Thread.currentThread()
        private val queued = ArrayDeque<Runnable>()
        private val delayed = ArrayList<Delayed>()
        var now = 1_000L
            private set
        var rejectPosts = false
        var rejectDelays = false

        override fun post(task: Runnable): Boolean {
            if (rejectPosts) return false
            queued.addLast(task)
            return true
        }
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            if (rejectDelays) return false
            delayed.add(Delayed(now + delayMs, task))
            return true
        }
        override fun remove(task: Runnable) {
            queued.removeAll { it === task }
            delayed.removeAll { it.task === task }
        }
        override fun checkThread() {
            check(Thread.currentThread() === owner)
        }
        fun runQueued() {
            checkThread()
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
        fun elapse(milliseconds: Long) {
            require(milliseconds >= 0L)
            now += milliseconds
        }
        fun advanceBy(milliseconds: Long) {
            elapse(milliseconds)
            runDue()
        }
        fun runDue() {
            runQueued()
            while (true) {
                val next = delayed.minByOrNull { it.due } ?: return
                if (next.due > now) return
                delayed.remove(next)
                next.task.run()
                runQueued()
            }
        }
    }

    private class Native : TeslaUwbNativeRanging {
        private object Preference : TeslaUwbNativeRanging.Prepared
        var permission = true
        var fault = Fault.NONE
        var closeThrows = false
        var onCreate: (() -> Unit)? = null
        var onStart: (() -> Unit)? = null
        var onClose: (() -> Unit)? = null
        var parameters: TeslaUwbRangingParameters? = null
            private set
        var peerIds: List<UUID> = emptyList()
            private set
        lateinit var callback: TeslaUwbNativeRanging.Callback
            private set
        val hasCallback: Boolean get() = this::callback.isInitialized
        var starts = 0
            private set
        var closeRequests = 0
            private set
        private val session = object : TeslaUwbNativeRanging.Session {
            override fun start(prepared: TeslaUwbNativeRanging.Prepared) {
                assertSame(Preference, prepared)
                starts++
                onStart?.invoke()
                when (fault) {
                    Fault.START_THROW -> throw IllegalStateException("native start failure")
                    Fault.START_SECURITY -> throw SecurityException("native start permission lost")
                    else -> Unit
                }
            }
            override fun close() {
                closeRequests++
                onClose?.invoke()
                if (closeThrows) throw IllegalStateException("native close uncertain")
            }
        }

        override fun hasRangingPermission(): Boolean = permission
        override fun prepare(parameters: TeslaUwbRangingParameters, peerIds: List<UUID>): TeslaUwbNativeRanging.Prepared {
            this.parameters = parameters
            this.peerIds = peerIds.toList()
            if (fault == Fault.PREPARE_THROW) throw IllegalStateException("native preference failure")
            return Preference
        }
        override fun managerAvailable(): Boolean {
            if (fault == Fault.MANAGER_THROW) throw IllegalStateException("native manager lookup failure")
            return fault != Fault.MANAGER_MISSING
        }
        override fun create(callback: TeslaUwbNativeRanging.Callback): TeslaUwbNativeRanging.Session? {
            this.callback = callback
            onCreate?.invoke()
            if (fault == Fault.CREATE_THROW) throw IllegalStateException("native create uncertain")
            return if (fault == Fault.CREATE_NULL) null else session
        }
        fun result(peerIndex: Int = 0, meters: Double = 1.25) {
            callback.onResults(peerIds[peerIndex], TeslaUwbNativeRanging.UWB,
                meters, 2, 246_813L, null, null, null, null)
        }
    }

    private class RangingListener : TeslaUwbRanging.Listener {
        val events = ArrayList<String>()
        val failures = ArrayList<String>()
        val invalid = ArrayList<String>()
        val samples = ArrayList<TeslaUwbDistance>()
        val started = ArrayList<Int>()
        val stopped = ArrayList<Int>()
        val closedReasons = ArrayList<Int>()
        var opened = 0
            private set
        override fun onOpened() { opened++; events.add("opened") }
        override fun onStarted(peerIndex: Int) { started.add(peerIndex); events.add("started:$peerIndex") }
        override fun onDistance(sample: TeslaUwbDistance) { samples.add(sample); events.add("distance:${sample.peerIndex}") }
        override fun onInvalidResult(reason: String) { invalid.add(reason); events.add("invalid:$reason") }
        override fun onPeerStopped(peerIndex: Int) { stopped.add(peerIndex); events.add("stopped:$peerIndex") }
        override fun onFailure(reason: String) { failures.add(reason); events.add("failure:$reason") }
        override fun onClosed(reason: Int) { closedReasons.add(reason); events.add("closed:$reason") }
    }

    private class RangingFixture {
        val queue = ManualScheduler()
        val native = Native()
        val listener = RangingListener()
        val parameters = parameters()
        val ranging = TeslaUwbRanging(native, queue, { queue.now }, listener)
        fun start() = ranging.start(parameters)
        fun confirmClose(reason: Int = 7) {
            native.callback.onClosed(reason)
            queue.runQueued()
        }
    }

    private class Platform : TeslaUwbPlatform {
        var available = true
        var prepareThrows = false
        var registerThrows = false
        var unregisterThrows = false
        var onRegister: (() -> Unit)? = null
        var nativeFactory: () -> Native = { Native() }
        val natives = ArrayList<Native>()
        private lateinit var callback: (Result<TeslaUwbCapabilitySnapshot>) -> Unit
        private var registering = false
        var registrationLive = false
            private set
        var unregisteredWhileRegistering = false
            private set
        var unregisterCalls = 0
            private set

        override fun prepareCapabilities(): Boolean {
            if (prepareThrows) throw IllegalStateException("platform preparation failure")
            return available
        }
        override fun registerCapabilities(callback: (Result<TeslaUwbCapabilitySnapshot>) -> Unit) {
            this.callback = callback
            registrationLive = true // A throwing registration can still have installed the callback.
            registering = true
            try {
                onRegister?.invoke()
                if (registerThrows) throw IllegalStateException("registration uncertain")
            } finally {
                registering = false
            }
        }
        override fun unregisterCapabilities() {
            unregisterCalls++
            unregisteredWhileRegistering = unregisteredWhileRegistering || registering
            if (unregisterThrows) throw IllegalStateException("unregistration uncertain")
            registrationLive = false
        }
        override fun createNativeRanging(): TeslaUwbNativeRanging = nativeFactory().also(natives::add)
        fun emit(result: Result<TeslaUwbCapabilitySnapshot>) = callback(result)
    }

    private class Frame(private val body: ByteArray, private val completed: () -> Unit) {
        fun complete() {
            // Consumer-owned serialized response storage is independent of native parameter bytes.
            body.fill(0)
            completed()
        }
    }

    private class NegotiationFixture(serviceOwned: Boolean = false) {
        val queue = ManualScheduler()
        val platform = Platform()
        var state = TeslaUwbProbeState(serviceOwned = serviceOwned)
            private set
        var ready = 0
            private set
        val frames = ArrayList<Frame>()
        val finishes = ArrayList<Pair<TeslaKeyStage, String?>>()
        val cleanup = ArrayList<Boolean>()
        val coordinator: TeslaUwbNegotiation = TeslaUwbNegotiation(platform, queue, { queue.now },
            { 1_792_360_988_000L }, object : TeslaUwbNegotiation.Listener {
                override fun onCapabilitiesReady() { ready++ }
                override fun onSend(body: ByteArray, frameComplete: () -> Unit) {
                    frames.add(Frame(body, frameComplete))
                }
                override fun onState(transform: (TeslaUwbProbeState) -> TeslaUwbProbeState, event: String) {
                    state = transform(state)
                }
                override fun onFinish(stage: TeslaKeyStage, reason: String?) {
                    finishes.add(stage to reason)
                    closeAfterFinish()
                }
            }, serviceOwned = serviceOwned)

        private fun closeAfterFinish() {
            coordinator.close { cleanup.add(it) }
        }
        fun prepareReady() {
            coordinator.prepare()
            platform.emit(Result.success(capabilitySnapshot()))
            queue.runQueued()
            assertEquals(1, ready)
        }
        fun request(vararg addresses: ByteArray): List<TeslaFiraRequest> {
            val sessions = addresses.mapIndexed { index, bytes ->
                TeslaFiraRequest((index + 1).toLong(), bytes, 2, ByteArray(0), 3)
            }
            coordinator.receive(TeslaFiraMessage.Request(sessions))
            return sessions
        }
    }

    private companion object {
        fun parameters() = TeslaUwbRangingParameters(
            firaSessionId = 0x12345678,
            frameworkConfigId = 4,
            localAddress = byteArrayOf(18, 52),
            peerAddresses = listOf(byteArrayOf(65, 1), byteArrayOf(65, 2)),
            channel = 9,
            preambleIndex = 11,
            sessionKeyInfo = ByteArray(16) { (it + 1).toByte() },
            updateRate = 2,
            angleOfArrivalNeeded = true,
        )
        fun capabilitySnapshot() = TeslaUwbCapabilitySnapshot(
            TeslaFiraCapabilities(true, true, listOf(5, 9), listOf(9, 11), listOf(2, 4, 5),
                listOf(2), listOf(1, 2, 3)),
            azimuthSupported = true,
            elevationSupported = false,
        )
        fun copyParameters(source: TeslaUwbRangingParameters) = source.copy(
            localAddress = source.localAddress.copyOf(),
            peerAddresses = source.peerAddresses.map { it.copyOf() },
            sessionKeyInfo = source.sessionKeyInfo.copyOf(),
        )
        fun assertParameterBytes(expected: TeslaUwbRangingParameters, actual: TeslaUwbRangingParameters) {
            assertArrayEquals(expected.localAddress, actual.localAddress)
            assertArrayEquals(expected.sessionKeyInfo, actual.sessionKeyInfo)
            assertEquals(expected.peerAddresses.size, actual.peerAddresses.size)
            expected.peerAddresses.indices.forEach {
                assertArrayEquals(expected.peerAddresses[it], actual.peerAddresses[it])
            }
        }
        fun assertWiped(parameters: TeslaUwbRangingParameters) {
            assertArrayEquals(ByteArray(parameters.localAddress.size), parameters.localAddress)
            assertArrayEquals(ByteArray(parameters.sessionKeyInfo.size), parameters.sessionKeyInfo)
            parameters.peerAddresses.forEach { assertArrayEquals(ByteArray(it.size), it) }
        }
        inline fun <reified T : Throwable> expectThrows(action: () -> Unit): T {
            try {
                action()
            } catch (failure: Throwable) {
                if (failure is T) return failure
                throw AssertionError("Expected ${T::class.java.name}, received ${failure.javaClass.name}", failure)
            }
            throw AssertionError("Expected ${T::class.java.name}")
        }
    }
}
