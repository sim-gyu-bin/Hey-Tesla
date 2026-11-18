package com.heytesla.app.vehicle.connection

import com.heytesla.app.vehicle.ble.TeslaNativeGattOwner
import com.heytesla.app.vehicle.ble.TeslaNativeGattRequestKind
import com.heytesla.app.vehicle.ble.TeslaNativeGattStage
import com.heytesla.app.vehicle.key.TeslaBleCredential
import com.heytesla.app.vehicle.key.TeslaBleKeySession
import com.heytesla.app.vehicle.key.TeslaKeyProbeState
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.key.p256PublicKeyBytes
import com.heytesla.app.vehicle.key.p256PublicKeyFromBytes
import com.heytesla.app.vehicle.protocol.TeslaBleConversation
import com.heytesla.app.vehicle.protocol.TeslaBlePurpose
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.heytesla.app.vehicle.protocol.TeslaBleResponseObservation
import com.heytesla.app.vehicle.protocol.TeslaBleStep
import com.heytesla.app.vehicle.protocol.TeslaDriveState
import com.heytesla.app.vehicle.protocol.TeslaGear
import com.heytesla.app.vehicle.frunk.TeslaFrunkPolicy
import com.heytesla.app.vehicle.frunk.TeslaFrunkSequence
import com.google.protobuf.Timestamp
import com.tesla.generated.carserver.common.Common
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.vcsec.Vcsec

import com.heytesla.app.peer.OfficialTeslaPeer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.PriorityQueue
import javax.crypto.KeyAgreement

/** Host-only serial queue: elapsed jumps can deliberately leave uptime tasks unexecuted. */
internal class PeerClock {
    var elapsed = 1_000L
    private var order = 0L
    private data class Entry(val at: Long, val order: Long, val task: Runnable)
    private val tasks = PriorityQueue<Entry>(compareBy<Entry> { it.at }.thenBy { it.order })
    var accepting = true
    fun post(task: Runnable) = postDelayed(task, 0)
    fun postDelayed(task: Runnable, delayMs: Long): Boolean {
        if (!accepting) return false
        tasks.add(Entry(elapsed + delayMs, order++, task))
        return true
    }
    fun remove(task: Runnable) { tasks.removeIf { it.task === task } }
    /** Stops at a posted callback boundary without also dispatching the write it queues. */
    fun runOneReady(): Boolean {
        if (tasks.peek()?.at?.let { it <= elapsed } != true) return false
        tasks.remove().task.run()
        return true
    }
    fun runReady() {
        var count = 0
        while (tasks.peek()?.at?.let { it <= elapsed } == true) {
            check(count++ < 10_000) { "serial queue did not settle" }
            tasks.remove().task.run()
        }
    }
}



/** Same consumer, conversation, native owner and write pump as the Android facade. */
internal class VehiclePeerHarness(
    val peer: OfficialTeslaPeer = OfficialTeslaPeer(allowFrunk = true, allowWake = true),
    val serviceOwned: Boolean = true,
    val observeResponses: Boolean = true,
    val query: TeslaBleQuery = TeslaBleQuery.DRIVE_STATE,
) : AutoCloseable {
    val clock = PeerClock()
    val requests = ArrayList<OfficialTeslaPeer.CapturedRequest>()
    val handshakes = ArrayList<OfficialTeslaPeer.Event.Handshake>()
    val peerRejections = ArrayList<String>()
    val events = ArrayList<String>()
    val observations = ArrayList<TeslaBleResponseObservation>()
    val gattObservations = ArrayList<TeslaGattObservation>()
    class SdkWrite(val preparation: TeslaConnectionPreparationPhase,
                   val phase: com.heytesla.app.vehicle.protocol.TeslaBlePhase?,
                   val status: Int, val bytes: ByteArray)
    val sdkWrites = ArrayList<SdkWrite>()
    val parkStates = ArrayList<Pair<TeslaDriveState?, String?>>()
    var parkObserver: ((TeslaDriveState?, String?) -> Unit)? = null
    var stoppingObserver: ((String) -> Unit)? = null
    var executingObserver: (() -> Unit)? = null
    var eventObserver: ((String) -> Unit)? = null
    var sessionKeyObserver: (() -> Unit)? = null
    var wakeFactoryCalls = 0
        private set
    val driveRetransmitFlags = ArrayList<Boolean>()
    val transitions = ArrayList<String>()
    var bluetoothPermission = true
    var wallEpochMs = 1_800_000_000_000L
    var productConsent = true
    var bluetoothEnabled = true
    var responseObserver: ((TeslaBleResponseObservation) -> Unit)? = null
    var gattObserver: ((TeslaGattObservation) -> Unit)? = null
    val owner = TeslaNativeGattOwner<Any>()
    private val handle = Any()
    private val callbacks = ArrayDeque<() -> Unit>()
    var autoCallbacks = true
    var sdkStatus = 0
    var callbackStatus = 0
    var ownerAllowed = true
    var gattCloses = true
    var beforeWriteIssued: (() -> Unit)? = null
    var beforeWriteResult: (() -> Unit)? = null
    var stopCount = 0
    var cleanupFailed: Boolean? = null
    var stopReason: String? = null
    var stopStage: TeslaKeyStage? = null
    var keyState = TeslaKeyProbeState(stage = TeslaKeyStage.SUBSCRIBING)
        private set
    lateinit var session: TeslaBleKeySession
        private set
    private val transport = object : TeslaBleKeySession.Transport {
        override val canWrite get() = owner.stage == TeslaNativeGattStage.READY && owner.pending == null
        override val requestPending get() = owner.pending != null
        override val txProperties = 8 // BluetoothGattCharacteristic.PROPERTY_WRITE
        override fun submit(bytes: ByteArray) {
            val request = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, clock.elapsed))
            beforeWriteIssued?.invoke()
            check(session.writeIssued(request))
            sdkWrites.add(SdkWrite(session.preparationPhase, keyState.phase, sdkStatus, bytes.copyOf()))
            owner.recordRequestResult(request, sdkStatus)
            beforeWriteResult?.invoke()
            session.writeResult(request, sdkStatus, null)
            if (sdkStatus != 0) return
            val events = peer.consumeClientFramedBytes(bytes.copyOf(), clock.elapsed)
            for (event in events) when (event) {
                is OfficialTeslaPeer.Event.Handshake -> {
                    handshakes.add(event)
                    event.framedReply?.let { reply -> clock.post(Runnable { inject(reply) }) }
                }
                is OfficialTeslaPeer.Event.ClientRequest -> requests.add(event.request)
                is OfficialTeslaPeer.Event.Rejected -> {
                    peerRejections.add(event.reason)
                    error("independent peer rejected client: ${event.reason}")
                }
            }
            val completion = {
                if (owner.completeRequest(handle, request, TeslaNativeGattRequestKind.WRITE, clock.elapsed)) {
                    session.completeWrite(request, callbackStatus, clock.elapsed)
                }
                Unit
            }
            if (autoCallbacks) clock.post(Runnable { completion() }) else callbacks.addLast(completion)
        }
        override fun closeConfirmed(): Boolean {
            transitions.add("close")
            owner.seal()
            return owner.closeConfirmed({}, { if (!gattCloses) error("synthetic close failure") })
        }
    }
    init {
        check(owner.beginConnect()); owner.attach(handle); check(owner.connected(handle))
        for (kind in listOf(TeslaNativeGattRequestKind.SERVICES, TeslaNativeGattRequestKind.MTU,
            TeslaNativeGattRequestKind.CCCD)) {
            val request = checkNotNull(owner.beginRequest(kind, clock.elapsed))
            check(owner.recordRequestResult(request, 0))
            check(owner.completeRequest(handle, request, kind, clock.elapsed))
        }
        check(owner.ready())
        session = TeslaBleKeySession(deadline = clock.elapsed + 180_000, register = false,
            query = query, serviceConnection = serviceOwned, wallNow = { wallEpochMs },
            now = { clock.elapsed }, post = clock::post, postDelayed = clock::postDelayed,
            remove = clock::remove, listener = object : TeslaBleKeySession.Listener {
                override var state: TeslaKeyProbeState
                    get() = keyState
                    set(value) { keyState = value }
                override fun allowed(): Boolean {
                    if (!bluetoothPermission) {
                        session.cancel(TeslaKeyStage.CANCELED, "BLUETOOTH_PERMISSION_REQUIRED")
                        return false
                    }
                    if (!bluetoothEnabled) {
                        session.cancel(TeslaKeyStage.CANCELED, "BLUETOOTH_OFF")
                        return false
                    }
                    return ownerAllowed && productConsent
                }
                override fun onEvent(event: String) { events.add(event); eventObserver?.invoke(event) }
                override fun onParkState(drive: TeslaDriveState?, reason: String?) {
                    parkStates.add(drive to reason)
                    parkObserver?.invoke(drive, reason)
                }
                override fun onExecuting() {
                    transitions.add("executing")
                    executingObserver?.invoke()
                }
                override fun onStopping(reason: String) {
                    check(session.sealed)
                    transitions.add("stopping")
                    stoppingObserver?.invoke(reason)
                }
                override fun onNotification(receivedAtElapsedMs: Long) {
                    session.gattObservation = session.gattObservation?.let { previous ->
                        previous.copy(
                            firstRxElapsedMs = previous.firstRxElapsedMs ?: receivedAtElapsedMs,
                            lastRxElapsedMs = receivedAtElapsedMs,
                            rxNotificationCount = if (previous.rxNotificationCount == Long.MAX_VALUE)
                                Long.MAX_VALUE else previous.rxNotificationCount + 1,
                        )
                    }
                }
                override fun onGatt(event: TeslaGattEvent) { captureGatt(event) }
                override fun onStop(stage: TeslaKeyStage, reason: String?) {
                    stopCount++; stopStage = stage; stopReason = reason
                    keyState = keyState.copy(stage = stage, reason = reason)
                    session.close {
                        cleanupFailed = it
                        captureGatt(if (it) TeslaGattEvent.CLEANUP_FAILED else TeslaGattEvent.LOCAL_CLOSED)
                    }
                }
            }, transport = { transport })
        session.gattObservation = TeslaGattObservation(
            stage = keyState.stage, event = TeslaGattEvent.STAGE,
            operationStartedElapsedMs = clock.elapsed, stageStartedElapsedMs = clock.elapsed,
            observedElapsedMs = clock.elapsed, connectedObserved = true,
            serviceFound = true, txFound = true, rxFound = true, subscriptionConfirmed = true,
            rxFrameCount = if (serviceOwned) 0 else null,
            rxProtocolCount = if (serviceOwned) 0 else null,
        )
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val client = generator.generateKeyPair()
        val credential = object : TeslaBleCredential {
            override fun publicKeyBytes() = p256PublicKeyBytes(client.public as ECPublicKey)
            override fun deriveSessionKey(vehiclePublicKey: ByteArray): ByteArray {
                val agreement = KeyAgreement.getInstance("ECDH")
                agreement.init(client.private)
                agreement.doPhase(p256PublicKeyFromBytes(vehiclePublicKey), true)
                val secret = agreement.generateSecret()
                return try {
                    MessageDigest.getInstance("SHA-1").digest(secret).copyOf(16).also { sessionKeyObserver?.invoke() }
                }
                finally { secret.fill(0) }
            }
        }
        val onResponseObservation: ((TeslaBleResponseObservation) -> Unit)? = if (observeResponses) { observed ->
            observations.add(observed)
            session.pendingRxResponse = observed
            responseObserver?.invoke(observed)
        } else null
        fun conversation(query: TeslaBleQuery = this.query,
                         purpose: TeslaBlePurpose = TeslaBlePurpose.READ_ONLY,
                         authorizeWake: (() -> String?)? = null, wakeDeadline: Long? = null,
                         sessionRetransmitWindowMs: Long? = null) =
            TeslaBleConversation(credential, peer.vin, query = query, purpose = purpose,
                authorizeWake = authorizeWake, wakeDeadline = wakeDeadline,
                sessionRetransmitWindowMs = sessionRetransmitWindowMs,
                onResponseObservation = onResponseObservation, now = { clock.elapsed })
        if (serviceOwned) session.configureConnection(
            body = { conversation(TeslaBleQuery.BODY_STATUS) },
            wake = { authorize, deadline ->
                wakeFactoryCalls++
                conversation(TeslaBleQuery.BODY_STATUS, TeslaBlePurpose.VEHICLE_WAKE, authorize, deadline)
            },
            drive = { retransmit ->
                driveRetransmitFlags.add(retransmit)
                conversation(TeslaBleQuery.DRIVE_STATE, sessionRetransmitWindowMs =
                    if (retransmit) TeslaBleKeySession.SESSION_RETRANSMIT_WINDOW_MS else null)
            },
            command = { policy ->
                TeslaFrunkSequence(credential, peer.vin, policy, onResponseObservation) { clock.elapsed }
            },
        ) else session.conversation = conversation()
        session.negotiatedMtu(517)
    }
    private fun captureGatt(event: TeslaGattEvent) {
        val snapshot = checkNotNull(session.gattObservation).copy(
            stage = keyState.stage, event = event, observedElapsedMs = clock.elapsed)
        session.gattObservation = snapshot
        gattObservations.add(snapshot)
        gattObserver?.invoke(snapshot)
    }
    fun start() { session.watch(); session.start(); clock.runReady() }
    /** Existing DRIVE scenarios explicitly finish the new authenticated AWAKE preflight first. */
    fun startDrive() { start(); finishBodyPreflight() }
    fun answerHandshake() {
        inject(peer.sessionInfoResponse(handshakes.last().contextId, clock.elapsed))
        settle()
    }
    fun finishBodyPreflight() {
        check(serviceOwned && requests.last().kind == OfficialTeslaPeer.RequestKind.BODY)
        completeAll()
        replyBody(open = false)
        completeAll()
        if (requests.last().kind != OfficialTeslaPeer.RequestKind.DRIVE) {
            answerHandshake()
            completeAll()
        }
        check(requests.last().kind == OfficialTeslaPeer.RequestKind.DRIVE && !session.sealed)
    }
    val driveRequests get() = requests.filter { it.kind == OfficialTeslaPeer.RequestKind.DRIVE }
    fun inject(bytes: ByteArray, split: Int? = null, receivedAtElapsedMs: Long = clock.elapsed) {
        injectFrom(handle, bytes, split, receivedAtElapsedMs)
    }
    fun injectFrom(source: Any, bytes: ByteArray, split: Int? = null,
        receivedAtElapsedMs: Long = clock.elapsed, capturedGeneration: Long = owner.connectionGeneration) {
        if (!owner.accepts(source, capturedGeneration)) return
        if (split != null) {
            session.notify(bytes.copyOfRange(0, split), receivedAtElapsedMs)
            session.notify(bytes.copyOfRange(split, bytes.size), receivedAtElapsedMs)
        } else session.notify(bytes.copyOf(), receivedAtElapsedMs)
    }
    fun settle() = clock.runReady()
    fun completeOne(): Boolean {
        if (callbacks.isEmpty()) return false
        callbacks.removeFirst().invoke(); clock.runReady(); return true
    }
    fun completeAll() { var count = 0; while (completeOne()) check(count++ < 1000) }
    fun advance(millis: Long) {
        clock.elapsed += millis
        wallEpochMs += millis
        settle()
    }
    fun command() = session.dispatchServiceFrunk(TeslaFrunkPolicy(clock.elapsed, { clock.elapsed }, { wallEpochMs }))
    fun driveBody(gear: TeslaGear = TeslaGear.P, source: Long? = wallEpochMs): ByteArray {
        val shift = Vehicle.ShiftState.newBuilder().apply {
            val empty = Common.Void.getDefaultInstance()
            when (gear) {
                TeslaGear.P -> setP(empty)
                TeslaGear.R -> setR(empty)
                TeslaGear.N -> setN(empty)
                TeslaGear.D -> setD(empty)
                TeslaGear.UNKNOWN -> Unit
            }
        }
        val drive = Vehicle.DriveState.newBuilder().setShiftState(shift)
        if (source != null) drive.setTimestamp(Timestamp.newBuilder()
            .setSeconds(source / 1_000).setNanos((source % 1_000).toInt() * 1_000_000))
        return CarServer.Response.newBuilder().setVehicleData(Vehicle.VehicleData.newBuilder()
            .setDriveState(drive)).build().toByteArray()
    }
    /** UNKNOWN 기본 fixture는 EMPTY를 유지한다. wire oneof/source 변형도 실제 AEAD parser로 보낸다. */
    fun replyDriveShift(shift: Vehicle.ShiftState?,
        timestamp: Timestamp? = Timestamp.newBuilder().setSeconds(wallEpochMs / 1_000)
            .setNanos((wallEpochMs % 1_000).toInt() * 1_000_000).build(),
        request: OfficialTeslaPeer.CapturedRequest = requests.last(), receivedAt: Long = clock.elapsed,
        options: OfficialTeslaPeer.ResponseOptions = OfficialTeslaPeer.ResponseOptions()) {
        val drive = Vehicle.DriveState.newBuilder()
        if (shift != null) drive.setShiftState(shift)
        if (timestamp != null) drive.setTimestamp(timestamp)
        val body = CarServer.Response.newBuilder().setVehicleData(Vehicle.VehicleData.newBuilder()
            .setDriveState(drive)).build().toByteArray()
        val response = try { peer.encryptedResponse(request.id, body, options) } finally { body.fill(0) }
        try { inject(response, receivedAtElapsedMs = receivedAt); settle() } finally { response.fill(0) }
    }
    fun replyDrive(gear: TeslaGear = TeslaGear.P, source: Long? = wallEpochMs,
        request: OfficialTeslaPeer.CapturedRequest = requests.last(), receivedAt: Long = clock.elapsed,
        options: OfficialTeslaPeer.ResponseOptions = OfficialTeslaPeer.ResponseOptions()) {
        val body = driveBody(gear, source)
        val response = try { peer.encryptedResponse(request.id, body, options) } finally { body.fill(0) }
        try { inject(response, receivedAtElapsedMs = receivedAt); settle() } finally { response.fill(0) }
    }
    fun replyBody(open: Boolean = true, sleepStatus: Int = 1,
                  request: OfficialTeslaPeer.CapturedRequest = requests.last(),
                  receivedAt: Long = clock.elapsed,
                  options: OfficialTeslaPeer.ResponseOptions = OfficialTeslaPeer.ResponseOptions()) {
        val response = peer.bodyStatusResponse(request.id, sleepStatus, open, options)
        try { inject(response, receivedAtElapsedMs = receivedAt); settle() } finally { response.fill(0) }
    }
    fun replyWake(options: OfficialTeslaPeer.ResponseOptions = OfficialTeslaPeer.ResponseOptions(),
                  request: OfficialTeslaPeer.CapturedRequest = requests.last()) {
        val response = peer.wakeResponse(request.id, options)
        try { inject(response); settle() } finally { response.fill(0) }
    }
    override fun close() {
        if (!session.sealed) session.cancel()
        session.close { cleanupFailed = it }
        clock.runReady()
        sdkWrites.forEach { it.bytes.fill(0) }
        peer.close()
    }
}
