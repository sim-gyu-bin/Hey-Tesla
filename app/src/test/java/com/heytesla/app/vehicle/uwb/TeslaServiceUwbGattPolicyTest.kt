package com.heytesla.app.vehicle.uwb

import com.heytesla.app.vehicle.ble.TeslaGattConnectRetry
import com.heytesla.app.vehicle.ble.TeslaNativeGattOwner
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.key.teslaGattConnectRetryEligible
import com.heytesla.app.vehicle.key.teslaGattDeadlineExpired

import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

/** 실제 생산 admission/기한/133 정책과 native owner를 함께 실행한다. 무선·키 저장소는 사용하지 않는다. */
class TeslaServiceUwbGattPolicyTest {
    @Test fun staleInitialAdvertisementNeverIssuesOrOwnsAHandle() {
        val h = Harness()
        h.now = 11_001L
        assertEquals("SCAN_RESULT_STALE", h.reason())
        assertFalse(h.create())
        assertEquals(0, h.creates)
        assertNull(h.owner.handle)
        assertTrue(h.admission.requireFresh)
    }

    @Test fun returnedOwnedHandleContinuesBeyondTenSecondsWithoutStartingHandshakeOrExtendingDeadlines() {
        val h = Harness()
        assertTrue(h.create())
        val stageDeadline = h.stageDeadline
        val deadline = h.deadline
        h.now = 12_001L
        assertTrue(h.now - h.sourceElapsedMs > 10_000L)
        assertNull(h.reason())
        assertFalse(h.handshakeStarted)
        assertEquals(stageDeadline, h.stageDeadline)
        assertEquals(deadline, h.deadline)
        assertEquals(1_000L, h.sourceElapsedMs)
        assertSame(h.native, h.owner.handle)
    }

    @Test fun nullThrowAndDifferentHandleNeverAdmitAndAnewOperationRequiresFreshnessAgain() {
        for (mode in listOf("NULL", "THROW", "WRONG_OWNER")) {
            val h = Harness()
            assertFalse(h.create(mode))
            assertTrue(h.admission.requireFresh)
            h.now = 11_001L
            assertEquals("SCAN_RESULT_STALE", h.reason())
        }
        val admitted = Harness()
        assertTrue(admitted.create())
        admitted.admission.returnedHandleOwned(null, admitted.native)
        assertFalse(admitted.admission.requireFresh) // 접수 필드 초기화/null은 입장을 되돌리지 않는다.
        assertTrue(Harness().admission.requireFresh)
    }

    @Test fun cancellationDuringAllocationStillOwnsAndClosesReturnButCannotContinue() {
        val h = Harness()
        assertTrue(h.owner.beginConnect())
        h.sealed = true
        h.owner.seal()
        h.owner.attach(h.native)
        h.admission.returnedHandleOwned(h.native, h.owner.handle)
        assertFalse(h.admission.requireFresh)
        assertEquals("SEALED", h.reason())
        assertFalse(h.owner.accepts(h.native))
        var closes = 0
        assertTrue(h.owner.closeConfirmed({}, { closes++ }))
        assertEquals(1, closes)
        assertNull(h.owner.handle)
    }

    @Test fun admissionDoesNotBypassOffOwnerVinPermissionsFgsBluetoothAssociationOrDeadlines() {
        val cases: List<Pair<String, (Harness) -> Unit>> = listOf(
            "FIELD_OWNER_NOT_ARMED" to { it.serviceOn = false },
            "FIELD_OWNER_NOT_ARMED" to { it.ownerArmed = false },
            "BLE_SCAN_FGS_NOT_RUNNING" to { it.scanReady = false },
            "RANGING_PERMISSION_REQUIRED" to { it.rangingPermission = false },
            "ASSOCIATION_CHANGED" to { it.associationMatches = false },
            "UWB_VIN_SNAPSHOT_CHANGED" to { it.targetReason = "UWB_VIN_SNAPSHOT_CHANGED" },
            "BLUETOOTH_OFF" to { it.fieldReason = "BLUETOOTH_OFF" },
            "BLUETOOTH_PERMISSION_REQUIRED" to { it.fieldReason = "BLUETOOTH_PERMISSION_REQUIRED" },
            "DEADLINE_EXPIRED" to { it.now = it.stageDeadline },
            "DEADLINE_EXPIRED" to { it.stageDeadline = Long.MAX_VALUE; it.now = it.deadline },
        )
        for ((expected, revoke) in cases) {
            val h = Harness()
            assertTrue(h.create())
            h.now = 12_001L
            revoke(h)
            assertEquals(expected, h.reason())
            assertTrue(h.admission.requireFresh.not())
            assertEquals(1, h.creates)
        }
    }

    @Test fun ownerRevocationShortCircuitsAllLaterReadinessAccess() {
        var laterReads = 0
        assertEquals("FIELD_OWNER_NOT_ARMED", serviceUwbReadinessReason(
            ownerArmed = { false }, scanReady = { laterReads++; error("owner 이후 접근 금지") },
            initialBudgetExpired = { laterReads++; false }, deviceAvailable = { laterReads++; true },
            sourceTimestampNanos = 1_000_000_000L, now = { laterReads++; 12_001L }, requireFresh = false,
            rangingPermission = { laterReads++; true }, associationMatches = { laterReads++; true },
            targetReason = { laterReads++; null }, fieldReason = { laterReads++; null },
        ))
        assertEquals(0, laterReads)
    }

    @Test fun initial133UsesTheSameOwnedHandleAfterTwoSecondsExactlyOnceDespiteOldAdvertisement() {
        val h = Harness()
        assertTrue(h.create())
        val originalGeneration = h.owner.connectionGeneration
        val originalStageDeadline = h.stageDeadline
        h.now = 12_001L
        assertTrue(h.retry.retry(133, h.retryEligible()))
        assertTrue(h.retry.retry(133, h.retryEligible()))
        h.now = 14_000L
        h.runWake()
        assertEquals(0, h.reconnects)
        h.now = 14_001L
        h.runWake()
        assertEquals(1, h.reconnects)
        assertEquals(1, h.creates)
        assertSame(h.native, h.owner.handle)
        assertFalse(h.owner.accepts(h.native, originalGeneration))
        assertFalse(h.retry.retry(133, h.retryEligible()))
        assertEquals(originalStageDeadline, h.stageDeadline)
        assertFalse(h.admission.requireFresh)
    }

    @Test fun lateTransmittedProgressedManualAndUnownedConnectionsCannotRetry133() {
        val disqualifiers: List<(Harness) -> Unit> = listOf(
            { it.transmissionAttempted = true },
            { it.handshakeStarted = true },
            { it.stage = TeslaKeyStage.DISCOVERING },
            { assertTrue(it.owner.connected(it.native)) },
            { it.serviceUwb = false },
            { it.register = true },
            { it.sameHandle = false },
            { it.connected = true },
        )
        for (disqualify in disqualifiers) {
            val h = Harness()
            assertTrue(h.create())
            disqualify(h)
            assertFalse(h.retry.retry(133, h.retryEligible()))
            assertTrue(h.tasks.isEmpty())
            assertEquals(0, h.reconnects)
        }
    }

    @Test fun pendingRetryCannotSurviveOwnerLossOrOriginalTwentySecondAndOverallBudgets() {
        val revocations: List<(Harness) -> Unit> = listOf(
            { it.serviceOn = false }, { it.ownerArmed = false },
            { it.targetReason = "UWB_VIN_SNAPSHOT_CHANGED" },
            { it.rangingPermission = false }, { it.scanReady = false },
            { it.now = it.stageDeadline },
            { it.stageDeadline = Long.MAX_VALUE; it.now = it.deadline },
        )
        for (revoke in revocations) {
            val h = Harness()
            assertTrue(h.create())
            h.now = 12_001L
            assertTrue(h.retry.retry(133, h.retryEligible()))
            val late = h.tasks.first().first
            h.now += 2_000L
            revoke(h)
            h.runWake()
            late.run()
            assertEquals(0, h.reconnects)
            assertEquals(1, h.events.count { it == "TESLA_APPROACH_GATT_RETRY_CANCELED" })
            assertFalse(h.retry.retry(133, h.retryEligible()))
        }
    }

    private class Harness {
        val sourceElapsedMs = 1_000L
        var now = 9_000L
        val deadline = 181_000L
        var stageDeadline = now + 20_000L
        var sealed = false
        var serviceOn = true
        var ownerArmed = true
        var scanReady = true
        var rangingPermission = true
        var associationMatches = true
        var targetReason: String? = null
        var fieldReason: String? = null
        var transmissionAttempted = false
        var handshakeStarted = false
        var stage = TeslaKeyStage.CONNECTING
        var serviceUwb = true
        var register = false
        var sameHandle = true
        var connected = false
        var creates = 0
        var reconnects = 0
        val native = Any()
        val owner = TeslaNativeGattOwner<Any>()
        val admission = TeslaServiceUwbGattAdmission()
        val tasks = ArrayDeque<Pair<Runnable, Long>>()
        val events = mutableListOf<String>()
        val retry = TeslaGattConnectRetry(
            now = { now }, postDelayed = { task, delay -> tasks.add(task to delay); true },
            remove = { task -> tasks.removeAll { it.first === task } },
            allowed = { reason() == null }, event = { events.add(it) },
            failed = { fail("예약 거절 없음") },
            connect = {
                assertEquals(true, owner.reconnect {
                    assertSame(native, it)
                    reconnects++
                    true
                })
            },
        )

        fun reason(): String? {
            if (sealed) return "SEALED"
            if (teslaGattDeadlineExpired(now, deadline, stageDeadline, Long.MAX_VALUE)) return "DEADLINE_EXPIRED"
            return serviceUwbReadinessReason(
                ownerArmed = { serviceOn && ownerArmed }, scanReady = { scanReady },
                initialBudgetExpired = { now >= deadline }, deviceAvailable = { true },
                sourceTimestampNanos = sourceElapsedMs * 1_000_000L, now = { now },
                requireFresh = admission.requireFresh, rangingPermission = { rangingPermission },
                associationMatches = { associationMatches }, targetReason = { targetReason },
                fieldReason = { fieldReason },
            )
        }

        fun create(mode: String = "OWNED"): Boolean {
            if (reason() != null) return false
            assertTrue(owner.beginConnect())
            creates++
            val returned = try {
                if (mode == "THROW") throw SecurityException("격리된 SDK 할당 실패")
                native.takeUnless { mode == "NULL" }
            } catch (_: SecurityException) {
                owner.allocationFailed()
                return false
            }
            owner.attach(returned)
            admission.returnedHandleOwned(returned, if (mode == "WRONG_OWNER") Any() else owner.handle)
            return !admission.requireFresh
        }

        fun retryEligible() = teslaGattConnectRetryEligible(
            serviceUwb = serviceUwb, register = register, stage = stage,
            transmissionAttempted = transmissionAttempted, handshakeStarted = handshakeStarted,
            connectionProgressed = owner.connectionProgressed,
            sameHandleReconnectable = sameHandle && owner.canReconnect, connected = connected,
        )

        fun runWake() = tasks.removeFirst().first.run()
    }
}
