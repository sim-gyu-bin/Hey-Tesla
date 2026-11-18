package com.heytesla.app.vehicle.connection

import com.heytesla.app.vehicle.frunk.TeslaFrunkPolicy
import com.heytesla.app.vehicle.protocol.TeslaDriveState
import com.heytesla.app.vehicle.protocol.TeslaGear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeslaParkMonitorTest {
    private val wall = 1_800_000_000_000L
    private val elapsed = 20_000L

    @Test fun runtimeAndBackendUseOriginalReceiptAndSourceBoundaries() {
        val exact = TeslaDriveState(TeslaGear.P, wall - TeslaFrunkPolicy.PARK_SOURCE_MS,
            elapsed - TeslaFrunkPolicy.PARK_RECEIPT_MS)
        assertNull(serviceParkReason(exact, elapsed, wall))
        assertEquals("CONNECTION_P_RECEIPT_STALE", serviceParkReason(exact, elapsed + 1, wall))
        assertEquals("CONNECTION_P_SOURCE_TIME_STALE", serviceParkReason(exact, elapsed, wall + 1))
        assertNull(serviceParkReason(exact.copy(sourceTimestampEpochMillis =
            wall + TeslaFrunkPolicy.PARK_SOURCE_FUTURE_SKEW_MS), elapsed, wall))
        assertEquals("CONNECTION_P_SOURCE_TIME_STALE", serviceParkReason(exact.copy(sourceTimestampEpochMillis =
            wall + TeslaFrunkPolicy.PARK_SOURCE_FUTURE_SKEW_MS + 1), elapsed, wall))
    }

    @Test fun missingOrNonparkAndInvalidClocksNeverGrantPark() {
        assertEquals("CONNECTION_EXPLICIT_P_REQUIRED", serviceParkReason(null, elapsed, wall))
        for (gear in listOf(TeslaGear.D, TeslaGear.R, TeslaGear.N, TeslaGear.UNKNOWN)) {
            assertEquals("CONNECTION_EXPLICIT_P_REQUIRED", serviceParkReason(TeslaDriveState(gear, wall, elapsed), elapsed, wall))
        }
        val park = TeslaDriveState(TeslaGear.P, wall, elapsed)
        for (receipt in listOf(-1L, elapsed + 1, Long.MAX_VALUE)) {
            assertEquals("CONNECTION_P_RECEIPT_STALE", serviceParkReason(park.copy(receivedAtElapsedRealtime = receipt), elapsed, wall))
        }
        assertEquals("CONNECTION_P_SOURCE_TIME_REQUIRED", serviceParkReason(park.copy(sourceTimestampEpochMillis = null), elapsed, wall))
        for (source in listOf(0L, -1L, Long.MAX_VALUE)) {
            assertEquals("CONNECTION_P_SOURCE_TIME_STALE", serviceParkReason(park.copy(sourceTimestampEpochMillis = source), elapsed, wall))
        }
        assertEquals("CONNECTION_P_SOURCE_TIME_STALE", serviceParkReason(park, elapsed, 0))
        assertEquals("CONNECTION_P_RECEIPT_STALE", serviceParkReason(park, -1, wall))
    }

    @Test fun pollingReadinessDoesNotRefreshReceiptAndElapsedRollbackSealsReadiness() {
        var now = elapsed
        var wallNow = wall
        val monitor = TeslaParkMonitor({ now }, { wallNow })
        val park = TeslaDriveState(TeslaGear.P, wall, elapsed)
        assertNull(monitor.accept(park))
        repeat(20) {
            now += 250
            wallNow += 250
            assertNull(monitor.readinessReason())
            assertEquals(park, monitor.observed)
        }
        now++
        assertEquals("CONNECTION_P_RECEIPT_STALE", monitor.readinessReason())
        now--
        assertEquals("CONNECTION_TIME_REVERSED", monitor.readinessReason())
        monitor.clear()
        assertNull(monitor.observed)
        now++
        assertEquals("CONNECTION_EXPLICIT_P_REQUIRED", monitor.readinessReason())
    }
}
