package com.heytesla.app.vehicle.uwb

import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.protocol.TeslaFiraCapabilities
import com.heytesla.app.vehicle.protocol.TeslaFiraMessage
import com.heytesla.app.vehicle.protocol.TeslaFiraNegotiation
import com.heytesla.app.vehicle.protocol.TeslaFiraPlan
import com.heytesla.app.vehicle.protocol.TeslaFiraProtocol
import com.heytesla.app.vehicle.protocol.TeslaFiraRequest

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 독립 coordinator의 실제 Handler/Listener 경계. RF·BLE·키·마이크·전역 runtime은 열지 않는다. */
@RunWith(AndroidJUnit4::class)
class TeslaFiraCoordinatorDeviceTest {
    @Test fun vehicleStopBeforeResponseCompletionCannotStartClosedPlansOrFinishBeforeStoppedTx() = withFixture { fixture ->
        onHandler {
            fixture.request(0L, 11L)
            val plans = fixture.plans()
            fixture.stop(0, 11)
            assertEquals(2, fixture.frames.size)
            assertEquals(listOf(0, 11), stoppedValues(fixture.frames[1].body))
            plans.forEach(::assertWiped)
            assertTrue(fixture.finishes.isEmpty())

            // response는 아직 송신 완료 대기 중이었다. stop으로 닫힌 plan을 다시 시작하면 안 된다.
            fixture.frames[0].complete()
            fixture.assertNoBackend()
            assertTrue(fixture.finishes.isEmpty())
            fixture.frames[1].complete()
            assertEquals(listOf(TeslaKeyStage.FAILED to "NO_DISTANCE_RECEIVED"), fixture.finishes)
            assertEquals(listOf(false), fixture.cleanupResults)
            fixture.frames[0].complete()
            fixture.frames[1].complete()
            assertEquals(1, fixture.finishes.size)
            assertEquals(2, fixture.frames.size)
            assertEquals(1, fixture.events.count { it == "TESLA_UWB_SESSION_STOPPED_SENT" })
            fixture.assertNoBackend()
        }
    }

    @Test fun unrepresentableLocalCloseCompletesWithoutStoppedTxOrDuplicateNotSent() = withFixture { fixture ->
        onHandler {
            val ids = setOf(12L, 0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL)
            fixture.request(*ids.toLongArray())
            val plans = fixture.plans()
            fixture.closeSessions(ids)
            plans.forEach(::assertWiped)
            assertEquals(1, fixture.frames.size) // response만 존재하며 빈 stopped는 전송하지 않는다.
            assertEquals(listOf(TeslaKeyStage.FAILED to "NO_DISTANCE_RECEIVED"), fixture.finishes)
            assertEquals(listOf(false), fixture.cleanupResults)
            assertEquals(1, fixture.events.count { it == "TESLA_UWB_SESSION_STOPPED_NOT_SENT" })
            assertFalse(fixture.events.contains("TESLA_UWB_SESSION_STOPPED_SENT"))
            fixture.frames[0].complete()
            fixture.closeSessions(ids)
            assertEquals(1, fixture.finishes.size)
            assertEquals(1, fixture.frames.size)
            fixture.assertNoBackend()
        }
    }

    @Test fun largeIdTimeoutClosesEveryPlanAndFinishesWithoutAnUnrepresentableStoppedFrame() = withFixture { fixture ->
        onHandler {
            fixture.request(0x8000_0000L, 0xffff_ffffL)
            val plans = fixture.plans()
            // response callback으로 RF를 열지 않고 기존 순수 측정기한만 시작한다.
            fixture.startMeasurementWindow()
            fixture.clock += 60_000L
            fixture.coordinator.checkReadiness()
            plans.forEach(::assertWiped)
            assertEquals(listOf(TeslaKeyStage.TIMED_OUT to "NO_DISTANCE_RECEIVED"), fixture.finishes)
            assertEquals(listOf(false), fixture.cleanupResults)
            assertEquals(1, fixture.frames.size)
            assertEquals(1, fixture.events.count { it == "TESLA_UWB_SESSION_STOPPED_NOT_SENT" })
            fixture.frames[0].complete()
            fixture.coordinator.checkReadiness()
            assertEquals(1, fixture.finishes.size)
            fixture.assertNoBackend()
        }
    }

    @Test fun partialEnumStopPreservesLargeSessionsAndIndependentLocalClosesWaitForPendingTx() = withFixture { fixture ->
        onHandler {
            fixture.request(11L, 0x8000_0000L, 0xffff_ffffL)
            val plans = fixture.plans()
            val secondAddress = plans[1].localAddress.copyOf()
            val secondSts = plans[1].sts.copyOf()
            val thirdAddress = plans[2].localAddress.copyOf()
            val thirdSts = plans[2].sts.copyOf()
            try {
                fixture.state = fixture.state.copy(livePeerIndices = setOf(0, 1, 2))
                fixture.stop(11)
                assertWiped(plans[0])
                assertEquals(setOf(1, 2), fixture.state.livePeerIndices)
                assertArrayEquals(secondAddress, plans[1].localAddress)
                assertArrayEquals(secondSts, plans[1].sts)
                assertArrayEquals(thirdAddress, plans[2].localAddress)
                assertArrayEquals(thirdSts, plans[2].sts)
                assertEquals(listOf(11), stoppedValues(fixture.frames[1].body))

                fixture.closeSessions(setOf(0x8000_0000L))
                assertWiped(plans[1])
                assertEquals(setOf(2), fixture.state.livePeerIndices)
                assertArrayEquals(thirdAddress, plans[2].localAddress)
                assertArrayEquals(thirdSts, plans[2].sts)
                assertTrue(fixture.finishes.isEmpty())
                fixture.closeSessions(setOf(0xffff_ffffL))
                plans.forEach(::assertWiped)
                assertTrue(fixture.state.livePeerIndices.isEmpty())
                assertTrue(fixture.finishes.isEmpty()) // enum batch의 실제 송신 완료는 아직 오지 않았다.
                assertEquals(2, fixture.frames.size)
                assertEquals(2, fixture.events.count { it == "TESLA_UWB_SESSION_STOPPED_NOT_SENT" })
                fixture.frames[0].complete()
                fixture.assertNoBackend()
                fixture.frames[1].complete()
                assertEquals(listOf(TeslaKeyStage.FAILED to "NO_DISTANCE_RECEIVED"), fixture.finishes)
                assertEquals(listOf(false), fixture.cleanupResults)
            } finally {
                secondAddress.fill(0); secondSts.fill(0); thirdAddress.fill(0); thirdSts.fill(0)
            }
        }
    }

    @Test fun mixedLocalCloseSendsOnlyRepresentableIdsAndSettlesTheWholeBatch() = withFixture { fixture ->
        onHandler {
            val ids = setOf(0L, 12L, 11L, 0x8000_000bL, 0xffff_ffffL)
            fixture.request(*ids.toLongArray())
            val plans = fixture.plans()
            fixture.closeSessions(ids)
            plans.forEach(::assertWiped)
            assertEquals(2, fixture.frames.size)
            assertEquals(listOf(0, 11), stoppedValues(fixture.frames[1].body))
            assertTrue(fixture.finishes.isEmpty())
            fixture.frames[1].complete()
            assertEquals(listOf(TeslaKeyStage.FAILED to "NO_DISTANCE_RECEIVED"), fixture.finishes)
            assertEquals(listOf(false), fixture.cleanupResults)
            assertEquals(1, fixture.events.count { it == "TESLA_UWB_SESSION_STOPPED_SENT" })
            assertFalse(fixture.events.contains("TESLA_UWB_SESSION_STOPPED_NOT_SENT"))
            fixture.frames[0].complete()
            fixture.frames[1].complete()
            assertEquals(1, fixture.finishes.size)
            assertEquals(2, fixture.frames.size)
            fixture.assertNoBackend()
        }
    }

    @Test fun cancellationSealsPendingResponseAndStoppedCallbacksWithoutNewTx() {
        for (stopFirst in listOf(false, true)) withFixture { fixture ->
            onHandler {
                fixture.request(0L, 0xffff_ffffL)
                val plans = fixture.plans()
                if (stopFirst) fixture.stop(0)
                val frameCount = fixture.frames.size
                fixture.coordinator.close { fixture.cleanupResults.add(it) }
                plans.forEach(::assertWiped)
                assertEquals(listOf(false), fixture.cleanupResults)
                fixture.frames.forEach { it.complete(); it.complete() }
                assertEquals(frameCount, fixture.frames.size)
                assertTrue(fixture.finishes.isEmpty())
                assertFalse(fixture.events.contains("TESLA_UWB_SESSION_STOPPED_SENT"))
                val lateRequest = TeslaFiraRequest(12L, byteArrayOf(3, 4), 1, byteArrayOf(), 0)
                fixture.coordinator.receive(TeslaFiraMessage.Request(listOf(lateRequest)))
                assertTrue(lateRequest.addresses.all { it == 0.toByte() })
                assertEquals(frameCount, fixture.frames.size)
                fixture.assertNoBackend()
            }
        }
    }

    @Test fun emptyUnknownAndUnmatchedStopsFailClosedWithoutPartialStoppedTx() {
        val cases = listOf(
            emptyList<Int>() to "INVALID_STOP_ENDPOINT",
            listOf(-1) to "INVALID_STOP_ENDPOINT",
            listOf(12) to "INVALID_STOP_ENDPOINT",
            listOf(Int.MIN_VALUE) to "INVALID_STOP_ENDPOINT",
            listOf(11) to "UNKNOWN_STOP_ENDPOINT",
            listOf(0, 11) to "UNKNOWN_STOP_ENDPOINT",
        )
        for ((endpoints, reason) in cases) withFixture { fixture ->
            onHandler {
                fixture.request(0L, 0x8000_000bL, 0xffff_ffffL)
                val plans = fixture.plans()
                fixture.stop(*endpoints.toIntArray())
                plans.forEach(::assertWiped)
                assertEquals(listOf(TeslaKeyStage.FAILED to reason), fixture.finishes)
                assertEquals(listOf(false), fixture.cleanupResults)
                assertEquals(1, fixture.frames.size)
                fixture.frames[0].complete()
                fixture.stop(0)
                assertEquals(1, fixture.finishes.size)
                assertEquals(1, fixture.frames.size)
                fixture.assertNoBackend()
            }
        }
    }

    private class Frame(val body: ByteArray, val complete: () -> Unit)

    private class NoServicesContext(base: Context) : ContextWrapper(base) {
        var backendAccesses = 0
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? {
            backendAccesses++
            throw AssertionError("Coordinator fixture must not access a system service")
        }
        override fun checkSelfPermission(permission: String): Int {
            backendAccesses++
            throw AssertionError("Coordinator fixture must not enter RF permission handling")
        }
    }

    private class Fixture {
        private val context = NoServicesContext(InstrumentationRegistry.getInstrumentation().targetContext)
        var clock = 1_000L
        var state = TeslaUwbProbeState()
        val frames = ArrayList<Frame>()
        val events = ArrayList<String>()
        val finishes = ArrayList<Pair<TeslaKeyStage, String?>>()
        val cleanupResults = ArrayList<Boolean>()
        lateinit var coordinator: TeslaUwbNegotiation
            private set

        init {
            coordinator = TeslaUwbNegotiation(AndroidTeslaUwbPlatform(context),
                AndroidTeslaUwbScheduler(Handler(Looper.getMainLooper())), { clock }, { 1_792_360_988_000L },
                object : TeslaUwbNegotiation.Listener {
                    override fun onCapabilitiesReady() = throw AssertionError("No native capability registration")
                    override fun onSend(body: ByteArray, frameComplete: () -> Unit) {
                        check(Looper.myLooper() == Looper.getMainLooper())
                        frames.add(Frame(body, frameComplete))
                    }
                    override fun onState(transform: (TeslaUwbProbeState) -> TeslaUwbProbeState, event: String) {
                        check(Looper.myLooper() == Looper.getMainLooper())
                        state = transform(state)
                        events.add(event)
                    }
                    override fun onFinish(stage: TeslaKeyStage, reason: String?) {
                        finishes.add(stage to reason)
                        // 실제 probe Listener와 같은 owner 정리 경로이며, 전역 runtime은 참조하지 않는다.
                        coordinator.close { cleanupResults.add(it) }
                    }
                })
            TeslaUwbNegotiation::class.java.getDeclaredField("negotiation").apply { isAccessible = true }
                .set(coordinator, TeslaFiraNegotiation(TeslaFiraCapabilities(
                    true, true, listOf(5), listOf(9), listOf(2), listOf(2), listOf(3))))
        }

        fun request(vararg ids: Long) {
            val body = proto {
                writeByteArray(54, proto {
                    ids.forEachIndexed { index, id ->
                        writeByteArray(1, proto {
                            if (id != 0L) writeUInt32(1, id.toInt())
                            writeByteArray(3, byteArrayOf(1, (index + 2).toByte()))
                            writeEnum(7, 1)
                        })
                    }
                })
            }
            try { coordinator.receive(checkNotNull(TeslaFiraProtocol.decode(body))) }
            finally { body.fill(0) }
        }

        fun stop(vararg endpoints: Int) {
            val body = proto { writeByteArray(48, proto { endpoints.forEach { writeEnum(1, it) } }) }
            try { coordinator.receive(checkNotNull(TeslaFiraProtocol.decode(body))) }
            finally { body.fill(0) }
        }

        fun plans(): List<TeslaFiraPlan> = endpoints().map { endpoint ->
            endpoint.javaClass.getDeclaredField("plan").apply { isAccessible = true }.get(endpoint) as TeslaFiraPlan
        }

        fun closeSessions(ids: Set<Long>) {
            TeslaUwbNegotiation::class.java.getDeclaredMethod("closeEndpoints", Set::class.java)
                .apply { isAccessible = true }.invoke(coordinator, ids)
        }

        fun startMeasurementWindow() {
            val window = TeslaUwbNegotiation::class.java.getDeclaredField("measurementWindow")
                .apply { isAccessible = true }.get(coordinator) as TeslaUwbMeasurementWindow
            window.start(clock)
        }

        fun assertNoBackend() {
            assertEquals(0, context.backendAccesses)
            endpoints().forEach { endpoint ->
                assertNull(endpoint.javaClass.getDeclaredField("backend").apply { isAccessible = true }.get(endpoint))
            }
            assertFalse(state.backendOpened)
            assertEquals(0, state.startedPeerCount)
        }

        fun dispose() {
            coordinator.close { }
            frames.forEach { it.body.fill(0) }
        }

        private fun endpoints(): List<Any> = (TeslaUwbNegotiation::class.java.getDeclaredField("endpoints")
            .apply { isAccessible = true }.get(coordinator) as List<*>).map { checkNotNull(it) }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { onHandler { fixture.dispose() } }
    }

    private fun onHandler(action: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        check(Handler(Looper.getMainLooper()).post {
            try { action() } catch (error: Throwable) { failure = error } finally { done.countDown() }
        })
        assertTrue("Coordinator Handler task must finish", done.await(5, TimeUnit.SECONDS))
        failure?.let { throw it }
    }

    private fun stoppedValues(body: ByteArray): List<Int> {
        val root = CodedInputStream.newInstance(body)
        assertEquals((43 shl 3) or 2, root.readTag())
        val payload = CodedInputStream.newInstance(root.readByteArray())
        val values = ArrayList<Int>()
        while (!payload.isAtEnd) {
            assertEquals(8, payload.readTag())
            values.add(payload.readEnum())
        }
        assertTrue(root.isAtEnd)
        return values
    }

    private fun assertWiped(plan: TeslaFiraPlan) {
        assertTrue(plan.localAddress.all { it == 0.toByte() })
        assertTrue(plan.sts.all { it == 0.toByte() })
        assertTrue(plan.peers.all { peer -> peer.all { it == 0.toByte() } })
    }

    private companion object {
        fun proto(build: CodedOutputStream.() -> Unit): ByteArray {
            val bytes = ByteArrayOutputStream()
            val output = CodedOutputStream.newInstance(bytes)
            output.build()
            output.flush()
            return bytes.toByteArray()
        }
    }
}
