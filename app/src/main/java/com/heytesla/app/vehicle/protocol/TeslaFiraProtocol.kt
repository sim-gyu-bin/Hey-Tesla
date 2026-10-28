package com.heytesla.app.vehicle.protocol

import com.google.protobuf.CodedInputStream
import java.io.Closeable
import java.security.SecureRandom

/** APK 4.61.6-4647 adapters, not extensions to the public VCSEC reserved fields. */
internal sealed interface TeslaFiraMessage : Closeable {
    class Request(val sessions: List<TeslaFiraRequest>) : TeslaFiraMessage {
        override fun close() = sessions.forEach(TeslaFiraRequest::close)
        override fun toString(): String = "FiRaRequest"
    }
    class Stop(val endpoints: List<Int>) : TeslaFiraMessage {
        override fun close() = Unit
        override fun toString(): String = "FiRaStop"
    }
}

internal class TeslaFiraRequest(
    val requestSessionId: Long,
    val addresses: ByteArray,
    val stsMode: Int,
    val vehicleSts: ByteArray,
    val updateRate: Int,
) : Closeable {
    override fun close() { addresses.fill(0); vehicleSts.fill(0) }
    override fun toString(): String = "FiRaSessionRequest"
}

internal class TeslaFiraException(val reason: String) : IllegalArgumentException(reason)

internal data class TeslaFiraCapabilities(
    val enabled: Boolean,
    val distance: Boolean,
    val channels: List<Int>,
    val preambles: List<Int>,
    val configIds: List<Int>,
    val slotDurations: List<Int>,
    val updateRates: List<Int>,
)

internal class TeslaFiraPlan(
    val requestSessionId: Long,
    val firaSessionId: Int,
    val frameworkConfigId: Int,
    val localAddress: ByteArray,
    val peers: List<ByteArray>,
    val channel: Int,
    val preamble: Int,
    val sts: ByteArray,
    val updateRate: Int,
    val peerOffset: Int,
) : Closeable {
    override fun close() { localAddress.fill(0); peers.forEach { it.fill(0) }; sts.fill(0) }
    override fun toString(): String = "FiRaPlan"
}

/** Pure, all-or-nothing negotiation. No BLE, Android service, key creation or vehicle side effects. */
internal class TeslaFiraNegotiation(
    private val capabilities: TeslaFiraCapabilities,
    private val random: SecureRandom = SecureRandom(),
) : Closeable {
    private val owned = ArrayList<TeslaFiraPlan>()
    private var prepared = false
    private var closed = false

    fun validateCapabilities() {
        reject(!capabilities.enabled, "UWB_NOT_ENABLED")
        reject(!capabilities.distance, "DISTANCE_UNSUPPORTED")
        reject(capabilities.channels.none { it == 5 || it == 9 }, "CHANNEL_INTERSECTION_EMPTY")
        reject(capabilities.preambles.none { it in 9..12 }, "PREAMBLE_INTERSECTION_EMPTY")
        reject(2 !in capabilities.slotDurations, "SLOT_DURATION_UNSUPPORTED")
        reject(capabilities.configIds.none { it in 1..5 }, "CONFIG_UNSUPPORTED")
        reject(capabilities.updateRates.none { it in 1..3 }, "UPDATE_RATE_UNSUPPORTED")
    }

    fun prepare(request: TeslaFiraMessage.Request): List<TeslaFiraPlan> {
        reject(closed || prepared, "NEGOTIATION_ALREADY_ACTIVE")
        validateCapabilities()
        reject(request.sessions.isEmpty(), "EMPTY_REQUEST")
        val requestSessionIds = HashSet<Long>()
        request.sessions.forEach { reject(!requestSessionIds.add(it.requestSessionId), "DUPLICATE_ENDPOINT") }
        request.sessions.forEach { validateRequest(it) }
        val channel = if (5 in capabilities.channels) 5 else 9
        val preambles = capabilities.preambles.filter { it in 9..12 }.distinct()
        var offset = 0
        try {
            for (session in request.sessions) {
                val peers = ArrayList<ByteArray>(session.addresses.size / 2)
                val local = ByteArray(2)
                val sts = ByteArray(if (session.stsMode == 1) 8 else 16)
                var transferred = false
                try {
                    for (index in session.addresses.indices step 2) {
                        peers.add(byteArrayOf(session.addresses[index], session.addresses[index + 1]))
                    }
                    val config = config(session.stsMode, peers.size)
                    do { random.nextBytes(local) } while (invalidAddress(local) || peers.any { it.contentEquals(local) })
                    var id: Int
                    do { id = random.nextInt() } while (id == 0 || owned.any { it.firaSessionId == id })
                    random.nextBytes(sts)
                    owned.add(TeslaFiraPlan(session.requestSessionId, id, config, local, peers,
                        channel, preambles[random.nextInt(preambles.size)], sts,
                        if (session.updateRate == 0) 3 else session.updateRate, offset))
                    transferred = true
                    offset += peers.size
                } finally {
                    if (!transferred) { local.fill(0); sts.fill(0); peers.forEach { it.fill(0) } }
                }
            }
            prepared = true
            return owned.toList()
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    fun selectStop(endpoints: List<Int>, activeRequestSessionIds: Set<Long>): List<TeslaFiraPlan> {
        reject(closed || !prepared, "STOP_WITHOUT_SESSION")
        // Unknown or unmatched stop values fail closed by our policy, not APK equivalence.
        reject(endpoints.isEmpty() || endpoints.any { it !in 0..11 }, "INVALID_STOP_ENDPOINT")
        reject(endpoints.any { it.toLong() !in activeRequestSessionIds }, "UNKNOWN_STOP_ENDPOINT")
        return owned.filter { plan -> endpoints.any { it.toLong() == plan.requestSessionId } }
    }

    private fun validateRequest(request: TeslaFiraRequest) {
        reject(request.requestSessionId !in 0..0xffff_ffffL, "UNSUPPORTED_ENDPOINT")
        reject(request.addresses.isEmpty() || request.addresses.size % 2 != 0, "INVALID_PEER_ADDRESSES")
        val peerCount = request.addresses.size / 2
        val seen = HashSet<Int>()
        for (index in request.addresses.indices step 2) {
            val value = ((request.addresses[index].toInt() and 255) shl 8) or
                (request.addresses[index + 1].toInt() and 255)
            reject(value == 0 || value == 65535 || !seen.add(value), "INVALID_PEER_ADDRESSES")
        }
        reject(request.vehicleSts.isNotEmpty(), "VEHICLE_STS_UNSUPPORTED")
        reject(request.stsMode !in 1..3, "STS_MODE_UNSUPPORTED")
        reject(request.updateRate !in 0..3, "UPDATE_RATE_UNSUPPORTED")
        val rate = if (request.updateRate == 0) 3 else request.updateRate
        reject(rate !in capabilities.updateRates, "UPDATE_RATE_UNSUPPORTED")
        config(request.stsMode, peerCount)
        // APK uses a fixed 2ms slot and rate policy; request intervals/slot units remain unconfirmed.
        // Request field 2 has no observed APK config branch; it remains an ignored wire field.
    }

    private fun config(mode: Int, peers: Int): Int {
        val preferred = when (mode) { 1 -> 2; 2 -> 4; 3 -> 5; else -> 0 }
        if (preferred in capabilities.configIds) return preferred
        val unicast = when (mode) { 1 -> 1; 2 -> 3; else -> 0 }
        if (peers == 1 && unicast in capabilities.configIds && unicast != 0) return unicast
        throw TeslaFiraException("CONFIG_UNSUPPORTED")
    }

    override fun close() {
        closed = true
        owned.forEach(TeslaFiraPlan::close)
        owned.clear()
    }

    private fun invalidAddress(bytes: ByteArray): Boolean =
        bytes.all { it == 0.toByte() } || bytes.all { it == 255.toByte() }

    private fun reject(condition: Boolean, reason: String) { if (condition) throw TeslaFiraException(reason) }
}

internal object TeslaFiraProtocol {
    /** CAPABILITIES_UNSPECIFIED: exact empty n0 from lg0.b.a; no inferred support masks. */
    fun capabilities(): ByteArray = message { bytes(58, ByteArray(0)) }

    fun response(plans: List<TeslaFiraPlan>, epochSeconds: Long): ByteArray {
        require(epochSeconds in 0..0xffff_ffffL)
        return message {
            nested(59) {
                plans.forEach { plan ->
                    nested(1) {
                        if (plan.requestSessionId != 0L) uint(1, plan.requestSessionId)
                        nested(2) {
                            bytes(1, plan.localAddress)
                            uint(2, plan.preamble.toLong())
                            uint(3, plan.channel.toLong())
                            uint(4, plan.firaSessionId.toLong() and 0xffff_ffffL)
                            bytes(5, plan.sts)
                        }
                        // Success errorCode must be absent; zero means FIRAERROR_UNKNOWN.
                    }
                }
                uint(2, epochSeconds)
                // keyId absent: STS is never a long-term key identifier.
            }
        }
    }

    /** Exact enum conversion only; an empty result means our caller must omit the stopped TX. */
    fun stoppedEndpoints(requestSessionIds: Collection<Long>): List<Int> =
        requestSessionIds.mapNotNull { if (it in 0L..11L) it.toInt() else null }

    fun stopped(endpoints: List<Int>): ByteArray {
        require(endpoints.isNotEmpty() && endpoints.all { it in 0..11 })
        return message { nested(43) { endpoints.distinct().forEach { uint(1, it.toLong()) } } }
    }

    fun decode(body: ByteArray): TeslaFiraMessage? {
        require(body.size <= TESLA_BLE_MAX_FRAME_BYTES)
        val input = CodedInputStream.newInstance(body)
        var result: TeslaFiraMessage? = null
        try {
            while (!input.isAtEnd) {
                val tag = input.readTag()
                when (tag) {
                    (54 shl 3) or 2 -> {
                        if (result != null) throw TeslaFiraException("AMBIGUOUS_FIRA_MESSAGE")
                        result = readRequests(input.readByteArray())
                    }
                    (48 shl 3) or 2 -> {
                        if (result != null) throw TeslaFiraException("AMBIGUOUS_FIRA_MESSAGE")
                        result = readStop(input.readByteArray())
                    }
                    else -> if (!input.skipField(tag)) throw TeslaFiraException("INVALID_FIRA_MESSAGE")
                }
            }
            return result
        } catch (_: Exception) {
            result?.close()
            throw TeslaFiraException("INVALID_FIRA_MESSAGE")
        }
    }

    private fun readRequests(bytes: ByteArray): TeslaFiraMessage.Request {
        val sessions = ArrayList<TeslaFiraRequest>()
        try {
            val input = CodedInputStream.newInstance(bytes)
            while (!input.isAtEnd) {
                val tag = input.readTag()
                if (tag == 10) sessions.add(readSession(input.readByteArray()))
                else if (!input.skipField(tag)) throw TeslaFiraException("INVALID_FIRA_MESSAGE")
            }
            return TeslaFiraMessage.Request(sessions)
        } catch (failure: Exception) {
            sessions.forEach(TeslaFiraRequest::close)
            throw failure
        } finally { bytes.fill(0) }
    }

    private fun readSession(bytes: ByteArray): TeslaFiraRequest {
        var requestSessionId = 0L
        var addresses = ByteArray(0)
        var mode = 0
        var sts = ByteArray(0)
        var rate = 0
        try {
            val input = CodedInputStream.newInstance(bytes)
            while (!input.isAtEnd) {
                val tag = input.readTag()
                when (tag) {
                    8 -> requestSessionId = input.readUInt32().toLong() and 0xffff_ffffL
                    26 -> { addresses.fill(0); addresses = input.readByteArray() }
                    56 -> mode = input.readEnum()
                    66 -> { sts.fill(0); sts = input.readByteArray() }
                    72 -> rate = input.readEnum()
                    else -> if (!input.skipField(tag)) throw TeslaFiraException("INVALID_FIRA_MESSAGE")
                }
            }
            return TeslaFiraRequest(requestSessionId, addresses, mode, sts, rate)
        } catch (failure: Exception) {
            addresses.fill(0); sts.fill(0)
            throw failure
        } finally { bytes.fill(0) }
    }

    private fun readStop(bytes: ByteArray): TeslaFiraMessage.Stop {
        try {
            val input = CodedInputStream.newInstance(bytes)
            val endpoints = ArrayList<Int>()
            while (!input.isAtEnd) {
                when (val tag = input.readTag()) {
                    8 -> endpoints.add(input.readEnum())
                    10 -> {
                        val limit = input.pushLimit(input.readRawVarint32())
                        while (input.bytesUntilLimit > 0) endpoints.add(input.readEnum())
                        input.popLimit(limit)
                    }
                    else -> if (!input.skipField(tag)) throw TeslaFiraException("INVALID_FIRA_MESSAGE")
                }
            }
            return TeslaFiraMessage.Stop(endpoints.distinct())
        } finally { bytes.fill(0) }
    }

    private fun message(build: Writer.() -> Unit): ByteArray {
        val writer = Writer()
        try { writer.build(); return writer.finish() } finally { writer.close() }
    }

    /** Small bounded protobuf writer; every temporary plaintext buffer is caller-owned and wiped. */
    private class Writer : Closeable {
        private var buffer = ByteArray(64)
        private var size = 0
        private fun reserve(count: Int) {
            require(size + count <= TESLA_BLE_MAX_FRAME_BYTES)
            if (size + count <= buffer.size) return
            val grown = buffer.copyOf(maxOf(size + count, buffer.size * 2))
            buffer.fill(0)
            buffer = grown
        }
        private fun varint(input: Long) {
            var value = input
            reserve(10)
            while (value > 127L) {
                buffer[size++] = ((value.toInt() and 127) or 128).toByte()
                value = value ushr 7
            }
            buffer[size++] = value.toByte()
        }
        fun uint(field: Int, value: Long) {
            require(value in 0..0xffff_ffffL)
            varint((field shl 3).toLong())
            varint(value)
        }
        fun bytes(field: Int, value: ByteArray) {
            varint(((field shl 3) or 2).toLong())
            varint(value.size.toLong())
            reserve(value.size)
            value.copyInto(buffer, size)
            size += value.size
        }
        fun nested(field: Int, build: Writer.() -> Unit) {
            val value = message(build)
            try { bytes(field, value) } finally { value.fill(0) }
        }
        fun finish(): ByteArray = buffer.copyOf(size)
        override fun close() { buffer.fill(0); size = 0 }
    }
}
