package com.heytesla.app

/** 프로세스 RAM에서만 명령 인정 회차를 소비한다. 실패·취소 뒤 같은 명령으로 재입장하지 않는다. */
internal class TeslaFrunkConsentGate {
    private var consumedAt = -1L

    fun claim(commandAcceptedAtElapsedMs: Long, now: Long): String? {
        TeslaFrunkPolicy.consentReason(commandAcceptedAtElapsedMs, now)?.let { return it }
        if (commandAcceptedAtElapsedMs <= consumedAt) return "FRUNK_COMMAND_ALREADY_CONSUMED"
        consumedAt = commandAcceptedAtElapsedMs
        return null
    }
}

internal enum class TeslaFrunkResult { NOT_SENT, UNKNOWN, REJECTED, OPEN_OBSERVED }

/**
 * 단일 GATT 회차의 제어 허가와 소비자 결과. 이전 접근 조회를 입력받는 경로가 없다.
 * 원 wall 시각을 로컬 시각으로 보정하지 않으며, SDK 접수와 callback/ACK/OPEN을 분리한다.
 */
internal class TeslaFrunkPolicy(
    val commandAcceptedAtElapsedMs: Long,
    private val now: () -> Long,
    private val wallNow: () -> Long,
) {
    val deadline: Long = if (commandAcceptedAtElapsedMs in 0..Long.MAX_VALUE - CONSENT_MS)
        commandAcceptedAtElapsedMs + CONSENT_MS else 0L
    private var lastElapsed = commandAcceptedAtElapsedMs
    private var stopped = false
    private var drive: TeslaDriveState? = null
    var driveVerified = false
        private set
    var commandSent = false
        private set
    var commandAcknowledged = false
        private set
    var commandRejected = false
        private set
    var openObserved = false
        private set
    var commandFrameComplete = false
        private set
    private var uncertainDispatch = false
    private var issuing = false
    private var awaitingCallback = false
    private var nextOffset = 0
    private var pendingLength = 0
    val mayHaveSent: Boolean get() = commandSent || uncertainDispatch
    val result: TeslaFrunkResult get() = when {
        commandRejected -> TeslaFrunkResult.REJECTED
        commandAcknowledged && openObserved -> TeslaFrunkResult.OPEN_OBSERVED
        mayHaveSent -> TeslaFrunkResult.UNKNOWN
        else -> TeslaFrunkResult.NOT_SENT
    }

    fun readinessReason(): String? {
        if (stopped) return "FRUNK_CANCELED"
        val current = now()
        if (current < lastElapsed) return "FRUNK_TIME_REVERSED"
        lastElapsed = current
        return consentReason(commandAcceptedAtElapsedMs, current)
    }

    fun acceptDrive(value: TeslaDriveState?): String? {
        readinessReason()?.let { return it }
        if (driveVerified) return "FRUNK_DRIVE_ALREADY_CONSUMED"
        freshParkReason(value)?.let { return it }
        drive = value
        driveVerified = true
        return null
    }

    fun authorizeActuator(): String? {
        readinessReason()?.let { return it }
        if (!driveVerified) return "FRUNK_FRESH_P_REQUIRED"
        if (commandFrameComplete || commandRejected) return "FRUNK_COMMAND_ALREADY_CONSUMED"
        return freshParkReason(drive)
    }

    private fun freshParkReason(value: TeslaDriveState?): String? {
        if (value?.gear != TeslaGear.P) return "FRUNK_EXPLICIT_P_REQUIRED"
        val current = now()
        val received = value.receivedAtElapsedRealtime
        if (received < commandAcceptedAtElapsedMs || received > current || current - received > PARK_RECEIPT_MS) {
            return "FRUNK_P_RECEIPT_STALE"
        }
        val source = value.sourceTimestampEpochMillis ?: return "FRUNK_P_SOURCE_TIME_REQUIRED"
        val wall = wallNow()
        // 차량/폰 wall 시계차만 2초 허용한다. monotonic 수신 미래값에는 적용하지 않는다.
        if (source <= 0 || wall <= 0 ||
            (if (source > wall) source - wall > PARK_SOURCE_FUTURE_SKEW_MS else wall - source > PARK_SOURCE_MS)) {
            return "FRUNK_P_SOURCE_TIME_STALE"
        }
        return null
    }

    /** 실제 actuator SDK 호출 직전. 접수된 offset은 callback 뒤에도 재발급하지 않는다. */
    fun beginWrite(offset: Int, length: Int): String? {
        authorizeActuator()?.let { return it }
        if (issuing || awaitingCallback || offset != nextOffset || length <= 0) return "FRUNK_DUPLICATE_WRITE"
        pendingLength = length
        issuing = true
        uncertainDispatch = true
        return null
    }

    /** 201만 미접수 복구 대상이다. 예외/반환 불명은 전송 가능성을 남기고 봉인한다. */
    fun recordSdkResult(status: Int?, failed: Boolean = false) {
        if (!issuing) return
        issuing = false
        when {
            failed || status == null -> stopped = true
            status == 0 -> {
                commandSent = true
                uncertainDispatch = false
                awaitingCallback = true
            }
            status == 201 -> uncertainDispatch = false
            else -> {
                uncertainDispatch = false
                stopped = true
            }
        }
    }

    fun recordWriteCallback(status: Int, frameComplete: Boolean): Boolean {
        if (stopped || !awaitingCallback) return false
        awaitingCallback = false
        if (status != 0) {
            stopped = true
            return false
        }
        nextOffset += pendingLength
        pendingLength = 0
        commandFrameComplete = frameComplete
        return true
    }

    fun recordReply(reply: TeslaFrunkReply) {
        if (stopped || !commandFrameComplete) return
        when (reply) {
            TeslaFrunkReply.ACKNOWLEDGED -> commandAcknowledged = true
            TeslaFrunkReply.REJECTED -> commandRejected = true
            TeslaFrunkReply.UNCONFIRMED -> Unit
        }
    }

    fun observeBody(status: TeslaReadOnlyStatus) {
        if (stopped || !commandFrameComplete || commandRejected) return
        // UNKNOWN(3), AJAR, OPENING, 부재는 CLOSED나 OPEN으로 보정하지 않는다.
        openObserved = status.frontTrunkState == 1
    }

    fun cancel() { stopped = true }

    companion object {
        const val CONSENT_MS = 40_000L
        const val PARK_RECEIPT_MS = 5_000L
        const val PARK_SOURCE_MS = 10_000L
        const val PARK_SOURCE_FUTURE_SKEW_MS = 2_000L

        fun consentReason(commandAcceptedAtElapsedMs: Long, now: Long): String? = when {
            commandAcceptedAtElapsedMs < 0 || commandAcceptedAtElapsedMs > Long.MAX_VALUE - CONSENT_MS ||
                now < commandAcceptedAtElapsedMs -> "FRUNK_COMMAND_INVALID"
            now - commandAcceptedAtElapsedMs >= CONSENT_MS -> "FRUNK_COMMAND_EXPIRED"
            else -> null
        }
    }
}
