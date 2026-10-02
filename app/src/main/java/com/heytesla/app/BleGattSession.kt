package com.heytesla.app

internal enum class BleProbeStatus {
    IDLE, BLOCKED, CONNECTING, DISCOVERING, SUBSCRIBING, OBSERVING, CLEANING_UP,
    COMPLETE, CANCELED, TIMED_OUT, FAILED, CLEANUP_FAILED,
}

internal data class BleProbeState(
    val status: BleProbeStatus = BleProbeStatus.IDLE,
    val reason: String? = null,
    val connected: Boolean = false,
    val serviceFound: Boolean = false,
    val txFound: Boolean = false,
    val rxFound: Boolean = false,
    val subscriptionConfirmed: Boolean = false,
    val remoteUnsubscribeConfirmed: Boolean = false,
    val disconnectConfirmed: Boolean = false,
    val localClosed: Boolean = false,
    val notificationCount: Int = 0,
    val elapsedMs: Long = 0,
) {
    val active: Boolean get() = when (status) {
        BleProbeStatus.CONNECTING, BleProbeStatus.DISCOVERING, BleProbeStatus.SUBSCRIBING,
        BleProbeStatus.OBSERVING, BleProbeStatus.CLEANING_UP -> true
        else -> false
    }
}

internal enum class BleSubscriptionMode { INDICATE, NOTIFY }

internal data class BleGattProfile(
    val serviceFound: Boolean,
    val txFound: Boolean,
    val rxFound: Boolean,
    val cccdFound: Boolean,
    val mode: BleSubscriptionMode?,
)

/** Android handle 하나에 대응한다. characteristic write와 payload 전달 API는 없다. */
internal interface BleGattPort {
    fun connect(): Boolean
    fun discoverServices(): Boolean
    fun profile(): BleGattProfile
    fun setNotifications(enabled: Boolean): Boolean
    fun writeSubscription(enabled: Boolean): Boolean
    fun disconnect()
    fun close()
}

internal interface BleGattListener {
    fun connection(token: Long, source: BleGattPort, success: Boolean, connected: Boolean)
    fun services(token: Long, source: BleGattPort, success: Boolean)
    fun descriptor(token: Long, source: BleGattPort, enabled: Boolean, success: Boolean)
    fun notification(token: Long, source: BleGattPort)
}

/**
 * Main 직렬 경계의 작은 비동기 세션. 시계와 handle만 대체해 실제 취소·정리 전이를 검사한다.
 * 정상 continuation은 취소 즉시 봉인하지만, 진행 중 CCCD callback은 정리 전용으로 계속 받는다.
 */
internal class BleGattSession(
    val token: Long,
    private val sessionDeadline: Long,
    private val port: BleGattPort,
    private val now: () -> Long,
    private val publish: (BleProbeState) -> Unit,
    private val release: () -> Unit,
) : BleGattListener {
    var state = BleProbeState()
        private set
    private val started = now()
    private var stageDeadline = 0L
    private var cleanupDeadline = 0L
    private var unsubscribeDeadline = 0L
    private var outcome = BleProbeStatus.FAILED
    private var outcomeReason = "INTERNAL_FAILURE"
    private var allocated = false
    private var connectedNow = false
    private var notificationsEnabled = false
    private var enableRequested = false
    private var pendingWrite: Boolean? = null
    private var disableAttempted = false
    private var disableFinished = false
    private var disconnectRequested = false
    private var finished = false

    fun start() {
        if (state.status != BleProbeStatus.IDLE) return
        enter(BleProbeStatus.CONNECTING, CONNECT_MS)
        if (now() >= stageDeadline) {
            cleanup(BleProbeStatus.TIMED_OUT, "SESSION_EXPIRED")
            return
        }
        allocated = try { port.connect() } catch (_: Exception) { false }
        if (!allocated) cleanup(BleProbeStatus.FAILED, "CONNECT_REQUEST_FAILED")
    }

    fun cancel() {
        if (!finished && state.status != BleProbeStatus.CLEANING_UP && state.status != BleProbeStatus.IDLE) {
            cleanup(BleProbeStatus.CANCELED, "USER_CANCELED")
        }
    }

    /** 외부 stop도 cancel만 전달한다. 이 메서드는 deadline으로 lease를 먼저 풀지 않는다. */
    fun tick(blockedReason: String? = null) {
        if (finished || state.status == BleProbeStatus.IDLE) return
        if (state.status == BleProbeStatus.CLEANING_UP) {
            advanceCleanup()
            return
        }
        when {
            blockedReason != null -> cleanup(BleProbeStatus.CANCELED, blockedReason)
            now() >= stageDeadline -> {
                if (state.status == BleProbeStatus.OBSERVING && now() < sessionDeadline - CLEANUP_MS) {
                    cleanup(BleProbeStatus.COMPLETE, "SUBSCRIPTION_OBSERVED")
                } else {
                    cleanup(BleProbeStatus.TIMED_OUT, "${state.status.name}_TIMEOUT")
                }
            }
        }
    }

    override fun connection(token: Long, source: BleGattPort, success: Boolean, connected: Boolean) {
        if (!accepts(token, source)) return
        tick()
        if (finished) return
        if (!connected) {
            connectedNow = false
            emit { state.copy(disconnectConfirmed = true, elapsedMs = it) }
            if (state.status == BleProbeStatus.CLEANING_UP) closeLocal()
            else cleanup(BleProbeStatus.FAILED, if (success) "CONNECTION_LOST" else "CONNECTION_FAILED")
            return
        }
        if (!success) {
            if (state.status != BleProbeStatus.CLEANING_UP) cleanup(BleProbeStatus.FAILED, "CONNECTION_FAILED")
            return
        }
        val wasConnected = connectedNow
        connectedNow = true
        emit { state.copy(connected = true, elapsedMs = it) }
        if (state.status == BleProbeStatus.CLEANING_UP) {
            // connect 취소와 실제 연결 완료가 경합하면 새 연결에도 disconnect를 적용한다.
            if (!wasConnected) disconnectRequested = false
            advanceCleanup()
            return
        }
        if (state.status != BleProbeStatus.CONNECTING) return
        enter(BleProbeStatus.DISCOVERING, DISCOVER_MS)
        val accepted = try { port.discoverServices() } catch (_: Exception) { false }
        if (!accepted) cleanup(BleProbeStatus.FAILED, "DISCOVERY_REQUEST_FAILED")
    }

    override fun services(token: Long, source: BleGattPort, success: Boolean) {
        if (!accepts(token, source)) return
        tick()
        if (finished || state.status != BleProbeStatus.DISCOVERING) return
        if (!success) {
            cleanup(BleProbeStatus.FAILED, "DISCOVERY_FAILED")
            return
        }
        val profile = try { port.profile() } catch (_: Exception) {
            cleanup(BleProbeStatus.FAILED, "PROFILE_READ_FAILED")
            return
        }
        emit { state.copy(serviceFound = profile.serviceFound, txFound = profile.txFound, rxFound = profile.rxFound, elapsedMs = it) }
        val missing = when {
            !profile.serviceFound -> "TESLA_SERVICE_MISSING"
            !profile.txFound -> "TESLA_TX_MISSING"
            !profile.rxFound -> "TESLA_RX_MISSING"
            profile.mode == null -> "RX_SUBSCRIPTION_UNSUPPORTED"
            !profile.cccdFound -> "RX_CCCD_MISSING"
            else -> null
        }
        if (missing != null) {
            cleanup(BleProbeStatus.FAILED, missing)
            return
        }
        enter(BleProbeStatus.SUBSCRIBING, SUBSCRIBE_MS)
        notificationsEnabled = try { port.setNotifications(true) } catch (_: Exception) { false }
        if (!notificationsEnabled) {
            cleanup(BleProbeStatus.FAILED, "LOCAL_SUBSCRIPTION_FAILED")
            return
        }
        pendingWrite = true
        enableRequested = try { port.writeSubscription(true) } catch (_: Exception) { false }
        if (!enableRequested) {
            pendingWrite = null
            cleanup(BleProbeStatus.FAILED, "SUBSCRIPTION_REQUEST_FAILED")
        }
    }

    override fun descriptor(token: Long, source: BleGattPort, enabled: Boolean, success: Boolean) {
        if (!accepts(token, source)) return
        tick()
        if (finished || pendingWrite != enabled) return
        pendingWrite = null
        if (enabled) {
            if (success) emit { state.copy(subscriptionConfirmed = true, elapsedMs = it) }
            if (state.status == BleProbeStatus.CLEANING_UP) {
                advanceCleanup()
            } else if (state.status == BleProbeStatus.SUBSCRIBING) {
                if (success) enter(BleProbeStatus.OBSERVING, OBSERVE_MS)
                else cleanup(BleProbeStatus.FAILED, "SUBSCRIPTION_FAILED")
            }
        } else if (state.status == BleProbeStatus.CLEANING_UP) {
            disableFinished = true
            if (success) emit { state.copy(remoteUnsubscribeConfirmed = true, elapsedMs = it) }
            advanceCleanup()
        }
    }

    override fun notification(token: Long, source: BleGattPort) {
        if (!accepts(token, source)) return
        tick()
        if (finished || state.status != BleProbeStatus.OBSERVING || !state.subscriptionConfirmed) return
        if (state.notificationCount != Int.MAX_VALUE) emit { state.copy(notificationCount = state.notificationCount + 1, elapsedMs = it) }
    }

    private fun accepts(token: Long, source: BleGattPort) = !finished && token == this.token && source === port

    private fun enter(status: BleProbeStatus, limit: Long) {
        stageDeadline = minOf(now() + limit, sessionDeadline - CLEANUP_MS)
        emit { state.copy(status = status, elapsedMs = it) }
    }

    private fun cleanup(status: BleProbeStatus, reason: String) {
        if (finished || state.status == BleProbeStatus.CLEANING_UP) return
        // COMPLETE를 요청할 수 있는 유일한 경로는 확인된 구독의 관찰 deadline이다.
        outcome = if (status == BleProbeStatus.COMPLETE && !state.subscriptionConfirmed) BleProbeStatus.FAILED else status
        outcomeReason = reason
        cleanupDeadline = minOf(now() + CLEANUP_MS, sessionDeadline)
        unsubscribeDeadline = minOf(now() + UNSUBSCRIBE_MS, cleanupDeadline)
        emit { state.copy(status = BleProbeStatus.CLEANING_UP, reason = reason, elapsedMs = it) }
        if (notificationsEnabled) {
            try { port.setNotifications(false) } catch (_: Exception) { /* close evidence와 독립이다. */ }
            notificationsEnabled = false
        }
        advanceCleanup()
    }

    private fun advanceCleanup() {
        if (finished || state.status != BleProbeStatus.CLEANING_UP) return
        if (!allocated || state.disconnectConfirmed || now() >= cleanupDeadline) {
            closeLocal()
            return
        }
        if (disconnectRequested) return
        if (connectedNow && enableRequested && !disableFinished && now() < unsubscribeDeadline) {
            // 취소 때 enable이 진행 중이면 callback 또는 제한 시각까지 기다린다. 중첩 write 금지.
            if (pendingWrite != null) return
            if (!disableAttempted) {
                disableAttempted = true
                pendingWrite = false
                val accepted = try { port.writeSubscription(false) } catch (_: Exception) { false }
                if (accepted) return
                pendingWrite = null
                disableFinished = true
            }
        }
        disconnectRequested = true
        try { port.disconnect() } catch (_: Exception) { /* disconnected callback과 close를 별도로 확인한다. */ }
    }

    private fun closeLocal() {
        if (finished) return
        // callback과 재진입을 먼저 봉인한다. close 실패도 외부 stop이나 늦은 callback으로 재해제하지 않는다.
        finished = true
        val closed = try { port.close(); true } catch (_: Exception) { false }
        emit { elapsed ->
            state.copy(
                status = if (closed) outcome else BleProbeStatus.CLEANUP_FAILED,
                reason = if (closed) outcomeReason else "LOCAL_CLOSE_FAILED_RESTART_REQUIRED",
                localClosed = closed,
                elapsedMs = elapsed,
            )
        }
        if (closed) release()
    }

    private inline fun emit(copyState: (Long) -> BleProbeState) {
        state = copyState((now() - started).coerceAtLeast(0))
        publish(state)
    }

    companion object {
        const val CONNECT_MS = 8_000L
        const val DISCOVER_MS = 6_000L
        const val SUBSCRIBE_MS = 4_000L
        const val OBSERVE_MS = 3_000L
        const val UNSUBSCRIBE_MS = 2_000L
        const val CLEANUP_MS = 4_000L
    }
}
