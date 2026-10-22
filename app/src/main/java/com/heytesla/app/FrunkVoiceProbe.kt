package com.heytesla.app

import android.content.Context
import android.os.Looper
import android.speech.SpeechRecognizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 실차 전용 DIRECT_MIC. 파일·PCM·dry-run gateway·TTS 입력 API를 갖지 않는다.
 * 앱이 녹음한 마이크 PCM을 Android 온디바이스 SpeechRecognizer(ko-KR, segmented EXTRA_AUDIO_SOURCE)로 인식한다.
 * 실차 시험도 녹음·최종 원문을 백업 제외 전용 저장소에 기본 보관한다. 인식 원문 후보는 화면에 RAM으로만 표시한다.
 * 판정은 [FrunkVoicePolicy]만 한다.
 */
internal class FrunkVoiceProbe(context: Context, private val runtime: DiagnosticRuntime) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Trial? = null

    private class Trial(val session: SessionPolicy.Session, val startedAtElapsedMs: Long) {
        val gate = FrunkVoicePolicy(session.deadline, startedAtElapsedMs)
        /** 정책 gate와 정확히 같은 COMMAND 기한. 캡처 자동 종료도 이 값을 쓴다. */
        val gateDeadline = minOf(session.deadline, startedAtElapsedMs + FrunkVoicePolicy.COMMAND_MS)
        val canceled = AtomicBoolean(false)
        /** 마이크 입력이 실제로 해제됐는지. 인식기 정리 결과와 분리해 보고한다. */
        var inputReleased = true
        /** 인식기·pipe 정리 실패. 예약은 재시작 전까지 유지한다. */
        var cleanupFailed = false
        var round: Round? = null
    }

    private class Round(val deadline: Long) {
        /** 캡처 루프가 IO에서 읽는 단일 종료 사유. 정책 상태는 Main만 바꾼다. */
        val stopped = AtomicReference<String?>(null)
    }

    /**
     * @param koKrInstalled ko-KR 온디바이스 인식 지원 조회가 완료됐고 설치로 보고됐는지.
     */
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
            // 캡처·인식 대기는 이 신호만 보고 스스로 멈춘다.
            trial.canceled.set(true)
            trial.gate.cancel()
            trial.round?.stopped?.compareAndSet(null, "USER_CANCELED")
            runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(
                stage = FrunkVoiceStage.CLEANING, cancelRequested = true)) }
        }
        if (trial != null || runtime.state.value.frunkVoice.active) runtime.event("FRUNK_VOICE_CANCELED")
        // 승인 ticket부터 폐기한다. 마이크·인식기 해제는 listen/run 소유자의 finally만 수행한다.
        runtime.cancelFrunkVoice()
    }

    private suspend fun run(trial: Trial) {
        var handedOff = false
        try {
            // 미승인 회차도 입력 해제·예약 반환 경로는 그대로 탄다.
            if (listen(trial) && !trial.canceled.get() && trial.inputReleased &&
                runtime.frunkVoiceReadinessReason(trial.session.id) == null && trial.gate.validTime(runtime.now())
            ) {
                handedOff = runtime.finishFrunkVoiceAudio(trial.session.id, inputReleased = true)
            }
        } catch (_: Exception) {
            trial.gate.cancel("FRUNK_VOICE_RECOGNITION_FAILED")
        } finally {
            if (!trial.inputReleased || trial.cleanupFailed) {
                trial.gate.cancel("FRUNK_VOICE_CLEANUP_FAILED")
                runtime.cancelFrunkVoice()
                runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(stage = FrunkVoiceStage.CLEANUP_FAILED,
                    active = false, inputReleased = trial.inputReleased, reason = "FRUNK_VOICE_CLEANUP_FAILED")) }
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
        if (trial.canceled.get() || !trial.gate.validTime(runtime.now())) return false
        if (runtime.frunkVoiceReadinessReason(trial.session.id) != null) {
            trial.gate.cancel("FRUNK_VOICE_READINESS_LOST")
            return false
        }
        val deadline = trial.gateDeadline
        val round = Round(deadline)
        trial.round = round
        trial.inputReleased = false
        runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(
            stage = FrunkVoiceStage.COMMAND, inputReleased = false)) }
        val buffer = ByteArray(SpeechMicCapture.MAX_BYTES)
        // 기한·취소·준비도 상실은 Main의 watchdog만 판정한다. 캡처 IO는 완성된 사유만 읽는다.
        // 기한 만료는 차량 전송만 막고 캡처는 자체 deadline까지 계속한다. 남은 PCM은 마스킹 로그용으로 인식한다.
        val watcher = scope.launch {
            while (trial.round === round) {
                if (trial.canceled.get()) {
                    round.stopped.compareAndSet(null, "USER_CANCELED")
                } else if (runtime.now() >= deadline) {
                    trial.gate.cancel("FRUNK_VOICE_EXPIRED")
                    break
                } else if (runtime.frunkVoiceReadinessReason(trial.session.id) != null) {
                    trial.gate.cancel("FRUNK_VOICE_READINESS_LOST")
                    round.stopped.compareAndSet(null, "FRUNK_VOICE_READINESS_LOST")
                }
                if (round.stopped.get() != null) break
                delay(100)
            }
        }
        try {
            val captured = withContext(Dispatchers.IO) {
                SpeechMicCapture.capture(
                    context = app,
                    pcm = buffer,
                    deadlineElapsed = deadline,
                    canceled = trial.canceled,
                    finish = null,
                    stopReason = { round.stopped.get() },
                    silenceStopMs = SpeechMicCapture.ENDPOINT_SILENCE_MS,
                ) { }
            }
            trial.inputReleased = captured.cleanupFailures.isEmpty() && !captured.releaseUncertain
            trial.cleanupFailed = !trial.inputReleased
            runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(inputReleased = trial.inputReleased)) }
            if (captured.failure != null) {
                if (captured.failure != "USER_CANCELED" &&
                    trial.gate.phase == FrunkVoicePolicy.Phase.COMMAND
                ) trial.gate.cancel("FRUNK_VOICE_RECOGNITION_FAILED")
                return false
            }
            if (trial.canceled.get()) return false
            // 캡처가 끝난 시점에 COMMAND 기한이 지났다면 gate만 만료로 닫는다. 승인은 막고 인식은 계속한다.
            if (trial.gate.phase == FrunkVoicePolicy.Phase.COMMAND) trial.gate.validTime(runtime.now())
            val expired = trial.gate.phase == FrunkVoicePolicy.Phase.STOPPED &&
                trial.gate.reason == "FRUNK_VOICE_EXPIRED"
            if (trial.gate.phase != FrunkVoicePolicy.Phase.COMMAND && !expired) return false
            if (captured.bytes == 0) {
                if (trial.gate.phase == FrunkVoicePolicy.Phase.COMMAND) {
                    trial.gate.cancel("FRUNK_VOICE_RECOGNITION_FAILED")
                }
                return false
            }
            val recordingId = try {
                withContext(Dispatchers.IO) { SpeechRecordingStore(app).save(buffer, captured.bytes).id }
                    .also { id -> runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(recordingId = id)) } }
            } catch (_: Exception) {
                runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(recordingFailure = true)) }
                null
            }
            // 입력 해제가 확인된 뒤 같은 PCM을 인식한다. 기한이 지난 비취소 인식도 마스킹 로그용으로 끝까지 기다린다.
            val recognized = AndroidPcmRecognizer(app).recognize(buffer, captured.bytes, RECOGNITION_TIMEOUT_MS) {
                trial.canceled.get()
            }
            if (recognized.cleanupFailures.isNotEmpty() || recognized.releaseUncertain) trial.cleanupFailed = true
            val candidates = AndroidPcmRecognizer.texts(recognized.candidates)
            val scores = AndroidPcmRecognizer.scores(recognized.candidates)
            if (recordingId != null) {
                try {
                    withContext(Dispatchers.IO) {
                        SpeechRecordingStore(app).saveResult(recordingId, candidates.firstOrNull(),
                            recognized.failure, runtime.now() - trial.startedAtElapsedMs)
                    }
                } catch (_: Exception) {
                    runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(recordingFailure = true)) }
                }
            }
            // 일부 segment가 왔어도 인식기 실패는 명령 없음으로 본다.
            if (recognized.failure != null) {
                if (recognized.failure != "USER_CANCELED" &&
                    trial.gate.phase == FrunkVoicePolicy.Phase.COMMAND
                ) trial.gate.cancel("FRUNK_VOICE_RECOGNITION_FAILED")
                if (!trial.canceled.get()) showTranscript(trial, recognized.candidates, accepted = false)
                return false
            }
            val receivedAt = runtime.now()
            if (!trial.canceled.get() && trial.gate.phase != FrunkVoicePolicy.Phase.STOPPED &&
                runtime.frunkVoiceReadinessReason(trial.session.id) != null
            ) trial.gate.cancel("FRUNK_VOICE_READINESS_LOST")
            val accepted = if (!trial.canceled.get() && trial.gate.validTime(receivedAt)) {
                trial.gate.command(receivedAt, SpeechTrialMode.DIRECT_MIC,
                    final = true, candidates = candidates, confidence = scores)
            } else false
            runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(
                commandConfidence = trial.gate.acceptedConfidence)) }
            if (!trial.canceled.get()) showTranscript(trial, recognized.candidates, accepted)
            // 정책은 정제 전 n-best·원 점수로만 판정한다. 로그 정제 결과는 실행에 쓰지 않는다.
            runtime.recordFrunkVoiceRecognition(trial.session.id,
                FrunkVoiceRecognition.capture(candidates, scores, accepted, trial.gate.reason))
            if (accepted && trial.gate.acceptedConfidence == null) runtime.event("FRUNK_VOICE_CONFIDENCE_UNAVAILABLE")
            if (accepted) runtime.event("FRUNK_VOICE_COMMAND_ACCEPTED")
            return accepted
        } catch (_: Exception) {
            trial.gate.cancel("FRUNK_VOICE_RECOGNITION_FAILED")
            return false
        } finally {
            watcher.cancel()
            trial.round = null
            withContext(Dispatchers.IO) { buffer.fill(0) }
            runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(stage = FrunkVoiceStage.CLEANING,
                inputReleased = trial.inputReleased)) }
        }
    }

    /** 사용자 중단이 아닌 회차의 원문 후보를 화면 RAM 상태에만 올린다. 로그·파일에는 쓰지 않는다. */
    private fun showTranscript(trial: Trial, candidates: List<SttHypothesis>, accepted: Boolean) {
        runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(transcript = SttTranscript.of(candidates),
            transcriptAccepted = accepted, transcriptReason = if (accepted) null else trial.gate.reason)) }
    }

    private fun stopped(code: String) {
        runtime.update { it.copy(frunkVoice = it.frunkVoice.copy(stage = FrunkVoiceStage.STOPPED,
            active = false, reason = code)) }
        runtime.event(code)
    }

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun onDeviceAvailable() = try {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
    } catch (_: Exception) {
        false
    }

    private companion object {
        /** 인식기 무응답 상한. 차량 승인은 별도로 COMMAND 기한이 막는다. */
        const val RECOGNITION_TIMEOUT_MS = 15_000L
    }
}
