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
        // 이전 프로세스의 OS 스캔 등록만 정리한다. 감지·서비스를 복원하지 않는다.
        BleScanReceiver.cleanupOrphanedScans(this, runtime)
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
    val locationPermission: Boolean = false,
    val locationEnabled: Boolean = false,
    val bleDetectionEnabled: Boolean = false,
    val previousExitReason: Int? = null,
    val previousExitWallMs: Long? = null,
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
    val bleFieldScanRecoveryCount: Int = 0,
    val bleFieldScanRecoveryAt: Long? = null,
    val bleFieldScanRecoveryError: Int? = null,
    val bleFieldScanTimerDelayMs: Long? = null,
    val events: List<String> = emptyList(),
    val teslaKeyDiagnosticActive: Boolean = false,
    val teslaKeyCleanupFailed: Boolean = false,
    val teslaApproachRead: TeslaApproachReadSummary? = null,
    val vehicleVin: VehicleVinState = VehicleVinState(),
    val fieldLog: FieldLogStatus = FieldLogStatus(),
    val bleCandidateDiagnostics: BleCandidateDiagnostics? = null,
    val frunkVoice: FrunkVoiceState = FrunkVoiceState(),
    val frunkVoiceVisible: Boolean = false,
)

class DiagnosticRuntime private constructor(
    private val app: DiagnosticApp,
    vehicleVinStore: VehicleVinStore,
    teslaKeyStore: TeslaBleKeyStore,
    fieldLogFile: File,
    fieldMarkerName: String,
) {
    constructor(app: DiagnosticApp) : this(
        app, VehicleVinStore(app), TeslaBleKeyStore(app),
        File(app.noBackupFilesDir, FIELD_LOG_RELATIVE_PATH), "ble_field_run",
    )

    /** 계측은 생산 VIN·차량 키·로그·실행 marker를 변경하지 않는 독립 저장 경로를 사용한다. */
    internal constructor(app: DiagnosticApp, vehicleVinStore: VehicleVinStore, teslaKeyStore: TeslaBleKeyStore) : this(
        app, vehicleVinStore, teslaKeyStore,
        File(app.noBackupFilesDir, "vehicle-vin-tests/runtime-${UUID.randomUUID()}/events-v2.jsonl"),
        "vehicle_vin_runtime_test_${UUID.randomUUID()}",
    )
    val handler = Handler(Looper.getMainLooper())
    val policy = SessionPolicy()
    private val mutable = MutableStateFlow(DiagnosticState())
    val state = mutable.asStateFlow()
    internal val bleProbeState = MutableStateFlow(BleProbeState())
    internal val teslaKeyState = MutableStateFlow(TeslaKeyProbeState())
    private val teslaKeyProbe by lazy { TeslaBleKeyProbe(app, this, teslaKeyState, teslaKeyStore) }
    private var teslaKeyToken: Long? = null
    private var nextTeslaKeyToken = 0L
    private var teslaApproachRequest: TeslaApproachReadRequest? = null
    private var teslaApproachVin: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preference = booleanPreferencesKey(
        if (fieldMarkerName == "ble_field_run") "user_enabled_preference" else "${fieldMarkerName}_user_enabled_preference")
    private val observationPolicy = ObservationPolicy()
    private var observationService: ObservationService? = null
    private var observationBinding: Pair<String, android.content.ServiceConnection>? = null
    private var observationMode = ObservationService.Mode.OBSERVATION
    private val pendingBleAdvertisedName = BlePendingAdvertisedName()
    private val fieldMarker = app.getSharedPreferences(fieldMarkerName, Context.MODE_PRIVATE)
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
    private data class FrunkVoiceLease(
        val sessionId: Long, val vin: String, val associationId: Int,
        val gate: FrunkVoicePolicy, val deadline: Long,
    )
    private var frunkVoiceLease: FrunkVoiceLease? = null
    private var frunkVoiceTarget: FrunkVoiceLease? = null
    private var frunkVoiceScreenVisible = false
    internal var uwbSupportActive = false
        private set
    internal var uwbSupportCleanupFailed = false
        private set
    internal var vehicleChooserPending = false
    internal var diagnosticCleanupUncertain = false
        private set
    private val vehicleVinController = VehicleVinController(
        vehicleVinStore, scope, ::vehicleVinWorkBlockedReason,
    ) { value ->
        val restored = state.value.vehicleVin.status == VehicleVinStatus.LOADING && value.status == VehicleVinStatus.READY
        update { it.copy(vehicleVin = value) }
        if (restored) handler.post { restoreBleDetection() }
    }

    fun saveVehicleVin(input: String) = vehicleVinController.save(input)
    fun vehicleVinChangeBlockedReason(): String? = vehicleVinController.changeBlockedReason()
    internal fun vehicleVinSnapshot(): String? = vehicleVinController.snapshot()

    private fun vehicleVinWorkBlockedReason(): String? {
        val s = state.value
        if (vehicleChooserPending) return "VIN_CHANGE_BLOCKED_VEHICLE_CHOOSER"
        if (diagnosticCleanupUncertain || s.teslaKeyCleanupFailed || uwbSupportCleanupFailed ||
            bleProbeState.value.status == BleProbeStatus.CLEANUP_FAILED ||
            s.bleFieldTrialStopReason == "LOCAL_CLOSE_FAILED_RESTART_REQUIRED"
        ) return "VIN_CHANGE_BLOCKED_CLEANUP"
        if (teslaKeyReserved() || uwbSupportActive || policy.current != null || microphone != null ||
            speechDiagnosticId != null || bleDiagnosticId != null || observationService != null ||
            observationPolicy.pendingRequest != null || observationPolicy.runningRequest != null ||
            fieldTrialReserved() || s.sessionId != null || s.speechDiagnosticActive || s.bleDiagnosticActive ||
            s.enabled || s.observing || s.observationStartPending || s.observationServiceRunning
        ) return "VIN_CHANGE_BLOCKED_ACTIVE_WORK"
        if (!activityVisible) return "VISIBLE_UI_REQUIRED"
        if (app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) return "UNLOCKED_UI_REQUIRED"
        return null
    }

    private fun vehicleVinSaving(): Boolean = vehicleVinController.state.status == VehicleVinStatus.SAVING

    internal fun teslaKeyReserved(): Boolean = teslaKeyToken != null

    internal fun teslaKeyBlockedReason(): String? {
        if (diagnosticCleanupUncertain) return "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED"
        if (teslaKeyReserved()) return if (state.value.teslaKeyCleanupFailed) "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" else "LEASE_UNAVAILABLE"
        vehicleVinController.readinessReason()?.let { return it }
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

    internal fun teslaKeyReadinessReason(token: Long, request: TeslaApproachReadRequest? = null,
        requireFresh: Boolean = true): String? {
        if (teslaKeyToken != token) return "LEASE_LOST"
        val owned = teslaApproachRequest
        if (owned != null) {
            if (owned !== request) return "LEASE_LOST"
            approachOwnerReason(owned, requireFresh)?.let { return it }
            if (vehicleVinSnapshot() != teslaApproachVin) return "READINESS_CHECK_FAILED"
            val s = state.value
            if (uwbSupportBlocked() || policy.current != null || microphone != null ||
                s.speechDiagnosticActive || s.bleDiagnosticActive || s.automaticMicrophoneEnabled
            ) return "DIAGNOSTIC_EXCLUSIVITY_LOST"
            return null
        }
        if (request != null) return "LEASE_LOST"
        frunkVoiceTarget?.let { target ->
            if (!frunkVoiceScreenVisible) return "VISIBLE_UI_REQUIRED"
            if (vehicleVinSnapshot() != target.vin || singleAssociationId() != target.associationId) return "ASSOCIATION_CHANGED"
            if (diagnosticCleanupUncertain || state.value.teslaKeyCleanupFailed) return "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED"
            if (app.getSystemService(PowerManager::class.java)?.isInteractive != true ||
                app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false
            ) return "UNLOCKED_UI_REQUIRED"
        }
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
        syncFrunkVoiceResult()
        frunkVoiceTarget = null
        teslaKeyToken = null
        teslaApproachRequest = null
        teslaApproachVin = null
        update { it.copy(teslaKeyDiagnosticActive = false) }
    }

    internal fun retainTeslaKey(token: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (teslaKeyToken == token) update { it.copy(teslaKeyCleanupFailed = true) }
        syncFrunkVoiceResult()
    }

    internal fun startTeslaKey(register: Boolean = false, query: TeslaBleQuery = TeslaBleQuery.BODY_STATUS) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val vin = vehicleVinSnapshot()
        if (vin == null) {
            if (!teslaKeyReserved()) teslaKeyState.value = TeslaKeyProbeState(
                stage = TeslaKeyStage.BLOCKED, query = query, registrationRequested = register,
                reason = vehicleVinController.readinessReason() ?: "VIN_NOT_REGISTERED",
            )
            event("TESLA_KEY_START_BLOCKED")
            return
        }
        teslaKeyProbe.start(vin, register, query)
    }
    /** Activity와 수동 버튼의 취소는 service-owned 읽기에 적용하지 않는다. */
    internal fun cancelTeslaKey(reason: String = "USER_STOP") {
        if (teslaKeyReserved() && teslaApproachRequest == null) teslaKeyProbe.cancel(reason)
    }

    /** 화면 수명만 갱신한다. true 전환은 마이크나 이전 명령을 재개하지 않는다. */
    internal fun setFrunkVoiceVisible(visible: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        frunkVoiceScreenVisible = visible
        update { it.copy(frunkVoiceVisible = visible) }
        if (!visible) {
            if (frunkVoiceLease != null) speechDiagnosticCancel?.invoke()
            cancelFrunkVoice()
        }
    }

    internal fun frunkVoiceBlockedReason(ownSpeechId: Long? = null): String? {
        if (!frunkVoiceScreenVisible || !activityVisible) return "VISIBLE_UI_REQUIRED"
        if (app.getSystemService(PowerManager::class.java)?.isInteractive != true ||
            app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false
        ) return "UNLOCKED_UI_REQUIRED"
        if (diagnosticCleanupUncertain || state.value.teslaKeyCleanupFailed || uwbSupportCleanupFailed ||
            bleProbeState.value.status == BleProbeStatus.CLEANUP_FAILED
        ) return "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED"
        vehicleVinController.readinessReason()?.let { return it }
        val s = state.value
        if (s.bleDetectionEnabled || s.enabled || s.observing || s.observationStartPending ||
            s.observationServiceRunning || fieldTrialReserved()
        ) return "DETECTION_MUST_STOP"
        if (vehicleChooserPending || teslaKeyReserved() || uwbSupportBlocked() || microphone != null ||
            s.bleDiagnosticActive || bleDiagnosticId != null ||
            (policy.current != null && policy.current?.id != ownSpeechId) ||
            (s.speechDiagnosticActive && speechDiagnosticId != ownSpeechId)
        ) return "LEASE_UNAVAILABLE"
        if (!granted(Manifest.permission.RECORD_AUDIO)) return "MIC_PERMISSION_REQUIRED"
        if (!s.bluetoothPermission) return "BLUETOOTH_PERMISSION_REQUIRED"
        if (!s.bluetooth) return "BLUETOOTH_OFF"
        if (singleAssociationId() == null) return "SINGLE_ASSOCIATION_REQUIRED"
        return null
    }

    internal fun bindFrunkVoice(session: SessionPolicy.Session, gate: FrunkVoicePolicy): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (speechDiagnosticId != session.id || !policy.accepts(session.id) ||
            frunkVoiceLease != null || frunkVoiceTarget != null ||
            frunkVoiceBlockedReason(session.id) != null
        ) return false
        frunkVoiceLease = FrunkVoiceLease(session.id, vehicleVinSnapshot() ?: return false,
            singleAssociationId() ?: return false, gate, session.deadline)
        return true
    }

    internal fun frunkVoiceReadinessReason(id: Long): String? {
        check(Looper.myLooper() == Looper.getMainLooper())
        refresh()
        val lease = frunkVoiceLease ?: return "LEASE_LOST"
        if (lease.sessionId != id || speechDiagnosticId != id || !policy.accepts(id)) return "LEASE_LOST"
        if (vehicleVinSnapshot() != lease.vin || singleAssociationId() != lease.associationId) return "ASSOCIATION_CHANGED"
        return frunkVoiceBlockedReason(id)
    }

    /** 정상 해제→예약 반환→core 입장은 suspend/post 없는 동일 Main 경계다. */
    internal fun finishFrunkVoiceAudio(id: Long, inputReleased: Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val lease = frunkVoiceLease ?: return false
        if (lease.sessionId != id || frunkVoiceReadinessReason(id) != null ||
            !lease.gate.takeForDispatch(now(), inputReleased)
        ) return false
        val commandAcceptedAtElapsedMs = lease.gate.commandAcceptedAtElapsedMs ?: return false
        releaseSpeechDiagnostic(id)
        if (speechDiagnosticId != null || policy.current != null || state.value.speechDiagnosticActive) return false
        return startFrunkFromVoice(commandAcceptedAtElapsedMs)
    }

    internal fun startFrunkFromVoice(commandAcceptedAtElapsedMs: Long): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val lease = frunkVoiceLease ?: return false
        // 소진된 실차 전용 gate만 받는다. dry-run 결과·timestamp 단독으로는 입장할 수 없다.
        frunkVoiceLease = null
        refresh()
        if (lease.gate.phase != FrunkVoicePolicy.Phase.DISPATCHED ||
            lease.gate.commandAcceptedAtElapsedMs != commandAcceptedAtElapsedMs ||
            now() < commandAcceptedAtElapsedMs || now() - commandAcceptedAtElapsedMs >= FrunkVoicePolicy.HANDOFF_MS ||
            now() >= lease.deadline || frunkVoiceBlockedReason() != null ||
            vehicleVinSnapshot() != lease.vin || singleAssociationId() != lease.associationId
        ) {
            event("FRUNK_VOICE_HANDOFF_BLOCKED")
            return false
        }
        event("FRUNK_VOICE_CORE_STARTED")
        // 이전 수동/실차 회차의 관측이 새 음성 회차에 섞이지 않게 한다. 활성 예약은 위에서 배제했다.
        teslaKeyState.value = TeslaKeyProbeState()
        frunkVoiceTarget = lease
        update { it.copy(frunkVoice = it.frunkVoice.copy(stage = FrunkVoiceStage.CORE,
            active = true, inputReleased = true, coreStarted = true, cancelRequested = false,
            frunkCommandRequested = false, frunkCommandSent = false, frunkCommandAcknowledged = false,
            frunkOpenObserved = false, frunkFreshParkVerified = false,
            coreStage = null, coreOutcome = null, lastCoreStep = null)) }
        var startFailed = false
        try {
            teslaKeyProbe.startFrunkOpen(lease.vin, commandAcceptedAtElapsedMs)
        } catch (_: Exception) {
            startFailed = true
            diagnosticCleanupUncertain = true
            try { cancelTeslaKey("USER_STOP") } catch (_: Exception) { /* 예약·불명 marker를 유지한다. */ }
            event("FRUNK_VOICE_CORE_UNKNOWN")
        }
        syncFrunkVoiceResult()
        if (!teslaKeyReserved()) frunkVoiceTarget = null
        if (startFailed) update { it.copy(frunkVoice = it.frunkVoice.copy(reason = "FRUNK_VOICE_CORE_UNKNOWN")) }
        return true
    }

    internal fun cancelFrunkVoice() {
        check(Looper.myLooper() == Looper.getMainLooper())
        frunkVoiceLease?.gate?.cancel()
        frunkVoiceLease = null
        if (frunkVoiceTarget != null) {
            syncFrunkVoiceResult()
            update { it.copy(frunkVoice = it.frunkVoice.copy(cancelRequested = true)) }
            cancelTeslaKey("USER_STOP")
        }
    }

    private fun syncFrunkVoiceResult() {
        if (frunkVoiceTarget == null || !state.value.frunkVoice.coreStarted) return
        val result = teslaKeyState.value
        val core = state.value.frunkVoice.copy(
            stage = if (result.active) FrunkVoiceStage.CORE else FrunkVoiceStage.COMPLETE,
            active = result.active, reason = result.reason,
            frunkCommandRequested = result.frunkCommandRequested,
            frunkCommandSent = result.frunkCommandSent,
            frunkCommandAcknowledged = result.frunkCommandAcknowledged,
            frunkOpenObserved = result.frunkOpenObserved,
            frunkFreshParkVerified = result.frunkFreshParkVerified,
            coreStage = result.stage, coreOutcome = result.outcome,
        )
        // 종료 뒤에는 마지막으로 관측한 active 단계를 유지한다. 시간으로 추정하지 않는다.
        // 연결 정리는 실패 위치가 아니므로 종료 직전 정리 단계로 이전 실패 단계를 덮지 않는다.
        val next = core.copy(
            lastCoreStep = if (result.active && result.stage != TeslaKeyStage.CLEANING_UP) {
                core.activeCoreStep()
            } else {
                state.value.frunkVoice.lastCoreStep
            },
        )
        if (next == state.value.frunkVoice) return
        update { it.copy(frunkVoice = next) }
    }

    internal fun approachOwnerReason(request: TeslaApproachReadRequest, requireFresh: Boolean = true): String? {
        if (!ownsBleField(request.owner, request.fieldToken) || state.value.bleFieldTrialStopping ||
            !state.value.observing || !state.value.bleFieldConfig.authenticatedReadOnly ||
            !request.owner.ownsApproachRead(request)
        ) return "FIELD_OWNER_NOT_ARMED"
        if (!request.owner.approachScanPaused(request) ||
            !AndroidBleScanTransport.foregroundRunning(app)
        ) return "BLE_SCAN_FGS_NOT_RUNNING"
        if (now() >= request.deadline) return "DEADLINE_EXPIRED"
        if (request.device == null || request.sourceTimestampNanos <= 0) return "SCAN_DEVICE_UNAVAILABLE"
        if (requireFresh && (request.sourceElapsedMs > now() ||
                now() - request.sourceElapsedMs > BleFieldTrialPolicy.READ_START_FRESH_MS)
        ) return "SCAN_RESULT_STALE"
        if (singleAssociationId() != request.associationId) return "ASSOCIATION_CHANGED"
        vehicleVinController.readinessReason()?.let { return it }
        return fieldReadinessReason()
    }

    internal fun acquireTeslaApproachRead(request: TeslaApproachReadRequest): Long? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (approachOwnerReason(request) != null || teslaKeyReserved() || uwbSupportBlocked() ||
            policy.current != null || microphone != null || state.value.speechDiagnosticActive ||
            state.value.bleDiagnosticActive || state.value.automaticMicrophoneEnabled
        ) return null
        val vin = vehicleVinSnapshot() ?: return null
        val token = ++nextTeslaKeyToken
        teslaKeyToken = token
        teslaApproachRequest = request
        teslaApproachVin = vin
        update { it.copy(teslaKeyDiagnosticActive = true) }
        return token
    }

    internal fun startTeslaApproachRead(request: TeslaApproachReadRequest,
        finished: (TeslaApproachReadSummary, Boolean) -> Unit) {
        teslaKeyProbe.startApproach(request, finished)
    }

    internal fun cancelTeslaApproachRead(owner: ObservationService, token: String, attempt: Long, reason: String) {
        val request = teslaApproachRequest ?: return
        if (request.owner === owner && request.fieldToken == token && request.attempt == attempt) teslaKeyProbe.cancel(reason)
    }

    internal fun approachReadInFlight(owner: ObservationService, token: String, attempt: Long): Boolean =
        teslaApproachRequest?.let { it.owner === owner && it.fieldToken == token && it.attempt == attempt } == true

    internal fun acquireUwbSupport(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val s = state.value
        if (vehicleVinSaving() || teslaKeyReserved() || uwbSupportActive || uwbSupportCleanupFailed || !activityVisible ||
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
    private val fieldLog = FieldEventSink(fieldLogFile)
    private val detectionPreference = BleDetectionPreference(app, "${fieldMarkerName}_activation")
    private var restoreSuppressedByUserExit = false
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
                if (state.value.bluetooth) restoreBleDetection()
            }
            if (intent?.action == android.location.LocationManager.MODE_CHANGED_ACTION && state.value.locationEnabled) {
                restoreBleDetection()
            }
        }
    }

    init {
        vehicleVinController.restore()
        app.registerReceiver(readinessReceiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
            addAction(android.location.LocationManager.MODE_CHANGED_ACTION)
        }, Context.RECEIVER_EXPORTED)
        fieldLog.onStatusChanged = {
            // writer의 낡은 스냅샷 대신 Main에서 최신 건강 상태를 읽는다.
            if (Looper.myLooper() == Looper.getMainLooper()) fieldLogChanged()
            else handler.post { fieldLogChanged() }
        }
        fieldLog.start()
        update { it.copy(fieldLog = fieldLog.status()) }
        // core phase 전이는 사건 코드 없이도 일어난다. 관측 즉시 화면 상태로 옮겨 가짜 진행 없이 보여준다.
        scope.launch {
            teslaKeyState.collect { if (frunkVoiceTarget != null) syncFrunkVoiceResult() }
        }
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
        try {
            val exit = app.getSystemService(android.app.ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(app.packageName, 0, 1)?.firstOrNull()
            // 공개 종료 이력은 강제 종료와 다른 사용자 요청 종료를 세분하지 않는다.
            // 사용자 요청 종료 뒤에는 보수적으로 명시적 재시작을 기다린다.
            restoreSuppressedByUserExit = exit?.reason == android.app.ApplicationExitInfo.REASON_USER_REQUESTED
            update { it.copy(bleDetectionEnabled = detectionPreference.enabled(),
                previousExitReason = exit?.reason, previousExitWallMs = exit?.timestamp) }
            if (exit != null) event("PROCESS_EXIT_OBSERVED")
        } catch (_: Exception) { /* 이력 접근 실패는 감지 시작을 막지 않는다. */ }
        handler.post { restoreBleDetection() }
    }
    private fun fieldLogChanged() {
        update { it.copy(fieldLog = fieldLog.status()) }
        // 진단 기록의 실패는 표시하되 BLE 관찰의 수명과 분리한다.
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
        syncFrunkVoiceResult()
        if (code == "FRUNK_VOICE_CLEANUP_FAILED" ||
            code == "OBSERVE_STOP_FAILED_LOCAL_GATE_CLOSED" || code == "BLE_FIELD_WAKE_LOCK_RELEASE_FAILED" ||
            code == "SPEECH_SUPPORT_DESTROY_FAILED"
        ) {
            diagnosticCleanupUncertain = true
        }
        record(code, speechTrial)
    }

    internal fun recordBleTrial(code: String, summary: BleTrialSummary) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { recordBleTrial(code, summary) }; return }
        record(code, bleTrial = summary)
    }
    internal fun recordTeslaApproachRead(code: String, summary: TeslaApproachReadSummary) {
        check(Looper.myLooper() == Looper.getMainLooper())
        update { it.copy(teslaApproachRead = summary) }
        record(code, teslaApproachRead = summary)
    }

    /** 동의로 시작된 실차 probe의 유효 최종 결과만 호출한다. 원문·예외는 RAM 사건에도 남기지 않는다. */
    internal fun recordFrunkVoiceRecognition(id: Long, result: FrunkVoiceRecognitionResult) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (frunkVoiceLease?.sessionId != id || speechDiagnosticId != id) return
        record(FrunkVoiceRecognition.EVENT, frunkVoiceRecognition = result)
    }

    internal fun recordBleEvidence(evidence: BleEventEvidence) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { recordBleEvidence(evidence) }; return }
        if ((evidence.kind == BleEvidenceKind.SCAN_FAILED && evidence.scanFailureCode == BleSupplementalScanner.FAILURE_STOP) ||
            (evidence.kind == BleEvidenceKind.SCAN_STOPPED && evidence.accepted == false)
        ) {
            diagnosticCleanupUncertain = true
        }
        val scanStatusChanged = when (evidence.kind) {
            BleEvidenceKind.SCAN_RECOVERY_SCHEDULED -> {
                update { it.copy(bleFieldScanRecoveryCount = evidence.retryCount ?: it.bleFieldScanRecoveryCount,
                    bleFieldScanRecoveryAt = evidence.scheduledElapsedMs,
                    bleFieldScanRecoveryError = evidence.scanFailureCode) }
                true
            }
            BleEvidenceKind.SCAN_STARTED, BleEvidenceKind.SCAN_FAILED,
            BleEvidenceKind.SCAN_RECOVERY_EXHAUSTED -> {
                update { it.copy(bleFieldScanRecoveryAt = null) }
                true
            }
            BleEvidenceKind.SCAN_TIMER_DELAYED -> {
                update { it.copy(bleFieldScanTimerDelayMs = evidence.timerDelayMs) }
                false
            }
            else -> false
        }
        record("BLE_EVIDENCE", bleEvidence = evidence)
        if (scanStatusChanged && state.value.bleFieldTrialActive && !state.value.bleFieldTrialStopping) {
            observationService?.scanStatusChanged()
        }
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
        bleEvidence: BleEventEvidence? = null, teslaApproachRead: TeslaApproachReadSummary? = null,
        frunkVoiceRecognition: FrunkVoiceRecognitionResult? = null): String {
        val line = stamp(code)
        recordEvent(line)
        persist(code, speechTrial, bleTrial, bleEvidence, teslaApproachRead, frunkVoiceRecognition)
        return line
    }

    private fun persist(code: String, speechTrial: SpeechTrialSummary?, bleTrial: BleTrialSummary?,
        bleEvidence: BleEventEvidence?, teslaApproachRead: TeslaApproachReadSummary?,
        frunkVoiceRecognition: FrunkVoiceRecognitionResult?) {
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
                    teslaApproachRead = teslaApproachRead,
                    candidateDiagnostics = state.value.bleCandidateDiagnostics.takeIf { code == "BLE_CANDIDATE_DIAGNOSTICS" },
                    frunkVoiceRecognition = frunkVoiceRecognition,
                ),
            )
        } catch (_: Exception) {
            // 예상 밖 예외도 조용히 삼키지 않는다. 원문 없이 유실 집계로만 남긴다.
            fieldLog.recordDroppedLocally()
        }
    }

    private fun fieldSnapshot(): Map<String, Any?> {
        val s = state.value
        val frunk = teslaKeyState.value
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
            FieldStateKeys.BLE_FIELD_AUTHENTICATED_READ to s.bleFieldConfig.authenticatedReadOnly,
            FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER to
                (s.bleFieldConfig.scanFilterMode == BleScanFilterMode.VEHICLE_NAME),
            FieldStateKeys.BLE_FIELD_CANDIDATE_COUNT to s.bleFieldCandidateCount,
            FieldStateKeys.BLE_FIELD_SCAN_RUNNING to s.bleFieldScanRunning,
            FieldStateKeys.BLE_FIELD_SCAN_FAILURE to s.bleFieldScanFailure,
            FieldStateKeys.FRUNK_COMMAND_REQUESTED to frunk.frunkCommandRequested,
            FieldStateKeys.FRUNK_COMMAND_SENT to frunk.frunkCommandSent,
            FieldStateKeys.FRUNK_COMMAND_ACKNOWLEDGED to frunk.frunkCommandAcknowledged,
            FieldStateKeys.FRUNK_OPEN_OBSERVED to frunk.frunkOpenObserved,
            FieldStateKeys.FRUNK_LOCAL_CLOSED to (frunk.frunkCommandRequested && frunk.localClosed),
            FieldStateKeys.FRUNK_CLEANUP_FAILED to (frunk.frunkCommandRequested && frunk.stage == TeslaKeyStage.CLEANUP_FAILED),
        )
    }

    private fun stamp(code: String) = "${now()} ms · $code"

    private fun recordEvent(line: String) {
        update { it.copy(events = (listOf(line) + it.events).take(32)) }
    }

    internal fun acquireSpeechDiagnostic(cancel: () -> Unit): SessionPolicy.Session? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (vehicleVinSaving() || uwbSupportBlocked() || teslaKeyReserved() || fieldTrialReserved()) return null
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
        if (state.value.bleFieldConfig.observationOnly ||
            state.value.bleFieldConfig.scanFilterMode == BleScanFilterMode.VEHICLE_NAME
        ) return "FIELD_OWNER_NOT_ARMED"
        if (uwbSupportBlocked() || teslaKeyReserved()) return "LEASE_UNAVAILABLE"
        if (policy.current != null || microphone != null || state.value.speechDiagnosticActive || state.value.bleDiagnosticActive) return "LEASE_UNAVAILABLE"
        return fieldReadinessReason()
    }

    fun bleFieldTrialBlockedReason(config: BleFieldConfig = state.value.bleFieldConfig, requireVisible: Boolean = true): String? {
        if (diagnosticCleanupUncertain) return "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED"
        config.safetyBlockedReason()?.let { return it }
        vehicleVinController.readinessReason()?.let { return it }
        if (requireVisible && !activityVisible) return "VISIBLE_UI_REQUIRED"
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
        config.safetyBlockedReason()?.let { return it }
        if (config.authenticatedReadOnly) vehicleVinController.readinessReason()?.let { return it }
        if (config.supplementalScan && !granted(Manifest.permission.BLUETOOTH_SCAN)) return "BLUETOOTH_SCAN_PERMISSION_REQUIRED"
        if (config.supplementalScan && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return "BLE_LOCATION_PERMISSION_REQUIRED"
        if (config.supplementalScan && !state.value.locationEnabled) return "BLE_LOCATION_DISABLED"
        return observationReadinessReason()
    }

    fun startBleFieldTrial(config: BleFieldConfig = BleFieldConfig.BASELINE, restoring: Boolean = false) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (restoring && config != BleFieldConfig.VEHICLE_NAME_DETECTION) {
            rejectBleField("BLE_NAME_DETECTION_OPTIONS_INVALID")
            return
        }
        config.safetyBlockedReason()?.let { rejectBleField(it); return }
        val vin = vehicleVinSnapshot() ?: run {
            rejectBleField(vehicleVinController.readinessReason() ?: "VIN_NOT_REGISTERED")
            return
        }
        refresh()
        bleFieldTrialBlockedReason(config, requireVisible = !restoring)?.let { rejectBleField(it); return }
        if (!restoring) restoreSuppressedByUserExit = false
        val advertisedName = if (config.scanFilterMode == BleScanFilterMode.VEHICLE_NAME) {
            TeslaBleAdvertisement.localName(vin) ?: run { rejectBleField("VIN_FORMAT_INVALID"); return }
        } else null
        val token = UUID.randomUUID().toString()
        if (!observationPolicy.request(token)) { rejectBleField("LEASE_UNAVAILABLE"); return }
        observationMode = ObservationService.Mode.BLE_FIELD
        pendingBleAdvertisedName.clear()
        if (advertisedName != null) pendingBleAdvertisedName.arm(token, advertisedName)
        // 구 실행의 비동기 로그 완료가 새 실행 marker를 지우지 못하게 봉인한다.
        clearFieldMarkerWhenLogged = false
        trialId = UUID.randomUUID().toString()
        resetPresenceBaseline()
        bleProbeState.value = BleProbeState()
        update { it.copy(bleFieldTrialStarting = true, bleFieldTrialStopReason = null,
            bleFieldConfig = config, teslaApproachRead = null, bleCandidateDiagnostics = null,
            bleFieldCandidateCount = 0, bleFieldScanRunning = false, bleFieldScanFailure = null,
            bleFieldScanRecoveryCount = 0, bleFieldScanRecoveryAt = null,
            bleFieldScanRecoveryError = null, bleFieldScanTimerDelayMs = null,
            bleFieldTrialWaitingForDeparture = false, bleFieldTrialAttemptCount = 0, bleFieldTrialCompletedCount = 0,
            observationStartPending = true, automaticMicrophoneEnabled = false) }
        recordBleTrial("BLE_FIELD_START_REQUESTED", bleTrialSummary(0, BleProbeState(), false))
        if (!observationPolicy.accepts(token) || !state.value.bleFieldTrialStarting) return
        val marked = try { fieldMarker.edit().putBoolean("unclosed", true).commit() } catch (_: Exception) { false }
        if (!marked) { markerAvailable = false; stopBleFieldTrial("FIELD_MARKER_WRITE_FAILED"); return }
        if (config.scanFilterMode == BleScanFilterMode.VEHICLE_NAME && !setBleDetectionPreference(true)) {
            stopBleFieldTrial("BLE_DETECTION_PREFERENCE_FAILED")
            return
        }
        try {
            val intent = Intent(app, ObservationService::class.java).setAction(ObservationService.ACTION_START)
                .putExtra(ObservationService.REQUEST_ID, token).putExtra(ObservationService.MODE, ObservationService.Mode.BLE_FIELD.name)
            if (config.supplementalScan) {
                org.altbeacon.beacon.BeaconManager.getInstanceForApplication(app).setBackgroundMode(!activityVisible)
                bindBleObservation(intent, token)
            } else app.startForegroundService(intent)
            handler.postDelayed({
                if (observationPolicy.pendingRequest == token) stopBleFieldTrial("OBSERVATION_FGS_START_TIMEOUT")
            }, 5_000)
        } catch (_: SecurityException) { stopBleFieldTrial("OBSERVATION_FGS_SECURITY_DENIED") }
        catch (_: android.app.ForegroundServiceStartNotAllowedException) { stopBleFieldTrial("OBSERVATION_FGS_BACKGROUND_START_DENIED") }
        catch (_: Exception) { stopBleFieldTrial("OBSERVATION_FGS_START_FAILED") }
    }

    private fun bindBleObservation(intent: Intent, token: String) {
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                if (observationBinding?.second !== this || !acceptsObservationRequest(token, ObservationService.Mode.BLE_FIELD)) return
                val local = binder as? ObservationService.LocalBinder
                if (local == null) stopBleFieldTrial("OBSERVATION_SERVICE_DESTROYED")
                else local.start(intent)
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) {
                if (observationBinding?.second === this) stopObservation("OBSERVATION_SERVICE_DESTROYED", token)
            }
            override fun onBindingDied(name: android.content.ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: android.content.ComponentName) = onServiceDisconnected(name)
        }
        observationBinding = token to connection
        if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            observationBinding = null
            stopBleFieldTrial("OBSERVATION_FGS_START_FAILED")
        }
    }

    private fun releaseBleObservationBinding(token: String) {
        val binding = observationBinding ?: return
        if (binding.first != token) return
        observationBinding = null
        try { app.unbindService(binding.second) }
        catch (_: IllegalArgumentException) { event("OBSERVE_STOP_FAILED_LOCAL_GATE_CLOSED") }
    }

    internal fun restoreBleDetection() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!detectionPreference.enabled() || fieldTrialReserved() || state.value.enabled ||
            state.value.observationStartPending || state.value.observationServiceRunning) return
        if (restoreSuppressedByUserExit) {
            update { it.copy(bleFieldTrialStopReason = "BLE_DETECTION_USER_STOP_REQUIRES_START") }
            return
        }
        // 복원 직후 과거 후보·GATT·마이크·명령을 재생하지 않는다.
        refresh()
        event("BLE_DETECTION_RESTORE_REQUESTED")
        val config = BleFieldConfig.VEHICLE_NAME_DETECTION
        val reason = bleFieldTrialBlockedReason(config, requireVisible = false)
        if (reason != null) {
            update { it.copy(bleFieldTrialStopReason = reason) }
            event("BLE_DETECTION_RESTORE_BLOCKED")
            return
        }
        startBleFieldTrial(config, restoring = true)
    }

    private fun setBleDetectionPreference(enabled: Boolean): Boolean {
        val saved = try { detectionPreference.setEnabled(enabled) } catch (_: Exception) { false }
        if (saved) update { it.copy(bleDetectionEnabled = enabled) }
        else event("BLE_DETECTION_PREFERENCE_FAILED")
        return saved
    }

    private fun rejectBleField(reason: String) {
        if (!state.value.bleFieldTrialActive && !state.value.bleFieldTrialStarting && !state.value.bleFieldTrialStopping) {
            update { it.copy(bleFieldTrialStopReason = reason) }
        }
        recordBleTrial("BLE_FIELD_START_REJECTED", bleTrialSummary(0, BleProbeState(status = BleProbeStatus.BLOCKED, reason = reason), false))
    }

    /** Intent에는 request token만 둔다. 실제 서비스·현재 association이 소유한 요청만 한 번 소비한다. */
    internal fun consumeBleFieldAdvertisedName(owner: ObservationService, token: String): String? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!ownsBleField(owner, token) || state.value.bleFieldTrialStopping ||
            !state.value.observing || state.value.bleFieldConfig.safetyBlockedReason() != null ||
            state.value.bleFieldConfig.scanFilterMode != BleScanFilterMode.VEHICLE_NAME
        ) return null
        val id = owner.observedAssociationId
        return pendingBleAdvertisedName.consume(token, ownsAssociation = id != null && id == singleAssociationId())
    }

    internal fun discardBleFieldAdvertisedName(token: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        pendingBleAdvertisedName.clear(token)
    }

    fun stopBleFieldTrial(reason: String = "USER_STOP") {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (reason == "USER_STOP" || reason == "BLE_FIELD_NOTIFICATION_STOP" || reason == "DISABLED") {
            persistPreference(false)
            if (!setBleDetectionPreference(false)) {
                update { it.copy(bleFieldTrialStopReason = "BLE_DETECTION_PREFERENCE_FAILED") }
            }
        }
        if (observationMode != ObservationService.Mode.BLE_FIELD || state.value.bleFieldTrialStopping ||
            (!state.value.bleFieldTrialStarting && !state.value.bleFieldTrialActive)
        ) return
        pendingBleAdvertisedName.clear()
        update { it.copy(bleFieldTrialStopping = true, bleFieldTrialStarting = false, observing = false,
            bleFieldTrialWaitingForDeparture = false, bleFieldTrialStopReason = reason, stopReason = reason,
            bleFieldScanRecoveryAt = null) }
        event("BLE_FIELD_STOP_REQUESTED")
        val owner = observationService
        if (owner != null) owner.stopBleField(reason)
        else finishBleField(null, observationPolicy.pendingRequest ?: return)
    }

    internal fun finishBleField(owner: ObservationService?, token: String, cleanupFailed: Boolean = false) {
        if (observationMode != ObservationService.Mode.BLE_FIELD ||
            (owner != null && !ownsObservation(owner, token)) || !observationPolicy.finish(token)
        ) return
        pendingBleAdvertisedName.clear(token)
        observationService = null
        update { it.copy(enabled = false, observing = false, present = false, observationStartPending = false,
            observationServiceRunning = false, bleFieldTrialActive = false, bleFieldTrialStarting = false,
            bleFieldTrialStopping = false, bleFieldTrialWaitingForDeparture = false, automaticMicrophoneEnabled = false,
            bleFieldScanRunning = false, bleFieldScanRecoveryAt = null,
            bleFieldTrialStopReason = if (cleanupFailed) "LOCAL_CLOSE_FAILED_RESTART_REQUIRED" else it.bleFieldTrialStopReason) }
        resetPresenceBaseline()
        event("BLE_FIELD_STOPPED")
        // 최종 회차/중지 행의 fd.sync까지 끝나야 정상 종료 marker를 남긴다. FGS 정리를 지연시키지는 않는다.
        clearFieldMarkerWhenLogged = !cleanupFailed && !diagnosticCleanupUncertain
        clearLoggedFieldMarker()
        trialId = null
        releaseBleObservationBinding(token)
    }

    fun refresh() {
        val ids = try {
            if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) cdm?.myAssociations?.filterNot { it.isSelfManaged }?.map { it.id }.orEmpty() else emptyList()
        } catch (_: Exception) { event("ASSOCIATION_READ_FAILED"); emptyList() }
        associationIds = ids
        val bt = try { granted(Manifest.permission.BLUETOOTH_CONNECT) && app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (_: SecurityException) { false }
        update { it.copy(associationCount = ids.size, bluetooth = bt, assistant = assistantActive(), microphonePermission = granted(Manifest.permission.RECORD_AUDIO), bluetoothPermission = granted(Manifest.permission.BLUETOOTH_CONNECT), bluetoothScanPermission = granted(Manifest.permission.BLUETOOTH_SCAN), notificationPermission = granted(Manifest.permission.POST_NOTIFICATIONS),
            locationPermission = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            locationEnabled = app.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true) }
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
        if (enabled && vehicleVinSaving()) { event("VIN_SAVING"); return }
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
        if (vehicleVinSaving()) return "VIN_SAVING"
        if (s.speechDiagnosticActive || s.bleDiagnosticActive || policy.current?.diagnostic == true) return "DIAGNOSTIC_OBSERVATION_BLOCKED"
        return observationReadinessReason()
    }

    private fun observationReadinessReason(): String? {
        if (diagnosticCleanupUncertain) return "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED"
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
        if (vehicleVinSaving() || diagnosticCleanupUncertain) return false
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
        if (vehicleVinSaving()) { event("VIN_SAVING"); return }
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
        if (teslaKeyReserved() && teslaApproachRequest == null) { this.event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
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
        if (teslaKeyReserved()) { this.event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
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
        if (vehicleVinSaving() || uwbSupportBlocked() || teslaKeyReserved()) return false
        refresh()
        val s = state.value
        return !fieldTrialReserved() && !s.speechDiagnosticActive && !s.bleDiagnosticActive && policy.current?.diagnostic != true &&
            s.enabled && s.observationServiceRunning &&
            s.automaticMicrophoneEnabled && s.observing &&
            s.bluetooth && s.assistant && s.microphonePermission
    }

    fun manualStart(context: Context) {
        if (vehicleVinSaving()) { event("VIN_SAVING"); return }
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
        if (teslaKeyReserved() && teslaApproachRequest == null) { cancelTeslaKey(reason); return }
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
        diagnosticCleanupUncertain || it.bleFieldTrialActive || it.bleFieldTrialStarting || it.bleFieldTrialStopping
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
