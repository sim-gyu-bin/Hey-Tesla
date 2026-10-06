package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test

class BleGattSessionTest {
    @Test fun externalStopDuringSubscribeRetainsLeaseUntilDisableDisconnectAndClose() {
        val h = Harness()
        h.subscribing()
        assertFalse(h.state.subscriptionConfirmed)

        h.ownerStop()
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)
        assertEquals(listOf(true), h.port.writes) // enable in-flight 중 disable 중첩 금지.
        assertEquals(h.lease, h.policy.current)
        assertNull(h.policy.startManual(h.clock.ms))

        h.descriptor(enabled = true)
        assertTrue(h.state.subscriptionConfirmed)
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)
        assertEquals(listOf(true, false), h.port.writes)
        h.descriptor(enabled = true) // 중복 enable callback은 정리 중 disable 확인으로 쓸 수 없다.
        h.session.notification(h.lease.id, h.port)
        assertFalse(h.state.remoteUnsubscribeConfirmed)
        assertEquals(0, h.state.notificationCount)

        h.descriptor(enabled = false)
        assertTrue(h.state.remoteUnsubscribeConfirmed)
        assertFalse(h.state.localClosed)
        assertEquals(h.lease, h.policy.current) // disconnect 요청 접수는 해제가 아니다.
        assertNull(h.policy.startDiagnostic(h.clock.ms))

        h.disconnected()
        assertEquals(BleProbeStatus.CANCELED, h.state.status)
        assertTrue(h.state.disconnectConfirmed)
        assertTrue(h.state.localClosed)
        assertNull(h.policy.current)
    }

    @Test fun oldTokenOrOldHandleCannotMutateReplacementSession() {
        val policy = SessionPolicy()
        val old = Harness(policy = policy)
        old.ownerStop()
        old.disconnected()
        val oldFinal = old.state
        val replacement = Harness(policy = policy)
        val oldEvidence = old.evidence.toList()
        val replacementEvidence = replacement.evidence.toList()

        replacement.session.connection(old.lease.id, old.port, success = true, connected = true)
        replacement.session.connection(replacement.lease.id, old.port, success = true, connected = true)
        replacement.session.connection(old.lease.id, replacement.port, success = true, connected = true)
        replacement.session.services(old.lease.id, old.port, success = true)
        replacement.session.descriptor(old.lease.id, old.port, enabled = true, success = true)
        old.connected()
        old.descriptor(enabled = true)

        replacement.session.notification(old.lease.id, old.port)
        old.session.services(old.lease.id, old.port, success = true, gattStatus = 8)
        old.session.notification(old.lease.id, old.port)
        assertEquals(oldEvidence, old.evidence)
        assertEquals(replacementEvidence, replacement.evidence)
        assertEquals(oldFinal, old.state)
        assertEquals(BleProbeStatus.CONNECTING, replacement.state.status)
        assertFalse(replacement.state.connected)
        assertFalse(replacement.state.subscriptionConfirmed)
        assertEquals(replacement.lease, policy.current)
        assertEquals(0, replacement.port.discoveryRequests)
        replacement.connected()
        assertEquals(BleProbeStatus.DISCOVERING, replacement.state.status)
    }

    @Test fun connectionTimeoutClosesAfterBoundedCleanupWithoutInventingRemoteEvidence() {
        val h = Harness()
        h.clock.ms = BleGattSession.CONNECT_MS
        h.session.tick()
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)
        assertEquals("CONNECTING_TIMEOUT", h.state.reason)
        assertEquals(h.lease, h.policy.current)

        h.clock.ms += BleGattSession.CLEANUP_MS
        h.session.tick()
        assertEquals(BleProbeStatus.TIMED_OUT, h.state.status)
        assertTrue(h.state.localClosed)
        assertFalse(h.state.subscriptionConfirmed)
        assertFalse(h.state.remoteUnsubscribeConfirmed)
        assertFalse(h.state.disconnectConfirmed)
        assertNull(h.policy.current)
        h.connected()
        assertEquals(BleProbeStatus.TIMED_OUT, h.state.status)
        assertFalse(h.state.connected)
    }

    @Test fun closeFailureRetainsReservationAfterExternalStopAndSessionExpiry() {
        val h = Harness()
        h.port.closeFails = true
        h.ownerStop()
        h.disconnected()
        assertEquals(BleProbeStatus.CLEANUP_FAILED, h.state.status)
        assertEquals("LOCAL_CLOSE_FAILED_RESTART_REQUIRED", h.state.reason)
        assertFalse(h.state.localClosed)
        assertTrue(h.state.disconnectConfirmed)
        assertEquals(h.lease, h.policy.current)

        h.clock.ms = h.lease.deadline + 1
        h.ownerStop()
        h.session.tick()
        h.connected()
        assertTrue(h.policy.expired(h.lease.id, h.clock.ms))
        assertNull(h.policy.startManual(h.clock.ms))
        assertNull(h.policy.startDiagnostic(h.clock.ms))
        assertEquals(h.lease, h.policy.current)
        assertEquals(1, h.port.closeCalls)
        assertEquals(BleProbeStatus.CLEANUP_FAILED, h.state.status)
    }

    @Test fun acceptedSubscribeWithoutCallbackTimesOutAndLateConfirmationCannotComplete() {
        val h = Harness()
        h.subscribing()
        assertEquals(BleProbeStatus.SUBSCRIBING, h.state.status)
        assertFalse(h.state.subscriptionConfirmed)
        h.clock.ms = BleGattSession.SUBSCRIBE_MS
        h.session.tick()
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)

        h.descriptor(enabled = true)
        assertTrue(h.state.subscriptionConfirmed) // 늦은 callback은 증거만 보존한다.
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)
        h.session.notification(h.lease.id, h.port)
        h.descriptor(enabled = false)
        h.disconnected()
        assertEquals(BleProbeStatus.TIMED_OUT, h.state.status)
        assertEquals("SUBSCRIBING_TIMEOUT", h.state.reason)
        assertEquals(0, h.state.notificationCount)
        assertTrue(h.state.localClosed)
        assertNull(h.policy.current)
    }

    @Test fun pendingEnableCannotHoldCanceledCleanupForeverOrTriggerWriteAfterUnsubscribeDeadline() {
        val h = Harness()
        h.subscribing()
        h.ownerStop()
        h.clock.ms = BleGattSession.UNSUBSCRIBE_MS
        h.session.tick()
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)
        assertEquals(1, h.port.disconnectRequests)
        assertEquals(h.lease, h.policy.current)

        h.descriptor(enabled = true)
        assertTrue(h.state.subscriptionConfirmed)
        assertEquals(listOf(true), h.port.writes) // 해제 제한 이후 새 descriptor write를 시작하지 않는다.
        assertFalse(h.state.remoteUnsubscribeConfirmed)
        h.clock.ms = BleGattSession.CLEANUP_MS
        h.session.tick()
        assertEquals(BleProbeStatus.CANCELED, h.state.status)
        assertTrue(h.state.localClosed)
        assertFalse(h.state.disconnectConfirmed)
        assertNull(h.policy.current)
        h.descriptor(enabled = false)
        assertFalse(h.state.remoteUnsubscribeConfirmed)
    }

    @Test fun confirmedQuietObservationCanCompleteWhileUnsubscribeAndDisconnectRemainUnconfirmed() {
        val h = Harness()
        h.observing()
        h.clock.ms = BleGattSession.OBSERVE_MS
        h.session.tick()
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)
        assertEquals(h.lease, h.policy.current)

        h.port.disconnectFails = true
        h.descriptor(enabled = false, success = false)
        h.clock.ms += BleGattSession.CLEANUP_MS
        h.session.tick()
        assertEquals(BleProbeStatus.COMPLETE, h.state.status)
        assertTrue(h.state.subscriptionConfirmed)
        assertEquals(0, h.state.notificationCount)
        assertNull(h.state.firstRxElapsedMs)
        assertTrue(h.evidence.none { it.kind == BleEvidenceKind.GATT_FIRST_RX })
        assertFalse(h.state.remoteUnsubscribeConfirmed)
        assertFalse(h.state.disconnectConfirmed)
        assertTrue(h.state.localClosed)
        assertNull(h.policy.current)
    }

    @Test fun canceledConnectThatCompletesLateIsDisconnectedWithoutDiscovery() {
        val h = Harness()
        h.ownerStop()
        assertEquals(1, h.port.disconnectRequests)
        h.connected()
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)
        assertEquals(0, h.port.discoveryRequests)
        assertEquals(2, h.port.disconnectRequests)
        assertEquals(h.lease, h.policy.current)
        h.disconnected()
        assertEquals(BleProbeStatus.CANCELED, h.state.status)
        assertTrue(h.state.localClosed)
    }

    @Test fun bluetoothOffWinsOverObservationCompletionAndBlocksFurtherReceive() {
        val h = Harness()
        h.observing()
        h.clock.ms = BleGattSession.OBSERVE_MS
        h.session.tick("BLUETOOTH_OFF")
        h.session.notification(h.lease.id, h.port)
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)
        assertEquals("BLUETOOTH_OFF", h.state.reason)
        assertEquals(0, h.state.notificationCount)
        assertEquals(h.lease, h.policy.current)
        h.descriptor(enabled = false)
        h.disconnected()
        assertEquals(BleProbeStatus.CANCELED, h.state.status)
        assertTrue(h.state.localClosed)
        assertNull(h.policy.current)
    }

    @Test fun delayedMainCallbackCannotSpendTheReservedCleanupWindowOnObservation() {
        val h = Harness()
        h.observing()
        h.clock.ms = h.lease.deadline - BleGattSession.CLEANUP_MS
        h.session.tick()
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)
        assertEquals("OBSERVING_TIMEOUT", h.state.reason)
        assertEquals(h.lease, h.policy.current)
        h.clock.ms = h.lease.deadline
        h.session.tick()
        assertEquals(BleProbeStatus.TIMED_OUT, h.state.status)
        assertTrue(h.state.localClosed)
        assertFalse(h.state.remoteUnsubscribeConfirmed)
        assertNull(h.policy.current)
    }

    @Test fun departureKeepsSubscriptionCleanupBoundedAndCannotResumeObservation() {
        val h = Harness()
        h.subscribing()
        h.session.cancel("DEPARTED")
        h.descriptor(enabled = true)
        assertEquals("DEPARTED", h.state.reason)
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)
        h.clock.ms = BleGattSession.CLEANUP_MS
        h.session.tick()
        assertEquals(BleProbeStatus.CANCELED, h.state.status)
        assertTrue(h.state.localClosed)
        assertNull(h.policy.current)
    }

    @Test fun realCallbackStatusSurvivesFailureAndTimeoutDoesNotInventSdkCode() {
        val failed = Harness()
        failed.session.connection(failed.lease.id, failed.port, success = false, connected = false, gattStatus = 133)
        assertEquals(BleProbeStatus.FAILED, failed.state.status)
        assertEquals(133, failed.state.gattStatus)
        val timeout = Harness()
        timeout.clock.ms = BleGattSession.CONNECT_MS + BleGattSession.CLEANUP_MS
        timeout.session.tick()
        timeout.clock.ms += BleGattSession.CLEANUP_MS
        timeout.session.tick()
        assertNull(timeout.state.gattStatus)
        assertEquals(BleProbeStatus.TIMED_OUT, timeout.state.status)
    }

    @Test fun primaryFailure133IsNotReplacedByCleanupDisconnect8() {
        val h = Harness()
        h.session.connection(h.lease.id, h.port, success = false, connected = true, gattStatus = 133)
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)
        assertEquals(133, h.state.primaryGattStatus)
        assertNull(h.state.cleanupGattStatus)
        assertEquals(h.lease, h.policy.current)

        h.disconnected(gattStatus = 8)
        assertEquals(BleProbeStatus.FAILED, h.state.status)
        assertEquals("CONNECTION_FAILED", h.state.reason)
        assertEquals(133, h.state.primaryGattStatus)
        assertEquals(8, h.state.cleanupGattStatus)
        assertEquals(8, h.state.gattStatus) // 기존 마지막 callback consumer 값은 유지한다.
        assertTrue(h.state.localClosed)
        assertNull(h.policy.current)
        val callbacks = h.evidence.filter { it.kind == BleEvidenceKind.GATT_CALLBACK }
        assertEquals(listOf(BleEvidencePhase.CONNECTING, BleEvidencePhase.CLEANING_UP), callbacks.map { it.phase })
        assertEquals(listOf(133, 8), callbacks.map { it.gattStatus })
    }

    @Test fun successfulPrimaryZeroIsFrozenEvenWhenUnsubscribeAndDisconnectFail() {
        val h = Harness(backgroundConnect = true)
        h.connected(gattStatus = 0)
        h.session.services(h.lease.id, h.port, success = true, gattStatus = 0)
        h.descriptor(enabled = true, gattStatus = 0)
        h.ownerStop()
        h.descriptor(enabled = false, success = false, gattStatus = 8)
        h.disconnected(gattStatus = 8)
        assertEquals(BleProbeStatus.CANCELED, h.state.status)
        assertEquals(0, h.state.primaryGattStatus)
        assertEquals(8, h.state.cleanupGattStatus)
        assertTrue(h.state.backgroundConnect)
        assertFalse(h.state.remoteUnsubscribeConfirmed)
        assertTrue(h.state.localClosed)
    }

    @Test fun firstRxUsesMonotonicElapsedTimeAndOnlyOnePayloadFreeEvent() {
        val h = Harness(clock = Clock(1_000), attempt = 42)
        h.subscribing()
        h.session.notification(h.lease.id, h.port) // 구독 callback 전 RX는 관찰 성공으로 세지 않는다.
        assertNull(h.state.firstRxElapsedMs)
        h.descriptor(enabled = true)
        h.clock.ms = 1_125
        h.session.notification(h.lease.id, h.port)
        h.clock.ms = 1_240
        repeat(100) { h.session.notification(h.lease.id, h.port) }
        assertEquals(101, h.state.notificationCount)
        assertEquals(125L, h.state.firstRxElapsedMs)
        val firstRx = h.evidence.filter { it.kind == BleEvidenceKind.GATT_FIRST_RX }
        assertEquals(1, firstRx.size)
        assertEquals(42L, firstRx.single().attempt)
        assertEquals(1_125L, firstRx.single().receivedElapsedMs)
        assertEquals(BleEvidencePhase.OBSERVING, firstRx.single().phase)
        assertTrue(h.evidence.none { it.kind == BleEvidenceKind.GATT_CALLBACK && it.action == BleEvidenceAction.RX_CALLBACK })
        h.ownerStop()
        h.session.notification(h.lease.id, h.port)
        assertEquals(101, h.state.notificationCount)
        assertEquals(125L, h.state.firstRxElapsedMs)
        assertEquals(1, h.evidence.count { it.kind == BleEvidenceKind.GATT_FIRST_RX })
    }

    @Test fun candidateDeadlineReservesCleanupAndNeverExtendsTheLeaseDeadline() {
        val short = Harness(deadline = 6_000)
        short.clock.ms = 1_999
        short.session.tick()
        assertEquals(BleProbeStatus.CONNECTING, short.state.status)
        short.clock.ms = 2_000
        short.session.tick()
        assertEquals(BleProbeStatus.CLEANING_UP, short.state.status)
        assertEquals(short.lease, short.policy.current)
        short.clock.ms = 6_000
        short.session.tick()
        assertEquals(BleProbeStatus.TIMED_OUT, short.state.status)
        assertTrue(short.state.localClosed)
        assertNull(short.policy.current)

        val long = Harness(deadline = 100_000)
        long.clock.ms = 7_999
        long.connected()
        long.clock.ms = 13_998
        long.session.services(long.lease.id, long.port, success = true)
        long.clock.ms = 17_997
        long.descriptor(enabled = true)
        long.clock.ms = long.lease.deadline - BleGattSession.CLEANUP_MS
        long.session.tick()
        assertEquals(BleProbeStatus.CLEANING_UP, long.state.status)
        long.clock.ms = long.lease.deadline
        long.session.tick()
        assertTrue(long.state.localClosed)
        assertNull(long.policy.current)
    }

    @Test fun exhaustedCandidateBudgetCannotAllocateGattOrInventPrimaryStatus() {
        val h = Harness(clock = Clock(5_000), deadline = 5_000)
        assertEquals(0, h.port.connectCalls)
        assertEquals(BleProbeStatus.TIMED_OUT, h.state.status)
        assertEquals("SESSION_EXPIRED", h.state.reason)
        assertTrue(h.state.localClosed)
        assertNull(h.policy.current)
        assertNull(h.state.primaryGattStatus)
        assertNull(h.state.cleanupGattStatus)
        assertTrue(h.evidence.none { it.action == BleEvidenceAction.CONNECT })
    }

    @Test fun requestRejectionAndExceptionRemainDifferentWithoutRetainingExceptionText() {
        val rejected = Harness(port = FakePort(connectAccepted = false))
        val failed = Harness(port = FakePort(connectFails = true))
        val rejectedRequest = rejected.evidence.single { it.action == BleEvidenceAction.CONNECT }
        val failedRequest = failed.evidence.single { it.action == BleEvidenceAction.CONNECT }
        assertEquals(BleEvidenceResult.REJECTED, rejectedRequest.result)
        assertEquals(BleEvidenceResult.EXCEPTION, failedRequest.result)
        assertEquals(false, rejectedRequest.accepted)
        assertEquals(false, failedRequest.accepted)
        assertEquals("CONNECT_REQUEST_FAILED", rejected.state.reason)
        assertEquals("CONNECT_REQUEST_FAILED", failed.state.reason)
        assertTrue(rejected.state.localClosed)
        assertTrue(failed.state.localClosed)
        assertFalse(failedRequest.encode().contains("민감"))
    }

    @Test fun unsubscribeReadRequestIsNotACallbackAndCannotInventRemoteConfirmation() {
        val h = Harness()
        h.observing()
        h.ownerStop()
        var reads = 0
        assertFalse(h.session.verifyUnsubscribe(h.lease.id, h.port, 0) { reads++; false })
        assertEquals(1, reads)
        assertFalse(h.state.remoteUnsubscribeConfirmed)
        assertEquals(1, h.port.disconnectRequests)
        val writeCallback = h.evidence.last { it.kind == BleEvidenceKind.GATT_CALLBACK }
        assertEquals(BleEvidenceAction.DESCRIPTOR_WRITE_CALLBACK, writeCallback.action)
        val request = h.evidence.single { it.action == BleEvidenceAction.CCCD_READ }
        assertEquals(BleEvidenceResult.REJECTED, request.result)
        assertTrue(h.evidence.none { it.action == BleEvidenceAction.DESCRIPTOR_READ_CALLBACK })
        h.disconnected()
        val recorded = h.evidence.toList()
        assertFalse(h.session.verifyUnsubscribe(h.lease.id, h.port, 0) { reads++; true })
        assertEquals(1, reads)
        assertEquals(recorded, h.evidence)
    }

    @Test fun unsubscribeReadCallbackAloneConfirmsRemoteDisableAndPreservesPrimary() {
        val h = Harness()
        h.connected(gattStatus = 0)
        h.session.services(h.lease.id, h.port, success = true, gattStatus = 0)
        h.descriptor(enabled = true, gattStatus = 0)
        h.ownerStop()
        assertTrue(h.session.verifyUnsubscribe(h.lease.id, h.port, 0) { true })
        assertFalse(h.state.remoteUnsubscribeConfirmed)
        assertEquals(0, h.port.disconnectRequests)
        h.session.descriptor(h.lease.id, h.port, false, true, 0, BleEvidenceAction.DESCRIPTOR_READ_CALLBACK)
        assertTrue(h.state.remoteUnsubscribeConfirmed)
        assertEquals(1, h.port.disconnectRequests)
        assertEquals(0, h.state.primaryGattStatus)
        assertEquals(0, h.state.cleanupGattStatus)
        assertEquals(1, h.evidence.count { it.action == BleEvidenceAction.DESCRIPTOR_READ_CALLBACK })
        h.disconnected()
        assertNull(h.policy.current)
    }

    @Test fun duplicateCallbackFloodIsBoundedWithoutLosingFinalSdkStatus() {
        val h = Harness()
        h.connected(gattStatus = 0)
        repeat(1_000) { h.connected(gattStatus = it) }
        assertEquals(999, h.state.primaryGattStatus)
        assertEquals(BleGattSession.MAX_CALLBACK_EVIDENCE, h.evidence.count { it.kind == BleEvidenceKind.GATT_CALLBACK })
        assertEquals(1, h.port.discoveryRequests)
        assertTrue(h.evidence.count { it.kind == BleEvidenceKind.GATT_REQUEST } <= BleGattSession.MAX_REQUEST_EVIDENCE)
        h.ownerStop()
        h.disconnected(gattStatus = 8)
        assertEquals(999, h.state.primaryGattStatus)
        assertEquals(8, h.state.cleanupGattStatus)
        assertTrue(h.state.localClosed)
        assertNull(h.policy.current)
    }

    @Test fun finalSummaryCallbackObservesActualLeaseReleaseAndMeasuredCleanupPhase() {
        val policy = SessionPolicy()
        val port = FakePort()
        var time = 10L
        val lease = checkNotNull(policy.startDiagnostic(time))
        var final: BleProbeState? = null
        val session = BleGattSession(lease.id, lease.deadline, port, { time }, {}, {
            check(policy.finish(lease.id, time))
        }, finishedCallback = {
            assertNull(policy.current)
            assertTrue(port.closed)
            final = it
        })
        session.start()
        time = 25
        session.cancel("DEPARTED")
        time = 39
        session.connection(lease.id, port, success = true, connected = false, gattStatus = 0)
        assertEquals(29L, final?.elapsedMs)
        assertEquals(14L, final?.phaseElapsedMs)
        assertEquals("DEPARTED", final?.reason)
    }

    @Test fun reentrantStopFromStagePublicationCannotAllocateGattAfterLeaseRelease() {
        val policy = SessionPolicy()
        val port = FakePort()
        val lease = checkNotNull(policy.startDiagnostic(0))
        lateinit var session: BleGattSession
        session = BleGattSession(lease.id, lease.deadline, port, { 0 }, {
            if (it.status == BleProbeStatus.CONNECTING) session.cancel("USER_STOP")
        }, {
            check(policy.finish(lease.id, 0))
        })
        session.start()
        assertEquals(0, port.connectCalls)
        assertEquals(1, port.closeCalls)
        assertEquals(BleProbeStatus.CANCELED, session.state.status)
        assertNull(policy.current)
    }

    private class Clock(var ms: Long = 0)

    @Test fun stopFromAcceptedSubscribeEvidenceStillWaitsForTheInFlightWriteBeforeDisable() {
        val h = Harness()
        h.evidenceConsumer = {
            if (it.kind == BleEvidenceKind.GATT_REQUEST && it.action == BleEvidenceAction.CCCD_ON) h.ownerStop()
        }
        h.subscribing()
        assertEquals(BleProbeStatus.CLEANING_UP, h.state.status)
        assertEquals(listOf(true), h.port.writes)
        assertEquals(0, h.port.disconnectRequests)
        assertEquals(h.lease, h.policy.current)
        h.descriptor(enabled = true)
        assertEquals(listOf(true, false), h.port.writes)
        h.descriptor(enabled = false)
        h.disconnected()
        assertEquals(BleProbeStatus.CANCELED, h.state.status)
        assertTrue(h.state.localClosed)
        assertNull(h.policy.current)
    }

    private class Harness(
        val policy: SessionPolicy = SessionPolicy(),
        val clock: Clock = Clock(),
        val port: FakePort = FakePort(),
        deadline: Long? = null,
        backgroundConnect: Boolean = false,
        attempt: Long = 1,
    ) {
        val lease = checkNotNull(policy.startDiagnostic(clock.ms))
        val evidence = mutableListOf<BleEventEvidence>()
        var evidenceConsumer: (BleEventEvidence) -> Unit = {}
        val session = BleGattSession(lease.id, lease.deadline, port, { clock.ms }, {}, {
            check(port.closed)
            check(policy.finish(lease.id, clock.ms))
        }, deadline = deadline, backgroundConnect = backgroundConnect, attempt = attempt, recordEvidence = {
            evidence.add(it)
            evidenceConsumer(it)
        })
        val ownerStop: () -> Unit = { session.cancel() }
        val state get() = session.state

        init { session.start() }

        fun connected(gattStatus: Int? = null) = session.connection(lease.id, port, success = true, connected = true, gattStatus = gattStatus)
        fun disconnected(gattStatus: Int? = null) = session.connection(lease.id, port, success = true, connected = false, gattStatus = gattStatus)
        fun descriptor(enabled: Boolean, success: Boolean = true, gattStatus: Int? = null) = session.descriptor(lease.id, port, enabled, success, gattStatus)
        fun subscribing() {
            connected()
            session.services(lease.id, port, success = true)
        }
        fun observing() {
            subscribing()
            descriptor(enabled = true)
        }
    }

    private class FakePort(
        private val connectAccepted: Boolean = true,
        private val connectFails: Boolean = false,
    ) : BleGattPort {
        val writes = mutableListOf<Boolean>()
        var discoveryRequests = 0
        var connectCalls = 0
        var disconnectRequests = 0
        var closeCalls = 0
        var closeFails = false
        var disconnectFails = false
        var closed = false

        override fun connect(): Boolean {
            connectCalls++
            if (connectFails) error("민감 예외 원문")
            return connectAccepted
        }
        override fun discoverServices(): Boolean { discoveryRequests++; return true }
        override fun profile() = BleGattProfile(true, true, true, true, BleSubscriptionMode.INDICATE)
        override fun setNotifications(enabled: Boolean) = true
        override fun writeSubscription(enabled: Boolean): Boolean { writes.add(enabled); return true }
        override fun disconnect() {
            disconnectRequests++
            if (disconnectFails) throw IllegalStateException("DISCONNECT_FAILURE")
        }
        override fun close() {
            closeCalls++
            if (closeFails) throw IllegalStateException("CLOSE_FAILURE")
            closed = true
        }
    }
}
