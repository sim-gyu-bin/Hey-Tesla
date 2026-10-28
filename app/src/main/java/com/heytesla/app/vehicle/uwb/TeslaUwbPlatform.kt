package com.heytesla.app.vehicle.uwb

import com.heytesla.app.vehicle.protocol.TeslaFiraCapabilities

import java.util.UUID

/** Serial owner queue; post must never execute a task inline. */
internal interface TeslaUwbScheduler {
    fun post(task: Runnable): Boolean
    fun postDelayed(task: Runnable, delayMs: Long): Boolean
    fun remove(task: Runnable)
    fun checkThread()
}

internal data class TeslaUwbCapabilitySnapshot(
    val capabilities: TeslaFiraCapabilities,
    val azimuthSupported: Boolean,
    val elevationSupported: Boolean,
)

/** Capability registration and native factories only; the common code owns both state machines. */
internal interface TeslaUwbPlatform {
    fun prepareCapabilities(): Boolean
    fun registerCapabilities(callback: (Result<TeslaUwbCapabilitySnapshot>) -> Unit)
    fun unregisterCapabilities()
    fun createNativeRanging(): TeslaUwbNativeRanging
}

/** Unvalidated SDK values. Callbacks may be synchronous; TeslaUwbRanging queues every delivery. */
internal interface TeslaUwbNativeRanging {
    interface Prepared

    interface Session {
        fun start(prepared: Prepared)
        /** Returning acknowledges the request, not native teardown. */
        fun close()
    }

    interface Callback {
        fun onOpened()
        fun onOpenFailed(reason: Int)
        fun onStarted(peerId: UUID, technology: Int)
        fun onResults(
            peerId: UUID,
            technology: Int,
            meters: Double?,
            confidence: Int?,
            frameworkTimestampMillis: Long,
            azimuthRaw: Double?,
            azimuthConfidence: Int?,
            elevationRaw: Double?,
            elevationConfidence: Int?,
        )
        fun onStopped(peerId: UUID, technology: Int)
        fun onClosed(reason: Int)
    }

    fun hasRangingPermission(): Boolean
    /** Input bytes are owned and erased by TeslaUwbRanging, not by this port. */
    fun prepare(parameters: TeslaUwbRangingParameters, peerIds: List<UUID>): Prepared
    fun managerAvailable(): Boolean
    fun create(callback: Callback): Session?

    companion object {
        // Android RangingManager.UWB; retain the raw namespace for host-side invalid-input checks.
        const val UWB = 0
    }
}
