package com.heytesla.app

import com.heytesla.app.app.ObservationPolicy
import com.heytesla.app.app.SessionPolicy
import com.heytesla.app.diagnostics.BleCandidateDiagnostics
import com.heytesla.app.diagnostics.BleTrialSummary
import com.heytesla.app.diagnostics.SpeechTrialSummary
import com.heytesla.app.logging.FIELD_LOG_RELATIVE_PATH
import com.heytesla.app.logging.FieldEventSink
import com.heytesla.app.logging.FieldLogStatus
import com.heytesla.app.logging.FieldRecord
import com.heytesla.app.logging.FieldStateKeys
import com.heytesla.app.ui.vehicleNavigationCollectionDeadline
import com.heytesla.app.ui.vehicleNavigationShouldCollect
import com.heytesla.app.vehicle.ble.BleProbeState
import com.heytesla.app.vehicle.ble.BleProbeStatus
import com.heytesla.app.vehicle.detection.BleEventEvidence
import com.heytesla.app.vehicle.detection.BleEvidenceGate
import com.heytesla.app.vehicle.detection.BleEvidenceKind
import com.heytesla.app.vehicle.detection.BleEvidenceSignal
import com.heytesla.app.vehicle.detection.BleFieldConfig
import com.heytesla.app.vehicle.detection.BlePendingAdvertisedName
import com.heytesla.app.vehicle.detection.BleSupplementalScanner
import com.heytesla.app.vehicle.detection.TeslaBleAdvertisement
import com.heytesla.app.vehicle.frunk.FrunkVoicePolicy
import com.heytesla.app.vehicle.frunk.FrunkVoiceRecognition
import com.heytesla.app.vehicle.frunk.FrunkVoiceRecognitionResult
import com.heytesla.app.vehicle.frunk.FrunkVoiceStage
import com.heytesla.app.vehicle.frunk.FrunkVoiceState
import com.heytesla.app.vehicle.frunk.activeCoreStep
import com.heytesla.app.vehicle.key.TeslaBleKeyProbe
import com.heytesla.app.vehicle.key.TeslaBleKeyStore
import com.heytesla.app.vehicle.key.TeslaKeyProbeState
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.key.VehicleVinController
import com.heytesla.app.vehicle.key.VehicleVinState
import com.heytesla.app.vehicle.key.VehicleVinStatus
import com.heytesla.app.vehicle.key.VehicleVinStore
import com.heytesla.app.vehicle.navigation.AndroidPhoneNavigationSource
import com.heytesla.app.vehicle.navigation.PhoneNavigationHeading
import com.heytesla.app.vehicle.navigation.PhoneNavigationLocation
import com.heytesla.app.vehicle.navigation.PhoneNavigationSource
import com.heytesla.app.vehicle.navigation.PhoneNavigationUnavailable
import com.heytesla.app.vehicle.navigation.VehicleCoordinateObservation
import com.heytesla.app.vehicle.navigation.VehicleNavigationPresentation
import com.heytesla.app.vehicle.navigation.VehicleNavigationStatus
import com.heytesla.app.vehicle.navigation.VehicleNavigationTracker
import com.heytesla.app.vehicle.navigation.closeVehicleNavigationResources
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.heytesla.app.vehicle.protocol.canonicalFields
import com.heytesla.app.vehicle.uwb.TeslaServiceUwbRequest
import com.heytesla.app.vehicle.uwb.TeslaUwbProbeState
import com.heytesla.app.vehicle.uwb.TeslaUwbStage
import com.heytesla.app.vehicle.uwb.serviceUwbReadinessReason

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

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
    val associationCount: Int = 0,
    val observing: Boolean = false,
    val bluetooth: Boolean = false,
    val assistant: Boolean = false,
    val microphonePermission: Boolean = false,
    val bluetoothPermission: Boolean = false,
    val bluetoothScanPermission: Boolean = false,
    val locationPermission: Boolean = false,
    val locationEnabled: Boolean = false,
    val backgroundLocationPermission: Boolean = false,
    val bleDetectionEnabled: Boolean = false,
    val previousExitReason: Int? = null,
    val previousExitWallMs: Long? = null,
    val notificationPermission: Boolean = false,
    val present: Boolean = false,
    val session: String = "대기",
    val sessionId: Long? = null,
    val deadline: Long? = null,
    val samples: Long = 0,
    val rms: Double = 0.0,
    val silenced: Boolean? = null,
    val stopReason: String = "PROCESS_START_OFF",
    val speechDiagnosticActive: Boolean = false,
    val bleFieldTrialActive: Boolean = false,
    val bleFieldTrialStarting: Boolean = false,
    val bleFieldTrialStopping: Boolean = false,
    val bleFieldTrialWaitingForDeparture: Boolean = false,
    val bleFieldTrialAttemptCount: Int = 0,
    val bleFieldTrialCompletedCount: Int = 0,
    val bleFieldTrialStopReason: String? = null,
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

    /** 계측은 생산 VIN·차량 키·로그·실행 marker·활성화 설정을 변경하지 않는 독립 저장 경로를 사용한다. */
    internal constructor(
        app: DiagnosticApp,
        vehicleVinStore: VehicleVinStore,
        teslaKeyStore: TeslaBleKeyStore,
        fieldMarkerName: String = "vehicle_vin_runtime_test_${UUID.randomUUID()}",
    ) : this(
        app, vehicleVinStore, teslaKeyStore,
        File(app.noBackupFilesDir, "vehicle-vin-tests/runtime-${UUID.randomUUID()}/events-v2.jsonl"),
        fieldMarkerName,
    )
    val handler = Handler(Looper.getMainLooper())
    val policy = SessionPolicy()
    private val mutable = MutableStateFlow(DiagnosticState())
    val state = mutable.asStateFlow()
    internal val bleProbeState = MutableStateFlow(BleProbeState())
    internal val teslaKeyState = MutableStateFlow(TeslaKeyProbeState())
    internal val teslaUwbState = MutableStateFlow(TeslaUwbProbeState())
    private val teslaKeyProbe by lazy { TeslaBleKeyProbe(app, this, teslaKeyState, teslaKeyStore) }
    private var teslaKeyToken: Long? = null
    private var nextTeslaKeyToken = 0L
    private var teslaServiceUwbRequest: TeslaServiceUwbRequest? = null
    private var teslaServiceUwbVin: String? = null
    private val navigationTracker = VehicleNavigationTracker()
    private val mutableNavigation = MutableStateFlow(VehicleNavigationPresentation(VehicleNavigationStatus.OFF))
    internal val vehicleNavigation = mutableNavigation.asStateFlow()
    private var navigationOwner: Pair<ObservationService, String>? = null
    private var navigationVin: String? = null
    private var navigationStartToken: String? = null
    private var navigationSource: PhoneNavigationSource? = null
    private var navigationForeground: CoordinateForegroundLease? = null
    private var navigationForegroundArmed = false
    private var navigationAttempt: TeslaServiceUwbRequest? = null
    private var navigationAttemptRejected = false
    private var navigationVehicleFixDeadline: Long? = null
    private var navigationSourceActive = false
    private var navigationStarting = false
    private var navigationClosing = false
    private var navigationCleanupFailed = false
    private var navigationSourceFailure: PhoneNavigationUnavailable? = null
    private val navigationCloseCallbacks = ArrayList<(Boolean) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val observationPolicy = ObservationPolicy()
    private var observationService: ObservationService? = null
    private var observationBinding: Pair<String, android.content.ServiceConnection>? = null
    private val pendingBleAdvertisedName = BlePendingAdvertisedName()
    private val fieldMarker = app.getSharedPreferences(fieldMarkerName, Context.MODE_PRIVATE)
    private var markerAvailable = true
    private var clearFieldMarkerWhenLogged = false
    private var associationIds: List<Int> = emptyList()
    var assistant: AssistantService? = null
    var microphone: MicrophoneService? = null
    var activityVisible = false
    private var speechDiagnosticCancel: (() -> Unit)? = null
    private var speechDiagnosticId: Long? = null
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
        val restored = state.value.vehicleVin.status == VehicleVinStatus.LOADING && value.status != VehicleVinStatus.LOADING
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
            speechDiagnosticId != null || observationService != null ||
            observationPolicy.pendingRequest != null || observationPolicy.runningRequest != null ||
            fieldTrialReserved() || s.sessionId != null || s.speechDiagnosticActive ||
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
            s.speechDiagnosticActive || s.enabled || s.observing ||
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

    internal fun teslaKeyReadinessReason(token: Long,
        requireFresh: Boolean = true, serviceUwb: TeslaServiceUwbRequest? = null): String? {
        if (teslaKeyToken != token) return "LEASE_LOST"
        val serviceOwned = teslaServiceUwbRequest
        if (serviceOwned != null) {
            if (serviceOwned !== serviceUwb) return "LEASE_LOST"
            serviceUwbOwnerReason(serviceOwned, requireFresh)?.let { return it }
            if (vehicleVinSnapshot() != teslaServiceUwbVin) return "UWB_VIN_SNAPSHOT_CHANGED"
            val s = state.value
            if (uwbSupportBlocked() || policy.current != null || microphone != null ||
                s.speechDiagnosticActive
            ) return "DIAGNOSTIC_EXCLUSIVITY_LOST"
            return null
        }
        if (serviceUwb != null) return "LEASE_LOST"
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
            s.speechDiagnosticActive || s.enabled || s.observing ||
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
        val releasedNavigationAttempt = teslaServiceUwbRequest
        teslaServiceUwbRequest = null
        teslaServiceUwbVin = null
        update { it.copy(teslaKeyDiagnosticActive = false) }
        releasedNavigationAttempt?.let(::navigationAttemptFinished)
    }

    internal fun retainTeslaKey(token: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (teslaKeyToken == token) {
            update { it.copy(teslaKeyCleanupFailed = true) }
            if (teslaServiceUwbRequest != null) sealVehicleNavigation()
        }
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

    internal fun uwbRangingBlockedReason(): String? {
        teslaKeyBlockedReason()?.let { return it }
        // 복원 대기/권한 제한으로 실행만 멈춘 ON도 사용자 OFF로 간주하지 않는다.
        if (state.value.bleDetectionEnabled) return "LEASE_UNAVAILABLE"
        if (app.checkSelfPermission(Manifest.permission.RANGING) != PackageManager.PERMISSION_GRANTED) {
            return "RANGING_PERMISSION_REQUIRED"
        }
        return null
    }

    internal fun startUwbRanging() {
        check(Looper.myLooper() == Looper.getMainLooper())
        refresh()
        val reason = uwbRangingBlockedReason()
        if (reason != null) {
            if (!teslaKeyReserved()) teslaUwbState.value = TeslaUwbProbeState(
                stage = TeslaUwbStage.BLOCKED,
                rangingPermission = app.checkSelfPermission(Manifest.permission.RANGING) == PackageManager.PERMISSION_GRANTED,
                reason = reason,
            )
            event("TESLA_UWB_BLOCKED")
            return
        }
        val vin = vehicleVinSnapshot() ?: return
        teslaKeyProbe.startUwb(vin)
    }
    /** 화면/HOME/잠금 취소 예외는 정확한 서비스 소유 작업에만 적용한다. */
    internal fun cancelTeslaKey(reason: String = "USER_STOP") {
        if (teslaKeyReserved() && teslaServiceUwbRequest == null) teslaKeyProbe.cancel(reason)
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
    internal fun finishFrunkVoiceAudio(id: Long, inputReleased: Boolean, cleanupConfirmed: Boolean?): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val lease = frunkVoiceLease ?: return false
        if (lease.sessionId != id) return false
        if (!inputReleased || cleanupConfirmed != true) {
            lease.gate.cancel("FRUNK_VOICE_CLEANUP_FAILED")
            update { it.copy(frunkVoice = it.frunkVoice.copy(stage = FrunkVoiceStage.CLEANUP_FAILED,
                active = false, inputReleased = inputReleased, reason = "FRUNK_VOICE_CLEANUP_FAILED")) }
            // speech lease를 반환하지 않는다. 이 최종 경계만 호출돼도 전역 재입장 차단을 남긴다.
            event("FRUNK_VOICE_CLEANUP_FAILED")
            return false
        }
        if (frunkVoiceReadinessReason(id) != null ||
            !lease.gate.takeForDispatch(now(), inputReleased, cleanupConfirmed)
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

    internal fun serviceUwbOwnerReason(request: TeslaServiceUwbRequest, requireFresh: Boolean = true): String? {
        val s = state.value
        return serviceUwbReadinessReason(
            ownerArmed = { s.bleDetectionEnabled && ownsBleField(request.owner, request.fieldToken) &&
                !s.bleFieldTrialStopping && s.observing &&
                request.owner.ownsServiceUwb(request) },
            scanReady = { request.owner.serviceUwbScanPaused(request) && AndroidBleScanTransport.foregroundRunning(app) },
            initialBudgetExpired = { !request.ranging && now() >= request.deadline },
            deviceAvailable = { request.device != null }, sourceTimestampNanos = request.sourceTimestampNanos,
            now = { now() }, requireFresh = requireFresh,
            rangingPermission = { granted(Manifest.permission.RANGING) },
            associationMatches = { singleAssociationId() == request.associationId },
            targetReason = { vehicleVinController.readinessReason() },
            fieldReason = { fieldReadinessReason() },
        )
    }

    private fun navigationOwnerCurrent(owner: ObservationService, token: String): Boolean {
        val s = state.value
        return ownsBleField(owner, token) && s.bleDetectionEnabled &&
            s.observing && !s.bleFieldTrialStopping && !s.teslaKeyCleanupFailed && !diagnosticCleanupUncertain &&
            navigationVin == vehicleVinSnapshot()
    }

    private fun navigationStartAllowed(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) && state.value.locationEnabled &&
            (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ||
                (activityVisible && app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false))

    /** Permission returns never call this. It neither enables nor restores a service. */
    fun resumeVehicleNavigation() {
        check(Looper.myLooper() == Looper.getMainLooper())
        refresh()
        val owner = observationService ?: return
        val token = observationPolicy.runningRequest ?: return
        if (!activityVisible || !ownsBleField(owner, token) || !state.value.bleDetectionEnabled ||
            state.value.bleFieldTrialStopping ||
            navigationCleanupFailed || navigationClosing || navigationForegroundArmed || navigationStarting
        ) return
        navigationStartToken = token
        coordinateReadinessTick(owner, token)
    }

    /** The existing one-second service readiness timer also expires display-only input. */
    internal fun coordinateReadinessTick(owner: ObservationService, token: String) {
        if (!ownsBleField(owner, token)) return
        if (navigationOwner == null) {
            navigationOwner = owner to token
            navigationVin = vehicleVinSnapshot()
            navigationTracker.clear()
        }
        if (navigationOwner?.first !== owner || navigationOwner?.second != token) return
        if (!navigationOwnerCurrent(owner, token)) {
            closeVehicleNavigation(owner, token) { }
            return
        }
        if (navigationSourceActive) {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) || !state.value.locationEnabled ||
                !AndroidBleScanTransport.foregroundRunning(app)
            ) {
                sealVehicleNavigation()
                stopBleFieldTrial("BLE_SCAN_FGS_NOT_RUNNING")
                return
            }
            val projected = projectVehicleNavigation()
            if (projected != null && !vehicleNavigationShouldCollect(
                operationActive = navigationAttempt?.let { teslaServiceUwbRequest === it } == true,
                readRejected = navigationAttemptRejected,
                vehicleFixDeadlineElapsedMs = navigationVehicleFixDeadline,
                presentationStatus = projected.status, nowElapsedMs = now(),
            )) {
                navigationAttemptRejected = true
                stopNavigationPhoneSource(VehicleNavigationPresentation(
                    if (projected.status == VehicleNavigationStatus.CLOCK_INVALID) VehicleNavigationStatus.CLOCK_INVALID
                    else if (navigationVehicleFixDeadline != null) VehicleNavigationStatus.VEHICLE_LOCATION_STALE
                    else VehicleNavigationStatus.VEHICLE_LOCATION_UNAVAILABLE))
            }
            return
        }
        if (navigationStarting || navigationClosing || navigationCleanupFailed || navigationForegroundArmed) return
        if (navigationStartToken != token) {
            if (mutableNavigation.value.status == VehicleNavigationStatus.OFF) {
                mutableNavigation.value = VehicleNavigationPresentation(VehicleNavigationStatus.START_REQUIRED)
            }
            return
        }
        if (!navigationStartAllowed()) {
            navigationStartToken = null
            mutableNavigation.value = VehicleNavigationPresentation(when {
                !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> VehicleNavigationStatus.LOCATION_PERMISSION_REQUIRED
                !state.value.locationEnabled -> VehicleNavigationStatus.LOCATION_DISABLED
                else -> VehicleNavigationStatus.START_REQUIRED
            })
            return
        }
        if (!AndroidBleScanTransport.foregroundRunning(app)) return
        navigationStartToken = null
        navigationStarting = true
        mutableNavigation.value = VehicleNavigationPresentation(VehicleNavigationStatus.STARTING)
        val lease = CoordinateForegroundLease(app, token, handler) {
            if (navigationOwner?.first === owner && navigationOwner?.second == token) {
                sealVehicleNavigation()
                stopBleFieldTrial("BLE_SCAN_FGS_NOT_RUNNING")
            }
        }
        navigationForeground = lease
        lease.start(allowed = { navigationOwnerCurrent(owner, token) && navigationStartAllowed() &&
            navigationForeground === lease && navigationStarting }) { success ->
            if (navigationForeground !== lease || navigationClosing) return@start
            navigationStarting = false
            if (!success || !navigationOwnerCurrent(owner, token) ||
                !granted(Manifest.permission.ACCESS_FINE_LOCATION) || !state.value.locationEnabled ||
                !AndroidBleScanTransport.foregroundRunning(app)
            ) {
                closeVehicleNavigation(owner, token) { clean ->
                    val current = state.value
                    if (clean && ownsBleField(owner, token) && current.bleDetectionEnabled &&
                        current.observing && !current.bleFieldTrialStopping
                    ) mutableNavigation.value = VehicleNavigationPresentation(VehicleNavigationStatus.FOREGROUND_FAILED)
                }
                return@start
            }
            navigationForegroundArmed = true
            mutableNavigation.value = VehicleNavigationPresentation(VehicleNavigationStatus.WAITING_FOR_VEHICLE_LOCATION)
            navigationAttempt?.let(::startNavigationPhoneSource)
        }
    }

    /** Exact candidate admission owns collection; merely arming a location FGS never registers sensors. */
    private fun navigationAttemptStarted(request: TeslaServiceUwbRequest) {
        navigationAttempt = request
        navigationAttemptRejected = false
        navigationVehicleFixDeadline = null
        if (!stopNavigationPhoneSource(VehicleNavigationPresentation(
            if (navigationForegroundArmed) VehicleNavigationStatus.WAITING_FOR_VEHICLE_LOCATION
            else mutableNavigation.value.status))) return
        startNavigationPhoneSource(request)
    }

    private fun navigationAttemptFinished(request: TeslaServiceUwbRequest) {
        if (navigationAttempt !== request) return
        navigationAttempt = null
        if (!navigationSourceActive) return
        val projected = projectVehicleNavigation() ?: return
        if (!vehicleNavigationShouldCollect(false, navigationAttemptRejected,
                navigationVehicleFixDeadline, projected.status, now())) {
            stopNavigationPhoneSource(VehicleNavigationPresentation(when {
                projected.status == VehicleNavigationStatus.CLOCK_INVALID -> VehicleNavigationStatus.CLOCK_INVALID
                navigationVehicleFixDeadline == null -> VehicleNavigationStatus.VEHICLE_LOCATION_UNAVAILABLE
                else -> VehicleNavigationStatus.VEHICLE_LOCATION_STALE
            }))
        }
    }

    private fun startNavigationPhoneSource(request: TeslaServiceUwbRequest) {
        val owner = request.owner
        val token = request.fieldToken
        if (!navigationForegroundArmed || navigationAttempt !== request || navigationAttemptRejected ||
            teslaServiceUwbRequest !== request || navigationSourceActive || navigationSource != null ||
            navigationClosing || navigationCleanupFailed || !navigationOwnerCurrent(owner, token) ||
            !granted(Manifest.permission.ACCESS_FINE_LOCATION) || !state.value.locationEnabled ||
            !AndroidBleScanTransport.foregroundRunning(app)
        ) return
        val source = AndroidPhoneNavigationSource(app, ::now)
        navigationSource = source
        navigationSourceActive = true
        navigationSourceFailure = null
        try {
            source.start(object : PhoneNavigationSource.Listener {
                private fun current() = navigationSource === source && navigationSourceActive &&
                    navigationOwnerCurrent(owner, token)
                override fun onLocation(value: PhoneNavigationLocation) {
                    if (!current()) return
                    navigationTracker.acceptPhoneLocation(value)
                    projectVehicleNavigation()
                }
                override fun onHeading(value: PhoneNavigationHeading) {
                    if (!current()) return
                    navigationTracker.acceptPhoneHeading(value)
                    if (navigationSourceFailure == PhoneNavigationUnavailable.HEADING_UNSUPPORTED ||
                        navigationSourceFailure == PhoneNavigationUnavailable.HEADING_UNRELIABLE
                    ) navigationSourceFailure = null
                    projectVehicleNavigation()
                }
                override fun onUnavailable(reason: PhoneNavigationUnavailable) {
                    if (!current()) return
                    navigationSourceFailure = reason
                    when (reason) {
                        PhoneNavigationUnavailable.HEADING_UNSUPPORTED, PhoneNavigationUnavailable.HEADING_UNRELIABLE ->
                            navigationTracker.invalidatePhoneHeading()
                        else -> {
                            navigationTracker.clear()
                            navigationAttemptRejected = true
                            navigationSourceActive = false
                            val status = when (reason) {
                                PhoneNavigationUnavailable.LOCATION_PERMISSION_REQUIRED -> VehicleNavigationStatus.LOCATION_PERMISSION_REQUIRED
                                PhoneNavigationUnavailable.LOCATION_DISABLED -> VehicleNavigationStatus.LOCATION_DISABLED
                                else -> VehicleNavigationStatus.LOCATION_UNAVAILABLE
                            }
                            mutableNavigation.value = VehicleNavigationPresentation(status)
                            // Seal synchronously; close after start() has finished any partial registration.
                            handler.post {
                                if (navigationSource === source) stopNavigationPhoneSource(VehicleNavigationPresentation(status))
                            }
                        }
                    }
                    projectVehicleNavigation()
                    if (reason == PhoneNavigationUnavailable.LOCATION_PERMISSION_REQUIRED ||
                        reason == PhoneNavigationUnavailable.LOCATION_DISABLED) refresh()
                }
            })
            projectVehicleNavigation()
        } catch (failure: Exception) {
            navigationAttemptRejected = true
            if (failure.message == "PHONE_NAVIGATION_CLEANUP_FAILED") failVehicleNavigationCleanup()
            stopNavigationPhoneSource(VehicleNavigationPresentation(VehicleNavigationStatus.LOCATION_UNAVAILABLE))
        }
    }

    /** Keep the legitimate FGS armed for the next approach, but release all GPS/sensor IO and raw RAM. */
    private fun stopNavigationPhoneSource(presentation: VehicleNavigationPresentation): Boolean {
        navigationSourceActive = false
        navigationTracker.clear()
        navigationSourceFailure = null
        navigationVehicleFixDeadline = null
        mutableNavigation.value = if (navigationCleanupFailed) VehicleNavigationPresentation(VehicleNavigationStatus.CLEANUP_FAILED)
            else presentation
        return try {
            navigationSource?.close()
            navigationSource = null
            !navigationCleanupFailed
        } catch (_: Exception) {
            failVehicleNavigationCleanup()
            false
        }
    }

    internal fun serviceCoordinatesWanted(request: TeslaServiceUwbRequest): Boolean =
        navigationForegroundArmed && navigationAttempt === request && !navigationAttemptRejected &&
            navigationSourceActive && !navigationClosing && !navigationCleanupFailed &&
            navigationOwner?.first === request.owner && navigationOwner?.second == request.fieldToken &&
            navigationOwnerCurrent(request.owner, request.fieldToken) && teslaServiceUwbRequest === request &&
            teslaServiceUwbVin == navigationVin && request.owner.ownsServiceUwb(request) &&
            granted(Manifest.permission.ACCESS_FINE_LOCATION) && state.value.locationEnabled &&
            AndroidBleScanTransport.foregroundRunning(app)

    internal fun serviceCoordinateResult(
        request: TeslaServiceUwbRequest, observation: VehicleCoordinateObservation?, reason: String?,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!serviceCoordinatesWanted(request)) return
        // Reason is intentionally not logged or copied into DiagnosticState/FieldLog.
        if (observation == null) {
            navigationAttemptRejected = true
            stopNavigationPhoneSource(VehicleNavigationPresentation(VehicleNavigationStatus.VEHICLE_LOCATION_UNAVAILABLE))
            return
        }
        navigationTracker.acceptVehicleLocation(observation)
        // Anchor before the epoch read: a delayed sample can shorten, never extend, the IO window.
        val deadlineElapsed = now()
        val deadlineEpoch = System.currentTimeMillis()
        navigationVehicleFixDeadline = vehicleNavigationCollectionDeadline(
            observation.fixEpochMs, observation.receivedAtElapsedMs, deadlineEpoch, deadlineElapsed)
        val projected = projectVehicleNavigation() ?: return
        if (!vehicleNavigationShouldCollect(true, false, navigationVehicleFixDeadline, projected.status, now())) {
            navigationAttemptRejected = true
            stopNavigationPhoneSource(VehicleNavigationPresentation(
                if (projected.status == VehicleNavigationStatus.CLOCK_INVALID) VehicleNavigationStatus.CLOCK_INVALID
                else VehicleNavigationStatus.VEHICLE_LOCATION_STALE))
        }
    }

    private fun projectVehicleNavigation(): VehicleNavigationPresentation? {
        if (!navigationSourceActive) return null
        val elapsedBefore = now()
        val epoch = System.currentTimeMillis()
        val elapsedAfter = now()
        val projected = navigationTracker.project(elapsedBefore, epoch, elapsedAfter)
        val sourceStatus = when (navigationSourceFailure) {
            PhoneNavigationUnavailable.LOCATION_PERMISSION_REQUIRED -> VehicleNavigationStatus.LOCATION_PERMISSION_REQUIRED
            PhoneNavigationUnavailable.LOCATION_DISABLED -> VehicleNavigationStatus.LOCATION_DISABLED
            PhoneNavigationUnavailable.LOCATION_UNAVAILABLE -> VehicleNavigationStatus.LOCATION_UNAVAILABLE
            PhoneNavigationUnavailable.HEADING_UNSUPPORTED -> VehicleNavigationStatus.HEADING_UNSUPPORTED
            PhoneNavigationUnavailable.HEADING_UNRELIABLE -> VehicleNavigationStatus.HEADING_UNRELIABLE
            null -> null
        }
        mutableNavigation.value = when {
            sourceStatus != null && (projected.distanceMeters != null ||
                sourceStatus != VehicleNavigationStatus.HEADING_UNSUPPORTED &&
                sourceStatus != VehicleNavigationStatus.HEADING_UNRELIABLE) ->
                projected.copy(status = sourceStatus, arrowDegrees = null, directionLabel = null)
            else -> projected
        }
        return projected
    }

    /** Seal ingress and raw RAM synchronously, before asynchronous native/FGS cleanup. */
    internal fun sealVehicleNavigation() {
        navigationStartToken = null
        navigationForegroundArmed = false
        navigationAttempt = null
        navigationAttemptRejected = true
        navigationStarting = false
        stopNavigationPhoneSource(VehicleNavigationPresentation(VehicleNavigationStatus.OFF))
    }

    private fun failVehicleNavigationCleanup() {
        navigationCleanupFailed = true
        diagnosticCleanupUncertain = true
        mutableNavigation.value = VehicleNavigationPresentation(VehicleNavigationStatus.CLEANUP_FAILED)
        if (!state.value.bleFieldTrialStopping) stopBleFieldTrial("LOCAL_CLOSE_FAILED_RESTART_REQUIRED")
        update { it.copy(bleFieldTrialStopReason = "LOCAL_CLOSE_FAILED_RESTART_REQUIRED") }
    }

    internal fun closeVehicleNavigation(owner: ObservationService, token: String, complete: (Boolean) -> Unit) {
        if (navigationOwner?.first !== owner || navigationOwner?.second != token) { complete(!navigationCleanupFailed); return }
        navigationCloseCallbacks.add(complete)
        if (navigationClosing) return
        navigationClosing = true
        fun finish(success: Boolean) {
            if (!success) failVehicleNavigationCleanup()
            val clean = success && !navigationCleanupFailed
            if (clean) {
                navigationForeground = null
                navigationOwner = null
                navigationVin = null
                navigationClosing = false
            }
            while (navigationCloseCallbacks.isNotEmpty()) {
                navigationCloseCallbacks.removeAt(navigationCloseCallbacks.lastIndex)(clean)
            }
            if (!clean && !state.value.bleFieldTrialStopping) stopBleFieldTrial("LOCAL_CLOSE_FAILED_RESTART_REQUIRED")
        }
        closeVehicleNavigationResources(
            closeSource = {
                sealVehicleNavigation()
                // Delivery sealing is not native unregister confirmation; retain source/FGS refs on failure.
                !navigationCleanupFailed && navigationSource == null
            },
            closeForeground = { completed ->
                val foreground = navigationForeground
                if (foreground == null) completed(true) else foreground.close(completed)
            },
            complete = ::finish,
        )
    }

    internal fun acquireServiceUwb(request: TeslaServiceUwbRequest): Long? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (serviceUwbOwnerReason(request) != null || teslaKeyReserved() || uwbSupportBlocked() ||
            diagnosticCleanupUncertain || policy.current != null || microphone != null ||
            state.value.speechDiagnosticActive
        ) return null
        val vin = vehicleVinSnapshot() ?: return null
        val token = ++nextTeslaKeyToken
        teslaKeyToken = token
        teslaServiceUwbRequest = request
        teslaServiceUwbVin = vin
        update { it.copy(teslaKeyDiagnosticActive = true) }
        navigationAttemptStarted(request)
        return token
    }

    internal fun startServiceUwb(request: TeslaServiceUwbRequest, finished: (BleProbeState, Boolean) -> Unit) {
        teslaKeyProbe.startServiceUwb(request, finished)
    }

    internal fun serviceUwbInFlight(owner: ObservationService, token: String, attempt: Long): Boolean =
        teslaServiceUwbRequest?.let { it.owner === owner && it.fieldToken == token && it.attempt == attempt } == true

    internal fun cancelServiceUwb(owner: ObservationService, token: String, attempt: Long, reason: String) {
        if (serviceUwbInFlight(owner, token, attempt)) teslaKeyProbe.cancel(reason)
    }

    internal fun uwbStateChanged() {
        val request = teslaServiceUwbRequest
        if (request != null && !request.ranging && teslaUwbState.value.stage == TeslaUwbStage.RANGING) {
            request.markRanging()
            request.owner.serviceUwbRanging(request)
        }
        observationService?.uwbStateChanged()
    }

    internal fun acquireUwbSupport(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val s = state.value
        if (vehicleVinSaving() || teslaKeyReserved() || uwbSupportActive || uwbSupportCleanupFailed || !activityVisible ||
            policy.current != null || microphone != null || fieldTrialReserved() ||
            s.speechDiagnosticActive || s.enabled || s.observing ||
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
    private var restoreSuppressedReason: String? = null
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
        // 권한·Bluetooth 스냅샷이 실제값이 되도록 refresh 뒤에 기록한다.
        refresh()
        event("PROCESS_START_OFF")
        try {
            if (fieldMarker.getBoolean("unclosed", false)) event("BLE_FIELD_PREVIOUS_RUN_UNCLOSED")
        } catch (_: Exception) {
            markerAvailable = false
            event("BLE_FIELD_MARKER_READ_FAILED")
        }
        var lastUserEnabledWallMs = 0L
        try {
            update { it.copy(bleDetectionEnabled = detectionPreference.enabled()) }
            lastUserEnabledWallMs = detectionPreference.lastUserEnabledWallMs()
        } catch (_: Exception) {
            restoreSuppressedReason = "BLE_DETECTION_PREFERENCE_FAILED"
            update { it.copy(bleFieldTrialStopReason = "BLE_DETECTION_PREFERENCE_FAILED") }
            event("BLE_DETECTION_PREFERENCE_FAILED")
        }
        try {
            val exit = app.getSystemService(android.app.ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(app.packageName, 0, 1)?.firstOrNull()
            // 공개 종료 이력은 강제 종료와 다른 사용자 요청 종료를 세분하지 않는다.
            // 이후 foreground에서 명시적으로 켠 기록이 없다면 새 사용자 시작을 기다린다.
            if (restoreSuppressedReason == null &&
                bleDetectionRequiresExplicitStart(exit?.reason, exit?.timestamp, lastUserEnabledWallMs)
            ) {
                restoreSuppressedReason = "BLE_DETECTION_USER_STOP_REQUIRES_START"
            }
            update { it.copy(previousExitReason = exit?.reason, previousExitWallMs = exit?.timestamp) }
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
        if (code.startsWith("TESLA_UWB_") && code != "TESLA_UWB_GATT_OBSERVATION") uwbStateChanged()
    }

    internal fun recordBleTrial(code: String, summary: BleTrialSummary) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { recordBleTrial(code, summary) }; return }
        record(code, bleTrial = summary)
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
        bleEvidence: BleEventEvidence? = null,
        frunkVoiceRecognition: FrunkVoiceRecognitionResult? = null) {
        recordEvent(stamp(code))
        persist(code, speechTrial, bleTrial, bleEvidence, frunkVoiceRecognition)
    }

    private fun persist(code: String, speechTrial: SpeechTrialSummary?, bleTrial: BleTrialSummary?,
        bleEvidence: BleEventEvidence?,
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
                    state = fieldSnapshot(code),
                    speechTrial = speechTrial,
                    bleTrial = bleTrial,
                    bleEvidence = bleEvidence,
                    teslaApproachRead = null,
                    candidateDiagnostics = state.value.bleCandidateDiagnostics.takeIf { code == "BLE_CANDIDATE_DIAGNOSTICS" },
                    frunkVoiceRecognition = frunkVoiceRecognition,
                ),
            )
        } catch (_: Exception) {
            // 예상 밖 예외도 조용히 삼키지 않는다. 원문 없이 유실 집계로만 남긴다.
            fieldLog.recordDroppedLocally()
        }
    }

    private fun fieldSnapshot(code: String): Map<String, Any?> {
        val s = state.value
        val frunk = teslaKeyState.value
        return linkedMapOf(
            FieldStateKeys.ENABLED to s.enabled,
            FieldStateKeys.AUTOMATIC_MIC to false,
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
            FieldStateKeys.BLE_FIELD_OBSERVATION_ONLY to BleFieldConfig.observationOnly,
            FieldStateKeys.BLE_FIELD_SUPPLEMENTAL_SCAN to BleFieldConfig.supplementalScan,
            FieldStateKeys.BLE_FIELD_BT_ASSIST to BleFieldConfig.btAssist,
            FieldStateKeys.BLE_FIELD_BACKGROUND_CONNECT to BleFieldConfig.backgroundConnect,
            FieldStateKeys.BLE_FIELD_RETRY_ENABLED to BleFieldConfig.retryEnabled,
            FieldStateKeys.BLE_FIELD_AUTHENTICATED_READ to BleFieldConfig.authenticatedReadOnly,
            FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER to BleFieldConfig.advertisedNameFilter,
            FieldStateKeys.BLE_FIELD_CANDIDATE_COUNT to s.bleFieldCandidateCount,
            FieldStateKeys.BLE_FIELD_SCAN_RUNNING to s.bleFieldScanRunning,
            FieldStateKeys.BLE_FIELD_SCAN_FAILURE to s.bleFieldScanFailure,
            FieldStateKeys.FRUNK_COMMAND_REQUESTED to frunk.frunkCommandRequested,
            FieldStateKeys.FRUNK_COMMAND_SENT to frunk.frunkCommandSent,
            FieldStateKeys.FRUNK_COMMAND_ACKNOWLEDGED to frunk.frunkCommandAcknowledged,
            FieldStateKeys.FRUNK_OPEN_OBSERVED to frunk.frunkOpenObserved,
            FieldStateKeys.FRUNK_LOCAL_CLOSED to (frunk.frunkCommandRequested && frunk.localClosed),
            FieldStateKeys.FRUNK_CLEANUP_FAILED to (frunk.frunkCommandRequested && frunk.stage == TeslaKeyStage.CLEANUP_FAILED),
        ).also { snapshot ->
            if (code.startsWith("TESLA_UWB_")) {
                val uwb = teslaUwbState.value
                val last = uwb.lastReceivedSample
                snapshot[FieldStateKeys.UWB_STAGE] = uwb.stage.name
                snapshot[FieldStateKeys.UWB_BACKEND_OPENED] = uwb.backendOpened
                snapshot[FieldStateKeys.UWB_AZIMUTH_SUPPORTED] = uwb.azimuthSupported
                snapshot[FieldStateKeys.UWB_ELEVATION_SUPPORTED] = uwb.elevationSupported
                snapshot[FieldStateKeys.UWB_PEER_COUNT] = uwb.peerCount
                snapshot[FieldStateKeys.UWB_STARTED_PEER_COUNT] = uwb.startedPeerCount
                snapshot[FieldStateKeys.UWB_VALID_DISTANCE_COUNT] = uwb.validDistanceCount
                snapshot[FieldStateKeys.UWB_INVALID_RESULT_COUNT] = uwb.invalidResultCount
                snapshot[FieldStateKeys.UWB_LAST_PEER_INDEX] = last?.peerIndex
                snapshot[FieldStateKeys.UWB_LAST_DISTANCE_METERS] = last?.meters
                snapshot[FieldStateKeys.UWB_LAST_CONFIDENCE] = last?.confidence
                snapshot[FieldStateKeys.UWB_LAST_AZIMUTH_RAW] = last?.azimuthRaw
                snapshot[FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE] = last?.azimuthConfidence
                snapshot[FieldStateKeys.UWB_LAST_ELEVATION_RAW] = last?.elevationRaw
                snapshot[FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE] = last?.elevationConfidence
                snapshot[FieldStateKeys.UWB_LAST_FRAMEWORK_TIMESTAMP_MS] = last?.frameworkTimestampMillis
                snapshot[FieldStateKeys.UWB_LAST_RECEIVED_ELAPSED_MS] = last?.receivedAtElapsedMs
                snapshot[FieldStateKeys.UWB_CLOSED_REASON] = uwb.closedReason
                snapshot[FieldStateKeys.UWB_OPEN_FAILED_REASON] = uwb.openFailedReason
                snapshot[FieldStateKeys.UWB_REASON] = uwb.reason
                val gatt = uwb.gatt
                snapshot[FieldStateKeys.UWB_GATT_STAGE] = gatt?.stage?.name
                snapshot[FieldStateKeys.UWB_GATT_EVENT] = gatt?.event?.name
                snapshot[FieldStateKeys.UWB_GATT_OPERATION_STARTED_ELAPSED_MS] = gatt?.operationStartedElapsedMs
                snapshot[FieldStateKeys.UWB_GATT_STAGE_STARTED_ELAPSED_MS] = gatt?.stageStartedElapsedMs
                snapshot[FieldStateKeys.UWB_GATT_OBSERVED_ELAPSED_MS] = gatt?.observedElapsedMs
                snapshot[FieldStateKeys.UWB_GATT_CONNECT_ISSUED_ELAPSED_MS] = gatt?.connectIssuedElapsedMs
                snapshot[FieldStateKeys.UWB_GATT_CONNECT_RETURNED_ELAPSED_MS] = gatt?.connectReturnedElapsedMs
                snapshot[FieldStateKeys.UWB_GATT_AUTO_CONNECT] = gatt?.autoConnect
                snapshot[FieldStateKeys.UWB_GATT_CONNECT_ACTION] = gatt?.connectAction
                snapshot[FieldStateKeys.UWB_GATT_CONNECT_ACCEPTED] = gatt?.connectAccepted
                snapshot[FieldStateKeys.UWB_GATT_ADVERTISEMENT_AGE_AT_CONNECT_MS] = gatt?.advertisementAgeAtConnectMs
                snapshot[FieldStateKeys.UWB_GATT_CONNECTION_STATUS] = gatt?.connectionStatus
                snapshot[FieldStateKeys.UWB_GATT_CONNECTION_STATE] = gatt?.connectionState
                snapshot[FieldStateKeys.UWB_GATT_CONNECTION_CALLBACK_ELAPSED_MS] = gatt?.connectionCallbackElapsedMs
                snapshot[FieldStateKeys.UWB_GATT_DESCRIPTOR_REQUEST_STATUS] = gatt?.descriptorRequestStatus
                snapshot[FieldStateKeys.UWB_GATT_DESCRIPTOR_CALLBACK_STATUS] = gatt?.descriptorCallbackStatus
                snapshot[FieldStateKeys.UWB_GATT_DESCRIPTOR_CALLBACK_ELAPSED_MS] = gatt?.descriptorCallbackElapsedMs
                snapshot[FieldStateKeys.UWB_GATT_FIRST_RX_ELAPSED_MS] = gatt?.firstRxElapsedMs
                snapshot[FieldStateKeys.UWB_GATT_LAST_RX_ELAPSED_MS] = gatt?.lastRxElapsedMs
                snapshot[FieldStateKeys.UWB_GATT_CONNECTED_OBSERVED] = gatt?.connectedObserved
                snapshot[FieldStateKeys.UWB_GATT_SERVICE_FOUND] = gatt?.serviceFound
                snapshot[FieldStateKeys.UWB_GATT_TX_FOUND] = gatt?.txFound
                snapshot[FieldStateKeys.UWB_GATT_RX_FOUND] = gatt?.rxFound
                snapshot[FieldStateKeys.UWB_GATT_SUBSCRIPTION_CONFIRMED] = gatt?.subscriptionConfirmed
                snapshot[FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT] = gatt?.rxNotificationCount
                snapshot[FieldStateKeys.UWB_RX_FRAME_COUNT] = gatt?.rxFrameCount
                snapshot[FieldStateKeys.UWB_RX_PROTOCOL_COUNT] = gatt?.rxProtocolCount
                snapshot[FieldStateKeys.UWB_RX_NOTIFICATION_FRAME_COUNT] = gatt?.rxNotificationFrameCount
                snapshot[FieldStateKeys.UWB_RX_DECODER_INCOMPLETE] = gatt?.rxDecoderIncomplete
                snapshot[FieldStateKeys.UWB_RX_DECODER_FAILED] = gatt?.rxDecoderFailed
                snapshot[FieldStateKeys.UWB_RX_PROTOCOL_RECEIVED_ELAPSED_MS] = gatt?.rxProtocolReceivedElapsedMs
                snapshot[FieldStateKeys.UWB_RX_PROTOCOL_OBSERVED_ELAPSED_MS] = gatt?.rxProtocolObservedElapsedMs
                val response = gatt?.rxResponse
                snapshot[FieldStateKeys.UWB_RX_STEP] = response?.step?.name
                snapshot[FieldStateKeys.UWB_RX_INPUT_KIND] = response?.inputKind?.name
                snapshot[FieldStateKeys.UWB_RX_AEAD] = response?.aead?.name
                snapshot[FieldStateKeys.UWB_RX_FIRA_KIND] = response?.firaKind?.name
                snapshot[FieldStateKeys.UWB_RX_AUTH_EVIDENCE] = response?.authEvidence?.name
                snapshot[FieldStateKeys.UWB_RX_DISPOSITION] = response?.disposition
                snapshot[FieldStateKeys.UWB_RX_EXPECTED_DOMAIN] = response?.expectedDomain
                snapshot[FieldStateKeys.UWB_RX_FROM_PRESENT] = response?.fromPresent
                snapshot[FieldStateKeys.UWB_RX_FROM_KIND] = response?.fromKind
                snapshot[FieldStateKeys.UWB_RX_FROM_DOMAIN] = response?.fromDomain
                snapshot[FieldStateKeys.UWB_RX_TO_PRESENT] = response?.toPresent
                snapshot[FieldStateKeys.UWB_RX_TO_KIND] = response?.toKind
                snapshot[FieldStateKeys.UWB_RX_PAYLOAD_KIND] = response?.payloadKind
                snapshot[FieldStateKeys.UWB_RX_DOMAIN_MISMATCH_REASON] = response?.domainMismatchReason
                snapshot[FieldStateKeys.UWB_RX_REQUEST_UUID_PRESENT] = response?.requestUuidPresent
                snapshot[FieldStateKeys.UWB_RX_REQUEST_UUID_MATCHES] = response?.requestUuidMatches
                snapshot[FieldStateKeys.UWB_RX_TO_ROUTE_MATCHES] = response?.toRouteMatches
                snapshot[FieldStateKeys.UWB_RX_SIGNED_STATUS_PRESENT] = response?.signedStatusPresent
                snapshot[FieldStateKeys.UWB_RX_SIGNED_MESSAGE_FAULT] = response?.signedMessageFault
                snapshot[FieldStateKeys.UWB_RX_OPERATION_STATUS] = response?.operationStatus
                val bodySummary = response?.bodySummary
                snapshot[FieldStateKeys.UWB_RX_BODY_BYTES] = bodySummary?.byteCount
                snapshot[FieldStateKeys.UWB_RX_BODY_STATUS] = bodySummary?.status?.name
                snapshot[FieldStateKeys.UWB_RX_BODY_FIELDS] = bodySummary?.canonicalFields()
                snapshot[FieldStateKeys.UWB_RX_REQUEST_CORRELATION] = response?.requestCorrelation?.name
            }
        }
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
            state.value.observationServiceRunning || state.value.speechDiagnosticActive ||
            !granted(Manifest.permission.RECORD_AUDIO)
        ) return null
        val session = policy.startDiagnostic(now()) ?: return null
        speechDiagnosticCancel = cancel
        speechDiagnosticId = session.id
        update { it.copy(speechDiagnosticActive = true) }
        return session
    }

    internal fun releaseSpeechDiagnostic(id: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (speechDiagnosticId != id || policy.current?.let { it.id == id && it.diagnostic } != true) return
        policy.finish(id)
        speechDiagnosticCancel = null
        speechDiagnosticId = null
        update { it.copy(speechDiagnosticActive = false) }
    }

    internal fun ownsBleField(owner: ObservationService, token: String): Boolean =
        ownsObservation(owner, token) && state.value.bleFieldTrialActive

    /** 제품 서비스는 새 정확 광고에서 UWB만 시작한다. 마이크·등록·차량 명령은 연결하지 않는다. */
    fun setVehicleServiceEnabled(enabled: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!enabled) {
            // 실행 owner가 없어도 저장된 사용 의도를 먼저 끈다.
            stopBleFieldTrial("DISABLED")
            return
        }
        val current = state.value
        if (current.bleFieldTrialStarting || current.bleFieldTrialActive) return
        startBleFieldTrial()
    }

    fun vehicleServiceBlockedReason(): String? = bleFieldTrialBlockedReason()

    fun bleFieldTrialBlockedReason(requireVisible: Boolean = true): String? {
        if (diagnosticCleanupUncertain) return "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED"
        vehicleVinController.readinessReason()?.let { return it }
        if (requireVisible && !activityVisible) return "VISIBLE_UI_REQUIRED"
        if (uwbSupportBlocked() || teslaKeyReserved()) return "LEASE_UNAVAILABLE"
        val s = state.value
        if (s.bleFieldTrialActive || s.bleFieldTrialStarting || s.bleFieldTrialStopping ||
            policy.current != null || microphone != null || s.speechDiagnosticActive
        ) return "LEASE_UNAVAILABLE"
        if (s.enabled || s.observing || s.observationStartPending || s.observationServiceRunning) return "OBSERVATION_ACTIVE"
        if (!markerAvailable) return "FIELD_MARKER_UNAVAILABLE"
        return fieldReadinessReason()
    }

    private fun fieldReadinessReason(): String? {
        vehicleVinController.readinessReason()?.let { return it }
        if (!granted(Manifest.permission.BLUETOOTH_SCAN)) return "BLUETOOTH_SCAN_PERMISSION_REQUIRED"
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return "BLE_LOCATION_PERMISSION_REQUIRED"
        if (!state.value.locationEnabled) return "BLE_LOCATION_DISABLED"
        return observationReadinessReason()
    }

    fun startBleFieldTrial(restoring: Boolean = false) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val vin = vehicleVinSnapshot() ?: run {
            rejectBleField(vehicleVinController.readinessReason() ?: "VIN_NOT_REGISTERED")
            return
        }
        refresh()
        bleFieldTrialBlockedReason(requireVisible = !restoring)?.let { rejectBleField(it); return }
        if (!restoring) restoreSuppressedReason = null
        val advertisedName = TeslaBleAdvertisement.localName(vin)
            ?: run { rejectBleField("VIN_FORMAT_INVALID"); return }
        val token = UUID.randomUUID().toString()
        if (!observationPolicy.request(token)) { rejectBleField("LEASE_UNAVAILABLE"); return }
        navigationTracker.clear()
        mutableNavigation.value = VehicleNavigationPresentation(VehicleNavigationStatus.START_REQUIRED)
        navigationStartToken = token.takeIf { !restoring || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
        pendingBleAdvertisedName.clear()
        pendingBleAdvertisedName.arm(token, advertisedName)
        // 구 실행의 비동기 로그 완료가 새 실행 marker를 지우지 못하게 봉인한다.
        clearFieldMarkerWhenLogged = false
        trialId = UUID.randomUUID().toString()
        update { it.copy(present = false) }
        bleProbeState.value = BleProbeState()
        teslaUwbState.value = TeslaUwbProbeState(
            serviceOwned = true, rangingPermission = granted(Manifest.permission.RANGING),
            stage = if (granted(Manifest.permission.RANGING)) TeslaUwbStage.IDLE else TeslaUwbStage.BLOCKED,
            reason = if (granted(Manifest.permission.RANGING)) null else "RANGING_PERMISSION_REQUIRED")
        update { it.copy(bleFieldTrialStarting = true, bleFieldTrialStopReason = null,
            bleCandidateDiagnostics = null,
            bleFieldCandidateCount = 0, bleFieldScanRunning = false, bleFieldScanFailure = null,
            bleFieldScanRecoveryCount = 0, bleFieldScanRecoveryAt = null,
            bleFieldScanRecoveryError = null, bleFieldScanTimerDelayMs = null,
            bleFieldTrialWaitingForDeparture = false, bleFieldTrialAttemptCount = 0, bleFieldTrialCompletedCount = 0,
            observationStartPending = true) }
        recordBleTrial("BLE_FIELD_START_REQUESTED", bleTrialSummary(0, BleProbeState(), false))
        if (!observationPolicy.accepts(token) || !state.value.bleFieldTrialStarting) return
        val marked = try { fieldMarker.edit().putBoolean("unclosed", true).commit() } catch (_: Exception) { false }
        if (!marked) { markerAvailable = false; stopBleFieldTrial("FIELD_MARKER_WRITE_FAILED"); return }
        if (!setBleDetectionPreference(true, userInitiated = !restoring)) {
            stopBleFieldTrial("BLE_DETECTION_PREFERENCE_FAILED")
            return
        }
        try {
            val intent = Intent(app, ObservationService::class.java).setAction(ObservationService.ACTION_START)
                .putExtra(ObservationService.REQUEST_ID, token)
            org.altbeacon.beacon.BeaconManager.getInstanceForApplication(app).setBackgroundMode(!activityVisible)
            bindBleObservation(intent, token)
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
                if (observationBinding?.second !== this || !acceptsObservationRequest(token)) return
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
        val current = state.value
        if (!current.bleDetectionEnabled || current.bleFieldTrialStarting || current.bleFieldTrialActive ||
            current.bleFieldTrialStopping || current.enabled || current.observationStartPending ||
            current.observationServiceRunning
        ) return
        val suppressed = restoreSuppressedReason
        if (suppressed != null) {
            update { it.copy(bleFieldTrialStopReason = suppressed) }
            return
        }
        // 복원 직후 과거 후보·GATT·마이크·명령을 재생하지 않는다.
        refresh()
        event("BLE_DETECTION_RESTORE_REQUESTED")
        val reason = bleFieldTrialBlockedReason(requireVisible = false)
        if (reason != null) {
            update { it.copy(bleFieldTrialStopReason = reason) }
            event("BLE_DETECTION_RESTORE_BLOCKED")
            return
        }
        startBleFieldTrial(restoring = true)
    }

    private fun setBleDetectionPreference(enabled: Boolean, userInitiated: Boolean = true): Boolean {
        val saved = try { detectionPreference.setEnabled(enabled, userInitiated) } catch (_: Exception) { false }
        if (saved) update { it.copy(bleDetectionEnabled = enabled) }
        else {
            // commit 실패 뒤의 SharedPreferences 메모리 값으로 자동복원을 재개하지 않는다.
            restoreSuppressedReason = "BLE_DETECTION_PREFERENCE_FAILED"
            update { it.copy(bleFieldTrialStopReason = "BLE_DETECTION_PREFERENCE_FAILED") }
            event("BLE_DETECTION_PREFERENCE_FAILED")
        }
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
        if (!ownsBleField(owner, token) || state.value.bleFieldTrialStopping || !state.value.observing) return null
        val id = owner.observedAssociationId
        return pendingBleAdvertisedName.consume(token, ownsAssociation = id != null && id == singleAssociationId())
    }

    internal fun discardBleFieldAdvertisedName(token: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        pendingBleAdvertisedName.clear(token)
    }

    fun stopBleFieldTrial(reason: String = "USER_STOP") {
        check(Looper.myLooper() == Looper.getMainLooper())
        val userStop = reason == "USER_STOP" || reason == "BLE_FIELD_NOTIFICATION_STOP" || reason == "DISABLED"
        val preferenceFailed = if (userStop) {
            restoreSuppressedReason = "BLE_DETECTION_USER_STOP_REQUIRES_START"
            !setBleDetectionPreference(false)
        } else false
        val stopReason = if (preferenceFailed) "BLE_DETECTION_PREFERENCE_FAILED" else reason
        if (state.value.bleFieldTrialStopping || (!state.value.bleFieldTrialStarting && !state.value.bleFieldTrialActive)
        ) {
            if (userStop && state.value.bleFieldTrialStopReason != "LOCAL_CLOSE_FAILED_RESTART_REQUIRED") {
                update { it.copy(bleFieldTrialStopReason = stopReason) }
            }
            return
        }
        pendingBleAdvertisedName.clear()
        update { it.copy(bleFieldTrialStopping = true, bleFieldTrialStarting = false, observing = false,
            bleFieldTrialWaitingForDeparture = false, bleFieldTrialStopReason = stopReason, stopReason = stopReason,
            bleFieldScanRecoveryAt = null) }
        sealVehicleNavigation()
        event("BLE_FIELD_STOP_REQUESTED")
        val owner = observationService
        if (owner != null) owner.stopBleField(stopReason)
        else finishBleField(null, observationPolicy.pendingRequest ?: return)
    }

    internal fun finishBleField(owner: ObservationService?, token: String, cleanupFailed: Boolean = false) {
        if ((owner != null && !ownsObservation(owner, token)) || !observationPolicy.finish(token)
        ) return
        pendingBleAdvertisedName.clear(token)
        observationService = null
        update { it.copy(enabled = false, observing = false, present = false, observationStartPending = false,
            observationServiceRunning = false, bleFieldTrialActive = false, bleFieldTrialStarting = false,
            bleFieldTrialStopping = false, bleFieldTrialWaitingForDeparture = false,
            bleFieldScanRunning = false, bleFieldScanRecoveryAt = null,
            bleFieldTrialStopReason = if (cleanupFailed) "LOCAL_CLOSE_FAILED_RESTART_REQUIRED" else it.bleFieldTrialStopReason) }
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
            backgroundLocationPermission = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            locationEnabled = app.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true) }
        val rangingPermission = granted(Manifest.permission.RANGING)
        val uwb = teslaUwbState.value
        teslaUwbState.value = if (state.value.bleFieldTrialActive &&
            !teslaKeyReserved() && !state.value.bleFieldTrialStopping && !rangingPermission
        ) uwb.copy(serviceOwned = true, rangingPermission = false, stage = TeslaUwbStage.BLOCKED,
            reason = "RANGING_PERMISSION_REQUIRED", livePeerIndices = emptySet())
        else if (rangingPermission && uwb.serviceOwned && uwb.stage == TeslaUwbStage.BLOCKED &&
            uwb.reason == "RANGING_PERMISSION_REQUIRED"
        ) uwb.copy(rangingPermission = true, stage = TeslaUwbStage.IDLE, reason = null)
        else uwb.copy(rangingPermission = rangingPermission)
        if (teslaKeyReserved()) teslaKeyProbe.checkReadiness()
        if (observationPolicy.pendingRequest != null || observationPolicy.runningRequest != null) {
            val reason = fieldReadinessReason()
            if (reason != null) stopObservation(reason)
            else if (observationService?.observedAssociationId?.let { it != ids.singleOrNull() } == true) {
                stopObservation("ASSOCIATION_REMOVED")
            }
        }
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

    internal fun acceptsObservationRequest(token: String) = observationPolicy.pendingRequest == token

    internal fun observationRequestBlockedReason(): String? = fieldReadinessReason()

    internal fun observationStarted(service: ObservationService, token: String): Boolean {
        if (vehicleVinSaving() || diagnosticCleanupUncertain) return false
        if (uwbSupportBlocked() || teslaKeyReserved()) return false
        if (state.value.speechDiagnosticActive || policy.current?.diagnostic == true) return false
        if (!observationPolicy.promote(token)) return false
        observationService = service
        update { it.copy(enabled = true, observationServiceRunning = true, observationStartPending = false,
            bleFieldTrialActive = true, bleFieldTrialStarting = false) }
        event("BLE_FIELD_RUNNING")
        return true
    }

    internal fun ownsObservation(service: ObservationService, token: String) =
        observationService === service && observationPolicy.runningRequest == token

    internal fun observationAccepted(service: ObservationService, token: String) {
        if (!ownsObservation(service, token)) return
        update { it.copy(observing = true) }
        event("OBSERVE_REQUEST_ACCEPTED_NOT_PRESENCE_PROOF")
        event("BLE_FIELD_WAITING")
    }

    /** 사용자 OFF, 알림 OFF, 시작 실패와 서비스 파괴가 모두 이 경계를 통과한다. */
    internal fun stopObservation(reason: String, token: String? = null) {
        if (token == null || observationPolicy.accepts(token)) stopBleFieldTrial(reason)
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
        if (teslaKeyReserved() && teslaServiceUwbRequest == null) { this.event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (fieldTrialReserved()) {
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
                queueDelayMs = now() - receivedElapsedMs, localGattActive = false))
            if (gate == null) checkNotNull(owner).fieldPresence(event, receivedElapsedMs)
            else this.event("PRESENCE_IGNORED_NOT_ARMED")
            return
        }
        this.event("PRESENCE_IGNORED_NOT_ARMED")
    }

    fun manualStart(context: Context) {
        if (vehicleVinSaving()) { event("VIN_SAVING"); return }
        if (teslaKeyReserved()) { event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (uwbSupportBlocked()) { event("UWB_SUPPORT_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (fieldTrialReserved()) { event("BLE_FIELD_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (state.value.speechDiagnosticActive) { event("SPEECH_TRIAL_MANUAL_BLOCKED"); return }
        if (!activityVisible || !granted(Manifest.permission.RECORD_AUDIO)) { event("MANUAL_REQUIRES_VISIBLE_UI_AND_MIC_PERMISSION"); return }
        val session = policy.startManual(now()) ?: run { event("SESSION_ALREADY_RUNNING"); return }
        begin(session)
        launchMicrophone(context, session)
    }

    private fun begin(session: SessionPolicy.Session) {
        update { it.copy(session = "수동 마이크 진단 (자동 접근 합격 아님)", sessionId = session.id, deadline = session.deadline, samples = 0, rms = 0.0, silenced = null) }
        event("MANUAL_SESSION_REQUESTED")
        handler.postDelayed({
            if (policy.accepts(session.id) && microphone == null) stop("FGS_START_TIMEOUT")
        }, 5_000)
    }

    fun launchMicrophone(context: Context, session: SessionPolicy.Session) {
        if (teslaKeyReserved()) { event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (uwbSupportBlocked()) { event("UWB_SUPPORT_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (fieldTrialReserved()) { event("BLE_FIELD_OTHER_DIAGNOSTIC_BLOCKED"); return }
        if (state.value.speechDiagnosticActive) { event("SPEECH_TRIAL_MICROPHONE_BLOCKED"); return }
        if (session.diagnostic || !policy.accepts(session.id)) { event("MICROPHONE_SESSION_REJECTED"); return }
        try {
            context.startForegroundService(Intent(context, MicrophoneService::class.java).putExtra("session_id", session.id))
        } catch (_: SecurityException) { stop("FGS_SECURITY_WHILE_IN_USE_DENIED") }
        catch (_: android.app.ForegroundServiceStartNotAllowedException) { stop("FGS_BACKGROUND_START_DENIED") }
        catch (_: Exception) { stop("FGS_START_FAILED") }
    }

    fun stop(reason: String) {
        if (teslaKeyReserved() && teslaServiceUwbRequest == null) { cancelTeslaKey(reason); return }
        // Activity·지원조회·마이크의 일반 stop은 서비스 소유 시험을 취소하지 못한다.
        if (fieldTrialReserved()) { event("BLE_FIELD_OTHER_DIAGNOSTIC_BLOCKED"); return }
        // 진단 current는 STT·TTS/GATT 실제 해제 뒤 소유자만 종료한다. 외부 stop은 취소만 전달한다.
        val diagnostic = policy.current?.takeIf { it.diagnostic }
        if (diagnostic != null) {
            if (speechDiagnosticId == diagnostic.id) speechDiagnosticCancel?.invoke()
            event(reason)
            return
        }
        val session = policy.current
        if (session != null) {
            policy.finish(session.id)
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
