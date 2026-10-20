package com.heytesla.app

/** 서비스가 사용하는 단일 정책. 시간은 단조 시계이며 모든 호출은 Main에서 직렬화한다. */
internal class BleFieldTrialPolicy(
    val config: BleFieldConfig,
    private val now: () -> Long,
    private val evidence: (BleEventEvidence) -> Unit = {},
) {
    internal data class Decision(val startAttempt: Long? = null, val cancelAttempt: Long? = null) {
        companion object { val NONE = Decision() }
    }

    var accepting = true
        private set
    var present = false
        private set
    var candidateCount = 0L
        private set
    var attemptCount = 0L
        private set
    var completedCount = 0L
        private set
    var currentAttempt: Long? = null
        private set
    var leaseRetained = false
        private set
    var localGattActive = false
        private set
    var attemptDeadline: Long? = null
        private set

    private val mergeSources = config.btAssist || config.supplementalScan
    var cdmPresent = false
        private set
    private var externalBtConnected = false
    private var lastCdmAt = Long.MIN_VALUE
    private var lastBtAt = Long.MIN_VALUE
    private var scanFreshUntil: Long? = null
    private var departureGraceUntil: Long? = null
    private var btWithdrawn = false
    private var localRecentUntil: Long? = null
    private var attemptCandidate = 0L
    private var candidateAttempts = 0
    private var candidateDeadline: Long? = null
    private var retryAt: Long? = null
    private var lastScanAt = Long.MIN_VALUE
    private var attemptCancelRequested = false
    private var lastFinishedAt = Long.MIN_VALUE

    var acceptedAdvertisementCount = 0L
        private set
    private var candidateAdvertisementCount = 0L
    private var suppressedReadCount = 0L
    private var lastAcceptedReceivedElapsedMs: Long? = null
    private var lastAcceptedDeliveredElapsedMs: Long? = null
    private var lastExpiredElapsedMs: Long? = null
    private var lastExpiryDelayMs: Long? = null

    /** getter에는 tick/expiry/조회/스캔 동작이 없다. 지연된 timer도 그대로 관측한다. */
    fun diagnostics(scanRunning: Boolean?): BleCandidateDiagnostics = BleCandidateDiagnostics(
        candidate = candidateCount.takeIf { it > 0 },
        observedElapsedMs = now(),
        candidatePresent = present,
        readInFlight = currentAttempt != null,
        waitingForDeparture = waitingForDeparture,
        scanRunning = scanRunning,
        acceptedAdvertisementCount = acceptedAdvertisementCount,
        candidateAdvertisementCount = candidateAdvertisementCount,
        suppressedReadCount = suppressedReadCount,
        lastAcceptedReceivedElapsedMs = lastAcceptedReceivedElapsedMs,
        lastAcceptedDeliveredElapsedMs = lastAcceptedDeliveredElapsedMs,
        expiresElapsedMs = candidateExpiry(),
        lastExpiredElapsedMs = lastExpiredElapsedMs,
        lastExpiryDelayMs = lastExpiryDelayMs,
    )

    val expiresElapsedMs: Long?
        get() = candidateExpiry()

    /** 조회 예산과 별개인 현재 소스 만료. 무기한 CDM/BT·로컬 GATT 봉인 중에는 기한 없음. */
    private fun candidateExpiry(): Long? {
        if (config.authenticatedReadOnly && currentAttempt != null) return null
        if (!accepting || !present || !mergeSources ||
            !config.authenticatedReadOnly && (cdmPresent || externalBtConnected) ||
            btWithdrawn && localGattActive
        ) return null
        var expiry = maxOf(scanFreshUntil ?: 0L, departureGraceUntil ?: 0L)
        if (btWithdrawn) expiry = maxOf(expiry, localRecentUntil ?: 0L)
        return expiry
    }

    private fun observeAdvertisement(received: Long, delivered: Long) {
        // 관측 범위만 제한한다. 비인증 모드의 기존 광고 판정/TTL 정책은 변경하지 않는다.
        if (received < 0 || received > delivered || delivered - received > READ_START_FRESH_MS ||
            lastAcceptedReceivedElapsedMs?.let { received <= it } == true
        ) return
        acceptedAdvertisementCount++
        candidateAdvertisementCount = if (present) candidateAdvertisementCount + 1 else 1
        if (candidateAttempts > 0 && waitingForDeparture) suppressedReadCount++
        lastAcceptedReceivedElapsedMs = received
        lastAcceptedDeliveredElapsedMs = delivered
    }

    val waitingForDeparture: Boolean
        get() = accepting && present && currentAttempt == null && retryAt == null && !config.observationOnly

    /** 현재 소스 만료 또는 실제 close 이후 retry만 예약한다. timer 자체는 후보를 만들지 않는다. */
    val nextWakeAt: Long?
        get() {
            if (!accepting) return null
            var next = if (config.authenticatedReadOnly) {
                if (currentAttempt != null && !attemptCancelRequested) attemptDeadline else null
            } else retryAt
            if (present && mergeSources && (!config.authenticatedReadOnly || currentAttempt == null) &&
                (config.authenticatedReadOnly || !cdmPresent && !externalBtConnected)) {
                var expiry = maxOf(scanFreshUntil ?: 0L, departureGraceUntil ?: 0L)
                if (btWithdrawn) {
                    if (localGattActive) return next
                    expiry = maxOf(expiry, localRecentUntil ?: 0L)
                }
                if (expiry > now()) next = next?.let { minOf(it, expiry) } ?: expiry
            }
            return next
        }

    fun signal(signal: BleEvidenceSignal, receivedElapsedMs: Long): Decision {
        val timestamp = now()
        if (!accepting) {
            emit(BleEvidenceKind.SIGNAL_SHADOWED, signal, BleEvidenceGate.STOPPING, receivedElapsedMs)
            return Decision.NONE
        }
        if (receivedElapsedMs > timestamp || timestamp - receivedElapsedMs > SIGNAL_FRESH_MS) {
            emit(BleEvidenceKind.SIGNAL_SHADOWED, signal, BleEvidenceGate.STALE, receivedElapsedMs)
            return Decision.NONE
        }
        if (config.authenticatedReadOnly && signal != BleEvidenceSignal.FILTERED_SCAN) {
            emit(BleEvidenceKind.SIGNAL_SHADOWED, signal, received = receivedElapsedMs)
            return Decision.NONE
        }
        if (config.authenticatedReadOnly &&
            (receivedElapsedMs < 0 || receivedElapsedMs <= lastScanAt ||
                receivedElapsedMs <= lastFinishedAt || timestamp - receivedElapsedMs > READ_START_FRESH_MS)
        ) {
            emit(BleEvidenceKind.SIGNAL_SHADOWED, signal, BleEvidenceGate.STALE, receivedElapsedMs)
            return Decision.NONE
        }
        if (config.authenticatedReadOnly) {
            lastScanAt = receivedElapsedMs
            // 새 광고만 만료된 접근을 철회/재무장할 수 있다. 완료 callback/timer는 조회를 시작하지 않는다.
            if (present && currentAttempt == null && timestamp >=
                maxOf(scanFreshUntil ?: 0L, departureGraceUntil ?: 0L)) expire()
        }
        val bt = signal == BleEvidenceSignal.BT_CONNECTED || signal == BleEvidenceSignal.BT_DISCONNECTED
        val recent = localRecentUntil?.let { timestamp < it } == true
        val receivedLocally = localRecentUntil?.let { receivedElapsedMs <= it } == true
        if (bt && (localGattActive || recent || receivedLocally)) {
            // 끊김은 출현/이탈 증거가 아니다. 기존 외부 연결 증거만 철회하고 흔들림 시간을 둔다.
            if (signal == BleEvidenceSignal.BT_DISCONNECTED && receivedElapsedMs >= lastBtAt &&
                (externalBtConnected || !btWithdrawn)
            ) {
                lastBtAt = receivedElapsedMs
                externalBtConnected = false
                btWithdrawn = true
                departureGraceUntil = maxOf(departureGraceUntil ?: 0L, timestamp + DEPARTURE_GRACE_MS)
            }
            emit(BleEvidenceKind.SIGNAL_SELF_SUPPRESSED, signal,
                if (localGattActive) BleEvidenceGate.LOCAL_GATT else BleEvidenceGate.RECENT_LOCAL_GATT, receivedElapsedMs)
            return Decision.NONE
        }
        when (signal) {
            BleEvidenceSignal.CDM_BLE_APPEARED, BleEvidenceSignal.CDM_BLE_DISAPPEARED -> {
                if (receivedElapsedMs < lastCdmAt) {
                    emit(BleEvidenceKind.SIGNAL_SHADOWED, signal, BleEvidenceGate.STALE, receivedElapsedMs)
                    return Decision.NONE
                }
                lastCdmAt = receivedElapsedMs
                val wasCdmPresent = cdmPresent
                cdmPresent = signal == BleEvidenceSignal.CDM_BLE_APPEARED
                if (!cdmPresent) {
                    if (!mergeSources) return expire(signal, receivedElapsedMs)
                    if (present && wasCdmPresent) departureGraceUntil = timestamp + DEPARTURE_GRACE_MS
                    emit(BleEvidenceKind.CANDIDATE_MERGED, signal, received = receivedElapsedMs)
                    return tick()
                }
                departureGraceUntil = null
            }
            BleEvidenceSignal.BT_CONNECTED, BleEvidenceSignal.BT_DISCONNECTED -> {
                if (!config.btAssist) {
                    emit(BleEvidenceKind.SIGNAL_SHADOWED, signal, received = receivedElapsedMs)
                    return Decision.NONE
                }
                if (receivedElapsedMs < lastBtAt) {
                    emit(BleEvidenceKind.SIGNAL_SHADOWED, signal, BleEvidenceGate.STALE, receivedElapsedMs)
                    return Decision.NONE
                }
                lastBtAt = receivedElapsedMs
                val wasBtConnected = externalBtConnected
                externalBtConnected = signal == BleEvidenceSignal.BT_CONNECTED
                btWithdrawn = !externalBtConnected
                if (!externalBtConnected) {
                    if (present && wasBtConnected) departureGraceUntil = timestamp + DEPARTURE_GRACE_MS
                    emit(BleEvidenceKind.CANDIDATE_MERGED, signal, received = receivedElapsedMs)
                    return tick()
                }
            }
            BleEvidenceSignal.FILTERED_SCAN -> {
                if (!config.supplementalScan) return Decision.NONE
                val freshness = if (config.authenticatedReadOnly) READ_START_FRESH_MS else SIGNAL_FRESH_MS
                scanFreshUntil = maxOf(scanFreshUntil ?: 0L, receivedElapsedMs + freshness)
                if (!present) candidateAdvertisementCount = 0
                observeAdvertisement(receivedElapsedMs, timestamp)
            }
        }
        if (present) {
            if (config.authenticatedReadOnly && currentAttempt == null &&
                retryAt?.let { timestamp >= it } == true) {
                retryAt = null
                if (candidateAttempts < MAX_ATTEMPTS && hasRetryBudget(timestamp)) return begin()
                emit(BleEvidenceKind.RETRY_SUPPRESSED, gate = BleEvidenceGate.BUDGET)
            }
            // 패킷마다 증거 행을 만들지 않는다. 스캔 소비자는 최초 수신/새 후보만 계측한다.
            if (signal != BleEvidenceSignal.FILTERED_SCAN) {
                emit(BleEvidenceKind.CANDIDATE_MERGED, signal, BleEvidenceGate.DUPLICATE, receivedElapsedMs)
            }
            return Decision.NONE
        }
        present = true
        candidateCount++
        if (signal != BleEvidenceSignal.FILTERED_SCAN) candidateAdvertisementCount = 0
        candidateAttempts = 0
        candidateDeadline = null
        retryAt = null
        emit(BleEvidenceKind.CANDIDATE_SIGNAL, signal,
            if (config.observationOnly) BleEvidenceGate.OBSERVATION_ONLY else null, receivedElapsedMs)
        return if (accepting && currentAttempt == null && !config.observationOnly) begin() else Decision.NONE
    }

    fun tick(): Decision {
        if (!accepting) return Decision.NONE
        val timestamp = now()
        if (config.authenticatedReadOnly && currentAttempt != null && !attemptCancelRequested &&
            timestamp >= (attemptDeadline ?: Long.MAX_VALUE)
        ) {
            attemptCancelRequested = true
            return Decision(cancelAttempt = currentAttempt)
        }
        val freshScan = scanFreshUntil?.let { timestamp < it } == true
        val departureReady = timestamp >= (departureGraceUntil ?: 0L) &&
            (!btWithdrawn || (!localGattActive && timestamp >= (localRecentUntil ?: 0L)))
        if (present && mergeSources && (!config.authenticatedReadOnly || currentAttempt == null) &&
            (config.authenticatedReadOnly || !cdmPresent && !externalBtConnected) && !freshScan && departureReady) {
            return expire()
        }
        if (config.authenticatedReadOnly) return Decision.NONE
        val due = retryAt ?: return Decision.NONE
        if (timestamp < due || currentAttempt != null) return Decision.NONE
        retryAt = null
        if (!present || config.observationOnly) return Decision.NONE
        if (!hasSource(timestamp)) {
            emit(BleEvidenceKind.RETRY_SUPPRESSED, gate = BleEvidenceGate.UNAVAILABLE)
            return Decision.NONE
        }
        if (!hasRetryBudget(timestamp)) {
            emit(BleEvidenceKind.RETRY_SUPPRESSED, gate = BleEvidenceGate.BUDGET)
            return Decision.NONE
        }
        return begin()
    }

    /** Probe의 실제 close와 예약 반환 후에만 호출한다. 늦은 완료는 새 회차에 영향을 주지 않는다. */
    fun finished(attempt: Long, state: BleProbeState, leaseReleased: Boolean): Decision {
        if (currentAttempt != attempt || leaseRetained) return Decision.NONE
        if (!leaseReleased || state.status == BleProbeStatus.CLEANUP_FAILED ||
            config.authenticatedReadOnly && !state.localClosed) {
            leaseRetained = true
            stop()
            emit(BleEvidenceKind.RETRY_SUPPRESSED, gate = BleEvidenceGate.CLEANUP_FAILED)
            return Decision.NONE
        }
        currentAttempt = null
        localGattActive = false
        localRecentUntil = now() + LOCAL_GATT_RECENT_MS
        if (config.authenticatedReadOnly) {
            lastFinishedAt = now()
            departureGraceUntil = now() + READ_START_FRESH_MS
        }
        completedCount++
        if (!accepting) return Decision.NONE
        val expiry = tick()
        if (expiry.cancelAttempt != null || !present) return expiry
        if (attemptCandidate != candidateCount) {
            return if (!config.observationOnly && !config.authenticatedReadOnly) begin() else Decision.NONE
        }
        val retryEnabled = config.retryEnabled || config.authenticatedReadOnly
        val delay = if (config.authenticatedReadOnly) AUTHENTICATED_RETRY_DELAY_MS else RETRY_DELAY_MS
        if (retryEnabled && retryable(state) && candidateAttempts < MAX_ATTEMPTS &&
            (config.authenticatedReadOnly || hasSource(now())) && hasRetryBudget(now() + delay)
        ) {
            retryAt = now() + delay
            emit(BleEvidenceKind.RETRY_SCHEDULED, gate = BleEvidenceGate.COOLDOWN)
        } else if (retryEnabled) {
            val gate = when {
                state.status == BleProbeStatus.COMPLETE -> BleEvidenceGate.SUCCESS
                state.status == BleProbeStatus.BLOCKED -> BleEvidenceGate.BLOCKED
                !hasRetryBudget(now() + delay) -> BleEvidenceGate.BUDGET
                else -> BleEvidenceGate.UNAVAILABLE
            }
            emit(BleEvidenceKind.RETRY_SUPPRESSED, gate = gate)
        }
        return Decision.NONE
    }

    fun stop(): Long? {
        accepting = false
        retryAt = null
        scanFreshUntil = null
        departureGraceUntil = null
        return currentAttempt
    }

    private fun expire(signal: BleEvidenceSignal? = null, received: Long? = null): Decision {
        if (!present) return Decision.NONE
        val timestamp = now()
        val deadline = candidateExpiry()
        lastExpiredElapsedMs = timestamp
        lastExpiryDelayMs = deadline?.let { (timestamp - it).coerceAtLeast(0) }
        present = false
        retryAt = null
        scanFreshUntil = null
        departureGraceUntil = null
        btWithdrawn = false
        emit(BleEvidenceKind.CANDIDATE_EXPIRED, signal, received = received)
        return currentAttempt?.let { Decision(cancelAttempt = it) } ?: Decision.NONE
    }

    private fun begin(): Decision {
        check(currentAttempt == null && accepting && present && !config.observationOnly)
        val timestamp = now()
        if (candidateDeadline == null) candidateDeadline = timestamp + CANDIDATE_BUDGET_MS
        candidateAttempts++
        attemptCancelRequested = false
        attemptCandidate = candidateCount
        attemptDeadline = candidateDeadline
        localGattActive = true // connectGatt 발급 이전부터 봉인한다.
        val attempt = ++attemptCount
        currentAttempt = attempt
        return Decision(startAttempt = attempt)
    }

    private fun hasRetryBudget(timestamp: Long) =
        candidateDeadline?.let { it - timestamp >= BleGattSession.CONNECT_MS + BleGattSession.CLEANUP_MS } == true

    private fun hasSource(timestamp: Long) =
        cdmPresent || externalBtConnected || scanFreshUntil?.let { timestamp < it } == true

    private fun retryable(state: BleProbeState): Boolean = when (state.status) {
        BleProbeStatus.FAILED -> state.reason in
            if (config.authenticatedReadOnly) AUTHENTICATED_RETRY_FAILURES else RETRY_FAILURES
        BleProbeStatus.TIMED_OUT -> if (config.authenticatedReadOnly) {
            state.reason == "DEADLINE_EXPIRED"
        } else state.reason in RETRY_TIMEOUTS
        else -> false
    }

    private fun emit(
        kind: BleEvidenceKind,
        signal: BleEvidenceSignal? = null,
        gate: BleEvidenceGate? = null,
        received: Long? = null,
    ) {
        evidence(BleEventEvidence(kind, signal = signal, gate = gate, candidate = candidateCount.takeIf { it > 0 },
            attempt = currentAttempt, receivedElapsedMs = received,
            queueDelayMs = received?.let { now() - it },
            accepted = kind == BleEvidenceKind.CANDIDATE_SIGNAL || kind == BleEvidenceKind.CANDIDATE_MERGED ||
                kind == BleEvidenceKind.RETRY_SCHEDULED,
            localGattActive = localGattActive, localGattRecent = localRecentUntil?.let { now() < it } == true))
    }

    companion object {
        const val DEPARTURE_GRACE_MS = 30_000L
        const val SIGNAL_FRESH_MS = 60_000L
        const val LOCAL_GATT_RECENT_MS = 15_000L
        const val CANDIDATE_BUDGET_MS = 40_000L
        const val READ_START_FRESH_MS = 10_000L
        const val RETRY_DELAY_MS = 2_000L
        const val AUTHENTICATED_RETRY_DELAY_MS = 3_500L
        private val AUTHENTICATED_RETRY_FAILURES = setOf(
            "GATT_CONNECT_FAILED", "GATT_CONNECTION_LOST", "GATT_DISCOVERY_FAILED",
            "GATT_MTU_FAILED", "GATT_SUBSCRIPTION_FAILED", "GATT_WRITE_FAILED",
        )
        const val MAX_ATTEMPTS = 2
        private val RETRY_FAILURES = setOf("CONNECTION_FAILED", "CONNECTION_LOST", "CONNECT_REQUEST_FAILED",
            "DISCOVERY_FAILED", "SUBSCRIPTION_FAILED")
        private val RETRY_TIMEOUTS = setOf("CONNECTING_TIMEOUT", "DISCOVERING_TIMEOUT", "SUBSCRIBING_TIMEOUT")
    }
}
