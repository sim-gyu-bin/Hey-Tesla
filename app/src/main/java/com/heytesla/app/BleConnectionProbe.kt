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

/** 등록 주소와 GATT는 이 소유자 내부 RAM에만 둔다. 키·인증·차량 characteristic write는 없다. */
internal class BleConnectionProbe(
    context: Context,
    private val state: MutableStateFlow<BleProbeState>,
    private val runtime: DiagnosticRuntime,
) {
    private val app = context.applicationContext
    private val bluetooth = app.getSystemService(BluetoothManager::class.java)
    private var current: BleGattSession? = null
    private var associationId: Int? = null
    private var watchdog: Runnable? = null

    @SuppressLint("MissingPermission")
    fun start() {
        mainThread()
        val previous = current
        if (previous != null) {
            runtime.event(if (previous.state.status == BleProbeStatus.CLEANUP_FAILED) "BLE_PROBE_RESTART_REQUIRED" else "BLE_PROBE_BUSY")
            return
        }
        val session = runtime.acquireBleDiagnostic(::cancel)
        if (session == null) {
            blocked(runtime.bleDiagnosticBlockedReason() ?: "LEASE_UNAVAILABLE")
            return
        }
        // acquire와 resolve는 같은 Main turn이다. BLUETOOTH_CONNECT 외 권한·새 스캔을 요구하지 않는다.
        val device: BluetoothDevice
        try {
            val selected = singleAssociation()
            val address = selected?.deviceMacAddress
            val adapter = bluetooth?.adapter
            val reason = when {
                selected == null -> "SINGLE_ASSOCIATION_REQUIRED"
                address == null -> "ASSOCIATION_ADDRESS_UNAVAILABLE"
                adapter == null -> "BLUETOOTH_UNAVAILABLE"
                !adapter.isEnabled -> "BLUETOOTH_OFF"
                else -> null
            }
            if (reason != null) {
                runtime.releaseBleDiagnostic(session.id) // GATT는 아직 생성하지 않았다.
                blocked(reason)
                return
            }
            // MacAddress 문자열은 소문자이며 String overload는 대문자만 허용한다.
            device = checkNotNull(adapter).getRemoteDevice(checkNotNull(address).toByteArray())
            associationId = checkNotNull(selected).id
        } catch (_: SecurityException) {
            runtime.releaseBleDiagnostic(session.id)
            blocked("BLUETOOTH_PERMISSION_REQUIRED")
            return
        } catch (_: Exception) {
            runtime.releaseBleDiagnostic(session.id)
            blocked("ASSOCIATION_RESOLVE_FAILED")
            return
        }
        val port = AndroidBleGattPort(app, device, session.id, runtime.handler) {
            val owner = current
            if (owner != null) advance(owner)
            owner
        }
        val trial = BleGattSession(
            session.id, session.deadline, port, runtime::now,
            publish = { next ->
                state.value = next
                if (!next.active && next.status != BleProbeStatus.IDLE) runtime.event("BLE_PROBE_${next.status.name}")
            },
            release = {
                runtime.releaseBleDiagnostic(session.id)
                watchdog?.let { runtime.handler.removeCallbacks(it) }
                watchdog = null
                associationId = null
                current = null
            },
        )
        current = trial
        runtime.event("BLE_PROBE_STARTED")
        trial.start()
        if (current !== trial || !trial.state.active) return
        val tick = object : Runnable {
            override fun run() {
                if (current !== trial) return
                advance(trial)
                if (current === trial && trial.state.active) runtime.handler.postDelayed(this, WATCHDOG_MS)
                else watchdog = null
            }
        }
        watchdog = tick
        runtime.handler.postDelayed(tick, WATCHDOG_MS)
    }

    fun cancel() {
        mainThread()
        current?.cancel()
    }

    private fun blocked(reason: String) {
        associationId = null
        state.value = BleProbeState(status = BleProbeStatus.BLOCKED, reason = reason)
        runtime.event("BLE_PROBE_BLOCKED")
    }

    private fun advance(trial: BleGattSession) {
        if (!trial.state.active) return
        trial.tick(if (trial.state.status == BleProbeStatus.CLEANING_UP) null else prerequisiteReason(trial.token))
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
        if (!runtime.activityVisible) return "VISIBLE_UI_REQUIRED"
        if (!runtime.granted(Manifest.permission.BLUETOOTH_CONNECT)) return "BLUETOOTH_PERMISSION_REVOKED"
        val snapshot = runtime.state.value
        if (snapshot.enabled || snapshot.observing || snapshot.observationStartPending || snapshot.observationServiceRunning ||
            snapshot.speechDiagnosticActive || runtime.microphone != null
        ) return "DIAGNOSTIC_EXCLUSIVITY_LOST"
        return try {
            val adapter = bluetooth?.adapter
            when {
                adapter == null -> "BLUETOOTH_UNAVAILABLE"
                !adapter.isEnabled -> "BLUETOOTH_OFF"
                else -> {
                    val selected = singleAssociation()
                    if (selected == null || selected.id != associationId || selected.deviceMacAddress == null) "ASSOCIATION_REMOVED" else null
                }
            }
        } catch (_: SecurityException) {
            "BLUETOOTH_PERMISSION_REVOKED"
        } catch (_: Exception) {
            "PREREQUISITE_READ_FAILED"
        }
    }

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private companion object {
        const val WATCHDOG_MS = 100L
    }
}

/** 각 callback은 token과 동일 BluetoothGatt·RX/CCCD handle을 확인한 뒤 Main에 전달한다. */
@SuppressLint("MissingPermission")
private class AndroidBleGattPort(
    private val context: Context,
    private val device: BluetoothDevice,
    private val token: Long,
    private val handler: Handler,
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
            dispatch(gatt) { it.connection(token, this@AndroidBleGattPort, status == BluetoothGatt.GATT_SUCCESS, newState == BluetoothProfile.STATE_CONNECTED) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            dispatch(gatt) { it.services(token, this@AndroidBleGattPort, status == BluetoothGatt.GATT_SUCCESS) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            dispatch(gatt) { owner ->
                if (descriptor !== cccd) return@dispatch
                when (subscription.writeCompleted(status == BluetoothGatt.GATT_SUCCESS)) {
                    BleCccdWriteResult.IGNORE -> Unit
                    BleCccdWriteResult.ENABLED -> owner.descriptor(token, this@AndroidBleGattPort, true, true)
                    BleCccdWriteResult.ENABLE_FAILED -> owner.descriptor(token, this@AndroidBleGattPort, true, false)
                    BleCccdWriteResult.DISABLE_FAILED -> owner.descriptor(token, this@AndroidBleGattPort, false, false)
                    BleCccdWriteResult.VERIFY_DISABLED -> {
                        // 중복 enable callback을 disable 완료로 추정하지 않는다. 실제 CCCD 0을 읽어 확인한다.
                        val accepted = try { gatt.readDescriptor(descriptor) } catch (_: Exception) { false }
                        if (!accepted) {
                            subscription.abort()
                            owner.descriptor(token, this@AndroidBleGattPort, false, false)
                        }
                    }
                }
            }
        }

        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int, value: ByteArray) {
            dispatch(gatt) { owner ->
                if (descriptor !== cccd) return@dispatch
                val disabled = subscription.readCompleted(status == BluetoothGatt.GATT_SUCCESS, value) ?: return@dispatch
                owner.descriptor(token, this@AndroidBleGattPort, false, disabled)
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
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
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
