package com.heytesla.app.vehicle.detection

import com.heytesla.app.diagnostics.BleCandidateDiagnostics
import com.heytesla.app.vehicle.ble.BleProbeState
import com.heytesla.app.vehicle.ble.BleProbeStatus

/** 서비스가 사용하는 단일 정책. 시간은 단조 시계이며 모든 호출은 Main에서 직렬화한다. */
internal class BleFieldTrialPolicy(
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

    private var scanFreshUntil: Long? = null
    private var localRecentUntil: Long? = null
    private var candidateAttempted = false
    private var lastScanAt = Long.MIN_VALUE
    private var attemptCancelRequested = false
    private var lastFinishedAt = Long.MIN_VALUE
    private var serviceScanRunning = false
    private var serviceScanStartedAt = Long.MAX_VALUE
    private var serviceDepartureRemainingMs = READ_START_FRESH_MS
    private var serviceExpiredAt: Long? = null

    /** 중단된 native scan의 무수신 시간은 차량 이탈 증거가 아니다. */
    fun scanAvailabilityChanged(running: Boolean) {
        if (serviceScanRunning == running) return
        serviceScanRunning = running
        if (running) {
            serviceScanStartedAt = now()
            if (present) scanFreshUntil = now() + serviceDepartureRemainingMs
        } else if (present) {
            serviceDepartureRemainingMs = ((scanFreshUntil ?: now()) - now())
                .coerceIn(0L, READ_START_FRESH_MS)
        }
    }

    /** 첫 유효거리 이후 종료는 coordinator의 수신 신선도 정책만 소유한다. */
    fun serviceRanging(attempt: Long) {
        if (currentAttempt == attempt && accepting && !attemptCancelRequested) attemptDeadline = null
    }

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
        observedElapsedMs = now(), candidatePresent = present, readInFlight = currentAttempt != null,
        waitingForDeparture = waitingForDeparture, scanRunning = scanRunning,
        acceptedAdvertisementCount = acceptedAdvertisementCount,
        candidateAdvertisementCount = candidateAdvertisementCount, suppressedReadCount = suppressedReadCount,
        lastAcceptedReceivedElapsedMs = lastAcceptedReceivedElapsedMs,
        lastAcceptedDeliveredElapsedMs = lastAcceptedDeliveredElapsedMs,
        expiresElapsedMs = candidateExpiry(), lastExpiredElapsedMs = lastExpiredElapsedMs,
        lastExpiryDelayMs = lastExpiryDelayMs,
    )

    val expiresElapsedMs: Long? get() = candidateExpiry()

    /** 연결·정리·스캔 중지 시간은 이탈로 계산하지 않는다. */
    private fun candidateExpiry(): Long? {
        if (currentAttempt != null || !serviceScanRunning || !accepting || !present) return null
        return scanFreshUntil ?: 0L
    }

    private fun observeAdvertisement(received: Long, delivered: Long) {
        if (received < 0 || received > delivered || delivered - received > READ_START_FRESH_MS ||
            lastAcceptedReceivedElapsedMs?.let { received <= it } == true
        ) return
        acceptedAdvertisementCount++
        candidateAdvertisementCount = if (present) candidateAdvertisementCount + 1 else 1
        if (candidateAttempted && waitingForDeparture) suppressedReadCount++
        lastAcceptedReceivedElapsedMs = received
        lastAcceptedDeliveredElapsedMs = delivered
    }

    val waitingForDeparture: Boolean
        get() = accepting && present && currentAttempt == null

    /** 현재 소스 만료와 최초 측정 예산만 예약한다. timer 자체는 후보를 만들지 않는다. */
    val nextWakeAt: Long?
        get() {
            if (!accepting) return null
            var next = if (currentAttempt != null && !attemptCancelRequested) attemptDeadline else null
            if (!serviceScanRunning) return next
            if (present && currentAttempt == null) {
                val expiry = scanFreshUntil ?: 0L
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
        // CDM/BT는 기록만 남기며 정확한 새 광고를 대신하지 않는다.
        if (receivedElapsedMs > timestamp || timestamp - receivedElapsedMs > 60_000L) {
            emit(BleEvidenceKind.SIGNAL_SHADOWED, signal, BleEvidenceGate.STALE, receivedElapsedMs)
            return Decision.NONE
        }
        if (signal != BleEvidenceSignal.FILTERED_SCAN) {
            emit(BleEvidenceKind.SIGNAL_SHADOWED, signal, received = receivedElapsedMs)
            return Decision.NONE
        }
        if (receivedElapsedMs < 0 || receivedElapsedMs <= lastScanAt || receivedElapsedMs <= lastFinishedAt ||
            timestamp - receivedElapsedMs > READ_START_FRESH_MS || !serviceScanRunning ||
            receivedElapsedMs < serviceScanStartedAt
        ) {
            emit(BleEvidenceKind.SIGNAL_SHADOWED, signal, BleEvidenceGate.STALE, receivedElapsedMs)
            return Decision.NONE
        }
        lastScanAt = receivedElapsedMs
        // 배치 전달 지연은 이탈이 아니다. 기한 전 수신 광고는 만료 직후에도 같은 후보다.
        if (!present && candidateAttempted && serviceExpiredAt?.let { receivedElapsedMs < it } == true) {
            present = true
            scanFreshUntil = receivedElapsedMs + READ_START_FRESH_MS
            serviceExpiredAt = null
        }
        if (present && currentAttempt == null && receivedElapsedMs >= (scanFreshUntil ?: 0L)) expire()
        scanFreshUntil = maxOf(scanFreshUntil ?: 0L, receivedElapsedMs + READ_START_FRESH_MS)
        serviceDepartureRemainingMs = READ_START_FRESH_MS
        if (!present) candidateAdvertisementCount = 0
        observeAdvertisement(receivedElapsedMs, timestamp)
        if (present) return Decision.NONE
        present = true
        serviceExpiredAt = null
        candidateCount++
        candidateAttempted = false
        emit(BleEvidenceKind.CANDIDATE_SIGNAL, signal, received = receivedElapsedMs)
        return if (accepting && currentAttempt == null) begin() else Decision.NONE
    }

    fun tick(): Decision {
        if (!accepting) return Decision.NONE
        val timestamp = now()
        if (currentAttempt != null && !attemptCancelRequested && timestamp >= (attemptDeadline ?: Long.MAX_VALUE)) {
            attemptCancelRequested = true
            return Decision(cancelAttempt = currentAttempt)
        }
        if (present && currentAttempt == null && serviceScanRunning && timestamp >= (scanFreshUntil ?: 0L)) {
            expire()
        }
        return Decision.NONE
    }

    /** 실제 close와 예약 반환 후에만 호출한다. 늦은 완료는 새 회차에 영향을 주지 않는다. */
    fun finished(attempt: Long, state: BleProbeState, leaseReleased: Boolean) {
        if (currentAttempt != attempt || leaseRetained) return
        if (!leaseReleased || state.status == BleProbeStatus.CLEANUP_FAILED || !state.localClosed) {
            leaseRetained = true
            stop()
            emit(BleEvidenceKind.RETRY_SUPPRESSED, gate = BleEvidenceGate.CLEANUP_FAILED)
            return
        }
        currentAttempt = null
        localGattActive = false
        localRecentUntil = now() + LOCAL_GATT_RECENT_MS
        lastFinishedAt = now()
        serviceDepartureRemainingMs = READ_START_FRESH_MS
        if (serviceScanRunning) scanFreshUntil = now() + READ_START_FRESH_MS
        completedCount++
        if (accepting) tick()
    }

    fun stop() {
        accepting = false
        scanFreshUntil = null
    }

    private fun expire() {
        if (!present) return
        val timestamp = now()
        val deadline = candidateExpiry()
        serviceExpiredAt = deadline
        lastExpiredElapsedMs = timestamp
        lastExpiryDelayMs = deadline?.let { (timestamp - it).coerceAtLeast(0) }
        present = false
        scanFreshUntil = null
        emit(BleEvidenceKind.CANDIDATE_EXPIRED)
    }

    private fun begin(): Decision {
        check(currentAttempt == null && accepting && present)
        candidateAttempted = true
        attemptCancelRequested = false
        attemptDeadline = now() + SERVICE_INITIAL_BUDGET_MS
        localGattActive = true // connectGatt 발급 이전부터 봉인한다.
        val attempt = ++attemptCount
        currentAttempt = attempt
        return Decision(startAttempt = attempt)
    }

    private fun emit(
        kind: BleEvidenceKind, signal: BleEvidenceSignal? = null,
        gate: BleEvidenceGate? = null, received: Long? = null,
    ) {
        evidence(BleEventEvidence(kind, signal = signal, gate = gate, candidate = candidateCount.takeIf { it > 0 },
            attempt = currentAttempt, receivedElapsedMs = received, queueDelayMs = received?.let { now() - it },
            accepted = kind == BleEvidenceKind.CANDIDATE_SIGNAL,
            localGattActive = localGattActive, localGattRecent = localRecentUntil?.let { now() < it } == true))
    }

    companion object {
        const val LOCAL_GATT_RECENT_MS = 15_000L
        const val READ_START_FRESH_MS = 10_000L
        const val SERVICE_INITIAL_BUDGET_MS = 180_000L
    }
}
