package com.heytesla.app.vehicle.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNavigationSourcePolicyTest {
    @Test fun delayedCallbacksAndSuspendQueuesNeverPromoteAnOldNativeFixToCurrent() {
        val ingress = 100_000L
        assertNull(navigationSourceFixElapsedMs(80_000L * NANOS_PER_MS, ingress, ingress,
            VehicleNavigationPolicy.PHONE_FIX_TTL_MS))
        assertNull(navigationSourceFixElapsedMs(97_000L * NANOS_PER_MS, ingress, ingress,
            VehicleNavigationPolicy.HEADING_FIX_TTL_MS))
        // A callback captured before a long queue/suspend is also stale at consumption.
        assertNull(navigationSourceFixElapsedMs(ingress * NANOS_PER_MS, ingress, ingress + 15_001L,
            VehicleNavigationPolicy.PHONE_FIX_TTL_MS))
        assertNull(navigationSourceFixElapsedMs(ingress * NANOS_PER_MS, ingress, ingress + 2_001L,
            VehicleNavigationPolicy.HEADING_FIX_TTL_MS))
        // A fresh-looking source timestamp cannot conceal an independently old ingress.
        assertNull(navigationSourceFixElapsedMs(ingress * NANOS_PER_MS, ingress - 20_000L, ingress,
            VehicleNavigationPolicy.PHONE_FIX_TTL_MS))
    }

    @Test fun unknownFutureAndRollbackTimestampsAreRejectedRatherThanReplacedWithIngress() {
        for (unknown in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertNull(navigationSourceFixElapsedMs(unknown, 100_000L, 100_000L,
                VehicleNavigationPolicy.HEADING_FIX_TTL_MS))
        }
        assertNull(navigationSourceFixElapsedMs(103_000L * NANOS_PER_MS, 100_000L, 100_000L,
            VehicleNavigationPolicy.HEADING_FIX_TTL_MS))
        assertNull(navigationSourceFixElapsedMs(100_000L * NANOS_PER_MS, 100_000L, 97_000L,
            VehicleNavigationPolicy.HEADING_FIX_TTL_MS))
        assertEquals(99_123L, checkNotNull(navigationSourceFixElapsedMs(99_123_999_999L,
            100_000L, 100_000L, VehicleNavigationPolicy.HEADING_FIX_TTL_MS)))
    }

    @Test fun missingAngularAccuracyAndInvalidQuaternionCannotBecomeAnApparentlyStableNorth() {
        assertTrue(navigationRotationVectorReliable(floatArrayOf(0f, 0f, 0f, 1f, 0.1f), true))
        for (vector in listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 0f, 1f, -1f),
            floatArrayOf(0f, 0f, 0f, 1f, Float.NaN),
            floatArrayOf(0f, 0f, 0f, 1f, 0.6f),
            floatArrayOf(0f, 0f, 0f, 0f, 0.1f),
            floatArrayOf(Float.POSITIVE_INFINITY, 0f, 0f, 1f, 0.1f),
        )) assertFalse(navigationRotationVectorReliable(vector, true))
        assertFalse(navigationRotationVectorReliable(floatArrayOf(0f, 0f, 0f, 1f, 0.1f), false))
    }

    @Test fun displayTopAndDeclinationShareClockwiseTrueNorthReferenceAcrossWrap() {
        val northTop = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val eastTop = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        val westTop = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(12.0, checkNotNull(navigationPhoneTopTrueHeading(northTop, 12.0)), 0.001)
        assertEquals(350.0, checkNotNull(navigationPhoneTopTrueHeading(eastTop, -100.0)), 0.001)
        assertEquals(5.0, checkNotNull(navigationPhoneTopTrueHeading(westTop, 95.0)), 0.001)
        // The same vehicle rhumb bearing minus this true heading is a small clockwise turn,
        // not the uncorrected magnetic angle and not an interpolated full revolution.
        val heading = checkNotNull(navigationPhoneTopTrueHeading(northTop, -1.0))
        assertEquals(1.0, navigationWrapDegrees(0.0 - heading), 0.001)
    }

    @Test fun verticalAndNearVerticalPhoneTopHaveNoFrontFacingZeroFallback() {
        val vertical = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val nearlyVertical = floatArrayOf(1f, 0f, 0f, 0f, 0.1f, -0.9949874f, 0f, 0.9949874f, 0.1f)
        val malformed = floatArrayOf(1f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 1f)
        assertNull(navigationPhoneTopTrueHeading(vertical, 10.0))
        assertNull(navigationPhoneTopTrueHeading(nearlyVertical, 10.0))
        assertNull(navigationPhoneTopTrueHeading(malformed, 10.0))
        assertNull(navigationPhoneTopTrueHeading(FloatArray(9) { Float.NaN }, 10.0))
        assertNull(navigationPhoneTopTrueHeading(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), Double.NaN))
    }

    @Test fun oldAuxiliaryProviderCallbackCannotEraseOrRefreshAFreshGpsFixInTheTracker() {
        val consumer = NavigationConsumer()
        val admission = PhoneNavigationLocationAdmission(consumer)
        consumer.tracker.acceptVehicleLocation(vehicle())
        admission.accept(ELAPSED * NANOS_PER_MS, ELAPSED, ELAPSED, 0.0, 0.0, 5.0, null)
        consumer.onHeading(PhoneNavigationHeading(0.0, ELAPSED, ELAPSED))
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            consumer.tracker.project(ELAPSED, EPOCH, ELAPSED).status)
        // NETWORK delivers an old fix after a fresh GPS callback. Its TTL failure must not
        // emit LOCATION_UNAVAILABLE and thereby erase the consumer's current phone/heading.
        admission.accept((ELAPSED - 20_000L) * NANOS_PER_MS, ELAPSED + 1L, ELAPSED + 1L,
            1.0, 1.0, 20.0, null)
        val preserved = consumer.tracker.project(ELAPSED + 1L, EPOCH + 1L, ELAPSED + 1L)
        assertEquals(0, consumer.unavailableCount)
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE, preserved.status)
        assertEquals(200.0, checkNotNull(preserved.distanceMeters), 0.001)
        // Rejecting the old callback must not refresh the accepted GPS fix's lifetime.
        val expired = consumer.tracker.project(ELAPSED + 15_001L, EPOCH + 15_001L, ELAPSED + 15_001L)
        assertEquals(VehicleNavigationStatus.PHONE_LOCATION_STALE, expired.status)
        assertNull(expired.distanceMeters)
        assertNull(expired.arrowDegrees)
    }

    @Test fun malformedNewProviderSamplesPreserveFreshInputButInvalidateItAfterItsOwnTtl() {
        val consumer = NavigationConsumer()
        val admission = PhoneNavigationLocationAdmission(consumer)
        consumer.tracker.acceptVehicleLocation(vehicle())
        admission.accept(ELAPSED * NANOS_PER_MS, ELAPSED, ELAPSED, 0.0, 0.0, 5.0, null)
        consumer.onHeading(PhoneNavigationHeading(0.0, ELAPSED, ELAPSED))
        admission.accept((ELAPSED + 1L) * NANOS_PER_MS, ELAPSED + 1L, ELAPSED + 1L,
            Double.NaN, 0.0, 5.0, null)
        admission.accept(0L, ELAPSED + 2L, ELAPSED + 2L, 0.0, 0.0, null, null)
        val preserved = consumer.tracker.project(ELAPSED + 2L, EPOCH + 2L, ELAPSED + 2L)
        assertEquals(0, consumer.unavailableCount)
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE, preserved.status)
        assertEquals(200.0, checkNotNull(preserved.distanceMeters), 0.001)
        admission.accept(0L, ELAPSED + 15_001L, ELAPSED + 15_001L, 0.0, 0.0, null, null)
        assertEquals(1, consumer.unavailableCount)
        val unavailable = consumer.tracker.project(ELAPSED + 15_001L, EPOCH + 15_001L, ELAPSED + 15_001L)
        assertEquals(VehicleNavigationStatus.WAITING_FOR_PHONE_LOCATION, unavailable.status)
        assertNull(unavailable.distanceMeters)
        assertNull(unavailable.arrowDegrees)
        admission.accept((ELAPSED + 15_002L) * NANOS_PER_MS, ELAPSED + 15_002L, ELAPSED + 15_002L,
            0.0, 0.0, 5.0, null)
        consumer.onHeading(PhoneNavigationHeading(0.0, ELAPSED + 15_002L, ELAPSED + 15_002L))
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            consumer.tracker.project(ELAPSED + 15_002L, EPOCH + 15_002L, ELAPSED + 15_002L).status)
    }

    @Test fun invalidProviderSampleCannotExtendAnExpiredIngressEvenIfItsNativeFixIsYounger() {
        val consumer = NavigationConsumer()
        val admission = PhoneNavigationLocationAdmission(consumer)
        consumer.tracker.acceptVehicleLocation(vehicle())
        admission.accept((ELAPSED + 2_000L) * NANOS_PER_MS, ELAPSED, ELAPSED, 0.0, 0.0, 5.0, null)
        consumer.onHeading(PhoneNavigationHeading(0.0, ELAPSED, ELAPSED))
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            consumer.tracker.project(ELAPSED, EPOCH, ELAPSED).status)
        admission.accept(0L, ELAPSED + 15_001L, ELAPSED + 15_001L, 0.0, 0.0, null, null)
        assertEquals(1, consumer.unavailableCount)
        val unavailable = consumer.tracker.project(ELAPSED + 15_001L, EPOCH + 15_001L, ELAPSED + 15_001L)
        assertEquals(VehicleNavigationStatus.WAITING_FOR_PHONE_LOCATION, unavailable.status)
        assertNull(unavailable.distanceMeters)
        assertNull(unavailable.arrowDegrees)
    }

    private class NavigationConsumer : PhoneNavigationSource.Listener {
        val tracker = VehicleNavigationTracker()
        var unavailableCount = 0
            private set

        override fun onLocation(value: PhoneNavigationLocation) = tracker.acceptPhoneLocation(value)
        override fun onHeading(value: PhoneNavigationHeading) = tracker.acceptPhoneHeading(value)
        override fun onUnavailable(reason: PhoneNavigationUnavailable) {
            unavailableCount++
            tracker.invalidatePhoneLocation()
        }
    }

    private fun vehicle() = VehicleCoordinateObservation(
        NavigationPoint(Math.toDegrees(200.0 / VehicleNavigationPolicy.EARTH_RADIUS_METERS), 0.0),
        EPOCH, EPOCH, ELAPSED, false)

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val ELAPSED = 100_000L
        const val EPOCH = 1_800_000_000_000L
    }
}
