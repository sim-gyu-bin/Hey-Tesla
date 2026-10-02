package com.heytesla.app

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 영속 로그 경로 조각. `Context.noBackupFilesDir` 기준 상대 경로다. */
const val FIELD_LOG_RELATIVE_PATH = "field-diagnostics/events.jsonl"

/**
 * JSONL 레코드 스키마 버전.
 *
 * 4 = 로컬 dry-run·오프라인 TTS 결과와 입력·출력·포커스 해제 요약.
 * 3 = `speechTrial.commandDecision`, 2 = 판정 없는 `speechTrial`, 1 = 요약 없는 과거 행.
 * 앱은 항상 현재 버전으로만 덧붙이고 기존 행을 고치지 않으므로 이전 버전과 공존한다.
 * 파서는 행마다 `version`을 보고, 없어진 필드를 가정하지 않는다.
 */
const val FIELD_LOG_RECORD_VERSION = 4

/** 이 시험의 로그 상한. 도달하면 회전·삭제·덮어쓰기 없이 기록만 멈춘다. */
const val FIELD_LOG_LIMIT_BYTES = 2L * 1024L * 1024L

private const val QUEUE_CAPACITY = 512
private const val MAX_CODE_LENGTH = 256
private const val NEWLINE = '\n'.code.toByte()
private const val NEWLINE_INT = '\n'.code

enum class FieldLogHealth { OK, LIMIT_REACHED, QUEUE_OVERFLOW, WRITE_FAILED }

/** 실패 코드. 소스에 고정된 값만 쓰며 예외 메시지·경로 원문은 담지 않는다. */
object FieldLogFailureCode {
    const val OPEN_FAILED = "OPEN_FAILED"
    const val WRITE_FAILED = "WRITE_FAILED"
    const val SYNC_FAILED = "SYNC_FAILED"
    const val LIMIT_REACHED = "LIMIT_REACHED"
    const val QUEUE_OVERFLOW = "QUEUE_OVERFLOW"
}

/**
 * 영속 로그 상태. writer 스레드가 갱신하며 실패는 프로세스가 끝날 때까지 유지된다.
 * 회복 경로(삭제·회전)를 만들지 않으므로 실패 표시는 계속 남는다.
 *
 * [pending]은 허용되어 아직 디스크에 확정되지 않은 레코드 수다. 큐 대기뿐 아니라 writer가
 * 집어 든 뒤 아직 `fd.sync`를 통과하지 못한 레코드도 포함한다.
 */
data class FieldLogStatus(
    val health: FieldLogHealth = FieldLogHealth.OK,
    val failureCode: String? = null,
    val path: String = "",
    val limitBytes: Long = FIELD_LOG_LIMIT_BYTES,
    val fileBytes: Long = 0L,
    val pending: Int = 0,
    val written: Long = 0L,
    val dropped: Long = 0L,
    val rejected: Long = 0L,
) {
    val failed: Boolean get() = health != FieldLogHealth.OK
}

/**
 * 한 줄로 기록할 사건. 값은 Boolean·Int·Long·String만 허용하며 키는 [FieldStateKeys]에 고정된 것만 쓴다.
 * VIN·association ID·BT 주소·위치·음성·인식 원문·예외 메시지는 어떤 필드로도 담지 않는다.
 *
 * [trialId]는 접근 관찰 쪽 세션 식별자이며 STT 회차 ID가 아니다. 음성 시험 회차는 [speechTrial]과
 * 이 행의 `event`·`wallMs`·`elapsedMs`로 구분한다.
 *
 * [speechTrial]은 회차를 끝내는 그 행에만 붙는 상세요약이다. 시작·게이트·다른 사건 행은 null이므로
 * 직전 회차 값이 다음 행으로 이어지지 않는다. 요약도 문자열 allowlist와 유한한 신뢰도만 통과시킨다.
 */
data class FieldRecord(
    val processId: String,
    val trialId: String?,
    val wallTimeMs: Long,
    val elapsedRealtimeMs: Long,
    val appVersion: String,
    val event: String,
    val state: Map<String, Any?>,
    val speechTrial: SpeechTrialSummary? = null,
    val version: Int = FIELD_LOG_RECORD_VERSION,
)

/** JSON `state` 객체에 쓸 수 있는 키. 여기 없는 키는 인코딩 단계에서 버린다. */
object FieldStateKeys {
    const val ENABLED = "enabled"
    const val AUTOMATIC_MIC = "autoMic"
    const val OBSERVATION_SERVICE_RUNNING = "observationServiceRunning"
    const val OBSERVATION_START_PENDING = "observationStartPending"
    const val OBSERVING = "observing"
    const val PRESENT = "present"
    const val BLUETOOTH = "bluetooth"
    const val ASSISTANT = "assistant"
    const val MIC_PERMISSION = "micPermission"
    const val BLUETOOTH_PERMISSION = "bluetoothPermission"
    const val NOTIFICATION_PERMISSION = "notificationPermission"
    const val ASSOCIATIONS = "associations"
    const val SESSION_ACTIVE = "sessionActive"
    const val STOP_REASON = "stopReason"

    /** 기록 순서를 고정한 허용 키 목록. */
    val ORDER: List<String> = listOf(
        ENABLED,
        AUTOMATIC_MIC,
        OBSERVATION_SERVICE_RUNNING,
        OBSERVATION_START_PENDING,
        OBSERVING,
        PRESENT,
        BLUETOOTH,
        ASSISTANT,
        MIC_PERMISSION,
        BLUETOOTH_PERMISSION,
        NOTIFICATION_PERMISSION,
        ASSOCIATIONS,
        SESSION_ACTIVE,
        STOP_REASON,
    )
}

/**
 * event code 단일 allowlist. 소스에 실제로 있는 code만 허용하며, 정규식으로 문자열 모양만 보고 통과시키지 않는다.
 * 새 code를 추가할 때는 여기에도 추가해야 한다. 목록 밖 code는 파일에 쓰지 않고
 * [FieldLogStatus.rejected]로 집계한다.
 */
object FieldEvents {
    private val fixedCodes = setOf(
        "ASSOCIATION_CREATED",
        "ASSOCIATION_FAILED",
        "ASSOCIATION_READ_FAILED",
        "ASSOCIATION_REMOVED",
        "ASSOCIATION_REQUEST_FAILED",
        "ASSOCIATION_REQUIRED",
        "ASSOCIATION_SECURITY_DENIED",
        "APPROACH_PREREQUISITE_MISSING",
        "ASSISTANT_CHANGED",
        "ASSISTANT_DESTROYED",
        "ASSISTANT_NOT_ACTIVE",
        "ASSISTANT_NOT_READY",
        "ASSISTANT_READY",
        "AUDIO_INITIALIZATION_FAILED",
        "AUDIO_READ_ERROR",
        "AUDIO_RECORD_STARTED_NOT_RECOGNITION",
        "AUDIO_WORKER_FAILED",
        "AUTO_PREREQUISITE_LOST",
        "AUTO_SESSION_REQUESTED",
        "AUTOMATIC_MIC_BLOCKED_SESSION_RUNNING",
        "AUTOMATIC_MIC_DISABLED",
        "AUTOMATIC_MIC_ENABLED_NEXT_APPEARANCE_ONLY",
        "AUTOMATIC_MIC_OPTED_IN",
        "AUTOMATIC_MIC_REQUIRES_OBSERVATION_OFF",
        "BLUETOOTH_OFF",
        "BLUETOOTH_STATE_CHANGED",
        "BT_CONNECTED_NOT_APPROACH_TRIGGER",
        "BT_DISCONNECTED",
        "CDM_UNAVAILABLE",
        "CDM_UNSUPPORTED",
        "CHOOSER_APPROVED",
        "CHOOSER_CANCELED_OR_FAILED",
        "CHOOSER_LAUNCH_FAILED",
        "DEPARTED",
        "DISABLED",
        "DUPLICATE_OR_COOLDOWN_APPROACH_IGNORED",
        "FEATURE_DISABLED",
        "FEATURE_ENABLED_THIS_PROCESS",
        "FGS_BACKGROUND_START_DENIED",
        "FGS_SECURITY_WHILE_IN_USE_DENIED",
        "FGS_START_FAILED",
        "FGS_START_TIMEOUT",
        "INPUT_SILENCED",
        "MANUAL_DIAGNOSTICS_HIDDEN",
        "MANUAL_REQUIRES_VISIBLE_UI_AND_MIC_PERMISSION",
        "MANUAL_SESSION_REQUESTED",
        "MANUAL_UI_HIDDEN",
        "MIC_FGS_START_DENIED",
        "MIC_PERMISSION_REVOKED",
        "MIC_SECURITY_WHILE_IN_USE_DENIED",
        "MIC_SERVICE_DESTROYED",
        "MULTIPLE_ASSOCIATIONS_UNSUPPORTED",
        "NOTIFICATION_STOP",
        "OBSERVATION_ONLY",
        "OBSERVATION_BLUETOOTH_PERMISSION_REQUIRED",
        "OBSERVATION_FGS_BACKGROUND_START_DENIED",
        "OBSERVATION_FGS_RUNNING",
        "OBSERVATION_FGS_SECURITY_DENIED",
        "OBSERVATION_FGS_START_FAILED",
        "OBSERVATION_FGS_START_REQUESTED",
        "OBSERVATION_FGS_START_TIMEOUT",
        "OBSERVATION_NOTIFICATION_PERMISSION_REQUIRED",
        "OBSERVATION_NOTIFICATION_STOP",
        "OBSERVATION_NOTIFICATIONS_BLOCKED",
        "OBSERVATION_REQUIRES_VISIBLE_UI",
        "OBSERVATION_SERVICE_DESTROYED",
        "OBSERVATION_STALE_START_IGNORED",
        "OBSERVE_ONLY_CAPTURE_SUPPRESSED",
        "OBSERVE_REQUEST_ACCEPTED_NOT_PRESENCE_PROOF",
        "OBSERVE_REQUIRES_ENABLE",
        "OBSERVE_SECURITY_DENIED",
        "OBSERVE_STOP_FAILED_LOCAL_GATE_CLOSED",
        "OBSERVE_STOPPED",
        "OBSERVE_UNAVAILABLE",
        "PRESENCE_IGNORED_NOT_ARMED",
        "PROCESS_START_OFF",
        "REAL_BLE_APPEARED",
        "REAL_BLE_DISAPPEARED",
        "RUNTIME_PERMISSION_RESULT",
        "SESSION_ALREADY_RUNNING",
        "SESSION_EXPIRED",
        "SETTINGS_READ_FAILED",
        "SETTINGS_SCREEN_UNAVAILABLE",
        "SETTINGS_WRITE_FAILED",
        "SPEECH_SUPPORT_REQUESTED",
        "SPEECH_TRIAL_APPROACH_BLOCKED",
        "SPEECH_TRIAL_AUTOMATIC_MIC_BLOCKED",
        "SPEECH_TRIAL_BUSY",
        "SPEECH_TRIAL_ENABLE_BLOCKED",
        "SPEECH_TRIAL_MANUAL_BLOCKED",
        "SPEECH_TRIAL_MICROPHONE_BLOCKED",
        "SPEECH_TRIAL_RESTART_REQUIRED",
        "STT_MODEL_DOWNLOAD_UNSUPPORTED",
        "UI_PAUSED",
        "UI_RESUMED",
        "UNSUPPORTED_PRESENCE_EVENT",
        "USER_STOP",
        "VIN_FORMAT_INVALID",
    )

    /** SpeechSupportProbe가 만드는 `SPEECH_SUPPORT_<사유>` 사유. */
    private val supportReasons = setOf(
        "CANCELED",
        "DESTROY_FAILED",
        "METADATA_FAILED",
        "METADATA_RECEIVED",
        "ON_DEVICE_API_UNAVAILABLE",
        "ON_DEVICE_UNAVAILABLE",
        "PERMISSION_CHANGED",
        "PERMISSION_REQUIRED",
        "REQUEST_FAILED",
        "SECURITY_DENIED",
        "TIMEOUT",
        "UNEXPECTED_RECOGNITION_EVENT",
    )

    /** SpeechRecognitionProbe가 거부할 때 쓰는 `SPEECH_TRIAL_<사유>` 사유. 모드 접미사가 붙지 않는다. */
    private val trialRejectionReasons = setOf(
        "KO_KR_NOT_INSTALLED",
        "LEASE_UNAVAILABLE",
        "MIC_PERMISSION_REQUIRED",
        "ON_DEVICE_UNAVAILABLE",
    )

    /** SpeechRecognitionProbe 시험 결과의 `SPEECH_TRIAL_<사유>_<모드>` 사유. */
    private val trialOutcomeReasons = setOf(
        "API_FAILURE",
        "AUDIO_CALLBACK_RELEASE_FAILED",
        "AUDIO_CAPTURE_FAILED",
        "AUDIO_FORMAT_UNAVAILABLE",
        "AUDIO_READ_FAILED",
        "AUDIO_RELEASE_FAILED",
        "AUDIO_STOP_FAILED",
        "EMPTY_PCM",
        "FINAL_RECEIVED",
        "INPUT_SILENCED",
        "INTERNAL_FAILURE",
        "MIC_PERMISSION_REVOKED",
        "MIC_PREREQUISITE_LOST",
        "NO_FINAL_RESULT",
        "PCM_FIXTURE_CLOSE_FAILED",
        "PCM_FIXTURE_LOAD_FAILED",
        "PCM_WRITE_FAILED",
        "RECOGNITION_TIMEOUT",
        "STOP_FAILED",
        "USER_CANCELED",
        "SESSION_EXPIRED",
        "VOICE_GUIDANCE_FAILED",
        "TTS_RELEASE_FAILED_RESTART_REQUIRED",
    )

    /** 정리 실패를 `_AND_`로 이어 붙일 때 쓰는 조각. */
    private val trialCleanupTokens = setOf(
        "AUDIO_CALLBACK_RELEASE_FAILED",
        "AUDIO_RELEASE_FAILED",
        "AUDIO_STOP_FAILED",
        "PCM_FIXTURE_CLOSE_FAILED",
        "PCM_READ_FD_CLOSE_FAILED",
        "PCM_WRITE_FD_CLOSE_FAILED",
        "RECOGNIZER_CANCEL_FAILED",
        "RECOGNIZER_DESTROY_FAILED",
    )

    private val trialModes = setOf("DIRECT_MIC", "BUFFERED_PCM", "TTS_PCM", "SILENT_PCM")

    private val cleanupSuffixes = listOf("_RESTART_REQUIRED", "_RESOURCES_RELEASED")

    fun isAllowed(code: String): Boolean {
        if (code.isEmpty() || code.length > MAX_CODE_LENGTH) return false
        if (code in fixedCodes) return true
        if (numericCode(code, "ASSOCIATION_FAILED_CODE_")) return true
        if (numericCode(code, "SPEECH_SUPPORT_SUPPORT_ERROR_")) return true
        if (numericCode(code, "SPEECH_SUPPORT_RECOGNIZER_ERROR_")) return true
        suffix(code, "SPEECH_SUPPORT_")?.let { return it in supportReasons }
        suffix(code, "SPEECH_TRIAL_START_")?.let { return it in trialModes }
        suffix(code, "SPEECH_TRIAL_")?.let { return trialOutcomeAllowed(it) }
        return false
    }

    private fun trialOutcomeAllowed(value: String): Boolean {
        if (value in trialRejectionReasons) return true
        val mode = trialModes.firstOrNull { value.endsWith("_$it") } ?: return false
        val reason = value.removeSuffix("_$mode")
        if (reason.isEmpty()) return false
        if (reason in trialOutcomeReasons) return true
        if (numericCode(reason, "RECOGNIZER_ERROR_")) return true
        if (numericCode(reason, "SEGMENTS_RECEIVED_")) return true
        return cleanupFailure(reason)
    }

    private fun cleanupFailure(reason: String): Boolean {
        val suffixText = cleanupSuffixes.firstOrNull { reason.endsWith(it) } ?: return false
        val tokens = reason.removeSuffix(suffixText).split("_AND_")
        return tokens.isNotEmpty() && tokens.all { it in trialCleanupTokens }
    }

    private fun suffix(code: String, prefix: String): String? =
        if (code.startsWith(prefix) && code.length > prefix.length) code.substring(prefix.length) else null

    /**
     * 정수 접미사 code. 호출부가 넘기는 오류 코드·조각 수·연결 오류 코드는 작은 정수이고,
     * 접미사 자체는 앞에 붙을 수 있는 `-` 하나와 숫자만 허용하므로 임의 문자열이 통과하지 않는다.
     * `AssociationRequest.Callback.onFailure`처럼 음수를 넘기는 호출부의 코드를 유실 없이 남기기 위한 범위다.
     */
    private fun numericCode(code: String, prefix: String): Boolean {
        val value = suffix(code, prefix) ?: return false
        val digits = if (value.startsWith('-')) value.substring(1) else value
        return digits.length in 1..6 && digits.all { it in '0'..'9' }
    }
}

/**
 * JSONL 인코딩. 한 레코드는 반드시 한 줄이며 제어문자를 escape한다.
 * `state`의 문자열 값도 [FieldEvents] allowlist를 통과한 것만 기록하므로 임의 문자열이 파일로 새지 않는다.
 */
internal object FieldJson {
    fun encode(record: FieldRecord): String = buildString(384) {
        append("{\"version\":").append(record.version)
        append(",\"processId\":").append(quote(record.processId))
        append(",\"trialId\":").append(record.trialId?.let { quote(it) } ?: "null")
        append(",\"wallMs\":").append(record.wallTimeMs)
        append(",\"elapsedMs\":").append(record.elapsedRealtimeMs)
        append(",\"appVersion\":").append(quote(record.appVersion))
        append(",\"event\":").append(quote(record.event))
        // 요약은 이 행에만 붙는다. 없는 행은 명시적 null이라 이전 회차 값으로 해석될 여지가 없다.
        append(",\"speechTrial\":").append(record.speechTrial?.let { SpeechTrialSummaryJson.encode(it) } ?: "null")
        append(",\"state\":{")
        var first = true
        for (key in FieldStateKeys.ORDER) {
            if (!record.state.containsKey(key)) continue
            if (!first) append(',')
            first = false
            append(quote(key)).append(':').append(primitive(record.state[key]))
        }
        append("}}")
    }

    /** 허용 타입 밖의 값과 allowlist 밖 문자열은 `null`로 낮춘다. */
    private fun primitive(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Int -> value.toString()
        is Long -> value.toString()
        is String -> if (FieldEvents.isAllowed(value)) quote(value) else "null"
        else -> "null"
    }

    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch < ' ' -> {
                    append("\\u00")
                    append(HEX[ch.code shr 4])
                    append(HEX[ch.code and 0x0f])
                }
                else -> append(ch)
            }
        }
        append('"')
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * 단일 IO 스레드가 소유하는 append 전용 JSONL sink.
 *
 * - [enqueue]는 allowlist 검사·큐 적재·원자 상태 갱신만 한다. 디스크 접근도, handle 조작도 하지 않는다.
 * - 파일 열기·쓰기·`fd.sync`·handle close·파일 크기 조회는 writer 스레드 전용이다. 레코드마다 flush + `fd.sync`를 수행한다.
 * - 기존 파일은 절대 수정·삭제하지 않는다. 마지막 바이트가 줄바꿈이 아니면 잘린 행을 그대로 둔 채 줄바꿈 하나만 덧붙인다.
 * - 상한 도달·IO 실패·큐 초과는 회전·삭제 없이 기록 중단으로만 처리하고 상태에 남긴다.
 * - Android API를 쓰지 않으므로 실제 파일 동작을 JVM 테스트로 검증한다. [start]를 호출해야 writer가 동작한다.
 */
class FieldEventSink(
    private val file: File,
    val limitBytes: Long = FIELD_LOG_LIMIT_BYTES,
    queueCapacity: Int = QUEUE_CAPACITY,
) {
    private class Command(val record: FieldRecord?, val latch: CountDownLatch?)

    private val queue = LinkedBlockingQueue<Command>(queueCapacity)
    private val running = AtomicBoolean(false)
    private val statusRef = AtomicReference(FieldLogStatus(path = file.absolutePath, limitBytes = limitBytes))

    /** producer가 세우는 실패 요청. 실제 handle close·크기 확정은 writer가 수행한다. */
    private val failureRequest = AtomicReference<String?>(null)

    private var handle: RandomAccessFile? = null
    private var writtenBytes = 0L
    private var finalized = false

    /** 상태 콜백. 인자를 넘기지 않으므로 구독자는 실행 시점의 [status]를 읽어 최신값을 반영한다. */
    @Volatile
    var onStatusChanged: (() -> Unit)? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val thread = Thread(Runnable { runWriter() }, "field-event-log")
        thread.isDaemon = true
        thread.start()
    }

    fun status(): FieldLogStatus = statusRef.get()

    /**
     * 기록을 큐에 넣는다. allowlist 밖 code·이미 실패한 sink·큐 초과는 넣지 않고 집계만 갱신한다.
     * 이 함수는 어느 스레드에서 불러도 디스크 IO를 하지 않는다.
     *
     * pending은 큐 적재 **전에** 올린다. 그래야 writer가 먼저 감소시켜 pending이 음수나 중간 0이 되는
     * 구간이 생기지 않는다. offer가 실패하면 같은 publish에서 pending을 되돌리고 유실로 집계한다.
     */
    fun enqueue(record: FieldRecord): Boolean {
        if (!FieldEvents.isAllowed(record.event)) {
            recordRejected()
            return false
        }
        if (statusRef.get().failed || failureRequest.get() != null) {
            drop(1)
            return false
        }
        publish { it.copy(pending = it.pending + 1) }
        if (!queue.offer(Command(record, null))) {
            publish { it.copy(pending = it.pending - 1, dropped = it.dropped + 1) }
            requestFailure(FieldLogFailureCode.QUEUE_OVERFLOW)
            return false
        }
        return true
    }

    /** allowlist 밖 code. 파일에는 쓰지 않고 집계만 한다. */
    fun recordRejected() {
        publish { it.copy(rejected = it.rejected + 1) }
    }

    /**
     * producer 쪽에서 기록이 유실된 경우(허용 code를 만들지 못한 예상 밖 예외 등) 고정 실패로 확정하고 집계만 남긴다.
     * 화면에서 보이지 않는 유실을 성공처럼 넘기지 않도록 [FieldLogFailureCode.WRITE_FAILED] 상시 실패 표시를 함께 세운다.
     * 디스크 접근도, handle 조작도 하지 않는다.
     */
    fun recordDroppedLocally() {
        requestFailure(FieldLogFailureCode.WRITE_FAILED)
        drop(1)
    }

    /** 앞서 넣은 기록이 실제로 디스크에 확정될 때까지 기다린다. 분석·테스트용 배리어이며 UI 경로에서 호출하지 않는다. */
    fun sync(timeoutMs: Long = 5_000L): Boolean {
        if (!running.get()) return false
        val latch = CountDownLatch(1)
        try {
            queue.put(Command(null, latch))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun runWriter() {
        while (true) {
            val command = try {
                queue.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            try {
                handleCommand(command)
            } catch (_: Throwable) {
                // writer가 죽으면 대기 기록이 조용히 쌓인다. 어떤 예외든 고정 실패로 확정하고 계속 돈다.
                command.latch?.countDown()
                failWriter(FieldLogFailureCode.WRITE_FAILED, command.record)
            }
        }
    }

    private fun handleCommand(command: Command) {
        if (failureRequest.get() != null) {
            finalizeFailure()
            settleLost(command)
            return
        }
        val latch = command.latch
        if (latch != null) {
            latch.countDown()
            return
        }
        val record = command.record ?: return
        writeOne(record)
    }

    /** 레코드 하나를 쓰고 flush + `fd.sync`까지 마친 뒤에만 확정으로 집계한다. */
    private fun writeOne(record: FieldRecord) {
        if (failureRequest.get() != null) {
            finalizeFailure()
            settleRecord(record)
            return
        }
        val out = open()
        if (out == null) {
            settleRecord(record)
            return
        }
        val line = try {
            FieldJson.encode(record).toByteArray(Charsets.UTF_8)
        } catch (_: Throwable) {
            // 인코딩 예외로 기록 전체가 멈추지 않도록 고정 실패로 확정한다.
            failWriter(FieldLogFailureCode.WRITE_FAILED, record)
            return
        }
        if (writtenBytes + line.size + 1L > limitBytes) {
            failWriter(FieldLogFailureCode.LIMIT_REACHED, record)
            return
        }
        try {
            out.write(line)
            out.write(NEWLINE_INT)
        } catch (_: Exception) {
            failWriter(FieldLogFailureCode.WRITE_FAILED, record)
            return
        }
        try {
            out.fd.sync()
        } catch (_: Exception) {
            failWriter(FieldLogFailureCode.SYNC_FAILED, record)
            return
        }
        writtenBytes += line.size + 1L
        publish { it.copy(fileBytes = writtenBytes, written = it.written + 1, pending = it.pending - 1) }
    }

    /** writer 전용. handle을 열고 기존 데이터 뒤에 이어 쓸 위치를 잡는다. */
    private fun open(): RandomAccessFile? {
        handle?.let { return it }
        return try {
            file.parentFile?.mkdirs()
            val existing = if (file.isFile) file.length() else 0L
            val out = RandomAccessFile(file, "rw")
            // 이후 seek/read/write 단계가 실패해도 failWriter가 닫을 수 있도록 생성 즉시 handle로 넘긴다.
            handle = out
            var length = existing
            if (existing > 0L) {
                out.seek(existing - 1L)
                if (out.read() != NEWLINE_INT && existing < limitBytes) {
                    // 이전 데이터는 그대로 두고 줄바꿈 하나만 추가해 잘린 행을 다음 레코드와 분리한다.
                    out.seek(existing)
                    out.writeByte(NEWLINE_INT)
                    length = existing + 1L
                }
            }
            out.seek(length)
            writtenBytes = length
            publish { it.copy(fileBytes = length) }
            out
        } catch (_: Exception) {
            failWriter(FieldLogFailureCode.OPEN_FAILED, null)
            null
        }
    }

    private fun settleLost(command: Command) {
        // 실패 확정 뒤에도 sync() 같은 배리어 명령은 반드시 풀어 준다.
        command.latch?.countDown()
        command.record?.let { settleRecord(it) }
    }

    private fun settleRecord(record: FieldRecord) {
        publish { it.copy(dropped = it.dropped + 1, pending = it.pending - 1) }
    }

    /** producer가 요청한 실패를 writer가 확정한다. handle close와 파일 크기 조회는 여기서만 일어난다. */
    private fun finalizeFailure() {
        if (finalized) return
        val code = failureRequest.get() ?: return
        finalized = true
        failWriter(code, null)
    }

    private fun failWriter(code: String, lost: FieldRecord?) {
        // 먼저 확정된 원인을 유지한다. producer 요청과 writer 실패가 겹쳐도 health와 failureCode가 서로 어긋나지 않는다.
        val first = failureRequest.compareAndSet(null, code)
        closeHandle()
        val length = try {
            file.length()
        } catch (_: Exception) {
            0L
        }
        val lostCount = if (lost == null) 0 else 1
        val effective = if (first) code else failureRequest.get() ?: code
        publish {
            it.copy(
                health = healthFor(effective),
                failureCode = effective,
                fileBytes = length,
                dropped = it.dropped + lostCount,
                pending = it.pending - lostCount,
            )
        }
    }

    private fun closeHandle() {
        val out = handle ?: return
        handle = null
        try {
            out.close()
        } catch (_: Exception) {
            // 닫기 실패는 상태에 남길 새 정보가 없다. 이미 실패 상태이거나 프로세스가 끝난다.
        }
    }

    /** producer 전용. handle을 건드리지 않고 원자 상태만 실패로 표시한다. */
    private fun requestFailure(code: String) {
        failureRequest.compareAndSet(null, code)
        publish { it.copy(health = healthFor(code), failureCode = code) }
    }

    private fun drop(count: Int) {
        publish { it.copy(dropped = it.dropped + count) }
    }

    private fun healthFor(code: String) = when (code) {
        FieldLogFailureCode.LIMIT_REACHED -> FieldLogHealth.LIMIT_REACHED
        FieldLogFailureCode.QUEUE_OVERFLOW -> FieldLogHealth.QUEUE_OVERFLOW
        else -> FieldLogHealth.WRITE_FAILED
    }

    private fun publish(block: (FieldLogStatus) -> FieldLogStatus) {
        var current = statusRef.get()
        while (true) {
            val updated = block(current)
            if (statusRef.compareAndSet(current, updated)) {
                // 구독자 예외가 writer를 죽이지 않게 한다.
                try {
                    onStatusChanged?.invoke()
                } catch (_: Throwable) {
                    // 콜백 실패는 상태에 남길 정보가 없다.
                }
                return
            }
            current = statusRef.get()
        }
    }
}
