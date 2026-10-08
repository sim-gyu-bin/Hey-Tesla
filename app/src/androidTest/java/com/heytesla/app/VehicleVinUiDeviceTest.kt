package com.heytesla.app

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 공통 UI→fixture runtime→실제 암호화 저장소→새 runtime 복원. production 식별값은 읽거나 쓰지 않는다. */
@RunWith(AndroidJUnit4::class)
class VehicleVinUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun commonRegistrationReallyPersistsAndEveryConsumerRequiresAnExplicitAction() {
        var host = VehicleVinUiTestHost()
        host.launch()
        try {
            openDiagnostics()
            openEditor(host)
            setInput("0000000000000000")
            assertFalse(clickable(awaitText("VIN 암호화 저장")).isEnabled)
            setInput("0000000000000000I")
            assertFalse(clickable(awaitText("VIN 암호화 저장")).isEnabled)
            setInput(" 5yj3e1ea7kf000001 ")
            assertTrue(clickable(awaitText("VIN 암호화 저장")).isEnabled)
            clickText("VIN 암호화 저장")
            host.await { host.runtime.state.value.vehicleVin.status == VehicleVinStatus.READY }
            val registered = host.runtime.state.value.vehicleVin
            assertTrue(registered.maskedVin != null)
            awaitText("VIN 저장됨 · ${registered.maskedVin}")
            assertNull(findNode { it.className?.toString() == "android.widget.EditText" })
            assertNull(findNode { it.text?.toString()?.contains("5YJ3E1EA7KF000001") == true })
            assertEquals(0, host.startCount)
            assertEquals(0, host.associateCount)
            assertTrue(host.vinFile.isFile)
            assertFalse(host.vinFile.readBytes().toString(Charsets.ISO_8859_1).contains("5YJ3E1EA7KF000001"))

            clickText("차량 키")
            assertTrue(clickable(awaitText("우리 앱 키로 인증·상태 1회 조회")).isEnabled)
            assertTrue(clickable(awaitText("주차 기어 상태 1회 조회")).isEnabled)
            assertFalse(clickable(awaitText("키 추가 요청 · 카드 승인 후 상태 1회 조회")).isEnabled)
            assertEquals(0, host.startCount)
            clickText("BLE 연결")
            assertTrue(clickable(awaitText("차량 광고명 감지 시작")).isEnabled)
            assertEquals(0, host.startCount)
            assertNoWork(host)

            clickText("등록 VIN 변경")
            setInput("00000000000000000")
            instrumentation.runOnMainSync { instrumentation.callActivityOnPause(host.activity) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { instrumentation.callActivityOnResume(host.activity) }
            clickText("등록 VIN 변경")
            assertTrue(awaitEditor().text.isNullOrEmpty())
            clickText("VIN 입력 취소")
            clickText("등록 VIN 변경")
            setInput("00000000000000000")
            clickText("차량 키")
            clickText("등록 VIN 변경")
            assertTrue(awaitEditor().text.isNullOrEmpty())
            clickText("VIN 입력 취소")
            clickText("홈")
            openDiagnostics()
            awaitText("VIN 저장됨 · ${registered.maskedVin}")
            assertEquals(0, host.startCount)
            assertNoWork(host)

            clickText("등록 VIN 변경")
            setInput("00000000000000000")
            host.close()
            host = VehicleVinUiTestHost()
            host.launch()
            openDiagnostics()
            assertEquals(registered, host.runtime.state.value.vehicleVin)
            awaitText("VIN 저장됨 · ${registered.maskedVin}")
            assertEquals(0, host.startCount)
            clickText("등록 VIN 변경")
            assertTrue(awaitEditor().text.isNullOrEmpty())
            clickText("VIN 입력 취소")
            clickText("차량 키")
            assertTrue(clickable(awaitText("주차 기어 상태 1회 조회")).isEnabled)
            clickText("홈")
            clickText("설정")
            clickText("차량 등록")
            awaitText("등록된 VIN 사용 · ${registered.maskedVin}")
            assertTrue(clickable(awaitText("차량 선택 시작")).isEnabled)
            assertEquals(0, host.associateCount)
            assertNoWork(host)
        } finally {
            host.close()
        }
    }

    @Test
    fun savingFailureAndForeignLeaseCannotEnableConsumersOrClaimSaveCompletion() {
        val host = VehicleVinUiTestHost()
        host.launch()
        try {
            host.seedRegisteredVin()
            openDiagnostics()
            clickText("차량 키")
            for (status in listOf(VehicleVinStatus.LOADING, VehicleVinStatus.NOT_REGISTERED, VehicleVinStatus.SAVING, VehicleVinStatus.FAILED)) {
                instrumentation.runOnMainSync {
                    host.state.value = host.state.value.copy(vehicleVin = VehicleVinState(status, reason = if (status == VehicleVinStatus.FAILED) "VIN_SAVE_FAILED" else null))
                }
                assertFalse(clickable(awaitText("우리 앱 키로 인증·상태 1회 조회")).isEnabled)
                assertFalse(clickable(awaitText("주차 기어 상태 1회 조회")).isEnabled)
                if (status == VehicleVinStatus.SAVING || status == VehicleVinStatus.FAILED) {
                    assertNull(findNode { it.text?.toString()?.startsWith("VIN 저장됨") == true })
                }
            }
            host.seedRegisteredVin()
            instrumentation.runOnMainSync { host.foreignBlocked = "VIN_CHANGE_BLOCKED_VEHICLE_CHOOSER"; host.invalidate() }
            assertFalse(clickable(awaitText("등록 VIN 변경")).isEnabled)
            assertFalse(clickable(awaitText("주차 기어 상태 1회 조회")).isEnabled)
            clickText("BLE 연결")
            assertFalse(clickable(awaitText("차량 광고명 감지 시작")).isEnabled)
            assertEquals(0, host.startCount)
            assertNoWork(host)
        } finally {
            host.close()
        }
    }

    private fun assertNoWork(host: VehicleVinUiTestHost) {
        instrumentation.runOnMainSync {
            assertFalse(host.runtime.teslaKeyReserved())
            assertFalse(host.runtime.state.value.bleFieldTrialActive)
            assertFalse(host.runtime.state.value.bleFieldTrialStarting)
            assertFalse(host.runtime.state.value.bleFieldScanRunning)
            assertFalse(host.runtime.bleProbeState.value.active)
            assertNull(host.runtime.policy.current)
            assertNull(host.runtime.microphone)
        }
    }

    private fun openDiagnostics() { clickText("설정"); clickText("개발자 진단") }
    private fun openEditor(host: VehicleVinUiTestHost) {
        clickText(if (host.runtime.state.value.vehicleVin.status == VehicleVinStatus.READY) "등록 VIN 변경" else "공통 VIN 등록")
    }
    private fun awaitEditor() = awaitNode { it.className?.toString() == "android.widget.EditText" }
    private fun awaitText(text: String) = awaitNode { it.text?.toString() == text }
    private fun setInput(value: String) {
        assertTrue(awaitEditor().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.waitForIdleSync()
    }
    private fun clickText(text: String) {
        assertTrue(clickable(awaitText(text)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.isEnabled && current.parent != null) current = current.parent
        return current
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        repeat(20) {
            if (findNode { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) return@repeat
            instrumentation.waitForIdleSync()
        }
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            findNode(predicate)?.let { return it }
            findNode { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(25)
        }
        throw AssertionError("공통 VIN native consumer 요소를 확인할 수 없습니다")
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
