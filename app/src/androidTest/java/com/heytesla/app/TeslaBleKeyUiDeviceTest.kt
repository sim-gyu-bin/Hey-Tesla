package com.heytesla.app

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith

/** 격리 VIN/키 저장소와 실제 화면 consumer를 시험한다. 준비 중 취소로 GATT·TX·차량 등록을 막는다. */
@RunWith(AndroidJUnit4::class)
class TeslaBleKeyUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val fixture = VehicleVinUiTestHost()
    private val runtime get() = fixture.runtime

    @After
    fun closeFixture() { fixture.close() }

    @Test
    fun readOnlyStartCanceledDuringKeyPreparationReleasesItsLeaseWithoutTransmission() {
        launchApp()
        instrumentation.runOnMainSync {
            try {
                runtime.startTeslaKey(register = false)
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
    fun driveQueryPauseDuringKeyPreparationCancelsWithoutTransmissionOrAutoResume() {
        val activity = launchApp()
        instrumentation.runOnMainSync {
            try {
                runtime.startTeslaKey(register = false, query = TeslaBleQuery.DRIVE_STATE)
                assertEquals(TeslaKeyStage.PREPARING_KEY, runtime.teslaKeyState.value.stage)
                assertEquals(TeslaBleQuery.DRIVE_STATE, runtime.teslaKeyState.value.query)
                assertFalse(runtime.teslaKeyState.value.registrationRequested)
                assertTrue(runtime.teslaKeyReserved())
                // fixture도 실제 Activity pause와 같은 가시성 박탈·취소 순서로 봉인한다.
                instrumentation.callActivityOnPause(activity)
                runtime.activityVisible = false
                runtime.cancelTeslaKey("UI_PAUSED")
                assertFalse(runtime.activityVisible)
            } finally {
                runtime.cancelTeslaKey()
                instrumentation.callActivityOnResume(activity)
                runtime.activityVisible = true
            }
        }
        awaitRuntime { !runtime.teslaKeyReserved() }
        instrumentation.runOnMainSync {
            val result = runtime.teslaKeyState.value
            assertEquals(TeslaBleQuery.DRIVE_STATE, result.query)
            assertEquals(TeslaKeyStage.CANCELED, result.stage)
            assertEquals(TeslaKeyOutcome.CANCELED, result.outcome)
            assertFalse(result.transmissionAttempted)
            assertFalse(result.registrationRequested)
            assertTrue(result.localClosed)
            assertNull(result.status)
            assertFalse(runtime.state.value.teslaKeyDiagnosticActive)
            assertNull(runtime.teslaKeyBlockedReason())
        }
    }

    @Test
    fun driveObservationsStayHistoricalAndUnknownOrCleanupNeverBecomesParkingSuccess() {
        launchApp()
        openKeyScreen()
        var original = TeslaKeyProbeState()
        instrumentation.runOnMainSync {
            assertFalse(runtime.teslaKeyReserved())
            original = runtime.teslaKeyState.value
        }
        try {
            fun show(gear: TeslaGear?, sourceTime: Long?, title: String, cleanupFailed: Boolean = false) {
                val drive = gear?.let { TeslaDriveState(it, sourceTime, SystemClock.elapsedRealtime()) }
                instrumentation.runOnMainSync {
                    // RAM 화면 소비 경계만 합성한다. start·GATT·TX·키 등록은 호출하지 않는다.
                    runtime.teslaKeyState.value = TeslaKeyProbeState(
                        query = TeslaBleQuery.DRIVE_STATE,
                        stage = if (cleanupFailed) TeslaKeyStage.CLEANUP_FAILED else TeslaKeyStage.COMPLETE,
                        outcome = TeslaKeyOutcome.VERIFIED_STATUS,
                        status = TeslaReadOnlyStatus(null, null, drive),
                        localClosed = !cleanupFailed,
                    )
                }
                openKeyScreen()
                awaitNode { it.text?.toString() == title }
                assertNull(findNode { it.text?.toString() == "조회 성공" })
                assertNull(findNode { it.text?.toString() == "잠금 상태" })
                assertNull(findNode { it.text?.toString() == "프렁크 상태" })
                awaitNode { it.text?.toString()?.contains("제어 허가에 재사용하지 않습니다") == true }
                instrumentation.runOnMainSync {
                    assertFalse(runtime.teslaKeyReserved())
                    assertNull(runtime.policy.current)
                    assertNull(runtime.microphone)
                }
                // 다음 상태의 핵심 값과 입력 게이트도 위에서부터 관찰한다.
                openKeyScreen()
            }
            // 과거 원본 시각이 있어도 현재 P/근접/제어 허가가 되지 않는다.
            show(TeslaGear.P, 1_000L, "P 관측 · 지난 조회")
            awaitNode { it.text?.toString() == "P · 지난 조회에서 관측" }
            awaitNode { it.text?.toString()?.startsWith("차량 원본 시각 있음") == true }
            show(TeslaGear.P, null, "P 관측 · 현재 P 확인 안 됨")
            awaitNode { it.text?.toString()?.startsWith("차량 원본 시각 없음") == true }
            for (gear in listOf(TeslaGear.R, TeslaGear.N, TeslaGear.D)) {
                show(gear, 1_000L, "${gear.name} 관측 · P 아님")
                awaitNode { it.text?.toString() == "${gear.name} · P 아님" }
            }
            show(TeslaGear.UNKNOWN, 1_000L, "주차 기어 확인 안 됨")
            awaitNode { it.text?.toString() == "UNKNOWN · 확인 안 됨" }
            show(null, null, "주차 기어 확인 안 됨")
            awaitNode { it.text?.toString() == "UNKNOWN · 확인 안 됨" }
            show(TeslaGear.P, 1_000L, "정리 실패", cleanupFailed = true)
            assertNull(findNode { it.text?.toString() == "P 관측 · 지난 조회" })
            assertFalse(clickableParent(awaitNode { it.text?.toString() == "주차 기어 상태 1회 조회" }).isEnabled)
            awaitNode { it.isContentInvalid && !it.error.isNullOrEmpty() }
        } finally {
            instrumentation.runOnMainSync { runtime.teslaKeyState.value = original }
            clickText("홈")
        }
    }

    @Test
    fun registrationReportDoesNotBecomeSuccessUntilAuthenticatedStatusIsComplete() {
        launchApp()
        clickText("설정")
        clickText("차량에 앱 키 등록")
        var original = TeslaKeyProbeState()
        instrumentation.runOnMainSync { original = runtime.teslaKeyState.value }
        try {
            fun show(state: TeslaKeyProbeState, title: String) {
                instrumentation.runOnMainSync { runtime.teslaKeyState.value = state }
                awaitNode { it.text?.toString() == title }
            }
            show(TeslaKeyProbeState(stage = TeslaKeyStage.WAITING_FOR_CARD,
                outcome = TeslaKeyOutcome.PENDING, registrationRequested = true), "키카드 승인 대기")
            show(TeslaKeyProbeState(stage = TeslaKeyStage.HANDSHAKING,
                outcome = TeslaKeyOutcome.PENDING, registrationRequested = true,
                registrationReported = true), "앱 키 확인 중")
            show(TeslaKeyProbeState(stage = TeslaKeyStage.FAILED,
                outcome = TeslaKeyOutcome.UNKNOWN, registrationRequested = true,
                registrationReported = true), "등록 보고 수신 · 앱 키 확인 실패")
            assertNull(findNode { it.text?.toString() == "등록 보고 수신 · 상태 확인됨" })
            show(TeslaKeyProbeState(stage = TeslaKeyStage.COMPLETE,
                outcome = TeslaKeyOutcome.VERIFIED_STATUS, registrationRequested = true,
                registrationReported = true), "등록 보고 수신 · 상태 확인됨")
            show(TeslaKeyProbeState(stage = TeslaKeyStage.CLEANUP_FAILED,
                outcome = TeslaKeyOutcome.VERIFIED_STATUS, registrationRequested = true,
                registrationReported = true), "정리 실패")
            assertNull(findNode { it.text?.toString() == "등록 보고 수신 · 상태 확인됨" })
        } finally {
            instrumentation.runOnMainSync { runtime.teslaKeyState.value = original }
            clickText("홈")
        }
    }

    @Test
    fun registrationCanceledDuringKeyPreparationDoesNotTransmitOrClaimRegistration() {
        launchApp()
        instrumentation.runOnMainSync {
            try {
                runtime.startTeslaKey(register = true)
                assertEquals(TeslaKeyStage.PREPARING_KEY, runtime.teslaKeyState.value.stage)
                assertTrue(runtime.teslaKeyState.value.registrationRequested)
                assertTrue(runtime.teslaKeyReserved())
            } finally {
                runtime.cancelTeslaKey()
            }
        }
        awaitRuntime { !runtime.teslaKeyReserved() }
        instrumentation.runOnMainSync {
            val result = runtime.teslaKeyState.value
            assertEquals(TeslaKeyOutcome.CANCELED, result.outcome)
            assertTrue(result.localClosed)
            assertFalse(result.transmissionAttempted)
            assertFalse(result.registrationReported)
            assertFalse(result.registrationUncertain)
            assertNull(runtime.teslaKeyBlockedReason())
        }
    }

    @Test
    fun owningKeyLeaseDisablesReentryWithoutAnAdmissionErrorButForeignLeaseShowsOne() {
        launchApp()
        openKeyScreen()
        assertTrue(clickableParent(awaitNode { it.text?.toString() == "우리 앱 키로 인증·상태 1회 조회" }).isEnabled)
        assertTrue(clickableParent(awaitNode { it.text?.toString() == "주차 기어 상태 1회 조회" }).isEnabled)
        openKeyScreen()
        var token: Long? = null
        instrumentation.runOnMainSync {
            runtime.refresh()
            token = runtime.acquireTeslaKey()
        }
        assertNotNull(token)
        try {
            assertFalse(clickableParent(awaitNode { it.text?.toString() == "우리 앱 키로 인증·상태 1회 조회" }).isEnabled)
            assertFalse(clickableParent(awaitNode { it.text?.toString() == "키 추가 요청 · 카드 승인 후 상태 1회 조회" }).isEnabled)
            assertFalse(clickableParent(awaitNode { it.text?.toString() == "주차 기어 상태 1회 조회" }).isEnabled)
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
            assertFalse(clickableParent(awaitNode { it.text?.toString() == "우리 앱 키로 인증·상태 1회 조회" }).isEnabled)
            assertFalse(clickableParent(awaitNode { it.text?.toString() == "주차 기어 상태 1회 조회" }).isEnabled)
            val error = awaitNode { it.isContentInvalid }
            assertFalse(error.error.isNullOrEmpty())
        } finally {
            instrumentation.runOnMainSync { runtime.releaseUwbSupport(cleanupFailed = false) }
        }
    }

    @Test
    fun directRegistrationUsesCommonVinButRequiresFreshConsentAfterLeaving() {
        launchApp()
        clickText("설정")
        clickText("차량에 앱 키 등록")
        val register = awaitNode { it.text?.toString() == "차량에 앱 키 등록 시작" }
        assertFalse(clickableParent(register).isEnabled)
        assertFalse(clickableParent(awaitNode { it.text?.toString() == "차량에 앱 키 등록 시작" }).isEnabled)
        val consent = awaitNode { it.contentDescription?.toString() == "우리 앱 키 추가와 차량 키카드 직접 승인에 동의" }
        assertFalse(consent.isChecked)
        assertTrue(clickableParent(consent).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue(clickableParent(awaitNode { it.text?.toString() == "차량에 앱 키 등록 시작" }).isEnabled)
        // 실제 등록 버튼은 누르지 않는다. 차량 키카드 승인과 전송은 사용자만 실행한다.
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        clickText("차량에 앱 키 등록")
        assertFalse(awaitNode { it.contentDescription?.toString() == "우리 앱 키 추가와 차량 키카드 직접 승인에 동의" }.isChecked)
        assertFalse(clickableParent(awaitNode { it.text?.toString() == "차량에 앱 키 등록 시작" }).isEnabled)
    }

    private fun launchApp(): MainActivity {
        val activity = fixture.launch()
        fixture.seedRegisteredVin()
        return activity
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
    fun backgroundingKeepsRegisteredVinButDoesNotAuthorizeOrRestartKeyRegistration() {
        val activity = launchApp()
        openKeyScreen()
        assertTrue(clickableParent(awaitNode { it.text?.toString() == "우리 앱 키로 인증·상태 1회 조회" }).isEnabled)
        assertTrue(clickableParent(awaitNode { it.text?.toString() == "주차 기어 상태 1회 조회" }).isEnabled)
        val consent = awaitNode { it.contentDescription?.toString() == "우리 앱 키 추가와 차량 키카드 직접 승인에 동의" }
        assertFalse(consent.isChecked)
        assertTrue(clickableParent(consent).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.runOnMainSync { instrumentation.callActivityOnPause(activity) }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { instrumentation.callActivityOnResume(activity) }
        assertFalse(awaitNode { it.contentDescription?.toString() == "우리 앱 키 추가와 차량 키카드 직접 승인에 동의" }.isChecked)
        assertFalse(clickableParent(awaitNode { it.text?.toString() == "키 추가 요청 · 카드 승인 후 상태 1회 조회" }).isEnabled)
        assertTrue(clickableParent(awaitNode { it.text?.toString() == "우리 앱 키로 인증·상태 1회 조회" }).isEnabled)
        assertTrue(clickableParent(awaitNode { it.text?.toString() == "주차 기어 상태 1회 조회" }).isEnabled)
        assertEquals(0, fixture.startCount)
        assertFalse(runtime.teslaKeyReserved())
    }

    private fun openKeyScreen() {
        instrumentation.waitForIdleSync()
        repeat(20) {
            if (findNode { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true) {
                instrumentation.waitForIdleSync()
            }
        }
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
        repeat(12) {
            if (findNode { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) return@repeat
            instrumentation.waitForIdleSync()
        }
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
