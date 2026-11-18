package com.heytesla.app.speech.product

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.JsonReader
import android.util.JsonToken
import com.heytesla.app.logging.FieldJson
import com.heytesla.app.logging.ProductVoiceDiagnosticJson
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.Channels
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 앱 홈 최근 결과와 noBackup 전용 저장소에만 전달하는 완료 발화/실패 요약. */
data class ProductVoiceTranscriptRecord(
    val processId: String,
    val trialId: String?,
    val voiceLeaseId: Long,
    val utterance: Long,
    val wallTimeMs: Long,
    val elapsedRealtimeMs: Long,
    val appVersion: String,
    val text: String?,
    val diagnostic: ProductVoiceDiagnostic,
    val reason: String? = null,
) {
    override fun toString(): String = "ProductVoiceTranscriptRecord(REDACTED)"
}

data class ProductVoiceTranscriptReadResult(
    val records: List<ProductVoiceTranscriptRecord>,
    val failure: String? = null,
) {
    override fun toString(): String = "ProductVoiceTranscriptReadResult(REDACTED)"
}

enum class ProductVoiceTranscriptWriteResult {
    SAVED, LIMIT_REACHED, QUEUE_FULL, WRITE_FAILED, INVALID_RECORD,
}

/**
 * 호출자가 noBackupFilesDir의 canonical 경로 아래 product-voice-transcripts를 주입한다.
 * 새 쓰기는 schemaVersion=2 UTF-8 JSON이며 schemaVersion=1 완료본도 읽는다.
 * 같은 폴더의 CREATE_NEW <UUID>.json.tmp를 sync한 뒤 원자 rename한 파일만 완료본이다.
 * 실패 임시파일을 포함한 기존 항목은 삭제/수정하지 않으며 모든 일반 파일의 실제 바이트를 한도에 센다.
 * 경로/권한/IO 실패는 원문이나 예외 메시지 없이 WRITE_FAILED로만 반환한다.
 * readRecent는 최신 완료 UUID.json 최대 5개만 읽으며 기존 파일/실패 임시파일은 바꾸지 않는다.
 * 전체 디렉터리 1MiB/4096항목 한도, 경로/IO 오류, 잘못된 JSON은 고정 실패 코드로만 알린다.
 */
class ProductVoiceTranscriptStore(
    private val root: File,
    private val maxBytes: Long = MAX_BYTES,
) {
    init {
        require(maxBytes in 0L..MAX_BYTES) { "PRODUCT_TRANSCRIPT_LIMIT_INVALID" }
    }

    fun append(record: ProductVoiceTranscriptRecord): ProductVoiceTranscriptWriteResult {
        if (!validRecord(record)) return ProductVoiceTranscriptWriteResult.INVALID_RECORD
        // 여러 store 인스턴스도 같은 프로세스에서 용량 확인과 완료를 교차시키지 않는다.
        return synchronized(ioLock) {
            try {
                val directory = checkedRoot()
                val bytes = json(record).toByteArray(Charsets.UTF_8)
                var remaining = maxBytes
                var entries = 0
                Files.newDirectoryStream(directory.toPath()).use { children ->
                    for (path in children) {
                        if (++entries >= MAX_ENTRIES) return@synchronized ProductVoiceTranscriptWriteResult.LIMIT_REACHED
                        val entry = path.toFile()
                        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        if (!attributes.isRegularFile || entry.canonicalFile.parentFile != directory) {
                            return@synchronized ProductVoiceTranscriptWriteResult.WRITE_FAILED
                        }
                        if (attributes.size() > remaining) return@synchronized ProductVoiceTranscriptWriteResult.LIMIT_REACHED
                        remaining -= attributes.size()
                    }
                }
                if (bytes.size.toLong() > remaining) return@synchronized ProductVoiceTranscriptWriteResult.LIMIT_REACHED
                val id = UUID.randomUUID().toString()
                val temporary = File(directory, "$id.json.tmp").toPath()
                val target = File(directory, "$id.json").toPath()
                // NOFOLLOW_LINKS와 CREATE_NEW로 기존 임시파일도 열지 않는다.
                FileChannel.open(
                    temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || checkedRoot() != directory) {
                    return@synchronized ProductVoiceTranscriptWriteResult.WRITE_FAILED
                }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
                ProductVoiceTranscriptWriteResult.SAVED
            } catch (_: IOException) {
                ProductVoiceTranscriptWriteResult.WRITE_FAILED
            } catch (_: java.nio.file.InvalidPathException) {
                ProductVoiceTranscriptWriteResult.WRITE_FAILED
            } catch (_: java.nio.file.DirectoryIteratorException) {
                ProductVoiceTranscriptWriteResult.WRITE_FAILED
            } catch (_: SecurityException) {
                ProductVoiceTranscriptWriteResult.WRITE_FAILED
            } catch (_: UnsupportedOperationException) {
                ProductVoiceTranscriptWriteResult.WRITE_FAILED
            }
        }
    }

    fun readRecent(limit: Int = 5): ProductVoiceTranscriptReadResult {
        val count = limit.coerceIn(0, MAX_RECENT)
        if (count == 0) return ProductVoiceTranscriptReadResult(emptyList())
        return synchronized(ioLock) {
            try {
                val directory = checkedRoot(create = false)
                if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    return@synchronized ProductVoiceTranscriptReadResult(emptyList())
                }
                var remaining = maxBytes
                var entries = 0
                val recent = ArrayList<CompletedFile>(count)
                Files.newDirectoryStream(directory.toPath()).use { children ->
                    for (path in children) {
                        if (++entries > MAX_ENTRIES) {
                            return@synchronized ProductVoiceTranscriptReadResult(emptyList(), "LIMIT_REACHED")
                        }
                        val file = path.toFile()
                        // Android NIO attributes는 실제 FS의 초 미만 mtime을 잃을 수 있다.
                        // lstat의 원시 timespec을 유지하고 초/나노초를 따로 비교한다.
                        val attributes = Os.lstat(file.path)
                        if (!OsConstants.S_ISREG(attributes.st_mode) || file.canonicalFile.parentFile != directory) {
                            return@synchronized ProductVoiceTranscriptReadResult(emptyList(), "READ_FAILED")
                        }
                        if (attributes.st_size > remaining) {
                            return@synchronized ProductVoiceTranscriptReadResult(emptyList(), "LIMIT_REACHED")
                        }
                        remaining -= attributes.st_size
                        if (COMPLETED_NAME.matches(file.name)) {
                            recent.add(CompletedFile(file, attributes.st_size,
                                attributes.st_mtim.tv_sec, attributes.st_mtim.tv_nsec))
                            recent.sortWith(COMPLETED_ORDER)
                            if (recent.size > count) recent.removeAt(count)
                        }
                    }
                }
                val records = ArrayList<ProductVoiceTranscriptRecord>(recent.size)
                var failure: String? = null
                for (completed in recent) {
                    try {
                        records.add(readRecord(completed))
                    } catch (_: InvalidRecordException) {
                        if (failure == null) failure = "INVALID_RECORD"
                    }
                }
                if (checkedRoot(create = false) != directory) throw IOException("PRODUCT_TRANSCRIPT_PATH_INVALID")
                ProductVoiceTranscriptReadResult(records, failure)
            } catch (_: IOException) {
                ProductVoiceTranscriptReadResult(emptyList(), "READ_FAILED")
            } catch (_: ErrnoException) {
                ProductVoiceTranscriptReadResult(emptyList(), "READ_FAILED")
            } catch (_: java.nio.file.InvalidPathException) {
                ProductVoiceTranscriptReadResult(emptyList(), "READ_FAILED")
            } catch (_: java.nio.file.DirectoryIteratorException) {
                ProductVoiceTranscriptReadResult(emptyList(), "READ_FAILED")
            } catch (_: SecurityException) {
                ProductVoiceTranscriptReadResult(emptyList(), "READ_FAILED")
            } catch (_: UnsupportedOperationException) {
                ProductVoiceTranscriptReadResult(emptyList(), "READ_FAILED")
            }
        }
    }

    private fun checkedRoot(create: Boolean = true): File {
        val absolute = root.absoluteFile.toPath()
        if (absolute != absolute.normalize()) throw IOException("PRODUCT_TRANSCRIPT_PATH_INVALID")
        var ancestor: java.nio.file.Path? = absolute
        while (ancestor != null) {
            if (Files.isSymbolicLink(ancestor)) throw IOException("PRODUCT_TRANSCRIPT_PATH_INVALID")
            ancestor = ancestor.parent
        }
        val exists = Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
        if (!exists && create) Files.createDirectory(absolute)
        if ((exists || create) && !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("PRODUCT_TRANSCRIPT_PATH_INVALID")
        }
        val canonical = root.canonicalFile
        if (canonical.toPath() != absolute) throw IOException("PRODUCT_TRANSCRIPT_PATH_INVALID")
        return canonical
    }

    private fun validRecord(record: ProductVoiceTranscriptRecord): Boolean {
        val diagnostic = record.diagnostic
        val completed = when (diagnostic.outcome) {
            "EMPTY", "GRAMMAR_NO_MATCH", "ACCEPTED" ->
                record.text != null && diagnostic.microphoneReleased == true &&
                    diagnostic.streamCleanupConfirmed == true &&
                    (diagnostic.outcome != "ACCEPTED" || diagnostic.recognizerCleanupConfirmed == true)
            "FAILURE", "SEALED" -> record.text == null && record.reason != null
            else -> false
        }
        return completed &&
            (record.text == null || (record.text.length <= MAX_TEXT_CHARS && wellFormed(record.text))) &&
            (record.reason == null || FIXED_REASON.matches(record.reason)) &&
            record.processId.isNotBlank() && record.processId.length <= MAX_METADATA_CHARS && wellFormed(record.processId) &&
            (record.trialId == null || (record.trialId.length <= MAX_METADATA_CHARS && wellFormed(record.trialId))) &&
            record.appVersion.isNotBlank() && record.appVersion.length <= MAX_METADATA_CHARS && wellFormed(record.appVersion) &&
            record.voiceLeaseId > 0 && record.utterance > 0 && record.wallTimeMs >= 0 && record.elapsedRealtimeMs >= 0
    }

    private fun wellFormed(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val ch = value[index++]
            if (Character.isHighSurrogate(ch)) {
                if (index == value.length || !Character.isLowSurrogate(value[index++])) return false
            } else if (Character.isLowSurrogate(ch)) return false
        }
        return true
    }

    private fun json(record: ProductVoiceTranscriptRecord): String = buildString {
        append("{\"schemaVersion\":2,\"processId\":").append(FieldJson.quote(record.processId))
        append(",\"trialId\":").append(record.trialId?.let(FieldJson::quote) ?: "null")
        append(",\"voiceLeaseId\":").append(record.voiceLeaseId)
        append(",\"utterance\":").append(record.utterance)
        append(",\"wallTimeMs\":").append(record.wallTimeMs)
        append(",\"elapsedRealtimeMs\":").append(record.elapsedRealtimeMs)
        append(",\"appVersion\":").append(FieldJson.quote(record.appVersion))
        append(",\"text\":").append(record.text?.let(FieldJson::quote) ?: "null")
        append(",\"diagnostic\":")
        ProductVoiceDiagnosticJson.appendTo(this, record.diagnostic)
        append(",\"reason\":").append(record.reason?.let(FieldJson::quote) ?: "null")
        append("}\n")
    }

    private data class CompletedFile(
        val file: File,
        val size: Long,
        val modifiedSeconds: Long,
        val modifiedNanos: Long,
    )
    private class InvalidRecordException : IOException("PRODUCT_TRANSCRIPT_INVALID_RECORD")

    private fun readRecord(completed: CompletedFile): ProductVoiceTranscriptRecord {
        return FileChannel.open(completed.file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != completed.size) throw IOException("PRODUCT_TRANSCRIPT_FILE_CHANGED")
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val record = try {
                JsonReader(InputStreamReader(BoundedInput(Channels.newInputStream(channel), completed.size), decoder)).use { reader ->
                    val parsed = parseRecord(reader)
                    if (reader.peek() != JsonToken.END_DOCUMENT) invalidRecord()
                    parsed
                }
            } catch (_: IOException) {
                throw InvalidRecordException()
            } catch (_: IllegalArgumentException) {
                throw InvalidRecordException()
            } catch (_: IllegalStateException) {
                throw InvalidRecordException()
            }
            // JsonReader.close는 channel도 닫으므로 경로/타입은 다시 NOFOLLOW로 확인한다.
            val attributes = Files.readAttributes(completed.file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile || attributes.size() != completed.size ||
                completed.file.canonicalFile != completed.file.absoluteFile) {
                throw IOException("PRODUCT_TRANSCRIPT_FILE_CHANGED")
            }
            record
        }
    }

    /** 파일이 읽는 도중 커져도 inventory에서 승인된 바이트보다 많이 읽지 않는다. */
    private class BoundedInput(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining == 0L) return -1
            val value = `in`.read()
            if (value == -1) throw InvalidRecordException()
            remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return read()
            val count = `in`.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count == -1) throw InvalidRecordException()
            remaining -= count
            return count
        }
    }

    private fun parseRecord(reader: JsonReader): ProductVoiceTranscriptRecord {
        var schema = 0L
        var processId: String? = null
        var trialId: String? = null
        var voiceLeaseId = 0L
        var utterance = 0L
        var wallTimeMs = -1L
        var elapsedRealtimeMs = -1L
        var appVersion: String? = null
        var text: String? = null
        var diagnostic: ProductVoiceDiagnostic? = null
        var reason: String? = null
        val seen = readObject(reader, RECORD_FIELDS) { index ->
            when (index) {
                0 -> schema = readLong(reader)
                1 -> processId = readString(reader)
                2 -> trialId = readNullableString(reader)
                3 -> voiceLeaseId = readLong(reader)
                4 -> utterance = readLong(reader)
                5 -> wallTimeMs = readLong(reader)
                6 -> elapsedRealtimeMs = readLong(reader)
                7 -> appVersion = readString(reader)
                8 -> text = readNullableString(reader)
                9 -> diagnostic = parseDiagnostic(reader)
                10 -> reason = readNullableString(reader)
            }
        }
        if (schema !in 1L..2L || seen and 0x3ff != 0x3ff ||
            (schema == 2L && seen and 0x400 == 0) ||
            (schema == 1L && (seen and 0x400 != 0 || text == null))) invalidRecord()
        val result = ProductVoiceTranscriptRecord(
            processId ?: invalidRecord(), trialId, voiceLeaseId, utterance, wallTimeMs,
            elapsedRealtimeMs, appVersion ?: invalidRecord(), text, diagnostic ?: invalidRecord(), reason,
        )
        if (!validRecord(result)) invalidRecord()
        return result
    }

    private fun parseDiagnostic(reader: JsonReader): ProductVoiceDiagnostic {
        var outcome: String? = null
        var utteranceBytes: Int? = null
        var voicedBytes: Int? = null
        var suppressedOnsets: Int? = null
        var silenceBytesAtEnd: Int? = null
        var captureMs: Long? = null
        var loadMs: Long? = null
        var decodeMs: Long? = null
        var cleanupMs: Long? = null
        var modelReused: Boolean? = null
        var microphoneReleased: Boolean? = null
        var streamCleanupConfirmed: Boolean? = null
        var recognizerCleanupConfirmed: Boolean? = null
        val seen = readObject(reader, DIAGNOSTIC_FIELDS) { index ->
            when (index) {
                0 -> outcome = readString(reader)
                1 -> utteranceBytes = readNullableInt(reader)
                2 -> voicedBytes = readNullableInt(reader)
                3 -> suppressedOnsets = readNullableInt(reader)
                4 -> silenceBytesAtEnd = readNullableInt(reader)
                5 -> captureMs = readNullableLong(reader)
                6 -> loadMs = readNullableLong(reader)
                7 -> decodeMs = readNullableLong(reader)
                8 -> cleanupMs = readNullableLong(reader)
                9 -> modelReused = readNullableBoolean(reader)
                10 -> microphoneReleased = readNullableBoolean(reader)
                11 -> streamCleanupConfirmed = readNullableBoolean(reader)
                12 -> recognizerCleanupConfirmed = readNullableBoolean(reader)
            }
        }
        if (seen != 0x1fff ||
            (utteranceBytes != null && (utteranceBytes!! !in 0..320_000 || utteranceBytes!! % 2 != 0)) ||
            !validPcmBytes(voicedBytes, utteranceBytes) || !validPcmBytes(silenceBytesAtEnd, utteranceBytes) ||
            (suppressedOnsets != null && suppressedOnsets!! < 0) ||
            (captureMs != null && captureMs!! !in 0L..86_400_000L) ||
            !validDuration(loadMs) || !validDuration(decodeMs) || !validDuration(cleanupMs)) invalidRecord()
        return ProductVoiceDiagnostic(
            outcome ?: invalidRecord(), utteranceBytes, voicedBytes, suppressedOnsets, silenceBytesAtEnd,
            captureMs, loadMs, decodeMs, cleanupMs, modelReused, microphoneReleased,
            streamCleanupConfirmed, recognizerCleanupConfirmed,
        )
    }

    private fun validPcmBytes(value: Int?, utterance: Int?): Boolean =
        value == null || (utterance != null && value in 0..utterance && value % 2 == 0)

    private fun validDuration(value: Long?): Boolean = value == null || value in 0L..600_000L

    /** 알 수 없는/중복 필드는 건너뛰지 않아 외부 중첩 객체로 parser 깊이를 늘릴 수 없다. */
    private inline fun readObject(reader: JsonReader, fields: Array<String>, consume: (Int) -> Unit): Int {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) invalidRecord()
        reader.beginObject()
        var seen = 0
        while (reader.hasNext()) {
            val index = fields.indexOf(reader.nextName())
            if (index < 0 || seen and (1 shl index) != 0) invalidRecord()
            seen = seen or (1 shl index)
            consume(index)
        }
        reader.endObject()
        return seen
    }

    private fun readString(reader: JsonReader): String {
        if (reader.peek() != JsonToken.STRING) invalidRecord()
        return reader.nextString()
    }

    private fun readLong(reader: JsonReader): Long {
        if (reader.peek() != JsonToken.NUMBER) invalidRecord()
        return reader.nextString().toLongOrNull() ?: invalidRecord()
    }

    private fun readNullableString(reader: JsonReader): String? =
        if (takeNull(reader)) null else readString(reader)

    private fun readNullableLong(reader: JsonReader): Long? =
        if (takeNull(reader)) null else readLong(reader)

    private fun readNullableInt(reader: JsonReader): Int? {
        val value = readNullableLong(reader) ?: return null
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) invalidRecord()
        return value.toInt()
    }

    private fun readNullableBoolean(reader: JsonReader): Boolean? {
        if (takeNull(reader)) return null
        if (reader.peek() != JsonToken.BOOLEAN) invalidRecord()
        return reader.nextBoolean()
    }

    private fun takeNull(reader: JsonReader): Boolean {
        if (reader.peek() != JsonToken.NULL) return false
        reader.nextNull()
        return true
    }

    private fun invalidRecord(): Nothing = throw InvalidRecordException()

    companion object {
        private const val MAX_BYTES = 1_048_576L
        private const val MAX_TEXT_CHARS = 4_096
        private const val MAX_METADATA_CHARS = 128
        private const val MAX_RECENT = 5
        private const val MAX_ENTRIES = 4_096
        private val FIXED_REASON = Regex("[A-Z][A-Z0-9_]{0,127}")
        private val COMPLETED_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")
        private val COMPLETED_ORDER = compareByDescending<CompletedFile> { it.modifiedSeconds }
            .thenByDescending { it.modifiedNanos }.thenByDescending { it.file.name }
        private val RECORD_FIELDS = arrayOf(
            "schemaVersion", "processId", "trialId", "voiceLeaseId", "utterance", "wallTimeMs",
            "elapsedRealtimeMs", "appVersion", "text", "diagnostic", "reason",
        )
        private val DIAGNOSTIC_FIELDS = arrayOf(
            "outcome", "utteranceBytes", "voicedBytes", "suppressedOnsets", "silenceBytesAtEnd",
            "captureMs", "loadMs", "decodeMs", "cleanupMs", "modelReused", "microphoneReleased",
            "streamCleanupConfirmed", "recognizerCleanupConfirmed",
        )
        private val ioLock = Any()
    }
}

/**
 * submit은 디스크를 만지지 않고 최대 16 pending을 넘기면 즉시 false다.
 * true는 큐 접수일 뿐 SAVED가 아니다. onResult에는 고정 enum만 전달한다.
 * 항상 저장 가능한 개발 버전 전용 worker이며 executor는 비동기 실행기를 주입해야 한다.
 */
class ProductVoiceTranscriptWriter(
    private val store: ProductVoiceTranscriptStore,
    private val onResult: (ProductVoiceTranscriptWriteResult) -> Unit,
    private val executor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "product-transcript-io").apply { isDaemon = true }
    },
) {
    private val lock = Any()
    private val queue = ArrayDeque<ProductVoiceTranscriptRecord>(QUEUE_CAPACITY)
    private var draining = false

    fun submit(record: ProductVoiceTranscriptRecord): Boolean {
        var schedule = false
        val rejected = synchronized(lock) {
            when {
                queue.size == QUEUE_CAPACITY -> ProductVoiceTranscriptWriteResult.QUEUE_FULL
                else -> {
                    queue.addLast(record)
                    if (!draining) {
                        draining = true
                        schedule = true
                    }
                    null
                }
            }
        }
        if (rejected != null) {
            report(rejected)
            return false
        }
        if (schedule) {
            try {
                executor.execute(::drain)
            } catch (_: RuntimeException) {
                val failures = synchronized(lock) {
                    val count = queue.size
                    queue.clear()
                    draining = false
                    count
                }
                repeat(failures) { report(ProductVoiceTranscriptWriteResult.WRITE_FAILED) }
                return false
            }
        }
        return true
    }

    private fun drain() {
        while (true) {
            val pending = synchronized(lock) {
                if (queue.isEmpty()) {
                    draining = false
                    return
                }
                queue.removeFirst()
            }
            report(store.append(pending))
        }
    }

    private fun report(result: ProductVoiceTranscriptWriteResult) {
        try {
            onResult(result)
        } catch (_: RuntimeException) {
            // 소비자 오류 때문에 후속 pending을 멈추거나 원문/예외 메시지를 로그하지 않는다.
        }
    }

    companion object {
        private const val QUEUE_CAPACITY = 16
    }
}
