package com.heytesla.app.vehicle.navigation

import java.io.Closeable
import kotlin.math.atan2

internal enum class PhoneNavigationUnavailable {
    LOCATION_PERMISSION_REQUIRED,
    LOCATION_DISABLED,
    LOCATION_UNAVAILABLE,
    HEADING_UNSUPPORTED,
    HEADING_UNRELIABLE,
}

/**
 * Single-use, main-thread source. The owner must establish its location FGS before start().
 * No permission request, service start, stored fix, or vehicle operation belongs in this source.
 * close() seals delivery immediately; a native cleanup failure throws a fixed, non-sensitive
 * exception and retains the affected registration for a later close() attempt.
 */
internal interface PhoneNavigationSource : Closeable {
    fun start(listener: Listener)

    interface Listener {
        fun onLocation(value: PhoneNavigationLocation)
        fun onHeading(value: PhoneNavigationHeading)
        fun onUnavailable(reason: PhoneNavigationUnavailable)
    }
}

/** Native nanoseconds share elapsedRealtime's suspend-aware origin, not callback time. */
internal fun navigationSourceFixElapsedMs(
    timestampNanos: Long,
    receivedAtElapsedMs: Long,
    nowElapsedMs: Long,
    ttlMs: Long,
): Long? {
    if (timestampNanos <= 0L) return null
    val fixMs = timestampNanos / 1_000_000L
    return fixMs.takeIf { navigationElapsedSampleFresh(it, receivedAtElapsedMs, nowElapsedMs, ttlMs) }
}

/**
 * Location callback admission shared by Android ingress and its tracker-bound consumer tests.
 * An older/invalid auxiliary-provider sample cannot revoke a still-fresh accepted fix, and
 * rejection never changes that fix's native or ingress timestamp.
 */
internal class PhoneNavigationLocationAdmission(private val listener: PhoneNavigationSource.Listener) {
    var latestLocation: PhoneNavigationLocation? = null
        private set

    fun clear() { latestLocation = null }

    fun accept(
        timestampNanos: Long,
        receivedAtElapsedMs: Long,
        nowElapsedMs: Long,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double?,
        altitudeMeters: Double?,
    ): PhoneNavigationLocation? {
        val previous = latestLocation
        if (timestampNanos > 0L && previous != null &&
            timestampNanos / 1_000_000L <= previous.fixElapsedMs) return null
        val fixElapsedMs = navigationSourceFixElapsedMs(timestampNanos, receivedAtElapsedMs,
            nowElapsedMs, VehicleNavigationPolicy.PHONE_FIX_TTL_MS)
        if (fixElapsedMs == null || accuracyMeters == null || !accuracyMeters.isFinite() ||
            accuracyMeters < 0.0 || !latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0) {
            if (previous == null || !navigationElapsedSampleFresh(previous.fixElapsedMs,
                    previous.receivedAtElapsedMs, nowElapsedMs, VehicleNavigationPolicy.PHONE_FIX_TTL_MS)) {
                clear()
                listener.onUnavailable(PhoneNavigationUnavailable.LOCATION_UNAVAILABLE)
            }
            return null
        }
        val sample = PhoneNavigationLocation(NavigationPoint(latitude, longitude), accuracyMeters,
            altitudeMeters?.takeIf { it.isFinite() }, fixElapsedMs, receivedAtElapsedMs)
        latestLocation = sample
        listener.onLocation(sample)
        return sample
    }
}

private const val MIN_PHONE_TOP_HORIZONTAL_SQUARED = 0.0225 // 0.15: reject a near-vertical top axis.

private const val MAX_ROTATION_HEADING_ERROR_RADIANS = 0.5235988f // 30-degree display policy.

/** Unknown angular accuracy or a malformed quaternion must not turn into a north-facing arrow. */
internal fun navigationRotationVectorReliable(values: FloatArray, accuracyKnown: Boolean): Boolean {
    if (!accuracyKnown || values.size < 5 || !values[4].isFinite() ||
        values[4] < 0f || values[4] > MAX_ROTATION_HEADING_ERROR_RADIANS) return false
    var quaternionLengthSquared = 0.0
    for (index in 0..3) {
        val value = values[index].toDouble()
        if (!value.isFinite()) return false
        quaternionLengthSquared += value * value
    }
    return quaternionLengthSquared in 0.98..1.02
}

/**
 * Android's display-remapped device +Y is the visible phone top. Matrix columns map device
 * axes into east/north/up, so atan2(east, north) is clockwise from magnetic north. Never invent
 * a frontal zero-degree heading when the top axis has no stable horizontal projection.
 */
internal fun navigationPhoneTopTrueHeading(displayRotationMatrix: FloatArray, declinationDegrees: Double): Double? {
    if (displayRotationMatrix.size != 9 || !declinationDegrees.isFinite()) return null
    for (value in displayRotationMatrix) if (!value.isFinite()) return null
    val east = displayRotationMatrix[1].toDouble()
    val north = displayRotationMatrix[4].toDouble()
    val up = displayRotationMatrix[7].toDouble()
    val horizontalSquared = east * east + north * north
    val axisLengthSquared = horizontalSquared + up * up
    if (axisLengthSquared !in 0.98..1.02 || horizontalSquared < MIN_PHONE_TOP_HORIZONTAL_SQUARED) return null
    return navigationWrapDegrees(Math.toDegrees(atan2(east, north)) + declinationDegrees)
}
