package com.heytesla.app

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID

internal data class SpeechRecordingInfo(
    val id: String,
    val createdAtMs: Long,
    val pcmBytes: Int,
    val source: String = DIRECT_MIC_SOURCE,
)

internal data class SpeechRecordingPcm(
    val info: SpeechRecordingInfo,
    val pcm: ByteArray,
)

/**
 * 사용자가 직접 시작한 마이크 시험 녹음(계측 시에는 SYNTHETIC_FIXTURE)과 원본 재시험 결과를
 * `noBackupFilesDir/speech-recordings`에만 보존한다.
 *
 * 녹음 하나는 `<UUID>/audio.wav`(16 kHz mono PCM16, canonical 44바이트 header)와
 * `<UUID>/capture.json`(완료 메타)로 구성되고, capture.json은 WAV 저장이 끝난 뒤 마지막에 원자 rename된다.
 * 원본 재시험 결과는 `<UUID>/runs/<runUUID>.json`에만 덧붙이며 기존 녹음·메타를 고치지 않는다.
 *
 * 문자열 경로·오디오·인식 원문을 예외 메시지나 일반 field 로그로 내보내지 않는다. 실패는 고정 코드다.
 * 삭제·자동 정리·업로드 API는 없고, 저장소 밖 경로·심볼릭 링크·손상 WAV·미완료 메타는 소비 전에 거절한다.
 * 실패한 저장이 남긴 `<UUID>/capture.json.tmp`와 `runs/<runUUID>.json.tmp`는 지우지 않고 그대로 둔다.
 * 소비자는 `.tmp`와 완료 코드가 붙지 않은 항목을 읽지 않아야 한다.
 */
internal class SpeechRecordingStore(
    private val root: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, RELATIVE_PATH))

    /** 완료된 녹음만 최신순으로 돌려준다. 손상되었거나 capture.json이 없는 폴더는 조용히 제외한다. */
    fun list(): List<SpeechRecordingInfo> {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return emptyList()
        val entries = root.listFiles() ?: return emptyList()
        val completed = ArrayList<SpeechRecordingInfo>(entries.size)
        for (entry in entries) {
            if (Files.isSymbolicLink(entry.toPath()) || !entry.isDirectory) continue
            val id = validUuid(entry.name) ?: continue
            val info = try {
                readInfo(entry, id)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            if (info != null) completed += info
        }
        return completed.sortedWith(
            compareByDescending<SpeechRecordingInfo> { it.createdAtMs }.thenByDescending { it.id },
        )
    }

    /**
     * [pcm]의 앞 [size]바이트만 WAV로 저장한다. RAW PCM은 복제하지 않고 capture.json에는 길이만 남긴다.
     * 저장 디렉터리와 파일 이름은 고유 UUID이며 기존 파일을 덮어쓰지 않는다.
     *
     * [source]는 실기기 Direct Mic 녹음과 계측용 합성 fixture를 구분한다. 허용값은
     * [DIRECT_MIC_SOURCE]·[SYNTHETIC_FIXTURE_SOURCE]뿐이며 기본값은 실제 마이크다.
     */
    fun save(
        pcm: ByteArray,
        size: Int,
        source: String = DIRECT_MIC_SOURCE,
    ): SpeechRecordingInfo {
        require(size in SAMPLE_BYTES..MAX_PCM_BYTES && size <= pcm.size && size % SAMPLE_BYTES == 0) {
            "RECORDING_SIZE_INVALID"
        }
        require(validSource(source) != null) { "RECORDING_SOURCE_INVALID" }
        try {
            ensureRoot()
            val id = UUID.randomUUID().toString()
            val directory = File(root, id)
            if (!directory.mkdir()) throw IOException("RECORDING_DIRECTORY_FAILED")
            val info = SpeechRecordingInfo(id, clock(), size, source)
            writeAudio(File(directory, AUDIO_FILE_NAME), pcm, size)
            writeCapture(directory, info)
            return info
        } catch (_: SecurityException) {
            throw IOException("RECORDING_SAVE_FAILED")
        }
    }

    /** 완료 메타와 WAV가 모두 일치할 때만 PCM을 돌려준다. 손상·경로 이탈·심볼릭 링크는 거절한다. */
    fun load(id: String): SpeechRecordingPcm {
        try {
            val directory = recordingDirectory(id)
            if (!directory.isDirectory) throw IOException("RECORDING_NOT_FOUND")
            val info = readInfo(directory, id)
            return SpeechRecordingPcm(info, readPcm(File(directory, AUDIO_FILE_NAME), info))
        } catch (_: SecurityException) {
            throw IOException("RECORDING_LOAD_FAILED")
        }
    }

    /**
     * 저장된 PCM으로 다시 인식한 결과를 `runs/<runUUID>.json`에만 추가하고 run UUID를 돌려준다.
     * [text]·[failure]는 전용 결과 JSON에만 쓰며 capture.json·로그에는 남기지 않는다.
     */
    fun saveResult(id: String, text: String?, failure: String?, elapsedMs: Long): String {
        require(elapsedMs >= 0L) { "RECORDING_RESULT_INVALID" }
        try {
            val directory = recordingDirectory(id)
            if (!directory.isDirectory) throw IOException("RECORDING_NOT_FOUND")
            readInfo(directory, id)
            val runs = File(directory, RUNS_DIRECTORY)
            if (Files.isSymbolicLink(runs.toPath())) throw IOException("RECORDING_RESULT_FAILED")
            if (!runs.isDirectory && !runs.mkdir()) throw IOException("RECORDING_RESULT_FAILED")
            val runId = UUID.randomUUID().toString()
            val target = File(runs, runId + RUN_SUFFIX)
            val temporary = File(runs, runId + RUN_TEMP_SUFFIX)
            try {
                if (!temporary.createNewFile()) throw IOException("RECORDING_RESULT_FAILED")
                FileOutputStream(temporary).use { output ->
                    output.write(
                        resultJson(runId, id, text, failure, elapsedMs).toByteArray(Charsets.UTF_8),
                    )
                    output.flush()
                    output.fd.sync()
                }
                if (target.exists() || !temporary.renameTo(target)) {
                    throw IOException("RECORDING_RESULT_FAILED")
                }
            } catch (_: IOException) {
                throw IOException("RECORDING_RESULT_FAILED")
            }
            return runId
        } catch (_: SecurityException) {
            throw IOException("RECORDING_RESULT_FAILED")
        }
    }

    private fun ensureRoot() {
        if (Files.isSymbolicLink(root.toPath())) throw IOException("RECORDING_ROOT_INVALID")
        if (root.isDirectory) return
        if (root.exists() || !root.mkdirs()) throw IOException("RECORDING_ROOT_INVALID")
    }

    /** UUID 정규 문자열만 허용하고, canonical 경로가 저장소 바로 아래인지 확인한다. */
    private fun recordingDirectory(id: String): File {
        val valid = validUuid(id) ?: throw IOException("RECORDING_ID_INVALID")
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
            throw IOException("RECORDING_ID_INVALID")
        }
        val directory = File(root, valid)
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("RECORDING_ID_INVALID")
        val canonicalRoot = try {
            root.canonicalFile
        } catch (_: IOException) {
            throw IOException("RECORDING_ID_INVALID")
        }
        val canonical = try {
            directory.canonicalFile
        } catch (_: IOException) {
            throw IOException("RECORDING_ID_INVALID")
        }
        if (canonical.parentFile != canonicalRoot) throw IOException("RECORDING_ID_INVALID")
        return canonical
    }

    private fun readInfo(directory: File, id: String): SpeechRecordingInfo {
        val metadata = File(directory, CAPTURE_FILE_NAME)
        if (!metadata.isFile || Files.isSymbolicLink(metadata.toPath())) {
            throw IOException("RECORDING_METADATA_INVALID")
        }
        if (metadata.length() !in 1L..CAPTURE_MAX_BYTES.toLong()) {
            throw IOException("RECORDING_METADATA_INVALID")
        }
        val bytes = try {
            metadata.readBytes()
        } catch (_: IOException) {
            throw IOException("RECORDING_METADATA_INVALID")
        } catch (_: SecurityException) {
            throw IOException("RECORDING_METADATA_INVALID")
        }
        val info = parseCapture(bytes, id) ?: throw IOException("RECORDING_METADATA_INVALID")
        validateWave(File(directory, AUDIO_FILE_NAME), info.pcmBytes)
        return info
    }

    private fun writeAudio(file: File, pcm: ByteArray, size: Int) {
        try {
            if (!file.createNewFile()) throw IOException("RECORDING_AUDIO_WRITE_FAILED")
            FileOutputStream(file).use { output ->
                output.write(waveHeader(size))
                output.write(pcm, 0, size)
                output.flush()
                output.fd.sync()
            }
        } catch (_: IOException) {
            throw IOException("RECORDING_AUDIO_WRITE_FAILED")
        } catch (_: SecurityException) {
            throw IOException("RECORDING_AUDIO_WRITE_FAILED")
        }
    }

    private fun writeCapture(directory: File, info: SpeechRecordingInfo) {
        val temporary = File(directory, CAPTURE_TEMP_NAME)
        val target = File(directory, CAPTURE_FILE_NAME)
        try {
            if (!temporary.createNewFile()) throw IOException("RECORDING_CAPTURE_WRITE_FAILED")
            FileOutputStream(temporary).use { output ->
                output.write(captureJson(info).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (target.exists() || !temporary.renameTo(target)) {
                throw IOException("RECORDING_CAPTURE_WRITE_FAILED")
            }
        } catch (_: IOException) {
            throw IOException("RECORDING_CAPTURE_WRITE_FAILED")
        } catch (_: SecurityException) {
            throw IOException("RECORDING_CAPTURE_WRITE_FAILED")
        }
    }

    private fun validateWave(file: File, pcmBytes: Int) {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            throw IOException("RECORDING_AUDIO_INVALID")
        }
        if (file.length() != WAVE_HEADER_BYTES.toLong() + pcmBytes.toLong()) {
            throw IOException("RECORDING_AUDIO_INVALID")
        }
        val header = ByteArray(WAVE_HEADER_BYTES)
        try {
            FileInputStream(file).use { input ->
                if (!readFully(input, header, 0, WAVE_HEADER_BYTES)) {
                    throw IOException("RECORDING_AUDIO_INVALID")
                }
            }
        } catch (_: IOException) {
            throw IOException("RECORDING_AUDIO_INVALID")
        } catch (_: SecurityException) {
            throw IOException("RECORDING_AUDIO_INVALID")
        }
        if (!header.contentEquals(waveHeader(pcmBytes))) throw IOException("RECORDING_AUDIO_INVALID")
    }

    private fun readPcm(file: File, info: SpeechRecordingInfo): ByteArray {
        val expectedHeader = waveHeader(info.pcmBytes)
        try {
            FileInputStream(file).use { input ->
                val header = ByteArray(WAVE_HEADER_BYTES)
                if (!readFully(input, header, 0, WAVE_HEADER_BYTES) ||
                    !header.contentEquals(expectedHeader)
                ) {
                    throw IOException("RECORDING_AUDIO_INVALID")
                }
                val pcm = ByteArray(info.pcmBytes)
                if (!readFully(input, pcm, 0, pcm.size) || input.read() != -1) {
                    throw IOException("RECORDING_AUDIO_INVALID")
                }
                return pcm
            }
        } catch (_: IOException) {
            throw IOException("RECORDING_AUDIO_INVALID")
        } catch (_: SecurityException) {
            throw IOException("RECORDING_AUDIO_INVALID")
        }
    }

    private fun captureJson(info: SpeechRecordingInfo): String =
        "{\"schemaVersion\":$CAPTURE_SCHEMA_VERSION" +
            ",\"id\":${jsonString(info.id)}" +
            ",\"createdAtMs\":${info.createdAtMs}" +
            ",\"sampleRate\":$SAMPLE_RATE" +
            ",\"channels\":$CHANNELS" +
            ",\"bitsPerSample\":$BITS_PER_SAMPLE" +
            ",\"pcmBytes\":${info.pcmBytes}" +
            ",\"source\":${jsonString(info.source)}}"

    private fun resultJson(
        runId: String,
        recordingId: String,
        text: String?,
        failure: String?,
        elapsedMs: Long,
    ): String =
        "{\"schemaVersion\":$RESULT_SCHEMA_VERSION" +
            ",\"id\":${jsonString(runId)}" +
            ",\"recordingId\":${jsonString(recordingId)}" +
            ",\"createdAtMs\":${clock()}" +
            ",\"elapsedMs\":$elapsedMs" +
            ",\"engine\":${jsonString(ENGINE_NAME)}" +
            ",\"confidence\":null" +
            ",\"source\":${jsonString(STORED_PCM_SOURCE)}" +
            ",\"text\":${text?.let(::jsonString) ?: "null"}" +
            ",\"failure\":${failure?.let(::jsonString) ?: "null"}}"

    private companion object {
        const val RELATIVE_PATH = "speech-recordings"
        const val AUDIO_FILE_NAME = "audio.wav"
        const val CAPTURE_FILE_NAME = "capture.json"
        const val CAPTURE_TEMP_NAME = "capture.json.tmp"
        const val RUNS_DIRECTORY = "runs"
        const val RUN_SUFFIX = ".json"
        const val RUN_TEMP_SUFFIX = ".json.tmp"

        const val WAVE_HEADER_BYTES = 44
        const val CAPTURE_MAX_BYTES = 4_096

        /** WAV 당 하나의 run만 덧붙이므로 결과 파일은 장문 원문을 담을 수 있다. 상한은 두지 않고 원자 저장만 한다. */
        fun waveHeader(pcmBytes: Int): ByteArray =
            ByteBuffer.allocate(WAVE_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + pcmBytes)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort())
                putShort(CHANNELS.toShort())
                putInt(SAMPLE_RATE)
                putInt(SAMPLE_RATE * CHANNELS * SAMPLE_BYTES)
                putShort((CHANNELS * SAMPLE_BYTES).toShort())
                putShort(BITS_PER_SAMPLE.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmBytes)
            }.array()
    }
}

private const val CAPTURE_SCHEMA_VERSION = 1
private const val RESULT_SCHEMA_VERSION = 1
private const val SAMPLE_RATE = SpeechMicCapture.RATE
private const val CHANNELS = 1
private const val BITS_PER_SAMPLE = 16
private const val SAMPLE_BYTES = BITS_PER_SAMPLE / 8
private const val MAX_PCM_BYTES = SpeechMicCapture.MAX_BYTES
private const val DIRECT_MIC_SOURCE = "DIRECT_MIC"
private const val SYNTHETIC_FIXTURE_SOURCE = "SYNTHETIC_FIXTURE"
private const val STORED_PCM_SOURCE = "STORED_PCM"
private const val ENGINE_NAME = "Android on-device SpeechRecognizer ko-KR"

private const val UUID_LENGTH = 36

/** canonical 소문자 UUID만 받는다. `1-1-1-1-1`·대문자·경로 조각은 모두 거절한다. */
private fun validUuid(value: String): String? {
    if (value.length != UUID_LENGTH) return null
    val parsed = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return parsed.toString().takeIf { it == value }
}

private fun readFully(input: InputStream, target: ByteArray, offset: Int, size: Int): Boolean {
    var position = offset
    val end = offset + size
    while (position < end) {
        val count = input.read(target, position, end - position)
        if (count <= 0) return false
        position += count
    }
    return true
}

/** 실기기 마이크와 계측 합성 fixture만 출처로 허용한다. 그 밖의 값은 손상·오용으로 본다. */
private fun validSource(source: String): String? = when (source) {
    DIRECT_MIC_SOURCE, SYNTHETIC_FIXTURE_SOURCE -> source
    else -> null
}

/** 문자열을 JSON 문자열 리터럴로 쓴다. 원문은 호출자가 넘긴 전용 결과 JSON 밖으로 나가지 않는다. */
private fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

/**
 * capture.json은 이 저장소가 직접 쓴 flat object만 받는다. 키 8개·정수/문자열만 허용하고
 * 중복 키·후행 바이트·숫자 아닌 값은 손상으로 본다.
 */
private fun parseCapture(bytes: ByteArray, expectedId: String): SpeechRecordingInfo? {
    val fields = parseFlatObject(String(bytes, Charsets.UTF_8)) ?: return null
    if (fields.size != CAPTURE_FIELD_COUNT) return null
    if ((fields["schemaVersion"] as? Long)?.toInt() != CAPTURE_SCHEMA_VERSION) return null
    val id = fields["id"] as? String ?: return null
    if (id != expectedId) return null
    val createdAtMs = fields["createdAtMs"] as? Long ?: return null
    if (createdAtMs < 0L) return null
    if (fields["sampleRate"] != SAMPLE_RATE.toLong()) return null
    if (fields["channels"] != CHANNELS.toLong()) return null
    if (fields["bitsPerSample"] != BITS_PER_SAMPLE.toLong()) return null
    val pcmBytes = fields["pcmBytes"] as? Long ?: return null
    if (pcmBytes < 2L || pcmBytes > MAX_PCM_BYTES.toLong() || pcmBytes % 2L != 0L) return null
    val source = fields["source"] as? String ?: return null
    if (validSource(source) == null) return null
    return SpeechRecordingInfo(id, createdAtMs, pcmBytes.toInt(), source)
}

private const val CAPTURE_FIELD_COUNT = 8

private fun parseFlatObject(text: String): Map<String, Any>? {
    var index = 0
    val length = text.length

    fun skipWhitespace() {
        while (index < length && text[index].isWhitespace()) index++
    }

    fun parseString(): String? {
        if (index >= length || text[index] != '"') return null
        index++
        val value = StringBuilder()
        while (index < length) {
            val character = text[index++]
            when {
                character == '"' -> return value.toString()
                character == '\\' -> {
                    if (index >= length) return null
                    when (text[index++]) {
                        '"' -> value.append('"')
                        '\\' -> value.append('\\')
                        '/' -> value.append('/')
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000C')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            if (index + 4 > length) return null
                            val code = text.substring(index, index + 4).toIntOrNull(16) ?: return null
                            value.append(code.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                }
                character < ' ' -> return null
                else -> value.append(character)
            }
        }
        return null
    }

    fun parseNumber(): Long? {
        val start = index
        if (index < length && text[index] == '-') index++
        val digits = index
        while (index < length && text[index].isDigit()) index++
        if (index == digits) return null
        return text.substring(start, index).toLongOrNull()
    }

    val fields = HashMap<String, Any>()
    skipWhitespace()
    if (index >= length || text[index] != '{') return null
    index++
    skipWhitespace()
    if (index < length && text[index] == '}') {
        index++
        skipWhitespace()
        return if (index == length) fields else null
    }
    while (true) {
        skipWhitespace()
        val key = parseString() ?: return null
        skipWhitespace()
        if (index >= length || text[index] != ':') return null
        index++
        skipWhitespace()
        val value: Any = if (index < length && text[index] == '"') {
            parseString() ?: return null
        } else {
            parseNumber() ?: return null
        }
        if (fields.put(key, value) != null) return null
        skipWhitespace()
        if (index >= length) return null
        when (text[index]) {
            ',' -> index++
            '}' -> {
                index++
                skipWhitespace()
                return if (index == length) fields else null
            }
            else -> return null
        }
    }
}
