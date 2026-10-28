package com.heytesla.app.vehicle.navigation

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** RAM-only WGS84 point. An explicitly present (0, 0) is not a missing-value sentinel. */
internal data class NavigationPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0) { "INVALID_NAVIGATION_POINT" }
    }

    override fun toString(): String = "NavigationPoint(redacted)"
}

/** gps_as_of is converted from epoch seconds by the authenticated vehicle parser. */
internal data class VehicleCoordinateObservation(
    val point: NavigationPoint,
    val fixEpochMs: Long,
    val stateEpochMs: Long?,
    val receivedAtElapsedMs: Long,
    val isRaw: Boolean,
) {
    override fun toString(): String = "VehicleCoordinateObservation(redacted)"
}

internal data class PhoneNavigationLocation(
    val point: NavigationPoint,
    val accuracyMeters: Double,
    val altitudeMeters: Double?,
    val fixElapsedMs: Long,
    val receivedAtElapsedMs: Long,
) {
    override fun toString(): String = "PhoneNavigationLocation(redacted)"
}

internal data class PhoneNavigationHeading(
    val trueNorthDegrees: Double,
    val fixElapsedMs: Long,
    val receivedAtElapsedMs: Long,
) {
    override fun toString(): String = "PhoneNavigationHeading(redacted)"
}

internal enum class VehicleNavigationStatus {
    OFF,
    START_REQUIRED,
    STARTING,
    LOCATION_PERMISSION_REQUIRED,
    LOCATION_DISABLED,
    LOCATION_UNAVAILABLE,
    HEADING_UNSUPPORTED,
    HEADING_UNRELIABLE,
    FOREGROUND_FAILED,
    CLEANUP_FAILED,
    VEHICLE_LOCATION_UNAVAILABLE,
    WAITING_FOR_VEHICLE_LOCATION,
    WAITING_FOR_PHONE_LOCATION,
    VEHICLE_LOCATION_STALE,
    PHONE_LOCATION_STALE,
    PHONE_LOCATION_INACCURATE,
    HEADING_UNAVAILABLE,
    HEADING_STALE,
    NEARBY,
    DISTANCE_ONLY,
    DIRECTION_AVAILABLE,
    CLOCK_INVALID,
    CLOCK_SAMPLE_DELAYED,
}

/** The only navigation value intended for UI/StateFlow; never contains either coordinate. */
internal data class VehicleNavigationPresentation(
    val status: VehicleNavigationStatus,
    val distanceMeters: Double? = null,
    val arrowDegrees: Double? = null,
    val directionLabel: String? = null,
    val vehicleAgeSeconds: Long? = null,
    val phoneAccuracyMeters: Double? = null,
)

/** Display/freshness policy only. None of these thresholds authorizes UWB or a vehicle command. */
internal object VehicleNavigationPolicy {
    // WGS84 mean Earth radius; the distance is an approximate spherical distance, not a survey.
    const val EARTH_RADIUS_METERS = 6_371_008.8
    const val VEHICLE_FIX_TTL_MS = 60_000L
    const val PHONE_FIX_TTL_MS = 15_000L
    const val HEADING_FIX_TTL_MS = 2_000L
    const val FUTURE_TOLERANCE_MS = 2_000L
    const val MAX_CLOCK_SAMPLE_SPAN_MS = 100L
    const val MAX_PHONE_ACCURACY_METERS = 100.0
    const val NEARBY_MIN_METERS = 10.0
    const val MAX_DIRECTION_DISTANCE_METERS = 1_000.0
}

/** Single-owner, serial tracker. No coordinates survive clear(), persistence, or process restart. */
internal class VehicleNavigationTracker {
    private var vehicleLocation: VehicleCoordinateObservation? = null
    private var phoneLocation: PhoneNavigationLocation? = null
    private var phoneHeading: PhoneNavigationHeading? = null
    private var clockElapsedMs = -1L
    private var lastClockReadElapsedMs = -1L
    private var clockEpochMs = 0L
    private var clockInvalid = false

    fun acceptPhoneLocation(value: PhoneNavigationLocation) { phoneLocation = value }
    fun acceptPhoneHeading(value: PhoneNavigationHeading) { phoneHeading = value }
    fun acceptVehicleLocation(value: VehicleCoordinateObservation) { vehicleLocation = value }
    fun invalidatePhoneLocation() { phoneLocation = null; phoneHeading = null }
    fun invalidatePhoneHeading() { phoneHeading = null }
    fun invalidateVehicleLocation() { vehicleLocation = null }

    fun clear() {
        vehicleLocation = null
        phoneLocation = null
        phoneHeading = null
        clockElapsedMs = -1L
        lastClockReadElapsedMs = -1L
        clockEpochMs = 0L
        clockInvalid = false
    }

    /**
     * Sample elapsed -> epoch -> elapsed. A wide interval is not a clock rollback: suppress
     * current values for that projection without changing the epoch anchor, then sample again.
     * Accepted intervals connect at their midpoint, retaining an epoch lower bound across the
     * endpoint uncertainty so neither sampling jitter nor wall-clock drift revives an old fix.
     * Actual elapsed rollback or a material wall-clock rollback seals this owner until clear().
     */
    fun project(elapsedBeforeMs: Long, nowEpochMs: Long, elapsedAfterMs: Long): VehicleNavigationPresentation {
        val effectiveEpochMs = advanceClock(elapsedBeforeMs, nowEpochMs, elapsedAfterMs)
        if (effectiveEpochMs < 0L) return VehicleNavigationPresentation(
            if (clockInvalid) VehicleNavigationStatus.CLOCK_INVALID else VehicleNavigationStatus.CLOCK_SAMPLE_DELAYED)
        val nowElapsedMs = elapsedAfterMs
        val vehicle = vehicleLocation
            ?: return VehicleNavigationPresentation(VehicleNavigationStatus.WAITING_FOR_VEHICLE_LOCATION)
        val vehicleAgeMs = vehicleAgeMs(vehicle, nowElapsedMs, effectiveEpochMs)
            ?: return VehicleNavigationPresentation(VehicleNavigationStatus.VEHICLE_LOCATION_STALE)
        val vehicleAgeSeconds = vehicleAgeMs / 1_000L
        if (vehicleAgeMs > VehicleNavigationPolicy.VEHICLE_FIX_TTL_MS) {
            return VehicleNavigationPresentation(VehicleNavigationStatus.VEHICLE_LOCATION_STALE,
                vehicleAgeSeconds = vehicleAgeSeconds)
        }
        val phone = phoneLocation ?: return VehicleNavigationPresentation(
            VehicleNavigationStatus.WAITING_FOR_PHONE_LOCATION, vehicleAgeSeconds = vehicleAgeSeconds)
        if (!navigationElapsedSampleFresh(phone.fixElapsedMs, phone.receivedAtElapsedMs,
                nowElapsedMs, VehicleNavigationPolicy.PHONE_FIX_TTL_MS)) {
            return VehicleNavigationPresentation(VehicleNavigationStatus.PHONE_LOCATION_STALE,
                vehicleAgeSeconds = vehicleAgeSeconds)
        }
        if (!phone.accuracyMeters.isFinite() || phone.accuracyMeters < 0.0 ||
            phone.accuracyMeters > VehicleNavigationPolicy.MAX_PHONE_ACCURACY_METERS) {
            return VehicleNavigationPresentation(VehicleNavigationStatus.PHONE_LOCATION_INACCURATE,
                vehicleAgeSeconds = vehicleAgeSeconds,
                phoneAccuracyMeters = phone.accuracyMeters.takeIf { it.isFinite() && it >= 0.0 })
        }
        val distance = vehicleNavigationDistanceMeters(phone.point, vehicle.point)
        val near = distance <= max(VehicleNavigationPolicy.NEARBY_MIN_METERS, phone.accuracyMeters)
        val heading = phoneHeading
        val bearing = if (!near && distance <= VehicleNavigationPolicy.MAX_DIRECTION_DISTANCE_METERS) {
            vehicleNavigationRhumbBearingDegrees(phone.point, vehicle.point)
        } else null
        val status = when {
            near -> VehicleNavigationStatus.NEARBY
            distance > VehicleNavigationPolicy.MAX_DIRECTION_DISTANCE_METERS || bearing == null ->
                VehicleNavigationStatus.DISTANCE_ONLY
            heading == null || !heading.trueNorthDegrees.isFinite() -> VehicleNavigationStatus.HEADING_UNAVAILABLE
            !navigationElapsedSampleFresh(heading.fixElapsedMs, heading.receivedAtElapsedMs,
                nowElapsedMs, VehicleNavigationPolicy.HEADING_FIX_TTL_MS) -> VehicleNavigationStatus.HEADING_STALE
            else -> VehicleNavigationStatus.DIRECTION_AVAILABLE
        }
        val hasDirection = status == VehicleNavigationStatus.DIRECTION_AVAILABLE
        return VehicleNavigationPresentation(
            status = status,
            distanceMeters = distance,
            arrowDegrees = if (hasDirection) navigationWrapDegrees(checkNotNull(bearing) -
                navigationWrapDegrees(checkNotNull(heading).trueNorthDegrees)) else null,
            directionLabel = if (hasDirection) navigationDirectionLabel(checkNotNull(bearing)) else null,
            vehicleAgeSeconds = vehicleAgeSeconds,
            phoneAccuracyMeters = phone.accuracyMeters,
        )
    }

    /** Negative result means unavailable; only actual clock faults latch clockInvalid. */
    private fun advanceClock(elapsedBeforeMs: Long, nowEpochMs: Long, elapsedAfterMs: Long): Long {
        if (clockInvalid) return -1L
        if (elapsedBeforeMs < 0L || elapsedAfterMs < elapsedBeforeMs || nowEpochMs <= 0L ||
            elapsedBeforeMs < lastClockReadElapsedMs) {
            clockInvalid = true
            return -1L
        }
        lastClockReadElapsedMs = elapsedAfterMs
        val spanMs = elapsedAfterMs - elapsedBeforeMs
        if (spanMs > VehicleNavigationPolicy.MAX_CLOCK_SAMPLE_SPAN_MS) return -1L
        val midpointMs = elapsedBeforeMs + spanMs / 2L
        val beforeMidpointMs = midpointMs - elapsedBeforeMs
        val afterMidpointMs = elapsedAfterMs - midpointMs
        if (nowEpochMs <= afterMidpointMs || nowEpochMs > Long.MAX_VALUE - beforeMidpointMs) {
            clockInvalid = true
            return -1L
        }
        val sampledEpochLowerMs = nowEpochMs - afterMidpointMs
        val sampledEpochUpperMs = nowEpochMs + beforeMidpointMs
        var nextEpochLowerMs = sampledEpochLowerMs
        if (clockElapsedMs >= 0L) {
            val deltaMs = midpointMs - clockElapsedMs
            if (deltaMs > Long.MAX_VALUE - clockEpochMs) {
                clockInvalid = true
                return -1L
            }
            val expectedEpochLowerMs = clockEpochMs + deltaMs
            if (sampledEpochUpperMs < expectedEpochLowerMs &&
                expectedEpochLowerMs - sampledEpochUpperMs > VehicleNavigationPolicy.FUTURE_TOLERANCE_MS) {
                clockInvalid = true
                return -1L
            }
            nextEpochLowerMs = max(sampledEpochLowerMs, expectedEpochLowerMs)
        }
        if (nextEpochLowerMs > Long.MAX_VALUE - afterMidpointMs) {
            clockInvalid = true
            return -1L
        }
        clockEpochMs = nextEpochLowerMs
        clockElapsedMs = midpointMs
        return nextEpochLowerMs + afterMidpointMs
    }

    private fun vehicleAgeMs(value: VehicleCoordinateObservation, nowElapsedMs: Long, nowEpochMs: Long): Long? {
        if (value.fixEpochMs <= 0L || !navigationNotTooFuture(value.fixEpochMs, nowEpochMs) ||
            value.receivedAtElapsedMs < 0L || !navigationNotTooFuture(value.receivedAtElapsedMs, nowElapsedMs)) return null
        val stateEpochMs = value.stateEpochMs
        if (stateEpochMs != null && (stateEpochMs <= 0L ||
                !navigationNotTooFuture(stateEpochMs, nowEpochMs) ||
                !navigationNotTooFuture(value.fixEpochMs, stateEpochMs))) return null
        return max(max(0L, nowEpochMs - value.fixEpochMs), max(0L, nowElapsedMs - value.receivedAtElapsedMs))
    }
}

internal fun navigationNotTooFuture(value: Long, now: Long): Boolean =
    value <= now || value - now <= VehicleNavigationPolicy.FUTURE_TOLERANCE_MS

/** Source time, callback ingress, and projection time must independently remain fresh. */
internal fun navigationElapsedSampleFresh(fixMs: Long, receivedMs: Long, nowMs: Long, ttlMs: Long): Boolean =
    fixMs >= 0L && receivedMs >= 0L && nowMs >= 0L &&
        navigationNotTooFuture(fixMs, receivedMs) && navigationNotTooFuture(fixMs, nowMs) &&
        navigationNotTooFuture(receivedMs, nowMs) &&
        (nowMs <= fixMs || nowMs - fixMs <= ttlMs) &&
        (nowMs <= receivedMs || nowMs - receivedMs <= ttlMs)

internal fun navigationWrapDegrees(value: Double): Double {
    val wrapped = value % 360.0
    return if (wrapped < 0.0) wrapped + 360.0 else if (wrapped == 0.0) 0.0 else wrapped
}

/** Haversine, with a clamped asin argument for roundoff at coincident/antipodal points. */
internal fun vehicleNavigationDistanceMeters(from: NavigationPoint, to: NavigationPoint): Double {
    val fromLatitude = Math.toRadians(from.latitude)
    val toLatitude = Math.toRadians(to.latitude)
    val halfLatitude = sin((toLatitude - fromLatitude) / 2.0)
    val halfLongitude = sin(Math.toRadians(to.longitude - from.longitude) / 2.0)
    val a = halfLatitude * halfLatitude + cos(fromLatitude) * cos(toLatitude) * halfLongitude * halfLongitude
    return 2.0 * VehicleNavigationPolicy.EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** Shortest rhumb line; poles, coincident points, and equal east/west paths have no unique arrow. */
internal fun vehicleNavigationRhumbBearingDegrees(from: NavigationPoint, to: NavigationPoint): Double? {
    if (abs(from.latitude) == 90.0 || abs(to.latitude) == 90.0) return null
    val fromLatitude = Math.toRadians(from.latitude)
    val toLatitude = Math.toRadians(to.latitude)
    var longitudeDelta = Math.toRadians(to.longitude - from.longitude)
    if (longitudeDelta > Math.PI) longitudeDelta -= 2.0 * Math.PI
    if (longitudeDelta < -Math.PI) longitudeDelta += 2.0 * Math.PI
    if (abs(abs(longitudeDelta) - Math.PI) < 1e-12) return null
    val latitudeDelta = ln(tan(Math.PI / 4.0 + toLatitude / 2.0) /
        tan(Math.PI / 4.0 + fromLatitude / 2.0))
    if (!latitudeDelta.isFinite() || (abs(longitudeDelta) < 1e-15 && abs(latitudeDelta) < 1e-15)) return null
    return navigationWrapDegrees(Math.toDegrees(atan2(longitudeDelta, latitudeDelta)))
}

private fun navigationDirectionLabel(bearingDegrees: Double): String = when (
    ((navigationWrapDegrees(bearingDegrees) + 22.5) / 45.0).toInt() % 8
) {
    0 -> "북쪽"
    1 -> "북동쪽"
    2 -> "동쪽"
    3 -> "남동쪽"
    4 -> "남쪽"
    5 -> "남서쪽"
    6 -> "서쪽"
    else -> "북서쪽"
}
