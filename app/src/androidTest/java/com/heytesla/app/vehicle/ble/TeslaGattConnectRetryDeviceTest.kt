package com.heytesla.app.vehicle.ble

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 실제 production helper/Handler 예약 검증. native 133·무선 연결 재현이 아니며 차량에 접근하지 않는다. */
@RunWith(AndroidJUnit4::class)
class TeslaGattConnectRetryDeviceTest {
    @Test fun elapsedWaitAndCancellationOnMainHandler() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handler = Handler(Looper.getMainLooper())
        val native = Any()
        val owner = TeslaNativeGattOwner<Any>()
        val connected = CountDownLatch(1)
        val canceledWakePassed = CountDownLatch(1)
        var live: TeslaGattConnectRetry? = null
        var canceled: TeslaGattConnectRetry? = null
        var startedAt = 0L
        var connectedAt = 0L
        var canceledConnects = 0
        val canceledEvents = mutableListOf<String>()
        val barrier = Runnable { canceledWakePassed.countDown() }
        try {
            instrumentation.runOnMainSync {
                assertTrue(owner.beginConnect())
                owner.attach(native)
                live = TeslaGattConnectRetry(
                    now = SystemClock::elapsedRealtime,
                    postDelayed = { task, delay -> handler.postDelayed(task, delay) },
                    remove = handler::removeCallbacks,
                    allowed = { true }, event = {},
                    failed = { connected.countDown() },
                    connect = {
                        assertEquals(true, owner.reconnect {
                            assertSame(native, it)
                            connectedAt = SystemClock.elapsedRealtime()
                            true
                        })
                        connected.countDown()
                    },
                )
                canceled = TeslaGattConnectRetry(
                    now = SystemClock::elapsedRealtime,
                    postDelayed = { task, delay -> handler.postDelayed(task, delay) },
                    remove = handler::removeCallbacks,
                    allowed = { true }, event = { canceledEvents.add(it) },
                    failed = {}, connect = { canceledConnects++ },
                )
                startedAt = SystemClock.elapsedRealtime()
                assertTrue(checkNotNull(live).retry(133, true))
                assertTrue(checkNotNull(live).retry(133, true))
                assertTrue(checkNotNull(canceled).retry(133, true))
                checkNotNull(canceled).seal()
                check(handler.postDelayed(barrier, 2_250L))
            }
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            assertTrue(canceledWakePassed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertTrue(connectedAt - startedAt >= 2_000L)
                assertSame(native, owner.handle)
                assertFalse(checkNotNull(live).retry(133, true))
                assertEquals(0, canceledConnects)
                assertEquals(listOf("TESLA_APPROACH_GATT_RETRY_SCHEDULED",
                    "TESLA_APPROACH_GATT_RETRY_CANCELED"), canceledEvents)
            }
        } finally {
            instrumentation.runOnMainSync {
                live?.seal()
                canceled?.seal()
                handler.removeCallbacks(barrier)
                assertTrue(owner.closeConfirmed({}, {}))
            }
        }
    }

    @Test fun postedPreviousGenerationCannotTerminateOrInitializeReconnectedHandle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handler = Handler(Looper.getMainLooper())
        val native = Any()
        val owner = TeslaNativeGattOwner<Any>()
        val dispatched = CountDownLatch(1)
        var staleEffects = 0
        var discoveries = 0
        var disconnects = 0
        var closes = 0
        var currentEntry = 0L
        val oldSuccess: Runnable
        val oldFailure: Runnable
        val newSuccess: Runnable
        // 콜백 진입에서 캡처한 generation을 MainHandler dispatch가 검사하는 실제 경계다.
        instrumentation.runOnMainSync {
            assertTrue(owner.beginConnect())
            owner.attach(native)
        }
        val previousEntry = owner.connectionGeneration
        oldSuccess = Runnable {
            if (owner.accepts(native, previousEntry)) {
                staleEffects++
                owner.connected(native, previousEntry)
            }
        }
        oldFailure = Runnable {
            if (owner.accepts(native, previousEntry)) {
                staleEffects++
                owner.seal()
            }
        }
        newSuccess = Runnable {
            if (owner.connected(native, currentEntry)) {
                val request = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.SERVICES,
                    SystemClock.elapsedRealtime()))
                assertTrue(owner.recordRequestResult(request, 0))
                discoveries++
            }
        }
        try {
            instrumentation.runOnMainSync {
                check(handler.post(oldSuccess))
                check(handler.post(oldFailure))
                // 같은 MainHandler 실행 안에서 재발급하므로 이전 callback 둘은 아직 게시 상태다.
                assertEquals(true, owner.reconnect { assertSame(native, it); true })
                currentEntry = owner.connectionGeneration
                check(handler.post(newSuccess))
                check(handler.post(newSuccess))
                check(handler.post { dispatched.countDown() })
            }
            assertTrue(dispatched.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(0, staleEffects)
                assertEquals(1, discoveries)
                assertSame(native, owner.handle)
                repeat(2) {
                    assertTrue(owner.closeConfirmed({ disconnects++ }, { closes++ }))
                }
                assertEquals(1, disconnects)
                assertEquals(1, closes)
            }
        } finally {
            instrumentation.runOnMainSync {
                handler.removeCallbacks(oldSuccess)
                handler.removeCallbacks(oldFailure)
                handler.removeCallbacks(newSuccess)
                owner.closeConfirmed({}, {})
            }
        }
    }

    @Test fun connectedDuringPendingDelayCancelsWakeAndDoesNotInitializeTwice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handler = Handler(Looper.getMainLooper())
        val native = Any()
        val owner = TeslaNativeGattOwner<Any>()
        val wakePassed = CountDownLatch(1)
        var retry: TeslaGattConnectRetry? = null
        var reconnects = 0
        var discoveries = 0
        val barrier = Runnable { wakePassed.countDown() }
        var connectedCallback: Runnable? = null
        try {
            instrumentation.runOnMainSync {
                assertTrue(owner.beginConnect())
                owner.attach(native)
                val scheduler = TeslaGattConnectRetry(
                    now = SystemClock::elapsedRealtime,
                    postDelayed = { task, delay -> handler.postDelayed(task, delay) },
                    remove = handler::removeCallbacks,
                    allowed = { true }, event = {},
                    failed = { throw AssertionError("MainHandler 예약 거절") },
                    connect = { reconnects++ },
                )
                retry = scheduler
                assertTrue(scheduler.retry(133, owner.canReconnect))
                assertTrue(scheduler.retry(133, owner.canReconnect))
                val captured = owner.connectionGeneration
                val callback = Runnable {
                    if (owner.connected(native, captured)) {
                        scheduler.seal()
                        val request = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.SERVICES,
                            SystemClock.elapsedRealtime()))
                        assertTrue(owner.recordRequestResult(request, 0))
                        discoveries++
                    }
                }
                connectedCallback = callback
                check(handler.post(callback))
                check(handler.post(callback))
                check(handler.postDelayed(barrier, 2_250L))
            }
            assertTrue(wakePassed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(0, reconnects)
                assertEquals(1, discoveries)
                assertFalse(checkNotNull(retry).retry(133, owner.canReconnect))
                assertSame(native, owner.handle)
            }
        } finally {
            instrumentation.runOnMainSync {
                retry?.seal()
                connectedCallback?.let(handler::removeCallbacks)
                handler.removeCallbacks(barrier)
                owner.closeConfirmed({}, {})
            }
        }
    }
}
