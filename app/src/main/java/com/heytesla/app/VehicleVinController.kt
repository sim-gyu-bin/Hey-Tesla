package com.heytesla.app

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main에서 입장/상태 변경을 직렬화한다. 저장·복원은 IO에서만 수행하며 어떤 작업도 시작하지 않는다. */
internal class VehicleVinController(
    private val store: VehicleVinStore,
    private val scope: CoroutineScope,
    private val workBlockedReason: () -> String?,
    private val publish: (VehicleVinState) -> Unit,
) {
    var state = VehicleVinState()
        private set
    private var vin: VehicleVin? = null
    private var restoreRequested = false

    fun restore() {
        mainThread()
        check(!restoreRequested)
        restoreRequested = true
        scope.launch {
            val restored = try {
                withContext(Dispatchers.IO) { store.restore() }
            } catch (_: Exception) {
                vin = null
                change(VehicleVinState(VehicleVinStatus.FAILED, reason = "VIN_RESTORE_FAILED"))
                return@launch
            }
            vin = restored
            change(if (restored == null) VehicleVinState(VehicleVinStatus.NOT_REGISTERED)
                else VehicleVinState(VehicleVinStatus.READY, restored.masked))
        }
    }

    fun changeBlockedReason(): String? {
        mainThread()
        return when (state.status) {
            VehicleVinStatus.LOADING -> "VIN_LOADING"
            VehicleVinStatus.SAVING -> "VIN_SAVING"
            else -> workBlockedReason()
        }
    }

    fun readinessReason(): String? = when (state.status) {
        VehicleVinStatus.LOADING -> "VIN_LOADING"
        VehicleVinStatus.NOT_REGISTERED -> "VIN_NOT_REGISTERED"
        VehicleVinStatus.SAVING -> "VIN_SAVING"
        VehicleVinStatus.FAILED -> state.reason ?: "VIN_RESTORE_FAILED"
        VehicleVinStatus.READY -> null
    }

    fun snapshot(): String? {
        mainThread()
        return if (state.status == VehicleVinStatus.READY) vin?.snapshot() else null
    }

    fun save(input: String) {
        mainThread()
        changeBlockedReason()?.let { reason -> change(state.copy(reason = reason)); return }
        val candidate = VehicleVin.parse(input)
        if (candidate == null) { change(state.copy(reason = "VIN_FORMAT_INVALID")); return }
        change(state.copy(status = VehicleVinStatus.SAVING, reason = null))
        scope.launch {
            val saved = try {
                withContext(Dispatchers.IO) { store.save(candidate) }
            } catch (_: Exception) {
                // 디스크의 이전 암호문은 보존하지만 실패한 변경 뒤에는 새 작업 대상으로 재사용하지 않는다.
                vin = null
                change(state.copy(status = VehicleVinStatus.FAILED, reason = "VIN_SAVE_FAILED"))
                return@launch
            }
            vin = saved
            change(VehicleVinState(VehicleVinStatus.READY, saved.masked))
        }
    }

    private fun change(next: VehicleVinState) {
        mainThread()
        state = next
        publish(next)
    }

    private fun mainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "VIN_STATE_REQUIRES_MAIN" }
    }
}
