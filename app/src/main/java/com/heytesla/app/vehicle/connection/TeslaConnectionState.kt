package com.heytesla.app.vehicle.connection

import com.heytesla.app.vehicle.frunk.TeslaFrunkPolicy
import com.heytesla.app.vehicle.protocol.TeslaDriveState
import com.heytesla.app.vehicle.protocol.TeslaGear

enum class TeslaConnectionStage {
    IDLE, PREPARING, CONNECTING, CHECKING_VEHICLE, WAKING_VEHICLE, AUTHENTICATING,
    MONITORING, EXECUTING, STOPPED, BLOCKED, FAILED,
}

data class TeslaConnectionState(
    val stage: TeslaConnectionStage = TeslaConnectionStage.IDLE,
    val gear: TeslaGear? = null,
    val parkFresh: Boolean = false,
    val reason: String? = null,
    val gatt: TeslaGattObservation? = null,
    val frunkCommandSent: Boolean = false,
    val frunkCommandAcknowledged: Boolean = false,
    val frunkOpenObserved: Boolean = false,
)

/** Only authenticated DRIVE results enter this RAM policy. Traffic and timer wakes never refresh P. */
internal class TeslaParkMonitor(private val now: () -> Long, private val wallNow: () -> Long) {
    private var lastElapsed = now()
    private var drive: TeslaDriveState? = null
    val observed: TeslaDriveState? get() = drive

    fun accept(value: TeslaDriveState?): String? {
        freshnessReason(value)?.let { return it }
        drive = value
        return null
    }

    fun readinessReason(): String? = freshnessReason(drive)

    private fun freshnessReason(value: TeslaDriveState?): String? {
        val current = now()
        if (current < lastElapsed) return "CONNECTION_TIME_REVERSED"
        lastElapsed = current
        return serviceParkReason(value, current, wallNow())
    }

    fun clear() { drive = null }

    companion object {
        const val POLL_MS = 2_000L
    }
}

/** Runtime and backend share the same gate; no raw source time leaves RAM. */
internal fun serviceParkReason(drive: TeslaDriveState?, nowElapsedMs: Long, wallTimeMs: Long): String? {
    if (drive?.gear != TeslaGear.P) return "CONNECTION_EXPLICIT_P_REQUIRED"
    val receipt = drive.receivedAtElapsedRealtime
    if (receipt < 0 || receipt > nowElapsedMs ||
        nowElapsedMs - receipt > TeslaFrunkPolicy.PARK_RECEIPT_MS) {
        return "CONNECTION_P_RECEIPT_STALE"
    }
    val source = drive.sourceTimestampEpochMillis ?: return "CONNECTION_P_SOURCE_TIME_REQUIRED"
    if (source <= 0 || wallTimeMs <= 0 ||
        (if (source > wallTimeMs) source - wallTimeMs > TeslaFrunkPolicy.PARK_SOURCE_FUTURE_SKEW_MS
        else wallTimeMs - source > TeslaFrunkPolicy.PARK_SOURCE_MS)) {
        return "CONNECTION_P_SOURCE_TIME_STALE"
    }
    return null
}
