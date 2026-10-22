package com.heytesla.app

import android.Manifest
import android.content.Context
import android.os.Looper
import android.speech.SpeechRecognizer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SpeechTrialMode(val label: String) {
    DIRECT_MIC("온디바이스 직접 마이크"), BUFFERED_PCM("RAM PCM 인계"),
    TTS_PCM("합성 한국어 파일 PCM"), SILENT_PCM("무음 PCM 대조")
}

internal enum class SpeechTrialStatus(val label: String) {
    IDLE("대기"), STARTING("준비 중"), CAPTURING("캡처 중"), RECOGNIZING("인식 중"),
    VALIDATING("로컬 명령 판정 중"), RESPONSE_INITIALIZING("오프라인 음성 안내 준비 중"), RESPONDING("음성 안내 중"),
    COMPLETE("진단 종료"), FAILED("실패"), CANCELED("취소됨"), TIMED_OUT("시간 초과"), CLEANING("자원 정리 중")
}

internal data class SpeechTrialState(
    val status: SpeechTrialStatus = SpeechTrialStatus.IDLE,
    val mode: SpeechTrialMode? = null,
    val active: Boolean = false,
    val reason: String? = null,
    val samples: Long = 0,
    val pcmBytesWritten: Int = 0,
    val finalReceived: Boolean = false,
    val phraseMatched: Boolean? = null,
    val confidence: Float? = null,
    val elapsedMs: Long? = null,
    val recordingId: String? = null,
    val recordingFailure: Boolean = false,
    /** 최종 결과가 있을 때만 채워지는 진단 판정. */
    val decision: SpeechCommandDecision? = null,
    val commandResult: DiagnosticCommandResult = DiagnosticCommandResult.NOT_ATTEMPTED,
    val responseStatus: SpeechResponseStatus = SpeechResponseStatus.NOT_STARTED,
    val responseReason: SpeechResponseReason? = null,
    val inputReleased: Boolean = false,
    val outputReleased: Boolean = true,
    val audioFocusReleased: Boolean = true,
    val offlineVoiceSelected: Boolean = false,
    val responseCleanupFailed: Boolean = false,
    /** 사용자 중단이 아닌 회차의 인식 원문 후보. 화면 표시 전용 RAM 상태이며 요약·이벤트·파일에 넣지 않는다. */
    val transcript: SttTranscript? = null,
    /** sherpa-onnx 그림자 인식. transcript와 같은 RAM 전용 규칙이며 명령 판정에 쓰지 않는다. */
    val shadow: ShadowTranscript? = null,
)

/**
 * 진단 네 모드 모두 RAM에 모은 16 kHz mono PCM16을 Android 온디바이스 SpeechRecognizer(ko-KR)에
 * segmented pipe로 넘긴다. 직접 마이크도 실시간 partial이 아니라 캡처 종료·입력 해제 뒤에만 인식한다.
 * 마이크 입력 시험은 별도 체크 없이 녹음·1순위 결과를 전용 파일로 보관하고, 합성·무음 파일 입력은
 * 저장하지 않는다. 엔진 점수 0.0·-1 같은 무효값은 null로 두고 만들지 않는다. 원문 n-best 앞쪽 3개는
 * 다음 시험 시작 전까지 화면 RAM 상태로만 둔다. 같은 PCM을 sherpa-onnx 한국어 모델로 한 번 더 인식한
 * 그림자 결과도 같은 규칙으로 화면에만 두며, 명령 판정·dry-run·요약에는 Android 결과만 쓴다.
 */
internal class SpeechRecognitionProbe(
    context: Context,
    private val state: MutableStateFlow<SpeechTrialState>,
    private val runtime: DiagnosticRuntime,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Trial? = null
    private var restartRequired = false
    private val gateway = DryRunVehicleGateway()

    private class Trial(val mode: SpeechTrialMode, val started: Long, val session: SessionPolicy.Session) {
        /** 수동 마이크 시험만 PCM·최종 원문을 저장한다. 합성·무음 파일 입력은 제외한다. */
        val savesRecording = mode == SpeechTrialMode.DIRECT_MIC || mode == SpeechTrialMode.BUFFERED_PCM
        val command = DiagnosticCommandPolicy(session.deadline)
        var response: SpeechResponse? = null
        var finished = false
        val canceled = AtomicBoolean(false)
        val finishCapture = AtomicBoolean(false)
        val cleanupFailures = ConcurrentLinkedQueue<String>()
        val releaseUncertain = AtomicBoolean(false)
        var pcm: ByteArray? = null
        var size = 0
        var acceptedBytes = 0
        var recordingId: String? = null
        var recordingFailure = false
        /** 마이크·파일 입력이 실제로 해제됐는지. 인식기·TTS 정리 결과와 분리한다. */
        var inputReleased = false
    }

    private data class Outcome(
        val status: SpeechTrialStatus,
        val reason: String,
        val finalReceived: Boolean = false,
        val matched: Boolean? = null,
        val confidence: Float? = null,
        val decision: SpeechCommandDecision? = null,
        val transcript: SttTranscript? = null,
        val shadow: ShadowTranscript? = null,
    )

    /**
     * @param koKrInstalled ko-KR 온디바이스 인식 지원 조회가 완료됐고 설치로 보고됐는지.
     */
    fun start(mode: SpeechTrialMode, koKrInstalled: Boolean) {
        mainThread()
        if (restartRequired) { runtime.event("SPEECH_TRIAL_RESTART_REQUIRED"); return }
        if (current != null) { runtime.event("SPEECH_TRIAL_BUSY"); return }
        var session: SessionPolicy.Session? = null
        val rejected = when {
            !koKrInstalled -> "KO_KR_NOT_INSTALLED"
            !runtime.granted(Manifest.permission.RECORD_AUDIO) -> "MIC_PERMISSION_REQUIRED"
            !onDeviceAvailable() -> "ON_DEVICE_UNAVAILABLE"
            else -> {
                session = runtime.acquireSpeechDiagnostic(::cancel)
                if (session == null) "LEASE_UNAVAILABLE" else null
            }
        }
        if (rejected != null) {
            state.value = SpeechTrialState(status = SpeechTrialStatus.FAILED, mode = mode, reason = rejected)
            runtime.event("SPEECH_TRIAL_$rejected", rejectedSummary(mode, rejected))
            return
        }
        val trial = Trial(mode, runtime.now(), checkNotNull(session))
        current = trial
        state.value = SpeechTrialState(status = SpeechTrialStatus.STARTING, mode = mode, active = true)
        runtime.event("SPEECH_TRIAL_START_${mode.name}")
        scope.launch { runTrial(trial) }
    }

    /** 직접 마이크 캡처만 수동 종료한다. 파일 PCM 모드는 이미 캡처가 끝났으므로 무시된다. */
    fun finishCapture() {
        mainThread()
        val trial = current ?: return
        if (!accepts(trial)) return
        trial.finishCapture.set(true)
    }

    fun cancel() {
        mainThread()
        val trial = current ?: return
        if (trial.finished) return
        // 콜백·진행 보고를 먼저 봉인한다. 인식기·버퍼·lease 해제는 worker의 finally만 한다.
        trial.canceled.set(true)
        trial.command.cancel()
        trial.finishCapture.set(true)
        state.value = state.value.copy(status = SpeechTrialStatus.CLEANING)
        trial.response?.cancel()
    }

    private suspend fun runTrial(trial: Trial) {
        var outcome = failure("INTERNAL_FAILURE")
        var response = SpeechResponseOutcome()
        try {
            val captureFailure: String? = when (trial.mode) {
                SpeechTrialMode.DIRECT_MIC, SpeechTrialMode.BUFFERED_PCM -> captureMicrophone(trial)
                SpeechTrialMode.TTS_PCM, SpeechTrialMode.SILENT_PCM ->
                    withContext(Dispatchers.IO) { loadFixture(trial) }
            }
            if (captureFailure == null && trial.size > 0 && trial.savesRecording && !trial.canceled.get()) {
                try {
                    trial.recordingId = withContext(Dispatchers.IO) {
                        SpeechRecordingStore(app).save(checkNotNull(trial.pcm), trial.size).id
                    }
                } catch (_: Exception) {
                    trial.recordingFailure = true
                }
            }
            if (accepts(trial)) {
                state.value = state.value.copy(samples = trial.size / 2L)
            }
            outcome = when {
                captureFailure == "USER_CANCELED" || trial.canceled.get() ->
                    Outcome(SpeechTrialStatus.CANCELED, "USER_CANCELED")
                captureFailure != null -> failure(captureFailure)
                trial.size == 0 -> failure("EMPTY_PCM")
                !runtime.activityVisible || !runtime.granted(Manifest.permission.RECORD_AUDIO) ->
                    failure("MIC_PREREQUISITE_LOST")
                else -> recognize(trial)
            }
        } catch (_: Exception) {
            outcome = failure("API_FAILURE")
        } finally {
            // 마지막 판정과 dry-run 시작 사이에는 suspend가 없다. 취소·만료·중복과 같은 Main 경계다.
            if (trial.canceled.get() || !runtime.policy.accepts(trial.session.id) || !runtime.activityVisible) trial.command.cancel()
            state.value = state.value.copy(status = SpeechTrialStatus.VALIDATING)
            val commandResult = trial.command.decide(
                runtime.now(),
                outcome.status == SpeechTrialStatus.COMPLETE && outcome.finalReceived &&
                    runtime.granted(Manifest.permission.RECORD_AUDIO),
                outcome.decision,
                gateway,
            )
            state.value = state.value.copy(commandResult = commandResult)
            state.value = state.value.copy(status = SpeechTrialStatus.CLEANING)
            val pcm = trial.pcm
            trial.pcm = null
            if (pcm != null) withContext(Dispatchers.IO) { pcm.fill(0) }
        }

        if (trial.inputReleased && trial.cleanupFailures.isEmpty() && !trial.releaseUncertain.get() &&
            interruption(trial) == null) {
            val speaker = SpeechResponse(app, "diagnostic-${trial.session.id}")
            trial.response = speaker
            response = speaker.speak(trial.command.result, { interruption(trial) }) { status ->
                state.value = state.value.copy(
                    status = if (status == SpeechResponseStatus.INITIALIZING) SpeechTrialStatus.RESPONSE_INITIALIZING else SpeechTrialStatus.RESPONDING,
                    responseStatus = status, inputReleased = trial.inputReleased,
                    outputReleased = false, audioFocusReleased = status != SpeechResponseStatus.SPEAKING,
                    offlineVoiceSelected = status == SpeechResponseStatus.SPEAKING,
                )
            }
            trial.response = null
            if (response.releaseUncertain) trial.releaseUncertain.set(true)
        } else {
            val interrupted = interruption(trial)
            response = SpeechResponseOutcome(
                when (interrupted) {
                    SpeechResponseReason.USER_CANCELED -> SpeechResponseStatus.CANCELED
                    SpeechResponseReason.SESSION_EXPIRED -> SpeechResponseStatus.EXPIRED
                    else -> SpeechResponseStatus.SKIPPED
                },
                interrupted ?: SpeechResponseReason.INPUT_NOT_RELEASED,
            )
        }
        when {
            trial.cleanupFailures.isNotEmpty() -> {
                val disposition = if (trial.releaseUncertain.get()) "RESTART_REQUIRED" else "RESOURCES_RELEASED"
                outcome = outcome.copy(
                    status = SpeechTrialStatus.FAILED,
                    reason = trial.cleanupFailures.distinct().joinToString("_AND_") + "_$disposition",
                    decision = null,
                )
            }
            response.releaseUncertain -> outcome = outcome.copy(status = SpeechTrialStatus.FAILED, reason = "TTS_RELEASE_FAILED_RESTART_REQUIRED")
            response.status == SpeechResponseStatus.FAILED -> outcome = outcome.copy(status = SpeechTrialStatus.FAILED, reason = "VOICE_GUIDANCE_FAILED")
            trial.canceled.get() || response.status == SpeechResponseStatus.CANCELED ->
                outcome = outcome.copy(status = SpeechTrialStatus.CANCELED, reason = "USER_CANCELED",
                    decision = outcome.decision.takeIf { trial.command.result == DiagnosticCommandResult.PROCESSED || trial.command.result == DiagnosticCommandResult.UNKNOWN })
            runtime.now() >= trial.session.deadline || response.status == SpeechResponseStatus.EXPIRED ->
                outcome = outcome.copy(status = SpeechTrialStatus.TIMED_OUT, reason = "SESSION_EXPIRED",
                    decision = outcome.decision.takeIf { trial.command.result == DiagnosticCommandResult.PROCESSED || trial.command.result == DiagnosticCommandResult.UNKNOWN })
        }
        val elapsed = runtime.now() - trial.started
        trial.finished = true
        state.value = SpeechTrialState(
            status = outcome.status, mode = trial.mode, reason = outcome.reason,
            samples = trial.size / 2L, pcmBytesWritten = trial.acceptedBytes,
            finalReceived = outcome.finalReceived, phraseMatched = outcome.matched,
            confidence = outcome.confidence, elapsedMs = elapsed, decision = outcome.decision,
            recordingId = trial.recordingId, recordingFailure = trial.recordingFailure,
            commandResult = trial.command.result, responseStatus = response.status, responseReason = response.reason,
            inputReleased = trial.inputReleased, outputReleased = response.outputReleased,
            audioFocusReleased = response.audioFocusReleased, offlineVoiceSelected = response.offlineVoiceSelected,
            responseCleanupFailed = response.cleanupFailed, transcript = outcome.transcript, shadow = outcome.shadow,
        )
        restartRequired = trial.releaseUncertain.get()
        // current와 lease는 STT부터 TTS 완료·정리까지 유지한다. 해제 불명은 재시작 외에 풀지 않는다.
        if (!restartRequired) {
            runtime.releaseSpeechDiagnostic(trial.session.id)
            current = null
        }
        runtime.event(
            "SPEECH_TRIAL_${outcome.reason}_${trial.mode.name}",
            SpeechTrialSummary(
                mode = trial.mode.name, status = outcome.status.name, reason = outcome.reason,
                finalReceived = outcome.finalReceived, phraseMatched = outcome.matched,
                confidence = outcome.confidence, elapsedMs = elapsed,
                samples = trial.size / 2L, pcmBytesWritten = trial.acceptedBytes,
                audioLeaseRetained = restartRequired, decision = outcome.decision,
                commandResult = trial.command.result, responseStatus = response.status, responseReason = response.reason,
                inputReleased = trial.inputReleased, outputReleased = response.outputReleased,
                audioFocusReleased = response.audioFocusReleased, offlineVoiceSelected = response.offlineVoiceSelected,
                responseCleanupFailed = response.cleanupFailed,
            ),
        )
    }

    /**
     * 제한된 RAM 버퍼로 직접 마이크를 캡처한다. 반환 시 AudioRecord는 이미 해제됐고,
     * 호출자는 이 반환 뒤에만 인식을 시작한다. 캡처 실패는 고정 비민감 코드다.
     */
    private suspend fun captureMicrophone(trial: Trial): String? {
        val buffer = ByteArray(MAX_BYTES)
        trial.pcm = buffer
        if (trial.canceled.get()) {
            trial.inputReleased = true
            return "USER_CANCELED"
        }
        publish(trial, SpeechTrialStatus.CAPTURING)
        val captured = withContext(Dispatchers.IO) {
            SpeechMicCapture.capture(
                context = app,
                pcm = buffer,
                deadlineElapsed = trial.started + LIMIT_MS,
                canceled = trial.canceled,
                finish = trial.finishCapture,
                stopReason = { null },
                silenceStopMs = 0,
                progress = { count ->
                    withContext(Dispatchers.Main.immediate) {
                        if (accepts(trial)) {
                            state.value = state.value.copy(samples = count / 2L)
                        }
                    }
                },
            )
        }
        trial.size = captured.bytes
        captured.cleanupFailures.forEach { trial.cleanupFailures.add(it) }
        if (captured.releaseUncertain) trial.releaseUncertain.set(true)
        trial.inputReleased = captured.cleanupFailures.isEmpty() && !captured.releaseUncertain
        return captured.failure
    }

    /**
     * PCM 전체를 Android 온디바이스 인식기에 넘긴다. 인식기는 마이크를 열지 않는다.
     * 취소·화면 이탈·세션 만료는 인식기 대기를 즉시 끊고 결과를 쓰지 않는다.
     */
    private suspend fun recognize(trial: Trial): Outcome {
        if (accepts(trial)) {
            state.value = state.value.copy(samples = trial.size / 2L)
        }
        publish(trial, SpeechTrialStatus.RECOGNIZING)
        val pcm = trial.pcm ?: return failure("EMPTY_PCM")
        val recognized = AndroidPcmRecognizer(app).recognize(pcm, trial.size, RECOGNITION_TIMEOUT_MS) {
            trial.canceled.get() || !runtime.activityVisible || runtime.now() >= trial.session.deadline
        }
        trial.acceptedBytes = recognized.acceptedBytes
        recognized.cleanupFailures.forEach { trial.cleanupFailures.add(it) }
        if (recognized.releaseUncertain) trial.releaseUncertain.set(true)
        val candidates = recognized.candidates
        trial.recordingId?.let { id ->
            try {
                withContext(Dispatchers.IO) {
                    SpeechRecordingStore(app).saveResult(
                        id, candidates.firstOrNull()?.text, recognized.failure, runtime.now() - trial.started,
                    )
                }
            } catch (_: Exception) {
                trial.recordingFailure = true
            }
        }
        if (accepts(trial)) {
            state.value = state.value.copy(pcmBytesWritten = recognized.acceptedBytes)
        }
        val outcome = when {
            trial.canceled.get() || !runtime.activityVisible -> Outcome(SpeechTrialStatus.CANCELED, "USER_CANCELED")
            runtime.now() >= trial.session.deadline -> Outcome(SpeechTrialStatus.TIMED_OUT, "SESSION_EXPIRED")
            recognized.failure == "RECOGNITION_TIMEOUT" -> Outcome(SpeechTrialStatus.TIMED_OUT, "RECOGNITION_TIMEOUT")
            recognized.failure != null -> failure(recognized.failure)
            else -> recognitionOutcome(recognized.segments.size, candidates)
        }
        // 사용자 중단 회차는 원문을 화면에도 올리지 않는다. 실패·만료 회차는 받은 후보를 그대로 보여 준다.
        if (outcome.status == SpeechTrialStatus.CANCELED) return outcome
        val shadow = shadowTranscript(trial, pcm)
        return outcome.copy(transcript = SttTranscript.of(candidates), shadow = shadow)
    }

    /**
     * Android 결과 뒤 같은 PCM을 sherpa-onnx로 인식한다. 취소·화면 이탈·만료면 시작하지 않고,
     * 인식 중 만료되면 결과를 버린다. 네이티브 인식은 중간에 끊을 수 없어 취소는 인식이 끝난 뒤 반영된다.
     */
    private suspend fun shadowTranscript(trial: Trial, pcm: ByteArray): ShadowTranscript? {
        fun stopped() = trial.canceled.get() || !runtime.activityVisible || runtime.now() >= trial.session.deadline
        if (stopped()) return null
        val shadow = withContext(Dispatchers.Default) { SherpaShadowRecognizer.decode(app.assets, pcm, trial.size) }
        return if (stopped()) null else shadow.judged(SpeechCommandPhrases.normalizedSentence)
    }

    /**
     * n-best 중 하나라도 기대 문장과 같으면 일치로 본다. 판정은 어느 후보든 취소면 취소,
     * 아니면 명령 후보가 있으면 후보, 그 외에는 1순위 판정이다. 점수는 (0, 1] 유한값만 남긴다.
     */
    private fun recognitionOutcome(segments: Int, candidates: List<SttHypothesis>): Outcome {
        val normalized = candidates.map { SpeechCommandParser.normalize(it.text) }
        val first = normalized.indexOfFirst { it.isNotEmpty() }
        if (first < 0) return failure("NO_FINAL_RESULT")
        val matched = normalized.indexOf(SpeechCommandPhrases.normalizedSentence)
        val picked = if (matched >= 0) matched else first
        val decisions = normalized.filter { it.isNotEmpty() && it.length <= SpeechCommandParser.MAX_NORMALIZED_LENGTH }
            .map(SpeechCommandParser::decideNormalized)
        val decision = when {
            SpeechCommandDecision.COMMAND_CANCELED in decisions -> SpeechCommandDecision.COMMAND_CANCELED
            SpeechCommandDecision.FRUNK_OPEN_CANDIDATE in decisions -> SpeechCommandDecision.FRUNK_OPEN_CANDIDATE
            normalized[first].length <= SpeechCommandParser.MAX_NORMALIZED_LENGTH ->
                SpeechCommandParser.decideNormalized(normalized[first])
            else -> null
        }
        return Outcome(
            SpeechTrialStatus.COMPLETE, if (segments <= 1) "FINAL_RECEIVED" else "SEGMENTS_RECEIVED_$segments", true,
            matched >= 0, candidates[picked].confidence?.takeIf { it.isFinite() && it > 0f && it <= 1f }, decision,
        )
    }

    private fun interruption(trial: Trial): SpeechResponseReason? = when {
        trial.canceled.get() || !runtime.activityVisible || !runtime.policy.accepts(trial.session.id) ||
            !runtime.granted(Manifest.permission.RECORD_AUDIO) -> SpeechResponseReason.USER_CANCELED
        runtime.now() >= trial.session.deadline -> SpeechResponseReason.SESSION_EXPIRED
        else -> null
    }

    /**
     * 시작 전 거절 요약. 캡처·정리 단계를 지나지 않았으므로 수치·신뢰도는 0·null이고
     * 예약은 잡히지 않았거나(거절 사유가 `LEASE_UNAVAILABLE`) 잡히기 전에 끝났다.
     */
    private fun rejectedSummary(mode: SpeechTrialMode, reason: String) = SpeechTrialSummary(
        mode = mode.name,
        status = SpeechTrialStatus.FAILED.name,
        reason = reason,
        finalReceived = false,
        phraseMatched = null,
        confidence = null,
        elapsedMs = null,
        samples = 0L,
        pcmBytesWritten = 0,
        audioLeaseRetained = false,
    )

    private fun loadFixture(trial: Trial): String? {
        var input: java.io.InputStream? = null
        trial.inputReleased = true
        return try {
            if (trial.canceled.get()) return "USER_CANCELED"
            val pcm = ByteArray(MAX_BYTES)
            trial.pcm = pcm
            val stream = app.resources.openRawResource(R.raw.speech_trial_ko)
            input = stream
            trial.size = SpeechPcmFixture.read(stream, pcm) { trial.canceled.get() }
            if (trial.mode == SpeechTrialMode.SILENT_PCM) pcm.fill(0, 0, trial.size)
            null
        } catch (_: Exception) {
            "PCM_FIXTURE_LOAD_FAILED"
        } finally {
            try { input?.close() }
            catch (_: Exception) {
                trial.cleanupFailures.add("PCM_FIXTURE_CLOSE_FAILED")
                trial.releaseUncertain.set(true)
                trial.inputReleased = false
            }
        }
    }

    private fun publish(trial: Trial, status: SpeechTrialStatus) {
        if (accepts(trial)) state.value = state.value.copy(status = status)
    }

    private fun accepts(trial: Trial): Boolean =
        current === trial && !trial.finished && !trial.canceled.get() &&
            runtime.now() < trial.session.deadline && runtime.policy.accepts(trial.session.id)

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun onDeviceAvailable() = try {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
    } catch (_: Exception) {
        false
    }
    private fun failure(reason: String) = Outcome(SpeechTrialStatus.FAILED, reason)

    private companion object {
        const val LIMIT_MS = 10_000L
        const val RECOGNITION_TIMEOUT_MS = 15_000L
        const val MAX_BYTES = SpeechMicCapture.MAX_BYTES
    }
}
