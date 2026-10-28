package com.heytesla.app.vehicle.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VehicleNavigationCleanupTest {

    @Test fun sourceReleaseFailureNeverReturnsForegroundLeaseAndCompletesFailureOnce() {
        for (throws in listOf(false, true)) {
            var foregroundCalls = 0
            val results = ArrayList<Boolean>()
            closeVehicleNavigationResources(
                closeSource = {
                    if (throws) throw IllegalStateException("PHONE_NAVIGATION_CLEANUP_FAILED")
                    false
                },
                closeForeground = { completed -> foregroundCalls++; completed(true) },
                complete = { results.add(it) },
            )
            assertEquals(0, foregroundCalls)
            assertEquals(listOf(false), results)
        }
    }

    @Test fun sourceReleasePrecedesForegroundAndSuccessWaitsForItsActualCompletion() {
        val events = ArrayList<String>()
        var foregroundResult: ((Boolean) -> Unit)? = null
        closeVehicleNavigationResources(
            closeSource = { events.add("source"); true },
            closeForeground = { completed -> events.add("foreground"); foregroundResult = completed },
            complete = { events.add("complete:$it") },
        )
        assertEquals(listOf("source", "foreground"), events)
        checkNotNull(foregroundResult)(true)
        checkNotNull(foregroundResult)(false)
        assertEquals(listOf("source", "foreground", "complete:true"), events)
    }

    @Test fun synchronousConsumerExceptionIsNotReclassifiedOrDeliveredAgain() {
        val marker = IllegalStateException("CONSUMER_FAILURE")
        var calls = 0
        var thrown: Exception? = null
        try {
            closeVehicleNavigationResources(
                closeSource = { true },
                closeForeground = { completed -> completed(true); completed(false) },
                complete = { calls++; throw marker },
            )
        } catch (failure: Exception) { thrown = failure }
        assertSame(marker, thrown)
        assertEquals(1, calls)
    }

    @Test fun reentrantForegroundCompletionCannotRunItsOwnerTwice() {
        var foregroundResult: ((Boolean) -> Unit)? = null
        var calls = 0
        closeVehicleNavigationResources(
            closeSource = { true },
            closeForeground = { foregroundResult = it },
            complete = { calls++; checkNotNull(foregroundResult)(false) },
        )
        checkNotNull(foregroundResult)(true)
        assertEquals(1, calls)
    }

    @Test fun foregroundStartOfCloseFailureSealsLaterCompletionAndReportsFailureOnce() {
        var foregroundResult: ((Boolean) -> Unit)? = null
        val results = ArrayList<Boolean>()
        closeVehicleNavigationResources(
            closeSource = { true },
            closeForeground = { completed ->
                foregroundResult = completed
                throw IllegalStateException("FOREGROUND_CLOSE_FAILED")
            },
            complete = { results.add(it) },
        )
        checkNotNull(foregroundResult)(true)
        assertEquals(listOf(false), results)
    }
}
