package com.heytesla.app.speech

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechRecordingStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private var now = 1_000L

    private fun store(): SpeechRecordingStore =
        SpeechRecordingStore(temporary.root) { now }

    @Test
    fun savesCanonicalWaveAndLoadsTheSamePcm() {
        val pcm = ByteArray(6) { (it + 1).toByte() }
        val info = store().save(pcm, pcm.size)

        assertEquals(6, info.pcmBytes)
        assertEquals(now, info.createdAtMs)
        assertEquals("DIRECT_MIC", info.source)
        assertArrayEquals(pcm, store().load(info.id).pcm)

        val wav = File(File(temporary.root, info.id), "audio.wav").readBytes()
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size, header.getInt(40))
    }

    @Test
    fun marksSyntheticFixturesAsSuchAndRejectsUnknownSources() {
        val store = store()
        val info = store.save(byteArrayOf(1, 2, 3, 4), 4, "SYNTHETIC_FIXTURE")
        assertEquals("SYNTHETIC_FIXTURE", info.source)
        val restored = store.load(info.id)
        assertEquals("SYNTHETIC_FIXTURE", restored.info.source)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), restored.pcm)
        assertEquals("SYNTHETIC_FIXTURE", store.list().single().source)
        val capture = File(File(temporary.root, info.id), "capture.json").readText()
        assertTrue(capture.contains("\"source\":\"SYNTHETIC_FIXTURE\""))
        assertFalse(capture.contains("\"source\":\"DIRECT_MIC\""))

        val direct = store.save(byteArrayOf(5, 6), 2)
        assertEquals("DIRECT_MIC", direct.source)
        assertEquals("DIRECT_MIC", store.load(direct.id).info.source)

        for (source in listOf("", "MIC", "DIRECT_MIC ", "TTS", "SYNTHETIC_FIXTURE ")) {
            try {
                store.save(byteArrayOf(1, 2), 2, source)
                fail("허용되지 않은 출처는 저장하면 안 됩니다")
            } catch (error: IllegalArgumentException) {
                assertEquals("RECORDING_SOURCE_INVALID", error.message)
            }
        }

        val directCapture = File(File(temporary.root, direct.id), "capture.json")
        directCapture.writeText(directCapture.readText().replace("\"DIRECT_MIC\"", "\"MIC\""))
        assertFalse(store.list().any { it.id == direct.id })
        try {
            store.load(direct.id)
            fail("알 수 없는 출처가 기록된 녹음은 읽으면 안 됩니다")
        } catch (_: IOException) {
        }
    }

    @Test
    fun rejectsPcmOutsideSampleBoundsWithoutWritingAnything() {
        val store = store()
        val cases = listOf(
            ByteArray(SpeechMicCapture.MAX_BYTES + 2) to SpeechMicCapture.MAX_BYTES + 2,
            ByteArray(4) to 5,
            ByteArray(4) to 0,
            ByteArray(4) to 3,
        )
        for ((pcm, size) in cases) {
            try {
                store.save(pcm, size)
                fail("상한을 벗어난 PCM은 저장하면 안 됩니다")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(store.list().isEmpty())
        assertEquals(0, temporary.root.listFiles()!!.size)
    }

    @Test
    fun rejectsCorruptedOrTruncatedWave() {
        val store = store()
        val pcm = ByteArray(64) { (it * 3).toByte() }
        val info = store.save(pcm, pcm.size)
        val wav = File(File(temporary.root, info.id), "audio.wav")
        val original = wav.readBytes()

        val wrongSampleRate = original.copyOf()
        wrongSampleRate[24] = (wrongSampleRate[24].toInt() xor 1).toByte()
        assertRejected(store, info.id, wrongSampleRate)

        assertRejected(store, info.id, original.copyOf(original.size - 2))
        assertRejected(store, info.id, original + byteArrayOf(0))
    }

    private fun assertRejected(store: SpeechRecordingStore, id: String, bytes: ByteArray) {
        val wav = File(File(temporary.root, id), "audio.wav")
        wav.writeBytes(bytes)
        try {
            store.load(id)
            fail("손상된 WAV를 소비하면 안 됩니다")
        } catch (error: IOException) {
            assertEquals("RECORDING_AUDIO_INVALID", error.message)
            assertFalse(error.message!!.contains(temporary.root.absolutePath))
        }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun rejectsIdsThatAreNotCanonicalRecordingUuids() {
        val store = store()
        val info = store.save(byteArrayOf(1, 2, 3, 4), 4)
        val ids = listOf(
            "",
            ".",
            "..",
            "../outside",
            info.id + "/../outside",
            info.id.uppercase(),
            "1-1-1-1-1",
            UUID.randomUUID().toString().dropLast(1) + "g",
        )
        for (id in ids) {
            try {
                store.load(id)
                fail("저장소 밖을 가리키는 id는 거절해야 합니다")
            } catch (error: IOException) {
                assertEquals("RECORDING_ID_INVALID", error.message)
            }
            try {
                store.saveResult(id, "텍스트", null, 10)
                fail("저장소 밖을 가리키는 id는 결과도 거절해야 합니다")
            } catch (error: IOException) {
                assertEquals("RECORDING_ID_INVALID", error.message)
            }
        }
        assertTrue(store.load(info.id).pcm.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun rejectsSymlinkedRecordingDirectory() {
        val outside = Files.createTempDirectory("speech-recording-outside").toFile()
        try {
            val store = store()
            val info = store.save(byteArrayOf(1, 2, 3, 4), 4)
            val directory = File(temporary.root, info.id)
            directory.copyRecursively(outside, overwrite = true)
            directory.deleteRecursively()
            try {
                Files.createSymbolicLink(directory.toPath(), outside.toPath())
            } catch (_: UnsupportedOperationException) {
                assumeTrue("심볼릭 링크를 만들 수 없는 환경입니다", false)
            } catch (_: IOException) {
                assumeTrue("심볼릭 링크를 만들 수 없는 환경입니다", false)
            }
            try {
                store.load(info.id)
                fail("심볼릭 링크 너머의 녹음을 읽으면 안 됩니다")
            } catch (_: IOException) {
            }
            assertTrue(store.list().isEmpty())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun keepsRecognizedTextOnlyInTheRunFile() {
        val store = store()
        val info = store.save(byteArrayOf(1, 2, 3, 4), 4)
        val directory = File(temporary.root, info.id)
        val capture = File(directory, "capture.json")
        val captureBefore = capture.readBytes()
        val text = "프렁크 열어 \"지금\"\n둘째 줄"

        val runId = store.saveResult(info.id, text, null, 1_250L)

        assertEquals(36, runId.length)
        assertArrayEquals(captureBefore, capture.readBytes())
        assertFalse(capture.readText().contains("프렁크"))
        val run = File(File(directory, "runs"), runId + ".json").readText()
        assertTrue(
            run.contains("\"text\":\"프렁크 열어 \\\"지금\\\"\\n둘째 줄\""),
        )
        assertTrue(run.contains("\"recordingId\":\"${info.id}\""))
        assertTrue(run.contains("\"confidence\":null"))
        assertTrue(run.contains("\"source\":\"STORED_PCM\""))
        assertTrue(run.contains("\"failure\":null"))
        assertTrue(run.contains("\"elapsedMs\":1250"))

        val failureRun = store.saveResult(info.id, null, "RECOGNITION_FAILED", 90L)
        val failed = File(File(directory, "runs"), failureRun + ".json").readText()
        assertTrue(failed.contains("\"text\":null"))
        assertTrue(failed.contains("\"failure\":\"RECOGNITION_FAILED\""))
        assertFalse(failed.contains("프렁크"))
        assertArrayEquals(captureBefore, capture.readBytes())
        assertEquals(2, File(directory, "runs").listFiles()!!.size)
    }

    @Test
    fun listsOnlyCompletedRecordingsNewestFirst() {
        val store = store()
        now = 1_000L
        val older = store.save(byteArrayOf(1, 2), 2)
        now = 2_000L
        val newer = store.save(byteArrayOf(3, 4), 2)
        now = 1_500L
        val middle = store.save(byteArrayOf(5, 6), 2)

        File(temporary.root, UUID.randomUUID().toString()).mkdir()

        assertEquals(listOf(newer.id, middle.id, older.id), store.list().map { it.id })

        File(File(temporary.root, newer.id), "capture.json").delete()
        assertEquals(listOf(middle.id, older.id), store.list().map { it.id })
        try {
            store.load(newer.id)
            fail("완료 메타가 없는 녹음은 읽으면 안 됩니다")
        } catch (_: IOException) {
        }
    }
}
