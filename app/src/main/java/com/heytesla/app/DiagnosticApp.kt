package com.heytesla.app

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
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
    private var observationId: Int? = null
    var assistant: AssistantService? = null
    var microphone: MicrophoneService? = null
    var activityVisible = false
    private var generation = 0L
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
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
            if (!state.value.bluetooth) stop("BLUETOOTH_OFF")
            event("BLUETOOTH_STATE_CHANGED")
        }
    }

    init {
        app.registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
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
    fun event(code: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { event(code) }; return }
        record(code)
    }

    /** RAM 최근 32 목록과 영속 로그에 같은 사건을 남긴다. */
    private fun record(code: String): String {
        val line = stamp(code)
        recordEvent(line)
        persist(code)
        return line
    }

    private fun persist(code: String) {
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

    internal fun acquireSpeechDiagnostic(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!activityVisible || policy.current != null || microphone != null ||
            state.value.enabled || state.value.speechDiagnosticActive ||
            !granted(Manifest.permission.RECORD_AUDIO)
        ) return false
        ++generation
        policy.cancelPending()
        update { it.copy(speechDiagnosticActive = true) }
        return true
    }

    internal fun releaseSpeechDiagnostic() {
        check(Looper.myLooper() == Looper.getMainLooper())
        update { it.copy(speechDiagnosticActive = false) }
    }

    fun refresh() {
        val ids = try {
            if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) cdm?.myAssociations?.filterNot { it.isSelfManaged }?.map { it.id }.orEmpty() else emptyList()
        } catch (_: Exception) { event("ASSOCIATION_READ_FAILED"); emptyList() }
        val bt = try { granted(Manifest.permission.BLUETOOTH_CONNECT) && app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (_: SecurityException) { false }
        update { it.copy(associations = ids, bluetooth = bt, assistant = assistantActive(), microphonePermission = granted(Manifest.permission.RECORD_AUDIO), bluetoothPermission = granted(Manifest.permission.BLUETOOTH_CONNECT), notificationPermission = granted(Manifest.permission.POST_NOTIFICATIONS)) }
        if (observationId != null && observationId !in ids) {
            observationId = null
            update { it.copy(observing = false) }
            stop("ASSOCIATION_REMOVED")
        }
    }

    fun setEnabled(enabled: Boolean) {
        val current = state.value
        if (enabled && current.speechDiagnosticActive) { event("SPEECH_TRIAL_ENABLE_BLOCKED"); return }
        if (enabled && current.enabled) {
            // 중복 활성화 요청은 새 시험을 시작하지 않는다. 기존 시험 ID와 관찰을 유지한다.
            persistPreference(true)
            refresh()
            return
        }
        if (enabled) {
            // false→true 실제 전이에서 시험 ID를 먼저 만든다. 관찰 요청·스냅샷·종료 사건이 같은 시험으로 묶인다.
            trialId = UUID.randomUUID().toString()
        }
        update { it.copy(enabled = enabled) }
        persistPreference(enabled)
        if (!enabled) {
            stop("DISABLED")
            update { it.copy(automaticMicrophoneEnabled = false) }
            resetPresenceBaseline()
            stopObserving()
            event("FEATURE_DISABLED")
            trialId = null
        } else {
            resetPresenceBaseline()
            refresh()
            startObserving()
            event(if (state.value.automaticMicrophoneEnabled) "AUTOMATIC_MIC_OPTED_IN" else "OBSERVATION_ONLY")
            event("FEATURE_ENABLED_THIS_PROCESS")
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
        if (s.enabled || s.observing) { event("AUTOMATIC_MIC_REQUIRES_OBSERVATION_OFF"); return }
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
        if (!state.value.enabled) { event("OBSERVE_REQUIRES_ENABLE"); return }
        val ids = state.value.associations
        if (ids.size != 1) { event(if (ids.isEmpty()) "ASSOCIATION_REQUIRED" else "MULTIPLE_ASSOCIATIONS_UNSUPPORTED"); return }
        try {
            cdm?.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(ids.single()).build()) ?: error("CDM")
            observationId = ids.single()
            update { it.copy(observing = true) }
            event("OBSERVE_REQUEST_ACCEPTED_NOT_PRESENCE_PROOF")
        } catch (_: SecurityException) { event("OBSERVE_SECURITY_DENIED") }
        catch (_: Exception) { event("OBSERVE_UNAVAILABLE") }
    }

    private fun stopObserving() {
        val ids = state.value.associations
        var failed = false
        for (id in ids) {
            try { cdm?.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build()) }
            catch (_: Exception) { failed = true }
        }
        observationId = null
        update { it.copy(observing = false) }
        event(if (failed) "OBSERVE_STOP_FAILED_LOCAL_GATE_CLOSED" else "OBSERVE_STOPPED")
    }

    fun presence(id: Int, event: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { presence(id, event) }; return }
        if (state.value.speechDiagnosticActive) { this.event("SPEECH_TRIAL_APPROACH_BLOCKED"); return }
        refresh()
        if (id !in state.value.associations || id != observationId || !state.value.enabled) {
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
        return !s.speechDiagnosticActive && s.enabled && s.automaticMicrophoneEnabled && s.observing &&
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
