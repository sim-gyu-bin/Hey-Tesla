package com.heytesla.app.logging

import com.heytesla.app.diagnostics.SpeechTrialSummary
import com.heytesla.app.diagnostics.TeslaApproachReadSummary
import com.heytesla.app.speech.SpeechCommandDecision
import com.heytesla.app.vehicle.detection.BleEventEvidence
import com.heytesla.app.vehicle.detection.BleEvidenceKind
import com.heytesla.app.vehicle.frunk.FrunkVoiceRecognition
import com.heytesla.app.vehicle.frunk.FrunkVoiceRecognitionCandidate
import com.heytesla.app.vehicle.frunk.FrunkVoiceRecognitionResult
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.uwb.TeslaUwbGattEvent

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 실제 파일에 대한 append·재개·회전 상한·쓰기 실패·allowlist 동작을 검증한다.
 * 임시 디렉터리는 회수 근거로 남긴다. 제거 대상은 sink가 소유하는 새 archive뿐이다.
 */
class FieldEventLogTest {
    @Test fun frunkRecognitionStorageBoundaryMasksDirectPayloadAndDropsUnknownReason() {
        val file = newFile()
        val sink = startSink(file)
        // capture를 우회해도 저장 경계가 원문과 잘못된 신뢰도를 차단해야 한다.
        val raw = FrunkVoiceRecognitionResult(listOf(
            FrunkVoiceRecognitionCandidate("헤이 테슬라 가상사용자 프렁크 열어줘", 0.9f),
            FrunkVoiceRecognitionCandidate("테슬라@example.invalid", Float.NaN),
            FrunkVoiceRecognitionCandidate("프렁크123", -1f),
            FrunkVoiceRecognitionCandidate("SYNTHETIC-EXCESS-ID", 1f),
        ), accepted = false, reason = "SYNTHETIC-PRIVATE-REASON")
        assertTrue(sink.enqueue(record(FrunkVoiceRecognition.EVENT).copy(frunkVoiceRecognition = raw)))
        assertTrue(sink.sync())
        val text = file.readText()
        for (secret in listOf("가상사용자", "테슬라@example.invalid", "프렁크123",
            "SYNTHETIC-EXCESS-ID", "SYNTHETIC-PRIVATE-REASON")) assertFalse(text.contains(secret))
        assertTrue(text.contains("\"text\":\"헤이 테슬라 [마스킹] 프렁크 열어줘\""))
        assertTrue(text.contains("\"confidence\":0.9,\"masked\":true"))
        assertEquals(2, Regex("\"confidence\":null").findAll(text).count())
        assertTrue(text.contains("\"candidatesTruncated\":true"))
        assertTrue(text.contains("\"policy\":{\"accepted\":false,\"reason\":null}"))
        assertEquals(1, text.count { it == '\n' })
    }

    @Test fun frunkRecognitionCannotLeakOntoGeneralSpeechSupportOrLifecycleEvents() {
        val payload = FrunkVoiceRecognitionResult(
            listOf(FrunkVoiceRecognitionCandidate("헤이 테슬라 프렁크 열어줘", 0.99f)),
            true, null)
        val file = newFile()
        val sink = startSink(file)
        for (event in listOf("SPEECH_TRIAL_FINAL_RECEIVED_DIRECT_MIC", "SPEECH_SUPPORT_METADATA_RECEIVED",
            "FRUNK_VOICE_STARTED", "FRUNK_VOICE_CANCELED", "FRUNK_VOICE_EXPIRED",
            "FRUNK_VOICE_COMMAND_ACCEPTED")) {
            assertTrue(sink.enqueue(record(event).copy(frunkVoiceRecognition = payload)))
        }
        assertTrue(sink.sync())
        val text = file.readText()
        assertFalse(text.contains("헤이 테슬라 프렁크 열어줘"))
        assertTrue(text.lineSequence().filter { it.isNotEmpty() }.all {
            it.contains("\"frunkVoiceRecognition\":null")
        })
    }

    @Test fun frunkRecognitionIsSchema22OnlyAndDoesNotCarryIntoFollowingRows() {
        val payload = FrunkVoiceRecognition.capture(listOf("취소"), null, false, "FRUNK_VOICE_CANCELED")
        for (version in 1..21) {
            val encoded = FieldJson.encode(record(FrunkVoiceRecognition.EVENT).copy(
                version = version, frunkVoiceRecognition = payload))
            assertTrue(encoded.contains("\"frunkVoiceRecognition\":null"))
            assertFalse(encoded.contains("\"text\":"))
        }
        val file = newFile()
        val first = startSink(file)
        assertTrue(first.enqueue(record(FrunkVoiceRecognition.EVENT).copy(frunkVoiceRecognition = payload)))
        assertTrue(first.sync())
        val second = startSink(file)
        assertTrue(second.enqueue(record("FRUNK_VOICE_CANCELED")))
        assertTrue(second.sync())
        val rows = file.readLines()
        assertTrue(rows[0].contains("\"text\":\"취소\",\"confidence\":null"))
        assertTrue(rows[0].contains("\"policy\":{\"accepted\":false,\"reason\":\"FRUNK_VOICE_CANCELED\"}"))
        assertTrue(rows[1].contains("\"frunkVoiceRecognition\":null"))
        assertFalse(rows[1].contains("\"candidates\""))
    }

    @Test fun appendsAfterExistingCompleteLine() {
        val file = newFile()
        val prefix = "EARLIER-ROW\n"
        writeExisting(file, prefix)
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
        assertTrue(sink.sync())

        val text = file.readText()
        assertTrue(text.startsWith(prefix))
        val lines = text.split('\n').dropLastWhile { it.isEmpty() }
        assertEquals(2, lines.size)
        assertEquals("EARLIER-ROW", lines[0])
        assertTrue(lines[1].contains("\"event\":\"REAL_BLE_APPEARED\""))
        assertEquals(1L, sink.status().written)
        assertEquals(0, sink.status().pending)
    }

    @Test fun partialLastLineIsSeparatedWithoutRewritingIt() {
        val file = newFile()
        // 이전 실행이 중간에 끊겨 남은 잘린 행. 바이트를 고치지 않고 줄바꿈만 덧붙여야 한다.
        val truncated = "{\"version\":1,\"processId\":\"e5f"
        writeExisting(file, truncated)
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.sync())

        val bytes = file.readBytes()
        assertArrayEquals(truncated.toByteArray(Charsets.UTF_8), bytes.copyOfRange(0, truncated.length))
        assertTrue(bytes[truncated.length] == '\n'.code.toByte())
        val lines = file.readText().split('\n').dropLastWhile { it.isEmpty() }
        assertEquals(2, lines.size)
        assertEquals(truncated, lines[0])
    }

    @Test fun restartAppendsAfterPreviousProcessRecords() {
        val file = newFile()
        val first = startSink(file)
        assertTrue(first.enqueue(record("PROCESS_START_OFF")))
        assertTrue(first.sync())
        val firstRunBytes = file.readText()

        val second = startSink(file)
        assertTrue(second.enqueue(record("UI_RESUMED")))
        assertTrue(second.sync())

        val text = file.readText()
        assertEquals(firstRunBytes, text.substring(0, firstRunBytes.length))
        assertEquals(2, text.split('\n').dropLastWhile { it.isEmpty() }.size)
        // written은 sink별 확정 건수이고 fileBytes는 기존 로그까지 포함한 파일 전체 크기다.
        assertEquals(1L, second.status().written)
        assertEquals(file.length(), second.status().fileBytes)
    }

    @Test fun currentVersionRowsAppendWithoutRewritingLegacyVersionOneRows() {
        val file = newFile()
        // 예전 실행이 남긴 v1 행: speechTrial 키 자체가 없다.
        val legacy = "{\"version\":1,\"processId\":\"legacy\",\"appVersion\":\"0.8.0-probe\"," +
            "\"event\":\"PROCESS_START_OFF\",\"state\":{}}\n"
        writeExisting(file, legacy)

        val sink = startSink(file)
        assertTrue(
            sink.enqueue(
                record(
                    "SPEECH_TRIAL_FINAL_RECEIVED_TTS_PCM",
                    speechTrial = summary(confidence = 0.25f, decision = SpeechCommandDecision.FRUNK_OPEN_CANDIDATE),
                ),
            ),
        )
        assertTrue(sink.sync())

        val text = file.readText()
        // 기존 v1 행은 바이트까지 그대로 남고 새 행만 현재 버전으로 덧붙는다.
        assertEquals(legacy, text.substring(0, legacy.length))
        val lines = text.split('\n').dropLastWhile { it.isEmpty() }
        assertEquals(2, lines.size)
        assertFalse(lines[0].contains("speechTrial"))
        assertTrue(lines[1].startsWith("{\"version\":$FIELD_LOG_RECORD_VERSION,"))
        assertEquals("0.25", confidenceOf(lines[1]))
        assertTrue(trialValue(lines[1]).contains("\"mode\":\"TTS_PCM\""))
        assertTrue(trialValue(lines[1]).contains("\"commandDecision\":\"FRUNK_OPEN_CANDIDATE\""))
    }

    @Test fun repeatedRotationBoundsAllFilesAndRetainsRecentTransitionsInOrder() {
        val file = newFile()
        val budget = 4_096L
        val sink = startSink(file, limitBytes = budget)
        val transitions = listOf(
            "BLE_DETECTION_RESTORE_REQUESTED",
            "BLE_DETECTION_RESTORE_BLOCKED",
            "BLE_DETECTION_PREFERENCE_FAILED",
            "PROCESS_EXIT_OBSERVED",
            "BLE_LOCATION_PERMISSION_REQUIRED",
            "BLE_LOCATION_DISABLED",
            "REAL_BLE_APPEARED",
            "REAL_BLE_DISAPPEARED",
        )
        val expected = (0 until 80).map { index ->
            record(transitions[index % transitions.size]).copy(wallTimeMs = 1_700_000_000_000L + index)
        }
        for (row in expected) {
            assertTrue(sink.enqueue(row))
            assertTrue(sink.sync())
            assertBoundedRetention(file, sink, budget)
        }

        val rows = retainedRows(file)
        val times = rows.map(::wallTimeOf)
        assertTrue(times.first() > expected.first().wallTimeMs)
        assertEquals(expected.takeLast(rows.size).map { it.wallTimeMs }, times)
        assertEquals(expected.takeLast(4).map { it.event }, rows.takeLast(4).map(::eventOf))
        assertTrue(retainedFiles(file).all { it.isFile })
        assertEquals(80L, sink.status().written)
        assertEquals(0L, sink.status().dropped)
    }

    @Test fun reopenedSinkContinuesRotationAcrossExistingArchives() {
        val file = newFile()
        val budget = 4_096L
        val first = startSink(file, budget)
        for (index in 0 until 20) {
            assertTrue(first.enqueue(record("REAL_BLE_APPEARED").copy(wallTimeMs = index.toLong())))
            assertTrue(first.sync())
        }
        val second = startSink(file, budget)
        for (index in 20 until 40) {
            assertTrue(second.enqueue(record("REAL_BLE_DISAPPEARED").copy(wallTimeMs = index.toLong())))
            assertTrue(second.sync())
            assertBoundedRetention(file, second, budget)
        }
        val rows = retainedRows(file)
        assertEquals((40 - rows.size until 40).map(Int::toLong), rows.map(::wallTimeOf))
        assertEquals(20L, second.status().written)
        assertEquals(0L, second.status().dropped)
    }

    @Test fun fullPartialActiveRotatesWithoutJoiningTheNextRecord() {
        val file = newFile()
        val prefix = "{\"version\":8,\"state\":{"
        val partial = prefix + " ".repeat(512 - prefix.length)
        writeExisting(file, partial)
        val sink = startSink(file, limitBytes = 2_048L)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.sync())

        assertArrayEquals(partial.toByteArray(Charsets.UTF_8), archive(file, 1).readBytes())
        assertEquals(listOf("PROCESS_START_OFF"), file.readLines().map(::eventOf))
        assertBoundedRetention(file, sink, 2_048L)
        assertEquals(1L, sink.status().written)
    }

    @Test fun legacyLogsKeysSettingsAndSimilarNamesSurviveRepeatedRotation() {
        val file = newFile()
        val names = listOf(
            "events.jsonl", "events.1.jsonl", "events-v2.4.jsonl",
            "events-v2.1.jsonl.backup", "events-v2.backup.jsonl",
            "vehicle-key.dat", "settings.xml",
        )
        val preserved = names.mapIndexed { index, name ->
            File(file.parentFile, name).also { writeExisting(it, "preserved-$index\n") }
        }
        val before = preserved.map { it.readBytes() }
        val sink = startSink(file, limitBytes = 2_048L)
        repeat(30) { index ->
            assertTrue(sink.enqueue(record("REAL_BLE_APPEARED").copy(wallTimeMs = index.toLong())))
            assertTrue(sink.sync())
        }

        for (index in preserved.indices) assertArrayEquals(before[index], preserved[index].readBytes())
        assertBoundedRetention(file, sink, 2_048L)
        val rows = retainedRows(file)
        assertEquals((30 - rows.size until 30).map(Int::toLong), rows.map(::wallTimeOf))
        assertEquals(0L, sink.status().dropped)
    }

    @Test fun legacyTargetIsRejectedWithoutAppendingOrDeletingIt() {
        val file = File(newDir(), "events.jsonl")
        val original = "legacy-event\n"
        writeExisting(file, original)
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.sync())

        assertEquals(original, file.readText())
        assertEquals(FieldLogFailureCode.OPEN_FAILED, sink.status().failureCode)
        assertEquals(0L, sink.status().written)
        assertEquals(1L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
    }

    @Test fun activeAndArchiveSymlinksNeverModifyOrDeleteTheirTargets() {
        for (activeLink in listOf(true, false)) {
            val file = newFile()
            val legacy = File(file.parentFile, "events.jsonl")
            writeExisting(legacy, "untouched\n")
            val link = if (activeLink) file else archive(file, 3)
            Files.createSymbolicLink(link.toPath(), legacy.toPath())
            val sink = startSink(file)
            assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
            assertTrue(sink.sync())

            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals("untouched\n", legacy.readText())
            assertEquals(FieldLogFailureCode.OPEN_FAILED, sink.status().failureCode)
            assertEquals(0L, sink.status().written)
            assertEquals(1L, sink.status().dropped)
            assertEquals(0, sink.status().pending)
        }
    }

    @Test fun oversizedRecordFailsBeforeEvictingAnyRetainedFile() {
        val file = newFile()
        writeExisting(file, "active-row\n")
        for (index in 1..3) writeExisting(archive(file, index), "archived-$index\n")
        val before = retainedFiles(file).map { it.readBytes() }
        val sink = startSink(file, limitBytes = 2_048L)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF").copy(trialId = "x".repeat(512))))
        assertTrue(sink.sync())

        for ((index, retained) in retainedFiles(file).withIndex()) {
            assertArrayEquals(before[index], retained.readBytes())
        }
        assertEquals(FieldLogHealth.LIMIT_REACHED, sink.status().health)
        assertEquals(FieldLogFailureCode.LIMIT_REACHED, sink.status().failureCode)
        assertEquals(0L, sink.status().written)
        assertEquals(1L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
        assertFalse(sink.enqueue(record("UI_RESUMED")))
        assertEquals(2L, sink.status().dropped)
    }

    @Test fun rotationFailurePreservesConfirmedRowsAndAccountsForFollowingRecords() {
        val file = newFile()
        val sink = startSink(file, limitBytes = 1_600L)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.sync())
        val confirmed = file.readBytes()
        val blocker = archive(file, 3)
        assertTrue(blocker.mkdir())
        val protected = File(blocker, "keep")
        protected.writeText("untouched")
        sink.enqueue(record("REAL_BLE_APPEARED"))
        sink.enqueue(record("REAL_BLE_DISAPPEARED"))
        assertTrue(sink.sync())

        assertArrayEquals(confirmed, file.readBytes())
        assertEquals("untouched", protected.readText())
        assertEquals(confirmed.size.toLong(), sink.status().fileBytes)
        assertEquals(FieldLogHealth.WRITE_FAILED, sink.status().health)
        assertEquals(FieldLogFailureCode.WRITE_FAILED, sink.status().failureCode)
        assertEquals(1L, sink.status().written)
        assertEquals(2L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
    }

    @Test fun writeExceptionAccountsPartialBytesAndDrainsAcceptedRecords() {
        val file = newFile()
        val sink = FieldEventSink(file, FIELD_LOG_LIMIT_BYTES, 512) { target ->
            object : RandomAccessFile(target, "rw") {
                override fun write(bytes: ByteArray) {
                    super.write(bytes, 0, bytes.size / 2)
                    throw IOException()
                }
            }
        }
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
        sink.start()
        assertTrue(sink.sync())

        assertTrue(file.length() > 0L)
        assertFalse(file.readText().endsWith("\n"))
        assertEquals(FieldLogFailureCode.WRITE_FAILED, sink.status().failureCode)
        assertEquals(file.length(), sink.status().fileBytes)
        assertEquals(0L, sink.status().written)
        assertEquals(2L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
        assertFalse(sink.enqueue(record("UI_RESUMED")))
        assertEquals(3L, sink.status().dropped)
    }

    @Test fun syncExceptionDoesNotConfirmAnUnsyncedRecord() {
        val file = newFile()
        val sink = FieldEventSink(file, FIELD_LOG_LIMIT_BYTES, 512) { target ->
            object : RandomAccessFile(target, "rw") {
                override fun write(value: Int) {
                    super.write(value)
                    close()
                }
            }
        }
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
        sink.start()
        assertTrue(sink.sync())

        assertEquals(listOf("PROCESS_START_OFF"), file.readLines().map(::eventOf))
        assertEquals(FieldLogFailureCode.SYNC_FAILED, sink.status().failureCode)
        assertEquals(file.length(), sink.status().fileBytes)
        assertEquals(0L, sink.status().written)
        assertEquals(2L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
    }

    @Test fun queueOverflowDuringWriteKeepsFirstFailureAndSettlesEveryPendingRecord() {
        val file = newFile()
        val enteredWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val negativePending = AtomicBoolean(false)
        val sink = FieldEventSink(file, FIELD_LOG_LIMIT_BYTES, 1) { target ->
            object : RandomAccessFile(target, "rw") {
                override fun write(bytes: ByteArray) {
                    enteredWrite.countDown()
                    if (!releaseWrite.await(5, TimeUnit.SECONDS)) throw IOException()
                    throw IOException()
                }
            }
        }
        sink.onStatusChanged = { if (sink.status().pending < 0) negativePending.set(true) }
        sink.start()
        try {
            assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
            assertTrue(enteredWrite.await(5, TimeUnit.SECONDS))
            assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
            assertFalse(sink.enqueue(record("REAL_BLE_DISAPPEARED")))
            assertEquals(2, sink.status().pending)
            sink.recordDroppedLocally()
            assertEquals(FieldLogHealth.QUEUE_OVERFLOW, sink.status().health)
            assertEquals(FieldLogFailureCode.QUEUE_OVERFLOW, sink.status().failureCode)
        } finally {
            releaseWrite.countDown()
        }
        assertTrue(sink.sync())

        assertEquals(FieldLogHealth.QUEUE_OVERFLOW, sink.status().health)
        assertEquals(FieldLogFailureCode.QUEUE_OVERFLOW, sink.status().failureCode)
        assertEquals(0L, sink.status().written)
        assertEquals(4L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
        assertFalse(negativePending.get())
    }

    @Test fun syncTimeoutDoesNotWaitIndefinitelyOrChangeRecordAccounting() {
        for (fullQueue in listOf(false, true)) {
            for (timeoutMs in listOf(25L, 0L, -1L, Long.MIN_VALUE)) {
                val enteredOpen = CountDownLatch(1)
                val releaseOpen = CountDownLatch(1)
                val sink = FieldEventSink(newFile(), FIELD_LOG_LIMIT_BYTES, 1) { target ->
                    enteredOpen.countDown()
                    if (!releaseOpen.await(5, TimeUnit.SECONDS)) throw IOException()
                    RandomAccessFile(target, "rw")
                }
                val finished = CountDownLatch(1)
                val result = AtomicBoolean(true)
                val waiter = Thread {
                    try {
                        result.set(sink.sync(timeoutMs))
                    } finally {
                        finished.countDown()
                    }
                }.apply { isDaemon = true }
                val recordCount = if (fullQueue) 2 else 1
                sink.start()
                try {
                    assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
                    assertTrue(enteredOpen.await(5, TimeUnit.SECONDS))
                    if (fullQueue) assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
                    waiter.start()
                    assertTrue(finished.await(1, TimeUnit.SECONDS))
                    assertFalse(result.get())
                    assertEquals(recordCount, sink.status().pending)
                    assertEquals(0L, sink.status().written)
                    assertEquals(0L, sink.status().dropped)
                    assertFalse(sink.status().failed)
                } finally {
                    releaseOpen.countDown()
                    waiter.interrupt()
                    waiter.join(5_000L)
                }
                assertFalse(waiter.isAlive)
                assertTrue(sink.sync())
                assertEquals(recordCount.toLong(), sink.status().written)
                assertEquals(0, sink.status().pending)
                assertEquals(0L, sink.status().dropped)
                assertFalse(sink.status().failed)
            }
        }
    }

    @Test fun syncSharesOneTimeoutBudgetAcrossQueueAndBarrierWaits() {
        val enteredFirstWrite = CountDownLatch(1)
        val enteredSecondWrite = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val releaseSecondWrite = CountDownLatch(1)
        val sink = FieldEventSink(newFile(), FIELD_LOG_LIMIT_BYTES, 1) { target ->
            object : RandomAccessFile(target, "rw") {
                private var firstWrite = true

                override fun write(bytes: ByteArray) {
                    val release = if (firstWrite) {
                        firstWrite = false
                        enteredFirstWrite.countDown()
                        releaseFirstWrite
                    } else {
                        enteredSecondWrite.countDown()
                        releaseSecondWrite
                    }
                    if (!release.await(5, TimeUnit.SECONDS)) throw IOException()
                    super.write(bytes)
                }
            }
        }
        val enteredSync = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicBoolean(true)
        val waiter = Thread {
            try {
                enteredSync.countDown()
                result.set(sink.sync(1_500L))
            } finally {
                finished.countDown()
            }
        }.apply { isDaemon = true }
        sink.start()
        try {
            assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
            assertTrue(enteredFirstWrite.await(5, TimeUnit.SECONDS))
            assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
            waiter.start()
            assertTrue(enteredSync.await(5, TimeUnit.SECONDS))
            assertFalse(finished.await(900, TimeUnit.MILLISECONDS))
            releaseFirstWrite.countDown()
            assertTrue(enteredSecondWrite.await(5, TimeUnit.SECONDS))
            // 큐에서 쓴 약 900ms 뒤에 배리어가 새 1,500ms 예산을 받으면 안 된다.
            assertTrue(finished.await(900, TimeUnit.MILLISECONDS))
            assertFalse(result.get())
            assertEquals(1L, sink.status().written)
            assertEquals(1, sink.status().pending)
            assertEquals(0L, sink.status().dropped)
            assertFalse(sink.status().failed)
        } finally {
            releaseFirstWrite.countDown()
            releaseSecondWrite.countDown()
            waiter.interrupt()
            waiter.join(5_000L)
        }
        assertFalse(waiter.isAlive)
        assertTrue(sink.sync())
        assertEquals(2L, sink.status().written)
        assertEquals(0, sink.status().pending)
        assertEquals(0L, sink.status().dropped)
        assertFalse(sink.status().failed)
    }

    @Test fun syncInterruptionPreservesFlagDuringQueueAndBarrierWaits() {
        for (fullQueue in listOf(false, true)) {
            val enteredOpen = CountDownLatch(1)
            val releaseOpen = CountDownLatch(1)
            val sink = FieldEventSink(newFile(), FIELD_LOG_LIMIT_BYTES, 1) { target ->
                enteredOpen.countDown()
                if (!releaseOpen.await(5, TimeUnit.SECONDS)) throw IOException()
                RandomAccessFile(target, "rw")
            }
            val enteredSync = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val result = AtomicBoolean(true)
            val interrupted = AtomicBoolean(false)
            val waiter = Thread {
                try {
                    enteredSync.countDown()
                    result.set(sink.sync(Long.MAX_VALUE))
                    interrupted.set(Thread.currentThread().isInterrupted)
                } finally {
                    finished.countDown()
                }
            }.apply { isDaemon = true }
            val recordCount = if (fullQueue) 2 else 1
            sink.start()
            try {
                assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
                assertTrue(enteredOpen.await(5, TimeUnit.SECONDS))
                if (fullQueue) assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
                waiter.start()
                assertTrue(enteredSync.await(5, TimeUnit.SECONDS))
                assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
                waiter.interrupt()
                assertTrue(finished.await(1, TimeUnit.SECONDS))
                assertFalse(result.get())
                assertTrue(interrupted.get())
                assertEquals(recordCount, sink.status().pending)
                assertEquals(0L, sink.status().written)
                assertEquals(0L, sink.status().dropped)
                assertFalse(sink.status().failed)
            } finally {
                releaseOpen.countDown()
                waiter.interrupt()
                waiter.join(5_000L)
            }
            assertFalse(waiter.isAlive)
            assertTrue(sink.sync())
            assertEquals(recordCount.toLong(), sink.status().written)
            assertEquals(0, sink.status().pending)
            assertEquals(0L, sink.status().dropped)
            assertFalse(sink.status().failed)
        }
    }


    @Test fun unopenableTargetReportsFixedFailureAndDropsRecords() {
        val dir = newDir()
        val blocker = File(dir, "blocker")
        blocker.writeText("blocking file, not a directory")
        val sink = startSink(File(blocker, File(FIELD_LOG_RELATIVE_PATH).name))

        sink.enqueue(record("PROCESS_START_OFF"))
        assertTrue(sink.sync())

        val status = sink.status()
        assertTrue(status.failed)
        assertEquals(FieldLogHealth.WRITE_FAILED, status.health)
        assertEquals(FieldLogFailureCode.OPEN_FAILED, status.failureCode)
        assertEquals(0L, status.written)
        assertEquals(1L, status.dropped)
        assertEquals("blocking file, not a directory", blocker.readText())
        // 실패 뒤 enqueue는 디스크 접근 없이 거부된다.
        assertFalse(sink.enqueue(record("PROCESS_START_OFF")))
        assertEquals(2L, sink.status().dropped)
    }

    @Test fun queueOverflowStopsBeforeWriterStarts() {
        val sink = FieldEventSink(newFile(), queueCapacity = 1)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertFalse(sink.enqueue(record("REAL_BLE_APPEARED")))

        val status = sink.status()
        assertEquals(FieldLogHealth.QUEUE_OVERFLOW, status.health)
        assertEquals(FieldLogFailureCode.QUEUE_OVERFLOW, status.failureCode)
        assertEquals(1L, status.dropped)
        // 대기 2까지 올린 뒤 offer 실패분을 즉시 되돌리므로 1이다. 음수나 중간 0이 남지 않는다.
        assertEquals(1, status.pending)
        assertFalse(sink.sync())
    }

    @Test fun queueOverflowBeforeStartDrainsAcceptedRecordsWhenWriterStarts() {
        val file = newFile()
        val sink = FieldEventSink(file, queueCapacity = 1)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertFalse(sink.enqueue(record("REAL_BLE_APPEARED")))
        sink.start()
        assertTrue(sink.sync())

        assertEquals(FieldLogHealth.QUEUE_OVERFLOW, sink.status().health)
        assertEquals(FieldLogFailureCode.QUEUE_OVERFLOW, sink.status().failureCode)
        assertEquals(0L, sink.status().written)
        assertEquals(2L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
        assertFalse(file.exists())
    }

    @Test fun localDropMarksPersistentFailureAndStopsRecording() {
        val file = newFile()
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.sync())
        assertEquals(0, sink.status().pending)

        sink.recordDroppedLocally()

        val status = sink.status()
        assertTrue(status.failed)
        assertEquals(FieldLogHealth.WRITE_FAILED, status.health)
        assertEquals(FieldLogFailureCode.WRITE_FAILED, status.failureCode)
        assertEquals(1L, status.dropped)
        assertEquals(1L, status.written)
        // 유실 확정 뒤에는 파일에 더 쓰지 않는다.
        assertFalse(sink.enqueue(record("UI_RESUMED")))
        assertEquals(2L, sink.status().dropped)
        assertEquals(1, file.readText().split('\n').dropLastWhile { it.isEmpty() }.size)
    }

    @Test fun codesOutsideAllowlistAreCountedAndNeverWritten() {
        val file = newFile()
        val sink = startSink(file)
        assertFalse(sink.enqueue(record("VIN_1HGCM82633A004352")))
        assertFalse(sink.enqueue(record("REAL_BLE_APPEARED_EXTRA")))
        assertFalse(sink.enqueue(record("")))
        assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
        assertTrue(sink.sync())

        val status = sink.status()
        assertEquals(3L, status.rejected)
        assertEquals(1L, status.written)
        val text = file.readText()
        assertFalse(text.contains("VIN"))
        assertFalse(text.contains("EXTRA"))
        assertEquals(1, text.split('\n').dropLastWhile { it.isEmpty() }.size)
    }

    @Test fun associationFailureCodeKeepsSignButNothingElse() {
        val file = newFile()
        val sink = startSink(file)
        // CompanionDeviceManager는 취소를 -1 같은 음수 코드로 넘길 수 있다. 이 code는 유실 없이 남아야 한다.
        assertTrue(sink.enqueue(record("ASSOCIATION_FAILED_CODE_-1")))
        assertFalse(sink.enqueue(record("ASSOCIATION_FAILED_CODE_")))
        assertFalse(sink.enqueue(record("ASSOCIATION_FAILED_CODE_12A")))
        assertFalse(sink.enqueue(record("ASSOCIATION_FAILED_CODE_1234567")))
        assertFalse(sink.enqueue(record("ASSOCIATION_FAILED_CODE_1HGCM82633A004352")))
        assertTrue(sink.sync())

        val status = sink.status()
        assertEquals(1L, status.written)
        assertEquals(4L, status.rejected)
        val text = file.readText()
        assertTrue(text.contains("\"event\":\"ASSOCIATION_FAILED_CODE_-1\""))
        assertFalse(text.contains("12A"))
        assertFalse(text.contains("1HGCM82633A004352"))
    }

    @Test fun stateValuesOutsideAllowlistBecomeNullAndUnknownKeysAreDropped() {
        val file = newFile()
        val sink = startSink(file)
        assertTrue(
            sink.enqueue(
                record(
                    "UI_RESUMED",
                    mapOf(
                        FieldStateKeys.PRESENT to true,
                        FieldStateKeys.ASSOCIATIONS to 2,
                        FieldStateKeys.SESSION_ACTIVE to false,
                        FieldStateKeys.STOP_REASON to "user@example.com",
                        "vin" to "1HGCM82633A004352",
                    ),
                ),
            ),
        )
        assertTrue(sink.sync())

        val text = file.readText()
        assertTrue(text.contains("\"present\":true"))
        assertTrue(text.contains("\"associations\":2"))
        assertTrue(text.contains("\"sessionActive\":false"))
        assertTrue(text.contains("\"stopReason\":null"))
        assertFalse(text.contains("user@example.com"))
        assertFalse(text.contains("1HGCM82633A004352"))
        assertFalse(text.contains("\"vin\""))
    }

    @Test fun uwbDistanceLogKeepsMetersAndClockFieldsButRejectsNonNumericAndNonFiniteValues() {
        val file = newFile()
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED", mapOf(
            FieldStateKeys.UWB_STAGE to "RANGING",
            FieldStateKeys.UWB_AZIMUTH_SUPPORTED to true,
            FieldStateKeys.UWB_ELEVATION_SUPPORTED to false,
            FieldStateKeys.UWB_LAST_PEER_INDEX to 1,
            FieldStateKeys.UWB_LAST_DISTANCE_METERS to 1.25,
            FieldStateKeys.UWB_LAST_CONFIDENCE to 1,
            FieldStateKeys.UWB_LAST_AZIMUTH_RAW to -540.0,
            FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE to 0,
            FieldStateKeys.UWB_LAST_ELEVATION_RAW to -0.25,
            FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE to 2,
            FieldStateKeys.UWB_LAST_FRAMEWORK_TIMESTAMP_MS to 9_876L,
            FieldStateKeys.UWB_LAST_RECEIVED_ELAPSED_MS to 10_000L,
        ))))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, "PRIVATE-MATERIAL")) {
            assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED", mapOf(
                FieldStateKeys.UWB_STAGE to "PRIVATE-MATERIAL",
                FieldStateKeys.UWB_LAST_DISTANCE_METERS to invalid,
                FieldStateKeys.UWB_LAST_PEER_INDEX to "PRIVATE-MATERIAL",
                FieldStateKeys.UWB_BACKEND_OPENED to "PRIVATE-MATERIAL",
                FieldStateKeys.UWB_REASON to "PRIVATE-MATERIAL",
                FieldStateKeys.UWB_OPEN_FAILED_REASON to "PRIVATE-MATERIAL",
                "sts" to "PRIVATE-MATERIAL",
            ))))
        }
        assertTrue(sink.sync())
        val lines = file.readLines()
        assertTrue(lines.first().contains("\"uwbAzimuthSupported\":true"))
        assertTrue(lines.first().contains("\"uwbElevationSupported\":false"))
        assertTrue(lines.first().contains("\"uwbLastPeerIndex\":1"))
        assertTrue(lines.first().contains("\"uwbLastDistanceMeters\":1.25"))
        assertTrue(lines.first().contains("\"uwbLastConfidence\":1"))
        assertTrue(lines.first().contains("\"uwbLastAzimuthRaw\":-540.0"))
        assertTrue(lines.first().contains("\"uwbLastAzimuthConfidence\":0"))
        assertTrue(lines.first().contains("\"uwbLastElevationRaw\":-0.25"))
        assertTrue(lines.first().contains("\"uwbLastElevationConfidence\":2"))
        assertTrue(lines.first().contains("\"uwbLastFrameworkTimestampMs\":9876"))
        assertTrue(lines.first().contains("\"uwbLastReceivedElapsedMs\":10000"))
        for (line in lines.drop(1)) {
            assertTrue(line.contains("\"uwbStage\":null"))
            assertTrue(line.contains("\"uwbLastDistanceMeters\":null"))
            assertTrue(line.contains("\"uwbLastPeerIndex\":null"))
            assertTrue(line.contains("\"uwbBackendOpened\":null"))
            assertTrue(line.contains("\"uwbReason\":null"))
            assertTrue(line.contains("\"uwbOpenFailedReason\":null"))
        }
        val text = lines.joinToString("\n")
        assertFalse(text.contains("PRIVATE-MATERIAL"))
        assertFalse(text.contains("NaN"))
        assertFalse(text.contains("Infinity"))
        assertFalse(text.contains("\"sts\""))
    }

    @Test fun uwbRawAnglesRemainFiniteSignedDoublesWithoutUnitConversionOrConfidenceFallback() {
        val file = newFile()
        val sink = startSink(file)
        val rawValues = listOf(-Double.MAX_VALUE, -540.0, -0.0, 0.0, Double.MIN_VALUE, Math.PI, Double.MAX_VALUE, null)
        for (raw in rawValues) {
            assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED", mapOf(
                FieldStateKeys.UWB_LAST_CONFIDENCE to 2,
                FieldStateKeys.UWB_LAST_AZIMUTH_RAW to raw,
                FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE to null,
                FieldStateKeys.UWB_LAST_ELEVATION_RAW to null,
                FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE to 0,
                FieldStateKeys.UWB_AZIMUTH_SUPPORTED to null,
                FieldStateKeys.UWB_ELEVATION_SUPPORTED to true,
            ))))
            assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED", mapOf(
                FieldStateKeys.UWB_LAST_CONFIDENCE to 0,
                FieldStateKeys.UWB_LAST_AZIMUTH_RAW to null,
                FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE to 2L,
                FieldStateKeys.UWB_LAST_ELEVATION_RAW to raw,
                FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE to null,
                FieldStateKeys.UWB_AZIMUTH_SUPPORTED to false,
                FieldStateKeys.UWB_ELEVATION_SUPPORTED to null,
            ))))
        }
        assertTrue(sink.sync())
        val lines = file.readLines()
        assertEquals(rawValues.size * 2, lines.size)
        for ((index, raw) in rawValues.withIndex()) {
            val azimuth = lines[index * 2]
            assertTrue(azimuth.contains("\"uwbLastAzimuthRaw\":$raw"))
            assertTrue(azimuth.contains("\"uwbLastAzimuthConfidence\":null"))
            assertTrue(azimuth.contains("\"uwbLastElevationRaw\":null"))
            assertTrue(azimuth.contains("\"uwbLastElevationConfidence\":0"))
            assertTrue(azimuth.contains("\"uwbAzimuthSupported\":null"))
            assertTrue(azimuth.contains("\"uwbElevationSupported\":true"))
            val elevation = lines[index * 2 + 1]
            assertTrue(elevation.contains("\"uwbLastAzimuthRaw\":null"))
            assertTrue(elevation.contains("\"uwbLastAzimuthConfidence\":2"))
            assertTrue(elevation.contains("\"uwbLastElevationRaw\":$raw"))
            assertTrue(elevation.contains("\"uwbLastElevationConfidence\":null"))
            assertTrue(elevation.contains("\"uwbAzimuthSupported\":false"))
            assertTrue(elevation.contains("\"uwbElevationSupported\":null"))
        }
    }

    @Test fun uwbRawAngleConfidenceAndCapabilityRejectWrongTypesAndPrivateMaterial() {
        val file = newFile()
        val sink = startSink(file)
        val privateObject = object {
            override fun toString() = "PRIVATE-MATERIAL"
        }
        val nonNumeric = listOf(null, true, false, "PRIVATE-MATERIAL", "RANGING",
            privateObject, listOf("PRIVATE-MATERIAL"), mapOf("private" to "PRIVATE-MATERIAL"))
        val cases = listOf(
            listOf(FieldStateKeys.UWB_LAST_AZIMUTH_RAW, FieldStateKeys.UWB_LAST_ELEVATION_RAW) to
                (nonNumeric + listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1, 1L, 1f)),
            listOf(FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE, FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE) to
                (nonNumeric + listOf(-1, 3, Long.MAX_VALUE, 1.0, 1f, Double.NaN, Double.POSITIVE_INFINITY)),
            listOf(FieldStateKeys.UWB_AZIMUTH_SUPPORTED, FieldStateKeys.UWB_ELEVATION_SUPPORTED) to
                listOf(null, 0, 1L, 1.0, "true", "PRIVATE-MATERIAL", privateObject,
                    listOf("PRIVATE-MATERIAL"), mapOf("private" to "PRIVATE-MATERIAL")),
        )
        val expectedKeys = mutableListOf<List<String>>()
        for ((keys, invalidValues) in cases) {
            for (invalid in invalidValues) {
                assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED",
                    keys.associateWith { invalid } + mapOf(
                        "vin" to "PRIVATE-MATERIAL", "mac" to "PRIVATE-MATERIAL",
                        "sts" to "PRIVATE-MATERIAL", "gps" to "PRIVATE-MATERIAL",
                        "uwbAngleUnit" to "PRIVATE-MATERIAL", "uwbDirection" to "PRIVATE-MATERIAL",
                    ))))
                expectedKeys += keys
            }
        }
        assertTrue(sink.sync())
        val lines = file.readLines()
        assertEquals(expectedKeys.size, lines.size)
        for ((index, keys) in expectedKeys.withIndex()) {
            for (key in keys) assertTrue(lines[index].contains("\"$key\":null"))
        }
        val text = lines.joinToString("\n")
        for (forbidden in listOf("PRIVATE-MATERIAL", "NaN", "Infinity", "\"vin\"", "\"mac\"",
            "\"sts\"", "\"gps\"", "\"uwbAngleUnit\"", "\"uwbDirection\"")) {
            assertFalse(text.contains(forbidden))
        }
    }

    @Test fun uwbAppendsWithoutChangingLegacyRowsOrCarryingAnglesIntoNextSample() {
        val file = newFile()
        val legacy = (1..24).joinToString("") { version ->
            FieldJson.encode(record("PROCESS_START_OFF").copy(version = version)) + "\n"
        }
        writeExisting(file, legacy)
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED", mapOf(
            FieldStateKeys.UWB_LAST_PEER_INDEX to 0,
            FieldStateKeys.UWB_LAST_AZIMUTH_RAW to -1.25,
            FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE to 1,
            FieldStateKeys.UWB_LAST_ELEVATION_RAW to 0.5,
            FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE to 2,
            FieldStateKeys.UWB_AZIMUTH_SUPPORTED to true,
            FieldStateKeys.UWB_ELEVATION_SUPPORTED to true,
        ))))
        assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED", mapOf(
            FieldStateKeys.UWB_LAST_PEER_INDEX to 1,
            FieldStateKeys.UWB_LAST_AZIMUTH_RAW to null,
            FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE to null,
            FieldStateKeys.UWB_LAST_ELEVATION_RAW to null,
            FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE to null,
            FieldStateKeys.UWB_AZIMUTH_SUPPORTED to null,
            FieldStateKeys.UWB_ELEVATION_SUPPORTED to null,
        ))))
        assertTrue(sink.enqueue(record("TESLA_UWB_STARTED")))
        assertTrue(sink.sync())
        val text = file.readText()
        assertEquals(legacy, text.substring(0, legacy.length))
        val current = text.substring(legacy.length).lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(3, current.size)
        assertTrue(current[0].contains("\"uwbLastAzimuthRaw\":-1.25"))
        assertTrue(current[0].contains("\"uwbLastElevationRaw\":0.5"))
        for (key in listOf(FieldStateKeys.UWB_LAST_AZIMUTH_RAW, FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE,
            FieldStateKeys.UWB_LAST_ELEVATION_RAW, FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE,
            FieldStateKeys.UWB_AZIMUTH_SUPPORTED, FieldStateKeys.UWB_ELEVATION_SUPPORTED)) {
            assertTrue(current[1].contains("\"$key\":null"))
            assertFalse(current[2].contains("\"$key\":"))
        }
    }

    @Test fun uwbGattSinkPreservesNativeStatusesAndNullableAdmissionWithoutPromotingCompletion() {
        val file = newFile()
        val sink = startSink(file)
        val cases = listOf(133 to false, 8 to true, 0 to null, -9876 to true)
        for ((status, accepted) in cases) {
            assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", mapOf(
                FieldStateKeys.UWB_GATT_STAGE to "CONNECTING",
                FieldStateKeys.UWB_GATT_EVENT to "CONNECTION_CALLBACK",
                FieldStateKeys.UWB_GATT_OPERATION_STARTED_ELAPSED_MS to 0L,
                FieldStateKeys.UWB_GATT_STAGE_STARTED_ELAPSED_MS to 1_000L,
                FieldStateKeys.UWB_GATT_OBSERVED_ELAPSED_MS to 4_321L,
                FieldStateKeys.UWB_GATT_CONNECT_ISSUED_ELAPSED_MS to 1_000L,
                FieldStateKeys.UWB_GATT_CONNECT_RETURNED_ELAPSED_MS to 1_001L,
                FieldStateKeys.UWB_GATT_ADVERTISEMENT_AGE_AT_CONNECT_MS to 0L,
                FieldStateKeys.UWB_GATT_CONNECTION_CALLBACK_ELAPSED_MS to 4_321L,
                FieldStateKeys.UWB_GATT_AUTO_CONNECT to false,
                FieldStateKeys.UWB_GATT_CONNECT_ACTION to if (status == 8) "RECONNECT" else "CREATE",
                FieldStateKeys.UWB_GATT_CONNECT_ACCEPTED to accepted,
                FieldStateKeys.UWB_GATT_CONNECTION_STATUS to status,
                FieldStateKeys.UWB_GATT_CONNECTION_STATE to 0,
                FieldStateKeys.UWB_GATT_DESCRIPTOR_REQUEST_STATUS to 201,
                FieldStateKeys.UWB_GATT_DESCRIPTOR_CALLBACK_STATUS to 0,
                FieldStateKeys.UWB_GATT_CONNECTED_OBSERVED to false,
                FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT to 0L,
            ))))
        }
        assertTrue(sink.enqueue(record("TESLA_UWB_STARTED",
            FieldStateKeys.UWB_GATT_KEYS.associateWith { null })))
        assertTrue(sink.sync())
        val rows = file.readLines()
        for ((index, case) in cases.withIndex()) {
            val row = rows[index]
            assertTrue(row.contains("\"uwbGattConnectionStatus\":${case.first}"))
            assertTrue(row.contains("\"uwbGattConnectAccepted\":${case.second}"))
            assertTrue(row.contains("\"uwbGattConnectionState\":0"))
            assertTrue(row.contains("\"uwbGattConnectedObserved\":false"))
            assertTrue(row.contains("\"uwbGattRxNotificationCount\":0"))
            assertTrue(row.contains("\"uwbGattOperationStartedElapsedMs\":0"))
            assertTrue(row.contains("\"uwbGattObservedElapsedMs\":4321"))
            assertTrue(row.contains("\"uwbGattDescriptorRequestStatus\":201"))
            assertTrue(row.contains("\"uwbGattDescriptorCallbackStatus\":0"))
        }
        for (key in FieldStateKeys.UWB_GATT_KEYS) assertTrue(rows.last().contains("\"$key\":null"))
    }

    @Test fun uwbGattSinkRejectsWrongTypesNegativeTimesFutureTimesAndOutOfRangeInts() {
        val file = newFile()
        val sink = startSink(file)
        val expectations = mutableListOf<List<String>>()
        val groups = listOf(
            FieldStateKeys.UWB_GATT_BOOLS.toList() to listOf<Any?>(null, 0, 1L, 1.0, "true", "PRIVATE-MATERIAL"),
            FieldStateKeys.UWB_GATT_INTS.toList() to listOf<Any?>(null, true, false, 1.0, "133",
                Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1, "PRIVATE-MATERIAL"),
            FieldStateKeys.UWB_GATT_TIMES.toList() to listOf<Any?>(null, -1L, 4_322L, Long.MAX_VALUE,
                true, 1.0, "1000", "PRIVATE-MATERIAL"),
            listOf(FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT) to
                listOf<Any?>(null, -1L, true, 0.0, "0", "PRIVATE-MATERIAL"),
            listOf(FieldStateKeys.UWB_GATT_STAGE, FieldStateKeys.UWB_GATT_EVENT,
                FieldStateKeys.UWB_GATT_CONNECT_ACTION) to
                listOf<Any?>(null, true, 0, "PRIVATE-MATERIAL", listOf("PRIVATE-MATERIAL")),
        )
        for ((keys, values) in groups) {
            for (value in values) {
                assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", keys.associateWith { value })))
                expectations += keys
            }
        }
        assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION",
            FieldStateKeys.UWB_GATT_TIMES.associateWith { 0L } +
                mapOf(FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT to Long.MAX_VALUE))))
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertEquals(expectations.size + 1, rows.size)
        for ((index, keys) in expectations.withIndex()) {
            for (key in keys) assertTrue(rows[index].contains("\"$key\":null"))
        }
        for (key in FieldStateKeys.UWB_GATT_TIMES) assertTrue(rows.last().contains("\"$key\":0"))
        assertTrue(rows.last().contains("\"uwbGattRxNotificationCount\":${Long.MAX_VALUE}"))
        assertFalse(file.readText().contains("PRIVATE-MATERIAL"))
    }

    @Test fun uwbGattEnumsAreExplicitAndLegacySchemasNeverGainGattObservations() {
        val file = newFile()
        val sink = startSink(file)
        val expected = mutableListOf<Pair<String, String>>()
        for ((key, values) in listOf(
            FieldStateKeys.UWB_GATT_STAGE to TeslaKeyStage.entries.map { it.name },
            FieldStateKeys.UWB_GATT_EVENT to TeslaUwbGattEvent.entries.map { it.name },
            FieldStateKeys.UWB_GATT_CONNECT_ACTION to listOf("CREATE", "RECONNECT"),
        )) {
            for (value in values) {
                assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", mapOf(key to value))))
                expected += key to value
            }
        }
        for (version in 1..24) {
            assertTrue(sink.enqueue(record("TESLA_UWB_CAPABILITIES_RECEIVED",
                FieldStateKeys.UWB_GATT_KEYS.associateWith { 0L }).copy(version = version)))
        }
        assertTrue(sink.sync())
        val rows = file.readLines()
        for ((index, pair) in expected.withIndex()) {
            assertTrue(rows[index].contains("\"${pair.first}\":\"${pair.second}\""))
        }
        for (row in rows.drop(expected.size)) {
            for (key in FieldStateKeys.UWB_GATT_KEYS) assertTrue(row.contains("\"$key\":null"))
        }
    }

    @Test fun schema26RxSinkPreservesTypedObservationsAndDoesNotCarryMetadata() {
        val file = newFile()
        val sink = startSink(file)
        val state = mapOf(
            FieldStateKeys.UWB_GATT_EVENT to "PROTOCOL",
            FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT to 12L,
            FieldStateKeys.UWB_RX_FRAME_COUNT to 3L,
            FieldStateKeys.UWB_RX_PROTOCOL_COUNT to 1L,
            FieldStateKeys.UWB_RX_NOTIFICATION_FRAME_COUNT to 0,
            FieldStateKeys.UWB_RX_DECODER_INCOMPLETE to false,
            FieldStateKeys.UWB_RX_DECODER_FAILED to false,
            FieldStateKeys.UWB_RX_PROTOCOL_RECEIVED_ELAPSED_MS to 1_000L,
            FieldStateKeys.UWB_RX_PROTOCOL_OBSERVED_ELAPSED_MS to 4_000L,
            FieldStateKeys.UWB_RX_STEP to "UWB_INPUT",
            FieldStateKeys.UWB_RX_INPUT_KIND to "RAW_FIRA",
            FieldStateKeys.UWB_RX_AEAD to "NOT_ATTEMPTED",
            FieldStateKeys.UWB_RX_FIRA_KIND to "REQUEST",
            FieldStateKeys.UWB_RX_DISPOSITION to "FIRA_REQUEST",
            FieldStateKeys.UWB_RX_EXPECTED_DOMAIN to 0,
            FieldStateKeys.UWB_RX_FROM_PRESENT to false,
            FieldStateKeys.UWB_RX_FROM_DOMAIN to Int.MIN_VALUE,
            FieldStateKeys.UWB_RX_TO_PRESENT to false,
            FieldStateKeys.UWB_RX_REQUEST_UUID_PRESENT to false,
            FieldStateKeys.UWB_RX_REQUEST_UUID_MATCHES to null,
            FieldStateKeys.UWB_RX_TO_ROUTE_MATCHES to null,
            FieldStateKeys.UWB_RX_SIGNED_STATUS_PRESENT to true,
            FieldStateKeys.UWB_RX_SIGNED_MESSAGE_FAULT to 0,
            FieldStateKeys.UWB_RX_OPERATION_STATUS to Int.MAX_VALUE,
            FieldStateKeys.UWB_LAST_AZIMUTH_RAW to -1.25,
        )
        assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", state).copy(version = 26)))
        assertTrue(sink.enqueue(record("TESLA_UWB_COMPLETE", state)))
        assertTrue(sink.enqueue(record("TESLA_UWB_STARTED",
            FieldStateKeys.UWB_RX_KEYS.associateWith { null } +
                mapOf(FieldStateKeys.UWB_RX_FRAME_COUNT to 0L, FieldStateKeys.UWB_RX_PROTOCOL_COUNT to 0L))))
        assertTrue(sink.sync())
        val rows = file.readLines()
        for (row in rows.take(2)) {
            assertTrue(row.contains("\"uwbGattEvent\":\"PROTOCOL\""))
            assertTrue(row.contains("\"uwbRxFrameCount\":3"))
            assertTrue(row.contains("\"uwbRxProtocolCount\":1"))
            assertTrue(row.contains("\"uwbRxNotificationFrameCount\":0"))
            assertTrue(row.contains("\"uwbRxDecoderIncomplete\":false"))
            assertTrue(row.contains("\"uwbRxFiraKind\":\"REQUEST\""))
            assertTrue(row.contains("\"uwbRxDisposition\":\"FIRA_REQUEST\""))
            assertTrue(row.contains("\"uwbRxSignedMessageFault\":0"))
            assertTrue(row.contains("\"uwbRxOperationStatus\":${Int.MAX_VALUE}"))
            assertTrue(row.contains("\"uwbRxFromDomain\":${Int.MIN_VALUE}"))
            assertTrue(row.contains("\"uwbRxRequestUuidMatches\":null"))
            assertTrue(row.contains("\"uwbLastAzimuthRaw\":-1.25"))
        }
        assertTrue(rows.last().contains("\"uwbRxFrameCount\":0"))
        assertTrue(rows.last().contains("\"uwbRxProtocolCount\":0"))
        for (key in FieldStateKeys.UWB_RX_KEYS - FieldStateKeys.UWB_RX_COUNTS) {
            assertTrue(rows.last().contains("\"$key\":null"))
        }
    }

    @Test fun schema26RxAllowlistRejectsSecretsNonFiniteNumbersAndBooleanNumbers() {
        val file = newFile()
        val sink = startSink(file)
        val secret = "PRIVATE-RAW-KEY-STS-VIN-MAC-UUID"
        val groups = listOf(
            FieldStateKeys.UWB_RX_BOOLS.toList() to listOf<Any?>(0, 1L, 0.0, "false", secret),
            FieldStateKeys.UWB_RX_INTS.toList() to listOf<Any?>(true, false, 0.0, "0",
                Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1, Double.NaN, Double.POSITIVE_INFINITY, secret),
            FieldStateKeys.UWB_RX_COUNTS.toList() to listOf<Any?>(true, false, -1L, 0.0, "0",
                Double.NaN, Double.NEGATIVE_INFINITY, secret),
            FieldStateKeys.UWB_RX_TIMES.toList() to listOf<Any?>(true, false, -1L, 4_322L, Long.MAX_VALUE,
                0.0, "0", Double.NaN, Double.POSITIVE_INFINITY, secret),
            listOf(FieldStateKeys.UWB_RX_NOTIFICATION_FRAME_COUNT) to
                listOf<Any?>(-1, 17, true, false, 0.0, "0", Double.NaN, Double.POSITIVE_INFINITY, secret),
            FieldStateKeys.UWB_RX_ENUMS.keys.toList() to listOf<Any?>(true, 0, secret, listOf(secret)),
        )
        val expected = mutableListOf<List<String>>()
        for ((keys, values) in groups) {
            for (value in values) {
                val state = keys.associateWith { value } + mapOf(
                    "rawPayload" to secret, "key" to secret, "sts" to secret,
                    "vin" to secret, "mac" to secret, "uuid" to secret)
                assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", state)))
                expected += keys
            }
        }
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertEquals(expected.size, rows.size)
        for ((index, keys) in expected.withIndex()) {
            for (key in keys) assertTrue(rows[index].contains("\"$key\":null"))
        }
        assertFalse(file.readText().contains(secret))
    }

    @Test fun schema26RxVocabularyBoundsAndLegacyNullAreExplicit() {
        val file = newFile()
        val sink = startSink(file)
        val expected = mutableListOf<Pair<String, String>>()
        for ((key, values) in FieldStateKeys.UWB_RX_ENUMS) {
            for (value in values) {
                assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", mapOf(key to value))))
                expected += key to "\"$value\""
            }
        }
        for ((key, value) in FieldStateKeys.UWB_RX_BOOLS.associateWith { false } +
            FieldStateKeys.UWB_RX_INTS.associateWith { Int.MIN_VALUE } +
            FieldStateKeys.UWB_RX_COUNTS.associateWith { Long.MAX_VALUE } +
            FieldStateKeys.UWB_RX_TIMES.associateWith { 0L } +
            mapOf(FieldStateKeys.UWB_RX_NOTIFICATION_FRAME_COUNT to 16)) {
            assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", mapOf(key to value))))
            expected += key to value.toString()
        }
        for (version in 1..25) {
            assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION",
                FieldStateKeys.UWB_RX_KEYS.associateWith { 0 } +
                    mapOf(FieldStateKeys.UWB_GATT_EVENT to "PROTOCOL")).copy(version = version)))
        }
        assertTrue(sink.sync())
        val rows = file.readLines()
        for ((index, pair) in expected.withIndex()) {
            assertTrue(rows[index].contains("\"${pair.first}\":${pair.second}"))
        }
        for (row in rows.drop(expected.size)) {
            for (key in FieldStateKeys.UWB_RX_KEYS) assertTrue(row.contains("\"$key\":null"))
            assertTrue(row.contains("\"uwbGattEvent\":null"))
        }
    }

    @Test fun schema26FullRxRowsUseActualUtf8SizeForExistingRotationBudget() {
        val file = newFile()
        val state = FieldStateKeys.UWB_RX_ENUMS.mapValues { (_, values) -> values.maxBy { it.length } } +
            FieldStateKeys.UWB_RX_BOOLS.associateWith { false } +
            FieldStateKeys.UWB_RX_INTS.associateWith { Int.MIN_VALUE } +
            FieldStateKeys.UWB_RX_COUNTS.associateWith { Long.MAX_VALUE } +
            FieldStateKeys.UWB_RX_TIMES.associateWith { 4_321L } +
            FieldStateKeys.UWB_GATT_TIMES.associateWith { 4_321L } +
            FieldStateKeys.UWB_GATT_BOOLS.associateWith { false } +
            FieldStateKeys.UWB_GATT_INTS.associateWith { Int.MIN_VALUE } +
            mapOf(FieldStateKeys.UWB_RX_NOTIFICATION_FRAME_COUNT to 16,
                FieldStateKeys.UWB_GATT_EVENT to "PROTOCOL",
                FieldStateKeys.UWB_GATT_STAGE to "NEGOTIATING_MTU",
                FieldStateKeys.UWB_GATT_CONNECT_ACTION to "RECONNECT",
                FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT to Long.MAX_VALUE,
                FieldStateKeys.UWB_LAST_AZIMUTH_RAW to -Double.MAX_VALUE,
                FieldStateKeys.UWB_LAST_ELEVATION_RAW to Double.MAX_VALUE)
        val records = (0 until 32).map { index ->
            record("TESLA_UWB_GATT_OBSERVATION", state).copy(wallTimeMs = 1_700_000_000_000L + index)
        }
        val actualMaxLineBytes = records.maxOf { (FieldJson.encode(it) + "\n").toByteArray(Charsets.UTF_8).size }.toLong()
        assertTrue(actualMaxLineBytes <= FIELD_LOG_LIMIT_BYTES / 4)
        val limitBytes = actualMaxLineBytes * 8
        val sink = startSink(file, limitBytes = limitBytes)
        for (row in records) {
            assertTrue(sink.enqueue(row))
            assertTrue(sink.sync())
            assertBoundedRetention(file, sink, limitBytes)
        }
        val retained = retainedRows(file)
        assertEquals(records.takeLast(retained.size).map { it.wallTimeMs }, retained.map(::wallTimeOf))
        assertEquals(0L, sink.status().dropped)
        assertEquals(0L, sink.status().rejected)
    }

    @Test fun schema27BodySinkDistinguishesEmptyFromUnobservedAndDoesNotCarryMetadata() {
        val file = newFile()
        val sink = startSink(file)
        val unobserved = FieldStateKeys.UWB_RX_SUMMARY_KEYS.associateWith { null }
        val empty = bodyState(bytes = 0, fields = "") +
            mapOf(FieldStateKeys.UWB_RX_REQUEST_CORRELATION to "CURRENT")
        assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", empty)))
        assertTrue(sink.enqueue(record("TESLA_UWB_COMPLETE", empty)))
        assertTrue(sink.enqueue(record("TESLA_UWB_STARTED", unobserved)))
        assertTrue(sink.sync())
        val rows = file.readLines()
        for (row in rows.take(2)) {
            assertTrue(row.contains("\"uwbRxBodyBytes\":0"))
            assertTrue(row.contains("\"uwbRxBodyStatus\":\"COMPLETE\""))
            assertTrue(row.contains("\"uwbRxBodyFields\":\"\""))
            assertTrue(row.contains("\"uwbRxRequestCorrelation\":\"CURRENT\""))
        }
        for (key in FieldStateKeys.UWB_RX_SUMMARY_KEYS) {
            assertTrue(rows.last().contains("\"$key\":null"))
        }
        assertEquals(FieldLogHealth.OK, sink.status().health)
        assertEquals(0L, sink.status().dropped)
    }

    @Test fun schema27BodyVocabularyAndCanonicalWireLengthsArePreserved() {
        val shapes = listOf(
            "1/0/1", "1/0/10", "1/1/8", "1/2/0", "1/2/127",
            "1/2/128", "1/2/4093", "1/5/4", "536870911/0/1",
            "1/0/1,2/1/8,3/2/0,4/5/4",
        )
        for (status in listOf("COMPLETE", "MALFORMED", "UNSUPPORTED")) {
            for (fields in shapes) {
                if (status != "COMPLETE" && fields == "1/2/4093") continue // 남은 tag 한 바이트도 들어갈 수 없다.
                val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                    bodyState(bytes = 4096L, status = status, fields = fields)))
                assertTrue(row.contains("\"uwbRxBodyBytes\":4096"))
                assertTrue(row.contains("\"uwbRxBodyStatus\":\"$status\""))
                assertTrue(row.contains("\"uwbRxBodyFields\":\"$fields\""))
            }
        }
        for (correlation in listOf("CURRENT", "PREVIOUS", "UNMATCHED")) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                mapOf(FieldStateKeys.UWB_RX_REQUEST_CORRELATION to correlation)))
            assertTrue(row.contains("\"uwbRxRequestCorrelation\":\"$correlation\""))
        }
    }

    @Test fun schema27BodyRejectsNonCanonicalNumericTriplesAsAnAtomicGroup() {
        val malformed = listOf(
            "secret-marker", "TESLA_UWB_STARTED", "1/0", "1/0/1/2", "1//1",
            "/0/1", "1/0/", ",1/0/1", "1/0/1,", "1/0/1,,2/0/1",
            "1/0/1 2/0/1", " 1/0/1", "1/0/1 ", "1/0/1\n", "1/0/1\u0000",
            "１/0/1", "1/٠/1", "1/0/１", "+1/0/1", "1/+0/1", "1/0/+1",
            "-1/0/1", "1/0/-1", "01/0/1", "1/00/1", "1/0/01", "1/2/00",
            "0/0/1", "536870912/0/1", "999999999999999999/0/1",
            "1/3/0", "1/4/0", "1/6/0", "1/7/0", "1/9/0",
            "1/0/0", "1/0/11", "1/1/7", "1/1/9", "1/5/3", "1/5/5",
            "1/2/4097", "1/2/999999999999999999",
            List(9) { "1/0/1" }.joinToString(","),
            List(8) { "536870911/2/4096" }.joinToString(",") + "0",
        )
        for (fields in malformed) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                bodyState(bytes = 4096, fields = fields) +
                    mapOf(FieldStateKeys.UWB_RX_REQUEST_CORRELATION to "PREVIOUS")))
            assertBodySummaryNull(row)
            assertTrue(row.contains("\"uwbRxRequestCorrelation\":\"PREVIOUS\""))
        }
    }

    @Test fun schema27BodyRejectsPartialWrongTypesAndUnlistedStatusWithoutStringFallback() {
        val complete = bodyState()
        val invalid = mutableListOf<Map<String, Any?>>()
        for (key in FieldStateKeys.UWB_RX_BODY_KEYS) {
            invalid += complete - key
            invalid += complete + (key to null)
            for (value in listOf<Any?>(true, false, 1.0, listOf(1), "TESLA_UWB_STARTED")) {
                invalid += complete + (key to value)
            }
        }
        for (bytes in listOf<Any?>(-1, 4097, Long.MAX_VALUE, "2")) {
            invalid += bodyState(bytes = bytes)
        }
        for (status in listOf("complete", "CURRENT", "COMPLETE ", "")) {
            invalid += bodyState(status = status)
        }
        for (state in invalid) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION", state))
            for (key in FieldStateKeys.UWB_RX_BODY_KEYS.intersect(state.keys)) {
                assertTrue(row.contains("\"$key\":null"))
            }
        }
        for (value in listOf<Any?>(true, 0, "TESLA_UWB_STARTED", "current", "CURRENT ", listOf("CURRENT"))) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                complete + (FieldStateKeys.UWB_RX_REQUEST_CORRELATION to value)))
            assertTrue(row.contains("\"uwbRxRequestCorrelation\":null"))
            assertTrue(row.contains("\"uwbRxBodyFields\":\"1/0/1\""))
        }
    }

    @Test fun schema27BodyMinimumStructureFitsByteBudgetWithoutClaimingExactEncodingLength() {
        for ((fields, minimum) in listOf(
            "1/0/1" to 2, "16/0/1" to 3, "536870911/0/1" to 6,
            "1/1/8" to 9, "1/2/0" to 2, "1/2/127" to 129,
            "1/2/128" to 131, "1/5/4" to 5,
            "1/0/1,2/2/0" to 4,
        )) {
            for (status in listOf("COMPLETE", "MALFORMED", "UNSUPPORTED")) {
                val short = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                    bodyState(bytes = minimum - 1, status = status, fields = fields)))
                assertBodySummaryNull(short)
                if (status != "COMPLETE") {
                    assertBodySummaryNull(FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                        bodyState(bytes = minimum, status = status, fields = fields))))
                }
                for (bytes in if (status == "COMPLETE") listOf(minimum, minimum + 1) else listOf(minimum + 1)) {
                    val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                        bodyState(bytes = bytes, status = status, fields = fields)))
                    assertTrue(row.contains("\"uwbRxBodyFields\":\"$fields\""))
                }
            }
        }
        assertBodySummaryNull(FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
            bodyState(bytes = 1, fields = ""))))
        for (fields in listOf("1/2/4094", "1/2/4096")) {
            assertBodySummaryNull(FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                bodyState(bytes = 4096, fields = fields))))
        }
    }

    @Test fun schema27BodyStatusRejectsImpossibleCompletionAndBudgetCombinations() {
        val seven = List(7) { "1/0/1" }.joinToString(",")
        val eight = List(8) { "1/0/1" }.joinToString(",")
        val valid = listOf(
            Triple("TRUNCATED", eight, 17),
            Triple("MALFORMED", seven, 15),
            Triple("UNSUPPORTED", seven, 15),
            Triple("MALFORMED", "", 1),
            Triple("UNSUPPORTED", "", 1),
            Triple("COMPLETE", eight, 16),
            Triple("COMPLETE", eight, 17), // 非canonical tag encoding의 정확한 길이는 주장하지 않는다.
        )
        for ((status, fields, bytes) in valid) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                bodyState(bytes = bytes, status = status, fields = fields)))
            assertTrue(row.contains("\"uwbRxBodyStatus\":\"$status\""))
            assertTrue(row.contains("\"uwbRxBodyFields\":\"$fields\""))
        }
        val invalid = listOf(
            Triple("TRUNCATED", seven, 17),
            Triple("TRUNCATED", eight, 16),
            Triple("TRUNCATED", "", 1),
            Triple("TRUNCATED", "1/0/1", 3),
            Triple("MALFORMED", eight, 17),
            Triple("UNSUPPORTED", eight, 17),
            Triple("MALFORMED", seven, 14),
            Triple("UNSUPPORTED", seven, 14),
            Triple("MALFORMED", "", 0),
            Triple("UNSUPPORTED", "", 0),
            Triple("TRUNCATED", "", 0),
        )
        for ((status, fields, bytes) in invalid) {
            assertBodySummaryNull(FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                bodyState(bytes = bytes, status = status, fields = fields))))
        }
    }

    @Test fun schema27MaximumBoundedShapePersistsButOverBudgetShapeDoesNot() {
        // 8개, 최대 field 번호. body 상한 안에서 3개 네자리+5개 세자리 payload 길이를 보존한다.
        val fields = (List(3) { "536870911/2/1000" } + List(5) { "536870911/2/100" }).joinToString(",")
        val file = newFile()
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION",
            bodyState(bytes = 4096, fields = fields))))
        // 문법상 135자라도 구조 최소길이가 4096을 넘으면 전체 요약을 제거한다.
        val overBudget = List(8) { "536870911/2/4096" }.joinToString(",")
        assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION",
            bodyState(bytes = 4096, fields = overBudget))))
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertTrue(rows.first().contains("\"uwbRxBodyFields\":\"$fields\""))
        assertBodySummaryNull(rows.last())
        assertFalse(rows.last().contains(overBudget))
        assertEquals(FieldLogHealth.OK, sink.status().health)
        assertEquals(0L, sink.status().dropped)
    }

    @Test fun legacySchemasIgnoreAllSchema27KeysWhileSchema26RxStaysAvailable() {
        val state = bodyState() + mapOf(
            FieldStateKeys.UWB_RX_REQUEST_CORRELATION to "CURRENT",
            FieldStateKeys.UWB_RX_FRAME_COUNT to 1L,
            FieldStateKeys.UWB_RX_FIRA_KIND to "UNRECOGNIZED",
        )
        for (version in 1..26) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION", state).copy(version = version))
            for (key in FieldStateKeys.UWB_RX_SUMMARY_KEYS) assertTrue(row.contains("\"$key\":null"))
            if (version == 26) {
                assertTrue(row.contains("\"uwbRxFrameCount\":1"))
                assertTrue(row.contains("\"uwbRxFiraKind\":\"UNRECOGNIZED\""))
            }
        }
    }

    @Test fun schema27SinkNeverPersistsRawSecretMarkersOrArbitraryStringFallbacks() {
        val file = newFile()
        val sink = startSink(file)
        val secret = "RAW-SECRET-KEY-STS-VIN-MAC-UUID-BODY"
        for (key in FieldStateKeys.UWB_RX_SUMMARY_KEYS) {
            assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION",
                bodyState() + mapOf(key to secret, "rawBody" to secret, "payload" to secret))))
        }
        assertTrue(sink.sync())
        assertFalse(file.readText().contains(secret))
        assertEquals(4L, sink.status().written)
        assertEquals(0L, sink.status().rejected)
        assertEquals(FieldLogHealth.OK, sink.status().health)
    }

    @Test fun advertisedNameFilterOnlyPersistsBooleansAndDropsIdentifierMaterial() {
        val file = newFile()
        val sink = startSink(file)
        val identifiers = listOf(
            "S0123456789abcdefC",
            "0123456789abcdef0123456789abcdef01234567",
            "SYNTHETIC-VIN-MATERIAL",
            "02:00:00:00:00:01",
            "SYNTHETIC-NEW-STRING",
        )
        val invalid: List<Any?> = identifiers + listOf("BLE_FIELD_RUNNING", 1, 1L, null)
        for (value in listOf<Any?>(false, true) + invalid) {
            assertTrue(
                sink.enqueue(
                    record(
                        "BLE_FIELD_RUNNING",
                        mapOf(
                            FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER to value,
                            "advertisedName" to identifiers[0],
                            "hash" to identifiers[1],
                            "vin" to identifiers[2],
                            "address" to identifiers[3],
                            "peerKey" to identifiers[4],
                        ),
                    ),
                ),
            )
        }
        assertTrue(sink.sync())
        val text = file.readText()
        val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(2 + invalid.size, lines.size)
        val key = "\"${FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER}\":"
        assertTrue(lines[0].contains("${key}false"))
        assertTrue(lines[1].contains("${key}true"))
        for (line in lines.drop(2)) assertTrue(line.contains("${key}null"))
        for (identifier in identifiers) assertFalse(text.contains(identifier))
        for (unknownKey in listOf("advertisedName", "hash", "vin", "address", "peerKey")) {
            assertFalse(text.contains("\"$unknownKey\":"))
        }
    }

    @Test fun advertisedNameOptionDoesNotPersistAcrossRowsOrReopenAndLegacyRowsStayIntact() {
        val file = newFile()
        val legacy = (1..6).joinToString("") { version ->
            FieldJson.encode(record("BLE_FIELD_START_REQUESTED").copy(version = version)) + "\n"
        }
        writeExisting(file, legacy)
        val first = startSink(file)
        assertTrue(
            first.enqueue(
                record("BLE_FIELD_START_REQUESTED", mapOf(FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER to true)),
            ),
        )
        assertTrue(first.enqueue(record("BLE_FIELD_STOPPED")))
        assertTrue(first.sync())
        val second = startSink(file)
        assertTrue(
            second.enqueue(
                record("BLE_FIELD_START_REQUESTED", mapOf(FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER to false)),
            ),
        )
        assertTrue(second.enqueue(record("BLE_FIELD_STOPPED")))
        assertTrue(second.sync())
        val text = file.readText()
        assertEquals(legacy, text.substring(0, legacy.length))
        val current = text.substring(legacy.length).lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(4, current.size)
        val key = "\"${FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER}\":"
        assertTrue(current[0].contains("${key}true"))
        assertFalse(current[1].contains(key))
        assertTrue(current[2].contains("${key}false"))
        assertFalse(current[3].contains(key))
    }

    @Test fun scanEvidenceKeepsUnknownContextNullAndClampsNegativeTimingAndRetryValues() {
        val file = newFile()
        val sink = startSink(file)
        val delayed = BleEventEvidence(BleEvidenceKind.SCAN_TIMER_DELAYED,
            scheduledElapsedMs = -1, timerDelayMs = Long.MIN_VALUE, retryCount = -1)
        val recovery = BleEventEvidence(BleEvidenceKind.SCAN_RECOVERY_SCHEDULED,
            scanFailureCode = 5, scheduledElapsedMs = 240_001, retryCount = 2,
            interactive = false, deviceLocked = true)
        assertTrue(sink.enqueue(record("BLE_EVIDENCE").copy(bleEvidence = delayed)))
        assertTrue(sink.enqueue(record("BLE_EVIDENCE").copy(bleEvidence = recovery)))
        assertTrue(sink.enqueue(record("BLE_EVIDENCE")))
        assertTrue(sink.sync())
        val rows = file.readText().lineSequence().filter { it.isNotEmpty() }.toList()
        assertTrue(rows[0].contains("\"scheduledElapsedMs\":0"))
        assertTrue(rows[0].contains("\"timerDelayMs\":0"))
        assertTrue(rows[0].contains("\"retryCount\":0"))
        assertTrue(rows[0].contains("\"interactive\":null"))
        assertTrue(rows[0].contains("\"deviceLocked\":null"))
        assertTrue(rows[1].contains("\"scanFailureCode\":5"))
        assertTrue(rows[1].contains("\"scheduledElapsedMs\":240001"))
        assertTrue(rows[1].contains("\"retryCount\":2"))
        assertTrue(rows[1].contains("\"interactive\":false"))
        assertTrue(rows[1].contains("\"deviceLocked\":true"))
        assertTrue(rows[2].contains("\"bleEvidence\":null"))
    }

    @Test fun encodedRecordStaysOnOneLine() {
        val file = newFile()
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("REAL_BLE_APPEARED").copy(trialId = "id\"with\\escape\nand newline")))
        assertTrue(sink.sync())

        val text = file.readText()
        assertEquals(1, text.count { it == '\n' })
        assertFalse(text.contains('\r'))
        assertTrue(text.contains("id\\\"with\\\\escape\\u000aand newline"))
        assertEquals(1, text.split('\n').dropLastWhile { it.isEmpty() }.size)
    }

    @Test fun statusCallbackObservesConfirmedRecord() {
        val sink = startSink(newFile())
        var lastSeen: FieldLogStatus? = null
        sink.onStatusChanged = { lastSeen = sink.status() }

        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.sync())

        val observed = lastSeen
        assertNotNull(observed)
        assertEquals(1L, observed!!.written)
        assertEquals(0, observed.pending)
    }

    @Test
    fun trialSummariesStayOnTheirOwnRowsAcrossReopen() {
        val file = newFile()
        val first = startSink(file)
        assertTrue(first.enqueue(record("SPEECH_TRIAL_START_TTS_PCM")))
        assertTrue(
            first.enqueue(
                record(
                    "SPEECH_TRIAL_FINAL_RECEIVED_TTS_PCM",
                    speechTrial = summary(
                        mode = "TTS_PCM",
                        status = "COMPLETE",
                        reason = "FINAL_RECEIVED",
                        finalReceived = true,
                        phraseMatched = true,
                        confidence = 0.5f,
                        elapsedMs = 1_200L,
                        samples = 48_000L,
                        pcmBytesWritten = 96_000,
                        audioLeaseRetained = false,
                        decision = SpeechCommandDecision.FRUNK_OPEN_CANDIDATE,
                    ),
                ),
            ),
        )
        assertTrue(first.sync())

        // 프로세스 재시작: 앞선 행을 고치지 않고 이어 쓰며, 두 번째 회차는 자기 값만 남긴다.
        val second = startSink(file)
        assertTrue(second.enqueue(record("SPEECH_TRIAL_START_SILENT_PCM")))
        assertTrue(
            second.enqueue(
                record(
                    "SPEECH_TRIAL_RECOGNITION_TIMEOUT_SILENT_PCM",
                    speechTrial = summary(
                        mode = "SILENT_PCM",
                        status = "TIMED_OUT",
                        reason = "RECOGNITION_TIMEOUT",
                        finalReceived = false,
                        phraseMatched = null,
                        confidence = null,
                        elapsedMs = 10_000L,
                        samples = 0L,
                        pcmBytesWritten = 0,
                        audioLeaseRetained = true,
                    ),
                ),
            ),
        )
        assertTrue(second.sync())

        val lines = file.readText().split('\n').dropLastWhile { it.isEmpty() }
        assertEquals(4, lines.size)
        // 시작 행에는 요약이 붙지 않는다.
        assertEquals("null", trialValue(lines[0]))
        assertEquals("null", trialValue(lines[2]))

        val firstTrial = trialValue(lines[1])
        assertTrue(firstTrial.contains("\"mode\":\"TTS_PCM\""))
        assertTrue(firstTrial.contains("\"status\":\"COMPLETE\""))
        assertTrue(firstTrial.contains("\"reason\":\"FINAL_RECEIVED\""))
        assertTrue(firstTrial.contains("\"finalReceived\":true"))
        assertTrue(firstTrial.contains("\"phraseMatched\":true"))
        assertTrue(firstTrial.contains("\"confidence\":0.5"))
        assertTrue(firstTrial.contains("\"elapsedMs\":1200"))
        assertTrue(firstTrial.contains("\"samples\":48000"))
        assertTrue(firstTrial.contains("\"pcmBytesWritten\":96000"))
        assertTrue(firstTrial.contains("\"audioLeaseRetained\":false"))
        assertTrue(firstTrial.contains("\"commandDecision\":\"FRUNK_OPEN_CANDIDATE\""))
        // 회차 추적: 요약의 모드·사유가 그 행의 이벤트 code와 같은 값이어야 한다.
        assertTrue(lines[1].contains("\"event\":\"SPEECH_TRIAL_FINAL_RECEIVED_TTS_PCM\""))

        val secondTrial = trialValue(lines[3])
        assertTrue(secondTrial.contains("\"mode\":\"SILENT_PCM\""))
        assertTrue(secondTrial.contains("\"status\":\"TIMED_OUT\""))
        assertTrue(secondTrial.contains("\"reason\":\"RECOGNITION_TIMEOUT\""))
        assertTrue(secondTrial.contains("\"finalReceived\":false"))
        assertTrue(secondTrial.contains("\"phraseMatched\":null"))
        assertTrue(secondTrial.contains("\"confidence\":null"))
        assertTrue(secondTrial.contains("\"elapsedMs\":10000"))
        assertTrue(secondTrial.contains("\"samples\":0"))
        assertTrue(secondTrial.contains("\"pcmBytesWritten\":0"))
        assertTrue(secondTrial.contains("\"audioLeaseRetained\":true"))
        // 실패·취소·시간 초과 회차에는 판정이 없고, 앞 회차 성공 판정이 이어지지 않는다.
        assertTrue(secondTrial.contains("\"commandDecision\":null"))
        assertFalse(secondTrial.contains("FRUNK_OPEN_CANDIDATE"))

        // 회차 값이 서로 섞이지 않는다.
        assertFalse(firstTrial.contains("RECOGNITION_TIMEOUT"))
        assertFalse(firstTrial.contains("10000"))
        assertFalse(secondTrial.contains("FINAL_RECEIVED"))
        assertFalse(secondTrial.contains("48000"))
    }

    @Test
    fun trialConfidenceOutsideUnitRangeBecomesNull() {
        val file = newFile()
        val sink = startSink(file)
        // 유효 경계 0.0·1.0은 그대로 남기고 범위 밖·비유한 점수만 버린다.
        val valid = listOf(0f, 1f)
        val invalid = listOf(-0.1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)
        for (confidence in valid + invalid) {
            assertTrue(
                sink.enqueue(
                    record("SPEECH_TRIAL_FINAL_RECEIVED_TTS_PCM", speechTrial = summary(confidence = confidence)),
                ),
            )
        }
        assertTrue(sink.sync())

        val lines = file.readText().split('\n').dropLastWhile { it.isEmpty() }
        assertEquals(valid.size + invalid.size, lines.size)
        assertEquals("0.0", confidenceOf(lines[0]))
        assertEquals("1.0", confidenceOf(lines[1]))
        for (index in valid.size until lines.size) {
            assertEquals("null", confidenceOf(lines[index]))
            // 점수만 버리고 그 회차 행과 나머지 수치는 남는다.
            assertTrue(trialValue(lines[index]).contains("\"samples\":12000"))
        }
        val text = file.readText()
        assertFalse(text.contains("NaN"))
        assertFalse(text.contains("Infinity"))
        assertFalse(text.contains("\"confidence\":2"))
    }

    @Test
    fun trialSummaryStringsOutsideAllowlistBecomeNullAndRowStays() {
        val file = newFile()
        val sink = startSink(file)
        assertTrue(
            sink.enqueue(
                record(
                    "SPEECH_TRIAL_INTERNAL_FAILURE_DIRECT_MIC",
                    speechTrial = summary(
                        mode = "VIN_1HGCM82633A004352",
                        status = "enabled",
                        reason = "RAW_TEXT_1HGCM82633A004352",
                        phraseMatched = null,
                        confidence = null,
                        elapsedMs = 0L,
                        samples = 0L,
                        pcmBytesWritten = 0,
                    ),
                ),
            ),
        )
        assertTrue(sink.sync())

        val text = file.readText()
        val trial = trialValue(text.split('\n').first { it.isNotEmpty() })
        assertTrue(trial.contains("\"mode\":null"))
        assertTrue(trial.contains("\"status\":null"))
        assertTrue(trial.contains("\"reason\":null"))
        // 모르는 값은 성공값으로 채우지 않는다.
        assertTrue(trial.contains("\"confidence\":null"))
        assertTrue(trial.contains("\"phraseMatched\":null"))
        assertTrue(trial.contains("\"elapsedMs\":0"))
        // allowlist 밖 원문은 값으로도 남지 않는다.
        assertFalse(text.contains("1HGCM82633A004352"))
        assertFalse(text.contains("RAW_TEXT"))
        assertFalse(text.contains("enabled"))
    }

    @Test fun approachSnapshotsRotateWithinExistingBudgetWithoutLeakingIntoNextEvent() {
        val file = newFile()
        val legacy = "{\"version\":8,\"event\":\"PROCESS_START_OFF\",\"state\":{}}\n"
        writeExisting(file, legacy)
        val events = listOf(
            "TESLA_APPROACH_READ_STARTED", "TESLA_APPROACH_READ_STAGE",
            "TESLA_APPROACH_BODY_VERIFIED", "TESLA_APPROACH_DRIVE_VERIFIED",
            "TESLA_APPROACH_READ_FINISHED", "TESLA_APPROACH_READ_BLOCKED",
            "TESLA_APPROACH_READ_CLEANUP_FAILED",
        )
        val expected = (0 until 40).map { index ->
            record(events[index % events.size], state = mapOf(FieldStateKeys.BLE_FIELD_AUTHENTICATED_READ to true)).copy(
                wallTimeMs = 1_700_000_000_000L + index,
                teslaApproachRead = TeslaApproachReadSummary(
                    candidate = index + 1L, attempt = index + 1L,
                    stage = "FAILED", query = "DRIVE_STATE", outcome = "PARTIAL",
                    reason = "DRIVE_READ_FAILED", bodyVerified = true,
                    bodyReceivedElapsedMs = 100L, localClosed = true,
                ),
            )
        }
        // 스키마 확장에도 한 행은 파일에 들어가고 여러 번 회전하도록 용량 경계를 정한다.
        val limitBytes = expected.maxOf { (FieldJson.encode(it) + "\n").toByteArray(Charsets.UTF_8).size }.toLong() * 8
        val sink = startSink(file, limitBytes = limitBytes)
        assertTrue(sink.enqueue(expected.first()))
        assertTrue(sink.sync())
        assertTrue(file.readText().startsWith(legacy))
        for (row in expected.drop(1)) {
            assertTrue(sink.enqueue(row))
            assertTrue(sink.sync())
            assertBoundedRetention(file, sink, limitBytes)
        }
        val retained = retainedRows(file)
        assertEquals(expected.takeLast(retained.size).map { it.wallTimeMs }, retained.map(::wallTimeOf))
        assertEquals(expected.takeLast(retained.size).map { it.event }, retained.map(::eventOf))
        assertTrue(retained.all { it.contains("\"teslaApproachRead\":{") })
        assertTrue(retained.all { it.contains("\"bleFieldAuthenticatedRead\":true") })
        assertEquals(0L, sink.status().rejected)
        assertEquals(0L, sink.status().dropped)
        assertTrue(sink.enqueue(record("BLE_FIELD_WAITING")))
        assertTrue(sink.sync())
        val next = retainedRows(file).last()
        assertTrue(next.contains("\"teslaApproachRead\":null"))
        assertFalse(next.contains("\"bodyVerified\""))
        assertFalse(next.contains("\"driveSourceWallMs\""))
    }

    @Test fun authenticatedReadOptionCannotCarryIdentifiersOrIntegerTruthiness() {
        val file = newFile()
        val sink = startSink(file)
        val key = FieldStateKeys.BLE_FIELD_AUTHENTICATED_READ
        val secret = "VIN-MAC-KEY-raw"
        for (value in listOf(true, false, null, secret, "BLE_FIELD_RUNNING", 1, 0, 1L)) {
            assertTrue(sink.enqueue(record("BLE_FIELD_RUNNING", state = mapOf(key to value))))
        }
        assertFalse(sink.enqueue(record("TESLA_APPROACH_READ_FINISHED_$secret")))
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertTrue(rows[0].contains("\"$key\":true"))
        assertTrue(rows[1].contains("\"$key\":false"))
        assertTrue(rows.drop(2).all { it.contains("\"$key\":null") })
        assertFalse(file.readText().contains(secret))
        assertEquals(1L, sink.status().rejected)
        assertEquals(FieldLogHealth.OK, sink.status().health)
    }

    private fun archive(file: File, index: Int): File = File(file.parentFile, "events-v2.$index.jsonl")

    /** 가장 오래된 archive부터 active까지 소비자가 회수하는 순서다. */
    private fun retainedFiles(file: File): List<File> = (3 downTo 1).map { archive(file, it) } + file

    private fun retainedRows(file: File): List<String> =
        retainedFiles(file).filter { it.isFile }.flatMap { it.readLines() }

    private fun wallTimeOf(row: String): Long =
        row.substringAfter("\"wallMs\":").substringBefore(',').toLong()

    private fun eventOf(row: String): String =
        row.substringAfter("\"event\":\"").substringBefore('"')

    private fun assertBoundedRetention(file: File, sink: FieldEventSink, budget: Long) {
        val retained = retainedFiles(file)
        for (part in retained) assertTrue(part.length() <= budget / 4L)
        val total = retained.sumOf { it.length() }
        assertTrue(total <= budget)
        assertEquals(total, sink.status().fileBytes)
        assertEquals(FieldLogHealth.OK, sink.status().health)
        assertEquals(0, sink.status().pending)
    }

    private fun newDir(): File = Files.createTempDirectory("field-log-test").toFile()

    private fun newFile(): File = File(newDir(), FIELD_LOG_RELATIVE_PATH)

    /** 이전 실행이 남긴 로그를 흉내 낸다. sink와 달리 부모 디렉터리를 테스트가 직접 만든다. */
    private fun writeExisting(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun startSink(file: File, limitBytes: Long = FIELD_LOG_LIMIT_BYTES): FieldEventSink {
        val sink = FieldEventSink(file, limitBytes)
        sink.start()
        return sink
    }

    private fun bodyState(
        bytes: Any? = 2,
        status: Any? = "COMPLETE",
        fields: Any? = "1/0/1",
    ): Map<String, Any?> = mapOf(
        FieldStateKeys.UWB_RX_BODY_BYTES to bytes,
        FieldStateKeys.UWB_RX_BODY_STATUS to status,
        FieldStateKeys.UWB_RX_BODY_FIELDS to fields,
    )

    private fun assertBodySummaryNull(row: String) {
        for (key in FieldStateKeys.UWB_RX_BODY_KEYS) assertTrue(row.contains("\"$key\":null"))
    }

    private fun record(
        event: String,
        state: Map<String, Any?> = emptyMap(),
        speechTrial: SpeechTrialSummary? = null,
    ) = FieldRecord(
        processId = "11111111-2222-3333-4444-555555555555",
        trialId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        wallTimeMs = 1_700_000_000_000L,
        elapsedRealtimeMs = 4_321L,
        appVersion = "0.1.0-test",
        event = event,
        state = state,
        speechTrial = speechTrial,
    )

    private fun summary(
        mode: String = "TTS_PCM",
        status: String = "COMPLETE",
        reason: String = "FINAL_RECEIVED",
        finalReceived: Boolean = true,
        phraseMatched: Boolean? = true,
        confidence: Float? = 0.5f,
        elapsedMs: Long? = 1_000L,
        samples: Long = 12_000L,
        pcmBytesWritten: Int = 24_000,
        audioLeaseRetained: Boolean = false,
        decision: SpeechCommandDecision? = null,
    ) = SpeechTrialSummary(
        mode = mode,
        status = status,
        reason = reason,
        finalReceived = finalReceived,
        phraseMatched = phraseMatched,
        confidence = confidence,
        elapsedMs = elapsedMs,
        samples = samples,
        pcmBytesWritten = pcmBytesWritten,
        audioLeaseRetained = audioLeaseRetained,
        decision = decision,
    )

    /** 한 행의 `speechTrial` 값만 뽑는다. 키 순서가 바뀌어도 값만 보도록 중괄호 짝으로 자른다. */
    private fun trialValue(line: String): String {
        val key = "\"speechTrial\":"
        val start = line.indexOf(key)
        assertTrue("speechTrial 키가 없다: $line", start >= 0)
        val value = line.substring(start + key.length)
        if (value.startsWith("null")) return "null"
        var depth = 0
        for ((index, ch) in value.withIndex()) {
            when (ch) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return value.substring(0, index + 1)
                }
            }
        }
        fail("speechTrial 객체가 닫히지 않았다: $line")
        return ""
    }

    /** 한 행의 `confidence` 값만 뽑는다. */
    private fun confidenceOf(line: String): String {
        val key = "\"confidence\":"
        val start = line.indexOf(key)
        assertTrue("confidence 키가 없다: $line", start >= 0)
        return line.substring(start + key.length).substringBefore(',')
    }
}
