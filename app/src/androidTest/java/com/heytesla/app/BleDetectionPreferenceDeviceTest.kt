package com.heytesla.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 생산 활성화 설정과 키·차량 데이터를 건드리지 않는다. */
@RunWith(AndroidJUnit4::class)
class BleDetectionPreferenceDeviceTest {
    @Test fun explicitOffSurvivesRecreationAndDoesNotChangeAnotherActivationStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ble_activation_test_${UUID.randomUUID()}"
        val independent = BleDetectionPreference(context, "${name}_independent")
        val first = BleDetectionPreference(context, name)
        assertFalse(first.enabled())
        assertTrue(first.setEnabled(true))
        val recreated = BleDetectionPreference(context, name)
        assertTrue(recreated.enabled())
        assertFalse(independent.enabled())
        assertTrue(recreated.setEnabled(false))
        assertFalse(BleDetectionPreference(context, name).enabled())
        assertFalse(independent.enabled())
    }
}
