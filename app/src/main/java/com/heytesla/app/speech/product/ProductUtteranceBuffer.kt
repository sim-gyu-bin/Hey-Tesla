package com.heytesla.app.speech.product

import com.heytesla.app.vehicle.frunk.FrunkVoicePolicy

/**
 * 무음·발화 후보 대기에는 작은 순환 pre-roll만 덮어쓴다. 연속 120ms 유성 확인 뒤에만 최대 10초 PCM을 할당한다.
 * endpoint는 실제 수신한 무음 표본으로 판정한다. 스케줄 지연/빈 read를 발화 종료로 오인하지 않는다.
 * 기존 SpeechMicCapture와 같은 PCM16/RMS 임계값·1초 endpoint 휴리스틱이며 화자 인증이 아니다.
 */
internal class ProductUtteranceBuffer {
    enum class Status { WAITING, CAPTURING, COMPLETE, TRUNCATED, CLOCK_REVERSED }

    private val prefix = ByteArray(PRE_ROLL_BYTES)
    private var prefixBytes = 0
    private var prefixNext = 0
    private var silenceBytes = 0
    private var candidateBytes = 0
    private var candidateStartedAt: Long? = null
    private var lastTime: Long? = null
    var pcm: ByteArray? = null
        private set
    var bytes = 0
        private set
    var voicedBytes = 0
        private set
    var suppressedOnsets = 0
        private set
    val silenceBytesAtEnd: Int
        get() = silenceBytes
    var startedAt: Long? = null
        private set
    var status = Status.WAITING
        private set

    fun checkTime(now: Long): Status {
        if (status != Status.WAITING && status != Status.CAPTURING) return status
        if (now < 0 || lastTime?.let { now < it } == true) status = Status.CLOCK_REVERSED
        lastTime = now
        if (status == Status.CAPTURING && now - requireNotNull(startedAt) >= FrunkVoicePolicy.COMMAND_MS) {
            status = Status.TRUNCATED
        }
        return status
    }

    fun append(chunk: ByteArray, count: Int, now: Long): Status {
        require(count in 0..chunk.size && count <= CHUNK_BYTES && count % 2 == 0)
        checkTime(now)
        if (count == 0 || (status != Status.WAITING && status != Status.CAPTURING)) return status
        val voiced = voiced(chunk, count)
        if (status == Status.WAITING) {
            appendPrefix(chunk, count)
            if (!voiced) {
                if (candidateBytes > 0 && suppressedOnsets < Int.MAX_VALUE) suppressedOnsets++
                candidateBytes = 0
                candidateStartedAt = null
                return status
            }
            if (candidateBytes == 0) {
                candidateStartedAt = (now - count * 1_000L / BYTES_PER_SECOND).coerceAtLeast(0)
            }
            candidateBytes += count
            if (candidateBytes < ONSET_BYTES) return status
            // 확인 시각이 아니라 첫 후보 chunk의 시작으로 명령 기한을 고정한다.
            startedAt = requireNotNull(candidateStartedAt)
            voicedBytes = candidateBytes
            candidateBytes = 0
            candidateStartedAt = null
            if (now - requireNotNull(startedAt) >= FrunkVoicePolicy.COMMAND_MS) {
                status = Status.TRUNCATED
                return status
            }
            val target = ByteArray(MAX_BYTES)
            pcm = target
            val offset = if (prefixBytes == prefix.size) prefixNext else 0
            val first = minOf(prefixBytes, prefix.size - offset)
            prefix.copyInto(target, 0, offset, offset + first)
            prefix.copyInto(target, first, 0, prefixBytes - first)
            bytes = prefixBytes
            prefix.fill(0)
            status = Status.CAPTURING
            // 후보와 확인 chunk 모두 이미 pre-roll에 있으므로 다시 붙이지 않는다.
            return status
        }
        val target = requireNotNull(pcm)
        // 한도에 닿은 입력은 마지막 무음 여부와 관계없이 잘린 발화다. 부분 명령을 인식하지 않는다.
        if (count >= target.size - bytes) {
            status = Status.TRUNCATED
            return status
        }
        chunk.copyInto(target, bytes, 0, count)
        bytes += count
        if (voiced) voicedBytes += count
        silenceBytes = if (voiced) 0 else silenceBytes + count
        if (silenceBytes >= ENDPOINT_BYTES) status = Status.COMPLETE
        return status
    }

    fun clear() {
        prefix.fill(0)
        pcm?.fill(0)
        pcm = null
        prefixBytes = 0
        prefixNext = 0
        bytes = 0
        silenceBytes = 0
        candidateBytes = 0
        candidateStartedAt = null
        voicedBytes = 0
        suppressedOnsets = 0
        // clear는 재사용/새 회차가 아니다. 늦은 append로 버퍼를 되살리지 못한다.
        if (status == Status.WAITING || status == Status.CAPTURING) status = Status.TRUNCATED
    }

    private fun appendPrefix(chunk: ByteArray, count: Int) {
        val first = minOf(count, prefix.size - prefixNext)
        chunk.copyInto(prefix, prefixNext, 0, first)
        chunk.copyInto(prefix, 0, first, count)
        prefixNext = (prefixNext + count) % prefix.size
        prefixBytes = minOf(prefix.size, prefixBytes + count)
    }

    private fun voiced(pcm: ByteArray, count: Int): Boolean {
        var sum = 0L
        var index = 0
        while (index < count) {
            val sample = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toLong() * sample
            index += 2
        }
        return sum >= VOICE_RMS.toLong() * VOICE_RMS * (count / 2)
    }

    companion object {
        const val RATE = 16_000
        const val BYTES_PER_SECOND = RATE * 2
        const val CHUNK_BYTES = 640 // 20ms; idle에서도 하나만 재사용한다.
        const val MAX_BYTES = 320_000
        private const val PRE_ROLL_BYTES = BYTES_PER_SECOND / 5
        private const val ONSET_BYTES = BYTES_PER_SECOND * 120 / 1_000
        private const val ENDPOINT_BYTES = BYTES_PER_SECOND
        private const val VOICE_RMS = 1_000
    }
}
