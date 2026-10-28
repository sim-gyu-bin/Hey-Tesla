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
import com.heytesla.app.vehicle.navigation.VehicleCoordinateObservation
import com.heytesla.app.vehicle.protocol.TeslaBleAuthEvidence
import com.heytesla.app.vehicle.protocol.TeslaBleConversation
import com.heytesla.app.vehicle.protocol.TeslaBleFrameDecoder
import com.heytesla.app.vehicle.protocol.TeslaBlePhase
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.heytesla.app.vehicle.protocol.TeslaBleResponseObservation
import com.heytesla.app.vehicle.protocol.TeslaBleStep
import com.heytesla.app.vehicle.protocol.TeslaReadOnlyStatus
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceBatteryState
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceInfoProtocol
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceInfoRequest
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceInfoSource
import com.heytesla.app.vehicle.uwb.TeslaUwbGattEvent
import com.heytesla.app.vehicle.uwb.TeslaUwbGattObservation
import com.heytesla.app.vehicle.uwb.TeslaUwbNegotiation
import com.heytesla.app.vehicle.uwb.TeslaUwbStage

import com.tesla.generated.universalmessage.UniversalMessage
import java.util.ArrayDeque

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
    private val frunk: TeslaFrunkPolicy? = null,
    private val uwbRequested: Boolean = false,
    private val deviceInfoSource: TeslaUwbAppDeviceInfoSource? = null,
    private val now: () -> Long,
    private val post: (Runnable) -> Boolean,
    private val postDelayed: (Runnable, Long) -> Boolean,
    private val remove: (Runnable) -> Unit,
    private val listener: Listener,
    private val transport: () -> Transport?,
) {
    init {
        require(!uwbRequested || deviceInfoSource != null) { "UWB_DEVICE_INFO_SOURCE_REQUIRED" }
    }

    interface Listener {
        var state: TeslaKeyProbeState
        val uwbStage: TeslaUwbStage? get() = null
        fun allowed(): Boolean
        fun onStop(stage: TeslaKeyStage, reason: String?)
        fun onEvent(event: String) {}
        fun onStage() {}
        fun onGatt(event: TeslaUwbGattEvent) {}
        fun onFrunk() {}
        fun onNotification(receivedAtElapsedMs: Long) {}
        fun onUwbAuthenticating() {}
        fun coordinatesWanted(): Boolean = false
        fun onCoordinates(observation: VehicleCoordinateObservation?, reason: String?) {}
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
    /** Set only while preparing the existing credential; consumed once after primary authentication. */
    var locationConversationFactory: (() -> TeslaBleConversation)? = null
    private var locationConversation: TeslaBleConversation? = null
    private var locationAttempted = false
    private var primaryAuthenticated = false
    private var locationDeadline = Long.MAX_VALUE
    private var locationOutgoing: ByteArray? = null
    private var locationFrame = false
    private var locationFrameAccepted = false
    private var locationCancellationGeneration = 0L
    private val locationResumeTask = Runnable { if (allowed()) drain() }
    var frunkSequence: TeslaFrunkSequence? = null
    var uwb: TeslaUwbNegotiation? = null
    var gattObservation: TeslaUwbGattObservation? = null
    var pendingRxResponse: TeslaBleResponseObservation? = null
    var stageDeadline = deadline
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
    private val decoder = TeslaBleFrameDecoder()
    private val incoming = ArrayDeque<ReceivedFrame>()
    private var queuedBytes = 0
    private val uwbOutgoing = ArrayDeque<Pair<ByteArray, () -> Unit>>()
    private var uwbFrameComplete: (() -> Unit)? = null
    private var completingUwbFrame = false
    private var deviceInfoSubscribed = false
    private var lastBatteryState: TeslaUwbAppDeviceBatteryState? = null
    private var pendingBatteryChange: ByteArray? = null
    private val deviceInfoCallbackLock = Any()
    private var deviceInfoCallbackQueued = false
    private var deviceInfoSourceClosed = false
    private var deviceInfoCleanupFailed = false
    @Volatile private var deviceInfoCallbackFailed = false
    private val deviceInfoChangedTask = Runnable {
        synchronized(deviceInfoCallbackLock) { deviceInfoCallbackQueued = false }
        try {
            if (allowed() && deviceInfoSubscribed) sendDeviceInfo(includeBatteryState = true, changedOnly = true)
        } catch (_: Exception) {
            stop(TeslaKeyStage.FAILED, "DEVICE_INFO_SOURCE_FAILED")
        }
    }
    private var watchdog: Runnable? = null
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

    /** 서비스의 첫 유효 거리 callback에서만 호출한다. 기존 초기 예산 검사를 통과한 뒤 기한을 해제한다. */
    fun continueServiceRanging(): Boolean {
        if (!allowed()) return false
        checkNotNull(conversation).continueServiceRanging()
        deadline = Long.MAX_VALUE
        stageDeadline = Long.MAX_VALUE
        return true
    }

    /** Facade의 native callback 입장에서도 동일 protocol/write 기한을 검사한다. */
    fun checkDeadlines(): Boolean {
        if (sealed) return false
        if (deviceInfoCallbackFailed) {
            stop(TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED")
            return false
        }
        uwb?.checkReadiness()
        if (sealed) return false
        checkLocationLifetime()
        if (sealed) return false
        frunk?.readinessReason()?.let { stop(TeslaKeyStage.CANCELED, it); return false }
        if (now() < deadline && now() >= stageDeadline && frunkSequence?.awaitingActionReply == true &&
            frame == null && !writePump.writePending && !writePump.queued) {
            stageDeadline = deadline
            step(checkNotNull(frunkSequence).observeAfterMissingReply())
            return !sealed
        }
        if (teslaGattDeadlineExpired(now(), deadline, stageDeadline, writeDeadline)) {
            stop(TeslaKeyStage.TIMED_OUT, "DEADLINE_EXPIRED")
            return false
        }
        return true
    }

    private fun allowed(): Boolean {
        if (sealed) return false
        if (!listener.allowed()) {
            seal() // An owner/permission loss must release metadata listeners even without a TX.
            return false
        }
        return checkDeadlines()
    }

    fun stage(next: TeslaKeyStage, limit: Long) {
        if (listener.state.stage == next) return
        stageDeadline = minOf(deadline, now() + limit)
        listener.state = listener.state.copy(stage = next)
        listener.onStage()
    }

    fun negotiatedMtu(value: Int) { require(value in 23..517); mtu = value }

    /** 실제 CCCD 성공 뒤에만 facade가 호출한다. host도 같은 시작 경계를 사용한다. */
    fun start() {
        if (!allowed()) return
        try {
            val first = when {
                frunkSequence != null -> checkNotNull(frunkSequence).start()
                else -> checkNotNull(conversation).start(register)
            }
            step(first)
        } catch (_: Exception) { stop(TeslaKeyStage.FAILED, "PROTOCOL_START_FAILED") }
    }

    fun sendFira(body: ByteArray, frameComplete: () -> Unit) {
        if (!allowed()) { body.fill(0); return }
        if (uwbOutgoing.size >= MAX_QUEUED_FRAMES) {
            body.fill(0)
            stop(TeslaKeyStage.FAILED, "TX_QUEUE_OVERFLOW")
            return
        }
        uwbOutgoing.addLast(body to frameComplete)
        sendNextUwb()
    }

    private fun sendNextUwb() {
        if (!allowed() || completingUwbFrame || frame != null || writePump.writePending || writePump.queued ||
            writePump.descriptorPending) return
        val body: ByteArray
        if (uwbOutgoing.isNotEmpty()) {
            val outgoing = uwbOutgoing.removeFirst()
            body = outgoing.first
            uwbFrameComplete = outgoing.second
        } else if (pendingBatteryChange != null) {
            body = checkNotNull(pendingBatteryChange)
            pendingBatteryChange = null
            uwbFrameComplete = NO_FRAME_ACTION
        } else {
            if (incoming.isNotEmpty()) return // Consume already queued primary request/stop before sidecar TX.
            val payload = locationOutgoing ?: return
            locationOutgoing = null
            if (locationConversation == null) { payload.fill(0); return }
            sendFrame(payload, sidecar = true)
            return
        }
        step(checkNotNull(conversation).sendFira(body))
    }

    private fun startLocationSidecar() {
        if (!primaryAuthenticated || locationAttempted || locationConversationFactory == null ||
            !listener.coordinatesWanted()) return
        locationAttempted = true
        val factory = checkNotNull(locationConversationFactory)
        locationConversationFactory = null
        if (!allowed()) return
        val created = try { factory() } catch (_: Exception) {
            if (!sealed && listener.allowed() && listener.coordinatesWanted()) {
                listener.onCoordinates(null, "LOCATION_PROTOCOL_INVALID")
            }
            return
        }
        locationConversation = created
        locationDeadline = now() + LOCATION_MS
        try {
            locationStep(created.start(false))
            sendNextUwb()
        } catch (_: Exception) {
            if (locationConversation != null) finishLocation(null, "LOCATION_PROTOCOL_INVALID")
        }
    }

    private fun checkLocationLifetime() {
        startLocationSidecar()
        if (locationConversation == null) return
        if (!listener.coordinatesWanted()) finishLocation(null, "LOCATION_OWNER_UNAVAILABLE")
        else if (now() >= locationDeadline) finishLocation(null, "LOCATION_TIMEOUT")
    }

    private fun locationStep(next: TeslaBleStep) {
        when (next) {
            is TeslaBleStep.Send -> {
                if (locationConversation == null || locationOutgoing != null ||
                    next.actuator || next.payload.isEmpty() || next.payload.size > MAX_TX_PAYLOAD) {
                    next.payload.fill(0)
                    finishLocation(null, "LOCATION_PROTOCOL_INVALID")
                } else locationOutgoing = next.payload
            }
            is TeslaBleStep.Waiting -> Unit // No primary phase, authentication event or deadline mutation.
            is TeslaBleStep.LocationResult -> finishLocation(next.observation, next.reason)
            is TeslaBleStep.Failed -> finishLocation(null,
                if (next.authEvidence != null) "LOCATION_AUTHENTICATION_REJECTED" else "LOCATION_PROTOCOL_INVALID")
            else -> {
                discardUnconsumedTeslaBleStep(next)
                finishLocation(null, "LOCATION_PROTOCOL_INVALID")
            }
        }
    }

    private fun finishLocation(observation: VehicleCoordinateObservation?, reason: String?) {
        val active = locationConversation ?: return
        locationConversation = null
        locationDeadline = Long.MAX_VALUE
        locationOutgoing?.fill(0)
        locationOutgoing = null
        active.close()
        cancelUnissuedLocationFrame()
        // An already issued frame MUST finish at its BE16 boundary, even on sidecar timeout.
        // Its context is gone, so no response can cause a query/retry or escape this owner.
        if (!sealed && listener.allowed() && listener.coordinatesWanted()) listener.onCoordinates(observation, reason)
    }

    private fun cancelUnissuedLocationFrame() {
        if (!locationFrame || locationFrameAccepted || offset != 0 || pendingRequest != null || sdkRequest != null ||
            transport()?.requestPending != false || writePump.descriptorPending) return
        if (!writePump.idle && !writePump.cancelUnissuedWrite()) return
        locationCancellationGeneration++
        frame?.fill(0)
        frame = null
        chunk?.fill(0)
        chunk = null
        locationFrame = false
        writeDeadline = Long.MAX_VALUE
        writeDispatchOwner = null
        queuedWrite = null
        pendingWriteObservation = null
        val posted = try { post(locationResumeTask) } catch (_: Exception) { false }
        if (!posted) stop(TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED")
    }

    private fun isLocationEnvelope(payload: ByteArray): Boolean {
        val envelope = try { UniversalMessage.RoutableMessage.parseFrom(payload) } catch (_: Exception) { return false }
        return envelope.hasFromDestination() &&
            envelope.fromDestination.subDestinationCase == UniversalMessage.Destination.SubDestinationCase.DOMAIN &&
            envelope.fromDestination.domain == UniversalMessage.Domain.DOMAIN_INFOTAINMENT
    }

    private fun receiveDeviceInfoRequest(request: TeslaUwbAppDeviceInfoRequest) {
        if (!uwbRequested) { stop(TeslaKeyStage.FAILED, "UWB_PURPOSE_INVALID"); return }
        when (request) {
            TeslaUwbAppDeviceInfoRequest.NONE -> Unit
            TeslaUwbAppDeviceInfoRequest.GET_MODEL_NUMBER ->
                sendDeviceInfo(includeBatteryState = false, changedOnly = false)
            TeslaUwbAppDeviceInfoRequest.ONCHANGE_BATTERY_STATE -> {
                if (!deviceInfoSubscribed) {
                    deviceInfoSubscribed = true
                    checkNotNull(deviceInfoSource).subscribe(::onDeviceInfoChanged)
                }
                // A repeated request gets a fresh response but never a second subscription.
                sendDeviceInfo(includeBatteryState = true, changedOnly = false)
            }
        }
    }

    /** Platform callbacks only schedule one owner-queue task, never inspect ownership off-thread. */
    private fun onDeviceInfoChanged() {
        synchronized(deviceInfoCallbackLock) {
            if (deviceInfoSourceClosed || deviceInfoCallbackQueued) return
            deviceInfoCallbackQueued = true
            val posted = try { post(deviceInfoChangedTask) } catch (_: Exception) { false }
            if (!posted) deviceInfoCallbackFailed = true
        }
    }

    private fun sendDeviceInfo(includeBatteryState: Boolean, changedOnly: Boolean) {
        if (!allowed()) return
        if (!changedOnly && uwbOutgoing.size >= MAX_QUEUED_FRAMES) {
            stop(TeslaKeyStage.FAILED, "TX_QUEUE_OVERFLOW")
            return
        }
        val snapshot = checkNotNull(deviceInfoSource).snapshot(includeBatteryState)
        try {
            if (!allowed()) return
            val battery = snapshot.batteryState
            require(!includeBatteryState || battery != null) { "DEVICE_INFO_BATTERY_STATE_REQUIRED" }
            require(includeBatteryState || battery == null) { "DEVICE_INFO_MODEL_BATTERY_STATE_UNEXPECTED" }
            if (changedOnly && battery == lastBatteryState) return
            val body = TeslaUwbAppDeviceInfoProtocol.response(snapshot)
            if (includeBatteryState) {
                lastBatteryState = battery
                pendingBatteryChange?.fill(0)
                pendingBatteryChange = null
            }
            if (changedOnly) {
                // Keep only the latest unsent change; noisy broadcasts cannot grow the TX queue.
                pendingBatteryChange = body
                sendNextUwb()
            } else sendFira(body, NO_FRAME_ACTION)
        } finally { snapshot.close() }
    }

    /** null은 adapter의 bounded notification 복사 거절. 마지막 fragment의 callback 진입 시각을 보존한다. */
    fun notify(bytes: ByteArray?, receivedAtElapsedMs: Long) {
        try {
            if (!allowed()) return
            listener.onNotification(receivedAtElapsedMs)
            gattObservation = gattObservation?.withDecoderResult(null, null, null)
            if (!allowed()) return
            if (bytes == null || bytes.size > MAX_QUEUED_BYTES) {
                listener.onGatt(TeslaUwbGattEvent.RX)
                stop(TeslaKeyStage.FAILED, "RX_FRAME_INVALID")
                return
            }
            val frames = try { decoder.append(bytes) }
            catch (failure: Exception) {
                gattObservation = gattObservation?.withDecoderResult(null, decoder.hasIncompleteFrame(), true)
                listener.onGatt(TeslaUwbGattEvent.RX)
                throw failure
            }
            gattObservation = gattObservation?.withDecoderResult(frames.size, decoder.hasIncompleteFrame(), false)
            var nextFrame = 0
            try {
                listener.onGatt(TeslaUwbGattEvent.RX)
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
        if (!allowed() || request.kind != TeslaNativeGattRequestKind.WRITE || !writePump.markWriteIssued()) return false
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
        val locationWrite = locationFrame
        val cancellationBeforeReturn = locationCancellationGeneration
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
        if (locationFrame && result == 0 && failure == null) locationFrameAccepted = true
        writePump.recordWriteResult(result, failure)
        if (result != BUSY_STATUS) {
            if (listener.state.phase == TeslaBlePhase.WAITING_FOR_CARD) registrationAttempted = true
            if (!listener.state.transmissionAttempted) {
                listener.state = listener.state.copy(transmissionAttempted = true)
                listener.onEvent("TESLA_KEY_TX_ATTEMPTED")
            }
        } else pendingRequest = null
        listener.onGatt(TeslaUwbGattEvent.WRITE)
        if (frunkFrame && (failure != null || result != 0 && result != BUSY_STATUS)) {
            stop(TeslaKeyStage.FAILED, if (failure != null) "GATT_WRITE_EXCEPTION" else "GATT_WRITE_REJECTED")
            return
        }
        if (!allowed()) return
        if (result == BUSY_STATUS) {
            if (failure == null && locationWrite && cancellationBeforeReturn != locationCancellationGeneration) return
            if (writePump.retryBusy(minOf(deadline, stageDeadline, writeDeadline))) return
            if (failure == null && locationWrite && cancellationBeforeReturn != locationCancellationGeneration) return
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
        listener.onGatt(TeslaUwbGattEvent.WRITE)
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
            locationFrame = false
            locationFrameAccepted = false
            val completed = uwbFrameComplete
            uwbFrameComplete = null
            if (completed != null) {
                // 이미 수신된 stop은 기존 conversation/FiRa 경로로 먼저 소비한다. 별도 plaintext parser 없음.
                // 이 경계의 onSend는 queue에만 넣어 response completion과 stopped TX의 순서를 뒤집지 않는다.
                completingUwbFrame = true
                try {
                    drain()
                    if (allowed()) completed()
                } finally { completingUwbFrame = false }
                if (!allowed()) return
                sendNextUwb()
            }
        }
        if (frame != null) queueWrite() else {
            drain()
            sendNextUwb()
        }
    }

    private fun protocolPhase(phase: TeslaBlePhase) {
        if (sealed) return
        if (uwbRequested) {
            if (phase == TeslaBlePhase.HANDSHAKING) {
                listener.onUwbAuthenticating()
                if (sealed) return
            }
            else if (phase == TeslaBlePhase.READING_STATUS) {
                listener.state = listener.state.copy(phase = phase, outcome = TeslaKeyOutcome.PENDING)
                return
            }
        }
        val next = when (phase) {
            TeslaBlePhase.WAITING_FOR_CARD -> TeslaKeyStage.WAITING_FOR_CARD
            TeslaBlePhase.HANDSHAKING -> TeslaKeyStage.HANDSHAKING
            TeslaBlePhase.READING_STATUS -> TeslaKeyStage.READING_STATUS
        }
        stage(next, if (phase == TeslaBlePhase.WAITING_FOR_CARD) CARD_MS else PROTOCOL_MS)
        if (!sealed) listener.state = listener.state.copy(phase = phase, outcome = TeslaKeyOutcome.PENDING)
    }

    private fun step(next: TeslaBleStep) {
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
        when (next) {
            TeslaBleStep.UwbReady -> {
                if (!uwbRequested) { stop(TeslaKeyStage.FAILED, "UWB_PURPOSE_INVALID"); return }
                stageDeadline = minOf(deadline, now() + 30_000L)
                checkNotNull(uwb).authenticated()
                primaryAuthenticated = true
                startLocationSidecar()
            }
            is TeslaBleStep.UwbInput -> {
                if (!uwbRequested) { next.message.close(); stop(TeslaKeyStage.FAILED, "UWB_PURPOSE_INVALID"); return }
                val coordinator = uwb ?: run {
                    next.message.close()
                    stop(TeslaKeyStage.FAILED, "UWB_PURPOSE_INVALID")
                    return
                }
                coordinator.receive(next.message)
                if (listener.uwbStage == TeslaUwbStage.STARTING) stageDeadline = deadline
            }
            is TeslaBleStep.UwbDeviceInfoRequest -> receiveDeviceInfoRequest(next.request)
            is TeslaBleStep.Send -> {
                try {
                    listener.state = listener.state.copy(reason = null)
                    protocolPhase(next.phase)
                    if (!allowed()) { next.payload.fill(0); return }
                } catch (failure: Exception) {
                    next.payload.fill(0)
                    throw failure
                }
                frunkFrame = next.actuator
                if (completingUwbFrame || frame != null || writePump.writePending || writePump.queued || writePump.descriptorPending ||
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
                if (frunk != null) {
                    listener.onFrunk()
                    if (!allowed()) return
                    listener.state = listener.state.copy(status = next.status)
                    val verifiedOpen = frunk.result == TeslaFrunkResult.OPEN_OBSERVED
                    if (verifiedOpen) listener.state = listener.state.copy(outcome = TeslaKeyOutcome.VERIFIED_STATUS)
                    stop(if (verifiedOpen) TeslaKeyStage.COMPLETE else TeslaKeyStage.FAILED, when {
                        verifiedOpen -> null
                        frunkSequence?.responseUnknown == true -> "FRUNK_ACTION_RESPONSE_UNKNOWN"
                        else -> "FRUNK_OPEN_NOT_OBSERVED"
                    })
                    return
                }
                listener.state = listener.state.copy(outcome = TeslaKeyOutcome.VERIFIED_STATUS, status = next.status)
                listener.onEvent(query.verifiedEventCode)
                stop(TeslaKeyStage.COMPLETE, null)
            }
            is TeslaBleStep.FrunkResult -> stop(TeslaKeyStage.FAILED, "FRUNK_SEQUENCE_REQUIRED")
            is TeslaBleStep.LocationResult -> stop(TeslaKeyStage.FAILED, "LOCATION_SIDECAR_REQUIRED")
            is TeslaBleStep.Failed -> {
                next.authEvidence?.let { listener.onEvent(it.eventCode) }
                stop(TeslaKeyStage.FAILED, if (uwbRequested) {
                    if (next.authEvidence != null) "AUTHENTICATION_FAILED"
                    else next.reason.takeIf { it.startsWith("UWB_") } ?: "UWB_PROTOCOL_RESPONSE_INVALID"
                } else if (frunk != null) {
                    next.reason.takeIf { it.startsWith("FRUNK_") } ?: "FRUNK_PROTOCOL_RESPONSE_INVALID"
                } else next.reason)
            }
        }
    }

    private fun sendFrame(payload: ByteArray, sidecar: Boolean = false) {
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
        locationFrame = sidecar
        locationFrameAccepted = false
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
        try { owner.submit(bytes) }
        catch (_: Exception) { if (allowed()) stop(TeslaKeyStage.FAILED, "GATT_WRITE_EXCEPTION") }
    }

    private fun drain() {
        while (allowed() && frame == null && !writePump.writePending && !writePump.queued &&
            !writePump.descriptorPending && incoming.isNotEmpty()) {
            val received = incoming.removeFirst()
            queuedBytes -= received.payload.size
            try {
                if (uwbRequested && locationAttempted && isLocationEnvelope(received.payload)) {
                    val active = locationConversation
                    if (active != null) {
                        var produced: TeslaBleStep? = null
                        try {
                            gattObservation = gattObservation?.beginningProtocol(received.receivedAtElapsedMs)
                            try { produced = active.receive(received.payload, received.receivedAtElapsedMs) }
                            finally {
                                // Shared receive denominator, but never reuse a primary UWB verdict for domain3.
                                gattObservation = gattObservation?.withProtocolResult(now(), null)
                                listener.onGatt(TeslaUwbGattEvent.PROTOCOL)
                            }
                            val next = checkNotNull(produced)
                            if (!allowed()) {
                                discardUnconsumedTeslaBleStep(next)
                                produced = null
                                continue
                            }
                            produced = null
                            locationStep(next)
                        } catch (_: Exception) {
                            produced?.let(::discardUnconsumedTeslaBleStep)
                            finishLocation(null, "LOCATION_PROTOCOL_INVALID")
                        }
                    }
                    continue
                }
                val frunkSequence = frunkSequence
                val previousFrunkQuery = frunkSequence?.query
                val next = when {
                    frunkSequence != null -> frunkSequence.receive(received.payload, received.receivedAtElapsedMs)
                    else -> {
                        val conversation = checkNotNull(conversation)
                        pendingRxResponse = null
                        gattObservation = gattObservation?.beginningProtocol(received.receivedAtElapsedMs)
                        try {
                            val result = conversation.receive(received.payload, received.receivedAtElapsedMs)
                            gattObservation = gattObservation?.withProtocolResult(now(), pendingRxResponse)
                            result
                        } catch (failure: Exception) {
                            gattObservation = gattObservation?.withProtocolResult(now(), null)
                            listener.onGatt(TeslaUwbGattEvent.PROTOCOL)
                            throw failure
                        } finally { pendingRxResponse = null }
                    }
                }
                try {
                    listener.onGatt(TeslaUwbGattEvent.PROTOCOL)
                    if (frunkSequence != null) listener.onFrunk()
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
        sendNextUwb()
    }

    fun cancel(stage: TeslaKeyStage = TeslaKeyStage.CANCELED, reason: String? = "USER_STOP") = stop(stage, reason)

    private fun stop(stage: TeslaKeyStage, reason: String?) {
        if (!seal()) return
        listener.onStop(stage, reason)
    }

    /** Facade가 자기 owner/lease를 봉인하기 전에 호출한다. 완료 callback은 여기서 폐기한다. */
    fun seal(): Boolean {
        if (sealed) return false
        sealed = true
        synchronized(deviceInfoCallbackLock) { deviceInfoSourceClosed = true }
        try { remove(deviceInfoChangedTask) } catch (_: Exception) { deviceInfoCleanupFailed = true }
        try { deviceInfoSource?.close() } catch (_: Exception) { deviceInfoCleanupFailed = true }
        deviceInfoSubscribed = false
        lastBatteryState = null
        pendingBatteryChange?.fill(0)
        pendingBatteryChange = null
        pendingRxResponse = null
        locationConversationFactory = null
        locationConversation?.close()
        locationConversation = null
        locationOutgoing?.fill(0)
        locationOutgoing = null
        locationDeadline = Long.MAX_VALUE
        remove(locationResumeTask)
        locationFrame = false
        locationFrameAccepted = false
        conversation?.close()
        frame?.fill(0)
        frame = null
        chunk?.fill(0)
        chunk = null
        clearIncoming()
        decoder.clear()
        frunk?.cancel()
        writePump.seal()
        writeDispatchOwner = null
        pendingRequest = null
        pendingWriteObservation = null
        lastWrite = sealedWriteObservation(lastWrite, queuedWrite)
        queuedWrite = null
        while (uwbOutgoing.isNotEmpty()) uwbOutgoing.removeFirst().first.fill(0)
        uwbFrameComplete = null
        watchdog?.let(remove)
        watchdog = null
        return true
    }

    /** Credential/handle 할당 반환을 기다리는 책임은 facade에 있다. 모든 close 결과를 합쳐 한 번 통지한다. */
    fun close(completed: (Boolean) -> Unit) {
        closeResult?.let { completed(it); return }
        if (closeStarted) return
        closeStarted = true
        seal()
        closeFailed = deviceInfoCleanupFailed || frunkSequence?.cleanupFailed == true
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
        fun finish(failed: Boolean) {
            if (closeResult != null) return
            closeFailed = closeFailed || failed
            closeResult = closeFailed
            completed(closeFailed)
        }
        val coordinator = uwb
        if (coordinator != null) {
            try { coordinator.close(::finish) } catch (_: Exception) { finish(true) }
        } else finish(false)
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
        private const val WATCHDOG_MS = 250L
        private const val BUSY_STATUS = 201
        private const val LOCATION_MS = 15_000L
        private val NO_FRAME_ACTION: () -> Unit = {}
    }
}

internal fun teslaGattDeadlineExpired(now: Long, deadline: Long, stageDeadline: Long,
    writeDeadline: Long): Boolean = now >= minOf(deadline, stageDeadline, writeDeadline)

/** 관측 재진입 취소·예외로 소비되지 않은 결과의 소유 배열은 다음 송신/협상으로 넘기지 않는다. */
internal fun discardUnconsumedTeslaBleStep(step: TeslaBleStep) {
    if (step is TeslaBleStep.Send) step.payload.fill(0)
    if (step is TeslaBleStep.UwbInput) step.message.close()
}
