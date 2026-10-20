package com.heytesla.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 실차 전용 DIRECT_MIC. 파일·PCM·dry-run gateway·TTS 입력 API를 갖지 않는다. */
internal class FrunkVoiceProbe(context: Context, private val runtime: DiagnosticRuntime) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Trial? = null

    private class Trial(val session: SessionPolicy.Session, startedAtElapsedMs: Long) {
        val gate = FrunkVoicePolicy(session.deadline, startedAtElapsedMs)
        var canceled = false
        var inputReleased = true
        var round: Round? = null
    }

    private class Round(val deadline: Long) {
        val result = CompletableDeferred<Boolean>()
        var recognizer: SpeechRecognizer? = null
    }

    fun start(consent: Boolean, koKrInstalled: Boolean) {
        mainThread()
        if (current != null || runtime.state.value.frunkVoice.active || runtime.teslaKeyReserved()) return
        if (runtime.diagnosticCleanupUncertain) { runtime.event("FRUNK_VOICE_ADMISSION_BLOCKED"); return }
        runtime.refresh()
        val blocked = when {
            !consent -> "FRUNK_VOICE_CONSENT_REQUIRED"
            !koKrInstalled -> "FRUNK_VOICE_MODEL_REQUIRED"
            runtime.frunkVoiceBlockedReason() != null -> "FRUNK_VOICE_ADMISSION_BLOCKED"
            !onDeviceAvailable() -> "FRUNK_VOICE_MODEL_REQUIRED"
            else -> null
        }
        if (blocked != null) {
            runtime.update { it.copy(frunkVoice = FrunkVoiceState()) }
            stopped(blocked)
            return
        }
        val session = runtime.acquireSpeechDiagnostic(::cancel)
            ?: run { stopped("FRUNK_VOICE_ADMISSION_BLOCKED"); return }
        val trial = Trial(session, runtime.now())
        if (!runtime.bindFrunkVoice(session, trial.gate)) {
            runtime.releaseSpeechDiagnostic(session.id)
            stopped("FRUNK_VOICE_ADMISSION_BLOCKED")
            return
        }
        current = trial
        runtime.update { it.copy(frunkVoice = FrunkVoiceState(stage = FrunkVoiceStage.COMMAND, active = true)) }
        runtime.event("FRUNK_VOICE_STARTED")
        scope.launch { run(trial) }
    }

    fun cancel() {
        mainThread()
        if (runtime.state.value.frunkVoice.stage == FrunkVoiceStage.CLEANUP_FAILED) {
            runtime.cancelFrunkVoice()
            return
        }
        val trial = current
        if (trial != null) {
            trial.canceled = true
            trial.gate.cancel()
            trial.round?.result?.complete(false)
            runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(
                stage = FrunkVoiceStage.CLEANING, cancelRequested = true)) }
        }
        if (trial != null || runtime.state.value.frunkVoice.active) runtime.event("FRUNK_VOICE_CANCELED")
        // 승인 ticket부터 폐기한다. 실제 해제는 아래 소유자의 finally만 수행한다.
        runtime.cancelFrunkVoice()
    }

    private suspend fun run(trial: Trial) {
        var handedOff = false
        try {
            if (listen(trial) && !trial.canceled && trial.inputReleased &&
                runtime.frunkVoiceReadinessReason(trial.session.id) == null && trial.gate.validTime(runtime.now())
            ) {
                handedOff = runtime.finishFrunkVoiceAudio(trial.session.id, inputReleased = true)
            }
        } catch (_: Exception) {
            trial.gate.cancel("FRUNK_VOICE_RECOGNITION_FAILED")
        } finally {
            if (!trial.inputReleased) {
                trial.gate.cancel("FRUNK_VOICE_CLEANUP_FAILED")
                runtime.cancelFrunkVoice()
                runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(stage = FrunkVoiceStage.CLEANUP_FAILED,
                    active = false, inputReleased = false, reason = "FRUNK_VOICE_CLEANUP_FAILED")) }
                // current·speech lease는 유지한다. 화면 재생성도 이 runtime 차단을 해제하지 못한다.
                runtime.event("FRUNK_VOICE_CLEANUP_FAILED")
            } else {
                if (!handedOff) {
                    runtime.cancelFrunkVoice()
                    runtime.releaseSpeechDiagnostic(trial.session.id)
                    stopped(trial.gate.reason ?: "FRUNK_VOICE_HANDOFF_BLOCKED")
                }
                current = null
            }
        }
    }

    private suspend fun listen(trial: Trial): Boolean {
        if (trial.canceled || !trial.gate.validTime(runtime.now())) return false
        if (runtime.frunkVoiceReadinessReason(trial.session.id) != null) {
            trial.gate.cancel("FRUNK_VOICE_READINESS_LOST")
            return false
        }
        val deadline = minOf(trial.session.deadline, runtime.now() + FrunkVoicePolicy.COMMAND_MS)
        val round = Round(deadline)
        trial.round = round
        try {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            round.recognizer = recognizer
            trial.inputReleased = false
            runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(
                stage = FrunkVoiceStage.COMMAND,
                inputReleased = false)) }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) {
                    if (!accepts(trial, round)) return
                    trial.gate.cancel("FRUNK_VOICE_RECOGNITION_FAILED")
                    round.result.complete(false)
                }
                override fun onResults(results: Bundle?) {
                    if (!accepts(trial, round)) return
                    if (runtime.frunkVoiceReadinessReason(trial.session.id) != null) {
                        trial.gate.cancel("FRUNK_VOICE_READINESS_LOST")
                        round.result.complete(false)
                        return
                    }
                    val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val confidence = scores?.singleOrNull()?.takeIf { it.isFinite() && it in 0f..1f }
                    runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(commandConfidence = confidence)) }
                    val receivedAt = runtime.now()
                    if (!trial.gate.validTime(receivedAt)) {
                        round.result.complete(false)
                        return
                    }
                    // 정책은 정제 전 최종 후보·원 신뢰도로만 판정한다. 로그 정제 결과는 실행에 쓰지 않는다.
                    val accepted = trial.gate.command(receivedAt, SpeechTrialMode.DIRECT_MIC,
                        final = true, candidates = candidates, confidence = scores)
                    runtime.recordFrunkVoiceRecognition(trial.session.id,
                        FrunkVoiceRecognition.capture(candidates, scores, accepted, trial.gate.reason))
                    if (accepted) runtime.event("FRUNK_VOICE_COMMAND_ACCEPTED")
                    round.result.complete(accepted)
                }
                // 부분·조각을 이어 붙여 전체 최종 신뢰도를 합성하지 않는다.
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onSegmentResults(segmentResults: Bundle) {
                    if (accepts(trial, round)) {
                        trial.gate.cancel("FRUNK_VOICE_FINAL_REQUIRED")
                        round.result.complete(false)
                    }
                }
                override fun onEndOfSegmentedSession() {
                    if (accepts(trial, round)) {
                        trial.gate.cancel("FRUNK_VOICE_FINAL_REQUIRED")
                        round.result.complete(false)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
            while (!round.result.isCompleted) {
                if (trial.canceled) {
                    trial.gate.cancel()
                    round.result.complete(false)
                } else if (runtime.now() >= deadline || !trial.gate.validTime(runtime.now())) {
                    trial.gate.cancel("FRUNK_VOICE_EXPIRED")
                    round.result.complete(false)
                } else if (runtime.frunkVoiceReadinessReason(trial.session.id) != null) {
                    trial.gate.cancel("FRUNK_VOICE_READINESS_LOST")
                    round.result.complete(false)
                }
                if (!round.result.isCompleted) delay(100)
            }
            return round.result.await()
        } catch (_: Exception) {
            trial.gate.cancel("FRUNK_VOICE_RECOGNITION_FAILED")
            return false
        } finally {
            // 콜백을 먼저 봉인하고, 모든 정리 호출이 정상 반환해야 core에 입장한다.
            trial.round = null
            runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(stage = FrunkVoiceStage.CLEANING)) }
            var released = true
            try { round.recognizer?.cancel() } catch (_: Exception) { released = false }
            try { round.recognizer?.destroy() } catch (_: Exception) { released = false }
            round.recognizer = null
            trial.inputReleased = released
            runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(inputReleased = released)) }
        }
    }

    private fun accepts(trial: Trial, round: Round): Boolean = current === trial && !trial.canceled &&
        trial.round === round && !round.result.isCompleted && runtime.now() < round.deadline

    private fun stopped(code: String) {
        runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(stage = FrunkVoiceStage.STOPPED,
            active = false, reason = code)) }
        runtime.event(code)
    }

    private fun onDeviceAvailable() = try {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
    } catch (_: Exception) { false }

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())
}
