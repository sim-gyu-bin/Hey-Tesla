package com.heytesla.app.vehicle.uwb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TeslaUwbDistanceTest {
    @Test fun onlyKnownUwbPeerWithPresentFiniteNonnegativeDistanceAndReportedConfidenceIsAccepted() {
        for (confidence in 0..2) {
            assertNull(teslaUwbInvalidResultReason(0, 0, 0.0, confidence))
            assertNull(teslaUwbInvalidResultReason(3, 0, 1.25, confidence))
        }
        for (distance in listOf(null, -0.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertNotNull(teslaUwbInvalidResultReason(0, 0, distance, 2))
        }
        for (peer in listOf(null, -1)) {
            assertNotNull(teslaUwbInvalidResultReason(peer, 0, 1.25, 2))
        }
        for (technology in listOf(-1, 1, 2, 3, Int.MAX_VALUE)) {
            assertNotNull(teslaUwbInvalidResultReason(0, technology, 1.25, 2))
        }
        for (confidence in listOf(null, -1, 3, Int.MAX_VALUE)) {
            assertNotNull(teslaUwbInvalidResultReason(0, 0, 1.25, confidence))
        }
    }

    @Test fun finiteAnglesRemainRawWithoutInferringDegreesOrRadians() {
        for (raw in listOf(-540.0, -Math.PI, -0.0, 0.0, Math.PI, 180.0, Double.MAX_VALUE)) {
            assertEquals(raw, checkNotNull(teslaUwbAngleRaw(raw)), 0.0)
        }
    }

    @Test fun missingAndNonFiniteAnglesDoNotBecomeZero() {
        for (raw in listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertNull(teslaUwbAngleRaw(raw))
        }
    }

    @Test fun eachMeasurementConfidenceIsValidatedWithoutCoercion() {
        for (confidence in 0..2) {
            assertEquals(confidence, teslaUwbMeasurementConfidence(confidence))
        }
        for (confidence in listOf(null, -1, 3, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertNull(teslaUwbMeasurementConfidence(confidence))
        }
    }
}
