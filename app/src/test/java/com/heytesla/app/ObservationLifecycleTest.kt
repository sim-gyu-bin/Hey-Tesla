package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test

class ObservationLifecycleTest {
    @Test fun offDuringStartupRejectsDelayedForegroundIntent() {
        val policy = ObservationPolicy()
        assertTrue(policy.request("first"))
        assertTrue(policy.finish())

        assertFalse(policy.promote("first"))
        assertFalse(policy.accepts("first"))
        assertNull(policy.pendingRequest)
        assertNull(policy.runningRequest)
    }

    @Test fun delayedOldStartCannotConsumeReplacementRequest() {
        val policy = ObservationPolicy()
        policy.request("old")
        policy.finish()
        assertTrue(policy.request("new"))

        assertFalse(policy.promote("old"))
        assertEquals("new", policy.pendingRequest)
        assertNull(policy.runningRequest)
        assertTrue(policy.promote("new"))
        assertEquals("new", policy.runningRequest)
    }

    @Test fun staleNotificationAndDestroyCannotStopReplacementService() {
        val policy = ObservationPolicy()
        policy.request("old")
        policy.promote("old")
        policy.finish("old")
        policy.request("new")
        policy.promote("new")

        assertFalse(policy.finish("old"))
        assertTrue(policy.accepts("new"))
        assertEquals("new", policy.runningRequest)
        assertNull(policy.pendingRequest)
    }

    @Test fun duplicateOnCannotReplacePendingOrRunningRequest() {
        val policy = ObservationPolicy()
        policy.request("current")
        assertFalse(policy.request("duplicate-pending"))
        assertTrue(policy.promote("current"))
        assertFalse(policy.promote("current"))
        assertFalse(policy.request("duplicate-running"))

        assertEquals("current", policy.runningRequest)
        assertFalse(policy.accepts("duplicate-pending"))
        assertFalse(policy.accepts("duplicate-running"))
    }

    @Test fun matchingStopClosesRunningRequestWithoutImplicitRestart() {
        val policy = ObservationPolicy()
        policy.request("current")
        policy.promote("current")
        assertTrue(policy.finish("current"))

        assertNull(policy.pendingRequest)
        assertNull(policy.runningRequest)
        assertFalse(policy.promote("current"))
        assertTrue(policy.request("explicit-new-on"))
        assertNull(policy.runningRequest)
    }

    @Test fun processRecreationDoesNotAcceptPreviousStartOrStopIntent() {
        val previousProcess = ObservationPolicy()
        previousProcess.request("previous-process")
        previousProcess.promote("previous-process")
        val restartedProcess = ObservationPolicy()

        assertFalse(restartedProcess.promote("previous-process"))
        assertFalse(restartedProcess.accepts("previous-process"))
        restartedProcess.request("new-process")
        assertFalse(restartedProcess.finish("previous-process"))
        assertEquals("new-process", restartedProcess.pendingRequest)
        assertNull(restartedProcess.runningRequest)
    }
}
