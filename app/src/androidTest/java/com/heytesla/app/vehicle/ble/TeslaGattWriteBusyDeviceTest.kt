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

/** 생산 펌프·실제 MainHandler 검증. SDK 반환/완료는 합성이며 무선·차량 성공 시험이 아니다. */
@RunWith(AndroidJUnit4::class)
class TeslaGattWriteBusyDeviceTest {
    @Test fun busyDelayBoundAndCancellationOnMainHandler() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handler = Handler(Looper.getMainLooper())
        for (mode in listOf("accepted", "exhausted", "canceled")) {
            val barrierPassed = CountDownLatch(1)
            val barrier = Runnable { barrierPassed.countDown() }
            val issuedAt = mutableListOf<Long>()
            var completions = 0
            var deadline = 0L
            lateinit var pump: TeslaGattWritePump
            var created: TeslaGattWritePump? = null
            try {
                instrumentation.runOnMainSync {
                    deadline = SystemClock.elapsedRealtime() + 5_000L
                    pump = TeslaGattWritePump(
                        post = { check(handler.post(it)) },
                        postDelayed = { task, delay -> handler.postDelayed(task, delay) },
                        now = SystemClock::elapsedRealtime,
                        remove = handler::removeCallbacks,
                        allowed = { SystemClock.elapsedRealtime() < deadline },
                        onQueued = {},
                        submit = {
                            assertTrue(pump.markWriteIssued())
                            issuedAt.add(SystemClock.elapsedRealtime())
                            val status = if (mode == "accepted" && issuedAt.size == 2) 0 else 201
                            pump.recordWriteResult(status)
                            if (status == 0) {
                                assertFalse(pump.retryBusy(deadline))
                                check(handler.post {
                                    assertEquals(TeslaGattWriteCompletion.SUCCEEDED, pump.completeWrite(0))
                                    completions++
                                    assertEquals(TeslaGattWriteCompletion.IGNORED, pump.completeWrite(0))
                                })
                            } else {
                                assertEquals(TeslaGattWriteCompletion.IGNORED, pump.completeWrite(0))
                                assertEquals(issuedAt.size < 3, pump.retryBusy(deadline))
                                if (mode == "canceled") pump.seal()
                            }
                            if (status == 0 || issuedAt.size == 3 || mode == "canceled") {
                                check(handler.postDelayed(barrier, 150L))
                            }
                        },
                    )
                    created = pump
                    assertTrue(pump.queue())
                    assertFalse(pump.queue())
                }
                assertTrue(barrierPassed.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    val expectedCalls = when (mode) { "accepted" -> 2; "exhausted" -> 3; else -> 1 }
                    assertEquals(expectedCalls, issuedAt.size)
                    assertEquals(expectedCalls - 1, pump.busyRetryCount)
                    assertEquals(if (mode == "accepted") 1 else 0, completions)
                    for (index in 1 until issuedAt.size) assertTrue(issuedAt[index] - issuedAt[index - 1] >= 100L)
                    assertFalse(pump.retryBusy(deadline))
                    if (mode != "accepted") assertFalse(pump.queue())
                    println("WRITE_BUSY_HANDLER mode=$mode calls=${issuedAt.size} retries=${pump.busyRetryCount} completions=$completions")
                }
            } finally {
                instrumentation.runOnMainSync {
                    created?.seal()
                    handler.removeCallbacks(barrier)
                }
            }
        }
    }
}
