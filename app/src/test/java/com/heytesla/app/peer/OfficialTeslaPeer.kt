package com.heytesla.app.peer

import com.google.protobuf.ByteString
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * In-memory UWB + read-only LocationState vehicle-side oracle. No Android/BLE/RF, account,
 * storage, actuator or key-registration API. VIN is always seventeen synthetic ASCII zeroes.
 * Public wire/auth contract (pinned, not a production helper):
 * https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/pkg/protocol/protocol.md
 * https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/internal/authentication/peer.go
 * Public protobuf bindings are permitted; private FiRa/device info use independent wire oracles.
 * BE16: REA ev_c7fab75b224a30060acd0d86e300795fb25f9b605918d29ac39d40e3ba06baff.
 * Request metadata: ev_8defd1cbe9950769ee031f4bed2da8cb9a3b07e53b8af47cc03805409c7f709b.
 * Response metadata/request-tag binding: ev_8a137491ec32200665848ec3def8a900a0c5dfd0666e40343bac0767f2331010.
 * Raw/wrapped distinction: ev_48092339a640e9a22646e27e350669033ca7721233f44010b8e79bd59ecfa1eb.
 * This models a static protocol contract, not an actual vehicle or official app runtime.
 * In particular, official decrypted-FiRa collector re-entry remains unproven.
 * Captures and arrays must not be logged; toString deliberately omits synthetic payloads.
 */
internal class OfficialTeslaPeer(
    private val autoHandshake: Boolean = true,
    private val initialClockSeconds: Long = 100,
    private val initialCounter: Long = 0,
    private val autoLocationHandshake: Boolean = autoHandshake,
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
        val fira: FiraWire.ClientPayload?,
        val deviceInfo: DeviceInfoWire.Response?,
        val locationQuery: Boolean,
    ) : AutoCloseable {
        override fun close() {
            route.fill(0); uuid.fill(0); requestHash.fill(0); plaintext.fill(0)
            fira?.close(); deviceInfo?.close()
        }
        override fun toString(): String = "PeerCapturedReadOnlyOrUwbRequest"
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
    private val offered = HashMap<Long, Int>()
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
            val reply = if (autoHandshake && (domain != 3 || autoLocationHandshake)) sessionInfoResponse(context.id, nowMillis) else null
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
        var parsed: FiraWire.ClientPayload? = null
        var deviceInfo: DeviceInfoWire.Response? = null
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
            if (domain == 3) {
                // Exact nested allowlist: Action.vehicleAction(2) / getVehicleData(1) /
                // getLocationState(7), each exclusive and the final message empty.
                reject(!LocationWire.isExclusiveLocationRequest(plaintext), "ONLY_LOCATION_READ_ALLOWED")
            } else {
                parsed = try { FiraWire.decodeClientPayload(plaintext) }
                catch (_: IllegalArgumentException) {
                    deviceInfo = DeviceInfoWire.decodeResponse(plaintext)
                    null
                }
                parsed?.let(::validateClientPayload)
            }
            val id = nextId++
            val hash = OfficialPeerCrypto.requestHash(tag)
            val route = message.fromDestination.routingAddress.toByteArray()
            val uuid = message.uuid.toByteArray()
            requests[id] = RequestContext(context, route.copyOf(), uuid.copyOf(), hash.copyOf())
            val capture = CapturedRequest(id, domain, route, uuid, hash, counter, expires, flags,
                plaintext, parsed, deviceInfo, locationQuery = domain == 3)
            captures.add(capture)
            context.state.clientCounters[clientIdentity] = counter
            plaintext = null
            parsed = null
            deviceInfo = null
            return Event.ClientRequest(capture)
        } finally {
            epoch.fill(0); nonce.fill(0); tag.fill(0); plaintext?.fill(0)
            parsed?.close(); deviceInfo?.close()
        }
    }

    private fun validateClientPayload(payload: FiraWire.ClientPayload) {
        when (payload) {
            is FiraWire.ClientPayload.Capabilities -> Unit // Bit meanings are not inferred from the APK's empty n0.
            is FiraWire.ClientPayload.Response -> {
                reject(payload.sessions.isEmpty(), "FIRA_RESPONSE_EMPTY")
                reject(payload.sessions.map { it.requestSessionId }.toSet().size != payload.sessions.size, "FIRA_RESPONSE_DUPLICATE_ID")
                for (response in payload.sessions) {
                    val mode = offered[response.requestSessionId] ?: throw PeerRejection("FIRA_RESPONSE_UNOFFERED_ID")
                    reject((response.data == null) == (response.errorCode == null), "FIRA_RESPONSE_SUCCESS_ERROR_PRESENCE")
                    val data = response.data ?: continue
                    reject(payload.keyIdPresent, "FIRA_RESPONSE_UNEXPECTED_KEY_ID")
                    reject(data.localAddress.size != 2, "FIRA_RESPONSE_LOCAL_ADDRESS")
                    reject(data.channel != 5L && data.channel != 9L, "FIRA_RESPONSE_CHANNEL")
                    reject(data.preamble !in 9L..12L, "FIRA_RESPONSE_PREAMBLE")
                    reject(data.sts.size != if (mode == 1) 8 else 16, "FIRA_RESPONSE_STS_LENGTH")
                    reject(data.responderAddresses.isNotEmpty(), "FIRA_RESPONSE_UNEXPECTED_RESPONDER_ADDRESSES")
                    // Backend ID is uint32, NOT the request ID or stop enum; zero/equality are not forbidden here.
                }
            }
            is FiraWire.ClientPayload.Stopped -> {
                reject(payload.endpoints.distinct().size != payload.endpoints.size, "FIRA_STOPPED_DUPLICATE")
                reject(payload.endpoints.any { it.toLong() !in offered }, "FIRA_STOPPED_UNOFFERED_ID")
            }
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
                if (request.handshake.domain == 2) rememberVehiclePayload(plaintext)
                return result
            } finally { ciphertext.fill(0); tag.fill(0) }
        } finally { nonce.fill(0); encrypted.fill(0) }
    }

    fun rawFira(plaintext: ByteArray): ByteArray {
        check(!closed) { "PEER_CLOSED" }
        rememberVehiclePayload(plaintext)
        return frame(plaintext)
    }

    fun wrappedFira(plaintext: ByteArray, domain: Int = 2, route: ByteArray = byteArrayOf(),
                    requestUuid: ByteArray = byteArrayOf(), flags: Long = 0,
                    operationStatus: Int = 0, fault: Long = 0,
                    envelopeMutation: ((UniversalMessage.RoutableMessage.Builder) -> Unit)? = null): ByteArray {
        check(!closed) { "PEER_CLOSED" }
        val builder = responseEnvelope(domain, route, requestUuid)
            .setProtobufMessageAsBytes(ByteString.copyFrom(plaintext)).setFlags(asUInt32(flags))
            .setSignedMessageStatus(UniversalMessage.MessageStatus.newBuilder()
                .setOperationStatusValue(operationStatus).setSignedMessageFaultValue(asUInt32(fault)))
        envelopeMutation?.invoke(builder)
        rememberVehiclePayload(plaintext)
        return frame(builder.build().toByteArray())
    }

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

    private fun rememberVehiclePayload(plaintext: ByteArray) {
        // Malformed/unknown payload generation is allowed for negative scenarios, without inventing sessions.
        val payload = try { FiraWire.decodeVehiclePayload(plaintext) } catch (_: IllegalArgumentException) { return }
        payload.use {
            if (it is FiraWire.VehiclePayload.Request) for (session in it.sessions) {
                offered[session.requestSessionId] = session.stsMode
            }
        }
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
        offered.clear()
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
