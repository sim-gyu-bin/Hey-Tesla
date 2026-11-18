package com.heytesla.app.diagnostics

/**
 * 정책에 전달된 유효 filtered 광고 관측. native 전체 광고 수나 차량 인증 결과가 아니다.
 * received는 ScanResult의 단조시각, delivered/observed는 정책 적용/집계 시점이다.
 * expires는 현재 후보의 실제 소스 만료 deadline이며, 타이머 지연으로 observed보다 과거일 수 있다.
 * null은 미관측/기한 없음, 0은 실제 관측된 0이다. 민감 문자열이나 원시 패킷을 담지 않는다.
 */
data class BleCandidateDiagnostics(
    val candidate: Long? = null,
    val observedElapsedMs: Long = 0,
    val candidatePresent: Boolean = false,
    val readInFlight: Boolean = false,
    val waitingForDeparture: Boolean = false,
    val scanRunning: Boolean? = null,
    val acceptedAdvertisementCount: Long = 0,
    val candidateAdvertisementCount: Long = 0,
    val suppressedReadCount: Long = 0,
    val lastAcceptedReceivedElapsedMs: Long? = null,
    val lastAcceptedDeliveredElapsedMs: Long? = null,
    val expiresElapsedMs: Long? = null,
    val lastExpiredElapsedMs: Long? = null,
    val lastExpiryDelayMs: Long? = null,
)

/** 비민감 scalar만 직렬화한다. 잘못된 범위/미래의 과거 관측은 해당 필드만 null이다. */
object BleCandidateDiagnosticsJson {
    fun encode(snapshot: BleCandidateDiagnostics): String {
        val observed = snapshot.observedElapsedMs.takeIf { it >= 0 }
        val delivered = snapshot.lastAcceptedDeliveredElapsedMs?.takeIf {
            observed != null && it in 0..observed
        }
        val received = snapshot.lastAcceptedReceivedElapsedMs?.takeIf {
            observed != null && it in 0..(delivered ?: observed)
        }
        val expired = snapshot.lastExpiredElapsedMs?.takeIf {
            observed != null && it in 0..observed
        }
        return buildString(640) {
            append("{\"candidate\":").append(snapshot.candidate?.takeIf { it > 0 })
            append(",\"observedElapsedMs\":").append(observed)
            append(",\"candidatePresent\":").append(snapshot.candidatePresent)
            append(",\"readInFlight\":").append(snapshot.readInFlight)
            append(",\"waitingForDeparture\":").append(snapshot.waitingForDeparture)
            append(",\"scanRunning\":").append(snapshot.scanRunning)
            append(",\"acceptedAdvertisementCount\":").append(snapshot.acceptedAdvertisementCount.takeIf { it >= 0 })
            append(",\"candidateAdvertisementCount\":").append(snapshot.candidateAdvertisementCount.takeIf {
                it >= 0 && it <= snapshot.acceptedAdvertisementCount
            })
            append(",\"suppressedReadCount\":").append(snapshot.suppressedReadCount.takeIf {
                it >= 0 && it <= snapshot.acceptedAdvertisementCount
            })
            append(",\"lastAcceptedReceivedElapsedMs\":").append(received)
            append(",\"lastAcceptedDeliveredElapsedMs\":").append(delivered)
            // deadline은 미래일 수 있고, 관측이 지연되면 과거일 수도 있다.
            append(",\"expiresElapsedMs\":").append(snapshot.expiresElapsedMs?.takeIf {
                snapshot.candidatePresent && observed != null && it >= 0
            })
            append(",\"lastExpiredElapsedMs\":").append(expired)
            append(",\"lastExpiryDelayMs\":").append(snapshot.lastExpiryDelayMs?.takeIf {
                expired != null && it in 0..expired
            })
            append('}')
        }
    }
}

/** Main 직렬 호출 전용. 정책을 실행하지 않고 dirty 관측만 집계한다. 세대마다 runnable 하나를 재사용한다. */
internal class BleCandidateDiagnosticsPublisher(
    private val now: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val isCurrent: () -> Boolean,
    private val snapshot: () -> BleCandidateDiagnostics,
    private val publish: (BleCandidateDiagnostics) -> Unit,
) {
    var lastSnapshot: BleCandidateDiagnostics? = null
        private set
    private var enabled = true
    private var dirty = false
    private var scheduled = false
    private var lastFlushAt = now()
    private val tick = Runnable {
        scheduled = false
        if (enabled && dirty && isCurrent()) {
            val remaining = INTERVAL_MS - (now() - lastFlushAt)
            if (remaining > 0) schedule(remaining) else flush()
        }
    }

    fun markDirty() {
        if (!enabled || !isCurrent()) return
        dirty = true
        if (!scheduled) schedule((INTERVAL_MS - (now() - lastFlushAt)).coerceAtLeast(0))
    }

    /** 전이는 간격 제한 없이 기록한다. stop 이후에도 실제 close/scan stop 완료는 강제 관측할 수 있다. */
    fun flush() {
        if (!isCurrent()) return
        cancelTimer()
        dirty = false
        lastFlushAt = now()
        val value = snapshot()
        lastSnapshot = value
        // 진단 기록 실패로 정책/스캔/조회 수명을 중지하거나 retry를 발급하지 않는다.
        try { publish(value) } catch (_: Exception) { }
    }

    fun stop() {
        enabled = false
        dirty = false
        cancelTimer()
    }

    private fun schedule(delay: Long) {
        removeCallbacks(tick)
        scheduled = true
        postDelayed(tick, delay)
    }

    private fun cancelTimer() {
        if (scheduled) removeCallbacks(tick)
        scheduled = false
    }

    companion object {
        const val INTERVAL_MS = 10_000L
    }
}
