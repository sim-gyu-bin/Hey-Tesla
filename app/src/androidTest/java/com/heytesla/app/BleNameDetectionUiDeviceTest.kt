package com.heytesla.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 공통 등록 뒤 실제 BLE consumer의 admission·조건 고정·명시적 실행을 검사한다. 라디오는 시작하지 않는다. */
@RunWith(AndroidJUnit4::class)
class BleNameDetectionUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val startLabel = "차량 광고명 감지 시작"
    private val presetLabel = "차량 광고명 감지 · GATT 없음"

    @Test
    fun commonVinAndAdmissionAreRequiredThenRunningSnapshotSealsEveryCondition() {
        val host = VehicleVinUiTestHost()
        host.launch()
        val state = host.state
        try {
            instrumentation.runOnMainSync {
                state.value = state.value.copy(vehicleVin = VehicleVinState(VehicleVinStatus.NOT_REGISTERED))
            }
            openBle()
            assertFalse(clickableParent(awaitText(startLabel)).isEnabled)
            listOf("감지만 · GATT 없음", "BT 연결 신호 보조", "보조 스캔", "제한 재시도", "백그라운드 연결 비교").forEach {
                assertFalse(clickableParent(awaitText(it)).isEnabled)
            }
            host.seedRegisteredVin()
            assertTrue(clickableParent(awaitText(startLabel)).isEnabled)
            assertEquals(0, host.startCount)
            instrumentation.runOnMainSync {
                host.foreignBlocked = "LEASE_UNAVAILABLE"
                host.invalidate()
            }
            assertFalse(clickableParent(awaitText(startLabel)).isEnabled)
            instrumentation.runOnMainSync {
                host.foreignBlocked = null
                state.value = state.value.copy(bleFieldConfig = BleFieldConfig.VEHICLE_NAME_DETECTION, bleFieldTrialStarting = true)
            }
            assertFalse(clickableParent(awaitText("등록 VIN 변경")).isEnabled)
            assertFalse(clickableParent(awaitText(startLabel)).isEnabled)
            listOf(presetLabel, "기준 방식 · 등록 주소", "개선 방식 · 등록 주소", "감지만 · GATT 없음", "BT 연결 신호 보조", "보조 스캔", "제한 재시도", "백그라운드 연결 비교").forEach {
                assertFalse("$it: 실행 중 조건은 변경할 수 없어야 합니다", clickableParent(awaitText(it)).isEnabled)
            }
            clickText("주말 BLE 시험 중지")
            assertEquals(0, host.startCount)
            instrumentation.runOnMainSync { state.value = state.value.copy(bleFieldTrialStarting = false) }
            assertNoRadioOrAudioStart()
        } finally {
            instrumentation.runOnMainSync { state.value = state.value.copy(bleFieldTrialStarting = false) }
            host.close()
        }
    }

    @Test
    fun restoredCommonVinSurvivesNavigationAndPauseButDetectionNeedsAnExplicitButton() {
        val host = VehicleVinUiTestHost()
        val activity = host.launch()
        try {
            host.seedRegisteredVin()
            openBle()
            assertTrue(clickableParent(awaitText(startLabel)).isEnabled)
            clickText("음성")
            openBle()
            assertTrue(clickableParent(awaitText(startLabel)).isEnabled)
            instrumentation.runOnMainSync { instrumentation.callActivityOnPause(activity) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { instrumentation.callActivityOnResume(activity) }
            assertTrue(clickableParent(awaitText(startLabel)).isEnabled)
            assertEquals(0, host.startCount)
            clickText("기준 방식 · 등록 주소")
            assertTrue(clickableParent(awaitText("선택한 조건으로 BLE 시험 시작")).isEnabled)
            clickText("개선 방식 · 등록 주소")
            assertTrue(clickableParent(awaitText("선택한 조건으로 BLE 시험 시작")).isEnabled)
            assertEquals(0, host.startCount)
            assertNoRadioOrAudioStart()
        } finally {
            host.close()
        }
    }


    private fun assertNoRadioOrAudioStart() {
        val runtime = (context.applicationContext as DiagnosticApp).runtime
        assertFalse(runtime.state.value.bleFieldTrialActive)
        assertFalse(runtime.state.value.bleFieldTrialStarting)
        assertFalse(runtime.state.value.bleFieldScanRunning)
        assertFalse(runtime.bleProbeState.value.active)
        assertFalse(runtime.teslaKeyReserved())
        assertEquals(null, runtime.policy.current)
        assertEquals(null, runtime.microphone)
    }

    private fun openBle() {
        if (findNode { it.text?.toString() == "BLE 연결" } == null) {
            clickText("설정")
            clickText("개발자 진단")
        }
        val tab = awaitText("BLE 연결")
        if (!tab.isSelected) {
            assertTrue(clickableParent(tab).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        }
        scrollToTop()
    }


    private fun awaitText(text: String) = awaitNode { it.text?.toString() == text }

    private fun clickText(text: String) {
        assertTrue(clickableParent(awaitText(text)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun scrollToTop() {
        repeat(12) {
            if (findNode { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) return
            instrumentation.waitForIdleSync()
        }
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.isEnabled && current.parent != null) current = current.parent
        return current
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        scrollToTop()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            findNode(predicate)?.let { return it }
            findNode { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(50)
        }
        throw AssertionError("BLE native consumer의 화면 요소를 확인할 수 없습니다")
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        automation.clearCache()
        for (window in automation.windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == context.packageName) {
                findInTree(root, predicate)?.let { return it }
            }
        }
        val root = automation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != context.packageName) return null
        return findInTree(root, predicate)
    }

    private fun findInTree(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                walk(child)?.let { return it }
            }
            return null
        }
        return walk(root)
    }
}
