package com.heytesla.app.ui

import com.heytesla.app.BleDetectionPreference
import com.heytesla.app.DiagnosticRuntime
import com.heytesla.app.MainActivity
import com.heytesla.app.speech.product.ProductVoiceDiagnostic
import com.heytesla.app.speech.product.ProductVoiceTranscriptRecord
import com.heytesla.app.vehicle.key.VehicleVinStatus

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
    fun restoredOnAndDismissedConsentNeverGrantMicrophoneConsent() {
        val host = VehicleVinUiTestHost(initiallyEnabledService = true)
        host.launch()
        try {
            instrumentation.runOnMainSync {
                assertFalse(host.runtime.state.value.productVoiceConsent)
                assertNoRadioOrAudioWork(host.runtime)
            }
            clickText("음성 프렁크 사용 안내")
            clickText("동의하지 않음")
            instrumentation.runOnMainSync {
                assertFalse(host.runtime.state.value.productVoiceConsent)
                assertEquals(0, host.consentChangeCount)
                host.installContent()
            }
            instrumentation.waitForIdleSync()
            assertTrue(awaitServiceSwitch().isChecked)
            instrumentation.runOnMainSync {
                assertFalse(host.runtime.state.value.productVoiceConsent)
                assertNoRadioOrAudioWork(host.runtime)
            }
            assertTrue(awaitServiceSwitch().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            host.await { !host.runtime.state.value.bleDetectionEnabled }
            assertEquals(0, host.startCount)
        } finally { host.close() }
    }

    @Test
    fun explicitConsentDoesNotStartAnApproachAndRevocationRemainsAvailable() {
        val host = VehicleVinUiTestHost(initiallyEnabledService = true)
        host.launch()
        try {
            clickText("음성 프렁크 사용 안내")
            clickText("동의하고 사용")
            host.await { host.runtime.state.value.productVoiceConsent }
            instrumentation.runOnMainSync { assertNoRadioOrAudioWork(host.runtime) }
            assertEquals(1, host.consentChangeCount)
            instrumentation.runOnMainSync {
                host.foreignBlocked = "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED"
                host.state.value = host.state.value.copy(microphonePermission = false, assistant = false)
                host.invalidate()
            }
            clickText("음성 동의 철회·중지")
            host.await { !host.runtime.state.value.productVoiceConsent }
            assertEquals(2, host.consentChangeCount)
            assertTrue(awaitServiceSwitch().isChecked)
            assertTrue(awaitServiceSwitch().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            host.await { !host.runtime.state.value.bleDetectionEnabled }
            instrumentation.runOnMainSync { assertNoRadioOrAudioWork(host.runtime) }
            assertEquals(0, host.startCount)
        } finally { host.close() }
    }

    @Test
    fun homeShowsMismatchEmptyAndTruncatedWithoutReusingPreviousTextOrExecuting() {
        val host = VehicleVinUiTestHost(isolatedVinStore = true)
        host.launch()
        try {
            val mismatch = "합성 확인 문장 · 오늘 하늘은 맑아요"
            showResults(host, listOf(
                voiceResult(3, null, "FAILURE", "PRODUCT_VOICE_UTTERANCE_TRUNCATED"),
                voiceResult(2, "", "EMPTY", "EMPTY"),
                voiceResult(1, mismatch, "GRAMMAR_NO_MATCH", "GRAMMAR_NO_MATCH"),
            ))
            assertFalse(awaitServiceSwitch().isChecked)
            awaitNode { it.text?.toString() == "최근 음성 결과" }
            awaitNode { it.text?.toString() == "인식 전 발화 잘림 · 원문 없음" }
            awaitNode { it.text?.toString() == "사유: PRODUCT_VOICE_UTTERANCE_TRUNCATED" }
            awaitNode { it.text?.toString() == "음성 처리 실패 · 명령 실행 허가 아님" }
            awaitNode { it.text?.toString() == "인식 원문: (빈 결과)" }
            awaitNode { it.text?.toString() == "빈 인식 결과 · 명령 불일치" }
            awaitNode { it.text?.toString() == mismatch }
            awaitNode { it.text?.toString() == "명령 불일치" }
            awaitNode { it.text?.toString()?.startsWith("시각: ") == true }
            instrumentation.runOnMainSync {
                assertFalse(host.runtime.state.value.productVoiceConsent)
                assertNoRadioOrAudioWork(host.runtime)
            }
            assertEquals(0, host.startCount)
            assertEquals(0, host.consentChangeCount)
        } finally { host.close() }
    }

    @Test
    fun longHomeTranscriptRemainsAccessibleAtLargeFontAndAfterReentry() {
        val host = VehicleVinUiTestHost(isolatedVinStore = true, fontScale = 1.6f)
        host.launch()
        try {
            val longText = "합성 긴 문장 시작\n" + "주변 대화도 명령으로 실행하지 않고 결과만 확인합니다. ".repeat(18) +
                "\n합성 긴 문장 끝"
            showResults(host, listOf(voiceResult(1, longText, "GRAMMAR_NO_MATCH", "GRAMMAR_NO_MATCH")))
            awaitNode { it.text?.toString() == longText }
            // 긴 문장 아래의 실제 행동에도 스크롤로 접근할 수 있어야 한다.
            clickText("음성 프렁크 사용 안내")
            clickText("동의하지 않음")
            clickText("설정")
            clickText("개발자 진단")
            clickText("홈")
            awaitNode { it.text?.toString() == longText }
            assertFalse(awaitServiceSwitch().isChecked)
            instrumentation.runOnMainSync { assertNoRadioOrAudioWork(host.runtime) }
            assertEquals(0, host.startCount)
            assertEquals(0, host.consentChangeCount)
        } finally { host.close() }
    }

    @Test
    fun homeDistinguishesNoResultsLoadingReadFailureAndUnsavedRamResults() {
        val host = VehicleVinUiTestHost(isolatedVinStore = true)
        host.launch()
        try {
            showResults(host, emptyList())
            awaitNode { it.text?.toString() == "아직 음성 결과가 없어요." }
            instrumentation.runOnMainSync {
                host.runtime.update { it.copy(productVoiceResultsLoading = true) }
            }
            awaitNode { it.text?.toString() == "저장된 음성 결과를 불러오는 중이에요." }
            assertEquals(null, findNode { it.text?.toString() == "아직 음성 결과가 없어요." })
            instrumentation.runOnMainSync {
                host.runtime.update { it.copy(productVoiceResultsLoading = false,
                    productVoiceResultsReadFailure = "INVALID_RECORD") }
            }
            awaitNode { it.text?.toString()?.startsWith("음성 결과 읽기 실패") == true }
            assertEquals(null, findNode { it.text?.toString() == "아직 음성 결과가 없어요." })
            val unsaved = "합성 저장 실패에도 화면에서 확인할 결과"
            instrumentation.runOnMainSync {
                host.runtime.update { it.copy(
                    productVoiceResults = listOf(voiceResult(1, unsaved, "GRAMMAR_NO_MATCH", "GRAMMAR_NO_MATCH")),
                    productTranscriptWriteFailure = "QUEUE_FULL",
                ) }
            }
            awaitNode { it.text?.toString()?.startsWith("음성 결과 저장 실패") == true }
            awaitNode { it.text?.toString() == unsaved }
            instrumentation.runOnMainSync {
                assertEquals(0, host.runtime.state.value.productTranscriptSavedCount)
                assertNoRadioOrAudioWork(host.runtime)
            }
        } finally { host.close() }
    }

    @Test
    fun homeExposesOnlyFiveRecentResultsInNewestFirstAccessibilityOrder() {
        val host = VehicleVinUiTestHost(isolatedVinStore = true)
        host.launch()
        try {
            val records = (6L downTo 1L).map { utterance ->
                voiceResult(utterance, "합성 최근 결과 $utterance", "GRAMMAR_NO_MATCH", "GRAMMAR_NO_MATCH")
            }
            showResults(host, records)
            awaitServiceSwitch()
            val observed = linkedSetOf<String>()
            repeat(30) {
                automation.clearCache()
                fun collect(node: AccessibilityNodeInfo?) {
                    if (node == null) return
                    val text = node.text?.toString()
                    if (node.packageName?.toString() == context.packageName &&
                        text?.startsWith("합성 최근 결과 ") == true) observed += text
                    for (index in 0 until node.childCount) collect(node.getChild(index))
                }
                collect(automation.rootInActiveWindow)
                // Compose의 접근성 page-scroll은 비동기 애니메이션이다. waitForIdleSync만으로
                // 연속 page-scroll을 보내면 중간 viewport를 읽기 전에 바닥까지 이동할 수 있다.
                // 짧은 실제 drag와 정지 구간으로 viewport를 겹쳐 읽고 fling은 만들지 않는다.
                dragHomeBySmallStep()
            }
            assertEquals((6 downTo 2).map { "합성 최근 결과 $it" }, observed.toList())
            instrumentation.runOnMainSync { assertNoRadioOrAudioWork(host.runtime) }
            assertEquals(0, host.startCount)
        } finally { host.close() }
    }

    private fun dragHomeBySmallStep() {
        val scroll = findNode { it.isScrollable }
            ?: throw AssertionError("홈 결과를 탐색할 스크롤 영역이 없습니다")
        val bounds = Rect()
        scroll.getBoundsInScreen(bounds)
        val x = bounds.exactCenterX()
        val startY = bounds.top + bounds.height() * 0.75f
        val distance = bounds.height() * 0.25f
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("홈 스크롤 터치가 전달되어야 합니다", automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        touch(MotionEvent.ACTION_DOWN, startY)
        for (step in 1..10) {
            SystemClock.sleep(20)
            touch(MotionEvent.ACTION_MOVE, startY - distance * step / 10)
        }
        // 마지막 위치에 머물러 손을 떼는 속도로 인한 관성 스크롤을 막는다.
        SystemClock.sleep(150)
        touch(MotionEvent.ACTION_MOVE, startY - distance)
        touch(MotionEvent.ACTION_UP, startY - distance)
        automation.waitForIdle(150, 3_000)
        instrumentation.waitForIdleSync()
    }

    @Test
    fun notificationEntryReturnsExistingDiagnosticsToHomeAndNeverReplaysAcceptedResult() {
        val host = VehicleVinUiTestHost(isolatedVinStore = true)
        host.launch()
        try {
            val recognized = "합성 보관된 명령 문장"
            showResults(host, listOf(voiceResult(1, recognized, "ACCEPTED", "GRAMMAR_ACCEPTED")))
            openDiagnostics()
            val notificationEntry = PendingIntent.getActivity(
                context, 861,
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_SHOW_VOICE_RESULTS)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            notificationEntry.send()
            awaitNode { it.text?.toString() == "최근 음성 결과" }
            awaitNode { it.text?.toString() == recognized }
            awaitNode { it.text?.toString() == "명령 문장 인정 · 차량 실행 결과 아님" }
            // 같은 알림의 재진입도 새 실행이 아니라 표시 이동이어야 한다.
            clickText("설정")
            notificationEntry.send()
            awaitNode { it.text?.toString() == recognized }
            assertFalse(awaitServiceSwitch().isChecked)
            instrumentation.runOnMainSync {
                assertFalse(host.runtime.state.value.productVoiceConsent)
                assertNoRadioOrAudioWork(host.runtime)
            }
            assertEquals(0, host.startCount)
            assertEquals(0, host.consentChangeCount)
        } finally { host.close() }
    }

    private fun showResults(host: VehicleVinUiTestHost, records: List<ProductVoiceTranscriptRecord>) {
        instrumentation.runOnMainSync {
            host.runtime.update { it.copy(productVoiceResults = records, productVoiceResultsLoading = false,
                productVoiceResultsReadFailure = null, productTranscriptWriteFailure = null) }
        }
        host.await { host.state.value.productVoiceResults == records && !host.state.value.productVoiceResultsLoading }
    }

    private fun voiceResult(utterance: Long, text: String?, outcome: String, reason: String) =
        ProductVoiceTranscriptRecord(
            processId = "synthetic-ui", trialId = null, voiceLeaseId = 86,
            utterance = utterance, wallTimeMs = 1_800_000_000_000L + utterance * 1_000,
            elapsedRealtimeMs = utterance * 1_000, appVersion = "synthetic-ui",
            text = text, reason = reason,
            diagnostic = ProductVoiceDiagnostic(outcome, utteranceBytes = 4_000, voicedBytes = 2_000,
                microphoneReleased = true, streamCleanupConfirmed = true,
                recognizerCleanupConfirmed = outcome != "GRAMMAR_NO_MATCH" && outcome != "EMPTY"),
        )

    private fun openDiagnostics() {
        clickText("설정")
        clickText("개발자 진단")
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
