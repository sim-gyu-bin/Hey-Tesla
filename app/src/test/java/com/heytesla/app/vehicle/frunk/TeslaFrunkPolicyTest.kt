package com.heytesla.app.vehicle.frunk

import com.heytesla.app.vehicle.ble.TeslaGattWriteCompletion
import com.heytesla.app.vehicle.ble.TeslaGattWritePump
import com.heytesla.app.vehicle.protocol.TeslaDriveState
import com.heytesla.app.vehicle.protocol.TeslaFrunkReply
import com.heytesla.app.vehicle.protocol.TeslaGear
import com.heytesla.app.vehicle.protocol.TeslaReadOnlyStatus

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** 생산 정책과 write pump를 직접 소비한다. 실행은 메인 통합 검증 담당이다. */
class TeslaFrunkPolicyTest {
    @Test fun sourceWallAllowsOnlyBoundedVehicleClockSkewAndPreservesMonotonicStrictness() {
        for ((offset, allowed) in listOf(-10_001L to false, -10_000L to true,
            1_061L to true, 2_000L to true, 2_001L to false)) {
            val h = Harness()
            val reason = h.policy.acceptDrive(h.park(source = h.wall + offset))
            assertEquals("source offset=$offset", allowed, reason == null)
            assertEquals(allowed, h.policy.driveVerified)
        }
        for (received in listOf(100_001L, 94_999L)) {
            val h = Harness(commandAcceptedAtElapsedMs = 90_000L)
            assertNotNull(h.policy.acceptDrive(h.park(received = received, source = h.wall + 1_061L)))
            assertFalse(h.policy.driveVerified)
        }
        val boundary = Harness(commandAcceptedAtElapsedMs = 90_000L)
        assertNull(boundary.policy.acceptDrive(boundary.park(received = 95_000L)))
    }

    @Test fun missingUnknownOrNonParkDriveNeverAuthorizesActuator() {
        val values: List<(Harness) -> TeslaDriveState?> = listOf(
            { null }, { it.park(source = null) }, { it.park(source = 0) },
            { it.park(source = Long.MAX_VALUE) },
            { it.park().copy(gear = TeslaGear.UNKNOWN) }, { it.park().copy(gear = TeslaGear.R) },
            { it.park().copy(gear = TeslaGear.N) }, { it.park().copy(gear = TeslaGear.D) },
        )
        for (value in values) {
            val h = Harness()
            assertNotNull(h.policy.acceptDrive(value(h)))
            assertNotNull(h.policy.beginWrite(0, 100))
            assertFalse(h.policy.mayHaveSent)
        }
    }

    @Test fun parkAndSourceAgeAreRecheckedAtActualSdkBoundary() {
        for (revoke in listOf<(Harness) -> Unit>(
            { it.elapsed += 5_001L },
            { it.wall += 10_001L },
            { it.wall -= 2_001L },
            { it.elapsed = it.policy.deadline },
            { it.elapsed-- },
            { it.policy.cancel() },
        )) {
            val h = Harness()
            assertNull(h.policy.acceptDrive(h.park()))
            revoke(h)
            assertNotNull(h.policy.beginWrite(0, 100))
            assertFalse(h.policy.commandSent)
            assertEquals(TeslaFrunkResult.NOT_SENT, h.policy.result)
        }
    }

    @Test fun acceptedCommandCannotBeFutureExpiredReplayedOrOverflowed() {
        val gate = TeslaFrunkConsentGate()
        assertEquals("FRUNK_COMMAND_INVALID", gate.claim(-1, 100_000))
        assertEquals("FRUNK_COMMAND_INVALID", gate.claim(100_001, 100_000))
        assertEquals("FRUNK_COMMAND_EXPIRED", gate.claim(60_000, 100_000))
        assertEquals("FRUNK_COMMAND_INVALID", gate.claim(Long.MAX_VALUE, Long.MAX_VALUE))
        assertNull(gate.claim(60_001, 100_000))
        assertEquals("FRUNK_COMMAND_ALREADY_CONSUMED", gate.claim(60_001, 100_000))
        assertEquals("FRUNK_COMMAND_ALREADY_CONSUMED", gate.claim(60_000, 99_999))
        assertNull(gate.claim(100_000, 100_000))
        assertEquals("FRUNK_COMMAND_ALREADY_CONSUMED", gate.claim(100_000, 100_001))
        val h = Harness()
        assertEquals(140_000L, h.policy.deadline)
        h.elapsed = 139_999
        assertNull(h.policy.readinessReason())
        h.elapsed = 140_000
        assertEquals("FRUNK_COMMAND_EXPIRED", h.policy.readinessReason())
    }

    @Test fun sdkBusyCanRetryOnlyUnacceptedOffsetAndNeverConsumesNativeCallback() {
        val h = Harness()
        assertNull(h.policy.acceptDrive(h.park()))
        assertNull(h.policy.beginWrite(0, 100))
        h.policy.recordSdkResult(201)
        assertFalse(h.policy.recordWriteCallback(0, true))
        assertFalse(h.policy.commandSent)
        assertFalse(h.policy.mayHaveSent)
        assertNull(h.policy.beginWrite(0, 100))
        h.policy.recordSdkResult(0)
        assertTrue(h.policy.commandSent)
        assertNotNull(h.policy.beginWrite(0, 100))
        assertTrue(h.policy.recordWriteCallback(0, false))
        assertNotNull(h.policy.beginWrite(0, 100))
        assertNull(h.policy.beginWrite(100, 50))
        h.policy.recordSdkResult(0)
        assertTrue(h.policy.recordWriteCallback(0, true))
        assertNotNull(h.policy.beginWrite(150, 1))
        assertNotNull(h.policy.beginWrite(0, 150))
        assertEquals(TeslaFrunkResult.UNKNOWN, h.policy.result)
    }

    @Test fun acceptedNativeFailureAndDispatchExceptionRemainUnknownWithoutReplay() {
        for (exception in listOf(false, true)) {
            val h = Harness()
            assertNull(h.policy.acceptDrive(h.park()))
            assertNull(h.policy.beginWrite(0, 100))
            h.policy.recordSdkResult(if (exception) null else 0, failed = exception)
            assertFalse(h.policy.recordWriteCallback(201, true))
            assertNotNull(h.policy.beginWrite(0, 100))
            h.policy.recordReply(TeslaFrunkReply.ACKNOWLEDGED)
            h.policy.observeBody(TeslaReadOnlyStatus(frontTrunkState = 1))
            assertFalse(h.policy.openObserved)
            assertEquals(TeslaFrunkResult.UNKNOWN, h.policy.result)
        }
    }

    @Test fun cancellationBeforeSdkPreventsIssueAndAfterSdkNeverClaimsWithdrawal() {
        val before = Harness()
        assertNull(before.policy.acceptDrive(before.park()))
        before.policy.cancel()
        assertNotNull(before.policy.beginWrite(0, 100))
        assertEquals(TeslaFrunkResult.NOT_SENT, before.policy.result)
        val after = Harness()
        after.completeFrame()
        after.policy.cancel()
        after.policy.recordReply(TeslaFrunkReply.ACKNOWLEDGED)
        after.policy.observeBody(TeslaReadOnlyStatus(frontTrunkState = 1))
        assertNotNull(after.policy.beginWrite(0, 100))
        assertFalse(after.policy.commandAcknowledged)
        assertFalse(after.policy.openObserved)
        assertEquals(TeslaFrunkResult.UNKNOWN, after.policy.result)
    }

    @Test fun consumerDistinguishesSdkAckOpenUnknownAndAuthenticatedRejection() {
        for (reply in TeslaFrunkReply.entries) {
            for (closure in listOf(null, 0, 1, 2, 3, 4, 5, 6, 99)) {
                val h = Harness()
                h.completeFrame()
                assertEquals(TeslaFrunkResult.UNKNOWN, h.policy.result)
                h.policy.recordReply(reply)
                assertEquals(reply == TeslaFrunkReply.ACKNOWLEDGED, h.policy.commandAcknowledged)
                assertFalse(h.policy.openObserved)
                val body = TeslaReadOnlyStatus(frontTrunkState = closure)
                h.policy.observeBody(body)
                assertEquals(closure, body.frontTrunkState)
                assertEquals(closure == 1 && reply != TeslaFrunkReply.REJECTED, h.policy.openObserved)
                val expected = when {
                    reply == TeslaFrunkReply.REJECTED -> TeslaFrunkResult.REJECTED
                    reply == TeslaFrunkReply.ACKNOWLEDGED && closure == 1 -> TeslaFrunkResult.OPEN_OBSERVED
                    else -> TeslaFrunkResult.UNKNOWN
                }
                assertEquals(expected, h.policy.result)
            }
        }
    }

    @Test fun productionPumpRetries201ButNotSdk0CallbackFailureOrExpiredPark() {
        val busy = WriteHarness()
        busy.sdkStatus = 201
        assertTrue(busy.pump.queue())
        busy.runNext()
        assertEquals(1, busy.issues)
        assertEquals(TeslaGattWriteCompletion.IGNORED, busy.callback(0))
        busy.sdkStatus = 0
        busy.runNext()
        assertEquals(2, busy.issues)
        assertEquals(TeslaGattWriteCompletion.FAILED, busy.callback(201))
        assertFalse(busy.pump.retryBusy(busy.h.policy.deadline))
        assertFalse(busy.pump.queue())
        assertEquals(TeslaFrunkResult.UNKNOWN, busy.h.policy.result)
        val expired = WriteHarness()
        expired.sdkStatus = 201
        assertTrue(expired.pump.queue())
        expired.runNext()
        expired.h.elapsed += 5_001
        expired.runNext()
        assertEquals(1, expired.issues)
        assertFalse(expired.h.policy.mayHaveSent)
    }

    private class Harness(commandAcceptedAtElapsedMs: Long = 100_000L) {
        var elapsed = 100_000L
        var wall = 1_792_360_988_423L
        val policy = TeslaFrunkPolicy(commandAcceptedAtElapsedMs, { elapsed }, { wall })
        fun park(received: Long = elapsed, source: Long? = wall) = TeslaDriveState(TeslaGear.P, source, received)
        fun completeFrame() {
            assertNull(policy.acceptDrive(park()))
            assertNull(policy.beginWrite(0, 100))
            policy.recordSdkResult(0)
            assertTrue(policy.recordWriteCallback(0, true))
        }
    }

    private class WriteHarness {
        val h = Harness().also { assertNull(it.policy.acceptDrive(it.park())) }
        val tasks = ArrayDeque<Runnable>()
        var sdkStatus = 0
        var issues = 0
        val pump: TeslaGattWritePump = TeslaGattWritePump(
            post = { tasks.addLast(it) },
            postDelayed = { task, _ -> tasks.addLast(task); true },
            now = { h.elapsed }, remove = { tasks.remove(it) },
            allowed = { h.policy.authorizeActuator() == null }, onQueued = {},
            submit = ::submit,
        )
        private fun submit() {
            if (h.policy.beginWrite(0, 100) != null) return
            assertTrue(pump.markWriteIssued())
            issues++
            h.policy.recordSdkResult(sdkStatus)
            pump.recordWriteResult(sdkStatus)
            if (sdkStatus == 201) pump.retryBusy(h.policy.deadline)
        }
        fun runNext() { tasks.removeFirst().run() }
        fun callback(status: Int): TeslaGattWriteCompletion {
            val result = pump.completeWrite(status)
            if (result != TeslaGattWriteCompletion.IGNORED) h.policy.recordWriteCallback(status, true)
            return result
        }
    }
}
