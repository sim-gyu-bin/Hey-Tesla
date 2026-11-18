package com.heytesla.app.speech.product

import org.junit.Assert.*
import org.junit.Test

class ProductUtteranceBufferTest {
    private fun frame(sample: Int, bytes: Int = ProductUtteranceBuffer.CHUNK_BYTES): ByteArray =
        ByteArray(bytes).also {
            for (index in it.indices step 2) {
                it[index] = sample.toByte()
                it[index + 1] = (sample shr 8).toByte()
            }
        }

    private fun confirmOnset(buffer: ProductUtteranceBuffer, start: Long = 0) {
        val voice = frame(3_000)
        repeat(6) { index ->
            assertEquals(
                if (index < 5) ProductUtteranceBuffer.Status.WAITING else ProductUtteranceBuffer.Status.CAPTURING,
                buffer.append(voice, voice.size, start + (index + 1) * 20L),
            )
        }
    }

    @Test fun longIdleAndUnconfirmedOnsetDoNotAllocateOrExpire() {
        val buffer = ProductUtteranceBuffer()
        val silent = frame(0)
        repeat(2_000) { index ->
            assertEquals(ProductUtteranceBuffer.Status.WAITING,
                buffer.append(silent, silent.size, (index + 1) * 20L))
            assertNull(buffer.pcm)
            assertNull(buffer.startedAt)
            assertEquals(0, buffer.bytes)
        }
        val later = 8 * 60 * 60 * 1_000L
        assertEquals(ProductUtteranceBuffer.Status.WAITING, buffer.checkTime(later))
        val voice = frame(3_000)
        repeat(5) { index ->
            assertEquals(ProductUtteranceBuffer.Status.WAITING,
                buffer.append(voice, voice.size, later + (index + 1) * 20))
            assertNull(buffer.pcm)
            assertNull(buffer.startedAt)
            assertEquals(0, buffer.bytes)
            assertEquals(0, buffer.voicedBytes)
        }
        assertEquals(ProductUtteranceBuffer.Status.CAPTURING, buffer.append(voice, voice.size, later + 120))
        assertEquals(later, buffer.startedAt)
        assertEquals(6_400, buffer.bytes) // 최근 200ms에는 확인 전 후보 120ms도 포함된다.
        assertEquals(3_840, buffer.voicedBytes)
        assertEquals(ProductUtteranceBuffer.MAX_BYTES, requireNotNull(buffer.pcm).size)
        buffer.clear()
    }

    @Test fun repeatedTwentyMillisecondPulsesAndSilenceNeverOpenAnUtterance() {
        val buffer = ProductUtteranceBuffer()
        val voice = frame(3_000)
        val silent = frame(0)
        repeat(100) { index ->
            assertEquals(ProductUtteranceBuffer.Status.WAITING,
                buffer.append(voice, voice.size, index * 40L + 20))
            assertEquals(ProductUtteranceBuffer.Status.WAITING,
                buffer.append(silent, silent.size, index * 40L + 40))
            assertNull(buffer.pcm)
            assertNull(buffer.startedAt)
            assertEquals(0, buffer.bytes)
            assertEquals(0, buffer.voicedBytes)
            assertEquals(index + 1, buffer.suppressedOnsets)
        }
        repeat(50) { index ->
            assertEquals(ProductUtteranceBuffer.Status.WAITING,
                buffer.append(silent, silent.size, 4_020L + index * 20))
        }
        assertEquals(100, buffer.suppressedOnsets)
        assertEquals(0, buffer.silenceBytesAtEnd)
        buffer.clear()
    }

    @Test fun onsetRequiresAllSamplesThroughTheExactOneHundredTwentyMillisecondBoundary() {
        val buffer = ProductUtteranceBuffer()
        val voice = frame(3_000)
        repeat(5) { index -> buffer.append(voice, voice.size, (index + 1) * 20L) }
        assertEquals(ProductUtteranceBuffer.Status.WAITING, buffer.append(voice, 608, 119))
        assertNull(buffer.pcm) // 119ms = 3808bytes
        assertEquals(ProductUtteranceBuffer.Status.WAITING, buffer.append(voice, 30, 120))
        assertNull(buffer.pcm) // 마지막 PCM16 표본 하나가 아직 부족하다.
        assertEquals(ProductUtteranceBuffer.Status.CAPTURING, buffer.append(voice, 2, 120))
        assertEquals(0L, buffer.startedAt)
        assertEquals(3_840, buffer.bytes)
        assertEquals(3_840, buffer.voicedBytes)
        buffer.clear()
    }

    @Test fun oneActualUnvoicedSampleResetsContinuityAndCountsOneSuppressedOnset() {
        val buffer = ProductUtteranceBuffer()
        val voice = frame(3_000)
        val silent = frame(0)
        repeat(5) { index -> buffer.append(voice, voice.size, (index + 1) * 20L) }
        assertEquals(ProductUtteranceBuffer.Status.WAITING, buffer.append(silent, 2, 101))
        assertEquals(1, buffer.suppressedOnsets)
        repeat(5) { index ->
            assertEquals(ProductUtteranceBuffer.Status.WAITING,
                buffer.append(voice, voice.size, 121L + index * 20))
            assertNull(buffer.pcm)
        }
        assertEquals(ProductUtteranceBuffer.Status.CAPTURING, buffer.append(voice, voice.size, 221))
        assertEquals(101L, buffer.startedAt)
        assertEquals(3_840, buffer.voicedBytes) // 폐기된 후보가 pre-roll에 남아도 확정 유성량에는 넣지 않는다.
        assertEquals(1, buffer.suppressedOnsets)
        buffer.clear()
    }

    @Test fun emptyReadsNeitherConfirmNorSuppressAnOnset() {
        val buffer = ProductUtteranceBuffer()
        val voice = frame(3_000)
        repeat(5) { index -> buffer.append(voice, voice.size, (index + 1) * 20L) }
        assertEquals(ProductUtteranceBuffer.Status.WAITING, buffer.append(frame(0), 0, 1_000))
        assertNull(buffer.pcm)
        assertEquals(0, buffer.suppressedOnsets)
        assertEquals(ProductUtteranceBuffer.Status.CAPTURING, buffer.append(voice, voice.size, 1_020))
        assertEquals(0L, buffer.startedAt)
        assertEquals(3_840, buffer.voicedBytes)
        assertEquals(0, buffer.silenceBytesAtEnd)
        buffer.clear()
    }

    @Test fun variableChunkSizesUseReceivedBytesRatherThanReadCountOrWallTime() {
        val buffer = ProductUtteranceBuffer()
        val voice = frame(3_000)
        val counts = listOf(32, 128, 640, 320, 512, 608, 640, 640, 318)
        counts.forEachIndexed { index, count ->
            assertEquals(ProductUtteranceBuffer.Status.WAITING, buffer.append(voice, count, 1_001L + index))
            assertNull(buffer.pcm)
        }
        assertEquals(ProductUtteranceBuffer.Status.CAPTURING, buffer.append(voice, 2, 1_010))
        assertEquals(1_000L, buffer.startedAt)
        assertEquals(3_840, buffer.bytes)
        assertEquals(3_840, buffer.voicedBytes)
        assertArrayEquals(frame(3_000, 3_840), requireNotNull(buffer.pcm).copyOf(buffer.bytes))
        buffer.clear()
    }

    @Test fun rmsThresholdAndPartialReadsIgnoreUnreceivedBackingBytes() {
        val buffer = ProductUtteranceBuffer()
        val belowThreshold = frame(999)
        repeat(6) { index ->
            assertEquals(ProductUtteranceBuffer.Status.WAITING,
                buffer.append(belowThreshold, belowThreshold.size, (index + 1) * 20L))
        }
        val staleVoice = frame(3_000).also { it[0] = 0; it[1] = 0 }
        repeat(6) { index ->
            assertEquals(ProductUtteranceBuffer.Status.WAITING, buffer.append(staleVoice, 2, 121L + index))
        }
        assertNull(buffer.pcm)
        val threshold = frame(1_000)
        repeat(6) { index -> buffer.append(threshold, threshold.size, 146L + index * 20) }
        assertEquals(ProductUtteranceBuffer.Status.CAPTURING, buffer.status)
        assertEquals(3_840, buffer.voicedBytes)
        assertEquals(0, buffer.suppressedOnsets)
        buffer.clear()
    }

    @Test fun preRollPreservesFirstCandidateSamplesExactlyOnceAndInOrder() {
        val buffer = ProductUtteranceBuffer()
        for (sample in 1..15) {
            val idle = frame(sample)
            buffer.append(idle, idle.size, sample * 20L)
        }
        for (index in 1..6) {
            val voice = frame(3_000 + index)
            buffer.append(voice, voice.size, 300L + index * 20)
        }
        val expected = ((12..15).map { frame(it) } + (1..6).map { frame(3_000 + it) })
            .fold(ByteArray(0)) { accumulated, next -> accumulated + next }
        val pcm = requireNotNull(buffer.pcm)
        assertEquals(expected.size, buffer.bytes)
        assertArrayEquals(expected, pcm.copyOf(buffer.bytes))
        assertEquals(300L, buffer.startedAt)
        assertEquals(3_840, buffer.voicedBytes)
        buffer.clear()
        assertTrue(pcm.all { it == 0.toByte() })
    }

    @Test fun schedulingGapAndEmptyReadCannotPretendToBeEndpointSilence() {
        val buffer = ProductUtteranceBuffer()
        confirmOnset(buffer)
        val silent = frame(0)
        assertEquals(ProductUtteranceBuffer.Status.CAPTURING, buffer.append(silent, 0, 2_000))
        assertEquals(0, buffer.silenceBytesAtEnd)
        repeat(49) { index ->
            assertEquals(ProductUtteranceBuffer.Status.CAPTURING,
                buffer.append(silent, silent.size, 2_020L + index * 20))
        }
        assertEquals(31_360, buffer.silenceBytesAtEnd)
        assertEquals(ProductUtteranceBuffer.Status.COMPLETE, buffer.append(silent, silent.size, 3_000))
        assertEquals(32_000, buffer.silenceBytesAtEnd)
        assertEquals(3_840, buffer.voicedBytes)
        buffer.clear()
    }

    @Test fun renewedVoiceResetsEndpointAndAccumulatesOnlyActualVoicedSamples() {
        val buffer = ProductUtteranceBuffer()
        confirmOnset(buffer)
        val silent = frame(0)
        repeat(49) { index -> buffer.append(silent, silent.size, 140L + index * 20) }
        val voice = frame(3_000)
        assertEquals(ProductUtteranceBuffer.Status.CAPTURING, buffer.append(voice, 320, 1_110))
        assertEquals(0, buffer.silenceBytesAtEnd)
        assertEquals(4_160, buffer.voicedBytes)
        repeat(49) { index ->
            assertEquals(ProductUtteranceBuffer.Status.CAPTURING,
                buffer.append(silent, silent.size, 1_130L + index * 20))
        }
        assertEquals(ProductUtteranceBuffer.Status.CAPTURING, buffer.append(silent, 638, 2_110))
        assertEquals(31_998, buffer.silenceBytesAtEnd)
        assertEquals(ProductUtteranceBuffer.Status.COMPLETE, buffer.append(silent, 2, 2_110))
        val bytesAtEnd = buffer.bytes
        assertEquals(ProductUtteranceBuffer.Status.COMPLETE, buffer.append(voice, voice.size, 2_130))
        assertEquals(bytesAtEnd, buffer.bytes)
        assertEquals(4_160, buffer.voicedBytes)
        assertEquals(32_000, buffer.silenceBytesAtEnd)
        buffer.clear()
    }

    @Test fun tenSecondDeadlineBelongsToFirstCandidateNotOnsetConfirmation() {
        val buffer = ProductUtteranceBuffer()
        confirmOnset(buffer, start = 5_000)
        assertEquals(5_000L, buffer.startedAt)
        assertEquals(ProductUtteranceBuffer.Status.CAPTURING, buffer.checkTime(14_999))
        assertEquals(ProductUtteranceBuffer.Status.TRUNCATED, buffer.checkTime(15_000))
        val silent = frame(0)
        repeat(50) { index ->
            assertEquals(ProductUtteranceBuffer.Status.TRUNCATED,
                buffer.append(silent, silent.size, 15_020L + index * 20))
        }
        assertEquals(0, buffer.silenceBytesAtEnd)
        buffer.clear()
        assertNull(buffer.pcm)
    }

    @Test fun delayedConfirmationCannotReissueExpiredCandidateDeadline() {
        val buffer = ProductUtteranceBuffer()
        val voice = frame(3_000)
        repeat(5) { index -> buffer.append(voice, voice.size, (index + 1) * 20L) }
        assertEquals(ProductUtteranceBuffer.Status.WAITING, buffer.append(voice, 0, 10_000))
        assertEquals(ProductUtteranceBuffer.Status.TRUNCATED, buffer.append(voice, voice.size, 10_020))
        assertEquals(0L, buffer.startedAt)
        assertNull(buffer.pcm)
        buffer.clear()
    }

    @Test fun sampleLimitTruncatesEvenWhenClockAdvancesTooSlowly() {
        val buffer = ProductUtteranceBuffer()
        val voice = frame(3_000)
        repeat(499) { index ->
            assertEquals(
                if (index < 5) ProductUtteranceBuffer.Status.WAITING else ProductUtteranceBuffer.Status.CAPTURING,
                buffer.append(voice, voice.size, 20L + index),
            )
        }
        assertEquals(ProductUtteranceBuffer.Status.TRUNCATED, buffer.append(voice, voice.size, 519))
        assertTrue(buffer.bytes < ProductUtteranceBuffer.MAX_BYTES)
        assertEquals(buffer.bytes, buffer.voicedBytes)
        buffer.clear()
    }

    @Test fun clockRollbackSealsIdleCandidateAndConfirmedUtterance() {
        repeat(3) { state ->
            val buffer = ProductUtteranceBuffer()
            when (state) {
                0 -> buffer.append(frame(0), ProductUtteranceBuffer.CHUNK_BYTES, 1_000)
                1 -> buffer.append(frame(3_000), ProductUtteranceBuffer.CHUNK_BYTES, 1_000)
                else -> confirmOnset(buffer, start = 880)
            }
            assertEquals(ProductUtteranceBuffer.Status.CLOCK_REVERSED, buffer.checkTime(999))
            assertEquals(ProductUtteranceBuffer.Status.CLOCK_REVERSED,
                buffer.append(frame(3_000), ProductUtteranceBuffer.CHUNK_BYTES, 1_020))
            buffer.clear()
            assertNull(buffer.pcm)
        }
        val negativeTime = ProductUtteranceBuffer()
        assertEquals(ProductUtteranceBuffer.Status.CLOCK_REVERSED, negativeTime.checkTime(-1))
        negativeTime.clear()
    }

    @Test fun clearErasesPcmAndCountersAndLateFramesCannotReopenAnyStage() {
        repeat(3) { state ->
            val buffer = ProductUtteranceBuffer()
            val voice = frame(3_000)
            val silent = frame(0)
            buffer.append(voice, voice.size, 20)
            buffer.append(silent, silent.size, 40)
            assertEquals(1, buffer.suppressedOnsets)
            when (state) {
                1 -> buffer.append(voice, voice.size, 60)
                2 -> {
                    confirmOnset(buffer, start = 40)
                    buffer.append(silent, silent.size, 180)
                }
            }
            val pcm = buffer.pcm
            if (state == 2) assertTrue(requireNotNull(pcm).any { it != 0.toByte() })
            buffer.clear()
            if (pcm != null) assertTrue(pcm.all { it == 0.toByte() })
            assertNull(buffer.pcm)
            assertEquals(0, buffer.bytes)
            assertEquals(0, buffer.voicedBytes)
            assertEquals(0, buffer.suppressedOnsets)
            assertEquals(0, buffer.silenceBytesAtEnd)
            assertEquals(ProductUtteranceBuffer.Status.TRUNCATED, buffer.append(voice, voice.size, 200))
            assertNull(buffer.pcm)
            assertEquals(0, buffer.bytes)
            assertEquals(0, buffer.voicedBytes)
            assertEquals(0, buffer.suppressedOnsets)
        }
    }
}
