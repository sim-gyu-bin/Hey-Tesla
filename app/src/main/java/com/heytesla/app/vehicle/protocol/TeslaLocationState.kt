package com.heytesla.app.vehicle.protocol

import com.heytesla.app.vehicle.navigation.NavigationPoint
import com.heytesla.app.vehicle.navigation.VehicleCoordinateObservation

import com.tesla.generated.carserver.vehicle.Vehicle

/** Authenticated payload interpretation only. Freshness remains a separate navigation policy. */
internal object TeslaLocationStateParser {
    fun parse(value: Vehicle.LocationState, receivedAt: Long, nowEpochMs: Long): TeslaBleStep.LocationResult {
        fun unavailable(reason: String) = TeslaBleStep.LocationResult(null, reason)
        if (!value.hasGpsAsOf()) return unavailable("LOCATION_FIX_TIME_MISSING")
        // uint64 is represented by a signed JVM long. Negative means unsigned overflow here.
        val seconds = value.gpsAsOf
        if (seconds < 0 || seconds > Long.MAX_VALUE / 1_000L) return unavailable("LOCATION_FIX_TIME_OVERFLOW")
        val fixEpochMs = seconds * 1_000L
        if (nowEpochMs < 0 || fixEpochMs > nowEpochMs && fixEpochMs - nowEpochMs > 2_000L) {
            return unavailable("LOCATION_FIX_TIME_FUTURE")
        }
        if (receivedAt < 0) return unavailable("LOCATION_RECEIPT_TIME_INVALID")
        var reason = "LOCATION_COORDINATES_MISSING"
        var point: NavigationPoint? = null
        var raw = false
        val nativePresent = value.hasNativeLatitude() || value.hasNativeLongitude() || value.hasNativeType()
        val estimatedValid = value.hasEstimatedGpsValid() && value.estimatedGpsValid
        if (!estimatedValid) {
            reason = if (value.hasEstimatedGpsValid()) "LOCATION_ESTIMATED_INVALID" else "LOCATION_ESTIMATED_VALIDITY_MISSING"
        } else if (nativePresent) {
            reason = when {
                !value.hasNativeLatitude() || !value.hasNativeLongitude() -> "LOCATION_NATIVE_PAIR_INCOMPLETE"
                !value.hasNativeType() || value.nativeType.typeCase != Vehicle.LocationState.GPSCoordinateType.TypeCase.WGS ->
                    "LOCATION_COORDINATE_SYSTEM_UNSUPPORTED"
                value.hasNativeLocationSupported() && !value.nativeLocationSupported -> "LOCATION_NATIVE_UNSUPPORTED"
                else -> {
                    point = point(value.nativeLatitude, value.nativeLongitude)
                    if (point == null) "LOCATION_COORDINATES_INVALID" else reason
                }
            }
        } else if (value.hasLatitude() && value.hasLongitude()) {
            point = point(value.latitude, value.longitude)
            if (point == null) reason = "LOCATION_COORDINATES_INVALID"
        } else if (value.hasLatitude() || value.hasLongitude()) {
            reason = "LOCATION_PAIR_INCOMPLETE"
        }
        // Raw geo is explicitly WGS in vehicle.proto; never borrow an axis from another family.
        if (point == null && value.hasGeoLatitude() && value.hasGeoLongitude()) {
            point = point(value.geoLatitude, value.geoLongitude)
            raw = point != null
            if (point == null) reason = "LOCATION_RAW_COORDINATES_INVALID"
        } else if (point == null && (value.hasGeoLatitude() || value.hasGeoLongitude())) {
            reason = "LOCATION_RAW_PAIR_INCOMPLETE"
        }
        val selected = point ?: return unavailable(reason)
        val stateEpochMs = if (value.hasTimestamp()) {
            val stamp = value.timestamp
            if (stamp.seconds in -62_135_596_800L..253_402_300_799L && stamp.nanos in 0..999_999_999) {
                stamp.seconds * 1_000L + stamp.nanos / 1_000_000L
            } else null
        } else null
        return TeslaBleStep.LocationResult(VehicleCoordinateObservation(selected, fixEpochMs, stateEpochMs, receivedAt, raw), null)
    }

    private fun point(latitude: Float, longitude: Float): NavigationPoint? {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90f..90f || longitude !in -180f..180f) return null
        return NavigationPoint(latitude.toDouble(), longitude.toDouble())
    }
}
