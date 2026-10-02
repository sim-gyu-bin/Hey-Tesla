package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test

class BleCccdSubscriptionTest {
    @Test fun duplicateEnableCompletionCannotConfirmDisableWithoutReadback() {
        val subscription = BleCccdSubscription()
        assertTrue(subscription.begin(true))
        assertEquals(BleCccdWriteResult.ENABLED, subscription.writeCompleted(true))
        assertTrue(subscription.begin(false))

        // enable 중복과 disable 완료는 callback 인수만으로 구분할 수 없다.
        assertEquals(BleCccdWriteResult.VERIFY_DISABLED, subscription.writeCompleted(true))
        assertFalse(subscription.begin(true))
        assertEquals(BleCccdWriteResult.IGNORE, subscription.writeCompleted(true))
        assertEquals(false, subscription.readCompleted(true, byteArrayOf(2, 0)))
        assertNull(subscription.readCompleted(true, byteArrayOf(0, 0)))
    }

    @Test fun onlySuccessfulTwoByteDisabledReadbackConfirmsUnsubscribe() {
        for (value in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 0, 0), byteArrayOf(0, 1), byteArrayOf(1, 0))) {
            val subscription = verifyingDisable()
            assertEquals(false, subscription.readCompleted(true, value))
        }
        assertEquals(false, verifyingDisable().readCompleted(false, byteArrayOf(0, 0)))
        val confirmed = verifyingDisable()
        assertEquals(true, confirmed.readCompleted(true, byteArrayOf(0, 0)))
        assertNull(confirmed.readCompleted(true, byteArrayOf(0, 0)))
    }

    @Test fun failedOrAbortedWritesCannotAcceptAnUnsolicitedReadback() {
        val subscription = BleCccdSubscription()
        assertTrue(subscription.begin(false))
        assertEquals(BleCccdWriteResult.DISABLE_FAILED, subscription.writeCompleted(false))
        assertNull(subscription.readCompleted(true, byteArrayOf(0, 0)))
        assertTrue(subscription.begin(true))
        subscription.abort()
        assertEquals(BleCccdWriteResult.IGNORE, subscription.writeCompleted(true))
        assertNull(subscription.readCompleted(true, byteArrayOf(0, 0)))
    }

    private fun verifyingDisable() = BleCccdSubscription().apply {
        assertTrue(begin(false))
        assertEquals(BleCccdWriteResult.VERIFY_DISABLED, writeCompleted(true))
    }
}
