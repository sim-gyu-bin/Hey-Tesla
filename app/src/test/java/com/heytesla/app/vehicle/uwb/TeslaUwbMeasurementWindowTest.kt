package com.heytesla.app.vehicle.uwb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaUwbMeasurementWindowTest {
    @Test fun serviceWaitsSixtySecondsForFirstValidDistance() {
        val window = TeslaUwbMeasurementWindow(serviceOwned = true)
        assertFalse(window.receiveDistance(500L))
        window.start(1_000L)
        assertFalse(window.expired(60_999L))
        assertTrue(window.expired(61_000L))
        assertFalse(window.receiveDistance(61_000L))
        assertFalse(window.hasDistance)
        assertEquals("NO_DISTANCE_RECEIVED", window.timeoutReason)
    }

    @Test fun firstDistanceReplacesInitialDeadlineWithTenSecondSilenceLimit() {
        val window = TeslaUwbMeasurementWindow(serviceOwned = true)
        window.start(1_000L)
        assertTrue(window.receiveDistance(2_000L))
        assertEquals(12_000L, window.deadlineElapsedMs)
        assertFalse(window.expired(11_999L))
        assertTrue(window.expired(12_000L))
        assertFalse(window.receiveDistance(12_000L))
        assertEquals(12_000L, window.deadlineElapsedMs)
        assertEquals("DISTANCE_RECEIVE_TIMEOUT", window.timeoutReason)
    }

    @Test fun firstDistanceNearInitialDeadlineKeepsServiceAlivePastSixtySeconds() {
        val window = TeslaUwbMeasurementWindow(serviceOwned = true)
        window.start(0L)
        assertTrue(window.receiveDistance(59_999L))
        assertFalse(window.expired(60_000L))
        assertFalse(window.expired(69_998L))
        assertTrue(window.expired(69_999L))
    }

    @Test fun continuedReceiptsKeepServiceAlivePastManualAndOwnerLimits() {
        val window = TeslaUwbMeasurementWindow(serviceOwned = true)
        window.start(0L)
        for (receivedAt in 1_000L..200_000L step 1_000L) {
            assertFalse(window.expired(receivedAt))
            assertTrue(window.receiveDistance(receivedAt))
        }
        assertEquals(210_000L, window.deadlineElapsedMs)
        assertFalse(window.expired(209_999L))
        assertTrue(window.expired(210_000L))
        assertFalse(window.receiveDistance(210_001L))
        assertEquals("DISTANCE_RECEIVE_TIMEOUT", window.timeoutReason)
    }

    @Test fun manualMeasurementStillEndsAtItsOriginalSixtySecondDeadline() {
        val window = TeslaUwbMeasurementWindow(serviceOwned = false)
        window.start(1_000L)
        assertTrue(window.receiveDistance(2_000L))
        assertFalse(window.expired(12_000L))
        assertTrue(window.receiveDistance(60_999L))
        assertEquals(61_000L, window.deadlineElapsedMs)
        assertTrue(window.expired(61_000L))
        assertFalse(window.receiveDistance(61_000L))
        assertTrue(window.hasDistance)
        assertNull(window.timeoutReason)
    }

    @Test fun manualMeasurementWithoutDistanceStillTimesOut() {
        val window = TeslaUwbMeasurementWindow(serviceOwned = false)
        window.start(1_000L)
        assertFalse(window.expired(60_999L))
        assertTrue(window.expired(61_000L))
        assertEquals("NO_DISTANCE_RECEIVED", window.timeoutReason)
    }
}
