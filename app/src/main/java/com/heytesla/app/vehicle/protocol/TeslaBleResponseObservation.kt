package com.heytesla.app.vehicle.protocol

internal enum class TeslaBleRxStep { WAITING, SEND, COMPLETE, FRUNK_RESULT, FAILED, UWB_READY, UWB_INPUT }
internal enum class TeslaBleRxInputKind { ROUTABLE_MESSAGE, RAW_FIRA, WRAPPED_PLAINTEXT, ENCRYPTED_RESPONSE }
internal enum class TeslaBleRxAead { NOT_ATTEMPTED, VERIFIED, FAILED }
internal enum class TeslaBleRxFiraKind { REQUEST, STOP, UNRECOGNIZED, INVALID }

/** header는 비인증 관측이며 AEAD 결과·실제 반환 단계와 별개다. 주소·UUID·키·payload 원문은 보존하지 않는다. */
internal data class TeslaBleResponseObservation(
    val disposition: String,
    val expectedDomain: Int,
    val fromPresent: Boolean? = null,
    val fromKind: String? = null,
    val fromDomain: Int? = null,
    val toPresent: Boolean? = null,
    val toKind: String? = null,
    val payloadKind: String? = null,
    val domainMismatchReason: String? = null,
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
)
