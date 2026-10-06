package com.heytesla.app

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
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
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
    val associationCount: Int = 0,
    val observing: Boolean = false,
    val bluetooth: Boolean = false,
    val assistant: Boolean = false,
    val microphonePermission: Boolean = false,
    val bluetoothPermission: Boolean = false,
    val bluetoothScanPermission: Boolean = false,
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
    val bleDiagnosticActive: Boolean = false,
    val bleFieldTrialActive: Boolean = false,
    val bleFieldTrialStarting: Boolean = false,
    val bleFieldTrialStopping: Boolean = false,
    val bleFieldTrialWaitingForDeparture: Boolean = false,
    val bleFieldTrialAttemptCount: Int = 0,
    val bleFieldTrialCompletedCount: Int = 0,
    val bleFieldTrialStopReason: String? = null,
    val bleFieldConfig: BleFieldConfig = BleFieldConfig.BASELINE,
    val bleFieldCandidateCount: Int = 0,
    val bleFieldScanRunning: Boolean = false,
    val bleFieldScanFailure: Int? = null,
    val events: List<String> = emptyList(),
    val teslaKeyDiagnosticActive: Boolean = false,
    val teslaKeyCleanupFailed: Boolean = false,
    val fieldLog: FieldLogStatus = FieldLogStatus(),
)

class DiagnosticRuntime(private val app: DiagnosticApp) {
    val handler = Handler(Looper.getMainLooper())
    val policy = SessionPolicy()
    private val mutable = MutableStateFlow(DiagnosticState())
    val state = mutable.asStateFlow()
    internal val bleProbeState = MutableStateFlow(BleProbeState())
    internal val teslaKeyState = MutableStateFlow(TeslaKeyProbeState())
    private val teslaKeyProbe by lazy { TeslaBleKeyProbe(app, this, teslaKeyState) }
    private var teslaKeyToken: Long? = null
    private var nextTeslaKeyToken = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preference = booleanPreferencesKey("user_enabled_preference")
    private val observationPolicy = ObservationPolicy()
    private var observationService: ObservationService? = null
    private var observationMode = ObservationService.Mode.OBSERVATION
    private val fieldMarker = app.getSharedPreferences("ble_field_run", Context.MODE_PRIVATE)
    private var markerAvailable = true
    private var clearFieldMarkerWhenLogged = false
    private var associationIds: List<Int> = emptyList()
    var assistant: AssistantService? = null
    var microphone: MicrophoneService? = null
    var activityVisible = false
    private var generation = 0L
    private var speechDiagnosticCancel: (() -> Unit)? = null
    private var speechDiagnosticId: Long? = null
    private var bleDiagnosticId: Long? = null
    private var bleDiagnosticCancel: (() -> Unit)? = null
    internal var uwbSupportActive = false
        private set
    internal var uwbSupportCleanupFailed = false
        private set

    internal fun teslaKeyReserved(): Boolean = teslaKeyToken != null

    internal fun teslaKeyBlockedReason(): String? {
        if (teslaKeyReserved()) return if (state.value.teslaKeyCleanupFailed) "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" else "LEASE_UNAVAILABLE"
        if (!activityVisible) return "VISIBLE_UI_REQUIRED"
        if (app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) return "UNLOCKED_UI_REQUIRED"
        val s = state.value
        if (uwbSupportBlocked() || policy.current != null || microphone != null || fieldTrialReserved() ||
            s.speechDiagnosticActive || s.bleDiagnosticActive || s.enabled || s.observing ||
            s.observationStartPending || s.observationServiceRunning
        ) return "LEASE_UNAVAILABLE"
        if (!s.bluetoothPermission) return "BLUETOOTH_PERMISSION_REQUIRED"
        if (!s.bluetooth) return "BLUETOOTH_OFF"
        if (associationIds.size != 1) return "SINGLE_ASSOCIATION_REQUIRED"
        return null
    }

    internal fun acquireTeslaKey(): Long? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (teslaKeyBlockedReason() != null) return null
        val token = ++nextTeslaKeyToken
        teslaKeyToken = token
        update { it.copy(teslaKeyDiagnosticActive = true) }
        return token
    }

    internal fun teslaKeyReadinessReason(token: Long): String? {
        if (teslaKeyToken != token) return "LEASE_LOST"
        if (!activityVisible) return "VISIBLE_UI_REQUIRED"
        val s = state.value
        if (uwbSupportBlocked() || policy.current != null || microphone != null || fieldTrialReserved() ||
            s.speechDiagnosticActive || s.bleDiagnosticActive || s.enabled || s.observing ||
            s.observationStartPending || s.observationServiceRunning
        ) return "DIAGNOSTIC_EXCLUSIVITY_LOST"
        return null
    }

    internal fun releaseTeslaKey(token: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (teslaKeyToken != token || state.value.teslaKeyCleanupFailed) return
        teslaKeyToken = null
        update { it.copy(teslaKeyDiagnosticActive = false) }
    }

    internal fun retainTeslaKey(token: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (teslaKeyToken == token) update { it.copy(teslaKeyCleanupFailed = true) }
    }

    internal fun startTeslaKey(input: String, register: Boolean) = teslaKeyProbe.start(input, register)
    internal fun cancelTeslaKey(reason: String = "USER_STOP") {
        if (teslaKeyReserved()) teslaKeyProbe.cancel(reason)
    }

    internal fun acquireUwbSupport(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val s = state.value
        if (teslaKeyReserved() || uwbSupportActive || uwbSupportCleanupFailed || !activityVisible ||
            policy.current != null || microphone != null || fieldTrialReserved() ||
            s.speechDiagnosticActive || s.bleDiagnosticActive || s.enabled || s.observing ||
            s.observationStartPending || s.observationServiceRunning
        ) return false
        uwbSupportActive = true
        return true
    }

    internal fun releaseUwbSupport(cleanupFailed: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (cleanupFailed) uwbSupportCleanupFailed = true
        if (!uwbSupportCleanupFailed) uwbSupportActive = false
    }

    private fun uwbSupportBlocked(): Boolean = uwbSupportActive || uwbSupportCleanupFailed
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
                if (!state.value.bluetooth && (policy.current?.diagnostic == false || state.value.bleDiagnosticActive)) stop("BLUETOOTH_OFF")
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
            // writer의 낡은 스냅샷 대신 Main에서 최신 건강 상태를 읽는다.
            if (Looper.myLooper() == Looper.getMainLooper()) fieldLogChanged()
            else handler.post { fieldLogChanged() }
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
        try {
            if (fieldMarker.getBoolean("unclosed", false)) event("BLE_FIELD_PREVIOUS_RUN_UNCLOSED")
        } catch (_: Exception) {
            markerAvailable = false
            event("BLE_FIELD_MARKER_READ_FAILED")
        }
    }
    private fun fieldLogChanged() {
        update { it.copy(fieldLog = fieldLog.status()) }
        if (state.value.fieldLog.failed && fieldTrialReserved()) stopBleFieldTrial("FIELD_LOG_UNHEALTHY")
        clearLoggedFieldMarker()
    }

    private fun clearLoggedFieldMarker() {
        val log = fieldLog.status()
        if (!clearFieldMarkerWhenLogged || fieldTrialReserved() || log.failed || log.pending != 0) return
        clearFieldMarkerWhenLogged = false
        val cleared = try { fieldMarker.edit().putBoolean("unclosed", false).commit() } catch (_: Exception) { false }
        if (!cleared) {
            markerAvailable = false
            event("BLE_FIELD_MARKER_WRITE_FAILED")
        }
    }


    fun now() = SystemClock.elapsedRealtime()
    fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    internal fun singleAssociationId(): Int? = associationIds.singleOrNull()
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

    internal fun recordBleTrial(code: String, summary: BleTrialSummary) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { recordBleTrial(code, summary) }; return }
        record(code, bleTrial = summary)
    }

    internal fun recordBleEvidence(evidence: BleEventEvidence) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { recordBleEvidence(evidence) }; return }
        record("BLE_EVIDENCE", bleEvidence = evidence)
    }

    internal fun bleTrialSummary(attempt: Long, probe: BleProbeState, leaseRetained: Boolean): BleTrialSummary {
        val battery = try { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return BleTrialSummary(
            attempt = attempt, status = probe.status.name, reason = probe.reason, gattStatus = probe.gattStatus,
            elapsedMs = probe.elapsedMs, connected = probe.connected, serviceFound = probe.serviceFound,
            txFound = probe.txFound, rxFound = probe.rxFound, subscriptionConfirmed = probe.subscriptionConfirmed,
            remoteUnsubscribeConfirmed = probe.remoteUnsubscribeConfirmed, disconnectConfirmed = probe.disconnectConfirmed,
            localClosed = probe.localClosed, notificationCount = probe.notificationCount, leaseRetained = leaseRetained,
            interactive = app.getSystemService(PowerManager::class.java)?.isInteractive == true,
            deviceLocked = app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true,
            batteryPercent = if (level >= 0 && scale > 0) (level.toLong() * 100 / scale).toInt().coerceIn(0, 100) else null,
            phaseElapsedMs = probe.phaseElapsedMs.takeUnless { probe.status == BleProbeStatus.IDLE || probe.status == BleProbeStatus.BLOCKED },
            primaryGattStatus = probe.primaryGattStatus,
            cleanupGattStatus = probe.cleanupGattStatus,
            firstRxElapsedMs = probe.firstRxElapsedMs,
            backgroundConnect = probe.backgroundConnect,
        )
    }

    /** RAM 최근 32 목록과 영속 로그에 같은 사건을 남긴다. */
    private fun record(code: String, speechTrial: SpeechTrialSummary? = null, bleTrial: BleTrialSummary? = null,
        bleEvidence: BleEventEvidence? = null): String {
        val line = stamp(code)
        recordEvent(line)
        persist(code, speechTrial, bleTrial, bleEvidence)
        return line
    }

    private fun persist(code: String, speechTrial: SpeechTrialSummary?, bleTrial: BleTrialSummary?, bleEvidence: BleEventEvidence?) {
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
                    bleTrial = bleTrial,
                    bleEvidence = bleEvidence,
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
            FieldStateKeys.ASSOCIATIONS to s.associationCount,
            FieldStateKeys.SESSION_ACTIVE to (s.sessionId != null),
            FieldStateKeys.STOP_REASON to s.stopReason,
            FieldStateKeys.BLE_FIELD_TRIAL_ACTIVE to s.bleFieldTrialActive,
            FieldStateKeys.BLE_FIELD_TRIAL_STARTING to s.bleFieldTrialStarting,
            FieldStateKeys.BLE_FIELD_TRIAL_STOPPING to s.bleFieldTrialStopping,
            FieldStateKeys.BLE_FIELD_TRIAL_WAITING_FOR_DEPARTURE to s.bleFieldTrialWaitingForDeparture,
            FieldStateKeys.BLE_FIELD_TRIAL_ATTEMPT_COUNT to s.bleFieldTrialAttemptCount,
            FieldStateKeys.BLE_FIELD_TRIAL_COMPLETED_COUNT to s.bleFieldTrialCompletedCount,
            FieldStateKeys.BLE_FIELD_TRIAL_STOP_REASON to s.bleFieldTrialStopReason,
            FieldStateKeys.BLE_SCAN_PERMISSION to s.bluetoothScanPermission,
            FieldStateKeys.BLE_FIELD_OBSERVATION_ONLY to s.bleFieldConfig.observationOnly,
            FieldStateKeys.BLE_FIELD_SUPPLEMENTAL_SCAN to s.bleFieldConfig.supplementalScan,
            FieldStateKeys.BLE_FIELD_BT_ASSIST to s.bleFieldConfig.btAssist,
            FieldStateKeys.BLE_FIELD_BACKGROUND_CONNECT to s.bleFieldConfig.backgroundConnect,
            FieldStateKeys.BLE_FIELD_RETRY_ENABLED to s.bleFieldConfig.retryEnabled,
            FieldStateKeys.BLE_FIELD_CANDIDATE_COUNT to s.bleFieldCandidateCount,
            FieldStateKeys.BLE_FIELD_SCAN_RUNNING to s.bleFieldScanRunning,
            FieldStateKeys.BLE_FIELD_SCAN_FAILURE to s.bleFieldScanFailure,
        )
    }

    private fun stamp(code: String) = "${now()} ms · $code"

    private fun recordEvent(line: String) {
        update { it.copy(events = (listOf(line) + it.events).take(32)) }
    }

    internal fun acquireSpeechDiagnostic(cancel: () -> Unit): SessionPolicy.Session? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (uwbSupportBlocked() || teslaKeyReserved()) return null
        if (!activityVisible || policy.current != null || microphone != null ||
            state.value.enabled || state.value.observing || state.value.observationStartPending ||
            state.value.observationServiceRunning || state.value.speechDiagnosticActive || state.value.bleDiagnosticActive ||
            !granted(Manifest.permission.RECORD_AUDIO)
        ) return null
        val session = policy.startDiagnostic(now()) ?: return null
        ++generation
        speechDiagnosticCancel = cancel
        speechDiagnosticId = session.id
        update { it.copy(speechDiagnosticActive = true) }
        return session
    }

    internal fun releaseSpeechDiagnostic(id: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (speechDiagnosticId != id || policy.current?.let { it.id == id && it.diagnostic } != true) return
        policy.finish(id, now())
        speechDiagnosticCancel = null
        speechDiagnosticId = null
        update { it.copy(speechDiagnosticActive = false) }
    }

    /** 화면 배제 예외는 정확히 같은 BLE_FIELD FGS 소유자의 회차에만 허용한다. */
    internal fun acquireBleDiagnostic(owner: ObservationService, token: String, cancel: () -> Unit): SessionPolicy.Session? {
        check(Looper.myLooper() == Looper.getMainLooper())
        refresh()
        if (bleFieldAttemptBlockedReason(owner, token) != null) return null
        val session = policy.startDiagnostic(now()) ?: return null
        ++generation
        bleDiagnosticId = session.id
        bleDiagnosticCancel = cancel
        update { it.copy(bleDiagnosticActive = true) }
        return session
    }

    /** GATT 소유자만 close 성공(또는 GATT 생성 전 거절) 뒤 호출한다. 만료·외부 stop은 해제가 아니다. */
    internal fun releaseBleDiagnostic(id: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (bleDiagnosticId != id || policy.current?.let { it.id == id && it.diagnostic } != true) return
        policy.finish(id, now())
        bleDiagnosticId = null
        bleDiagnosticCancel = null
        update { it.copy(bleDiagnosticActive = false) }
    }

    internal fun ownsBleField(owner: ObservationService, token: String): Boolean =
        observationMode == ObservationService.Mode.BLE_FIELD && ownsObservation(owner, token) && state.value.bleFieldTrialActive

    internal fun bleFieldAttemptBlockedReason(owner: ObservationService, token: String): String? {
        if (!ownsBleField(owner, token) || state.value.bleFieldTrialStopping || !state.value.observing) return "FIELD_OWNER_NOT_ARMED"
        if (uwbSupportBlocked() || teslaKeyReserved()) return "LEASE_UNAVAILABLE"
        if (policy.current != null || microphone != null || state.value.speechDiagnosticActive || state.value.bleDiagnosticActive) return "LEASE_UNAVAILABLE"
        return fieldReadinessReason()
    }

    fun bleFieldTrialBlockedReason(config: BleFieldConfig = state.value.bleFieldConfig): String? {
        if (!activityVisible) return "VISIBLE_UI_REQUIRED"
        if (uwbSupportBlocked() || teslaKeyReserved()) return "LEASE_UNAVAILABLE"
        val s = state.value
        if (s.bleFieldTrialActive || s.bleFieldTrialStarting || s.bleFieldTrialStopping ||
            policy.current != null || microphone != null || s.speechDiagnosticActive || s.bleDiagnosticActive
        ) return "LEASE_UNAVAILABLE"
        if (s.enabled || s.observing || s.observationStartPending || s.observationServiceRunning) return "OBSERVATION_ACTIVE"
        if (!markerAvailable) return "FIELD_MARKER_UNAVAILABLE"
        return fieldReadinessReason(config)
    }

    private fun fieldReadinessReason(config: BleFieldConfig = state.value.bleFieldConfig): String? {
        if (state.value.fieldLog.failed) return "FIELD_LOG_UNHEALTHY"
        if (config.supplementalScan && !granted(Manifest.permission.BLUETOOTH_SCAN)) return "BLUETOOTH_SCAN_PERMISSION_REQUIRED"
        return observationReadinessReason()
    }

    fun startBleFieldTrial(config: BleFieldConfig = BleFieldConfig.BASELINE) {
        check(Looper.myLooper() == Looper.getMainLooper())
        refresh()
        bleFieldTrialBlockedReason(config)?.let { rejectBleField(it); return }
        val token = UUID.randomUUID().toString()
        if (!observationPolicy.request(token)) { rejectBleField("LEASE_UNAVAILABLE"); return }
        observationMode = ObservationService.Mode.BLE_FIELD
        // 구 실행의 비동기 로그 완료가 새 실행 marker를 지우지 못하게 봉인한다.
        clearFieldMarkerWhenLogged = false
        trialId = UUID.randomUUID().toString()
        resetPresenceBaseline()
        bleProbeState.value = BleProbeState()
        update { it.copy(bleFieldTrialStarting = true, bleFieldTrialStopReason = null,
            bleFieldConfig = config, bleFieldCandidateCount = 0, bleFieldScanRunning = false, bleFieldScanFailure = null,
            bleFieldTrialWaitingForDeparture = false, bleFieldTrialAttemptCount = 0, bleFieldTrialCompletedCount = 0,
            observationStartPending = true, automaticMicrophoneEnabled = false) }
        recordBleTrial("BLE_FIELD_START_REQUESTED", bleTrialSummary(0, BleProbeState(), false))
        if (!observationPolicy.accepts(token) || !state.value.bleFieldTrialStarting) return
        val marked = try { fieldMarker.edit().putBoolean("unclosed", true).commit() } catch (_: Exception) { false }
        if (!marked) { markerAvailable = false; stopBleFieldTrial("FIELD_MARKER_WRITE_FAILED"); return }
        try {
            app.startForegroundService(Intent(app, ObservationService::class.java).setAction(ObservationService.ACTION_START)
                .putExtra(ObservationService.REQUEST_ID, token).putExtra(ObservationService.MODE, ObservationService.Mode.BLE_FIELD.name))
            handler.postDelayed({
                if (observationPolicy.pendingRequest == token) stopBleFieldTrial("OBSERVATION_FGS_START_TIMEOUT")
            }, 5_000)
        } catch (_: SecurityException) { stopBleFieldTrial("OBSERVATION_FGS_SECURITY_DENIED") }
        catch (_: android.app.ForegroundServiceStartNotAllowedException) { stopBleFieldTrial("OBSERVATION_FGS_BACKGROUND_START_DENIED") }
        catch (_: Exception) { stopBleFieldTrial("OBSERVATION_FGS_START_FAILED") }
    }

    private fun rejectBleField(reason: String) {
        if (!state.value.bleFieldTrialActive && !state.value.bleFieldTrialStarting && !state.value.bleFieldTrialStopping) {
            update { it.copy(bleFieldTrialStopReason = reason) }
        }
        recordBleTrial("BLE_FIELD_START_REJECTED", bleTrialSummary(0, BleProbeState(status = BleProbeStatus.BLOCKED, reason = reason), false))
    }

    fun stopBleFieldTrial(reason: String = "USER_STOP") {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (observationMode != ObservationService.Mode.BLE_FIELD || state.value.bleFieldTrialStopping ||
            (!state.value.bleFieldTrialStarting && !state.value.bleFieldTrialActive)
        ) return
        update { it.copy(bleFieldTrialStopping = true, bleFieldTrialStarting = false, observing = false,
            bleFieldTrialWaitingForDeparture = false, bleFieldTrialStopReason = reason, stopReason = reason) }
        event("BLE_FIELD_STOP_REQUESTED")
        val owner = observationService
        if (owner != null) owner.stopBleField(reason)
        else finishBleField(null, observationPolicy.pendingRequest ?: return)
    }

    internal fun finishBleField(owner: ObservationService?, token: String, cleanupFailed: Boolean = false) {
        if (observationMode != ObservationService.Mode.BLE_FIELD ||
            (owner != null && !ownsObservation(owner, token)) || !observationPolicy.finish(token)
        ) return
        observationService = null
        update { it.copy(enabled = false, observing = false, present = false, observationStartPending = false,
            observationServiceRunning = false, bleFieldTrialActive = false, bleFieldTrialStarting = false,
            bleFieldTrialStopping = false, bleFieldTrialWaitingForDeparture = false, automaticMicrophoneEnabled = false,
            bleFieldScanRunning = false,
            bleFieldTrialStopReason = if (cleanupFailed) "LOCAL_CLOSE_FAILED_RESTART_REQUIRED" else it.bleFieldTrialStopReason) }
        resetPresenceBaseline()
        event("BLE_FIELD_STOPPED")
        // 최종 회차/중지 행의 fd.sync까지 끝나야 정상 종료 marker를 남긴다. FGS 정리를 지연시키지는 않는다.
        clearFieldMarkerWhenLogged = !cleanupFailed
        clearLoggedFieldMarker()
        trialId = null
    }

    fun refresh() {
        val ids = try {
            if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) cdm?.myAssociations?.filterNot { it.isSelfManaged }?.map { it.id }.orEmpty() else emptyList()
        } catch (_: Exception) { event("ASSOCIATION_READ_FAILED"); emptyList() }
        associationIds = ids
        val bt = try { granted(Manifest.permission.BLUETOOTH_CONNECT) && app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (_: SecurityException) { false }
        update { it.copy(associationCount = ids.size, bluetooth = bt, assistant = assistantActive(), microphonePermission = granted(Manifest.permission.RECORD_AUDIO), bluetoothPermission = granted(Manifest.permission.BLUETOOTH_CONNECT), bluetoothScanPermission = granted(Manifest.permission.BLUETOOTH_SCAN), notificationPermission = granted(Manifest.permission.POST_NOTIFICATIONS)) }
        if (teslaKeyReserved()) teslaKeyProbe.checkReadiness()
        if (observationPolicy.pendingRequest != null || observationPolicy.runningRequest != null) {
            val reason = if (observationMode == ObservationService.Mode.BLE_FIELD) fieldReadinessReason() else observationBlockedReason()
            if (reason != null) stopObservation(reason)
            else if (observationService?.observedAssociationId?.let { it != ids.singleOrNull() } == true) {
                stopObservation("ASSOCIATION_REMOVED")
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (enabled && teslaKeyReserved()) { event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (enabled && uwbSupportBlocked()) { event("UWB_SUPPORT_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (!enabled) {
            stopObservation("DISABLED")
            return
        }
        val current = state.value
        if (fieldTrialReserved()) { event("BLE_FIELD_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (current.enabled || current.observationStartPending) {
            refresh()
            return
        }
        if (current.speechDiagnosticActive) { event("SPEECH_TRIAL_ENABLE_BLOCKED"); return }
        if (current.bleDiagnosticActive) { event("BLE_PROBE_ENABLE_BLOCKED"); return }
        if (!activityVisible) { observationStartRejected("OBSERVATION_REQUIRES_VISIBLE_UI"); return }
        refresh()
        observationBlockedReason()?.let { observationStartRejected(it); return }

        val token = UUID.randomUUID().toString()
        if (!observationPolicy.request(token)) return
        observationMode = ObservationService.Mode.OBSERVATION
        trialId = UUID.randomUUID().toString()
        resetPresenceBaseline()
        update { it.copy(observationStartPending = true) }
        persistPreference(true)
        event("OBSERVATION_FGS_START_REQUESTED")
        try {
            app.startForegroundService(
                Intent(app, ObservationService::class.java)
                    .setAction(ObservationService.ACTION_START)
                    .putExtra(ObservationService.REQUEST_ID, token)
                    .putExtra(ObservationService.MODE, ObservationService.Mode.OBSERVATION.name),
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
        if (uwbSupportBlocked() || teslaKeyReserved()) return "DIAGNOSTIC_OBSERVATION_BLOCKED"
        if (s.speechDiagnosticActive || s.bleDiagnosticActive || policy.current?.diagnostic == true) return "DIAGNOSTIC_OBSERVATION_BLOCKED"
        return observationReadinessReason()
    }

    private fun observationReadinessReason(): String? {
        val s = state.value
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) return "CDM_UNSUPPORTED"
        if (cdm == null) return "CDM_UNAVAILABLE"
        if (s.associationCount != 1) return if (s.associationCount == 0) "ASSOCIATION_REQUIRED" else "MULTIPLE_ASSOCIATIONS_UNSUPPORTED"
        if (!s.bluetoothPermission) return "OBSERVATION_BLUETOOTH_PERMISSION_REQUIRED"
        if (!s.bluetooth) return "BLUETOOTH_OFF"
        if (!s.notificationPermission) return "OBSERVATION_NOTIFICATION_PERMISSION_REQUIRED"
        val manager = app.getSystemService(NotificationManager::class.java) ?: return "OBSERVATION_NOTIFICATIONS_BLOCKED"
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(ObservationService.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return "OBSERVATION_NOTIFICATIONS_BLOCKED"
        return null
    }

    internal fun acceptsObservationRequest(token: String, mode: ObservationService.Mode) =
        observationPolicy.pendingRequest == token && observationMode == mode

    internal fun observationRequestBlockedReason(): String? =
        if (observationMode == ObservationService.Mode.BLE_FIELD) fieldReadinessReason() else observationBlockedReason()

    internal fun observationStarted(service: ObservationService, token: String): Boolean {
        if (uwbSupportBlocked() || teslaKeyReserved()) return false
        if (state.value.speechDiagnosticActive || state.value.bleDiagnosticActive || policy.current?.diagnostic == true) return false
        if (!observationPolicy.promote(token)) return false
        observationService = service
        if (observationMode == ObservationService.Mode.BLE_FIELD) {
            update { it.copy(enabled = true, observationServiceRunning = true, observationStartPending = false,
                bleFieldTrialActive = true, bleFieldTrialStarting = false, automaticMicrophoneEnabled = false) }
            event("BLE_FIELD_RUNNING")
        } else {
            update { it.copy(enabled = true, observationServiceRunning = true, observationStartPending = false) }
            event("OBSERVATION_FGS_RUNNING")
            event(if (state.value.automaticMicrophoneEnabled) "AUTOMATIC_MIC_OPTED_IN" else "OBSERVATION_ONLY")
            event("FEATURE_ENABLED_THIS_PROCESS")
        }
        return true
    }

    internal fun ownsObservation(service: ObservationService, token: String) =
        observationService === service && observationPolicy.runningRequest == token

    internal fun observationAccepted(service: ObservationService, token: String) {
        if (!ownsObservation(service, token)) return
        update { it.copy(observing = true) }
        event("OBSERVE_REQUEST_ACCEPTED_NOT_PRESENCE_PROOF")
        if (observationMode == ObservationService.Mode.BLE_FIELD) event("BLE_FIELD_WAITING")
    }

    /** 사용자 OFF, 알림 OFF, 시작 실패와 서비스 파괴가 모두 이 경계를 통과한다. */
    internal fun stopObservation(reason: String, token: String? = null) {
        if (observationMode == ObservationService.Mode.BLE_FIELD && fieldTrialReserved()) {
            if (token == null || observationPolicy.accepts(token)) stopBleFieldTrial(reason)
            return
        }
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
     * 자동 마이크 동의는 접근 진단이 꺼져 있고 캡처·음성·BLE 예약이 없을 때만 바꾼다.
     * 진단 비활성화는 동의와 지연 작업을 함께 해제한다.
     */
    fun setAutomaticMicrophone(enabled: Boolean) {
        if (teslaKeyReserved()) { event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (uwbSupportBlocked()) { event("UWB_SUPPORT_OTHER_DIAGNOSTIC_BLOCKED"); return }
        val s = state.value
        if (fieldTrialReserved()) { event("BLE_FIELD_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (s.enabled || s.observing || s.observationStartPending || s.observationServiceRunning) {
            event("AUTOMATIC_MIC_REQUIRES_OBSERVATION_OFF")
            return
        }
        if (s.speechDiagnosticActive) { event("SPEECH_TRIAL_AUTOMATIC_MIC_BLOCKED"); return }
        if (s.bleDiagnosticActive) { event("BLE_PROBE_AUTOMATIC_MIC_BLOCKED"); return }
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
        if (teslaKeyReserved()) { event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (uwbSupportBlocked()) { event("UWB_SUPPORT_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (fieldTrialReserved()) { event("BLE_FIELD_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (state.value.speechDiagnosticActive || state.value.bleDiagnosticActive) { event("DIAGNOSTIC_OBSERVE_BLOCKED"); return }
        refresh()
        if (!state.value.observationServiceRunning) { event("OBSERVE_REQUIRES_ENABLE"); return }
        observationService?.startObserving()
    }

    fun presence(id: Int, event: Int, receivedElapsedMs: Long = now()) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { receivePresence(id, event, receivedElapsedMs) }
        } else receivePresence(id, event, receivedElapsedMs)
    }

    private fun receivePresence(id: Int, event: Int, receivedElapsedMs: Long) {
        recordBleEvidence(BleEventEvidence(BleEvidenceKind.PRESENCE_RECEIVED,
            signal = presenceSignal(event), receivedElapsedMs = receivedElapsedMs))
        dispatchPresence(id, event, receivedElapsedMs)
    }

    private fun presenceSignal(event: Int): BleEvidenceSignal? = when (event) {
        DevicePresenceEvent.EVENT_BLE_APPEARED -> BleEvidenceSignal.CDM_BLE_APPEARED
        DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> BleEvidenceSignal.CDM_BLE_DISAPPEARED
        DevicePresenceEvent.EVENT_BT_CONNECTED -> BleEvidenceSignal.BT_CONNECTED
        DevicePresenceEvent.EVENT_BT_DISCONNECTED -> BleEvidenceSignal.BT_DISCONNECTED
        else -> null
    }

    private fun dispatchPresence(id: Int, event: Int, receivedElapsedMs: Long) {
        if (teslaKeyReserved()) { this.event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (observationMode == ObservationService.Mode.BLE_FIELD && fieldTrialReserved()) {
            refresh()
            val owner = observationService
            val token = observationPolicy.runningRequest
            val gate = when {
                state.value.bleFieldTrialStopping -> BleEvidenceGate.STOPPING
                owner == null || token == null -> BleEvidenceGate.OWNER_MISSING
                !ownsBleField(owner, token) -> BleEvidenceGate.UNARMED
                !state.value.observing -> BleEvidenceGate.NOT_OBSERVING
                owner.observedAssociationId != id || id !in associationIds -> BleEvidenceGate.WRONG_ASSOCIATION
                presenceSignal(event) == null -> BleEvidenceGate.UNSUPPORTED
                else -> null
            }
            recordBleEvidence(BleEventEvidence(
                if (gate == null) BleEvidenceKind.PRESENCE_DISPATCHED else BleEvidenceKind.PRESENCE_REJECTED,
                signal = presenceSignal(event), gate = gate, receivedElapsedMs = receivedElapsedMs,
                queueDelayMs = now() - receivedElapsedMs, localGattActive = state.value.bleDiagnosticActive))
            if (gate == null) checkNotNull(owner).fieldPresence(event, receivedElapsedMs)
            else this.event("PRESENCE_IGNORED_NOT_ARMED")
            return
        }
        if (state.value.speechDiagnosticActive) { this.event("SPEECH_TRIAL_APPROACH_BLOCKED"); return }
        if (state.value.bleDiagnosticActive) { this.event("BLE_PROBE_APPROACH_BLOCKED"); return }
        refresh()
        if (id !in associationIds || observationService?.observedAssociationId != id ||
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
        if (uwbSupportBlocked() || teslaKeyReserved()) return false
        refresh()
        val s = state.value
        return !fieldTrialReserved() && !s.speechDiagnosticActive && !s.bleDiagnosticActive && policy.current?.diagnostic != true &&
            s.enabled && s.observationServiceRunning &&
            s.automaticMicrophoneEnabled && s.observing &&
            s.bluetooth && s.assistant && s.microphonePermission
    }

    fun manualStart(context: Context) {
        if (teslaKeyReserved()) { event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (uwbSupportBlocked()) { event("UWB_SUPPORT_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (fieldTrialReserved()) { event("BLE_FIELD_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (state.value.speechDiagnosticActive) { event("SPEECH_TRIAL_MANUAL_BLOCKED"); return }
        if (state.value.bleDiagnosticActive) { event("BLE_PROBE_MANUAL_BLOCKED"); return }
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
        if (teslaKeyReserved()) { event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (uwbSupportBlocked()) { event("UWB_SUPPORT_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (fieldTrialReserved()) { event("BLE_FIELD_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (state.value.speechDiagnosticActive) { event("SPEECH_TRIAL_MICROPHONE_BLOCKED"); return }
        if (state.value.bleDiagnosticActive) { event("BLE_PROBE_MICROPHONE_BLOCKED"); return }
        if (session.diagnostic || !policy.accepts(session.id)) { event("MICROPHONE_SESSION_REJECTED"); return }
        try {
            context.startForegroundService(Intent(context, MicrophoneService::class.java).putExtra("session_id", session.id))
        } catch (_: SecurityException) { stop("FGS_SECURITY_WHILE_IN_USE_DENIED") }
        catch (_: android.app.ForegroundServiceStartNotAllowedException) { stop("FGS_BACKGROUND_START_DENIED") }
        catch (_: Exception) { stop("FGS_START_FAILED") }
    }

    fun stop(reason: String) {
        if (teslaKeyReserved()) { cancelTeslaKey("USER_STOP"); return }
        // Activity·지원조회·마이크의 일반 stop은 서비스 소유 시험을 취소하지 못한다.
        if (fieldTrialReserved()) { event("BLE_FIELD_OTHER_DIAGNOSTIC_BLOCKED"); return }
        // 진단 current는 STT·TTS/GATT 실제 해제 뒤 소유자만 종료한다. 외부 stop은 취소만 전달한다.
        val diagnostic = policy.current?.takeIf { it.diagnostic }
        if (diagnostic != null) {
            if (bleDiagnosticId == diagnostic.id) bleDiagnosticCancel?.invoke()
            else if (speechDiagnosticId == diagnostic.id) speechDiagnosticCancel?.invoke()
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

    private fun fieldTrialReserved(): Boolean = state.value.let {
        it.bleFieldTrialActive || it.bleFieldTrialStarting || it.bleFieldTrialStopping
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
