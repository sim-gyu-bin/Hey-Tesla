package com.heytesla.app.vehicle.ble

import com.heytesla.app.diagnostics.TeslaApproachReadSummary

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** production pump의 예약·접수·완료 경계를 검증한다. 실행은 main 통합 소유자가 담당한다. */
class TeslaGattWritePumpTest {
    @Test fun cancelUnissuedFrameInvalidatesCapturedDispatchWithoutBorrowingTheNextFrame() {
        val h = Harness()
        assertTrue(h.pump.queue())
        val oldDispatch = h.tasks.first()
        assertTrue(h.pump.cancelUnissuedWrite())
        assertTrue(h.tasks.isEmpty())
        assertTrue(h.pump.queue())
        oldDispatch.run()
        assertEquals(0, h.submissions)
        assertTrue(h.pump.queued)
        h.runNext()
        assertEquals(1, h.submissions)
        assertFalse(h.pump.cancelUnissuedWrite())
        assertEquals(TeslaGattWriteCompletion.SUCCEEDED, h.pump.completeWrite(0))
    }

    @Test fun canceledBusyTaskCannotIssueTheNextPrimaryFrameOrCompleteItsCallback() {
        val h = Harness(sdkStatus = 201)
        assertTrue(h.pump.queue())
        h.runNext()
        val oldBusy = h.busyTasks.first().task
        assertTrue(h.pump.cancelUnissuedWrite())
        assertTrue(h.busyTasks.isEmpty())
        h.sdkStatus = 0
        assertTrue(h.pump.queue())
        oldBusy.run()
        assertEquals(1, h.submissions)
        assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
        h.runNext()
        assertEquals(2, h.submissions)
        assertFalse(h.pump.cancelUnissuedWrite())
        assertEquals(TeslaGattWriteCompletion.SUCCEEDED, h.pump.completeWrite(0))
    }

    @Test fun cancellationInsideRunningBusyAllowedCannotSealTheNextPrimaryQueue() {
        val h = Harness(sdkStatus = 201)
        assertTrue(h.pump.queue())
        h.runNext()
        h.onAllowed = {
            h.onAllowed = {}
            assertTrue(h.pump.cancelUnissuedWrite())
            h.sdkStatus = 0
            assertTrue(h.pump.queue())
        }
        h.runBusy()
        assertEquals(1, h.submissions)
        assertTrue(h.pump.queued)
        h.runNext()
        assertEquals(2, h.submissions)
        assertEquals(TeslaGattWriteCompletion.SUCCEEDED, h.pump.completeWrite(0))
    }

    @Test fun cancellationBeforeIssueCannotUnsealDescriptorsOrAnIssuedSdkRequest() {
        val h = Harness()
        assertTrue(h.pump.beginDescriptor(true))
        assertFalse(h.pump.cancelUnissuedWrite())
        assertTrue(h.pump.completeDescriptor())
        assertTrue(h.pump.queue())
        h.beforeIssue = { assertTrue(h.pump.cancelUnissuedWrite()) }
        h.runNext()
        assertEquals(0, h.submissions)
        h.beforeIssue = {}
        assertTrue(h.pump.queue())
        h.runNext()
        assertFalse(h.pump.cancelUnissuedWrite())
        h.pump.seal()
        assertFalse(h.pump.cancelUnissuedWrite())
        assertFalse(h.pump.queue())
    }

    @Test fun descriptorCompletionQueuesWithoutSubmittingInsideCallback() {
        val h = Harness()
        assertTrue(h.pump.beginDescriptor(true))
        assertFalse(h.pump.queue())
        assertTrue(h.pump.completeDescriptor())
        assertTrue(h.pump.queue())
        assertFalse(h.pump.completeDescriptor())
        assertFalse(h.pump.queue())
        assertFalse(h.pump.beginDescriptor(true))
        assertEquals(0, h.submissions)
        assertEquals(1, h.tasks.size)
        h.runNext()
        assertEquals(1, h.submissions)
        assertTrue(h.pump.writePending)
        assertFalse(h.pump.queue())
        assertFalse(h.pump.beginDescriptor(true))
    }

    @Test fun duplicateCallbacksCannotCompleteQueuedNextChunk() {
        val h = Harness()
        assertTrue(h.pump.queue())
        assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
        h.runNext()
        assertEquals(TeslaGattWriteCompletion.SUCCEEDED, h.pump.completeWrite(0))
        assertTrue(h.pump.queue())
        assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
        assertEquals(1, h.submissions)
        assertEquals(1, h.tasks.size)
        h.runNext()
        assertEquals(2, h.submissions)
        assertEquals(TeslaGattWriteCompletion.SUCCEEDED, h.pump.completeWrite(0))
    }

    @Test fun canceledRunnableCannotSubmitEvenIfRemovalRacesExecution() {
        val h = Harness()
        assertTrue(h.pump.queue())
        val stale = h.tasks.first()
        h.pump.seal()
        assertTrue(h.tasks.isEmpty())
        stale.run()
        assertFalse(h.pump.queue())
        assertEquals(0, h.submissions)
    }

    @Test fun expiredQueueDoesNotCallSdk() {
        val h = Harness()
        assertTrue(h.pump.queue())
        h.now = h.deadline
        h.runNext()
        assertEquals(0, h.submissions)
        assertFalse(h.pump.writePending)
    }

    @Test fun previousOwnerQueueCannotSubmitForNewOperation() {
        val old = Harness()
        assertTrue(old.pump.queue())
        old.ownsGatt = false
        val current = Harness()
        assertTrue(current.pump.queue())
        old.runNext()
        assertEquals(0, old.submissions)
        assertEquals(0, current.submissions)
        current.runNext()
        assertEquals(1, current.submissions)
    }

    @Test fun permissionLossBetweenQueueAndDispatchDoesNotCallSdk() {
        val h = Harness()
        assertTrue(h.pump.queue())
        h.permission = false
        h.runNext()
        assertEquals(0, h.submissions)
    }

    @Test fun sdkBusyThenAcceptanceWaitsForExactlyOneNativeCompletion() {
        val h = Harness(sdkStatus = 201)
        assertTrue(h.pump.queue())
        h.runNext()
        assertEquals(201, h.observedRequestStatus)
        assertEquals(1, h.submissions)
        assertEquals(0, h.pump.busyRetryCount)
        assertEquals(100L, h.busyTasks.first().due)
        assertTrue(h.pump.queued)
        assertFalse(h.pump.writePending)
        assertFalse(h.pump.queue())
        assertFalse(h.pump.beginDescriptor(true))
        assertFalse(h.pump.retryBusy(h.deadline))
        assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
        h.sdkStatus = 0
        h.runBusy()
        assertEquals(100L, h.now)
        assertEquals(2, h.submissions)
        assertEquals(1, h.pump.busyRetryCount)
        assertEquals(0, h.observedRequestStatus)
        assertTrue(h.pump.writePending)
        assertFalse(h.pump.retryBusy(h.deadline))
        assertEquals(TeslaGattWriteCompletion.SUCCEEDED, h.pump.completeWrite(0))
        assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
        assertTrue(h.busyTasks.isEmpty())
        assertTrue(h.pump.queue())
        assertEquals(0, h.pump.busyRetryCount)
    }

    @Test fun threeBusyRejectionsSealWithoutFourthSdkIssueOrLateCompletion() {
        val h = Harness(sdkStatus = 201)
        assertTrue(h.pump.queue())
        h.runNext()
        h.runBusy()
        assertEquals(2, h.submissions)
        assertEquals(1, h.pump.busyRetryCount)
        h.runBusy()
        assertEquals(3, h.submissions)
        assertEquals(2, h.pump.busyRetryCount)
        assertEquals(201, h.observedRequestStatus)
        assertFalse(h.pump.queued)
        assertFalse(h.pump.writePending)
        assertFalse(h.pump.retryBusy(h.deadline))
        assertFalse(h.pump.queue())
        assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
        assertTrue(h.busyTasks.isEmpty())
    }

    @Test fun sdkAcceptanceDoesNotTurnNativeFailureIntoCompletion() {
        val h = Harness()
        assertTrue(h.pump.queue())
        h.runNext()
        assertEquals(0, h.observedRequestStatus)
        assertEquals(TeslaGattWriteCompletion.FAILED, h.pump.completeWrite(133))
        assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
        assertFalse(h.pump.queue())
        assertEquals(1, h.submissions)
    }

    @Test fun nativeBusyCallbackAfterSdkAcceptanceIsNotAnUnacceptedSdkRetry() {
        val h = Harness(sdkStatus = 201)
        assertTrue(h.pump.queue())
        h.runNext()
        h.sdkStatus = 0
        h.runBusy()
        assertEquals(TeslaGattWriteCompletion.FAILED, h.pump.completeWrite(201))
        assertFalse(h.pump.retryBusy(h.deadline))
        assertFalse(h.pump.queue())
        assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
        assertEquals(2, h.submissions)
        assertEquals(1, h.pump.busyRetryCount)
        assertTrue(h.busyTasks.isEmpty())
    }

    @Test fun sdkSecurityAndOtherExceptionsSealWithoutRetryOrNativeCompletion() {
        for (failure in listOf(SecurityException(), IllegalArgumentException(), IllegalStateException())) {
            val h = Harness(sdkStatus = null, sdkFailure = failure)
            assertTrue(h.pump.queue())
            h.runNext()
            assertFalse(h.pump.retryBusy(h.deadline))
            assertFalse(h.pump.queue())
            assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
            assertEquals(1, h.submissions)
            assertEquals(0, h.pump.busyRetryCount)
            assertNull(h.observedRequestStatus)
            assertTrue(h.busyTasks.isEmpty())
        }
    }

    @Test fun nonBusySdkErrorsAndExceptionSentinelNeverRetry() {
        for (status in listOf(-1, 1, 133, 200, 202)) {
            val h = Harness(sdkStatus = status)
            assertTrue(h.pump.queue())
            h.runNext()
            assertFalse(h.pump.retryBusy(h.deadline))
            assertFalse(h.pump.queue())
            assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
            assertEquals(status, h.observedRequestStatus)
            assertEquals(1, h.submissions)
            assertEquals(0, h.pump.busyRetryCount)
            assertTrue(h.busyTasks.isEmpty())
        }
    }

    @Test fun busyReservationCannotSurviveOffPermissionOwnerLossOrCleanup() {
        val revoke: List<(Harness) -> Unit> = listOf(
            { it.enabled = false }, { it.permission = false }, { it.ownsGatt = false }, { it.pump.seal() },
        )
        for (cancel in revoke) {
            val h = Harness(sdkStatus = 201)
            assertTrue(h.pump.queue())
            h.runNext()
            val stale = h.busyTasks.first().task
            cancel(h)
            h.now = 100L
            stale.run()
            h.enabled = true
            h.permission = true
            h.ownsGatt = true
            stale.run()
            assertEquals(1, h.submissions)
            assertEquals(0, h.pump.busyRetryCount)
            assertFalse(h.pump.queue())
            assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
        }
    }

    @Test fun writeAndTotalDeadlineRejectDelayedBusyDispatchWithoutExtendingEither() {
        for (totalExpiresFirst in listOf(false, true)) {
            val h = Harness(sdkStatus = 201)
            if (totalExpiresFirst) h.deadline = 50_000L
            val limit = minOf(h.deadline, h.totalDeadline)
            h.now = limit - 150L
            assertTrue(h.pump.queue())
            h.runNext()
            h.now = limit
            h.runBusy()
            assertEquals(1, h.submissions)
            assertEquals(0, h.pump.busyRetryCount)
            assertFalse(h.pump.queue())
            assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
        }
    }

    @Test fun busyDelayMustFitBeforeOriginalDeadline() {
        val h = Harness(sdkStatus = 201)
        h.now = h.deadline - 100L
        assertTrue(h.pump.queue())
        h.runNext()
        assertTrue(h.busyTasks.isEmpty())
        assertFalse(h.pump.retryBusy(h.deadline))
        assertFalse(h.pump.queue())
        assertEquals(1, h.submissions)
        assertEquals(0, h.pump.busyRetryCount)
    }

    @Test fun consumedBusyGenerationCannotAccelerateNextReservation() {
        val h = Harness(sdkStatus = 201)
        assertTrue(h.pump.queue())
        h.runNext()
        val first = h.busyTasks.first().task
        h.runBusy()
        assertEquals(2, h.submissions)
        assertEquals(200L, h.busyTasks.first().due)
        first.run()
        assertEquals(2, h.submissions)
        assertEquals(1, h.pump.busyRetryCount)
        h.sdkStatus = 0
        h.runBusy()
        first.run()
        assertEquals(3, h.submissions)
        assertEquals(2, h.pump.busyRetryCount)
        assertEquals(TeslaGattWriteCompletion.SUCCEEDED, h.pump.completeWrite(0))
        assertTrue(h.pump.queue())
        first.run()
        assertEquals(3, h.submissions)
        assertEquals(0, h.pump.busyRetryCount)
    }

    @Test fun cancellationImmediatelyBeforeBusySdkIssueDoesNotIncrementRetryCount() {
        val h = Harness(sdkStatus = 201)
        assertTrue(h.pump.queue())
        h.runNext()
        h.beforeIssue = { h.pump.seal() }
        h.runBusy()
        assertEquals(1, h.submissions)
        assertEquals(0, h.pump.busyRetryCount)
        assertEquals(201, h.observedRequestStatus)
        assertFalse(h.pump.queue())
    }

    @Test fun callbackTimeoutAfterAcceptanceCannotScheduleBusyRetry() {
        val h = Harness()
        assertTrue(h.pump.queue())
        h.runNext()
        h.now = h.deadline
        h.pump.seal()
        assertFalse(h.pump.retryBusy(h.deadline))
        assertEquals(TeslaGattWriteCompletion.IGNORED, h.pump.completeWrite(0))
        assertEquals(1, h.submissions)
        assertTrue(h.busyTasks.isEmpty())
    }

    @Test fun rejectedBusySchedulingSealsWithoutConsumingAnotherSdkIssue() {
        val h = Harness(sdkStatus = 201)
        h.scheduleAccepted = false
        assertTrue(h.pump.queue())
        h.runNext()
        assertEquals(1, h.submissions)
        assertEquals(0, h.pump.busyRetryCount)
        assertTrue(h.busyTasks.isEmpty())
        assertFalse(h.pump.queue())
        assertFalse(h.pump.retryBusy(h.deadline))
    }

    @Test fun reentrantCancellationFromQueueObservationDoesNotPostOrSubmit() {
        val h = Harness()
        h.onQueued = { h.pump.seal() }
        assertFalse(h.pump.queue())
        assertTrue(h.tasks.isEmpty())
        assertEquals(0, h.submissions)
    }

    @Test fun failedQueueObservationSealsBeforeAnotherRequest() {
        val h = Harness()
        h.onQueued = { throw IllegalStateException() }
        try {
            h.pump.queue()
            fail("예약 관측 예외가 전달되어야 한다")
        } catch (_: IllegalStateException) {
            assertFalse(h.pump.queue())
            assertTrue(h.tasks.isEmpty())
            assertEquals(0, h.submissions)
        }
    }

    @Test fun rejectedStaleAndDuplicateCallbacksCannotClaimNativeWriteCompletion() {
        val submitted = TeslaApproachReadSummary(
            writeSequence = 1L, writeRequestedElapsedMs = 10L,
            writeRequestStatus = 0, writeDispatchQueued = false,
        )
        val rejected = submitted.copy(writeRequestStatus = 201)
        assertNull(completedNativeWriteObservation(rejected, rejected, 0, 15L))
        val queued = submitted.copy(writeDispatchQueued = true)
        assertNull(completedNativeWriteObservation(queued, queued, 0, 15L))
        val otherOwner = submitted.copy()
        assertNull(completedNativeWriteObservation(submitted, otherOwner, 0, 15L))
        val failed = checkNotNull(completedNativeWriteObservation(submitted, submitted, 133, 15L))
        assertEquals(133, failed.writeCallbackStatus)
        assertEquals(15L, failed.writeCallbackElapsedMs)
        assertNull(completedNativeWriteObservation(failed, submitted, 0, 16L))
        assertNull(completedNativeWriteObservation(failed, failed, 0, 16L))
    }

    private class Harness(var sdkStatus: Int? = 0, val sdkFailure: Exception? = null) {
        data class Scheduled(val task: Runnable, val due: Long)
        val tasks = ArrayDeque<Runnable>()
        val busyTasks = ArrayDeque<Scheduled>()
        var now = 0L
        var deadline = 5_000L
        val totalDeadline = 40_000L
        var enabled = true
        var scheduleAccepted = true
        var permission = true
        var ownsGatt = true
        var submissions = 0
        var observedRequestStatus: Int? = null
        var onQueued: () -> Unit = {}
        var beforeIssue: () -> Unit = {}
        var onAllowed: () -> Unit = {}
        val pump = TeslaGattWritePump(
            post = { tasks.addLast(it) },
            postDelayed = { task, delay ->
                if (scheduleAccepted) busyTasks.addLast(Scheduled(task, now + delay))
                scheduleAccepted
            },
            now = { now },
            remove = { task ->
                tasks.remove(task)
                busyTasks.removeAll { it.task === task }
            },
            allowed = { onAllowed(); enabled && permission && ownsGatt && now < minOf(deadline, totalDeadline) },
            onQueued = { onQueued() },
            submit = { submit() },
        )

        private fun submit() {
            beforeIssue()
            if (!pump.markWriteIssued()) return
            submissions++
            observedRequestStatus = sdkStatus
            pump.recordWriteResult(sdkStatus, sdkFailure)
            if (sdkStatus == 201) pump.retryBusy(minOf(deadline, totalDeadline))
        }

        fun runNext() = tasks.removeFirst().run()
        fun runBusy() {
            val scheduled = busyTasks.removeFirst()
            now = maxOf(now, scheduled.due)
            scheduled.task.run()
        }
    }
}
