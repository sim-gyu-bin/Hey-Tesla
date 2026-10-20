package com.heytesla.app

/** 비인증 수신 header 관측. 주소·UUID·키·payload 원문은 보존하지 않는다. */
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
)
