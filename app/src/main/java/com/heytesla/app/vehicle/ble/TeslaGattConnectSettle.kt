package com.heytesla.app.vehicle.ble

/** 같은 직렬 실행자에서 사용한다. Handler uptime이 아닌 elapsed 시각으로 최소 대기를 보장한다. */
internal class TeslaGattConnectSettle(
    private val now: () -> Long,
    private val postDelayed: (Runnable, Long) -> Boolean,
    private val remove: (Runnable) -> Unit,
    private val allowed: () -> Boolean,
    private val onScheduleFailed: () -> Unit,
    private val connect: () -> Unit,
) {
    private var startedAt: Long? = null
    private var sealed = false
    private var completed = false
    private val wake = Runnable { advance() }

    fun start() {
        if (startedAt != null || sealed || !allowed()) return
        val started = now()
        startedAt = started
        advance()
    }

    private fun advance() {
        if (sealed || completed || !allowed()) return
        val remaining = checkNotNull(startedAt) + SETTLE_MS - now()
        if (remaining > 0) {
            if (!postDelayed(wake, remaining)) {
                seal()
                onScheduleFailed()
            }
            return
        }
        completed = true
        // 대기를 마친 뒤에도 owner·권한·기한을 다시 검사한다.
        if (!sealed && allowed()) connect()
    }

    fun seal() {
        sealed = true
        remove(wake)
    }

    private companion object {
        const val SETTLE_MS = 500L
    }
}
