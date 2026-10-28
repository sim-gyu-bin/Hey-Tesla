package com.heytesla.app.vehicle.uwb

import com.heytesla.app.vehicle.protocol.TeslaFiraCapabilities
import com.heytesla.app.vehicle.protocol.TeslaFiraException

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.ranging.RangingCapabilities
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.SessionConfig
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.raw.RawRangingDevice
import android.ranging.uwb.UwbAddress
import android.ranging.uwb.UwbComplexChannel
import android.ranging.uwb.UwbRangingParams
import java.util.UUID
import java.util.concurrent.Executor

internal class AndroidTeslaUwbScheduler(private val handler: Handler) : TeslaUwbScheduler {
    override fun post(task: Runnable): Boolean = handler.post(task)
    override fun postDelayed(task: Runnable, delayMs: Long): Boolean = handler.postDelayed(task, delayMs)
    override fun remove(task: Runnable) = handler.removeCallbacks(task)
    override fun checkThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Ranging backend requires main thread" }
    }
}

internal class AndroidTeslaUwbPlatform(context: Context) : TeslaUwbPlatform {
    private val context = context.applicationContext
    private var manager: RangingManager? = null
    private var callback: RangingManager.RangingCapabilitiesCallback? = null

    override fun prepareCapabilities(): Boolean {
        manager = context.getSystemService(RangingManager::class.java)
        return manager != null
    }

    override fun registerCapabilities(callback: (Result<TeslaUwbCapabilitySnapshot>) -> Unit) {
        val nativeCallback = object : RangingManager.RangingCapabilitiesCallback {
            override fun onRangingCapabilities(capabilities: RangingCapabilities) {
                val snapshot = try {
                    val uwb = capabilities.uwbCapabilities ?: throw TeslaFiraException("BACKEND_UNAVAILABLE")
                    Result.success(TeslaUwbCapabilitySnapshot(
                        capabilities = TeslaFiraCapabilities(
                            enabled = capabilities.technologyAvailability[RangingManager.UWB] == 3,
                            distance = uwb.isDistanceMeasurementSupported,
                            channels = uwb.supportedChannels,
                            preambles = uwb.supportedPreambleIndexes,
                            configIds = uwb.supportedConfigIds,
                            slotDurations = uwb.supportedSlotDurations,
                            updateRates = uwb.supportedRangingUpdateRates,
                        ),
                        azimuthSupported = uwb.isAzimuthalAngleSupported,
                        elevationSupported = uwb.isElevationAngleSupported,
                    ))
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
                callback(snapshot)
            }
        }
        this.callback = nativeCallback // Retain before registration, including a partial failure.
        checkNotNull(manager).registerCapabilitiesCallback(context.mainExecutor, nativeCallback)
    }

    override fun unregisterCapabilities() {
        try {
            checkNotNull(manager).unregisterCapabilitiesCallback(checkNotNull(callback))
        } finally {
            callback = null
            manager = null
        }
    }

    override fun createNativeRanging(): TeslaUwbNativeRanging = AndroidTeslaUwbNativeRanging(context)
}

/** Android builders and raw callback extraction only; no lifecycle or result-validation policy. */
private class AndroidTeslaUwbNativeRanging(private val context: Context) : TeslaUwbNativeRanging {
    private class Preference(val value: RangingPreference) : TeslaUwbNativeRanging.Prepared
    private var manager: RangingManager? = null

    override fun hasRangingPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RANGING) == PackageManager.PERMISSION_GRANTED

    override fun prepare(
        parameters: TeslaUwbRangingParameters,
        peerIds: List<UUID>,
    ): TeslaUwbNativeRanging.Prepared {
        val local = UwbAddress.fromBytes(parameters.localAddress)
        val channel = UwbComplexChannel.Builder()
            .setChannel(parameters.channel)
            .setPreambleIndex(parameters.preambleIndex)
            .build()
        val raw = RawInitiatorRangingConfig.Builder()
        parameters.peerAddresses.forEachIndexed { index, address ->
            val uwb = UwbRangingParams.Builder(
                parameters.firaSessionId,
                parameters.frameworkConfigId,
                local,
                UwbAddress.fromBytes(address),
            )
                .setComplexChannel(channel)
                .setSessionKeyInfo(parameters.sessionKeyInfo)
                .setRangingUpdateRate(parameters.updateRate)
                .setSlotDuration(UwbRangingParams.DURATION_2_MS)
            if (parameters.frameworkConfigId == 5) {
                // Controller parity with the authenticated Tesla path; no sub-session key.
                uwb.setSubSessionId(0)
            }
            raw.addRawRangingDevice(
                RawRangingDevice.Builder()
                    .setRangingDevice(RangingDevice.Builder().setUuid(peerIds[index]).build())
                    .setUwbRangingParams(uwb.build())
                    .build(),
            )
        }
        // SDK setters/getters copy STS internally. Those SDK-owned copies cannot be erased here.
        return Preference(RangingPreference.Builder(RangingPreference.DEVICE_ROLE_INITIATOR, raw.build())
            .setSessionConfig(SessionConfig.Builder()
                .setAngleOfArrivalNeeded(parameters.angleOfArrivalNeeded)
                .build())
            .build())
    }

    override fun managerAvailable(): Boolean {
        manager = context.getSystemService(RangingManager::class.java)
        return manager != null
    }

    override fun create(callback: TeslaUwbNativeRanging.Callback): TeslaUwbNativeRanging.Session? {
        // The common callback queues before observing state, even if the SDK invokes this inline.
        val nativeCallback = object : RangingSession.Callback {
            override fun onOpened() = callback.onOpened()
            override fun onOpenFailed(reason: Int) = callback.onOpenFailed(reason)
            override fun onStarted(peer: RangingDevice, technology: Int) = callback.onStarted(peer.uuid, technology)
            override fun onResults(peer: RangingDevice, data: RangingData) {
                val distance = data.distance
                val azimuth = data.azimuth
                val elevation = data.elevation
                callback.onResults(peer.uuid, data.rangingTechnology, distance?.measurement, distance?.confidence,
                    data.timestampMillis, azimuth?.measurement, azimuth?.confidence,
                    elevation?.measurement, elevation?.confidence)
            }
            override fun onStopped(peer: RangingDevice, technology: Int) = callback.onStopped(peer.uuid, technology)
            override fun onClosed(reason: Int) = callback.onClosed(reason)
        }
        val session = checkNotNull(manager).createRangingSession(Executor { it.run() }, nativeCallback) ?: return null
        return object : TeslaUwbNativeRanging.Session {
            override fun start(prepared: TeslaUwbNativeRanging.Prepared) {
                session.start((prepared as Preference).value)
            }
            override fun close() = session.close()
        }
    }
}
