package com.heytesla.app.vehicle.uwb

import com.heytesla.app.BuildConfig
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceBatteryState
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceInfo
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceInfoSource
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceInfoVersion
import com.heytesla.app.vehicle.protocol.TeslaUwbAvailability
import com.heytesla.app.vehicle.protocol.teslaUwbAppBatteryPercent
import com.heytesla.app.vehicle.protocol.teslaUwbAppBatteryState
import com.heytesla.app.vehicle.protocol.teslaUwbAppThermalState

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Native RANGING owner data only: no GMS preference, device identifier or Tesla model/version spoof. */
internal class AndroidTeslaUwbAppDeviceInfoSource(
    context: Context,
    private val nativeReady: () -> Boolean,
) : TeslaUwbAppDeviceInfoSource {
    private val context = context.applicationContext
    private val closed = AtomicBoolean(false)
    private var subscribed = false
    private var changed: (() -> Unit)? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var powerReceiver: BroadcastReceiver? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var powerManager: PowerManager? = null
    private var modelHash: ByteArray? = null
    private var version: TeslaUwbAppDeviceInfoVersion? = null

    override fun snapshot(includeBatteryState: Boolean): TeslaUwbAppDeviceInfo {
        check(!closed.get()) { "DEVICE_INFO_SOURCE_CLOSED" }
        val hash = modelHash ?: digest("SHA-256", Build.MODEL).also { modelHash = it }
        val actualVersion = version ?: readVersion().also { version = it }
        val battery = if (includeBatteryState) readBatteryState() else null
        return TeslaUwbAppDeviceInfo(hash.copyOf(), availability(), actualVersion, battery)
    }

    private fun availability(): TeslaUwbAvailability = when {
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB) -> TeslaUwbAvailability.UNSUPPORTED
        context.checkSelfPermission(Manifest.permission.RANGING) != PackageManager.PERMISSION_GRANTED ->
            TeslaUwbAvailability.PERMISSION_DENIED
        nativeReady() -> TeslaUwbAvailability.AVAILABLE
        else -> TeslaUwbAvailability.UNKNOWN
    }

    private fun readVersion(): TeslaUwbAppDeviceInfoVersion {
        val components = BuildConfig.VERSION_NAME.substringBefore('-').split('.')
        require(components.size == 3) { "INVALID_APP_VERSION" }
        return TeslaUwbAppDeviceInfoVersion(
            osVersion = Build.VERSION.SDK_INT.toLong(),
            brandHash = sha1Suffix(Build.BRAND),
            incrementalHash = sha1Suffix(Build.VERSION.INCREMENTAL),
            appMajor = components[0].toLong(), appMinor = components[1].toLong(), appPatch = components[2].toLong(),
        )
    }

    private fun power(): PowerManager = powerManager ?: checkNotNull(
        context.getSystemService(PowerManager::class.java),
    ) { "POWER_MANAGER_UNAVAILABLE" }.also { powerManager = it }

    private fun readBatteryState(): TeslaUwbAppDeviceBatteryState {
        // Query the sticky state only for ONCHANGE; GET_MODEL never reads battery/power/thermal.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val manager = power()
        return TeslaUwbAppDeviceBatteryState(
            percent = battery?.let { teslaUwbAppBatteryPercent(
                it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            ) },
            state = teslaUwbAppBatteryState(battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1),
            // minSdk 36 already includes the public API 29 thermal listener and status query.
            thermal = teslaUwbAppThermalState(manager.currentThermalStatus),
            lowPower = manager.isPowerSaveMode,
        )
    }

    override fun subscribe(onChanged: () -> Unit) {
        check(!closed.get() && !subscribed) { "DEVICE_INFO_SUBSCRIPTION_INVALID" }
        subscribed = true
        changed = onChanged
        fun receiver() = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = notifyChanged()
        }
        try {
            // Retain every registration before the call, including a partially successful failure.
            val battery = receiver()
            batteryReceiver = battery
            context.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            val powerStateReceiver = receiver()
            powerReceiver = powerStateReceiver
            context.registerReceiver(powerStateReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            val listener = PowerManager.OnThermalStatusChangedListener { notifyChanged() }
            thermalListener = listener
            power().addThermalStatusListener(context.mainExecutor, listener)
        } catch (failure: Exception) {
            try { close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun notifyChanged() {
        if (!closed.get()) changed?.invoke()
    }

    override fun close() {
        closed.set(true) // Late platform callbacks are rejected even if unregister throws.
        changed = null
        var failure: Exception? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        batteryReceiver?.let { receiver -> attempt {
            context.unregisterReceiver(receiver)
            batteryReceiver = null
        } }
        powerReceiver?.let { receiver -> attempt {
            context.unregisterReceiver(receiver)
            powerReceiver = null
        } }
        thermalListener?.let { listener -> attempt {
            power().removeThermalStatusListener(listener)
            thermalListener = null
        } }
        modelHash?.fill(0)
        modelHash = null
        version = null
        failure?.let { throw it }
    }

    private fun digest(algorithm: String, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try { MessageDigest.getInstance(algorithm).digest(bytes) } finally { bytes.fill(0) }
    }

    /** Last eight SHA-1 hex digits, interpreted as an unsigned uint32 without creating a hex string. */
    private fun sha1Suffix(value: String): Long {
        val hash = digest("SHA-1", value)
        return try {
            var result = 0L
            for (index in hash.size - 4 until hash.size) result = (result shl 8) or (hash[index].toLong() and 255L)
            result
        } finally { hash.fill(0) }
    }
}
