package com.heytesla.app

import java.io.InputStream

/** 고정 PCM16/mono/16 kHz fixture만 허용한다. 출력 버퍼의 수명과 소거는 호출자 소유다. */
internal object SpeechPcmFixture {
    private const val HEADER_BUDGET = 8_192

    fun read(input: InputStream, pcm: ByteArray, canceled: () -> Boolean): Int {
        val header = ByteArray(4_096)
        fun readFully(target: ByteArray, offset: Int, size: Int) {
            var position = offset
            val end = offset + size
            while (position < end) {
                check(!canceled()) { "FIXTURE_CANCELED" }
                val count = input.read(target, position, minOf(4_096, end - position))
                require(count > 0) { "TRUNCATED_WAV" }
                position += count
            }
        }
        fun uint(offset: Int, bytes: Int): Long {
            var value = 0L
            for (index in 0 until bytes) {
                value = value or ((header[offset + index].toLong() and 255) shl (index * 8))
            }
            return value
        }
        fun tag(value: String) = value.indices.all { header[it] == value[it].code.toByte() }
        readFully(header, 0, 12)
        require(tag("RIFF") && (0..3).all { header[it + 8] == "WAVE"[it].code.toByte() }) { "INVALID_WAV" }
        val total = uint(4, 4) + 8
        require(total in 12L..(pcm.size.toLong() + HEADER_BUDGET)) { "WAV_LIMIT" }
        var remaining = total - 12
        var overhead = 12L
        var formatSeen = false
        var dataSize = 0
        while (remaining > 0) {
            require(remaining >= 8) { "TRUNCATED_CHUNK" }
            readFully(header, 0, 8)
            remaining -= 8
            overhead += 8
            val size = uint(4, 4)
            val padded = size + (size and 1)
            require(padded <= remaining) { "CHUNK_BOUNDARY" }
            val isFormat = tag("fmt ")
            val isData = tag("data")
            if (!isData) overhead += padded
            require(overhead <= HEADER_BUDGET) { "WAV_HEADER_LIMIT" }
            when {
                isFormat -> {
                    require(!formatSeen && dataSize == 0 && size == 16L) { "INVALID_FMT" }
                    readFully(header, 0, 16)
                    require(uint(0, 2) == 1L && uint(2, 2) == 1L && uint(4, 4) == 16_000L &&
                        uint(8, 4) == 32_000L && uint(12, 2) == 2L && uint(14, 2) == 16L) { "UNSUPPORTED_WAV" }
                    formatSeen = true
                }
                isData -> {
                    require(formatSeen && dataSize == 0 && size > 0 && size <= pcm.size && size % 2 == 0L) { "INVALID_PCM" }
                    readFully(pcm, 0, size.toInt())
                    dataSize = size.toInt()
                }
                else -> {
                    var unread = size
                    while (unread > 0) {
                        val count = minOf(unread, header.size.toLong()).toInt()
                        readFully(header, 0, count)
                        unread -= count
                    }
                }
            }
            if (size and 1L != 0L) readFully(header, 0, 1)
            remaining -= padded
        }
        check(!canceled()) { "FIXTURE_CANCELED" }
        require(formatSeen && dataSize > 0 && input.read() == -1) { "INVALID_WAV_END" }
        return dataSize
    }
}
