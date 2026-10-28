package com.heytesla.app.vehicle.uwb

import java.util.UUID
import java.util.concurrent.RejectedExecutionException

internal data class TeslaUwbRangingParameters(
    val firaSessionId: Int,
    val frameworkConfigId: Int,
    val localAddress: ByteArray,
    val peerAddresses: List<ByteArray>,
    val channel: Int,
    val preambleIndex: Int,
    val sessionKeyInfo: ByteArray,
    val updateRate: Int,
    val angleOfArrivalNeeded: Boolean,
) {
    override fun toString(): String = "TeslaUwbRangingParameters"
}

internal data class TeslaUwbDistance(
    val peerIndex: Int,
    val meters: Double,
    val confidence: Int,
    val frameworkTimestampMillis: Long,
    /** Elapsed time captured at callback ingress, before owner-queue dispatch. */
    val receivedAtElapsedMs: Long,
    /** Public degrees and Android 16 UWB radians conflict; retain raw values without conversion. */
    val azimuthRaw: Double? = null,
    val azimuthConfidence: Int? = null,
    val elevationRaw: Double? = null,
    val elevationConfidence: Int? = null,
)

/** Pure validation; framework timestamps deliberately retain their unconfirmed clock base. */
internal fun teslaUwbInvalidResultReason(
    peerIndex: Int?,
    technology: Int,
    meters: Double?,
    confidence: Int?,
): String? = when {
    peerIndex == null || peerIndex < 0 -> "UNKNOWN_PEER"
    technology != TeslaUwbNativeRanging.UWB -> "UNEXPECTED_TECHNOLOGY"
    meters == null -> "MISSING_DISTANCE"
    !meters.isFinite() || meters < 0.0 -> "INVALID_DISTANCE"
    teslaUwbMeasurementConfidence(confidence) == null -> "INVALID_CONFIDENCE"
    else -> null
}

/** No unit-dependent bounds or inferred conversion; missing and non-finite angles stay absent. */
internal fun teslaUwbAngleRaw(raw: Double?): Double? = raw?.takeIf { it.isFinite() }

/** Validate each measurement's confidence independently, never borrowing another axis's value. */
internal fun teslaUwbMeasurementConfidence(confidence: Int?): Int? = when (confidence) {
    0, 1, 2 -> confidence
    else -> null
}

/**
 * Single-use, main-thread RAW initiator. The caller owns capability checks and its BLE/UWB lease.
 * Callback execution is always queued, including callbacks emitted during create/start/close.
 * A close request is not evidence of native teardown: only onClosed confirms that boundary.
 */
internal class TeslaUwbRanging(
    private val native: TeslaUwbNativeRanging,
    private val scheduler: TeslaUwbScheduler,
    private val now: () -> Long,
    val listener: Listener,
) {
    internal interface Listener {
        fun onOpened()
        fun onStarted(peerIndex: Int)
        fun onDistance(sample: TeslaUwbDistance)
        fun onInvalidResult(reason: String)
        fun onPeerStopped(peerIndex: Int)
        fun onFailure(reason: String)
        fun onClosed(reason: Int)
    }

    private enum class State { NEW, STARTING, OPEN, CLOSING, CLOSED }

    private var state = State.NEW
    private var session: TeslaUwbNativeRanging.Session? = null
    private var nativeStartAttempted = false
    private var cleanupFailed = false
    private var rejectingEvents = false
    private var closeTimeout: Runnable? = null
    private var closeDeadlineElapsedMs = 0L
    private val closeCompletions = java.util.ArrayDeque<(Boolean) -> Unit>()
    private val ownedBytes = ArrayList<ByteArray>()
    private val peerIndexes = HashMap<UUID, Int>()

    private fun enqueue(action: () -> Unit) {
        if (!scheduler.post(Runnable(action))) throw RejectedExecutionException("Ranging callback queue unavailable")
    }

    private val callback = object : TeslaUwbNativeRanging.Callback {
        override fun onOpened() = enqueue {
            if (!acceptsEvents() || state != State.STARTING) return@enqueue
            state = State.OPEN
            deliver { listener.onOpened() }
        }

        override fun onOpenFailed(reason: Int) = enqueue {
            if (!acceptsEvents()) return@enqueue
            fail("OPEN_FAILED_$reason")
        }

        override fun onStarted(peerId: UUID, technology: Int) = enqueue {
            if (!acceptsEvents() || !checkPermission()) return@enqueue
            val index = knownPeer(peerId, technology) ?: return@enqueue
            deliver { listener.onStarted(index) }
        }

        override fun onResults(
            peerId: UUID,
            technology: Int,
            meters: Double?,
            confidence: Int?,
            frameworkTimestampMillis: Long,
            azimuthRaw: Double?,
            azimuthConfidence: Int?,
            elevationRaw: Double?,
            elevationConfidence: Int?,
        ) {
            val receivedAt = now()
            enqueue {
                if (!acceptsEvents() || !checkPermission()) return@enqueue
                val index = peerIndexes[peerId]
                val invalid = teslaUwbInvalidResultReason(index, technology, meters, confidence)
                if (invalid != null) {
                    deliver { listener.onInvalidResult(invalid) }
                    return@enqueue
                }
                val sample = TeslaUwbDistance(
                    peerIndex = checkNotNull(index),
                    meters = checkNotNull(meters),
                    confidence = checkNotNull(confidence),
                    frameworkTimestampMillis = frameworkTimestampMillis,
                    receivedAtElapsedMs = receivedAt,
                    azimuthRaw = teslaUwbAngleRaw(azimuthRaw),
                    azimuthConfidence = teslaUwbMeasurementConfidence(azimuthConfidence),
                    elevationRaw = teslaUwbAngleRaw(elevationRaw),
                    elevationConfidence = teslaUwbMeasurementConfidence(elevationConfidence),
                )
                deliver { listener.onDistance(sample) }
            }
        }

        override fun onStopped(peerId: UUID, technology: Int) = enqueue {
            if (!acceptsEvents() || !checkPermission()) return@enqueue
            val index = knownPeer(peerId, technology) ?: return@enqueue
            // A stopped peer does not establish that the whole native session is closed.
            deliver { listener.onPeerStopped(index) }
        }

        override fun onClosed(reason: Int) = enqueue {
            if (state == State.CLOSED || !nativeStartAttempted) return@enqueue
            // Even a delayed callback cannot clear a failure already latched by an exception.
            finishClose(reason)
        }
    }

    fun start(parameters: TeslaUwbRangingParameters) {
        scheduler.checkThread()
        check(state == State.NEW) { "Ranging backend is single-use" }
        state = State.STARTING
        if (!checkPermission()) return
        val invalid = invalidParameters(parameters)
        if (invalid != null) {
            fail(invalid)
            return
        }
        val prepared = try {
            prepareNative(parameters)
        } catch (_: Exception) {
            fail("PARAMETER_BUILD_FAILED")
            return
        }
        val managerAvailable = try {
            native.managerAvailable()
        } catch (_: Exception) {
            fail("MANAGER_LOOKUP_FAILED")
            return
        }
        if (!managerAvailable) {
            fail("MANAGER_UNAVAILABLE")
            return
        }
        val created = try {
            native.create(callback)
        } catch (_: Exception) {
            fail("SESSION_CREATE_FAILED")
            return
        }
        if (created == null) {
            fail("SESSION_CREATE_NULL")
            return
        }
        session = created
        if (!checkPermission()) return
        // start may reach the service before throwing; that ownership must not be guessed away.
        nativeStartAttempted = true
        try {
            created.start(prepared)
        } catch (_: SecurityException) {
            cleanupFailed = true
            fail("START_PERMISSION_DENIED")
        } catch (_: Exception) {
            cleanupFailed = true
            fail("START_FAILED")
        }
    }

    /** Each call receives exactly one completion, including reentrant and already-closed calls. */
    fun close(completed: (cleanupFailed: Boolean) -> Unit) {
        scheduler.checkThread()
        if (state == State.CLOSED) {
            complete(completed)
            return
        }
        closeCompletions.add(completed)
        requestClose()
    }

    private fun invalidParameters(parameters: TeslaUwbRangingParameters): String? = when {
        parameters.firaSessionId == 0 -> "INVALID_SESSION_ID"
        parameters.frameworkConfigId !in 1..5 -> "INVALID_FRAMEWORK_CONFIG"
        parameters.localAddress.size != 2 -> "INVALID_LOCAL_ADDRESS"
        parameters.peerAddresses.isEmpty() || parameters.peerAddresses.any { it.size != 2 } -> "INVALID_PEER_ADDRESSES"
        parameters.channel <= 0 || parameters.preambleIndex <= 0 -> "INVALID_COMPLEX_CHANNEL"
        parameters.sessionKeyInfo.size != (if (parameters.frameworkConfigId <= 2) 8 else 16) -> "INVALID_SESSION_KEY"
        parameters.updateRate !in 1..3 -> "INVALID_UPDATE_RATE"
        else -> null
    }

    private fun own(bytes: ByteArray): ByteArray = bytes.copyOf().also { ownedBytes.add(it) }

    private fun prepareNative(parameters: TeslaUwbRangingParameters): TeslaUwbNativeRanging.Prepared {
        val peerIds = ArrayList<UUID>(parameters.peerAddresses.size)
        val ownedParameters = parameters.copy(
            localAddress = own(parameters.localAddress),
            sessionKeyInfo = own(parameters.sessionKeyInfo),
            peerAddresses = parameters.peerAddresses.mapIndexed { index, address ->
                val id = UUID.randomUUID()
                peerIndexes[id] = index
                peerIds.add(id)
                own(address)
            },
        )
        // The native preference is only held through the synchronous start call.
        return native.prepare(ownedParameters, peerIds)
    }

    private fun knownPeer(peerId: UUID, technology: Int): Int? {
        val index = peerIndexes[peerId]
        val invalid = when {
            index == null -> "UNKNOWN_PEER"
            technology != TeslaUwbNativeRanging.UWB -> "UNEXPECTED_TECHNOLOGY"
            else -> null
        }
        if (invalid != null) deliver { listener.onInvalidResult(invalid) }
        return if (invalid == null) index else null
    }

    private fun acceptsEvents(): Boolean = !rejectingEvents && (state == State.STARTING || state == State.OPEN)

    private fun checkPermission(): Boolean {
        val granted = native.hasRangingPermission()
        if (!granted) fail("RANGING_PERMISSION_DENIED")
        return granted
    }

    private inline fun deliver(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            if (state != State.CLOSED) fail("LISTENER_FAILED")
        }
    }

    private fun fail(reason: String) {
        if (state == State.CLOSED || rejectingEvents) return
        rejectingEvents = true
        try {
            listener.onFailure(reason)
        } catch (_: Exception) {
            // A consumer failure must not skip native teardown or recurse through onFailure.
        } finally {
            requestClose()
        }
    }

    private fun requestClose() {
        if (state == State.CLOSED || state == State.CLOSING) return
        state = State.CLOSING
        rejectingEvents = true
        if (!nativeStartAttempted) {
            finishClose()
            return
        }
        closeDeadlineElapsedMs = now() + CLOSE_TIMEOUT_MS
        val timeout = Runnable {
            if (state != State.CLOSING) return@Runnable
            cleanupFailed = true
            try {
                listener.onFailure("CLOSE_TIMEOUT")
            } catch (_: Exception) {
                // The finally block still completes cleanup if the consumer throws.
            } finally {
                finishClose()
            }
        }
        closeTimeout = timeout
        val timeoutQueued = scheduler.postDelayed(timeout, CLOSE_TIMEOUT_MS)
        if (!timeoutQueued) cleanupFailed = true
        try {
            // close delegates to stop in SDK36. Its return is only a request acknowledgement.
            checkNotNull(session).close()
        } catch (_: Exception) {
            cleanupFailed = true
        }
        if (!timeoutQueued) finishClose()
    }

    private fun finishClose(reason: Int? = null) {
        if (state == State.CLOSED) return
        if (state == State.CLOSING && nativeStartAttempted &&
            now() >= closeDeadlineElapsedMs) cleanupFailed = true
        state = State.CLOSED
        rejectingEvents = true
        closeTimeout?.let(scheduler::remove)
        closeTimeout = null
        session = null
        ownedBytes.forEach { it.fill(0) }
        ownedBytes.clear()
        peerIndexes.clear()
        if (reason != null) deliver { listener.onClosed(reason) }
        // Remove each callback before invoking it, so reentrant close cannot run it twice.
        while (closeCompletions.isNotEmpty()) {
            val completion = closeCompletions.removeFirst()
            complete(completion)
        }
    }

    private fun complete(completion: (Boolean) -> Unit) {
        try {
            completion(cleanupFailed)
        } catch (_: Exception) {
            // One consumer must not prevent other registered close completions.
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 5_000L
    }
}
