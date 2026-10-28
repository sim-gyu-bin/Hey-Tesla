package com.heytesla.app

import android.content.AttributionSource
import android.content.Intent
import android.os.RemoteException
import android.speech.ModelDownloadListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** 비서 등록에 필요한 컴포넌트일 뿐, 어떤 언어의 STT도 제공하지 않는다. */
class UnsupportedRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        reject(listener)
    }

    override fun onStopListening(listener: Callback) {
        reject(listener)
    }

    override fun onCancel(listener: Callback) {
        // 시작 시 즉시 오류로 종료하므로 녹음기·작업·모델 등 취소할 자원이 없다.
        // 취소된 요청에 성공/오류 결과를 추가 전달하지 않는다.
    }

    override fun onCheckRecognitionSupport(recognizerIntent: Intent, supportCallback: SupportCallback) {
        supportCallback.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
    }

    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        attributionSource: AttributionSource,
        supportCallback: SupportCallback,
    ) {
        supportCallback.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
    }

    override fun onTriggerModelDownload(recognizerIntent: Intent) {
        // 이 오버로드에는 결과 콜백이 없다. 다운로드나 외부 인식을 시작하지 않는다.
        (application as DiagnosticApp).runtime.event("STT_MODEL_DOWNLOAD_UNSUPPORTED")
    }

    override fun onTriggerModelDownload(recognizerIntent: Intent, attributionSource: AttributionSource) {
        onTriggerModelDownload(recognizerIntent)
    }

    override fun onTriggerModelDownload(
        recognizerIntent: Intent,
        attributionSource: AttributionSource,
        listener: ModelDownloadListener,
    ) {
        listener.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
    }

    private fun reject(listener: Callback) {
        try {
            listener.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        } catch (_: RemoteException) {
            // 요청자가 이미 종료된 경우에도 보유 자원이나 재시도할 작업이 없다.
        }
    }
}
