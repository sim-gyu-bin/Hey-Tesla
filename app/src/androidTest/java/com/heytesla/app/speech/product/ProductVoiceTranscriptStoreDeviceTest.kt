package com.heytesla.app.speech.product

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 Android JsonReader/파일 IO만 실행한다. 마이크·차량·설정은 건드리지 않으며 합성 파일도 보존한다. */
@RunWith(AndroidJUnit4::class)
class ProductVoiceTranscriptStoreDeviceTest {
    private fun directory(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
        "product-transcript-device-${UUID.randomUUID()}",
    ).canonicalFile.apply { assertTrue(mkdir()) }

    private fun record(utterance: Long, text: String?) = ProductVoiceTranscriptRecord(
        processId = "synthetic-process", trialId = null, voiceLeaseId = 7, utterance = utterance,
        wallTimeMs = 1_000, elapsedRealtimeMs = 200, appVersion = "device-test", text = text,
        diagnostic = ProductVoiceDiagnostic(
            outcome = "GRAMMAR_NO_MATCH", utteranceBytes = 640, voicedBytes = 320,
            suppressedOnsets = 2, silenceBytesAtEnd = 160, captureMs = 20, loadMs = 3,
            decodeMs = 4, cleanupMs = 5, modelReused = true, microphoneReleased = true,
            streamCleanupConfirmed = true, recognizerCleanupConfirmed = false,
        ),
        reason = "FRUNK_VOICE_GRAMMAR_NO_MATCH",
    )

    // v85의 실제 schema1 필드/nullable 수치 형식. 새 writer 결과를 schema1이라고 추정하지 않는다.
    private fun legacyJson(utterance: Long = 1, textJson: String = "\"합성\\n\\\"끝😀\""): String =
        "{\"schemaVersion\":1,\"processId\":\"synthetic-process\",\"trialId\":null," +
            "\"voiceLeaseId\":7,\"utterance\":$utterance,\"wallTimeMs\":1000,\"elapsedRealtimeMs\":200," +
            "\"appVersion\":\"device-test\",\"text\":$textJson," +
            "\"diagnostic\":{\"outcome\":\"GRAMMAR_NO_MATCH\",\"utteranceBytes\":640,\"voicedBytes\":320," +
            "\"suppressedOnsets\":2,\"silenceBytesAtEnd\":160,\"captureMs\":20,\"loadMs\":3," +
            "\"decodeMs\":4,\"cleanupMs\":5,\"modelReused\":true,\"microphoneReleased\":true," +
            "\"streamCleanupConfirmed\":true,\"recognizerCleanupConfirmed\":false}}"

    private fun schema2Json(): String = legacyJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        .removeSuffix("}") + ",\"reason\":null}"

    private fun completed(root: File, json: String, modifiedMs: Long = 1_000): File =
        newFile(root, "${UUID.randomUUID()}.json", json.toByteArray(Charsets.UTF_8)).apply {
            assertTrue(setLastModified(modifiedMs))
            // fixture 순서 전제를 NIO가 아닌 실제 native timespec으로 먼저 확인한다.
            val nativeTime = Os.lstat(path).st_mtim
            assertEquals(modifiedMs / 1_000, nativeTime.tv_sec)
            assertEquals((modifiedMs % 1_000) * 1_000_000, nativeTime.tv_nsec)
        }

    private fun newFile(root: File, name: String, bytes: ByteArray): File = File(root, name).apply {
        Files.write(toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    private fun snapshot(root: File): Map<String, ByteArray> = root.listFiles()!!.associate { it.name to it.readBytes() }

    private fun assertUnchanged(root: File, before: Map<String, ByteArray>) {
        assertEquals(before.keys, root.listFiles()!!.map { it.name }.toSet())
        for ((name, bytes) in before) assertArrayEquals(bytes, File(root, name).readBytes())
    }

    @Test
    fun restoresActualLegacyAndNewCompletedFilesAfterStoreRecreationWithoutReusingTextForFailures() {
        val root = directory()
        completed(root, legacyJson())
        val writerStore = ProductVoiceTranscriptStore(root)
        val decoded = record(2, "합성\n\r\t\u0000\"\\끝😀")
        val empty = record(3, "").copy(diagnostic = record(3, "").diagnostic.copy(outcome = "EMPTY"),
            reason = "FRUNK_VOICE_EMPTY")
        val failure = record(4, null).copy(
            diagnostic = ProductVoiceDiagnostic("FAILURE", utteranceBytes = 320_000,
                microphoneReleased = false, streamCleanupConfirmed = false),
            reason = "PRODUCT_VOICE_UTTERANCE_TIMEOUT",
        )
        val sealed = failure.copy(utterance = 5, diagnostic = failure.diagnostic.copy(outcome = "SEALED"),
            reason = "PRODUCT_VOICE_CANCELLED")
        for (candidate in listOf(decoded, empty, failure, sealed)) {
            assertEquals(ProductVoiceTranscriptWriteResult.SAVED, writerStore.append(candidate))
        }
        val before = snapshot(root)
        val restored = ProductVoiceTranscriptStore(root).readRecent()
        assertNull(restored.failure)
        assertEquals(5, restored.records.size)
        val byUtterance = restored.records.associateBy { it.utterance }
        assertEquals(record(1, "합성\n\"끝😀").copy(reason = null), byUtterance[1L])
        assertEquals(decoded, byUtterance[2L])
        assertEquals(empty, byUtterance[3L])
        assertEquals(failure, byUtterance[4L])
        assertEquals(sealed, byUtterance[5L])
        assertNull(byUtterance[4L]!!.text)
        assertNull(byUtterance[5L]!!.text)
        assertEquals("ProductVoiceTranscriptReadResult(REDACTED)", restored.toString())
        assertEquals("ProductVoiceTranscriptRecord(REDACTED)", byUtterance[2L].toString())
        assertUnchanged(root, before)
    }

    @Test
    fun readsOnlyLatestFiveCompletedUuidFilesAndClampsLimitWithoutTouchingOtherFiles() {
        val root = directory()
        for (utterance in 1L..7L) completed(root, legacyJson(utterance), 1_000 + utterance)
        newFile(root, "${UUID.randomUUID()}.json.tmp", "incomplete synthetic JSON".toByteArray())
        newFile(root, "external.json", "not a completed UUID file".toByteArray())
        newFile(root, "external.bin", "preserved synthetic bytes".toByteArray())
        val before = snapshot(root)
        val store = ProductVoiceTranscriptStore(root)
        val recent = store.readRecent(Int.MAX_VALUE)
        assertNull(recent.failure)
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), recent.records.map { it.utterance })
        assertEquals(listOf(7L, 6L), store.readRecent(2).records.map { it.utterance })
        assertEquals(emptyList<ProductVoiceTranscriptRecord>(), store.readRecent(0).records)
        assertEquals(emptyList<ProductVoiceTranscriptRecord>(), store.readRecent(-1).records)
        assertUnchanged(root, before)
    }

    @Test
    fun reportsBadSelectedFileAndKeepsOtherSelectedResultsWithoutFillingFromOlderFiles() {
        val root = directory()
        for (utterance in 1L..7L) completed(root, legacyJson(utterance), 1_000 + utterance)
        completed(root, "{broken synthetic JSON", 1_008)
        val before = snapshot(root)
        val restored = ProductVoiceTranscriptStore(root).readRecent()
        assertEquals("INVALID_RECORD", restored.failure)
        assertEquals(listOf(7L, 6L, 5L, 4L), restored.records.map { it.utterance })
        assertUnchanged(root, before)
    }

    @Test
    fun ordersActualNativeSubMillisecondMtimesAndRetainsNanosecondComparisonBoundary() {
        // FileTime 자체의 나노초 비교와 Android NIO stat의 정보 손실을 구분한다.
        val oneNano = FileTime.from(1_000_000_001, TimeUnit.NANOSECONDS)
        val twoNanos = FileTime.from(1_000_000_002, TimeUnit.NANOSECONDS)
        assertEquals(oneNano.toMillis(), twoNanos.toMillis())
        assertTrue(oneNano.compareTo(twoNanos) < 0)
        assertTrue(twoNanos.compareTo(oneNano) > 0)

        val root = directory()
        val older = completed(root, legacyJson(1))
        val newer = completed(root, legacyJson(2))
        Files.setLastModifiedTime(older.toPath(), FileTime.from(1_000_100, TimeUnit.MICROSECONDS))
        Files.setLastModifiedTime(newer.toPath(), FileTime.from(1_000_900, TimeUnit.MICROSECONDS))
        val olderNative = Os.lstat(older.path).st_mtim
        val newerNative = Os.lstat(newer.path).st_mtim
        assertEquals(1L, olderNative.tv_sec)
        assertEquals(1L, newerNative.tv_sec)
        assertEquals(100_000L, olderNative.tv_nsec)
        assertEquals(900_000L, newerNative.tv_nsec)
        val before = snapshot(root)
        val result = ProductVoiceTranscriptStore(root).readRecent()
        assertNull(result.failure)
        assertEquals(listOf(2L, 1L), result.records.map { it.utterance })
        assertUnchanged(root, before)
    }

    @Test
    fun rejectsMalformedUnknownDuplicateWrongTypeAndUnsafeSummaryRecordsWithoutLeakingOrModifyingThem() {
        val legacy = legacyJson()
        val current = schema2Json()
        val invalid = listOf(
            "{broken synthetic JSON",
            legacy + "{}",
            legacy.replace("\"schemaVersion\":1", "\"schemaVersion\":3"),
            legacy.replace("\"trialId\":null,", ""),
            legacy.replace("\"processId\":", "\"external\":{\"nested\":[[[[]]]]},\"processId\":"),
            legacy.replace("\"voiceLeaseId\":7", "\"voiceLeaseId\":7,\"voiceLeaseId\":8"),
            legacy.replace("\"voiceLeaseId\":7", "\"voiceLeaseId\":\"7\""),
            legacy.replace("\"voiceLeaseId\":7", "\"voiceLeaseId\":9223372036854775808"),
            legacy.replace("\"voicedBytes\":320", "\"voicedBytes\":-2"),
            legacy.replace("\"utteranceBytes\":640", "\"utteranceBytes\":641"),
            legacy.replace("\"captureMs\":20", "\"captureMs\":86400001"),
            legacy.replace("\"microphoneReleased\":true", "\"microphoneReleased\":false"),
            legacy.replace("\"streamCleanupConfirmed\":true", "\"streamCleanupConfirmed\":\"true\""),
            legacy.replace("\"diagnostic\":{", "\"diagnostic\":{\"external\":null,"),
            legacyJson(textJson = "null"),
            legacyJson(textJson = "1"),
            legacyJson(textJson = "\"${"가".repeat(4_097)}\""),
            legacyJson(textJson = "\"\\ud800\""),
            current.replace(",\"reason\":null", ""),
            current.replace("\"reason\":null", "\"reason\":\"주변 합성 문장\""),
            current.replace("\"GRAMMAR_NO_MATCH\"", "\"FAILURE\""),
            schema2Json().replace("\"GRAMMAR_NO_MATCH\"", "\"PREPARED\""),
            schema2Json().replace("\"GRAMMAR_NO_MATCH\"", "\"CLOSED\""),
        )
        for (json in invalid) {
            val root = directory()
            completed(root, json)
            val before = snapshot(root)
            val result = ProductVoiceTranscriptStore(root).readRecent()
            assertTrue(result.records.isEmpty())
            assertEquals("INVALID_RECORD", result.failure)
            assertEquals("ProductVoiceTranscriptReadResult(REDACTED)", result.toString())
            assertUnchanged(root, before)
        }
        val root = directory()
        newFile(root, "${UUID.randomUUID()}.json", byteArrayOf(0xc3.toByte(), 0x28))
        val before = snapshot(root)
        assertEquals("INVALID_RECORD", ProductVoiceTranscriptStore(root).readRecent().failure)
        assertUnchanged(root, before)
    }

    @Test
    fun missingDirectoryReadDoesNotCreateItAndUnsafePathsOrEntriesFailClosed() {
        val parent = directory()
        val missing = File(parent, "missing")
        assertEquals(ProductVoiceTranscriptReadResult(emptyList()), ProductVoiceTranscriptStore(missing).readRecent())
        assertFalse(missing.exists())
        val outside = directory()
        val outsideFile = completed(outside, legacyJson())
        val outsideBytes = outsideFile.readBytes()
        val linkedRoot = File(parent, "linked-root")
        Files.createSymbolicLink(linkedRoot.toPath(), outside.toPath())
        val entryRoot = directory()
        Files.createSymbolicLink(File(entryRoot, "${UUID.randomUUID()}.json").toPath(), outsideFile.toPath())
        val nestedRoot = directory()
        assertTrue(File(nestedRoot, "preserved-directory").mkdir())
        val fileRoot = newFile(parent, "not-a-directory", "preserved synthetic bytes".toByteArray())
        val unsafe = listOf(
            linkedRoot, File(linkedRoot, "child"), entryRoot, nestedRoot, fileRoot,
            File(parent, "unused/../escaped"), File(parent, "invalid\u0000path"),
        )
        for (root in unsafe) {
            val result = ProductVoiceTranscriptStore(root).readRecent()
            assertTrue(result.records.isEmpty())
            assertEquals("READ_FAILED", result.failure)
        }
        assertTrue(Files.isSymbolicLink(linkedRoot.toPath()))
        assertArrayEquals(outsideBytes, outsideFile.readBytes())
        assertFalse(File(parent, "escaped").exists())
        assertEquals("preserved synthetic bytes", fileRoot.readText())
    }

    @Test
    fun boundsTotalBytesIncludingIgnoredFilesAndRefusesOverlargeFilesWithoutChangingThem() {
        val exactRoot = directory()
        val completedFile = completed(exactRoot, legacyJson())
        val ignored = newFile(exactRoot, "preserved.json.tmp", "합성 미완료".toByteArray(Charsets.UTF_8))
        val total = completedFile.length() + ignored.length()
        val before = snapshot(exactRoot)
        assertNull(ProductVoiceTranscriptStore(exactRoot, total).readRecent().failure)
        assertEquals("LIMIT_REACHED", ProductVoiceTranscriptStore(exactRoot, total - 1).readRecent().failure)
        assertUnchanged(exactRoot, before)

        val root = directory()
        val overlarge = newFile(root, "${UUID.randomUUID()}.json", ByteArray(1_048_577) { 17 })
        val overlargeBytes = overlarge.readBytes()
        val result = ProductVoiceTranscriptStore(root).readRecent()
        assertTrue(result.records.isEmpty())
        assertEquals("LIMIT_REACHED", result.failure)
        assertArrayEquals(overlargeBytes, overlarge.readBytes())

        val ignoredRoot = directory()
        completed(ignoredRoot, legacyJson())
        val external = newFile(ignoredRoot, "preserved.bin", ByteArray(1_048_576) { 23 })
        assertEquals("LIMIT_REACHED", ProductVoiceTranscriptStore(ignoredRoot).readRecent().failure)
        assertEquals(1_048_576L, external.length())
    }

    @Test
    fun boundsDirectoryEntryEnumerationEvenWhenFilesAreEmpty() {
        val root = directory()
        for (index in 1..4_096) newFile(root, "synthetic-$index.tmp", ByteArray(0))
        assertNull(ProductVoiceTranscriptStore(root).readRecent().failure)
        newFile(root, "synthetic-4097.tmp", ByteArray(0))
        val result = ProductVoiceTranscriptStore(root).readRecent()
        assertTrue(result.records.isEmpty())
        assertEquals("LIMIT_REACHED", result.failure)
        assertEquals(4_097, root.listFiles()!!.size)
    }
}
