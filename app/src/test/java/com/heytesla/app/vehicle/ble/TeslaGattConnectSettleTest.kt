package com.heytesla.app.vehicle.ble

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class TeslaGattConnectSettleTest {
    @Test fun cancelDuringSettlePreventsConnectEvenWhenRemovedRunnableRunsLate() {
        val h = Harness()
        h.settle.start()
        val late = h.tasks.first().task
        h.now = 250L
        h.settle.seal()
        assertTrue(h.tasks.isEmpty())
        h.now = 900L
        late.run()
        h.settle.start()
        assertEquals(0, h.connects)
        assertNull(h.connectedAt)
    }

    @Test fun earlyHandlerWakeWaitsRemainingElapsedTimeWithoutRestartingSettle() {
        val h = Harness()
        h.settle.start()
        assertEquals(500L, h.tasks.first().delay)
        h.now = 499L
        h.runNext()
        assertEquals(0, h.connects)
        assertEquals(1L, h.tasks.first().delay)
        h.now = 500L
        val duplicate = h.tasks.first().task
        h.runNext()
        duplicate.run()
        assertEquals(1, h.connects)
        assertEquals(500L, h.connectedAt)
    }

    @Test fun ownerFreshnessPermissionOrDeadlineLossBeforeWakePreventsConnect() {
        for (loss in listOf<(Harness) -> Unit>(
            { it.owner = false }, { it.fresh = false }, { it.permission = false },
            { it.now = it.deadline },
        )) {
            val h = Harness()
            h.settle.start()
            h.now = 500L
            loss(h)
            h.runNext()
            assertEquals(0, h.connects)
            assertNull(h.connectedAt)
        }
    }

    @Test fun deepSleepElapsedDeadlineIsNotExtendedByLateHandlerExecution() {
        val h = Harness()
        h.settle.start()
        h.now = 60_000L
        h.runNext()
        assertEquals(0, h.connects)
        assertNull(h.connectedAt)
    }

    @Test fun finalOwnerCheckCanCancelOrRevokeAdmissionBeforeConnect() {
        for (cancel in listOf<(Harness) -> Unit>(
            { it.settle.seal() }, { it.owner = false },
        )) {
            val h = Harness()
            h.onAllowed = {
                if (h.now >= 500L) {
                    h.onAllowed = {}
                    cancel(h)
                }
            }
            h.settle.start()
            h.now = 500L
            h.runNext()
            assertNull(h.connectedAt)
            assertEquals(0, h.connects)
        }
    }

    @Test fun oldGenerationCannotConnectOrCompleteNewGeneration() {
        val old = Harness()
        old.settle.start()
        val stale = old.tasks.first().task
        old.settle.seal()
        val current = Harness()
        current.settle.start()
        old.now = 500L
        stale.run()
        assertEquals(0, old.connects)
        assertEquals(0, current.connects)
        assertNull(current.connectedAt)
        current.now = 500L
        current.runNext()
        assertEquals(1, current.connects)
    }

    @Test fun rejectedSchedulingSealsWithoutAttemptingConnect() {
        val h = Harness()
        h.acceptSchedule = false
        h.settle.start()
        h.now = 500L
        h.settle.start()
        assertEquals(1, h.scheduleFailures)
        assertEquals(0, h.connects)
        assertNull(h.connectedAt)
    }

    private data class Pending(val task: Runnable, val delay: Long)

    private class Harness {
        val tasks = ArrayDeque<Pending>()
        var now = 0L
        val deadline = 40_000L
        var owner = true
        var fresh = true
        var permission = true
        var acceptSchedule = true
        var connectedAt: Long? = null
        var onAllowed: () -> Unit = {}
        var scheduleFailures = 0
        var connects = 0
        val settle = TeslaGattConnectSettle(
            now = { now },
            postDelayed = { task, delay ->
                if (acceptSchedule) tasks.addLast(Pending(task, delay))
                acceptSchedule
            },
            remove = { task -> tasks.removeAll { it.task === task } },
            allowed = {
                val admitted = owner && fresh && permission && now < deadline
                onAllowed()
                admitted
            },
            onScheduleFailed = { scheduleFailures++ },
            connect = { connects++; connectedAt = now },
        )
        fun runNext() = tasks.removeFirst().task.run()
    }
}
