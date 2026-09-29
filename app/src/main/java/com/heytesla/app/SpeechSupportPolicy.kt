package com.heytesla.app

internal data class KoreanLanguageSupport(val exactKoKr: Boolean, val otherKorean: Boolean)

internal data class SpeechSupportMetadata(
    val installed: KoreanLanguageSupport,
    val pending: KoreanLanguageSupport,
    val downloadable: KoreanLanguageSupport,
    val online: KoreanLanguageSupport,
) {
    val koKrInstalled: Boolean get() = installed.exactKoKr
}

internal object SpeechSupportPolicy {
    fun classify(languages: List<String>): KoreanLanguageSupport = KoreanLanguageSupport(
        exactKoKr = languages.any { it.equals("ko-KR", ignoreCase = true) },
        otherKorean = languages.any {
            !it.equals("ko-KR", ignoreCase = true) &&
                (it.equals("ko", ignoreCase = true) || it.startsWith("ko-", ignoreCase = true))
        },
    )
}
