package com.heytesla.app.vehicle.connection

import com.heytesla.app.vehicle.protocol.TeslaReadOnlyStatus

internal enum class TeslaConnectionPreparationPhase { BODY, WAKE, DRIVE }

/** Authenticated BODY ingress authorizes only one bounded wake, never P or physical wake completion. */
internal class TeslaConnectionWakePolicy(
    private val operationDeadline: Long,
    private val now: () -> Long,
) {
    private var bodyReceipt: Long? = null
    private var lastElapsed: Long? = null
    private var bodyVerified = false
    private var sleepStatus: Int? = null
    var wakeDeadline: Long? = null
        private set
    val wakeRequired: Boolean get() = bodyVerified && sleepStatus == 2
    val awake: Boolean get() = bodyVerified && sleepStatus == 1

    fun acceptBody(status: TeslaReadOnlyStatus): String? {
        if (bodyVerified || status.driveState != null) return "CONNECTION_BODY_RESPONSE_UNEXPECTED"
        val receipt = status.bodyReceivedAtElapsedRealtime
        freshnessReason(receipt, now())?.let { return it }
        val verifiedReceipt = checkNotNull(receipt)
        bodyReceipt = verifiedReceipt
        sleepStatus = status.bodyVehicleSleepStatus
        val receiptDeadline = if (verifiedReceipt > Long.MAX_VALUE - BODY_FRESH_MS) Long.MAX_VALUE
            else verifiedReceipt + BODY_FRESH_MS
        wakeDeadline = minOf(operationDeadline, receiptDeadline)
        bodyVerified = true
        return null
    }

    /** Called before wake encryption and every actual SDK write, including SDK BUSY reissues. */
    fun authorizationReason(): String? {
        if (!wakeRequired) return "CONNECTION_ASLEEP_REQUIRED"
        val current = now()
        freshnessReason(bodyReceipt, current)?.let { return it }
        if (current >= checkNotNull(wakeDeadline)) return "CONNECTION_WAKE_DEADLINE_EXPIRED"
        return null
    }

    private fun freshnessReason(receipt: Long?, current: Long): String? {
        val previous = lastElapsed
        lastElapsed = current
        if (previous != null && current < previous) return "CONNECTION_TIME_REVERSED"
        if (receipt == null) return "CONNECTION_BODY_RECEIPT_REQUIRED"
        if (receipt < 0 || receipt > current || current - receipt > BODY_FRESH_MS) {
            return "CONNECTION_BODY_RECEIPT_STALE"
        }
        return null
    }

    companion object {
        const val BODY_FRESH_MS = 5_000L
    }
}
