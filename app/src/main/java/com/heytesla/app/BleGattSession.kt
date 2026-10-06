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
    val gattStatus: Int? = null,
    /** 마지막 전이 때 끝난 단계 또는 현재 단계의 실제 monotonic 경과 시간. */
    val phaseElapsedMs: Long = 0,
    val primaryGattStatus: Int? = null,
    val cleanupGattStatus: Int? = null,
    val firstRxElapsedMs: Long? = null,
    val backgroundConnect: Boolean = false,
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
    fun connection(token: Long, source: BleGattPort, success: Boolean, connected: Boolean, gattStatus: Int? = null)
    fun services(token: Long, source: BleGattPort, success: Boolean, gattStatus: Int? = null)
    fun descriptor(token: Long, source: BleGattPort, enabled: Boolean, success: Boolean, gattStatus: Int? = null, action: BleEvidenceAction = BleEvidenceAction.DESCRIPTOR_WRITE_CALLBACK)
    fun verifyUnsubscribe(token: Long, source: BleGattPort, gattStatus: Int, read: () -> Boolean): Boolean
    fun notification(token: Long, source: BleGattPort)
}

/**
 * Main 직렬 경계의 작은 비동기 세션. 시계와 handle만 대체해 실제 취소·정리 전이를 검사한다.
 * 정상 continuation은 취소 즉시 봉인하지만, 진행 중 CCCD callback은 정리 전용으로 계속 받는다.
 */
internal class BleGattSession(
    val token: Long,
    sessionDeadline: Long,
    private val port: BleGattPort,
    private val now: () -> Long,
    private val publish: (BleProbeState) -> Unit,
    private val release: () -> Unit,
    private val finishedCallback: (BleProbeState) -> Unit = {},
    private val attempt: Long = token,
    backgroundConnect: Boolean = false,
    deadline: Long? = null,
    private val recordEvidence: (BleEventEvidence) -> Unit = {},
) : BleGattListener {
    private val sessionDeadline = minOf(sessionDeadline, deadline ?: sessionDeadline)
    var state = BleProbeState(backgroundConnect = backgroundConnect)
        private set
    private val started = now()
    private var phaseStarted = started
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
    private var requestEvidenceCount = 0
    private var callbackEvidenceCount = 0

    fun start() {
        if (state.status != BleProbeStatus.IDLE) return
        enter(BleProbeStatus.CONNECTING, CONNECT_MS)
        if (finished || state.status != BleProbeStatus.CONNECTING) return
        if (now() >= stageDeadline) {
            cleanup(BleProbeStatus.TIMED_OUT, "SESSION_EXPIRED")
            return
        }
        request(BleEvidenceAction.CONNECT, received = { allocated = it }) { port.connect() }
        if (!allocated) cleanup(BleProbeStatus.FAILED, "CONNECT_REQUEST_FAILED")
    }

    fun cancel(reason: String = "USER_CANCELED") {
        // 소유권 부착 뒤 시작 이벤트 기록 중 stop이 재진입하면 IDLE도 먼저 봉인·반환한다.
        if (!finished && state.status != BleProbeStatus.CLEANING_UP) {
            cleanup(BleProbeStatus.CANCELED, reason)
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

    override fun connection(token: Long, source: BleGattPort, success: Boolean, connected: Boolean, gattStatus: Int?) {
        if (!accepts(token, source)) return
        tick()
        if (finished || !state.active) return
        callback(BleEvidenceAction.CONNECTION_CALLBACK, success, gattStatus)
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
        if (finished || state.status != BleProbeStatus.DISCOVERING) return
        val accepted = request(BleEvidenceAction.DISCOVER) { port.discoverServices() }
        if (!accepted) cleanup(BleProbeStatus.FAILED, "DISCOVERY_REQUEST_FAILED")
    }

    override fun services(token: Long, source: BleGattPort, success: Boolean, gattStatus: Int?) {
        if (!accepts(token, source)) return
        tick()
        if (finished || state.status != BleProbeStatus.DISCOVERING) return
        callback(BleEvidenceAction.SERVICES_CALLBACK, success, gattStatus)
        if (finished || state.status != BleProbeStatus.DISCOVERING) return
        if (!success) {
            cleanup(BleProbeStatus.FAILED, "DISCOVERY_FAILED")
            return
        }
        val profile = try {
            port.profile()
        } catch (_: Exception) {
            requestEvidence(BleEvidenceAction.PROFILE, BleEvidenceResult.EXCEPTION)
            cleanup(BleProbeStatus.FAILED, "PROFILE_READ_FAILED")
            return
        }
        requestEvidence(BleEvidenceAction.PROFILE, BleEvidenceResult.COMPLETED)
        if (finished || state.status != BleProbeStatus.DISCOVERING) return
        emit { state.copy(serviceFound = profile.serviceFound, txFound = profile.txFound, rxFound = profile.rxFound, elapsedMs = it) }
        if (finished || state.status != BleProbeStatus.DISCOVERING) return
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
        if (finished || state.status != BleProbeStatus.SUBSCRIBING) return
        request(BleEvidenceAction.NOTIFICATION_ON, received = { notificationsEnabled = it }) { port.setNotifications(true) }
        if (finished || state.status != BleProbeStatus.SUBSCRIBING) return
        if (!notificationsEnabled) {
            cleanup(BleProbeStatus.FAILED, "LOCAL_SUBSCRIPTION_FAILED")
            return
        }
        pendingWrite = true
        request(BleEvidenceAction.CCCD_ON, received = { enableRequested = it }) { port.writeSubscription(true) }
        if (!enableRequested) {
            pendingWrite = null
            cleanup(BleProbeStatus.FAILED, "SUBSCRIPTION_REQUEST_FAILED")
        }
    }

    override fun descriptor(token: Long, source: BleGattPort, enabled: Boolean, success: Boolean, gattStatus: Int?, action: BleEvidenceAction) {
        if (!accepts(token, source)) return
        tick()
        if (!acceptsDescriptor(enabled)) return
        callback(action, success, gattStatus)
        if (!acceptsDescriptor(enabled)) return
        completeDescriptor(enabled, success)
    }

    /** 해제 write 성공은 구독 해제의 증거가 아니다. 실제 CCCD readback 요청/결과를 따로 남긴다. */
    override fun verifyUnsubscribe(token: Long, source: BleGattPort, gattStatus: Int, read: () -> Boolean): Boolean {
        if (!accepts(token, source)) return false
        tick()
        if (!acceptsDescriptor(false)) return false
        callback(BleEvidenceAction.DESCRIPTOR_WRITE_CALLBACK, true, gattStatus)
        if (!acceptsDescriptor(false)) return false
        val accepted = request(BleEvidenceAction.CCCD_READ, operation = read)
        if (!accepted && acceptsDescriptor(false)) completeDescriptor(false, false)
        return accepted
    }

    private fun acceptsDescriptor(enabled: Boolean) =
        !finished && pendingWrite == enabled &&
            (state.status == BleProbeStatus.CLEANING_UP || enabled && state.status == BleProbeStatus.SUBSCRIBING)

    private fun completeDescriptor(enabled: Boolean, success: Boolean) {
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
        if (state.notificationCount == Int.MAX_VALUE) return
        val first = state.firstRxElapsedMs == null
        emit(beforePublish = {
            if (first) recordEvidence(BleEventEvidence(
                kind = BleEvidenceKind.GATT_FIRST_RX,
                action = BleEvidenceAction.RX_CALLBACK,
                phase = BleEvidencePhase.OBSERVING,
                attempt = attempt,
                receivedElapsedMs = now(),
                accepted = true,
            ))
        }) {
            state.copy(notificationCount = state.notificationCount + 1, firstRxElapsedMs = state.firstRxElapsedMs ?: it, elapsedMs = it)
        }
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
            request(BleEvidenceAction.NOTIFICATION_OFF) { port.setNotifications(false) }
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
                val accepted = request(BleEvidenceAction.CCCD_OFF) { port.writeSubscription(false) }
                if (accepted) return
                pendingWrite = null
                disableFinished = true
            }
        }
        disconnectRequested = true
        requestUnit(BleEvidenceAction.DISCONNECT) { port.disconnect() }
    }

    private fun closeLocal() {
        if (finished) return
        // callback과 재진입을 먼저 봉인한다. close 실패도 외부 stop이나 늦은 callback으로 재해제하지 않는다.
        finished = true
        val closed = requestUnit(BleEvidenceAction.CLOSE) { port.close() }
        emit { elapsed ->
            state.copy(
                status = if (closed) outcome else BleProbeStatus.CLEANUP_FAILED,
                reason = if (closed) outcomeReason else "LOCAL_CLOSE_FAILED_RESTART_REQUIRED",
                localClosed = closed,
                elapsedMs = elapsed,
            )
        }
        if (closed) release()
        // 요약은 예약의 실제 반환 뒤에 기록한다. close 실패는 반환 없이 실패 상태 그대로 전달한다.
        finishedCallback(state)
    }

    private inline fun request(
        action: BleEvidenceAction,
        received: (Boolean) -> Unit = {},
        operation: () -> Boolean,
    ): Boolean {
        val accepted = try {
            operation()
        } catch (_: Exception) {
            received(false)
            requestEvidence(action, BleEvidenceResult.EXCEPTION)
            return false
        }
        // 기록 consumer가 stop을 재진입해도 이미 접수된 GATT 요청을 정리할 수 있어야 한다.
        received(accepted)
        requestEvidence(action, if (accepted) BleEvidenceResult.ACCEPTED else BleEvidenceResult.REJECTED)
        return accepted
    }

    private inline fun requestUnit(action: BleEvidenceAction, operation: () -> Unit): Boolean {
        try {
            operation()
        } catch (_: Exception) {
            requestEvidence(action, BleEvidenceResult.EXCEPTION)
            return false
        }
        requestEvidence(action, BleEvidenceResult.COMPLETED)
        return true
    }

    private fun requestEvidence(action: BleEvidenceAction, result: BleEvidenceResult) {
        if (requestEvidenceCount >= MAX_REQUEST_EVIDENCE) return
        requestEvidenceCount++
        recordEvidence(BleEventEvidence(
            kind = BleEvidenceKind.GATT_REQUEST,
            action = action,
            phase = evidencePhase(),
            result = result,
            attempt = attempt,
            receivedElapsedMs = now(),
            accepted = result == BleEvidenceResult.ACCEPTED || result == BleEvidenceResult.COMPLETED,
        ))
    }

    private fun callback(action: BleEvidenceAction, success: Boolean, gattStatus: Int?) {
        val phase = evidencePhase()
        // 상태를 먼저 반영하고 publish 직전에 기록한다. 기록 중 stop이 재진입해도 기존 회차 증거다.
        emit(beforePublish = {
            if (callbackEvidenceCount < MAX_CALLBACK_EVIDENCE) {
                callbackEvidenceCount++
                recordEvidence(BleEventEvidence(
                    kind = BleEvidenceKind.GATT_CALLBACK,
                    action = action,
                    phase = phase,
                    attempt = attempt,
                    receivedElapsedMs = now(),
                    accepted = success,
                    gattStatus = gattStatus,
                ))
            }
        }) {
            // 정리 전 마지막 SDK status는 동결한다. status 없는 합성 결과로도 덮지 않는다.
            state.copy(
                gattStatus = gattStatus,
                primaryGattStatus = if (phase == BleEvidencePhase.CLEANING_UP) state.primaryGattStatus else gattStatus ?: state.primaryGattStatus,
                cleanupGattStatus = if (phase == BleEvidencePhase.CLEANING_UP) gattStatus ?: state.cleanupGattStatus else state.cleanupGattStatus,
                elapsedMs = it,
            )
        }
    }

    private fun evidencePhase(): BleEvidencePhase? = when (state.status) {
        BleProbeStatus.CONNECTING -> BleEvidencePhase.CONNECTING
        BleProbeStatus.DISCOVERING -> BleEvidencePhase.DISCOVERING
        BleProbeStatus.SUBSCRIBING -> BleEvidencePhase.SUBSCRIBING
        BleProbeStatus.OBSERVING -> BleEvidencePhase.OBSERVING
        BleProbeStatus.CLEANING_UP -> BleEvidencePhase.CLEANING_UP
        else -> null
    }

    private inline fun emit(beforePublish: () -> Unit = {}, copyState: (Long) -> BleProbeState) {
        val timestamp = now()
        val next = copyState((timestamp - started).coerceAtLeast(0))
        val changed = next.status != state.status
        state = next.copy(phaseElapsedMs = (timestamp - phaseStarted).coerceAtLeast(0))
        if (changed) phaseStarted = timestamp
        beforePublish()
        publish(state)
    }

    companion object {
        const val CONNECT_MS = 8_000L
        const val DISCOVER_MS = 6_000L
        const val SUBSCRIBE_MS = 4_000L
        const val OBSERVE_MS = 3_000L
        const val UNSUBSCRIBE_MS = 2_000L
        const val CLEANUP_MS = 4_000L
        // RX는 최초 한 건만 기록한다. 요청/콜백 폭주도 회차당 각 32건으로 제한해 2 MiB 로그를 보호한다.
        const val MAX_REQUEST_EVIDENCE = 32
        const val MAX_CALLBACK_EVIDENCE = 32
    }
}
