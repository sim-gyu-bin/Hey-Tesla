package com.heytesla.app.peer

import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream

/** Public pinned schema, encoded independently of the production parser and generated vehicle types. */
internal object LocationWire {
    fun isExclusiveLocationRequest(bytes: ByteArray): Boolean =
        bytes.contentEquals(byteArrayOf(0x12, 0x04, 0x0a, 0x02, 0x3a, 0x00))

    class Fix(
        val latitude: Float? = null, val longitude: Float? = null,
        val nativeLatitude: Float? = null, val nativeLongitude: Float? = null,
        /** 1=GCJ, 2=WGS; null means no type message. */
        val nativeType: Int? = null, val nativeSupported: Boolean? = null,
        val rawLatitude: Float? = null, val rawLongitude: Float? = null,
        val correctedLatitude: Float? = null, val correctedLongitude: Float? = null,
        val estimatedValid: Boolean? = null, val gpsSeconds: Long? = 1_800_000_000L,
        val stateSeconds: Long? = null, val stateNanos: Int = 0,
    ) {
        override fun toString(): String = "SyntheticPeerLocationFix"
    }

    fun response(fix: Fix? = Fix(), result: Int = 0): ByteArray = encode {
        if (result != 0) nested(1, encode { writeEnum(1, result) })
        if (fix != null) nested(2, encode { nested(8, encode {
            fix.latitude?.let { writeFloat(101, it) }
            fix.longitude?.let { writeFloat(102, it) }
            fix.gpsSeconds?.let { writeUInt64(104, it) }
            fix.nativeSupported?.let { writeBool(105, it) }
            fix.nativeLatitude?.let { writeFloat(106, it) }
            fix.nativeLongitude?.let { writeFloat(107, it) }
            fix.nativeType?.let { type -> nested(8, encode { nested(type, byteArrayOf()) }) }
            fix.correctedLatitude?.let { writeFloat(109, it) }
            fix.correctedLongitude?.let { writeFloat(110, it) }
            fix.rawLatitude?.let { writeFloat(114, it) }
            fix.rawLongitude?.let { writeFloat(115, it) }
            fix.estimatedValid?.let { writeBool(119, it) }
            fix.stateSeconds?.let { seconds -> nested(11, encode {
                writeInt64(1, seconds)
                if (fix.stateNanos != 0) writeInt32(2, fix.stateNanos)
            }) }
        }) })
    }

    private fun CodedOutputStream.nested(field: Int, bytes: ByteArray) = writeByteArray(field, bytes)
    private fun encode(body: CodedOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        CodedOutputStream.newInstance(bytes).apply { body(); flush() }
        return bytes.toByteArray()
    }
}
