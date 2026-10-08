package com.heytesla.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/** 사용자 요청의 단일 connectedDevice FGS. 프로세스·BOOT·Activity 복원으로 시험을 시작하지 않는다. */
class ObservationService : Service() {
    internal enum class Mode { OBSERVATION, BLE_FIELD }

    private val runtime get() = (application as DiagnosticApp).runtime
    private var requestId: String? = null
    private var mode = Mode.OBSERVATION
    private var lastStartId = 0
    private var fieldPolicy: BleFieldTrialPolicy? = null
    private var probe: BleConnectionProbe? = null
    private var scanner: BleSupplementalScanner? = null
    private var policyTick: Runnable? = null
    private var policyTickAt: Long? = null
    private var readinessTick: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    internal var observedAssociationId: Int? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val token = intent?.getStringExtra(REQUEST_ID)
        if (intent?.action == ACTION_STOP) {
            if (token != null && runtime.ownsObservation(this, token)) {
                runtime.stopObservation(if (mode == Mode.BLE_FIELD) "BLE_FIELD_NOTIFICATION_STOP" else "OBSERVATION_NOTIFICATION_STOP", token)
            } else if (requestId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (token != null && token == requestId && runtime.ownsObservation(this, token)) return START_NOT_STICKY
        val requestedMode = Mode.entries.firstOrNull { it.name == intent?.getStringExtra(MODE) }
        if (intent?.action != ACTION_START || token == null || requestedMode == null ||
            !runtime.acceptsObservationRequest(token, requestedMode)
        ) {
            if (requestId == null) {
                try { promote(token ?: "unarmed") } catch (_: Exception) { }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            runtime.event("OBSERVATION_STALE_START_IGNORED")
            return START_NOT_STICKY
        }

        requestId = token
        mode = requestedMode
        if (mode == Mode.BLE_FIELD) {
            val config = runtime.state.value.bleFieldConfig
            config.safetyBlockedReason()?.let { fail(it, token); return START_NOT_STICKY }
            fieldPolicy = BleFieldTrialPolicy(config, runtime::now, runtime::recordBleEvidence)
            if (!config.observationOnly && config.scanFilterMode == BleScanFilterMode.ASSOCIATION_ADDRESS) {
                probe = BleConnectionProbe(this, runtime.bleProbeState, runtime, this, token, ::stage, ::attemptFinished)
            }
        }
        try {
            promote(token)
            runtime.refresh()
            if (!runtime.acceptsObservationRequest(token, mode)) {
                finishObserving()
                return START_NOT_STICKY
            }
            runtime.observationRequestBlockedReason()?.let { fail(it, token); return START_NOT_STICKY }
            if (!runtime.observationStarted(this, token)) {
                fail("LEASE_UNAVAILABLE", token)
                return START_NOT_STICKY
            }
            startObserving()
            if (mode == Mode.BLE_FIELD && runtime.ownsBleField(this, token) && !runtime.state.value.bleFieldTrialStopping) {
                scheduleReadiness(token)
                startSupplementalScan()
                updateNotification()
            }
        } catch (_: SecurityException) { fail("OBSERVATION_FGS_SECURITY_DENIED", token) }
        catch (_: android.app.ForegroundServiceStartNotAllowedException) { fail("OBSERVATION_FGS_BACKGROUND_START_DENIED", token) }
        catch (_: Exception) { fail("OBSERVATION_FGS_START_FAILED", token) }
        return START_NOT_STICKY
    }

    private fun promote(token: String) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "등록 차량 접근 관찰", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification(token), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun notification(token: String): Notification {
        val stopIntent = Intent(this, ObservationService::class.java)
            .setAction(ACTION_STOP).setIdentifier(token).putExtra(REQUEST_ID, token)
        val stop = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val field = mode == Mode.BLE_FIELD
        val s = runtime.state.value
        val text = when {
            !field -> "실제 BLE 감지는 별도 확인 · 자동 마이크는 별도 동의"
            runtime.bleProbeState.value.status == BleProbeStatus.CLEANUP_FAILED -> "재시작 필요"
            s.bleFieldTrialStopping || runtime.bleProbeState.value.status == BleProbeStatus.CLEANING_UP -> "정리 중"
            s.bleFieldScanFailure != null -> "보조 스캔 실패 · 시험 중지"
            fieldPolicy?.config?.observationOnly == true -> "관찰 전용 · 후보 ${s.bleFieldCandidateCount}건 · GATT 미실행"
            s.bleFieldTrialWaitingForDeparture -> "이탈 후 다음 접근 대기 · 완료 ${s.bleFieldTrialCompletedCount}회"
            s.bleDiagnosticActive -> "읽기 전용 GATT 진단 ${s.bleFieldTrialAttemptCount}회차"
            s.bleFieldTrialStarting -> "주말 BLE 반복 시험 시작 중"
            else -> "차량 출현 대기 · 완료 ${s.bleFieldTrialCompletedCount}회"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (field) "주말 BLE 반복 시험" else "등록 차량 접근 관찰 서비스")
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, if (field) "시험 즉시 중지" else "관찰 즉시 종료", stop).build())
            .build()
    }

    private fun updateNotification() {
        val token = requestId ?: return
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(token)) }
        catch (_: Exception) { runtime.stopObservation("OBSERVATION_NOTIFICATIONS_BLOCKED", token) }
    }

    internal fun startObserving() {
        runtime.refresh()
        val token = requestId ?: return
        if (!runtime.ownsObservation(this, token) || observedAssociationId != null || runtime.state.value.bleFieldTrialStopping) return
        runtime.observationRequestBlockedReason()?.let { fail(it, token); return }
        val id = runtime.singleAssociationId() ?: run { fail("ASSOCIATION_REMOVED", token); return }
        try {
            observedAssociationId = id
            runtime.cdm?.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build()) ?: error("CDM")
            runtime.observationAccepted(this, token)
        } catch (_: SecurityException) { fail("OBSERVE_SECURITY_DENIED", token) }
        catch (_: Exception) { fail("OBSERVE_UNAVAILABLE", token) }
    }

    internal fun fieldPresence(event: Int, receivedElapsedMs: Long) {
        val token = requestId ?: return
        val policy = fieldPolicy ?: return
        if (!runtime.ownsBleField(this, token) || !policy.accepting || runtime.state.value.bleFieldTrialStopping) return
        val signal = when (event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> BleEvidenceSignal.CDM_BLE_APPEARED
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> BleEvidenceSignal.CDM_BLE_DISAPPEARED
            DevicePresenceEvent.EVENT_BT_CONNECTED -> BleEvidenceSignal.BT_CONNECTED
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> BleEvidenceSignal.BT_DISCONNECTED
            else -> return
        }
        val wasCdmPresent = policy.cdmPresent
        val decision = policy.signal(signal, receivedElapsedMs)
        if (!policy.accepting || !runtime.ownsBleField(this, token) || runtime.state.value.bleFieldTrialStopping) return
        if (wasCdmPresent != policy.cdmPresent) {
            runtime.update { it.copy(
                appearedCount = it.appearedCount + if (policy.cdmPresent) 1 else 0,
                disappearedCount = it.disappearedCount + if (policy.cdmPresent) 0 else 1) }
            runtime.event(if (policy.cdmPresent) "BLE_FIELD_APPEARED" else "BLE_FIELD_DISAPPEARED")
        }
        applyDecision(decision)
    }

    private fun applyDecision(decision: BleFieldTrialPolicy.Decision) {
        val policy = fieldPolicy ?: return
        val token = requestId ?: return
        if (!policy.accepting || !runtime.ownsBleField(this, token) || runtime.state.value.bleFieldTrialStopping) return
        syncFieldState()
        decision.cancelAttempt?.let { if (fieldPolicy?.currentAttempt == it) probe?.cancel("DEPARTED") }
        decision.startAttempt?.let(::startAttempt)
        schedulePolicy()
        updateNotification()
    }

    private fun startAttempt(attempt: Long) {
        val policy = fieldPolicy ?: return
        val token = requestId ?: return
        if (policy.config.observationOnly || policy.config.scanFilterMode == BleScanFilterMode.VEHICLE_NAME ||
            policy.config.safetyBlockedReason() != null || policy.currentAttempt != attempt
        ) return
        if (!policy.accepting || !runtime.ownsBleField(this, token) || runtime.state.value.bleFieldTrialStopping) {
            attemptFinished(attempt, BleProbeState(status = BleProbeStatus.CANCELED, reason = "FIELD_OWNER_NOT_ARMED"), false)
            return
        }
        scanner?.pause()
        // pause 실패가 stop을 요청했으면 connectGatt를 발급하지 않는다.
        if (!policy.accepting || runtime.state.value.bleFieldTrialStopping) {
            attemptFinished(attempt, BleProbeState(status = BleProbeStatus.CANCELED, reason = "FIELD_OWNER_NOT_ARMED"), false)
            return
        }
        syncFieldState()
        try {
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeyTesla:BleFieldGatt")?.apply {
                setReferenceCounted(false)
                acquire(BleFieldTrialPolicy.CANDIDATE_BUDGET_MS + 5_000L)
            }
        } catch (_: Exception) {
            wakeLock = null
            runtime.event("BLE_FIELD_WAKE_LOCK_UNAVAILABLE")
        }
        checkNotNull(probe).start(attempt, backgroundConnect = policy.config.backgroundConnect, deadline = policy.attemptDeadline)
    }

    private fun startSupplementalScan() {
        val policy = fieldPolicy ?: return
        if (!policy.config.supplementalScan || !policy.accepting) return
        val id = observedAssociationId ?: return
        val token = requestId ?: return
        if (!runtime.ownsBleField(this, token) || runtime.state.value.bleFieldTrialStopping) return
        policy.config.safetyBlockedReason()?.let { fail(it, token); return }
        val advertisedName = if (policy.config.scanFilterMode == BleScanFilterMode.VEHICLE_NAME) {
            runtime.consumeBleFieldAdvertisedName(this, token) ?: run { fail("BLE_SCAN_NAME_UNAVAILABLE", token); return }
        } else null
        scanner = BleSupplementalScanner(this, runtime,
            onMatch = { received, rssi, firstMatch ->
                val token = requestId
                if (token != null && runtime.ownsBleField(this, token) && policy.accepting &&
                    !runtime.state.value.bleFieldTrialStopping
                ) {
                    val candidatesBefore = policy.candidateCount
                    val decision = policy.signal(BleEvidenceSignal.FILTERED_SCAN, received)
                    if (firstMatch || candidatesBefore != policy.candidateCount) {
                        runtime.recordBleEvidence(BleEventEvidence(BleEvidenceKind.SCAN_MATCH,
                            signal = BleEvidenceSignal.FILTERED_SCAN, candidate = policy.candidateCount.takeIf { it > 0 },
                            receivedElapsedMs = received, queueDelayMs = runtime.now() - received,
                            rssi = rssi, scanRunning = true))
                    }
                    if (decision !== BleFieldTrialPolicy.Decision.NONE || candidatesBefore != policy.candidateCount) {
                        applyDecision(decision)
                    } else schedulePolicy()
                }
            },
            onRunning = { running -> runtime.update { it.copy(bleFieldScanRunning = running) } },
            onFailure = { reason, code ->
                runtime.update { it.copy(bleFieldScanFailure = code, bleFieldScanRunning = false) }
                runtime.stopBleFieldTrial(reason)
            })
        scanner?.start(id, policy.config, advertisedName)
        if (probe?.hasActiveGatt == true || policy.currentAttempt != null) scanner?.pause()
    }

    private fun schedulePolicy() {
        val policy = fieldPolicy ?: return
        val at = policy.nextWakeAt
        if (!policy.accepting || at == null) {
            clearPolicyTimer()
            return
        }
        // 스캔 매 패킷으로 만료가 연장돼도 기존의 더 이른 timer 하나를 재사용한다.
        if (policyTick != null && policyTickAt?.let { it <= at } == true) return
        clearPolicyTimer()
        val token = requestId ?: return
        val task = Runnable {
            policyTick = null
            policyTickAt = null
            if (fieldPolicy !== policy || !policy.accepting || !runtime.ownsBleField(this, token) ||
                runtime.state.value.bleFieldTrialStopping
            ) return@Runnable
            applyDecision(policy.tick())
        }
        policyTick = task
        policyTickAt = at
        runtime.handler.postDelayed(task, (at - runtime.now()).coerceAtLeast(0))
    }

    private fun clearPolicyTimer() {
        policyTick?.let { runtime.handler.removeCallbacks(it) }
        policyTick = null
        policyTickAt = null
    }

    private fun stage(attempt: Long, state: BleProbeState) {
        if (fieldPolicy?.currentAttempt != attempt) return
        runtime.recordBleTrial("BLE_FIELD_STAGE", runtime.bleTrialSummary(attempt, state, runtime.state.value.bleDiagnosticActive))
        updateNotification()
    }

    private fun attemptFinished(attempt: Long, state: BleProbeState, leaseRetained: Boolean) {
        val policy = fieldPolicy ?: return
        if (policy.currentAttempt != attempt) return
        releaseWakeLock()
        // probe가 실제 lease를 반환한 뒤 들어온다. 실패한 close는 예약을 유지한 상태로 기록한다.
        runtime.recordBleTrial("BLE_FIELD_TRIAL_FINISHED", runtime.bleTrialSummary(attempt, state, leaseRetained))
        val next = policy.finished(attempt, state, !leaseRetained)
        syncFieldState()
        if (leaseRetained) {
            runtime.recordBleTrial("BLE_FIELD_CLEANUP_FAILED", runtime.bleTrialSummary(attempt, state, true))
            runtime.stopBleFieldTrial("LOCAL_CLOSE_FAILED_RESTART_REQUIRED")
            finishField(cleanupFailed = true)
        } else if (runtime.state.value.bleFieldTrialStopping) finishField()
        else if (next.startAttempt != null) applyDecision(next)
        else {
            scanner?.resume()
            if (!policy.accepting || requestId == null || runtime.state.value.bleFieldTrialStopping) return
            schedulePolicy()
            runtime.event("BLE_FIELD_WAITING")
            updateNotification()
        }
    }

    private fun syncFieldState() {
        val p = fieldPolicy ?: return
        runtime.update { it.copy(present = p.present,
            bleFieldCandidateCount = p.candidateCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            bleFieldTrialAttemptCount = p.attemptCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            bleFieldTrialCompletedCount = p.completedCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            bleFieldTrialWaitingForDeparture = p.waitingForDeparture) }
    }

    private fun scheduleReadiness(token: String) {
        var heartbeatAt = runtime.now() + HEARTBEAT_MS
        val task = object : Runnable {
            override fun run() {
                if (!runtime.ownsBleField(this@ObservationService, token) || runtime.state.value.bleFieldTrialStopping) return
                runtime.refresh()
                if (!runtime.ownsBleField(this@ObservationService, token) || runtime.state.value.bleFieldTrialStopping) return
                if (runtime.now() >= heartbeatAt) {
                    // 생존성 행은 이전 완료 회차의 증거를 재사용하지 않는다.
                    runtime.recordBleTrial("BLE_FIELD_HEARTBEAT", runtime.bleTrialSummary(0, BleProbeState(), false))
                    heartbeatAt = runtime.now() + HEARTBEAT_MS
                }
                runtime.handler.postDelayed(this, READINESS_MS)
            }
        }
        readinessTick = task
        runtime.handler.postDelayed(task, READINESS_MS)
    }

    internal fun stopBleField(reason: String) {
        fieldPolicy?.stop()
        clearPolicyTimer()
        scanner?.stop()
        // 신규 CDM 트리거를 먼저 봉인한다. stop 요청 예외여도 로컬 observed ID는 복원하지 않는다.
        sealPresence()
        readinessTick?.let { runtime.handler.removeCallbacks(it) }
        readinessTick = null
        updateNotification()
        if (fieldPolicy?.currentAttempt == null) finishField()
        else if (runtime.bleProbeState.value.status == BleProbeStatus.CLEANUP_FAILED) finishField(cleanupFailed = true)
        else probe?.cancel(reason)
    }

    private fun finishField(cleanupFailed: Boolean = false) {
        val token = requestId ?: return
        sealPresence()
        clearPolicyTimer()
        scanner?.stop()
        scanner = null
        readinessTick?.let { runtime.handler.removeCallbacks(it) }
        readinessTick = null
        releaseWakeLock()
        runtime.finishBleField(this, token, cleanupFailed)
        requestId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (lastStartId != 0) stopSelf(lastStartId) else stopSelf()
    }

    private fun releaseWakeLock() {
        val held = wakeLock
        wakeLock = null
        try { if (held?.isHeld == true) held.release() } catch (_: Exception) { runtime.event("BLE_FIELD_WAKE_LOCK_RELEASE_FAILED") }
    }

    private fun fail(reason: String, token: String) {
        runtime.stopObservation(reason, token)
        if (mode == Mode.OBSERVATION || !runtime.ownsObservation(this, token)) finishObserving()
    }

    private fun sealPresence() {
        val id = observedAssociationId
        observedAssociationId = null
        if (id != null) {
            var failed = false
            try { runtime.cdm?.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build()) ?: error("CDM") }
            catch (_: Exception) { failed = true }
            runtime.event(if (failed) "OBSERVE_STOP_FAILED_LOCAL_GATE_CLOSED" else "OBSERVE_STOPPED")
        }
    }

    internal fun finishObserving() {
        if (mode == Mode.BLE_FIELD && requestId?.let { runtime.ownsObservation(this, it) } == true) {
            runtime.stopBleFieldTrial("OBSERVATION_SERVICE_DESTROYED")
            return
        }
        requestId?.let(runtime::discardBleFieldAdvertisedName)
        sealPresence()
        requestId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (lastStartId != 0) stopSelf(lastStartId) else stopSelf()
    }

    override fun onDestroy() {
        val token = requestId
        token?.let(runtime::discardBleFieldAdvertisedName)
        fieldPolicy?.stop()
        clearPolicyTimer()
        scanner?.stop()
        scanner = null
        readinessTick?.let { runtime.handler.removeCallbacks(it) }
        readinessTick = null
        if (token != null && runtime.ownsObservation(this, token)) runtime.stopObservation("OBSERVATION_SERVICE_DESTROYED", token)
        if (mode == Mode.OBSERVATION || requestId == null) finishObserving()
        // FIELD 정리 중에는 Application handler와 기존 probe만 bounded close를 마친다. 새 회차 발급은 봉인됐다.
        super.onDestroy()
    }

    companion object {
        internal const val CHANNEL = "vehicle_observation"
        internal const val ACTION_START = "com.heytesla.app.START_OBSERVATION"
        internal const val ACTION_STOP = "com.heytesla.app.STOP_OBSERVATION"
        internal const val REQUEST_ID = "observation_request_id"
        internal const val MODE = "observation_mode"
        private const val NOTIFICATION_ID = 101
        private const val READINESS_MS = 5_000L
        private const val HEARTBEAT_MS = 30 * 60 * 1_000L
    }
}
