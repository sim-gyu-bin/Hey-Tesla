package com.heytesla.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 이후 명시 ON보다 새 사용자 종료만 복원을 막는다. Android의 stopped-package 제한은 별도로 유지된다. */
internal fun bleDetectionRequiresExplicitStart(
    previousExitReason: Int?,
    previousExitWallMs: Long?,
    lastUserEnabledWallMs: Long,
): Boolean = previousExitReason == android.app.ApplicationExitInfo.REASON_USER_REQUESTED &&
    (previousExitWallMs ?: 0L) >= lastUserEnabledWallMs

/** 차량 키·VIN·이전 근접 상태·명령은 저장하지 않는다. 광고명 감지만 복원한다. */
internal class BleDetectionPreference(context: Context, name: String) {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun enabled(): Boolean = preferences.getBoolean("enabled", false)
    fun lastUserEnabledWallMs(): Long = preferences.getLong("last_user_enabled_wall_ms", 0L)
    fun setEnabled(enabled: Boolean, userInitiated: Boolean = true): Boolean {
        val edit = preferences.edit().putBoolean("enabled", enabled)
        if (enabled && userInitiated) edit.putLong("last_user_enabled_wall_ms", System.currentTimeMillis())
        return edit.commit()
    }
}

/** 시스템 이벤트는 새 관측만 요청한다. 강제 종료는 Android의 stopped-package 규칙을 따른다. */
class BleDetectionRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        (context.applicationContext as DiagnosticApp).runtime.restoreBleDetection()
    }
}
