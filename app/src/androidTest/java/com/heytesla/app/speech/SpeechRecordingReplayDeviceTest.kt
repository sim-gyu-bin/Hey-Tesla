package com.heytesla.app.speech

import com.heytesla.app.R
import com.heytesla.app.vehicle.frunk.FrunkVoicePolicy

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

/**
 * ADB 개발 전용: 저장 PCM → Android 온디바이스 SpeechRecognizer(ko-KR) → 결과 파일·logcat.
 * 마이크·차량 런타임을 호출하지 않는다.
 */
class SpeechRecordingReplayDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun replaySavedRecording() {
        val id = InstrumentationRegistry.getArguments().getString("recordingId")
        assumeNotNull(id)
        val store = SpeechRecordingStore(context)
        val recording = store.load(requireNotNull(id))
        val started = SystemClock.elapsedRealtime()
        try {
            val result = recognize(recording.pcm, recording.pcm.size)
            store.saveResult(recording.info.id, result.candidates.firstOrNull()?.text, result.failure,
                SystemClock.elapsedRealtime() - started)
            assertEquals(recording.pcm.size, result.acceptedBytes)
        } finally {
            recording.pcm.fill(0)
        }
    }

    @Test fun savedSyntheticSpeechDecodesIdentically() {
        val pcm = ByteArray(SpeechMicCapture.MAX_BYTES)
        try {
            val size = context.resources.openRawResource(R.raw.speech_trial_ko).use {
                SpeechPcmFixture.read(it, pcm) { false }
            }
            val store = SpeechRecordingStore(context)
            val info = store.save(pcm, size, "SYNTHETIC_FIXTURE")
            val restored = store.load(info.id)
            try {
                assertArrayEquals("파일 보관·복원 중 PCM 변형", pcm.copyOf(size), restored.pcm)
                val direct = recognize(pcm, size)
                val started = SystemClock.elapsedRealtime()
                val replay = recognize(restored.pcm, restored.pcm.size)
                assertNull(direct.failure)
                assertNull(replay.failure)
                assertEquals(size, direct.acceptedBytes)
                assertEquals(size, replay.acceptedBytes)
                assertTrue("합성 음성에서 후보 없음", replay.candidates.isNotEmpty())
                store.saveResult(info.id, replay.candidates.first().text, replay.failure,
                    SystemClock.elapsedRealtime() - started)
            } finally { restored.pcm.fill(0) }
        } finally { pcm.fill(0) }
    }

    /**
     * 보관 녹음 전체와 합성 fixture를 실제 프렁크 문법 게이트에 넣어 logcat(HTSTT)에만 남긴다. 결과 파일은 바꾸지 않는다.
     * sherpa-onnx 그림자 인식은 원문 없이 일치·판정·시간만 남긴다.
     */
    @Test fun replayAll() {
        val store = SpeechRecordingStore(context)
        val pcm = ByteArray(SpeechMicCapture.MAX_BYTES)
        try {
            val size = context.resources.openRawResource(R.raw.speech_trial_ko).use {
                SpeechPcmFixture.read(it, pcm) { false }
            }
            report("fixture", pcm, size)
        } finally { pcm.fill(0) }
        for (info in store.list().sortedBy { it.id }) {
            val recording = store.load(info.id)
            try { report(info.id.take(8), recording.pcm, recording.pcm.size) } finally { recording.pcm.fill(0) }
        }
    }

    private fun report(name: String, pcm: ByteArray, size: Int) {
        val result = recognize(pcm, size)
        val texts = AndroidPcmRecognizer.texts(result.candidates)
        val scores = AndroidPcmRecognizer.scores(result.candidates)
        val gate = FrunkVoicePolicy(deadline = Long.MAX_VALUE, startedAtElapsedMs = 0L)
        val accepted = result.failure == null &&
            gate.command(1L, SpeechTrialMode.DIRECT_MIC, final = true, candidates = texts, confidence = scores)
        Log.i(TAG, "$name segments=${result.segments.size} candidates=$texts " +
            "scores=${scores?.joinToString(",", "[", "]")} failure=${result.failure} " +
            "accepted=$accepted reason=${gate.reason} cleanup=${result.cleanupFailures}")
        assertEquals("$name 정리 실패", emptyList<String>(), result.cleanupFailures)
        val shadow = SherpaShadowRecognizer.decode(context.assets, pcm, size).judged(SpeechCommandPhrases.normalizedSentence)
        Log.i(TAG, "$name shadow matched=${shadow.matched} decision=${shadow.decision} empty=${shadow.text.isNullOrEmpty()} " +
            "failure=${shadow.failure} loadMs=${shadow.loadMs} decodeMs=${shadow.decodeMs}")
        assertNull("$name 그림자 인식 실패", shadow.failure)
    }

    private fun recognize(pcm: ByteArray, size: Int): AndroidPcmResult = runBlocking {
        AndroidPcmRecognizer(context).recognize(pcm, size, RECOGNITION_TIMEOUT_MS) { false }
    }

    private companion object {
        const val TAG = "HTSTT"
        const val RECOGNITION_TIMEOUT_MS = 30_000L
    }
}
