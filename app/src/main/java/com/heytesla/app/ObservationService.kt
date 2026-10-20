package com.heytesla.app

import android.app.KeyguardManager
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
import android.os.Binder
import android.os.PowerManager

/** CDM/policy/GATT 소유자. 보조 스캔 모드는 bound-only이며 BeaconService만 FGS 알림을 소유한다. */
class ObservationService : Service() {
    internal enum class Mode { OBSERVATION, BLE_FIELD }

    private val runtime get() = (application as DiagnosticApp).runtime
    private var requestId: String? = null
    private var mode = Mode.OBSERVATION
    private var lastStartId = 0
    private var beaconForeground = false
    private var finishingField = false
    private var fieldPolicy: BleFieldTrialPolicy? = null
    private var probe: BleConnectionProbe? = null
    private var scanner: BleSupplementalScanner? = null
    private var approachRequest: TeslaApproachReadRequest? = null
    private var approachPauseConfirmed = false
    private var destroyed = false
    private var policyTick: Runnable? = null
    private var policyTickAt: Long? = null
    private var candidateDiagnosticsPublisher: BleCandidateDiagnosticsPublisher? = null
    private var candidateScanRunning: Boolean? = null
    private var readinessTick: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    internal var observedAssociationId: Int? = null
        private set

    internal fun ownsApproachRead(request: TeslaApproachReadRequest): Boolean =
        !destroyed && approachRequest === request && request.owner === this &&
            requestId == request.fieldToken && fieldPolicy?.config?.authenticatedReadOnly == true &&
            fieldPolicy?.accepting == true && fieldPolicy?.present == true &&
            fieldPolicy?.currentAttempt == request.attempt && fieldPolicy?.candidateCount == request.candidate &&
            observedAssociationId == request.associationId

    internal fun approachScanPaused(request: TeslaApproachReadRequest): Boolean =
        approachRequest === request && approachPauseConfirmed
    internal inner class LocalBinder : Binder() {
        fun start(intent: Intent) {
            onStartCommand(intent, 0, 0)
        }
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

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
                if (startId != 0) try { promote(token ?: "unarmed") } catch (_: Exception) { }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            runtime.event("OBSERVATION_STALE_START_IGNORED")
            return START_NOT_STICKY
        }

        candidateDiagnosticsPublisher?.stop()
        candidateDiagnosticsPublisher = null
        candidateScanRunning = null
        fieldPolicy = null
        finishingField = false
        runtime.update { it.copy(bleCandidateDiagnostics = null) }
        requestId = token
        mode = requestedMode
        if (mode == Mode.BLE_FIELD) {
            val config = runtime.state.value.bleFieldConfig
            beaconForeground = config.supplementalScan
            config.safetyBlockedReason()?.let { fail(it, token); return START_NOT_STICKY }
            val policy = BleFieldTrialPolicy(config, runtime::now, runtime::recordBleEvidence)
            fieldPolicy = policy
            candidateDiagnosticsPublisher = BleCandidateDiagnosticsPublisher(
                now = runtime::now,
                postDelayed = { task, delay -> runtime.handler.postDelayed(task, delay) },
                removeCallbacks = { runtime.handler.removeCallbacks(it) },
                isCurrent = { fieldPolicy === policy && requestId == token && mode == Mode.BLE_FIELD &&
                    runtime.ownsBleField(this, token) },
                snapshot = { policy.diagnostics(candidateScanRunning) },
                publish = { snapshot ->
                    runtime.update { it.copy(bleCandidateDiagnostics = snapshot) }
                    runtime.event("BLE_CANDIDATE_DIAGNOSTICS")
                },
            )
            if (!config.observationOnly && config.scanFilterMode == BleScanFilterMode.ASSOCIATION_ADDRESS) {
                probe = BleConnectionProbe(this, runtime.bleProbeState, runtime, this, token,
                    { attempt, state -> if (fieldPolicy === policy && requestId == token) stage(attempt, state) },
                    { attempt, state, retained ->
                        if (fieldPolicy === policy && requestId == token) attemptFinished(attempt, state, retained)
                    })
            }
        }
        try {
            if (!beaconForeground) promote(token)
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
            if (mode == Mode.BLE_FIELD) syncFieldState()
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
            s.teslaKeyCleanupFailed || s.teslaApproachRead?.stage == "CLEANUP_FAILED" ||
                runtime.bleProbeState.value.status == BleProbeStatus.CLEANUP_FAILED -> "재시작 필요"
            s.bleFieldTrialStopping || s.teslaApproachRead?.stage == "CLEANING_UP" ||
                runtime.bleProbeState.value.status == BleProbeStatus.CLEANING_UP -> "정리 중"
            s.bleFieldScanFailure != null -> "보조 스캔 실패 · 시험 중지"
            s.bleFieldScanRecoveryAt != null -> if (fieldPolicy?.config?.observationOnly == true) {
                "OS 스캔 오류 · 제한복구 대기 · GATT 없음"
            } else "OS 스캔 오류 · 제한복구 대기"
            fieldPolicy?.config?.observationOnly == true -> "관찰 전용 · 후보 ${s.bleFieldCandidateCount}건 · GATT 미실행"
            s.bleFieldConfig.authenticatedReadOnly && (s.teslaKeyDiagnosticActive ||
                s.teslaApproachRead?.stage == "PAUSING_SCAN") -> "기존 앱 키로 BODY·DRIVE 읽기 조회 중"
            s.bleFieldTrialWaitingForDeparture -> "이탈 후 다음 접근 대기 · 완료 ${s.bleFieldTrialCompletedCount}회"
            s.bleFieldConfig.authenticatedReadOnly && s.present &&
                s.teslaApproachRead?.localClosed == true -> "연결 정리됨 · 새 광고에서 제한 재조회 대기"
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
        if (beaconForeground && !AndroidBleScanTransport.foregroundRunning(this)) return
        try { getSystemService(NotificationManager::class.java).notify(
            if (beaconForeground) ALTBEACON_NOTIFICATION_ID else NOTIFICATION_ID, notification(token)) }
        catch (_: Exception) { runtime.stopObservation("OBSERVATION_NOTIFICATIONS_BLOCKED", token) }
    }

    internal fun scanStatusChanged() {
        updateNotification()
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
        decision.cancelAttempt?.let {
            if (policy.currentAttempt == it) {
                val reason = if (runtime.now() >= (policy.attemptDeadline ?: Long.MAX_VALUE)) "DEADLINE_EXPIRED" else "DEPARTED"
                if (policy.config.authenticatedReadOnly) cancelApproachAttempt(it, reason) else probe?.cancel(reason)
            }
        }
        decision.startAttempt?.let(::startAttempt)
        schedulePolicy()
        updateNotification()
    }

    private fun startAttempt(attempt: Long) {
        val policy = fieldPolicy ?: return
        val token = requestId ?: return
        if (policy.config.observationOnly ||
            policy.config.scanFilterMode == BleScanFilterMode.VEHICLE_NAME && !policy.config.authenticatedReadOnly ||
            policy.config.safetyBlockedReason() != null || policy.currentAttempt != attempt
        ) return
        if (!policy.accepting || !runtime.ownsBleField(this, token) || runtime.state.value.bleFieldTrialStopping) {
            attemptFinished(attempt, BleProbeState(status = BleProbeStatus.CANCELED, reason = "FIELD_OWNER_NOT_ARMED"), false)
            return
        }
        if (policy.config.authenticatedReadOnly && approachRequest?.attempt != attempt) {
            finishUnstartedApproach(attempt, "SCAN_DEVICE_UNAVAILABLE")
            return
        }
        if (policy.config.authenticatedReadOnly) runtime.recordTeslaApproachRead("TESLA_APPROACH_READ_STARTED",
            TeslaApproachReadSummary(candidate = policy.candidateCount, attempt = attempt, stage = "PAUSING_SCAN",
                query = TeslaBleQuery.BODY_STATUS.name, leaseRetained = true))
        val scan = scanner
        approachPauseConfirmed = false
        if (scan != null) scan.pause {
            if (approachRequest?.attempt == attempt) approachPauseConfirmed = true
            startGattAfterPause(attempt, policy, token)
        }
        else if (policy.config.authenticatedReadOnly) finishUnstartedApproach(attempt, "BLE_SCAN_FGS_NOT_RUNNING")
        else startGattAfterPause(attempt, policy, token)
    }

    private fun startGattAfterPause(attempt: Long, policy: BleFieldTrialPolicy, token: String) {
        if (fieldPolicy !== policy || policy.currentAttempt != attempt || !policy.accepting ||
            !runtime.ownsBleField(this, token) || runtime.state.value.bleFieldTrialStopping
        ) return
        if (runtime.now() >= (policy.attemptDeadline ?: Long.MAX_VALUE)) {
            if (policy.config.authenticatedReadOnly) finishUnstartedApproach(attempt, "DEADLINE_EXPIRED")
            else attemptFinished(attempt, BleProbeState(status = BleProbeStatus.TIMED_OUT,
                reason = "DEADLINE_EXPIRED", localClosed = true), false)
            return
        }
        if (policy.config.authenticatedReadOnly) {
            val request = approachRequest ?: return
            runtime.approachOwnerReason(request)?.let { finishUnstartedApproach(attempt, it); return }
        }
        syncFieldState(forceDiagnostics = true)
        try {
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeyTesla:BleFieldGatt")?.apply {
                setReferenceCounted(false)
                acquire(BleFieldTrialPolicy.CANDIDATE_BUDGET_MS + 5_000L)
            }
        } catch (_: Exception) {
            wakeLock = null
            runtime.event("BLE_FIELD_WAKE_LOCK_UNAVAILABLE")
        }
        if (policy.config.authenticatedReadOnly) {
            val request = checkNotNull(approachRequest)
            runtime.startTeslaApproachRead(request) { summary, retained ->
                if (fieldPolicy !== policy || requestId != token) return@startTeslaApproachRead
                val status = when (summary.stage) {
                    "COMPLETE" -> BleProbeStatus.COMPLETE
                    "BLOCKED" -> BleProbeStatus.BLOCKED
                    "CANCELED" -> BleProbeStatus.CANCELED
                    "TIMED_OUT" -> BleProbeStatus.TIMED_OUT
                    "CLEANUP_FAILED" -> BleProbeStatus.CLEANUP_FAILED
                    else -> BleProbeStatus.FAILED
                }
                attemptFinished(attempt, BleProbeState(status = status, reason = summary.reason,
                    localClosed = summary.localClosed), retained)
            }
        } else checkNotNull(probe).start(attempt, backgroundConnect = policy.config.backgroundConnect, deadline = policy.attemptDeadline)
    }

    private fun finishUnstartedApproach(attempt: Long, reason: String) {
        val policy = fieldPolicy ?: return
        if (policy.currentAttempt != attempt) return
        val status = when {
            reason == "DEADLINE_EXPIRED" -> BleProbeStatus.TIMED_OUT
            !policy.accepting || reason == "DEPARTED" -> BleProbeStatus.CANCELED
            else -> BleProbeStatus.BLOCKED
        }
        val summary = TeslaApproachReadSummary(candidate = policy.candidateCount, attempt = attempt,
            stage = status.name, outcome = if (status == BleProbeStatus.CANCELED) "CANCELED" else "NOT_SENT",
            query = TeslaBleQuery.BODY_STATUS.name, reason = reason, localClosed = true)
        runtime.recordTeslaApproachRead("TESLA_APPROACH_READ_BLOCKED", summary)
        attemptFinished(attempt, BleProbeState(status = status, reason = reason, localClosed = true), false)
    }

    private fun cancelApproachAttempt(attempt: Long, reason: String) {
        val token = requestId ?: return
        if (runtime.approachReadInFlight(this, token, attempt)) runtime.cancelTeslaApproachRead(this, token, attempt, reason)
        else finishUnstartedApproach(attempt, reason)
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
            onMatch = { result, firstMatch ->
                val received = result.timestampNanos / 1_000_000L
                val rssi = result.rssi
                if (fieldPolicy === policy && requestId == token && runtime.ownsBleField(this, token) && policy.accepting &&
                    !runtime.state.value.bleFieldTrialStopping
                ) {
                    val advertisementsBefore = policy.acceptedAdvertisementCount
                    val candidatesBefore = policy.candidateCount
                    val decision = policy.signal(BleEvidenceSignal.FILTERED_SCAN, received)
                    if (policy.acceptedAdvertisementCount != advertisementsBefore) candidateDiagnosticsPublisher?.markDirty()
                    if (policy.config.authenticatedReadOnly && decision.startAttempt != null) {
                        val device = result.device
                        val deadline = policy.attemptDeadline
                        if (device != null && deadline != null) {
                            approachRequest = TeslaApproachReadRequest(this, token, policy.candidateCount,
                                decision.startAttempt, id, device, result.timestampNanos, deadline,
                                advertisementConnectable = result.isConnectable)
                        }
                    }
                    if (firstMatch || candidatesBefore != policy.candidateCount) {
                        // 처리 시점 상태다. 배치 안 광고의 원래 수신 시점 잠금 상태로 해석하지 않는다.
                        runtime.recordBleEvidence(BleEventEvidence(BleEvidenceKind.SCAN_MATCH,
                            signal = BleEvidenceSignal.FILTERED_SCAN, candidate = policy.candidateCount.takeIf { it > 0 },
                            receivedElapsedMs = received, queueDelayMs = runtime.now() - received,
                            rssi = rssi, scanRunning = true,
                            interactive = getSystemService(PowerManager::class.java)?.isInteractive,
                            deviceLocked = getSystemService(KeyguardManager::class.java)?.isDeviceLocked))
                    }
                    if (decision !== BleFieldTrialPolicy.Decision.NONE || candidatesBefore != policy.candidateCount) {
                        applyDecision(decision)
                    } else schedulePolicy()
                }
            },
            onRunning = { running ->
                if (fieldPolicy === policy && requestId == token && runtime.ownsBleField(this, token)) {
                    candidateScanRunning = running
                    runtime.update { it.copy(bleFieldScanRunning = running) }
                    syncFieldState(forceDiagnostics = true)
                }
            },
            onFailure = failure@ { reason, code ->
                if (fieldPolicy !== policy || requestId != token || !runtime.ownsBleField(this, token)) return@failure
                runtime.update { it.copy(bleFieldScanFailure = code, bleFieldScanRunning = false) }
                val pending = approachRequest
                if (pending != null && !runtime.approachReadInFlight(this, token, pending.attempt)) {
                    val retained = code == BleSupplementalScanner.FAILURE_STOP
                    val summary = TeslaApproachReadSummary(candidate = pending.candidate, attempt = pending.attempt,
                        stage = if (retained) "CLEANUP_FAILED" else "BLOCKED", query = TeslaBleQuery.BODY_STATUS.name,
                        reason = reason, localClosed = true, leaseRetained = retained)
                    runtime.recordTeslaApproachRead(if (retained) "TESLA_APPROACH_READ_CLEANUP_FAILED" else "TESLA_APPROACH_READ_BLOCKED", summary)
                    attemptFinished(pending.attempt, BleProbeState(status = if (retained) BleProbeStatus.CLEANUP_FAILED
                        else BleProbeStatus.BLOCKED, reason = reason, localClosed = true), retained)
                }
                if (policy.config.authenticatedReadOnly && pending == null && code == BleSupplementalScanner.FAILURE_STOP) {
                    runtime.state.value.teslaApproachRead?.let { last ->
                        runtime.recordTeslaApproachRead("TESLA_APPROACH_READ_CLEANUP_FAILED",
                            last.copy(stage = "CLEANUP_FAILED", outcome = if (last.bodyVerified && !last.driveVerified) "PARTIAL" else "UNKNOWN",
                                reason = "BLE_SCAN_STOP_FAILED", leaseRetained = true))
                    }
                }
                runtime.stopBleFieldTrial(reason)
            },
            foregroundNotification = notification(token))
        scanner?.start(id, policy.config, advertisedName)
        if (!policy.config.authenticatedReadOnly && (probe?.hasActiveGatt == true || policy.currentAttempt != null)) scanner?.pause()
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
        val readFinal = runtime.state.value.teslaApproachRead?.takeIf { policy.config.authenticatedReadOnly && it.attempt == attempt }
        releaseWakeLock()
        approachRequest?.takeIf { it.attempt == attempt }?.clear()
        approachRequest = null
        approachPauseConfirmed = false
        // probe가 실제 lease를 반환한 뒤 들어온다. 실패한 close는 예약을 유지한 상태로 기록한다.
        runtime.recordBleTrial("BLE_FIELD_TRIAL_FINISHED", runtime.bleTrialSummary(attempt, state, leaseRetained))
        val next = policy.finished(attempt, state, !leaseRetained)
        syncFieldState(forceDiagnostics = true)
        if (leaseRetained) {
            runtime.recordBleTrial("BLE_FIELD_CLEANUP_FAILED", runtime.bleTrialSummary(attempt, state, true))
            runtime.stopBleFieldTrial("LOCAL_CLOSE_FAILED_RESTART_REQUIRED")
            finishField(cleanupFailed = true)
        } else if (runtime.state.value.bleFieldTrialStopping) {
            readFinal?.let { runtime.recordTeslaApproachRead("TESLA_APPROACH_READ_FINISHED", it) }
            finishField()
        }
        else if (next.startAttempt != null) applyDecision(next)
        else {
            readFinal?.let { runtime.recordTeslaApproachRead("TESLA_APPROACH_READ_STAGE", it.copy(stage = "RESUMING_SCAN")) }
            scanner?.resume()
            if (!policy.accepting || requestId == null || runtime.state.value.bleFieldTrialStopping) return
            readFinal?.let { runtime.recordTeslaApproachRead("TESLA_APPROACH_READ_FINISHED", it) }
            schedulePolicy()
            runtime.event("BLE_FIELD_WAITING")
            updateNotification()
        }
    }

    private fun syncFieldState(forceDiagnostics: Boolean = false) {
        val p = fieldPolicy ?: return
        val token = requestId ?: return
        if (!runtime.ownsBleField(this, token)) return
        runtime.update { it.copy(present = p.present,
            bleFieldCandidateCount = p.candidateCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            bleFieldTrialAttemptCount = p.attemptCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            bleFieldTrialCompletedCount = p.completedCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            bleFieldTrialWaitingForDeparture = p.waitingForDeparture) }
        val publisher = candidateDiagnosticsPublisher ?: return
        val previous = publisher.lastSnapshot
        if (forceDiagnostics || previous == null ||
            previous.candidate != p.candidateCount.takeIf { it > 0 } ||
            previous.candidatePresent != p.present || previous.readInFlight != (p.currentAttempt != null) ||
            previous.waitingForDeparture != p.waitingForDeparture
        ) publisher.flush() else if (previous.expiresElapsedMs != p.expiresElapsedMs ||
            previous.acceptedAdvertisementCount != p.acceptedAdvertisementCount
        ) publisher.markDirty()
    }

    private fun scheduleReadiness(token: String) {
        var heartbeatAt = runtime.now() + HEARTBEAT_MS
        val policy = fieldPolicy
        val task = object : Runnable {
            override fun run() {
                if (fieldPolicy !== policy || !runtime.ownsBleField(this@ObservationService, token) ||
                    runtime.state.value.bleFieldTrialStopping
                ) return
                runtime.refresh()
                if (!runtime.ownsBleField(this@ObservationService, token) || runtime.state.value.bleFieldTrialStopping) return
                runtime.observationRequestBlockedReason()?.let { fail(it, token); return }
                scanner?.reconcile()
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
        candidateDiagnosticsPublisher?.stop()
        syncFieldState(forceDiagnostics = true)
        clearPolicyTimer()
        // stop/release는 GATT·키 IO·callback 정리 뒤 finishField에서만 한다. pause는 새 광고/GATT action을 봉인한다.
        scanner?.pause()
        // 신규 CDM 트리거를 먼저 봉인한다. stop 요청 예외여도 로컬 observed ID는 복원하지 않는다.
        sealPresence()
        readinessTick?.let { runtime.handler.removeCallbacks(it) }
        readinessTick = null
        updateNotification()
        val attempt = fieldPolicy?.currentAttempt
        if (attempt == null) finishField()
        else if (fieldPolicy?.config?.authenticatedReadOnly == true) {
            if (runtime.state.value.teslaKeyCleanupFailed) finishField(cleanupFailed = true)
            else cancelApproachAttempt(attempt, reason)
        } else if (runtime.bleProbeState.value.status == BleProbeStatus.CLEANUP_FAILED) finishField(cleanupFailed = true)
        else if (probe?.hasActiveGatt != true) {
            attemptFinished(attempt, BleProbeState(status = BleProbeStatus.CANCELED, reason = reason), false)
        } else probe?.cancel(reason)
    }

    private fun finishField(cleanupFailed: Boolean = false) {
        val token = requestId ?: return
        val policy = fieldPolicy
        sealPresence()
        clearPolicyTimer()
        candidateDiagnosticsPublisher?.stop()
        if (finishingField) return
        finishingField = true
        readinessTick?.let { runtime.handler.removeCallbacks(it) }
        readinessTick = null
        releaseWakeLock()
        val scan = scanner
        fun finish(scanStopped: Boolean) {
            if (fieldPolicy !== policy || requestId != token) return
            scanner = null
            syncFieldState(forceDiagnostics = true)
            candidateDiagnosticsPublisher?.stop()
            runtime.finishBleField(this, token, cleanupFailed || !scanStopped)
            requestId = null
            if (!beaconForeground) stopForeground(STOP_FOREGROUND_REMOVE)
            if (lastStartId != 0) stopSelf(lastStartId) else stopSelf()
        }
        if (scan == null) finish(true) else scan.stop(::finish)
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
        candidateDiagnosticsPublisher?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (lastStartId != 0) stopSelf(lastStartId) else stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        val token = requestId
        token?.let(runtime::discardBleFieldAdvertisedName)
        fieldPolicy?.stop()
        clearPolicyTimer()
        candidateDiagnosticsPublisher?.stop()
        syncFieldState(forceDiagnostics = true)
        // 남아 있는 IO/GATT 회수 전에 BeaconService/scan lease를 먼저 해제하지 않는다.
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
        internal const val ALTBEACON_NOTIFICATION_ID = 102
        private const val READINESS_MS = 5_000L
        private const val HEARTBEAT_MS = 30 * 60 * 1_000L
    }
}
