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

        replacement.session.connection(old.lease.id, old.port, success = true, connected = true)
        replacement.session.connection(replacement.lease.id, old.port, success = true, connected = true)
        replacement.session.connection(old.lease.id, replacement.port, success = true, connected = true)
        replacement.session.services(old.lease.id, old.port, success = true)
        replacement.session.descriptor(old.lease.id, old.port, enabled = true, success = true)
        old.connected()
        old.descriptor(enabled = true)

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

    private class Clock(var ms: Long = 0)

    private class Harness(
        val policy: SessionPolicy = SessionPolicy(),
        val clock: Clock = Clock(),
        val port: FakePort = FakePort(),
    ) {
        val lease = checkNotNull(policy.startDiagnostic(clock.ms))
        val session = BleGattSession(lease.id, lease.deadline, port, { clock.ms }, {}, {
            check(port.closed)
            check(policy.finish(lease.id, clock.ms))
        })
        val ownerStop: () -> Unit = session::cancel
        val state get() = session.state

        init { session.start() }

        fun connected() = session.connection(lease.id, port, success = true, connected = true)
        fun disconnected() = session.connection(lease.id, port, success = true, connected = false)
        fun descriptor(enabled: Boolean, success: Boolean = true) = session.descriptor(lease.id, port, enabled, success)
        fun subscribing() {
            connected()
            session.services(lease.id, port, success = true)
        }
        fun observing() {
            subscribing()
            descriptor(enabled = true)
        }
    }

    private class FakePort : BleGattPort {
        val writes = mutableListOf<Boolean>()
        var discoveryRequests = 0
        var disconnectRequests = 0
        var closeCalls = 0
        var closeFails = false
        var disconnectFails = false
        var closed = false

        override fun connect() = true
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
