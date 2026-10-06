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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

internal enum class TeslaKeyStage {
    IDLE, PREPARING_KEY, CONNECTING, DISCOVERING, NEGOTIATING_MTU, SUBSCRIBING,
    WAITING_FOR_CARD, HANDSHAKING, READING_STATUS, CLEANING_UP,
    COMPLETE, BLOCKED, CANCELED, TIMED_OUT, FAILED, CLEANUP_FAILED,
}

internal enum class TeslaKeyOutcome { NOT_SENT, PENDING, CANCELED, FAILED, UNKNOWN, VERIFIED_STATUS }

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
) {
    val active: Boolean get() = stage.ordinal in TeslaKeyStage.PREPARING_KEY.ordinal..TeslaKeyStage.CLEANING_UP.ordinal
}

/** Application runtime 소유. Activity 재생성은 close 실패 예약을 해제하지 않는다. */
@SuppressLint("MissingPermission")
internal class TeslaBleKeyProbe(
    context: Context,
    private val runtime: DiagnosticRuntime,
    private val state: MutableStateFlow<TeslaKeyProbeState>,
) {
    private val app = context.applicationContext
    private val bluetooth = app.getSystemService(BluetoothManager::class.java)
    private val keyStore = TeslaBleKeyStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Operation? = null

    private class Operation(val token: Long, val vin: ByteArray, val register: Boolean, val deadline: Long) {
        var associationId: Int? = null
        var associationAddress: MacAddress? = null
        var stageDeadline = deadline
        var preparing = false
        var sealed = false
        var terminal = TeslaKeyStage.FAILED
        var conversation: TeslaBleConversation? = null
        var gatt: BluetoothGatt? = null
        var tx: BluetoothGattCharacteristic? = null
        var rx: BluetoothGattCharacteristic? = null
        var cccd: BluetoothGattDescriptor? = null
        var cccdValue: ByteArray? = null
        var mtu = 23
        var descriptorPending = false
        var writePending = false
        var writeDeadline = Long.MAX_VALUE
        var chunk: ByteArray? = null
        var frame: ByteArray? = null
        var offset = 0
        var registrationAttempted = false
        var connectUncertain = false
        val decoder = TeslaBleFrameDecoder()
        val incoming = ArrayDeque<ByteArray>()
        var queuedBytes = 0
        var watchdog: Runnable? = null
    }

    fun start(input: String, register: Boolean) {
        mainThread()
        if (current != null || runtime.teslaKeyReserved()) {
            runtime.event("TESLA_KEY_START_BLOCKED")
            return
        }
        val normalized = input.trim().uppercase(Locale.ROOT)
        if (!normalized.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) {
            state.value = TeslaKeyProbeState(stage = TeslaKeyStage.BLOCKED, reason = "VIN_FORMAT_INVALID")
            runtime.event("TESLA_KEY_START_BLOCKED")
            return
        }
        runtime.refresh()
        val token = runtime.acquireTeslaKey() ?: run {
            state.value = TeslaKeyProbeState(stage = TeslaKeyStage.BLOCKED, reason = runtime.teslaKeyBlockedReason() ?: "LEASE_UNAVAILABLE")
            runtime.event("TESLA_KEY_START_BLOCKED")
            return
        }
        val op = Operation(token, normalized.toByteArray(Charsets.US_ASCII), register, runtime.now() + TOTAL_MS)
        current = op
        state.value = TeslaKeyProbeState(stage = TeslaKeyStage.PREPARING_KEY, registrationRequested = register)
        op.stageDeadline = minOf(op.deadline, runtime.now() + PREPARE_MS)
        runtime.event("TESLA_KEY_STARTED")
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
            val credential = try { withContext(Dispatchers.IO) { keyStore.getOrCreate() } } catch (_: Exception) { null }
            if (current !== op) return@launch
            op.preparing = false
            if (op.sealed) { finishCleanup(op); return@launch }
            if (!allowed(op)) return@launch
            if (credential == null) { stop(op, TeslaKeyStage.FAILED, "KEY_PREPARATION_FAILED"); return@launch }
            try {
                op.conversation = TeslaBleConversation(credential, op.vin, runtime::now)
                state.value = state.value.copy(credentialReady = true)
                runtime.event("TESLA_KEY_CREDENTIAL_READY")
                connect(op)
            } catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "KEY_PREPARATION_FAILED") }
        }
    }

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
        if (runtime.now() >= minOf(op.deadline, op.stageDeadline, op.writeDeadline)) {
            stop(op, TeslaKeyStage.TIMED_OUT, "DEADLINE_EXPIRED")
            return false
        }
        val reason = runtime.teslaKeyReadinessReason(op.token) ?: deviceReadiness(op)
        if (reason != null) { stop(op, TeslaKeyStage.CANCELED, reason); return false }
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
            app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false -> "UNLOCKED_UI_REQUIRED"
            else -> {
                val selected = singleAssociation()
                when {
                    selected == null -> "SINGLE_ASSOCIATION_REQUIRED"
                    selected.deviceMacAddress == null -> "ASSOCIATION_ADDRESS_UNAVAILABLE"
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
    }

    private fun connect(op: Operation) {
        if (!allowed(op)) return
        try {
            val selected = singleAssociation()
                ?: run { stop(op, TeslaKeyStage.FAILED, "SINGLE_ASSOCIATION_REQUIRED"); return }
            val address = selected.deviceMacAddress
                ?: run { stop(op, TeslaKeyStage.FAILED, "ASSOCIATION_ADDRESS_UNAVAILABLE"); return }
            op.associationId = selected.id
            val device = bluetooth?.adapter?.getRemoteDevice(address.toByteArray())
                ?: run { stop(op, TeslaKeyStage.FAILED, "BLUETOOTH_UNAVAILABLE"); return }
            stage(op, TeslaKeyStage.CONNECTING, CONNECT_MS)
            if (!allowed(op)) return
            op.connectUncertain = true
            op.gatt = device.connectGatt(app, false, callback(op), BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, runtime.handler)
            op.connectUncertain = false
            if (op.gatt == null) stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_REJECTED")
        } catch (_: SecurityException) { stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_PERMISSION_DENIED") }
        catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED") }
    }

    private fun callback(op: Operation) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = dispatch(op, gatt) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                stop(op, TeslaKeyStage.FAILED, "GATT_CONNECTION_LOST")
            } else if (newState == BluetoothProfile.STATE_CONNECTED && state.value.stage == TeslaKeyStage.CONNECTING) {
                stage(op, TeslaKeyStage.DISCOVERING, DISCOVER_MS)
                request(op, "GATT_DISCOVERY_REJECTED") { gatt.discoverServices() }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = dispatch(op, gatt) {
            if (state.value.stage != TeslaKeyStage.DISCOVERING) return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS) { stop(op, TeslaKeyStage.FAILED, "GATT_DISCOVERY_FAILED"); return@dispatch }
            val service = gatt.getService(SERVICE)
            op.tx = service?.getCharacteristic(TX)
            op.rx = service?.getCharacteristic(RX)
            op.cccd = op.rx?.getDescriptor(CCCD)
            val txProperties = op.tx?.properties ?: 0
            val rxProperties = op.rx?.properties ?: 0
            op.cccdValue = when {
                rxProperties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                rxProperties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else -> null
            }
            if (service == null || op.tx == null || txProperties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
                op.rx == null || op.cccd == null || op.cccdValue == null
            ) { stop(op, TeslaKeyStage.FAILED, "TESLA_GATT_PROFILE_UNSUPPORTED"); return@dispatch }
            stage(op, TeslaKeyStage.NEGOTIATING_MTU, MTU_MS)
            request(op, "GATT_MTU_REJECTED") { gatt.requestMtu(247) }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = dispatch(op, gatt) {
            if (state.value.stage != TeslaKeyStage.NEGOTIATING_MTU) return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS || mtu !in 23..517) {
                stop(op, TeslaKeyStage.FAILED, "GATT_MTU_FAILED"); return@dispatch
            }
            op.mtu = mtu
            stage(op, TeslaKeyStage.SUBSCRIBING, SUBSCRIBE_MS)
            if (!request(op, "GATT_NOTIFICATION_REJECTED") { gatt.setCharacteristicNotification(checkNotNull(op.rx), true) }) return@dispatch
            op.descriptorPending = true
            request(op, "GATT_SUBSCRIPTION_REJECTED") {
                gatt.writeDescriptor(checkNotNull(op.cccd), checkNotNull(op.cccdValue)) == BluetoothStatusCodes.SUCCESS
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = dispatch(op, gatt) {
            if (descriptor !== op.cccd || !op.descriptorPending || state.value.stage != TeslaKeyStage.SUBSCRIBING) return@dispatch
            op.descriptorPending = false
            if (status != BluetoothGatt.GATT_SUCCESS) { stop(op, TeslaKeyStage.FAILED, "GATT_SUBSCRIPTION_FAILED"); return@dispatch }
            state.value = state.value.copy(subscriptionConfirmed = true)
            runtime.event("TESLA_KEY_SUBSCRIBED")
            try { step(op, checkNotNull(op.conversation).start(op.register)) }
            catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "PROTOCOL_START_FAILED") }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = dispatch(op, gatt) {
            if (characteristic !== op.tx || !op.writePending) return@dispatch
            op.writePending = false
            op.writeDeadline = Long.MAX_VALUE
            val size = op.chunk?.size ?: 0
            op.chunk?.fill(0)
            op.chunk = null
            if (status != BluetoothGatt.GATT_SUCCESS) { stop(op, TeslaKeyStage.FAILED, "GATT_WRITE_FAILED"); return@dispatch }
            op.offset += size
            if (op.offset == op.frame?.size) {
                op.frame?.fill(0)
                op.frame = null
                op.offset = 0
            }
            // 중복 콜백이 같은 dispatch에서 다음 chunk 완료로 처리되지 않게 다음 write를 분리한다.
            runtime.handler.post {
                if (!allowed(op)) return@post
                if (op.frame != null) writeNext(op) else drain(op)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.size > MAX_QUEUED_BYTES) {
                dispatch(op, gatt) { stop(op, TeslaKeyStage.FAILED, "RX_FRAME_INVALID") }
                return
            }
            val bytes = value.copyOf()
            dispatch(op, gatt, discarded = { bytes.fill(0) }) {
                try {
                    if (characteristic !== op.rx || !state.value.subscriptionConfirmed) return@dispatch
                    val frames = op.decoder.append(bytes)
                    for (index in frames.indices) {
                        val frame = frames[index]
                        if (op.incoming.size >= MAX_QUEUED_FRAMES || op.queuedBytes + frame.size > MAX_QUEUED_BYTES) {
                            for (remaining in index until frames.size) frames[remaining].fill(0)
                            stop(op, TeslaKeyStage.FAILED, "RX_BUFFER_LIMIT")
                            return@dispatch
                        }
                        op.incoming.addLast(frame)
                        op.queuedBytes += frame.size
                    }
                    if (op.frame == null && !op.writePending) drain(op)
                } catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "RX_FRAME_INVALID") }
                finally { bytes.fill(0) }
            }
        }
    }

    private fun dispatch(op: Operation, source: BluetoothGatt, discarded: () -> Unit = {}, action: () -> Unit) {
        if (Looper.myLooper() != runtime.handler.looper) {
            runtime.handler.post { dispatch(op, source, discarded, action) }
            return
        }
        if (current !== op || source !== op.gatt || !allowed(op)) { discarded(); return }
        try { action() } catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED") }
    }

    private inline fun request(op: Operation, reason: String, action: () -> Boolean): Boolean {
        // 권한·BT·association·UI·deadline 검사와 실제 SDK 호출은 Main의 같은 직렬 경계다.
        if (!allowed(op)) return false
        val accepted = try { action() } catch (_: Exception) { false }
        if (!accepted) stop(op, TeslaKeyStage.FAILED, reason)
        return accepted
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
                protocolPhase(op, next.phase)
                if (op.frame != null || op.writePending || next.payload.isEmpty() || next.payload.size > MAX_TX_PAYLOAD) {
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
                writeNext(op)
            }
            is TeslaBleStep.Waiting -> {
                next.authEvidence?.let { runtime.event(it.eventCode) }
                protocolPhase(op, next.phase)
            }
            is TeslaBleStep.Complete -> {
                state.value = state.value.copy(outcome = TeslaKeyOutcome.VERIFIED_STATUS, status = next.status)
                runtime.event("TESLA_KEY_STATUS_VERIFIED")
                stop(op, TeslaKeyStage.COMPLETE, null)
            }
            is TeslaBleStep.Failed -> {
                next.authEvidence?.let { runtime.event(it.eventCode) }
                stop(op, TeslaKeyStage.FAILED, next.reason)
            }
        }
    }

    private fun writeNext(op: Operation) {
        if (!allowed(op) || op.writePending || op.descriptorPending) return
        val frame = op.frame ?: return
        val owner = op.gatt ?: return
        val tx = op.tx ?: return
        val chunk = frame.copyOfRange(op.offset, minOf(frame.size, op.offset + op.mtu - 3))
        op.chunk = chunk
        op.writePending = true
        request(op, "GATT_WRITE_REJECTED") {
            op.writeDeadline = minOf(op.deadline, runtime.now() + WRITE_MS)
            if (state.value.phase == TeslaBlePhase.WAITING_FOR_CARD) op.registrationAttempted = true
            // 전송 직전 검사 통과 뒤 실제 SDK 호출 시도만 기록한다.
            if (!state.value.transmissionAttempted) {
                state.value = state.value.copy(transmissionAttempted = true)
                runtime.event("TESLA_KEY_TX_ATTEMPTED")
            }
            owner.writeCharacteristic(tx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        }
    }

    private fun drain(op: Operation) {
        while (allowed(op) && op.frame == null && !op.writePending && op.incoming.isNotEmpty()) {
            val payload = op.incoming.removeFirst()
            op.queuedBytes -= payload.size
            try { step(op, checkNotNull(op.conversation).receive(payload)) }
            catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "PROTOCOL_RESPONSE_INVALID") }
            finally { payload.fill(0) }
        }
    }

    private fun stop(op: Operation, terminal: TeslaKeyStage, reason: String?) {
        if (current !== op || op.sealed) return
        op.sealed = true // 전송·callback 먼저 봉인한다.
        op.terminal = terminal
        op.watchdog?.let(runtime.handler::removeCallbacks)
        op.watchdog = null
        val before = state.value
        val outcome = when {
            before.outcome == TeslaKeyOutcome.VERIFIED_STATUS -> before.outcome
            before.transmissionAttempted -> TeslaKeyOutcome.UNKNOWN
            terminal == TeslaKeyStage.CANCELED -> TeslaKeyOutcome.CANCELED
            else -> TeslaKeyOutcome.FAILED
        }
        state.value = before.copy(stage = TeslaKeyStage.CLEANING_UP, outcome = outcome, reason = reason,
            registrationUncertain = op.registrationAttempted && !before.registrationReported)
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
        if (current !== op || !op.sealed || op.preparing) return
        var failed = op.connectUncertain
        try { op.conversation?.close() } catch (_: Exception) { failed = true }
        op.conversation = null
        op.vin.fill(0)
        op.frame?.fill(0)
        op.frame = null
        op.chunk?.fill(0)
        op.chunk = null
        while (op.incoming.isNotEmpty()) op.incoming.removeFirst().fill(0)
        op.queuedBytes = 0
        op.associationAddress = null
        try { op.decoder.clear() } catch (_: Exception) { failed = true }
        val owner = op.gatt
        if (owner != null) {
            try { op.rx?.let { owner.setCharacteristicNotification(it, false) } } catch (_: Exception) { /* 원격 해제는 추정하지 않는다. */ }
            try { owner.disconnect() } catch (_: Exception) { /* 로컬 close와 다른 증거다. */ }
            try { owner.close(); op.gatt = null } catch (_: Exception) { failed = true }
        }
        state.value = state.value.copy(stage = if (failed) TeslaKeyStage.CLEANUP_FAILED else op.terminal,
            localClosed = op.gatt == null && !op.connectUncertain,
            reason = if (failed) "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" else state.value.reason)
        if (failed) {
            runtime.retainTeslaKey(op.token)
            runtime.event("TESLA_KEY_CLEANUP_FAILED")
            // current·lease를 유지한다. Activity 재생성/다시 취소로 close를 성공 처리하지 않는다.
        } else {
            current = null
            runtime.releaseTeslaKey(op.token)
            runtime.event("TESLA_KEY_LOCAL_CLOSED")
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
