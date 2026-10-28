package com.heytesla.app.vehicle.uwb

import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.protocol.TeslaFiraException
import com.heytesla.app.vehicle.protocol.TeslaFiraMessage
import com.heytesla.app.vehicle.protocol.TeslaFiraNegotiation
import com.heytesla.app.vehicle.protocol.TeslaFiraPlan
import com.heytesla.app.vehicle.protocol.TeslaFiraProtocol

/** Elapsed callback-receipt termination policy, not a guarantee of measurement freshness. */
internal class TeslaUwbMeasurementWindow(private val serviceOwned: Boolean) {
    var deadlineElapsedMs: Long = Long.MAX_VALUE
        private set
    var hasDistance: Boolean = false
        private set
    private var started = false

    fun start(nowElapsedMs: Long) {
        check(!started)
        started = true
        deadlineElapsedMs = nowElapsedMs + INITIAL_RECEIVE_TIMEOUT_MS
    }

    fun expired(nowElapsedMs: Long): Boolean = started && nowElapsedMs >= deadlineElapsedMs

    fun receiveDistance(receivedAtElapsedMs: Long): Boolean {
        if (!started || expired(receivedAtElapsedMs)) return false
        hasDistance = true
        if (serviceOwned) deadlineElapsedMs = receivedAtElapsedMs + RECEIVE_SILENCE_TIMEOUT_MS
        return true
    }

    val timeoutReason: String? get() = when {
        !hasDistance -> "NO_DISTANCE_RECEIVED"
        serviceOwned -> "DISTANCE_RECEIVE_TIMEOUT"
        else -> null
    }

    private companion object {
        // Product shutdown limits only; neither limit validates the framework's raw timestamp.
        const val INITIAL_RECEIVE_TIMEOUT_MS = 60_000L
        const val RECEIVE_SILENCE_TIMEOUT_MS = 10_000L
    }
}

/** Uses the key probe's single lease. All callbacks, including capability registration, are queued. */
internal class TeslaUwbNegotiation(
    private val platform: TeslaUwbPlatform,
    private val scheduler: TeslaUwbScheduler,
    private val now: () -> Long,
    private val wallNow: () -> Long,
    private val listener: Listener,
    private val serviceOwned: Boolean = false,
) {
    internal interface Listener {
        fun onCapabilitiesReady()
        fun onSend(body: ByteArray, frameComplete: () -> Unit)
        fun onState(transform: (TeslaUwbProbeState) -> TeslaUwbProbeState, event: String)
        fun onFinish(stage: TeslaKeyStage, reason: String?)
    }

    private class Endpoint(val plan: TeslaFiraPlan) {
        var backend: TeslaUwbRanging? = null
        var closing = false
        var closed = false
        var stopHandled = false
        val stoppedPeers = HashSet<Int>()
    }

    private var registrationAttempted = false
    private var registrationConfirmed = false
    private var registering = false
    private var closing = false
    private var finished = false
    @Volatile private var cleanupFailed = false
    private var capabilityTimeout: Runnable? = null
    private val measurementWindow = TeslaUwbMeasurementWindow(serviceOwned)
    private val measurementTimeout = Runnable {
        if (!closing && !finished && finishAfterStop == null) {
            if (measurementWindow.expired(now())) endMeasurement()
            else scheduleMeasurementTimeout()
        }
    }
    private var capabilityDeadline = 0L
    private var azimuthSupported = false
    private var elevationSupported = false
    private var negotiation: TeslaFiraNegotiation? = null
    private val endpoints = ArrayList<Endpoint>()
    private val startedPeers = HashSet<Int>()
    private var validDistances = 0L
    private var pendingStops = 0
    private val closeCallbacks = ArrayList<(Boolean) -> Unit>()
    private var finishAfterStop: Pair<TeslaKeyStage, String?>? = null

    fun prepare() {
        try {
            if (!platform.prepareCapabilities()) { fail("BACKEND_UNAVAILABLE"); return }
            capabilityDeadline = now() + 10_000L
            val expiry = Runnable { if (!closing && negotiation == null) fail("CAPABILITIES_TIMEOUT") }
            capabilityTimeout = expiry
            if (!scheduler.postDelayed(expiry, 10_000L)) { fail("CAPABILITIES_REQUEST_FAILED"); return }
            registrationAttempted = true
            registering = true
            platform.registerCapabilities { snapshot ->
                if (!scheduler.post(Runnable { receiveCapabilities(snapshot) })) {
                    // No callback thread may publish state or release the main-owned lease.
                    cleanupFailed = true
                }
            }
            registrationConfirmed = true
        } catch (_: Exception) {
            cleanupFailed = registrationAttempted
            fail("CAPABILITIES_REQUEST_FAILED")
        } finally {
            registering = false
            if (closing) finishCloseIfReady()
        }
    }

    /** May finish synchronously; the owner must recheck its sealed state before continuing. */
    fun checkReadiness() {
        if (closing || finished) return
        val elapsed = now()
        val reason = when {
            cleanupFailed -> "CAPABILITIES_CALLBACK_FAILED"
            negotiation == null && capabilityDeadline != 0L && elapsed >= capabilityDeadline -> "CAPABILITIES_TIMEOUT"
            else -> null
        }
        if (reason != null) { fail(reason); return }
        // Handler delays use uptime; the owner's watchdog also enforces elapsed time after sleep.
        if (measurementWindow.expired(elapsed)) endMeasurement()
    }

    private fun receiveCapabilities(result: Result<TeslaUwbCapabilitySnapshot>) {
        if (closing || finished) return
        try {
            val snapshot = result.getOrThrow()
            val cap = snapshot.capabilities
            val candidate = TeslaFiraNegotiation(cap)
            candidate.validateCapabilities()
            azimuthSupported = snapshot.azimuthSupported
            elevationSupported = snapshot.elevationSupported
            listener.onState({ it.copy(serviceOwned = serviceOwned,
                azimuthSupported = azimuthSupported, elevationSupported = elevationSupported) },
                "TESLA_UWB_CAPABILITIES_RECEIVED")
            if (closing || finished) { candidate.close(); return }
            if (negotiation != null) {
                if (endpoints.isEmpty()) {
                    // The request must use the latest support snapshot, not the first callback.
                    negotiation?.close()
                    negotiation = candidate
                    return
                }
                candidate.close()
                if (endpoints.any { !it.closed && (it.plan.channel !in cap.channels || it.plan.preamble !in cap.preambles ||
                        it.plan.frameworkConfigId !in cap.configIds || it.plan.updateRate !in cap.updateRates) }) {
                    fail("CAPABILITIES_CHANGED")
                }
                return
            }
            if (now() >= capabilityDeadline) { candidate.close(); fail("CAPABILITIES_TIMEOUT"); return }
            capabilityTimeout?.let(scheduler::remove)
            capabilityTimeout = null
            negotiation = candidate
            listener.onCapabilitiesReady()
        } catch (failure: TeslaFiraException) { fail(failure.reason) }
        catch (_: Exception) { fail("CAPABILITIES_INVALID") }
    }

    fun authenticated() {
        if (closing || finished) return
        listener.onState({ it.copy(stage = TeslaUwbStage.WAITING_FOR_VEHICLE,
            reason = "CAPABILITIES_UNSPECIFIED") }, "TESLA_UWB_AUTHENTICATED")
        if (closing || finished) return
        listener.onSend(TeslaFiraProtocol.capabilities()) {
            if (!closing && !finished) listener.onState({ it.copy(reason = "CAPABILITIES_UNSPECIFIED") },
                "TESLA_UWB_CAPABILITIES_UNSPECIFIED")
        }
    }

    fun receive(message: TeslaFiraMessage) {
        try {
            if (closing || finished) return
            when (message) {
                is TeslaFiraMessage.Request -> {
                    val plans = checkNotNull(negotiation).prepare(message)
                    plans.forEach { endpoints.add(Endpoint(it)) }
                    listener.onState({ it.copy(stage = TeslaUwbStage.STARTING,
                        peerCount = plans.sumOf { p -> p.peers.size }, reason = null) }, "TESLA_UWB_REQUEST_RECEIVED")
                    if (closing || finished) return
                    val response = TeslaFiraProtocol.response(plans, wallNow() / 1_000L)
                    listener.onSend(response) {
                        if (!closing && !finished) {
                            listener.onState({ it }, "TESLA_UWB_RESPONSE_SENT")
                            startBackends()
                        }
                    }
                }
                is TeslaFiraMessage.Stop -> {
                    val selected = checkNotNull(negotiation).selectStop(message.endpoints,
                        endpoints.filter { !it.closing && !it.closed }.map { it.plan.requestSessionId }.toSet())
                    listener.onState({ it }, "TESLA_UWB_VEHICLE_STOP_RECEIVED")
                    closeEndpoints(selected.map { it.requestSessionId }.toSet())
                }
            }
        } catch (failure: TeslaFiraException) { fail(failure.reason) }
        catch (_: Exception) { fail("NEGOTIATION_FAILED") }
        finally { message.close() }
    }

    private fun startBackends() {
        // Our deterministic ordering, not a claim about the APK dispatcher: complete BLE frame first.
        if (closing || finished || endpoints.none { !it.closing && !it.closed }) return
        measurementWindow.start(now())
        if (!scheduleMeasurementTimeout()) return
        for (endpoint in endpoints) {
            if (closing || finished) return
            // A vehicle stop may close and wipe a plan while the response frame is still queued.
            if (endpoint.closing || endpoint.closed) continue
            val plan = endpoint.plan
            val backend = TeslaUwbRanging(platform.createNativeRanging(), scheduler, now, object : TeslaUwbRanging.Listener {
                private fun active(): Boolean {
                    if (closing || finished || endpoint.closing || endpoint.closed) return false
                    if (measurementWindow.expired(now())) { endMeasurement(); return false }
                    return true
                }
                override fun onOpened() {
                    if (active()) listener.onState({ it.copy(backendOpened = true) }, "TESLA_UWB_BACKEND_OPENED")
                }
                override fun onStarted(peerIndex: Int) {
                    if (!active() || peerIndex in endpoint.stoppedPeers) return
                    val index = plan.peerOffset + peerIndex
                    if (!startedPeers.add(index)) return
                    listener.onState({ it.copy(startedPeerCount = startedPeers.size,
                        livePeerIndices = it.livePeerIndices + index) }, "TESLA_UWB_PEER_STARTED")
                }
                override fun onDistance(sample: TeslaUwbDistance) {
                    if (!active() || sample.peerIndex in endpoint.stoppedPeers) return
                    val mapped = sample.copy(peerIndex = plan.peerOffset + sample.peerIndex)
                    val firstDistance = !measurementWindow.hasDistance
                    if (!measurementWindow.receiveDistance(mapped.receivedAtElapsedMs)) {
                        endMeasurement()
                        return
                    }
                    if (serviceOwned && firstDistance && !scheduleMeasurementTimeout()) return
                    validDistances++
                    listener.onState({ state -> state.copy(stage = TeslaUwbStage.RANGING,
                        validDistanceCount = validDistances, lastReceivedSample = mapped,
                        livePeerIndices = if (mapped.peerIndex in state.livePeerIndices) state.livePeerIndices
                            else state.livePeerIndices + mapped.peerIndex,
                        samples = (state.samples.filter { it.peerIndex != mapped.peerIndex } + mapped)
                            .sortedBy { it.peerIndex }, reason = null) }, "TESLA_UWB_DISTANCE_RECEIVED")
                }
                override fun onInvalidResult(reason: String) {
                    if (active()) listener.onState({ it.copy(invalidResultCount = it.invalidResultCount + 1,
                        reason = "INVALID_RANGING_RESULT") }, "TESLA_UWB_INVALID_RESULT")
                }
                override fun onPeerStopped(peerIndex: Int) {
                    if (!active()) return
                    if (!endpoint.stoppedPeers.add(peerIndex)) return
                    listener.onState({ it.copy(livePeerIndices = it.livePeerIndices - (plan.peerOffset + peerIndex)) },
                        "TESLA_UWB_PEER_STOPPED")
                    if (endpoint.stoppedPeers.size == plan.peers.size) closeEndpoints(setOf(plan.requestSessionId))
                }
                override fun onFailure(reason: String) {
                    if (closing) return
                    val openReason = if (reason.startsWith("OPEN_FAILED_")) {
                        reason.removePrefix("OPEN_FAILED_").toIntOrNull()
                    } else null
                    if (openReason != null) {
                        try {
                            listener.onState({ it.copy(stage = TeslaUwbStage.FAILED, reason = "NATIVE_OPEN_FAILED",
                                openFailedReason = openReason) }, "TESLA_UWB_FAILED")
                        } catch (_: Exception) { cleanupFailed = true }
                    }
                    fail(when {
                        reason == "CLOSE_TIMEOUT" -> "NATIVE_CLOSE_TIMEOUT"
                        reason == "START_FAILED" || reason == "START_PERMISSION_DENIED" -> "NATIVE_START_FAILED"
                        reason.startsWith("OPEN_FAILED_") -> "NATIVE_OPEN_FAILED"
                        reason == "RANGING_PERMISSION_DENIED" -> "RANGING_PERMISSION_REQUIRED"
                        else -> "NATIVE_BACKEND_FAILED"
                    })
                }
                override fun onClosed(reason: Int) {
                    try {
                        listener.onState({ it.copy(closedReason = reason,
                            livePeerIndices = it.livePeerIndices.filterTo(LinkedHashSet()) { index ->
                                index !in plan.peerOffset until (plan.peerOffset + plan.peers.size)
                            }) }, "TESLA_UWB_BACKEND_CLOSED")
                    } catch (_: Exception) { cleanupFailed = true }
                    if (!closing && !finished && !endpoint.closing) {
                        if (measurementWindow.expired(now())) endMeasurement()
                        else closeEndpoints(setOf(plan.requestSessionId))
                    }
                }
            })
            endpoint.backend = backend // Own before start: synchronous failure must still close it.
            try {
                backend.start(TeslaUwbRangingParameters(plan.firaSessionId, plan.frameworkConfigId,
                    plan.localAddress, plan.peers, plan.channel, plan.preamble, plan.sts, plan.updateRate,
                    angleOfArrivalNeeded = azimuthSupported || elevationSupported))
            } catch (_: Exception) { fail("NATIVE_START_FAILED") }
        }
    }

    private fun scheduleMeasurementTimeout(): Boolean {
        scheduler.remove(measurementTimeout)
        val delay = (measurementWindow.deadlineElapsedMs - now()).coerceAtLeast(0L)
        if (scheduler.postDelayed(measurementTimeout, delay)) return true
        fail("MEASUREMENT_SCHEDULE_FAILED")
        return false
    }

    private fun endMeasurement() {
        if (closing || finished || finishAfterStop != null ||
            endpoints.none { !it.closing && !it.closed }) return
        val reason = measurementWindow.timeoutReason
        finishAfterStop = if (reason == null) TeslaKeyStage.COMPLETE to null
            else TeslaKeyStage.TIMED_OUT to reason
        closeEndpoints(endpoints.filter { !it.closed && !it.closing }.map { it.plan.requestSessionId }.toSet())
    }

    private fun closeEndpoints(ids: Set<Long>) {
        if (closing || finished || ids.isEmpty()) return
        val selected = endpoints.filter { it.plan.requestSessionId in ids && !it.closing && !it.closed }
        if (selected.isEmpty()) return
        selected.forEach { it.closing = true }
        val ending = endpoints.all { it.closing || it.closed }
        if (ending) scheduler.remove(measurementTimeout)
        var remaining = selected.size
        pendingStops++
        try {
            listener.onState({ state -> state.copy(
                stage = if (ending) TeslaUwbStage.CLEANING_UP else state.stage,
                reason = finishAfterStop?.second ?: state.reason,
                livePeerIndices = state.livePeerIndices.filterTo(LinkedHashSet()) { index ->
                    selected.none { index in it.plan.peerOffset until (it.plan.peerOffset + it.plan.peers.size) }
                }) }, "TESLA_UWB_ENDPOINT_CLOSING")
        } catch (_: Exception) { cleanupFailed = true }
        for (endpoint in selected) {
            fun complete(failed: Boolean) {
                if (endpoint.closed) return
                cleanupFailed = cleanupFailed || failed
                endpoint.closed = true
                endpoint.plan.close()
                remaining--
                if (remaining != 0) return
                pendingStops--
                if (closing) { finishCloseIfReady(); return }
                if (finished) return
                if (cleanupFailed) { fail("NATIVE_CLEANUP_FAILED"); return }
                val stoppedEndpoints = TeslaFiraProtocol.stoppedEndpoints(selected.map { it.plan.requestSessionId })
                if (stoppedEndpoints.isEmpty()) {
                    // Our conservative policy omits an unrepresentable batch instead of sending an empty z1.
                    completeStopped(selected, sent = false)
                } else {
                    listener.onSend(TeslaFiraProtocol.stopped(stoppedEndpoints)) {
                        completeStopped(selected, sent = true)
                    }
                }
            }
            val backend = endpoint.backend
            if (backend == null) complete(false)
            else try { backend.close(::complete) } catch (_: Exception) { complete(true) }
        }
    }

    private fun completeStopped(selected: List<Endpoint>, sent: Boolean) {
        if (closing || finished || selected.all { it.stopHandled }) return
        // Completion is independent of actual TX: uint32 request IDs need not be stop enum values.
        selected.forEach { it.stopHandled = true }
        listener.onState({ it }, if (sent) "TESLA_UWB_SESSION_STOPPED_SENT" else "TESLA_UWB_SESSION_STOPPED_NOT_SENT")
        if (closing || finished) return
        if (endpoints.all { it.closed && it.stopHandled } && pendingStops == 0) {
            val result = finishAfterStop ?: if (validDistances > 0) TeslaKeyStage.COMPLETE to null
                else TeslaKeyStage.FAILED to "NO_DISTANCE_RECEIVED"
            finished = true
            listener.onFinish(result.first, result.second)
        }
    }

    fun close(completed: (Boolean) -> Unit) {
        closeCallbacks.add(completed)
        if (closing) { finishCloseIfReady(); return }
        closing = true
        capabilityTimeout?.let(scheduler::remove)
        scheduler.remove(measurementTimeout)
        capabilityTimeout = null
        try { listener.onState({ it.copy(livePeerIndices = emptySet()) }, "TESLA_UWB_PEERS_ENDED") }
        catch (_: Exception) { cleanupFailed = true }
        if (endpoints.any { !it.stopHandled }) {
            // User cancel/hidden/failed transport never emits new vehicle traffic.
            try { listener.onState({ it }, "TESLA_UWB_SESSION_STOPPED_NOT_SENT") }
            catch (_: Exception) { cleanupFailed = true }
        }
        if (!registering) unregister()
        val remaining = endpoints.filter { !it.closed && !it.closing }
        remaining.forEach { it.closing = true }
        for (endpoint in remaining) {
            val backend = endpoint.backend
            if (backend == null) { endpoint.closed = true; endpoint.plan.close(); continue }
            try {
                backend.close closeBackend@ { failed ->
                    if (endpoint.closed) return@closeBackend
                    cleanupFailed = cleanupFailed || failed
                    endpoint.closed = true
                    endpoint.plan.close()
                    finishCloseIfReady()
                }
            } catch (_: Exception) {
                cleanupFailed = true
                endpoint.closed = true
                endpoint.plan.close()
            }
        }
        finishCloseIfReady()
    }

    private fun unregister() {
        if (!registrationAttempted) return
        if (!registrationConfirmed) cleanupFailed = true
        try { platform.unregisterCapabilities() }
        catch (_: Exception) { cleanupFailed = true }
        registrationAttempted = false
        registrationConfirmed = false
    }

    private fun finishCloseIfReady() {
        if (!closing || registering) return
        unregister()
        if (endpoints.any { !it.closed } || pendingStops != 0) return
        negotiation?.close()
        negotiation = null
        val callbacks = closeCallbacks.toList()
        closeCallbacks.clear()
        callbacks.forEach { it(cleanupFailed) }
    }

    private fun fail(reason: String) {
        if (!closing && !finished) {
            finished = true
            try { listener.onState({ it.copy(livePeerIndices = emptySet()) }, "TESLA_UWB_PEERS_ENDED") }
            catch (_: Exception) { cleanupFailed = true }
            listener.onFinish(if (reason == "CAPABILITIES_TIMEOUT") TeslaKeyStage.TIMED_OUT else TeslaKeyStage.FAILED, reason)
        }
    }
}
