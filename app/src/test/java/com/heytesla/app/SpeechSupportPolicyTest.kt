package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test

class SpeechSupportPolicyTest {
    @Test fun pendingDownloadableAndOnlineNeverEstablishInstallation() {
        val metadata = SpeechSupportMetadata(
            installed = SpeechSupportPolicy.classify(listOf("ko", "ko-KP")),
            pending = SpeechSupportPolicy.classify(listOf("ko-KR")),
            downloadable = SpeechSupportPolicy.classify(listOf("ko-KR")),
            online = SpeechSupportPolicy.classify(listOf("ko-KR")),
        )
        assertFalse(metadata.koKrInstalled)
        assertTrue(metadata.copy(installed = SpeechSupportPolicy.classify(listOf("KO-kr"))).koKrInstalled)
    }

    @Test fun onlyExactKoreanRegionTagEstablishesTargetSupport() {
        val otherKorean = SpeechSupportPolicy.classify(listOf("ko", "ko-KP", "ko-KR-x-private"))
        assertFalse(otherKorean.exactKoKr)
        assertTrue(otherKorean.otherKorean)
        val exact = SpeechSupportPolicy.classify(listOf("kO-Kr"))
        assertTrue(exact.exactKoKr)
        assertFalse(exact.otherKorean)
        assertFalse(SpeechSupportPolicy.classify(listOf("ko_KR", " ko-KR", "ko-KR ")).exactKoKr)
    }
}
