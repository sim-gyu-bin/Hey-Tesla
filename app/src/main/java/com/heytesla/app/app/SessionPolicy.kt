package com.heytesla.app.app

/** Main-thread confined. Manual microphone and explicit speech diagnostics share one lease. */
class SessionPolicy {
    data class Session(val id: Long, val deadline: Long, val diagnostic: Boolean = false)
    var current: Session? = null
        private set
    private var nextId = 0L

    fun startManual(now: Long): Session? {
        if (current != null) return null
        return Session(++nextId, now + MANUAL_MS).also { current = it }
    }

    /** STT→dry-run→입력 해제→TTS→출력 해제를 동일 current 안에서 소유한다. */
    internal fun startDiagnostic(now: Long): Session? {
        if (current != null) return null
        return Session(++nextId, now + DIAGNOSTIC_MS, diagnostic = true).also { current = it }
    }

    fun expired(id: Long, now: Long) = current?.let { it.id == id && now >= it.deadline } == true
    fun accepts(id: Long) = current?.id == id
    fun finish(id: Long): Boolean {
        val session = current ?: return false
        if (session.id != id) return false
        current = null
        return true
    }

    companion object {
        const val MANUAL_MS = 10_000L
        const val DIAGNOSTIC_MS = 40_000L
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
