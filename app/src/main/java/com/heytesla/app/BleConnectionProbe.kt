package com.heytesla.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.companion.AssociationInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

/** 등록 주소와 GATT는 서비스 소유자 내부 RAM에만 둔다. 키·인증·차량 characteristic write는 없다. */
internal class BleConnectionProbe(
    context: Context,
    private val state: MutableStateFlow<BleProbeState>,
    private val runtime: DiagnosticRuntime,
    private val service: ObservationService,
    private val requestId: String,
    private val onStage: (Long, BleProbeState) -> Unit,
    private val onFinished: (Long, BleProbeState, Boolean) -> Unit,
) {
    private val app = context.applicationContext
    private val bluetooth = app.getSystemService(BluetoothManager::class.java)
    private val ownership = BleGattOwnership(runtime::releaseBleDiagnostic) { runtime.handler.removeCallbacks(it) }
    private var startingGatt = false

    /** 실제 handle 발급 전부터 정리·close까지 봉인한다. close 실패이면 소유권과 예약을 유지한다. */
    val hasActiveGatt: Boolean get() = startingGatt || ownership.current != null

    @SuppressLint("MissingPermission")
    fun start(attempt: Long, backgroundConnect: Boolean = false, deadline: Long? = null) {
        mainThread()
        check(!hasActiveGatt) // 반복 발급은 서비스 정책이 close 후에만 수행한다.
        startingGatt = true
        val session = runtime.acquireBleDiagnostic(service, requestId) { cancel("USER_STOP") }
        if (session == null) {
            blocked(attempt, runtime.bleFieldAttemptBlockedReason(service, requestId) ?: "LEASE_UNAVAILABLE", backgroundConnect)
            return
        }
        val device: BluetoothDevice
        val selectedAssociationId: Int
        try {
            val selected = singleAssociation()
            val address = selected?.deviceMacAddress
            val adapter = bluetooth?.adapter
            val reason = when {
                selected == null -> "SINGLE_ASSOCIATION_REQUIRED"
                selected.id != service.observedAssociationId -> "ASSOCIATION_REMOVED"
                address == null -> "ASSOCIATION_ADDRESS_UNAVAILABLE"
                adapter == null -> "BLUETOOTH_UNAVAILABLE"
                !adapter.isEnabled -> "BLUETOOTH_OFF"
                else -> null
            }
            if (reason != null) {
                runtime.stopBleFieldTrial(reason)
                runtime.releaseBleDiagnostic(session.id) // GATT를 생성하지 않았으므로 반환 가능하다.
                blocked(attempt, reason, backgroundConnect)
                return
            }
            device = checkNotNull(adapter).getRemoteDevice(checkNotNull(address).toByteArray())
            selectedAssociationId = checkNotNull(selected).id
        } catch (_: SecurityException) {
            runtime.stopBleFieldTrial("BLUETOOTH_PERMISSION_REQUIRED")
            runtime.releaseBleDiagnostic(session.id)
            blocked(attempt, "BLUETOOTH_PERMISSION_REQUIRED", backgroundConnect)
            return
        } catch (_: Exception) {
            runtime.releaseBleDiagnostic(session.id)
            blocked(attempt, "ASSOCIATION_RESOLVE_FAILED", backgroundConnect)
            return
        }
        val port = AndroidBleGattPort(app, device, session.id, runtime.handler, backgroundConnect) {
            // 이전 GATT callback은 새 회차의 watchdog/prerequisite까지 실행할 수 없다.
            ownership.current?.takeIf { it.token == session.id }?.also(::advance)
        }
        var lastStatus = BleProbeStatus.IDLE
        val trial = BleGattSession(
            session.id, session.deadline, port, runtime::now,
            publish = { next ->
                if (ownership.current?.token == session.id) {
                    state.value = next
                    if (next.status != lastStatus) {
                        lastStatus = next.status
                        if (next.active) onStage(attempt, next)
                    }
                }
            },
            release = { ownership.release(session.id) },
            finishedCallback = { result ->
                ownership.stopWatchdog(session.id)
                runtime.event("BLE_PROBE_${result.status.name}")
                onFinished(attempt, result, !result.localClosed)
            },
            attempt = attempt,
            backgroundConnect = backgroundConnect,
            deadline = deadline,
            recordEvidence = runtime::recordBleEvidence,
        )
        ownership.attach(trial, selectedAssociationId)
        startingGatt = false
        runtime.event("BLE_PROBE_STARTED")
        trial.start()
        if (ownership.current !== trial || !trial.state.active) return
        val tick = object : Runnable {
            override fun run() {
                if (ownership.current !== trial) return
                advance(trial)
                if (ownership.current === trial && trial.state.active) runtime.handler.postDelayed(this, WATCHDOG_MS)
            }
        }
        ownership.watch(session.id, tick)
        runtime.handler.postDelayed(tick, WATCHDOG_MS)
    }

    fun cancel(reason: String = "USER_STOP") {
        mainThread()
        ownership.current?.cancel(reason)
    }

    private fun blocked(attempt: Long, reason: String, backgroundConnect: Boolean) {
        startingGatt = false
        val result = BleProbeState(status = BleProbeStatus.BLOCKED, reason = reason, backgroundConnect = backgroundConnect)
        state.value = result
        runtime.event("BLE_PROBE_BLOCKED")
        onFinished(attempt, result, false)
    }

    private fun advance(trial: BleGattSession) {
        if (!trial.state.active) return
        val reason = if (trial.state.status == BleProbeStatus.CLEANING_UP) null else prerequisiteReason(trial.token)
        // 조건 상실도 CDM 신규 트리거 봉인→GATT 정리 순서. 회차 timeout은 전체 주말 대기를 끝내지 않는다.
        if (reason != null) runtime.stopBleFieldTrial(reason)
        if (ownership.current === trial) trial.tick(reason)
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

    @SuppressLint("MissingPermission")
    private fun prerequisiteReason(token: Long): String? {
        if (!runtime.policy.accepts(token)) return "LEASE_LOST"
        if (!runtime.ownsBleField(service, requestId) || runtime.state.value.bleFieldTrialStopping) return "FIELD_OWNER_NOT_ARMED"
        if (!runtime.granted(Manifest.permission.BLUETOOTH_CONNECT)) return "BLUETOOTH_PERMISSION_REVOKED"
        val snapshot = runtime.state.value
        if (!snapshot.observing || snapshot.speechDiagnosticActive || runtime.microphone != null ||
            service.observedAssociationId != ownership.associationId
        ) return "DIAGNOSTIC_EXCLUSIVITY_LOST"
        return try {
            val adapter = bluetooth?.adapter
            when {
                adapter == null -> "BLUETOOTH_UNAVAILABLE"
                !adapter.isEnabled -> "BLUETOOTH_OFF"
                else -> {
                    val selected = singleAssociation()
                    if (selected == null || selected.id != ownership.associationId || selected.deviceMacAddress == null) "ASSOCIATION_REMOVED" else null
                }
            }
        } catch (_: SecurityException) { "BLUETOOTH_PERMISSION_REVOKED" }
        catch (_: Exception) { "PREREQUISITE_READ_FAILED" }
    }

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private companion object { const val WATCHDOG_MS = 100L }
}

/** 각 callback은 token과 동일 BluetoothGatt·RX/CCCD handle을 확인한 뒤 Main에 전달한다. */
@SuppressLint("MissingPermission")
private class AndroidBleGattPort(
    private val context: Context,
    private val device: BluetoothDevice,
    private val token: Long,
    private val handler: Handler,
    private val backgroundConnect: Boolean,
    private val listener: () -> BleGattListener?,
) : BleGattPort {
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var cccd: BluetoothGattDescriptor? = null
    private var mode: BleSubscriptionMode? = null
    private val subscription = BleCccdSubscription()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState != BluetoothProfile.STATE_CONNECTED && newState != BluetoothProfile.STATE_DISCONNECTED) return
            dispatch(gatt) { it.connection(token, this@AndroidBleGattPort, status == BluetoothGatt.GATT_SUCCESS, newState == BluetoothProfile.STATE_CONNECTED, status) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            dispatch(gatt) { it.services(token, this@AndroidBleGattPort, status == BluetoothGatt.GATT_SUCCESS, status) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            dispatch(gatt) { owner ->
                if (descriptor !== cccd) return@dispatch
                when (subscription.writeCompleted(status == BluetoothGatt.GATT_SUCCESS)) {
                    BleCccdWriteResult.IGNORE -> Unit
                    BleCccdWriteResult.ENABLED -> owner.descriptor(token, this@AndroidBleGattPort, true, true, status)
                    BleCccdWriteResult.ENABLE_FAILED -> owner.descriptor(token, this@AndroidBleGattPort, true, false, status)
                    BleCccdWriteResult.DISABLE_FAILED -> owner.descriptor(token, this@AndroidBleGattPort, false, false, status)
                    BleCccdWriteResult.VERIFY_DISABLED -> {
                        // 중복 enable callback을 disable 완료로 추정하지 않는다. 실제 CCCD 0을 읽어 확인한다.
                        val accepted = owner.verifyUnsubscribe(token, this@AndroidBleGattPort, status) {
                            gatt.readDescriptor(descriptor)
                        }
                        if (!accepted) {
                            subscription.abort()
                        }
                    }
                }
            }
        }

        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int, value: ByteArray) {
            dispatch(gatt) { owner ->
                if (descriptor !== cccd) return@dispatch
                val disabled = subscription.readCompleted(status == BluetoothGatt.GATT_SUCCESS, value) ?: return@dispatch
                owner.descriptor(token, this@AndroidBleGattPort, false, disabled, status, BleEvidenceAction.DESCRIPTOR_READ_CALLBACK)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // 수신 bytes는 읽거나 복사하지 않는다. Main closure에도 value를 캡처하지 않는다.
            changed(gatt, characteristic)
        }

    }

    private fun changed(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        dispatch(source) { if (characteristic === rx) it.notification(token, this) }
    }

    private fun dispatch(source: BluetoothGatt, action: (BleGattListener) -> Unit) {
        // connectGatt에 Main Handler도 지정한다. 다른 스레드이면 post 후 handle을 다시 확인한다.
        if (Looper.myLooper() != handler.looper) {
            handler.post { dispatch(source, action) }
            return
        }
        if (gatt !== source) return
        listener()?.let(action)
    }

    override fun connect(): Boolean {
        check(gatt == null)
        gatt = device.connectGatt(context, backgroundConnect, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
        return gatt != null
    }

    override fun discoverServices() = gatt?.discoverServices() == true

    override fun profile(): BleGattProfile {
        val service = gatt?.getService(SERVICE)
        val tx = service?.getCharacteristic(TX)
        rx = service?.getCharacteristic(RX)
        val properties = rx?.properties ?: 0
        mode = when {
            properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BleSubscriptionMode.INDICATE
            properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BleSubscriptionMode.NOTIFY
            else -> null
        }
        cccd = rx?.getDescriptor(CCCD)
        return BleGattProfile(service != null, tx != null, rx != null, cccd != null, mode)
    }

    override fun setNotifications(enabled: Boolean): Boolean {
        val characteristic = rx ?: return false
        return gatt?.setCharacteristicNotification(characteristic, enabled) == true
    }

    override fun writeSubscription(enabled: Boolean): Boolean {
        val owner = gatt ?: return false
        val descriptor = cccd ?: return false
        val value = if (!enabled) BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE else when (mode) {
            BleSubscriptionMode.INDICATE -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            BleSubscriptionMode.NOTIFY -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            null -> return false
        }
        if (!subscription.begin(enabled)) return false
        try {
            if (owner.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS) return true
        } catch (failure: Exception) {
            subscription.abort()
            throw failure
        }
        subscription.abort()
        return false
    }

    override fun disconnect() { gatt?.disconnect() }

    override fun close() {
        gatt?.close() // 예외이면 handle을 유지한다. 성공은 원격 CCCD/disconnect 확인을 대신하지 않는다.
        gatt = null
        rx = null
        cccd = null
        subscription.abort()
    }

    private companion object {
        val SERVICE: UUID = UUID.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")
        val TX: UUID = UUID.fromString("00000212-b2d1-43f0-9b88-960cebf8b91e")
        val RX: UUID = UUID.fromString("00000213-b2d1-43f0-9b88-960cebf8b91e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
