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

    private fun record(event: String, state: Map<String, Any?> = emptyMap()) = FieldRecord(
        processId = "11111111-2222-3333-4444-555555555555",
        trialId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        wallTimeMs = 1_700_000_000_000L,
        elapsedRealtimeMs = 4_321L,
        appVersion = "0.1.0-test",
        event = event,
        state = state,
    )
}
