package com.heytesla.app.speech.product

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

class ProductVoiceTranscriptStoreTest {
    // 사용자 정책에 따라 합성 fixture도 격리 디렉터리에 보존하며 자동 정리하지 않는다.
    private fun directory(): File = Files.createTempDirectory("product-transcript-host-").toFile().canonicalFile

    private fun record(text: String = "합성 시험 문장") = ProductVoiceTranscriptRecord(
        processId = "synthetic-process", trialId = "synthetic-trial", voiceLeaseId = 7,
        utterance = 1, wallTimeMs = 1_000, elapsedRealtimeMs = 200, appVersion = "test-version",
        text = text,
        diagnostic = ProductVoiceDiagnostic(
            outcome = "GRAMMAR_NO_MATCH", utteranceBytes = 640, voicedBytes = 320,
            suppressedOnsets = 2, silenceBytesAtEnd = 160, captureMs = 20, loadMs = 3,
            decodeMs = 4, cleanupMs = 5, modelReused = true, microphoneReleased = true,
            streamCleanupConfirmed = true, recognizerCleanupConfirmed = false,
        ),
    )

    @Test
    fun writesOnlyAtomicCompletedUuidJsonWithExactCorrelationAndEscapedText() {
        val root = File(directory(), "transcripts")
        val text = "합성\n\r\t\u0000\"\\끝😀"
        assertEquals(ProductVoiceTranscriptWriteResult.SAVED, ProductVoiceTranscriptStore(root).append(record(text)))
        val completed = root.listFiles()!!.single()
        assertEquals(completed.nameWithoutExtension, UUID.fromString(completed.nameWithoutExtension).toString())
        assertEquals("json", completed.extension)
        val expected = "{\"schemaVersion\":2,\"processId\":\"synthetic-process\",\"trialId\":\"synthetic-trial\"," +
            "\"voiceLeaseId\":7,\"utterance\":1,\"wallTimeMs\":1000,\"elapsedRealtimeMs\":200," +
            "\"appVersion\":\"test-version\",\"text\":\"합성\\u000a\\u000d\\u0009\\u0000\\\"\\\\끝😀\"," +
            "\"diagnostic\":{\"outcome\":\"GRAMMAR_NO_MATCH\",\"utteranceBytes\":640,\"voicedBytes\":320," +
            "\"suppressedOnsets\":2,\"silenceBytesAtEnd\":160,\"captureMs\":20,\"loadMs\":3," +
            "\"decodeMs\":4,\"cleanupMs\":5,\"modelReused\":true,\"microphoneReleased\":true," +
            "\"streamCleanupConfirmed\":true,\"recognizerCleanupConfirmed\":false},\"reason\":null}\n"
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), completed.readBytes())
        assertEquals(1, completed.readText().count { it == '\n' })
        assertEquals("ProductVoiceTranscriptRecord(REDACTED)", record(text).toString())
    }

    @Test
    fun appendsWithoutChangingPriorCompletedOrIncompleteFiles() {
        val root = directory()
        val incomplete = File(root, "prior.json.tmp").apply { writeText("synthetic-incomplete") }
        val store = ProductVoiceTranscriptStore(root)
        assertEquals(ProductVoiceTranscriptWriteResult.SAVED, store.append(record()))
        val first = root.listFiles()!!.single { it.extension == "json" }
        val firstBytes = first.readBytes()
        assertEquals(ProductVoiceTranscriptWriteResult.SAVED, store.append(record("다음 합성 결과").copy(utterance = 2)))
        assertArrayEquals(firstBytes, first.readBytes())
        assertEquals("synthetic-incomplete", incomplete.readText())
        assertEquals(2, root.listFiles()!!.count { it.extension == "json" })
        assertEquals(3, root.listFiles()!!.size)
    }

    @Test
    fun keepsEmptyResultsAndAcceptsOnlyActuallyCleanCompletedOutcomes() {
        val root = directory()
        val store = ProductVoiceTranscriptStore(root)
        val base = record("")
        for (outcome in listOf("EMPTY", "GRAMMAR_NO_MATCH", "ACCEPTED")) {
            assertEquals(ProductVoiceTranscriptWriteResult.SAVED, store.append(base.copy(
                diagnostic = base.diagnostic.copy(outcome = outcome, recognizerCleanupConfirmed = true),
            )))
        }
        assertTrue(root.listFiles()!!.all { it.readText().contains("\"text\":\"\"") })
        val rejected = listOf("PREPARED", "CLOSED", "FAILURE", "SEALED", "unknown", "ACCEPTED").map {
            base.copy(diagnostic = base.diagnostic.copy(outcome = it))
        } + listOf(
            base.copy(diagnostic = base.diagnostic.copy(microphoneReleased = false)),
            base.copy(diagnostic = base.diagnostic.copy(streamCleanupConfirmed = null)),
            base.copy(text = null),
        )
        for (candidate in rejected) assertEquals(ProductVoiceTranscriptWriteResult.INVALID_RECORD, store.append(candidate))
        assertEquals(3, root.listFiles()!!.size)
    }

    @Test
    fun preservesFailureAndSealedSummariesWithoutInventingTextOrRequiringSuccessfulCleanup() {
        val root = directory()
        val store = ProductVoiceTranscriptStore(root)
        val base = record().copy(text = null, reason = "PRODUCT_VOICE_UTTERANCE_TIMEOUT",
            diagnostic = ProductVoiceDiagnostic("FAILURE", utteranceBytes = 320_000,
                microphoneReleased = false, streamCleanupConfirmed = false, recognizerCleanupConfirmed = null))
        for (outcome in listOf("FAILURE", "SEALED")) {
            assertEquals(ProductVoiceTranscriptWriteResult.SAVED,
                store.append(base.copy(diagnostic = base.diagnostic.copy(outcome = outcome))))
        }
        assertEquals(2, root.listFiles()!!.size)
        assertTrue(root.listFiles()!!.all {
            val json = it.readText()
            json.contains("\"schemaVersion\":2,") && json.contains("\"text\":null,") &&
                json.contains("\"reason\":\"PRODUCT_VOICE_UTTERANCE_TIMEOUT\"")
        })
        assertEquals("ProductVoiceTranscriptRecord(REDACTED)", base.toString())
        assertEquals("ProductVoiceTranscriptReadResult(REDACTED)",
            ProductVoiceTranscriptReadResult(listOf(base), "READ_FAILED").toString())
    }

    @Test
    fun rejectsTextInFailureAndFreeFormReasonWithoutWritingOrEchoingThem() {
        val root = File(directory(), "transcripts")
        val store = ProductVoiceTranscriptStore(root)
        val failure = record().copy(text = null, reason = "PRODUCT_VOICE_FAILED",
            diagnostic = ProductVoiceDiagnostic("FAILURE"))
        val rejected = listOf(
            failure.copy(text = "옛 합성 문장을 실패에 붙이지 않음"),
            failure.copy(reason = null),
            failure.copy(reason = ""),
            failure.copy(reason = "실패 원문"),
            failure.copy(reason = "PRODUCT_VOICE_FAILED\nTEXT"),
            failure.copy(reason = "A".repeat(129)),
            failure.copy(reason = "1_FAILURE"),
            record().copy(reason = "사용자 원문"),
        )
        for (candidate in rejected) {
            assertEquals(ProductVoiceTranscriptWriteResult.INVALID_RECORD, store.append(candidate))
        }
        assertFalse(root.exists())
        assertEquals(ProductVoiceTranscriptWriteResult.SAVED,
            store.append(record().copy(reason = "FRUNK_VOICE_GRAMMAR_NO_MATCH")))
    }

    @Test
    fun rejectsOversizeAndMalformedTextWithoutTruncationOrCreatingRoot() {
        val root = File(directory(), "transcripts")
        val store = ProductVoiceTranscriptStore(root)
        for (text in listOf("가".repeat(4_097), "\ud800", "\udc00", "시작\ud800끝")) {
            assertEquals(ProductVoiceTranscriptWriteResult.INVALID_RECORD, store.append(record(text)))
            assertFalse(root.exists())
        }
        val maximum = "가".repeat(4_096)
        assertEquals(ProductVoiceTranscriptWriteResult.SAVED, store.append(record(maximum)))
        assertTrue(root.listFiles()!!.single().readText().contains("\"text\":\"$maximum\""))
    }

    @Test
    fun enforcesUtf8ByteBudgetIncludingPreservedIncompleteAndUnrelatedFiles() {
        val probe = directory()
        val candidate = record("가".repeat(500))
        assertEquals(ProductVoiceTranscriptWriteResult.SAVED, ProductVoiceTranscriptStore(probe).append(candidate))
        val encoded = probe.listFiles()!!.single().readBytes()
        assertTrue(encoded.size > String(encoded, Charsets.UTF_8).length)

        val exact = directory()
        val prior = File(exact, "previous.json.tmp").apply { writeText("보존") }
        val original = prior.readBytes()
        val limit = encoded.size.toLong() + original.size
        val store = ProductVoiceTranscriptStore(exact, limit)
        assertEquals(ProductVoiceTranscriptWriteResult.SAVED, store.append(candidate))
        assertEquals(ProductVoiceTranscriptWriteResult.LIMIT_REACHED, store.append(candidate))
        assertEquals(limit, exact.listFiles()!!.sumOf { it.length() })
        assertArrayEquals(original, prior.readBytes())

        val short = directory()
        assertEquals(ProductVoiceTranscriptWriteResult.LIMIT_REACHED,
            ProductVoiceTranscriptStore(short, encoded.size.toLong() - 1).append(candidate))
        assertTrue(short.listFiles()!!.isEmpty())
    }

    @Test
    fun defaultLimitDoesNotRotateOrRemoveAnything() {
        val root = directory()
        val existing = File(root, "preserved.bin").apply { writeBytes(ByteArray(1_048_576) { 17 }) }
        assertEquals(ProductVoiceTranscriptWriteResult.LIMIT_REACHED, ProductVoiceTranscriptStore(root).append(record()))
        assertEquals(1, root.listFiles()!!.size)
        assertEquals(1_048_576L, existing.length())
        assertTrue(existing.readBytes().all { it == 17.toByte() })
    }

    @Test
    fun rejectsRootAndAncestorSymlinksAndParentTraversalWithoutOutsideWrites() {
        val parent = directory()
        val outside = directory()
        val linkedRoot = File(parent, "linked-root")
        Files.createSymbolicLink(linkedRoot.toPath(), outside.toPath())
        assertEquals(ProductVoiceTranscriptWriteResult.WRITE_FAILED,
            ProductVoiceTranscriptStore(linkedRoot).append(record()))
        assertEquals(ProductVoiceTranscriptWriteResult.WRITE_FAILED,
            ProductVoiceTranscriptStore(File(linkedRoot, "child")).append(record()))
        val traversal = File(File(parent, "unused"), "../escaped")
        assertEquals(ProductVoiceTranscriptWriteResult.WRITE_FAILED,
            ProductVoiceTranscriptStore(traversal).append(record()))
        assertTrue(outside.listFiles()!!.isEmpty())
        assertFalse(File(parent, "escaped").exists())
    }

    @Test
    fun rejectsSymlinkEntriesAndUnscannableDirectoriesWhilePreservingThem() {
        val root = directory()
        val outside = File(directory(), "unchanged.json").apply { writeText("synthetic-original") }
        val link = File(root, "existing.json")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertEquals(ProductVoiceTranscriptWriteResult.WRITE_FAILED, ProductVoiceTranscriptStore(root).append(record()))
        assertEquals("synthetic-original", outside.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        val nestedRoot = directory()
        val nested = File(nestedRoot, "preserved-directory").apply { mkdir() }
        assertEquals(ProductVoiceTranscriptWriteResult.WRITE_FAILED, ProductVoiceTranscriptStore(nestedRoot).append(record()))
        assertTrue(nested.isDirectory)
        assertEquals(1, nestedRoot.listFiles()!!.size)
    }

    @Test
    fun pathAndPermissionFailuresReturnOnlyFixedFailureAndDoNotReplaceFiles() {
        val fileRoot = File(directory(), "not-a-directory").apply { writeText("synthetic-preserved") }
        assertEquals(ProductVoiceTranscriptWriteResult.WRITE_FAILED, ProductVoiceTranscriptStore(fileRoot).append(record()))
        assertEquals("synthetic-preserved", fileRoot.readText())
        val missingParent = File(directory(), "missing-parent/child")
        assertEquals(ProductVoiceTranscriptWriteResult.WRITE_FAILED,
            ProductVoiceTranscriptStore(missingParent).append(record()))

        assertEquals(ProductVoiceTranscriptWriteResult.WRITE_FAILED,
            ProductVoiceTranscriptStore(File(directory(), "invalid\u0000path")).append(record()))

        val locked = directory()
        Files.setPosixFilePermissions(locked.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        assumeFalse("권한을 우회하는 host에서는 권한 경계를 관측할 수 없음", Files.isWritable(locked.toPath()))
        assertEquals(ProductVoiceTranscriptWriteResult.WRITE_FAILED, ProductVoiceTranscriptStore(locked).append(record()))
        assertTrue(locked.listFiles()!!.isEmpty())
    }
}
