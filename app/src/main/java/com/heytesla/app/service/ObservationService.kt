package com.heytesla.app

import com.heytesla.app.diagnostics.BleCandidateDiagnosticsPublisher
import com.heytesla.app.ui.teslaUwbSampleIsRecent
import com.heytesla.app.ui.vehicleNavigationText
import com.heytesla.app.ui.vehicleUwbCompactText
import com.heytesla.app.ui.vehicleUwbNotificationText
import com.heytesla.app.vehicle.ble.BleProbeState
import com.heytesla.app.vehicle.ble.BleProbeStatus
import com.heytesla.app.vehicle.detection.BleEventEvidence
import com.heytesla.app.vehicle.detection.BleEvidenceKind
import com.heytesla.app.vehicle.detection.BleEvidenceSignal
import com.heytesla.app.vehicle.detection.BleFieldTrialPolicy
import com.heytesla.app.vehicle.detection.BleSupplementalScanner
import com.heytesla.app.vehicle.uwb.TeslaServiceUwbRequest
import com.heytesla.app.vehicle.uwb.TeslaUwbProbeState
import com.heytesla.app.vehicle.uwb.TeslaUwbStage

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

/** 현재 BLE 감지·서비스 UWB 소유자. bound-only이며 BeaconService만 FGS 알림을 소유한다. */
class ObservationService : Service() {
    private val runtime get() = (application as DiagnosticApp).runtime
    private var requestId: String? = null
    private var lastStartId = 0
    private var finishingField = false
    private var fieldPolicy: BleFieldTrialPolicy? = null
    private var scanner: BleSupplementalScanner? = null
    private var serviceUwbRequest: TeslaServiceUwbRequest? = null
    private var serviceUwbPauseConfirmed = false
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

    internal fun ownsServiceUwb(request: TeslaServiceUwbRequest): Boolean =
        !destroyed && serviceUwbRequest === request && request.owner === this &&
            requestId == request.fieldToken &&
            fieldPolicy?.accepting == true && fieldPolicy?.present == true &&
            fieldPolicy?.currentAttempt == request.attempt && fieldPolicy?.candidateCount == request.candidate &&
            observedAssociationId == request.associationId

    internal fun serviceUwbScanPaused(request: TeslaServiceUwbRequest): Boolean =
        serviceUwbRequest === request && serviceUwbPauseConfirmed

    internal fun serviceUwbRanging(request: TeslaServiceUwbRequest) {
        if (!ownsServiceUwb(request)) return
        fieldPolicy?.serviceRanging(request.attempt)
        schedulePolicy()
    }

    internal fun uwbStateChanged() {
        val request = serviceUwbRequest
        val state = runtime.teslaUwbState.value
        val sample = state.lastReceivedSample
        if (request != null && ownsServiceUwb(request) && state.serviceOwned &&
            state.stage == TeslaUwbStage.RANGING && sample != null &&
            teslaUwbSampleIsRecent(state, sample, runtime.now()) &&
            (wakeLockRenewedAt == null || sample.receivedAtElapsedMs - checkNotNull(wakeLockRenewedAt) >= 5_000L)
        ) {
            // 5초 갱신 간격 + 무수신 10초 + 정리 5초. 새 거리 없이는 OS 기한을 연장하지 않는다.
            if (acquireWakeLock(20_000L)) wakeLockRenewedAt = sample.receivedAtElapsedMs
            else runtime.stopBleFieldTrial(if (wakeLockCleanupFailed) "LOCAL_CLOSE_FAILED_RESTART_REQUIRED"
                else "BLE_FIELD_WAKE_LOCK_UNAVAILABLE")
        }
        updateNotification()
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

    private fun notificationStatusText(): String {
        val s = runtime.state.value
        val uwbText = vehicleUwbNotificationText(runtime.teslaUwbState.value, runtime.now())
        return when {
            s.teslaKeyCleanupFailed || s.bleFieldTrialStopReason == "LOCAL_CLOSE_FAILED_RESTART_REQUIRED" ||
                runtime.bleProbeState.value.status == BleProbeStatus.CLEANUP_FAILED -> "재시작 필요"
            s.bleFieldTrialStopping || runtime.bleProbeState.value.status == BleProbeStatus.CLEANING_UP -> "정리 중"
            uwbText != null -> uwbText
            serviceUwbRequest != null -> "UWB 준비 중 · BLE 스캔 일시 중지"
            s.bleFieldTrialWaitingForDeparture -> "측정 종료 · 이탈 후 다음 접근 대기"
            s.bleFieldScanFailure != null -> "차량 감지 중지 · 스캔 오류"
            s.bleFieldScanRecoveryAt != null -> "차량 감지 일시 중지 · OS 스캔 복구 대기"
            !s.bleFieldScanRunning -> "차량 감지 시작 중"
            s.present -> "차량 신호 확인 · UWB 준비 상태 확인"
            else -> "차량 신호 대기 · 접근 시 UWB 측정"
        }
    }

    private fun notificationText(): String {
        val status = notificationStatusText()
        val s = runtime.state.value
        if (s.bleFieldTrialStopping ||
            s.teslaKeyCleanupFailed || s.bleFieldTrialStopReason == "LOCAL_CLOSE_FAILED_RESTART_REQUIRED" ||
            runtime.bleProbeState.value.status == BleProbeStatus.CLEANUP_FAILED ||
            runtime.teslaUwbState.value.stage == TeslaUwbStage.CLEANUP_FAILED
        ) return status
        val uwb = vehicleUwbNotificationText(runtime.teslaUwbState.value, runtime.now()) ?: "UWB 실측 미수신"
        val coordinate = vehicleNavigationText(runtime.vehicleNavigation.value)
        return if (status == uwb) "$coordinate\n$uwb" else "$status\n$coordinate\n$uwb"
    }

    private fun notification(token: String, text: String = notificationText()): Notification {
        val uwbStage = runtime.teslaUwbState.value.stage
        val compact = if ('\n' !in text || uwbStage == TeslaUwbStage.FAILED ||
            uwbStage == TeslaUwbStage.BLOCKED || uwbStage == TeslaUwbStage.TIMED_OUT
        ) notificationStatusText() else vehicleNavigationText(runtime.vehicleNavigation.value) +
            " · " + vehicleUwbCompactText(runtime.teslaUwbState.value, runtime.now())
        val stopIntent = Intent(this, ObservationService::class.java)
            .setAction(ACTION_STOP).setIdentifier(token).putExtra(REQUEST_ID, token)
        val stop = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vehicle_notification)
            .setContentTitle("Hey Tesla 서비스")
            .setContentText(compact).setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_vehicle_notification)
                .setContentTitle("Hey Tesla 서비스").setContentText("차량 안내 서비스 실행 중")
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
                cancelServiceUwbAttempt(it, reason)
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
        if (serviceUwbRequest?.attempt != attempt) {
            finishUnstartedServiceUwb(attempt, "SCAN_DEVICE_UNAVAILABLE")
            return
        }
        if (!runtime.granted(android.Manifest.permission.RANGING)) {
            finishUnstartedServiceUwb(attempt, "RANGING_PERMISSION_REQUIRED")
            return
        }
        runtime.teslaUwbState.value = TeslaUwbProbeState(stage = TeslaUwbStage.PREPARING,
            serviceOwned = true, rangingPermission = runtime.granted(android.Manifest.permission.RANGING))
        runtime.event("TESLA_UWB_STARTED")
        val scan = scanner
        serviceUwbPauseConfirmed = false
        if (scan != null) scan.pause {
            if (serviceUwbRequest?.attempt == attempt) serviceUwbPauseConfirmed = true
            startGattAfterPause(attempt, policy, token)
        }
        else finishUnstartedServiceUwb(attempt, "BLE_SCAN_FGS_NOT_RUNNING")
    }

    private fun startGattAfterPause(attempt: Long, policy: BleFieldTrialPolicy, token: String) {
        if (fieldPolicy !== policy || policy.currentAttempt != attempt || !policy.accepting ||
            !runtime.ownsBleField(this, token) || runtime.state.value.bleFieldTrialStopping
        ) return
        if (runtime.now() >= (policy.attemptDeadline ?: Long.MAX_VALUE)) {
            finishUnstartedServiceUwb(attempt, "DEADLINE_EXPIRED")
            return
        }
        val request = serviceUwbRequest ?: return
        runtime.serviceUwbOwnerReason(request)?.let { finishUnstartedServiceUwb(attempt, it); return }
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
        runtime.startServiceUwb(request) { state, retained ->
            if (fieldPolicy === policy && requestId == token) attemptFinished(attempt, state, retained)
        }
    }

    private fun finishUnstartedServiceUwb(attempt: Long, reason: String, retained: Boolean = false) {
        val policy = fieldPolicy ?: return
        if (policy.currentAttempt != attempt) return
        val status = when {
            retained -> BleProbeStatus.CLEANUP_FAILED
            reason == "DEADLINE_EXPIRED" -> BleProbeStatus.TIMED_OUT
            !policy.accepting || reason == "DEPARTED" -> BleProbeStatus.CANCELED
            else -> BleProbeStatus.BLOCKED
        }
        runtime.teslaUwbState.value = TeslaUwbProbeState(
            stage = when (status) {
                BleProbeStatus.CLEANUP_FAILED -> TeslaUwbStage.CLEANUP_FAILED
                BleProbeStatus.TIMED_OUT -> TeslaUwbStage.TIMED_OUT
                BleProbeStatus.CANCELED -> TeslaUwbStage.CANCELED
                else -> TeslaUwbStage.BLOCKED
            }, serviceOwned = true, rangingPermission = runtime.granted(android.Manifest.permission.RANGING), reason = reason)
        runtime.event(if (retained) "TESLA_UWB_CLEANUP_FAILED" else "TESLA_UWB_BLOCKED")
        attemptFinished(attempt, BleProbeState(status = status, reason = reason, localClosed = !retained), retained)
    }

    private fun cancelServiceUwbAttempt(attempt: Long, reason: String) {
        val token = requestId ?: return
        if (runtime.serviceUwbInFlight(this, token, attempt)) runtime.cancelServiceUwb(this, token, attempt, reason)
        else finishUnstartedServiceUwb(attempt, reason)
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
                            serviceUwbRequest = TeslaServiceUwbRequest(this, token, policy.candidateCount,
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
                val uwbPending = serviceUwbRequest
                if (uwbPending != null && !runtime.serviceUwbInFlight(this, token, uwbPending.attempt)) {
                    finishUnstartedServiceUwb(uwbPending.attempt, reason, code == BleSupplementalScanner.FAILURE_STOP)
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
        serviceUwbRequest?.takeIf { it.attempt == attempt }?.clear()
        if (!retained) serviceUwbRequest = null
        serviceUwbPauseConfirmed = false
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
                runtime.coordinateReadinessTick(this@ObservationService, token)
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
        runtime.sealVehicleNavigation()
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
        else cancelServiceUwbAttempt(attempt, reason)
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
        runtime.closeVehicleNavigation(this, token) { coordinatesClosed ->
            finishFieldAfterCoordinates(policy, token, cleanupFailed || !wakeReleased || !coordinatesClosed)
        }
    }

    private fun finishFieldAfterCoordinates(policy: BleFieldTrialPolicy?, token: String, cleanupFailed: Boolean) {
        if (fieldPolicy !== policy || requestId != token) return
        if (cleanupFailed) {
            runtime.update { it.copy(bleFieldTrialStopping = true,
                bleFieldTrialStopReason = "LOCAL_CLOSE_FAILED_RESTART_REQUIRED") }
            updateNotification()
            return // 실패한 UWB/GATT 정리는 scan lease·BeaconService FGS를 반환하지 않는다.
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
