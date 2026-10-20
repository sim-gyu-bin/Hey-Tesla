package com.heytesla.app

internal enum class TeslaGattWriteCompletion { IGNORED, SUCCEEDED, FAILED }

/** 같은 직렬 실행자에서 사용한다. post는 현재 callback이 반환된 뒤 runnable을 실행해야 한다. */
internal class TeslaGattWritePump(
    private val post: (Runnable) -> Unit,
    private val postDelayed: (Runnable, Long) -> Boolean,
    private val now: () -> Long,
    private val remove: (Runnable) -> Unit,
    private val allowed: () -> Boolean,
    private val onQueued: () -> Unit,
    private val submit: () -> Unit,
) {
    private enum class State { IDLE, QUEUED, SUBMITTING, BUSY_WAIT, WAITING_CALLBACK, SEALED }

    private var state = State.IDLE
    var descriptorPending = false
        private set
    val queued: Boolean get() = state == State.QUEUED || state == State.BUSY_WAIT
    val writePending: Boolean get() = state == State.SUBMITTING || state == State.WAITING_CALLBACK
    val submitting: Boolean get() = state == State.SUBMITTING
    var busyRetryCount = 0
        private set
    private var issued = false
    private var retrySubmission = false
    private var busyGeneration = 0L
    private var busyTask: Runnable? = null

    // 회차마다 하나를 재사용한다. 이전 회차의 runnable은 seal 뒤에 실행되어도 제출할 수 없다.
    private val dispatch = Runnable {
        if (state != State.QUEUED) return@Runnable
        if (!allowed() || descriptorPending) {
            if (state == State.QUEUED) state = State.IDLE
            return@Runnable
        }
        if (state != State.QUEUED) return@Runnable
        state = State.SUBMITTING
        issued = false
        retrySubmission = false
        submit()
    }

    fun beginDescriptor(allowed: Boolean): Boolean {
        if (!allowed || state != State.IDLE || descriptorPending) return false
        descriptorPending = true
        return true
    }

    fun completeDescriptor(): Boolean {
        if (state == State.SEALED || !descriptorPending) return false
        descriptorPending = false
        return true
    }

    fun queue(): Boolean {
        if (state != State.IDLE || descriptorPending) return false
        if (!allowed() || state != State.IDLE || descriptorPending) return false
        state = State.QUEUED
        busyRetryCount = 0
        try {
            onQueued()
            // 진단 이벤트의 재진입 취소·기한/owner 상실 뒤에는 post도 하지 않는다.
            if (!allowed() || state != State.QUEUED) return false
            post(dispatch)
        } catch (exception: Exception) {
            seal()
            throw exception
        }
        return true
    }

    /** SDK 호출 직전에만 기록한다. 예약·진단 이벤트·취소는 재발급 횟수에 포함하지 않는다. */
    fun markWriteIssued(): Boolean {
        if (state != State.SUBMITTING || issued) return false
        issued = true
        if (retrySubmission) busyRetryCount++
        return true
    }

    /** SDK 0 이후에는 callback만 기다린다. 201만 미접수로 분리하고 나머지는 봉인한다. */
    fun recordWriteResult(status: Int?, failure: Exception? = null) {
        if (state != State.SUBMITTING || !issued) return
        when {
            failure != null -> seal()
            status == 0 -> state = State.WAITING_CALLBACK
            status == BUSY_STATUS && busyRetryCount < MAX_BUSY_RETRIES -> state = State.BUSY_WAIT
            else -> seal()
        }
    }

    /** 100ms는 Tesla APK 값이 아닌 우리 제한값이다. 원 쓰기/회차 기한은 연장하지 않는다. */
    fun retryBusy(deadline: Long): Boolean {
        if (state != State.BUSY_WAIT || busyTask != null) return false
        if (!allowed() || state != State.BUSY_WAIT || deadline - now() <= BUSY_DELAY_MS) {
            seal()
            return false
        }
        val generation = ++busyGeneration
        val task = Runnable {
            if (generation != busyGeneration || state != State.BUSY_WAIT) return@Runnable
            busyTask = null
            if (!allowed() || state != State.BUSY_WAIT || now() >= deadline || descriptorPending) {
                seal()
                return@Runnable
            }
            state = State.SUBMITTING
            issued = false
            retrySubmission = true
            submit()
        }
        busyTask = task
        try {
            if (postDelayed(task, BUSY_DELAY_MS)) return true
        } catch (exception: Exception) {
            seal()
            throw exception
        }
        seal()
        return false
    }

    fun completeWrite(status: Int): TeslaGattWriteCompletion {
        if (state != State.WAITING_CALLBACK) return TeslaGattWriteCompletion.IGNORED
        if (status != 0) {
            seal()
            return TeslaGattWriteCompletion.FAILED
        }
        state = State.IDLE
        return TeslaGattWriteCompletion.SUCCEEDED
    }

    fun seal() {
        state = State.SEALED
        descriptorPending = false
        busyGeneration++
        busyTask?.let(remove)
        busyTask = null
        remove(dispatch)
    }

    private companion object {
        const val BUSY_STATUS = 201
        const val MAX_BUSY_RETRIES = 2
        const val BUSY_DELAY_MS = 100L
    }
}
