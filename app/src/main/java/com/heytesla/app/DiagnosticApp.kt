package com.heytesla.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.companion.DevicePresenceEvent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private val Context.settings by preferencesDataStore("diagnostic_preferences")

class DiagnosticApp : Application() {
    lateinit var runtime: DiagnosticRuntime
        private set
    override fun onCreate() {
        super.onCreate()
        runtime = DiagnosticRuntime(this)
    }
}

data class DiagnosticState(
    val enabled: Boolean = false,
    val observationServiceRunning: Boolean = false,
    val observationStartPending: Boolean = false,
    val automaticMicrophoneEnabled: Boolean = false,
    val savedPreference: Boolean = false,
    val associations: List<Int> = emptyList(),
    val observing: Boolean = false,
    val bluetooth: Boolean = false,
    val assistant: Boolean = false,
    val microphonePermission: Boolean = false,
    val bluetoothPermission: Boolean = false,
    val notificationPermission: Boolean = false,
    val present: Boolean = false,
    val appearedCount: Int = 0,
    val disappearedCount: Int = 0,
    val lastAppeared: String? = null,
    val lastDisappeared: String? = null,
    val session: String = "대기",
    val sessionId: Long? = null,
    val deadline: Long? = null,
    val samples: Long = 0,
    val rms: Double = 0.0,
    val silenced: Boolean? = null,
    val stopReason: String = "PROCESS_START_OFF",
    val speechDiagnosticActive: Boolean = false,
    val events: List<String> = emptyList(),
    val fieldLog: FieldLogStatus = FieldLogStatus(),
)

class DiagnosticRuntime(private val app: DiagnosticApp) {
    val handler = Handler(Looper.getMainLooper())
    val policy = SessionPolicy()
    private val mutable = MutableStateFlow(DiagnosticState())
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preference = booleanPreferencesKey("user_enabled_preference")
    private val observationPolicy = ObservationPolicy()
    private var observationService: ObservationService? = null
    var assistant: AssistantService? = null
    var microphone: MicrophoneService? = null
    var activityVisible = false
    private var generation = 0L
    private var speechDiagnosticCancel: (() -> Unit)? = null
    private val fieldLog = FieldEventSink(File(app.noBackupFilesDir, FIELD_LOG_RELATIVE_PATH))
    private val processId = UUID.randomUUID().toString()
    private val appVersion = try {
        app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0L)).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    /** 접근 진단이 false→true가 될 때 만들고 종료 사건까지 유지하는 시험 식별자. */
    private var trialId: String? = null
    val cdm: CompanionDeviceManager? get() = app.getSystemService(CompanionDeviceManager::class.java)
    private val readinessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                if (!state.value.bluetooth && policy.current?.diagnostic == false) stop("BLUETOOTH_OFF")
                event("BLUETOOTH_STATE_CHANGED")
            }
        }
    }

    init {
        app.registerReceiver(readinessReceiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
        }, Context.RECEIVER_EXPORTED)
        fieldLog.onStatusChanged = {
            // 콜백은 최신 sink 상태를 Main에서 읽는다. writer 스레드의 스냅샷을 넘기면 이전 값이 나중에 덮어쓸 수 있다.
            if (Looper.myLooper() == Looper.getMainLooper()) update { it.copy(fieldLog = fieldLog.status()) }
            else handler.post { update { it.copy(fieldLog = fieldLog.status()) } }
        }
        fieldLog.start()
        update { it.copy(fieldLog = fieldLog.status()) }
        scope.launch {
            try {
                app.settings.data.collect { data -> update { it.copy(savedPreference = data[preference] ?: false) } }
            } catch (_: Exception) { event("SETTINGS_READ_FAILED") }
        }
        // 권한·Bluetooth 스냅샷이 실제값이 되도록 refresh 뒤에 기록한다.
        refresh()
        event("PROCESS_START_OFF")
    }

    fun now() = SystemClock.elapsedRealtime()
    fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun assistantActive() = VoiceInteractionService.isActiveService(app, ComponentName(app, AssistantService::class.java))
    fun update(block: (DiagnosticState) -> DiagnosticState) { mutable.value = block(mutable.value) }
    fun event(code: String) = event(code, null)

    /**
     * 회차 종료처럼 상세 요약이 있는 사건. 요약은 이 호출이 만든 그 행에만 붙고 다음 사건으로 넘어가지 않는다.
     * 시작·게이트 사건은 코드 하나만 받는 형태를 그대로 쓰므로 요약 필드는 `null`로 남는다.
     */
    internal fun event(code: String, speechTrial: SpeechTrialSummary?) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { event(code, speechTrial) }; return }
        record(code, speechTrial)
    }

    /** RAM 최근 32 목록과 영속 로그에 같은 사건을 남긴다. */
    private fun record(code: String, speechTrial: SpeechTrialSummary? = null): String {
        val line = stamp(code)
        recordEvent(line)
        persist(code, speechTrial)
        return line
    }

    private fun persist(code: String, speechTrial: SpeechTrialSummary?) {
        try {
            // allowlist 판정과 allowlist 밖 집계는 sink가 단독으로 담당한다.
            fieldLog.enqueue(
                FieldRecord(
                    processId = processId,
                    trialId = trialId,
                    wallTimeMs = System.currentTimeMillis(),
                    elapsedRealtimeMs = now(),
                    appVersion = appVersion,
                    event = code,
                    state = fieldSnapshot(),
                    speechTrial = speechTrial,
                ),
            )
        } catch (_: Exception) {
            // 예상 밖 예외도 조용히 삼키지 않는다. 원문 없이 유실 집계로만 남긴다.
            fieldLog.recordDroppedLocally()
        }
    }

    private fun fieldSnapshot(): Map<String, Any?> {
        val s = state.value
        return linkedMapOf(
            FieldStateKeys.ENABLED to s.enabled,
            FieldStateKeys.AUTOMATIC_MIC to s.automaticMicrophoneEnabled,
            FieldStateKeys.OBSERVATION_SERVICE_RUNNING to s.observationServiceRunning,
            FieldStateKeys.OBSERVATION_START_PENDING to s.observationStartPending,
            FieldStateKeys.OBSERVING to s.observing,
            FieldStateKeys.PRESENT to s.present,
            FieldStateKeys.BLUETOOTH to s.bluetooth,
            FieldStateKeys.ASSISTANT to s.assistant,
            FieldStateKeys.MIC_PERMISSION to s.microphonePermission,
            FieldStateKeys.BLUETOOTH_PERMISSION to s.bluetoothPermission,
            FieldStateKeys.NOTIFICATION_PERMISSION to s.notificationPermission,
            FieldStateKeys.ASSOCIATIONS to s.associations.size,
            FieldStateKeys.SESSION_ACTIVE to (s.sessionId != null),
            FieldStateKeys.STOP_REASON to s.stopReason,
        )
    }

    private fun stamp(code: String) = "${now()} ms · $code"

    private fun recordEvent(line: String) {
        update { it.copy(events = (listOf(line) + it.events).take(32)) }
    }

    internal fun acquireSpeechDiagnostic(cancel: () -> Unit): SessionPolicy.Session? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!activityVisible || policy.current != null || microphone != null ||
            state.value.enabled || state.value.observationStartPending ||
            state.value.observationServiceRunning || state.value.speechDiagnosticActive ||
            !granted(Manifest.permission.RECORD_AUDIO)
        ) return null
        val session = policy.startDiagnostic(now()) ?: return null
        ++generation
        speechDiagnosticCancel = cancel
        update { it.copy(speechDiagnosticActive = true) }
        return session
    }

    internal fun releaseSpeechDiagnostic(id: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (policy.current?.let { it.id == id && it.diagnostic } != true) return
        policy.finish(id, now())
        speechDiagnosticCancel = null
        update { it.copy(speechDiagnosticActive = false) }
    }

    fun refresh() {
        val ids = try {
            if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) cdm?.myAssociations?.filterNot { it.isSelfManaged }?.map { it.id }.orEmpty() else emptyList()
        } catch (_: Exception) { event("ASSOCIATION_READ_FAILED"); emptyList() }
        val bt = try { granted(Manifest.permission.BLUETOOTH_CONNECT) && app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (_: SecurityException) { false }
        update { it.copy(associations = ids, bluetooth = bt, assistant = assistantActive(), microphonePermission = granted(Manifest.permission.RECORD_AUDIO), bluetoothPermission = granted(Manifest.permission.BLUETOOTH_CONNECT), notificationPermission = granted(Manifest.permission.POST_NOTIFICATIONS)) }
        if (observationPolicy.pendingRequest != null || observationPolicy.runningRequest != null) {
            val reason = observationBlockedReason()
            if (reason != null) stopObservation(reason)
            else if (observationService?.observedAssociationId?.let { it !in ids } == true) {
                stopObservation("ASSOCIATION_REMOVED")
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!enabled) {
            stopObservation("DISABLED")
            return
        }
        val current = state.value
        if (current.enabled || current.observationStartPending) {
            refresh()
            return
        }
        if (current.speechDiagnosticActive) { event("SPEECH_TRIAL_ENABLE_BLOCKED"); return }
        if (!activityVisible) { observationStartRejected("OBSERVATION_REQUIRES_VISIBLE_UI"); return }
        refresh()
        observationBlockedReason()?.let { observationStartRejected(it); return }

        val token = UUID.randomUUID().toString()
        if (!observationPolicy.request(token)) return
        trialId = UUID.randomUUID().toString()
        resetPresenceBaseline()
        update { it.copy(observationStartPending = true) }
        persistPreference(true)
        event("OBSERVATION_FGS_START_REQUESTED")
        try {
            app.startForegroundService(
                Intent(app, ObservationService::class.java)
                    .setAction(ObservationService.ACTION_START)
                    .putExtra(ObservationService.REQUEST_ID, token),
            )
            handler.postDelayed({
                if (observationPolicy.pendingRequest == token) {
                    stopObservation("OBSERVATION_FGS_START_TIMEOUT", token)
                }
            }, 5_000)
        } catch (_: SecurityException) { stopObservation("OBSERVATION_FGS_SECURITY_DENIED", token) }
        catch (_: android.app.ForegroundServiceStartNotAllowedException) {
            stopObservation("OBSERVATION_FGS_BACKGROUND_START_DENIED", token)
        } catch (_: Exception) { stopObservation("OBSERVATION_FGS_START_FAILED", token) }
    }

    private fun observationStartRejected(reason: String) {
        update { it.copy(stopReason = reason) }
        event(reason)
    }

    /** 스냅샷은 refresh가 갱신한다. 관찰 전용에는 기본 비서·마이크 권한을 요구하지 않는다. */
    internal fun observationBlockedReason(): String? {
        val s = state.value
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) return "CDM_UNSUPPORTED"
        if (cdm == null) return "CDM_UNAVAILABLE"
        if (s.associations.size != 1) return if (s.associations.isEmpty()) "ASSOCIATION_REQUIRED" else "MULTIPLE_ASSOCIATIONS_UNSUPPORTED"
        if (!s.bluetoothPermission) return "OBSERVATION_BLUETOOTH_PERMISSION_REQUIRED"
        if (!s.bluetooth) return "BLUETOOTH_OFF"
        if (!s.notificationPermission) return "OBSERVATION_NOTIFICATION_PERMISSION_REQUIRED"
        val manager = app.getSystemService(NotificationManager::class.java) ?: return "OBSERVATION_NOTIFICATIONS_BLOCKED"
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(ObservationService.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return "OBSERVATION_NOTIFICATIONS_BLOCKED"
        return null
    }

    internal fun acceptsObservationRequest(token: String) = observationPolicy.pendingRequest == token

    internal fun observationStarted(service: ObservationService, token: String): Boolean {
        if (!observationPolicy.promote(token)) return false
        observationService = service
        update { it.copy(enabled = true, observationServiceRunning = true, observationStartPending = false) }
        event("OBSERVATION_FGS_RUNNING")
        event(if (state.value.automaticMicrophoneEnabled) "AUTOMATIC_MIC_OPTED_IN" else "OBSERVATION_ONLY")
        event("FEATURE_ENABLED_THIS_PROCESS")
        return true
    }

    internal fun ownsObservation(service: ObservationService, token: String) =
        observationService === service && observationPolicy.runningRequest == token

    internal fun observationAccepted(service: ObservationService, token: String) {
        if (!ownsObservation(service, token)) return
        update { it.copy(observing = true) }
        event("OBSERVE_REQUEST_ACCEPTED_NOT_PRESENCE_PROOF")
    }

    /** 사용자 OFF, 알림 OFF, 시작 실패와 서비스 파괴가 모두 이 경계를 통과한다. */
    internal fun stopObservation(reason: String, token: String? = null) {
        if (!observationPolicy.finish(token)) return
        val owner = observationService
        observationService = null
        update {
            it.copy(enabled = false, observationServiceRunning = false, observationStartPending = false,
                observing = false, present = false, automaticMicrophoneEnabled = false)
        }
        try {
            stop(reason)
        } finally {
            // 마이크 해제 실패가 독립적인 CDM·관찰 FGS 정리를 막지 못하게 한다.
            resetPresenceBaseline()
            persistPreference(false)
            try {
                owner?.finishObserving()
            } finally {
                event("FEATURE_DISABLED")
                trialId = null
            }
        }
    }

    private fun persistPreference(enabled: Boolean) {
        scope.launch {
            try { app.settings.edit { it[preference] = enabled } }
            catch (_: Exception) { event("SETTINGS_WRITE_FAILED") }
        }
    }

    /**
     * 자동 마이크 동의는 접근 진단이 꺼져 있고 캡처·음성 예약이 없을 때만 바꾼다.
     * 진단 비활성화는 동의와 지연 작업을 함께 해제한다.
     */
    fun setAutomaticMicrophone(enabled: Boolean) {
        val s = state.value
        if (s.enabled || s.observing || s.observationStartPending || s.observationServiceRunning) {
            event("AUTOMATIC_MIC_REQUIRES_OBSERVATION_OFF")
            return
        }
        if (s.speechDiagnosticActive) { event("SPEECH_TRIAL_AUTOMATIC_MIC_BLOCKED"); return }
        if (policy.current != null || microphone != null) { event("AUTOMATIC_MIC_BLOCKED_SESSION_RUNNING"); return }
        if (s.automaticMicrophoneEnabled == enabled) return
        if (enabled) {
            resetPresenceBaseline()
            update { it.copy(automaticMicrophoneEnabled = true) }
            event("AUTOMATIC_MIC_ENABLED_NEXT_APPEARANCE_ONLY")
        } else {
            update { it.copy(automaticMicrophoneEnabled = false) }
            stop("AUTOMATIC_MIC_DISABLED")
        }
    }

    /** 이전 출현·대기 캡처를 새 시험 증거로 재사용하지 않도록 관찰 기준점을 초기화한다. */
    private fun resetPresenceBaseline() {
        ++generation
        policy.resetPresence()
        update { it.copy(present = false) }
    }

    fun startObserving() {
        refresh()
        if (!state.value.observationServiceRunning) { event("OBSERVE_REQUIRES_ENABLE"); return }
        observationService?.startObserving()
    }

    fun presence(id: Int, event: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { presence(id, event) }; return }
        if (state.value.speechDiagnosticActive) { this.event("SPEECH_TRIAL_APPROACH_BLOCKED"); return }
        refresh()
        if (id !in state.value.associations || observationService?.observedAssociationId != id ||
            !state.value.enabled || !state.value.observationServiceRunning || !state.value.observing
        ) {
            this.event("PRESENCE_IGNORED_NOT_ARMED")
            return
        }
        when (event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> {
                update { it.copy(present = true, appearedCount = it.appearedCount + 1) }
                val line = record("REAL_BLE_APPEARED")
                update { it.copy(lastAppeared = line) }
                if (!state.value.automaticMicrophoneEnabled) {
                    // 관찰 전용: 정책 대기열도 자동 캡처도 만들지 않는다.
                    this.event("OBSERVE_ONLY_CAPTURE_SUPPRESSED")
                    return
                }
                if (!policy.appeared(now())) {
                    this.event("DUPLICATE_OR_COOLDOWN_APPROACH_IGNORED")
                    return
                }
                val token = ++generation
                handler.postDelayed({
                    if (token == generation && automaticAllowed()) {
                        policy.startAutomatic(now())?.let { session ->
                            begin(session)
                            val active = assistant
                            if (active == null) stop("ASSISTANT_NOT_READY") else active.startApproach(session)
                        }
                    } else if (token == generation) { policy.cancelPending(); this.event("APPROACH_PREREQUISITE_MISSING") }
                }, SessionPolicy.DEBOUNCE_MS)
            }
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> {
                update { it.copy(present = false, disappearedCount = it.disappearedCount + 1) }
                val line = record("REAL_BLE_DISAPPEARED")
                update { it.copy(lastDisappeared = line) }
                policy.disappeared()
                stop("DEPARTED")
            }
            DevicePresenceEvent.EVENT_BT_CONNECTED -> this.event("BT_CONNECTED_NOT_APPROACH_TRIGGER")
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> { this.event("BT_DISCONNECTED"); stop("BT_DISCONNECTED") }
            else -> this.event("UNSUPPORTED_PRESENCE_EVENT")
        }
    }

    fun automaticAllowed(): Boolean {
        refresh()
        val s = state.value
        return !s.speechDiagnosticActive && s.enabled && s.observationServiceRunning &&
            s.automaticMicrophoneEnabled && s.observing &&
            s.bluetooth && s.assistant && s.microphonePermission
    }

    fun manualStart(context: Context) {
        if (state.value.speechDiagnosticActive) { event("SPEECH_TRIAL_MANUAL_BLOCKED"); return }
        if (!activityVisible || !granted(Manifest.permission.RECORD_AUDIO)) { event("MANUAL_REQUIRES_VISIBLE_UI_AND_MIC_PERMISSION"); return }
        val session = policy.startManual(now()) ?: run { event("SESSION_ALREADY_RUNNING"); return }
        ++generation
        begin(session)
        launchMicrophone(context, session)
    }

    private fun begin(session: SessionPolicy.Session) {
        update { it.copy(session = if (session.automatic) "자동 접근 마이크 진단" else "수동 마이크 진단 (자동 접근 합격 아님)", sessionId = session.id, deadline = session.deadline, samples = 0, rms = 0.0, silenced = null) }
        event(if (session.automatic) "AUTO_SESSION_REQUESTED" else "MANUAL_SESSION_REQUESTED")
        handler.postDelayed({
            if (policy.accepts(session.id) && microphone == null) stop("FGS_START_TIMEOUT")
        }, 5_000)
    }

    fun launchMicrophone(context: Context, session: SessionPolicy.Session) {
        if (state.value.speechDiagnosticActive) { event("SPEECH_TRIAL_MICROPHONE_BLOCKED"); return }
        try {
            context.startForegroundService(Intent(context, MicrophoneService::class.java).putExtra("session_id", session.id))
        } catch (_: SecurityException) { stop("FGS_SECURITY_WHILE_IN_USE_DENIED") }
        catch (_: android.app.ForegroundServiceStartNotAllowedException) { stop("FGS_BACKGROUND_START_DENIED") }
        catch (_: Exception) { stop("FGS_START_FAILED") }
    }

    fun stop(reason: String) {
        // 진단 current는 STT·TTS 실제 해제 뒤 소유자만 종료한다. 외부 stop은 취소만 전달한다.
        if (policy.current?.diagnostic == true) {
            speechDiagnosticCancel?.invoke()
            event(reason)
            return
        }
        ++generation
        policy.cancelPending()
        val session = policy.current
        if (session != null) {
            policy.finish(session.id, now())
            microphone?.finishCapture()
        }
        update { it.copy(session = "대기", sessionId = null, deadline = null, stopReason = reason) }
        event(reason)
    }

    fun samples(id: Long, count: Long, rms: Double) {
        if (policy.accepts(id)) update { it.copy(samples = count, rms = rms) }
    }

    fun silenced(id: Long, value: Boolean) {
        if (!policy.accepts(id)) return
        update { it.copy(silenced = value) }
        if (value) stop("INPUT_SILENCED")
    }
}
