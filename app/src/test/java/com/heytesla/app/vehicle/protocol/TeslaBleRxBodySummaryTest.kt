package com.heytesla.app.vehicle.protocol

import org.junit.Assert.*
import org.junit.Test

class TeslaBleRxBodySummaryTest {
    @Test fun emptyAndByteCapDistinguishObservedEmptyFromExcludedBody() {
        assertEquals(TeslaBleRxBodySummary(0, TeslaBleRxBodyStatus.COMPLETE, emptyList()),
            summarizeTeslaBleRxBody(byteArrayOf()))
        assertEquals("", requireNotNull(summarizeTeslaBleRxBody(byteArrayOf())).canonicalFields())
        val boundary = byteArrayOf(0x0a, 0xfd.toByte(), 0x1f) + ByteArray(4093) { 0x7f }
        val observed = requireNotNull(summarizeTeslaBleRxBody(boundary))
        assertEquals(4096, observed.byteCount)
        assertEquals(TeslaBleRxBodyStatus.COMPLETE, observed.status)
        assertEquals(listOf(TeslaBleRxBodyField(1, 2, 4093)), observed.fields)
        assertNull(summarizeTeslaBleRxBody(boundary + byteArrayOf(0)))
    }

    @Test fun scalarLengthsAndMaximumFieldNumberContainNoValues() {
        val body = byteArrayOf(0x08, 0x7f, 0x10, 0x80.toByte(), 0x01, 0x19) + ByteArray(8) { 0x55 } +
            byteArrayOf(0x25) + ByteArray(4) { 0x66 } +
            byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f) +
            ByteArray(9) { 0xff.toByte() } + byteArrayOf(0x01)
        val observed = requireNotNull(summarizeTeslaBleRxBody(body))
        assertEquals(TeslaBleRxBodyStatus.COMPLETE, observed.status)
        assertEquals(listOf(TeslaBleRxBodyField(1, 0, 1), TeslaBleRxBodyField(2, 0, 2),
            TeslaBleRxBodyField(3, 1, 8), TeslaBleRxBodyField(4, 5, 4),
            TeslaBleRxBodyField(536870911, 0, 10)), observed.fields)
        assertEquals("1/0/1,2/0/2,3/1/8,4/5/4,536870911/0/10", observed.canonicalFields())
        assertEquals(body.size, observed.byteCount)
    }

    @Test fun lengthDelimitedIsTopLevelOnlyAndExcludesLengthPrefix() {
        // Nested bytes deliberately do not form a valid protobuf; the scanner must not descend.
        val body = byteArrayOf(0x0a, 0x00, 0x12, 0x83.toByte(), 0x00, 0x00, 0xff.toByte(), 0x80.toByte())
        val observed = requireNotNull(summarizeTeslaBleRxBody(body))
        assertEquals(TeslaBleRxBodyStatus.COMPLETE, observed.status)
        assertEquals(listOf(TeslaBleRxBodyField(1, 2, 0), TeslaBleRxBodyField(2, 2, 3)), observed.fields)
        assertEquals("1/2/0,2/2/3", observed.canonicalFields())
    }

    @Test fun boundedVarintsAcceptZeroExtensionButRejectHighBitsOverflowAndTruncation() {
        val extendedTag = byteArrayOf(0x88.toByte()) + ByteArray(8) { 0x80.toByte() } + byteArrayOf(0)
        val extendedValue = ByteArray(9) { 0x80.toByte() } + byteArrayOf(0)
        assertEquals(listOf(TeslaBleRxBodyField(1, 0, 10)),
            requireNotNull(summarizeTeslaBleRxBody(extendedTag + extendedValue)).fields)
        val extendedLength = ByteArray(9) { 0x80.toByte() } + byteArrayOf(0)
        assertEquals(listOf(TeslaBleRxBodyField(1, 2, 0)),
            requireNotNull(summarizeTeslaBleRxBody(byteArrayOf(0x0a) + extendedLength)).fields)
        val invalid = listOf(
            byteArrayOf(0x80.toByte()), // truncated tag
            ByteArray(10) { 0x80.toByte() } + byteArrayOf(0), // tag exceeds ten bytes
            byteArrayOf(0x88.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10, 0), // tag > uint32
            byteArrayOf(0x08, 0x80.toByte()), // truncated scalar
            byteArrayOf(0x08) + ByteArray(9) { 0xff.toByte() } + byteArrayOf(0x02), // uint64 overflow
            byteArrayOf(0x08) + ByteArray(10) { 0x80.toByte() } + byteArrayOf(0),
            byteArrayOf(0x0a, 0x80.toByte()), // truncated length
            byteArrayOf(0x0a, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10),
            byteArrayOf(0x0a) + ByteArray(9) { 0x80.toByte() } + byteArrayOf(0x01), // no low-bit wrap
            byteArrayOf(0x0a, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f),
        )
        for (wire in invalid) assertMalformed(wire)
    }

    @Test fun malformedLengthsFixedFieldsAndIllegalTagsKeepOnlyCompletedPrefix() {
        for (wire in listOf(
            byteArrayOf(0), byteArrayOf(1), byteArrayOf(0x0e), byteArrayOf(0x0f),
            byteArrayOf(0x0a, 0x02, 0x01), // declared payload crosses end
            byteArrayOf(0x0a, 0x81.toByte(), 0x20), // length 4097 exceeds observation cap
            byteArrayOf(0x09) + ByteArray(7),
            byteArrayOf(0x0d) + ByteArray(3),
        )) assertMalformed(wire)
    }

    @Test fun groupsStopBeforeRecordingAnItemAndNeverDescend() {
        for (groupTag in listOf(0x0b, 0x0c)) {
            val wire = byteArrayOf(0x10, 0x01, groupTag.toByte()) + ByteArray(1024) { 0x0b }
            val observed = requireNotNull(summarizeTeslaBleRxBody(wire))
            assertEquals(TeslaBleRxBodyStatus.UNSUPPORTED, observed.status)
            assertEquals(listOf(TeslaBleRxBodyField(2, 0, 1)), observed.fields)
            assertEquals(wire.size, observed.byteCount)
        }
    }

    @Test fun exactlyEightIsCompleteWhileAnyUninspectedNinthItemIsTruncated() {
        val eight = ByteArray(16) { if (it % 2 == 0) 0x08 else 0x01 }
        val complete = requireNotNull(summarizeTeslaBleRxBody(eight))
        assertEquals(TeslaBleRxBodyStatus.COMPLETE, complete.status)
        assertEquals(8, complete.fields.size)
        for (tail in listOf(byteArrayOf(0x08, 0x01), byteArrayOf(0), byteArrayOf(0x80.toByte()))) {
            val observed = requireNotNull(summarizeTeslaBleRxBody(eight + tail))
            assertEquals(TeslaBleRxBodyStatus.TRUNCATED, observed.status)
            assertEquals(complete.fields, observed.fields)
        }
        val maximumFields = firaTestMessage {
            repeat(8) { writeByteArray(536870911, ByteArray(504)) }
        }
        val maximum = requireNotNull(summarizeTeslaBleRxBody(maximumFields))
        assertEquals(8, maximum.fields.size)
        assertTrue(maximum.canonicalFields().length <= 135)
    }

    @Test fun metadataOwnsImmutableFieldsAndDoesNotRetainSecretMarkerOrScalarValues() {
        val marker = "SECRET_MARKER_STS_VIN_ROUTE_UUID".toByteArray(Charsets.US_ASCII)
        val body = byteArrayOf(0x0a, marker.size.toByte()) + marker + byteArrayOf(0x10, 0x01)
        val observed = requireNotNull(summarizeTeslaBleRxBody(body))
        val alternate = byteArrayOf(0x0a, marker.size.toByte()) + ByteArray(marker.size) { 0x7f } +
            byteArrayOf(0x10, 0x7f)
        assertEquals(observed, summarizeTeslaBleRxBody(alternate))
        assertFalse(observed.toString().contains("SECRET_MARKER"))
        assertEquals("1/2/${marker.size},2/0/1", observed.canonicalFields())
        body.fill(0)
        marker.fill(0)
        assertEquals(listOf(TeslaBleRxBodyField(1, 2, marker.size), TeslaBleRxBodyField(2, 0, 1)), observed.fields)
        assertThrows(UnsupportedOperationException::class.java) {
            (observed.fields as MutableList<TeslaBleRxBodyField>).clear()
        }
        assertEquals(2, observed.fields.size)
    }

    private fun assertMalformed(tail: ByteArray) {
        val wire = byteArrayOf(0x10, 0x01) + tail
        val observed = requireNotNull(summarizeTeslaBleRxBody(wire))
        assertEquals(TeslaBleRxBodyStatus.MALFORMED, observed.status)
        assertEquals(listOf(TeslaBleRxBodyField(2, 0, 1)), observed.fields)
        assertEquals(wire.size, observed.byteCount)
    }
}
