package com.heytesla.app

/** Main-thread confined. Only an actual BLE departure rearms automatic approach. */
class SessionPolicy {
    data class Session(val id: Long, val automatic: Boolean, val deadline: Long)
    var current: Session? = null
        private set
    var present = false
        private set
    private var pendingAt: Long? = null
    private var departed = true
    private var cooldownUntil = 0L
    private var nextId = 0L

    fun appeared(now: Long): Boolean {
        if (present) return false
        present = true
        if (current != null || !departed || now < cooldownUntil) return false
        pendingAt = now + DEBOUNCE_MS
        return true
    }

    fun disappeared() {
        present = false
        departed = true
        pendingAt = null
    }

    fun cancelPending() { pendingAt = null }

    /**
     * 세션이 없을 때만 호출한다. 이전 출현·대기 캡처를 새 시험 증거로 재사용하지 않도록 현재 감지만 지운다.
     * 실제 이탈(departed)과 cooldown은 보존해 재장전 조건을 완화하지 않는다.
     */
    fun resetPresence() {
        present = false
        pendingAt = null
    }

    fun startAutomatic(now: Long): Session? {
        val due = pendingAt ?: return null
        if (!present || now < due || now < cooldownUntil || !departed || current != null) return null
        pendingAt = null
        departed = false
        return Session(++nextId, true, now + AUTOMATIC_MS).also { current = it }
    }

    fun startManual(now: Long): Session? {
        if (current != null) return null
        pendingAt = null
        return Session(++nextId, false, now + MANUAL_MS).also { current = it }
    }

    fun expired(id: Long, now: Long) = current?.let { it.id == id && now >= it.deadline } == true
    fun accepts(id: Long) = current?.id == id
    fun finish(id: Long, now: Long): Boolean {
        val session = current ?: return false
        if (session.id != id) return false
        current = null
        if (session.automatic) cooldownUntil = now + COOLDOWN_MS
        return true
    }

    companion object {
        const val DEBOUNCE_MS = 1_500L
        const val AUTOMATIC_MS = 90_000L
        const val MANUAL_MS = 10_000L
        const val COOLDOWN_MS = 30_000L
    }
}

/** 메인 스레드 전용. 요청 토큰은 프로세스에서 새로 발급하며 저장·복원하지 않는다. */
internal class ObservationPolicy {
    var pendingRequest: String? = null
        private set
    var runningRequest: String? = null
        private set

    fun request(token: String): Boolean {
        if (pendingRequest != null || runningRequest != null) return false
        pendingRequest = token
        return true
    }

    fun promote(token: String): Boolean {
        if (pendingRequest != token) return false
        pendingRequest = null
        runningRequest = token
        return true
    }

    fun accepts(token: String) = pendingRequest == token || runningRequest == token

    fun finish(token: String? = null): Boolean {
        if (token != null && !accepts(token)) return false
        pendingRequest = null
        runningRequest = null
        return true
    }
}
