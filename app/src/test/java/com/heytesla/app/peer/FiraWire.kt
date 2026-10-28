package com.heytesla.app.peer

import java.io.ByteArrayOutputStream

/**
 * Independent private-wire oracle, not an extension of the public VCSEC schema.
 * Tesla 4.61.6-4647 APK SHA256 63adb6bb0556a44ea83996c719e2dfdcdbda4b33b8250bf46f6e5ee853c3d083.
 * Outer 54/59/48/43/58: REA ev_5ac737834dc5ce9483667f1a7934ab95a8d792ea45f13c63a1d9b61e8fea709c
 * and ev_a7243ac488ab4c631f1f33abe65454da18dd46b16a03ad4b6cb55518a7c545aa.
 * Request u0 fields1:uint32 ID,3:addresses,7:STS mode,8:vehicle STS,9:rate:
 * ev_34bbee4e3226c61c19eaf540a529d731964a46099a8b70e500b30d28dae91b4f,
 * ev_3e803031b4ab458e25a6dab65c9c45f82ce58adedab955df0e188c10acc7d00a.
 * Response v0 ID/data/optional error fields1/2/3:
 * ev_69dce2188d95144cfa582ab3da3bdd7e19b23628cd132e520437dba763bf1843;
 * t0 address/preamble/channel/new backend ID/STS/responder addresses fields1..6:
 * ev_30532a31d171bfacb2ba1fb9d39a8464f4f135d8187b10b2e9faf66c3e5dc9f9;
 * r0 items/wall seconds/optional keyId fields1/2/3:
 * ev_10797eef55f98e2aa151a79ae865964d5df901caaa5c93b7934a8ba7b0890f00,
 * ev_f8a1f4560a1d3d3f4f9526a5d1a19f846d8c67877c105a7e3672c9b54ffce671.
 * Stop/stopped repeated endpoint enum field1 (exact 0..11, not a uint32 request-ID namespace):
 * ev_6e5250c884e3861f11d5b73f8442eabfb4c3257a3679c11db9615b2799d6694f,
 * ev_e2c8fabb20432f94128ecac16e73c188cd871ce54dbfe9391393fd6612ad235f,
 * ev_a20fc4a4dbad9b00523ff1640d519b891a7eb3be345f8f29b8b78755f241312d.
 * Empty capabilities n0 (not an invented support mask):
 * ev_10c59f0efbc323a2b6db6ebc573ee96e8b566015dabd340b2ac31a8f38e3d281,
 * ev_02a04af1a7b5f34313b4238e016c084234ee16212e0d5ce7c756681a4bd2f79c.
 * No production FiRa class, protobuf reader/writer or fixture helper is used here.
 */
internal object FiraWire {
    class SessionRequest(
        val requestSessionId: Long,
        val addresses: ByteArray,
        val stsMode: Int = 1,
        val vehicleSts: ByteArray = byteArrayOf(),
        val updateRate: Int = 0,
        val rangingType: Int = 0,
        val slotDuration: Long = 0,
        val rangingInterval: Long = 0,
        val intervalSkipCount: Long = 0,
        val explicitZeroId: Boolean = false,
    ) : AutoCloseable {
        override fun close() { addresses.fill(0); vehicleSts.fill(0) }
        override fun toString(): String = "PeerFiRaSessionRequest"
    }

    sealed interface VehiclePayload : AutoCloseable {
        class Request(val sessions: List<SessionRequest>) : VehiclePayload {
            override fun close() = sessions.forEach { it.close() }
            override fun toString(): String = "PeerFiRaRequest"
        }
        class Stop(val endpoints: List<Int>) : VehiclePayload {
            override fun close() = Unit
            override fun toString(): String = "PeerFiRaStop"
        }
    }

    sealed interface ClientPayload : AutoCloseable {
        class Capabilities(
            val rangingTypeMask: Long,
            val stsModeMask: Long,
            val channelMask: Long,
            val preambleMask: Long,
            val slotDurations: List<Long>,
            val minRangingInterval: Long,
            val intervalReconfiguration: Boolean,
        ) : ClientPayload {
            override fun close() = Unit
            override fun toString(): String = "PeerFiRaCapabilities"
        }

        class Response(val sessions: List<SessionResponse>, val epochSeconds: Long,
                       val keyIdPresent: Boolean) : ClientPayload {
            override fun close() = sessions.forEach { it.data?.close() }
            override fun toString(): String = "PeerFiRaResponse"
        }

        class Stopped(val endpoints: List<Int>) : ClientPayload {
            override fun close() = Unit
            override fun toString(): String = "PeerFiRaStopped"
        }
    }

    class SessionResponse(val requestSessionId: Long, val data: SessionData?, val errorCode: Int?) {
        override fun toString(): String = "PeerFiRaSessionResponse"
    }

    class SessionData(val localAddress: ByteArray, val preamble: Long, val channel: Long,
                      val firaSessionId: Long, val sts: ByteArray,
                      val responderAddresses: ByteArray) : AutoCloseable {
        override fun close() { localAddress.fill(0); sts.fill(0); responderAddresses.fill(0) }
        override fun toString(): String = "PeerFiRaSessionData"
    }

    /** Mutation inputs intentionally allow unknown positive enums and invalid address/STS lengths. */
    fun request(sessions: List<SessionRequest>): ByteArray = encode {
        bytes(54, encode {
            for (session in sessions) bytes(1, encode {
                if (session.requestSessionId != 0L || session.explicitZeroId) uint(1, session.requestSessionId)
                if (session.rangingType != 0) uint(2, session.rangingType.toLong())
                if (session.addresses.isNotEmpty()) bytes(3, session.addresses)
                if (session.slotDuration != 0L) uint(5, session.slotDuration)
                if (session.rangingInterval != 0L) uint(6, session.rangingInterval)
                if (session.stsMode != 0) uint(7, session.stsMode.toLong())
                if (session.vehicleSts.isNotEmpty()) bytes(8, session.vehicleSts)
                if (session.updateRate != 0) uint(9, session.updateRate.toLong())
                if (session.intervalSkipCount != 0L) uint(10, session.intervalSkipCount)
            })
        })
    }

    /** 0 is one exact endpoint, never a wildcard. Unknown enum values are useful negative inputs. */
    fun stop(endpoints: List<Int>, packed: Boolean = false): ByteArray = encode {
        bytes(48, encode {
            if (packed) bytes(1, encode { endpoints.forEach { varint(it.toLong()) } })
            else endpoints.forEach { uint(1, it.toLong()) }
        })
    }

    fun capabilities(): ByteArray = encode { bytes(58, byteArrayOf()) }

    fun decodeVehiclePayload(bytes: ByteArray): VehiclePayload {
        val outer = Fields(bytes)
        require(outer.fields.size == 1) { "FIRA_OUTER_AMBIGUOUS" }
        return when (outer.fields.single().number) {
            54 -> {
                val requests = ArrayList<SessionRequest>()
                try {
                    for (entry in Fields(outer.bytes(54)).repeatedBytes(1)) {
                        val item = Fields(entry)
                        requests.add(SessionRequest(item.uint(1), item.bytes(3), item.uint(7).toInt(),
                            item.bytes(8), item.uint(9).toInt(), item.uint(2).toInt(), item.uint(5),
                            item.uint(6), item.uint(10), item.has(1)))
                    }
                    VehiclePayload.Request(requests)
                } catch (failure: Exception) {
                    requests.forEach { it.close() }
                    throw failure
                }
            }
            48 -> VehiclePayload.Stop(Fields(outer.bytes(48)).repeatedUInt(1).map {
                require(it <= Int.MAX_VALUE) { "ENDPOINT_ENUM_RANGE" }; it.toInt()
            })
            else -> throw IllegalArgumentException("VEHICLE_FIRA_KIND_NOT_ALLOWED")
        }
    }

    /** Anything but the three UWB outbound payloads is rejected, including all actuator/key commands. */
    fun decodeClientPayload(bytes: ByteArray): ClientPayload {
        val outer = Fields(bytes)
        require(outer.fields.size == 1) { "CLIENT_PAYLOAD_NOT_EXCLUSIVELY_UWB" }
        return when (outer.fields.single().number) {
            58 -> Fields(outer.bytes(58)).let { cap ->
                val reconfigure = cap.uint(7)
                require(reconfigure <= 1) { "CAPABILITIES_BOOL" }
                ClientPayload.Capabilities(cap.uint(1), cap.uint(2), cap.uint(3), cap.uint(4),
                    cap.repeatedUInt(5), cap.uint(6), reconfigure == 1L)
            }
            59 -> decodeResponse(outer.bytes(59))
            43 -> ClientPayload.Stopped(Fields(outer.bytes(43)).repeatedUInt(1).map {
                require(it in 0L..11L) { "STOPPED_UNKNOWN_ENUM" }; it.toInt()
            })
            else -> throw IllegalArgumentException("CLIENT_COMMAND_NOT_ALLOWED")
        }
    }

    private fun decodeResponse(bytes: ByteArray): ClientPayload.Response {
        val response = Fields(bytes)
        val sessions = ArrayList<SessionResponse>()
        try {
            for (entry in response.repeatedBytes(1)) {
                val item = Fields(entry)
                val data = if (item.has(2)) Fields(item.bytes(2)).let {
                    SessionData(it.bytes(1), it.uint(2), it.uint(3), it.uint(4), it.bytes(5), it.bytes(6))
                } else null
                try {
                    val error = if (item.has(3)) item.uint(3).also {
                        require(it <= Int.MAX_VALUE) { "FIRA_ERROR_ENUM_RANGE" }
                    }.toInt() else null
                    sessions.add(SessionResponse(item.uint(1), data, error))
                } catch (failure: Exception) { data?.close(); throw failure }
            }
            return ClientPayload.Response(sessions, response.uint(2), response.has(3))
        } catch (failure: Exception) {
            sessions.forEach { it.data?.close() }
            throw failure
        }
    }

    private class Field(val number: Int, val wireType: Int, val uint: Long = 0,
                        val bytes: ByteArray = byteArrayOf())

    /** Deliberately rejects duplicate singular fields rather than adopting protobuf last-one-wins. */
    private class Fields(bytes: ByteArray) {
        val fields = ArrayList<Field>()
        init {
            val input = Reader(bytes)
            while (!input.done) {
                val key = input.varint()
                require(key in 1..0xffff_ffffL) { "PROTOBUF_TAG" }
                val number = (key ushr 3).toInt()
                val wire = (key and 7).toInt()
                require(number != 0) { "PROTOBUF_FIELD_ZERO" }
                fields.add(when (wire) {
                    0 -> Field(number, wire, uint = input.varint())
                    1 -> { input.skip(8); Field(number, wire) }
                    2 -> Field(number, wire, bytes = input.bytes())
                    5 -> { input.skip(4); Field(number, wire) }
                    else -> throw IllegalArgumentException("PROTOBUF_WIRE_TYPE")
                })
            }
        }
        fun has(number: Int): Boolean = fields.any { it.number == number }
        private fun single(number: Int): Field? {
            val matches = fields.filter { it.number == number }
            require(matches.size <= 1) { "PROTOBUF_DUPLICATE_SINGULAR" }
            return matches.singleOrNull()
        }
        fun uint(number: Int): Long {
            val field = single(number) ?: return 0
            require(field.wireType == 0 && field.uint in 0..OfficialPeerCrypto.UINT32_MAX) { "PROTOBUF_UINT32" }
            return field.uint
        }
        fun bytes(number: Int): ByteArray {
            val field = single(number) ?: return byteArrayOf()
            require(field.wireType == 2) { "PROTOBUF_BYTES" }
            return field.bytes
        }
        fun repeatedBytes(number: Int): List<ByteArray> = fields.filter { it.number == number }.map {
            require(it.wireType == 2) { "PROTOBUF_REPEATED_BYTES" }; it.bytes
        }
        fun repeatedUInt(number: Int): List<Long> {
            val values = ArrayList<Long>()
            for (field in fields.filter { it.number == number }) when (field.wireType) {
                0 -> {
                    require(field.uint in 0..OfficialPeerCrypto.UINT32_MAX) { "PROTOBUF_UINT32" }
                    values.add(field.uint)
                }
                2 -> {
                    val input = Reader(field.bytes)
                    while (!input.done) values.add(input.varint().also {
                        require(it in 0..OfficialPeerCrypto.UINT32_MAX) { "PROTOBUF_UINT32" }
                    })
                }
                else -> throw IllegalArgumentException("PROTOBUF_REPEATED_UINT32")
            }
            return values
        }
    }

    private class Reader(private val data: ByteArray) {
        private var position = 0
        val done: Boolean get() = position == data.size
        fun varint(): Long {
            var value = 0L
            for (index in 0..9) {
                require(position < data.size) { "PROTOBUF_TRUNCATED_VARINT" }
                val octet = data[position++].toInt() and 255
                require(index != 9 || octet <= 1) { "PROTOBUF_VARINT_OVERFLOW" }
                value = value or ((octet and 127).toLong() shl (index * 7))
                if (octet < 128) return value
            }
            throw IllegalArgumentException("PROTOBUF_VARINT_OVERFLOW")
        }
        fun bytes(): ByteArray {
            val length = varint()
            require(length in 0..(data.size - position).toLong()) { "PROTOBUF_TRUNCATED_BYTES" }
            val result = data.copyOfRange(position, position + length.toInt())
            position += length.toInt()
            return result
        }
        fun skip(length: Int) {
            require(length <= data.size - position) { "PROTOBUF_TRUNCATED_FIXED" }
            position += length
        }
    }

    private class Writer {
        private val output = ByteArrayOutputStream()
        fun varint(value: Long) {
            require(value >= 0) { "PROTOBUF_UNSIGNED_VALUE" }
            var rest = value
            while (rest >= 128) {
                output.write(((rest and 127) or 128).toInt())
                rest = rest ushr 7
            }
            output.write(rest.toInt())
        }
        fun uint(field: Int, value: Long) {
            require(value in 0..OfficialPeerCrypto.UINT32_MAX) { "PROTOBUF_UINT32" }
            varint(field.toLong() shl 3)
            varint(value)
        }
        fun bytes(field: Int, value: ByteArray) {
            varint((field.toLong() shl 3) or 2)
            varint(value.size.toLong())
            output.write(value)
        }
        fun finish(): ByteArray = output.toByteArray()
    }

    private fun encode(block: Writer.() -> Unit): ByteArray = Writer().apply(block).finish()
}
