package com.heytesla.app.speech.product

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heytesla.app.speech.KoreanSpeechRecognizer
import com.heytesla.app.speech.PreparedKoreanSpeechRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실제 APK 모델을 한 번 준비하고 두 독립 native stream→제품 정책→owner full-close를 실행한다.
 * RAM 무음만 사용한다. 녹음 저장소·Android STT·마이크·서비스·차량은 호출하지 않는다.
 * capture 해제와 정책 시각만 fixture이며 기기 성능·실차 전송 검증을 대신하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class ProductKoreanSpeechDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realModelSilenceCannotAuthorizeProductCommand() {
        val pcm = ByteArray(16_000 * 2)
        val session = ProductVoiceSession()
        var owner: PreparedKoreanSpeechRecognizer? = null
        try {
            assertTrue(session.start())
            assertTrue(session.beginPreparation())
            assertFalse(session.beginCapture())
            val preparation = KoreanSpeechRecognizer.prepare(context.assets)
            owner = preparation.owner
            session.preparationFinished(owner != null, preparation.recognizerCleanupConfirmed, preparation.failure)
            assertNull("실제 APK 모델 준비 실패", preparation.failure)
            assertNotNull(preparation.loadMs)
            assertFalse(preparation.recognizerCleanupConfirmed)
            val prepared = requireNotNull(owner)
            repeat(2) { index ->
                assertTrue(session.canCapture)
                assertTrue(session.beginCapture())
                session.captureFinished(released = true, clean = true)
                assertTrue(session.beginRecognition())
                val started = SystemClock.elapsedRealtime()
                val result = prepared.decode(pcm, pcm.size)
                val elapsedMs = SystemClock.elapsedRealtime() - started
                assertNull("실제 native stream decode 실패", result.failure)
                assertTrue("실제 stream 해제 확인 필요", result.streamCleanupConfirmed)
                assertTrue(result.decodeCompleted)
                assertEquals(pcm.size, result.consumedBytes)
                assertNotNull(result.decodeMs)
                assertEquals(index > 0, result.modelReused)
                val decision = session.acceptRecognition(result, pcm.size, 0, 1)
                // 원문·PCM·식별자를 출력하지 않고 관측된 수치와 정책 판정만 남긴다.
                Log.i("HTProductSpeechTest", "fixture=prepared-silence-policy-clock-fixture stream=$index " +
                    "decision=$decision failure=${result.failure} streamCleanup=${result.streamCleanupConfirmed} " +
                    "modelReused=${result.modelReused} loadMs=${preparation.loadMs} " +
                    "decodeMs=${result.decodeMs} cleanupMs=${result.cleanupMs} elapsedMs=$elapsedMs")
                assertEquals(ProductVoiceSession.Decision.WAIT, decision)
                assertNull(session.stopReason.get())
                assertTrue(session.microphoneReleased)
                assertTrue(session.streamCleanupConfirmed)
                assertFalse("보유 중인 engine을 full cleanup으로 보고하면 안 된다", session.cleanupConfirmed)
            }
            session.stop("PRODUCT_VOICE_DEVICE_TEST_FINISHED")
        } finally {
            pcm.fill(0)
            owner?.let { prepared ->
                val cleanup = prepared.close()
                session.recognizerClosed(cleanup)
                assertTrue("실제 native stream 정리 필요", cleanup.streamCleanupConfirmed)
                assertTrue("실제 native recognizer 정리 필요", cleanup.recognizerCleanupConfirmed)
                assertSame(cleanup, prepared.close())
                Log.i("HTProductSpeechTest", "fixture=prepared-silence-full-close " +
                    "streamCleanup=${cleanup.streamCleanupConfirmed} " +
                    "recognizerCleanup=${cleanup.recognizerCleanupConfirmed} cleanupMs=${cleanup.cleanupMs}")
            }
        }
        val completion = requireNotNull(session.complete(2))
        assertNull(completion.gate)
        assertTrue(completion.cleanupConfirmed)
        assertTrue(completion.microphoneReleased)
        assertEquals("PRODUCT_VOICE_DEVICE_TEST_FINISHED", completion.reason)
        assertFalse(session.beginCapture())
    }
}
