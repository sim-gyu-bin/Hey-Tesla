package com.heytesla.app.vehicle.ble

import com.heytesla.app.ui.VehicleVinUiTestHost
import com.heytesla.app.vehicle.key.TeslaBleKeyStore
import com.heytesla.app.vehicle.key.TeslaKeyOutcome
import com.heytesla.app.vehicle.key.TeslaKeyStage

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 격리 합성 VIN/키로 실제 probe를 시작하되 settle 안에서 취소한다. 연결·TX·키 등록은 금지한다. */
@RunWith(AndroidJUnit4::class)
class TeslaGattConnectSettleDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val fixture = VehicleVinUiTestHost()

    @After fun closeFixture() = fixture.close()

    @Test fun cancelAfterCredentialPreparationDuringSettleClosesWithoutAnyConnectionOrTransmission() {
        assumeTrue("실제 probe settle fixture는 명시 선택해야 합니다",
            InstrumentationRegistry.getArguments().getString("runNativeSettleFixture") == "true")
        fixture.launch()
        fixture.seedRegisteredVin()
        // fixture runtime과 동일한 test-owned alias만 준비한다. 사용자 키를 읽거나 바꾸지 않는다.
        TeslaBleKeyStore(instrumentation.targetContext, "heytesla.key.test.ui-regression.v1").getOrCreate()
        val runtime = fixture.runtime
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val canceled = CountDownLatch(1)
        var cancellationTask: Runnable? = null
        var failure: Throwable? = null
        var preparedAt = 0L
        var canceledAt = 0L
        try {
            instrumentation.runOnMainSync {
                runtime.refresh()
                assumeTrue("권한·Bluetooth ON·단일 기존 association·화면 잠금 해제가 필요합니다",
                    runtime.teslaKeyBlockedReason() == null)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runtime.teslaKeyState.first { it.credentialReady }
                    preparedAt = SystemClock.elapsedRealtime()
                    val task = Runnable {
                        try {
                            val before = runtime.teslaKeyState.value
                            // KEY 준비 전 취소가 아니다. 500ms 대기가 없다면 이미 CONNECTING 이후다.
                            assertTrue(before.credentialReady)
                            assertEquals(TeslaKeyStage.PREPARING_KEY, before.stage)
                            assertFalse(before.transmissionAttempted)
                            assertTrue(runtime.teslaKeyReserved())
                            canceledAt = SystemClock.elapsedRealtime()
                        } catch (error: Throwable) {
                            failure = error
                        } finally {
                            runtime.cancelTeslaKey("SETTLE_DEVICE_REGRESSION")
                            canceled.countDown()
                        }
                    }
                    cancellationTask = task
                    // Main 큐에서 +100ms가 +500ms wake보다 먼저 실행된다. Main을 sleep시키지 않는다.
                    check(runtime.handler.postDelayed(task, 100L))
                }
                runtime.startTeslaKey(register = false)
            }
            assertTrue("기존 credential 준비 뒤 settle 취소가 실행되어야 합니다", canceled.await(10, TimeUnit.SECONDS))
            failure?.let { throw it }
            assertTrue(canceledAt - preparedAt >= 100L)
            val oldWakePassed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                check(runtime.handler.postDelayed({ oldWakePassed.countDown() }, 750L))
            }
            assertTrue(oldWakePassed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val result = runtime.teslaKeyState.value
                assertEquals(TeslaKeyStage.CANCELED, result.stage)
                assertEquals(TeslaKeyOutcome.CANCELED, result.outcome)
                assertTrue(result.localClosed)
                assertFalse(result.transmissionAttempted)
                assertFalse(result.subscriptionConfirmed)
                assertFalse(result.registrationRequested)
                assertNull(result.status)
                assertFalse(runtime.teslaKeyReserved())
                assertFalse(runtime.state.value.teslaKeyDiagnosticActive)
                // 실패/취소 뒤 lease 재획득도 막히지 않아야 한다. 재연결은 하지 않는다.
                val nextLease = runtime.acquireTeslaKey()
                assertNotNull(nextLease)
                nextLease?.let(runtime::releaseTeslaKey)
            }
        } finally {
            instrumentation.runOnMainSync {
                cancellationTask?.let(runtime.handler::removeCallbacks)
                runtime.cancelTeslaKey("SETTLE_DEVICE_FIXTURE_CLOSED")
                scope.cancel()
            }
        }
    }
}
