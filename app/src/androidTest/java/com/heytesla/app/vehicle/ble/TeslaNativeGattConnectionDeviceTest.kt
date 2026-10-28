package com.heytesla.app.vehicle.ble

import com.heytesla.app.vehicle.uwb.TeslaServiceUwbGattAdmission

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 명시 선택한 비차량 lab 대상의 SDK 생성·기존 handle 재접속·취소/close 경계만 검사한다. 속성/TX는 발행하지 않는다. */
@SuppressLint("MissingPermission")
@RunWith(AndroidJUnit4::class)
class TeslaNativeGattConnectionDeviceTest {
    @Test fun actualSdkHandleIsAttachedBeforeCancellationAndCannotAcceptCallbacksAfterClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val address = args.getString("nativeGattLabAddress")
        assumeTrue("비차량 lab 대상과 smoke 실행을 명시해야 합니다",
            args.getString("runNativeGattLabSmoke") == "true" && address != null)
        require(address != null && BluetoothAdapter.checkBluetoothAddress(address))
        val context = instrumentation.targetContext
        assumeTrue(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        assumeTrue(adapter != null && adapter.isEnabled)
        val associations = context.getSystemService(CompanionDeviceManager::class.java).myAssociations
        assertFalse("등록 차량을 lab 대상으로 사용하지 않습니다", associations.any {
            it.deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true
        })
        val handler = Handler(Looper.getMainLooper())
        val owner = TeslaNativeGattOwner<BluetoothGatt>()
        val admission = TeslaServiceUwbGattAdmission()
        val callbackFailure = AtomicReference<Throwable?>()
        val callbackEntries = AtomicInteger()
        val barrier = CountDownLatch(1)
        var closed = false
        var native: BluetoothGatt? = null
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                callbackEntries.incrementAndGet()
                try {
                    assertSame(handler.looper, Looper.myLooper())
                    // 이 lab은 callback에서 서비스를 찾거나 전송하지 않는다.
                    assertFalse(owner.accepts(gatt))
                } catch (error: Throwable) { callbackFailure.compareAndSet(null, error) }
            }
        }
        try {
            instrumentation.runOnMainSync {
                assertTrue(admission.requireFresh)
                val allocated = owner.connectNative(context, checkNotNull(adapter).getRemoteDevice(address), callback, handler)
                native = allocated
                assertNotNull("실제 SDK가 GATT client를 반환해야 합니다", allocated)
                assertSame(allocated, owner.handle)
                admission.returnedHandleOwned(allocated, owner.handle)
                assertFalse(admission.requireFresh)
                assertFalse(owner.allocating)
                assertFalse(owner.allocationUncertain)
                assertTrue(owner.accepts(checkNotNull(allocated)))
                owner.seal()
                closed = owner.closeNative()
                assertTrue("로컬 close 호출이 정상 반환해야 합니다", closed)
                assertNull(owner.handle)
                assertFalse(owner.accepts(allocated))
                assertFalse(admission.requireFresh)
                assertFalse(owner.beginConnect())
                assertEquals(TeslaNativeGattStage.SEALED, owner.stage)
                check(handler.postDelayed({ barrier.countDown() }, 750L))
            }
            assertTrue(barrier.await(5, TimeUnit.SECONDS))
            callbackFailure.get()?.let { throw it }
            instrumentation.runOnMainSync {
                assertNull(owner.handle)
                assertFalse(owner.accepts(checkNotNull(native)))
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "GATT_LAB_SMOKE: sdkHandleAttached=true localCloseReturned=true sealedGuard=true nativeCallbackCount=${callbackEntries.get()}; RF/MTU/CCCD/TX untested\n")
            })
        } finally {
            instrumentation.runOnMainSync {
                if (!closed) assertTrue("lab handle도 close 결과를 숨기지 않습니다", owner.closeNative())
            }
        }
    }

    @Test fun actualExistingSdkHandleReconnectsOnceThenClosesWithoutAcceptingOldGeneration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val address = args.getString("nativeGattLabAddress")
        assumeTrue("비차량 lab 대상과 smoke 실행을 명시해야 합니다",
            args.getString("runNativeGattLabSmoke") == "true" && address != null)
        require(address != null && BluetoothAdapter.checkBluetoothAddress(address))
        val context = instrumentation.targetContext
        assumeTrue(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        assumeTrue(adapter != null && adapter.isEnabled)
        assertFalse(context.getSystemService(CompanionDeviceManager::class.java).myAssociations.any {
            it.deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true
        })
        val handler = Handler(Looper.getMainLooper())
        val owner = TeslaNativeGattOwner<BluetoothGatt>()
        val admission = TeslaServiceUwbGattAdmission()
        val barrier = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val callbacks = AtomicInteger()
        var native: BluetoothGatt? = null
        var autoConnect: Boolean? = null
        var accepted: Boolean? = null
        var closed = false
        var started = 0L
        var issued = 0L
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                callbacks.incrementAndGet()
                // 실제 callback이 있어도 이 lab은 탐색·MTU·구독·TX를 발급하지 않는다.
                try {
                    assertSame(handler.looper, Looper.myLooper())
                    if (closed) assertFalse(owner.accepts(gatt))
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }
        }
        val reconnect = Runnable {
            try {
                val original = checkNotNull(native)
                val previousGeneration = owner.connectionGeneration
                issued = android.os.SystemClock.elapsedRealtime()
                accepted = owner.reconnect { retained ->
                    assertSame(original, retained)
                    retained.connect()
                }
                assertNotNull("SDK를 실제 호출한 Boolean 반환과 미발급 null을 구분합니다", accepted)
                assertSame(original, owner.handle)
                admission.returnedHandleOwned(null, owner.handle)
                assertFalse(admission.requireFresh) // SDK 재접속 접수값은 최초 handle 입장을 되돌리지 않는다.
                assertFalse(owner.accepts(original, previousGeneration))
                assertEquals(previousGeneration + 1, owner.connectionGeneration)
                assertNull(owner.reconnect { throw AssertionError("두 번째 SDK 재접속은 금지합니다") })
                owner.seal()
                closed = owner.closeNative()
                assertTrue(closed)
                assertNull(owner.handle)
                assertFalse(owner.accepts(original))
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { barrier.countDown() }
        }
        try {
            instrumentation.runOnMainSync {
                native = owner.connectNative(context, checkNotNull(adapter).getRemoteDevice(address), callback, handler) {
                    autoConnect = it
                }
                admission.returnedHandleOwned(native, owner.handle)
                assertFalse(admission.requireFresh)
                assertNotNull(native)
                started = android.os.SystemClock.elapsedRealtime()
                check(handler.postDelayed(reconnect, 2_000L))
            }
            assertTrue(barrier.await(8, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertTrue("실제 Handler가 2초 이전에 재접속해서는 안 됩니다", issued - started >= 2_000L)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "NATIVE_RECONNECT_SMOKE: autoConnect=$autoConnect sameHandle=true " +
                    "sdkConnectAccepted=$accepted delayMs=${issued - started} localCloseReturned=$closed " +
                    "oldGenerationRejected=true nativeCallbackCount=${callbacks.get()}; native133/RF/attributes/TX untested\n")
            })
        } finally {
            instrumentation.runOnMainSync {
                handler.removeCallbacks(reconnect)
                if (!closed) assertTrue(owner.closeNative())
            }
        }
    }
}
