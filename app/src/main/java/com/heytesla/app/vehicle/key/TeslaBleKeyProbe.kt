package com.heytesla.app.vehicle.key

import com.heytesla.app.DiagnosticRuntime
import com.heytesla.app.vehicle.ble.BleProbeState
import com.heytesla.app.vehicle.ble.BleProbeStatus
import com.heytesla.app.vehicle.ble.TeslaGattConnectRetry
import com.heytesla.app.vehicle.ble.TeslaGattConnectSettle
import com.heytesla.app.vehicle.ble.TeslaNativeGattOwner
import com.heytesla.app.vehicle.ble.TeslaNativeGattRequestKind
import com.heytesla.app.vehicle.ble.TeslaNativeGattStage
import com.heytesla.app.vehicle.ble.closeNative
import com.heytesla.app.vehicle.ble.connectNative
import com.heytesla.app.vehicle.frunk.TeslaFrunkConsentGate
import com.heytesla.app.vehicle.frunk.TeslaFrunkPolicy
import com.heytesla.app.vehicle.frunk.TeslaFrunkResult
import com.heytesla.app.vehicle.frunk.TeslaFrunkSequence
import com.heytesla.app.vehicle.navigation.VehicleCoordinateObservation
import com.heytesla.app.vehicle.protocol.TeslaBleConversation
import com.heytesla.app.vehicle.protocol.TeslaBlePurpose
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.heytesla.app.vehicle.uwb.AndroidTeslaUwbAppDeviceInfoSource
import com.heytesla.app.vehicle.uwb.AndroidTeslaUwbPlatform
import com.heytesla.app.vehicle.uwb.AndroidTeslaUwbScheduler
import com.heytesla.app.vehicle.uwb.TeslaServiceUwbGattAdmission
import com.heytesla.app.vehicle.uwb.TeslaServiceUwbRequest
import com.heytesla.app.vehicle.uwb.TeslaUwbGattEvent
import com.heytesla.app.vehicle.uwb.TeslaUwbGattObservation
import com.heytesla.app.vehicle.uwb.TeslaUwbNegotiation
import com.heytesla.app.vehicle.uwb.TeslaUwbProbeState
import com.heytesla.app.vehicle.uwb.TeslaUwbStage

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.content.Context
import android.net.MacAddress
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.UUID

/** Application runtime 소유. Activity 재생성은 close 실패 예약을 해제하지 않는다. */
@SuppressLint("MissingPermission")
internal class TeslaBleKeyProbe(
    context: Context,
    private val runtime: DiagnosticRuntime,
    private val state: MutableStateFlow<TeslaKeyProbeState>,
    private val keyStore: TeslaBleKeyStore = TeslaBleKeyStore(context.applicationContext),
    private val wallNow: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext
    private val bluetooth = app.getSystemService(BluetoothManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Operation? = null
    private val frunkConsent = TeslaFrunkConsentGate()

    private inner class Operation(
        val token: Long,
        val vin: ByteArray,
        val register: Boolean,
        val query: TeslaBleQuery,
        deadline: Long,
        val frunk: TeslaFrunkPolicy? = null,
        val uwbRequested: Boolean = false,
        val serviceUwb: TeslaServiceUwbRequest? = null,
        val serviceFinished: ((BleProbeState, Boolean) -> Unit)? = null,
    ) {
        val startedElapsedMs = runtime.now()
        var stageStartedElapsedMs = startedElapsedMs
        val serviceGattAdmission = if (serviceUwb != null) TeslaServiceUwbGattAdmission() else null
        var connectedObserved = false
        var serviceFound = false
        var txFound = false
        var rxFound = false
        var firstRxElapsedMs: Long? = null
        var rxNotificationCount = 0L
        var nativeGattStatus: Int? = null
        var primaryGattStatus: Int? = null
        var associationId: Int? = null
        var associationAddress: MacAddress? = null
        var preparing = false
        var cleanupStarted = false
        var terminal = TeslaKeyStage.FAILED
        var gatt: NativeTransport? = null
        var nativeUwbReady = false
        val session: TeslaBleKeySession = TeslaBleKeySession(
            deadline = deadline, register = register, query = query,
            frunk = frunk, uwbRequested = uwbRequested, now = runtime::now,
            deviceInfoSource = if (uwbRequested) AndroidTeslaUwbAppDeviceInfoSource(app) { nativeUwbReady } else null,
            post = { runtime.handler.post(it) },
            postDelayed = { task, delay -> runtime.handler.postDelayed(task, delay) },
            remove = runtime.handler::removeCallbacks,
            listener = object : TeslaBleKeySession.Listener {
                override var state: TeslaKeyProbeState
                    get() = this@TeslaBleKeyProbe.state.value
                    set(value) { this@TeslaBleKeyProbe.state.value = value }
                override val uwbStage get() = runtime.teslaUwbState.value.stage
                override fun allowed() = platformAllowed(this@Operation)
                override fun onStop(stage: TeslaKeyStage, reason: String?) = finishStopped(this@Operation, stage, reason)
                override fun onEvent(event: String) = runtime.event(event)
                override fun onStage() {
                    stageStartedElapsedMs = runtime.now()
                    publishServiceGatt(this@Operation, TeslaUwbGattEvent.STAGE)
                }
                override fun onGatt(event: TeslaUwbGattEvent) = publishServiceGatt(this@Operation, event)
                override fun onFrunk() = publishFrunk(this@Operation)
                override fun onNotification(receivedAtElapsedMs: Long) {
                    lastRxElapsedMs = receivedAtElapsedMs
                    if (firstRxElapsedMs == null) firstRxElapsedMs = receivedAtElapsedMs
                    if (rxNotificationCount < Long.MAX_VALUE) rxNotificationCount++
                }
                override fun onUwbAuthenticating() {
                    if (runtime.teslaUwbState.value.stage != TeslaUwbStage.AUTHENTICATING) {
                        runtime.teslaUwbState.value = runtime.teslaUwbState.value.copy(stage = TeslaUwbStage.AUTHENTICATING)
                        runtime.event("TESLA_UWB_AUTHENTICATING")
                    }
                }
                override fun coordinatesWanted(): Boolean {
                    val request = serviceUwb ?: return false
                    return current === this@Operation && !session.sealed &&
                        runtime.serviceCoordinatesWanted(request)
                }
                override fun onCoordinates(observation: VehicleCoordinateObservation?, reason: String?) {
                    val request = serviceUwb ?: return
                    if (allowed(this@Operation) && runtime.serviceCoordinatesWanted(request)) {
                        runtime.serviceCoordinateResult(request, observation, reason)
                    }
                }
            },
            transport = { gatt },
        ).also {
            it.gattObservation = if (serviceUwb != null) TeslaUwbGattObservation(
                stage = TeslaKeyStage.PREPARING_KEY, event = TeslaUwbGattEvent.STAGE,
                operationStartedElapsedMs = startedElapsedMs, stageStartedElapsedMs = startedElapsedMs,
                observedElapsedMs = startedElapsedMs, rxFrameCount = 0L, rxProtocolCount = 0L,
            ) else null
        }
        var frunkParkPublished = false
        var handshakeStarted = false
        val connectRetry = TeslaGattConnectRetry(
            now = runtime::now,
            postDelayed = { task, delay -> runtime.handler.postDelayed(task, delay) },
            remove = runtime.handler::removeCallbacks,
            allowed = { allowed(this) },
            event = { event ->
                if (event == "TESLA_APPROACH_GATT_RETRY_STARTED") {
                    // 대기 종료일 뿐 새 요청 발급의 증거는 아니다. 이전 연결 관측을 넘기지 않는다.
                    gattConnectionStatus = null
                    gattConnectionState = null
                    gattConnectionElapsedMs = null
                    gattDescriptorRequestStatus = null
                    gattDescriptorCallbackStatus = null
                    gattDescriptorCallbackElapsedMs = null
                    gattConnectIssuedElapsedMs = null
                    gattConnectReturnedElapsedMs = null
                    gattAutoConnect = null
                    gattConnectAction = null
                    gattConnectAccepted = null
                    advertisementAgeAtConnectMs = null
                }
                publishServiceGatt(this, when (event) {
                    "TESLA_APPROACH_GATT_RETRY_SCHEDULED" -> TeslaUwbGattEvent.RETRY_SCHEDULED
                    "TESLA_APPROACH_GATT_RETRY_STARTED" -> TeslaUwbGattEvent.RETRY_STARTED
                    else -> TeslaUwbGattEvent.RETRY_CANCELED
                })
            },
            failed = { stop(this, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED") },
            connect = {
                val transport = gatt
                if (transport == null) stop(this, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED")
                else transport.reconnect()
            },
        )
        var tx: BluetoothGattCharacteristic? = null
        var rx: BluetoothGattCharacteristic? = null
        var cccd: BluetoothGattDescriptor? = null
        var cccdValue: ByteArray? = null
        var gattConnectionStatus: Int? = null
        var gattConnectionState: Int? = null
        var gattConnectionElapsedMs: Long? = null
        var gattDescriptorRequestStatus: Int? = null
        var gattDescriptorCallbackStatus: Int? = null
        var gattDescriptorCallbackElapsedMs: Long? = null
        var lastRxElapsedMs: Long? = null
        var connectUncertain = false
        var gattConnectIssuedElapsedMs: Long? = null
        var gattConnectReturnedElapsedMs: Long? = null
        var gattAutoConnect: Boolean? = null
        var gattConnectAction: String? = null
        var gattConnectAccepted: Boolean? = null
        var advertisementAgeAtConnectMs: Long? = null
        val settle = TeslaGattConnectSettle(
            now = runtime::now,
            postDelayed = { task, delay -> runtime.handler.postDelayed(task, delay) },
            remove = runtime.handler::removeCallbacks,
            allowed = { allowed(this) },
            onScheduleFailed = { stop(this, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED") },
            connect = { connectSettled(this) },
        )
    }

    fun start(input: String, register: Boolean, query: TeslaBleQuery = TeslaBleQuery.BODY_STATUS) =
        startOperation(input, register, query)

    fun startUwb(input: String) = startOperation(input, false, TeslaBleQuery.BODY_STATUS, uwb = true)

    fun startServiceUwb(request: TeslaServiceUwbRequest, finished: (BleProbeState, Boolean) -> Unit) {
        mainThread()
        fun blocked(reason: String) {
            runtime.teslaUwbState.value = TeslaUwbProbeState(stage = TeslaUwbStage.BLOCKED,
                serviceOwned = true, rangingPermission = runtime.granted(Manifest.permission.RANGING), reason = reason)
            runtime.event("TESLA_UWB_BLOCKED")
            request.clear()
            finished(BleProbeState(status = BleProbeStatus.BLOCKED, reason = reason, localClosed = true), false)
        }
        if (current != null || runtime.teslaKeyReserved()) { blocked("LEASE_UNAVAILABLE"); return }
        runtime.refresh()
        runtime.serviceUwbOwnerReason(request)?.let { blocked(it); return }
        val vin = runtime.vehicleVinSnapshot() ?: run { blocked("VIN_NOT_REGISTERED"); return }
        val token = runtime.acquireServiceUwb(request) ?: run { blocked("LEASE_UNAVAILABLE"); return }
        val op = Operation(token, vin.toByteArray(Charsets.US_ASCII), false, TeslaBleQuery.BODY_STATUS,
            request.deadline, uwbRequested = true, serviceUwb = request, serviceFinished = finished)
        current = op
        op.associationId = request.associationId
        state.value = TeslaKeyProbeState(stage = TeslaKeyStage.PREPARING_KEY, outcome = TeslaKeyOutcome.PENDING)
        runtime.teslaUwbState.value = TeslaUwbProbeState(stage = TeslaUwbStage.PREPARING,
            serviceOwned = true, rangingPermission = true, gatt = op.session.gattObservation)
        runtime.event("TESLA_UWB_STARTED")
        publishServiceGatt(op, TeslaUwbGattEvent.STAGE)
        op.session.stageDeadline = minOf(op.session.deadline, runtime.now() + PREPARE_MS)
        if (!allowed(op)) return
        op.preparing = true
        op.session.watch()
        scope.launch {
            val credential = try { withContext(Dispatchers.IO) { keyStore.getExistingReadOnly() } }
                catch (_: Exception) { null }
            if (current !== op) return@launch
            op.preparing = false
            if (op.session.sealed) { finishCleanup(op); return@launch }
            if (!allowed(op)) return@launch
            if (credential == null) { stop(op, TeslaKeyStage.BLOCKED, "EXISTING_KEY_UNAVAILABLE"); return@launch }
            try {
                op.session.conversation = TeslaBleConversation(credential, op.vin, purpose = TeslaBlePurpose.UWB,
                    onResponseObservation = { response ->
                        // callback은 RAM에만 보관한다. 실제 drain 반환 경계에서 한 번 발행한다.
                        if (current === op && !op.session.sealed) op.session.pendingRxResponse = response
                    }, serviceOwned = true, now = runtime::now)
                op.session.locationConversationFactory = {
                    TeslaBleConversation(credential, op.vin, query = TeslaBleQuery.LOCATION_STATE,
                        purpose = TeslaBlePurpose.READ_ONLY, wallNow = wallNow, now = runtime::now)
                }
                state.value = state.value.copy(credentialReady = true)
                prepareUwb(op)
            } catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "KEY_PREPARATION_FAILED") }
        }
    }

    /** DIRECT_MIC 최종 명령 인정과 오디오 lease 반환을 마친 runtime만 호출한다. */
    fun startFrunkOpen(input: String, commandAcceptedAtElapsedMs: Long) {
        mainThread()
        if (current != null || runtime.teslaKeyReserved()) {
            runtime.event("TESLA_FRUNK_BLOCKED")
            return
        }
        frunkConsent.claim(commandAcceptedAtElapsedMs, runtime.now())?.let { reason ->
            state.value = TeslaKeyProbeState(stage = TeslaKeyStage.BLOCKED,
                frunkCommandRequested = true, reason = reason)
            runtime.event("TESLA_FRUNK_BLOCKED")
            return
        }
        startOperation(input, false, TeslaBleQuery.DRIVE_STATE,
            TeslaFrunkPolicy(commandAcceptedAtElapsedMs, runtime::now, wallNow))
    }

    private fun startOperation(input: String, register: Boolean, query: TeslaBleQuery,
        frunk: TeslaFrunkPolicy? = null, uwb: Boolean = false) {
        mainThread()
        if (current != null || runtime.teslaKeyReserved()) {
            if (uwb) runtime.event("TESLA_UWB_BLOCKED")
            runtime.event(if (frunk != null) "TESLA_FRUNK_BLOCKED" else "TESLA_KEY_START_BLOCKED")
            return
        }
        if (register && query != TeslaBleQuery.BODY_STATUS) {
            state.value = TeslaKeyProbeState(stage = TeslaKeyStage.BLOCKED, query = query,
                registrationRequested = true, reason = "DRIVE_STATE_REGISTRATION_NOT_ALLOWED")
            runtime.event("TESLA_KEY_START_BLOCKED")
            return
        }
        val normalized = input.trim().uppercase(Locale.ROOT)
        fun blockUwb(reason: String) {
            if (!uwb) return
            runtime.teslaUwbState.value = TeslaUwbProbeState(stage = TeslaUwbStage.BLOCKED,
                rangingPermission = runtime.granted(Manifest.permission.RANGING), reason = reason)
            runtime.event("TESLA_UWB_BLOCKED")
        }
        if (!normalized.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) {
            blockUwb("VIN_FORMAT_INVALID")
            state.value = TeslaKeyProbeState(stage = TeslaKeyStage.BLOCKED, query = query,
                frunkCommandRequested = frunk != null, reason = "VIN_FORMAT_INVALID")
            runtime.event(if (frunk != null) "TESLA_FRUNK_BLOCKED" else "TESLA_KEY_START_BLOCKED")
            return
        }
        runtime.refresh()
        if (uwb) {
            val blocked = runtime.uwbRangingBlockedReason()
                ?: if (normalized != runtime.vehicleVinSnapshot()) "UWB_VIN_SNAPSHOT_CHANGED" else null
            if (blocked != null) { blockUwb(blocked); return }
        }
        if (frunk != null && normalized != runtime.vehicleVinSnapshot()) {
            state.value = TeslaKeyProbeState(stage = TeslaKeyStage.BLOCKED, query = query,
                frunkCommandRequested = true, reason = "FRUNK_VIN_SNAPSHOT_CHANGED")
            runtime.event("TESLA_FRUNK_BLOCKED")
            return
        }
        val token = runtime.acquireTeslaKey() ?: run {
            blockUwb(runtime.teslaKeyBlockedReason() ?: "LEASE_UNAVAILABLE")
            state.value = TeslaKeyProbeState(stage = TeslaKeyStage.BLOCKED, query = query,
                frunkCommandRequested = frunk != null,
                reason = runtime.teslaKeyBlockedReason() ?: "LEASE_UNAVAILABLE")
            runtime.event(if (frunk != null) "TESLA_FRUNK_BLOCKED" else "TESLA_KEY_START_BLOCKED")
            return
        }
        val op = Operation(token, normalized.toByteArray(Charsets.US_ASCII), register, query,
            frunk?.deadline ?: (runtime.now() + TOTAL_MS), frunk = frunk, uwbRequested = uwb)
        current = op
        state.value = TeslaKeyProbeState(stage = TeslaKeyStage.PREPARING_KEY, registrationRequested = register,
            query = query, frunkCommandRequested = frunk != null)
        if (uwb) {
            runtime.teslaUwbState.value = TeslaUwbProbeState(stage = TeslaUwbStage.PREPARING,
                rangingPermission = true)
            runtime.event("TESLA_UWB_STARTED")
        } else if (runtime.teslaUwbState.value.serviceOwned) {
            // 실제 수동 lease 취득 뒤에만 지난 서비스 상태와 새 수동 작업의 출처를 분리한다.
            runtime.teslaUwbState.value = TeslaUwbProbeState(
                rangingPermission = runtime.granted(Manifest.permission.RANGING))
        }
        op.session.stageDeadline = minOf(op.session.deadline, runtime.now() + PREPARE_MS)
        runtime.event(if (frunk != null) "TESLA_FRUNK_COMMAND_REQUESTED" else "TESLA_KEY_STARTED")
        try {
            val selected = singleAssociation()
            if (selected == null || selected.deviceMacAddress == null) {
                stop(op, TeslaKeyStage.FAILED, "SINGLE_ASSOCIATION_REQUIRED")
                return
            }
            op.associationId = selected.id
            op.associationAddress = selected.deviceMacAddress
        } catch (_: Exception) {
            stop(op, TeslaKeyStage.FAILED, "ASSOCIATION_RESOLVE_FAILED")
            return
        }
        if (!allowed(op)) return
        op.preparing = true
        op.session.watch()
        // 작업 취소로 IO 완료를 잃지 않는다. 취소 시 전송은 즉시 봉인하고 IO 반환까지 lease를 유지한다.
        scope.launch {
            val credential = try { withContext(Dispatchers.IO) {
                if (op.register) keyStore.getOrCreate() else keyStore.getExistingReadOnly()
            } } catch (_: Exception) { null }
            if (current !== op) return@launch
            op.preparing = false
            if (op.session.sealed) { finishCleanup(op); return@launch }
            if (!allowed(op)) return@launch
            if (credential == null) {
                stop(op, TeslaKeyStage.FAILED, if (op.frunk != null || op.uwbRequested) "EXISTING_KEY_UNAVAILABLE" else "KEY_PREPARATION_FAILED")
                return@launch
            }
            try {
                if (op.frunk != null) {
                    op.session.frunkSequence = TeslaFrunkSequence(credential, op.vin, op.frunk, runtime::now)
                } else {
                    op.session.conversation = TeslaBleConversation(credential, op.vin, query = op.query,
                        purpose = if (op.uwbRequested) TeslaBlePurpose.UWB else TeslaBlePurpose.READ_ONLY,
                        now = runtime::now)
                }
                state.value = state.value.copy(credentialReady = true)
                runtime.event("TESLA_KEY_CREDENTIAL_READY")
                if (op.uwbRequested) prepareUwb(op) else connect(op)
            } catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "KEY_PREPARATION_FAILED") }
        }
    }

    private fun prepareUwb(op: Operation) {
        op.session.stageDeadline = minOf(op.session.deadline, runtime.now() + 10_000L)
        val coordinator = TeslaUwbNegotiation(AndroidTeslaUwbPlatform(app),
            AndroidTeslaUwbScheduler(runtime.handler), runtime::now, wallNow,
            object : TeslaUwbNegotiation.Listener {
                override fun onCapabilitiesReady() {
                    op.nativeUwbReady = true
                    if (allowed(op)) connect(op)
                }
                override fun onSend(body: ByteArray, frameComplete: () -> Unit) {
                    op.session.sendFira(body, frameComplete)
                }
                override fun onState(transform: (TeslaUwbProbeState) -> TeslaUwbProbeState, event: String) {
                    if (current !== op) return
                    val before = runtime.teslaUwbState.value
                    val next = transform(before)
                    if (!op.session.sealed && op.serviceUwb != null && next.stage == TeslaUwbStage.RANGING &&
                        runtime.now() >= op.session.deadline) {
                        stop(op, TeslaKeyStage.TIMED_OUT, "DEADLINE_EXPIRED")
                        return
                    }
                    runtime.teslaUwbState.value = if (op.session.sealed) before.copy(
                        stage = if (before.stage == TeslaUwbStage.CLEANUP_FAILED) before.stage else TeslaUwbStage.CLEANING_UP,
                        livePeerIndices = emptySet(),
                        closedReason = next.closedReason, openFailedReason = next.openFailedReason)
                    else next.copy(gatt = before.gatt)
                    if (!op.session.sealed && op.serviceUwb?.ranging == false && next.stage == TeslaUwbStage.RANGING) {
                        if (!op.session.continueServiceRanging()) return
                    }
                    runtime.event(event)
                }
                override fun onFinish(stage: TeslaKeyStage, reason: String?) {
                    stop(op, stage, reason)
                }
            }, serviceOwned = op.serviceUwb != null)
        op.session.uwb = coordinator
        coordinator.prepare()
    }

    private fun serviceGattSnapshot(op: Operation, event: TeslaUwbGattEvent) = checkNotNull(op.session.gattObservation).copy(
        stage = state.value.stage, event = event,
        operationStartedElapsedMs = op.startedElapsedMs,
        stageStartedElapsedMs = op.stageStartedElapsedMs, observedElapsedMs = runtime.now(),
        connectIssuedElapsedMs = op.gattConnectIssuedElapsedMs,
        connectReturnedElapsedMs = op.gattConnectReturnedElapsedMs,
        autoConnect = op.gattAutoConnect, connectAction = op.gattConnectAction,
        connectAccepted = op.gattConnectAccepted, advertisementAgeAtConnectMs = op.advertisementAgeAtConnectMs,
        connectionStatus = op.gattConnectionStatus, connectionState = op.gattConnectionState,
        connectionCallbackElapsedMs = op.gattConnectionElapsedMs,
        descriptorRequestStatus = op.gattDescriptorRequestStatus,
        descriptorCallbackStatus = op.gattDescriptorCallbackStatus,
        descriptorCallbackElapsedMs = op.gattDescriptorCallbackElapsedMs,
        firstRxElapsedMs = op.firstRxElapsedMs, lastRxElapsedMs = op.lastRxElapsedMs,
        connectedObserved = op.connectedObserved, serviceFound = op.serviceFound,
        txFound = op.txFound, rxFound = op.rxFound,
        subscriptionConfirmed = state.value.subscriptionConfirmed, rxNotificationCount = op.rxNotificationCount,
    )

    private fun publishServiceGatt(op: Operation, event: TeslaUwbGattEvent) {
        if (op.serviceUwb == null || current !== op) return
        op.session.gattObservation = serviceGattSnapshot(op, event)
        runtime.teslaUwbState.value = runtime.teslaUwbState.value.copy(gatt = op.session.gattObservation)
        runtime.event("TESLA_UWB_GATT_OBSERVATION")
    }

    private fun recordNativeGattStatus(op: Operation, status: Int) {
        op.nativeGattStatus = status
        // 재접속 시작은 이번 시도의 callback 필드만 비운다. 최초 실제 오류 원인은 작업 끝까지 남긴다.
        if (op.primaryGattStatus == null || op.primaryGattStatus == BluetoothGatt.GATT_SUCCESS) {
            op.primaryGattStatus = status
        }
    }

    fun cancel(reason: String = "USER_STOP") {
        mainThread()
        current?.session?.cancel(reason = reason)
    }

    fun checkReadiness() {
        mainThread()
        current?.session?.checkReadiness()
    }

    private fun allowed(op: Operation): Boolean = op.session.checkReadiness()

    private fun platformAllowed(op: Operation): Boolean {
        if (current !== op || op.session.sealed) return false
        if (op.gatt?.callbackDeliveryFailed == true) {
            stop(op, TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED")
            return false
        }
        if (op.uwbRequested) {
            if (op.serviceUwb == null && runtime.state.value.bleDetectionEnabled) {
                stop(op, TeslaKeyStage.CANCELED, "DIAGNOSTIC_EXCLUSIVITY_LOST")
                return false
            }
            if (!runtime.granted(Manifest.permission.RANGING)) {
                stop(op, TeslaKeyStage.CANCELED, "RANGING_PERMISSION_REQUIRED")
                return false
            }
            if (!vinMatches(op)) {
                stop(op, TeslaKeyStage.CANCELED, "UWB_VIN_SNAPSHOT_CHANGED")
                return false
            }
        }
        if (op.frunk != null && !vinMatches(op)) {
            stop(op, TeslaKeyStage.CANCELED, "FRUNK_VIN_SNAPSHOT_CHANGED")
            return false
        }
        val reason = runtime.teslaKeyReadinessReason(op.token,
            requireFresh = op.serviceGattAdmission?.requireFresh ?: true,
            serviceUwb = op.serviceUwb) ?: deviceReadiness(op)
        if (reason != null) { stop(op, TeslaKeyStage.CANCELED, reason); return false }
        return true
    }

    private fun vinMatches(op: Operation): Boolean {
        val value = runtime.vehicleVinSnapshot() ?: return false
        if (value.length != op.vin.size) return false
        for (index in op.vin.indices) if (value[index].code != op.vin[index].toInt()) return false
        return true
    }

    private fun singleAssociation(): AssociationInfo? {
        var selected: AssociationInfo? = null
        for (association in runtime.cdm?.myAssociations.orEmpty()) {
            if (association.isSelfManaged) continue
            if (selected != null) return null
            selected = association
        }
        return selected
    }

    private fun deviceReadiness(op: Operation): String? = try {
        when {
            !runtime.granted(Manifest.permission.BLUETOOTH_CONNECT) -> "BLUETOOTH_PERMISSION_REQUIRED"
            bluetooth?.adapter?.isEnabled != true -> "BLUETOOTH_OFF"
            op.uwbRequested && op.serviceUwb == null && app.getSystemService(PowerManager::class.java)?.isInteractive != true -> "SCREEN_ON_REQUIRED"
            op.frunk != null && app.getSystemService(PowerManager::class.java)?.isInteractive != true -> "SCREEN_ON_REQUIRED"
            op.serviceUwb == null && app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false -> "UNLOCKED_UI_REQUIRED"
            else -> {
                val selected = singleAssociation()
                when {
                    selected == null -> "SINGLE_ASSOCIATION_REQUIRED"
                    op.serviceUwb == null && selected.deviceMacAddress == null -> "ASSOCIATION_ADDRESS_UNAVAILABLE"
                    op.associationId != null && selected.id != op.associationId -> "ASSOCIATION_CHANGED"
                    op.associationAddress != null && selected.deviceMacAddress != op.associationAddress -> "ASSOCIATION_CHANGED"
                    else -> null
                }
            }
        }
    } catch (_: SecurityException) { "BLUETOOTH_PERMISSION_REQUIRED" }
    catch (_: Exception) { "READINESS_CHECK_FAILED" }

    private fun connect(op: Operation) = op.settle.start()

    private fun connectSettled(op: Operation) {
        if (!allowed(op)) return
        try {
            val device = if (op.serviceUwb != null) {
                op.serviceUwb.device ?: run { stop(op, TeslaKeyStage.BLOCKED, "SCAN_DEVICE_UNAVAILABLE"); return }
            } else {
                val selected = singleAssociation()
                    ?: run { stop(op, TeslaKeyStage.FAILED, "SINGLE_ASSOCIATION_REQUIRED"); return }
                val address = selected.deviceMacAddress
                    ?: run { stop(op, TeslaKeyStage.FAILED, "ASSOCIATION_ADDRESS_UNAVAILABLE"); return }
                op.associationId = selected.id
                bluetooth?.adapter?.getRemoteDevice(address.toByteArray())
                    ?: run { stop(op, TeslaKeyStage.FAILED, "BLUETOOTH_UNAVAILABLE"); return }
            }
            op.session.stage(TeslaKeyStage.CONNECTING, CONNECT_MS)
            if (!allowed(op)) return
            val owner = NativeTransport(op)
            op.gatt = owner
            if (!allowed(op)) return
            op.gattConnectionStatus = null
            op.gattConnectionState = null
            op.gattConnectionElapsedMs = null
            owner.connect(device)
        } catch (_: SecurityException) { stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_PERMISSION_DENIED") }
        catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED") }
    }

    private fun retryConnect(op: Operation, manager: NativeTransport, status: Int): Boolean =
        op.connectRetry.retry(status, eligible = teslaGattConnectRetryEligible(
            serviceUwb = op.serviceUwb != null,
            register = op.register, stage = state.value.stage,
            transmissionAttempted = state.value.transmissionAttempted,
            handshakeStarted = op.handshakeStarted,
            connectionProgressed = manager.connectionProgressed,
            sameHandleReconnectable = op.gatt === manager && manager.canReconnect,
            connected = op.gattConnectionState == BluetoothProfile.STATE_CONNECTED,
        ))

    private inner class NativeTransport(private val op: Operation) : BluetoothGattCallback(), TeslaBleKeySession.Transport {
        private val owner = TeslaNativeGattOwner<BluetoothGatt>()
        val allocating: Boolean get() = owner.allocating
        val connectionProgressed: Boolean get() = owner.connectionProgressed
        val canReconnect: Boolean get() = owner.canReconnect && nativeAutoConnect != null
        override val canWrite: Boolean get() = owner.stage == TeslaNativeGattStage.READY && owner.pending == null &&
            owner.handle?.let(owner::accepts) == true
        override val requestPending: Boolean get() = owner.pending != null
        override val txProperties: Int get() = checkNotNull(op.tx).properties
        private var nativeAutoConnect: Boolean? = null
        @Volatile var callbackDeliveryFailed = false
            private set

        fun connect(device: BluetoothDevice) {
            if (!allowed(op)) return
            op.connectUncertain = true
            try {
                // callback은 반드시 post하므로 SDK 반환 전에 들어와도 handle 부착 뒤 처리된다.
                val returned = owner.connectNative(app, device, this, runtime.handler) { autoConnect ->
                    nativeAutoConnect = autoConnect
                    recordConnectIssued("CREATE", autoConnect)
                }
                op.connectUncertain = owner.allocationUncertain
                op.serviceGattAdmission?.returnedHandleOwned(returned, owner.handle)
                recordConnectReturned(returned != null)
                if (returned == null) stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED")
            } catch (_: SecurityException) {
                publishServiceGatt(op, TeslaUwbGattEvent.CONNECT_RESULT)
                stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_PERMISSION_DENIED")
            } catch (_: Exception) {
                publishServiceGatt(op, TeslaUwbGattEvent.CONNECT_RESULT)
                stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED")
            } finally {
                // 취소가 SDK 안에서 재진입했어도 실제 반환 handle을 먼저 소유하고 닫는다.
                if (op.session.sealed) finishCleanup(op)
            }
        }

        fun reconnect() {
            if (!allowed(op)) return
            try {
                val accepted = owner.reconnect { native ->
                    recordConnectIssued("RECONNECT", checkNotNull(nativeAutoConnect))
                    val result = native.connect()
                    recordConnectReturned(result)
                    result
                }
                if (accepted != true) stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED")
            } catch (_: SecurityException) {
                publishServiceGatt(op, TeslaUwbGattEvent.CONNECT_RESULT)
                stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_PERMISSION_DENIED")
            } catch (_: Exception) {
                publishServiceGatt(op, TeslaUwbGattEvent.CONNECT_RESULT)
                stop(op, TeslaKeyStage.FAILED, "GATT_CONNECT_FAILED")
            } finally {
                if (op.session.sealed) finishCleanup(op)
            }
        }

        private fun recordConnectIssued(action: String, autoConnect: Boolean) {
            val issued = runtime.now()
            op.gattConnectAction = action
            op.gattAutoConnect = autoConnect
            op.gattConnectAccepted = null
            op.gattConnectIssuedElapsedMs = issued
            op.gattConnectReturnedElapsedMs = null
            op.advertisementAgeAtConnectMs = op.serviceUwb?.sourceElapsedMs?.let {
                (issued - it).takeIf { age -> age >= 0 }
            }
        }

        private fun recordConnectReturned(accepted: Boolean) {
            op.gattConnectReturnedElapsedMs = runtime.now()
            op.gattConnectAccepted = accepted
            publishServiceGatt(op, TeslaUwbGattEvent.CONNECT_RESULT)
        }

        fun seal() {
            owner.seal()
        }

        override fun closeConfirmed(): Boolean {
            seal()
            if (!owner.closeNative()) return false
            op.gatt = null
            op.connectUncertain = false
            return true
        }

        private fun post(native: BluetoothGatt, generation: Long = owner.connectionGeneration,
            discarded: () -> Unit = {}, action: () -> Unit) {
            val accepted = try {
                runtime.handler.post {
                    if (current !== op || op.gatt !== this || op.session.sealed ||
                        !owner.accepts(native, generation) || !allowed(op)) {
                        discarded()
                    } else {
                        try { action() }
                        catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "GATT_CALLBACK_FAILED") }
                    }
                }
            } catch (_: Exception) { false }
            if (!accepted) {
                callbackDeliveryFailed = true
                discarded()
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val generation = owner.connectionGeneration
            val entered = runtime.now()
            post(gatt, generation) {
                if (newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS && owner.stage != TeslaNativeGattStage.CONNECTING) return@post
                op.gattConnectionStatus = status
                op.gattConnectionState = newState
                op.gattConnectionElapsedMs = entered
                recordNativeGattStatus(op, status)
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    op.connectedObserved = true
                }
                publishServiceGatt(op, TeslaUwbGattEvent.CONNECTION_CALLBACK)
                if (!allowed(op)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                    if (owner.stage == TeslaNativeGattStage.CONNECTING && retryConnect(op, this, status)) return@post
                    stop(op, TeslaKeyStage.FAILED,
                        if (connectionProgressed) "GATT_CONNECTION_LOST" else "GATT_CONNECT_FAILED")
                    return@post
                }
                if (!owner.connected(gatt, generation)) return@post
                op.connectRetry.seal()
                op.session.stage(TeslaKeyStage.DISCOVERING, DISCOVER_MS)
                if (!allowed(op)) return@post
                val request = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.SERVICES, runtime.now()))
                if (!owner.recordRequestResult(request, if (gatt.discoverServices()) 0 else -1)) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_DISCOVERY_REJECTED")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val entered = runtime.now()
            val request = owner.pending
            post(gatt) {
                if (state.value.stage != TeslaKeyStage.DISCOVERING ||
                    !owner.completeRequest(gatt, request, TeslaNativeGattRequestKind.SERVICES, entered)) return@post
                recordNativeGattStatus(op, status)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_DISCOVERY_FAILED")
                    return@post
                }
                if (!resolveProfile(gatt)) {
                    stop(op, TeslaKeyStage.FAILED, "TESLA_GATT_PROFILE_UNSUPPORTED")
                    return@post
                }
                op.session.stage(TeslaKeyStage.NEGOTIATING_MTU, MTU_MS)
                if (!allowed(op)) return@post
                val mtuRequest = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.MTU, runtime.now()))
                if (!owner.recordRequestResult(mtuRequest, if (gatt.requestMtu(247)) 0 else -1)) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_MTU_REJECTED")
                }
            }
        }

        private fun resolveProfile(gatt: BluetoothGatt): Boolean {
            val service = gatt.services.singleOrNull { it.uuid == SERVICE } ?: return false
            op.serviceFound = true
            op.tx = service.characteristics.singleOrNull { it.uuid == TX }
            op.rx = service.characteristics.singleOrNull { it.uuid == RX }
            op.txFound = op.tx != null
            op.rxFound = op.rx != null
            op.cccd = op.rx?.descriptors?.singleOrNull { it.uuid == CCCD }
            val txProperties = op.tx?.properties ?: 0
            val rxProperties = op.rx?.properties ?: 0
            op.cccdValue = when {
                rxProperties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                rxProperties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else -> null
            }
            return op.tx != null && txProperties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 &&
                op.rx != null && op.cccd != null && op.cccdValue != null
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val entered = runtime.now()
            val request = owner.pending
            post(gatt) {
                if (state.value.stage != TeslaKeyStage.NEGOTIATING_MTU ||
                    !owner.completeRequest(gatt, request, TeslaNativeGattRequestKind.MTU, entered)) return@post
                recordNativeGattStatus(op, status)
                if (status != BluetoothGatt.GATT_SUCCESS || mtu !in 23..517) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_MTU_FAILED")
                    return@post
                }
                op.session.negotiatedMtu(mtu)
                op.session.stage(TeslaKeyStage.SUBSCRIBING, SUBSCRIBE_MS)
                if (!allowed(op) || !op.session.writePump.beginDescriptor(true)) return@post
                val rx = checkNotNull(op.rx)
                if (!gatt.setCharacteristicNotification(rx, true)) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_NOTIFICATION_REJECTED")
                    return@post
                }
                if (!allowed(op)) return@post
                val descriptorRequest = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.CCCD, runtime.now()))
                val result = try { gatt.writeDescriptor(checkNotNull(op.cccd), checkNotNull(op.cccdValue)) }
                catch (_: Exception) { stop(op, TeslaKeyStage.FAILED, "GATT_SUBSCRIPTION_FAILED"); return@post }
                op.gattDescriptorRequestStatus = result
                recordNativeGattStatus(op, result)
                val accepted = owner.recordRequestResult(descriptorRequest, result)
                publishServiceGatt(op, TeslaUwbGattEvent.STAGE)
                if (!accepted && allowed(op)) stop(op, TeslaKeyStage.FAILED, "GATT_SUBSCRIPTION_REJECTED")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val entered = runtime.now()
            val request = owner.pending
            post(gatt) {
                if (state.value.stage != TeslaKeyStage.SUBSCRIBING ||
                    descriptor !== op.cccd || descriptor.characteristic !== op.rx ||
                    !owner.completeRequest(gatt, request, TeslaNativeGattRequestKind.CCCD, entered) ||
                    !op.session.writePump.completeDescriptor()) return@post
                op.gattDescriptorCallbackStatus = status
                op.gattDescriptorCallbackElapsedMs = entered
                recordNativeGattStatus(op, status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    check(owner.ready())
                    state.value = state.value.copy(subscriptionConfirmed = true)
                }
                publishServiceGatt(op, TeslaUwbGattEvent.DESCRIPTOR_CALLBACK)
                if (!allowed(op)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_SUBSCRIPTION_FAILED")
                    return@post
                }
                runtime.event("TESLA_KEY_SUBSCRIBED")
                if (!allowed(op)) return@post
                op.handshakeStarted = op.serviceUwb != null
                op.session.start()
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            val entered = runtime.now()
            post(gatt) {
                if (entered >= (op.gattConnectIssuedElapsedMs ?: Long.MAX_VALUE) && owner.serviceChanged(gatt)) {
                    stop(op, TeslaKeyStage.FAILED, "GATT_SERVICES_INVALIDATED")
                }
            }
        }

        override fun submit(bytes: ByteArray) {
            val native = checkNotNull(owner.handle)
            val characteristic = checkNotNull(op.tx)
            if (!allowed(op) || op.gatt !== this || !canWrite || owner.handle !== native ||
                characteristic !== op.tx || !op.session.ownsSubmittingChunk(bytes)) return
            val request = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, runtime.now()))
            check(op.session.writeIssued(request))
            var result: Int? = null
            var failure: Exception? = null
            try { result = native.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
            catch (exception: Exception) { failure = exception }
            val returned = runtime.now()
            owner.recordRequestResult(request, result ?: -1)
            op.session.writeResult(request, result, failure, when (failure) {
                null -> null
                is SecurityException -> "SECURITY_EXCEPTION"
                is IllegalArgumentException -> "ILLEGAL_ARGUMENT_EXCEPTION"
                is IllegalStateException -> "ILLEGAL_STATE_EXCEPTION"
                is android.os.RemoteException -> "REMOTE_EXCEPTION"
                is RuntimeException -> "RUNTIME_EXCEPTION"
                else -> "OTHER_EXCEPTION"
            }, returnedElapsedMs = returned)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val entered = runtime.now()
            // SDK 호출 중 진입도 요청 토큰을 보존한다. 반환 관측만 post 실행 전에 완성된다.
            val request = owner.pending
            post(gatt) {
                if (characteristic !== op.tx || !op.session.writePump.writePending ||
                    !owner.completeRequest(gatt, request, TeslaNativeGattRequestKind.WRITE, entered)) return@post
                recordNativeGattStatus(op, status)
                op.session.completeWrite(request, status, entered)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val entered = runtime.now()
            receive(gatt, characteristic, value, entered)
        }

        private fun receive(native: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray, receivedAtElapsedRealtime: Long) {
            val bytes = if (value.size <= TeslaBleKeySession.MAX_QUEUED_BYTES) value.copyOf() else null
            post(native, discarded = { bytes?.fill(0) }) {
                if (owner.stage != TeslaNativeGattStage.READY || characteristic !== op.rx ||
                    !state.value.subscriptionConfirmed) {
                    bytes?.fill(0)
                    return@post
                }
                op.session.notify(bytes, receivedAtElapsedRealtime)
            }
        }
    }


    private fun publishFrunk(op: Operation) {
        if (current !== op) return
        val policy = op.frunk ?: return
        val before = state.value
        state.value = before.copy(frunkCommandSent = policy.commandSent,
            frunkCommandAcknowledged = policy.commandAcknowledged, frunkOpenObserved = policy.openObserved,
            frunkFreshParkVerified = policy.driveVerified)
        if (policy.driveVerified && !op.frunkParkPublished) {
            op.frunkParkPublished = true
            runtime.event("TESLA_FRUNK_FRESH_P_VERIFIED")
        }
        if (policy.commandSent && !before.frunkCommandSent) runtime.event("TESLA_FRUNK_COMMAND_SENT")
        if (policy.commandAcknowledged && !before.frunkCommandAcknowledged) runtime.event("TESLA_FRUNK_COMMAND_ACKNOWLEDGED")
        if (policy.openObserved && !before.frunkOpenObserved) runtime.event("TESLA_FRUNK_OPEN_OBSERVED")
    }
    private fun stop(op: Operation, terminal: TeslaKeyStage, reason: String?) {
        if (current === op) op.session.cancel(terminal, reason)
    }

    private fun finishStopped(op: Operation, terminal: TeslaKeyStage, reason: String?) {
        if (current !== op) return
        op.terminal = terminal
        op.settle.seal()
        op.connectRetry.seal()
        op.gatt?.seal()
        val before = state.value
        val outcome = when {
            op.frunk?.result == TeslaFrunkResult.REJECTED -> TeslaKeyOutcome.FAILED
            op.frunk?.result == TeslaFrunkResult.OPEN_OBSERVED -> TeslaKeyOutcome.VERIFIED_STATUS
            op.frunk?.mayHaveSent == true -> TeslaKeyOutcome.UNKNOWN
            op.frunk != null && terminal == TeslaKeyStage.CANCELED -> TeslaKeyOutcome.CANCELED
            op.frunk != null -> TeslaKeyOutcome.FAILED
            before.outcome == TeslaKeyOutcome.VERIFIED_STATUS -> before.outcome
            before.transmissionAttempted -> TeslaKeyOutcome.UNKNOWN
            terminal == TeslaKeyStage.CANCELED -> TeslaKeyOutcome.CANCELED
            else -> TeslaKeyOutcome.FAILED
        }
        op.stageStartedElapsedMs = runtime.now()
        state.value = before.copy(stage = TeslaKeyStage.CLEANING_UP, outcome = outcome, reason = reason,
            registrationUncertain = op.session.registrationAttempted && !before.registrationReported)
        if (op.uwbRequested) {
            runtime.teslaUwbState.value = runtime.teslaUwbState.value.copy(
                stage = TeslaUwbStage.CLEANING_UP, reason = reason, livePeerIndices = emptySet())
            runtime.event("TESLA_UWB_CLEANING_UP")
        }
        publishServiceGatt(op, TeslaUwbGattEvent.STAGE)
        if (op.frunk != null) runtime.event(when {
            op.frunk.commandRejected -> "TESLA_FRUNK_ACTION_REJECTED"
            outcome == TeslaKeyOutcome.UNKNOWN -> "TESLA_FRUNK_RESULT_UNKNOWN"
            terminal == TeslaKeyStage.CANCELED -> "TESLA_FRUNK_CANCELED"
            outcome == TeslaKeyOutcome.VERIFIED_STATUS -> "TESLA_FRUNK_COMPLETE"
            else -> "TESLA_FRUNK_FAILED"
        })
        runtime.event(when {
            outcome == TeslaKeyOutcome.UNKNOWN -> "TESLA_KEY_RESULT_UNKNOWN"
            terminal == TeslaKeyStage.CANCELED -> "TESLA_KEY_CANCELED"
            terminal == TeslaKeyStage.TIMED_OUT -> "TESLA_KEY_TIMEOUT"
            terminal == TeslaKeyStage.COMPLETE -> "TESLA_KEY_COMPLETE"
            else -> "TESLA_KEY_FAILED"
        })
        op.vin.fill(0)
        if (!op.preparing) finishCleanup(op)
    }

    private fun finishCleanup(op: Operation) {
        if (current !== op || !op.session.sealed || op.preparing || op.gatt?.allocating == true || op.cleanupStarted) return
        op.cleanupStarted = true
        op.vin.fill(0)
        op.associationAddress = null
        op.session.close { failed -> completeCleanup(op, failed || op.connectUncertain) }
    }

    private fun completeCleanup(op: Operation, failed: Boolean) {
        if (current !== op) return
        val completed = runtime.now()
        val cleanupElapsedMs = completed - op.stageStartedElapsedMs
        op.stageStartedElapsedMs = completed
        state.value = state.value.copy(stage = if (failed) TeslaKeyStage.CLEANUP_FAILED else op.terminal,
            localClosed = op.gatt == null && !op.connectUncertain,
            reason = if (failed) "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" else state.value.reason)
        publishServiceGatt(op, if (failed) TeslaUwbGattEvent.CLEANUP_FAILED else TeslaUwbGattEvent.LOCAL_CLOSED)
        val serviceResult = if (op.serviceUwb != null) {
            checkNotNull(runtime.teslaUwbState.value.gatt).terminalBleState(
                status = if (failed) BleProbeStatus.CLEANUP_FAILED else when (op.terminal) {
                    TeslaKeyStage.COMPLETE -> BleProbeStatus.COMPLETE
                    TeslaKeyStage.CANCELED -> BleProbeStatus.CANCELED
                    TeslaKeyStage.TIMED_OUT -> BleProbeStatus.TIMED_OUT
                    TeslaKeyStage.BLOCKED -> BleProbeStatus.BLOCKED
                    else -> BleProbeStatus.FAILED
                },
                reason = state.value.reason, localClosed = state.value.localClosed,
                gattStatus = op.nativeGattStatus, primaryGattStatus = op.primaryGattStatus,
                phaseElapsedMs = cleanupElapsedMs,
            )
        } else null
        if (op.uwbRequested) {
            val next = if (failed) TeslaUwbStage.CLEANUP_FAILED else when (op.terminal) {
                TeslaKeyStage.COMPLETE -> TeslaUwbStage.COMPLETE
                TeslaKeyStage.CANCELED -> TeslaUwbStage.CANCELED
                TeslaKeyStage.TIMED_OUT -> TeslaUwbStage.TIMED_OUT
                TeslaKeyStage.BLOCKED -> TeslaUwbStage.BLOCKED
                else -> TeslaUwbStage.FAILED
            }
            runtime.teslaUwbState.value = runtime.teslaUwbState.value.copy(stage = next,
                livePeerIndices = emptySet(),
                reason = if (failed) "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED" else runtime.teslaUwbState.value.reason)
            runtime.event(when (next) {
                TeslaUwbStage.COMPLETE -> "TESLA_UWB_COMPLETE"
                TeslaUwbStage.CANCELED -> "TESLA_UWB_CANCELED"
                TeslaUwbStage.TIMED_OUT -> "TESLA_UWB_TIMED_OUT"
                TeslaUwbStage.BLOCKED -> "TESLA_UWB_BLOCKED"
                TeslaUwbStage.CLEANUP_FAILED -> "TESLA_UWB_CLEANUP_FAILED"
                else -> "TESLA_UWB_FAILED"
            })
        }
        if (failed) {
            runtime.retainTeslaKey(op.token)
            runtime.event("TESLA_KEY_CLEANUP_FAILED")
            if (op.frunk != null) runtime.event("TESLA_FRUNK_CLEANUP_FAILED")
            op.serviceUwb?.clear()
            serviceResult?.let { op.serviceFinished?.invoke(it, true) }
            // current·lease를 유지한다. Activity 재생성/다시 취소로 close를 성공 처리하지 않는다.
        } else {
            current = null
            runtime.releaseTeslaKey(op.token)
            runtime.event("TESLA_KEY_LOCAL_CLOSED")
            if (op.uwbRequested) runtime.event("TESLA_UWB_LOCAL_CLOSED")
            if (op.frunk != null) runtime.event("TESLA_FRUNK_LOCAL_CLOSED")
            op.serviceUwb?.clear()
            serviceResult?.let { op.serviceFinished?.invoke(it, false) }
        }
    }

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private companion object {
        const val TOTAL_MS = 180_000L
        const val PREPARE_MS = 15_000L
        const val CONNECT_MS = 20_000L
        const val DISCOVER_MS = 10_000L
        const val MTU_MS = 5_000L
        const val SUBSCRIBE_MS = 10_000L
        val SERVICE: UUID = UUID.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")
        val TX: UUID = UUID.fromString("00000212-b2d1-43f0-9b88-960cebf8b91e")
        val RX: UUID = UUID.fromString("00000213-b2d1-43f0-9b88-960cebf8b91e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/** 최초 133의 같은 handle 재접속만 허용한다. 서비스 광고 입장은 이 자격을 넓히지 않는다. */
internal fun teslaGattConnectRetryEligible(
    serviceUwb: Boolean,
    register: Boolean,
    stage: TeslaKeyStage,
    transmissionAttempted: Boolean,
    handshakeStarted: Boolean,
    connectionProgressed: Boolean,
    sameHandleReconnectable: Boolean,
    connected: Boolean,
): Boolean = serviceUwb && !register && stage == TeslaKeyStage.CONNECTING &&
    !transmissionAttempted && !handshakeStarted && !connectionProgressed &&
    sameHandleReconnectable && !connected
