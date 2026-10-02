package com.heytesla.app

/** 로컬 진단 전용 계약. 계정·차량 키·통신·실차 전송 API를 받지 않는다. */
internal fun interface VehicleGateway {
    fun dryRunFrunkOpen(): DiagnosticCommandResult
}

/** 명령을 외부로 보내지 않고 로컬 dry-run 한 건의 처리만 반환한다. */
internal class DryRunVehicleGateway : VehicleGateway {
    override fun dryRunFrunkOpen() = DiagnosticCommandResult.PROCESSED
}
