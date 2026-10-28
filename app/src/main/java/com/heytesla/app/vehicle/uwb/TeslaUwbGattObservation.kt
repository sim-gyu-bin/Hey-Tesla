package com.heytesla.app.vehicle.uwb

import com.heytesla.app.vehicle.ble.BleProbeState
import com.heytesla.app.vehicle.ble.BleProbeStatus
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.protocol.TeslaBleResponseObservation

internal enum class TeslaUwbGattEvent {
    STAGE, CONNECT_RESULT, CONNECTION_CALLBACK, DESCRIPTOR_CALLBACK, RX, PROTOCOL, WRITE,
    RETRY_SCHEDULED, RETRY_STARTED, RETRY_CANCELED, LOCAL_CLOSED, CLEANUP_FAILED,
}

/** 같은 작업의 실제 monotonic 관측만 담는다. null은 미관측이며 연결 성공과 로컬 종료는 별개다. */
internal data class TeslaUwbGattObservation(
    val stage: TeslaKeyStage,
    val event: TeslaUwbGattEvent,
    val operationStartedElapsedMs: Long,
    val stageStartedElapsedMs: Long,
    val observedElapsedMs: Long,
    val connectIssuedElapsedMs: Long? = null,
    val connectReturnedElapsedMs: Long? = null,
    val autoConnect: Boolean? = null,
    val connectAction: String? = null,
    val connectAccepted: Boolean? = null,
    val advertisementAgeAtConnectMs: Long? = null,
    val connectionStatus: Int? = null,
    val connectionState: Int? = null,
    val connectionCallbackElapsedMs: Long? = null,
    val descriptorRequestStatus: Int? = null,
    val descriptorCallbackStatus: Int? = null,
    val descriptorCallbackElapsedMs: Long? = null,
    val firstRxElapsedMs: Long? = null,
    val lastRxElapsedMs: Long? = null,
    /** 이번 작업에서 성공 연결을 관측했는지이며 종료 당시 연결 상태가 아니다. */
    val connectedObserved: Boolean = false,
    val serviceFound: Boolean = false,
    val txFound: Boolean = false,
    val rxFound: Boolean = false,
    val subscriptionConfirmed: Boolean = false,
    val rxNotificationCount: Long = 0,
    /** null은 이 수신 관측을 지원하지 않는 작업, 0은 서비스 작업의 실제 무수신이다. */
    val rxFrameCount: Long? = null,
    val rxProtocolCount: Long? = null,
    /** 마지막 notification의 decoder 반환값. 예외·미호출이면 완성 수를 추정하지 않는다. */
    val rxNotificationFrameCount: Int? = null,
    val rxDecoderIncomplete: Boolean? = null,
    val rxDecoderFailed: Boolean? = null,
    val rxProtocolReceivedElapsedMs: Long? = null,
    val rxProtocolObservedElapsedMs: Long? = null,
    val rxResponse: TeslaBleResponseObservation? = null,
) {
    /** 완성 frame은 queue 접수·실제 protocol 소비와 무관하게 decoder 성공 반환에서만 센다. */
    fun withDecoderResult(frameCount: Int?, incomplete: Boolean?, failed: Boolean?) = copy(
        rxFrameCount = rxFrameCount?.let { count ->
            val added = frameCount?.toLong() ?: 0L
            if (count > Long.MAX_VALUE - added) Long.MAX_VALUE else count + added
        },
        rxNotificationFrameCount = frameCount,
        rxDecoderIncomplete = incomplete,
        rxDecoderFailed = failed,
    )

    /** 실제 receive 호출 직전. 이전 protocol의 결과·관측 시각을 새 호출에 붙이지 않는다. */
    fun beginningProtocol(receivedElapsedMs: Long) = copy(
        rxProtocolCount = rxProtocolCount?.let { if (it == Long.MAX_VALUE) it else it + 1L },
        rxProtocolReceivedElapsedMs = receivedElapsedMs,
        rxProtocolObservedElapsedMs = null,
        rxResponse = null,
    )

    /** 예외로 결과를 얻지 못했으면 response=null이며 정상 반환으로 꾸미지 않는다. */
    fun withProtocolResult(observedElapsedMs: Long, response: TeslaBleResponseObservation?) = copy(
        rxProtocolObservedElapsedMs = observedElapsedMs,
        rxResponse = response,
    )

    /** 정리 결과로 기본값을 만들지 않고 작업에서 실제 관측한 BLE 근거를 전달한다. */
    fun terminalBleState(status: BleProbeStatus, reason: String?, localClosed: Boolean,
        gattStatus: Int?, primaryGattStatus: Int?, phaseElapsedMs: Long): BleProbeState = BleProbeState(
        status = status, reason = reason, localClosed = localClosed,
        connected = connectedObserved, serviceFound = serviceFound, txFound = txFound, rxFound = rxFound,
        subscriptionConfirmed = subscriptionConfirmed,
        notificationCount = rxNotificationCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        elapsedMs = observedElapsedMs - operationStartedElapsedMs,
        phaseElapsedMs = phaseElapsedMs,
        firstRxElapsedMs = firstRxElapsedMs?.minus(operationStartedElapsedMs),
        gattStatus = gattStatus, primaryGattStatus = primaryGattStatus,
        backgroundConnect = autoConnect == true,
    )
}
