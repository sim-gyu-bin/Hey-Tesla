package com.heytesla.app.speech

import com.heytesla.app.speech.product.ProductVoiceSession
import org.junit.Assert.*
import org.junit.Test

/** JNI 자원 경계만 주입하며 실제 prepared owner→제품 정책과 standalone full-close를 검사한다. */
class KoreanSpeechRecognizerTest {
    private val exact = "헤이 테슬라 프렁크 열어줘"
    private val pcm = byteArrayOf(0, 0, 0, -128, -1, 127, -1, -1)

    @Test fun standaloneStillLoadsOnceAndRequiresBothNativeReleases() {
        val backend = NativeBoundary(exact)
        val result = standalone(backend)
        assertEquals(listOf("models", "load", "stream", "accept", "decode", "text",
            "streamRelease", "recognizerRelease"), backend.events)
        assertNull(result.failure)
        assertEquals(exact, result.text)
        assertTrue(result.cleanupConfirmed)
        assertTrue(result.decodeCompleted)
        assertEquals(pcm.size, result.consumedBytes)
        assertArrayEquals(floatArrayOf(0f, -1f, 32767f / 32768f, -1f / 32768f),
            requireNotNull(backend.acceptedSnapshot), 0f)
        assertWiped(backend)
    }

    @Test fun standaloneCleanupIsNotStreamOnlyAndConstructionUncertaintyStaysFailClosed() {
        for (stage in listOf("load", "stream", "accept", "decode", "text", "streamRelease", "recognizerRelease")) {
            for (linkage in listOf(false, true)) {
                val backend = NativeBoundary(exact, setOf(stage), linkage)
                val result = standalone(backend)
                assertNull(result.text)
                assertEquals(stage !in setOf("load", "stream", "streamRelease", "recognizerRelease"),
                    result.cleanupConfirmed)
                assertEquals(when (stage) {
                    "load" -> KoreanSpeechFailure.LOAD_FAILED
                    "streamRelease", "recognizerRelease" -> KoreanSpeechFailure.CLEANUP_FAILED
                    else -> KoreanSpeechFailure.DECODE_FAILED
                }, result.failure)
                if (stage != "load") assertEquals("recognizerRelease", backend.events.last())
                if (backend.acceptedSamples != null) assertWiped(backend)
            }
        }
    }

    @Test fun standaloneInvalidMissingAndCanceledInputsDoNotLoadAnEngine() {
        for (size in listOf(0, -2, pcm.size - 1, pcm.size + 2)) {
            val backend = NativeBoundary(exact)
            val result = KoreanSpeechRecognizer.decode(pcm, size, backend, { 0L })
            assertEquals(KoreanSpeechFailure.INVALID_PCM, result.failure)
            assertTrue(result.cleanupConfirmed)
            assertTrue(backend.events.isEmpty())
        }
        val canceled = NativeBoundary(exact)
        val canceledResult = standalone(canceled) { true }
        assertEquals(KoreanSpeechFailure.CANCELED, canceledResult.failure)
        assertTrue(canceled.events.isEmpty())
        val missing = NativeBoundary(exact).apply { available = false }
        val missingResult = standalone(missing)
        assertEquals(KoreanSpeechFailure.MODEL_MISSING, missingResult.failure)
        assertTrue(missingResult.cleanupConfirmed)
        assertEquals(listOf("models"), missing.events)
    }

    @Test fun standaloneCancellationDuringDecodeOrFullCloseStillWaitsForBothReleases() {
        for (duringClose in listOf(false, true)) {
            var canceled = false
            val backend = NativeBoundary(exact).apply {
                if (duringClose) onRelease = { canceled = true } else onDecode = { canceled = true }
            }
            val result = standalone(backend) { canceled }
            assertEquals(KoreanSpeechFailure.CANCELED, result.failure)
            assertNull(result.text)
            assertTrue(result.cleanupConfirmed)
            assertEquals(listOf("streamRelease", "recognizerRelease"), backend.events.takeLast(2))
            assertWiped(backend)
        }
    }

    @Test fun repeatedNonCommandsUseOneEngineButNewReleasedStreamsBeforeExactSingleUseHandoff() {
        val backend = NativeBoundary("")
        val session = ProductVoiceSession()
        val owner = prepare(session, backend)
        assertEquals(listOf("models", "load"), backend.events)
        assertFalse(session.cleanupConfirmed)
        for ((index, text) in listOf("", "...", "오늘 날씨 어때", "아까 $exact 라고 했어", exact).withIndex()) {
            backend.finalText = text
            recognizing(session)
            backend.onDecode = {
                assertTrue(session.microphoneReleased)
                assertFalse(session.cleanupConfirmed)
                assertFalse(session.beginCapture())
                assertEquals(index, backend.events.count { it == "streamRelease" })
                assertFalse(backend.events.contains("recognizerRelease"))
            }
            val result = owner.decode(pcm, pcm.size)
            assertEquals(index > 0, result.modelReused)
            assertTrue(result.streamCleanupConfirmed)
            assertWiped(backend)
            val decision = session.acceptRecognition(result, pcm.size, 0, 2_000)
            assertEquals(if (text == exact) ProductVoiceSession.Decision.ACCEPTED else ProductVoiceSession.Decision.WAIT,
                decision)
            assertFalse(session.cleanupConfirmed)
        }
        assertEquals(1, backend.events.count { it == "load" })
        assertEquals(5, backend.events.count { it == "stream" })
        assertEquals(5, backend.events.count { it == "streamRelease" })
        val cleanup = owner.close()
        assertTrue(cleanup.recognizerCleanupConfirmed)
        assertSame(cleanup, owner.close())
        assertEquals(1, backend.events.count { it == "recognizerRelease" })
        session.recognizerClosed(cleanup)
        val completion = requireNotNull(session.complete(2_001))
        val gate = requireNotNull(completion.gate)
        assertTrue(completion.cleanupConfirmed)
        assertNull(gate.acceptedConfidence)
        assertTrue(gate.takeForDispatch(6_999, completion.microphoneReleased, completion.cleanupConfirmed))
        assertFalse(gate.takeForDispatch(6_999, true, true))
        assertNull(session.complete(7_000))
        assertEquals(KoreanSpeechFailure.CLEANUP_FAILED, owner.decode(pcm, pcm.size).failure)
        assertEquals(5, backend.events.count { it == "stream" })
    }

    @Test fun offAndParkLossDuringLoadNeverOpenCaptureAndAwaitActualRelease() {
        for (reason in listOf("PRODUCT_VOICE_NOTIFICATION_STOP", "FRUNK_EXPLICIT_P_REQUIRED", "MIC_PERMISSION_REVOKED")) {
            val session = ProductVoiceSession()
            assertTrue(session.start())
            assertTrue(session.beginPreparation())
            val backend = NativeBoundary(exact).apply {
                onLoad = {
                    session.stop(reason)
                    assertFalse(session.cleanupConfirmed)
                    assertFalse(session.beginCapture())
                    assertFalse(events.contains("recognizerRelease"))
                }
            }
            val preparation = KoreanSpeechRecognizer.prepare(backend, { 1_000L }) { session.stopReason.get() != null }
            session.preparationFinished(preparation.owner != null, preparation.recognizerCleanupConfirmed, preparation.failure)
            assertNull(preparation.owner)
            assertEquals(KoreanSpeechFailure.CANCELED, preparation.failure)
            assertEquals(listOf("models", "load", "recognizerRelease"), backend.events)
            assertTrue(session.cleanupConfirmed)
            assertFalse(session.beginCapture())
            val completion = requireNotNull(session.complete(2_000))
            assertNull(completion.gate)
            assertEquals(reason, completion.reason)
        }
    }

    @Test fun offAndReadinessLossDuringPreparedDecodeSealLateSuccessWithoutConcurrentEngineRelease() {
        for (reason in listOf("PRODUCT_VOICE_NOTIFICATION_STOP", "PRODUCT_VOICE_OWNER_LOST",
            "FRUNK_EXPLICIT_P_REQUIRED", "MIC_PERMISSION_REVOKED")) {
            val session = ProductVoiceSession()
            val backend = NativeBoundary(exact)
            val owner = prepare(session, backend)
            recognizing(session)
            backend.onDecode = {
                session.stop(reason)
                assertFalse(session.cleanupConfirmed)
                assertFalse(backend.events.contains("recognizerRelease"))
            }
            val result = owner.decode(pcm, pcm.size) { session.stopReason.get() != null }
            assertNull(result.text)
            assertEquals(KoreanSpeechFailure.CANCELED, result.failure)
            assertTrue(result.streamCleanupConfirmed)
            assertEquals("streamRelease", backend.events.last())
            assertFalse(backend.events.contains("text"))
            assertWiped(backend)
            assertEquals(ProductVoiceSession.Decision.STOP, session.acceptRecognition(result, pcm.size, 0, 2_000))
            assertFalse(session.cleanupConfirmed)
            session.recognizerClosed(owner.close())
            val completion = requireNotNull(session.complete(2_001))
            assertTrue(completion.cleanupConfirmed)
            assertNull(completion.gate)
            assertEquals(reason, completion.reason)
        }
    }

    @Test fun preparedConstructionDecodeAndStreamReleaseFailuresCannotBecomeWaitOrAuthorize() {
        for (stage in listOf("stream", "accept", "decode", "text", "streamRelease")) {
            for (linkage in listOf(false, true)) {
                val session = ProductVoiceSession()
                val backend = NativeBoundary(exact, setOf(stage), linkage)
                val owner = prepare(session, backend)
                recognizing(session)
                val result = owner.decode(pcm, pcm.size)
                assertNull(result.text)
                assertEquals(stage !in setOf("stream", "streamRelease"), result.streamCleanupConfirmed)
                assertFalse(backend.events.contains("recognizerRelease"))
                assertEquals(ProductVoiceSession.Decision.STOP, session.acceptRecognition(result, pcm.size, 0, 2_000))
                val cleanup = owner.close()
                assertTrue(cleanup.recognizerCleanupConfirmed)
                session.recognizerClosed(cleanup)
                val completion = requireNotNull(session.complete(2_001))
                assertNull(completion.gate)
                assertEquals(stage !in setOf("stream", "streamRelease"), completion.cleanupConfirmed)
                assertEquals(if (completion.cleanupConfirmed) "PRODUCT_VOICE_DECODE_FAILED" else ProductVoiceSession.CLEANUP_FAILED,
                    completion.reason)
                if (backend.acceptedSamples != null) assertWiped(backend)
            }
        }
    }

    @Test fun engineCloseFailureAfterExactResultCannotBeRepairedByDuplicateClose() {
        for (linkage in listOf(false, true)) {
            val session = ProductVoiceSession()
            val backend = NativeBoundary(exact, setOf("recognizerRelease"), linkage)
            val owner = prepare(session, backend)
            recognizing(session)
            val result = owner.decode(pcm, pcm.size)
            assertEquals(ProductVoiceSession.Decision.ACCEPTED, session.acceptRecognition(result, pcm.size, 0, 2_000))
            val cleanup = owner.close()
            assertFalse(cleanup.recognizerCleanupConfirmed)
            assertTrue(cleanup.streamCleanupConfirmed)
            assertSame(cleanup, owner.close())
            assertEquals(1, backend.events.count { it == "recognizerRelease" })
            session.recognizerClosed(cleanup)
            val completion = requireNotNull(session.complete(2_001))
            assertNull(completion.gate)
            assertFalse(completion.cleanupConfirmed)
            assertEquals(ProductVoiceSession.CLEANUP_FAILED, completion.reason)
        }
    }

    @Test fun readinessLossDuringOwnerCloseRevokesAlreadyAcceptedGate() {
        val session = ProductVoiceSession()
        val backend = NativeBoundary(exact)
        val owner = prepare(session, backend)
        recognizing(session)
        val result = owner.decode(pcm, pcm.size)
        assertEquals(ProductVoiceSession.Decision.ACCEPTED, session.acceptRecognition(result, pcm.size, 0, 2_000))
        backend.onRelease = { session.stop("FRUNK_EXPLICIT_P_REQUIRED") }
        session.recognizerClosed(owner.close())
        val completion = requireNotNull(session.complete(2_001))
        assertTrue(completion.cleanupConfirmed)
        assertNull(completion.gate)
        assertEquals("FRUNK_EXPLICIT_P_REQUIRED", completion.reason)
    }

    @Test fun preparedMissingAndFailedLoadKeepCleanAndUnknownOwnershipDistinct() {
        for (missing in listOf(false, true)) {
            val session = ProductVoiceSession()
            session.start()
            session.beginPreparation()
            val backend = NativeBoundary(exact, if (missing) emptySet() else setOf("load")).apply {
                available = !missing
            }
            val preparation = KoreanSpeechRecognizer.prepare(backend, { 1_000L })
            session.preparationFinished(preparation.owner != null, preparation.recognizerCleanupConfirmed, preparation.failure)
            assertNull(preparation.owner)
            assertFalse(session.beginCapture())
            assertEquals(missing, preparation.recognizerCleanupConfirmed)
            val completion = requireNotNull(session.complete(2_000))
            assertEquals(missing, completion.cleanupConfirmed)
            assertEquals(if (missing) "PRODUCT_VOICE_MODEL_MISSING" else ProductVoiceSession.CLEANUP_FAILED,
                completion.reason)
        }
    }

    @Test fun cancellationTogetherWithReleaseUncertaintyRetainsReservationForBothApis() {
        var canceled = false
        val standaloneBackend = NativeBoundary(exact, setOf("streamRelease", "recognizerRelease")).apply {
            onDecode = { canceled = true }
        }
        val standaloneResult = standalone(standaloneBackend) { canceled }
        assertNull(standaloneResult.text)
        assertFalse(standaloneResult.cleanupConfirmed)
        assertEquals(KoreanSpeechFailure.CANCELED, standaloneResult.failure)
        assertEquals(listOf("streamRelease", "recognizerRelease"), standaloneBackend.events.takeLast(2))
        assertWiped(standaloneBackend)
        val session = ProductVoiceSession()
        val backend = NativeBoundary(exact, setOf("streamRelease", "recognizerRelease"))
        val owner = prepare(session, backend)
        recognizing(session)
        backend.onDecode = { session.stop("PRODUCT_VOICE_NOTIFICATION_STOP") }
        val result = owner.decode(pcm, pcm.size) { session.stopReason.get() != null }
        assertNull(result.text)
        assertFalse(result.streamCleanupConfirmed)
        assertEquals(ProductVoiceSession.Decision.STOP, session.acceptRecognition(result, pcm.size, 0, 2_000))
        session.recognizerClosed(owner.close())
        val completion = requireNotNull(session.complete(2_001))
        assertFalse(completion.cleanupConfirmed)
        assertNull(completion.gate)
        assertEquals(ProductVoiceSession.CLEANUP_FAILED, completion.reason)
    }

    @Test fun singleNativeFinalTextKeepsWholePhraseNormalizationAndCancelFirstWithoutConfidence() {
        for (canceled in listOf(false, true)) {
            val session = ProductVoiceSession()
            val backend = NativeBoundary(if (canceled) "$exact 취소" else "헤이 테슬라, 프렁크 열어줘!")
            val owner = prepare(session, backend)
            recognizing(session)
            val result = owner.decode(pcm, pcm.size)
            assertEquals(if (canceled) ProductVoiceSession.Decision.STOP else ProductVoiceSession.Decision.ACCEPTED,
                session.acceptRecognition(result, pcm.size, 0, 2_000))
            session.recognizerClosed(owner.close())
            val completion = requireNotNull(session.complete(2_001))
            assertTrue(completion.cleanupConfirmed)
            if (canceled) {
                assertNull(completion.gate)
                assertEquals("FRUNK_VOICE_CANCELED", completion.reason)
            } else {
                assertNull(requireNotNull(completion.gate).acceptedConfidence)
            }
        }
    }

    @Test fun stopDuringStreamReleaseDiscardsAlreadyProducedTextAndWaitsForEngineClose() {
        val session = ProductVoiceSession()
        val backend = NativeBoundary(exact)
        val owner = prepare(session, backend)
        recognizing(session)
        backend.onStreamRelease = {
            assertTrue(backend.events.contains("text"))
            session.stop("PRODUCT_VOICE_NOTIFICATION_STOP")
            assertFalse(backend.events.contains("recognizerRelease"))
        }
        val result = owner.decode(pcm, pcm.size) { session.stopReason.get() != null }
        assertNull(result.text)
        assertEquals(KoreanSpeechFailure.CANCELED, result.failure)
        assertTrue(result.streamCleanupConfirmed)
        assertEquals(ProductVoiceSession.Decision.STOP, session.acceptRecognition(result, pcm.size, 0, 2_000))
        assertFalse(session.cleanupConfirmed)
        session.recognizerClosed(owner.close())
        val completion = requireNotNull(session.complete(2_001))
        assertTrue(completion.cleanupConfirmed)
        assertNull(completion.gate)
        assertEquals("PRODUCT_VOICE_NOTIFICATION_STOP", completion.reason)
    }

    private fun prepare(session: ProductVoiceSession, backend: NativeBoundary): PreparedKoreanSpeechRecognizer {
        assertTrue(session.start())
        assertTrue(session.beginPreparation())
        val preparation = KoreanSpeechRecognizer.prepare(backend, { 1_000L }) { session.stopReason.get() != null }
        session.preparationFinished(preparation.owner != null, preparation.recognizerCleanupConfirmed, preparation.failure)
        assertNull(preparation.failure)
        return requireNotNull(preparation.owner)
    }

    private fun recognizing(session: ProductVoiceSession) {
        assertTrue(session.beginCapture())
        session.captureFinished(released = true, clean = true)
        assertTrue(session.beginRecognition())
    }

    private fun standalone(backend: NativeBoundary, canceled: () -> Boolean = { false }) =
        KoreanSpeechRecognizer.decode(pcm, pcm.size, backend, { 1_000L }, canceled)

    private fun assertWiped(backend: NativeBoundary) {
        assertTrue("변환 PCM은 실제 native 반환·stream 해제 후 지워져야 한다",
            requireNotNull(backend.acceptedSamples).all { it == 0f })
    }

    private class NativeBoundary(
        var finalText: String,
        private val failures: Set<String> = emptySet(),
        private val linkage: Boolean = false,
    ) : KoreanSpeechRecognizer.Backend {
        val events = mutableListOf<String>()
        var available = true
        var onLoad: () -> Unit = {}
        var onDecode: () -> Unit = {}
        var onStreamRelease: () -> Unit = {}
        var onRelease: () -> Unit = {}
        var acceptedSamples: FloatArray? = null
        var acceptedSnapshot: FloatArray? = null

        private fun enter(stage: String) {
            events += stage
            if (stage in failures) {
                if (linkage) throw UnsatisfiedLinkError("Injected native boundary failure")
                throw IllegalStateException("Injected native boundary failure")
            }
        }

        override fun modelsAvailable(): Boolean { enter("models"); return available }
        override fun load(): KoreanSpeechRecognizer.Recognizer {
            enter("load")
            onLoad()
            return object : KoreanSpeechRecognizer.Recognizer {
                override fun createStream(hotwords: String): KoreanSpeechRecognizer.Stream {
                    assertEquals(SpeechCommandPhrases.sherpaHotwords, hotwords)
                    enter("stream")
                    return object : KoreanSpeechRecognizer.Stream {
                        override fun accept(samples: FloatArray, sampleRate: Int) {
                            acceptedSamples = samples
                            acceptedSnapshot = samples.copyOf()
                            assertEquals(16_000, sampleRate)
                            enter("accept")
                        }
                        override fun release() { enter("streamRelease"); onStreamRelease() }
                    }
                }
                override fun decode(stream: KoreanSpeechRecognizer.Stream) { enter("decode"); onDecode() }
                override fun text(stream: KoreanSpeechRecognizer.Stream): String { enter("text"); return finalText }
                override fun release() { enter("recognizerRelease"); onRelease() }
            }
        }
    }
}
