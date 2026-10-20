package com.heytesla.app

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
