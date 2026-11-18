package com.heytesla.app

import com.heytesla.app.diagnostics.BleCandidateDiagnosticsPublisher
import com.heytesla.app.ui.vehicleServiceNotificationText
import com.heytesla.app.vehicle.ble.BleProbeState
import com.heytesla.app.vehicle.ble.BleProbeStatus
import com.heytesla.app.vehicle.detection.BleEventEvidence
import com.heytesla.app.vehicle.detection.BleEvidenceKind
import com.heytesla.app.vehicle.detection.BleEvidenceSignal
import com.heytesla.app.vehicle.detection.BleFieldTrialPolicy
import com.heytesla.app.vehicle.detection.BleSupplementalScanner
import com.heytesla.app.vehicle.connection.TeslaServiceConnectionRequest
import com.heytesla.app.vehicle.connection.TeslaConnectionState
import com.heytesla.app.vehicle.connection.TeslaConnectionStage

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

/** 현재 BLE 감지·인증 연결 소유자. bound-only이며 BeaconService가 감지 FGS를 소유한다. */
class ObservationService : Service() {
    private val runtime get() = (application as DiagnosticApp).runtime
    private var requestId: String? = null
    private var lastStartId = 0
    private var finishingField = false
    private var fieldPolicy: BleFieldTrialPolicy? = null
    private var scanner: BleSupplementalScanner? = null
    private var serviceConnectionRequest: TeslaServiceConnectionRequest? = null
    private var serviceConnectionPauseConfirmed = false
    private var lastNotificationToken: String? = null
    private var lastNotificationText: String? = null
    private var destroyed = false
    private var policyTick: Runnable? = null
    private var policyTickAt: Long? = null
    private var candidateDiagnosticsPublisher: BleCandidateDiagnosticsPublisher? = null
    private var candidateScanRunning: Boolean? = null
    private var readinessTick: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewedAt: Long? = null
    private var wakeLockCleanupFailed = false
    internal var observedAssociationId: Int? = null
        private set

    internal fun ownsServiceConnection(request: TeslaServiceConnectionRequest): Boolean =
        !destroyed && serviceConnectionRequest === request && request.owner === this &&
            requestId == request.fieldToken &&
            fieldPolicy?.accepting == true && fieldPolicy?.present == true &&
            fieldPolicy?.currentAttempt == request.attempt && fieldPolicy?.candidateCount == request.candidate &&
            observedAssociationId == request.associationId

    internal fun serviceConnectionScanPaused(request: TeslaServiceConnectionRequest): Boolean =
        serviceConnectionRequest === request && serviceConnectionPauseConfirmed

    internal fun serviceConnectionMonitoring(request: TeslaServiceConnectionRequest) {
        if (!ownsServiceConnection(request)) return
        fieldPolicy?.serviceMonitoring(request.attempt)
        schedulePolicy()
    }

    internal fun serviceParkRefreshed(request: TeslaServiceConnectionRequest, receivedAt: Long) {
        if (!ownsServiceConnection(request) || receivedAt > runtime.now() || runtime.now() - receivedAt > 5_000L) return
        if (wakeLockRenewedAt == null || receivedAt - checkNotNull(wakeLockRenewedAt) >= 5_000L) {
            // 새 인증 P만 갱신한다. 임의 GATT 패킷·오래된 P·인식 결과는 수명 증거가 아니다.
            if (acquireWakeLock(20_000L)) wakeLockRenewedAt = receivedAt
            else runtime.stopBleFieldTrial(if (wakeLockCleanupFailed) "LOCAL_CLOSE_FAILED_RESTART_REQUIRED"
                else "BLE_FIELD_WAKE_LOCK_UNAVAILABLE")
        }
        updateNotification()
    }

    internal fun serviceFrunkStarting(request: TeslaServiceConnectionRequest, acceptedAt: Long): Boolean {
        if (!ownsServiceConnection(request)) return false
        val now = runtime.now()
        val consentMs = com.heytesla.app.vehicle.frunk.TeslaFrunkPolicy.CONSENT_MS
        if (acceptedAt < 0L || acceptedAt > now || now - acceptedAt >= consentMs) return false
        val ready = acquireWakeLock(consentMs - (now - acceptedAt) + 5_000L)
        if (!ready) runtime.stopBleFieldTrial(if (wakeLockCleanupFailed) "LOCAL_CLOSE_FAILED_RESTART_REQUIRED"
            else "BLE_FIELD_WAKE_LOCK_UNAVAILABLE")
        return ready
    }
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
                runtime.stopObservation("BLE_FIELD_NOTIFICATION_STOP", token)
            } else if (requestId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (token != null && token == requestId && runtime.ownsObservation(this, token)) return START_NOT_STICKY
        if (intent?.action != ACTION_START || token == null ||
            !runtime.acceptsObservationRequest(token)
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
        val policy = BleFieldTrialPolicy(runtime::now, runtime::recordBleEvidence)
        fieldPolicy = policy
        candidateDiagnosticsPublisher = BleCandidateDiagnosticsPublisher(
            now = runtime::now,
            postDelayed = { task, delay -> runtime.handler.postDelayed(task, delay) },
            removeCallbacks = { runtime.handler.removeCallbacks(it) },
            isCurrent = { fieldPolicy === policy && requestId == token && runtime.ownsBleField(this, token) },
            snapshot = { policy.diagnostics(candidateScanRunning) },
            publish = { snapshot ->
                runtime.update { it.copy(bleCandidateDiagnostics = snapshot) }
                runtime.event("BLE_CANDIDATE_DIAGNOSTICS")
            },
        )
        try {
            runtime.refresh()
            if (!runtime.acceptsObservationRequest(token)) {
                finishObserving()
                return START_NOT_STICKY
            }
            runtime.observationRequestBlockedReason()?.let { fail(it, token); return START_NOT_STICKY }
            if (!runtime.observationStarted(this, token)) {
                fail("LEASE_UNAVAILABLE", token)
                return START_NOT_STICKY
            }
            syncFieldState()
            startObserving()
            if (runtime.ownsBleField(this, token) && !runtime.state.value.bleFieldTrialStopping) {
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
            NotificationChannel(CHANNEL, "Hey Tesla 서비스", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification(token), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun notificationText(): String = vehicleServiceNotificationText(runtime.state.value)

    private fun notification(token: String, text: String = notificationText()): Notification {
        val compact = text
        val stopIntent = Intent(this, ObservationService::class.java)
            .setAction(ACTION_STOP).setIdentifier(token).putExtra(REQUEST_ID, token)
        val stop = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_VOICE_RESULTS)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vehicle_notification)
            .setContentTitle("Hey Tesla 서비스")
            .setContentText(compact).setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_vehicle_notification)
                .setContentTitle("Hey Tesla 서비스").setContentText("차량 연결 서비스 실행 중")
                .setVisibility(Notification.VISIBILITY_PUBLIC).build())
            .setContentIntent(open).setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "서비스 끄기", stop).build())
            .build()
    }

    private fun updateNotification() {
        val token = requestId ?: return
        if (!AndroidBleScanTransport.foregroundRunning(this)) return
        val text = notificationText()
        if (lastNotificationToken == token && lastNotificationText == text) return
        try {
            getSystemService(NotificationManager::class.java).notify(
                ALTBEACON_NOTIFICATION_ID, notification(token, text))
            lastNotificationToken = token
            lastNotificationText = text
        } catch (_: Exception) { runtime.stopObservation("OBSERVATION_NOTIFICATIONS_BLOCKED", token) }
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
        val decision = policy.signal(signal, receivedElapsedMs)
        if (!policy.accepting || !runtime.ownsBleField(this, token) || runtime.state.value.bleFieldTrialStopping) return
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
                cancelServiceConnectionAttempt(it, reason)
            }
        }
        decision.startAttempt?.let(::startAttempt)
        schedulePolicy()
        updateNotification()
    }

    private fun startAttempt(attempt: Long) {
        val policy = fieldPolicy ?: return
        val token = requestId ?: return
        if (policy.currentAttempt != attempt) return
        if (!policy.accepting || !runtime.ownsBleField(this, token) || runtime.state.value.bleFieldTrialStopping) {
            attemptFinished(attempt, BleProbeState(status = BleProbeStatus.CANCELED, reason = "FIELD_OWNER_NOT_ARMED"), false)
            return
        }
        if (serviceConnectionRequest?.attempt != attempt) {
            finishUnstartedServiceConnection(attempt, "SCAN_DEVICE_UNAVAILABLE")
            return
        }
        runtime.teslaConnectionState.value = TeslaConnectionState(stage = TeslaConnectionStage.PREPARING)
        val scan = scanner
        serviceConnectionPauseConfirmed = false
        if (scan != null) scan.pause {
            if (serviceConnectionRequest?.attempt == attempt) serviceConnectionPauseConfirmed = true
            startGattAfterPause(attempt, policy, token)
        }
        else finishUnstartedServiceConnection(attempt, "BLE_SCAN_FGS_NOT_RUNNING")
    }

    private fun startGattAfterPause(attempt: Long, policy: BleFieldTrialPolicy, token: String) {
        if (fieldPolicy !== policy || policy.currentAttempt != attempt || !policy.accepting ||
            !runtime.ownsBleField(this, token) || runtime.state.value.bleFieldTrialStopping
        ) return
        if (runtime.now() >= (policy.attemptDeadline ?: Long.MAX_VALUE)) {
            finishUnstartedServiceConnection(attempt, "DEADLINE_EXPIRED")
            return
        }
        val request = serviceConnectionRequest ?: return
        runtime.serviceConnectionOwnerReason(request)?.let { finishUnstartedServiceConnection(attempt, it); return }
        syncFieldState(forceDiagnostics = true)
        val wakeReady = try {
            // acquire 예외에서도 같은 객체로 해제할 수 있도록 먼저 소유한다.
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeyTesla:BleFieldGatt")
            wakeLock?.setReferenceCounted(false)
            acquireWakeLock(BleFieldTrialPolicy.SERVICE_INITIAL_BUDGET_MS + 5_000L)
        } catch (_: Exception) {
            runtime.event("BLE_FIELD_WAKE_LOCK_UNAVAILABLE")
            releaseWakeLock()
            false
        }
        if (!wakeReady) {
            runtime.stopBleFieldTrial(if (wakeLockCleanupFailed) "LOCAL_CLOSE_FAILED_RESTART_REQUIRED"
                else "BLE_FIELD_WAKE_LOCK_UNAVAILABLE")
            return
        }
        runtime.startServiceConnection(request) { state, retained ->
            if (fieldPolicy === policy && requestId == token) attemptFinished(attempt, state, retained)
        }
    }

    private fun finishUnstartedServiceConnection(attempt: Long, reason: String, retained: Boolean = false) {
        val policy = fieldPolicy ?: return
        if (policy.currentAttempt != attempt) return
        val status = when {
            retained -> BleProbeStatus.CLEANUP_FAILED
            reason == "DEADLINE_EXPIRED" -> BleProbeStatus.TIMED_OUT
            !policy.accepting || reason == "DEPARTED" -> BleProbeStatus.CANCELED
            else -> BleProbeStatus.BLOCKED
        }
        runtime.teslaConnectionState.value = TeslaConnectionState(
            stage = when (status) {
                BleProbeStatus.CLEANUP_FAILED -> TeslaConnectionStage.FAILED
                BleProbeStatus.CANCELED -> TeslaConnectionStage.STOPPED
                else -> TeslaConnectionStage.BLOCKED
            }, reason = reason)
        runtime.event(if (retained) "TESLA_CONNECTION_CLEANUP_FAILED" else "TESLA_CONNECTION_BLOCKED")
        attemptFinished(attempt, BleProbeState(status = status, reason = reason, localClosed = !retained), retained)
    }

    private fun cancelServiceConnectionAttempt(attempt: Long, reason: String) {
        val token = requestId ?: return
        if (runtime.serviceConnectionInFlight(this, token, attempt)) runtime.cancelServiceConnection(this, token, attempt, reason)
        else finishUnstartedServiceConnection(attempt, reason)
    }
    private fun startSupplementalScan() {
        val policy = fieldPolicy ?: return
        if (!policy.accepting) return
        val id = observedAssociationId ?: return
        val token = requestId ?: return
        if (!runtime.ownsBleField(this, token) || runtime.state.value.bleFieldTrialStopping) return
        val advertisedName = runtime.consumeBleFieldAdvertisedName(this, token)
            ?: run { fail("BLE_SCAN_NAME_UNAVAILABLE", token); return }
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
                    if (decision.startAttempt != null) {
                        val device = result.device
                        val deadline = policy.attemptDeadline
                        if (device != null && deadline != null && result.timestampNanos > 0) {
                            serviceConnectionRequest = TeslaServiceConnectionRequest(this, token, policy.candidateCount,
                                decision.startAttempt, id, device, result.timestampNanos, deadline)
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
                    policy.scanAvailabilityChanged(running)
                    runtime.update { it.copy(bleFieldScanRunning = running) }
                    syncFieldState(forceDiagnostics = true)
                    schedulePolicy()
                }
            },
            onFailure = failure@ { reason, code ->
                if (fieldPolicy !== policy || requestId != token || !runtime.ownsBleField(this, token)) return@failure
                runtime.update { it.copy(bleFieldScanFailure = code, bleFieldScanRunning = false) }
                val connectionPending = serviceConnectionRequest
                if (connectionPending != null && !runtime.serviceConnectionInFlight(this, token, connectionPending.attempt)) {
                    finishUnstartedServiceConnection(connectionPending.attempt, reason, code == BleSupplementalScanner.FAILURE_STOP)
                }
                runtime.stopBleFieldTrial(reason)
            },
            foregroundNotification = notification(token))
        scanner?.start(advertisedName)
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

    private fun attemptFinished(attempt: Long, state: BleProbeState, leaseRetained: Boolean) {
        val policy = fieldPolicy ?: return
        if (policy.currentAttempt != attempt) return
        val wakeReleased = releaseWakeLock()
        val retained = leaseRetained || !wakeReleased
        serviceConnectionRequest?.takeIf { it.attempt == attempt }?.clear()
        if (!retained) serviceConnectionRequest = null
        serviceConnectionPauseConfirmed = false
        // 현재 키 probe가 실제 lease를 반환한 뒤 들어온다. 실패한 close는 예약을 유지한 상태로 기록한다.
        runtime.recordBleTrial("BLE_FIELD_TRIAL_FINISHED", runtime.bleTrialSummary(attempt, state, retained))
        policy.finished(attempt, state, !retained)
        syncFieldState(forceDiagnostics = true)
        if (retained) {
            runtime.recordBleTrial("BLE_FIELD_CLEANUP_FAILED", runtime.bleTrialSummary(attempt, state, true))
            runtime.stopBleFieldTrial("LOCAL_CLOSE_FAILED_RESTART_REQUIRED")
            finishField(cleanupFailed = true)
        } else if (runtime.state.value.bleFieldTrialStopping) {
            finishField()
        }
        else {
            scanner?.resume()
            if (!policy.accepting || requestId == null || runtime.state.value.bleFieldTrialStopping) return
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
                updateNotification()
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
        else if (runtime.state.value.teslaKeyCleanupFailed || fieldPolicy?.leaseRetained == true) finishField(cleanupFailed = true)
        else cancelServiceConnectionAttempt(attempt, reason)
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
        val wakeReleased = releaseWakeLock()
        finishFieldAfterResources(policy, token, cleanupFailed || !wakeReleased || runtime.diagnosticCleanupUncertain)
    }

    private fun finishFieldAfterResources(policy: BleFieldTrialPolicy?, token: String, cleanupFailed: Boolean) {
        if (fieldPolicy !== policy || requestId != token) return
        if (cleanupFailed) {
            runtime.update { it.copy(bleFieldTrialStopping = true,
                bleFieldTrialStopReason = "LOCAL_CLOSE_FAILED_RESTART_REQUIRED") }
            updateNotification()
            return // 오디오/GATT 정리 불명은 scan lease·BeaconService FGS를 반환하지 않는다.
        }
        val scan = scanner
        fun finish(scanStopped: Boolean) {
            if (fieldPolicy !== policy || requestId != token) return
            if (!scanStopped) {
                runtime.update { it.copy(bleFieldTrialStopping = true,
                    bleFieldTrialStopReason = "LOCAL_CLOSE_FAILED_RESTART_REQUIRED") }
                updateNotification()
                return
            }
            scanner = null
            syncFieldState(forceDiagnostics = true)
            candidateDiagnosticsPublisher?.stop()
            runtime.finishBleField(this, token, cleanupFailed || !scanStopped)
            requestId = null
            if (lastStartId != 0) stopSelf(lastStartId) else stopSelf()
        }
        if (scan == null) finish(true) else scan.stop(::finish)
    }

    private fun releaseWakeLock(): Boolean {
        wakeLockRenewedAt = null
        val held = wakeLock ?: return !wakeLockCleanupFailed
        return try {
            if (held.isHeld) held.release()
            wakeLock = null
            !wakeLockCleanupFailed
        } catch (_: Exception) {
            wakeLockCleanupFailed = true
            runtime.event("BLE_FIELD_WAKE_LOCK_RELEASE_FAILED")
            false // 참조·정리 불명을 보존하고 새 취득을 막는다.
        }
    }

    private fun acquireWakeLock(timeoutMs: Long): Boolean {
        val held = wakeLock ?: return false
        // non-ref 재취득은 기존 timeout부터 제거한다. 기존 held를 먼저 확실히 해제한다.
        if (!releaseWakeLock()) return false
        wakeLock = held
        return try {
            held.acquire(timeoutMs)
            true
        } catch (_: Exception) {
            runtime.event("BLE_FIELD_WAKE_LOCK_UNAVAILABLE")
            releaseWakeLock()
            false
        }
    }

    private fun fail(reason: String, token: String) {
        runtime.stopObservation(reason, token)
        if (!runtime.ownsObservation(this, token)) finishObserving()
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
        if (requestId?.let { runtime.ownsObservation(this, it) } == true) {
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
        if (requestId == null) finishObserving()
        // 정리 중에는 Application handler와 현재 키 probe만 bounded close를 마친다. 새 회차 발급은 봉인됐다.
        super.onDestroy()
    }

    companion object {
        internal const val CHANNEL = "vehicle_observation"
        internal const val ACTION_START = "com.heytesla.app.START_OBSERVATION"
        internal const val ACTION_STOP = "com.heytesla.app.STOP_OBSERVATION"
        internal const val REQUEST_ID = "observation_request_id"
        private const val NOTIFICATION_ID = 101
        internal const val ALTBEACON_NOTIFICATION_ID = 102
        private const val READINESS_MS = 1_000L
        private const val HEARTBEAT_MS = 30 * 60 * 1_000L
    }
}
