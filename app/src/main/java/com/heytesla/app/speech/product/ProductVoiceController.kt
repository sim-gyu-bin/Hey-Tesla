package com.heytesla.app.speech.product

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import com.heytesla.app.speech.KoreanSpeechFailure
import com.heytesla.app.speech.KoreanSpeechRecognizer
import com.heytesla.app.speech.KoreanSpeechStreamResult
import com.heytesla.app.speech.PreparedKoreanSpeechRecognizer
import com.heytesla.app.speech.SpeechCommandParser
import com.heytesla.app.vehicle.frunk.FrunkVoicePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 한 활성 제품 lease만의 RAM 음성 경로. PCM 저장소와 전역 모델 cache를 사용하지 않는다.
 * Main은 즉시 봉인하며 JNI load/decode/close는 다른 dispatcher에서 순차 반환까지 기다린다.
 */
internal class ProductVoiceController(
    context: Context,
    private val now: () -> Long,
    private val allowed: () -> String?,
    private val onState: (ProductVoiceState) -> Unit,
    private val onFinished: (ProductVoiceCompletion) -> Unit,
    private val onDiagnostic: (ProductVoiceDiagnostic, String?, String?) -> Unit = { _, _, _ -> },
) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = ProductVoiceSession()
    private var launched = false
    private var finished = false
    private var state = ProductVoiceState()
    private var terminalStage = ProductVoiceStage.STOPPED

    fun start() {
        mainThread()
        if (launched) return
        launched = true
        session.start()
        scope.launch { run() }
    }

    fun stop(reason: String) {
        mainThread()
        session.stop(reason)
        if (!finished) publish(ProductVoiceState(ProductVoiceStage.STOPPING, session.stopReason.get()))
    }

    private suspend fun run() {
        var watcher: Job? = null
        var owner: PreparedKoreanSpeechRecognizer? = null
        var recognitionStartedAt: Long? = null
        var acceptedDiagnostic: ProductVoiceDiagnostic? = null
        var acceptedText: String? = null
        var loadMs: Long? = null
        try {
            if (checkAllowed() != null) return
            publish(ProductVoiceState(ProductVoiceStage.PREPARING))
            watcher = scope.launch {
                while (session.stopReason.get() == null) {
                    if (checkAllowed() != null) break
                    recognitionStartedAt?.let { startedAt ->
                        val at = now()
                        if (at < startedAt || at - startedAt >= FrunkVoicePolicy.COMMAND_MS) {
                            session.stop("FRUNK_VOICE_EXPIRED")
                        }
                    }
                    delay(READINESS_POLL_MS)
                }
                if (session.stopReason.get() != null && !finished) {
                    try {
                        publish(ProductVoiceState(ProductVoiceStage.STOPPING, session.stopReason.get()))
                    } catch (_: Exception) {
                        terminalStage = ProductVoiceStage.FAILED
                        session.stop("PRODUCT_VOICE_STATE_FAILED")
                    } catch (_: LinkageError) {
                        terminalStage = ProductVoiceStage.FAILED
                        session.stop("PRODUCT_VOICE_STATE_FAILED")
                    }
                }
            }
            if (checkAllowed() != null || !session.beginPreparation()) return
            val preparation = withContext(Dispatchers.Default) {
                KoreanSpeechRecognizer.prepare(app.assets) { session.stopReason.get() != null }
            }
            owner = preparation.owner
            loadMs = preparation.loadMs
            session.preparationFinished(owner != null, preparation.recognizerCleanupConfirmed, preparation.failure)
            checkAllowed()
            if (preparation.failure != null && preparation.failure != KoreanSpeechFailure.CANCELED) {
                terminalStage = ProductVoiceStage.FAILED
            }
            if (session.stopReason.get() != null || owner == null) return
            diagnostic(ProductVoiceDiagnostic("PREPARED", loadMs = loadMs,
                microphoneReleased = session.microphoneReleased,
                streamCleanupConfirmed = session.streamCleanupConfirmed,
                recognizerCleanupConfirmed = session.recognizerCleanupConfirmed))
            val prepared = requireNotNull(owner)
            while (session.stopReason.get() == null) {
                publish(ProductVoiceState(ProductVoiceStage.PREPARING))
                if (checkAllowed() != null || !session.beginCapture()) break
                val utterance = ProductUtteranceBuffer()
                val captureStarted = now()
                var captureMs: Long? = null
                var result: KoreanSpeechStreamResult? = null
                var decision: ProductVoiceSession.Decision? = null
                try {
                    val captured = ProductMicCapture.capture(app, audioManager, utterance, now, session.stopReason) {
                        withContext(Dispatchers.Main.immediate) {
                            if (checkAllowed() == null) publish(ProductVoiceState(ProductVoiceStage.LISTENING))
                        }
                    }
                    captureMs = elapsed(captureStarted)
                    session.captureFinished(captured.microphoneReleased, captured.cleanupConfirmed)
                    if (captured.failure != null) {
                        if (session.stopReason.get() == null) terminalStage = ProductVoiceStage.FAILED
                        session.stop(captured.failure)
                    }
                    if (checkAllowed() != null) break
                    if (utterance.status != ProductUtteranceBuffer.Status.COMPLETE) {
                        terminalStage = ProductVoiceStage.FAILED
                        session.stop("PRODUCT_VOICE_UTTERANCE_INCOMPLETE")
                        break
                    }
                    val pcm = requireNotNull(utterance.pcm)
                    val startedAt = requireNotNull(utterance.startedAt)
                    val recognitionAt = now()
                    if (recognitionAt < startedAt || recognitionAt - startedAt >= FrunkVoicePolicy.COMMAND_MS) {
                        session.stop("FRUNK_VOICE_EXPIRED")
                        break
                    }
                    publish(ProductVoiceState(ProductVoiceStage.RECOGNIZING))
                    if (checkAllowed() != null || !session.beginRecognition()) break
                    recognitionStartedAt = startedAt
                    val decoded = withContext(Dispatchers.Default) {
                        prepared.decode(pcm, utterance.bytes) { session.stopReason.get() != null }
                    }
                    result = decoded
                    recognitionStartedAt = null
                    checkAllowed()
                    if (!decoded.streamCleanupConfirmed ||
                        (decoded.failure != null && decoded.failure != KoreanSpeechFailure.CANCELED)) {
                        terminalStage = ProductVoiceStage.FAILED
                    }
                    decision = session.acceptRecognition(decoded, utterance.bytes, startedAt, now())
                    if (decision != ProductVoiceSession.Decision.WAIT) break
                } catch (failure: Exception) {
                    terminalStage = ProductVoiceStage.FAILED
                    session.stop("PRODUCT_VOICE_FAILED")
                    throw failure
                } catch (failure: LinkageError) {
                    terminalStage = ProductVoiceStage.FAILED
                    session.stop("PRODUCT_VOICE_FAILED")
                    throw failure
                } finally {
                    recognitionStartedAt = null
                    val decoded = result
                    val stillAllowed = checkAllowed() == null
                    val outcome = when {
                        decision == ProductVoiceSession.Decision.ACCEPTED -> "ACCEPTED"
                        decision == ProductVoiceSession.Decision.WAIT && stillAllowed -> if (
                            SpeechCommandParser.normalize(requireNotNull(decoded?.text)).isEmpty()
                        ) "EMPTY" else "GRAMMAR_NO_MATCH"
                        terminalStage == ProductVoiceStage.FAILED -> "FAILURE"
                        else -> "SEALED"
                    }
                    // clear 전에 수치만 snapshot하고 PCM을 먼저 지운 뒤 Main callback에 넘긴다.
                    val observation = ProductVoiceDiagnostic(outcome,
                        utteranceBytes = utterance.bytes, voicedBytes = utterance.voicedBytes,
                        suppressedOnsets = utterance.suppressedOnsets,
                        silenceBytesAtEnd = utterance.silenceBytesAtEnd,
                        captureMs = captureMs ?: elapsed(captureStarted), decodeMs = decoded?.decodeMs,
                        cleanupMs = decoded?.cleanupMs, modelReused = decoded?.modelReused,
                        microphoneReleased = session.microphoneReleased,
                        streamCleanupConfirmed = session.streamCleanupConfirmed,
                        recognizerCleanupConfirmed = session.recognizerCleanupConfirmed)
                    utterance.clear()
                    if (decision == ProductVoiceSession.Decision.ACCEPTED) {
                        acceptedDiagnostic = observation
                        acceptedText = decoded?.text
                    } else {
                        val text = decoded?.text.takeIf {
                            decision == ProductVoiceSession.Decision.WAIT && stillAllowed
                        }
                        val reason = when (outcome) {
                            "EMPTY" -> "FRUNK_VOICE_EMPTY"
                            "GRAMMAR_NO_MATCH" -> "FRUNK_VOICE_GRAMMAR_NO_MATCH"
                            else -> session.stopReason.get()
                        }
                        diagnostic(observation, text, reason)
                    }
                }
            }
        } catch (_: Exception) {
            terminalStage = ProductVoiceStage.FAILED
            session.stop("PRODUCT_VOICE_FAILED")
        } catch (_: LinkageError) {
            terminalStage = ProductVoiceStage.FAILED
            session.stop("PRODUCT_VOICE_FAILED")
        } finally {
            // watchdog은 close 중에도 봉인만 한다. native owner를 동시에 release하지 않는다.
            val cleanup = owner?.let { prepared ->
                withContext(NonCancellable + Dispatchers.Default) { prepared.close() }
            }
            if (cleanup != null) session.recognizerClosed(cleanup)
            watcher?.cancelAndJoin()
            checkAllowed()
            var completion = requireNotNull(session.complete(now()))
            finished = true
            val stage = when {
                !completion.cleanupConfirmed -> ProductVoiceStage.FAILED
                completion.gate != null -> ProductVoiceStage.COMMAND_READY
                else -> terminalStage
            }
            try {
                publish(ProductVoiceState(stage, completion.reason))
            } catch (_: Exception) {
                session.stop("PRODUCT_VOICE_STATE_FAILED")
                terminalStage = ProductVoiceStage.FAILED
            } catch (_: LinkageError) {
                session.stop("PRODUCT_VOICE_STATE_FAILED")
                terminalStage = ProductVoiceStage.FAILED
            }
            checkAllowed()
            if (session.stopReason.get() != null && completion.gate != null) {
                completion.gate?.cancel(requireNotNull(session.stopReason.get()))
                completion = completion.copy(gate = null, reason = session.stopReason.get())
            }
            val closedOutcome = when {
                !completion.cleanupConfirmed || terminalStage == ProductVoiceStage.FAILED -> "FAILURE"
                completion.gate != null -> "CLOSED"
                else -> "SEALED"
            }
            val closeReported = diagnostic(ProductVoiceDiagnostic(closedOutcome, loadMs = loadMs,
                cleanupMs = cleanup?.cleanupMs, microphoneReleased = session.microphoneReleased,
                streamCleanupConfirmed = session.streamCleanupConfirmed,
                recognizerCleanupConfirmed = session.recognizerCleanupConfirmed))
            val accepted = acceptedDiagnostic
            if (accepted != null) {
                if (completion.gate?.validTime(now()) == false) session.stop("FRUNK_VOICE_EXPIRED")
                val canAccept = closeReported && completion.gate != null && checkAllowed() == null &&
                    completion.gate?.validTime(now()) == true
                val outcome = when {
                    canAccept -> "ACCEPTED"
                    !completion.cleanupConfirmed || terminalStage == ProductVoiceStage.FAILED -> "FAILURE"
                    else -> "SEALED"
                }
                val text = acceptedText.takeIf { canAccept }
                acceptedText = null
                diagnostic(accepted.copy(outcome = outcome,
                    cleanupMs = (accepted.cleanupMs ?: 0) + (cleanup?.cleanupMs ?: 0),
                    microphoneReleased = session.microphoneReleased,
                    streamCleanupConfirmed = session.streamCleanupConfirmed,
                    recognizerCleanupConfirmed = session.recognizerCleanupConfirmed), text,
                    if (canAccept) null else session.stopReason.get())
            }
            if (session.stopReason.get() != null ||
                (completion.gate != null && completion.gate?.phase != FrunkVoicePolicy.Phase.ACCEPTED) ||
                completion.gate?.validTime(now()) == false) {
                completion.gate?.cancel(session.stopReason.get() ?: "FRUNK_VOICE_EXPIRED")
                completion = completion.copy(gate = null, reason = if (completion.cleanupConfirmed)
                    session.stopReason.get() ?: "FRUNK_VOICE_EXPIRED" else ProductVoiceSession.CLEANUP_FAILED)
            }
            onFinished(completion)
        }
    }

    private fun diagnostic(value: ProductVoiceDiagnostic, text: String? = null,
        reason: String? = session.stopReason.get()): Boolean = try {
        mainThread()
        onDiagnostic(value, text, reason)
        true
    } catch (_: Exception) {
        terminalStage = ProductVoiceStage.FAILED
        session.stop("PRODUCT_VOICE_DIAGNOSTIC_FAILED")
        false
    } catch (_: LinkageError) {
        terminalStage = ProductVoiceStage.FAILED
        session.stop("PRODUCT_VOICE_DIAGNOSTIC_FAILED")
        false
    }

    private fun elapsed(started: Long): Long? = (now() - started).takeIf { it >= 0 }

    private fun checkAllowed(): String? {
        val reason = session.stopReason.get() ?: try {
            allowed() ?: ProductMicCapture.safetyReason(app, audioManager)
        } catch (_: Exception) {
            "PRODUCT_VOICE_READINESS_UNKNOWN"
        }
        if (reason != null) session.stop(reason)
        return reason
    }

    private fun publish(value: ProductVoiceState) {
        if (state == value) return
        state = value
        onState(value)
    }

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private companion object {
        const val READINESS_POLL_MS = 100L
    }
}
