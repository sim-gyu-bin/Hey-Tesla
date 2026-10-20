package com.heytesla.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 차량 키·VIN·이전 근접 상태·명령은 저장하지 않는다. 광고명 감지만 복원한다. */
internal class BleDetectionPreference(context: Context, name: String) {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun enabled(): Boolean = preferences.getBoolean("enabled", false)
    fun setEnabled(enabled: Boolean): Boolean = preferences.edit().putBoolean("enabled", enabled).commit()
}

/** 시스템 이벤트는 새 관측만 요청한다. 강제 종료는 Android의 stopped-package 규칙을 따른다. */
class BleDetectionRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        (context.applicationContext as DiagnosticApp).runtime.restoreBleDetection()
    }
}
