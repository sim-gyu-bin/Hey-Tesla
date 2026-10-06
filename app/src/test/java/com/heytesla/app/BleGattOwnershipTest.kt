package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test

class BleGattOwnershipTest {
    @Test fun cleanupReappearanceCreatesReplacementOnlyAfterOldOwnerAndLeaseAreCleared() {
        val leases = SessionPolicy()
        val field = BleFieldTrialPolicy(BleFieldConfig.BASELINE, { 0 })
        val removed = mutableListOf<Runnable>()
        val ownership = BleGattOwnership({ check(leases.finish(it, 0)) }, { removed.add(it) })
        val oldAttempt = checkNotNull(field.signal(BleEvidenceSignal.CDM_BLE_APPEARED, 0).startAttempt)
        val oldLease = checkNotNull(leases.startDiagnostic(0))
        val oldPort = Port()
        val oldWatchdog = Runnable {}
        val replacementWatchdog = Runnable {}
        val replacementPort = Port()
        var replacement: BleGattSession? = null
        var final: BleProbeState? = null
        val old = BleGattSession(oldLease.id, oldLease.deadline, oldPort, { 0 }, {},
            release = { ownership.release(oldLease.id) },
            finishedCallback = { result ->
                final = result
                assertTrue(result.localClosed)
                assertNull(ownership.current)
                assertNull(ownership.associationId)
                assertNull(ownership.watchdog)
                assertNull(leases.current)
                assertEquals(listOf(oldWatchdog), removed)
                assertEquals(2L, field.finished(oldAttempt, result, leaseReleased = result.localClosed).startAttempt)
                val lease = checkNotNull(leases.startDiagnostic(0))
                val next = BleGattSession(lease.id, lease.deadline, replacementPort, { 0 }, {},
                    release = { ownership.release(lease.id) })
                replacement = next
                ownership.attach(next, 20)
                ownership.watch(lease.id, replacementWatchdog)
                next.start()
            })
        ownership.attach(old, 10)
        ownership.watch(oldLease.id, oldWatchdog)
        old.start()
        assertEquals(oldAttempt, field.signal(BleEvidenceSignal.CDM_BLE_DISAPPEARED, 0).cancelAttempt)
        old.cancel("DEPARTED")
        assertNull(field.signal(BleEvidenceSignal.CDM_BLE_APPEARED, 0).startAttempt)
        assertSame(old, ownership.current)
        assertEquals(oldLease, leases.current)
        old.connection(oldLease.id, oldPort, success = true, connected = false, gattStatus = 0)

        val next = checkNotNull(replacement)
        assertEquals(BleProbeStatus.CANCELED, final?.status)
        assertEquals("DEPARTED", final?.reason)
        assertSame(next, ownership.current)
        assertEquals(20, ownership.associationId)
        assertSame(replacementWatchdog, ownership.watchdog)
        ownership.release(oldLease.id) // 늦은 구 소유자는 새 예약에 접근하지 못한다.
        ownership.stopWatchdog(oldLease.id)
        old.connection(oldLease.id, oldPort, success = true, connected = true, gattStatus = 0)
        assertSame(next, ownership.current)
        assertEquals(next.token, leases.current?.id)
        assertEquals(20, ownership.associationId)
        assertSame(replacementWatchdog, ownership.watchdog)
        assertEquals(listOf(oldWatchdog), removed)
        assertEquals(BleProbeStatus.CONNECTING, next.state.status)
        assertEquals(0, replacementPort.discoveryRequests)
    }

    @Test fun closeFailureStopsWatchdogButRetainsSameOwnerLeaseAndBlocksOtherDiagnostics() {
        val leases = SessionPolicy()
        val lease = checkNotNull(leases.startDiagnostic(0))
        val port = Port(closeFails = true)
        var removed = 0
        val ownership = BleGattOwnership({ check(leases.finish(it, 0)) }, { removed++ })
        val session = BleGattSession(lease.id, lease.deadline, port, { 0 }, {},
            release = { ownership.release(lease.id) },
            finishedCallback = {
                ownership.stopWatchdog(lease.id)
                assertEquals(BleProbeStatus.CLEANUP_FAILED, it.status)
                assertEquals(lease, leases.current)
                assertNull(leases.startManual(100_000))
            })
        ownership.attach(session, 10)
        ownership.watch(lease.id, Runnable {})
        session.start()
        session.cancel("USER_STOP")
        session.connection(lease.id, port, success = true, connected = false, gattStatus = 0)
        assertSame(session, ownership.current)
        assertEquals(10, ownership.associationId)
        assertNull(ownership.watchdog)
        assertEquals(1, removed)
        assertEquals(lease, leases.current)
    }

    @Test fun ownerStopBeforeStartClosesAndReleasesWithoutIssuingGattOrRearmingField() {
        val leases = SessionPolicy()
        val field = BleFieldTrialPolicy(BleFieldConfig.IMPROVED, { 0 })
        val attempt = checkNotNull(field.signal(BleEvidenceSignal.CDM_BLE_APPEARED, 0).startAttempt)
        val lease = checkNotNull(leases.startDiagnostic(0))
        val port = Port()
        val removed = mutableListOf<Runnable>()
        val watchdog = Runnable {}
        val ownership = BleGattOwnership({ check(leases.finish(it, 0)) }, { removed.add(it) })
        var final: BleProbeState? = null
        var finishedCalls = 0
        val session = BleGattSession(lease.id, lease.deadline, port, { 0 }, {},
            release = { ownership.release(lease.id) },
            finishedCallback = {
                finishedCalls++
                final = it
                assertNull(ownership.current)
                assertNull(leases.current)
                assertEquals(1, port.closeCalls)
                assertNull(field.finished(attempt, it, leaseReleased = it.localClosed).startAttempt)
            })
        ownership.attach(session, 10)
        ownership.watch(lease.id, watchdog)
        assertEquals(BleProbeStatus.IDLE, session.state.status)

        // BLE_PROBE_STARTED 기록 실패가 호출하는 실제 소유자 stop 순서: 정책 봉인 → cancel.
        assertEquals(attempt, field.stop())
        session.cancel("FIELD_LOG_UNHEALTHY")
        session.start() // 기록 호출이 반환된 뒤 이어지는 start도 새 GATT를 발급하지 못한다.

        assertEquals(BleProbeStatus.CANCELED, final?.status)
        assertEquals("FIELD_LOG_UNHEALTHY", final?.reason)
        assertTrue(checkNotNull(final).localClosed)
        assertFalse(checkNotNull(final).connected)
        assertEquals(0, port.connectRequests)
        assertEquals(1, port.closeCalls)
        assertEquals(1, finishedCalls)
        assertNull(ownership.current)
        assertNull(ownership.associationId)
        assertNull(ownership.watchdog)
        assertNull(leases.current)
        assertEquals(listOf(watchdog), removed)
        assertFalse(field.accepting)
        assertNull(field.signal(BleEvidenceSignal.CDM_BLE_APPEARED, 0).startAttempt)
        assertNull(field.tick().startAttempt)
    }

    private class Port(private val closeFails: Boolean = false) : BleGattPort {
        var discoveryRequests = 0
        var connectRequests = 0
        var closeCalls = 0
        override fun connect(): Boolean { connectRequests++; return true }
        override fun discoverServices(): Boolean { discoveryRequests++; return true }
        override fun profile() = BleGattProfile(true, true, true, true, BleSubscriptionMode.NOTIFY)
        override fun setNotifications(enabled: Boolean) = true
        override fun writeSubscription(enabled: Boolean) = true
        override fun disconnect() = Unit
        override fun close() {
            closeCalls++
            if (closeFails) error("CLOSE_FAILED")
        }
    }
}
