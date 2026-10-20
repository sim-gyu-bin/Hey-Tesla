package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class TeslaGattConnectRetryTest {
    @Test fun onlyInitialEligible133WaitsTwoSecondsAndReconnectsOnce() {
        val h = Harness()
        assertFalse(h.retry.retry(8, true))
        assertFalse(h.retry.retry(133, false))
        assertTrue(h.retry.retry(133, true))
        h.now = 1_500L
        assertTrue(h.retry.retry(133, true))
        assertEquals(1, h.tasks.size)
        h.now = 1_999L
        h.runNext()
        assertEquals(1L, h.tasks.first().second)
        assertEquals(0, h.connects)
        h.now = 2_000L
        val duplicate = h.tasks.first().first
        h.runNext()
        duplicate.run()
        assertEquals(1, h.connects)
        assertFalse(h.retry.retry(133, true))
        assertEquals(listOf("TESLA_APPROACH_GATT_RETRY_SCHEDULED",
            "TESLA_APPROACH_GATT_RETRY_STARTED"), h.events)
    }

    @Test fun connectedDuringWaitCancelsReconnectAndInitializesExactlyOnce() {
        val h = Harness()
        val native = Any()
        val owner = TeslaNativeGattOwner<Any>()
        assertTrue(owner.beginConnect())
        owner.attach(native)
        val captured = owner.connectionGeneration
        assertTrue(h.retry.retry(133, owner.canReconnect))
        val lateWake = h.tasks.first().first
        var discoveries = 0
        repeat(2) {
            if (owner.connected(native, captured)) {
                h.retry.seal()
                val request = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.SERVICES, h.now))
                assertTrue(owner.recordRequestResult(request, 0))
                discoveries++
            }
        }
        h.now = 2_000L
        lateWake.run()
        assertEquals(0, h.connects)
        assertEquals(1, discoveries)
        assertFalse(h.retry.retry(133, owner.canReconnect))
        assertEquals(listOf("TESLA_APPROACH_GATT_RETRY_SCHEDULED",
            "TESLA_APPROACH_GATT_RETRY_CANCELED"), h.events)
    }

    @Test fun readinessRevocationCancelsEvenAnAlreadyDispatchedWake() {
        val h = Harness()
        h.retry.retry(133, true)
        val late = h.tasks.first().first
        h.permitted = false // OFF·권한·BT·owner·수동 UI 경계는 production allowed가 취소한다.
        h.now = 2_000L
        h.runNext()
        late.run()
        assertEquals(0, h.connects)
        assertEquals(1, h.events.count { it == "TESLA_APPROACH_GATT_RETRY_CANCELED" })
    }

    @Test fun originalStageDeadlineWinsOverRetryWait() {
        val h = Harness()
        h.now = 19_000L
        h.retry.retry(133, true)
        h.now = h.stageDeadline
        h.runNext()
        assertEquals(0, h.connects)
        assertFalse(h.retry.retry(133, true))
    }

    @Test fun overallDeadlineWinsWithoutExtendingTheStageDeadline() {
        val h = Harness()
        h.stageDeadline = 45_000L
        h.now = 39_000L
        h.retry.retry(133, true)
        h.now = h.deadline
        h.runNext()
        assertEquals(0, h.connects)
        assertFalse(h.retry.retry(133, true))
    }

    @Test fun canceledOldWakeCannotRunNewOwnerConnection() {
        val old = Harness()
        old.retry.retry(133, true)
        val late = old.tasks.first().first
        old.retry.seal()
        old.retry.seal()
        val next = Harness()
        next.retry.retry(133, true)
        old.now = 2_000L
        late.run()
        assertEquals(0, old.connects)
        assertEquals(0, next.connects)
        assertEquals(1, old.events.count { it == "TESLA_APPROACH_GATT_RETRY_CANCELED" })
        next.now = 2_000L
        next.runNext()
        assertEquals(1, next.connects)
    }

    @Test fun observationReentryCanCancelScheduledOrStartedConnection() {
        for (event in listOf("TESLA_APPROACH_GATT_RETRY_SCHEDULED", "TESLA_APPROACH_GATT_RETRY_STARTED")) {
            val h = Harness()
            h.observe = { if (it == event) h.retry.seal() }
            h.retry.retry(133, true)
            h.now = 2_000L
            if (h.tasks.isNotEmpty()) h.runNext()
            assertEquals(0, h.connects)
        }
    }

    @Test fun rejectedHandlerReservationFailsOnceAndNeverReconnects() {
        val h = Harness()
        h.accept = false
        h.retry.retry(133, true)
        h.retry.seal()
        assertEquals(1, h.failures)
        assertEquals(0, h.connects)
        assertTrue(h.events.contains("TESLA_APPROACH_GATT_RETRY_CANCELED"))
        assertFalse(h.retry.retry(133, true))
    }

    private class Harness {
        var now = 0L
        val deadline = 40_000L
        var stageDeadline = 20_000L
        var permitted = true
        var accept = true
        var failures = 0
        var connects = 0
        var observe: (String) -> Unit = {}
        val events = mutableListOf<String>()
        val tasks = ArrayDeque<Pair<Runnable, Long>>()
        val retry = TeslaGattConnectRetry(
            now = { now },
            postDelayed = { task, delay -> if (accept) tasks.add(task to delay); accept },
            remove = { task -> tasks.removeAll { it.first === task } },
            allowed = { permitted && now < minOf(deadline, stageDeadline) },
            event = { events.add(it); observe(it) },
            failed = { failures++ },
            connect = { connects++ },
        )
        fun runNext() = tasks.removeFirst().first.run()
    }
}
