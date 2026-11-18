package com.heytesla.app.vehicle.protocol

enum class TeslaBleRxStep { WAITING, SEND, COMPLETE, FRUNK_RESULT, WAKE_RESULT, FAILED, UWB_READY, UWB_INPUT, IGNORED }
enum class TeslaBleRxInputKind { ROUTABLE_MESSAGE, RAW_FIRA, WRAPPED_PLAINTEXT, ENCRYPTED_RESPONSE }
enum class TeslaBleRxAead { NOT_ATTEMPTED, VERIFIED, FAILED }
enum class TeslaBleRxFiraKind { REQUEST, STOP, UNRECOGNIZED, INVALID }

/** header는 비인증 관측이며 AEAD 결과·실제 반환 단계와 별개다. 주소·UUID·키·payload 원문은 보존하지 않는다. */
data class TeslaBleResponseObservation(
    val disposition: String,
    val expectedDomain: Int,
    val fromPresent: Boolean? = null,
    val fromKind: String? = null,
    val fromDomain: Int? = null,
    val toPresent: Boolean? = null,
    val toKind: String? = null,
    val payloadKind: String? = null,
    val domainMismatchReason: String? = null,
    /** domain2 UUID 없는 broadcast의 VehicleSleepStatus_E 정수. 비인증 진단 전용이며 P·기상·제어 판단에 쓰지 않는다. */
    val domainMismatchVcsecSleepStatus: Int? = null,
    val requestUuidPresent: Boolean? = null,
    val requestUuidMatches: Boolean? = null,
    val toRouteMatches: Boolean? = null,
    val signedStatusPresent: Boolean? = null,
    val signedMessageFault: Int? = null,
    val operationStatus: Int? = null,
    val step: TeslaBleRxStep? = null,
    val inputKind: TeslaBleRxInputKind? = null,
    val aead: TeslaBleRxAead? = null,
    val firaKind: TeslaBleRxFiraKind? = null,
    val authEvidence: TeslaBleAuthEvidence? = null,
    val bodySummary: TeslaBleRxBodySummary? = null,
    val requestCorrelation: TeslaBleRxRequestCorrelation? = null,
    /** 상관 일치·AEAD 검증된 domain3 DRIVE 완료의 shift 분류. UNKNOWN의 원인만 구분하며 기어 판단에 쓰지 않는다. */
    val driveShiftState: String? = null,
    /** 원본 시각 없이 존재·canonical 범위·양수 여부만 분류한다. VALID는 fresh P나 최신 시각을 뜻하지 않는다. */
    val driveTimestampStatus: String? = null,
)
