package com.heytesla.app

/** PROCESSED는 로컬 dry-run 처리이며 차량 승인·전송·물리 완료가 아니다. */
enum class DiagnosticCommandResult { NOT_ATTEMPTED, REJECTED, CANCELED, EXPIRED, PROCESSED, UNKNOWN }

/**
 * 한 진단 소유권의 once-only 전이. 인식 콜백·취소·전송 시작은 모두 메인 스레드에서 직렬 처리한다.
 * 신뢰도를 제외하는 승인은 이 로컬 진단에만 적용한다. 파서 후보 범위는 확장하지 않는다.
 * 실행 결과를 기다리기 전에 UNKNOWN으로 봉인하므로 예외·재진입·반복 결과도 재시도하지 못한다.
 */
internal class DiagnosticCommandPolicy(private val deadline: Long) {
    var result = DiagnosticCommandResult.NOT_ATTEMPTED
        private set

    fun cancel() {
        if (result == DiagnosticCommandResult.NOT_ATTEMPTED) result = DiagnosticCommandResult.CANCELED
    }

    fun decide(
        now: Long,
        finalReceived: Boolean,
        decision: SpeechCommandDecision?,
        gateway: VehicleGateway,
    ): DiagnosticCommandResult {
        if (result != DiagnosticCommandResult.NOT_ATTEMPTED) return result
        result = when {
            now >= deadline -> DiagnosticCommandResult.EXPIRED
            !finalReceived || decision != SpeechCommandDecision.FRUNK_OPEN_CANDIDATE -> DiagnosticCommandResult.REJECTED
            else -> {
                result = DiagnosticCommandResult.UNKNOWN
                try {
                    when (gateway.dryRunFrunkOpen()) {
                        DiagnosticCommandResult.PROCESSED -> DiagnosticCommandResult.PROCESSED
                        DiagnosticCommandResult.REJECTED -> DiagnosticCommandResult.REJECTED
                        else -> DiagnosticCommandResult.UNKNOWN
                    }
                } catch (_: Exception) {
                    DiagnosticCommandResult.UNKNOWN
                }
            }
        }
        return result
    }
}
