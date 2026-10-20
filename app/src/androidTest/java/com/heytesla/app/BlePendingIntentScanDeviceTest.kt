package com.heytesla.app

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** native PI 등록/윈도우 구현 테스트는 폐기했다. legacy 정리 receiver의 외부 UID 경계만 유지한다. */
@RunWith(AndroidJUnit4::class)
class BlePendingIntentScanDeviceTest {
    @Test fun installedLegacyCleanupReceiverIsNotExportedToForeignUidBroadcasts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, BleScanReceiver::class.java), 0)
        assertFalse("외부 UID가 legacy scan 정리를 발급할 수 없어야 한다", receiver.exported)
        assertEquals(context.applicationInfo.uid, receiver.applicationInfo.uid)
    }
}
