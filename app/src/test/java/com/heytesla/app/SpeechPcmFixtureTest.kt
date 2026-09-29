package com.heytesla.app

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class SpeechPcmFixtureTest {
    @Test fun extractsOnlyPcmAcrossFillerAndOddPaddedChunks() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val wav = wave(chunk("fmt ", format()), chunk("FLLR", ByteArray(4_044)),
            chunk("JUNK", byteArrayOf(99)), chunk("data", expected))
        val pcm = ByteArray(320_000)
        val size = SpeechPcmFixture.read(wav.inputStream(), pcm) { false }
        assertEquals(expected.size, size)
        assertArrayEquals(expected, pcm.copyOf(size))
    }

    @Test fun rejectsEveryIncompatibleFormatField() {
        // encoding, channels, rate, byteRate, blockAlign, bitsPerSample
        for (offset in intArrayOf(0, 2, 4, 8, 12, 14)) {
            val format = format()
            format[offset] = (format[offset].toInt() xor 1).toByte()
            rejects(wave(chunk("fmt ", format), chunk("data", byteArrayOf(1, 2))))
        }
    }

    @Test fun rejectsTruncationLengthMismatchAndInvalidPcm() {
        val valid = wave(chunk("fmt ", format()), chunk("data", byteArrayOf(1, 2)))
        rejects(valid.copyOf(valid.size - 1))
        rejects(valid + byteArrayOf(0))
        val wrongChunkLength = valid.copyOf()
        ByteBuffer.wrap(wrongChunkLength).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 4)
        rejects(wrongChunkLength)
        for (size in intArrayOf(0, 1, 320_002)) {
            rejects(wave(chunk("fmt ", format()), chunk("data", ByteArray(size))))
        }
        rejects(wave(chunk("data", byteArrayOf(1, 2)), chunk("fmt ", format())))
        rejects(wave(chunk("fmt ", format()), chunk("FLLR", ByteArray(8_192)),
            chunk("data", byteArrayOf(1, 2))))
    }

    @Test fun cancellationInterruptsLoadingBeforeWholePcmIsRead() {
        val wav = wave(chunk("fmt ", format()), chunk("data", ByteArray(20_000)))
        val input = ByteArrayInputStream(wav)
        try {
            SpeechPcmFixture.read(input, ByteArray(320_000)) { wav.size - input.available() >= 4_096 }
            fail("취소된 PCM 로드는 성공하면 안 됩니다")
        } catch (_: IllegalStateException) {
            assertTrue(input.available() > 0)
        }
    }

    private fun rejects(wav: ByteArray) {
        try {
            SpeechPcmFixture.read(wav.inputStream(), ByteArray(320_000)) { false }
            fail("잘못된 WAV를 인식기에 전달하면 안 됩니다")
        } catch (_: IllegalArgumentException) { }
    }

    private fun format(): ByteArray = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16).array()

    private fun chunk(tag: String, payload: ByteArray): ByteArray =
        tag.toByteArray(Charsets.US_ASCII) + littleEndian(payload.size) + payload + ByteArray(payload.size % 2)

    private fun wave(vararg chunks: ByteArray): ByteArray {
        val body = "WAVE".toByteArray(Charsets.US_ASCII) + chunks.fold(ByteArray(0)) { all, chunk -> all + chunk }
        return "RIFF".toByteArray(Charsets.US_ASCII) + littleEndian(body.size) + body
    }

    private fun littleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
