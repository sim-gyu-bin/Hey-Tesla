package com.heytesla.app

/** 직렬 실행자 전용. 최초 native 133에만 같은 handle의 재연결을 elapsed 기준으로 대기한다. */
internal class TeslaGattConnectRetry(
    private val now: () -> Long,
    private val postDelayed: (Runnable, Long) -> Boolean,
    private val remove: (Runnable) -> Unit,
    private val allowed: () -> Boolean,
    private val event: (String) -> Unit,
    private val failed: () -> Unit,
    private val connect: () -> Unit,
) {
    private var used = false
    private var sealed = false
    private var pending = false
    private var due = 0L
    private val wake = Runnable { advance() }

    fun retry(status: Int, eligible: Boolean): Boolean {
        if (sealed || status != 133 || !eligible) return false
        if (pending) return true // 같은 대기 중의 중복 133은 예약을 소비하거나 실패시키지 않는다.
        if (used) return false
        used = true
        due = now() + 2_000L
        pending = true
        event("TESLA_APPROACH_GATT_RETRY_SCHEDULED")
        advance()
        return true
    }

    private fun advance() {
        if (sealed || !pending) return
        if (!allowed()) { seal(); return }
        if (sealed || !pending) return
        val remaining = due - now()
        if (remaining > 0) {
            if (!postDelayed(wake, remaining)) { seal(); failed() }
            return
        }
        pending = false
        event("TESLA_APPROACH_GATT_RETRY_STARTED")
        if (!sealed && allowed()) connect()
    }

    fun seal() {
        sealed = true
        remove(wake)
        if (pending) {
            pending = false
            event("TESLA_APPROACH_GATT_RETRY_CANCELED")
        }
    }
}
