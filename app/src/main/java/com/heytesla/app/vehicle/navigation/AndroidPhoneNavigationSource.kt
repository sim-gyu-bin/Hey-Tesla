package com.heytesla.app.vehicle.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface

/** Actual Android GPS/network + calibrated rotation-vector source; RAM-only and single-use. */
internal class AndroidPhoneNavigationSource(
    context: Context,
    private val nowElapsedMs: () -> Long,
) : PhoneNavigationSource {
    private val context = context.applicationContext
    private val locations = this.context.getSystemService(LocationManager::class.java)
    private val sensors = this.context.getSystemService(SensorManager::class.java)
    private val displays = this.context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val rotationMatrix = FloatArray(9)
    private val displayMatrix = FloatArray(9)
    private var listener: PhoneNavigationSource.Listener? = null
    private var started = false
    private var closed = false
    private var locationRegistrationAttempted = false
    private var sensorRegistrationAttempted = false
    private var locationAdmission: PhoneNavigationLocationAdmission? = null
    private val lastLocation: PhoneNavigationLocation? get() = locationAdmission?.latestLocation
    private var lastLocationProvider: String? = null
    private var declinationDegrees: Double? = null
    private var lastSensorTimestampNanos = 0L
    private var lastIngressElapsedMs = -1L
    private var clockInvalid = false

    private fun checkThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PHONE_NAVIGATION_REQUIRES_MAIN_THREAD" }
    }

    private fun hasFinePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean = hasFinePermission() ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun unavailable(reason: PhoneNavigationUnavailable) {
        if (!closed) listener?.onUnavailable(reason)
    }

    private fun clearLocation() {
        locationAdmission?.clear()
        lastLocationProvider = null
        declinationDegrees = null
    }

    private fun acceptIngress(receivedAtElapsedMs: Long): Boolean {
        if (receivedAtElapsedMs < 0L || receivedAtElapsedMs < lastIngressElapsedMs) clockInvalid = true
        if (clockInvalid) return false
        lastIngressElapsedMs = receivedAtElapsedMs
        return true
    }

    @SuppressLint("MissingPermission")
    override fun start(listener: PhoneNavigationSource.Listener) {
        checkThread()
        check(!started && !closed) { "PHONE_NAVIGATION_SOURCE_ALREADY_USED" }
        started = true
        this.listener = listener
        locationAdmission = PhoneNavigationLocationAdmission(listener)
        if (!hasLocationPermission()) {
            unavailable(PhoneNavigationUnavailable.LOCATION_PERMISSION_REQUIRED)
            return
        }
        val manager = locations
        if (manager == null) {
            unavailable(PhoneNavigationUnavailable.LOCATION_UNAVAILABLE)
            return
        }
        try {
            if (!manager.isLocationEnabled) {
                unavailable(PhoneNavigationUnavailable.LOCATION_DISABLED)
                return
            }
            val gpsExists = manager.hasProvider(LocationManager.GPS_PROVIDER)
            val networkExists = manager.hasProvider(LocationManager.NETWORK_PROVIDER)
            val gpsEnabled = gpsExists && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val networkEnabled = networkExists && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            val useGps = gpsEnabled && hasFinePermission()
            if (!useGps && !networkEnabled) {
                unavailable(when {
                    gpsEnabled && !hasFinePermission() -> PhoneNavigationUnavailable.LOCATION_PERMISSION_REQUIRED
                    gpsExists || networkExists -> PhoneNavigationUnavailable.LOCATION_DISABLED
                    else -> PhoneNavigationUnavailable.LOCATION_UNAVAILABLE
                })
                return
            }
            // Retain before every native call: an exception does not prove no registration occurred.
            if (useGps) {
                locationRegistrationAttempted = true
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0f,
                    locationListener, Looper.getMainLooper())
            }
            if (closed) return
            if (networkEnabled) {
                locationRegistrationAttempted = true
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_INTERVAL_MS, 0f,
                    locationListener, Looper.getMainLooper())
            }
            if (closed) return
            val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (sensor == null) {
                unavailable(PhoneNavigationUnavailable.HEADING_UNSUPPORTED)
                return
            }
            sensorRegistrationAttempted = true
            val registered = checkNotNull(sensors).registerListener(sensorListener, sensor,
                SensorManager.SENSOR_DELAY_UI, handler)
            if (!registered) {
                sensorRegistrationAttempted = false // false is the platform's explicit non-registration result.
                unavailable(PhoneNavigationUnavailable.HEADING_UNSUPPORTED)
            }
        } catch (_: Exception) {
            // No native exception text/cause: it can carry provider details or private input.
            close() // A cleanup failure takes precedence and keeps the uncertain handle retained.
            throw IllegalStateException("PHONE_NAVIGATION_START_FAILED")
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            checkThread()
            if (closed) return
            val receivedAtElapsedMs = nowElapsedMs()
            if (!acceptIngress(receivedAtElapsedMs)) {
                clearLocation()
                unavailable(PhoneNavigationUnavailable.LOCATION_UNAVAILABLE)
                return
            }
            if (!hasLocationPermission()) {
                clearLocation()
                unavailable(PhoneNavigationUnavailable.LOCATION_PERMISSION_REQUIRED)
                return
            }
            val manager = locations
            val provider = location.provider
            if (manager == null || !manager.isLocationEnabled) {
                clearLocation()
                unavailable(PhoneNavigationUnavailable.LOCATION_DISABLED)
                return
            }
            val providerAllowed = when (provider) {
                LocationManager.GPS_PROVIDER -> hasFinePermission()
                LocationManager.NETWORK_PROVIDER -> true
                else -> false
            }
            val enabled = try {
                providerAllowed && manager.isProviderEnabled(checkNotNull(provider))
            } catch (_: Exception) { false }
            if (!enabled) {
                clearLocation()
                unavailable(if (provider == LocationManager.GPS_PROVIDER && !hasFinePermission())
                    PhoneNavigationUnavailable.LOCATION_PERMISSION_REQUIRED
                    else PhoneNavigationUnavailable.LOCATION_UNAVAILABLE)
                return
            }
            val sample = locationAdmission?.accept(
                timestampNanos = location.elapsedRealtimeNanos,
                receivedAtElapsedMs = receivedAtElapsedMs,
                nowElapsedMs = nowElapsedMs(),
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            )
            if (sample == null) {
                if (lastLocation == null) clearLocation()
                return
            }
            if (closed) return // The consumer may close synchronously while accepting a location.
            lastLocationProvider = provider
            val epochMs = System.currentTimeMillis()
            declinationDegrees = if (epochMs > 0L) {
                // WMM uses mean sea level when altitude is absent; no altitude is claimed as measured.
                GeomagneticField(sample.point.latitude.toFloat(), sample.point.longitude.toFloat(),
                    (sample.altitudeMeters ?: 0.0).toFloat(), epochMs).declination.toDouble().takeIf { it.isFinite() }
            } else null
        }

        override fun onProviderDisabled(provider: String) {
            checkThread()
            if (closed) return
            val affectedCurrentFix = provider == lastLocationProvider
            if (affectedCurrentFix) clearLocation()
            val enabled = try {
                locations?.isLocationEnabled == true && (
                    (hasFinePermission() && locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) ||
                        (hasLocationPermission() && locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)))
            } catch (_: Exception) { false }
            if (enabled && !affectedCurrentFix) return
            unavailable(if (enabled) PhoneNavigationUnavailable.LOCATION_UNAVAILABLE
                else PhoneNavigationUnavailable.LOCATION_DISABLED)
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            checkThread()
            if (closed || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val receivedAtElapsedMs = nowElapsedMs()
            if (!acceptIngress(receivedAtElapsedMs)) {
                unavailable(PhoneNavigationUnavailable.HEADING_UNRELIABLE)
                return
            }
            if (!hasLocationPermission() ||
                (lastLocationProvider == LocationManager.GPS_PROVIDER && !hasFinePermission())) {
                clearLocation()
                unavailable(PhoneNavigationUnavailable.LOCATION_PERMISSION_REQUIRED)
                return
            }
            if (locations?.isLocationEnabled != true) {
                clearLocation()
                unavailable(PhoneNavigationUnavailable.LOCATION_DISABLED)
                return
            }
            val fixElapsedMs = navigationSourceFixElapsedMs(event.timestamp, receivedAtElapsedMs,
                nowElapsedMs(), VehicleNavigationPolicy.HEADING_FIX_TTL_MS)
            if (fixElapsedMs == null) {
                unavailable(PhoneNavigationUnavailable.HEADING_UNRELIABLE)
                return
            }
            if (event.timestamp <= lastSensorTimestampNanos) return
            lastSensorTimestampNanos = event.timestamp
            val accuracyKnown = event.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ||
                event.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH
            // Unknown heading accuracy or an invalid quaternion is not a usable orientation.
            if (!navigationRotationVectorReliable(event.values, accuracyKnown)) {
                unavailable(PhoneNavigationUnavailable.HEADING_UNRELIABLE)
                return
            }
            val phone = lastLocation
            val declination = declinationDegrees
            if (phone == null || declination == null ||
                phone.accuracyMeters > VehicleNavigationPolicy.MAX_PHONE_ACCURACY_METERS ||
                !navigationElapsedSampleFresh(phone.fixElapsedMs, phone.receivedAtElapsedMs,
                    receivedAtElapsedMs, VehicleNavigationPolicy.PHONE_FIX_TTL_MS)) {
                unavailable(PhoneNavigationUnavailable.HEADING_UNRELIABLE)
                return
            }
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val rotation = displays?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            val remapped = when (rotation) {
                Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(rotationMatrix,
                    SensorManager.AXIS_X, SensorManager.AXIS_Y, displayMatrix)
                Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotationMatrix,
                    SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, displayMatrix)
                Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotationMatrix,
                    SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, displayMatrix)
                Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotationMatrix,
                    SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, displayMatrix)
                else -> false
            }
            val heading = if (remapped) navigationPhoneTopTrueHeading(displayMatrix, declination) else null
            if (heading == null) {
                unavailable(PhoneNavigationUnavailable.HEADING_UNRELIABLE)
                return
            }
            listener?.onHeading(PhoneNavigationHeading(heading, fixElapsedMs, receivedAtElapsedMs))
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            checkThread()
            if (!closed && sensor.type == Sensor.TYPE_ROTATION_VECTOR &&
                accuracy != SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM &&
                accuracy != SensorManager.SENSOR_STATUS_ACCURACY_HIGH) {
                unavailable(PhoneNavigationUnavailable.HEADING_UNRELIABLE)
            }
        }
    }

    override fun close() {
        checkThread()
        closed = true // Seal delivery before any unregister, including synchronous/late callbacks.
        listener = null
        clearLocation()
        locationAdmission = null
        var failed = false
        if (locationRegistrationAttempted) {
            try {
                checkNotNull(locations).removeUpdates(locationListener)
                locationRegistrationAttempted = false
            } catch (_: Exception) { failed = true }
        }
        if (sensorRegistrationAttempted) {
            try {
                checkNotNull(sensors).unregisterListener(sensorListener)
                sensorRegistrationAttempted = false
            } catch (_: Exception) { failed = true }
        }
        if (failed) throw IllegalStateException("PHONE_NAVIGATION_CLEANUP_FAILED")
    }

    private companion object {
        const val LOCATION_INTERVAL_MS = 1_000L
    }
}
