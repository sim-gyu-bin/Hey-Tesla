package com.heytesla.app.vehicle.uwb

import com.heytesla.app.vehicle.ble.TeslaNativeGattOwner
import com.heytesla.app.vehicle.ble.TeslaNativeGattRequestKind
import com.heytesla.app.vehicle.ble.TeslaNativeGattStage
import com.heytesla.app.vehicle.key.TeslaBleCredential
import com.heytesla.app.vehicle.key.TeslaBleKeySession
import com.heytesla.app.vehicle.key.TeslaKeyProbeState
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.key.p256PublicKeyBytes
import com.heytesla.app.vehicle.key.p256PublicKeyFromBytes
import com.heytesla.app.vehicle.navigation.VehicleCoordinateObservation
import com.heytesla.app.vehicle.protocol.TeslaBleConversation
import com.heytesla.app.vehicle.protocol.TeslaBlePurpose
import com.heytesla.app.vehicle.protocol.TeslaBleQuery
import com.heytesla.app.vehicle.protocol.TeslaBleResponseObservation
import com.heytesla.app.vehicle.protocol.TeslaBleStep
import com.heytesla.app.vehicle.protocol.TeslaFiraCapabilities
import com.heytesla.app.vehicle.protocol.TeslaUwbAppBatteryState
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceBatteryState
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceInfo
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceInfoSource
import com.heytesla.app.vehicle.protocol.TeslaUwbAppDeviceInfoVersion
import com.heytesla.app.vehicle.protocol.TeslaUwbAppThermalState
import com.heytesla.app.vehicle.protocol.TeslaUwbAvailability

import com.heytesla.app.peer.OfficialTeslaPeer
import com.heytesla.app.peer.FiraWire
import com.heytesla.app.peer.DeviceInfoWire
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.PriorityQueue
import java.util.UUID
import javax.crypto.KeyAgreement

/** Host-only serial queue: elapsed jumps can deliberately leave uptime tasks unexecuted. */
internal class PeerClock : TeslaUwbScheduler {
    var elapsed = 1_000L
    private var order = 0L
    private data class Entry(val at: Long, val order: Long, val task: Runnable)
    private val tasks = PriorityQueue<Entry>(compareBy<Entry> { it.at }.thenBy { it.order })
    var accepting = true
    override fun post(task: Runnable) = postDelayed(task, 0)
    override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
        if (!accepting) return false
        tasks.add(Entry(elapsed + delayMs, order++, task))
        return true
    }
    override fun remove(task: Runnable) { tasks.removeIf { it.task === task } }
    override fun checkThread() = Unit
    fun runReady() {
        var count = 0
        while (tasks.peek()?.at?.let { it <= elapsed } == true) {
            check(count++ < 10_000) { "serial queue did not settle" }
            tasks.remove().task.run()
        }
    }
}

/** Only the Android SDK boundary is scripted; production ranging validation/lifecycle still runs. */
internal class PeerPlatform : TeslaUwbPlatform {
    val natives = ArrayList<Native>()
    var permission = true
    var closeCompletes = true
    var closeThrows = false
    var unregisterThrows = false
    var startThrows = false
    var registrations = 0
    var unregistrations = 0
    var capabilityCallback: ((Result<TeslaUwbCapabilitySnapshot>) -> Unit)? = null
    override fun prepareCapabilities() = true
    override fun registerCapabilities(callback: (Result<TeslaUwbCapabilitySnapshot>) -> Unit) {
        registrations++
        capabilityCallback = callback
        callback(Result.success(TeslaUwbCapabilitySnapshot(
            TeslaFiraCapabilities(true, true, listOf(5, 9), listOf(9, 10, 11, 12),
                listOf(1, 2, 3, 4, 5), listOf(1, 2), listOf(1, 2, 3)), true, true)))
    }
    override fun unregisterCapabilities() {
        unregistrations++
        if (unregisterThrows) error("synthetic unregister failure")
    }
    override fun createNativeRanging(): TeslaUwbNativeRanging = Native().also { natives.add(it) }
    inner class Native : TeslaUwbNativeRanging {
        lateinit var parameters: TeslaUwbRangingParameters
        lateinit var peerIds: List<UUID>
        lateinit var callback: TeslaUwbNativeRanging.Callback
        var starts = 0
        var closes = 0
        override fun hasRangingPermission() = permission
        override fun prepare(parameters: TeslaUwbRangingParameters, peerIds: List<UUID>): TeslaUwbNativeRanging.Prepared {
            this.parameters = parameters.copy(localAddress = parameters.localAddress.copyOf(),
                peerAddresses = parameters.peerAddresses.map { it.copyOf() },
                sessionKeyInfo = parameters.sessionKeyInfo.copyOf())
            this.peerIds = peerIds.toList()
            return object : TeslaUwbNativeRanging.Prepared {}
        }
        override fun managerAvailable() = true
        override fun create(callback: TeslaUwbNativeRanging.Callback): TeslaUwbNativeRanging.Session {
            this.callback = callback
            return object : TeslaUwbNativeRanging.Session {
                override fun start(prepared: TeslaUwbNativeRanging.Prepared) {
                    starts++
                    if (startThrows) error("synthetic start failure")
                    callback.onOpened()
                    peerIds.forEach { callback.onStarted(it, TeslaUwbNativeRanging.UWB) }
                }
                override fun close() {
                    closes++
                    if (closeThrows) error("synthetic close failure")
                    if (closeCompletes) callback.onClosed(0)
                }
            }
        }
        fun result(index: Int = 0, meters: Double? = 2.5, confidence: Int? = 2,
            technology: Int = TeslaUwbNativeRanging.UWB, id: UUID? = null) {
            callback.onResults(id ?: peerIds[index], technology, meters, confidence, 123,
                0.25, 2, null, null)
        }
    }
}
/**
 * Explicit host source, not an Android fallback. Values are deliberately not BuildConfig defaults.
 * The consumer must obtain fresh owned hashes, choose model/battery snapshots, and release listeners.
 */
internal class PeerDeviceInfoSource : TeslaUwbAppDeviceInfoSource {
    val modelName = "Synthetic peer model"
    val brand = "Synthetic peer brand"
    val incremental = "synthetic-peer-build"
    val version = TeslaUwbAppDeviceInfoVersion(35, sha1Suffix(brand), sha1Suffix(incremental), 7, 18, 29)
    var availability = TeslaUwbAvailability.AVAILABLE
    var battery = TeslaUwbAppDeviceBatteryState(63, TeslaUwbAppBatteryState.UNPLUGGED,
        TeslaUwbAppThermalState.MODERATE, true)
    val snapshotRequests = ArrayList<Boolean>()
    var subscriptions = 0
        private set
    var closeCalls = 0
        private set
    var closed = false
        private set
    private val listeners = LinkedHashSet<() -> Unit>()
    val activeSubscriptions: Int get() = listeners.size
    fun modelDigest(): ByteArray = MessageDigest.getInstance("SHA-256").digest(modelName.toByteArray(Charsets.UTF_8))
    override fun snapshot(includeBatteryState: Boolean): TeslaUwbAppDeviceInfo {
        check(!closed) { "HOST_SOURCE_CLOSED" }
        snapshotRequests.add(includeBatteryState)
        return TeslaUwbAppDeviceInfo(modelDigest(), availability, version,
            battery.takeIf { includeBatteryState })
    }
    override fun subscribe(onChanged: () -> Unit) {
        check(!closed) { "HOST_SOURCE_CLOSED" }
        subscriptions++
        listeners.add(onChanged)
    }
    fun captureChanged(): () -> Unit = listeners.single()
    fun emit() { listeners.toList().forEach { it() } }
    override fun close() {
        closeCalls++
        closed = true
        listeners.clear()
    }
    override fun toString(): String = "PeerDeviceInfoSource"
    private companion object {
        fun sha1Suffix(value: String): Long {
            val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
            return try {
                digest.takeLast(4).fold(0L) { result, octet -> (result shl 8) or (octet.toLong() and 255L) }
            } finally { digest.fill(0) }
        }
    }
}


/** Same consumer, conversation, owner, pump, negotiation and ranging classes as the Android facade. */
internal class VehiclePeerHarness(
    val peer: OfficialTeslaPeer = OfficialTeslaPeer(),
    val serviceOwned: Boolean = true,
    val observeResponses: Boolean = true,
    val coordinatesRequested: Boolean = false,
) : AutoCloseable {
    val clock = PeerClock()
    val platform = PeerPlatform()
    val deviceInfoSource = PeerDeviceInfoSource()
    val requests = ArrayList<OfficialTeslaPeer.CapturedRequest>()
    val handshakes = ArrayList<OfficialTeslaPeer.Event.Handshake>()
    val peerRejections = ArrayList<String>()
    val observations = ArrayList<TeslaBleResponseObservation>()
    val gattObservations = ArrayList<TeslaUwbGattObservation>()
    val coordinateResults = ArrayList<TeslaBleStep.LocationResult>()
    var coordinatesAllowed = true
    var wallEpochMs = 1_800_000_000_000L
    var responseObserver: ((TeslaBleResponseObservation) -> Unit)? = null
    var gattObserver: ((TeslaUwbGattObservation) -> Unit)? = null
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
    var uwbState = TeslaUwbProbeState()
    private var keyState = TeslaKeyProbeState(stage = TeslaKeyStage.SUBSCRIBING)
    lateinit var session: TeslaBleKeySession
        private set
    val negotiation: TeslaUwbNegotiation
    private val transport = object : TeslaBleKeySession.Transport {
        override val canWrite get() = owner.stage == TeslaNativeGattStage.READY && owner.pending == null
        override val requestPending get() = owner.pending != null
        override val txProperties = 8 // BluetoothGattCharacteristic.PROPERTY_WRITE
        override fun submit(bytes: ByteArray) {
            val request = checkNotNull(owner.beginRequest(TeslaNativeGattRequestKind.WRITE, clock.elapsed))
            beforeWriteIssued?.invoke()
            check(session.writeIssued(request))
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
            owner.seal()
            return gattCloses
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
            query = TeslaBleQuery.BODY_STATUS, uwbRequested = true,
            deviceInfoSource = deviceInfoSource,
            now = { clock.elapsed }, post = clock::post, postDelayed = clock::postDelayed,
            remove = clock::remove, listener = object : TeslaBleKeySession.Listener {
                override var state: TeslaKeyProbeState
                    get() = keyState
                    set(value) { keyState = value }
                override val uwbStage get() = uwbState.stage
                override fun allowed() = ownerAllowed
                override fun coordinatesWanted() = coordinatesRequested && serviceOwned && coordinatesAllowed
                override fun onCoordinates(observation: VehicleCoordinateObservation?, reason: String?) {
                    coordinateResults.add(TeslaBleStep.LocationResult(observation, reason))
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
                override fun onGatt(event: TeslaUwbGattEvent) { captureGatt(event) }
                override fun onStop(stage: TeslaKeyStage, reason: String?) {
                    stopCount++; stopStage = stage; stopReason = reason
                    keyState = keyState.copy(stage = stage, reason = reason)
                    session.close {
                        cleanupFailed = it
                        captureGatt(if (it) TeslaUwbGattEvent.CLEANUP_FAILED else TeslaUwbGattEvent.LOCAL_CLOSED)
                    }
                }
            }, transport = { transport })
        session.gattObservation = TeslaUwbGattObservation(
            stage = keyState.stage, event = TeslaUwbGattEvent.STAGE,
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
                return try { MessageDigest.getInstance("SHA-1").digest(secret).copyOf(16) }
                finally { secret.fill(0) }
            }
        }
        session.conversation = TeslaBleConversation(credential, peer.vin,
            purpose = TeslaBlePurpose.UWB, serviceOwned = serviceOwned,
            onResponseObservation = if (observeResponses) { observed ->
                observations.add(observed)
                session.pendingRxResponse = observed
                responseObserver?.invoke(observed)
            } else null,
            now = { clock.elapsed })
        if (coordinatesRequested) session.locationConversationFactory = {
            TeslaBleConversation(credential, peer.vin, query = TeslaBleQuery.LOCATION_STATE,
                wallNow = { wallEpochMs }, now = { clock.elapsed })
        }
        negotiation = TeslaUwbNegotiation(platform, clock, { clock.elapsed }, { 1_800_000_000_000L },
            object : TeslaUwbNegotiation.Listener {
                override fun onCapabilitiesReady() { session.start() }
                override fun onSend(body: ByteArray, frameComplete: () -> Unit) { session.sendFira(body, frameComplete) }
                override fun onState(transform: (TeslaUwbProbeState) -> TeslaUwbProbeState, event: String) {
                    val before = uwbState
                    val next = transform(before)
                    if (!session.sealed && serviceOwned && next.stage == TeslaUwbStage.RANGING &&
                        clock.elapsed >= session.deadline) {
                        session.cancel(TeslaKeyStage.TIMED_OUT, "DEADLINE_EXPIRED")
                        return
                    }
                    uwbState = if (session.sealed) before.copy(
                        stage = if (before.stage == TeslaUwbStage.CLEANUP_FAILED) before.stage else TeslaUwbStage.CLEANING_UP,
                        livePeerIndices = emptySet(),
                        closedReason = next.closedReason, openFailedReason = next.openFailedReason)
                    else next.copy(gatt = before.gatt)
                    if (!session.sealed && serviceOwned && next.stage == TeslaUwbStage.RANGING &&
                        session.deadline != Long.MAX_VALUE) {
                        if (!session.continueServiceRanging()) return
                    }
                }
                override fun onFinish(stage: TeslaKeyStage, reason: String?) { session.cancel(stage, reason ?: "FINISHED") }
            }, serviceOwned)
        session.uwb = negotiation
        session.negotiatedMtu(517)
    }
    private fun captureGatt(event: TeslaUwbGattEvent) {
        val snapshot = checkNotNull(session.gattObservation).copy(
            stage = keyState.stage, event = event, observedElapsedMs = clock.elapsed)
        session.gattObservation = snapshot
        gattObservations.add(snapshot)
        gattObserver?.invoke(snapshot)
    }
    fun start() { session.watch(); negotiation.prepare(); clock.runReady() }
    fun inject(bytes: ByteArray, split: Int? = null) {
        if (split != null) {
            session.notify(bytes.copyOfRange(0, split), clock.elapsed)
            session.notify(bytes.copyOfRange(split, bytes.size), clock.elapsed)
        } else session.notify(bytes.copyOf(), clock.elapsed)
    }
    fun settle() = clock.runReady()
    fun completeOne(): Boolean {
        if (callbacks.isEmpty()) return false
        callbacks.removeFirst().invoke(); clock.runReady(); return true
    }
    fun completeAll() { var count = 0; while (completeOne()) check(count++ < 1000) }
    fun request(sessions: List<FiraWire.SessionRequest>, form: String = "raw", split: Int? = null) {
        val payload = FiraWire.request(sessions)
        val bytes = when (form) {
            "raw" -> peer.rawFira(payload)
            "wrapped" -> peer.wrappedFira(payload)
            "encrypted" -> peer.encryptedResponse(requests.last { it.domain == 2 }.id, payload)
            else -> error("unknown form")
        }
        inject(bytes, split); settle()
    }
    fun stop(ids: List<Int>, packed: Boolean = false) { inject(peer.rawFira(FiraWire.stop(ids, packed))); settle() }
    fun responses() = requests.mapNotNull { it.fira as? FiraWire.ClientPayload.Response }
    fun deviceInfos() = requests.mapNotNull { it.deviceInfo }
    fun deviceInfo(kind: Long, form: String = "raw") {
        val body = DeviceInfoWire.request(kind)
        val current = requests.last { it.domain == 2 }
        val bytes = when (form) {
            "raw" -> peer.rawFira(body)
            "wrapped" -> peer.wrappedFira(body, route = current.route, requestUuid = current.uuid)
            "encrypted" -> peer.encryptedResponse(current.id, body)
            else -> error("unknown form")
        }
        inject(bytes); settle()
    }
    override fun close() {
        if (!session.sealed) session.cancel()
        session.close { cleanupFailed = it }
        clock.runReady()
        peer.close()
    }
}
