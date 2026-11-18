package com.heytesla.app.speech.product

import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductVoiceTranscriptWriterTest {
    // 실패물/완료본 모두 합성 데이터이며 자동 삭제하지 않고 host 격리 경로에 남긴다.
    private fun directory(): File = Files.createTempDirectory("product-transcript-writer-host-").toFile().canonicalFile

    private fun record(utterance: Long = 1) = ProductVoiceTranscriptRecord(
        processId = "synthetic-process", trialId = null, voiceLeaseId = 1, utterance = utterance,
        wallTimeMs = 1_000, elapsedRealtimeMs = 200, appVersion = "test-version", text = "합성-$utterance",
        diagnostic = ProductVoiceDiagnostic(
            outcome = "GRAMMAR_NO_MATCH", microphoneReleased = true,
            streamCleanupConfirmed = true, recognizerCleanupConfirmed = false,
        ),
    )

    /** 주입 가능한 실제 Executor: 요청한 작업을 명시 drain 전까지 실행하지 않는다. */
    private class DeferredExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }
        fun runPending() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test
    fun acceptsImmediatelyAndPerformsNoDiskWorkUntilWorkerExecutes() {
        val parent = directory()
        val root = File(parent, "transcripts")
        val results = mutableListOf<ProductVoiceTranscriptWriteResult>()
        val executor = DeferredExecutor()
        val writer = ProductVoiceTranscriptWriter(ProductVoiceTranscriptStore(root), results::add, executor)
        assertTrue(writer.submit(record()))
        assertFalse(root.exists())
        assertTrue(results.isEmpty())
        executor.runPending()
        assertEquals(listOf(ProductVoiceTranscriptWriteResult.SAVED), results)
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test
    fun acceptsSixteenPendingThenReportsQueueFullWithoutFalseSaved() {
        val root = directory()
        val results = mutableListOf<ProductVoiceTranscriptWriteResult>()
        val executor = DeferredExecutor()
        val writer = ProductVoiceTranscriptWriter(ProductVoiceTranscriptStore(root), results::add, executor)
        for (utterance in 1L..16L) assertTrue(writer.submit(record(utterance)))
        assertFalse(writer.submit(record(17)))
        assertEquals(listOf(ProductVoiceTranscriptWriteResult.QUEUE_FULL), results)
        assertTrue(root.listFiles()!!.isEmpty())
        executor.runPending()
        assertEquals(16, results.count { it == ProductVoiceTranscriptWriteResult.SAVED })
        assertEquals(16, root.listFiles()!!.size)
        assertFalse(root.listFiles()!!.any { it.readText().contains("\"text\":\"합성-17\"") })
    }


    @Test
    fun executorFailureReportsFixedFailureClearsPendingAndAllowsLaterSubmission() {
        val root = directory()
        val results = mutableListOf<ProductVoiceTranscriptWriteResult>()
        val deferred = DeferredExecutor()
        var reject = true
        val executor = Executor { task ->
            if (reject) throw RejectedExecutionException("합성 민감 메시지")
            deferred.execute(task)
        }
        val writer = ProductVoiceTranscriptWriter(ProductVoiceTranscriptStore(root), results::add, executor)
        assertFalse(writer.submit(record()))
        assertEquals(listOf(ProductVoiceTranscriptWriteResult.WRITE_FAILED), results)
        assertTrue(root.listFiles()!!.isEmpty())
        reject = false
        assertTrue(writer.submit(record(2)))
        deferred.runPending()
        assertEquals(listOf(ProductVoiceTranscriptWriteResult.WRITE_FAILED, ProductVoiceTranscriptWriteResult.SAVED), results)
        assertTrue(root.listFiles()!!.single().readText().contains("\"utterance\":2,"))
    }

    @Test
    fun storeFailuresAndInvalidRecordsAreNotReportedAsSavedAndDoNotStopQueue() {
        val root = directory()
        val results = mutableListOf<ProductVoiceTranscriptWriteResult>()
        val executor = DeferredExecutor()
        val writer = ProductVoiceTranscriptWriter(ProductVoiceTranscriptStore(root, 0), results::add, executor)
        assertTrue(writer.submit(record().copy(text = "가".repeat(4_097))))
        assertTrue(writer.submit(record(2)))
        executor.runPending()
        assertEquals(listOf(ProductVoiceTranscriptWriteResult.INVALID_RECORD, ProductVoiceTranscriptWriteResult.LIMIT_REACHED), results)
        assertTrue(root.listFiles()!!.isEmpty())

        val broken = File(directory(), "file-root").apply { writeText("synthetic-preserved") }
        val brokenWriter = ProductVoiceTranscriptWriter(ProductVoiceTranscriptStore(broken), results::add, executor)
        assertTrue(brokenWriter.submit(record(3)))
        executor.runPending()
        assertEquals(ProductVoiceTranscriptWriteResult.WRITE_FAILED, results.last())
        assertEquals("synthetic-preserved", broken.readText())
        assertFalse(results.contains(ProductVoiceTranscriptWriteResult.SAVED))
    }

    @Test
    fun resultConsumerFailureDoesNotStrandSubsequentWrites() {
        val root = directory()
        val executor = DeferredExecutor()
        val observed = mutableListOf<ProductVoiceTranscriptWriteResult>()
        val writer = ProductVoiceTranscriptWriter(ProductVoiceTranscriptStore(root), {
            observed.add(it)
            throw IllegalStateException("합성 callback 메시지")
        }, executor)
        assertTrue(writer.submit(record()))
        assertTrue(writer.submit(record(2)))
        executor.runPending()
        assertEquals(listOf(ProductVoiceTranscriptWriteResult.SAVED, ProductVoiceTranscriptWriteResult.SAVED), observed)
        assertEquals(2, root.listFiles()!!.size)
    }

    @Test(timeout = 5_000)
    fun defaultWorkerRunsOffCallerAndDoesNotBlockFurtherSubmissionsOnItsResultConsumer() {
        val root = directory()
        val firstCompleted = CountDownLatch(1)
        val releaseConsumer = CountDownLatch(1)
        val allCompleted = CountDownLatch(3)
        val results = Collections.synchronizedList(mutableListOf<ProductVoiceTranscriptWriteResult>())
        val caller = Thread.currentThread()
        var callbackThread: Thread? = null
        val writer = ProductVoiceTranscriptWriter(ProductVoiceTranscriptStore(root), { result ->
            callbackThread = Thread.currentThread()
            results.add(result)
            allCompleted.countDown()
            if (firstCompleted.count > 0) {
                firstCompleted.countDown()
                releaseConsumer.await(3, TimeUnit.SECONDS)
            }
        })
        try {
            assertTrue(writer.submit(record()))
            assertTrue(firstCompleted.await(2, TimeUnit.SECONDS))
            assertNotEquals(caller, callbackThread)
            assertTrue(callbackThread!!.isDaemon)
            assertTrue(writer.submit(record(2)))
            assertTrue(writer.submit(record(3)))
        } finally {
            releaseConsumer.countDown()
        }
        assertTrue(allCompleted.await(2, TimeUnit.SECONDS))
        assertEquals(List(3) { ProductVoiceTranscriptWriteResult.SAVED }, results)
        val completed = root.listFiles()!!.map { it.readText() }
        assertEquals(3, completed.size)
        assertTrue(completed.any { it.contains("\"utterance\":1,") })
        assertTrue(completed.any { it.contains("\"utterance\":3,") })
        assertTrue(completed.any { it.contains("\"utterance\":2,") })
    }
}
