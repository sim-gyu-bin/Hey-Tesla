package com.heytesla.app.vehicle.detection

import com.heytesla.app.AndroidBleScanTransport
import com.heytesla.app.BleDetectionPreference
import com.heytesla.app.CoordinateForegroundLease
import com.heytesla.app.DiagnosticApp
import com.heytesla.app.ObservationService
import com.heytesla.app.R
import com.heytesla.app.ui.VehicleVinUiTestHost
import com.heytesla.app.vehicle.navigation.AndroidPhoneNavigationSource
import com.heytesla.app.vehicle.navigation.PhoneNavigationHeading
import com.heytesla.app.vehicle.navigation.PhoneNavigationLocation
import com.heytesla.app.vehicle.navigation.PhoneNavigationSource
import com.heytesla.app.vehicle.navigation.PhoneNavigationUnavailable
import com.heytesla.app.vehicle.navigation.closeVehicleNavigationResources

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.Settings
import org.altbeacon.beacon.service.BeaconService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit non-vehicle bench: actual location FGS/source IO, but no scan/GATT/microphone or permission changes. */
@RunWith(AndroidJUnit4::class)
class CoordinateForegroundLeaseDeviceTest {
    @Test
    fun existingBeaconForegroundLocationLeaseAcknowledgesAndReleasesWithoutScanning() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(retainedResources == null) { "UNCERTAIN_LOCATION_BENCH_REQUIRES_PROCESS_RESTART" }
        val app = context.applicationContext as DiagnosticApp
        val manager = BeaconManager.getInstanceForApplication(context)
        fun consumersEmpty(): Boolean {
            // isAnyConsumerBound omits pending same-process bindings; inspect the map without exposing owners.
            val field = BeaconManager::class.java.getDeclaredField("consumers").apply { isAccessible = true }
            val consumers = field.get(manager) as Map<*, *>
            return synchronized(consumers) { consumers.isEmpty() }
        }
        fun idle(): Boolean {
            val s = app.runtime.state.value
            return !BleDetectionPreference(context, "ble_field_run_activation").enabled() &&
                !s.bleDetectionEnabled && !s.enabled && !s.bleFieldTrialActive && !s.bleFieldTrialStarting &&
                !s.bleFieldTrialStopping && !s.observationStartPending && !s.observationServiceRunning &&
                !s.teslaKeyDiagnosticActive && app.runtime.policy.current == null && app.runtime.microphone == null &&
                consumersEmpty() && !manager.isAnyConsumerBound && manager.rawScanCallback == null &&
                manager.rangedRegions.isEmpty() && manager.monitoredRegions.isEmpty() &&
                !AndroidBleScanTransport.foregroundRunning(context)
        }
        assumeTrue("Production service and every scanner consumer must already be OFF", idle())
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS)) {
            assumeTrue("This bench never requests or grants permissions",
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
        assumeTrue(context.getSystemService(LocationManager::class.java).isLocationEnabled)
        assumeTrue(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val notifications = context.getSystemService(NotificationManager::class.java)
        assumeTrue(notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(ObservationService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
        val previousStrategy = manager.activeSettings.scanStrategy
        val previousNotification = manager.foregroundServiceNotification
        val previousNotificationId = manager.foregroundServiceNotificationId
        val host = VehicleVinUiTestHost(isolatedVinStore = true)
        var rootBound = false
        var strategyChanged = false
        var lease: CoordinateForegroundLease? = null
        var leaseClosed = false
        var phoneSource: AndroidPhoneNavigationSource? = null
        var phoneSourceClosed = false
        var phoneCleanupFailed = false
        var phoneCleanupFailure: Exception? = null
        val phoneSourceClosing = AtomicBoolean(false)
        val locationCallbacks = AtomicInteger()
        val headingCallbacks = AtomicInteger()
        val lateCallbacks = AtomicInteger()
        val unavailable = EnumSet.noneOf(PhoneNavigationUnavailable::class.java)
        val connected = CountDownLatch(1)
        var service: Messenger? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = Messenger(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
        }
        try {
            host.launch()
            assumeTrue(idle())
            instrumentation.runOnMainSync {
                val notifications = context.getSystemService(NotificationManager::class.java)
                notifications.createNotificationChannel(NotificationChannel(ObservationService.CHANNEL,
                    "Hey Tesla 서비스", NotificationManager.IMPORTANCE_LOW))
                val notification = Notification.Builder(context, ObservationService.CHANNEL)
                    .setSmallIcon(R.drawable.ic_vehicle_notification).setContentTitle("Hey Tesla 위치 서비스 확인")
                    .setContentText("기기 내 FGS 소유권 확인 · 차량·스캔 실행 안 함").setOngoing(true).build()
                strategyChanged = true
                manager.adjustSettings(Settings(scanStrategy = Settings.ForegroundServiceScanStrategy(
                    notification, ObservationService.ALTBEACON_NOTIFICATION_ID)))
                // Direct binding only; BeaconManager.bind/startRanging/startMonitoring are never called.
                rootBound = context.bindService(Intent(context, BeaconService::class.java), connection, Context.BIND_AUTO_CREATE)
            }
            assertTrue(rootBound)
            assertTrue("Existing BeaconService must bind", connected.await(5, TimeUnit.SECONDS))
            assertTrue(AndroidBleScanTransport.foregroundRunning(context))
            exerciseOwnerOrdering(checkNotNull(service))
            val started = CountDownLatch(1)
            val promoted = AtomicBoolean(false)
            val lost = AtomicBoolean(false)
            instrumentation.runOnMainSync {
                lease = CoordinateForegroundLease(context, UUID.randomUUID().toString(), Handler(Looper.getMainLooper())) {
                    lost.set(true)
                }.also { current ->
                    current.start(allowed = { !context.getSystemService(KeyguardManager::class.java).isDeviceLocked }) {
                        promoted.set(it)
                        started.countDown()
                    }
                }
            }
            assertTrue("Location foreground result must be finite", started.await(6, TimeUnit.SECONDS))
            assertTrue("Only actual startForeground success may ACK location", promoted.get())
            val expected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            instrumentation.runOnMainSync { assertEquals(expected, checkNotNull(lease).confirmedForegroundTypes) }
            var sourceStartFailure: Exception? = null
            instrumentation.runOnMainSync {
                try {
                    val source = AndroidPhoneNavigationSource(context, SystemClock::elapsedRealtime)
                    phoneSource = source
                    source.start(object : PhoneNavigationSource.Listener {
                        override fun onLocation(value: PhoneNavigationLocation) {
                            if (phoneSourceClosing.get()) lateCallbacks.incrementAndGet() else locationCallbacks.incrementAndGet()
                        }
                        override fun onHeading(value: PhoneNavigationHeading) {
                            if (phoneSourceClosing.get()) lateCallbacks.incrementAndGet() else headingCallbacks.incrementAndGet()
                        }
                        override fun onUnavailable(reason: PhoneNavigationUnavailable) {
                            if (phoneSourceClosing.get()) lateCallbacks.incrementAndGet() else unavailable.add(reason)
                        }
                    })
                } catch (failure: Exception) {
                    sourceStartFailure = failure
                    if (failure.message == "PHONE_NAVIGATION_CLEANUP_FAILED") {
                        phoneSourceClosing.set(true)
                        phoneCleanupFailed = true
                        phoneCleanupFailure = failure
                    }
                }
            }
            sourceStartFailure?.let { throw it }
            // Indoor fixes/headings are not guaranteed. Zero callbacks are observations, not fake success/failure.
            SystemClock.sleep(1_000L)
            var sourceCloseFailure: Exception? = null
            instrumentation.runOnMainSync {
                phoneSourceClosing.set(true)
                try {
                    checkNotNull(phoneSource).close()
                    phoneSourceClosed = true
                } catch (failure: Exception) {
                    phoneCleanupFailed = true
                    phoneCleanupFailure = failure
                    sourceCloseFailure = failure
                }
            }
            sourceCloseFailure?.let { throw it }
            SystemClock.sleep(500L)
            instrumentation.waitForIdleSync()
            assertEquals(0, lateCallbacks.get())
            val stopped = CountDownLatch(1)
            val demoted = AtomicBoolean(false)
            val closeCallbacks = AtomicInteger()
            val reentrantCallbacks = AtomicInteger()
            val reentrantSucceeded = AtomicBoolean(false)
            instrumentation.runOnMainSync {
                val current = checkNotNull(lease)
                current.close { accepted ->
                    closeCallbacks.incrementAndGet()
                    demoted.set(accepted)
                    leaseClosed = accepted
                    if (accepted) current.close {
                        reentrantCallbacks.incrementAndGet()
                        reentrantSucceeded.set(it)
                    }
                    stopped.countDown()
                }
            }
            assertTrue(stopped.await(6, TimeUnit.SECONDS))
            assertTrue("Demotion must be acknowledged before lease binding is returned", demoted.get())
            assertEquals(1, closeCallbacks.get())
            assertEquals(1, reentrantCallbacks.get())
            assertTrue(reentrantSucceeded.get())
            val repeatedCallbacks = AtomicInteger()
            val repeatedSucceeded = AtomicBoolean(false)
            instrumentation.runOnMainSync {
                checkNotNull(lease).close { accepted ->
                    repeatedSucceeded.set(accepted)
                    repeatedCallbacks.incrementAndGet()
                }
            }
            assertEquals(1, repeatedCallbacks.get())
            assertTrue(repeatedSucceeded.get())
            exerciseCallbackFailure(context)
            exerciseLeaseCapacity(checkNotNull(service))
            instrumentation.runOnMainSync {
                assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, checkNotNull(lease).confirmedForegroundTypes)
                assertFalse(lost.get())
                assertFalse(manager.isAnyConsumerBound)
                assertEquals(null, manager.rawScanCallback)
                assertTrue(manager.rangedRegions.isEmpty())
                assertTrue(manager.monitoredRegions.isEmpty())
                context.unbindService(connection)
                rootBound = false
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (AndroidBleScanTransport.foregroundRunning(context) && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25)
            }
            assertFalse("Last binding must return the actual foreground service", AndroidBleScanTransport.foregroundRunning(context))
        } finally {
            val cleanupDone = CountDownLatch(1)
            var resourcesReleased = false
            instrumentation.runOnMainSync {
                closeVehicleNavigationResources(
                    closeSource = {
                        if (phoneCleanupFailed) false else try {
                            if (phoneSource != null && !phoneSourceClosed) {
                                phoneSourceClosing.set(true)
                                checkNotNull(phoneSource).close()
                                phoneSourceClosed = true
                            }
                            true
                        } catch (failure: Exception) {
                            phoneCleanupFailed = true
                            phoneCleanupFailure = failure
                            false
                        }
                    },
                    closeForeground = { completed ->
                        if (lease == null || leaseClosed) completed(true) else checkNotNull(lease).close { accepted ->
                            leaseClosed = accepted
                            completed(accepted)
                        }
                    },
                    complete = { released -> resourcesReleased = released; cleanupDone.countDown() },
                )
            }
            val cleanupConfirmed = cleanupDone.await(6, TimeUnit.SECONDS) && resourcesReleased
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putInt("phone_location_callbacks", locationCallbacks.get())
                putInt("phone_heading_callbacks", headingCallbacks.get())
                putString("phone_unavailable", unavailable.joinToString(",") { it.name })
                putInt("phone_after_close_callbacks", lateCallbacks.get())
            })
            var cleanupFailure: Exception? = null
            var strategyRestored = !strategyChanged
            try {
                instrumentation.runOnMainSync {
                    if (cleanupConfirmed) {
                        try {
                            if (rootBound) {
                                context.unbindService(connection)
                                rootBound = false
                            }
                            if (strategyChanged) {
                                if (previousNotification == null) manager.disableForegroundServiceScanning()
                                else manager.enableForegroundServiceScanning(previousNotification, previousNotificationId)
                                manager.adjustSettings(Settings(scanStrategy = previousStrategy))
                                strategyRestored = previousStrategy.compareTo(manager.activeSettings.scanStrategy) == 0
                            }
                        } catch (failure: Exception) { cleanupFailure = failure }
                    }
                }
            } finally {
                if (!cleanupConfirmed || rootBound || !strategyRestored) {
                    // Test-owned, process-RAM retention: no forced unbind, strategy reset, or lost native handle.
                    retainedResources = RetainedBenchResources(context, phoneSource, lease,
                        if (rootBound) connection else null, previousStrategy, previousNotification, previousNotificationId)
                }
                host.close()
            }
            phoneCleanupFailure?.let { throw it }
            cleanupFailure?.let { throw it }
            if (!cleanupConfirmed || rootBound || !strategyRestored) {
                throw AssertionError("Location bench cleanup was not confirmed; resources retained")
            }
        }
    }

    private class RetainedBenchResources(
        val context: Context,
        val source: PhoneNavigationSource?,
        val foreground: CoordinateForegroundLease?,
        val binding: ServiceConnection?,
        val priorStrategy: Settings.ScanStrategy,
        val priorNotification: Notification?,
        val priorNotificationId: Int,
    )

    private companion object {
        @Volatile private var retainedResources: RetainedBenchResources? = null
    }

    private data class WireResult(val sequence: Long, val accepted: Boolean, val types: Int)

    /** Real Messenger traffic with caller-selected order; no production hook or copied acceptance rule. */
    private class WireOwner(private val target: Messenger, private val token: String = UUID.randomUUID().toString()) {
        private val leaseId = UUID.randomUUID().toString()
        private val results = LinkedBlockingQueue<WireResult>()
        private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what != BeaconService.MSG_LOCATION_FOREGROUND_RESULT ||
                    message.data.getString(BeaconService.LOCATION_OWNER) != token ||
                    message.data.getString(BeaconService.LOCATION_LEASE_ID) != leaseId) return
                results.add(WireResult(message.data.getLong(BeaconService.LOCATION_REQUEST),
                    message.arg1 == 1, message.arg2))
            }
        })

        fun request(sequence: Long, enabled: Boolean): WireResult {
            var sendFailed = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val request = Message.obtain(null, BeaconService.MSG_LOCATION_FOREGROUND)
                request.replyTo = replies
                request.data.putString(BeaconService.LOCATION_OWNER, token)
                request.data.putString(BeaconService.LOCATION_LEASE_ID, leaseId)
                request.data.putLong(BeaconService.LOCATION_REQUEST, sequence)
                request.data.putBoolean(BeaconService.LOCATION_ENABLED, enabled)
                try { target.send(request) } catch (_: Exception) { sendFailed = true }
            }
            if (sendFailed) throw AssertionError("Location lease send failed")

            val result = results.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("Location lease ACK missing")
            assertEquals(sequence, result.sequence)
            return result
        }
    }
    private fun exerciseCallbackFailure(context: Context) {
        val marker = IllegalStateException("BENCH_CALLBACK_FAILURE")
        var callbackCount = 0
        var escaped = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Never started/bound: empty ownership closes synchronously through the same finish callback path.
            val empty = CoordinateForegroundLease(context, UUID.randomUUID().toString(), Handler(Looper.getMainLooper())) {
                throw AssertionError("An unstarted lease cannot lose a service")
            }
            try {
                empty.close { callbackCount++; throw marker }
            } catch (failure: Exception) { escaped = failure === marker }
        }
        assertTrue("Callback exceptions must escape, not be reclassified as unbind failure", escaped)
        assertEquals(1, callbackCount)
    }

    private fun assertWire(result: WireResult, accepted: Boolean, types: Int) {
        assertEquals(accepted, result.accepted)
        assertEquals(types, result.types)
    }

    private fun exerciseOwnerOrdering(target: Messenger) {
        val connected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val located = connected or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val token = UUID.randomUUID().toString()
        val a = WireOwner(target, token)
        val failed = WireOwner(target)
        assertWire(a.request(2L, true), true, located)
        assertWire(a.request(1L, false), false, located) // A stale disable must not undo enable(2).
        assertWire(failed.request(1L, true), false, located)
        assertWire(a.request(3L, false), true, connected)
        assertWire(a.request(2L, true), false, connected)
        assertWire(a.request(4L, true), false, connected) // Terminal also rejects a newer enable.
        assertWire(failed.request(1L, true), false, connected)
        assertWire(failed.request(2L, true), false, connected) // The failed admission cannot recover by replay.
        val b = WireOwner(target, token) // Same service owner may explicitly create a distinct new lease.
        assertWire(b.request(1L, true), true, located)
        assertWire(a.request(1L, false), false, located)
        assertWire(a.request(2L, true), false, located)
        assertWire(a.request(6L, false), false, located) // Even A's newer cleanup cannot touch B.
        assertWire(b.request(2L, false), true, connected)
    }

    private fun exerciseLeaseCapacity(target: Messenger) {
        val connected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val located = connected or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val owner = WireOwner(target)
        assertWire(owner.request(1L, true), true, located)
        repeat(128) {
            assertWire(WireOwner(target).request(1L, true), false, located)
        }
        assertWire(owner.request(2L, false), true, connected) // The cap never blocks current-owner cleanup.
        assertWire(WireOwner(target).request(1L, true), false, connected) // No tombstone eviction.
        assertWire(owner.request(1L, true), false, connected)
    }
}
