package com.heytesla.app.logging

import com.heytesla.app.diagnostics.SpeechTrialSummary
import com.heytesla.app.diagnostics.TeslaApproachReadSummary
import com.heytesla.app.speech.SpeechCommandDecision
import com.heytesla.app.vehicle.detection.BleEventEvidence
import com.heytesla.app.vehicle.detection.BleEvidenceKind
import com.heytesla.app.vehicle.frunk.FrunkVoiceRecognition
import com.heytesla.app.vehicle.frunk.FrunkVoiceRecognitionCandidate
import com.heytesla.app.vehicle.frunk.FrunkVoiceRecognitionResult
import com.heytesla.app.vehicle.key.TeslaKeyStage
import com.heytesla.app.vehicle.connection.TeslaGattEvent
import com.heytesla.app.vehicle.connection.TeslaConnectionStage
import com.heytesla.app.speech.product.ProductVoiceStage
import com.heytesla.app.speech.product.ProductVoiceDiagnostic
import com.heytesla.app.logging.legacy.VehicleNavigationObservation
import com.heytesla.app.logging.legacy.VehicleNavigationObservationKind
import com.heytesla.app.logging.legacy.VehicleNavigationObservationReason
import com.heytesla.app.logging.legacy.VehicleNavigationObservationStatus
import com.heytesla.app.logging.legacy.VehicleNavigationAuthEvidence
import com.heytesla.app.logging.legacy.VehicleNavigationRxDisposition
import com.heytesla.app.vehicle.connection.TeslaGattObservation
import com.heytesla.app.vehicle.connection.TeslaGattDeadlineCause
import com.heytesla.app.vehicle.protocol.TeslaBleResponseObservation
import com.heytesla.app.vehicle.protocol.TeslaBleRxStep
import com.heytesla.app.vehicle.protocol.TeslaBleRxAead
import com.heytesla.app.vehicle.protocol.TeslaBleRxInputKind

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 실제 파일에 대한 append·재개·회전 상한·쓰기 실패·allowlist 동작을 검증한다.
 * 임시 디렉터리는 회수 근거로 남긴다. 제거 대상은 sink가 소유하는 새 archive뿐이다.
 */
class FieldEventLogTest {
    @Test fun productVoiceDiagnosticCannotClaimNormalOutcomeBeforeRequiredCleanup() {
        fun encode(diagnostic: ProductVoiceDiagnostic) = FieldJson.encode(
            record("PRODUCT_VOICE_DIAGNOSTIC").copy(productVoiceDiagnostic = diagnostic))
        val clean = ProductVoiceDiagnostic("ACCEPTED", utteranceBytes = 32_000,
            voicedBytes = 16_000, silenceBytesAtEnd = 8_000, suppressedOnsets = 2,
            captureMs = 1_000, loadMs = 20, decodeMs = 30, cleanupMs = 4,
            modelReused = true, microphoneReleased = true, streamCleanupConfirmed = true,
            recognizerCleanupConfirmed = true)
        for (outcome in listOf("EMPTY", "GRAMMAR_NO_MATCH", "ACCEPTED")) {
            val diagnostic = clean.copy(outcome = outcome)
            assertTrue(encode(diagnostic).contains("\"outcome\":\"$outcome\""))
            for (released in listOf(false, null)) {
                for (blocked in listOf(diagnostic.copy(microphoneReleased = released),
                    diagnostic.copy(streamCleanupConfirmed = released))) {
                    val row = encode(blocked)
                    assertTrue(row.contains("\"outcome\":null"))
                    assertFalse(row.contains("\"outcome\":\"$outcome\""))
                    assertTrue(row.contains("\"utteranceBytes\":32000"))
                }
            }
        }
        for (outcome in listOf("ACCEPTED", "CLOSED")) {
            for (released in listOf(false, null)) {
                val row = encode(clean.copy(outcome = outcome, recognizerCleanupConfirmed = released))
                assertTrue(row.contains("\"outcome\":null"))
                assertTrue(row.contains("\"recognizerCleanupConfirmed\":$released"))
            }
        }
        // 비명령은 recognizer를 소유한 채 새 발화를 기다릴 수 있다.
        assertTrue(encode(clean.copy(outcome = "GRAMMAR_NO_MATCH",
            recognizerCleanupConfirmed = false)).contains("\"outcome\":\"GRAMMAR_NO_MATCH\""))
        for (outcome in listOf("PREPARED", "CLOSED")) {
            val row = encode(clean.copy(outcome = outcome,
                recognizerCleanupConfirmed = outcome == "CLOSED"))
            assertTrue(row.contains("\"outcome\":\"$outcome\""))
            for (key in listOf("utteranceBytes", "voicedBytes", "silenceBytesAtEnd",
                "suppressedOnsets", "captureMs", "decodeMs", "modelReused")) {
                assertTrue(key, row.contains("\"$key\":null"))
            }
            assertTrue(row.contains("\"loadMs\":20"))
            assertTrue(row.contains("\"cleanupMs\":4"))
        }
        for (outcome in listOf("FAILURE", "SEALED")) {
            val row = encode(clean.copy(outcome = outcome, microphoneReleased = false,
                streamCleanupConfirmed = false, recognizerCleanupConfirmed = false))
            assertTrue(row.contains("\"outcome\":\"$outcome\""))
            assertTrue(row.contains("\"microphoneReleased\":false"))
            assertTrue(row.contains("\"streamCleanupConfirmed\":false"))
            assertTrue(row.contains("\"recognizerCleanupConfirmed\":false"))
        }
    }

    @Test fun productVoiceDiagnosticDropsPrivateOutcomeAndImpossibleAudioMetrics() {
        val secret = "PRIVATE-TRANSCRIPT-PCM-VIN-MAC-UUID-KEY"
        val invalid = ProductVoiceDiagnostic(secret, utteranceBytes = 320_002,
            voicedBytes = 2, silenceBytesAtEnd = 0, suppressedOnsets = -1,
            captureMs = 86_400_001, loadMs = -1, decodeMs = 600_001, cleanupMs = Long.MAX_VALUE)
        val row = FieldJson.encode(record("PRODUCT_VOICE_DIAGNOSTIC").copy(productVoiceDiagnostic = invalid))
        assertFalse(row.contains(secret))
        for (key in listOf("outcome", "utteranceBytes", "voicedBytes", "silenceBytesAtEnd",
            "suppressedOnsets", "captureMs", "loadMs", "decodeMs", "cleanupMs")) {
            assertTrue(key, row.contains("\"$key\":null"))
        }
        for (bytes in listOf(-2, 1, 320_001, Int.MAX_VALUE)) {
            val encoded = FieldJson.encode(record("PRODUCT_VOICE_DIAGNOSTIC").copy(
                productVoiceDiagnostic = invalid.copy(utteranceBytes = bytes)))
            for (key in listOf("utteranceBytes", "voicedBytes", "silenceBytesAtEnd")) {
                assertTrue(key, encoded.contains("\"$key\":null"))
            }
        }
        val bounded = invalid.copy(outcome = "SEALED", utteranceBytes = 320_000,
            voicedBytes = 320_000, silenceBytesAtEnd = 0, suppressedOnsets = Int.MAX_VALUE,
            captureMs = 86_400_000, loadMs = 0, decodeMs = 600_000, cleanupMs = 600_000)
        val upper = FieldJson.encode(record("PRODUCT_VOICE_DIAGNOSTIC").copy(productVoiceDiagnostic = bounded))
        assertTrue(upper.contains("\"utteranceBytes\":320000"))
        assertTrue(upper.contains("\"voicedBytes\":320000"))
        assertTrue(upper.contains("\"silenceBytesAtEnd\":0"))
        assertTrue(upper.contains("\"captureMs\":86400000"))
        assertTrue(upper.contains("\"decodeMs\":600000"))
        for (bytes in listOf(-2, 1, 320_002)) {
            val encoded = FieldJson.encode(record("PRODUCT_VOICE_DIAGNOSTIC").copy(
                productVoiceDiagnostic = bounded.copy(voicedBytes = bytes, silenceBytesAtEnd = bytes)))
            assertTrue(encoded.contains("\"voicedBytes\":null"))
            assertTrue(encoded.contains("\"silenceBytesAtEnd\":null"))
        }
    }

    @Test fun productVoiceDiagnosticCannotLeakAcrossEventsOrBackfillOlderSchemas() {
        val diagnostic = ProductVoiceDiagnostic("ACCEPTED", utteranceBytes = 32_000,
            microphoneReleased = true, streamCleanupConfirmed = true, recognizerCleanupConfirmed = true)
        val file = newFile()
        val sink = startSink(file)
        val dedicated = record("PRODUCT_VOICE_DIAGNOSTIC").copy(productVoiceDiagnostic = diagnostic)
        assertTrue(sink.enqueue(dedicated))
        assertTrue(sink.enqueue(record("PRODUCT_VOICE_STATE_CHANGED").copy(productVoiceDiagnostic = diagnostic)))
        assertTrue(sink.enqueue(record("PRODUCT_VOICE_DIAGNOSTIC")))
        for (version in 1..38) {
            val historical = dedicated.copy(version = version)
            assertFalse(sink.enqueue(historical))
            assertFalse(FieldJson.encode(historical).contains("productVoiceDiagnostic"))
            assertTrue(sink.enqueue(record("PRODUCT_VOICE_STATE_CHANGED").copy(
                version = version, productVoiceDiagnostic = diagnostic)))
        }
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertTrue(rows.first().contains("\"outcome\":\"ACCEPTED\""))
        for (row in rows.drop(1)) {
            assertFalse(row.contains("\"outcome\":\"ACCEPTED\""))
            assertFalse(row.contains("\"utteranceBytes\":32000"))
        }
        assertTrue(rows[1].contains("\"productVoiceDiagnostic\":null"))
        assertTrue(rows[2].contains("\"productVoiceDiagnostic\":null"))
        assertEquals(38L, sink.status().rejected)
    }

    @Test fun connectionObservationDoesNotTurnSixNotificationsOrSdkAcceptanceIntoAuthentication() {
        val file = newFile()
        val sink = startSink(file)
        val handshake = connectionObservationFixture().copy(
            rxResponse = TeslaBleResponseObservation("REQUEST_UUID_MISMATCH", 3,
                requestUuidPresent = true, requestUuidMatches = false,
                step = TeslaBleRxStep.WAITING, aead = TeslaBleRxAead.NOT_ATTEMPTED),
            write = TeslaApproachReadSummary(writeSequence = 1, writeRequestStatus = 0,
                writeRequestedElapsedMs = 150, writeReturnedElapsedMs = 151),
        )
        assertTrue(sink.enqueue(record("TESLA_CONNECTION_GATT_OBSERVATION").copy(connectionObservation = handshake)))
        val readTimeout = handshake.copy(stage = TeslaKeyStage.READING_STATUS, event = TeslaGattEvent.STAGE,
            deadlineCause = TeslaGattDeadlineCause.PROTOCOL_STAGE, rxResponse = null,
            rxProtocolObservedElapsedMs = null)
        assertTrue(sink.enqueue(record("TESLA_CONNECTION_GATT_OBSERVATION").copy(connectionObservation = readTimeout)))
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertTrue(rows[0].contains("\"stage\":\"HANDSHAKING\""))
        assertTrue(rows[0].contains("\"rxNotificationCount\":6,\"rxFrameCount\":2,\"rxProtocolCount\":1"))
        assertTrue(rows[0].contains("\"requestUuidMatches\":false"))
        assertTrue(rows[0].contains("\"aead\":\"NOT_ATTEMPTED\",\"authEvidence\":null"))
        assertTrue(rows[0].contains("\"writeRequestStatus\":0"))
        assertTrue(rows[0].contains("\"writeCallbackStatus\":null"))
        assertFalse(rows[0].contains("STATUS_VERIFIED"))
        assertTrue(rows[1].contains("\"stage\":\"READING_STATUS\""))
        assertTrue(rows[1].contains("\"deadlineCause\":\"PROTOCOL_STAGE\""))
        assertTrue(rows[1].contains("\"rxResponse\":null"))
        assertFalse(rows[1].contains("REQUEST_UUID_MISMATCH"))
        for (cause in TeslaGattDeadlineCause.entries) {
            val row = FieldJson.encode(record("TESLA_CONNECTION_GATT_OBSERVATION").copy(
                connectionObservation = readTimeout.copy(deadlineCause = cause)))
            assertTrue(row.contains("\"deadlineCause\":\"${cause.name}\""))
        }
    }

    @Test fun connectionFrunkTerminalDispositionsArePreservedOnlySinceSchema35WithoutOpeningInference() {
        val replies = listOf(
            "FRUNK_ACKNOWLEDGED" to TeslaBleRxAead.VERIFIED,
            "FRUNK_UNCONFIRMED" to TeslaBleRxAead.NOT_ATTEMPTED,
            "FRUNK_UNCONFIRMED" to TeslaBleRxAead.VERIFIED,
        )
        val file = newFile()
        val sink = startSink(file)
        for ((index, reply) in replies.withIndex()) {
            val (disposition, aead) = reply
            val observation = connectionObservationFixture().copy(
                stage = TeslaKeyStage.READING_STATUS, event = TeslaGattEvent.PROTOCOL,
                rxProtocolCount = index.toLong() + 1,
                rxResponse = TeslaBleResponseObservation(disposition, 2,
                    step = TeslaBleRxStep.FRUNK_RESULT, aead = aead),
            )
            val current = record("TESLA_CONNECTION_GATT_OBSERVATION").copy(
                version = 35, connectionObservation = observation)
            assertTrue(sink.enqueue(current))
            assertTrue(sink.enqueue(current.copy(version = 34)))
            for (version in 1..33) {
                val historical = FieldJson.encode(current.copy(version = version))
                assertTrue(historical.contains("\"connectionObservation\":null"))
                assertFalse(historical.contains(disposition))
            }
        }
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertEquals(2 * replies.size, rows.size)
        for ((index, reply) in replies.withIndex()) {
            val (disposition, aead) = reply
            val current = rows[2 * index]
            val historical = rows[2 * index + 1]
            assertTrue(current.contains("\"disposition\":\"$disposition\""))
            assertTrue(historical.contains("\"disposition\":null"))
            assertFalse(historical.contains(disposition))
            for (row in listOf(current, historical)) {
                assertTrue(row.contains("\"step\":\"FRUNK_RESULT\""))
                assertTrue(row.contains("\"aead\":\"${aead.name}\""))
                assertTrue(row.contains("\"rxProtocolCount\":${index + 1}"))
                assertFalse(row.contains("\"frunkOpenObserved\":true"))
                assertFalse(row.contains("\"frunkCommandSent\":true"))
                assertFalse(row.contains("\"authEvidence\":\"ACKNOWLEDGED\""))
            }
        }
    }

    @Test fun connectionObservationPrivacyBoundaryRejectsUnknownStringsAndUncorrelatedSuccess() {
        val secret = "PRIVATE-RAW-VIN-MAC-UUID-KEY-TRANSCRIPT"
        val observation = connectionObservationFixture().copy(
            connectAction = secret,
            rxResponse = TeslaBleResponseObservation(secret, 3, fromKind = secret, toKind = secret,
                payloadKind = secret, domainMismatchReason = secret,
                requestUuidPresent = false, requestUuidMatches = true,
                toPresent = false, toRouteMatches = true,
                signedStatusPresent = false, signedMessageFault = 0, operationStatus = 0,
                driveShiftState = secret, driveTimestampStatus = secret),
            write = TeslaApproachReadSummary(writeException = secret, reason = secret,
                gear = secret, driveSourceWallMs = 9876543212345L, responseFromKind = secret),
        )
        val file = newFile()
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("TESLA_CONNECTION_GATT_OBSERVATION").copy(connectionObservation = observation)))
        assertTrue(sink.sync())
        val row = file.readText()
        assertFalse(row.contains(secret))
        assertFalse(row.contains("9876543212345"))
        for (key in listOf("connectAction", "disposition", "fromKind", "toKind", "payloadKind",
            "domainMismatchReason", "requestUuidMatches", "toRouteMatches", "signedMessageFault",
            "operationStatus", "writeException", "driveShiftState", "driveTimestampStatus")) {
            assertTrue(key, row.contains("\"$key\":null"))
        }
        for (key in listOf("firaKind", "bodySummary", "requestCorrelation", "driveSourceWallMs",
            "gear", "reason", "uwbGattStage", "uwbRxDisposition")) {
            assertFalse(key, row.contains("\"$key\":"))
        }
    }

    @Test fun connectionDomain2SleepStatusIsDiagnosticEnumOnlySinceSchema36() {
        val observation = connectionObservationFixture().copy(
            rxResponse = TeslaBleResponseObservation("DOMAIN_MISMATCH", 3, fromPresent = true,
                fromKind = "DOMAIN", fromDomain = 2, domainMismatchReason = "DOMAIN_DIFFERENT",
                domainMismatchVcsecSleepStatus = 2, step = TeslaBleRxStep.WAITING))
        val current = record("TESLA_CONNECTION_GATT_OBSERVATION").copy(version = 36, connectionObservation = observation)
        assertTrue(FieldJson.encode(current).contains("\"domainMismatchVcsecSleepStatus\":2"))
        assertFalse(FieldJson.encode(current.copy(version = 35)).contains("domainMismatchVcsecSleepStatus"))
        for (value in listOf(-1, 3, 99)) {
            val invalid = current.copy(connectionObservation = observation.copy(
                rxResponse = observation.rxResponse!!.copy(domainMismatchVcsecSleepStatus = value)))
            assertTrue(FieldJson.encode(invalid).contains("\"domainMismatchVcsecSleepStatus\":null"))
        }
        assertTrue(FieldEvents.isAllowed("TESLA_CONNECTION_SESSION_RETRANSMIT"))
    }

    @Test fun connectionDriveDiagnosticsRequireEveryAuthenticatedCorrelationBoundarySinceSchema37() {
        val verified = TeslaBleResponseObservation("STATUS_VERIFIED", 3,
            fromPresent = true, fromKind = "DOMAIN", fromDomain = 3,
            toPresent = true, toKind = "ROUTING_ADDRESS",
            requestUuidPresent = true, requestUuidMatches = true, toRouteMatches = true,
            inputKind = TeslaBleRxInputKind.ENCRYPTED_RESPONSE, aead = TeslaBleRxAead.VERIFIED,
            step = TeslaBleRxStep.COMPLETE, driveShiftState = "P", driveTimestampStatus = "VALID")
        fun row(response: TeslaBleResponseObservation, version: Int = 37) =
            FieldJson.encode(record("TESLA_CONNECTION_GATT_OBSERVATION").copy(version = version,
                connectionObservation = connectionObservationFixture().copy(rxResponse = response)))
        for (shift in listOf("ABSENT", "EMPTY", "INVALID", "P", "R", "N", "D", "SNA", "UNRECOGNIZED")) {
            for (timestamp in listOf("ABSENT", "INVALID", "NON_POSITIVE", "VALID")) {
                val encoded = row(verified.copy(driveShiftState = shift, driveTimestampStatus = timestamp))
                assertTrue(encoded.contains("\"driveShiftState\":\"$shift\""))
                assertTrue(encoded.contains("\"driveTimestampStatus\":\"$timestamp\""))
            }
        }
        for (version in 1..36) {
            val historical = row(verified, version)
            assertFalse(historical.contains("driveShiftState"))
            assertFalse(historical.contains("driveTimestampStatus"))
        }
        val forged = listOf(
            verified.copy(expectedDomain = 2),
            verified.copy(fromPresent = false), verified.copy(fromPresent = null),
            verified.copy(fromKind = "ROUTING_ADDRESS"), verified.copy(fromKind = null),
            verified.copy(fromDomain = 2), verified.copy(fromDomain = null),
            verified.copy(toPresent = false), verified.copy(toPresent = null),
            verified.copy(toKind = "DOMAIN"), verified.copy(toKind = null),
            verified.copy(requestUuidPresent = false), verified.copy(requestUuidPresent = null),
            verified.copy(requestUuidMatches = false), verified.copy(requestUuidMatches = null),
            verified.copy(toRouteMatches = false), verified.copy(toRouteMatches = null),
            verified.copy(disposition = "STATUS_INCOMPLETE"),
            verified.copy(disposition = "RESPONSE_REJECTED"),
            verified.copy(step = null), verified.copy(inputKind = null), verified.copy(aead = null),
        ) + TeslaBleRxStep.entries.filter { it != TeslaBleRxStep.COMPLETE }.map { verified.copy(step = it) } +
            TeslaBleRxInputKind.entries.filter { it != TeslaBleRxInputKind.ENCRYPTED_RESPONSE }.map { verified.copy(inputKind = it) } +
            TeslaBleRxAead.entries.filter { it != TeslaBleRxAead.VERIFIED }.map { verified.copy(aead = it) }
        for (response in forged) {
            val encoded = row(response)
            assertTrue(response.toString(), encoded.contains("\"driveShiftState\":null"))
            assertTrue(response.toString(), encoded.contains("\"driveTimestampStatus\":null"))
        }
        val secret = "PRIVATE-VIN-ADDRESS-KEY-ROUTE-UUID-PACKET-ABSOLUTE-TIME"
        for (unsupported in listOf(null, "", "UNKNOWN", " P", "valid", secret)) {
            val encoded = row(verified.copy(driveShiftState = unsupported, driveTimestampStatus = unsupported))
            assertTrue(encoded.contains("\"driveShiftState\":null"))
            assertTrue(encoded.contains("\"driveTimestampStatus\":null"))
            assertFalse(encoded.contains(secret))
        }
        // 두 allowlist는 서로 독립적이지만 인증 경계는 공유한다.
        assertTrue(row(verified.copy(driveShiftState = secret)).contains("\"driveTimestampStatus\":\"VALID\""))
        assertTrue(row(verified.copy(driveTimestampStatus = secret)).contains("\"driveShiftState\":\"P\""))
    }

    @Test fun connectionObservationCannotCarryAcrossEventVersionOrMutableCurrentSnapshot() {
        var current = connectionObservationFixture()
        val captured = record("TESLA_CONNECTION_GATT_OBSERVATION").copy(connectionObservation = current)
        current = current.copy(stage = TeslaKeyStage.READING_STATUS, rxNotificationCount = 99)
        val file = newFile()
        val sink = startSink(file)
        assertTrue(sink.enqueue(captured))
        assertTrue(sink.enqueue(record("TESLA_CONNECTION_STATE_CHANGED").copy(connectionObservation = current)))
        assertTrue(sink.enqueue(record("TESLA_CONNECTION_GATT_OBSERVATION")))
        for (version in 1..33) {
            assertTrue(sink.enqueue(captured.copy(version = version)))
        }
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertTrue(rows[0].contains("\"stage\":\"HANDSHAKING\""))
        assertTrue(rows[0].contains("\"rxNotificationCount\":6"))
        for (row in rows.drop(1)) {
            assertTrue(row.contains("\"connectionObservation\":null"))
            assertFalse(row.contains("\"rxNotificationCount\":"))
        }
    }

    @Test fun connectionObservationRejectsImpossibleTimesCountsAndPreservesUnobservedVersusZero() {
        val invalid = connectionObservationFixture().copy(
            stageStartedElapsedMs = 99, connectIssuedElapsedMs = 200, connectReturnedElapsedMs = 199,
            firstRxElapsedMs = 300, lastRxElapsedMs = 299,
            descriptorCallbackElapsedMs = 4_322,
            rxProtocolReceivedElapsedMs = 400, rxProtocolObservedElapsedMs = 399,
            rxNotificationCount = -1, rxFrameCount = null, rxProtocolCount = 0,
            rxNotificationFrameCount = 17, rxDecoderIncomplete = false,
            write = TeslaApproachReadSummary(writeSequence = 0, writeBusyRetryCount = 3,
                writeRequestedElapsedMs = 200, writeReturnedElapsedMs = 199,
                writeCallbackElapsedMs = -1, writeDispatchElapsedMs = 199,
                writePendingBefore = false, writeDispatchQueued = false),
        )
        val row = FieldJson.encode(record("TESLA_CONNECTION_GATT_OBSERVATION").copy(connectionObservation = invalid))
        for (key in listOf("stageStartedElapsedMs", "connectReturnedElapsedMs", "lastRxElapsedMs",
            "descriptorCallbackElapsedMs", "rxProtocolObservedElapsedMs", "rxNotificationCount",
            "rxFrameCount", "rxNotificationFrameCount", "writeSequence", "writeBusyRetryCount",
            "writeReturnedElapsedMs", "writeCallbackElapsedMs", "writeDispatchElapsedMs")) {
            assertTrue(key, row.contains("\"$key\":null"))
        }
        assertTrue(row.contains("\"rxProtocolCount\":0"))
        assertTrue(row.contains("\"rxDecoderIncomplete\":false"))
        assertTrue(row.contains("\"writePendingBefore\":false"))
        assertTrue(row.contains("\"writeDispatchQueued\":false"))
        val missing = FieldJson.encode(record("TESLA_CONNECTION_GATT_OBSERVATION").copy(
            connectionObservation = invalid.copy(write = null, rxProtocolCount = null)))
        assertTrue(missing.contains("\"write\":null"))
        assertTrue(missing.contains("\"rxProtocolCount\":null"))
    }

    @Test fun connectionObservationInvalidClockCannotProduceDurationsFromAnotherOperation() {
        val base = connectionObservationFixture().copy(
            connectIssuedElapsedMs = 200, connectReturnedElapsedMs = 201,
            write = TeslaApproachReadSummary(writeSequence = 1, writeRequestedElapsedMs = 250,
                writeReturnedElapsedMs = 251, writeCallbackElapsedMs = 252, writeDispatchElapsedMs = 250),
        )
        for (observation in listOf(
            base.copy(operationStartedElapsedMs = -1),
            base.copy(operationStartedElapsedMs = 4_322),
            base.copy(observedElapsedMs = 99),
            base.copy(observedElapsedMs = 4_322),
        )) {
            val row = FieldJson.encode(record("TESLA_CONNECTION_GATT_OBSERVATION").copy(
                connectionObservation = observation))
            for (key in listOf("observedElapsedMs", "stageStartedElapsedMs", "connectIssuedElapsedMs",
                "connectReturnedElapsedMs", "rxProtocolReceivedElapsedMs", "rxProtocolObservedElapsedMs",
                "writeRequestedElapsedMs", "writeReturnedElapsedMs", "writeCallbackElapsedMs", "writeDispatchElapsedMs")) {
                assertTrue(key, row.contains("\"$key\":null"))
            }
            // 관측된 분모는 잘못된 시각 때문에 무수신·성공으로 바꾸지 않는다.
            assertTrue(row.contains("\"rxNotificationCount\":6"))
            assertTrue(row.contains("\"writeSequence\":1"))
        }
    }

    @Test fun connectionObservationKeepsQueuedSdkRejectedAndCallbackFailureDistinct() {
        val file = newFile()
        val sink = startSink(file)
        val queued = TeslaApproachReadSummary(writeSequence = 1, writeRequestedElapsedMs = 200,
            writeDispatchQueued = true, writePendingBefore = false)
        val rejected = queued.copy(writeDispatchQueued = false, writeDispatchElapsedMs = 201,
            writeReturnedElapsedMs = 202, writeRequestStatus = 201, writeBusyRetryCount = 0)
        val callbackFailed = rejected.copy(writeSequence = 2, writeRequestStatus = 0,
            writeCallbackStatus = 133, writeCallbackElapsedMs = 203)
        for (write in listOf(queued, rejected, callbackFailed)) {
            assertTrue(sink.enqueue(record("TESLA_CONNECTION_GATT_OBSERVATION").copy(
                connectionObservation = connectionObservationFixture().copy(event = TeslaGattEvent.WRITE,
                    rxResponse = null, write = write))))
        }
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertTrue(rows[0].contains("\"writeDispatchQueued\":true,\"writeDispatchElapsedMs\":null"))
        assertTrue(rows[0].contains("\"writeRequestStatus\":null"))
        assertTrue(rows[1].contains("\"writeRequestStatus\":201"))
        assertTrue(rows[1].contains("\"writeCallbackStatus\":null"))
        assertTrue(rows[2].contains("\"writeRequestStatus\":0"))
        assertTrue(rows[2].contains("\"writeCallbackStatus\":133"))
        for (row in rows) {
            assertTrue(row.contains("\"rxResponse\":null"))
            assertFalse(row.contains("STATUS_VERIFIED"))
            assertFalse(row.contains("\"authEvidence\":\""))
        }
    }

    /** 메인 host smoke가 같은 고정 fixture를 실제 runtime 이벤트에도 공급할 수 있다. */
    internal fun connectionObservationFixture() = TeslaGattObservation(
        stage = TeslaKeyStage.HANDSHAKING, event = TeslaGattEvent.PROTOCOL,
        operationStartedElapsedMs = 100, stageStartedElapsedMs = 200, observedElapsedMs = 1_000,
        connectAccepted = true, connectedObserved = true, subscriptionConfirmed = true,
        rxNotificationCount = 6, rxFrameCount = 2, rxProtocolCount = 1,
        rxProtocolReceivedElapsedMs = 900, rxProtocolObservedElapsedMs = 901,
    )

    @Test fun productSnapshotUsesStrictEnumsBooleansAndFixedReasons() {
        val enums = mapOf(
            FieldStateKeys.CONNECTION_STAGE to TeslaConnectionStage.entries.map { it.name },
            FieldStateKeys.PRODUCT_VOICE_STAGE to ProductVoiceStage.entries.map { it.name },
            FieldStateKeys.CONNECTION_GEAR to listOf("P", "R", "N", "D", "UNKNOWN"),
            FieldStateKeys.CONNECTION_REASON to listOf("CONNECTION_P_RECEIPT_STALE", "GATT_CONNECTION_LOST"),
            FieldStateKeys.PRODUCT_VOICE_REASON to listOf("PRODUCT_VOICE_CLEANUP_FAILED_RESTART_REQUIRED",
                "PRODUCT_VOICE_CONSENT_REQUIRED", "FRUNK_VOICE_EXPIRED"),
        )
        for ((key, values) in enums) for (value in values) {
            val row = FieldJson.encode(record("PRODUCT_VOICE_STATE_CHANGED", mapOf(key to value)))
            assertTrue(row.contains("\"$key\":\"$value\""))
        }
        for (key in listOf(FieldStateKeys.CONNECTION_PARK_FRESH, FieldStateKeys.PRODUCT_VOICE_CONSENT)) {
            for (value in listOf(true, false)) {
                val row = FieldJson.encode(record("TESLA_CONNECTION_STATE_CHANGED", mapOf(key to value)))
                assertTrue(row.contains("\"$key\":$value"))
            }
        }
        val secret = "PRIVATE-TRANSCRIPT-PCM-VIN-ID-VEHICLE-TIME"
        for (key in FieldStateKeys.PRODUCT_KEYS) {
            for (value in listOf(null, secret, "", " P", "listening", 1, 1L, 1.0,
                listOf(secret), mapOf("raw" to secret))) {
                val row = FieldJson.encode(record("PRODUCT_VOICE_STATE_CHANGED", mapOf(key to value,
                    "transcript" to secret, "pcm" to secret, "vin" to secret,
                    "vehicleTimestamp" to 1_700_000_000_000L)))
                assertTrue("$key=$value", row.contains("\"$key\":null"))
                assertFalse(row.contains(secret))
                assertFalse(row.contains("\"vehicleTimestamp\""))
            }
        }
        for (key in enums.keys) {
            val row = FieldJson.encode(record("PRODUCT_VOICE_STATE_CHANGED", mapOf(key to true)))
            assertTrue(row.contains("\"$key\":null"))
        }
        for (key in listOf(FieldStateKeys.CONNECTION_PARK_FRESH, FieldStateKeys.PRODUCT_VOICE_CONSENT)) {
            val row = FieldJson.encode(record("PRODUCT_VOICE_STATE_CHANGED", mapOf(key to "true")))
            assertTrue(row.contains("\"$key\":null"))
        }
    }

    @Test fun productSnapshotIsUnavailableInEveryHistoricalSchema() {
        val state = mapOf(
            FieldStateKeys.CONNECTION_STAGE to "MONITORING",
            FieldStateKeys.CONNECTION_GEAR to "P",
            FieldStateKeys.CONNECTION_PARK_FRESH to true,
            FieldStateKeys.CONNECTION_REASON to "CONNECTION_ENDED",
            FieldStateKeys.PRODUCT_VOICE_STAGE to "STOPPING",
            FieldStateKeys.PRODUCT_VOICE_REASON to "PRODUCT_VOICE_CLEANUP_FAILED_RESTART_REQUIRED",
            FieldStateKeys.PRODUCT_VOICE_CONSENT to false,
        )
        for (version in 1..32) {
            val row = FieldJson.encode(record("PRODUCT_VOICE_STATE_CHANGED", state).copy(version = version))
            for (key in FieldStateKeys.PRODUCT_KEYS) assertTrue(row.contains("\"$key\":null"))
        }
    }

    @Test fun productAcceptanceAndCleanupDoNotCreateFrunkSuccess() {
        for (event in listOf("PRODUCT_VOICE_CONSENT_GRANTED", "PRODUCT_VOICE_CONSENT_REVOKED",
            "PRODUCT_VOICE_START_REQUESTED", "PRODUCT_VOICE_STATE_CHANGED", "PRODUCT_VOICE_CLEANUP_FAILED",
            "PRODUCT_VOICE_COMMAND_ACCEPTED", "PRODUCT_VOICE_STOPPED", "TESLA_CONNECTION_STARTED",
            "TESLA_CONNECTION_BLOCKED", "TESLA_CONNECTION_CLEANUP_FAILED",
            "TESLA_CONNECTION_STATE_CHANGED", "TESLA_CONNECTION_GATT_OBSERVATION")) {
            assertTrue(event, FieldEvents.isAllowed(event))
        }
        assertFalse(FieldEvents.isAllowed("PRODUCT_VOICE_PRIVATE_TRANSCRIPT"))
        val accepted = FieldJson.encode(record("PRODUCT_VOICE_COMMAND_ACCEPTED", mapOf(
            FieldStateKeys.PRODUCT_VOICE_STAGE to "COMMAND_READY",
            FieldStateKeys.FRUNK_COMMAND_REQUESTED to true,
            FieldStateKeys.FRUNK_COMMAND_SENT to false,
            FieldStateKeys.FRUNK_COMMAND_ACKNOWLEDGED to false,
            FieldStateKeys.FRUNK_OPEN_OBSERVED to false,
            FieldStateKeys.FRUNK_LOCAL_CLOSED to false,
        )))
        assertTrue(accepted.contains("\"frunkCommandRequested\":true"))
        for (key in listOf(FieldStateKeys.FRUNK_COMMAND_SENT, FieldStateKeys.FRUNK_COMMAND_ACKNOWLEDGED,
            FieldStateKeys.FRUNK_OPEN_OBSERVED, FieldStateKeys.FRUNK_LOCAL_CLOSED)) {
            assertTrue(accepted.contains("\"$key\":false"))
        }
        val failed = FieldJson.encode(record("PRODUCT_VOICE_CLEANUP_FAILED", mapOf(
            FieldStateKeys.PRODUCT_VOICE_STAGE to "FAILED",
            FieldStateKeys.PRODUCT_VOICE_REASON to "PRODUCT_VOICE_CLEANUP_FAILED_RESTART_REQUIRED",
        )))
        assertTrue(failed.contains("\"productVoiceReason\":\"PRODUCT_VOICE_CLEANUP_FAILED_RESTART_REQUIRED\""))
        assertFalse(failed.contains("\"frunkCommandSent\":"))
        assertFalse(failed.contains("\"frunkLocalClosed\":"))
    }

    @Test fun nativeObservationPersistsEndpointLocalPeerAndNullableCleanupSnapshot() {
        val state = nativeState()
        val row = FieldJson.encode(record("TESLA_UWB_PEER_STOPPED", state))
        assertTrue(row.contains("\"uwbNativeSessionTag\":\"${"a".repeat(64)}\""))
        assertTrue(row.contains("\"uwbNativeConfigId\":1"))
        assertTrue(row.contains("\"uwbNativeChannel\":9"))
        assertTrue(row.contains("\"uwbNativePreambleIndex\":10"))
        assertTrue(row.contains("\"uwbNativeUpdateRate\":1"))
        assertTrue(row.contains("\"uwbNativePeerCount\":5"))
        assertTrue(row.contains("\"uwbNativeEvent\":\"PEER_STOPPED\""))
        assertTrue(row.contains("\"uwbNativePeerIndex\":4"))
        assertTrue(row.contains("\"uwbNativeElapsedSinceStartMs\":123"))
        assertTrue(row.contains("\"uwbNativeCloseRequested\":false"))
        for (event in listOf("STARTING", "OPENED", "PEER_STARTED", "PEER_STOPPED", "CLOSED")) {
            val encoded = FieldJson.encode(record("TESLA_UWB_BACKEND_STARTING",
                state + (FieldStateKeys.UWB_NATIVE_EVENT to event) +
                    (FieldStateKeys.UWB_NATIVE_PEER_INDEX to null)))
            assertTrue(encoded.contains("\"uwbNativeEvent\":\"$event\""))
            assertTrue(encoded.contains("\"uwbNativePeerIndex\":null"))
        }
        val absent = FieldJson.encode(record("TESLA_UWB_COMPLETE",
            FieldStateKeys.UWB_NATIVE_KEYS.associateWith { null }))
        for (key in FieldStateKeys.UWB_NATIVE_KEYS) assertTrue(absent.contains("\"$key\":null"))
    }

    @Test fun nativeObservationRejectsRawValuesBadRangesAndHistoricalSchemas() {
        for (version in 1..30) {
            val row = FieldJson.encode(record("TESLA_UWB_PEER_STOPPED", nativeState()).copy(version = version))
            for (key in FieldStateKeys.UWB_NATIVE_KEYS) assertTrue(row.contains("\"$key\":null"))
        }
        val secret = "PRIVATE-SESSION-ID-VIN-MAC-STS"
        val badValues = mapOf(
            FieldStateKeys.UWB_NATIVE_SESSION_TAG to listOf<Any?>(secret, "A".repeat(64), "a".repeat(63), 42, true),
            FieldStateKeys.UWB_NATIVE_CONFIG_ID to listOf<Any?>(0, 6, 1.0, "1", true),
            FieldStateKeys.UWB_NATIVE_CHANNEL to listOf<Any?>(0, 8, 10, "9", false),
            FieldStateKeys.UWB_NATIVE_PREAMBLE_INDEX to listOf<Any?>(8, 13, 9.0, "10", true),
            FieldStateKeys.UWB_NATIVE_UPDATE_RATE to listOf<Any?>(0, 4, "1", true),
            FieldStateKeys.UWB_NATIVE_PEER_COUNT to listOf<Any?>(0, -1, Int.MAX_VALUE.toLong() + 1, "5", true),
            FieldStateKeys.UWB_NATIVE_EVENT to listOf<Any?>(secret, "closed", "CLOSED ", 4, true),
            FieldStateKeys.UWB_NATIVE_PEER_INDEX to listOf<Any?>(-1, 5, "4", true),
            FieldStateKeys.UWB_NATIVE_ELAPSED_SINCE_START_MS to listOf<Any?>(-1, 4322, "123", true),
            FieldStateKeys.UWB_NATIVE_CLOSE_REQUESTED to listOf<Any?>(0, 1, "false", secret),
        )
        for ((key, values) in badValues) for (value in values + listOf(null, emptyList<String>(), mapOf("raw" to secret))) {
            val row = FieldJson.encode(record("TESLA_UWB_PEER_STOPPED", nativeState() + (key to value)))
            assertTrue("$key=$value", row.contains("\"$key\":null"))
            assertFalse(row.contains(secret))
        }
        val unknownCount = FieldJson.encode(record("TESLA_UWB_PEER_STOPPED",
            nativeState() + (FieldStateKeys.UWB_NATIVE_PEER_COUNT to null)))
        assertTrue(unknownCount.contains("\"uwbNativePeerIndex\":null"))
    }

    private fun nativeState(): Map<String, Any?> = mapOf(
        FieldStateKeys.UWB_NATIVE_SESSION_TAG to "a".repeat(64),
        FieldStateKeys.UWB_NATIVE_CONFIG_ID to 1,
        FieldStateKeys.UWB_NATIVE_CHANNEL to 9,
        FieldStateKeys.UWB_NATIVE_PREAMBLE_INDEX to 10,
        FieldStateKeys.UWB_NATIVE_UPDATE_RATE to 1,
        FieldStateKeys.UWB_NATIVE_PEER_COUNT to 5,
        FieldStateKeys.UWB_NATIVE_EVENT to "PEER_STOPPED",
        FieldStateKeys.UWB_NATIVE_PEER_INDEX to 4,
        FieldStateKeys.UWB_NATIVE_ELAPSED_SINCE_START_MS to 123L,
        FieldStateKeys.UWB_NATIVE_CLOSE_REQUESTED to false,
    )

    @Test fun deadlineCausePersistsOnlyFixedEnumsAndDoesNotReplaceTerminationReason() {
        val file = newFile()
        val sink = startSink(file)
        val causes = listOf("OPERATION", "INITIAL_NEGOTIATION", "PROTOCOL_STAGE", "WRITE")
        for (cause in causes) {
            assertTrue(sink.enqueue(record("TESLA_UWB_COMPLETE", mapOf(
                FieldStateKeys.UWB_DEADLINE_CAUSE to cause,
                FieldStateKeys.UWB_REASON to "DEADLINE_EXPIRED",
                FieldStateKeys.UWB_BACKEND_OPENED to false,
                FieldStateKeys.UWB_VALID_DISTANCE_COUNT to 0,
            ))))
        }
        assertTrue(sink.enqueue(record("TESLA_UWB_COMPLETE", mapOf(
            FieldStateKeys.UWB_DEADLINE_CAUSE to "WRITE",
            FieldStateKeys.UWB_REASON to "GATT_WRITE_FAILED",
        ))))
        assertTrue(sink.enqueue(record("TESLA_UWB_STARTED",
            mapOf(FieldStateKeys.UWB_DEADLINE_CAUSE to null))))
        assertTrue(sink.sync())
        val rows = file.readLines()
        for ((index, cause) in causes.withIndex()) {
            assertTrue(rows[index].contains("\"uwbDeadlineCause\":\"$cause\""))
            assertTrue(rows[index].contains("\"uwbReason\":\"DEADLINE_EXPIRED\""))
            assertTrue(rows[index].contains("\"uwbBackendOpened\":false"))
            assertTrue(rows[index].contains("\"uwbValidDistanceCount\":0"))
        }
        assertTrue(rows[causes.size].contains("\"uwbReason\":\"GATT_WRITE_FAILED\""))
        assertTrue(rows[causes.size].contains("\"uwbDeadlineCause\":\"WRITE\""))
        assertTrue(rows.last().contains("\"uwbDeadlineCause\":null"))
    }

    @Test fun deadlineCauseRejectsHistoricalAndRawValuesWithoutInferringFromTimeout() {
        val secret = "PRIVATE-RAW-KEY-STS-VIN-MAC"
        val state = mapOf(FieldStateKeys.UWB_DEADLINE_CAUSE to "INITIAL_NEGOTIATION",
            FieldStateKeys.UWB_REASON to "DEADLINE_EXPIRED")
        for (version in 1..29) {
            val row = FieldJson.encode(record("TESLA_UWB_COMPLETE", state).copy(version = version))
            assertTrue(row.contains("\"uwbDeadlineCause\":null"))
            assertTrue(row.contains("\"uwbReason\":\"DEADLINE_EXPIRED\""))
        }
        for (value in listOf<Any?>(null, "UNKNOWN", "operation", "WRITE ", "DEADLINE_EXPIRED",
            secret, true, 0, emptyList<String>(), mapOf("cause" to secret))) {
            val row = FieldJson.encode(record("TESLA_UWB_COMPLETE", mapOf(
                FieldStateKeys.UWB_DEADLINE_CAUSE to value,
                FieldStateKeys.UWB_REASON to "DEADLINE_EXPIRED",
                "rawDeadline" to secret,
            )))
            assertTrue(row.contains("\"uwbDeadlineCause\":null"))
            assertFalse(row.contains(secret))
            assertFalse(row.contains("\"rawDeadline\":"))
        }
        val unobserved = FieldJson.encode(record("TESLA_UWB_COMPLETE", mapOf(
            FieldStateKeys.UWB_REASON to "DEADLINE_EXPIRED")))
        assertFalse(unobserved.contains("\"uwbDeadlineCause\":"))
        for (cause in listOf("OPERATION", "INITIAL_NEGOTIATION", "PROTOCOL_STAGE", "WRITE")) {
            val row = FieldJson.encode(record("TESLA_UWB_COMPLETE",
                mapOf(FieldStateKeys.UWB_REASON to cause)))
            assertTrue(row.contains("\"uwbReason\":null"))
            assertFalse(row.contains("\"uwbDeadlineCause\":"))
        }
    }

    @Test fun ignoredRxPersistsOnlyItsFixedClassificationWithoutInventingSuccess() {
        val file = newFile()
        val sink = startSink(file)
        val state = mapOf(
            FieldStateKeys.UWB_GATT_EVENT to "PROTOCOL",
            FieldStateKeys.UWB_RX_FRAME_COUNT to 1L,
            FieldStateKeys.UWB_RX_PROTOCOL_COUNT to 1L,
            FieldStateKeys.UWB_RX_STEP to "IGNORED",
            FieldStateKeys.UWB_RX_DISPOSITION to "RESPONSE_BEFORE_START",
            FieldStateKeys.UWB_RX_PROTOCOL_RECEIVED_ELAPSED_MS to 999L,
            FieldStateKeys.UWB_RX_PROTOCOL_OBSERVED_ELAPSED_MS to 1_001L,
            FieldStateKeys.UWB_RX_AUTH_EVIDENCE to null,
            FieldStateKeys.UWB_RX_FIRA_KIND to null,
            FieldStateKeys.UWB_BACKEND_OPENED to false,
            FieldStateKeys.UWB_VALID_DISTANCE_COUNT to 0,
            FieldStateKeys.UWB_LAST_DISTANCE_METERS to 0.0,
            FieldStateKeys.UWB_LAST_RECEIVED_ELAPSED_MS to 900L,
        )
        assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", state)))
        assertTrue(sink.enqueue(record("TESLA_UWB_COMPLETE", state)))
        assertTrue(sink.enqueue(record("TESLA_UWB_STARTED")))
        assertTrue(sink.sync())
        val rows = file.readLines()
        for (row in rows.take(2)) {
            assertTrue(row.contains("\"uwbRxStep\":\"IGNORED\""))
            assertTrue(row.contains("\"uwbRxDisposition\":\"RESPONSE_BEFORE_START\""))
            assertTrue(row.contains("\"uwbRxFrameCount\":1"))
            assertTrue(row.contains("\"uwbRxProtocolCount\":1"))
            assertTrue(row.contains("\"uwbRxProtocolReceivedElapsedMs\":999"))
            assertTrue(row.contains("\"uwbRxAuthEvidence\":null"))
            assertTrue(row.contains("\"uwbRxFiraKind\":null"))
            assertTrue(row.contains("\"uwbBackendOpened\":false"))
            assertTrue(row.contains("\"uwbValidDistanceCount\":0"))
            assertTrue(row.contains("\"uwbLastDistanceMeters\":0.0"))
            assertTrue(row.contains("\"uwbLastReceivedElapsedMs\":900"))
        }
        assertFalse(rows.last().contains("\"uwbRxStep\":"))
        assertFalse(rows.last().contains("\"uwbRxDisposition\":"))
    }

    @Test fun ignoredRxCannotBackfillHistoricalSchemasOrAcceptNearMissEnums() {
        val state = mapOf(FieldStateKeys.UWB_RX_STEP to "IGNORED",
            FieldStateKeys.UWB_RX_DISPOSITION to "RESPONSE_BEFORE_START")
        for (version in 1..28) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION", state).copy(version = version))
            assertTrue(row.contains("\"uwbRxStep\":null"))
            assertTrue(row.contains("\"uwbRxDisposition\":null"))
        }
        for (value in listOf<Any?>("ignored", "IGNORED ", "RESPONSE_BEFORE_START ",
            "response_before_start", "PRIVATE-RAW-KEY", true, 0, emptyList<String>())) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                mapOf(FieldStateKeys.UWB_RX_STEP to value, FieldStateKeys.UWB_RX_DISPOSITION to value)))
            assertTrue(row.contains("\"uwbRxStep\":null"))
            assertTrue(row.contains("\"uwbRxDisposition\":null"))
        }
    }

    @Test fun ignoredSidecarAndApproachKeepDispositionSeparateFromAuthenticatedResults() {
        val disposition = VehicleNavigationRxDisposition.fromCode("RESPONSE_BEFORE_START")
        val navigation = VehicleNavigationObservation(
            kind = VehicleNavigationObservationKind.SIDECAR_RX,
            disposition = disposition, vehicleFixPresent = false,
        )
        val row = FieldJson.encode(record(VehicleNavigationObservation.EVENT).copy(navigationObservation = navigation))
        assertTrue(row.contains("\"disposition\":\"RESPONSE_BEFORE_START\""))
        assertTrue(row.contains("\"authEvidence\":null"))
        assertTrue(row.contains("\"vehicleFixPresent\":false"))
        val old = FieldJson.encode(record(VehicleNavigationObservation.EVENT).copy(
            version = 28, navigationObservation = navigation))
        assertTrue(old.contains("\"disposition\":null"))
        assertEquals(VehicleNavigationRxDisposition.UNKNOWN,
            VehicleNavigationRxDisposition.fromCode("RESPONSE_BEFORE_START "))
        val summary = TeslaApproachReadSummary(
            stage = "HANDSHAKING", outcome = "PENDING", responseDisposition = "RESPONSE_BEFORE_START")
        val approach = FieldJson.encode(record("TESLA_APPROACH_RX_RESPONSE").copy(teslaApproachRead = summary))
        assertTrue(approach.contains("\"responseDisposition\":\"RESPONSE_BEFORE_START\""))
        assertTrue(approach.contains("\"bodyVerified\":false,\"driveVerified\":false"))
        assertTrue(approach.contains("\"bodyReceivedElapsedMs\":null,\"driveReceivedElapsedMs\":null"))
        for (version in 1..28) {
            val previous = FieldJson.encode(record("TESLA_APPROACH_RX_RESPONSE").copy(
                version = version, teslaApproachRead = summary))
            assertTrue(previous.contains("\"responseDisposition\":null"))
            assertTrue(previous.contains("\"bodyVerified\":false,\"driveVerified\":false"))
        }
        val rejected = FieldJson.encode(record("TESLA_APPROACH_RX_RESPONSE").copy(
            teslaApproachRead = summary.copy(responseDisposition = "RESPONSE_BEFORE_START ")))
        assertTrue(rejected.contains("\"responseDisposition\":null"))
    }

    @Test fun vehicleTimeInvalidEvidenceIsNotBackdatedIntoHistoricalSchemas() {
        val payload = VehicleNavigationObservation(
            kind = VehicleNavigationObservationKind.SOURCE_STOPPED,
            reason = VehicleNavigationObservationReason.VEHICLE_FIX_TIME_INVALID,
            status = VehicleNavigationObservationStatus.VEHICLE_LOCATION_TIME_INVALID,
            collecting = false,
        )
        for (reason in listOf(VehicleNavigationObservationReason.VEHICLE_FIX_TIME_INVALID,
                VehicleNavigationObservationReason.LOCATION_STATE_TIME_INVALID)) {
            val current = record(VehicleNavigationObservation.EVENT).copy(
                version = 32, navigationObservation = payload.copy(reason = reason))
            val encoded = FieldJson.encode(current)
            assertTrue(encoded.contains("\"reason\":\"${reason.name}\""))
            assertTrue(encoded.contains("\"status\":\"VEHICLE_LOCATION_TIME_INVALID\""))
            for (version in 28..31) {
                val old = FieldJson.encode(current.copy(version = version))
                assertTrue(old.contains("\"reason\":null"))
                assertTrue(old.contains("\"status\":null"))
                assertTrue(old.contains("\"collecting\":false"))
                assertFalse(old.contains("VEHICLE_FIX_EXPIRED"))
                assertFalse(old.contains("VEHICLE_LOCATION_STALE"))
            }
        }
        val expired = FieldJson.encode(record(VehicleNavigationObservation.EVENT).copy(
            version = 31, navigationObservation = payload.copy(
                reason = VehicleNavigationObservationReason.VEHICLE_FIX_EXPIRED,
                status = VehicleNavigationObservationStatus.VEHICLE_LOCATION_STALE)))
        assertTrue(expired.contains("\"reason\":\"VEHICLE_FIX_EXPIRED\""))
        assertTrue(expired.contains("\"status\":\"VEHICLE_LOCATION_STALE\""))
    }

    @Test fun navigationEvidenceCannotLeakAcrossVersionEventOrFollowingRecord() {
        val payload = VehicleNavigationObservation(
            kind = VehicleNavigationObservationKind.RESULT,
            reason = VehicleNavigationObservationReason.LOCATION_FIX_TIME_MISSING,
            candidate = 2, attempt = 1, vehicleFixPresent = false,
        )
        for (version in 1..27) {
            val encoded = FieldJson.encode(record(VehicleNavigationObservation.EVENT).copy(
                version = version, navigationObservation = payload))
            assertFalse(encoded.contains("\"kind\":\"RESULT\""))
        }
        val file = newFile()
        val sink = startSink(file)
        assertTrue(sink.enqueue(record(VehicleNavigationObservation.EVENT).copy(navigationObservation = payload)))
        assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED").copy(navigationObservation = payload)))
        assertTrue(sink.enqueue(record(VehicleNavigationObservation.EVENT)))
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertTrue(rows[0].contains("\"reason\":\"LOCATION_FIX_TIME_MISSING\""))
        assertTrue(rows[0].contains("\"vehicleFixPresent\":false"))
        assertFalse(rows[1].contains("\"kind\":\"RESULT\""))
        assertFalse(rows[2].contains("\"kind\":\"RESULT\""))
    }

    @Test fun navigationStorageDiscardsFreeTextReasonsAndInvalidAttemptNumbers() {
        val secret = "SYNTHETIC-PRIVATE-LOCATION-AND-TOKEN"
        val payload = VehicleNavigationObservation(
            kind = VehicleNavigationObservationKind.TERMINAL,
            reason = VehicleNavigationObservationReason.fromCode(secret),
            authEvidence = VehicleNavigationAuthEvidence.fromCode(secret),
            candidate = -1, attempt = Long.MIN_VALUE, cleanupSucceeded = false,
        )
        val file = newFile()
        val sink = startSink(file)
        assertTrue(sink.enqueue(record(VehicleNavigationObservation.EVENT).copy(navigationObservation = payload)))
        assertTrue(sink.sync())
        val encoded = file.readText()
        assertFalse(encoded.contains(secret))
        assertTrue(encoded.contains("\"reason\":\"UNKNOWN\""))
        assertTrue(encoded.contains("\"authEvidence\":\"UNKNOWN\""))
        assertTrue(encoded.contains("\"candidate\":null,\"attempt\":null"))
        assertTrue(encoded.contains("\"cleanupSucceeded\":false"))
    }

    @Test fun frunkRecognitionStorageBoundaryMasksDirectPayloadAndDropsUnknownReason() {
        val file = newFile()
        val sink = startSink(file)
        // capture를 우회해도 저장 경계가 원문과 잘못된 신뢰도를 차단해야 한다.
        val raw = FrunkVoiceRecognitionResult(listOf(
            FrunkVoiceRecognitionCandidate("헤이 테슬라 가상사용자 프렁크 열어줘", 0.9f),
            FrunkVoiceRecognitionCandidate("테슬라@example.invalid", Float.NaN),
            FrunkVoiceRecognitionCandidate("프렁크123", -1f),
            FrunkVoiceRecognitionCandidate("SYNTHETIC-EXCESS-ID", 1f),
        ), accepted = false, reason = "SYNTHETIC-PRIVATE-REASON")
        assertTrue(sink.enqueue(record(FrunkVoiceRecognition.EVENT).copy(frunkVoiceRecognition = raw)))
        assertTrue(sink.sync())
        val text = file.readText()
        for (secret in listOf("가상사용자", "테슬라@example.invalid", "프렁크123",
            "SYNTHETIC-EXCESS-ID", "SYNTHETIC-PRIVATE-REASON")) assertFalse(text.contains(secret))
        assertTrue(text.contains("\"text\":\"헤이 테슬라 [마스킹] 프렁크 열어줘\""))
        assertTrue(text.contains("\"confidence\":0.9,\"masked\":true"))
        assertEquals(2, Regex("\"confidence\":null").findAll(text).count())
        assertTrue(text.contains("\"candidatesTruncated\":true"))
        assertTrue(text.contains("\"policy\":{\"accepted\":false,\"reason\":null}"))
        assertEquals(1, text.count { it == '\n' })
    }

    @Test fun frunkRecognitionCannotLeakOntoGeneralSpeechSupportOrLifecycleEvents() {
        val payload = FrunkVoiceRecognitionResult(
            listOf(FrunkVoiceRecognitionCandidate("헤이 테슬라 프렁크 열어줘", 0.99f)),
            true, null)
        val file = newFile()
        val sink = startSink(file)
        for (event in listOf("SPEECH_TRIAL_FINAL_RECEIVED_DIRECT_MIC", "SPEECH_SUPPORT_METADATA_RECEIVED",
            "FRUNK_VOICE_STARTED", "FRUNK_VOICE_CANCELED", "FRUNK_VOICE_EXPIRED",
            "FRUNK_VOICE_COMMAND_ACCEPTED")) {
            assertTrue(sink.enqueue(record(event).copy(frunkVoiceRecognition = payload)))
        }
        assertTrue(sink.sync())
        val text = file.readText()
        assertFalse(text.contains("헤이 테슬라 프렁크 열어줘"))
        assertTrue(text.lineSequence().filter { it.isNotEmpty() }.all {
            it.contains("\"frunkVoiceRecognition\":null")
        })
    }

    @Test fun frunkRecognitionIsSchema22OnlyAndDoesNotCarryIntoFollowingRows() {
        val payload = FrunkVoiceRecognition.capture(listOf("취소"), null, false, "FRUNK_VOICE_CANCELED")
        for (version in 1..21) {
            val encoded = FieldJson.encode(record(FrunkVoiceRecognition.EVENT).copy(
                version = version, frunkVoiceRecognition = payload))
            assertTrue(encoded.contains("\"frunkVoiceRecognition\":null"))
            assertFalse(encoded.contains("\"text\":"))
        }
        val file = newFile()
        val first = startSink(file)
        assertTrue(first.enqueue(record(FrunkVoiceRecognition.EVENT).copy(frunkVoiceRecognition = payload)))
        assertTrue(first.sync())
        val second = startSink(file)
        assertTrue(second.enqueue(record("FRUNK_VOICE_CANCELED")))
        assertTrue(second.sync())
        val rows = file.readLines()
        assertTrue(rows[0].contains("\"text\":\"취소\",\"confidence\":null"))
        assertTrue(rows[0].contains("\"policy\":{\"accepted\":false,\"reason\":\"FRUNK_VOICE_CANCELED\"}"))
        assertTrue(rows[1].contains("\"frunkVoiceRecognition\":null"))
        assertFalse(rows[1].contains("\"candidates\""))
    }

    @Test fun appendsAfterExistingCompleteLine() {
        val file = newFile()
        val prefix = "EARLIER-ROW\n"
        writeExisting(file, prefix)
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
        assertTrue(sink.sync())

        val text = file.readText()
        assertTrue(text.startsWith(prefix))
        val lines = text.split('\n').dropLastWhile { it.isEmpty() }
        assertEquals(2, lines.size)
        assertEquals("EARLIER-ROW", lines[0])
        assertTrue(lines[1].contains("\"event\":\"REAL_BLE_APPEARED\""))
        assertEquals(1L, sink.status().written)
        assertEquals(0, sink.status().pending)
    }

    @Test fun partialLastLineIsSeparatedWithoutRewritingIt() {
        val file = newFile()
        // 이전 실행이 중간에 끊겨 남은 잘린 행. 바이트를 고치지 않고 줄바꿈만 덧붙여야 한다.
        val truncated = "{\"version\":1,\"processId\":\"e5f"
        writeExisting(file, truncated)
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.sync())

        val bytes = file.readBytes()
        assertArrayEquals(truncated.toByteArray(Charsets.UTF_8), bytes.copyOfRange(0, truncated.length))
        assertTrue(bytes[truncated.length] == '\n'.code.toByte())
        val lines = file.readText().split('\n').dropLastWhile { it.isEmpty() }
        assertEquals(2, lines.size)
        assertEquals(truncated, lines[0])
    }

    @Test fun restartAppendsAfterPreviousProcessRecords() {
        val file = newFile()
        val first = startSink(file)
        assertTrue(first.enqueue(record("PROCESS_START_OFF")))
        assertTrue(first.sync())
        val firstRunBytes = file.readText()

        val second = startSink(file)
        assertTrue(second.enqueue(record("UI_RESUMED")))
        assertTrue(second.sync())

        val text = file.readText()
        assertEquals(firstRunBytes, text.substring(0, firstRunBytes.length))
        assertEquals(2, text.split('\n').dropLastWhile { it.isEmpty() }.size)
        // written은 sink별 확정 건수이고 fileBytes는 기존 로그까지 포함한 파일 전체 크기다.
        assertEquals(1L, second.status().written)
        assertEquals(file.length(), second.status().fileBytes)
    }

    @Test fun currentVersionRowsAppendWithoutRewritingLegacyVersionOneRows() {
        val file = newFile()
        // 예전 실행이 남긴 v1 행: speechTrial 키 자체가 없다.
        val legacy = "{\"version\":1,\"processId\":\"legacy\",\"appVersion\":\"0.8.0-probe\"," +
            "\"event\":\"PROCESS_START_OFF\",\"state\":{}}\n"
        writeExisting(file, legacy)

        val sink = startSink(file)
        assertTrue(
            sink.enqueue(
                record(
                    "SPEECH_TRIAL_FINAL_RECEIVED_TTS_PCM",
                    speechTrial = summary(confidence = 0.25f, decision = SpeechCommandDecision.FRUNK_OPEN_CANDIDATE),
                ),
            ),
        )
        assertTrue(sink.sync())

        val text = file.readText()
        // 기존 v1 행은 바이트까지 그대로 남고 새 행만 현재 버전으로 덧붙는다.
        assertEquals(legacy, text.substring(0, legacy.length))
        val lines = text.split('\n').dropLastWhile { it.isEmpty() }
        assertEquals(2, lines.size)
        assertFalse(lines[0].contains("speechTrial"))
        assertTrue(lines[1].startsWith("{\"version\":$FIELD_LOG_RECORD_VERSION,"))
        assertEquals("0.25", confidenceOf(lines[1]))
        assertTrue(trialValue(lines[1]).contains("\"mode\":\"TTS_PCM\""))
        assertTrue(trialValue(lines[1]).contains("\"commandDecision\":\"FRUNK_OPEN_CANDIDATE\""))
    }

    @Test fun repeatedRotationBoundsAllFilesAndRetainsRecentTransitionsInOrder() {
        val file = newFile()
        val budget = 4_096L
        val sink = startSink(file, limitBytes = budget)
        val transitions = listOf(
            "BLE_DETECTION_RESTORE_REQUESTED",
            "BLE_DETECTION_RESTORE_BLOCKED",
            "BLE_DETECTION_PREFERENCE_FAILED",
            "PROCESS_EXIT_OBSERVED",
            "BLE_LOCATION_PERMISSION_REQUIRED",
            "BLE_LOCATION_DISABLED",
            "REAL_BLE_APPEARED",
            "REAL_BLE_DISAPPEARED",
        )
        val expected = (0 until 80).map { index ->
            record(transitions[index % transitions.size]).copy(wallTimeMs = 1_700_000_000_000L + index)
        }
        for (row in expected) {
            assertTrue(sink.enqueue(row))
            assertTrue(sink.sync())
            assertBoundedRetention(file, sink, budget)
        }

        val rows = retainedRows(file)
        val times = rows.map(::wallTimeOf)
        assertTrue(times.first() > expected.first().wallTimeMs)
        assertEquals(expected.takeLast(rows.size).map { it.wallTimeMs }, times)
        assertEquals(expected.takeLast(4).map { it.event }, rows.takeLast(4).map(::eventOf))
        assertTrue(retainedFiles(file).all { it.isFile })
        assertEquals(80L, sink.status().written)
        assertEquals(0L, sink.status().dropped)
    }

    @Test fun reopenedSinkContinuesRotationAcrossExistingArchives() {
        val file = newFile()
        val budget = 4_096L
        val first = startSink(file, budget)
        for (index in 0 until 20) {
            assertTrue(first.enqueue(record("REAL_BLE_APPEARED").copy(wallTimeMs = index.toLong())))
            assertTrue(first.sync())
        }
        val second = startSink(file, budget)
        for (index in 20 until 40) {
            assertTrue(second.enqueue(record("REAL_BLE_DISAPPEARED").copy(wallTimeMs = index.toLong())))
            assertTrue(second.sync())
            assertBoundedRetention(file, second, budget)
        }
        val rows = retainedRows(file)
        assertEquals((40 - rows.size until 40).map(Int::toLong), rows.map(::wallTimeOf))
        assertEquals(20L, second.status().written)
        assertEquals(0L, second.status().dropped)
    }

    @Test fun fullPartialActiveRotatesWithoutJoiningTheNextRecord() {
        val file = newFile()
        val prefix = "{\"version\":8,\"state\":{"
        val partial = prefix + " ".repeat(512 - prefix.length)
        writeExisting(file, partial)
        val sink = startSink(file, limitBytes = 2_048L)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.sync())

        assertArrayEquals(partial.toByteArray(Charsets.UTF_8), archive(file, 1).readBytes())
        assertEquals(listOf("PROCESS_START_OFF"), file.readLines().map(::eventOf))
        assertBoundedRetention(file, sink, 2_048L)
        assertEquals(1L, sink.status().written)
    }

    @Test fun legacyLogsKeysSettingsAndSimilarNamesSurviveRepeatedRotation() {
        val file = newFile()
        val names = listOf(
            "events.jsonl", "events.1.jsonl", "events-v2.4.jsonl",
            "events-v2.1.jsonl.backup", "events-v2.backup.jsonl",
            "vehicle-key.dat", "settings.xml",
        )
        val preserved = names.mapIndexed { index, name ->
            File(file.parentFile, name).also { writeExisting(it, "preserved-$index\n") }
        }
        val before = preserved.map { it.readBytes() }
        val sink = startSink(file, limitBytes = 2_048L)
        repeat(30) { index ->
            assertTrue(sink.enqueue(record("REAL_BLE_APPEARED").copy(wallTimeMs = index.toLong())))
            assertTrue(sink.sync())
        }

        for (index in preserved.indices) assertArrayEquals(before[index], preserved[index].readBytes())
        assertBoundedRetention(file, sink, 2_048L)
        val rows = retainedRows(file)
        assertEquals((30 - rows.size until 30).map(Int::toLong), rows.map(::wallTimeOf))
        assertEquals(0L, sink.status().dropped)
    }

    @Test fun legacyTargetIsRejectedWithoutAppendingOrDeletingIt() {
        val file = File(newDir(), "events.jsonl")
        val original = "legacy-event\n"
        writeExisting(file, original)
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.sync())

        assertEquals(original, file.readText())
        assertEquals(FieldLogFailureCode.OPEN_FAILED, sink.status().failureCode)
        assertEquals(0L, sink.status().written)
        assertEquals(1L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
    }

    @Test fun activeAndArchiveSymlinksNeverModifyOrDeleteTheirTargets() {
        for (activeLink in listOf(true, false)) {
            val file = newFile()
            val legacy = File(file.parentFile, "events.jsonl")
            writeExisting(legacy, "untouched\n")
            val link = if (activeLink) file else archive(file, 3)
            Files.createSymbolicLink(link.toPath(), legacy.toPath())
            val sink = startSink(file)
            assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
            assertTrue(sink.sync())

            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals("untouched\n", legacy.readText())
            assertEquals(FieldLogFailureCode.OPEN_FAILED, sink.status().failureCode)
            assertEquals(0L, sink.status().written)
            assertEquals(1L, sink.status().dropped)
            assertEquals(0, sink.status().pending)
        }
    }

    @Test fun oversizedRecordFailsBeforeEvictingAnyRetainedFile() {
        val file = newFile()
        writeExisting(file, "active-row\n")
        for (index in 1..3) writeExisting(archive(file, index), "archived-$index\n")
        val before = retainedFiles(file).map { it.readBytes() }
        val sink = startSink(file, limitBytes = 2_048L)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF").copy(trialId = "x".repeat(512))))
        assertTrue(sink.sync())

        for ((index, retained) in retainedFiles(file).withIndex()) {
            assertArrayEquals(before[index], retained.readBytes())
        }
        assertEquals(FieldLogHealth.LIMIT_REACHED, sink.status().health)
        assertEquals(FieldLogFailureCode.LIMIT_REACHED, sink.status().failureCode)
        assertEquals(0L, sink.status().written)
        assertEquals(1L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
        assertFalse(sink.enqueue(record("UI_RESUMED")))
        assertEquals(2L, sink.status().dropped)
    }

    @Test fun rotationFailurePreservesConfirmedRowsAndAccountsForFollowingRecords() {
        val file = newFile()
        val records = listOf(record("PROCESS_START_OFF"), record("REAL_BLE_APPEARED"),
            record("REAL_BLE_DISAPPEARED"))
        // 각 행은 단독으로 들어가되 두 번째 행은 회전해야 하므로 실제 UTF-8 한 행 크기로 정한다.
        val segmentBytes = records.maxOf { (FieldJson.encode(it) + "\n").toByteArray(Charsets.UTF_8).size }.toLong()
        val sink = startSink(file, limitBytes = segmentBytes * 4)
        assertTrue(sink.enqueue(records[0]))
        assertTrue(sink.sync())
        val confirmed = file.readBytes()
        val blocker = archive(file, 3)
        assertTrue(blocker.mkdir())
        val protected = File(blocker, "keep")
        protected.writeText("untouched")
        sink.enqueue(records[1])
        sink.enqueue(records[2])
        assertTrue(sink.sync())

        assertArrayEquals(confirmed, file.readBytes())
        assertEquals("untouched", protected.readText())
        assertEquals(confirmed.size.toLong(), sink.status().fileBytes)
        assertEquals(FieldLogHealth.WRITE_FAILED, sink.status().health)
        assertEquals(FieldLogFailureCode.WRITE_FAILED, sink.status().failureCode)
        assertEquals(1L, sink.status().written)
        assertEquals(2L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
    }

    @Test fun writeExceptionAccountsPartialBytesAndDrainsAcceptedRecords() {
        val file = newFile()
        val sink = FieldEventSink(file, FIELD_LOG_LIMIT_BYTES, 512) { target ->
            object : RandomAccessFile(target, "rw") {
                override fun write(bytes: ByteArray) {
                    super.write(bytes, 0, bytes.size / 2)
                    throw IOException()
                }
            }
        }
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
        sink.start()
        assertTrue(sink.sync())

        assertTrue(file.length() > 0L)
        assertFalse(file.readText().endsWith("\n"))
        assertEquals(FieldLogFailureCode.WRITE_FAILED, sink.status().failureCode)
        assertEquals(file.length(), sink.status().fileBytes)
        assertEquals(0L, sink.status().written)
        assertEquals(2L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
        assertFalse(sink.enqueue(record("UI_RESUMED")))
        assertEquals(3L, sink.status().dropped)
    }

    @Test fun syncExceptionDoesNotConfirmAnUnsyncedRecord() {
        val file = newFile()
        val sink = FieldEventSink(file, FIELD_LOG_LIMIT_BYTES, 512) { target ->
            object : RandomAccessFile(target, "rw") {
                override fun write(value: Int) {
                    super.write(value)
                    close()
                }
            }
        }
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
        sink.start()
        assertTrue(sink.sync())

        assertEquals(listOf("PROCESS_START_OFF"), file.readLines().map(::eventOf))
        assertEquals(FieldLogFailureCode.SYNC_FAILED, sink.status().failureCode)
        assertEquals(file.length(), sink.status().fileBytes)
        assertEquals(0L, sink.status().written)
        assertEquals(2L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
    }

    @Test fun queueOverflowDuringWriteKeepsFirstFailureAndSettlesEveryPendingRecord() {
        val file = newFile()
        val enteredWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val negativePending = AtomicBoolean(false)
        val sink = FieldEventSink(file, FIELD_LOG_LIMIT_BYTES, 1) { target ->
            object : RandomAccessFile(target, "rw") {
                override fun write(bytes: ByteArray) {
                    enteredWrite.countDown()
                    if (!releaseWrite.await(5, TimeUnit.SECONDS)) throw IOException()
                    throw IOException()
                }
            }
        }
        sink.onStatusChanged = { if (sink.status().pending < 0) negativePending.set(true) }
        sink.start()
        try {
            assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
            assertTrue(enteredWrite.await(5, TimeUnit.SECONDS))
            assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
            assertFalse(sink.enqueue(record("REAL_BLE_DISAPPEARED")))
            assertEquals(2, sink.status().pending)
            sink.recordDroppedLocally()
            assertEquals(FieldLogHealth.QUEUE_OVERFLOW, sink.status().health)
            assertEquals(FieldLogFailureCode.QUEUE_OVERFLOW, sink.status().failureCode)
        } finally {
            releaseWrite.countDown()
        }
        assertTrue(sink.sync())

        assertEquals(FieldLogHealth.QUEUE_OVERFLOW, sink.status().health)
        assertEquals(FieldLogFailureCode.QUEUE_OVERFLOW, sink.status().failureCode)
        assertEquals(0L, sink.status().written)
        assertEquals(4L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
        assertFalse(negativePending.get())
    }

    @Test fun syncTimeoutDoesNotWaitIndefinitelyOrChangeRecordAccounting() {
        for (fullQueue in listOf(false, true)) {
            for (timeoutMs in listOf(25L, 0L, -1L, Long.MIN_VALUE)) {
                val enteredOpen = CountDownLatch(1)
                val releaseOpen = CountDownLatch(1)
                val sink = FieldEventSink(newFile(), FIELD_LOG_LIMIT_BYTES, 1) { target ->
                    enteredOpen.countDown()
                    if (!releaseOpen.await(5, TimeUnit.SECONDS)) throw IOException()
                    RandomAccessFile(target, "rw")
                }
                val finished = CountDownLatch(1)
                val result = AtomicBoolean(true)
                val waiter = Thread {
                    try {
                        result.set(sink.sync(timeoutMs))
                    } finally {
                        finished.countDown()
                    }
                }.apply { isDaemon = true }
                val recordCount = if (fullQueue) 2 else 1
                sink.start()
                try {
                    assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
                    assertTrue(enteredOpen.await(5, TimeUnit.SECONDS))
                    if (fullQueue) assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
                    waiter.start()
                    assertTrue(finished.await(1, TimeUnit.SECONDS))
                    assertFalse(result.get())
                    assertEquals(recordCount, sink.status().pending)
                    assertEquals(0L, sink.status().written)
                    assertEquals(0L, sink.status().dropped)
                    assertFalse(sink.status().failed)
                } finally {
                    releaseOpen.countDown()
                    waiter.interrupt()
                    waiter.join(5_000L)
                }
                assertFalse(waiter.isAlive)
                assertTrue(sink.sync())
                assertEquals(recordCount.toLong(), sink.status().written)
                assertEquals(0, sink.status().pending)
                assertEquals(0L, sink.status().dropped)
                assertFalse(sink.status().failed)
            }
        }
    }

    @Test fun syncSharesOneTimeoutBudgetAcrossQueueAndBarrierWaits() {
        val enteredFirstWrite = CountDownLatch(1)
        val enteredSecondWrite = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val releaseSecondWrite = CountDownLatch(1)
        val sink = FieldEventSink(newFile(), FIELD_LOG_LIMIT_BYTES, 1) { target ->
            object : RandomAccessFile(target, "rw") {
                private var firstWrite = true

                override fun write(bytes: ByteArray) {
                    val release = if (firstWrite) {
                        firstWrite = false
                        enteredFirstWrite.countDown()
                        releaseFirstWrite
                    } else {
                        enteredSecondWrite.countDown()
                        releaseSecondWrite
                    }
                    if (!release.await(5, TimeUnit.SECONDS)) throw IOException()
                    super.write(bytes)
                }
            }
        }
        val enteredSync = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicBoolean(true)
        val waiter = Thread {
            try {
                enteredSync.countDown()
                result.set(sink.sync(1_500L))
            } finally {
                finished.countDown()
            }
        }.apply { isDaemon = true }
        sink.start()
        try {
            assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
            assertTrue(enteredFirstWrite.await(5, TimeUnit.SECONDS))
            assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
            waiter.start()
            assertTrue(enteredSync.await(5, TimeUnit.SECONDS))
            assertFalse(finished.await(900, TimeUnit.MILLISECONDS))
            releaseFirstWrite.countDown()
            assertTrue(enteredSecondWrite.await(5, TimeUnit.SECONDS))
            // 큐에서 쓴 약 900ms 뒤에 배리어가 새 1,500ms 예산을 받으면 안 된다.
            assertTrue(finished.await(900, TimeUnit.MILLISECONDS))
            assertFalse(result.get())
            assertEquals(1L, sink.status().written)
            assertEquals(1, sink.status().pending)
            assertEquals(0L, sink.status().dropped)
            assertFalse(sink.status().failed)
        } finally {
            releaseFirstWrite.countDown()
            releaseSecondWrite.countDown()
            waiter.interrupt()
            waiter.join(5_000L)
        }
        assertFalse(waiter.isAlive)
        assertTrue(sink.sync())
        assertEquals(2L, sink.status().written)
        assertEquals(0, sink.status().pending)
        assertEquals(0L, sink.status().dropped)
        assertFalse(sink.status().failed)
    }

    @Test fun syncInterruptionPreservesFlagDuringQueueAndBarrierWaits() {
        for (fullQueue in listOf(false, true)) {
            val enteredOpen = CountDownLatch(1)
            val releaseOpen = CountDownLatch(1)
            val sink = FieldEventSink(newFile(), FIELD_LOG_LIMIT_BYTES, 1) { target ->
                enteredOpen.countDown()
                if (!releaseOpen.await(5, TimeUnit.SECONDS)) throw IOException()
                RandomAccessFile(target, "rw")
            }
            val enteredSync = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val result = AtomicBoolean(true)
            val interrupted = AtomicBoolean(false)
            val waiter = Thread {
                try {
                    enteredSync.countDown()
                    result.set(sink.sync(Long.MAX_VALUE))
                    interrupted.set(Thread.currentThread().isInterrupted)
                } finally {
                    finished.countDown()
                }
            }.apply { isDaemon = true }
            val recordCount = if (fullQueue) 2 else 1
            sink.start()
            try {
                assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
                assertTrue(enteredOpen.await(5, TimeUnit.SECONDS))
                if (fullQueue) assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
                waiter.start()
                assertTrue(enteredSync.await(5, TimeUnit.SECONDS))
                assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
                waiter.interrupt()
                assertTrue(finished.await(1, TimeUnit.SECONDS))
                assertFalse(result.get())
                assertTrue(interrupted.get())
                assertEquals(recordCount, sink.status().pending)
                assertEquals(0L, sink.status().written)
                assertEquals(0L, sink.status().dropped)
                assertFalse(sink.status().failed)
            } finally {
                releaseOpen.countDown()
                waiter.interrupt()
                waiter.join(5_000L)
            }
            assertFalse(waiter.isAlive)
            assertTrue(sink.sync())
            assertEquals(recordCount.toLong(), sink.status().written)
            assertEquals(0, sink.status().pending)
            assertEquals(0L, sink.status().dropped)
            assertFalse(sink.status().failed)
        }
    }


    @Test fun unopenableTargetReportsFixedFailureAndDropsRecords() {
        val dir = newDir()
        val blocker = File(dir, "blocker")
        blocker.writeText("blocking file, not a directory")
        val sink = startSink(File(blocker, File(FIELD_LOG_RELATIVE_PATH).name))

        sink.enqueue(record("PROCESS_START_OFF"))
        assertTrue(sink.sync())

        val status = sink.status()
        assertTrue(status.failed)
        assertEquals(FieldLogHealth.WRITE_FAILED, status.health)
        assertEquals(FieldLogFailureCode.OPEN_FAILED, status.failureCode)
        assertEquals(0L, status.written)
        assertEquals(1L, status.dropped)
        assertEquals("blocking file, not a directory", blocker.readText())
        // 실패 뒤 enqueue는 디스크 접근 없이 거부된다.
        assertFalse(sink.enqueue(record("PROCESS_START_OFF")))
        assertEquals(2L, sink.status().dropped)
    }

    @Test fun queueOverflowStopsBeforeWriterStarts() {
        val sink = FieldEventSink(newFile(), queueCapacity = 1)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertFalse(sink.enqueue(record("REAL_BLE_APPEARED")))

        val status = sink.status()
        assertEquals(FieldLogHealth.QUEUE_OVERFLOW, status.health)
        assertEquals(FieldLogFailureCode.QUEUE_OVERFLOW, status.failureCode)
        assertEquals(1L, status.dropped)
        // 대기 2까지 올린 뒤 offer 실패분을 즉시 되돌리므로 1이다. 음수나 중간 0이 남지 않는다.
        assertEquals(1, status.pending)
        assertFalse(sink.sync())
    }

    @Test fun queueOverflowBeforeStartDrainsAcceptedRecordsWhenWriterStarts() {
        val file = newFile()
        val sink = FieldEventSink(file, queueCapacity = 1)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertFalse(sink.enqueue(record("REAL_BLE_APPEARED")))
        sink.start()
        assertTrue(sink.sync())

        assertEquals(FieldLogHealth.QUEUE_OVERFLOW, sink.status().health)
        assertEquals(FieldLogFailureCode.QUEUE_OVERFLOW, sink.status().failureCode)
        assertEquals(0L, sink.status().written)
        assertEquals(2L, sink.status().dropped)
        assertEquals(0, sink.status().pending)
        assertFalse(file.exists())
    }

    @Test fun localDropMarksPersistentFailureAndStopsRecording() {
        val file = newFile()
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.sync())
        assertEquals(0, sink.status().pending)

        sink.recordDroppedLocally()

        val status = sink.status()
        assertTrue(status.failed)
        assertEquals(FieldLogHealth.WRITE_FAILED, status.health)
        assertEquals(FieldLogFailureCode.WRITE_FAILED, status.failureCode)
        assertEquals(1L, status.dropped)
        assertEquals(1L, status.written)
        // 유실 확정 뒤에는 파일에 더 쓰지 않는다.
        assertFalse(sink.enqueue(record("UI_RESUMED")))
        assertEquals(2L, sink.status().dropped)
        assertEquals(1, file.readText().split('\n').dropLastWhile { it.isEmpty() }.size)
    }

    @Test fun codesOutsideAllowlistAreCountedAndNeverWritten() {
        val file = newFile()
        val sink = startSink(file)
        assertFalse(sink.enqueue(record("VIN_1HGCM82633A004352")))
        assertFalse(sink.enqueue(record("REAL_BLE_APPEARED_EXTRA")))
        assertFalse(sink.enqueue(record("")))
        assertTrue(sink.enqueue(record("REAL_BLE_APPEARED")))
        assertTrue(sink.sync())

        val status = sink.status()
        assertEquals(3L, status.rejected)
        assertEquals(1L, status.written)
        val text = file.readText()
        assertFalse(text.contains("VIN"))
        assertFalse(text.contains("EXTRA"))
        assertEquals(1, text.split('\n').dropLastWhile { it.isEmpty() }.size)
    }

    @Test fun associationFailureCodeKeepsSignButNothingElse() {
        val file = newFile()
        val sink = startSink(file)
        // CompanionDeviceManager는 취소를 -1 같은 음수 코드로 넘길 수 있다. 이 code는 유실 없이 남아야 한다.
        assertTrue(sink.enqueue(record("ASSOCIATION_FAILED_CODE_-1")))
        assertFalse(sink.enqueue(record("ASSOCIATION_FAILED_CODE_")))
        assertFalse(sink.enqueue(record("ASSOCIATION_FAILED_CODE_12A")))
        assertFalse(sink.enqueue(record("ASSOCIATION_FAILED_CODE_1234567")))
        assertFalse(sink.enqueue(record("ASSOCIATION_FAILED_CODE_1HGCM82633A004352")))
        assertTrue(sink.sync())

        val status = sink.status()
        assertEquals(1L, status.written)
        assertEquals(4L, status.rejected)
        val text = file.readText()
        assertTrue(text.contains("\"event\":\"ASSOCIATION_FAILED_CODE_-1\""))
        assertFalse(text.contains("12A"))
        assertFalse(text.contains("1HGCM82633A004352"))
    }

    @Test fun stateValuesOutsideAllowlistBecomeNullAndUnknownKeysAreDropped() {
        val file = newFile()
        val sink = startSink(file)
        assertTrue(
            sink.enqueue(
                record(
                    "UI_RESUMED",
                    mapOf(
                        FieldStateKeys.PRESENT to true,
                        FieldStateKeys.ASSOCIATIONS to 2,
                        FieldStateKeys.SESSION_ACTIVE to false,
                        FieldStateKeys.STOP_REASON to "user@example.com",
                        "vin" to "1HGCM82633A004352",
                    ),
                ),
            ),
        )
        assertTrue(sink.sync())

        val text = file.readText()
        assertTrue(text.contains("\"present\":true"))
        assertTrue(text.contains("\"associations\":2"))
        assertTrue(text.contains("\"sessionActive\":false"))
        assertTrue(text.contains("\"stopReason\":null"))
        assertFalse(text.contains("user@example.com"))
        assertFalse(text.contains("1HGCM82633A004352"))
        assertFalse(text.contains("\"vin\""))
    }

    @Test fun uwbDistanceLogKeepsMetersAndClockFieldsButRejectsNonNumericAndNonFiniteValues() {
        val file = newFile()
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED", mapOf(
            FieldStateKeys.UWB_STAGE to "RANGING",
            FieldStateKeys.UWB_AZIMUTH_SUPPORTED to true,
            FieldStateKeys.UWB_ELEVATION_SUPPORTED to false,
            FieldStateKeys.UWB_LAST_PEER_INDEX to 1,
            FieldStateKeys.UWB_LAST_DISTANCE_METERS to 1.25,
            FieldStateKeys.UWB_LAST_CONFIDENCE to 1,
            FieldStateKeys.UWB_LAST_AZIMUTH_RAW to -540.0,
            FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE to 0,
            FieldStateKeys.UWB_LAST_ELEVATION_RAW to -0.25,
            FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE to 2,
            FieldStateKeys.UWB_LAST_FRAMEWORK_TIMESTAMP_MS to 9_876L,
            FieldStateKeys.UWB_LAST_RECEIVED_ELAPSED_MS to 10_000L,
        ))))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, "PRIVATE-MATERIAL")) {
            assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED", mapOf(
                FieldStateKeys.UWB_STAGE to "PRIVATE-MATERIAL",
                FieldStateKeys.UWB_LAST_DISTANCE_METERS to invalid,
                FieldStateKeys.UWB_LAST_PEER_INDEX to "PRIVATE-MATERIAL",
                FieldStateKeys.UWB_BACKEND_OPENED to "PRIVATE-MATERIAL",
                FieldStateKeys.UWB_REASON to "PRIVATE-MATERIAL",
                FieldStateKeys.UWB_OPEN_FAILED_REASON to "PRIVATE-MATERIAL",
                "sts" to "PRIVATE-MATERIAL",
            ))))
        }
        assertTrue(sink.sync())
        val lines = file.readLines()
        assertTrue(lines.first().contains("\"uwbAzimuthSupported\":true"))
        assertTrue(lines.first().contains("\"uwbElevationSupported\":false"))
        assertTrue(lines.first().contains("\"uwbLastPeerIndex\":1"))
        assertTrue(lines.first().contains("\"uwbLastDistanceMeters\":1.25"))
        assertTrue(lines.first().contains("\"uwbLastConfidence\":1"))
        assertTrue(lines.first().contains("\"uwbLastAzimuthRaw\":-540.0"))
        assertTrue(lines.first().contains("\"uwbLastAzimuthConfidence\":0"))
        assertTrue(lines.first().contains("\"uwbLastElevationRaw\":-0.25"))
        assertTrue(lines.first().contains("\"uwbLastElevationConfidence\":2"))
        assertTrue(lines.first().contains("\"uwbLastFrameworkTimestampMs\":9876"))
        assertTrue(lines.first().contains("\"uwbLastReceivedElapsedMs\":10000"))
        for (line in lines.drop(1)) {
            assertTrue(line.contains("\"uwbStage\":null"))
            assertTrue(line.contains("\"uwbLastDistanceMeters\":null"))
            assertTrue(line.contains("\"uwbLastPeerIndex\":null"))
            assertTrue(line.contains("\"uwbBackendOpened\":null"))
            assertTrue(line.contains("\"uwbReason\":null"))
            assertTrue(line.contains("\"uwbOpenFailedReason\":null"))
        }
        val text = lines.joinToString("\n")
        assertFalse(text.contains("PRIVATE-MATERIAL"))
        assertFalse(text.contains("NaN"))
        assertFalse(text.contains("Infinity"))
        assertFalse(text.contains("\"sts\""))
    }

    @Test fun uwbRawAnglesRemainFiniteSignedDoublesWithoutUnitConversionOrConfidenceFallback() {
        val file = newFile()
        val sink = startSink(file)
        val rawValues = listOf(-Double.MAX_VALUE, -540.0, -0.0, 0.0, Double.MIN_VALUE, Math.PI, Double.MAX_VALUE, null)
        for (raw in rawValues) {
            assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED", mapOf(
                FieldStateKeys.UWB_LAST_CONFIDENCE to 2,
                FieldStateKeys.UWB_LAST_AZIMUTH_RAW to raw,
                FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE to null,
                FieldStateKeys.UWB_LAST_ELEVATION_RAW to null,
                FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE to 0,
                FieldStateKeys.UWB_AZIMUTH_SUPPORTED to null,
                FieldStateKeys.UWB_ELEVATION_SUPPORTED to true,
            ))))
            assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED", mapOf(
                FieldStateKeys.UWB_LAST_CONFIDENCE to 0,
                FieldStateKeys.UWB_LAST_AZIMUTH_RAW to null,
                FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE to 2L,
                FieldStateKeys.UWB_LAST_ELEVATION_RAW to raw,
                FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE to null,
                FieldStateKeys.UWB_AZIMUTH_SUPPORTED to false,
                FieldStateKeys.UWB_ELEVATION_SUPPORTED to null,
            ))))
        }
        assertTrue(sink.sync())
        val lines = file.readLines()
        assertEquals(rawValues.size * 2, lines.size)
        for ((index, raw) in rawValues.withIndex()) {
            val azimuth = lines[index * 2]
            assertTrue(azimuth.contains("\"uwbLastAzimuthRaw\":$raw"))
            assertTrue(azimuth.contains("\"uwbLastAzimuthConfidence\":null"))
            assertTrue(azimuth.contains("\"uwbLastElevationRaw\":null"))
            assertTrue(azimuth.contains("\"uwbLastElevationConfidence\":0"))
            assertTrue(azimuth.contains("\"uwbAzimuthSupported\":null"))
            assertTrue(azimuth.contains("\"uwbElevationSupported\":true"))
            val elevation = lines[index * 2 + 1]
            assertTrue(elevation.contains("\"uwbLastAzimuthRaw\":null"))
            assertTrue(elevation.contains("\"uwbLastAzimuthConfidence\":2"))
            assertTrue(elevation.contains("\"uwbLastElevationRaw\":$raw"))
            assertTrue(elevation.contains("\"uwbLastElevationConfidence\":null"))
            assertTrue(elevation.contains("\"uwbAzimuthSupported\":false"))
            assertTrue(elevation.contains("\"uwbElevationSupported\":null"))
        }
    }

    @Test fun uwbRawAngleConfidenceAndCapabilityRejectWrongTypesAndPrivateMaterial() {
        val file = newFile()
        val sink = startSink(file)
        val privateObject = object {
            override fun toString() = "PRIVATE-MATERIAL"
        }
        val nonNumeric = listOf(null, true, false, "PRIVATE-MATERIAL", "RANGING",
            privateObject, listOf("PRIVATE-MATERIAL"), mapOf("private" to "PRIVATE-MATERIAL"))
        val cases = listOf(
            listOf(FieldStateKeys.UWB_LAST_AZIMUTH_RAW, FieldStateKeys.UWB_LAST_ELEVATION_RAW) to
                (nonNumeric + listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1, 1L, 1f)),
            listOf(FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE, FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE) to
                (nonNumeric + listOf(-1, 3, Long.MAX_VALUE, 1.0, 1f, Double.NaN, Double.POSITIVE_INFINITY)),
            listOf(FieldStateKeys.UWB_AZIMUTH_SUPPORTED, FieldStateKeys.UWB_ELEVATION_SUPPORTED) to
                listOf(null, 0, 1L, 1.0, "true", "PRIVATE-MATERIAL", privateObject,
                    listOf("PRIVATE-MATERIAL"), mapOf("private" to "PRIVATE-MATERIAL")),
        )
        val expectedKeys = mutableListOf<List<String>>()
        for ((keys, invalidValues) in cases) {
            for (invalid in invalidValues) {
                assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED",
                    keys.associateWith { invalid } + mapOf(
                        "vin" to "PRIVATE-MATERIAL", "mac" to "PRIVATE-MATERIAL",
                        "sts" to "PRIVATE-MATERIAL", "gps" to "PRIVATE-MATERIAL",
                        "uwbAngleUnit" to "PRIVATE-MATERIAL", "uwbDirection" to "PRIVATE-MATERIAL",
                    ))))
                expectedKeys += keys
            }
        }
        assertTrue(sink.sync())
        val lines = file.readLines()
        assertEquals(expectedKeys.size, lines.size)
        for ((index, keys) in expectedKeys.withIndex()) {
            for (key in keys) assertTrue(lines[index].contains("\"$key\":null"))
        }
        val text = lines.joinToString("\n")
        for (forbidden in listOf("PRIVATE-MATERIAL", "NaN", "Infinity", "\"vin\"", "\"mac\"",
            "\"sts\"", "\"gps\"", "\"uwbAngleUnit\"", "\"uwbDirection\"")) {
            assertFalse(text.contains(forbidden))
        }
    }

    @Test fun uwbAppendsWithoutChangingLegacyRowsOrCarryingAnglesIntoNextSample() {
        val file = newFile()
        val legacy = (1..24).joinToString("") { version ->
            FieldJson.encode(record("PROCESS_START_OFF").copy(version = version)) + "\n"
        }
        writeExisting(file, legacy)
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED", mapOf(
            FieldStateKeys.UWB_LAST_PEER_INDEX to 0,
            FieldStateKeys.UWB_LAST_AZIMUTH_RAW to -1.25,
            FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE to 1,
            FieldStateKeys.UWB_LAST_ELEVATION_RAW to 0.5,
            FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE to 2,
            FieldStateKeys.UWB_AZIMUTH_SUPPORTED to true,
            FieldStateKeys.UWB_ELEVATION_SUPPORTED to true,
        ))))
        assertTrue(sink.enqueue(record("TESLA_UWB_DISTANCE_RECEIVED", mapOf(
            FieldStateKeys.UWB_LAST_PEER_INDEX to 1,
            FieldStateKeys.UWB_LAST_AZIMUTH_RAW to null,
            FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE to null,
            FieldStateKeys.UWB_LAST_ELEVATION_RAW to null,
            FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE to null,
            FieldStateKeys.UWB_AZIMUTH_SUPPORTED to null,
            FieldStateKeys.UWB_ELEVATION_SUPPORTED to null,
        ))))
        assertTrue(sink.enqueue(record("TESLA_UWB_STARTED")))
        assertTrue(sink.sync())
        val text = file.readText()
        assertEquals(legacy, text.substring(0, legacy.length))
        val current = text.substring(legacy.length).lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(3, current.size)
        assertTrue(current[0].contains("\"uwbLastAzimuthRaw\":-1.25"))
        assertTrue(current[0].contains("\"uwbLastElevationRaw\":0.5"))
        for (key in listOf(FieldStateKeys.UWB_LAST_AZIMUTH_RAW, FieldStateKeys.UWB_LAST_AZIMUTH_CONFIDENCE,
            FieldStateKeys.UWB_LAST_ELEVATION_RAW, FieldStateKeys.UWB_LAST_ELEVATION_CONFIDENCE,
            FieldStateKeys.UWB_AZIMUTH_SUPPORTED, FieldStateKeys.UWB_ELEVATION_SUPPORTED)) {
            assertTrue(current[1].contains("\"$key\":null"))
            assertFalse(current[2].contains("\"$key\":"))
        }
    }

    @Test fun uwbGattSinkPreservesNativeStatusesAndNullableAdmissionWithoutPromotingCompletion() {
        val file = newFile()
        val sink = startSink(file)
        val cases = listOf(133 to false, 8 to true, 0 to null, -9876 to true)
        for ((status, accepted) in cases) {
            assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", mapOf(
                FieldStateKeys.UWB_GATT_STAGE to "CONNECTING",
                FieldStateKeys.UWB_GATT_EVENT to "CONNECTION_CALLBACK",
                FieldStateKeys.UWB_GATT_OPERATION_STARTED_ELAPSED_MS to 0L,
                FieldStateKeys.UWB_GATT_STAGE_STARTED_ELAPSED_MS to 1_000L,
                FieldStateKeys.UWB_GATT_OBSERVED_ELAPSED_MS to 4_321L,
                FieldStateKeys.UWB_GATT_CONNECT_ISSUED_ELAPSED_MS to 1_000L,
                FieldStateKeys.UWB_GATT_CONNECT_RETURNED_ELAPSED_MS to 1_001L,
                FieldStateKeys.UWB_GATT_ADVERTISEMENT_AGE_AT_CONNECT_MS to 0L,
                FieldStateKeys.UWB_GATT_CONNECTION_CALLBACK_ELAPSED_MS to 4_321L,
                FieldStateKeys.UWB_GATT_AUTO_CONNECT to false,
                FieldStateKeys.UWB_GATT_CONNECT_ACTION to if (status == 8) "RECONNECT" else "CREATE",
                FieldStateKeys.UWB_GATT_CONNECT_ACCEPTED to accepted,
                FieldStateKeys.UWB_GATT_CONNECTION_STATUS to status,
                FieldStateKeys.UWB_GATT_CONNECTION_STATE to 0,
                FieldStateKeys.UWB_GATT_DESCRIPTOR_REQUEST_STATUS to 201,
                FieldStateKeys.UWB_GATT_DESCRIPTOR_CALLBACK_STATUS to 0,
                FieldStateKeys.UWB_GATT_CONNECTED_OBSERVED to false,
                FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT to 0L,
            ))))
        }
        assertTrue(sink.enqueue(record("TESLA_UWB_STARTED",
            FieldStateKeys.UWB_GATT_KEYS.associateWith { null })))
        assertTrue(sink.sync())
        val rows = file.readLines()
        for ((index, case) in cases.withIndex()) {
            val row = rows[index]
            assertTrue(row.contains("\"uwbGattConnectionStatus\":${case.first}"))
            assertTrue(row.contains("\"uwbGattConnectAccepted\":${case.second}"))
            assertTrue(row.contains("\"uwbGattConnectionState\":0"))
            assertTrue(row.contains("\"uwbGattConnectedObserved\":false"))
            assertTrue(row.contains("\"uwbGattRxNotificationCount\":0"))
            assertTrue(row.contains("\"uwbGattOperationStartedElapsedMs\":0"))
            assertTrue(row.contains("\"uwbGattObservedElapsedMs\":4321"))
            assertTrue(row.contains("\"uwbGattDescriptorRequestStatus\":201"))
            assertTrue(row.contains("\"uwbGattDescriptorCallbackStatus\":0"))
        }
        for (key in FieldStateKeys.UWB_GATT_KEYS) assertTrue(rows.last().contains("\"$key\":null"))
    }

    @Test fun uwbGattSinkRejectsWrongTypesNegativeTimesFutureTimesAndOutOfRangeInts() {
        val file = newFile()
        val sink = startSink(file)
        val expectations = mutableListOf<List<String>>()
        val groups = listOf(
            FieldStateKeys.UWB_GATT_BOOLS.toList() to listOf<Any?>(null, 0, 1L, 1.0, "true", "PRIVATE-MATERIAL"),
            FieldStateKeys.UWB_GATT_INTS.toList() to listOf<Any?>(null, true, false, 1.0, "133",
                Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1, "PRIVATE-MATERIAL"),
            FieldStateKeys.UWB_GATT_TIMES.toList() to listOf<Any?>(null, -1L, 4_322L, Long.MAX_VALUE,
                true, 1.0, "1000", "PRIVATE-MATERIAL"),
            listOf(FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT) to
                listOf<Any?>(null, -1L, true, 0.0, "0", "PRIVATE-MATERIAL"),
            listOf(FieldStateKeys.UWB_GATT_STAGE, FieldStateKeys.UWB_GATT_EVENT,
                FieldStateKeys.UWB_GATT_CONNECT_ACTION) to
                listOf<Any?>(null, true, 0, "PRIVATE-MATERIAL", listOf("PRIVATE-MATERIAL")),
        )
        for ((keys, values) in groups) {
            for (value in values) {
                assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", keys.associateWith { value })))
                expectations += keys
            }
        }
        assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION",
            FieldStateKeys.UWB_GATT_TIMES.associateWith { 0L } +
                mapOf(FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT to Long.MAX_VALUE))))
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertEquals(expectations.size + 1, rows.size)
        for ((index, keys) in expectations.withIndex()) {
            for (key in keys) assertTrue(rows[index].contains("\"$key\":null"))
        }
        for (key in FieldStateKeys.UWB_GATT_TIMES) assertTrue(rows.last().contains("\"$key\":0"))
        assertTrue(rows.last().contains("\"uwbGattRxNotificationCount\":${Long.MAX_VALUE}"))
        assertFalse(file.readText().contains("PRIVATE-MATERIAL"))
    }

    @Test fun uwbGattEnumsAreExplicitAndLegacySchemasNeverGainGattObservations() {
        val file = newFile()
        val sink = startSink(file)
        val expected = mutableListOf<Pair<String, String>>()
        for ((key, values) in listOf(
            FieldStateKeys.UWB_GATT_STAGE to TeslaKeyStage.entries.map { it.name },
            FieldStateKeys.UWB_GATT_EVENT to TeslaGattEvent.entries.map { it.name },
            FieldStateKeys.UWB_GATT_CONNECT_ACTION to listOf("CREATE", "RECONNECT"),
        )) {
            for (value in values) {
                assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", mapOf(key to value))))
                expected += key to value
            }
        }
        for (version in 1..24) {
            assertTrue(sink.enqueue(record("TESLA_UWB_CAPABILITIES_RECEIVED",
                FieldStateKeys.UWB_GATT_KEYS.associateWith { 0L }).copy(version = version)))
        }
        assertTrue(sink.sync())
        val rows = file.readLines()
        for ((index, pair) in expected.withIndex()) {
            assertTrue(rows[index].contains("\"${pair.first}\":\"${pair.second}\""))
        }
        for (row in rows.drop(expected.size)) {
            for (key in FieldStateKeys.UWB_GATT_KEYS) assertTrue(row.contains("\"$key\":null"))
        }
    }

    @Test fun schema26RxSinkPreservesTypedObservationsAndDoesNotCarryMetadata() {
        val file = newFile()
        val sink = startSink(file)
        val state = mapOf(
            FieldStateKeys.UWB_GATT_EVENT to "PROTOCOL",
            FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT to 12L,
            FieldStateKeys.UWB_RX_FRAME_COUNT to 3L,
            FieldStateKeys.UWB_RX_PROTOCOL_COUNT to 1L,
            FieldStateKeys.UWB_RX_NOTIFICATION_FRAME_COUNT to 0,
            FieldStateKeys.UWB_RX_DECODER_INCOMPLETE to false,
            FieldStateKeys.UWB_RX_DECODER_FAILED to false,
            FieldStateKeys.UWB_RX_PROTOCOL_RECEIVED_ELAPSED_MS to 1_000L,
            FieldStateKeys.UWB_RX_PROTOCOL_OBSERVED_ELAPSED_MS to 4_000L,
            FieldStateKeys.UWB_RX_STEP to "UWB_INPUT",
            FieldStateKeys.UWB_RX_INPUT_KIND to "RAW_FIRA",
            FieldStateKeys.UWB_RX_AEAD to "NOT_ATTEMPTED",
            FieldStateKeys.UWB_RX_FIRA_KIND to "REQUEST",
            FieldStateKeys.UWB_RX_DISPOSITION to "FIRA_REQUEST",
            FieldStateKeys.UWB_RX_EXPECTED_DOMAIN to 0,
            FieldStateKeys.UWB_RX_FROM_PRESENT to false,
            FieldStateKeys.UWB_RX_FROM_DOMAIN to Int.MIN_VALUE,
            FieldStateKeys.UWB_RX_TO_PRESENT to false,
            FieldStateKeys.UWB_RX_REQUEST_UUID_PRESENT to false,
            FieldStateKeys.UWB_RX_REQUEST_UUID_MATCHES to null,
            FieldStateKeys.UWB_RX_TO_ROUTE_MATCHES to null,
            FieldStateKeys.UWB_RX_SIGNED_STATUS_PRESENT to true,
            FieldStateKeys.UWB_RX_SIGNED_MESSAGE_FAULT to 0,
            FieldStateKeys.UWB_RX_OPERATION_STATUS to Int.MAX_VALUE,
            FieldStateKeys.UWB_LAST_AZIMUTH_RAW to -1.25,
        )
        assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", state).copy(version = 26)))
        assertTrue(sink.enqueue(record("TESLA_UWB_COMPLETE", state)))
        assertTrue(sink.enqueue(record("TESLA_UWB_STARTED",
            FieldStateKeys.UWB_RX_KEYS.associateWith { null } +
                mapOf(FieldStateKeys.UWB_RX_FRAME_COUNT to 0L, FieldStateKeys.UWB_RX_PROTOCOL_COUNT to 0L))))
        assertTrue(sink.sync())
        val rows = file.readLines()
        for (row in rows.take(2)) {
            assertTrue(row.contains("\"uwbGattEvent\":\"PROTOCOL\""))
            assertTrue(row.contains("\"uwbRxFrameCount\":3"))
            assertTrue(row.contains("\"uwbRxProtocolCount\":1"))
            assertTrue(row.contains("\"uwbRxNotificationFrameCount\":0"))
            assertTrue(row.contains("\"uwbRxDecoderIncomplete\":false"))
            assertTrue(row.contains("\"uwbRxFiraKind\":\"REQUEST\""))
            assertTrue(row.contains("\"uwbRxDisposition\":\"FIRA_REQUEST\""))
            assertTrue(row.contains("\"uwbRxSignedMessageFault\":0"))
            assertTrue(row.contains("\"uwbRxOperationStatus\":${Int.MAX_VALUE}"))
            assertTrue(row.contains("\"uwbRxFromDomain\":${Int.MIN_VALUE}"))
            assertTrue(row.contains("\"uwbRxRequestUuidMatches\":null"))
            assertTrue(row.contains("\"uwbLastAzimuthRaw\":-1.25"))
        }
        assertTrue(rows.last().contains("\"uwbRxFrameCount\":0"))
        assertTrue(rows.last().contains("\"uwbRxProtocolCount\":0"))
        for (key in FieldStateKeys.UWB_RX_KEYS - FieldStateKeys.UWB_RX_COUNTS) {
            assertTrue(rows.last().contains("\"$key\":null"))
        }
    }

    @Test fun schema26RxAllowlistRejectsSecretsNonFiniteNumbersAndBooleanNumbers() {
        val file = newFile()
        val sink = startSink(file)
        val secret = "PRIVATE-RAW-KEY-STS-VIN-MAC-UUID"
        val groups = listOf(
            FieldStateKeys.UWB_RX_BOOLS.toList() to listOf<Any?>(0, 1L, 0.0, "false", secret),
            FieldStateKeys.UWB_RX_INTS.toList() to listOf<Any?>(true, false, 0.0, "0",
                Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1, Double.NaN, Double.POSITIVE_INFINITY, secret),
            FieldStateKeys.UWB_RX_COUNTS.toList() to listOf<Any?>(true, false, -1L, 0.0, "0",
                Double.NaN, Double.NEGATIVE_INFINITY, secret),
            FieldStateKeys.UWB_RX_TIMES.toList() to listOf<Any?>(true, false, -1L, 4_322L, Long.MAX_VALUE,
                0.0, "0", Double.NaN, Double.POSITIVE_INFINITY, secret),
            listOf(FieldStateKeys.UWB_RX_NOTIFICATION_FRAME_COUNT) to
                listOf<Any?>(-1, 17, true, false, 0.0, "0", Double.NaN, Double.POSITIVE_INFINITY, secret),
            FieldStateKeys.UWB_RX_ENUMS.keys.toList() to listOf<Any?>(true, 0, secret, listOf(secret)),
        )
        val expected = mutableListOf<List<String>>()
        for ((keys, values) in groups) {
            for (value in values) {
                val state = keys.associateWith { value } + mapOf(
                    "rawPayload" to secret, "key" to secret, "sts" to secret,
                    "vin" to secret, "mac" to secret, "uuid" to secret)
                assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", state)))
                expected += keys
            }
        }
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertEquals(expected.size, rows.size)
        for ((index, keys) in expected.withIndex()) {
            for (key in keys) assertTrue(rows[index].contains("\"$key\":null"))
        }
        assertFalse(file.readText().contains(secret))
    }

    @Test fun schema26RxVocabularyBoundsAndLegacyNullAreExplicit() {
        val file = newFile()
        val sink = startSink(file)
        val expected = mutableListOf<Pair<String, String>>()
        for ((key, values) in FieldStateKeys.UWB_RX_ENUMS) {
            for (value in values) {
                assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", mapOf(key to value))))
                expected += key to "\"$value\""
            }
        }
        for ((key, value) in FieldStateKeys.UWB_RX_BOOLS.associateWith { false } +
            FieldStateKeys.UWB_RX_INTS.associateWith { Int.MIN_VALUE } +
            FieldStateKeys.UWB_RX_COUNTS.associateWith { Long.MAX_VALUE } +
            FieldStateKeys.UWB_RX_TIMES.associateWith { 0L } +
            mapOf(FieldStateKeys.UWB_RX_NOTIFICATION_FRAME_COUNT to 16)) {
            assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", mapOf(key to value))))
            expected += key to value.toString()
        }
        for (version in 1..25) {
            assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION",
                FieldStateKeys.UWB_RX_KEYS.associateWith { 0 } +
                    mapOf(FieldStateKeys.UWB_GATT_EVENT to "PROTOCOL")).copy(version = version)))
        }
        assertTrue(sink.sync())
        val rows = file.readLines()
        for ((index, pair) in expected.withIndex()) {
            assertTrue(rows[index].contains("\"${pair.first}\":${pair.second}"))
        }
        for (row in rows.drop(expected.size)) {
            for (key in FieldStateKeys.UWB_RX_KEYS) assertTrue(row.contains("\"$key\":null"))
            assertTrue(row.contains("\"uwbGattEvent\":null"))
        }
    }

    @Test fun schema26FullRxRowsUseActualUtf8SizeForExistingRotationBudget() {
        val file = newFile()
        val state = FieldStateKeys.UWB_RX_ENUMS.mapValues { (_, values) -> values.maxBy { it.length } } +
            FieldStateKeys.UWB_RX_BOOLS.associateWith { false } +
            FieldStateKeys.UWB_RX_INTS.associateWith { Int.MIN_VALUE } +
            FieldStateKeys.UWB_RX_COUNTS.associateWith { Long.MAX_VALUE } +
            FieldStateKeys.UWB_RX_TIMES.associateWith { 4_321L } +
            FieldStateKeys.UWB_GATT_TIMES.associateWith { 4_321L } +
            FieldStateKeys.UWB_GATT_BOOLS.associateWith { false } +
            FieldStateKeys.UWB_GATT_INTS.associateWith { Int.MIN_VALUE } +
            mapOf(FieldStateKeys.UWB_RX_NOTIFICATION_FRAME_COUNT to 16,
                FieldStateKeys.UWB_GATT_EVENT to "PROTOCOL",
                FieldStateKeys.UWB_GATT_STAGE to "NEGOTIATING_MTU",
                FieldStateKeys.UWB_GATT_CONNECT_ACTION to "RECONNECT",
                FieldStateKeys.UWB_GATT_RX_NOTIFICATION_COUNT to Long.MAX_VALUE,
                FieldStateKeys.UWB_LAST_AZIMUTH_RAW to -Double.MAX_VALUE,
                FieldStateKeys.UWB_LAST_ELEVATION_RAW to Double.MAX_VALUE)
        val records = (0 until 32).map { index ->
            record("TESLA_UWB_GATT_OBSERVATION", state).copy(wallTimeMs = 1_700_000_000_000L + index)
        }
        val actualMaxLineBytes = records.maxOf { (FieldJson.encode(it) + "\n").toByteArray(Charsets.UTF_8).size }.toLong()
        assertTrue(actualMaxLineBytes <= FIELD_LOG_LIMIT_BYTES / 4)
        val limitBytes = actualMaxLineBytes * 8
        val sink = startSink(file, limitBytes = limitBytes)
        for (row in records) {
            assertTrue(sink.enqueue(row))
            assertTrue(sink.sync())
            assertBoundedRetention(file, sink, limitBytes)
        }
        val retained = retainedRows(file)
        assertEquals(records.takeLast(retained.size).map { it.wallTimeMs }, retained.map(::wallTimeOf))
        assertEquals(0L, sink.status().dropped)
        assertEquals(0L, sink.status().rejected)
    }

    @Test fun schema27BodySinkDistinguishesEmptyFromUnobservedAndDoesNotCarryMetadata() {
        val file = newFile()
        val sink = startSink(file)
        val unobserved = FieldStateKeys.UWB_RX_SUMMARY_KEYS.associateWith { null }
        val empty = bodyState(bytes = 0, fields = "") +
            mapOf(FieldStateKeys.UWB_RX_REQUEST_CORRELATION to "CURRENT")
        assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION", empty)))
        assertTrue(sink.enqueue(record("TESLA_UWB_COMPLETE", empty)))
        assertTrue(sink.enqueue(record("TESLA_UWB_STARTED", unobserved)))
        assertTrue(sink.sync())
        val rows = file.readLines()
        for (row in rows.take(2)) {
            assertTrue(row.contains("\"uwbRxBodyBytes\":0"))
            assertTrue(row.contains("\"uwbRxBodyStatus\":\"COMPLETE\""))
            assertTrue(row.contains("\"uwbRxBodyFields\":\"\""))
            assertTrue(row.contains("\"uwbRxRequestCorrelation\":\"CURRENT\""))
        }
        for (key in FieldStateKeys.UWB_RX_SUMMARY_KEYS) {
            assertTrue(rows.last().contains("\"$key\":null"))
        }
        assertEquals(FieldLogHealth.OK, sink.status().health)
        assertEquals(0L, sink.status().dropped)
    }

    @Test fun schema27BodyVocabularyAndCanonicalWireLengthsArePreserved() {
        val shapes = listOf(
            "1/0/1", "1/0/10", "1/1/8", "1/2/0", "1/2/127",
            "1/2/128", "1/2/4093", "1/5/4", "536870911/0/1",
            "1/0/1,2/1/8,3/2/0,4/5/4",
        )
        for (status in listOf("COMPLETE", "MALFORMED", "UNSUPPORTED")) {
            for (fields in shapes) {
                if (status != "COMPLETE" && fields == "1/2/4093") continue // 남은 tag 한 바이트도 들어갈 수 없다.
                val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                    bodyState(bytes = 4096L, status = status, fields = fields)))
                assertTrue(row.contains("\"uwbRxBodyBytes\":4096"))
                assertTrue(row.contains("\"uwbRxBodyStatus\":\"$status\""))
                assertTrue(row.contains("\"uwbRxBodyFields\":\"$fields\""))
            }
        }
        for (correlation in listOf("CURRENT", "PREVIOUS", "UNMATCHED")) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                mapOf(FieldStateKeys.UWB_RX_REQUEST_CORRELATION to correlation)))
            assertTrue(row.contains("\"uwbRxRequestCorrelation\":\"$correlation\""))
        }
    }

    @Test fun schema27BodyRejectsNonCanonicalNumericTriplesAsAnAtomicGroup() {
        val malformed = listOf(
            "secret-marker", "TESLA_UWB_STARTED", "1/0", "1/0/1/2", "1//1",
            "/0/1", "1/0/", ",1/0/1", "1/0/1,", "1/0/1,,2/0/1",
            "1/0/1 2/0/1", " 1/0/1", "1/0/1 ", "1/0/1\n", "1/0/1\u0000",
            "１/0/1", "1/٠/1", "1/0/１", "+1/0/1", "1/+0/1", "1/0/+1",
            "-1/0/1", "1/0/-1", "01/0/1", "1/00/1", "1/0/01", "1/2/00",
            "0/0/1", "536870912/0/1", "999999999999999999/0/1",
            "1/3/0", "1/4/0", "1/6/0", "1/7/0", "1/9/0",
            "1/0/0", "1/0/11", "1/1/7", "1/1/9", "1/5/3", "1/5/5",
            "1/2/4097", "1/2/999999999999999999",
            List(9) { "1/0/1" }.joinToString(","),
            List(8) { "536870911/2/4096" }.joinToString(",") + "0",
        )
        for (fields in malformed) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                bodyState(bytes = 4096, fields = fields) +
                    mapOf(FieldStateKeys.UWB_RX_REQUEST_CORRELATION to "PREVIOUS")))
            assertBodySummaryNull(row)
            assertTrue(row.contains("\"uwbRxRequestCorrelation\":\"PREVIOUS\""))
        }
    }

    @Test fun schema27BodyRejectsPartialWrongTypesAndUnlistedStatusWithoutStringFallback() {
        val complete = bodyState()
        val invalid = mutableListOf<Map<String, Any?>>()
        for (key in FieldStateKeys.UWB_RX_BODY_KEYS) {
            invalid += complete - key
            invalid += complete + (key to null)
            for (value in listOf<Any?>(true, false, 1.0, listOf(1), "TESLA_UWB_STARTED")) {
                invalid += complete + (key to value)
            }
        }
        for (bytes in listOf<Any?>(-1, 4097, Long.MAX_VALUE, "2")) {
            invalid += bodyState(bytes = bytes)
        }
        for (status in listOf("complete", "CURRENT", "COMPLETE ", "")) {
            invalid += bodyState(status = status)
        }
        for (state in invalid) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION", state))
            for (key in FieldStateKeys.UWB_RX_BODY_KEYS.intersect(state.keys)) {
                assertTrue(row.contains("\"$key\":null"))
            }
        }
        for (value in listOf<Any?>(true, 0, "TESLA_UWB_STARTED", "current", "CURRENT ", listOf("CURRENT"))) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                complete + (FieldStateKeys.UWB_RX_REQUEST_CORRELATION to value)))
            assertTrue(row.contains("\"uwbRxRequestCorrelation\":null"))
            assertTrue(row.contains("\"uwbRxBodyFields\":\"1/0/1\""))
        }
    }

    @Test fun schema27BodyMinimumStructureFitsByteBudgetWithoutClaimingExactEncodingLength() {
        for ((fields, minimum) in listOf(
            "1/0/1" to 2, "16/0/1" to 3, "536870911/0/1" to 6,
            "1/1/8" to 9, "1/2/0" to 2, "1/2/127" to 129,
            "1/2/128" to 131, "1/5/4" to 5,
            "1/0/1,2/2/0" to 4,
        )) {
            for (status in listOf("COMPLETE", "MALFORMED", "UNSUPPORTED")) {
                val short = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                    bodyState(bytes = minimum - 1, status = status, fields = fields)))
                assertBodySummaryNull(short)
                if (status != "COMPLETE") {
                    assertBodySummaryNull(FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                        bodyState(bytes = minimum, status = status, fields = fields))))
                }
                for (bytes in if (status == "COMPLETE") listOf(minimum, minimum + 1) else listOf(minimum + 1)) {
                    val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                        bodyState(bytes = bytes, status = status, fields = fields)))
                    assertTrue(row.contains("\"uwbRxBodyFields\":\"$fields\""))
                }
            }
        }
        assertBodySummaryNull(FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
            bodyState(bytes = 1, fields = ""))))
        for (fields in listOf("1/2/4094", "1/2/4096")) {
            assertBodySummaryNull(FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                bodyState(bytes = 4096, fields = fields))))
        }
    }

    @Test fun schema27BodyStatusRejectsImpossibleCompletionAndBudgetCombinations() {
        val seven = List(7) { "1/0/1" }.joinToString(",")
        val eight = List(8) { "1/0/1" }.joinToString(",")
        val valid = listOf(
            Triple("TRUNCATED", eight, 17),
            Triple("MALFORMED", seven, 15),
            Triple("UNSUPPORTED", seven, 15),
            Triple("MALFORMED", "", 1),
            Triple("UNSUPPORTED", "", 1),
            Triple("COMPLETE", eight, 16),
            Triple("COMPLETE", eight, 17), // 非canonical tag encoding의 정확한 길이는 주장하지 않는다.
        )
        for ((status, fields, bytes) in valid) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                bodyState(bytes = bytes, status = status, fields = fields)))
            assertTrue(row.contains("\"uwbRxBodyStatus\":\"$status\""))
            assertTrue(row.contains("\"uwbRxBodyFields\":\"$fields\""))
        }
        val invalid = listOf(
            Triple("TRUNCATED", seven, 17),
            Triple("TRUNCATED", eight, 16),
            Triple("TRUNCATED", "", 1),
            Triple("TRUNCATED", "1/0/1", 3),
            Triple("MALFORMED", eight, 17),
            Triple("UNSUPPORTED", eight, 17),
            Triple("MALFORMED", seven, 14),
            Triple("UNSUPPORTED", seven, 14),
            Triple("MALFORMED", "", 0),
            Triple("UNSUPPORTED", "", 0),
            Triple("TRUNCATED", "", 0),
        )
        for ((status, fields, bytes) in invalid) {
            assertBodySummaryNull(FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION",
                bodyState(bytes = bytes, status = status, fields = fields))))
        }
    }

    @Test fun schema27MaximumBoundedShapePersistsButOverBudgetShapeDoesNot() {
        // 8개, 최대 field 번호. body 상한 안에서 3개 네자리+5개 세자리 payload 길이를 보존한다.
        val fields = (List(3) { "536870911/2/1000" } + List(5) { "536870911/2/100" }).joinToString(",")
        val file = newFile()
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION",
            bodyState(bytes = 4096, fields = fields))))
        // 문법상 135자라도 구조 최소길이가 4096을 넘으면 전체 요약을 제거한다.
        val overBudget = List(8) { "536870911/2/4096" }.joinToString(",")
        assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION",
            bodyState(bytes = 4096, fields = overBudget))))
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertTrue(rows.first().contains("\"uwbRxBodyFields\":\"$fields\""))
        assertBodySummaryNull(rows.last())
        assertFalse(rows.last().contains(overBudget))
        assertEquals(FieldLogHealth.OK, sink.status().health)
        assertEquals(0L, sink.status().dropped)
    }

    @Test fun legacySchemasIgnoreAllSchema27KeysWhileSchema26RxStaysAvailable() {
        val state = bodyState() + mapOf(
            FieldStateKeys.UWB_RX_REQUEST_CORRELATION to "CURRENT",
            FieldStateKeys.UWB_RX_FRAME_COUNT to 1L,
            FieldStateKeys.UWB_RX_FIRA_KIND to "UNRECOGNIZED",
        )
        for (version in 1..26) {
            val row = FieldJson.encode(record("TESLA_UWB_GATT_OBSERVATION", state).copy(version = version))
            for (key in FieldStateKeys.UWB_RX_SUMMARY_KEYS) assertTrue(row.contains("\"$key\":null"))
            if (version == 26) {
                assertTrue(row.contains("\"uwbRxFrameCount\":1"))
                assertTrue(row.contains("\"uwbRxFiraKind\":\"UNRECOGNIZED\""))
            }
        }
    }

    @Test fun schema27SinkNeverPersistsRawSecretMarkersOrArbitraryStringFallbacks() {
        val file = newFile()
        val sink = startSink(file)
        val secret = "RAW-SECRET-KEY-STS-VIN-MAC-UUID-BODY"
        for (key in FieldStateKeys.UWB_RX_SUMMARY_KEYS) {
            assertTrue(sink.enqueue(record("TESLA_UWB_GATT_OBSERVATION",
                bodyState() + mapOf(key to secret, "rawBody" to secret, "payload" to secret))))
        }
        assertTrue(sink.sync())
        assertFalse(file.readText().contains(secret))
        assertEquals(4L, sink.status().written)
        assertEquals(0L, sink.status().rejected)
        assertEquals(FieldLogHealth.OK, sink.status().health)
    }

    @Test fun advertisedNameFilterOnlyPersistsBooleansAndDropsIdentifierMaterial() {
        val file = newFile()
        val sink = startSink(file)
        val identifiers = listOf(
            "S0123456789abcdefC",
            "0123456789abcdef0123456789abcdef01234567",
            "SYNTHETIC-VIN-MATERIAL",
            "02:00:00:00:00:01",
            "SYNTHETIC-NEW-STRING",
        )
        val invalid: List<Any?> = identifiers + listOf("BLE_FIELD_RUNNING", 1, 1L, null)
        for (value in listOf<Any?>(false, true) + invalid) {
            assertTrue(
                sink.enqueue(
                    record(
                        "BLE_FIELD_RUNNING",
                        mapOf(
                            FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER to value,
                            "advertisedName" to identifiers[0],
                            "hash" to identifiers[1],
                            "vin" to identifiers[2],
                            "address" to identifiers[3],
                            "peerKey" to identifiers[4],
                        ),
                    ),
                ),
            )
        }
        assertTrue(sink.sync())
        val text = file.readText()
        val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(2 + invalid.size, lines.size)
        val key = "\"${FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER}\":"
        assertTrue(lines[0].contains("${key}false"))
        assertTrue(lines[1].contains("${key}true"))
        for (line in lines.drop(2)) assertTrue(line.contains("${key}null"))
        for (identifier in identifiers) assertFalse(text.contains(identifier))
        for (unknownKey in listOf("advertisedName", "hash", "vin", "address", "peerKey")) {
            assertFalse(text.contains("\"$unknownKey\":"))
        }
    }

    @Test fun advertisedNameOptionDoesNotPersistAcrossRowsOrReopenAndLegacyRowsStayIntact() {
        val file = newFile()
        val legacy = (1..6).joinToString("") { version ->
            FieldJson.encode(record("BLE_FIELD_START_REQUESTED").copy(version = version)) + "\n"
        }
        writeExisting(file, legacy)
        val first = startSink(file)
        assertTrue(
            first.enqueue(
                record("BLE_FIELD_START_REQUESTED", mapOf(FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER to true)),
            ),
        )
        assertTrue(first.enqueue(record("BLE_FIELD_STOPPED")))
        assertTrue(first.sync())
        val second = startSink(file)
        assertTrue(
            second.enqueue(
                record("BLE_FIELD_START_REQUESTED", mapOf(FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER to false)),
            ),
        )
        assertTrue(second.enqueue(record("BLE_FIELD_STOPPED")))
        assertTrue(second.sync())
        val text = file.readText()
        assertEquals(legacy, text.substring(0, legacy.length))
        val current = text.substring(legacy.length).lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(4, current.size)
        val key = "\"${FieldStateKeys.BLE_FIELD_ADVERTISED_NAME_FILTER}\":"
        assertTrue(current[0].contains("${key}true"))
        assertFalse(current[1].contains(key))
        assertTrue(current[2].contains("${key}false"))
        assertFalse(current[3].contains(key))
    }

    @Test fun scanEvidenceKeepsUnknownContextNullAndClampsNegativeTimingAndRetryValues() {
        val file = newFile()
        val sink = startSink(file)
        val delayed = BleEventEvidence(BleEvidenceKind.SCAN_TIMER_DELAYED,
            scheduledElapsedMs = -1, timerDelayMs = Long.MIN_VALUE, retryCount = -1)
        val recovery = BleEventEvidence(BleEvidenceKind.SCAN_RECOVERY_SCHEDULED,
            scanFailureCode = 5, scheduledElapsedMs = 240_001, retryCount = 2,
            interactive = false, deviceLocked = true)
        assertTrue(sink.enqueue(record("BLE_EVIDENCE").copy(bleEvidence = delayed)))
        assertTrue(sink.enqueue(record("BLE_EVIDENCE").copy(bleEvidence = recovery)))
        assertTrue(sink.enqueue(record("BLE_EVIDENCE")))
        assertTrue(sink.sync())
        val rows = file.readText().lineSequence().filter { it.isNotEmpty() }.toList()
        assertTrue(rows[0].contains("\"scheduledElapsedMs\":0"))
        assertTrue(rows[0].contains("\"timerDelayMs\":0"))
        assertTrue(rows[0].contains("\"retryCount\":0"))
        assertTrue(rows[0].contains("\"interactive\":null"))
        assertTrue(rows[0].contains("\"deviceLocked\":null"))
        assertTrue(rows[1].contains("\"scanFailureCode\":5"))
        assertTrue(rows[1].contains("\"scheduledElapsedMs\":240001"))
        assertTrue(rows[1].contains("\"retryCount\":2"))
        assertTrue(rows[1].contains("\"interactive\":false"))
        assertTrue(rows[1].contains("\"deviceLocked\":true"))
        assertTrue(rows[2].contains("\"bleEvidence\":null"))
    }

    @Test fun encodedRecordStaysOnOneLine() {
        val file = newFile()
        val sink = startSink(file)
        assertTrue(sink.enqueue(record("REAL_BLE_APPEARED").copy(trialId = "id\"with\\escape\nand newline")))
        assertTrue(sink.sync())

        val text = file.readText()
        assertEquals(1, text.count { it == '\n' })
        assertFalse(text.contains('\r'))
        assertTrue(text.contains("id\\\"with\\\\escape\\u000aand newline"))
        assertEquals(1, text.split('\n').dropLastWhile { it.isEmpty() }.size)
    }

    @Test fun statusCallbackObservesConfirmedRecord() {
        val sink = startSink(newFile())
        var lastSeen: FieldLogStatus? = null
        sink.onStatusChanged = { lastSeen = sink.status() }

        assertTrue(sink.enqueue(record("PROCESS_START_OFF")))
        assertTrue(sink.sync())

        val observed = lastSeen
        assertNotNull(observed)
        assertEquals(1L, observed!!.written)
        assertEquals(0, observed.pending)
    }

    @Test
    fun trialSummariesStayOnTheirOwnRowsAcrossReopen() {
        val file = newFile()
        val first = startSink(file)
        assertTrue(first.enqueue(record("SPEECH_TRIAL_START_TTS_PCM")))
        assertTrue(
            first.enqueue(
                record(
                    "SPEECH_TRIAL_FINAL_RECEIVED_TTS_PCM",
                    speechTrial = summary(
                        mode = "TTS_PCM",
                        status = "COMPLETE",
                        reason = "FINAL_RECEIVED",
                        finalReceived = true,
                        phraseMatched = true,
                        confidence = 0.5f,
                        elapsedMs = 1_200L,
                        samples = 48_000L,
                        pcmBytesWritten = 96_000,
                        audioLeaseRetained = false,
                        decision = SpeechCommandDecision.FRUNK_OPEN_CANDIDATE,
                    ),
                ),
            ),
        )
        assertTrue(first.sync())

        // 프로세스 재시작: 앞선 행을 고치지 않고 이어 쓰며, 두 번째 회차는 자기 값만 남긴다.
        val second = startSink(file)
        assertTrue(second.enqueue(record("SPEECH_TRIAL_START_SILENT_PCM")))
        assertTrue(
            second.enqueue(
                record(
                    "SPEECH_TRIAL_RECOGNITION_TIMEOUT_SILENT_PCM",
                    speechTrial = summary(
                        mode = "SILENT_PCM",
                        status = "TIMED_OUT",
                        reason = "RECOGNITION_TIMEOUT",
                        finalReceived = false,
                        phraseMatched = null,
                        confidence = null,
                        elapsedMs = 10_000L,
                        samples = 0L,
                        pcmBytesWritten = 0,
                        audioLeaseRetained = true,
                    ),
                ),
            ),
        )
        assertTrue(second.sync())

        val lines = file.readText().split('\n').dropLastWhile { it.isEmpty() }
        assertEquals(4, lines.size)
        // 시작 행에는 요약이 붙지 않는다.
        assertEquals("null", trialValue(lines[0]))
        assertEquals("null", trialValue(lines[2]))

        val firstTrial = trialValue(lines[1])
        assertTrue(firstTrial.contains("\"mode\":\"TTS_PCM\""))
        assertTrue(firstTrial.contains("\"status\":\"COMPLETE\""))
        assertTrue(firstTrial.contains("\"reason\":\"FINAL_RECEIVED\""))
        assertTrue(firstTrial.contains("\"finalReceived\":true"))
        assertTrue(firstTrial.contains("\"phraseMatched\":true"))
        assertTrue(firstTrial.contains("\"confidence\":0.5"))
        assertTrue(firstTrial.contains("\"elapsedMs\":1200"))
        assertTrue(firstTrial.contains("\"samples\":48000"))
        assertTrue(firstTrial.contains("\"pcmBytesWritten\":96000"))
        assertTrue(firstTrial.contains("\"audioLeaseRetained\":false"))
        assertTrue(firstTrial.contains("\"commandDecision\":\"FRUNK_OPEN_CANDIDATE\""))
        // 회차 추적: 요약의 모드·사유가 그 행의 이벤트 code와 같은 값이어야 한다.
        assertTrue(lines[1].contains("\"event\":\"SPEECH_TRIAL_FINAL_RECEIVED_TTS_PCM\""))

        val secondTrial = trialValue(lines[3])
        assertTrue(secondTrial.contains("\"mode\":\"SILENT_PCM\""))
        assertTrue(secondTrial.contains("\"status\":\"TIMED_OUT\""))
        assertTrue(secondTrial.contains("\"reason\":\"RECOGNITION_TIMEOUT\""))
        assertTrue(secondTrial.contains("\"finalReceived\":false"))
        assertTrue(secondTrial.contains("\"phraseMatched\":null"))
        assertTrue(secondTrial.contains("\"confidence\":null"))
        assertTrue(secondTrial.contains("\"elapsedMs\":10000"))
        assertTrue(secondTrial.contains("\"samples\":0"))
        assertTrue(secondTrial.contains("\"pcmBytesWritten\":0"))
        assertTrue(secondTrial.contains("\"audioLeaseRetained\":true"))
        // 실패·취소·시간 초과 회차에는 판정이 없고, 앞 회차 성공 판정이 이어지지 않는다.
        assertTrue(secondTrial.contains("\"commandDecision\":null"))
        assertFalse(secondTrial.contains("FRUNK_OPEN_CANDIDATE"))

        // 회차 값이 서로 섞이지 않는다.
        assertFalse(firstTrial.contains("RECOGNITION_TIMEOUT"))
        assertFalse(firstTrial.contains("10000"))
        assertFalse(secondTrial.contains("FINAL_RECEIVED"))
        assertFalse(secondTrial.contains("48000"))
    }

    @Test
    fun trialConfidenceOutsideUnitRangeBecomesNull() {
        val file = newFile()
        val sink = startSink(file)
        // 유효 경계 0.0·1.0은 그대로 남기고 범위 밖·비유한 점수만 버린다.
        val valid = listOf(0f, 1f)
        val invalid = listOf(-0.1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)
        for (confidence in valid + invalid) {
            assertTrue(
                sink.enqueue(
                    record("SPEECH_TRIAL_FINAL_RECEIVED_TTS_PCM", speechTrial = summary(confidence = confidence)),
                ),
            )
        }
        assertTrue(sink.sync())

        val lines = file.readText().split('\n').dropLastWhile { it.isEmpty() }
        assertEquals(valid.size + invalid.size, lines.size)
        assertEquals("0.0", confidenceOf(lines[0]))
        assertEquals("1.0", confidenceOf(lines[1]))
        for (index in valid.size until lines.size) {
            assertEquals("null", confidenceOf(lines[index]))
            // 점수만 버리고 그 회차 행과 나머지 수치는 남는다.
            assertTrue(trialValue(lines[index]).contains("\"samples\":12000"))
        }
        val text = file.readText()
        assertFalse(text.contains("NaN"))
        assertFalse(text.contains("Infinity"))
        assertFalse(text.contains("\"confidence\":2"))
    }

    @Test
    fun trialSummaryStringsOutsideAllowlistBecomeNullAndRowStays() {
        val file = newFile()
        val sink = startSink(file)
        assertTrue(
            sink.enqueue(
                record(
                    "SPEECH_TRIAL_INTERNAL_FAILURE_DIRECT_MIC",
                    speechTrial = summary(
                        mode = "VIN_1HGCM82633A004352",
                        status = "enabled",
                        reason = "RAW_TEXT_1HGCM82633A004352",
                        phraseMatched = null,
                        confidence = null,
                        elapsedMs = 0L,
                        samples = 0L,
                        pcmBytesWritten = 0,
                    ),
                ),
            ),
        )
        assertTrue(sink.sync())

        val text = file.readText()
        val trial = trialValue(text.split('\n').first { it.isNotEmpty() })
        assertTrue(trial.contains("\"mode\":null"))
        assertTrue(trial.contains("\"status\":null"))
        assertTrue(trial.contains("\"reason\":null"))
        // 모르는 값은 성공값으로 채우지 않는다.
        assertTrue(trial.contains("\"confidence\":null"))
        assertTrue(trial.contains("\"phraseMatched\":null"))
        assertTrue(trial.contains("\"elapsedMs\":0"))
        // allowlist 밖 원문은 값으로도 남지 않는다.
        assertFalse(text.contains("1HGCM82633A004352"))
        assertFalse(text.contains("RAW_TEXT"))
        assertFalse(text.contains("enabled"))
    }

    @Test fun approachSnapshotsRotateWithinExistingBudgetWithoutLeakingIntoNextEvent() {
        val file = newFile()
        val legacy = "{\"version\":8,\"event\":\"PROCESS_START_OFF\",\"state\":{}}\n"
        writeExisting(file, legacy)
        val events = listOf(
            "TESLA_APPROACH_READ_STARTED", "TESLA_APPROACH_READ_STAGE",
            "TESLA_APPROACH_BODY_VERIFIED", "TESLA_APPROACH_DRIVE_VERIFIED",
            "TESLA_APPROACH_READ_FINISHED", "TESLA_APPROACH_READ_BLOCKED",
            "TESLA_APPROACH_READ_CLEANUP_FAILED",
        )
        val expected = (0 until 40).map { index ->
            record(events[index % events.size], state = mapOf(FieldStateKeys.BLE_FIELD_AUTHENTICATED_READ to true)).copy(
                wallTimeMs = 1_700_000_000_000L + index,
                teslaApproachRead = TeslaApproachReadSummary(
                    candidate = index + 1L, attempt = index + 1L,
                    stage = "FAILED", query = "DRIVE_STATE", outcome = "PARTIAL",
                    reason = "DRIVE_READ_FAILED", bodyVerified = true,
                    bodyReceivedElapsedMs = 100L, localClosed = true,
                ),
            )
        }
        // 스키마 확장에도 한 행은 파일에 들어가고 여러 번 회전하도록 용량 경계를 정한다.
        val limitBytes = expected.maxOf { (FieldJson.encode(it) + "\n").toByteArray(Charsets.UTF_8).size }.toLong() * 8
        val sink = startSink(file, limitBytes = limitBytes)
        assertTrue(sink.enqueue(expected.first()))
        assertTrue(sink.sync())
        assertTrue(file.readText().startsWith(legacy))
        for (row in expected.drop(1)) {
            assertTrue(sink.enqueue(row))
            assertTrue(sink.sync())
            assertBoundedRetention(file, sink, limitBytes)
        }
        val retained = retainedRows(file)
        assertEquals(expected.takeLast(retained.size).map { it.wallTimeMs }, retained.map(::wallTimeOf))
        assertEquals(expected.takeLast(retained.size).map { it.event }, retained.map(::eventOf))
        assertTrue(retained.all { it.contains("\"teslaApproachRead\":{") })
        assertTrue(retained.all { it.contains("\"bleFieldAuthenticatedRead\":true") })
        assertEquals(0L, sink.status().rejected)
        assertEquals(0L, sink.status().dropped)
        assertTrue(sink.enqueue(record("BLE_FIELD_WAITING")))
        assertTrue(sink.sync())
        val next = retainedRows(file).last()
        assertTrue(next.contains("\"teslaApproachRead\":null"))
        assertFalse(next.contains("\"bodyVerified\""))
        assertFalse(next.contains("\"driveSourceWallMs\""))
    }

    @Test fun authenticatedReadOptionCannotCarryIdentifiersOrIntegerTruthiness() {
        val file = newFile()
        val sink = startSink(file)
        val key = FieldStateKeys.BLE_FIELD_AUTHENTICATED_READ
        val secret = "VIN-MAC-KEY-raw"
        for (value in listOf(true, false, null, secret, "BLE_FIELD_RUNNING", 1, 0, 1L)) {
            assertTrue(sink.enqueue(record("BLE_FIELD_RUNNING", state = mapOf(key to value))))
        }
        assertFalse(sink.enqueue(record("TESLA_APPROACH_READ_FINISHED_$secret")))
        assertTrue(sink.sync())
        val rows = file.readLines()
        assertTrue(rows[0].contains("\"$key\":true"))
        assertTrue(rows[1].contains("\"$key\":false"))
        assertTrue(rows.drop(2).all { it.contains("\"$key\":null") })
        assertFalse(file.readText().contains(secret))
        assertEquals(1L, sink.status().rejected)
        assertEquals(FieldLogHealth.OK, sink.status().health)
    }

    private fun archive(file: File, index: Int): File = File(file.parentFile, "events-v2.$index.jsonl")

    /** 가장 오래된 archive부터 active까지 소비자가 회수하는 순서다. */
    private fun retainedFiles(file: File): List<File> = (3 downTo 1).map { archive(file, it) } + file

    private fun retainedRows(file: File): List<String> =
        retainedFiles(file).filter { it.isFile }.flatMap { it.readLines() }

    private fun wallTimeOf(row: String): Long =
        row.substringAfter("\"wallMs\":").substringBefore(',').toLong()

    private fun eventOf(row: String): String =
        row.substringAfter("\"event\":\"").substringBefore('"')

    private fun assertBoundedRetention(file: File, sink: FieldEventSink, budget: Long) {
        val retained = retainedFiles(file)
        for (part in retained) assertTrue(part.length() <= budget / 4L)
        val total = retained.sumOf { it.length() }
        assertTrue(total <= budget)
        assertEquals(total, sink.status().fileBytes)
        assertEquals(FieldLogHealth.OK, sink.status().health)
        assertEquals(0, sink.status().pending)
    }

    private fun newDir(): File = Files.createTempDirectory("field-log-test").toFile()

    private fun newFile(): File = File(newDir(), FIELD_LOG_RELATIVE_PATH)

    /** 이전 실행이 남긴 로그를 흉내 낸다. sink와 달리 부모 디렉터리를 테스트가 직접 만든다. */
    private fun writeExisting(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun startSink(file: File, limitBytes: Long = FIELD_LOG_LIMIT_BYTES): FieldEventSink {
        val sink = FieldEventSink(file, limitBytes)
        sink.start()
        return sink
    }

    private fun bodyState(
        bytes: Any? = 2,
        status: Any? = "COMPLETE",
        fields: Any? = "1/0/1",
    ): Map<String, Any?> = mapOf(
        FieldStateKeys.UWB_RX_BODY_BYTES to bytes,
        FieldStateKeys.UWB_RX_BODY_STATUS to status,
        FieldStateKeys.UWB_RX_BODY_FIELDS to fields,
    )

    private fun assertBodySummaryNull(row: String) {
        for (key in FieldStateKeys.UWB_RX_BODY_KEYS) assertTrue(row.contains("\"$key\":null"))
    }

    private fun record(
        event: String,
        state: Map<String, Any?> = emptyMap(),
        speechTrial: SpeechTrialSummary? = null,
    ) = FieldRecord(
        processId = "11111111-2222-3333-4444-555555555555",
        trialId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        wallTimeMs = 1_700_000_000_000L,
        elapsedRealtimeMs = 4_321L,
        appVersion = "0.1.0-test",
        event = event,
        state = state,
        speechTrial = speechTrial,
    )

    private fun summary(
        mode: String = "TTS_PCM",
        status: String = "COMPLETE",
        reason: String = "FINAL_RECEIVED",
        finalReceived: Boolean = true,
        phraseMatched: Boolean? = true,
        confidence: Float? = 0.5f,
        elapsedMs: Long? = 1_000L,
        samples: Long = 12_000L,
        pcmBytesWritten: Int = 24_000,
        audioLeaseRetained: Boolean = false,
        decision: SpeechCommandDecision? = null,
    ) = SpeechTrialSummary(
        mode = mode,
        status = status,
        reason = reason,
        finalReceived = finalReceived,
        phraseMatched = phraseMatched,
        confidence = confidence,
        elapsedMs = elapsedMs,
        samples = samples,
        pcmBytesWritten = pcmBytesWritten,
        audioLeaseRetained = audioLeaseRetained,
        decision = decision,
    )

    /** 한 행의 `speechTrial` 값만 뽑는다. 키 순서가 바뀌어도 값만 보도록 중괄호 짝으로 자른다. */
    private fun trialValue(line: String): String {
        val key = "\"speechTrial\":"
        val start = line.indexOf(key)
        assertTrue("speechTrial 키가 없다: $line", start >= 0)
        val value = line.substring(start + key.length)
        if (value.startsWith("null")) return "null"
        var depth = 0
        for ((index, ch) in value.withIndex()) {
            when (ch) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return value.substring(0, index + 1)
                }
            }
        }
        fail("speechTrial 객체가 닫히지 않았다: $line")
        return ""
    }

    /** 한 행의 `confidence` 값만 뽑는다. */
    private fun confidenceOf(line: String): String {
        val key = "\"confidence\":"
        val start = line.indexOf(key)
        assertTrue("confidence 키가 없다: $line", start >= 0)
        return line.substring(start + key.length).substringBefore(',')
    }
}
