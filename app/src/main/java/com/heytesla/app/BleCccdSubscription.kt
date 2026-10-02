package com.heytesla.app

internal enum class BleCccdWriteResult {
    IGNORE, ENABLED, ENABLE_FAILED, VERIFY_DISABLED, DISABLE_FAILED,
}

/** CCCD write callback에는 요청 ID가 없다. 해제 확인은 별도의 단일 readback만 증거로 쓴다. */
internal class BleCccdSubscription {
    private enum class Phase { IDLE, ENABLING, DISABLING, VERIFYING_DISABLED }
    private var phase = Phase.IDLE

    fun begin(enabled: Boolean): Boolean {
        if (phase != Phase.IDLE) return false
        phase = if (enabled) Phase.ENABLING else Phase.DISABLING
        return true
    }

    fun writeCompleted(success: Boolean): BleCccdWriteResult = when (phase) {
        Phase.ENABLING -> {
            phase = Phase.IDLE
            if (success) BleCccdWriteResult.ENABLED else BleCccdWriteResult.ENABLE_FAILED
        }
        Phase.DISABLING -> {
            phase = if (success) Phase.VERIFYING_DISABLED else Phase.IDLE
            if (success) BleCccdWriteResult.VERIFY_DISABLED else BleCccdWriteResult.DISABLE_FAILED
        }
        Phase.IDLE, Phase.VERIFYING_DISABLED -> BleCccdWriteResult.IGNORE
    }

    fun readCompleted(success: Boolean, value: ByteArray): Boolean? {
        if (phase != Phase.VERIFYING_DISABLED) return null
        phase = Phase.IDLE
        return success && value.size == 2 && value[0] == 0.toByte() && value[1] == 0.toByte()
    }

    fun abort() { phase = Phase.IDLE }
}
