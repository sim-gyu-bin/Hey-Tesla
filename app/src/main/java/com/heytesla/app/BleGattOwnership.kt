package com.heytesla.app

/** Android probe가 그대로 사용하는 소유권 경계. 늦은 구 토큰은 새 handle/예약/타이머를 정리할 수 없다. */
internal class BleGattOwnership(
    private val releaseLease: (Long) -> Unit,
    private val removeWatchdog: (Runnable) -> Unit,
) {
    var current: BleGattSession? = null
        private set
    var associationId: Int? = null
        private set
    var watchdog: Runnable? = null
        private set

    fun attach(session: BleGattSession, associationId: Int) {
        check(current == null)
        current = session
        this.associationId = associationId
    }

    fun watch(token: Long, task: Runnable) {
        check(current?.token == token)
        check(watchdog == null)
        watchdog = task
    }

    fun stopWatchdog(token: Long) {
        if (current?.token != token) return
        watchdog?.let(removeWatchdog)
        watchdog = null
    }

    /** 실제 close 성공 뒤만 호출. 완료 callback은 이 메서드가 반환한 뒤에만 실행한다. */
    fun release(token: Long) {
        if (current?.token != token) return
        releaseLease(token)
        stopWatchdog(token)
        associationId = null
        current = null
    }
}
