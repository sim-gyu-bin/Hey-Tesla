package com.heytesla.app.peer

import java.io.ByteArrayOutputStream

/**
 * Independent private-VCSEC oracle. No production codec, generated private schema or Android API.
 * Vehicle field 44 is an enum; client field 40 is exclusively AppDeviceInfo, never an auth grant.
 * All diagnostics are structural codes, never model digests, payload bytes or source values.
 */
internal object DeviceInfoWire {
    class Version(
        val osVersion: Long,
        val brandHash: Long,
        val incrementalHash: Long,
        val appMajor: Long,
        val appMinor: Long,
        val appPatch: Long,
        val fields: Set<Int>,
    ) {
        override fun toString(): String = "PeerDeviceInfoVersion"
    }

    class Response(
        val modelSha256: ByteArray,
        val os: Long,
        val availability: Long,
        val version: Version,
        val batteryPercent: Long,
        val batteryState: Long,
        val thermal: Long,
        val lowPower: Boolean,
        val fields: Set<Int>,
    ) : AutoCloseable {
        override fun close() = modelSha256.fill(0)
        override fun toString(): String = "PeerDeviceInfoResponse"
    }

    /** Explicit NONE is useful even though a proto3 encoder would omit the default enum. */
    fun request(kind: Long): ByteArray = encode {
        varint(44L shl 3)
        varint(kind)
    }

    fun wrongWireRequest(): ByteArray = encode {
        varint((44L shl 3) or 2)
        varint(1)
        varint(1)
    }

    fun truncatedRequest(): ByteArray = byteArrayOf(0xe0.toByte(), 2, 0x80.toByte())

    /** Separate authorization request: synthetic 20-byte token, requested UNLOCK/DRIVE level. */
    fun authenticationRequest(level: Long): ByteArray {
        require(level in 1L..2L)
        val token = ByteArray(20) { 0x5a }
        return try {
            encode {
                nested(3, encode {
                    nested(2, encode { nested(1, token) })
                    varint(3L shl 3)
                    varint(level)
                })
            }
        } finally { token.fill(0) }
    }

    /** Only field40 is whitelisted. Mixed control/auth/FiRa or unknown fields cannot pass. */
    fun decodeResponse(bytes: ByteArray): Response {
        val outer = Fields(bytes, setOf(40))
        require(outer.numbers == setOf(40)) { "DEVICE_INFO_OUTER_NOT_EXCLUSIVE" }
        val info = Fields(outer.bytes(40), setOf(1, 2, 3, 4, 6, 7, 8, 9))
        require(info.numbers.containsAll(setOf(1, 2, 4))) { "DEVICE_INFO_REQUIRED_FIELDS" }
        val model = info.bytes(1)
        require(model.size == 32) { "DEVICE_INFO_MODEL_DIGEST_SIZE" }
        try {
            val os = info.uint(2)
            val availability = info.uint(3)
            require(os == 1L && availability in 0L..5L) { "DEVICE_INFO_OS_OR_AVAILABILITY" }
            val rawVersion = Fields(info.bytes(4), (1..6).toSet())
            val version = Version(rawVersion.uint(1), rawVersion.uint(2), rawVersion.uint(3),
                rawVersion.uint(4), rawVersion.uint(5), rawVersion.uint(6), rawVersion.numbers)
            val percent = info.uint(6)
            val state = info.uint(7)
            val thermal = info.uint(8)
            val lowPower = info.uint(9)
            require(percent in 0L..100L && state in 0L..3L && thermal in 0L..9L && lowPower in 0L..1L) {
                "DEVICE_INFO_BATTERY_RANGE"
            }
            return Response(model, os, availability, version, percent, state, thermal,
                lowPower == 1L, info.numbers)
        } catch (failure: Exception) {
            model.fill(0)
            throw failure
        }
    }

    private class Field(val wire: Int, val uint: Long = 0, val bytes: ByteArray = byteArrayOf())

    /** Every allowed field is singular; uint32 defaults MUST be omitted on the response wire. */
    private class Fields(bytes: ByteArray, allowed: Set<Int>) {
        private val values = LinkedHashMap<Int, Field>()
        val numbers: Set<Int> get() = values.keys.toSet()
        init {
            val reader = Reader(bytes)
            while (!reader.done) {
                val tag = reader.varint()
                require(tag in 1..0xffff_ffffL) { "DEVICE_INFO_TAG_RANGE" }
                val number = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                require(number in allowed && number !in values) { "DEVICE_INFO_UNKNOWN_OR_DUPLICATE_FIELD" }
                values[number] = when (wire) {
                    0 -> Field(wire, uint = reader.varint())
                    2 -> Field(wire, bytes = reader.bytes())
                    else -> throw IllegalArgumentException("DEVICE_INFO_WIRE_TYPE")
                }
            }
        }
        fun uint(number: Int): Long {
            val field = values[number] ?: return 0
            require(field.wire == 0 && field.uint in 1..0xffff_ffffL) { "DEVICE_INFO_UINT32_OR_DEFAULT" }
            return field.uint
        }
        fun bytes(number: Int): ByteArray {
            val field = values[number] ?: throw IllegalArgumentException("DEVICE_INFO_MISSING_NESTED_FIELD")
            require(field.wire == 2) { "DEVICE_INFO_BYTES_WIRE_TYPE" }
            return field.bytes
        }
    }

    private class Reader(private val bytes: ByteArray) {
        private var position = 0
        val done: Boolean get() = position == bytes.size
        fun varint(): Long {
            var value = 0L
            for (index in 0..9) {
                require(position < bytes.size) { "DEVICE_INFO_TRUNCATED_VARINT" }
                val octet = bytes[position++].toInt() and 255
                require(index != 9 || octet <= 1) { "DEVICE_INFO_VARINT_OVERFLOW" }
                value = value or ((octet and 127).toLong() shl (7 * index))
                if (octet < 128) return value
            }
            throw IllegalArgumentException("DEVICE_INFO_VARINT_OVERFLOW")
        }
        fun bytes(): ByteArray {
            val length = varint()
            require(length in 0..(bytes.size - position).toLong()) { "DEVICE_INFO_TRUNCATED_BYTES" }
            return bytes.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
        }
    }

    private class Writer {
        private val bytes = ByteArrayOutputStream()
        fun varint(value: Long) {
            require(value >= 0) { "DEVICE_INFO_NEGATIVE_REQUEST" }
            var rest = value
            while (rest >= 128) {
                bytes.write(((rest and 127) or 128).toInt())
                rest = rest ushr 7
            }
            bytes.write(rest.toInt())
        }
        fun nested(field: Int, value: ByteArray) {
            varint((field.toLong() shl 3) or 2)
            varint(value.size.toLong())
            bytes.write(value)
        }
        fun finish(): ByteArray = bytes.toByteArray()
    }
    private fun encode(block: Writer.() -> Unit): ByteArray = Writer().apply(block).finish()
}
