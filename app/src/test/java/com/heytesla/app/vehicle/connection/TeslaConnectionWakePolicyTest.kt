package com.heytesla.app.vehicle.connection

import com.heytesla.app.vehicle.protocol.TeslaDriveState
import com.heytesla.app.vehicle.protocol.TeslaGear
import com.heytesla.app.vehicle.protocol.TeslaReadOnlyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaConnectionWakePolicyTest {
    private val elapsed = 20_000L

    private fun body(receipt: Long? = elapsed, sleep: Int? = 2) = TeslaReadOnlyStatus(
        bodyVehicleSleepStatus = sleep, bodyReceivedAtElapsedRealtime = receipt,
    )

    @Test fun onlyFreshAuthenticatedAsleepBodyCanAuthorizeWake() {
        var current = elapsed
        val policy = TeslaConnectionWakePolicy(elapsed + 180_000L) { current }
        assertEquals("CONNECTION_ASLEEP_REQUIRED", policy.authorizationReason())
        assertNull(policy.acceptBody(body()))
        assertTrue(policy.wakeRequired)
        assertFalse(policy.awake)
        assertEquals(elapsed + 5_000L, policy.wakeDeadline)
        assertNull(policy.authorizationReason())
        current += 4_999L
        assertNull(policy.authorizationReason())
        current++
        assertEquals("CONNECTION_WAKE_DEADLINE_EXPIRED", policy.authorizationReason())
        current++
        assertEquals("CONNECTION_BODY_RECEIPT_STALE", policy.authorizationReason())
        assertEquals(elapsed + 5_000L, policy.wakeDeadline)
    }

    @Test fun awakeAndMissingOrUnknownSleepNeverAuthorizeWake() {
        for (sleep in listOf(1, 0, null, -1, 3, Int.MAX_VALUE)) {
            val policy = TeslaConnectionWakePolicy(elapsed + 180_000L) { elapsed }
            assertNull(policy.acceptBody(body(sleep = sleep)))
            assertFalse(policy.wakeRequired)
            assertEquals(sleep == 1, policy.awake)
            assertEquals("CONNECTION_ASLEEP_REQUIRED", policy.authorizationReason())
        }
    }

    @Test fun ingressReceiptMustBePresentNonnegativeNonfutureAndWithinFiveSeconds() {
        val missing = TeslaConnectionWakePolicy(Long.MAX_VALUE) { elapsed }
        assertEquals("CONNECTION_BODY_RECEIPT_REQUIRED", missing.acceptBody(body(receipt = null)))
        assertFalse(missing.wakeRequired)
        assertNull(missing.wakeDeadline)
        for (receipt in listOf(-1L, elapsed + 1L, Long.MAX_VALUE, elapsed - 5_001L)) {
            val policy = TeslaConnectionWakePolicy(Long.MAX_VALUE) { elapsed }
            assertEquals("CONNECTION_BODY_RECEIPT_STALE", policy.acceptBody(body(receipt)))
            assertFalse(policy.wakeRequired)
            assertNull(policy.wakeDeadline)
        }
        val exact = TeslaConnectionWakePolicy(Long.MAX_VALUE) { elapsed }
        assertNull(exact.acceptBody(body(elapsed - 5_000L)))
        assertEquals("CONNECTION_WAKE_DEADLINE_EXPIRED", exact.authorizationReason())
    }

    @Test fun processingDelayAndOverallBudgetNeverRestartWakeFreshness() {
        var current = elapsed + 4_000L
        val policy = TeslaConnectionWakePolicy(elapsed + 4_500L) { current }
        assertNull(policy.acceptBody(body()))
        assertEquals(elapsed + 4_500L, policy.wakeDeadline)
        repeat(4) {
            current += 100L
            assertNull(policy.authorizationReason())
            assertEquals(elapsed + 4_500L, policy.wakeDeadline)
        }
        current += 100L
        assertEquals("CONNECTION_WAKE_DEADLINE_EXPIRED", policy.authorizationReason())
        assertEquals("CONNECTION_BODY_RESPONSE_UNEXPECTED", policy.acceptBody(body(current)))
        assertEquals(elapsed + 4_500L, policy.wakeDeadline)
    }

    @Test fun delayedEncryptionRechecksOriginalBodyWithoutASecondBodyGrant() {
        var current = elapsed
        val policy = TeslaConnectionWakePolicy(Long.MAX_VALUE) { current }
        assertNull(policy.acceptBody(body()))
        current += 5_001L
        assertEquals("CONNECTION_BODY_RECEIPT_STALE", policy.authorizationReason())
        assertEquals("CONNECTION_BODY_RESPONSE_UNEXPECTED", policy.acceptBody(body(current)))
        assertEquals(elapsed + 5_000L, policy.wakeDeadline)
    }

    @Test fun elapsedRollbackAndDriveResultCannotSupplyWakeAuthorization() {
        var current = elapsed
        val policy = TeslaConnectionWakePolicy(Long.MAX_VALUE) { current }
        assertNull(policy.acceptBody(body()))
        current += 1_000L
        assertNull(policy.authorizationReason())
        current--
        assertEquals("CONNECTION_TIME_REVERSED", policy.authorizationReason())
        val drive = TeslaConnectionWakePolicy(Long.MAX_VALUE) { elapsed }
        assertEquals("CONNECTION_BODY_RESPONSE_UNEXPECTED", drive.acceptBody(body().copy(
            driveState = TeslaDriveState(TeslaGear.P, 1_800_000_000_000L, elapsed),
        )))
        assertFalse(drive.wakeRequired)
        assertNull(drive.wakeDeadline)
    }

    @Test fun receiptDeadlineSaturatesWithoutOverflowOrChangingTheOverallBudget() {
        var current = Long.MAX_VALUE - 2_000L
        val policy = TeslaConnectionWakePolicy(Long.MAX_VALUE - 1_000L) { current }
        assertNull(policy.acceptBody(body(current)))
        assertEquals(Long.MAX_VALUE - 1_000L, policy.wakeDeadline)
        assertNull(policy.authorizationReason())
        current = Long.MAX_VALUE - 1_000L
        assertEquals("CONNECTION_WAKE_DEADLINE_EXPIRED", policy.authorizationReason())
    }
}
