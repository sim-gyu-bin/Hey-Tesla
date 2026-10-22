package com.heytesla.app

/**
 * 표시용 고정 호출어·명령의 단일 원본. 정규화·전체 문장·hotword는 초기화 때 한 번만 파생한다.
 * [SpeechCommandParser]의 초기화 필드에서는 이 객체를 참조하지 않아 정규화 초기화 순환을 피한다.
 */
internal object SpeechCommandPhrases {
    val displayWakeWord = "헤이 테슬라"
    val displayCommand = "프렁크 열어줘"
    val normalizedWakeWord = SpeechCommandParser.normalize(displayWakeWord)
    val normalizedCommand = SpeechCommandParser.normalize(displayCommand)
    val displaySentence = "$displayWakeWord $displayCommand"
    val normalizedSentence = normalizedWakeWord + normalizedCommand

    /** sherpa가 "/"를 줄바꿈으로 바꿔 호출어·명령 두 구간만 각각 부스트한다. */
    val sherpaHotwords = "$displayWakeWord/$displayCommand"
}
