package com.heytesla.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.net.MacAddress
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

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

/** Application runtime 소유. Activity 재생성은 close 실패 예약을 해제하지 않는다. */
@SuppressLint("MissingPermission")
internal class TeslaBleKeyProbe(
    context: Context,
    private val runtime: DiagnosticRuntime,
    private val state: MutableStateFlow<TeslaKeyProbeState>,
    private val keyStore: TeslaBleKeyStore = TeslaBleKeyStore(context.applicationContext),
    private val wallNow: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext
    private val bluetooth = app.getSystemService(BluetoothManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Operation? = null
    private val frunkConsent = TeslaFrunkConsentGate()

    private data class ReceivedFrame(val payload: ByteArray, val receivedAtElapsedRealtime: Long)

    private inner class Operation(
        val token: Long,
        val vin: ByteArray,
        val register: Boolean,
        val query: TeslaBleQuery,
        val deadline: Long,
        val approach: TeslaApproachReadRequest? = null,
        val approachFinished: ((TeslaApproachReadSummary, Boolean) -> Unit)? = null,
        val frunk: TeslaFrunkPolicy? = null,
    ) {
        var associationId: Int? = null
        var associationAddress: MacAddress? = null
        var stageDeadline = deadline
        var preparing = false
        var sealed = false
        var cleanupStarted = false
        var terminal = TeslaKeyStage.FAILED
        var conversation: TeslaBleConversation? = null
        var approachSequence: TeslaApproachReadSequence? = null
        var frunkSequence: TeslaFrunkSequence? = null
        var frunkFrame = false
        var frunkParkPublished = false
        var approachHandshakeStarted = false
        var gatt: NativeTransport? = null
        val connectRetry = TeslaGattConnectRetry(
            now = runtime::now,
            postDelayed = { task, delay -> runtime.handler.postDelayed(task, delay) },
            remove = runtime.handler::removeCallbacks,
            allowed = { allowed(this) },
            event = { event ->
                if (event == "TESLA_APPROACH_GATT_RETRY_STARTED") {
                    // 대기 종료일 뿐 새 요청 발급의 증거는 아니다. 이전 연결 관측을 넘기지 않는다.
                    gattConnectionStatus = null
                    gattConnectionState = null
                    gattConnectionElapsedMs = null
                    gattConnectIssuedElapsedMs = null
                    gattConnectReturnedElapsedMs = null
                    gattAutoConnect = null
                    gattConnectAction = null
                    gattConnectAccepted = null
                    advertisementAgeAtConnectMs = null
                }
                publishApproach(this, event)
            },
            failed = { stop(this, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED") },
            connect = {
                val transport = gatt
                if (transport == null) stop(this, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED")
                else transport.reconnect()
            },
        )
        var tx: BluetoothGattCharacteristic? = null
        var rx: BluetoothGattCharacteristic? = null
        var cccd: BluetoothGattDescriptor? = null
        var cccdValue: ByteArray? = null
        var mtu = 23
        var writeDispatchOwner: NativeTransport? = null
        val writePump = TeslaGattWritePump(
            post = { check(runtime.handler.post(it)) },
            postDelayed = { task, delay -> runtime.handler.postDelayed(task, delay) },
            now = runtime::now,
            remove = runtime.handler::removeCallbacks,
            allowed = { allowed(this) && gatt != null && gatt === writeDispatchOwner &&
                gatt?.canWrite == true && tx != null && frame != null },
            onQueued = { recordQueuedWrite(this) },
            submit = {
                try { writeNext(this) }
                catch (_: Exception) { stop(this, TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED") }
            },
        )
        var writeDeadline = Long.MAX_VALUE
        var lastWrite: TeslaApproachReadSummary? = null
        var queuedWrite: TeslaApproachReadSummary? = null
        var gattConnectionStatus: Int? = null
        var gattConnectionState: Int? = null
        var gattConnectionElapsedMs: Long? = null
        var gattDescriptorRequestStatus: Int? = null
        var gattDescriptorCallbackStatus: Int? = null
        var gattDescriptorCallbackElapsedMs: Long? = null
        var lastRxElapsedMs: Long? = null
        var chunk: ByteArray? = null
        var frame: ByteArray? = null
        var offset = 0
        var registrationAttempted = false
        var connectUncertain = false
        val decoder = TeslaBleFrameDecoder()
        val incoming = ArrayDeque<ReceivedFrame>()
        var queuedBytes = 0
        var watchdog: Runnable? = null
        var connectSettleStartedElapsedMs: Long? = null
        var connectSettleCompletedElapsedMs: Long? = null
        var gattConnectIssuedElapsedMs: Long? = null
        var gattConnectReturnedElapsedMs: Long? = null
        var gattAutoConnect: Boolean? = null
        var gattConnectAction: String? = null
        var gattConnectAccepted: Boolean? = null
        var advertisementAgeAtConnectMs: Long? = null
        val settle = TeslaGattConnectSettle(
            now = runtime::now,
            postDelayed = { task, delay -> runtime.handler.postDelayed(task, delay) },
            remove = runtime.handler::removeCallbacks,
            allowed = { allowed(this) },
            onStarted = { started ->
                connectSettleStartedElapsedMs = started
                publishApproach(this, "TESLA_APPROACH_READ_STAGE")
            },
            onCompleted = { completed ->
                connectSettleCompletedElapsedMs = completed
                publishApproach(this, "TESLA_APPROACH_READ_STAGE")
            },
            onScheduleFailed = { stop(this, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED") },
            connect = { connectSettled(this) },
        )
    }

    fun start(input: String, register: Boolean, query: TeslaBleQuery = TeslaBleQuery.BODY_STATUS) =
        startOperation(input, register, query)

    /** DIRECT_MIC 최종 명령 인정과 오디오 lease 반환을 마친 runtime만 호출한다. */
    fun startFrunkOpen(input: String, commandAcceptedAtElapsedMs: Long) {
        mainThread()
        if (current != null || runtime.teslaKeyReserved()) {
            runtime.event("TESLA_FRUNK_BLOCKED")
            return
        }
        frunkConsent.claim(commandAcceptedAtElapsedMs, runtime.now())?.let { reason ->
            state.value = TeslaKeyProbeState(stage = TeslaKeyStage.BLOCKED,
                frunkCommandRequested = true, reason = reason)
            runtime.event("TESLA_FRUNK_BLOCKED")
            return
        }
        startOperation(input, false, TeslaBleQuery.DRIVE_STATE,
            TeslaFrunkPolicy(commandAcceptedAtElapsedMs, runtime::now, wallNow))
    }

    private fun startOperation(input: String, register: Boolean, query: TeslaBleQuery,
        frunk: TeslaFrunkPolicy? = null) {
        mainThread()
        if (current != null || runtime.teslaKeyReserved()) {
            runtime.event(if (frunk != null) "TESLA_FRUNK_BLOCKED" else "TESLA_KEY_START_BLOCKED")
            return
        }
        if (register && query != TeslaBleQuery.BODY_STATUS) {
            state.value = TeslaKeyProbeState(stage = TeslaKeyStage.BLOCKED, query = query,
                registrationRequested = true, reason = "DRIVE_STATE_REGISTRATION_NOT_ALLOWED")
            runtime.event("TESLA_KEY_START_BLOCKED")
            return
        }
        val normalized = input.trim().uppercase(Locale.ROOT)
        if (!normalized.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) {
            state.value = TeslaKeyProbeState(stage = TeslaKeyStage.BLOCKED, query = query,
                frunkCommandRequested = frunk != null, reason = "VIN_FORMAT_INVALID")
            runtime.event(if (frunk != null) "TESLA_FRUNK_BLOCKED" else "TESLA_KEY_START_BLOCKED")
            return
        }
        runtime.refresh()
        if (frunk != null && normalized != runtime.vehicleVinSnapshot()) {
            state.value = TeslaKeyProbeState(stage = TeslaKeyStage.BLOCKED, query = query,
                frunkCommandRequested = true, reason = "FRUNK_VIN_SNAPSHOT_CHANGED")
            runtime.event("TESLA_FRUNK_BLOCKED")
            return
        }
        val token = runtime.acquireTeslaKey() ?: run {
            state.value = TeslaKeyProbeState(stage = TeslaKeyStage.BLOCKED, query = query,
                frunkCommandRequested = frunk != null,
                reason = runtime.teslaKeyBlockedReason() ?: "LEASE_UNAVAILABLE")
            runtime.event(if (frunk != null) "TESLA_FRUNK_BLOCKED" else "TESLA_KEY_START_BLOCKED")
            return
        }
        val op = Operation(token, normalized.toByteArray(Charsets.US_ASCII), register, query,
            frunk?.deadline ?: (runtime.now() + TOTAL_MS), frunk = frunk)
        current = op
        state.value = TeslaKeyProbeState(stage = TeslaKeyStage.PREPARING_KEY, registrationRequested = register,
            query = query, frunkCommandRequested = frunk != null)
        op.stageDeadline = minOf(op.deadline, runtime.now() + PREPARE_MS)
        runtime.event(if (frunk != null) "TESLA_FRUNK_COMMAND_REQUESTED" else "TESLA_KEY_STARTED")
        try {
            val selected = singleAssociation()
            if (selected == null || selected.deviceMacAddress == null) {
                stop(op, TeslaKeyStage.FAILED, "SINGLE_ASSOCIATION_REQUIRED")
                return
            }
            op.associationId = selected.id
            op.associationAddress = selected.deviceMacAddress
        } catch (_: Exception) {
            stop(op, TeslaKeyStage.FAILED, "ASSOCIATION_RESOLVE_FAILED")
            return
        }
        if (!allowed(op)) return
        op.preparing = true
        watch(op)
        // 작업 취소로 IO 완료를 잃지 않는다. 취소 시 전송은 즉시 봉인하고 IO 반환까지 lease를 유지한다.
        scope.launch {
            val credential = try { withContext(Dispatchers.IO) {
                if (op.register) keyStore.getOrCreate() else keyStore.getExistingReadOnly()
            } } catch (_: Exception) { null }
            if (current !== op) return@launch
            op.preparing = false
            if (op.sealed) { finishCleanup(op); return@launch }
            if (!allowed(op)) return@launch
            if (credential == null) {
                stop(op, TeslaKeyStage.FAILED, if (op.frunk != null) "EXISTING_KEY_UNAVAILABLE" else "KEY_PREPARATION_FAILED")
                return@launch
            }
            try {
                if (op.frunk != null) {
                    op.frunkSequence = TeslaFrunkSequence(credential, op.vin, op.frunk, runtime::now)
                } else {
                    op.conversation = TeslaBleConversation(credential, op.vin, query = op.query, now = runtime::now)
                }
                state.value = state.value.copy(credentialReady = true)
                runtime.event("TESLA_KEY_CREDENTIAL_READY")
                connect(op)
            } catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "KEY_PREPARATION_FAILED") }
        }
    }

    fun startApproach(request: TeslaApproachReadRequest, finished: (TeslaApproachReadSummary, Boolean) -> Unit) {
        mainThread()
        fun blocked(reason: String) {
            val summary = TeslaApproachReadSummary(candidate = request.candidate, attempt = request.attempt,
                stage = "BLOCKED", query = TeslaBleQuery.BODY_STATUS.name, reason = reason, localClosed = true)
            runtime.recordTeslaApproachRead("TESLA_APPROACH_READ_BLOCKED", summary)
            request.clear()
            finished(summary, false)
        }
        if (current != null || runtime.teslaKeyReserved()) { blocked("LEASE_UNAVAILABLE"); return }
        runtime.refresh()
        runtime.approachOwnerReason(request)?.let { blocked(it); return }
        val vin = runtime.vehicleVinSnapshot() ?: run { blocked("VIN_NOT_REGISTERED"); return }
        val token = runtime.acquireTeslaApproachRead(request) ?: run { blocked("LEASE_UNAVAILABLE"); return }
        val op = Operation(token, vin.toByteArray(Charsets.US_ASCII), false, TeslaBleQuery.BODY_STATUS,
            request.deadline, request, finished)
        current = op
        op.associationId = request.associationId
        state.value = TeslaKeyProbeState(stage = TeslaKeyStage.PREPARING_KEY, outcome = TeslaKeyOutcome.PENDING)
        op.stageDeadline = minOf(op.deadline, runtime.now() + PREPARE_MS)
        publishApproach(op, "TESLA_APPROACH_READ_STAGE")
        if (!allowed(op)) return
        op.preparing = true
        watch(op)
        scope.launch {
            val credential = try { withContext(Dispatchers.IO) { keyStore.getExistingReadOnly() } } catch (_: Exception) { null }
            if (current !== op) return@launch
            op.preparing = false
            if (op.sealed) { finishCleanup(op); return@launch }
            if (!allowed(op)) return@launch
            if (credential == null) { stop(op, TeslaKeyStage.BLOCKED, "EXISTING_KEY_UNAVAILABLE"); return@launch }
            try {
                op.approachSequence = TeslaApproachReadSequence(credential, op.vin, request.candidate, request.attempt, runtime::now) { code, summary ->
                    runtime.recordTeslaApproachRead(code,
                        observed(op, summary.copy(stage = state.value.stage.name)))
                }
                state.value = state.value.copy(credentialReady = true)
                connect(op)
            } catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "KEY_PREPARATION_FAILED") }
        }
    }

    private fun publishApproach(op: Operation, event: String) {
        val request = op.approach ?: return
        val s = state.value
        val outcome = when {
            s.stage == TeslaKeyStage.COMPLETE -> "VERIFIED_STATUS"
            op.approachSequence?.summary?.bodyVerified == true &&
                op.approachSequence?.summary?.driveVerified != true -> "PARTIAL"
            s.outcome == TeslaKeyOutcome.VERIFIED_STATUS && s.stage == TeslaKeyStage.CLEANUP_FAILED -> "UNKNOWN"
            else -> s.outcome.name
        }
        val summary = op.approachSequence?.snapshot(s.stage.name, outcome, s.reason, s.localClosed,
            runtime.approachReadInFlight(request.owner, request.fieldToken, request.attempt))
            ?: TeslaApproachReadSummary(candidate = request.candidate, attempt = request.attempt,
                stage = s.stage.name, query = TeslaBleQuery.BODY_STATUS.name, outcome = outcome,
                reason = s.reason, localClosed = s.localClosed,
                leaseRetained = runtime.approachReadInFlight(request.owner, request.fieldToken, request.attempt))
        runtime.recordTeslaApproachRead(event, observed(op, summary))
    }

    private fun observed(op: Operation, summary: TeslaApproachReadSummary): TeslaApproachReadSummary =
        summary.copy(
            gattTransport = "NATIVE",
            connectSettleStartedElapsedMs = op.connectSettleStartedElapsedMs,
            connectSettleCompletedElapsedMs = op.connectSettleCompletedElapsedMs,
            gattConnectIssuedElapsedMs = op.gattConnectIssuedElapsedMs,
            gattConnectReturnedElapsedMs = op.gattConnectReturnedElapsedMs,
            gattAutoConnect = op.gattAutoConnect,
            gattConnectAction = op.gattConnectAction,
            gattConnectAccepted = op.gattConnectAccepted,
            advertisementAgeAtConnectMs = op.advertisementAgeAtConnectMs,
            gattConnectionStatus = op.gattConnectionStatus,
            gattConnectionState = op.gattConnectionState,
            gattConnectionElapsedMs = op.gattConnectionElapsedMs,
            gattDescriptorRequestStatus = op.gattDescriptorRequestStatus,
            gattDescriptorCallbackStatus = op.gattDescriptorCallbackStatus,
            gattDescriptorCallbackElapsedMs = op.gattDescriptorCallbackElapsedMs,
            lastRxElapsedMs = op.lastRxElapsedMs,
            advertisementConnectable = op.approach?.advertisementConnectable,
        ).withWriteObservation(op.queuedWrite ?: op.lastWrite)

    fun cancel(reason: String = "USER_STOP") {
        mainThread()
        current?.let { if (!it.sealed) stop(it, TeslaKeyStage.CANCELED, reason) }
    }

    fun checkReadiness() {
        mainThread()
        current?.let { if (!it.sealed) allowed(it) }
    }

    private fun watch(op: Operation) {
        val task = object : Runnable {
            override fun run() {
                if (current !== op || op.sealed) return
                if (allowed(op)) runtime.handler.postDelayed(this, WATCHDOG_MS)
            }
        }
        op.watchdog = task
        runtime.handler.postDelayed(task, WATCHDOG_MS)
    }

    private fun allowed(op: Operation): Boolean {
        if (current !== op || op.sealed) return false
        if (op.gatt?.callbackDeliveryFailed == true) {
            stop(op, TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED")
            return false
        }
        op.frunk?.readinessReason()?.let { stop(op, TeslaKeyStage.CANCELED, it); return false }
        if (op.frunk != null && !vinMatches(op)) {
            stop(op, TeslaKeyStage.CANCELED, "FRUNK_VIN_SNAPSHOT_CHANGED")
            return false
        }
        // 명령 응답만 빠졌고 frame 쓰기는 끝난 경우 새 BODY를 한 번 조회한다. actuator 재발급 없음.
        if (runtime.now() < op.deadline && runtime.now() >= op.stageDeadline &&
            op.frunkSequence?.awaitingActionReply == true && op.frame == null &&
            !op.writePump.writePending && !op.writePump.queued) {
            op.stageDeadline = op.deadline
            step(op, checkNotNull(op.frunkSequence).observeAfterMissingReply())
            return current === op && !op.sealed
        }
        if (runtime.now() >= minOf(op.deadline, op.stageDeadline, op.writeDeadline)) {
            stop(op, TeslaKeyStage.TIMED_OUT, "DEADLINE_EXPIRED")
            return false
        }
        val reason = runtime.teslaKeyReadinessReason(op.token, op.approach,
            requireFresh = !op.approachHandshakeStarted) ?: deviceReadiness(op)
        if (reason != null) { stop(op, TeslaKeyStage.CANCELED, reason); return false }
        return true
    }

    private fun vinMatches(op: Operation): Boolean {
        val value = runtime.vehicleVinSnapshot() ?: return false
        if (value.length != op.vin.size) return false
        for (index in op.vin.indices) if (value[index].code != op.vin[index].toInt()) return false
        return true
    }

    private fun singleAssociation(): AssociationInfo? {
        var selected: AssociationInfo? = null
        for (association in runtime.cdm?.myAssociations.orEmpty()) {
            if (association.isSelfManaged) continue
            if (selected != null) return null
            selected = association
        }
        return selected
    }

    private fun deviceReadiness(op: Operation): String? = try {
        when {
            !runtime.granted(Manifest.permission.BLUETOOTH_CONNECT) -> "BLUETOOTH_PERMISSION_REQUIRED"
            bluetooth?.adapter?.isEnabled != true -> "BLUETOOTH_OFF"
            op.frunk != null && app.getSystemService(PowerManager::class.java)?.isInteractive != true -> "SCREEN_ON_REQUIRED"
            op.approach == null && app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false -> "UNLOCKED_UI_REQUIRED"
            else -> {
                val selected = singleAssociation()
                when {
                    selected == null -> "SINGLE_ASSOCIATION_REQUIRED"
                    op.approach == null && selected.deviceMacAddress == null -> "ASSOCIATION_ADDRESS_UNAVAILABLE"
                    op.associationId != null && selected.id != op.associationId -> "ASSOCIATION_CHANGED"
                    op.associationAddress != null && selected.deviceMacAddress != op.associationAddress -> "ASSOCIATION_CHANGED"
                    else -> null
                }
            }
        }
    } catch (_: SecurityException) { "BLUETOOTH_PERMISSION_REQUIRED" }
    catch (_: Exception) { "READINESS_CHECK_FAILED" }

    private fun stage(op: Operation, next: TeslaKeyStage, limit: Long) {
        if (state.value.stage == next) return // 반복 Waiting/RX로 deadline을 연장하지 않는다.
        op.stageDeadline = minOf(op.deadline, runtime.now() + limit)
        state.value = state.value.copy(stage = next)
        publishApproach(op, "TESLA_APPROACH_READ_STAGE")
    }

    private fun connect(op: Operation) = op.settle.start()

    private fun connectSettled(op: Operation) {
        if (!allowed(op)) return
        try {
            val device = if (op.approach != null) {
                op.approach.device ?: run { stop(op, TeslaKeyStage.BLOCKED, "SCAN_DEVICE_UNAVAILABLE"); return }
            } else {
                val selected = singleAssociation()
                    ?: run { stop(op, TeslaKeyStage.FAILED, "SINGLE_ASSOCIATION_REQUIRED"); return }
                val address = selected.deviceMacAddress
                    ?: run { stop(op, TeslaKeyStage.FAILED, "ASSOCIATION_ADDRESS_UNAVAILABLE"); return }
                op.associationId = selected.id
                bluetooth?.adapter?.getRemoteDevice(address.toByteArray())
                    ?: run { stop(op, TeslaKeyStage.FAILED, "BLUETOOTH_UNAVAILABLE"); return }
            }
            stage(op, TeslaKeyStage.CONNECTING, CONNECT_MS)
            if (!allowed(op)) return
            val owner = NativeTransport(op)
            op.gatt = owner
            if (!allowed(op)) return
            op.gattConnectionStatus = null
            op.gattConnectionState = null
            op.gattConnectionElapsedMs = null
            owner.connect(device)
            if (current === op && !op.sealed) publishApproach(op, "TESLA_APPROACH_READ_STAGE")
        } catch (_: SecurityException) { stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_PERMISSION_DENIED") }
        catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED") }
    }

    private fun retryConnect(op: Operation, manager: NativeTransport, status: Int): Boolean =
        op.connectRetry.retry(status,
            eligible = op.approach != null && !op.register &&
                state.value.stage == TeslaKeyStage.CONNECTING &&
                !state.value.transmissionAttempted && !op.approachHandshakeStarted &&
                !manager.connectionProgressed && manager.canReconnect &&
                op.gattConnectionState != BluetoothProfile.STATE_CONNECTED,
        )

    private inner class NativeTransport(private val op: Operation) : BluetoothGattCallback() {
        private val owner = TeslaNativeGattOwner<BluetoothGatt>()
        val allocating: Boolean get() = owner.allocating
        val connectionProgressed: Boolean get() = owner.connectionProgressed
        val canReconnect: Boolean get() = owner.canReconnect && nativeAutoConnect != null
        val canWrite: Boolean get() = owner.stage == TeslaNativeGattStage.READY && owner.pending == null &&
            owner.handle?.let(owner::accepts) == true
        private var nativeAutoConnect: Boolean? = null
        private var pendingWriteObservation: TeslaApproachReadSummary? = null
        @Volatile var callbackDeliveryFailed = false
            private set

        fun connect(device: BluetoothDevice) {
            op.connectUncertain = true
            try {
                // callback은 반드시 post하므로 SDK 반환 전에 들어와도 handle 부착 뒤 처리된다.
                val returned = owner.connectNative(app, device, this, runtime.handler) { autoConnect ->
                    nativeAutoConnect = autoConnect
                    recordConnectIssued("CREATE", autoConnect)
                }
                op.connectUncertain = owner.allocationUncertain
                recordConnectReturned(returned != null)
                if (returned == null) stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED")
            } catch (_: SecurityException) {
                publishApproach(op, "TESLA_APPROACH_GATT_CONNECT_RESULT")
                stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_PERMISSION_DENIED")
            } catch (_: Exception) {
                publishApproach(op, "TESLA_APPROACH_GATT_CONNECT_RESULT")
                stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED")
            } finally {
                // 취소가 SDK 안에서 재진입했어도 실제 반환 handle을 먼저 소유하고 닫는다.
                if (op.sealed) finishCleanup(op)
            }
        }

        fun reconnect() {
            if (!allowed(op)) return
            try {
                val accepted = owner.reconnect { native ->
                    recordConnectIssued("RECONNECT", checkNotNull(nativeAutoConnect))
                    val result = native.connect()
                    recordConnectReturned(result)
                    result
                }
                if (accepted != true) stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED")
            } catch (_: SecurityException) {
                publishApproach(op, "TESLA_APPROACH_GATT_CONNECT_RESULT")
                stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_PERMISSION_DENIED")
            } catch (_: Exception) {
                publishApproach(op, "TESLA_APPROACH_GATT_CONNECT_RESULT")
                stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED")
            } finally {
                if (op.sealed) finishCleanup(op)
            }
        }

        private fun recordConnectIssued(action: String, autoConnect: Boolean) {
            val issued = runtime.now()
            op.gattConnectAction = action
            op.gattAutoConnect = autoConnect
            op.gattConnectAccepted = null
            op.gattConnectIssuedElapsedMs = issued
            op.gattConnectReturnedElapsedMs = null
            op.advertisementAgeAtConnectMs = op.approach?.sourceElapsedMs?.let {
                (issued - it).takeIf { age -> age >= 0 }
            }
        }

        private fun recordConnectReturned(accepted: Boolean) {
            op.gattConnectReturnedElapsedMs = runtime.now()
            op.gattConnectAccepted = accepted
            publishApproach(op, "TESLA_APPROACH_GATT_CONNECT_RESULT")
        }

        fun seal() {
            owner.seal()
            pendingWriteObservation = null
        }

        fun closeConfirmed(): Boolean {
            seal()
            return owner.closeNative()
        }

        private fun post(native: BluetoothGatt, generation: Long = owner.connectionGeneration,
            discarded: () -> Unit = {}, action: () -> Unit) {
            val accepted = try {
                runtime.handler.post {
                    if (current !== op || op.gatt !== this || op.sealed ||
                        !owner.accepts(native, generation) || !allowed(op)) {
                        discarded()
                    } else {
                        try { action() }
                        catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED") }
                    }
                }
            } catch (_: Exception) { false }
            if (!accepted) {
                callbackDeliveryFailed = true
                discarded()
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val generation = owner.connectionGeneration
            val entered = runtime.now()
            post(gatt, generation) {
                if (newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS && owner.stage != TeslaNativeGattStage.CONNECTING) return@post
                op.gattConnectionStatus = status
                op.gattConnectionState = newState
                op.gattConnectionElapsedMs = entered
                publishApproach(op, "TESLA_APPROACH_GATT_CONNECTION_CALLBACK")
                if (!allowed(op)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                    if (owner.stage == TeslaNativeGattStage.CONNECTING && retryConnect(op, this, status)) return@post
                    stop(op, TeslaKeyStage.FAILED,
                        if (connectionProgressed) "GATT_CONNECTION_LOST" else "GATT_CONNECT_FAILED")
                    return@post
                }
                if (!owner.connected(gatt, generation)) return@post
                op.connectRetry.seal()
                stage(op, TeslaKeyStage.DISCOVERING, DISCOVER_MS)
                if (!allowed(op)) return@post
                val request = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.SERVICES, runtime.now()))
                if (!owner.recordRequestResult(request, if (gatt.discoverServices()) 0 else -1)) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_DISCOVERY_REJECTED")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val entered = runtime.now()
            val request = owner.pending
            post(gatt) {
                if (state.value.stage != TeslaKeyStage.DISCOVERING ||
                    !owner.completeRequest(gatt, request, TeslaNativeGattRequestKind.SERVICES, entered)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_DISCOVERY_FAILED")
                    return@post
                }
                if (!resolveProfile(gatt)) {
                    stop(op, TeslaKeyStage.FAILED, "TESLA_GATT_PROFILE_UNSUPPORTED")
                    return@post
                }
                stage(op, TeslaKeyStage.NEGOTIATING_MTU, MTU_MS)
                if (!allowed(op)) return@post
                val mtuRequest = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.MTU, runtime.now()))
                if (!owner.recordRequestResult(mtuRequest, if (gatt.requestMtu(247)) 0 else -1)) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_MTU_REJECTED")
                }
            }
        }

        private fun resolveProfile(gatt: BluetoothGatt): Boolean {
            val service = gatt.services.singleOrNull { it.uuid == SERVICE } ?: return false
            op.tx = service.characteristics.singleOrNull { it.uuid == TX }
            op.rx = service.characteristics.singleOrNull { it.uuid == RX }
            op.cccd = op.rx?.descriptors?.singleOrNull { it.uuid == CCCD }
            val txProperties = op.tx?.properties ?: 0
            val rxProperties = op.rx?.properties ?: 0
            op.cccdValue = when {
                rxProperties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                rxProperties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else -> null
            }
            return op.tx != null && txProperties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 &&
                op.rx != null && op.cccd != null && op.cccdValue != null
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val entered = runtime.now()
            val request = owner.pending
            post(gatt) {
                if (state.value.stage != TeslaKeyStage.NEGOTIATING_MTU ||
                    !owner.completeRequest(gatt, request, TeslaNativeGattRequestKind.MTU, entered)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || mtu !in 23..517) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_MTU_FAILED")
                    return@post
                }
                op.mtu = mtu
                stage(op, TeslaKeyStage.SUBSCRIBING, SUBSCRIBE_MS)
                if (!allowed(op) || !op.writePump.beginDescriptor(true)) return@post
                val rx = checkNotNull(op.rx)
                if (!gatt.setCharacteristicNotification(rx, true)) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_NOTIFICATION_REJECTED")
                    return@post
                }
                if (!allowed(op)) return@post
                val descriptorRequest = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.CCCD, runtime.now()))
                val result = try { gatt.writeDescriptor(checkNotNull(op.cccd), checkNotNull(op.cccdValue)) }
                catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "GATT_SUBSCRIPTION_FAILED"); return@post }
                op.gattDescriptorRequestStatus = result
                val accepted = owner.recordRequestResult(descriptorRequest, result)
                publishApproach(op, "TESLA_APPROACH_READ_STAGE")
                if (!accepted && allowed(op)) stop(op, TeslaKeyStage.FAILED, "GATT_SUBSCRIPTION_REJECTED")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val entered = runtime.now()
            val request = owner.pending
            post(gatt) {
                if (state.value.stage != TeslaKeyStage.SUBSCRIBING ||
                    descriptor !== op.cccd || descriptor.characteristic !== op.rx ||
                    !owner.completeRequest(gatt, request, TeslaNativeGattRequestKind.CCCD, entered) ||
                    !op.writePump.completeDescriptor()) return@post
                op.gattDescriptorCallbackStatus = status
                op.gattDescriptorCallbackElapsedMs = entered
                publishApproach(op, "TESLA_APPROACH_GATT_DESCRIPTOR_CALLBACK")
                if (!allowed(op)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_SUBSCRIPTION_FAILED")
                    return@post
                }
                check(owner.ready())
                state.value = state.value.copy(subscriptionConfirmed = true)
                runtime.event("TESLA_KEY_SUBSCRIBED")
                if (!allowed(op)) return@post
                try {
                    val first = when {
                        op.frunkSequence != null -> checkNotNull(op.frunkSequence).start()
                        op.approach != null -> checkNotNull(op.approachSequence).start()
                        else -> checkNotNull(op.conversation).start(op.register)
                    }
                    op.approachHandshakeStarted = op.approach != null
                    step(op, first)
                } catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "PROTOCOL_START_FAILED") }
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            val entered = runtime.now()
            post(gatt) {
                if (entered >= (op.gattConnectIssuedElapsedMs ?: Long.MAX_VALUE) && owner.serviceChanged(gatt)) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_SERVICES_INVALIDATED")
                }
            }
        }

        fun send(characteristic: BluetoothGattCharacteristic, bytes: ByteArray, write: TeslaApproachReadSummary) {
            val native = checkNotNull(owner.handle)
            check(characteristic === op.tx)
            val prepared = write.copy(writeRequestedElapsedMs = runtime.now())
            op.queuedWrite = prepared
            publishApproach(op, "TESLA_APPROACH_WRITE_REQUESTED")
            if (!allowed(op) || op.gatt !== this || op.writeDispatchOwner !== this ||
                !canWrite || owner.handle !== native || characteristic !== op.tx || bytes !== op.chunk ||
                !op.writePump.submitting || op.queuedWrite !== prepared) return
            if (op.frunkFrame) {
                checkNotNull(op.frunk).beginWrite(op.offset, bytes.size)?.let {
                    stop(op, TeslaKeyStage.CANCELED, it)
                    return
                }
            }
            val issued = runtime.now()
            val request = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, issued))
            check(op.writePump.markWriteIssued())
            val submitted = prepared.copy(writeDispatchQueued = false, writeDispatchElapsedMs = issued,
                writeBusyRetryCount = op.writePump.busyRetryCount)
            op.lastWrite = submitted
            op.queuedWrite = null
            var result: Int? = null
            var failure: Exception? = null
            try { result = native.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
            catch (exception: Exception) { failure = exception }
            val returned = runtime.now()
            if (op.frunkFrame) {
                checkNotNull(op.frunk).recordSdkResult(result, failure != null)
                if (current === op) publishFrunk(op)
            }
            if (current !== op || op.sealed) return
            val observed = submitted.copy(writeReturnedElapsedMs = returned,
                writeRequestStatus = result, writeException = when (failure) {
                    null -> null
                    is SecurityException -> "SECURITY_EXCEPTION"
                    is IllegalArgumentException -> "ILLEGAL_ARGUMENT_EXCEPTION"
                    is IllegalStateException -> "ILLEGAL_STATE_EXCEPTION"
                    is android.os.RemoteException -> "REMOTE_EXCEPTION"
                    is RuntimeException -> "RUNTIME_EXCEPTION"
                    else -> "OTHER_EXCEPTION"
                })
            op.lastWrite = observed
            pendingWriteObservation = observed.takeIf { result == BluetoothStatusCodes.SUCCESS && failure == null }
            op.writePump.recordWriteResult(result, failure)
            owner.recordRequestResult(request, result ?: -1)
            // 201은 SDK 미접수다. 차량 TX/등록 시도나 인증 거절로 승격하지 않는다.
            if (result != BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                if (state.value.phase == TeslaBlePhase.WAITING_FOR_CARD) op.registrationAttempted = true
                if (!state.value.transmissionAttempted) {
                    state.value = state.value.copy(transmissionAttempted = true)
                    runtime.event("TESLA_KEY_TX_ATTEMPTED")
                }
            }
            publishApproach(op, "TESLA_APPROACH_WRITE_RESULT")
            if (op.frunkFrame && (failure != null || result != BluetoothStatusCodes.SUCCESS &&
                    result != BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY)) {
                stop(op, TeslaKeyStage.FAILED, if (failure != null) "GATT_WRITE_EXCEPTION" else "GATT_WRITE_REJECTED")
                return
            }
            if (!allowed(op)) return
            if (result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY &&
                op.writePump.retryBusy(minOf(op.deadline, op.stageDeadline, op.writeDeadline))) return
            if (failure != null || result != BluetoothStatusCodes.SUCCESS) {
                stop(op, TeslaKeyStage.FAILED, if (failure != null) "GATT_WRITE_EXCEPTION" else "GATT_WRITE_REJECTED")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val entered = runtime.now()
            // SDK 호출 중 진입도 요청 토큰을 보존한다. 반환 관측만 post 실행 전에 완성된다.
            val request = owner.pending
            post(gatt) {
                if (characteristic !== op.tx || !op.writePump.writePending ||
                    !owner.completeRequest(gatt, request, TeslaNativeGattRequestKind.WRITE, entered)) return@post
                val observation = pendingWriteObservation ?: return@post
                pendingWriteObservation = null
                completeWrite(op, observation, status, entered)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val entered = runtime.now()
            receive(gatt, characteristic, value, entered)
        }

        private fun receive(native: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray, receivedAtElapsedRealtime: Long) {
            val bytes = if (value.size <= MAX_QUEUED_BYTES) value.copyOf() else null
            post(native, discarded = { bytes?.fill(0) }) {
                try {
                    if (owner.stage != TeslaNativeGattStage.READY || characteristic !== op.rx ||
                        !state.value.subscriptionConfirmed) return@post
                    op.lastRxElapsedMs = receivedAtElapsedRealtime
                    op.approachSequence?.recordRxNotification(receivedAtElapsedRealtime)
                    publishApproach(op, "TESLA_APPROACH_RX_NOTIFICATION")
                    if (!allowed(op)) return@post
                    if (bytes == null) {
                        stop(op, TeslaKeyStage.FAILED, "RX_FRAME_INVALID")
                        return@post
                    }
                    val frames = op.decoder.append(bytes)
                    var nextFrame = 0
                    try {
                        while (nextFrame < frames.size) {
                            val frame = frames[nextFrame]
                            op.approachSequence?.recordRxFrame(runtime.now())
                            publishApproach(op, "TESLA_APPROACH_RX_FRAME")
                            if (!allowed(op)) return@post
                            if (op.incoming.size >= MAX_QUEUED_FRAMES || op.queuedBytes + frame.size > MAX_QUEUED_BYTES) {
                                stop(op, TeslaKeyStage.FAILED, "RX_BUFFER_LIMIT")
                                return@post
                            }
                            op.incoming.addLast(ReceivedFrame(frame, receivedAtElapsedRealtime))
                            op.queuedBytes += frame.size
                            nextFrame++
                        }
                    } finally {
                        // 이벤트 재진입 취소/예외 시 queue가 소유하지 않은 완성 frame도 지운다.
                        for (remaining in nextFrame until frames.size) frames[remaining].fill(0)
                    }
                    if (op.frame == null && !op.writePump.writePending && !op.writePump.queued &&
                        !op.writePump.descriptorPending) drain(op)
                } catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "RX_FRAME_INVALID") }
                finally { bytes?.fill(0) }
            }
        }
    }


    private fun completeWrite(op: Operation, write: TeslaApproachReadSummary, status: Int, completedElapsedMs: Long) {
        val observed = completedNativeWriteObservation(op.lastWrite, write, status, completedElapsedMs) ?: return
        val completion = op.writePump.completeWrite(status)
        if (completion == TeslaGattWriteCompletion.IGNORED) return
        op.lastWrite = observed
        if (op.frunkFrame) {
            checkNotNull(op.frunk).recordWriteCallback(status,
                frameComplete = op.offset + (op.chunk?.size ?: 0) == op.frame?.size)
        }
        if (op.frunkFrame && completion == TeslaGattWriteCompletion.FAILED) {
            stop(op, TeslaKeyStage.FAILED, "GATT_WRITE_FAILED")
            return
        }
        publishApproach(op, "TESLA_APPROACH_WRITE_CALLBACK")
        if (!allowed(op)) return
        op.writeDeadline = Long.MAX_VALUE
        val chunk = op.chunk ?: run { stop(op, TeslaKeyStage.FAILED, "TX_FRAME_INVALID"); return }
        val size = chunk.size
        chunk.fill(0)
        op.chunk = null
        if (completion == TeslaGattWriteCompletion.FAILED) { stop(op, TeslaKeyStage.FAILED, "GATT_WRITE_FAILED"); return }
        op.offset += size
        if (op.offset == op.frame?.size) {
            op.frame?.fill(0)
            op.frame = null
            op.offset = 0
            op.frunkFrame = false
        }
        if (op.frame != null) queueWrite(op) else drain(op)
    }


    private fun protocolPhase(op: Operation, phase: TeslaBlePhase) {
        val next = when (phase) {
            TeslaBlePhase.WAITING_FOR_CARD -> TeslaKeyStage.WAITING_FOR_CARD
            TeslaBlePhase.HANDSHAKING -> TeslaKeyStage.HANDSHAKING
            TeslaBlePhase.READING_STATUS -> TeslaKeyStage.READING_STATUS
        }
        stage(op, next, if (phase == TeslaBlePhase.WAITING_FOR_CARD) CARD_MS else PROTOCOL_MS)
        state.value = state.value.copy(phase = phase, outcome = TeslaKeyOutcome.PENDING)
    }

    private fun step(op: Operation, next: TeslaBleStep) {
        if (!allowed(op)) {
            if (next is TeslaBleStep.Send) next.payload.fill(0)
            return
        }
        val reported = op.conversation?.registrationReported == true
        if (reported && !state.value.registrationReported) runtime.event("TESLA_KEY_REGISTRATION_REPORTED")
        state.value = state.value.copy(registrationReported = reported)
        when (next) {
            is TeslaBleStep.Send -> {
                state.value = state.value.copy(reason = null)
                protocolPhase(op, next.phase)
                op.frunkFrame = next.actuator
                if (op.frame != null || op.writePump.writePending || op.writePump.queued || op.writePump.descriptorPending ||
                    next.payload.isEmpty() || next.payload.size > MAX_TX_PAYLOAD) {
                    next.payload.fill(0)
                    stop(op, TeslaKeyStage.FAILED, "TX_FRAME_INVALID")
                    return
                }
                val frame = ByteArray(next.payload.size + 2)
                frame[0] = (next.payload.size ushr 8).toByte()
                frame[1] = next.payload.size.toByte()
                next.payload.copyInto(frame, 2)
                next.payload.fill(0)
                op.frame = frame
                queueWrite(op)
            }
            is TeslaBleStep.Waiting -> {
                next.authEvidence?.let { runtime.event(it.eventCode) }
                protocolPhase(op, next.phase)
                if (op.approach == null && next.authEvidence != null && next.authEvidence != TeslaBleAuthEvidence.ACKNOWLEDGED) {
                    state.value = state.value.copy(
                        reason = "검증되지 않은 인증 응답 제외 · 정상 응답 대기 · ${next.authEvidence.eventCode}",
                    )
                }
            }
            is TeslaBleStep.Complete -> {
                if (op.frunk != null) {
                    publishFrunk(op)
                    if (!allowed(op)) return
                    state.value = state.value.copy(status = next.status)
                    val verifiedOpen = op.frunk.result == TeslaFrunkResult.OPEN_OBSERVED
                    if (verifiedOpen) state.value = state.value.copy(outcome = TeslaKeyOutcome.VERIFIED_STATUS)
                    stop(op, if (verifiedOpen) TeslaKeyStage.COMPLETE else TeslaKeyStage.FAILED,
                        when {
                            verifiedOpen -> null
                            op.frunkSequence?.responseUnknown == true -> "FRUNK_ACTION_RESPONSE_UNKNOWN"
                            else -> "FRUNK_OPEN_NOT_OBSERVED"
                        })
                    return
                }
                state.value = state.value.copy(outcome = TeslaKeyOutcome.VERIFIED_STATUS, status = next.status)
                if (op.approach == null) runtime.event(op.query.verifiedEventCode)
                stop(op, TeslaKeyStage.COMPLETE, null)
            }
            is TeslaBleStep.FrunkResult -> stop(op, TeslaKeyStage.FAILED, "FRUNK_SEQUENCE_REQUIRED")
            is TeslaBleStep.Failed -> {
                next.authEvidence?.let { runtime.event(it.eventCode) }
                stop(op, TeslaKeyStage.FAILED, if (op.frunk != null) {
                    next.reason.takeIf { it.startsWith("FRUNK_") } ?: "FRUNK_PROTOCOL_RESPONSE_INVALID"
                } else if (op.approach != null) {
                    if (next.authEvidence != null) "AUTHENTICATION_FAILED" else "PROTOCOL_RESPONSE_INVALID"
                } else next.reason)
            }
        }
    }

    private fun queueWrite(op: Operation) {
        if (!allowed(op) || op.frame == null || op.writePump.queued || op.writePump.writePending ||
            op.writePump.descriptorPending) return
        op.writeDispatchOwner = op.gatt ?: return
        try { op.writePump.queue() }
        catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED") }
    }

    private fun recordQueuedWrite(op: Operation, publish: Boolean = true) {
        val frame = checkNotNull(op.frame)
        val tx = checkNotNull(op.tx)
        op.queuedWrite = TeslaApproachReadSummary(
            writeSequence = (op.lastWrite?.writeSequence ?: 0L) + 1L,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            txProperties = tx.properties, negotiatedMtu = op.mtu,
            frameLength = frame.size, chunkLength = minOf(frame.size - op.offset, op.mtu - 3),
            frameOffset = op.offset, writePendingBefore = op.writePump.writePending,
            descriptorPendingBefore = op.writePump.descriptorPending, writeDispatchQueued = true,
            writeBusyRetryCount = op.writePump.busyRetryCount,
        )
        if (publish) publishApproach(op, "TESLA_APPROACH_WRITE_QUEUED")
    }

    private fun writeNext(op: Operation) {
        if (!allowed(op) || !op.writePump.submitting || op.writePump.descriptorPending) return
        val frame = op.frame ?: return
        val owner = op.gatt ?: return
        if (owner !== op.writeDispatchOwner) return
        val tx = op.tx ?: return
        // BUSY 미접수만 같은 chunk를 유지한다. SDK 접수 뒤 callback 실패/기한에는 재전송하지 않는다.
        val chunk = op.chunk ?: frame.copyOfRange(op.offset, minOf(frame.size, op.offset + op.mtu - 3)).also {
            op.chunk = it
        }
        if (op.writeDeadline == Long.MAX_VALUE) {
            op.writeDeadline = minOf(op.deadline, runtime.now() + WRITE_MS)
        }
        if (!allowed(op) || op.gatt !== owner || !op.writePump.submitting ||
            op.writePump.descriptorPending || op.frame !== frame || op.chunk !== chunk) return
        // 재발급 예약은 마지막 SDK 관측을 덮지 않는다. 실제 dispatch에서만 다음 sequence를 준비한다.
        if (op.queuedWrite == null) recordQueuedWrite(op, publish = false)
        val write = checkNotNull(op.queuedWrite)
        try {
            owner.send(tx, chunk, write)
        } catch (_: Exception) {
            if (allowed(op)) stop(op, TeslaKeyStage.FAILED, "GATT_WRITE_EXCEPTION")
        }
    }


    private fun drain(op: Operation) {
        while (allowed(op) && op.frame == null && !op.writePump.writePending && !op.writePump.queued &&
            !op.writePump.descriptorPending && op.incoming.isNotEmpty()) {
            val received = op.incoming.removeFirst()
            op.queuedBytes -= received.payload.size
            try {
                val frunkSequence = op.frunkSequence
                val previousFrunkQuery = frunkSequence?.query
                val sequence = op.approachSequence
                val previousQuery = sequence?.query
                val next = when {
                    frunkSequence != null -> frunkSequence.receive(received.payload, received.receivedAtElapsedRealtime)
                    sequence != null -> sequence.receive(received.payload, received.receivedAtElapsedRealtime)
                    else -> checkNotNull(op.conversation).receive(received.payload, received.receivedAtElapsedRealtime)
                }
                try {
                    if (sequence != null) publishApproach(op, "TESLA_APPROACH_RX_RESPONSE")
                    if (frunkSequence != null) publishFrunk(op)
                    if (!allowed(op)) {
                        if (next is TeslaBleStep.Send) next.payload.fill(0)
                        return
                    }
                } catch (failure: Exception) {
                    if (next is TeslaBleStep.Send) next.payload.fill(0)
                    throw failure
                }
                if (sequence != null && sequence.query != previousQuery ||
                    frunkSequence != null && (frunkSequence.query != previousFrunkQuery ||
                        next is TeslaBleStep.Send && next.phase == TeslaBlePhase.HANDSHAKING)) {
                    while (op.incoming.isNotEmpty()) op.incoming.removeFirst().payload.fill(0)
                    op.queuedBytes = 0
                    op.decoder.clear()
                    state.value = state.value.copy(query = frunkSequence?.query ?: checkNotNull(sequence).query,
                        phase = null, reason = null)
                }
                step(op, next)
            } catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "PROTOCOL_RESPONSE_INVALID") }
            finally { received.payload.fill(0) }
        }
    }


    private fun publishFrunk(op: Operation) {
        val policy = op.frunk ?: return
        val before = state.value
        state.value = before.copy(frunkCommandSent = policy.commandSent,
            frunkCommandAcknowledged = policy.commandAcknowledged, frunkOpenObserved = policy.openObserved,
            frunkFreshParkVerified = policy.driveVerified)
        if (policy.driveVerified && !op.frunkParkPublished) {
            op.frunkParkPublished = true
            runtime.event("TESLA_FRUNK_FRESH_P_VERIFIED")
        }
        if (policy.commandSent && !before.frunkCommandSent) runtime.event("TESLA_FRUNK_COMMAND_SENT")
        if (policy.commandAcknowledged && !before.frunkCommandAcknowledged) runtime.event("TESLA_FRUNK_COMMAND_ACKNOWLEDGED")
        if (policy.openObserved && !before.frunkOpenObserved) runtime.event("TESLA_FRUNK_OPEN_OBSERVED")
    }
    private fun stop(op: Operation, terminal: TeslaKeyStage, reason: String?) {
        if (current !== op || op.sealed) return
        op.sealed = true // 전송·callback 먼저 봉인한다.
        op.frunk?.cancel()
        op.terminal = terminal
        op.writePump.seal()
        op.writeDispatchOwner = null
        op.lastWrite = sealedWriteObservation(op.lastWrite, op.queuedWrite)
        op.queuedWrite = null
        op.watchdog?.let(runtime.handler::removeCallbacks)
        op.watchdog = null
        op.settle.seal()
        op.connectRetry.seal()
        op.gatt?.seal()
        val before = state.value
        val outcome = when {
            op.frunk?.result == TeslaFrunkResult.REJECTED -> TeslaKeyOutcome.FAILED
            op.frunk?.result == TeslaFrunkResult.OPEN_OBSERVED -> TeslaKeyOutcome.VERIFIED_STATUS
            op.frunk?.mayHaveSent == true -> TeslaKeyOutcome.UNKNOWN
            op.frunk != null && terminal == TeslaKeyStage.CANCELED -> TeslaKeyOutcome.CANCELED
            op.frunk != null -> TeslaKeyOutcome.FAILED
            before.outcome == TeslaKeyOutcome.VERIFIED_STATUS -> before.outcome
            before.transmissionAttempted -> TeslaKeyOutcome.UNKNOWN
            terminal == TeslaKeyStage.CANCELED -> TeslaKeyOutcome.CANCELED
            else -> TeslaKeyOutcome.FAILED
        }
        state.value = before.copy(stage = TeslaKeyStage.CLEANING_UP, outcome = outcome, reason = reason,
            registrationUncertain = op.registrationAttempted && !before.registrationReported)
        publishApproach(op, "TESLA_APPROACH_READ_STAGE")
        if (op.frunk != null) runtime.event(when {
            op.frunk.commandRejected -> "TESLA_FRUNK_ACTION_REJECTED"
            outcome == TeslaKeyOutcome.UNKNOWN -> "TESLA_FRUNK_RESULT_UNKNOWN"
            terminal == TeslaKeyStage.CANCELED -> "TESLA_FRUNK_CANCELED"
            outcome == TeslaKeyOutcome.VERIFIED_STATUS -> "TESLA_FRUNK_COMPLETE"
            else -> "TESLA_FRUNK_FAILED"
        })
        runtime.event(when {
            outcome == TeslaKeyOutcome.UNKNOWN -> "TESLA_KEY_RESULT_UNKNOWN"
            terminal == TeslaKeyStage.CANCELED -> "TESLA_KEY_CANCELED"
            terminal == TeslaKeyStage.TIMED_OUT -> "TESLA_KEY_TIMEOUT"
            terminal == TeslaKeyStage.COMPLETE -> "TESLA_KEY_COMPLETE"
            else -> "TESLA_KEY_FAILED"
        })
        op.vin.fill(0)
        if (!op.preparing) finishCleanup(op)
    }

    private fun finishCleanup(op: Operation) {
        if (current !== op || !op.sealed || op.preparing || op.gatt?.allocating == true || op.cleanupStarted) return
        op.cleanupStarted = true
        var failed = op.approachSequence?.cleanupFailed == true || op.frunkSequence?.cleanupFailed == true
        try { op.conversation?.close() } catch (_: Exception) { failed = true }
        try { op.approachSequence?.close() } catch (_: Exception) { failed = true }
        try { op.frunkSequence?.close() } catch (_: Exception) { failed = true }
        op.conversation = null
        op.vin.fill(0)
        op.frame?.fill(0)
        op.frame = null
        op.chunk?.fill(0)
        op.chunk = null
        while (op.incoming.isNotEmpty()) op.incoming.removeFirst().payload.fill(0)
        op.queuedBytes = 0
        op.associationAddress = null
        try { op.decoder.clear() } catch (_: Exception) { failed = true }
        val owner = op.gatt
        if (owner != null) {
            try {
                if (owner.closeConfirmed()) {
                    op.gatt = null
                    op.connectUncertain = false
                } else failed = true
            } catch (_: Exception) { failed = true }
        }
        if (op.connectUncertain) failed = true
        state.value = state.value.copy(stage = if (failed) TeslaKeyStage.CLEANUP_FAILED else op.terminal,
            localClosed = op.gatt == null && !op.connectUncertain,
            reason = if (failed) "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" else state.value.reason)
        if (failed) {
            runtime.retainTeslaKey(op.token)
            runtime.event("TESLA_KEY_CLEANUP_FAILED")
            if (op.frunk != null) runtime.event("TESLA_FRUNK_CLEANUP_FAILED")
            publishApproach(op, "TESLA_APPROACH_READ_CLEANUP_FAILED")
            op.approach?.let {
                it.clear()
                op.approachFinished?.invoke(checkNotNull(runtime.state.value.teslaApproachRead), true)
            }
            // current·lease를 유지한다. Activity 재생성/다시 취소로 close를 성공 처리하지 않는다.
        } else {
            current = null
            runtime.releaseTeslaKey(op.token)
            runtime.event("TESLA_KEY_LOCAL_CLOSED")
            if (op.frunk != null) runtime.event("TESLA_FRUNK_LOCAL_CLOSED")
            publishApproach(op, if (op.terminal == TeslaKeyStage.BLOCKED) "TESLA_APPROACH_READ_BLOCKED"
                else "TESLA_APPROACH_READ_STAGE")
            op.approach?.let {
                it.clear()
                op.approachFinished?.invoke(checkNotNull(runtime.state.value.teslaApproachRead), false)
            }
        }
    }

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private companion object {
        const val TOTAL_MS = 180_000L
        const val PREPARE_MS = 15_000L
        const val CONNECT_MS = 20_000L
        const val DISCOVER_MS = 10_000L
        const val MTU_MS = 5_000L
        const val SUBSCRIBE_MS = 10_000L
        const val WRITE_MS = 5_000L
        const val CARD_MS = 120_000L
        const val PROTOCOL_MS = 10_000L
        const val WATCHDOG_MS = 250L
        const val MAX_TX_PAYLOAD = 65_535
        const val MAX_QUEUED_BYTES = 8_192
        const val MAX_QUEUED_FRAMES = 8
        val SERVICE: UUID = UUID.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")
        val TX: UUID = UUID.fromString("00000212-b2d1-43f0-9b88-960cebf8b91e")
        val RX: UUID = UUID.fromString("00000213-b2d1-43f0-9b88-960cebf8b91e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
