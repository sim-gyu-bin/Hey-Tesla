package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 실제 파일에 대한 append·재개·상한·쓰기 실패·allowlist 동작을 검증한다.
 * 임시 디렉터리는 회수·분석 근거로 남겨 두고 지우지 않는다. 테스트는 파일 삭제를 하지 않는다.
 */
class FieldEventLogTest {
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

    @Test fun limitStopsWritingWithoutDeletingExistingRows() {
        val file = newFile()
        val prefix = "EARLIER-ROW\n"
        writeExisting(file, prefix)
        val sink = startSink(file, limitBytes = 600L)

        var accepted = 0
        while (accepted < 40 && !sink.status().failed) {
            if (sink.enqueue(record("REAL_BLE_APPEARED", mapOf(FieldStateKeys.PRESENT to true)))) accepted++
            sink.sync()
        }

        val status = sink.status()
        assertTrue(accepted >= 1)
        assertEquals(FieldLogHealth.LIMIT_REACHED, status.health)
        assertEquals(FieldLogFailureCode.LIMIT_REACHED, status.failureCode)
        // 상한을 넘긴 레코드 하나만 유실되고 나머지는 전부 확정된다.
        assertEquals(1L, status.dropped)
        assertEquals(accepted.toLong() - 1L, status.written)
        assertEquals(0, status.pending)

        val text = file.readText()
        assertTrue(text.startsWith(prefix))
        assertTrue(text.endsWith("\n"))
        assertTrue(file.length() <= 600L)
        assertTrue(status.fileBytes <= 600L)

        // 실패 확정 뒤에는 큐에 넣지 않고 집계만 한다.
        assertFalse(sink.enqueue(record("REAL_BLE_APPEARED")))
        assertEquals(2L, sink.status().dropped)
    }

    @Test fun unopenableTargetReportsFixedFailureAndDropsRecords() {
        val dir = newDir()
        val blocker = File(dir, "blocker")
        blocker.writeText("blocking file, not a directory")
        val sink = startSink(File(blocker, "events.jsonl"))

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
        for (line in current) assertTrue(line.startsWith("{\"version\":7,"))
        val key = "\"${FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER}\":"
        assertTrue(current[0].contains("${key}true"))
        assertFalse(current[1].contains(key))
        assertTrue(current[2].contains("${key}false"))
        assertFalse(current[3].contains(key))
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
