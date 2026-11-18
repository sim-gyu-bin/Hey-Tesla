package com.heytesla.app.peer

import com.google.protobuf.ByteString
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.vcsec.Vcsec
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * In-memory DRIVE/BODY and explicitly opted-in wake/front-trunk wire oracle. No Android/BLE/RF,
 * account, storage or key-registration API. No physical actuator access. VIN is seventeen synthetic ASCII zeroes.
 * Public wire/auth contract (pinned, not a production helper):
 * https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/pkg/protocol/protocol.md
 * https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/internal/authentication/peer.go
 * BE16: REA ev_c7fab75b224a30060acd0d86e300795fb25f9b605918d29ac39d40e3ba06baff.
 * Request metadata: ev_8defd1cbe9950769ee031f4bed2da8cb9a3b07e53b8af47cc03805409c7f709b.
 * Response metadata/request-tag binding: ev_8a137491ec32200665848ec3def8a900a0c5dfd0666e40343bac0767f2331010.
 * This models a static protocol contract, not an actual vehicle or official app runtime.
 * Captures and arrays must not be logged; toString deliberately omits synthetic payloads.
 */
internal class OfficialTeslaPeer(
    private val autoHandshake: Boolean = true,
    private val initialClockSeconds: Long = 100,
    private val initialCounter: Long = 0,
    private val allowFrunk: Boolean = false,
    private val allowWake: Boolean = false,
) : AutoCloseable {
    sealed interface Event {
        class Handshake(val contextId: Long, val domain: Int, val framedReply: ByteArray?) : Event {
            override fun toString(): String = "PeerHandshake"
        }
        class ClientRequest(val request: CapturedRequest) : Event {
            override fun toString(): String = "PeerClientRequest"
        }
        class Rejected(val reason: String) : Event {
            override fun toString(): String = "PeerRejected($reason)"
        }
    }

    enum class RequestKind { DRIVE, BODY, WAKE, FRUNK }

    class CapturedRequest internal constructor(
        val id: Long,
        val domain: Int,
        val route: ByteArray,
        val uuid: ByteArray,
        val requestHash: ByteArray,
        val counter: Long,
        val expiresAt: Long,
        val flags: Long,
        val plaintext: ByteArray,
        val kind: RequestKind,
    ) : AutoCloseable {
        override fun close() {
            route.fill(0); uuid.fill(0); requestHash.fill(0); plaintext.fill(0)
        }
        override fun toString(): String = "PeerCapturedRequest"
    }

    /** Values affect authenticated metadata. envelopeMutation runs AFTER authentication. */
    class ResponseOptions(
        val counter: Long? = null,
        val domain: Int? = null,
        val route: ByteArray? = null,
        val requestUuid: ByteArray? = null,
        val flags: Long = 0,
        val fault: Long = 0,
        val operationStatus: Int = 0,
        val requestHash: ByteArray? = null,
        val nonce: ByteArray? = null,
        val tagTransform: ((ByteArray) -> ByteArray)? = null,
        val ciphertextTransform: ((ByteArray) -> ByteArray)? = null,
        val envelopeMutation: ((UniversalMessage.RoutableMessage.Builder) -> Unit)? = null,
    ) {
        override fun toString(): String = "PeerResponseOptions"
    }

    class SessionInfoOptions(
        val counter: Long? = null,
        val clockSeconds: Long? = null,
        val epoch: ByteArray? = null,
        val publicKey: ByteArray? = null,
        val status: Int = 0,
        /** Unknown fields, alternative field order, etc. HMAC uses these EXACT bytes. */
        val rawSessionInfo: ByteArray? = null,
        val tagTransform: ((ByteArray) -> ByteArray)? = null,
        val envelopeMutation: ((UniversalMessage.RoutableMessage.Builder) -> Unit)? = null,
    ) {
        override fun toString(): String = "PeerSessionInfoOptions"
    }

    private class DomainState(val keyPair: KeyPair, val epoch: ByteArray,
                              var clockSeconds: Long, var clockAtMillis: Long) {
        val clientCounters = HashMap<ByteString, Long>()
    }
    private class HandshakeContext(val id: Long, val domain: Int, val route: ByteArray,
                                   val uuid: ByteArray, val clientPublic: ByteArray,
                                   val key: ByteArray, val state: DomainState) {
        fun clear() { route.fill(0); uuid.fill(0); clientPublic.fill(0); key.fill(0) }
    }
    private class RequestContext(val handshake: HandshakeContext, val route: ByteArray,
                                 val uuid: ByteArray, val requestHash: ByteArray) {
        var nextResponseCounter = 1L
        fun clear() { route.fill(0); uuid.fill(0); requestHash.fill(0) }
    }

    private val syntheticVin = "00000000000000000".toByteArray(Charsets.US_ASCII)
    val vin: ByteArray get() = syntheticVin.copyOf()
    private val random = SecureRandom()
    private val framer = PeerBe16Framer()
    private val domains = HashMap<Int, DomainState>()
    private val handshakes = LinkedHashMap<Long, HandshakeContext>()
    private val requests = LinkedHashMap<Long, RequestContext>()
    private val captures = ArrayList<CapturedRequest>()
    private var nextId = 1L
    private var closed = false
    val capturedRequests: List<CapturedRequest> get() = captures.toList()

    init {
        require(initialClockSeconds in 0..OfficialPeerCrypto.UINT32_MAX)
        require(initialCounter in 0..OfficialPeerCrypto.UINT32_MAX)
    }

    /**
     * Supply actual accepted transport chunks; the independent streaming framer is always used.
     * No write-completion, delay, retransmission or production state machine is simulated here.
     * Handshake replies are returned, never injected; autoHandshake=false permits manual timing.
     */
    fun consumeClientFramedBytes(bytes: ByteArray, nowMillis: Long): List<Event> {
        check(!closed) { "PEER_CLOSED" }
        val events = ArrayList<Event>()
        for (body in framer.append(bytes)) {
            try {
                events.add(acceptBody(body, nowMillis))
            } catch (failure: PeerRejection) {
                events.add(Event.Rejected(failure.code))
            } catch (_: Exception) {
                // Provider/parser exception text can contain input; never expose it as a diagnostic.
                events.add(Event.Rejected("MALFORMED_OR_UNAUTHENTICATED_REQUEST"))
            } finally { body.fill(0) }
        }
        return events
    }

    private fun acceptBody(body: ByteArray, nowMillis: Long): Event {
        val message = UniversalMessage.RoutableMessage.parseFrom(body)
        reject(!message.hasToDestination() ||
            message.toDestination.subDestinationCase != UniversalMessage.Destination.SubDestinationCase.DOMAIN,
            "REQUEST_TO_NOT_DOMAIN")
        val domain = message.toDestination.domainValue
        reject(domain != 2 && domain != 3, "REQUEST_DOMAIN_NOT_SUPPORTED")
        reject(!message.hasFromDestination() ||
            message.fromDestination.subDestinationCase != UniversalMessage.Destination.SubDestinationCase.ROUTING_ADDRESS ||
            message.fromDestination.routingAddress.size() != 16, "REQUEST_ROUTE")
        reject(message.uuid.isEmpty || message.uuid.size() > 16, "REQUEST_UUID")
        reject(!message.requestUuid.isEmpty || message.hasSignedMessageStatus(), "REQUEST_RESPONSE_ONLY_FIELDS")
        return when (message.payloadCase) {
            UniversalMessage.RoutableMessage.PayloadCase.SESSION_INFO_REQUEST -> acceptHandshake(message, domain, nowMillis)
            UniversalMessage.RoutableMessage.PayloadCase.PROTOBUF_MESSAGE_AS_BYTES -> acceptEncrypted(message, domain, nowMillis)
            else -> throw PeerRejection("REQUEST_PAYLOAD_NOT_ALLOWED")
        }
    }

    private fun acceptHandshake(message: UniversalMessage.RoutableMessage, domain: Int, nowMillis: Long): Event {
        reject(message.hasSignatureData() || message.flags != 0, "HANDSHAKE_AUTH_OR_FLAGS")
        val publicKey = message.sessionInfoRequest.publicKey.toByteArray()
        try {
            OfficialPeerCrypto.decodePublic(publicKey)
            val challenge = message.sessionInfoRequest.challenge
            reject(!challenge.isEmpty && challenge != message.uuid, "HANDSHAKE_CHALLENGE")
            val state = domains.getOrPut(domain) {
                DomainState(OfficialPeerCrypto.freshKeyPair(), freshBytes(16), initialClockSeconds, nowMillis)
            }
            val context = HandshakeContext(nextId++, domain, message.fromDestination.routingAddress.toByteArray(),
                message.uuid.toByteArray(), publicKey.copyOf(), OfficialPeerCrypto.sharedKey(state.keyPair.private, publicKey), state)
            handshakes[context.id] = context
            val reply = if (autoHandshake) sessionInfoResponse(context.id, nowMillis) else null
            return Event.Handshake(context.id, domain, reply)
        } finally { publicKey.fill(0) }
    }

    private fun acceptEncrypted(message: UniversalMessage.RoutableMessage, domain: Int, nowMillis: Long): Event {
        reject(!message.hasSignatureData() || !message.signatureData.hasAESGCMPersonalizedData(), "REQUEST_AUTH_TYPE")
        val signature = message.signatureData
        val publicKey = signature.signerIdentity.publicKey.toByteArray()
        val context = handshakes.values.lastOrNull {
            it.domain == domain && MessageDigest.isEqual(it.clientPublic, publicKey)
        } ?: throw PeerRejection("REQUEST_UNKNOWN_SYNTHETIC_CLIENT")
        publicKey.fill(0)
        val auth = signature.getAESGCMPersonalizedData()
        val epoch = auth.epoch.toByteArray()
        val nonce = auth.nonce.toByteArray()
        val tag = auth.tag.toByteArray()
        var plaintext: ByteArray? = null
        try {
            reject(epoch.isNotEmpty() && !MessageDigest.isEqual(epoch, context.state.epoch), "REQUEST_EPOCH")
            reject(nonce.size != 12 || tag.size != 16, "REQUEST_NONCE_OR_TAG_LENGTH")
            val flags = unsigned(message.flags)
            reject(flags and 2L == 0L, "REQUEST_ENCRYPT_RESPONSE_REQUIRED")
            val counter = unsigned(auth.counter)
            val clientIdentity = ByteString.copyFrom(context.clientPublic)
            val previous = context.state.clientCounters[clientIdentity] ?: initialCounter
            reject(counter <= previous, "REQUEST_COUNTER_REPLAY")
            val expires = unsigned(auth.expiresAt)
            reject(expires < clock(context.state, nowMillis), "REQUEST_EXPIRED")
            reject(requests.values.any {
                it.handshake.domain == domain && MessageDigest.isEqual(it.handshake.clientPublic, context.clientPublic) &&
                    MessageDigest.isEqual(it.uuid, message.uuid.toByteArray())
            }, "REQUEST_UUID_REUSED")
            val metadata = OfficialPeerCrypto.requestMetadata(domain, syntheticVin, context.state.epoch,
                expires, counter, flags)
            val encrypted = message.protobufMessageAsBytes.toByteArray() + tag
            plaintext = try { OfficialPeerCrypto.aesGcm(false, context.key, nonce, metadata, encrypted) }
            finally { metadata.fill(0); encrypted.fill(0) }
            val kind = classifyRequest(domain, plaintext)
            val id = nextId++
            val hash = OfficialPeerCrypto.requestHash(tag)
            val route = message.fromDestination.routingAddress.toByteArray()
            val uuid = message.uuid.toByteArray()
            requests[id] = RequestContext(context, route.copyOf(), uuid.copyOf(), hash.copyOf())
            val capture = CapturedRequest(id, domain, route, uuid, hash, counter, expires, flags,
                plaintext, kind)
            captures.add(capture)
            context.state.clientCounters[clientIdentity] = counter
            plaintext = null
            return Event.ClientRequest(capture)
        } finally {
            epoch.fill(0); nonce.fill(0); tag.fill(0); plaintext?.fill(0)
        }
    }

    private fun classifyRequest(domain: Int, bytes: ByteArray): RequestKind {
        val drive = CarServer.Action.newBuilder().setVehicleAction(CarServer.VehicleAction.newBuilder()
            .setGetVehicleData(CarServer.GetVehicleData.newBuilder()
                .setGetDriveState(CarServer.GetDriveState.getDefaultInstance()))).build().toByteArray()
        val body = Vcsec.UnsignedMessage.newBuilder().setInformationRequest(Vcsec.InformationRequest.newBuilder()
            .setInformationRequestType(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS))
            .build().toByteArray()
        val frunk = Vcsec.UnsignedMessage.newBuilder().setClosureMoveRequest(Vcsec.ClosureMoveRequest.newBuilder()
            .setFrontTrunk(Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE)).build().toByteArray()
        val wake = Vcsec.UnsignedMessage.newBuilder()
            .setRKEActionValue(30).build().toByteArray()
        return when {
            domain == 3 && bytes.contentEquals(drive) -> RequestKind.DRIVE
            domain == 2 && bytes.contentEquals(body) -> RequestKind.BODY
            domain == 2 && allowWake && bytes.contentEquals(wake) -> RequestKind.WAKE
            domain == 2 && allowFrunk && bytes.contentEquals(frunk) -> RequestKind.FRUNK
            else -> throw PeerRejection("CLIENT_COMMAND_NOT_ALLOWED")
        }
    }

    fun sessionInfoResponse(handshakeId: Long, nowMillis: Long,
                            options: SessionInfoOptions = SessionInfoOptions()): ByteArray {
        check(!closed) { "PEER_CLOSED" }
        val context = handshakes[handshakeId] ?: error("UNKNOWN_HANDSHAKE_CONTEXT")
        val state = context.state
        val counter = options.counter ?: (state.clientCounters[ByteString.copyFrom(context.clientPublic)] ?: initialCounter)
        val seconds = options.clockSeconds ?: clock(state, nowMillis)
        val info = options.rawSessionInfo?.copyOf() ?: Signatures.SessionInfo.newBuilder()
            .setCounter(asUInt32(counter)).setClockTime(asUInt32(seconds))
            .setPublicKey(ByteString.copyFrom(options.publicKey ?: OfficialPeerCrypto.encodePublic(state.keyPair.public)))
            .setEpoch(ByteString.copyFrom(options.epoch ?: state.epoch)).setStatusValue(options.status)
            .build().toByteArray()
        val tag = OfficialPeerCrypto.sessionInfoTag(context.key, syntheticVin, context.uuid, info)
        try {
            val wireTag = options.tagTransform?.invoke(tag.copyOf()) ?: tag
            val builder = responseEnvelope(context.domain, context.route, context.uuid)
                .setSessionInfo(ByteString.copyFrom(info))
                .setSignatureData(Signatures.SignatureData.newBuilder().setSessionInfoTag(
                    Signatures.HMAC_Signature_Data.newBuilder().setTag(ByteString.copyFrom(wireTag))))
            options.envelopeMutation?.invoke(builder)
            return frame(builder.build().toByteArray())
        } finally { info.fill(0); tag.fill(0) }
    }

    /** Authenticated reply for this EXACT request, including older retained request contexts. */
    fun encryptedResponse(requestId: Long, plaintext: ByteArray,
                          options: ResponseOptions = ResponseOptions()): ByteArray {
        check(!closed) { "PEER_CLOSED" }
        val request = requests[requestId] ?: error("UNKNOWN_REQUEST_CONTEXT")
        val counter = options.counter ?: request.nextResponseCounter
        asUInt32(counter)
        val domain = options.domain ?: request.handshake.domain
        val metadata = OfficialPeerCrypto.responseMetadata(domain, syntheticVin, counter, options.flags,
            options.requestHash ?: request.requestHash, options.fault)
        val nonce = options.nonce?.copyOf() ?: freshBytes(12)
        val encrypted = try { OfficialPeerCrypto.aesGcm(true, request.handshake.key, nonce, metadata, plaintext) }
        finally { metadata.fill(0) }
        try {
            val ciphertext = encrypted.copyOfRange(0, encrypted.size - 16)
            val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)
            try {
                val wireCiphertext = options.ciphertextTransform?.invoke(ciphertext.copyOf()) ?: ciphertext
                val wireTag = options.tagTransform?.invoke(tag.copyOf()) ?: tag
                val builder = responseEnvelope(domain, options.route ?: request.route, options.requestUuid ?: request.uuid)
                    .setFlags(asUInt32(options.flags))
                    .setSignedMessageStatus(UniversalMessage.MessageStatus.newBuilder()
                        .setOperationStatusValue(options.operationStatus).setSignedMessageFaultValue(asUInt32(options.fault)))
                    .setProtobufMessageAsBytes(ByteString.copyFrom(wireCiphertext))
                    .setSignatureData(Signatures.SignatureData.newBuilder().setAESGCMResponseData(
                        Signatures.AES_GCM_Response_Signature_Data.newBuilder().setCounter(asUInt32(counter))
                            .setNonce(ByteString.copyFrom(nonce)).setTag(ByteString.copyFrom(wireTag))))
                options.envelopeMutation?.invoke(builder)
                val result = frame(builder.build().toByteArray())
                request.nextResponseCounter = maxOf(request.nextResponseCounter, counter + 1)
                return result
            } finally { ciphertext.fill(0); tag.fill(0) }
        } finally { nonce.fill(0); encrypted.fill(0) }
    }

    /** Explicit BODY sleep state; UNKNOWN is not inferred to be AWAKE or ASLEEP. */
    fun bodyStatusResponse(requestId: Long, sleepStatus: Int, open: Boolean = false,
                           options: ResponseOptions = ResponseOptions()): ByteArray {
        require(sleepStatus in 0..2)
        check(captures.single { it.id == requestId }.kind == RequestKind.BODY)
        val body = Vcsec.FromVCSECMessage.newBuilder().setVehicleStatus(Vcsec.VehicleStatus.newBuilder()
            .setVehicleSleepStatusValue(sleepStatus)
            .setClosureStatuses(Vcsec.ClosureStatuses.newBuilder().setFrontTrunkValue(if (open) 1 else 0)))
            .build().toByteArray()
        return try { encryptedResponse(requestId, body, options) } finally { body.fill(0) }
    }

    /** A terminal encrypted RKE reply is an ACK, not evidence of physical waking or P. */
    fun wakeResponse(requestId: Long, options: ResponseOptions = ResponseOptions()): ByteArray {
        check(captures.single { it.id == requestId }.kind == RequestKind.WAKE)
        return encryptedResponse(requestId, byteArrayOf(), options)
    }

    /** Unauthenticated exact nominal OK permits only a separate DRIVE read, never authenticated ACK/P. */
    fun nominalWakeResponse(requestId: Long,
                            mutation: ((UniversalMessage.RoutableMessage.Builder) -> Unit)? = null): ByteArray {
        check(captures.single { it.id == requestId }.kind == RequestKind.WAKE)
        val request = checkNotNull(requests[requestId])
        val response = responseEnvelope(request.handshake.domain, request.route, request.uuid)
            .setSignedMessageStatus(UniversalMessage.MessageStatus.newBuilder().setOperationStatusValue(0))
        mutation?.invoke(response)
        return frame(response.build().toByteArray())
    }

    /** Missing an explicit status is unknown, not the nominal compatibility case. */
    fun unconfirmedWakeResponse(requestId: Long): ByteArray =
        nominalWakeResponse(requestId) { it.clearSignedMessageStatus() }

    /** Independent peer clock control; elapsed time must not be inferred from the consumer's clock. */
    fun setDomainClock(domain: Int, seconds: Long, nowMillis: Long) {
        require(seconds in 0..OfficialPeerCrypto.UINT32_MAX)
        val state = domains[domain] ?: error("DOMAIN_HANDSHAKE_REQUIRED")
        state.clockSeconds = seconds
        state.clockAtMillis = nowMillis
    }

    private fun clock(state: DomainState, nowMillis: Long): Long {
        reject(nowMillis < state.clockAtMillis, "PEER_CLOCK_MOVED_BACKWARDS")
        val seconds = state.clockSeconds + (nowMillis - state.clockAtMillis) / 1000
        reject(seconds > OfficialPeerCrypto.UINT32_MAX, "PEER_CLOCK_EXHAUSTED")
        return seconds
    }


    private fun responseEnvelope(domain: Int, route: ByteArray, uuid: ByteArray): UniversalMessage.RoutableMessage.Builder =
        UniversalMessage.RoutableMessage.newBuilder()
            .setFromDestination(UniversalMessage.Destination.newBuilder().setDomainValue(domain))
            .setToDestination(UniversalMessage.Destination.newBuilder().setRoutingAddress(ByteString.copyFrom(route)))
            .setRequestUuid(ByteString.copyFrom(uuid))

    private fun freshBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
    private fun unsigned(value: Int): Long = value.toLong() and OfficialPeerCrypto.UINT32_MAX
    private fun asUInt32(value: Long): Int {
        require(value in 0..OfficialPeerCrypto.UINT32_MAX) { "UINT32_RANGE" }
        return value.toInt()
    }
    private fun reject(condition: Boolean, code: String) { if (condition) throw PeerRejection(code) }
    private class PeerRejection(val code: String) : IllegalArgumentException(code)

    override fun close() {
        if (closed) return
        closed = true
        framer.close()
        captures.forEach { it.close() }; captures.clear()
        requests.values.forEach { it.clear() }; requests.clear()
        handshakes.values.forEach { it.clear() }; handshakes.clear()
        domains.values.forEach { it.epoch.fill(0); it.clientCounters.clear() }; domains.clear()
        syntheticVin.fill(0)
        // JCA private/provider objects cannot promise physical JVM-memory zeroization; references are dropped.
    }

    companion object {
        fun frame(body: ByteArray): ByteArray = PeerBe16Framer.frame(body)
    }
}

/** Own BE16 decoder supports split headers/bodies and arbitrarily coalesced complete frames. */
internal class PeerBe16Framer : AutoCloseable {
    private var highByte = -1
    private var expected = -1
    private var position = 0
    private var body = byteArrayOf()

    fun append(chunk: ByteArray): List<ByteArray> {
        val complete = ArrayList<ByteArray>()
        var index = 0
        while (index < chunk.size) {
            if (expected < 0) {
                if (highByte < 0) highByte = chunk[index++].toInt() and 255
                if (index == chunk.size) break
                expected = (highByte shl 8) or (chunk[index++].toInt() and 255)
                highByte = -1
                body = ByteArray(expected)
                position = 0
            }
            val count = minOf(expected - position, chunk.size - index)
            System.arraycopy(chunk, index, body, position, count)
            index += count
            position += count
            if (position == expected) {
                complete.add(body)
                body = byteArrayOf()
                expected = -1
                position = 0
            }
        }
        return complete
    }

    override fun close() {
        body.fill(0); body = byteArrayOf(); highByte = -1; expected = -1; position = 0
    }

    companion object {
        fun frame(body: ByteArray): ByteArray {
            require(body.size <= 65535) { "BE16_FRAME_SIZE" }
            val framed = ByteArray(body.size + 2)
            framed[0] = (body.size ushr 8).toByte()
            framed[1] = body.size.toByte()
            System.arraycopy(body, 0, framed, 2, body.size)
            return framed
        }
    }
}
