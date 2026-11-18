package com.heytesla.app.vehicle.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.content.Context
import android.os.Handler

/** Production과 기기 lab이 같은 실제 SDK 생성/소유권 부착 경계를 사용한다. */
@SuppressLint("MissingPermission")
internal fun TeslaNativeGattOwner<BluetoothGatt>.connectNative(
    context: Context,
    device: BluetoothDevice,
    callback: BluetoothGattCallback,
    handler: Handler,
    onIssuing: (autoConnect: Boolean) -> Unit = {},
): BluetoothGatt? {
    check(beginConnect())
    return try {
        val autoConnect = true // minSdk36: 지원 OS 모두 Tesla의 SDK34+ 연결 조건에 해당한다.
        onIssuing(autoConnect)
        val native = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK, handler)
        attach(native)
        native
    } catch (exception: Exception) {
        allocationFailed()
        throw exception
    }
}

/** true는 close()의 정상 반환뿐이다. 원격 disconnect ACK나 플랫폼 자원 해제 증명은 아니다. */
@SuppressLint("MissingPermission")
internal fun TeslaNativeGattOwner<BluetoothGatt>.closeNative(): Boolean =
    closeConfirmed(BluetoothGatt::disconnect, BluetoothGatt::close)
