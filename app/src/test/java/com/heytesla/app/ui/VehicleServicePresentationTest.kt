package com.heytesla.app.ui

import com.heytesla.app.vehicle.navigation.NavigationPoint
import com.heytesla.app.vehicle.navigation.PhoneNavigationHeading
import com.heytesla.app.vehicle.navigation.PhoneNavigationLocation
import com.heytesla.app.vehicle.navigation.VehicleCoordinateObservation
import com.heytesla.app.vehicle.navigation.VehicleNavigationPresentation
import com.heytesla.app.vehicle.navigation.VehicleNavigationStatus
import com.heytesla.app.vehicle.navigation.VehicleNavigationTracker
import com.heytesla.app.vehicle.uwb.TeslaUwbDistance
import com.heytesla.app.vehicle.uwb.TeslaUwbProbeState
import com.heytesla.app.vehicle.uwb.TeslaUwbStage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleServicePresentationTest {
    private fun sample(peer: Int = 0, receivedAt: Long = 5_000) = TeslaUwbDistance(
        peerIndex = peer,
        meters = 1.25,
        confidence = 2,
        frameworkTimestampMillis = Long.MAX_VALUE,
        receivedAtElapsedMs = receivedAt,
    )

    private fun ranging(vararg samples: TeslaUwbDistance) = TeslaUwbProbeState(
        serviceOwned = true,
        stage = TeslaUwbStage.RANGING,
        samples = samples.toList(),
        livePeerIndices = samples.map { it.peerIndex }.toSet(),
    )

    @Test fun newestLiveCallbackWinsAndTiesUsePeerIndexRegardlessOfListOrderOrDistance() {
        val older = sample(0, 4_999).copy(meters = 0.1)
        val lowerIndex = sample(1).copy(meters = 5.0)
        val higherIndex = sample(2).copy(meters = 0.2)
        assertSame(lowerIndex, vehicleUwbRecentSample(ranging(older, higherIndex, lowerIndex), 5_001))
        assertSame(lowerIndex, vehicleUwbRecentSample(ranging(lowerIndex, higherIndex, older), 5_001))
    }

    @Test fun expiryUsesCallbackElapsedNotFrameworkClockOrInvalidResultCount() {
        val original = sample()
        val state = ranging(original).copy(lastReceivedSample = original, validDistanceCount = 1)
        assertSame(original, vehicleUwbRecentSample(state, 7_000))
        assertNull(vehicleUwbRecentSample(state, 7_001))
        assertNull(vehicleUwbRecentSample(state.copy(invalidResultCount = 100), 7_001))
        assertSame(original, vehicleUwbRecentSample(state.copy(invalidResultCount = 100), 6_999))
        assertNull(vehicleUwbRecentSample(state, 4_999))
        val negativeClock = original.copy(receivedAtElapsedMs = -1)
        assertNull(vehicleUwbRecentSample(ranging(negativeClock), 0))
    }

    @Test fun stoppedPeerCannotUseRetainedSamplesOrLastCallback() {
        val stopped = sample(0, 5_001)
        val live = sample(1, 5_000)
        val state = ranging(stopped, live).copy(
            livePeerIndices = setOf(1),
            lastReceivedSample = stopped,
        )
        assertSame(live, vehicleUwbRecentSample(state, 5_002))
        assertFalse(teslaUwbSampleIsRecent(state, stopped, 5_002))
        assertNull(vehicleUwbRecentSample(state.copy(livePeerIndices = emptySet()), 5_002))
    }

    @Test fun currentDistanceRequiresActualRangingAndServiceOwnership() {
        val original = sample()
        val state = ranging(original)
        for (stage in TeslaUwbStage.entries) {
            if (stage == TeslaUwbStage.RANGING) continue
            assertNull(vehicleUwbRecentSample(state.copy(stage = stage), 5_001))
            assertFalse(teslaUwbSampleIsRecent(state.copy(stage = stage), original, 5_001))
        }
        assertNull(vehicleUwbRecentSample(state.copy(serviceOwned = false), 5_001))
        assertNull(vehicleUwbNotificationText(state.copy(serviceOwned = false), 5_001))
        assertNull(vehicleUwbNotificationText(state.copy(stage = TeslaUwbStage.IDLE), 5_001))
        assertNotNull(vehicleUwbNotificationText(state.copy(stage = TeslaUwbStage.CLEANUP_FAILED), 5_001))
        assertNotNull(vehicleUwbNotificationText(state.copy(stage = TeslaUwbStage.FAILED), 5_001))
    }

    @Test fun invalidDistancesConfidenceAndUnknownPeersCannotBecomeCurrent() {
        val original = sample()
        for (meters in listOf(-0.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            val invalid = original.copy(meters = meters)
            assertNull(vehicleUwbRecentSample(ranging(invalid), 5_001))
        }
        for (confidence in listOf(-1, 3, Int.MAX_VALUE)) {
            val invalid = original.copy(confidence = confidence)
            assertNull(vehicleUwbRecentSample(ranging(invalid), 5_001))
        }
        val unknown = original.copy(peerIndex = -1)
        assertNull(vehicleUwbRecentSample(ranging(unknown), 5_001))
        assertNull(vehicleUwbRecentSample(ranging(), 5_001))
        assertTrue(teslaUwbSampleIsRecent(ranging(original), original.copy(meters = 0.0, confidence = 0), 5_001))
    }

    @Test fun selectedDistanceKeepsOnlyItsOwnAnglesWithoutOldOrOtherPeerFallback() {
        val previous = sample(0, 4_999).copy(azimuthRaw = 0.25, azimuthConfidence = 2)
        val selected = sample(1).copy(elevationRaw = 0.5, elevationConfidence = null)
        val state = ranging(previous, selected).copy(
            azimuthSupported = true,
            elevationSupported = true,
            lastReceivedSample = previous,
        )
        val result = vehicleUwbRecentSample(state, 5_001)
        assertSame(selected, result)
        assertNull(result?.azimuthRaw)
        assertNull(result?.azimuthConfidence)
        assertNull(result?.elevationConfidence)
    }

    @Test fun newSessionDoesNotReplayTheLastReceivedSampleWithoutCurrentPeerSamples() {
        val old = sample()
        val state = ranging().copy(lastReceivedSample = old, livePeerIndices = setOf(old.peerIndex))
        assertNull(vehicleUwbRecentSample(state, 5_001))
    }

    @Test fun staleOrUnavailableCoordinatesNeverRenderRetainedDistanceOrArrow() {
        val valid = VehicleNavigationPresentation(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            distanceMeters = 42.0, arrowDegrees = 90.0, directionLabel = "북쪽")
        assertNotNull(vehicleNavigationDistance(valid))
        assertNotNull(vehicleNavigationArrow(valid))
        for (status in listOf(VehicleNavigationStatus.OFF, VehicleNavigationStatus.START_REQUIRED,
            VehicleNavigationStatus.VEHICLE_LOCATION_STALE, VehicleNavigationStatus.PHONE_LOCATION_STALE,
            VehicleNavigationStatus.PHONE_LOCATION_INACCURATE, VehicleNavigationStatus.CLOCK_INVALID,
            VehicleNavigationStatus.CLOCK_SAMPLE_DELAYED,
            VehicleNavigationStatus.LOCATION_PERMISSION_REQUIRED, VehicleNavigationStatus.CLEANUP_FAILED)) {
            val stale = valid.copy(status = status)
            assertNull(vehicleNavigationDistance(stale))
            assertNull(vehicleNavigationArrow(stale))
            assertFalse(vehicleNavigationText(stale).contains("42"))
        }
    }

    @Test fun phoneRelativeDirectionNeverUsesNorthLabelOrInventsMissingHeading() {
        val valid = VehicleNavigationPresentation(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            distanceMeters = 42.0, arrowDegrees = 90.0, directionLabel = "북쪽")
        assertTrue(vehicleNavigationText(valid).contains("폰 상단 기준 오른쪽"))
        assertFalse(vehicleNavigationText(valid).contains("북쪽"))
        for (status in listOf(VehicleNavigationStatus.HEADING_UNAVAILABLE, VehicleNavigationStatus.HEADING_STALE,
            VehicleNavigationStatus.HEADING_UNSUPPORTED, VehicleNavigationStatus.HEADING_UNRELIABLE,
            VehicleNavigationStatus.NEARBY, VehicleNavigationStatus.DISTANCE_ONLY)) {
            val withoutHeading = valid.copy(status = status)
            assertNotNull(vehicleNavigationDistance(withoutHeading))
            assertNull(vehicleNavigationArrow(withoutHeading))
            assertFalse(vehicleNavigationText(withoutHeading).contains("폰 상단 기준"))
        }
        for (arrow in listOf(-1.0, 360.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(vehicleNavigationArrow(valid.copy(arrowDegrees = arrow)))
        }
    }

    @Test fun coordinateDistanceCannotReviveAnExpiredOrStoppedUwbSample() {
        val uwb = ranging(sample())
        val coordinate = VehicleNavigationPresentation(VehicleNavigationStatus.DIRECTION_AVAILABLE,
            distanceMeters = 42.0, arrowDegrees = 0.0)
        assertNotNull(vehicleNavigationDistance(coordinate))
        assertNull(vehicleUwbRecentSample(uwb, 7_001))
        assertFalse(checkNotNull(vehicleUwbNotificationText(uwb, 7_001)).contains("1.25"))
        assertNull(vehicleUwbRecentSample(uwb.copy(livePeerIndices = emptySet()), 5_001))
        assertNull(vehicleNavigationDistance(coordinate.copy(distanceMeters = Double.NaN)))
    }

    @Test fun armedIdleServiceNeverNeedsPhoneCollectionAndUnresolvedAttemptStopsAtReleaseOrRejection() {
        val waiting = VehicleNavigationStatus.WAITING_FOR_VEHICLE_LOCATION
        assertFalse(vehicleNavigationShouldCollect(false, false, null, waiting, 10_000L))
        assertTrue(vehicleNavigationShouldCollect(true, false, null, waiting, 10_000L))
        assertFalse(vehicleNavigationShouldCollect(false, false, null, waiting, 10_001L))
        assertFalse(vehicleNavigationShouldCollect(true, true, null, waiting, 10_001L))
    }

    @Test fun acceptedFixSurvivesOperationReleaseOnlyUntilItsOriginalFixDeadlineEvenDuringLongUwbRanging() {
        val elapsed = 100_000L
        val epoch = 1_000_000L
        val tracker = VehicleNavigationTracker()
        tracker.acceptPhoneLocation(PhoneNavigationLocation(NavigationPoint(0.0, 0.0), 5.0, null, elapsed, elapsed))
        tracker.acceptPhoneHeading(PhoneNavigationHeading(0.0, elapsed, elapsed))
        tracker.acceptVehicleLocation(VehicleCoordinateObservation(NavigationPoint(0.0, 0.001),
            epoch - 59_000L, null, elapsed, false))
        val deadline = vehicleNavigationCollectionDeadline(epoch - 59_000L, elapsed, epoch, elapsed)
        val fresh = tracker.project(elapsed, epoch, elapsed)
        assertNotNull(vehicleNavigationDistance(fresh))
        assertTrue(vehicleNavigationShouldCollect(false, false, deadline, fresh.status, elapsed))
        val expired = tracker.project(elapsed + 1_001L, epoch + 1_001L, elapsed + 1_001L)
        assertNull(vehicleNavigationDistance(expired))
        assertFalse(vehicleNavigationShouldCollect(false, false, deadline, expired.status, elapsed + 1_001L))
        assertFalse(vehicleNavigationShouldCollect(true, false, deadline, expired.status, elapsed + 1_001L))
    }

    @Test fun clockSamplingDelayCannotExtendCollectionDeadlineOrResurrectARejectedRead() {
        val deadline = vehicleNavigationCollectionDeadline(1_000_000L, 100_000L, 1_000_000L, 100_000L)
        assertTrue(vehicleNavigationShouldCollect(false, false, deadline,
            VehicleNavigationStatus.CLOCK_SAMPLE_DELAYED, 100_001L))
        assertFalse(vehicleNavigationShouldCollect(true, false, deadline,
            VehicleNavigationStatus.CLOCK_SAMPLE_DELAYED, 160_001L))
        assertFalse(vehicleNavigationShouldCollect(true, false, deadline,
            VehicleNavigationStatus.CLOCK_INVALID, 100_001L))
        assertFalse(vehicleNavigationShouldCollect(true, true, deadline,
            VehicleNavigationStatus.DIRECTION_AVAILABLE, 100_001L))
    }
}
