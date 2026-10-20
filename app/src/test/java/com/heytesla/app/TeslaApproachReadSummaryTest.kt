package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test

/** 저장 경계의 원문 차단·미관측 시각·부분 결과를 검증한다. 실행은 통합 소유자가 담당한다. */
class TeslaApproachReadSummaryTest {
    @Test fun sleepStatusCannotEscapeWithoutVerifiedBodyAndReceivedTime() {
        for (verified in listOf(false, true)) {
            for (received in listOf(null, -1L, 0L)) {
                for (code in listOf(-1, 0, 1, 2, 99, Int.MAX_VALUE)) {
                    val encoded = TeslaApproachReadSummaryJson.encode(TeslaApproachReadSummary(
                        bodyVerified = verified, bodyReceivedElapsedMs = received, bodyVehicleSleepStatus = code))
                    val expected = code.takeIf { verified && received != null && received >= 0 && code >= 0 }
                    assertTrue(encoded.contains("\"bodyVehicleSleepStatus\":$expected"))
                    assertTrue(encoded.contains("\"driveVerified\":false"))
                }
            }
        }
    }

    @Test fun connectAdmissionPreservesFalseUnknownAndBlocksUntrustedAction() {
        val absent = FieldJson.encode(record(TeslaApproachReadSummary()))
        for (key in listOf("gattAutoConnect", "gattConnectAction", "gattConnectAccepted")) {
            assertTrue(absent.contains("\"$key\":null"))
        }
        for (action in listOf("CREATE", "RECONNECT")) {
            for (accepted in listOf(false, true)) {
                val encoded = FieldJson.encode(record(TeslaApproachReadSummary(
                    gattAutoConnect = false, gattConnectAction = action, gattConnectAccepted = accepted)))
                assertTrue(encoded.contains("\"gattAutoConnect\":false"))
                assertTrue(encoded.contains("\"gattConnectAction\":\"$action\""))
                assertTrue(encoded.contains("\"gattConnectAccepted\":$accepted"))
                assertTrue(encoded.contains("\"gattConnectionStatus\":null"))
                assertTrue(encoded.contains("\"bodyVerified\":false"))
            }
        }
        val secret = "VIN-MAC-KEY-private-action"
        val invalid = FieldJson.encode(record(TeslaApproachReadSummary(
            gattConnectAction = secret, gattAutoConnect = true)))
        assertFalse(invalid.contains(secret))
        assertTrue(invalid.contains("\"gattConnectAction\":null"))
        assertTrue(invalid.contains("\"gattConnectAccepted\":null"))
        assertTrue(invalid.contains("\"gattAutoConnect\":true"))
    }

    @Test fun connectionObservationConsumerPreservesUnknownFalseAndRejectsNegativeTimes() {
        val absent = FieldJson.encode(record(TeslaApproachReadSummary()))
        for (key in listOf("advertisementConnectable", "gattConnectIssuedElapsedMs",
            "gattConnectReturnedElapsedMs", "advertisementAgeAtConnectMs")) {
            assertTrue(absent.contains("\"$key\":null"))
        }
        val invalid = FieldJson.encode(record(TeslaApproachReadSummary(
            advertisementConnectable = false, gattConnectIssuedElapsedMs = -1L,
            gattConnectReturnedElapsedMs = -2L, advertisementAgeAtConnectMs = -3L)))
        assertTrue(invalid.contains("\"advertisementConnectable\":false"))
        for (key in listOf("gattConnectIssuedElapsedMs", "gattConnectReturnedElapsedMs",
            "advertisementAgeAtConnectMs")) {
            assertTrue(invalid.contains("\"$key\":null"))
        }
        val observed = FieldJson.encode(record(TeslaApproachReadSummary(
            advertisementConnectable = true, gattConnectIssuedElapsedMs = 0L,
            gattConnectReturnedElapsedMs = 12L, advertisementAgeAtConnectMs = 34L)))
        assertTrue(observed.contains("\"advertisementConnectable\":true"))
        assertTrue(observed.contains("\"gattConnectIssuedElapsedMs\":0"))
        assertTrue(observed.contains("\"gattConnectReturnedElapsedMs\":12"))
        assertTrue(observed.contains("\"advertisementAgeAtConnectMs\":34"))
    }

    @Test fun responseHeaderJsonDistinguishesMissingFromObservedFalseAndRawDomainZero() {
        val absent = TeslaApproachReadSummaryJson.encode(TeslaApproachReadSummary())
        for (key in listOf("responseExpectedDomain", "responseFromPresent", "responseFromKind",
            "responseFromDomain", "responseToPresent", "responseToKind", "responsePayloadKind",
            "responseDomainMismatchReason")) {
            assertTrue(absent.contains("\"$key\":null"))
        }
        val missing = TeslaApproachReadSummaryJson.encode(TeslaApproachReadSummary(
            responseExpectedDomain = 3, responseFromPresent = false, responseToPresent = false,
            responsePayloadKind = "UNKNOWN", responseDomainMismatchReason = "FROM_MISSING"))
        assertTrue(missing.contains("\"responseFromPresent\":false"))
        assertTrue(missing.contains("\"responseToPresent\":false"))
        assertTrue(missing.contains("\"responseFromDomain\":null"))
        assertTrue(missing.contains("\"responsePayloadKind\":\"UNKNOWN\""))
        for (domain in listOf(0, 777)) {
            val observed = TeslaApproachReadSummaryJson.encode(TeslaApproachReadSummary(
                responseExpectedDomain = 3, responseFromPresent = true, responseFromKind = "DOMAIN",
                responseFromDomain = domain, responseDomainMismatchReason = "DOMAIN_DIFFERENT"))
            assertTrue(observed.contains("\"responseFromDomain\":$domain"))
            assertTrue(observed.contains("\"responseFromPresent\":true"))
        }
    }

    @Test fun untrustedStringsAreNullWithoutErasingPartialEvidence() {
        val secret = "VIN-MAC-KEY-RX-exception\nraw"
        val encoded = FieldJson.encode(record(TeslaApproachReadSummary(
            candidate = 1L, attempt = 2L, stage = secret, query = secret,
            outcome = secret, reason = secret, gear = secret,
            responseDisposition = secret, responseQuery = secret,
            responseFromKind = secret, responseToKind = secret,
            responsePayloadKind = secret, responseDomainMismatchReason = secret,
            bodyVerified = true, driveVerified = false,
            bodyReceivedElapsedMs = 15L, lockState = 0, frontTrunkState = 0,
        )))
        assertFalse(encoded.contains(secret))
        assertFalse(encoded.contains("VIN-MAC-KEY"))
        for (key in listOf("stage", "query", "outcome", "reason", "gear", "responseDisposition", "responseQuery",
            "responseFromKind", "responseToKind", "responsePayloadKind", "responseDomainMismatchReason")) {
            assertTrue(encoded.contains("\"$key\":null"))
        }
        assertTrue(encoded.contains("\"bodyVerified\":true"))
        assertTrue(encoded.contains("\"driveVerified\":false"))
        assertTrue(encoded.contains("\"driveReceivedElapsedMs\":null"))
        assertTrue(encoded.contains("\"driveSourceWallMs\":null"))
        assertTrue(encoded.contains("\"localClosed\":false"))
        assertFalse(encoded.contains("VERIFIED_STATUS"))
    }

    @Test fun invalidIdentifiersAndTimesStayUnknownButObservedZeroIsNotMissing() {
        val invalid = TeslaApproachReadSummary(
            candidate = 0L, attempt = -1L, stage = "TIMED_OUT", outcome = "FAILED",
            reason = "READ_DEADLINE_EXPIRED", bodyReceivedElapsedMs = -1L,
            driveReceivedElapsedMs = -2L, driveSourceWallMs = -3L,
            rxNotificationCount = -1L, rxFrameCount = -2L, rxProtocolCount = -3L,
            lastRxElapsedMs = -4L, lastFrameElapsedMs = -5L,
            gattConnectionElapsedMs = -6L, gattDescriptorCallbackElapsedMs = -7L,
            writeDispatchElapsedMs = -8L,
        )
        val encoded = FieldJson.encode(record(invalid))
        for (key in listOf("candidate", "attempt", "bodyReceivedElapsedMs", "driveReceivedElapsedMs", "driveSourceWallMs",
            "rxNotificationCount", "rxFrameCount", "rxProtocolCount", "lastRxElapsedMs", "lastFrameElapsedMs",
            "gattConnectionElapsedMs", "gattDescriptorCallbackElapsedMs", "writeDispatchElapsedMs")) {
            assertTrue(encoded.contains("\"$key\":null"))
        }
        assertTrue(encoded.contains("\"reason\":\"READ_DEADLINE_EXPIRED\""))
        val observed = FieldJson.encode(record(invalid.copy(
            candidate = 1L, attempt = 1L, bodyReceivedElapsedMs = 0L,
            driveReceivedElapsedMs = 0L, driveSourceWallMs = 0L,
            lockState = 0, frontTrunkState = 0, gear = "UNKNOWN",
            rxNotificationCount = 0L, rxFrameCount = 0L, rxProtocolCount = 0L,
            lastRxElapsedMs = 0L, lastFrameElapsedMs = 0L,
            gattConnectionStatus = 0, gattConnectionState = 0, gattConnectionElapsedMs = 0L,
            gattDescriptorRequestStatus = 0, gattDescriptorCallbackStatus = 0,
            gattDescriptorCallbackElapsedMs = 0L, writeDispatchQueued = false, writeDispatchElapsedMs = 0L,
        )))
        for (key in listOf("bodyReceivedElapsedMs", "driveReceivedElapsedMs", "driveSourceWallMs", "lockState", "frontTrunkState",
            "rxNotificationCount", "rxFrameCount", "rxProtocolCount", "lastRxElapsedMs", "lastFrameElapsedMs",
            "gattConnectionStatus", "gattConnectionState", "gattConnectionElapsedMs",
            "gattDescriptorRequestStatus", "gattDescriptorCallbackStatus", "gattDescriptorCallbackElapsedMs",
            "writeDispatchElapsedMs")) {
            assertTrue(observed.contains("\"$key\":0"))
        }
        assertTrue(observed.contains("\"gear\":\"UNKNOWN\""))
        assertTrue(observed.contains("\"writeDispatchQueued\":false"))
        assertTrue(observed.contains("\"bodyVerified\":false"))
        assertTrue(observed.contains("\"driveVerified\":false"))
    }

    @Test fun writeExceptionCannotLeakRawDetailsOrImplyNativeCompletion() {
        val encoded = TeslaApproachReadSummaryJson.encode(TeslaApproachReadSummary(
            stage = "FAILED", outcome = "FAILED", writeSequence = 1L,
            writeException = "java.lang.SecurityException: VIN-MAC-KEY",
            writeReturnedElapsedMs = 0L,
        ))
        assertFalse(encoded.contains("VIN-MAC-KEY"))
        assertFalse(encoded.contains("java.lang.SecurityException"))
        assertTrue(encoded.contains("\"writeException\":null"))
        assertTrue(encoded.contains("\"writeRequestStatus\":null"))
        assertTrue(encoded.contains("\"writeCallbackStatus\":null"))
        assertTrue(encoded.contains("\"writeCallbackElapsedMs\":null"))
        assertTrue(encoded.contains("\"writeReturnedElapsedMs\":0"))
    }

    @Test fun sdkSuccessAndNativeFailureRemainDistinctAtPersistenceBoundary() {
        val encoded = TeslaApproachReadSummaryJson.encode(TeslaApproachReadSummary(
            stage = "FAILED", outcome = "FAILED", reason = "GATT_WRITE_FAILED",
            writeRequestStatus = 0, writeCallbackStatus = 133,
            writeRequestedElapsedMs = 10L, writeReturnedElapsedMs = 11L,
            writeCallbackElapsedMs = 12L, localClosed = true,
        ))
        assertTrue(encoded.contains("\"writeRequestStatus\":0"))
        assertTrue(encoded.contains("\"writeCallbackStatus\":133"))
        assertTrue(encoded.contains("\"writeException\":null"))
        assertTrue(encoded.contains("\"outcome\":\"FAILED\""))
        assertTrue(encoded.contains("\"bodyVerified\":false"))
        assertTrue(encoded.contains("\"driveVerified\":false"))
    }

    @Test fun unknownWriteObservationsAreNotSynthesizedAsSuccess() {
        val encoded = TeslaApproachReadSummaryJson.encode(TeslaApproachReadSummary(
            writeSequence = -1L, negotiatedMtu = 22, frameOffset = -1,
            writeRequestedElapsedMs = -1L, writeReturnedElapsedMs = -1L,
            writeCallbackElapsedMs = -1L,
        ))
        for (key in listOf("writeSequence", "negotiatedMtu", "frameOffset",
            "writeRequestedElapsedMs", "writeReturnedElapsedMs", "writeCallbackElapsedMs",
            "writeRequestStatus", "writeCallbackStatus", "writePendingBefore", "descriptorPendingBefore",
            "gattConnectionStatus", "gattConnectionState", "gattConnectionElapsedMs",
            "gattDescriptorRequestStatus", "gattDescriptorCallbackStatus", "gattDescriptorCallbackElapsedMs",
            "writeDispatchQueued", "writeDispatchElapsedMs")) {
            assertTrue(encoded.contains("\"$key\":null"))
        }
    }

    @Test fun canceledReservationCannotBeEncodedAsSdkAcceptance() {
        val queued = TeslaApproachReadSummary(
            writeSequence = 1L, frameLength = 50, chunkLength = 50, frameOffset = 0,
            writeDispatchQueued = true,
        )
        val encoded = FieldJson.encode(record(TeslaApproachReadSummary(
            stage = "CANCELED", outcome = "CANCELED", localClosed = true,
        ).withWriteObservation(sealedWriteObservation(null, queued))))
        assertTrue(encoded.contains("\"writeDispatchQueued\":false"))
        for (key in listOf("writeDispatchElapsedMs", "writeRequestedElapsedMs", "writeReturnedElapsedMs",
            "writeRequestStatus", "writeCallbackStatus", "writeCallbackElapsedMs")) {
            assertTrue(encoded.contains("\"$key\":null"))
        }
        assertTrue(encoded.contains("\"outcome\":\"CANCELED\""))
        assertTrue(encoded.contains("\"bodyVerified\":false"))
        assertTrue(encoded.contains("\"driveVerified\":false"))
    }

    @Test fun finalPersistenceKeepsOpNativeEvidenceAcrossNewWriteObservation() {
        val op = TeslaApproachReadSummary(
            stage = "FAILED", outcome = "PARTIAL", query = "DRIVE_STATE", reason = "GATT_CONNECTION_LOST",
            bodyVerified = true, localClosed = true, rxNotificationCount = 4L,
            gattConnectionStatus = 133, gattConnectionState = 0, gattConnectionElapsedMs = 30L,
            gattDescriptorRequestStatus = 0, gattDescriptorCallbackStatus = 0,
            gattDescriptorCallbackElapsedMs = 12L,
        )
        val encoded = FieldJson.encode(record(op.withWriteObservation(TeslaApproachReadSummary(
            writeSequence = 2L, writeRequestStatus = 0, writeCallbackStatus = 133,
            writeDispatchQueued = false, writeDispatchElapsedMs = 20L, writeCallbackElapsedMs = 25L,
        ))))
        assertTrue(encoded.contains("\"gattConnectionStatus\":133"))
        assertTrue(encoded.contains("\"gattConnectionState\":0"))
        assertTrue(encoded.contains("\"gattConnectionElapsedMs\":30"))
        assertTrue(encoded.contains("\"gattDescriptorRequestStatus\":0"))
        assertTrue(encoded.contains("\"gattDescriptorCallbackStatus\":0"))
        assertTrue(encoded.contains("\"gattDescriptorCallbackElapsedMs\":12"))
        assertTrue(encoded.contains("\"writeSequence\":2"))
        assertTrue(encoded.contains("\"writeDispatchElapsedMs\":20"))
        assertTrue(encoded.contains("\"writeCallbackStatus\":133"))
        assertTrue(encoded.contains("\"rxNotificationCount\":4"))
        assertTrue(encoded.contains("\"bodyVerified\":true"))
        assertTrue(encoded.contains("\"driveVerified\":false"))
        assertTrue(encoded.contains("\"outcome\":\"PARTIAL\""))
    }

    @Test fun canceledNextReservationKeepsPreviousActualWriteWithoutMixingSequences() {
        val actual = TeslaApproachReadSummary(
            writeSequence = 1L, writeRequestStatus = 0, writeCallbackStatus = 0,
            writeRequestedElapsedMs = 10L, writeReturnedElapsedMs = 11L, writeCallbackElapsedMs = 12L,
            writeDispatchQueued = false, writeDispatchElapsedMs = 10L,
        )
        val queued = TeslaApproachReadSummary(
            writeSequence = 2L, writeRequestedElapsedMs = 20L,
            writeDispatchQueued = true,
        )
        val encoded = FieldJson.encode(record(TeslaApproachReadSummary(
            stage = "CANCELED", outcome = "PARTIAL", bodyVerified = true,
            gattConnectionStatus = 0, gattDescriptorCallbackStatus = 0, localClosed = true,
        ).withWriteObservation(sealedWriteObservation(actual, queued))))
        assertTrue(encoded.contains("\"writeSequence\":1"))
        assertFalse(encoded.contains("\"writeSequence\":2"))
        assertTrue(encoded.contains("\"writeRequestedElapsedMs\":10"))
        assertFalse(encoded.contains("\"writeRequestedElapsedMs\":20"))
        assertTrue(encoded.contains("\"writeReturnedElapsedMs\":11"))
        assertTrue(encoded.contains("\"writeCallbackElapsedMs\":12"))
        assertTrue(encoded.contains("\"writeRequestStatus\":0"))
        assertTrue(encoded.contains("\"writeCallbackStatus\":0"))
        assertTrue(encoded.contains("\"writeDispatchQueued\":false"))
        assertTrue(encoded.contains("\"writeDispatchElapsedMs\":10"))
        assertTrue(encoded.contains("\"gattConnectionStatus\":0"))
        assertTrue(encoded.contains("\"gattDescriptorCallbackStatus\":0"))
        assertTrue(encoded.contains("\"bodyVerified\":true"))
        assertTrue(encoded.contains("\"driveVerified\":false"))
        assertTrue(encoded.contains("\"outcome\":\"PARTIAL\""))
    }

    @Test fun canceledBusyReissueKeepsLastSdkBusyWithoutInventingAnotherIssueOrCallback() {
        val actual = TeslaApproachReadSummary(
            writeSequence = 2L, writeRequestStatus = 201, writeBusyRetryCount = 1,
            writeRequestedElapsedMs = 110L, writeReturnedElapsedMs = 111L,
            writeDispatchQueued = false, writeDispatchElapsedMs = 110L,
        )
        val reservation = TeslaApproachReadSummary(
            writeSequence = 3L, writeRequestedElapsedMs = 210L,
            writeDispatchQueued = true, writeBusyRetryCount = 1,
        )
        val final = TeslaApproachReadSummary(
            stage = "CANCELED", outcome = "PARTIAL", query = "DRIVE_STATE",
            bodyVerified = true, localClosed = true, gattDescriptorCallbackStatus = 0,
        ).withWriteObservation(sealedWriteObservation(actual, reservation))
        assertEquals(2L, final.writeSequence)
        assertEquals(201, final.writeRequestStatus)
        assertEquals(1, final.writeBusyRetryCount)
        assertEquals(110L, final.writeDispatchElapsedMs)
        assertEquals(111L, final.writeReturnedElapsedMs)
        assertNull(final.writeCallbackStatus)
        assertNull(final.writeCallbackElapsedMs)
        assertNull(completedNativeWriteObservation(actual, actual, 0, 212L))
        assertTrue(final.bodyVerified)
        assertFalse(final.driveVerified)
        assertEquals("PARTIAL", final.outcome)
    }

    private fun record(summary: TeslaApproachReadSummary) = FieldRecord(
        processId = "11111111-1111-4111-8111-111111111111",
        trialId = "33333333-3333-4333-8333-333333333333",
        wallTimeMs = 1_700_000_000_000L, elapsedRealtimeMs = 100L,
        appVersion = "0.28.0-test", event = "TESLA_APPROACH_READ_FINISHED",
        state = emptyMap(), teslaApproachRead = summary,
    )
}
