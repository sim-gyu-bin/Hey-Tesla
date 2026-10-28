package com.heytesla.app.vehicle.protocol

import java.util.Collections

internal enum class TeslaBleRxBodyStatus { COMPLETE, TRUNCATED, MALFORMED, UNSUPPORTED }
internal enum class TeslaBleRxRequestCorrelation { CURRENT, PREVIOUS, UNMATCHED }

internal data class TeslaBleRxBodyField(val fieldNumber: Int, val wireType: Int, val lengthBytes: Int)

/** 최상위 wire 구조만 소유한다. 본문·값·식별자·해시·예외를 참조하지 않는다. */
internal data class TeslaBleRxBodySummary(
    val byteCount: Int,
    val status: TeslaBleRxBodyStatus,
    val fields: List<TeslaBleRxBodyField>,
)

/** TRUNCATED는 입력 손상이 아니라 8항목 관측 예산 소진이다. 상한 초과 본문은 읽지 않는다. */
internal fun summarizeTeslaBleRxBody(body: ByteArray): TeslaBleRxBodySummary? = when {
    body.size > MAX_RX_BODY_BYTES -> null
    body.isEmpty() -> TeslaBleRxBodySummary(0, TeslaBleRxBodyStatus.COMPLETE, emptyList())
    else -> RxBodyScanner(body).summarize()
}

/** 생산 metadata의 canonical ASCII 표현. 영속화 경계에서는 별도로 엄격 검증한다. */
internal fun TeslaBleRxBodySummary.canonicalFields(): String = buildString(fields.size * 16) {
    var index = 0
    while (index < fields.size) {
        if (index != 0) append(',')
        val field = fields[index]
        append(field.fieldNumber).append('/').append(field.wireType).append('/').append(field.lengthBytes)
        index++
    }
}

private const val MAX_RX_BODY_BYTES = 4096
private const val MAX_RX_BODY_FIELDS = 8

/** 길이 구간은 cursor만 이동하며 중첩 메시지·group 내부로 들어가지 않는다. */
private class RxBodyScanner(private val body: ByteArray) {
    private var position = 0
    private val fields = ArrayList<TeslaBleRxBodyField>(MAX_RX_BODY_FIELDS)

    fun summarize(): TeslaBleRxBodySummary {
        while (position < body.size) {
            if (fields.size == MAX_RX_BODY_FIELDS) return finish(TeslaBleRxBodyStatus.TRUNCATED)
            val tag = readStructuralVarint()
            if (tag < 0 || tag ushr 3 == 0L) return finish(TeslaBleRxBodyStatus.MALFORMED)
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 7).toInt()
            val lengthBytes = when (wireType) {
                0 -> skipValueVarint()
                1 -> skipFixed(8)
                2 -> {
                    val length = readStructuralVarint()
                    if (length < 0 || length > MAX_RX_BODY_BYTES || length > body.size - position) {
                        return finish(TeslaBleRxBodyStatus.MALFORMED)
                    }
                    position += length.toInt()
                    length.toInt()
                }
                3, 4 -> return finish(TeslaBleRxBodyStatus.UNSUPPORTED)
                5 -> skipFixed(4)
                else -> return finish(TeslaBleRxBodyStatus.MALFORMED)
            }
            if (lengthBytes < 0) return finish(TeslaBleRxBodyStatus.MALFORMED)
            fields.add(TeslaBleRxBodyField(fieldNumber, wireType, lengthBytes))
        }
        return finish(TeslaBleRxBodyStatus.COMPLETE)
    }

    /** tag/length에 필요한 uint32만 해석한다. 긴 zero-extension도 최대 10바이트로 제한한다. */
    private fun readStructuralVarint(): Long {
        var value = 0L
        var index = 0
        while (index < 10 && position < body.size) {
            val byte = body[position++].toInt() and 0xff
            val bits = byte and 0x7f
            if (index == 4 && bits > 15 || index >= 5 && bits != 0) return -1
            if (index < 5) value = value or (bits.toLong() shl (index * 7))
            if (byte and 0x80 == 0) return value
            index++
        }
        return -1
    }

    /** wire0의 값은 조립하지 않고 encoded 길이만 남긴다. uint64 고비트 overflow도 거절한다. */
    private fun skipValueVarint(): Int {
        var length = 0
        while (length < 10 && position < body.size) {
            val byte = body[position++].toInt() and 0xff
            length++
            if (length == 10 && byte and 0x7f > 1) return -1
            if (byte and 0x80 == 0) return length
        }
        return -1
    }

    private fun skipFixed(length: Int): Int {
        if (length > body.size - position) return -1
        position += length
        return length
    }

    private fun finish(status: TeslaBleRxBodyStatus): TeslaBleRxBodySummary =
        TeslaBleRxBodySummary(body.size, status, Collections.unmodifiableList(fields))
}
