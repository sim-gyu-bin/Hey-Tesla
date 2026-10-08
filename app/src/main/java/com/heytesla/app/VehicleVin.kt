package com.heytesla.app

import java.util.Locale

enum class VehicleVinStatus { LOADING, NOT_REGISTERED, READY, SAVING, FAILED }

data class VehicleVinState(
    val status: VehicleVinStatus = VehicleVinStatus.LOADING,
    val maskedVin: String? = null,
    val reason: String? = null,
)

/** 원문은 작업 시작 스냅샷에만 사용한다. data class/copy/toString에 식별자를 싣지 않는다. */
internal class VehicleVin private constructor(private val normalized: String) {
    val masked: String get() = "*************" + normalized.takeLast(4)
    fun snapshot(): String = normalized
    override fun toString(): String = "VehicleVin(REDACTED)"

    companion object {
        private val inputPattern = Regex("[A-HJ-NPR-Za-hj-npr-z0-9]{17}")
        fun isValid(input: String): Boolean = inputPattern.matches(input.trim())
        fun parse(input: String): VehicleVin? {
            val trimmed = input.trim()
            if (!inputPattern.matches(trimmed)) return null
            return VehicleVin(trimmed.uppercase(Locale.ROOT))
        }
    }
}

/** 원인 예외·파일 경로·키 alias·식별자를 전달하지 않는다. */
internal class VehicleVinStorageException(val code: String) : IllegalStateException(code)
