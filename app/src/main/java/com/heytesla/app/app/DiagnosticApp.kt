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
import com.heytesla.app.logging.ProductVoiceDiagnosticJson
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
import com.heytesla.app.speech.product.ProductVoiceCompletion
import com.heytesla.app.speech.product.ProductVoiceDiagnostic
import com.heytesla.app.speech.product.ProductVoiceStage
import com.heytesla.app.speech.product.ProductVoiceState
import com.heytesla.app.speech.product.ProductVoiceTranscriptRecord
import com.heytesla.app.speech.product.ProductVoiceTranscriptStore
import com.heytesla.app.speech.product.ProductVoiceTranscriptWriter
import com.heytesla.app.speech.product.ProductVoiceTranscriptWriteResult
import com.heytesla.app.speech.product.ProductVoiceTranscriptReadResult
import com.heytesla.app.vehicle.connection.TeslaServiceConnectionRequest
import com.heytesla.app.vehicle.connection.TeslaConnectionState
import com.heytesla.app.vehicle.connection.TeslaGattObservation
import com.heytesla.app.vehicle.connection.serviceConnectionReadinessReason
import com.heytesla.app.vehicle.connection.serviceParkReason
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.heytesla.app.vehicle.protocol.TeslaDriveState

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
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
    val productVoiceConsent: Boolean = false,
    val productVoiceConsentSaveFailed: Boolean = false,
    val productTranscriptSavedCount: Int = 0,
    val productTranscriptWriteFailure: String? = null,
    val productVoiceResults: List<ProductVoiceTranscriptRecord> = emptyList(),
    val productVoiceResultsLoading: Boolean = false,
    val productVoiceResultsReadFailure: String? = null,
    val productVoiceRecognitionOutcome: String? = null,
    val productVoice: ProductVoiceState = ProductVoiceState(),
    val vehicleConnection: TeslaConnectionState = TeslaConnectionState(),
)

class DiagnosticRuntime private constructor(
    private val app: DiagnosticApp,
    vehicleVinStore: VehicleVinStore,
    teslaKeyStore: TeslaBleKeyStore,
    fieldLogFile: File,
    fieldMarkerName: String,
    productTranscriptRoot: File,
    productTranscriptExecutor: Executor?,
    private val productTranscriptReadiness: ((Long) -> String?)?,
) {
    constructor(app: DiagnosticApp) : this(
        app, VehicleVinStore(app), TeslaBleKeyStore(app),
        File(app.noBackupFilesDir, FIELD_LOG_RELATIVE_PATH), "ble_field_run",
        File(app.noBackupFilesDir.canonicalFile, "product-voice-transcripts"), null, null,
    )

    /**
     * 계측은 생산 VIN·키·로그·marker·설정을 변경하지 않는 독립 저장 경로를 사용한다.
     * readiness 주입은 원문 저장의 양성 fixture 전용이며 제품 실행 게이트에는 적용하지 않는다.
     * exact lease/service·봉인·정상 정리 검사는 이 주입과 무관하게 항상 적용한다.
     */
    internal constructor(
        app: DiagnosticApp,
        vehicleVinStore: VehicleVinStore,
        teslaKeyStore: TeslaBleKeyStore,
        fieldMarkerName: String = "vehicle_vin_runtime_test_${UUID.randomUUID()}",
        productTranscriptRoot: File = File(app.noBackupFilesDir.canonicalFile,
            "product-voice-transcripts-test-${UUID.randomUUID()}"),
        productTranscriptExecutor: Executor? = null,
        productTranscriptReadiness: ((Long) -> String?)? = null,
    ) : this(
        app, vehicleVinStore, teslaKeyStore,
        File(app.noBackupFilesDir, "vehicle-vin-tests/runtime-${UUID.randomUUID()}/events-v2.jsonl"),
        fieldMarkerName,
        productTranscriptRoot, productTranscriptExecutor, productTranscriptReadiness,
    )
    val handler = Handler(Looper.getMainLooper())
    val policy = SessionPolicy()
    private val mutable = MutableStateFlow(DiagnosticState())
    val state = mutable.asStateFlow()
    internal val bleProbeState = MutableStateFlow(BleProbeState())
    internal val teslaKeyState = MutableStateFlow(TeslaKeyProbeState())
    internal val teslaConnectionState = MutableStateFlow(TeslaConnectionState())
    private val teslaKeyProbe by lazy { TeslaBleKeyProbe(app, this, teslaKeyState, teslaKeyStore) }
    private var teslaKeyToken: Long? = null
    private var nextTeslaKeyToken = 0L
    private var teslaServiceConnectionRequest: TeslaServiceConnectionRequest? = null
    private var teslaServiceConnectionVin: String? = null
    private data class ProductVoiceLease(
        val id: Long,
        val request: TeslaServiceConnectionRequest,
        val vin: String,
        val associationId: Int,
        var service: ProductMicrophoneService? = null,
        var sealed: Boolean = false,
        var utterance: Long = 0,
    )
    private var nextProductVoiceId = 0L
    private var productVoiceLease: ProductVoiceLease? = null
    private var productVoiceAttempt: TeslaServiceConnectionRequest? = null
    private var productParkState: TeslaDriveState? = null
    private var productVoiceCleanupWaiter: ((Boolean) -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val observationPolicy = ObservationPolicy()
    private var observationService: ObservationService? = null
    private var observationBinding: Pair<String, android.content.ServiceConnection>? = null
    private val pendingBleAdvertisedName = BlePendingAdvertisedName()
    private val fieldMarker = app.getSharedPreferences(fieldMarkerName, Context.MODE_PRIVATE)
    private var markerAvailable = true
    private var clearFieldMarkerWhenLogged = false
    private val productTranscriptStore = ProductVoiceTranscriptStore(productTranscriptRoot)
    private val productTranscriptIo = productTranscriptExecutor ?: Executors.newSingleThreadExecutor { task ->
        Thread(task, "product-transcript-io").apply { isDaemon = true }
    }
    private val productTranscriptWriter = ProductVoiceTranscriptWriter(
        productTranscriptStore, ::productTranscriptWritten, executor = productTranscriptIo)
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
        if (diagnosticCleanupUncertain || s.teslaKeyCleanupFailed ||
            bleProbeState.value.status == BleProbeStatus.CLEANUP_FAILED ||
            s.bleFieldTrialStopReason == "LOCAL_CLOSE_FAILED_RESTART_REQUIRED"
        ) return "VIN_CHANGE_BLOCKED_CLEANUP"
        if (teslaKeyReserved() || productVoiceLease != null || policy.current != null || microphone != null ||
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
        if (productVoiceLease != null || policy.current != null || microphone != null || fieldTrialReserved() ||
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
        requireFresh: Boolean = true, serviceConnection: TeslaServiceConnectionRequest? = null): String? {
        if (teslaKeyToken != token) return "LEASE_LOST"
        val serviceOwned = teslaServiceConnectionRequest
        if (serviceOwned != null) {
            if (serviceOwned !== serviceConnection) return "LEASE_LOST"
            serviceConnectionOwnerReason(serviceOwned, requireFresh)?.let { return it }
            if (vehicleVinSnapshot() != teslaServiceConnectionVin) return "ASSOCIATION_CHANGED"
            val s = state.value
            if (policy.current != null || microphone != null ||
                s.speechDiagnosticActive
            ) return "DIAGNOSTIC_EXCLUSIVITY_LOST"
            return null
        }
        if (serviceConnection != null) return "LEASE_LOST"
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
        if (productVoiceLease != null || policy.current != null || microphone != null || fieldTrialReserved() ||
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
        teslaServiceConnectionRequest = null
        teslaServiceConnectionVin = null
        update { it.copy(teslaKeyDiagnosticActive = false) }
    }

    internal fun retainTeslaKey(token: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (teslaKeyToken == token) {
            update { it.copy(teslaKeyCleanupFailed = true) }
            teslaServiceConnectionRequest?.let { serviceConnectionStopping(it, "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED") }
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

    /** 화면/HOME/잠금 취소 예외는 정확한 서비스 소유 작업에만 적용한다. */
    internal fun cancelTeslaKey(reason: String = "USER_STOP") {
        if (teslaKeyReserved() && teslaServiceConnectionRequest == null) teslaKeyProbe.cancel(reason)
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
        if (diagnosticCleanupUncertain || state.value.teslaKeyCleanupFailed ||
            bleProbeState.value.status == BleProbeStatus.CLEANUP_FAILED
        ) return "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED"
        vehicleVinController.readinessReason()?.let { return it }
        val s = state.value
        if (s.bleDetectionEnabled || s.enabled || s.observing || s.observationStartPending ||
            s.observationServiceRunning || fieldTrialReserved()
        ) return "DETECTION_MUST_STOP"
        if (vehicleChooserPending || teslaKeyReserved() || productVoiceLease != null || microphone != null ||
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

    internal fun serviceConnectionOwnerReason(request: TeslaServiceConnectionRequest, requireFresh: Boolean = true): String? {
        val s = state.value
        return serviceConnectionReadinessReason(
            ownerArmed = { s.bleDetectionEnabled && ownsBleField(request.owner, request.fieldToken) &&
                !s.bleFieldTrialStopping && s.observing &&
                request.owner.ownsServiceConnection(request) },
            scanReady = { request.owner.serviceConnectionScanPaused(request) && AndroidBleScanTransport.foregroundRunning(app) },
            initialBudgetExpired = { !request.monitoring && now() >= request.deadline },
            deviceAvailable = { request.device != null }, sourceTimestampNanos = request.sourceTimestampNanos,
            now = { now() }, requireFresh = requireFresh,
            associationMatches = { singleAssociationId() == request.associationId },
            targetReason = { vehicleVinController.readinessReason() },
            fieldReason = { fieldReadinessReason() ?: productVoicePreparationReason() },
        )
    }

    internal fun acquireServiceConnection(request: TeslaServiceConnectionRequest): Long? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (serviceConnectionOwnerReason(request) != null || teslaKeyReserved() || productVoiceLease != null ||
            diagnosticCleanupUncertain || policy.current != null || microphone != null ||
            state.value.speechDiagnosticActive
        ) return null
        val vin = vehicleVinSnapshot() ?: return null
        val token = ++nextTeslaKeyToken
        teslaKeyToken = token
        teslaServiceConnectionRequest = request
        teslaServiceConnectionVin = vin
        update { it.copy(teslaKeyDiagnosticActive = true) }
        return token
    }

    internal fun startServiceConnection(request: TeslaServiceConnectionRequest, finished: (BleProbeState, Boolean) -> Unit) {
        teslaKeyProbe.startServiceConnection(request) { result, retained ->
            // BLE 정리가 빨라도 오디오가 살아 있는 동안 다음 스캔/접근을 허용하지 않는다.
            if (productVoiceLease?.request === request) {
                productVoiceCleanupWaiter = { audioClosed ->
                    finished(if (audioClosed) result else result.copy(status = BleProbeStatus.CLEANUP_FAILED,
                        reason = "LOCAL_CLOSE_FAILED_RESTART_REQUIRED"), retained || !audioClosed)
                }
                if (diagnosticCleanupUncertain) {
                    productVoiceCleanupWaiter = null
                    finished(result.copy(status = BleProbeStatus.CLEANUP_FAILED,
                        reason = "LOCAL_CLOSE_FAILED_RESTART_REQUIRED"), true)
                } else stopProductVoice(result.reason ?: "CONNECTION_ENDED")
            } else finished(result, retained || diagnosticCleanupUncertain)
        }
    }

    internal fun serviceConnectionInFlight(owner: ObservationService, token: String, attempt: Long): Boolean =
        matchesServiceConnection(teslaServiceConnectionRequest, owner, token, attempt) ||
            matchesServiceConnection(productVoiceLease?.request, owner, token, attempt)

    private fun matchesServiceConnection(
        request: TeslaServiceConnectionRequest?, owner: ObservationService, token: String, attempt: Long,
    ): Boolean = request?.let { it.owner === owner && it.fieldToken == token && it.attempt == attempt } == true

    internal fun cancelServiceConnection(owner: ObservationService, token: String, attempt: Long, reason: String) {
        // BLE가 먼저 닫혀도 같은 요청의 오디오 정리 완료가 field 예약을 반환한다.
        if (matchesServiceConnection(productVoiceLease?.request, owner, token, attempt)) stopProductVoice(reason)
        if (matchesServiceConnection(teslaServiceConnectionRequest, owner, token, attempt)) teslaKeyProbe.cancel(reason)
    }

    /** 업데이트 이전 서비스 ON과 별도인 명시적 제품 마이크·제어 동의다. */
    fun setProductVoiceConsent(value: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!activityVisible || app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) return
        if (!value) {
            update { it.copy(productVoiceConsent = false) }
            stopProductVoice("PRODUCT_VOICE_CONSENT_REQUIRED")
            teslaServiceConnectionRequest?.let { cancelServiceConnection(it.owner, it.fieldToken, it.attempt,
                "PRODUCT_VOICE_CONSENT_REQUIRED") }
        }
        val saved = try { fieldMarker.edit().putBoolean("product_voice_consent", value).commit() }
            catch (_: Exception) { false }
        if (!saved) {
            update { it.copy(productVoiceConsentSaveFailed = true,
                productVoice = it.productVoice.copy(reason = "VOICE_PREFERENCE_FAILED")) }
            return
        }
        update { it.copy(productVoiceConsent = value, productVoiceConsentSaveFailed = false) }
        event(if (value) "PRODUCT_VOICE_CONSENT_GRANTED" else "PRODUCT_VOICE_CONSENT_REVOKED")
        // 허용/권한 복귀는 이미 끝난 접근을 재생하지 않는다.
    }

    private fun productTranscriptWritten(result: ProductVoiceTranscriptWriteResult) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { productTranscriptWritten(result) }
            return
        }
        when (result) {
            // 이전 pending의 성공은 더 최신 결과의 보관 실패를 지우지 않는다. 이번 실행의 실패 latch다.
            ProductVoiceTranscriptWriteResult.SAVED -> update { it.copy(
                productTranscriptSavedCount = it.productTranscriptSavedCount + 1) }
            else -> update { it.copy(productTranscriptWriteFailure = result.name) }
        }
    }

    private fun restoreProductVoiceResults() {
        update { it.copy(productVoiceResultsLoading = true) }
        try {
            productTranscriptIo.execute {
                val result = try { productTranscriptStore.readRecent() }
                    catch (_: Exception) { ProductVoiceTranscriptReadResult(emptyList(), "READ_FAILED") }
                    catch (_: LinkageError) { ProductVoiceTranscriptReadResult(emptyList(), "READ_FAILED") }
                handler.post {
                    update { current ->
                        // 복원은 표시 전용이다. 읽기 시작 뒤 도착한 이번 프로세스 결과를 항상 앞에 둔다.
                        val recent = current.productVoiceResults
                        val restored = result.records.filter { old ->
                            recent.none { live -> live.processId == old.processId &&
                                live.voiceLeaseId == old.voiceLeaseId && live.utterance == old.utterance }
                        }
                        current.copy(productVoiceResults = (recent + restored).take(5),
                            productVoiceResultsLoading = false, productVoiceResultsReadFailure = result.failure)
                    }
                }
            }
        } catch (_: Exception) {
            update { it.copy(productVoiceResultsLoading = false, productVoiceResultsReadFailure = "READ_FAILED") }
        } catch (_: LinkageError) {
            update { it.copy(productVoiceResultsLoading = false, productVoiceResultsReadFailure = "READ_FAILED") }
        }
    }


    internal fun productVoicePreparationReason(): String? = when {
        diagnosticCleanupUncertain || state.value.teslaKeyCleanupFailed -> "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED"
        !state.value.productVoiceConsent -> "PRODUCT_VOICE_CONSENT_REQUIRED"
        !granted(Manifest.permission.RECORD_AUDIO) -> "MIC_PERMISSION_REQUIRED"
        assistant?.productVoiceAvailable() != true ->
            if (assistantActive()) "ASSISTANT_NOT_READY" else "DEFAULT_ASSISTANT_REQUIRED"
        else -> null
    }

    internal fun productAssistantReady(service: AssistantService) {
        if (assistant !== service || !assistantActive()) return
        refresh()
    }

    internal fun productAssistantUnavailable(service: AssistantService) {
        if (assistant != null && assistant !== service) return
        stopProductVoice("ASSISTANT_UNAVAILABLE")
        teslaServiceConnectionRequest?.let {
            cancelServiceConnection(it.owner, it.fieldToken, it.attempt, "ASSISTANT_UNAVAILABLE")
        }
    }

    internal fun serviceParkStateChanged(request: TeslaServiceConnectionRequest, drive: TeslaDriveState?, reason: String?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (teslaServiceConnectionRequest !== request) return
        val blocked = reason ?: serviceParkReason(drive, now(), System.currentTimeMillis())
        productParkState = if (blocked == null) drive else null
        if (blocked != null) {
            serviceConnectionStopping(request, blocked)
            return
        }
        if (!request.monitoring) {
            request.markMonitoring()
            request.owner.serviceConnectionMonitoring(request)
        }
        request.owner.serviceParkRefreshed(request, checkNotNull(drive).receivedAtElapsedRealtime)
        if (productVoiceAttempt === request) return
        productVoiceAttempt = request
        val preparation = serviceConnectionOwnerReason(request, requireFresh = false)
        if (preparation != null) {
            update { it.copy(productVoice = ProductVoiceState(ProductVoiceStage.BLOCKED, preparation)) }
            cancelServiceConnection(request.owner, request.fieldToken, request.attempt, preparation)
            return
        }
        if (productVoiceLease != null || policy.current != null || microphone != null || state.value.speechDiagnosticActive) {
            cancelServiceConnection(request.owner, request.fieldToken, request.attempt, "LEASE_UNAVAILABLE")
            return
        }
        val lease = ProductVoiceLease(++nextProductVoiceId, request,
            vehicleVinSnapshot() ?: return, request.associationId)
        productVoiceLease = lease
        update { it.copy(productVoice = ProductVoiceState(ProductVoiceStage.PREPARING),
            productVoiceRecognitionOutcome = null) }
        event("PRODUCT_VOICE_START_REQUESTED")
        if (assistant?.startProductVoice(lease.id) != true) {
            lease.sealed = true
            productVoiceLease = null
            update { it.copy(productVoice = ProductVoiceState(ProductVoiceStage.BLOCKED, "VOICE_FGS_START_FAILED")) }
            cancelServiceConnection(request.owner, request.fieldToken, request.attempt, "VOICE_FGS_START_FAILED")
            return
        }
        handler.postDelayed({
            if (productVoiceLease === lease && lease.service == null) {
                lease.sealed = true
                ProductMicrophoneService.abandonPending(lease.id)
                productVoiceLease = null
                update { it.copy(productVoice = ProductVoiceState(ProductVoiceStage.BLOCKED, "VOICE_FGS_START_TIMEOUT")) }
                cancelServiceConnection(request.owner, request.fieldToken, request.attempt, "VOICE_FGS_START_TIMEOUT")
                productVoiceCleanupWaiter?.also { productVoiceCleanupWaiter = null }?.invoke(true)
            }
        }, 5_000L)
    }

    internal fun productVoiceAllowed(id: Long): String? {
        val lease = productVoiceLease ?: return "LEASE_LOST"
        if (lease.id != id || lease.sealed || teslaServiceConnectionRequest !== lease.request) return "LEASE_LOST"
        if (vehicleVinSnapshot() != lease.vin || singleAssociationId() != lease.associationId) return "ASSOCIATION_CHANGED"
        productVoicePreparationReason()?.let { return it }
        serviceConnectionOwnerReason(lease.request, requireFresh = false)?.let { return it }
        return serviceParkReason(productParkState, now(), System.currentTimeMillis())
    }

    internal fun productVoiceServiceStarted(id: Long, service: ProductMicrophoneService): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val lease = productVoiceLease ?: return false
        if (lease.id != id || lease.service != null) return false
        // 거부된 최초 start도 정리 완료를 이 인스턴스에서만 받는다.
        lease.service = service
        return productVoiceAllowed(id) == null
    }

    internal fun productVoiceChanged(id: Long, value: ProductVoiceState) {
        val lease = productVoiceLease ?: return
        if (lease.id != id || (lease.sealed && value.stage != ProductVoiceStage.STOPPING)) return
        if (state.value.productVoice == value) return
        update { it.copy(productVoice = value, productVoiceRecognitionOutcome =
            if (value.stage == ProductVoiceStage.RECOGNIZING || value.stage == ProductVoiceStage.COMMAND_READY)
                null else it.productVoiceRecognitionOutcome) }
        event("PRODUCT_VOICE_STATE_CHANGED")
        observationService?.scanStatusChanged()
    }

    /** 실행 허가와 별개인 exact-owner 관측. 봉인 후 실제 정리 결과도 현재 lease에서만 받는다. */
    internal fun productVoiceDiagnosed(id: Long, service: ProductMicrophoneService,
        value: ProductVoiceDiagnostic, text: String? = null, reason: String? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val lease = productVoiceLease ?: return
        if (lease.id != id || lease.service !== service) return
        val utterance = if ((value.utteranceBytes ?: 0) > 0 && value.outcome != "PREPARED" &&
            value.outcome != "CLOSED") ++lease.utterance else null
        val normal = value.outcome == "EMPTY" || value.outcome == "GRAMMAR_NO_MATCH" || value.outcome == "ACCEPTED"
        val blocked = if (normal && utterance != null) {
            val readinessReason = when {
                lease.sealed -> state.value.productVoice.reason ?: "LEASE_LOST"
                teslaServiceConnectionRequest !== lease.request -> "LEASE_LOST"
                else -> try {
                    val readiness = productTranscriptReadiness
                    if (readiness == null) productVoiceAllowed(id) else readiness(id)
                } catch (_: Exception) { "PRODUCT_VOICE_READINESS_UNKNOWN" }
                    catch (_: LinkageError) { "PRODUCT_VOICE_READINESS_UNKNOWN" }
            }
            readinessReason ?: when {
                value.microphoneReleased != true || value.streamCleanupConfirmed != true ||
                    (value.outcome == "ACCEPTED" && value.recognizerCleanupConfirmed != true) ->
                    "PRODUCT_VOICE_CLEANUP_FAILED_RESTART_REQUIRED"
                text == null -> "PRODUCT_VOICE_DECODE_FAILED"
                else -> null
            }
        } else null
        // 최종 게이트에 막힌 발화도 사라지지 않는다. 같은 일반 수치 행과 전용 결과를 SEALED로 상관시킨다.
        val observation = if (blocked != null) value.copy(outcome = "SEALED") else value
        val observationReason = blocked ?: reason
        val field = record(ProductVoiceDiagnosticJson.EVENT, productVoiceDiagnostic = observation) ?: return
        if (utterance == null) return
        val retainText = normal && blocked == null
        if (!retainText && ((observation.outcome != "FAILURE" && observation.outcome != "SEALED") ||
                observationReason == null)) return
        val fixedReason = when (observation.outcome) {
            "EMPTY" -> "FRUNK_VOICE_EMPTY"
            "GRAMMAR_NO_MATCH" -> "FRUNK_VOICE_GRAMMAR_NO_MATCH"
            "ACCEPTED" -> null
            else -> observationReason
        }
        val transcript = ProductVoiceTranscriptRecord(
            processId = field.processId, trialId = field.trialId,
            voiceLeaseId = id, utterance = utterance,
            wallTimeMs = field.wallTimeMs, elapsedRealtimeMs = field.elapsedRealtimeMs,
            appVersion = field.appVersion, text = if (retainText) text else null,
            diagnostic = observation, reason = fixedReason)
        // RAM 표시와 파일 쓰기는 분리한다. 실패/한도에도 방금 결과를 이전 원문으로 대체하지 않는다.
        update { it.copy(productVoiceResults = (listOf(transcript) + it.productVoiceResults).take(5),
            productVoiceRecognitionOutcome = when (observation.outcome) {
                "EMPTY", "GRAMMAR_NO_MATCH" -> observation.outcome
                else -> null
            }) }
        observationService?.scanStatusChanged()
        try {
            productTranscriptWriter.submit(transcript)
        } catch (_: Exception) {
            productTranscriptWritten(ProductVoiceTranscriptWriteResult.WRITE_FAILED)
        } catch (_: LinkageError) {
            productTranscriptWritten(ProductVoiceTranscriptWriteResult.WRITE_FAILED)
        }
    }

    internal fun productVoiceFinished(id: Long, service: ProductMicrophoneService, completion: ProductVoiceCompletion) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val lease = productVoiceLease ?: return
        if (lease.id != id || lease.service !== service) return
        val clean = completion.microphoneReleased && completion.cleanupConfirmed
        val allowed = if (lease.sealed) state.value.productVoice.reason ?: "LEASE_LOST" else productVoiceAllowed(id)
        lease.sealed = true
        if (!clean) {
            completion.gate?.cancel("FRUNK_VOICE_CLEANUP_FAILED")
            diagnosticCleanupUncertain = true
            update { it.copy(productVoice = ProductVoiceState(ProductVoiceStage.FAILED, "PRODUCT_VOICE_CLEANUP_FAILED_RESTART_REQUIRED")) }
            observationService?.scanStatusChanged()
            event("PRODUCT_VOICE_CLEANUP_FAILED")
            cancelServiceConnection(lease.request.owner, lease.request.fieldToken, lease.request.attempt,
                "LOCAL_CLOSE_FAILED_RESTART_REQUIRED")
            productVoiceCleanupWaiter?.also { productVoiceCleanupWaiter = null }?.invoke(false)
            return
        }
        productVoiceLease = null
        val gate = completion.gate
        val dispatch = allowed == null && gate != null &&
            gate.takeForDispatch(now(), inputReleased = true, cleanupConfirmed = true)
        if (dispatch) {
            val accepted = gate?.commandAcceptedAtElapsedMs
            if (accepted != null && lease.request.owner.serviceFrunkStarting(lease.request, accepted) &&
                teslaKeyProbe.dispatchServiceFrunk(lease.request, accepted)) {
                update { it.copy(productVoice = ProductVoiceState(ProductVoiceStage.COMMAND_READY)) }
                observationService?.scanStatusChanged()
                event("PRODUCT_VOICE_COMMAND_ACCEPTED")
                return
            }
        } else gate?.cancel(allowed ?: completion.reason ?: "FRUNK_VOICE_HANDOFF_BLOCKED")
        val reason = allowed ?: completion.reason ?: gate?.reason ?: "FRUNK_VOICE_HANDOFF_BLOCKED"
        update { it.copy(productVoice = ProductVoiceState(ProductVoiceStage.STOPPED, reason)) }
        observationService?.scanStatusChanged()
        event("PRODUCT_VOICE_STOPPED")
        cancelServiceConnection(lease.request.owner, lease.request.fieldToken, lease.request.attempt, reason)
        productVoiceCleanupWaiter?.also { productVoiceCleanupWaiter = null }?.invoke(true)
    }

    internal fun serviceConnectionStopping(request: TeslaServiceConnectionRequest, reason: String) {
        if (teslaServiceConnectionRequest !== request && productVoiceLease?.request !== request) return
        productParkState = null
        stopProductVoice(reason)
    }

    private fun stopProductVoice(reason: String) {
        val lease = productVoiceLease ?: return
        if (lease.sealed) return
        lease.sealed = true
        update { it.copy(productVoice = ProductVoiceState(ProductVoiceStage.STOPPING, reason)) }
        val service = lease.service
        if (service != null) service.stopProduct(reason)
        else {
            ProductMicrophoneService.abandonPending(lease.id)
            productVoiceLease = null
            update { it.copy(productVoice = ProductVoiceState(ProductVoiceStage.STOPPED, reason)) }
            productVoiceCleanupWaiter?.also { productVoiceCleanupWaiter = null }?.invoke(true)
        }
    }

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
        val consent = try { fieldMarker.getBoolean("product_voice_consent", false) } catch (_: Exception) { false }
        update { it.copy(productVoiceConsent = consent) }
        restoreProductVoiceResults()
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
            teslaConnectionState.collect { connection ->
                val previous = state.value.vehicleConnection
                update { it.copy(vehicleConnection = connection) }
                if (connection.stage != previous.stage || connection.gear != previous.gear ||
                    connection.parkFresh != previous.parkFresh || connection.reason != previous.reason) {
                    event("TESLA_CONNECTION_STATE_CHANGED")
                }
                observationService?.scanStatusChanged()
            }
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
        // 큐 처리 시점의 최신 상태가 아니라 event 호출 시점의 immutable 관측을 보존한다.
        val connectionObservation = teslaConnectionState.value.gatt.takeIf {
            code == "TESLA_CONNECTION_GATT_OBSERVATION"
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { eventOnMain(code, speechTrial, connectionObservation) }
            return
        }
        eventOnMain(code, speechTrial, connectionObservation)
    }

    private fun eventOnMain(code: String, speechTrial: SpeechTrialSummary?,
        connectionObservation: TeslaGattObservation?) {
        syncFrunkVoiceResult()
        if (code == "FRUNK_VOICE_CLEANUP_FAILED" ||
            code == "OBSERVE_STOP_FAILED_LOCAL_GATE_CLOSED" || code == "BLE_FIELD_WAKE_LOCK_RELEASE_FAILED" ||
            code == "SPEECH_SUPPORT_DESTROY_FAILED"
        ) {
            diagnosticCleanupUncertain = true
        }
        record(code, speechTrial, connectionObservation = connectionObservation)
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
        frunkVoiceRecognition: FrunkVoiceRecognitionResult? = null,
        connectionObservation: TeslaGattObservation? = null,
        productVoiceDiagnostic: ProductVoiceDiagnostic? = null): FieldRecord? {
        recordEvent(stamp(code))
        return persist(code, speechTrial, bleTrial, bleEvidence, frunkVoiceRecognition, connectionObservation, productVoiceDiagnostic)
    }

    private fun persist(code: String, speechTrial: SpeechTrialSummary?, bleTrial: BleTrialSummary?,
        bleEvidence: BleEventEvidence?,
        frunkVoiceRecognition: FrunkVoiceRecognitionResult?,
        connectionObservation: TeslaGattObservation?,
        productVoiceDiagnostic: ProductVoiceDiagnostic?): FieldRecord? {
        return try {
            val field = FieldRecord(
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
                teslaApproachRead = null,
                candidateDiagnostics = state.value.bleCandidateDiagnostics.takeIf { code == "BLE_CANDIDATE_DIAGNOSTICS" },
                frunkVoiceRecognition = frunkVoiceRecognition,
                connectionObservation = connectionObservation,
                productVoiceDiagnostic = productVoiceDiagnostic,
            )
            // 생성·enqueue 실패는 기존처럼 실행 경로로 전파하지 않는다.
            // allowlist 판정과 allowlist 밖 집계는 sink가 단독으로 담당한다.
            fieldLog.enqueue(field)
            field
        } catch (_: Exception) {
            // 예상 밖 예외도 조용히 삼키지 않는다. 원문 없이 유실 집계로만 남긴다.
            fieldLog.recordDroppedLocally()
            null
        }
    }

    private fun fieldSnapshot(): Map<String, Any?> {
        val s = state.value
        val frunk = teslaKeyState.value
        return linkedMapOf(
            FieldStateKeys.ENABLED to s.enabled,
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
            FieldStateKeys.SESSION_ACTIVE to (s.sessionId != null || productVoiceLease != null),
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
            FieldStateKeys.CONNECTION_STAGE to teslaConnectionState.value.stage.name,
            FieldStateKeys.CONNECTION_GEAR to teslaConnectionState.value.gear?.name,
            FieldStateKeys.CONNECTION_PARK_FRESH to teslaConnectionState.value.parkFresh,
            FieldStateKeys.CONNECTION_REASON to teslaConnectionState.value.reason,
            FieldStateKeys.PRODUCT_VOICE_STAGE to s.productVoice.stage.name,
            FieldStateKeys.PRODUCT_VOICE_REASON to s.productVoice.reason,
            FieldStateKeys.PRODUCT_VOICE_CONSENT to s.productVoiceConsent,
        )
    }

    private fun stamp(code: String) = "${now()} ms · $code"

    private fun recordEvent(line: String) {
        update { it.copy(events = (listOf(line) + it.events).take(32)) }
    }

    internal fun acquireSpeechDiagnostic(cancel: () -> Unit): SessionPolicy.Session? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (vehicleVinSaving() || productVoiceLease != null || teslaKeyReserved() || fieldTrialReserved()) return null
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

    /** 서비스 ON은 감지를 제어하며 제품 음성 동의·기본 비서 준비와 별도로 보존한다. */
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
        if (productVoiceLease != null || teslaKeyReserved()) return "LEASE_UNAVAILABLE"
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
        pendingBleAdvertisedName.clear()
        pendingBleAdvertisedName.arm(token, advertisedName)
        // 구 실행의 비동기 로그 완료가 새 실행 marker를 지우지 못하게 봉인한다.
        clearFieldMarkerWhenLogged = false
        trialId = UUID.randomUUID().toString()
        update { it.copy(present = false) }
        bleProbeState.value = BleProbeState()
        teslaConnectionState.value = TeslaConnectionState()
        productVoiceAttempt = null
        productParkState = null
        update { it.copy(productVoice = ProductVoiceState()) }
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
        stopProductVoice(stopReason)
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
            locationEnabled = app.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true) }
        if (teslaKeyReserved()) teslaKeyProbe.checkReadiness()
        productVoiceLease?.let { lease ->
            productVoiceAllowed(lease.id)?.let(::stopProductVoice)
        }
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
        if (productVoiceLease != null || teslaKeyReserved()) return false
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
        if (teslaKeyReserved() && teslaServiceConnectionRequest == null) { this.event("TESLA_KEY_OTHER_DIAGNOSTIC_BLOCKED"); return }
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
        if (teslaKeyReserved() && teslaServiceConnectionRequest == null) { cancelTeslaKey(reason); return }
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
