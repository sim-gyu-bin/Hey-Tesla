package com.heytesla.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.OutputStream

/** 실제 화면·예약과 전송 전 취소를 시험한다. 차량 통신·등록 승인은 실행하지 않는다. */
@RunWith(AndroidJUnit4::class)
class TeslaBleKeyUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun readOnlyStartCanceledDuringKeyPreparationReleasesItsLeaseWithoutTransmission() {
        launchApp()
        val runtime = (context.applicationContext as DiagnosticApp).runtime
        instrumentation.runOnMainSync {
            try {
                runtime.startTeslaKey("00000000000000000", register = false)
                assertEquals(TeslaKeyStage.PREPARING_KEY, runtime.teslaKeyState.value.stage)
                assertTrue(runtime.teslaKeyReserved())
                assertFalse(runtime.acquireUwbSupport())
            } finally {
                // IO 반환이 Main으로 돌아오기 전에 봉인하여 GATT 연결·TX를 막는다.
                runtime.cancelTeslaKey()
            }
        }
        awaitRuntime { !runtime.teslaKeyReserved() }
        instrumentation.runOnMainSync {
            val result = runtime.teslaKeyState.value
            assertEquals(TeslaKeyStage.CANCELED, result.stage)
            assertEquals(TeslaKeyOutcome.CANCELED, result.outcome)
            assertTrue(result.localClosed)
            assertFalse(result.transmissionAttempted)
            assertFalse(result.registrationRequested)
            assertFalse(runtime.state.value.teslaKeyDiagnosticActive)
            assertNull(runtime.teslaKeyBlockedReason())
            assertTrue(runtime.acquireUwbSupport())
            runtime.releaseUwbSupport(cleanupFailed = false)
        }
    }

    @Test
    fun owningKeyLeaseDisablesReentryWithoutAnAdmissionErrorButForeignLeaseShowsOne() {
        launchApp()
        openKeyScreen()
        val runtime = (context.applicationContext as DiagnosticApp).runtime
        val edit = awaitNode { it.className?.toString() == "android.widget.EditText" }
        assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "00000000000000000")
        }))
        if (automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            instrumentation.waitForIdleSync()
        }
        assertTrue(clickableParent(awaitNode { it.text?.toString() == "우리 앱 키로 인증·상태 1회 조회" }).isEnabled)
        openKeyScreen()
        var token: Long? = null
        instrumentation.runOnMainSync {
            runtime.refresh()
            token = runtime.acquireTeslaKey()
        }
        assertNotNull(token)
        try {
            assertFalse(awaitNode { it.className?.toString() == "android.widget.EditText" }.isEnabled)
            assertFalse(clickableParent(awaitNode { it.text?.toString() == "우리 앱 키로 인증·상태 1회 조회" }).isEnabled)
            assertFalse(clickableParent(awaitNode { it.text?.toString() == "키 추가 요청 · 카드 승인 후 상태 1회 조회" }).isEnabled)
            scrollToEnd()
            assertNull(findNode { it.isContentInvalid || !it.error.isNullOrEmpty() })
        } finally {
            instrumentation.runOnMainSync { token?.let(runtime::releaseTeslaKey) }
        }
        clickText("UWB 지원")
        var foreignLease = false
        instrumentation.runOnMainSync { foreignLease = runtime.acquireUwbSupport() }
        assertTrue(foreignLease)
        try {
            openKeyScreen()
            assertFalse(awaitNode { it.className?.toString() == "android.widget.EditText" }.isEnabled)
            assertFalse(clickableParent(awaitNode { it.text?.toString() == "우리 앱 키로 인증·상태 1회 조회" }).isEnabled)
            val error = awaitNode { it.isContentInvalid }
            assertFalse(error.error.isNullOrEmpty())
        } finally {
            instrumentation.runOnMainSync { runtime.releaseUwbSupport(cleanupFailed = false) }
        }
    }

    private fun launchApp() {
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    private fun awaitRuntime(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            SystemClock.sleep(25)
        }
        throw AssertionError("차량 키 진단의 비동기 정리 완료를 확인할 수 없습니다")
    }

    private fun scrollToEnd() {
        repeat(6) {
            if (findNode { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) return
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun backgroundingClearsVinAndDoesNotAuthorizeKeyRegistration() {
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        openKeyScreen()
        val edit = awaitNode { it.className?.toString() == "android.widget.EditText" }
        assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "00000000000000000")
        }))
        awaitNode { it.text?.toString() == "우리 앱 키로 인증·상태 1회 조회" }.also {
            assertTrue(clickableParent(it).isEnabled)
        }
        val consent = awaitNode { it.contentDescription?.toString() == "우리 앱 키 추가와 차량 키카드 직접 승인에 동의" }
        assertFalse(consent.isChecked)
        val register = awaitNode { it.text?.toString() == "키 추가 요청 · 카드 승인 후 상태 1회 조회" }
        assertFalse(clickableParent(register).isEnabled)
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        val backgroundDeadline = SystemClock.elapsedRealtime() + 5_000
        while (findNode { true } != null && SystemClock.elapsedRealtime() < backgroundDeadline) {
            SystemClock.sleep(50)
        }
        assertTrue(findNode { true } == null)
        // 앱 자체 background launch 대신 사용자의 재실행에 해당하는 shell 시작으로 복귀한다.
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("am start -W -n ${context.packageName}/${MainActivity::class.java.name}"),
        ).use { it.copyTo(OutputStream.nullOutputStream()) }
        openKeyScreen()
        val cleared = awaitNode { it.className?.toString() == "android.widget.EditText" }
        assertTrue(cleared.text.isNullOrEmpty())
        val query = awaitNode { it.text?.toString() == "우리 앱 키로 인증·상태 1회 조회" }
        assertFalse(clickableParent(query).isEnabled)
    }

    private fun openKeyScreen() {
        instrumentation.waitForIdleSync()
        awaitNode { node ->
            val label = node.text?.toString()
            label == "차량 키" || label == "설정" || label == "초기 준비" || label == "개발자 진단"
        }
        if (findNode { it.text?.toString() == "차량 키" } == null) {
            if (findNode { it.text?.toString() == "초기 준비" } == null) clickText("설정")
            clickText("개발자 진단")
        }
        val keyTab = awaitNode { it.text?.toString() == "차량 키" }
        if (!keyTab.isSelected) {
            assertTrue(clickableParent(keyTab).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        }
        repeat(6) {
            val scroll = findNode { it.isScrollable }
            if (scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) return
            instrumentation.waitForIdleSync()
        }
    }

    private fun clickText(text: String) {
        val node = awaitNode { it.text?.toString() == text }
        assertTrue(clickableParent(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            findNode(predicate)?.let { return it }
            findNode { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(50)
        }
        throw AssertionError("시험 대상 앱의 화면 요소를 확인할 수 없습니다")
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        automation.clearCache()
        for (window in automation.windows) find(window.root, predicate)?.let { return it }
        return find(automation.rootInActiveWindow, predicate)
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.packageName?.toString() == context.packageName && predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
}
