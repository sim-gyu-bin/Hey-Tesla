package com.heytesla.app.ui

import com.heytesla.app.BleDetectionPreference
import com.heytesla.app.DiagnosticRuntime
import com.heytesla.app.vehicle.key.VehicleVinStatus
import com.heytesla.app.vehicle.navigation.NavigationPoint
import com.heytesla.app.vehicle.navigation.PhoneNavigationHeading
import com.heytesla.app.vehicle.navigation.PhoneNavigationLocation
import com.heytesla.app.vehicle.navigation.VehicleCoordinateObservation
import com.heytesla.app.vehicle.navigation.VehicleNavigationStatus
import com.heytesla.app.vehicle.navigation.VehicleNavigationTracker
import com.heytesla.app.vehicle.uwb.TeslaUwbDistance
import com.heytesla.app.vehicle.uwb.TeslaUwbProbeState
import com.heytesla.app.vehicle.uwb.TeslaUwbStage

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 홈의 사용자 의도와 실행 실패를 구분하고 실제 격리 runtime의 OFF 영속화를 검사한다. 라디오는 시작하지 않는다. */
@RunWith(AndroidJUnit4::class)
class VehicleServiceUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun failedRestorationAndMissingPermissionSnapshotCannotPreventPersistedOff() {
        // 새 test-owned VIN 저장소는 비어 있어 저장 ON 복원도 실제 서비스·GATT를 시작할 수 없다.
        val host = VehicleVinUiTestHost(initiallyEnabledService = true)
        host.launch()
        try {
            host.await { host.runtime.state.value.vehicleVin.status == VehicleVinStatus.NOT_REGISTERED }
            instrumentation.runOnMainSync { host.runtime.restoreBleDetection() }
            host.await { host.state.value.bleDetectionEnabled && host.state.value.bleFieldTrialStopReason != null }
            instrumentation.runOnMainSync {
                // 권한 상실 화면 입력만 격리한다. 기기의 실제 권한·생산 설정은 변경하지 않는다.
                host.state.value = host.state.value.copy(bluetoothPermission = false,
                    bluetoothScanPermission = false, locationPermission = false, notificationPermission = false)
                assertTrue(host.runtime.vehicleServiceBlockedReason() != null)
                assertNoRadioOrAudioWork(host.runtime)
            }
            val service = awaitServiceSwitch()
            assertTrue(service.isChecked)
            assertTrue(service.isEnabled)
            // 저장 ON을 정상 실행으로 위장하지 않고 접근성 오류로도 노출해야 한다.
            awaitNode { it.isContentInvalid && !it.error.isNullOrEmpty() }
            assertTrue(service.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            host.await { !host.runtime.state.value.bleDetectionEnabled && !host.state.value.bleDetectionEnabled }
            assertFalse(awaitServiceSwitch().isChecked)
            assertFalse(BleDetectionPreference(context, host.servicePreferenceName).enabled())
            instrumentation.runOnMainSync {
                host.runtime.restoreBleDetection()
                assertNoRadioOrAudioWork(host.runtime)
            }
            assertEquals(0, host.startCount)
        } finally {
            host.close()
        }
    }

    @Test
    fun restoredVinAndScreenReentryNeverStartServiceOrAudioWithoutUserAction() {
        val host = VehicleVinUiTestHost()
        val activity = host.launch()
        try {
            host.seedRegisteredVin()
            val registered = host.runtime.state.value.vehicleVin
            clickText("설정")
            clickText("개발자 진단")
            clickText("차량 키")
            clickText("홈")
            instrumentation.runOnMainSync { instrumentation.callActivityOnPause(activity) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { instrumentation.callActivityOnResume(activity) }
            assertFalse(awaitServiceSwitch().isChecked)
            instrumentation.runOnMainSync {
                assertEquals(registered, host.runtime.state.value.vehicleVin)
                assertNoRadioOrAudioWork(host.runtime)
            }
            assertEquals(0, host.startCount)
        } finally {
            host.close()
        }
    }

    @Test
    fun coordinateAndUwbSourcesExpireIndependentlyAndOffRemovesBothWithoutRadioWork() {
        val host = VehicleVinUiTestHost(initiallyEnabledService = true)
        val tracker = VehicleNavigationTracker()
        host.launch()
        try {
            host.await { host.runtime.state.value.vehicleVin.status == VehicleVinStatus.NOT_REGISTERED }
            instrumentation.runOnMainSync {
                host.state.value = host.state.value.copy(bleDetectionEnabled = true, bleFieldTrialActive = true,
                    observationServiceRunning = true, observing = true, bleFieldScanRunning = true,
                    bleFieldTrialStopReason = null)
                feedTracker(tracker)
                host.projectNavigation(tracker)
            }
            awaitNode { it.text?.toString()?.contains("폰 상단 기준 오른쪽") == true }
            awaitNode { it.contentDescription?.toString() == "좌표 안내 나침반 · 폰 상단 기준" }
            instrumentation.runOnMainSync {
                tracker.clear()
                feedTracker(tracker, vehicleAgeMs = 59_500L)
                host.projectNavigation(tracker)
            }
            // No new source input: the real tracker's vehicle fix expires through the fixture's 1 s clock.
            awaitNode { it.text?.toString() == "좌표 안내 · 차량 좌표 기한 만료" }
            assertEquals(null, findNode { it.contentDescription?.toString() == "좌표 안내 나침반 · 폰 상단 기준" })
            assertEquals(null, findNode { it.text?.toString()?.startsWith("좌표 안내 약") == true })
            instrumentation.runOnMainSync {
                val sample = TeslaUwbDistance(peerIndex = 0, meters = 1.25, confidence = 2,
                    frameworkTimestampMillis = 0, receivedAtElapsedMs = SystemClock.elapsedRealtime())
                host.runtime.teslaUwbState.value = TeslaUwbProbeState(serviceOwned = true,
                    stage = TeslaUwbStage.RANGING, samples = listOf(sample), livePeerIndices = setOf(0))
            }
            awaitNode { it.text?.toString()?.contains("UWB 실측 1.25 m") == true }
            assertTrue(findNode { it.text?.toString() == "좌표 안내 · 차량 좌표 기한 만료" } != null)
            SystemClock.sleep(VEHICLE_UWB_SAMPLE_TTL_MS + 100L)
            awaitNode { it.text?.toString()?.contains("최근 2초 내 유효 거리 없음") == true }
            instrumentation.runOnMainSync {
                tracker.clear()
                feedTracker(tracker)
                host.projectNavigation(tracker)
                host.state.value = host.state.value.copy(bleFieldTrialStopping = true,
                    bleFieldTrialStopReason = "LOCAL_CLOSE_FAILED_RESTART_REQUIRED")
            }
            awaitNode { it.text?.toString()?.startsWith("서비스 오류 · 연결 정리가 확인되지") == true }
            assertEquals(null, findNode { it.text?.toString()?.startsWith("좌표 안내 약") == true })
            assertTrue(awaitServiceSwitch().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            host.await { !host.state.value.bleDetectionEnabled }
            assertEquals(null, findNode { it.text?.toString()?.startsWith("좌표 안내 약") == true })
            instrumentation.runOnMainSync { assertNoRadioOrAudioWork(host.runtime) }
            assertFalse(BleDetectionPreference(context, host.servicePreferenceName).enabled())
        } finally { host.close() }
    }

    @Test
    fun explicitNavigationResumeAndPermissionRefreshCannotCreateADeadService() {
        val host = VehicleVinUiTestHost(isolatedVinStore = true)
        host.launch()
        try {
            clickText("설정")
            var resume = awaitNode { it.text?.toString() == "위치 안내 재개" }
            while (!resume.isClickable && resume.parent != null) resume = resume.parent
            assertFalse("A dead owner has no enabled resume action", resume.isEnabled)
            instrumentation.runOnMainSync {
                host.runtime.resumeVehicleNavigation()
                host.runtime.refresh()
                assertNoRadioOrAudioWork(host.runtime)
                assertEquals(VehicleNavigationStatus.OFF, host.runtime.vehicleNavigation.value.status)
            }
            assertEquals(0, host.navigationResumeCount)
            assertEquals(0, host.startCount)
        } finally { host.close() }
    }

    private fun feedTracker(tracker: VehicleNavigationTracker, vehicleAgeMs: Long = 0L) {
        val elapsed = SystemClock.elapsedRealtime()
        val epoch = System.currentTimeMillis()
        // Non-personal, synthetic WGS84 points exercise actual geometry; nothing is logged or stored.
        tracker.acceptPhoneLocation(PhoneNavigationLocation(NavigationPoint(0.0, 0.0),
            accuracyMeters = 5.0, altitudeMeters = null, fixElapsedMs = elapsed, receivedAtElapsedMs = elapsed))
        tracker.acceptPhoneHeading(PhoneNavigationHeading(trueNorthDegrees = 0.0,
            fixElapsedMs = elapsed, receivedAtElapsedMs = elapsed))
        tracker.acceptVehicleLocation(VehicleCoordinateObservation(NavigationPoint(0.0, 0.001),
            fixEpochMs = epoch - vehicleAgeMs, stateEpochMs = null, receivedAtElapsedMs = elapsed, isRaw = false))
    }

    private fun assertNoRadioOrAudioWork(runtime: DiagnosticRuntime) {
        assertFalse(runtime.state.value.bleFieldTrialActive)
        assertFalse(runtime.state.value.bleFieldTrialStarting)
        assertFalse(runtime.state.value.bleFieldScanRunning)
        assertFalse(runtime.state.value.observationServiceRunning)
        assertFalse(runtime.bleProbeState.value.active)
        assertFalse(runtime.teslaKeyReserved())
        assertEquals(null, runtime.policy.current)
        assertEquals(null, runtime.microphone)
    }

    private fun awaitServiceSwitch(): AccessibilityNodeInfo {
        var node = awaitNode { it.contentDescription?.toString() == "서비스 사용" }
        while (!node.isCheckable && node.parent != null) node = node.parent
        assertTrue("서비스 사용 라벨에 상태를 가진 토글이 연결되어야 합니다", node.isCheckable)
        return node
    }

    private fun clickText(text: String) {
        var node = awaitNode { it.text?.toString() == text }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        instrumentation.waitForIdleSync()
        findNode(predicate)?.let { return it }
        repeat(12) {
            if (findNode { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) return@repeat
            instrumentation.waitForIdleSync()
        }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            findNode(predicate)?.let { return it }
            findNode { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(25)
        }
        val service = findNode {
            it.contentDescription?.toString()?.contains("서비스 사용") == true || it.text?.toString() == "서비스 사용"
        }
        val details = buildString {
            var node = service
            repeat(4) {
                val current = node ?: return@repeat
                append(" [class=").append(current.className)
                    .append(", description=").append(current.contentDescription)
                    .append(", checkable=").append(current.isCheckable)
                    .append(", checked=").append(current.isChecked)
                    .append(", clickable=").append(current.isClickable)
                    .append(", enabled=").append(current.isEnabled).append(']')
                node = current.parent
            }
        }
        throw AssertionError("서비스 사용 화면의 접근성 상태를 확인할 수 없습니다:$details")
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        automation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.packageName?.toString() == context.packageName && predicate(node)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        for (window in automation.windows) walk(window.root)?.let { return it }
        return walk(automation.rootInActiveWindow)
    }
}
