package com.heytesla.app.vehicle.key

import com.heytesla.app.diagnostics.TeslaApproachReadSummary
import com.heytesla.app.vehicle.ble.TeslaGattWriteCompletion
import com.heytesla.app.vehicle.ble.TeslaGattWritePump
import com.heytesla.app.vehicle.ble.TeslaNativeGattOwner
import com.heytesla.app.vehicle.ble.TeslaNativeGattRequestKind
import com.heytesla.app.vehicle.ble.completedNativeWriteObservation
import com.heytesla.app.vehicle.ble.sealedWriteObservation
import com.heytesla.app.vehicle.frunk.TeslaFrunkPolicy
import com.heytesla.app.vehicle.frunk.TeslaFrunkResult
import com.heytesla.app.vehicle.frunk.TeslaFrunkSequence
import com.heytesla.app.vehicle.protocol.TeslaBleAuthEvidence
import com.heytesla.app.vehicle.protocol.TeslaBleConversation
import com.heytesla.app.vehicle.protocol.TeslaBleFrameDecoder
import com.heytesla.app.vehicle.protocol.TeslaBlePhase
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.heytesla.app.vehicle.protocol.TeslaBleResponseObservation
import com.heytesla.app.vehicle.protocol.TeslaBleStep
import com.heytesla.app.vehicle.protocol.TeslaReadOnlyStatus
import com.heytesla.app.vehicle.protocol.TeslaWakeReply
import java.util.ArrayDeque
import com.heytesla.app.vehicle.connection.TeslaGattDeadlineCause
import com.heytesla.app.vehicle.connection.TeslaGattEvent
import com.heytesla.app.vehicle.connection.TeslaGattObservation
import com.heytesla.app.vehicle.connection.TeslaParkMonitor
import com.heytesla.app.vehicle.connection.TeslaConnectionPreparationPhase
import com.heytesla.app.vehicle.connection.TeslaConnectionWakePolicy
import com.heytesla.app.vehicle.protocol.TeslaDriveState
import com.heytesla.app.vehicle.protocol.TeslaGear

enum class TeslaKeyStage {
    IDLE, PREPARING_KEY, CONNECTING, DISCOVERING, NEGOTIATING_MTU, SUBSCRIBING,
    WAITING_FOR_CARD, HANDSHAKING, READING_STATUS, CLEANING_UP,
    COMPLETE, BLOCKED, CANCELED, TIMED_OUT, FAILED, CLEANUP_FAILED,
}

enum class TeslaKeyOutcome { NOT_SENT, PENDING, CANCELED, FAILED, UNKNOWN, VERIFIED_STATUS }

/** 식별자·키·원시 응답은 포함하지 않는 RAM 전용 화면 상태. */
internal data class TeslaKeyProbeState(
    val stage: TeslaKeyStage = TeslaKeyStage.IDLE,
    val outcome: TeslaKeyOutcome = TeslaKeyOutcome.NOT_SENT,
    val phase: TeslaBlePhase? = null,
    val registrationRequested: Boolean = false,
    val registrationReported: Boolean = false,
    val registrationUncertain: Boolean = false,
    val transmissionAttempted: Boolean = false,
    val credentialReady: Boolean = false,
    val subscriptionConfirmed: Boolean = false,
    val localClosed: Boolean = false,
    val status: TeslaReadOnlyStatus? = null,
    val reason: String? = null,
    val query: TeslaBleQuery = TeslaBleQuery.BODY_STATUS,
    val frunkCommandRequested: Boolean = false,
    val frunkCommandSent: Boolean = false,
    val frunkCommandAcknowledged: Boolean = false,
    val frunkOpenObserved: Boolean = false,
    /** 이번 회차에서 차량 P 상태가 신선한 것으로 확인됨. 전송 접수와 다른 사건이다. */
    val frunkFreshParkVerified: Boolean = false,
) {
    val active: Boolean get() = stage.ordinal in TeslaKeyStage.PREPARING_KEY.ordinal..TeslaKeyStage.CLEANING_UP.ordinal
}

/**
 * Android와 host가 함께 사용하는 실제 framed-byte consumer. 같은 직렬 실행자에서 호출한다.
 * post는 inline 실행하지 않는다. Transport는 기존 TeslaNativeGattOwner의 Request를 그대로
 * 전달하며 SDK 접수(writeResult)와 callback 완료(completeWrite)를 구분한다.
 * notify는 전달한 배열을 소유하여 소거한다. close의 Boolean은 cleanup 실패이며 lease 해제 허가가 아니다.
 */
internal class TeslaBleKeySession(
    var deadline: Long,
    private val register: Boolean,
    private val query: TeslaBleQuery,
    private var frunk: TeslaFrunkPolicy? = null,
    private val serviceConnection: Boolean = false,
    private val wallNow: () -> Long = System::currentTimeMillis,
    private val now: () -> Long,
    private val post: (Runnable) -> Boolean,
    private val postDelayed: (Runnable, Long) -> Boolean,
    private val remove: (Runnable) -> Unit,
    private val listener: Listener,
    private val transport: () -> Transport?,
) {
    interface Listener {
        var state: TeslaKeyProbeState
        fun allowed(): Boolean
        fun onStop(stage: TeslaKeyStage, reason: String?)
        fun onEvent(event: String) {}
        fun onStage() {}
        fun onGatt(event: TeslaGattEvent) {}
        fun onFrunk() {}
        fun onNotification(receivedAtElapsedMs: Long) {}
        fun onParkState(drive: TeslaDriveState?, reason: String?) {}
        fun onExecuting() {}
        fun onStopping(reason: String) {}
    }

    interface Transport {
        val canWrite: Boolean
        val requestPending: Boolean
        val txProperties: Int
        /** bytes는 완료까지 빌린 배열이다. 요청 발급 직후 writeIssued, SDK 반환 직후 writeResult를 호출한다. */
        fun submit(bytes: ByteArray)
        fun closeConfirmed(): Boolean
    }

    private data class ReceivedFrame(val payload: ByteArray, val receivedAtElapsedMs: Long)
    var conversation: TeslaBleConversation? = null
    var frunkSequence: TeslaFrunkSequence? = null
    var gattObservation: TeslaGattObservation? = null
    var pendingRxResponse: TeslaBleResponseObservation? = null
    var stageDeadline = deadline
        private set
    private var stageDeadlineCause = TeslaGattDeadlineCause.PROTOCOL_STAGE
    var writeDeadline = Long.MAX_VALUE
        private set
    var sealed = false
        private set
    var registrationAttempted = false
        private set
    var mtu = 23
        private set
    var lastWrite: TeslaApproachReadSummary? = null
        private set
    var queuedWrite: TeslaApproachReadSummary? = null
        private set
    private var writeDispatchOwner: Transport? = null
    private var pendingRequest: TeslaNativeGattOwner.Request? = null
    private var sdkRequest: TeslaNativeGattOwner.Request? = null
    private var pendingWriteObservation: TeslaApproachReadSummary? = null
    private var chunk: ByteArray? = null
    private var frame: ByteArray? = null
    private var offset = 0
    private var frunkFrame = false
    private var wakeFrame = false
    private val decoder = TeslaBleFrameDecoder()
    private val incoming = ArrayDeque<ReceivedFrame>()
    private var queuedBytes = 0
    private var watchdog: Runnable? = null
    private var terminationStarted = false
    private var closeStarted = false
    private var closeFailed = false
    private var closeResult: Boolean? = null
    val writePump = TeslaGattWritePump(
        post = { check(post(it)) }, postDelayed = postDelayed, now = now, remove = remove,
        allowed = { allowed() && transport() === writeDispatchOwner &&
            transport()?.canWrite == true && frame != null },
        onQueued = { recordQueuedWrite() },
        submit = {
            try { writeNext() }
            catch (_: Exception) { stop(TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED") }
        },
    )

    val frunkPolicy: TeslaFrunkPolicy? get() = frunk
    private val park = if (serviceConnection) TeslaParkMonitor(now, wallNow) else null
    private val wakePolicy = if (serviceConnection) TeslaConnectionWakePolicy(deadline, now) else null
    private var preparation = TeslaConnectionPreparationPhase.BODY
    val preparationPhase: TeslaConnectionPreparationPhase get() = preparation
    private var bodyFactory: (() -> TeslaBleConversation)? = null
    private var wakeFactory: ((authorize: () -> String?, deadline: Long) -> TeslaBleConversation)? = null
    private var driveFactory: ((sessionRetransmit: Boolean) -> TeslaBleConversation)? = null
    private var frunkFactory: ((TeslaFrunkPolicy) -> TeslaFrunkSequence)? = null
    private var queryOutstanding = false
    private var commandClaimed = false
    private var connectionStarted = false
    private var wakeCommandCreated = false
    // 단발 깨움 ACK 시 한 번 고정한다. 재조회·수신·source timestamp로 연장하지 않는다.
    private var initialDriveDeadline = Long.MAX_VALUE
    private var initialDriveLastElapsed = 0L
    private var initialDriveRetries = 0
    private var initialDriveRetryPending = false
    private var initialParkAccepted = false
    private val connectionTask = Runnable {
        try {
            if (allowed() && !queryOutstanding && frame == null && writePump.idle) {
                if (initialDriveRetryPending) {
                    initialDriveRetryPending = false
                    initialDriveRetries++
                    listener.onEvent("TESLA_CONNECTION_DRIVE_RETRY")
                    if (allowed()) startConnectionQuery() // 새 인증·새 identity, HELLO 재송신 창은 재무장하지 않는다.
                } else if (commandClaimed) beginServiceFrunk() else startConnectionQuery()
            }
        } catch (_: Exception) { stop(TeslaKeyStage.FAILED, "CONNECTION_PROTOCOL_START_FAILED") }
    }
    private var sessionRetransmits = 0
    private val sessionRetransmitTask = Runnable {
        try { retransmitSessionRequest() } catch (_: Exception) { stop(TeslaKeyStage.FAILED, "CONNECTION_PROTOCOL_START_FAILED") }
    }

    /** Credential factories remain bound to this operation; no second transport is allocated. */
    fun configureConnection(
        body: () -> TeslaBleConversation,
        wake: (authorize: () -> String?, deadline: Long) -> TeslaBleConversation,
        drive: (sessionRetransmit: Boolean) -> TeslaBleConversation,
        command: (TeslaFrunkPolicy) -> TeslaFrunkSequence,
    ) {
        check(serviceConnection && !register && frunk == null && driveFactory == null && !sealed)
        bodyFactory = body
        wakeFactory = wake
        driveFactory = drive
        frunkFactory = command
    }

    fun dispatchServiceFrunk(policy: TeslaFrunkPolicy): Boolean {
        if (!serviceConnection || commandClaimed || !allowed() || park?.observed == null ||
            park.readinessReason() != null || frunkFactory == null || transport()?.canWrite != true &&
            transport()?.requestPending != true) return false
        policy.readinessReason()?.let { return false }
        commandClaimed = true // Claim before any callback can reenter.
        frunk = policy
        deadline = minOf(deadline, policy.deadline)
        return try {
            remove(connectionTask)
            remove(sessionRetransmitTask)
            listener.state = listener.state.copy(frunkCommandRequested = true)
            listener.onExecuting()
            if (!allowed()) false
            else {
                if (!queryOutstanding) scheduleConnection(0L)
                !sealed
            }
        } catch (_: Exception) {
            stop(TeslaKeyStage.FAILED, "CONNECTION_PROTOCOL_START_FAILED")
            false
        }
    }

    private fun scheduleConnection(delay: Long) {
        val posted = try { postDelayed(connectionTask, delay) } catch (_: Exception) { false }
        if (!posted) stop(TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED")
    }

    private fun startConnectionPreparation() {
        check(!connectionStarted)
        connectionStarted = true
        startConnectionConversation(TeslaConnectionPreparationPhase.BODY)
    }

    private fun startConnectionQuery(afterWake: Boolean = false) =
        startConnectionConversation(TeslaConnectionPreparationPhase.DRIVE, afterWake)

    private fun startConnectionConversation(phase: TeslaConnectionPreparationPhase, afterWake: Boolean = false) {
        if (!allowed() || queryOutstanding || commandClaimed) return
        check(frame == null && writePump.idle && conversation == null)
        clearIncoming()
        decoder.clear()
        preparation = phase
        queryOutstanding = true
        listener.state = listener.state.copy(
            query = if (phase == TeslaConnectionPreparationPhase.DRIVE) TeslaBleQuery.DRIVE_STATE else TeslaBleQuery.BODY_STATUS,
            phase = null, reason = null,
        )
        conversation = when (phase) {
            TeslaConnectionPreparationPhase.BODY -> {
                val factory = checkNotNull(bodyFactory)
                bodyFactory = null
                factory()
            }
            TeslaConnectionPreparationPhase.WAKE -> {
                val factory = checkNotNull(wakeFactory)
                wakeFactory = null // Consume before callbacks or construction can reenter.
                authorizeConnectionWake()?.let { stop(TeslaKeyStage.CANCELED, it); return }
                factory(::authorizeConnectionWake, checkNotNull(checkNotNull(wakePolicy).wakeDeadline))
            }
            TeslaConnectionPreparationPhase.DRIVE -> checkNotNull(driveFactory).invoke(afterWake)
        }
        if (phase == TeslaConnectionPreparationPhase.WAKE) {
            listener.onEvent("TESLA_CONNECTION_WAKE_STARTED")
            if (!allowed()) return
        }
        step(checkNotNull(conversation).start(false))
        if (afterWake && phase == TeslaConnectionPreparationPhase.DRIVE && !sealed && queryOutstanding) {
            // wake ACK 뒤 infotainment 기동 중 첫 HELLO가 유실될 수 있다. 같은 요청 재송신만 늘리고
            // wake 재송신·P 추정은 없다. HANDSHAKING 진입 시 설정된 10초 기한을 이 창으로 대체한다.
            setStageDeadline(minOf(deadline, now() + SESSION_RETRANSMIT_WINDOW_MS))
            sessionRetransmits = 0
            scheduleSessionRetransmit()
        }
    }

    private fun scheduleSessionRetransmit() {
        val posted = try { postDelayed(sessionRetransmitTask, SESSION_RETRANSMIT_INTERVAL_MS) } catch (_: Exception) { false }
        if (!posted) stop(TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED")
    }

    private fun retransmitSessionRequest() {
        if (!allowed() || preparation != TeslaConnectionPreparationPhase.DRIVE || !queryOutstanding ||
            frunkSequence != null || commandClaimed || listener.state.stage != TeslaKeyStage.HANDSHAKING) return
        val current = conversation ?: return
        if (sessionRetransmits >= MAX_SESSION_RETRANSMITS) return
        if (frame != null || !writePump.idle) {
            scheduleSessionRetransmit() // 직전 쓰기 완료 전에는 이번 회차를 미룬다.
            return
        }
        sessionRetransmits++
        listener.onEvent("TESLA_CONNECTION_SESSION_RETRANSMIT")
        if (!allowed()) return
        val next = current.retransmitSessionRequest()
        if (next == TeslaBleStep.Ignored) return
        step(next)
        if (!sealed && sessionRetransmits < MAX_SESSION_RETRANSMITS) scheduleSessionRetransmit()
    }

    private fun retireConnectionConversation() {
        try { remove(sessionRetransmitTask) } catch (_: Exception) { closeFailed = true }
        queryOutstanding = false
        conversation?.close()
        conversation = null
    }

    private fun completeConnectionBody(status: TeslaReadOnlyStatus) {
        val policy = checkNotNull(wakePolicy)
        policy.acceptBody(status)?.let { stop(TeslaKeyStage.FAILED, it); return }
        retireConnectionConversation()
        listener.state = listener.state.copy(status = status, outcome = TeslaKeyOutcome.PENDING)
        listener.onEvent("TESLA_CONNECTION_BODY_VERIFIED")
        if (!allowed()) return
        if (policy.wakeRequired) {
            startConnectionConversation(TeslaConnectionPreparationPhase.WAKE)
        } else {
            listener.onEvent(if (policy.awake) "TESLA_CONNECTION_WAKE_SKIPPED_AWAKE" else "TESLA_CONNECTION_WAKE_SKIPPED_UNKNOWN")
            if (!allowed()) return
            startConnectionQuery()
        }
    }

    private fun completeConnectionWake(reply: TeslaWakeReply) {
        if (!serviceConnection || preparation != TeslaConnectionPreparationPhase.WAKE ||
            !queryOutstanding || conversation == null || frunkSequence != null || !wakeCommandCreated) {
            stop(TeslaKeyStage.FAILED, if (serviceConnection) "CONNECTION_WAKE_RESULT_UNEXPECTED" else "WAKE_SEQUENCE_REQUIRED")
            return
        }
        retireConnectionConversation()
        when (reply) {
            TeslaWakeReply.ACKNOWLEDGED -> {
                beginInitialDriveWindow()
                listener.onEvent("TESLA_CONNECTION_WAKE_ACKNOWLEDGED")
                if (!allowed()) return
                startConnectionQuery(afterWake = true) // ACK is neither P nor proof that the vehicle physically woke.
            }
            TeslaWakeReply.NOMINAL_ACK -> {
                beginInitialDriveWindow()
                listener.onEvent("TESLA_CONNECTION_WAKE_NOMINAL_ACK")
                if (!allowed()) return
                // An unsigned nominal ACK permits only a new authenticated read, never P or control.
                startConnectionQuery(afterWake = true)
            }
            TeslaWakeReply.REJECTED -> {
                listener.onEvent("TESLA_CONNECTION_WAKE_REJECTED")
                stop(TeslaKeyStage.FAILED, "CONNECTION_WAKE_REJECTED")
            }
            TeslaWakeReply.UNCONFIRMED -> {
                listener.onEvent("TESLA_CONNECTION_WAKE_UNCONFIRMED")
                stop(TeslaKeyStage.FAILED, "CONNECTION_WAKE_UNCONFIRMED")
            }
        }
    }

    private fun beginInitialDriveWindow() {
        if (initialDriveDeadline != Long.MAX_VALUE || initialParkAccepted) return
        val current = now()
        initialDriveLastElapsed = current
        initialDriveDeadline = minOf(deadline, current + INITIAL_DRIVE_READY_MS)
    }

    private fun authorizeConnectionWake(): String? {
        if (!allowed()) return listener.state.reason ?: "CONNECTION_OWNER_UNAVAILABLE"
        if (!serviceConnection || preparation != TeslaConnectionPreparationPhase.WAKE ||
            !queryOutstanding || commandClaimed || frunkSequence != null) return "CONNECTION_WAKE_FRAME_UNEXPECTED"
        return checkNotNull(wakePolicy).authorizationReason()
    }

    private fun completeConnectionQuery(status: TeslaReadOnlyStatus) {
        if (!queryOutstanding || conversation == null) {
            stop(TeslaKeyStage.FAILED, "CONNECTION_QUERY_RESULT_UNEXPECTED")
            return
        }
        when (preparation) {
            TeslaConnectionPreparationPhase.BODY -> { completeConnectionBody(status); return }
            TeslaConnectionPreparationPhase.WAKE -> {
                stop(TeslaKeyStage.FAILED, "CONNECTION_QUERY_RESULT_UNEXPECTED")
                return
            }
            TeslaConnectionPreparationPhase.DRIVE -> Unit
        }
        if (status.driveState == null || status.bodyReceivedAtElapsedRealtime != null) {
            stop(TeslaKeyStage.FAILED, "CONNECTION_DRIVE_RESPONSE_REQUIRED")
            return
        }
        retireConnectionConversation()
        if (initialDriveDeadline != Long.MAX_VALUE && !initialParkAccepted && !commandClaimed &&
            status.driveShiftStateInvalid && status.driveState.gear == TeslaGear.UNKNOWN) {
            if (initialDriveRetries >= MAX_INITIAL_DRIVE_RETRIES) {
                stop(TeslaKeyStage.CANCELED, "CONNECTION_EXPLICIT_P_REQUIRED")
                return
            }
            // INVALID는 P·음성 준비가 아니다. 완료된 읽기 기한은 폐기하되 원 준비창/global은 보존한다.
            setStageDeadline(deadline)
            initialDriveRetryPending = true
            listener.state = listener.state.copy(status = status, outcome = TeslaKeyOutcome.PENDING)
            scheduleConnection(TeslaParkMonitor.POLL_MS)
            return
        }
        val reason = checkNotNull(park).accept(status.driveState)
        if (reason != null) {
            // Retain the actual non-P gear, but seal before notifying the microphone owner.
            listener.state = listener.state.copy(status = status)
            stop(TeslaKeyStage.CANCELED, reason, drive = status.driveState)
            return
        }
        initialParkAccepted = true
        initialDriveDeadline = Long.MAX_VALUE
        if (!commandClaimed) deadline = Long.MAX_VALUE
        setStageDeadline(deadline)
        listener.state = listener.state.copy(status = status, outcome = TeslaKeyOutcome.VERIFIED_STATUS)
        listener.onParkState(status.driveState, null)
        if (!allowed()) return
        remove(connectionTask)
        scheduleConnection(if (commandClaimed) 0L else TeslaParkMonitor.POLL_MS)
    }

    private fun beginServiceFrunk() {
        if (!allowed() || queryOutstanding || frunkSequence != null) return
        check(commandClaimed && frame == null && writePump.idle)
        clearIncoming()
        decoder.clear()
        val factory = checkNotNull(frunkFactory)
        bodyFactory = null
        wakeFactory = null
        driveFactory = null
        frunkFactory = null
        frunkSequence = factory(checkNotNull(frunk))
        listener.state = listener.state.copy(query = TeslaBleQuery.DRIVE_STATE, phase = null)
        step(checkNotNull(frunkSequence).start())
    }

    /** 준비/연결을 포함한 동일 elapsed deadline watchdog. Handler uptime 지연과 시계를 합치지 않는다. */
    fun watch() {
        if (watchdog != null || sealed) return
        val task = object : Runnable {
            override fun run() {
                if (sealed) return
                if (allowed() && !postWatchdog(this)) {
                    stop(TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED")
                }
            }
        }
        watchdog = task
        if (!postWatchdog(task)) stop(TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED")
    }

    private fun postWatchdog(task: Runnable): Boolean =
        try { postDelayed(task, WATCHDOG_MS) } catch (_: Exception) { false }

    fun checkReadiness(): Boolean = allowed()

    /** Facade의 native callback 입장에서도 동일 protocol/write 기한을 검사한다. */
    fun checkDeadlines(): Boolean {
        if (sealed) return false
        val current = now()
        val readyDeadline = initialDriveDeadline
        if (readyDeadline != Long.MAX_VALUE) {
            if (current < initialDriveLastElapsed) {
                stop(TeslaKeyStage.CANCELED, "CONNECTION_TIME_REVERSED")
                return false
            }
            initialDriveLastElapsed = current
            // 먼저 만료한 원 global/protocol/write의 원인·관측을 준비창으로 덮어쓰지 않는다.
            val originalDeadline = minOf(deadline, stageDeadline, writeDeadline)
            if (current >= originalDeadline && originalDeadline <= readyDeadline) {
                expireDeadline(checkNotNull(teslaGattDeadlineCause(
                    current, deadline, stageDeadline, writeDeadline, stageDeadlineCause)))
                return false
            }
            if (current >= readyDeadline) {
                stop(TeslaKeyStage.CANCELED, "CONNECTION_EXPLICIT_P_REQUIRED")
                return false
            }
        }
        if (frunkSequence == null) park?.observed?.let {
            park.readinessReason()?.let { reason -> stop(TeslaKeyStage.CANCELED, reason); return false }
        }
        frunk?.readinessReason()?.let { stop(TeslaKeyStage.CANCELED, it); return false }
        if (now() < deadline && now() >= stageDeadline && frunkSequence?.awaitingActionReply == true &&
            frame == null && !writePump.writePending && !writePump.queued) {
            setStageDeadline(deadline)
            step(checkNotNull(frunkSequence).observeAfterMissingReply())
            return !sealed
        }
        val cause = teslaGattDeadlineCause(now(), deadline, stageDeadline, writeDeadline, stageDeadlineCause)
        if (cause != null) {
            expireDeadline(cause)
            return false
        }
        return true
    }

    private fun allowed(): Boolean {
        if (sealed) return false
        if (!listener.allowed()) {
            stop(TeslaKeyStage.CANCELED, "CONNECTION_OWNER_UNAVAILABLE")
            return false
        }
        return checkDeadlines()
    }

    /** 원인은 화면 단계가 아니라 이 기한을 실제 설정한 경계에 속한다. */
    fun setStageDeadline(value: Long, cause: TeslaGattDeadlineCause = TeslaGattDeadlineCause.PROTOCOL_STAGE) {
        stageDeadline = value
        stageDeadlineCause = cause
    }

    fun stage(next: TeslaKeyStage, limit: Long) {
        if (listener.state.stage == next) return
        setStageDeadline(minOf(deadline, now() + limit))
        listener.state = listener.state.copy(stage = next)
        listener.onStage()
    }

    fun negotiatedMtu(value: Int) { require(value in 23..517); mtu = value }

    /** 실제 CCCD 성공 뒤에만 facade가 호출한다. host도 같은 시작 경계를 사용한다. */
    fun start() {
        if (!allowed()) return
        try {
            val first = when {
                serviceConnection -> { startConnectionPreparation(); return }
                frunkSequence != null -> checkNotNull(frunkSequence).start()
                else -> checkNotNull(conversation).start(register)
            }
            step(first)
        } catch (_: Exception) { stop(TeslaKeyStage.FAILED, "PROTOCOL_START_FAILED") }
    }

    /** null은 adapter의 bounded notification 복사 거절. 마지막 fragment의 callback 진입 시각을 보존한다. */
    fun notify(bytes: ByteArray?, receivedAtElapsedMs: Long) {
        try {
            if (!allowed()) return
            listener.onNotification(receivedAtElapsedMs)
            gattObservation = gattObservation?.withDecoderResult(null, null, null)
            if (!allowed()) return
            if (bytes == null || bytes.size > MAX_QUEUED_BYTES) {
                listener.onGatt(TeslaGattEvent.RX)
                stop(TeslaKeyStage.FAILED, "RX_FRAME_INVALID")
                return
            }
            val frames = try { decoder.append(bytes) }
            catch (failure: Exception) {
                gattObservation = gattObservation?.withDecoderResult(null, decoder.hasIncompleteFrame(), true)
                listener.onGatt(TeslaGattEvent.RX)
                throw failure
            }
            gattObservation = gattObservation?.withDecoderResult(frames.size, decoder.hasIncompleteFrame(), false)
            var nextFrame = 0
            try {
                listener.onGatt(TeslaGattEvent.RX)
                if (!allowed()) return
                while (nextFrame < frames.size) {
                    val payload = frames[nextFrame]
                    if (!allowed()) return
                    if (incoming.size >= MAX_QUEUED_FRAMES || queuedBytes + payload.size > MAX_QUEUED_BYTES) {
                        stop(TeslaKeyStage.FAILED, "RX_BUFFER_LIMIT")
                        return
                    }
                    incoming.addLast(ReceivedFrame(payload, receivedAtElapsedMs))
                    queuedBytes += payload.size
                    nextFrame++
                }
            } finally {
                for (remaining in nextFrame until frames.size) frames[remaining].fill(0)
            }
            if (frame == null && !writePump.writePending && !writePump.queued && !writePump.descriptorPending) drain()
        } catch (_: Exception) { stop(TeslaKeyStage.FAILED, "RX_FRAME_INVALID") }
        finally { bytes?.fill(0) }
    }

    fun ownsSubmittingChunk(bytes: ByteArray): Boolean = !sealed && writePump.submitting && chunk === bytes &&
        transport() === writeDispatchOwner

    /** 기존 owner가 발급한 객체 자체가 callback identity다. 새 토큰 체계를 만들지 않는다. */
    fun writeIssued(request: TeslaNativeGattOwner.Request): Boolean {
        if (!allowed() || request.kind != TeslaNativeGattRequestKind.WRITE) return false
        if (wakeFrame) authorizeConnectionWake()?.let {
            stop(TeslaKeyStage.CANCELED, it)
            return false
        }
        if (frunkFrame) checkNotNull(frunk).authorizeActuator()?.let {
            stop(TeslaKeyStage.CANCELED, it)
            return false
        }
        if (!writePump.markWriteIssued()) return false
        val prepared = queuedWrite ?: return false
        pendingRequest = request
        sdkRequest = request
        lastWrite = prepared.copy(writeDispatchQueued = false, writeDispatchElapsedMs = request.issuedElapsedMs,
            writeBusyRetryCount = writePump.busyRetryCount)
        queuedWrite = null
        return true
    }

    /** SDK 반환만 기록한다. status 0은 frame 완료나 backend 시작이 아니다. */
    fun writeResult(request: TeslaNativeGattOwner.Request, result: Int?, failure: Exception?,
        exceptionCode: String? = null, returnedElapsedMs: Long = now()) {
        if (sdkRequest !== request) return
        sdkRequest = null
        if (frunkFrame) {
            checkNotNull(frunk).recordSdkResult(result, failure != null)
            listener.onFrunk()
        }
        if (sealed || pendingRequest !== request) return
        val observed = checkNotNull(lastWrite).copy(writeReturnedElapsedMs = returnedElapsedMs,
            writeRequestStatus = result, writeException = exceptionCode)
        lastWrite = observed
        pendingWriteObservation = observed.takeIf { result == 0 && failure == null }
        writePump.recordWriteResult(result, failure)
        if (result != BUSY_STATUS) {
            if (listener.state.phase == TeslaBlePhase.WAITING_FOR_CARD) registrationAttempted = true
            if (!listener.state.transmissionAttempted) {
                listener.state = listener.state.copy(transmissionAttempted = true)
                listener.onEvent("TESLA_KEY_TX_ATTEMPTED")
            }
        } else pendingRequest = null
        listener.onGatt(TeslaGattEvent.WRITE)
        if (frunkFrame && (failure != null || result != 0 && result != BUSY_STATUS)) {
            stop(TeslaKeyStage.FAILED, if (failure != null) "GATT_WRITE_EXCEPTION" else "GATT_WRITE_REJECTED")
            return
        }
        if (!allowed()) return
        if (result == BUSY_STATUS) {
            if (writePump.retryBusy(minOf(deadline, stageDeadline, writeDeadline))) return
        }
        if (failure != null || result != 0) {
            stop(TeslaKeyStage.FAILED, if (failure != null) "GATT_WRITE_EXCEPTION" else "GATT_WRITE_REJECTED")
        }
    }

    /** owner.completeRequest를 통과한 callback만 전달한다. captured Request는 native 진입에서 캡처한다. */
    fun completeWrite(request: TeslaNativeGattOwner.Request?, status: Int, completedElapsedMs: Long) {
        if (sealed || request == null || pendingRequest !== request) return
        val write = pendingWriteObservation ?: return
        val observed = completedNativeWriteObservation(lastWrite, write, status, completedElapsedMs) ?: return
        val completion = writePump.completeWrite(status)
        if (completion == TeslaGattWriteCompletion.IGNORED) return
        pendingRequest = null
        pendingWriteObservation = null
        lastWrite = observed
        if (frunkFrame) checkNotNull(frunk).recordWriteCallback(status,
            frameComplete = offset + (chunk?.size ?: 0) == frame?.size)
        if (frunkFrame && completion == TeslaGattWriteCompletion.FAILED) {
            stop(TeslaKeyStage.FAILED, "GATT_WRITE_FAILED")
            return
        }
        listener.onGatt(TeslaGattEvent.WRITE)
        if (!allowed()) return
        writeDeadline = Long.MAX_VALUE
        val completedChunk = chunk ?: run { stop(TeslaKeyStage.FAILED, "TX_FRAME_INVALID"); return }
        val size = completedChunk.size
        completedChunk.fill(0)
        chunk = null
        if (completion == TeslaGattWriteCompletion.FAILED) { stop(TeslaKeyStage.FAILED, "GATT_WRITE_FAILED"); return }
        offset += size
        if (offset == frame?.size) {
            frame?.fill(0)
            frame = null
            offset = 0
            frunkFrame = false
            wakeFrame = false
        }
        if (frame != null) queueWrite() else {
            drain()
        }
    }

    private fun protocolPhase(phase: TeslaBlePhase) {
        if (sealed) return
        val next = when (phase) {
            TeslaBlePhase.WAITING_FOR_CARD -> TeslaKeyStage.WAITING_FOR_CARD
            TeslaBlePhase.HANDSHAKING -> TeslaKeyStage.HANDSHAKING
            TeslaBlePhase.READING_STATUS -> TeslaKeyStage.READING_STATUS
        }
        stage(next, if (phase == TeslaBlePhase.WAITING_FOR_CARD) CARD_MS else PROTOCOL_MS)
        if (!sealed) listener.state = listener.state.copy(phase = phase, outcome = TeslaKeyOutcome.PENDING)
    }

    private fun step(next: TeslaBleStep) {
        // 선행 수신분은 등록 보고·readiness callback도 새로 만들지 않는다.
        if (next != TeslaBleStep.Ignored) {
            try {
                if (!allowed()) { discardUnconsumedTeslaBleStep(next); return }
                val reported = conversation?.registrationReported == true
                if (reported && !listener.state.registrationReported) listener.onEvent("TESLA_KEY_REGISTRATION_REPORTED")
                if (!allowed()) { discardUnconsumedTeslaBleStep(next); return }
                listener.state = listener.state.copy(registrationReported = reported)
            } catch (failure: Exception) {
                discardUnconsumedTeslaBleStep(next)
                throw failure
            }
        }
        when (next) {
            TeslaBleStep.Ignored -> Unit
            is TeslaBleStep.Send -> {
                try {
                    listener.state = listener.state.copy(reason = null)
                    protocolPhase(next.phase)
                    if (!allowed()) { next.payload.fill(0); return }
                } catch (failure: Exception) {
                    next.payload.fill(0)
                    throw failure
                }
                if (next.actuator && next.wake ||
                    next.wake && (!serviceConnection || preparation != TeslaConnectionPreparationPhase.WAKE ||
                        !queryOutstanding || frunkSequence != null || wakeCommandCreated ||
                        next.phase != TeslaBlePhase.READING_STATUS)) {
                    next.payload.fill(0)
                    stop(TeslaKeyStage.FAILED, "CONNECTION_WAKE_FRAME_UNEXPECTED")
                    return
                }
                if (serviceConnection && frunkSequence == null && preparation == TeslaConnectionPreparationPhase.WAKE &&
                    !next.wake && next.phase != TeslaBlePhase.HANDSHAKING) {
                    next.payload.fill(0)
                    stop(TeslaKeyStage.FAILED, "CONNECTION_WAKE_FRAME_REQUIRED")
                    return
                }
                if (next.wake) {
                    val reason = try { authorizeConnectionWake() } catch (failure: Exception) {
                        next.payload.fill(0)
                        throw failure
                    }
                    if (reason != null) {
                        next.payload.fill(0)
                        stop(TeslaKeyStage.CANCELED, reason)
                        return
                    }
                    wakeCommandCreated = true
                }
                frunkFrame = next.actuator
                wakeFrame = next.wake
                if (frame != null || writePump.writePending || writePump.queued || writePump.descriptorPending ||
                    next.payload.isEmpty() || next.payload.size > MAX_TX_PAYLOAD) {
                    next.payload.fill(0)
                    stop(TeslaKeyStage.FAILED, "TX_FRAME_INVALID")
                    return
                }
                sendFrame(next.payload)
            }
            is TeslaBleStep.Waiting -> {
                next.authEvidence?.let { listener.onEvent(it.eventCode) }
                if (!allowed()) return
                protocolPhase(next.phase)
                if (next.authEvidence != null && next.authEvidence != TeslaBleAuthEvidence.ACKNOWLEDGED) {
                    listener.state = listener.state.copy(
                        reason = "검증되지 않은 인증 응답 제외 · 정상 응답 대기 · ${next.authEvidence.eventCode}")
                }
            }
            is TeslaBleStep.Complete -> {
                if (frunkSequence != null) {
                    listener.onFrunk()
                    if (!allowed()) return
                    listener.state = listener.state.copy(status = next.status)
                    val verifiedOpen = checkNotNull(frunk).result == TeslaFrunkResult.OPEN_OBSERVED
                    if (verifiedOpen) listener.state = listener.state.copy(outcome = TeslaKeyOutcome.VERIFIED_STATUS)
                    stop(if (verifiedOpen) TeslaKeyStage.COMPLETE else TeslaKeyStage.FAILED, when {
                        verifiedOpen -> null
                        frunkSequence?.responseUnknown == true -> "FRUNK_ACTION_RESPONSE_UNKNOWN"
                        else -> "FRUNK_OPEN_NOT_OBSERVED"
                    })
                    return
                }
                if (serviceConnection) {
                    completeConnectionQuery(next.status)
                    return
                }
                listener.state = listener.state.copy(outcome = TeslaKeyOutcome.VERIFIED_STATUS, status = next.status)
                listener.onEvent(query.verifiedEventCode)
                stop(TeslaKeyStage.COMPLETE, null)
            }
            is TeslaBleStep.FrunkResult -> stop(TeslaKeyStage.FAILED, "FRUNK_SEQUENCE_REQUIRED")
            is TeslaBleStep.WakeResult -> completeConnectionWake(next.reply)
            is TeslaBleStep.Failed -> {
                next.authEvidence?.let { listener.onEvent(it.eventCode) }
                val reason = if (frunk != null) {
                    next.reason.takeIf { it.startsWith("FRUNK_") } ?: "FRUNK_PROTOCOL_RESPONSE_INVALID"
                } else if (serviceConnection) {
                    when (next.reason) {
                        "WAKE_EXPIRED" -> "CONNECTION_WAKE_DEADLINE_EXPIRED"
                        "WAKE_CLOCK_CHANGED" -> "CONNECTION_TIME_REVERSED"
                        "CONNECTION_BODY_RECEIPT_REQUIRED", "CONNECTION_BODY_RECEIPT_STALE",
                        "CONNECTION_ASLEEP_REQUIRED", "CONNECTION_WAKE_DEADLINE_EXPIRED",
                        "CONNECTION_TIME_REVERSED", "CONNECTION_WAKE_FRAME_UNEXPECTED" -> next.reason
                        else -> "CONNECTION_PROTOCOL_RESPONSE_INVALID"
                    }
                } else next.reason
                stop(TeslaKeyStage.FAILED, reason)
            }
        }
    }

    private fun sendFrame(payload: ByteArray) {
        if (payload.isEmpty() || payload.size > MAX_TX_PAYLOAD) {
            payload.fill(0)
            stop(TeslaKeyStage.FAILED, "TX_FRAME_INVALID")
            return
        }
        val framed = ByteArray(payload.size + 2)
        framed[0] = (payload.size ushr 8).toByte()
        framed[1] = payload.size.toByte()
        payload.copyInto(framed, 2)
        payload.fill(0)
        frame = framed
        queueWrite()
    }

    private fun queueWrite() {
        if (!allowed() || frame == null || writePump.queued || writePump.writePending || writePump.descriptorPending) return
        writeDispatchOwner = transport() ?: return
        try { writePump.queue() }
        catch (_: Exception) { stop(TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED") }
    }

    private fun recordQueuedWrite() {
        val framed = checkNotNull(frame)
        queuedWrite = TeslaApproachReadSummary(writeSequence = (lastWrite?.writeSequence ?: 0L) + 1L,
            writeType = 2, txProperties = checkNotNull(transport()).txProperties, negotiatedMtu = mtu,
            frameLength = framed.size, chunkLength = minOf(framed.size - offset, mtu - 3),
            frameOffset = offset, writePendingBefore = writePump.writePending,
            descriptorPendingBefore = writePump.descriptorPending, writeDispatchQueued = true,
            writeBusyRetryCount = writePump.busyRetryCount)
    }

    private fun writeNext() {
        if (!allowed() || !writePump.submitting || writePump.descriptorPending) return
        val framed = frame ?: return
        val owner = transport() ?: return
        if (owner !== writeDispatchOwner) return
        val bytes = chunk ?: framed.copyOfRange(offset, minOf(framed.size, offset + mtu - 3)).also { chunk = it }
        if (writeDeadline == Long.MAX_VALUE) writeDeadline = minOf(deadline, now() + WRITE_MS)
        if (!allowed() || transport() !== owner || !writePump.submitting || writePump.descriptorPending ||
            frame !== framed || chunk !== bytes) return
        if (queuedWrite == null) recordQueuedWrite()
        val prepared = checkNotNull(queuedWrite).copy(writeRequestedElapsedMs = now())
        queuedWrite = prepared
        if (!allowed() || transport() !== owner || !owner.canWrite || chunk !== bytes ||
            !writePump.submitting || queuedWrite !== prepared) return
        if (frunkFrame) checkNotNull(frunk).beginWrite(offset, bytes.size)?.let {
            stop(TeslaKeyStage.CANCELED, it)
            return
        }
        if (wakeFrame) authorizeConnectionWake()?.let {
            stop(TeslaKeyStage.CANCELED, it)
            return
        }
        try { owner.submit(bytes) }
        catch (_: Exception) { if (allowed()) stop(TeslaKeyStage.FAILED, "GATT_WRITE_EXCEPTION") }
    }

    private fun drain() {
        while (allowed() && frame == null && !writePump.writePending && !writePump.queued &&
            !writePump.descriptorPending && incoming.isNotEmpty()) {
            val received = incoming.removeFirst()
            queuedBytes -= received.payload.size
            try {
                val frunkSequence = frunkSequence
                val previousFrunkQuery = frunkSequence?.query
                val conversation = conversation
                if (frunkSequence == null && conversation == null) continue // Retired query ingress never enters a successor.
                pendingRxResponse = null
                gattObservation = gattObservation?.beginningProtocol(received.receivedAtElapsedMs)
                val next = try {
                    val result = if (frunkSequence != null) {
                        frunkSequence.receive(received.payload, received.receivedAtElapsedMs)
                    } else checkNotNull(conversation).receive(received.payload, received.receivedAtElapsedMs)
                    gattObservation = gattObservation?.withProtocolResult(now(), pendingRxResponse)
                    result
                } catch (failure: Exception) {
                    gattObservation = gattObservation?.withProtocolResult(now(), null)
                    listener.onGatt(TeslaGattEvent.PROTOCOL)
                    throw failure
                } finally { pendingRxResponse = null }
                try {
                    listener.onGatt(TeslaGattEvent.PROTOCOL)
                    if (frunkSequence != null && next != TeslaBleStep.Ignored) listener.onFrunk()
                    if (!allowed()) { discardUnconsumedTeslaBleStep(next); return }
                } catch (failure: Exception) { discardUnconsumedTeslaBleStep(next); throw failure }
                if (frunkSequence != null &&
                    (frunkSequence.query != previousFrunkQuery || next is TeslaBleStep.Send && next.phase == TeslaBlePhase.HANDSHAKING)) {
                    clearIncoming()
                    decoder.clear()
                    listener.state = listener.state.copy(query = frunkSequence.query, phase = null, reason = null)
                }
                step(next)
            } catch (_: Exception) { stop(TeslaKeyStage.FAILED, "PROTOCOL_RESPONSE_INVALID") }
            finally { received.payload.fill(0) }
        }
    }

    fun cancel(stage: TeslaKeyStage = TeslaKeyStage.CANCELED, reason: String? = "USER_STOP") = stop(stage, reason)

    fun expireDeadline(cause: TeslaGattDeadlineCause) {
        if (sealed || terminationStarted) return
        stop(TeslaKeyStage.TIMED_OUT, "DEADLINE_EXPIRED", cause)
    }

    private fun stop(stage: TeslaKeyStage, reason: String?, deadlineCause: TeslaGattDeadlineCause? = null,
        drive: TeslaDriveState? = null) {
        // null도 최초 종료의 미관측이다. 관측/cleanup 재진입으로 뒤늦은 기한을 붙이지 않는다.
        if (!sealed && !terminationStarted) {
            terminationStarted = true
            if (deadlineCause != null) gattObservation = gattObservation?.copy(deadlineCause = deadlineCause)
        }
        if (!seal()) return
        if (serviceConnection) {
            try { listener.onStopping(reason ?: "CONNECTION_ENDED") }
            catch (_: Exception) { closeFailed = true }
            try { listener.onParkState(drive, reason ?: "CONNECTION_ENDED") }
            catch (_: Exception) { closeFailed = true }
        }
        listener.onStop(stage, reason)
    }

    /** Facade가 자기 owner/lease를 봉인하기 전에 호출한다. 완료 callback은 여기서 폐기한다. */
    fun seal(): Boolean {
        if (sealed) return false
        terminationStarted = true
        sealed = true
        try { conversation?.close() } catch (_: Exception) { closeFailed = true }
        conversation = null
        bodyFactory = null
        wakeFactory = null
        driveFactory = null
        initialDriveRetryPending = false
        initialDriveDeadline = Long.MAX_VALUE
        frunkFactory = null
        park?.clear()
        try { remove(connectionTask) } catch (_: Exception) { closeFailed = true }
        try { remove(sessionRetransmitTask) } catch (_: Exception) { closeFailed = true }
        frame?.fill(0)
        frame = null
        wakeFrame = false
        chunk?.fill(0)
        chunk = null
        clearIncoming()
        decoder.clear()
        frunk?.cancel()
        try { writePump.seal() } catch (_: Exception) { closeFailed = true }
        writeDispatchOwner = null
        pendingRequest = null
        pendingWriteObservation = null
        lastWrite = sealedWriteObservation(lastWrite, queuedWrite)
        queuedWrite = null
        try { watchdog?.let(remove) } catch (_: Exception) { closeFailed = true }
        watchdog = null
        return true
    }

    /** Credential/handle 할당 반환을 기다리는 책임은 facade에 있다. 모든 close 결과를 합쳐 한 번 통지한다. */
    fun close(completed: (Boolean) -> Unit) {
        closeResult?.let { completed(it); return }
        if (closeStarted) return
        closeStarted = true
        seal()
        closeFailed = closeFailed || frunkSequence?.cleanupFailed == true
        try { conversation?.close() } catch (_: Exception) { closeFailed = true }
        try { frunkSequence?.close() } catch (_: Exception) { closeFailed = true }
        conversation = null
        frame?.fill(0)
        frame = null
        chunk?.fill(0)
        chunk = null
        clearIncoming()
        try { decoder.clear() } catch (_: Exception) { closeFailed = true }
        try { if (transport()?.closeConfirmed() == false) closeFailed = true }
        catch (_: Exception) { closeFailed = true }
        closeResult = closeFailed
        completed(closeFailed)
    }

    private fun clearIncoming() {
        while (incoming.isNotEmpty()) incoming.removeFirst().payload.fill(0)
        queuedBytes = 0
    }

    companion object {
        const val MAX_QUEUED_BYTES = 8_192
        private const val MAX_QUEUED_FRAMES = 8
        private const val MAX_TX_PAYLOAD = 65_535
        private const val WRITE_MS = 5_000L
        private const val CARD_MS = 120_000L
        private const val PROTOCOL_MS = 10_000L
        const val SESSION_RETRANSMIT_WINDOW_MS = 25_000L
        const val SESSION_RETRANSMIT_INTERVAL_MS = 2_500L
        const val MAX_SESSION_RETRANSMITS = 8
        private const val INITIAL_DRIVE_READY_MS = 25_000L
        private const val MAX_INITIAL_DRIVE_RETRIES = 3
        private const val WATCHDOG_MS = 250L
        private const val BUSY_STATUS = 201
    }
}

internal fun teslaGattDeadlineCause(now: Long, deadline: Long, stageDeadline: Long,
    writeDeadline: Long, stageCause: TeslaGattDeadlineCause): TeslaGattDeadlineCause? {
    val firstDeadline = minOf(deadline, stageDeadline, writeDeadline)
    if (now < firstDeadline) return null
    return when (firstDeadline) {
        deadline -> TeslaGattDeadlineCause.OPERATION
        writeDeadline -> TeslaGattDeadlineCause.WRITE
        else -> stageCause
    }
}

/** 관측 재진입 취소·예외로 소비되지 않은 결과의 소유 배열은 다음 송신/협상으로 넘기지 않는다. */
internal fun discardUnconsumedTeslaBleStep(step: TeslaBleStep) {
    if (step is TeslaBleStep.Send) step.payload.fill(0)
}
