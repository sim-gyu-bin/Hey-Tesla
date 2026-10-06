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

    val waitingForDeparture: Boolean
        get() = accepting && present && currentAttempt == null && retryAt == null && !config.observationOnly

    /** 현재 소스 만료 또는 실제 close 이후 retry만 예약한다. timer 자체는 후보를 만들지 않는다. */
    val nextWakeAt: Long?
        get() {
            if (!accepting) return null
            var next = retryAt
            if (present && mergeSources && !cdmPresent && !externalBtConnected) {
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
                scanFreshUntil = maxOf(scanFreshUntil ?: 0L, receivedElapsedMs + SIGNAL_FRESH_MS)
            }
        }
        if (present) {
            // 패킷마다 증거 행을 만들지 않는다. 스캔 소비자는 최초 수신/새 후보만 계측한다.
            if (signal != BleEvidenceSignal.FILTERED_SCAN) {
                emit(BleEvidenceKind.CANDIDATE_MERGED, signal, BleEvidenceGate.DUPLICATE, receivedElapsedMs)
            }
            return Decision.NONE
        }
        present = true
        candidateCount++
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
        val freshScan = scanFreshUntil?.let { timestamp < it } == true
        val departureReady = timestamp >= (departureGraceUntil ?: 0L) &&
            (!btWithdrawn || (!localGattActive && timestamp >= (localRecentUntil ?: 0L)))
        if (present && mergeSources && !cdmPresent && !externalBtConnected && !freshScan && departureReady) {
            return expire()
        }
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
        if (!leaseReleased || state.status == BleProbeStatus.CLEANUP_FAILED) {
            leaseRetained = true
            stop()
            emit(BleEvidenceKind.RETRY_SUPPRESSED, gate = BleEvidenceGate.CLEANUP_FAILED)
            return Decision.NONE
        }
        currentAttempt = null
        localGattActive = false
        localRecentUntil = now() + LOCAL_GATT_RECENT_MS
        completedCount++
        if (!accepting) return Decision.NONE
        val expiry = tick()
        if (expiry.cancelAttempt != null || !present) return expiry
        if (attemptCandidate != candidateCount) {
            return if (!config.observationOnly) begin() else Decision.NONE
        }
        if (config.retryEnabled && retryable(state) && candidateAttempts < MAX_ATTEMPTS &&
            hasSource(now()) && hasRetryBudget(now() + RETRY_DELAY_MS)
        ) {
            retryAt = now() + RETRY_DELAY_MS
            emit(BleEvidenceKind.RETRY_SCHEDULED, gate = BleEvidenceGate.COOLDOWN)
        } else if (config.retryEnabled) {
            val gate = when {
                state.status == BleProbeStatus.COMPLETE -> BleEvidenceGate.SUCCESS
                state.status == BleProbeStatus.BLOCKED -> BleEvidenceGate.BLOCKED
                !hasRetryBudget(now() + RETRY_DELAY_MS) -> BleEvidenceGate.BUDGET
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
        BleProbeStatus.FAILED -> state.reason in RETRY_FAILURES
        BleProbeStatus.TIMED_OUT -> state.reason in RETRY_TIMEOUTS
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
        const val SIGNAL_FRESH_MS = 180_000L
        const val LOCAL_GATT_RECENT_MS = 15_000L
        const val CANDIDATE_BUDGET_MS = 40_000L
        const val RETRY_DELAY_MS = 2_000L
        const val MAX_ATTEMPTS = 2
        private val RETRY_FAILURES = setOf("CONNECTION_FAILED", "CONNECTION_LOST", "CONNECT_REQUEST_FAILED",
            "DISCOVERY_FAILED", "SUBSCRIPTION_FAILED")
        private val RETRY_TIMEOUTS = setOf("CONNECTING_TIMEOUT", "DISCOVERING_TIMEOUT", "SUBSCRIBING_TIMEOUT")
    }
}
