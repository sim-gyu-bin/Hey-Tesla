package com.heytesla.app.vehicle.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleNavigationTest {
    @Test fun trueNorthBearingSubtractsPhoneHeadingWithoutBorrowingAnyUwbAngle() {
        val tracker = trackingNorth(200.0, heading = 90.0)
        val result = tracker.project(ELAPSED, EPOCH, ELAPSED)
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE, result.status)
        assertEquals(200.0, checkNotNull(result.distanceMeters), 0.001)
        assertEquals(270.0, checkNotNull(result.arrowDegrees), 0.001)
        assertEquals("북쪽", result.directionLabel)
    }

    @Test fun circularNorthCrossingAndSignedHeadingsNeverProduceNegativeOrFullTurnArrows() {
        val tracker = trackingNorth(200.0, heading = 359.0)
        assertEquals(1.0, checkNotNull(tracker.project(ELAPSED, EPOCH, ELAPSED).arrowDegrees), 0.001)
        tracker.acceptPhoneHeading(heading(1.0))
        assertEquals(359.0, checkNotNull(tracker.project(ELAPSED, EPOCH, ELAPSED).arrowDegrees), 0.001)
        tracker.acceptPhoneHeading(heading(-1.0))
        assertEquals(1.0, checkNotNull(tracker.project(ELAPSED, EPOCH, ELAPSED).arrowDegrees), 0.001)
        tracker.acceptPhoneHeading(heading(720.0))
        assertEquals(0.0, checkNotNull(tracker.project(ELAPSED, EPOCH, ELAPSED).arrowDegrees), 0.001)
    }

    @Test fun nearbyRadiusUsesReportedAccuracyAndNeverShowsAPreciseDirection() {
        for ((distance, accuracy) in listOf(0.0 to 1.0, 9.0 to 1.0, 10.0 to 1.0, 29.0 to 30.0)) {
            val result = trackingNorth(distance, accuracy = accuracy).project(ELAPSED, EPOCH, ELAPSED)
            assertEquals(VehicleNavigationStatus.NEARBY, result.status)
            assertNotNull(result.distanceMeters)
            assertNull(result.arrowDegrees)
            assertNull(result.directionLabel)
        }
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            trackingNorth(31.0, accuracy = 30.0).project(ELAPSED, EPOCH, ELAPSED).status)
    }

    @Test fun inaccurateOrInvalidPhoneFixCannotExposeCurrentDistanceOrDirection() {
        for (accuracy in listOf(100.01, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val result = trackingNorth(200.0, accuracy = accuracy).project(ELAPSED, EPOCH, ELAPSED)
            assertEquals(VehicleNavigationStatus.PHONE_LOCATION_INACCURATE, result.status)
            assertNoCurrentValue(result)
        }
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            trackingNorth(200.0, accuracy = 100.0).project(ELAPSED, EPOCH, ELAPSED).status)
    }

    @Test fun expiryUsesEachFixTimeEvenWhenItsCallbackHasJustArrived() {
        val vehicleOld = trackingNorth(200.0)
        vehicleOld.acceptVehicleLocation(vehicle(north(200.0), fixEpochMs = EPOCH - 60_001L))
        assertNoCurrentValue(vehicleOld.project(ELAPSED, EPOCH, ELAPSED).also {
            assertEquals(VehicleNavigationStatus.VEHICLE_LOCATION_STALE, it.status)
        })
        val phoneOld = trackingNorth(200.0)
        phoneOld.acceptPhoneLocation(phone(fixMs = ELAPSED - 15_001L))
        assertNoCurrentValue(phoneOld.project(ELAPSED, EPOCH, ELAPSED).also {
            assertEquals(VehicleNavigationStatus.PHONE_LOCATION_STALE, it.status)
        })
        val headingOld = trackingNorth(200.0)
        headingOld.acceptPhoneHeading(heading(0.0, fixMs = ELAPSED - 2_001L))
        val result = headingOld.project(ELAPSED, EPOCH, ELAPSED)
        assertEquals(VehicleNavigationStatus.HEADING_STALE, result.status)
        assertNotNull(result.distanceMeters)
        assertNull(result.arrowDegrees)
        assertNull(result.directionLabel)
    }

    @Test fun callbackSilenceExpiresPreviouslyVisibleValuesWithoutAnotherInput() {
        val tracker = trackingNorth(200.0)
        assertNotNull(tracker.project(ELAPSED, EPOCH, ELAPSED).arrowDegrees)
        assertEquals(VehicleNavigationStatus.HEADING_STALE,
            tracker.project(ELAPSED + 2_001L, EPOCH + 2_001L, ELAPSED + 2_001L).status)
        val phoneExpired = tracker.project(ELAPSED + 15_001L, EPOCH + 15_001L, ELAPSED + 15_001L)
        assertEquals(VehicleNavigationStatus.PHONE_LOCATION_STALE, phoneExpired.status)
        assertNoCurrentValue(phoneExpired)
        val vehicleExpired = tracker.project(ELAPSED + 60_001L, EPOCH + 60_001L, ELAPSED + 60_001L)
        assertEquals(VehicleNavigationStatus.VEHICLE_LOCATION_STALE, vehicleExpired.status)
        assertNoCurrentValue(vehicleExpired)
    }

    @Test fun receiptAgeAlsoExpiresARecentlyDatedVehicleFix() {
        val tracker = trackingNorth(200.0)
        tracker.acceptVehicleLocation(vehicle(north(200.0), receivedMs = ELAPSED - 60_001L))
        assertEquals(VehicleNavigationStatus.VEHICLE_LOCATION_STALE,
            tracker.project(ELAPSED, EPOCH, ELAPSED).status)
        tracker.acceptVehicleLocation(vehicle(north(200.0), receivedMs = ELAPSED + 2_001L))
        assertNoCurrentValue(tracker.project(ELAPSED, EPOCH, ELAPSED))
    }

    @Test fun ttlBoundaryAndTwoSecondFutureToleranceAreNotMissingValueDefaults() {
        val tracker = trackingNorth(200.0)
        tracker.acceptVehicleLocation(vehicle(north(200.0), fixEpochMs = EPOCH - 60_000L))
        tracker.acceptPhoneLocation(phone(fixMs = ELAPSED - 15_000L))
        tracker.acceptPhoneHeading(heading(0.0, fixMs = ELAPSED - 2_000L))
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE, tracker.project(ELAPSED, EPOCH, ELAPSED).status)
        tracker.acceptVehicleLocation(vehicle(north(200.0), fixEpochMs = EPOCH + 2_000L))
        tracker.acceptPhoneLocation(phone(fixMs = ELAPSED + 2_000L))
        tracker.acceptPhoneHeading(heading(0.0, fixMs = ELAPSED + 2_000L))
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE, tracker.project(ELAPSED, EPOCH, ELAPSED).status)
        tracker.acceptVehicleLocation(vehicle(north(200.0), fixEpochMs = EPOCH + 2_001L))
        assertNoCurrentValue(tracker.project(ELAPSED, EPOCH, ELAPSED))
        tracker.acceptVehicleLocation(vehicle(north(200.0)))
        tracker.acceptPhoneLocation(phone(fixMs = ELAPSED + 2_001L))
        assertNoCurrentValue(tracker.project(ELAPSED, EPOCH, ELAPSED))
        tracker.acceptPhoneLocation(phone())
        tracker.acceptPhoneHeading(heading(0.0, fixMs = ELAPSED + 2_001L))
        assertNull(tracker.project(ELAPSED, EPOCH, ELAPSED).arrowDegrees)
    }

    @Test fun delayedClockSamplingHidesValuesThenRecoversWithoutPoisoningTheAnchor() {
        val tracker = trackingNorth(200.0)
        val firstDelayed = tracker.project(ELAPSED, EPOCH + 3_000L, ELAPSED + 3_000L)
        assertEquals(VehicleNavigationStatus.CLOCK_SAMPLE_DELAYED, firstDelayed.status)
        assertNoCurrentValue(firstDelayed)
        tracker.acceptPhoneHeading(PhoneNavigationHeading(0.0, ELAPSED + 3_001L, ELAPSED + 3_001L))
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            tracker.project(ELAPSED + 3_001L, EPOCH + 3_001L, ELAPSED + 3_002L).status)
        val laterDelayed = tracker.project(ELAPSED + 3_003L, EPOCH + 6_003L, ELAPSED + 6_003L)
        assertEquals(VehicleNavigationStatus.CLOCK_SAMPLE_DELAYED, laterDelayed.status)
        assertNoCurrentValue(laterDelayed)
        tracker.acceptPhoneHeading(PhoneNavigationHeading(0.0, ELAPSED + 6_004L, ELAPSED + 6_004L))
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            tracker.project(ELAPSED + 6_004L, EPOCH + 6_004L, ELAPSED + 6_005L).status)
    }

    @Test fun validClockIntervalEndpointsDoNotManufactureRollback() {
        val tracker = trackingNorth(200.0)
        // First epoch is read at the interval end, second at the next interval's beginning.
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            tracker.project(ELAPSED, EPOCH + 100L, ELAPSED + 100L).status)
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            tracker.project(ELAPSED + 100L, EPOCH + 100L, ELAPSED + 200L).status)
        assertEquals(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            tracker.project(ELAPSED + 201L, EPOCH + 201L, ELAPSED + 201L).status)
    }

    @Test fun delayedOrUncertainClockSamplesCannotReviveAnAlreadyExpiredVehicleFix() {
        val tracker = trackingNorth(200.0)
        tracker.acceptVehicleLocation(vehicle(north(200.0), fixEpochMs = EPOCH - 59_950L))
        assertNotNull(tracker.project(ELAPSED, EPOCH, ELAPSED).distanceMeters)
        val expired = tracker.project(ELAPSED + 50L, EPOCH + 50L, ELAPSED + 150L)
        assertEquals(VehicleNavigationStatus.VEHICLE_LOCATION_STALE, expired.status)
        assertNoCurrentValue(expired)
        val delayed = tracker.project(ELAPSED + 151L, EPOCH + 3_151L, ELAPSED + 3_151L)
        assertEquals(VehicleNavigationStatus.CLOCK_SAMPLE_DELAYED, delayed.status)
        assertNoCurrentValue(delayed)
        val recoveredClock = tracker.project(ELAPSED + 3_152L, EPOCH + 3_152L, ELAPSED + 3_152L)
        assertEquals(VehicleNavigationStatus.VEHICLE_LOCATION_STALE, recoveredClock.status)
        assertNoCurrentValue(recoveredClock)
    }

    @Test fun elapsedRollbackInsideOrAfterDelayedSamplingStillSealsTheOwner() {
        val tracker = trackingNorth(200.0)
        tracker.project(ELAPSED, EPOCH + 3_000L, ELAPSED + 3_000L)
        assertEquals(VehicleNavigationStatus.CLOCK_INVALID,
            tracker.project(ELAPSED + 2_999L, EPOCH + 3_001L, ELAPSED + 3_001L).status)
        assertNoCurrentValue(tracker.project(ELAPSED + 3_002L, EPOCH + 3_002L, ELAPSED + 3_002L))
        val anotherOwner = trackingNorth(200.0)
        assertEquals(VehicleNavigationStatus.CLOCK_INVALID,
            anotherOwner.project(ELAPSED, EPOCH, ELAPSED - 1L).status)
    }

    @Test fun wallClockRollbackCannotReviveAValueThatAlreadyExpired() {
        val tracker = trackingNorth(200.0)
        tracker.acceptVehicleLocation(vehicle(north(200.0), fixEpochMs = EPOCH - 59_000L))
        assertNotNull(tracker.project(ELAPSED, EPOCH, ELAPSED).distanceMeters)
        assertEquals(VehicleNavigationStatus.VEHICLE_LOCATION_STALE,
            tracker.project(ELAPSED + 2_000L, EPOCH + 2_000L, ELAPSED + 2_000L).status)
        val rolledBack = tracker.project(ELAPSED + 2_001L, EPOCH - 3_600_000L, ELAPSED + 2_001L)
        assertEquals(VehicleNavigationStatus.CLOCK_INVALID, rolledBack.status)
        assertNoCurrentValue(rolledBack)
        tracker.acceptVehicleLocation(vehicle(north(200.0), receivedMs = ELAPSED + 2_002L))
        assertEquals(VehicleNavigationStatus.CLOCK_INVALID,
            tracker.project(ELAPSED + 2_002L, EPOCH + 2_002L, ELAPSED + 2_002L).status)
    }

    @Test fun elapsedRollbackSealsOwnerUntilClearAndNewObservations() {
        val tracker = trackingNorth(200.0)
        tracker.project(ELAPSED, EPOCH, ELAPSED)
        assertEquals(VehicleNavigationStatus.CLOCK_INVALID,
            tracker.project(ELAPSED - 1L, EPOCH + 1L, ELAPSED - 1L).status)
        assertNoCurrentValue(tracker.project(ELAPSED + 1L, EPOCH + 1L, ELAPSED + 1L))
        tracker.clear()
        tracker.acceptPhoneLocation(phone())
        tracker.acceptPhoneHeading(heading(0.0))
        assertEquals(VehicleNavigationStatus.WAITING_FOR_VEHICLE_LOCATION,
            tracker.project(ELAPSED, EPOCH, ELAPSED).status)
        tracker.acceptVehicleLocation(vehicle(north(200.0)))
        assertNotNull(tracker.project(ELAPSED, EPOCH, ELAPSED).arrowDegrees)
    }

    @Test fun smallWallClockDriftDoesNotExtendFixLifetime() {
        val tracker = trackingNorth(200.0)
        tracker.acceptVehicleLocation(vehicle(north(200.0), fixEpochMs = EPOCH - 59_000L))
        tracker.project(ELAPSED, EPOCH, ELAPSED)
        // Only one elapsed second passed, but wall time drifted back by half a second.
        assertNotNull(tracker.project(ELAPSED + 1_000L, EPOCH - 500L, ELAPSED + 1_000L).distanceMeters)
        assertEquals(VehicleNavigationStatus.VEHICLE_LOCATION_STALE,
            tracker.project(ELAPSED + 1_001L, EPOCH - 499L, ELAPSED + 1_001L).status)
    }

    @Test fun explicitInvalidationRemovesOnlyValuesThatDependOnThatInput() {
        val tracker = trackingNorth(200.0)
        tracker.invalidatePhoneHeading()
        val distanceOnly = tracker.project(ELAPSED, EPOCH, ELAPSED)
        assertEquals(VehicleNavigationStatus.HEADING_UNAVAILABLE, distanceOnly.status)
        assertNotNull(distanceOnly.distanceMeters)
        assertNull(distanceOnly.arrowDegrees)
        tracker.acceptPhoneHeading(heading(0.0))
        tracker.invalidatePhoneLocation()
        assertNoCurrentValue(tracker.project(ELAPSED, EPOCH, ELAPSED).also {
            assertEquals(VehicleNavigationStatus.WAITING_FOR_PHONE_LOCATION, it.status)
        })
        tracker.acceptPhoneLocation(phone())
        assertNull(tracker.project(ELAPSED, EPOCH, ELAPSED).arrowDegrees)
        tracker.acceptPhoneHeading(heading(0.0))
        tracker.invalidateVehicleLocation()
        assertNoCurrentValue(tracker.project(ELAPSED, EPOCH, ELAPSED).also {
            assertEquals(VehicleNavigationStatus.WAITING_FOR_VEHICLE_LOCATION, it.status)
        })
    }

    @Test fun missingStateTimestampIsAllowedButContradictoryOrUnknownFixTimeIsNot() {
        val tracker = trackingNorth(200.0)
        tracker.acceptVehicleLocation(vehicle(north(200.0), stateEpochMs = null))
        assertNotNull(tracker.project(ELAPSED, EPOCH, ELAPSED).distanceMeters)
        for (observation in listOf(
            vehicle(north(200.0), fixEpochMs = 0L),
            vehicle(north(200.0), stateEpochMs = 0L),
            vehicle(north(200.0), stateEpochMs = EPOCH + 2_001L),
            vehicle(north(200.0), stateEpochMs = EPOCH - 2_001L),
        )) {
            tracker.acceptVehicleLocation(observation)
            assertNoCurrentValue(tracker.project(ELAPSED, EPOCH, ELAPSED))
        }
    }

    @Test fun farVehicleRetainsOnlyApproximateDistanceAndInvalidHeadingNeverMeansNorth() {
        val far = trackingNorth(1_100.0).project(ELAPSED, EPOCH, ELAPSED)
        assertEquals(VehicleNavigationStatus.DISTANCE_ONLY, far.status)
        assertEquals(1_100.0, checkNotNull(far.distanceMeters), 0.001)
        assertNull(far.arrowDegrees)
        assertNull(far.directionLabel)
        val tracker = trackingNorth(200.0, heading = Double.NaN)
        val unknown = tracker.project(ELAPSED, EPOCH, ELAPSED)
        assertEquals(VehicleNavigationStatus.HEADING_UNAVAILABLE, unknown.status)
        assertNotNull(unknown.distanceMeters)
        assertNull(unknown.arrowDegrees)
    }

    @Test fun antimeridianRhumbBearingUsesTheShortRouteInBothDirections() {
        val westSide = NavigationPoint(0.0, 179.999)
        val eastSide = NavigationPoint(0.0, -179.999)
        val expected = VehicleNavigationPolicy.EARTH_RADIUS_METERS * Math.toRadians(0.002)
        assertEquals(expected, vehicleNavigationDistanceMeters(westSide, eastSide), 0.001)
        assertEquals(90.0, checkNotNull(vehicleNavigationRhumbBearingDegrees(westSide, eastSide)), 0.001)
        assertEquals(270.0, checkNotNull(vehicleNavigationRhumbBearingDegrees(eastSide, westSide)), 0.001)
        assertNull(vehicleNavigationRhumbBearingDegrees(NavigationPoint(0.0, -180.0), NavigationPoint(0.0, 180.0)))
    }

    @Test fun antipodesAndPolesHaveFiniteDistancesButNoInventedDirection() {
        val origin = NavigationPoint(0.0, 0.0)
        val antipode = NavigationPoint(0.0, 180.0)
        assertEquals(Math.PI * VehicleNavigationPolicy.EARTH_RADIUS_METERS,
            vehicleNavigationDistanceMeters(origin, antipode), 0.001)
        assertNull(vehicleNavigationRhumbBearingDegrees(origin, antipode))
        val pole = NavigationPoint(90.0, 0.0)
        assertTrue(vehicleNavigationDistanceMeters(pole, NavigationPoint(89.999, 45.0)).isFinite())
        assertNull(vehicleNavigationRhumbBearingDegrees(pole, NavigationPoint(89.999, 45.0)))
        val tracker = trackingNorth(200.0)
        tracker.acceptPhoneLocation(phone(point = pole))
        tracker.acceptVehicleLocation(vehicle(NavigationPoint(89.999, 45.0)))
        assertEquals(VehicleNavigationStatus.DISTANCE_ONLY, tracker.project(ELAPSED, EPOCH, ELAPSED).status)
    }

    @Test fun malformedCoordinatesFailWithoutIncludingCoordinatesInTheException() {
        for ((latitude, longitude) in listOf(
            Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY, 90.01 to 0.0, 0.0 to -180.01,
        )) {
            var rejected = false
            try {
                NavigationPoint(latitude, longitude)
            } catch (failure: IllegalArgumentException) {
                rejected = true
                assertEquals("INVALID_NAVIGATION_POINT", failure.message)
            }
            assertTrue(rejected)
        }
        assertFalse(vehicleNavigationDistanceMeters(NavigationPoint(0.0, 0.0), north(200.0)).isNaN())
    }

    private fun trackingNorth(distance: Double, heading: Double = 0.0, accuracy: Double = 5.0) =
        VehicleNavigationTracker().apply {
            acceptVehicleLocation(vehicle(north(distance)))
            acceptPhoneLocation(phone(accuracy = accuracy))
            acceptPhoneHeading(this@VehicleNavigationTest.heading(heading))
        }

    private fun north(meters: Double) = NavigationPoint(
        Math.toDegrees(meters / VehicleNavigationPolicy.EARTH_RADIUS_METERS), 0.0)

    private fun vehicle(
        point: NavigationPoint,
        fixEpochMs: Long = EPOCH,
        stateEpochMs: Long? = EPOCH,
        receivedMs: Long = ELAPSED,
    ) = VehicleCoordinateObservation(point, fixEpochMs, stateEpochMs, receivedMs, false)

    private fun phone(
        point: NavigationPoint = NavigationPoint(0.0, 0.0),
        accuracy: Double = 5.0,
        fixMs: Long = ELAPSED,
    ) = PhoneNavigationLocation(point, accuracy, null, fixMs, ELAPSED)

    private fun heading(degrees: Double, fixMs: Long = ELAPSED) =
        PhoneNavigationHeading(degrees, fixMs, ELAPSED)

    private fun assertNoCurrentValue(value: VehicleNavigationPresentation) {
        assertNull(value.distanceMeters)
        assertNull(value.arrowDegrees)
        assertNull(value.directionLabel)
    }

    private companion object {
        const val ELAPSED = 100_000L
        const val EPOCH = 1_800_000_000_000L
    }
}
