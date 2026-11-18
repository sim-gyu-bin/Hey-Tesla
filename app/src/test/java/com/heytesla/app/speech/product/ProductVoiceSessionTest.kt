package com.heytesla.app.speech.product

import com.heytesla.app.speech.KoreanSpeechCleanup
import com.heytesla.app.speech.KoreanSpeechFailure
import com.heytesla.app.speech.KoreanSpeechStreamResult
import com.heytesla.app.vehicle.frunk.FrunkVoicePolicy
import org.junit.Assert.*
import org.junit.Test

/** 자원 관측 fixture를 실제 제품 정책 소비 경계에 입력한다. native 동작은 별도 경계 테스트가 담당한다. */
class ProductVoiceSessionTest {
    private val exact = "헤이 테슬라 프렁크 열어줘"

    private fun prepared() = ProductVoiceSession().also {
        assertTrue(it.start())
        assertFalse(it.beginCapture())
        assertTrue(it.beginPreparation())
        assertFalse(it.cleanupConfirmed)
        it.preparationFinished(prepared = true, clean = false, failure = null)
    }

    private fun recognizing(session: ProductVoiceSession) {
        assertTrue(session.beginCapture())
        assertFalse(session.microphoneReleased)
        assertFalse(session.beginRecognition())
        session.captureFinished(released = true, clean = true)
        assertTrue(session.beginRecognition())
    }

    private fun ready() = prepared().also(::recognizing)

    private fun command(session: ProductVoiceSession, onset: Long = 0, now: Long = 2_000,
        text: String? = exact, clean: Boolean = true, failure: KoreanSpeechFailure? = null,
        consumedBytes: Int = 32_000, decoded: Boolean = true) = session.acceptRecognition(
        KoreanSpeechStreamResult(text, failure, 1, 1, consumedBytes, decoded, clean, false),
        expectedBytes = 32_000, startedAt = onset, now = now)

    private fun closed(session: ProductVoiceSession, engineClean: Boolean = true, streamClean: Boolean = true) {
        session.recognizerClosed(KoreanSpeechCleanup(streamClean, engineClean, 1))
    }

    @Test fun captureRequiresPreparationAndFullCloseIsRequiredBeforeSingleUseHandoff() {
        val session = prepared()
        assertFalse(session.cleanupConfirmed)
        assertFalse(session.beginPreparation())
        recognizing(session)
        assertEquals(ProductVoiceSession.Decision.ACCEPTED, command(session))
        assertFalse(session.cleanupConfirmed)
        assertTrue(session.streamCleanupConfirmed)
        assertFalse(session.recognizerCleanupConfirmed)
        assertFalse(session.beginCapture())
        closed(session)
        val result = requireNotNull(session.complete(2_001))
        assertTrue(result.microphoneReleased)
        assertTrue(result.cleanupConfirmed)
        val gate = requireNotNull(result.gate)
        assertEquals(FrunkVoicePolicy.Phase.ACCEPTED, gate.phase)
        assertNull(gate.acceptedConfidence)
        assertTrue(gate.takeForDispatch(2_002, result.microphoneReleased, result.cleanupConfirmed))
        assertFalse(gate.takeForDispatch(2_003, true, true))
        assertNull(session.complete(2_004))
        assertFalse(session.start())
    }

    @Test fun completingWithHeldEngineNeverReturnsGateOrReservation() {
        for (accepted in listOf(false, true)) {
            val session = ready()
            assertEquals(if (accepted) ProductVoiceSession.Decision.ACCEPTED else ProductVoiceSession.Decision.WAIT,
                command(session, text = if (accepted) exact else "오늘 날씨 어때"))
            val result = requireNotNull(session.complete(2_001))
            assertNull(result.gate)
            assertFalse(result.cleanupConfirmed)
            assertEquals(ProductVoiceSession.CLEANUP_FAILED, result.reason)
            closed(session)
            assertFalse(session.cleanupConfirmed)
            assertNull(session.complete(2_002))
        }
    }

    @Test fun emptyOrdinaryAndEmbeddedSpeechCanWaitRepeatedlyWithHeldEngine() {
        val session = ready()
        for (text in listOf("", "...", "오늘 날씨 어때", "아까 $exact 라고 했어")) {
            assertEquals(ProductVoiceSession.Decision.WAIT, command(session, text = text))
            assertNull(session.stopReason.get())
            assertTrue(session.streamCleanupConfirmed)
            assertFalse(session.cleanupConfirmed)
            assertTrue(session.canCapture)
            recognizing(session)
        }
        val later = 24 * 60 * 60 * 1_000L
        assertEquals(ProductVoiceSession.Decision.ACCEPTED,
            command(session, onset = later, now = later + 2_000, text = "헤이 테슬라, 프렁크 열어줘!"))
        closed(session)
        assertNotNull(requireNotNull(session.complete(later + 2_001)).gate)
    }

    @Test fun cancellationInFinalTextOverridesExactCommand() {
        val session = ready()
        assertEquals(ProductVoiceSession.Decision.STOP, command(session, text = "$exact 취소"))
        assertEquals("FRUNK_VOICE_CANCELED", session.stopReason.get())
        closed(session)
        val result = requireNotNull(session.complete(2_001))
        assertNull(result.gate)
        assertTrue(result.cleanupConfirmed)
    }

    @Test fun stopDuringPreparationSealsBeforeLateLoadSuccessAndWaitsForOwnerClose() {
        for (reason in listOf("PRODUCT_VOICE_NOTIFICATION_STOP", "FRUNK_EXPLICIT_P_REQUIRED",
            "MIC_PERMISSION_REVOKED", "PRODUCT_VOICE_OWNER_LOST", "PRODUCT_VOICE_CONSENT_REQUIRED")) {
            val session = ProductVoiceSession()
            assertTrue(session.start())
            assertTrue(session.beginPreparation())
            session.stop(reason)
            assertFalse(session.cleanupConfirmed)
            assertFalse(session.beginCapture())
            session.preparationFinished(prepared = true, clean = false, failure = null)
            assertFalse(session.beginCapture())
            assertFalse(session.cleanupConfirmed)
            closed(session)
            val completion = requireNotNull(session.complete(1))
            assertTrue(completion.cleanupConfirmed)
            assertNull(completion.gate)
            assertEquals(reason, completion.reason)
        }
    }

    @Test fun missingAndUncertainPreparationNeverFallBackToCapture() {
        for ((failure, clean) in listOf(KoreanSpeechFailure.MODEL_MISSING to true,
            KoreanSpeechFailure.LOAD_FAILED to false, KoreanSpeechFailure.CANCELED to true)) {
            val session = ProductVoiceSession()
            session.start()
            assertTrue(session.beginPreparation())
            session.preparationFinished(prepared = false, clean = clean, failure = failure)
            // 최초 준비 실패는 중복 성공으로 덮지 않는다.
            session.preparationFinished(prepared = true, clean = false, failure = null)
            assertFalse(session.beginCapture())
            val result = requireNotNull(session.complete(1))
            assertNull(result.gate)
            assertEquals(clean, result.cleanupConfirmed)
            if (!clean) assertEquals(ProductVoiceSession.CLEANUP_FAILED, result.reason)
            else assertNotNull(result.reason)
        }
    }

    @Test fun offOrReadinessLossDuringDecodeDiscardsLateExactSuccess() {
        for (reason in listOf("PRODUCT_VOICE_OWNER_LOST", "FRUNK_EXPLICIT_P_REQUIRED",
            "PRODUCT_VOICE_NOTIFICATION_STOP", "MIC_PERMISSION_REVOKED", "INPUT_SILENCED")) {
            val session = ready()
            session.stop(reason)
            assertFalse(session.cleanupConfirmed)
            // stream이 아직 실행 중이면 full-close 성공 보고도 받아들이지 않는다.
            closed(session)
            assertFalse(session.recognizerCleanupConfirmed)
            assertEquals(ProductVoiceSession.Decision.STOP, command(session))
            assertTrue(session.streamCleanupConfirmed)
            assertFalse(session.cleanupConfirmed)
            closed(session)
            val result = requireNotNull(session.complete(2_001))
            assertNull(result.gate)
            assertTrue(result.cleanupConfirmed)
            assertEquals(reason, result.reason)
        }
    }

    @Test fun engineCloseFailureAfterAcceptedDiscardsGateAndDuplicateCloseCannotRepairIt() {
        val session = ready()
        assertEquals(ProductVoiceSession.Decision.ACCEPTED, command(session))
        closed(session, engineClean = false)
        closed(session)
        val result = requireNotNull(session.complete(2_001))
        assertNull(result.gate)
        assertFalse(result.cleanupConfirmed)
        assertEquals(ProductVoiceSession.CLEANUP_FAILED, result.reason)
    }

    @Test fun streamUncertaintySurvivesOwnerCloseAndDuplicateResult() {
        val session = ready()
        assertEquals(ProductVoiceSession.Decision.STOP, command(session, clean = false))
        assertEquals(ProductVoiceSession.Decision.STOP, command(session))
        closed(session)
        val result = requireNotNull(session.complete(2_001))
        assertTrue(result.microphoneReleased)
        assertFalse(result.cleanupConfirmed)
        assertNull(result.gate)
        assertEquals(ProductVoiceSession.CLEANUP_FAILED, result.reason)
    }

    @Test fun microphoneOrCallbackCleanupUncertaintyPreservesReservationEvenAfterOwnerClose() {
        for ((released, clean) in listOf(false to false, false to true, true to false)) {
            val session = prepared()
            assertTrue(session.beginCapture())
            session.stop("PRODUCT_VOICE_NOTIFICATION_STOP")
            session.captureFinished(released, clean)
            session.captureFinished(released = true, clean = true)
            assertFalse(session.beginRecognition())
            closed(session)
            val result = requireNotNull(session.complete(2_001))
            assertEquals(released, result.microphoneReleased)
            assertFalse(result.cleanupConfirmed)
            assertNull(result.gate)
            assertEquals(ProductVoiceSession.CLEANUP_FAILED, result.reason)
        }
    }

    @Test fun originalOnsetAndHandoffDeadlinesCannotBeRenewedByCloseOrDuplicateResults() {
        val expired = ready()
        assertEquals(ProductVoiceSession.Decision.STOP, command(expired, onset = 50_000, now = 60_000))
        closed(expired)
        assertEquals("FRUNK_VOICE_EXPIRED", requireNotNull(expired.complete(60_001)).reason)
        val accepted = ready()
        assertEquals(ProductVoiceSession.Decision.ACCEPTED, command(accepted))
        assertEquals(ProductVoiceSession.Decision.STOP, command(accepted, onset = 6_000, now = 6_001))
        closed(accepted)
        val result = requireNotNull(accepted.complete(7_000))
        assertNull(result.gate)
        assertEquals("FRUNK_VOICE_EXPIRED", result.reason)
    }

    @Test fun incompleteNullOrFailedDecodeCannotBecomeOrdinarySpeech() {
        for (failure in listOf(null, KoreanSpeechFailure.DECODE_FAILED)) {
            val session = ready()
            assertEquals(ProductVoiceSession.Decision.STOP,
                command(session, failure = failure, consumedBytes = 31_998))
            closed(session)
            val result = requireNotNull(session.complete(2_001))
            assertNull(result.gate)
            assertTrue(result.cleanupConfirmed)
            assertEquals(if (failure == null) "PRODUCT_VOICE_PCM_INCOMPLETE" else "PRODUCT_VOICE_DECODE_FAILED",
                result.reason)
        }
        for (decoded in listOf(false, true)) {
            val session = ready()
            assertEquals(ProductVoiceSession.Decision.STOP, command(session, text = null, decoded = decoded))
            closed(session)
            assertEquals("PRODUCT_VOICE_DECODE_FAILED", requireNotNull(session.complete(2_001)).reason)
        }
    }

    @Test fun stopAfterAcceptedRevokesGateAndCompletionBeforeAcquisitionIsClean() {
        val session = ready()
        assertEquals(ProductVoiceSession.Decision.ACCEPTED, command(session))
        session.stop("PRODUCT_VOICE_NOTIFICATION_STOP")
        closed(session)
        assertNull(requireNotNull(session.complete(2_001)).gate)
        val rejected = ProductVoiceSession()
        rejected.stop("PRODUCT_VOICE_SERVICE_REJECTED")
        assertFalse(rejected.start())
        val result = requireNotNull(rejected.complete(0))
        assertTrue(result.cleanupConfirmed)
        assertTrue(result.microphoneReleased)
        assertNull(result.gate)
        assertNull(rejected.complete(1))
    }
}
