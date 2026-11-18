package com.heytesla.app.speech.product

import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.content.SharedPreferences
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heytesla.app.DiagnosticApp
import com.heytesla.app.DiagnosticRuntime
import com.heytesla.app.ObservationService
import com.heytesla.app.ProductMicrophoneService
import com.heytesla.app.logging.FieldEventSink
import com.heytesla.app.ui.productMicrophoneNotificationText
import com.heytesla.app.ui.vehicleServiceNotificationText
import com.heytesla.app.speech.KoreanSpeechCleanup
import com.heytesla.app.speech.KoreanSpeechStreamResult
import com.heytesla.app.vehicle.connection.TeslaServiceConnectionRequest
import com.heytesla.app.vehicle.frunk.FrunkVoicePolicy
import com.heytesla.app.vehicle.key.TeslaBleKeyStore
import com.heytesla.app.vehicle.key.VehicleVinStore
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 독립 runtime의 실제 철회/완료 경계만 실행한다. fixture는 획득된 RAM lease를 구성하며
 * Service lifecycle·FGS·마이크·STT·GATT·권한·생산 키/VIN/설정은 실행하거나 변경하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class ProductVoiceRuntimeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DiagnosticApp

    @Test fun consentRevocationSealsAudioBeforeFailedOrThrowingPreferenceCommitAndRejectsLateCommand() {
        instrumentation.runOnMainSync {
            assertFalse("명시 동의 철회 경계를 실행하려면 테스트 에뮬레이터가 잠금 해제되어 있어야 합니다.",
                requireNotNull(app.getSystemService(KeyguardManager::class.java)).isDeviceLocked)
            for (throws in listOf(false, true)) {
                val fixture = reservedRuntime(backendPresent = true)
                val runtime = fixture.runtime
                assertTrue(runtime.state.value.productVoiceConsent)
                assertFalse(runtime.state.value.productVoiceConsentSaveFailed)
                assertFalse(fixture.sealed())
                assertNull(fixture.serviceStopReason())

                var commitAttempts = 0
                var failing = true
                val markerField = runtimeField("fieldMarker")
                val original = markerField.get(runtime) as SharedPreferences
                markerField.set(runtime, preferencesWithCommit(original) {
                    commitAttempts++
                    // 저장 실패를 알기 전부터 RAM 동의와 진행 중 음성의 전송 허가는 철회돼야 한다.
                    assertFalse(runtime.state.value.productVoiceConsent)
                    if (failing) {
                        assertSame(fixture.lease, runtimeField("productVoiceLease").get(runtime))
                        assertTrue(fixture.sealed())
                        assertNotNull(fixture.serviceStopReason())
                        assertEquals("LEASE_LOST", runtime.productVoiceAllowed(fixture.id))
                        if (throws) throw IllegalStateException("Injected preference commit failure")
                        false
                    } else true
                })

                runtime.setProductVoiceConsent(false)
                assertEquals(1, commitAttempts)
                assertFalse(runtime.state.value.productVoiceConsent)
                assertTrue(runtime.state.value.productVoiceConsentSaveFailed)
                assertSame(fixture.lease, runtimeField("productVoiceLease").get(runtime))
                assertTrue(fixture.sealed())
                assertTrue(runtime.serviceConnectionInFlight(fixture.request.owner,
                    fixture.request.fieldToken, fixture.request.attempt))
                assertEquals("LEASE_LOST", runtime.productVoiceAllowed(fixture.id))

                // 철회 이후 늦게 도착한 유효 최종 명령도 정상 오디오 정리를 권한 회복으로 삼을 수 없다.
                val lateGate = acceptedGate(runtime)
                runtime.productVoiceFinished(fixture.id, fixture.service,
                    ProductVoiceCompletion(lateGate, microphoneReleased = true, cleanupConfirmed = true, reason = null))
                assertEquals(FrunkVoicePolicy.Phase.STOPPED, lateGate.phase)
                assertFalse(lateGate.takeForDispatch(runtime.now(), inputReleased = true, cleanupConfirmed = true))
                assertNull(runtimeField("productVoiceLease").get(runtime))
                assertFalse(runtime.state.value.productVoiceConsent)
                // 비동기 종료가 음성 reason을 바꾸더라도 저장 실패 안내는 남아야 한다.
                assertTrue(runtime.state.value.productVoiceConsentSaveFailed)
                assertNoVehicleDispatch(runtime)

                // 다음 명시적 저장이 실제 성공했다고 보고될 때만 저장 실패 latch가 해제된다.
                failing = false
                runtime.setProductVoiceConsent(false)
                assertEquals(2, commitAttempts)
                assertFalse(runtime.state.value.productVoiceConsent)
                assertFalse(runtime.state.value.productVoiceConsentSaveFailed)
                assertNoVehicleDispatch(runtime)
            }
        }
    }

    @Test fun backendAlreadyClosedStillOwnsExactAudioTupleUntilCleanupAndUnknownCleanupRetainsReservation() {
        instrumentation.runOnMainSync {
            val fixture = reservedRuntime(backendPresent = false)
            val runtime = fixture.runtime
            val request = fixture.request
            assertNull(runtimeField("teslaServiceConnectionRequest").get(runtime))
            assertTrue(runtime.serviceConnectionInFlight(request.owner, request.fieldToken, request.attempt))

            for ((owner, token, attempt) in listOf(
                Triple(ObservationService(), request.fieldToken, request.attempt),
                Triple(request.owner, "${request.fieldToken}-other", request.attempt),
                Triple(request.owner, request.fieldToken, request.attempt + 1),
            )) {
                assertFalse(runtime.serviceConnectionInFlight(owner, token, attempt))
                runtime.cancelServiceConnection(owner, token, attempt, "PRODUCT_VOICE_OWNER_LOST")
                assertFalse(fixture.sealed())
                assertNull(fixture.serviceStopReason())
                assertSame(fixture.lease, runtimeField("productVoiceLease").get(runtime))
            }

            runtime.cancelServiceConnection(request.owner, request.fieldToken, request.attempt,
                "PRODUCT_VOICE_OWNER_LOST")
            assertTrue(fixture.sealed())
            assertNotNull(fixture.serviceStopReason())
            assertSame(fixture.lease, runtimeField("productVoiceLease").get(runtime))
            assertTrue(runtime.serviceConnectionInFlight(request.owner, request.fieldToken, request.attempt))
            assertEquals("LEASE_LOST", runtime.productVoiceAllowed(fixture.id))
            assertFalse(runtime.diagnosticCleanupUncertain)

            val lateGate = acceptedGate(runtime)
            runtime.productVoiceFinished(fixture.id, fixture.service,
                ProductVoiceCompletion(lateGate, microphoneReleased = true, cleanupConfirmed = false,
                    reason = ProductVoiceSession.CLEANUP_FAILED))
            assertEquals(FrunkVoicePolicy.Phase.STOPPED, lateGate.phase)
            assertFalse(lateGate.takeForDispatch(runtime.now(), inputReleased = true, cleanupConfirmed = true))
            assertRetainedAfterCleanupFailure(fixture)

            // UI 재진입·늦은 LISTENING 통지·중복 취소는 불명 자원과 owner 예약을 지우지 못한다.
            runtime.activityVisible = false
            runtime.activityVisible = true
            runtime.productVoiceChanged(fixture.id, ProductVoiceState(ProductVoiceStage.LISTENING))
            runtime.cancelServiceConnection(request.owner, request.fieldToken, request.attempt,
                "PRODUCT_VOICE_NOTIFICATION_STOP")
            assertRetainedAfterCleanupFailure(fixture)
            assertNull(runtime.acquireSpeechDiagnostic {})
            assertNull(runtime.acquireTeslaKey())
            assertNoVehicleDispatch(runtime)
        }
    }

    @Test fun legacyFalsePreferenceStillAutomaticallySavesAndForeignOwnerCannotEmitText() {
        lateinit var fixture: Fixture
        val text = "SYNTHETIC_PRIVATE_AUTOMATIC"
        instrumentation.runOnMainSync {
            fixture = reservedRuntime(backendPresent = true, transcriptReadiness = { null })
            val runtime = fixture.runtime
            val preferences = runtimeField("fieldMarker").get(runtime) as SharedPreferences
            assertFalse(preferences.getBoolean("product_transcript_consent", true))
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic(), text)
            assertEquals(text, runtime.state.value.productVoiceResults.single().text)
            val events = runtime.state.value.events
            runtime.productVoiceDiagnosed(fixture.id + 1, fixture.service, transcriptDiagnostic(), text)
            runtime.productVoiceDiagnosed(fixture.id, ProductMicrophoneService(), transcriptDiagnostic(), text)
            assertEquals(events, runtime.state.value.events)
            assertEquals(1, runtime.state.value.productVoiceResults.size)
            assertEquals(0, runtime.state.value.productTranscriptSavedCount)
            assertFalse(preferences.getBoolean("product_transcript_consent", true))
        }
        assertEquals(1, drainTranscripts(fixture).size)
        assertEquals(1, fixture.runtime.state.value.productTranscriptSavedCount)
        assertEquals(1, diagnosticRows(fixture).size)
        assertNoRawInGeneralStateOrLog(fixture, text)
    }

    @Test fun completedTextAndFailureSummaryUseExactFieldRecordAndLeaseUtteranceWithoutDuplicatingMetrics() {
        lateinit var fixture: Fixture
        val text = "SYNTHETIC_PRIVATE_문장_\"줄바꿈\"\n다음"
        val acceptedText = "SYNTHETIC_PRIVATE_ACCEPTED"
        instrumentation.runOnMainSync {
            fixture = reservedRuntime(backendPresent = true, transcriptReadiness = { null })
            val runtime = fixture.runtime
            runtimeField("trialId").set(runtime, "synthetic-transcript-trial")
            runtime.productVoiceDiagnosed(fixture.id + 1, fixture.service, transcriptDiagnostic(), text)
            runtime.productVoiceDiagnosed(fixture.id, ProductMicrophoneService(), transcriptDiagnostic(), text)
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, ProductVoiceDiagnostic("PREPARED"), text)
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, ProductVoiceDiagnostic("CLOSED",
                microphoneReleased = true, streamCleanupConfirmed = true, recognizerCleanupConfirmed = true), text)
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic(captureMs = 11), text)
            runtime.productVoiceDiagnosed(fixture.id, fixture.service,
                transcriptDiagnostic("FAILURE", captureMs = 22), "SYNTHETIC_PRIVATE_FAILURE",
                "PRODUCT_VOICE_UTTERANCE_TRUNCATED")
            assertNull(runtime.state.value.productVoiceResults.first().text)
            assertEquals("PRODUCT_VOICE_UTTERANCE_TRUNCATED", runtime.state.value.productVoiceResults.first().reason)
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic("EMPTY", 33), "")
            runtime.productVoiceDiagnosed(fixture.id, fixture.service,
                transcriptDiagnostic("ACCEPTED", 44), acceptedText)
            assertEquals(0, runtime.state.value.productTranscriptSavedCount)
            assertFalse(fixture.transcriptRoot.exists())
            assertFalse(fixture.sealed())
            assertNull(fixture.serviceStopReason())
            assertNoVehicleDispatch(runtime)
        }
        val transcripts = drainTranscripts(fixture).sortedBy { it.getLong("utterance") }
        assertEquals(listOf(1L, 2L, 3L, 4L), transcripts.map { it.getLong("utterance") })
        assertEquals(text, transcripts[0].getString("text"))
        assertTrue(transcripts[1].isNull("text"))
        assertEquals("PRODUCT_VOICE_UTTERANCE_TRUNCATED", transcripts[1].getString("reason"))
        assertEquals("", transcripts[2].getString("text"))
        assertEquals(acceptedText, transcripts[3].getString("text"))
        val rows = diagnosticRows(fixture)
        assertEquals(6, rows.size)
        for (transcript in transcripts) {
            val diagnostic = transcript.getJSONObject("diagnostic")
            val field = rows.single {
                it.getJSONObject("productVoiceDiagnostic").optLong("captureMs", -1) == diagnostic.getLong("captureMs")
            }
            assertEquals(field.getString("processId"), transcript.getString("processId"))
            assertEquals(field.get("trialId"), transcript.get("trialId"))
            assertEquals(field.getLong("wallMs"), transcript.getLong("wallTimeMs"))
            assertEquals(field.getLong("elapsedMs"), transcript.getLong("elapsedRealtimeMs"))
            assertEquals(field.getString("appVersion"), transcript.getString("appVersion"))
            assertEquals(fixture.id, transcript.getLong("voiceLeaseId"))
            assertEquals(field.getJSONObject("productVoiceDiagnostic").toString(), diagnostic.toString())
            assertFalse(field.has("text"))
        }
        assertEquals(4, fixture.runtime.state.value.productTranscriptSavedCount)
        assertNull(fixture.runtime.state.value.productTranscriptWriteFailure)
        assertNoRawInGeneralStateOrLog(fixture, text)
        assertNoRawInGeneralStateOrLog(fixture, acceptedText)
        assertNoRawInGeneralStateOrLog(fixture, "SYNTHETIC_PRIVATE_FAILURE")
    }

    @Test fun staleParkReadinessCancellationAndLateResultsNeverPersistRawTextButKeepFailureSummary() {
        lateinit var fixture: Fixture
        val text = "SYNTHETIC_PRIVATE_REJECTED"
        var readiness: String? = null
        var throwReadiness = false
        instrumentation.runOnMainSync {
            fixture = reservedRuntime(backendPresent = true, transcriptReadiness = {
                if (throwReadiness) throw IllegalStateException(text)
                readiness
            })
            val runtime = fixture.runtime
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic(),
                "SYNTHETIC_PRIVATE_PREVIOUS")
            readiness = "CONNECTION_GEAR_NOT_P"
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic(), text)
            assertNull(runtime.state.value.productVoiceResults.first().text)
            assertEquals("SEALED", runtime.state.value.productVoiceResults.first().diagnostic.outcome)
            assertEquals("CONNECTION_GEAR_NOT_P", runtime.state.value.productVoiceResults.first().reason)
            readiness = "BLE_FIELD_OWNER_LOST"
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic(), text)
            readiness = null
            runtimeField("teslaServiceConnectionRequest").set(runtime, null)
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic(), text)
            runtimeField("teslaServiceConnectionRequest").set(runtime, fixture.request)
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic(), null)
            runtime.productVoiceDiagnosed(fixture.id, fixture.service,
                transcriptDiagnostic().copy(streamCleanupConfirmed = false), text)
            runtime.productVoiceDiagnosed(fixture.id, fixture.service,
                transcriptDiagnostic("ACCEPTED").copy(recognizerCleanupConfirmed = false), text)
            throwReadiness = true
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic(), text)
            throwReadiness = false
            runtime.cancelServiceConnection(fixture.request.owner, fixture.request.fieldToken,
                fixture.request.attempt, "PRODUCT_VOICE_NOTIFICATION_STOP")
            assertTrue(fixture.sealed())
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic(), text)
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic("SEALED"), text,
                "PRODUCT_VOICE_NOTIFICATION_STOP")
            // close 자체에는 발화 수치가 없어 이전 발화를 두 번째 저장하거나 원문을 붙이지 않는다.
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, ProductVoiceDiagnostic("SEALED",
                microphoneReleased = true, streamCleanupConfirmed = true, recognizerCleanupConfirmed = true),
                text, "PRODUCT_VOICE_NOTIFICATION_STOP")
            assertEquals(5, runtime.state.value.productVoiceResults.size)
            assertTrue(runtime.state.value.productVoiceResults.all { it.text == null })
            val events = runtime.state.value.events
            runtimeField("productVoiceLease").set(runtime, null)
            runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic(), text)
            assertEquals(events, runtime.state.value.events)
        }
        val transcripts = drainTranscripts(fixture).sortedBy { it.getLong("utterance") }
        assertEquals(10, transcripts.size)
        assertEquals("SYNTHETIC_PRIVATE_PREVIOUS", transcripts.first().getString("text"))
        val sealed = transcripts.drop(1)
        assertTrue(sealed.all { it.isNull("text") && it.getJSONObject("diagnostic").getString("outcome") == "SEALED" })
        assertEquals(listOf(
            "CONNECTION_GEAR_NOT_P", "BLE_FIELD_OWNER_LOST", "LEASE_LOST", "PRODUCT_VOICE_DECODE_FAILED",
            "PRODUCT_VOICE_CLEANUP_FAILED_RESTART_REQUIRED", "PRODUCT_VOICE_CLEANUP_FAILED_RESTART_REQUIRED",
            "PRODUCT_VOICE_READINESS_UNKNOWN", "PRODUCT_VOICE_NOTIFICATION_STOP", "PRODUCT_VOICE_NOTIFICATION_STOP",
        ), sealed.map { it.getString("reason") })
        val rows = diagnosticRows(fixture)
        assertEquals(11, rows.size)
        assertEquals(9, rows.count { it.getJSONObject("productVoiceDiagnostic").getString("outcome") == "SEALED" &&
            !it.getJSONObject("productVoiceDiagnostic").isNull("utteranceBytes") })
        assertEquals(10, fixture.runtime.state.value.productTranscriptSavedCount)
        assertNull(fixture.runtime.state.value.productTranscriptWriteFailure)
        assertNoRawInGeneralStateOrLog(fixture, text)
        assertNoRawInGeneralStateOrLog(fixture, "SYNTHETIC_PRIVATE_PREVIOUS")
    }

    @Test fun restartRestoresFiveResultsAndTruncationSummaryWithoutReplayingAnyWork() {
        lateinit var fixture: Fixture
        instrumentation.runOnMainSync {
            fixture = reservedRuntime(true, transcriptReadiness = { null })
            repeat(6) { index ->
                fixture.runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic(),
                    "SYNTHETIC_PRIVATE_RESTART_$index")
            }
            fixture.runtime.productVoiceDiagnosed(fixture.id, fixture.service, transcriptDiagnostic("FAILURE"),
                null, "PRODUCT_VOICE_UTTERANCE_TRUNCATED")
            assertEquals(5, fixture.runtime.state.value.productVoiceResults.size)
            assertNull(fixture.runtime.state.value.productVoiceResults.first().text)
        }
        assertEquals(7, drainTranscripts(fixture).size)
        // 완료 파일들의 순서를 고정해 파일시각 동률에 의존하지 않는다. 기존 생산 파일은 건드리지 않는다.
        val files = fixture.transcriptRoot.listFiles().orEmpty().sortedBy { JSONObject(it.readText()).getLong("utterance") }
        files.forEachIndexed { index, file -> assertTrue(file.setLastModified(1_700_000_000_000L + index * 2_000L)) }
        val executor = QueuedExecutor()
        lateinit var restored: DiagnosticRuntime
        instrumentation.runOnMainSync {
            restored = runtimeFor(fixture.transcriptRoot, executor)
            assertTrue(restored.state.value.productVoiceResultsLoading)
            assertTrue(restored.state.value.productVoiceResults.isEmpty())
            assertNull(runtimeField("productVoiceLease").get(restored))
            assertNull(runtimeField("teslaServiceConnectionRequest").get(restored))
            assertFalse(restored.state.value.productVoiceConsent)
        }
        executor.runAll()
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val state = restored.state.value
            assertFalse(state.productVoiceResultsLoading)
            assertNull(state.productVoiceResultsReadFailure)
            assertEquals(listOf(7L, 6L, 5L, 4L, 3L), state.productVoiceResults.map { it.utterance })
            assertNull(state.productVoiceResults.first().text)
            assertEquals("PRODUCT_VOICE_UTTERANCE_TRUNCATED", state.productVoiceResults.first().reason)
            assertEquals(0, state.productTranscriptSavedCount)
            assertNull(state.productVoiceRecognitionOutcome)
            assertEquals(ProductVoiceStage.IDLE, state.productVoice.stage)
            assertFalse(state.enabled || state.observationServiceRunning || state.observationStartPending)
            assertNull(restored.microphone)
            assertNull(runtimeField("productVoiceLease").get(restored))
            assertNull(runtimeField("teslaServiceConnectionRequest").get(restored))
            assertNoVehicleDispatch(restored)
        }
        assertNoRawInGeneralStateOrLog(fixture, "SYNTHETIC_PRIVATE_RESTART_5")
    }

    @Test fun liveResultWinsDelayedStartupReadAndCurrentNotificationOutcomeResetsOnNextDecodeAndAccepted() {
        lateinit var old: Fixture
        instrumentation.runOnMainSync {
            old = reservedRuntime(true, transcriptReadiness = { null })
            old.runtime.productVoiceDiagnosed(old.id, old.service, transcriptDiagnostic(),
                "SYNTHETIC_PRIVATE_OLD")
        }
        drainTranscripts(old)
        lateinit var live: Fixture
        instrumentation.runOnMainSync {
            live = reservedRuntime(true, transcriptReadiness = { null }, transcriptRoot = old.transcriptRoot)
            val runtime = live.runtime
            runtime.productVoiceDiagnosed(live.id, live.service, transcriptDiagnostic(), "SYNTHETIC_PRIVATE_LIVE")
            assertTrue(runtime.state.value.productVoiceResultsLoading)
            assertEquals("GRAMMAR_NO_MATCH", runtime.state.value.productVoiceRecognitionOutcome)
            runtime.productVoiceChanged(live.id, ProductVoiceState(ProductVoiceStage.PREPARING))
            runtime.productVoiceChanged(live.id, ProductVoiceState(ProductVoiceStage.LISTENING))
            assertTrue(productMicrophoneNotificationText(runtime.state.value.productVoice,
                runtime.state.value.productVoiceRecognitionOutcome).contains("명령 불일치 · 음성 대기"))
            runtime.productVoiceChanged(live.id, ProductVoiceState(ProductVoiceStage.RECOGNIZING))
            assertNull(runtime.state.value.productVoiceRecognitionOutcome)
        }
        drainTranscripts(live)
        instrumentation.runOnMainSync {
            val runtime = live.runtime
            assertFalse(runtime.state.value.productVoiceResultsLoading)
            assertEquals(listOf("SYNTHETIC_PRIVATE_LIVE", "SYNTHETIC_PRIVATE_OLD"),
                runtime.state.value.productVoiceResults.map { it.text })
            runtime.productVoiceDiagnosed(live.id, live.service, transcriptDiagnostic("EMPTY"), "")
            runtime.productVoiceChanged(live.id, ProductVoiceState(ProductVoiceStage.LISTENING))
            assertTrue(productMicrophoneNotificationText(runtime.state.value.productVoice,
                runtime.state.value.productVoiceRecognitionOutcome).contains("음성 확인 안 됨 · 음성 대기"))
            runtime.productVoiceDiagnosed(live.id, live.service, transcriptDiagnostic("ACCEPTED"),
                "SYNTHETIC_PRIVATE_ACCEPTED")
            assertNull(runtime.state.value.productVoiceRecognitionOutcome)
            assertNoVehicleDispatch(runtime)
            assertFalse(vehicleServiceNotificationText(runtime.state.value).contains("SYNTHETIC_PRIVATE"))
        }
        drainTranscripts(live)
        assertNoRawInGeneralStateOrLog(live, "SYNTHETIC_PRIVATE_LIVE")
        assertNoRawInGeneralStateOrLog(live, "SYNTHETIC_PRIVATE_ACCEPTED")
    }

    @Test fun transcriptWriteFailureKeepsCurrentRamResultAndOnlyCompletedFilesIncreaseSavedCount() {
        lateinit var invalid: Fixture
        lateinit var full: Fixture
        lateinit var ioFailure: Fixture
        instrumentation.runOnMainSync {
            invalid = reservedRuntime(true, transcriptReadiness = { null })
            invalid.runtime.productVoiceDiagnosed(invalid.id, invalid.service, transcriptDiagnostic(), "\uD800")
            full = reservedRuntime(true, transcriptReadiness = { null })
            repeat(17) {
                full.runtime.productVoiceDiagnosed(full.id, full.service, transcriptDiagnostic(captureMs = it.toLong()),
                    "SYNTHETIC_PRIVATE_QUEUE_$it")
            }
            assertEquals("QUEUE_FULL", full.runtime.state.value.productTranscriptWriteFailure)
            assertEquals("SYNTHETIC_PRIVATE_QUEUE_16", full.runtime.state.value.productVoiceResults.first().text)
            assertEquals(5, full.runtime.state.value.productVoiceResults.size)
            assertEquals(0, full.runtime.state.value.productTranscriptSavedCount)
            ioFailure = reservedRuntime(true, transcriptReadiness = { null })
        }
        assertTrue(ioFailure.transcriptRoot.createNewFile())
        instrumentation.runOnMainSync {
            ioFailure.runtime.productVoiceDiagnosed(ioFailure.id, ioFailure.service, transcriptDiagnostic(),
                "SYNTHETIC_PRIVATE_IO_FAILURE")
        }
        assertTrue(drainTranscripts(invalid).isEmpty())
        assertEquals("INVALID_RECORD", invalid.runtime.state.value.productTranscriptWriteFailure)
        assertEquals(0, invalid.runtime.state.value.productTranscriptSavedCount)
        assertEquals(16, drainTranscripts(full).size)
        assertEquals(16, full.runtime.state.value.productTranscriptSavedCount)
        assertEquals("QUEUE_FULL", full.runtime.state.value.productTranscriptWriteFailure)
        assertEquals("SYNTHETIC_PRIVATE_QUEUE_16", full.runtime.state.value.productVoiceResults.first().text)
        assertTrue(drainTranscripts(ioFailure).isEmpty())
        assertEquals("WRITE_FAILED", ioFailure.runtime.state.value.productTranscriptWriteFailure)
        assertEquals("READ_FAILED", ioFailure.runtime.state.value.productVoiceResultsReadFailure)
        assertEquals("SYNTHETIC_PRIVATE_IO_FAILURE", ioFailure.runtime.state.value.productVoiceResults.first().text)
        assertEquals(0, ioFailure.runtime.state.value.productTranscriptSavedCount)
        assertTrue(ioFailure.transcriptRoot.isFile)
        instrumentation.runOnMainSync {
            assertFalse(ioFailure.sealed())
            assertNull(ioFailure.serviceStopReason())
            assertFalse(full.sealed())
            assertNull(full.serviceStopReason())
            assertNoVehicleDispatch(ioFailure.runtime)
            assertNoVehicleDispatch(full.runtime)
        }
        assertNoRawInGeneralStateOrLog(full, "SYNTHETIC_PRIVATE_QUEUE_16")
        assertNoRawInGeneralStateOrLog(ioFailure, "SYNTHETIC_PRIVATE_IO_FAILURE")
    }

    private data class Fixture(
        val runtime: DiagnosticRuntime,
        val id: Long,
        val request: TeslaServiceConnectionRequest,
        val service: ProductMicrophoneService,
        val lease: Any,
        val transcriptRoot: File,
        val transcriptExecutor: QueuedExecutor,
    ) {
        fun sealed(): Boolean = lease.javaClass.getDeclaredField("sealed").run {
            isAccessible = true
            getBoolean(lease)
        }

        fun serviceStopReason(): String? = ProductMicrophoneService::class.java.getDeclaredField("sealedReason").run {
            isAccessible = true
            get(service) as String?
        }
    }

    /** FrunkVoiceCleanupDeviceTest와 같은 독립 저장 생성자; 키나 VIN 저장은 하지 않는다. */
    private fun reservedRuntime(backendPresent: Boolean,
        transcriptReadiness: (Long) -> String? = { "TEST_READINESS_BLOCKED" },
        transcriptRoot: File? = null): Fixture {
        val namespace = UUID.randomUUID().toString()
        val root = transcriptRoot ?: File(app.noBackupFilesDir.canonicalFile, "product-transcript-test-$namespace")
        val executor = QueuedExecutor()
        val runtime = runtimeFor(root, executor, transcriptReadiness)
        runtime.activityVisible = true
        val now = runtime.now()
        val request = TeslaServiceConnectionRequest(ObservationService(), "product-voice-test-$namespace",
            candidate = 1, attempt = 1, associationId = 1, device = inertDevice(),
            sourceTimestampNanos = now * 1_000_000, deadline = now + 40_000)
        val service = ProductMicrophoneService() // attach/onCreate/onStartCommand을 호출하지 않는다.
        val leaseField = runtimeField("productVoiceLease")
        val constructor = leaseField.type.getDeclaredConstructor(java.lang.Long.TYPE,
            TeslaServiceConnectionRequest::class.java, String::class.java, java.lang.Integer.TYPE,
            ProductMicrophoneService::class.java, java.lang.Boolean.TYPE, java.lang.Long.TYPE)
            .apply { isAccessible = true }
        val id = 1L
        val lease = constructor.newInstance(id, request, "5YJ3E1EA7KF000000", 1, service, false, 0L)
        leaseField.set(runtime, lease)
        if (backendPresent) runtimeField("teslaServiceConnectionRequest").set(runtime, request)
        runtime.update { it.copy(productVoiceConsent = true,
            productVoice = ProductVoiceState(ProductVoiceStage.LISTENING)) }
        return Fixture(runtime, id, request, service, lease, root, executor)
    }

    private fun runtimeFor(root: File, executor: Executor,
        readiness: ((Long) -> String?)? = null): DiagnosticRuntime {
        val namespace = UUID.randomUUID().toString()
        val marker = "product_voice_runtime_test_$namespace"
        // 새 fixture의 과거 false 값을 그대로 둔 채 자동 저장한다. 생산 설정은 읽거나 바꾸지 않는다.
        assertTrue(app.getSharedPreferences(marker, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("product_transcript_consent", false).commit())
        return DiagnosticRuntime(app,
            VehicleVinStore(app, File(app.noBackupFilesDir, "vehicle-vin-tests/product-voice-$namespace.enc"),
                "heytesla.vin.test.product-voice.$namespace"),
            TeslaBleKeyStore(app, "heytesla.product.voice.key.$namespace"),
            marker, root, executor, readiness)
    }

    /** API36 BluetoothDevice parcel은 address와 addressType뿐이다. adapter·RF·GATT를 조회하지 않는다. */
    private fun inertDevice(): BluetoothDevice {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeString("02:00:00:00:00:76")
            parcel.writeInt(BluetoothDevice.ADDRESS_TYPE_PUBLIC)
            parcel.setDataPosition(0)
            BluetoothDevice.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun preferencesWithCommit(original: SharedPreferences, key: String = "product_voice_consent",
        value: Boolean = false, commit: () -> Boolean): SharedPreferences {
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) {
                proxy, method, args ->
            when (method.name) {
                "putBoolean" -> {
                    assertEquals(key, args?.get(0))
                    assertEquals(value, args?.get(1))
                    proxy
                }
                "commit" -> commit()
                else -> throw AssertionError("Unexpected test editor operation: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) {
                _, method, args ->
            if (method.name == "edit") editor else method.invoke(original, *(args ?: emptyArray()))
        } as SharedPreferences
    }

    /** 실제 모델 대신 합성 결과를 주되 gate 생성은 제품 소비 경계를 통한다. */
    private fun acceptedGate(runtime: DiagnosticRuntime): FrunkVoicePolicy {
        val now = runtime.now()
        val session = ProductVoiceSession()
        assertTrue(session.start())
        assertTrue(session.beginPreparation())
        session.preparationFinished(prepared = true, clean = false, failure = null)
        assertTrue(session.beginCapture())
        session.captureFinished(released = true, clean = true)
        assertTrue(session.beginRecognition())
        val result = KoreanSpeechStreamResult("헤이 테슬라 프렁크 열어줘", failure = null,
            decodeMs = 1, cleanupMs = 1, consumedBytes = 32_000, decodeCompleted = true,
            streamCleanupConfirmed = true, modelReused = false)
        assertEquals(ProductVoiceSession.Decision.ACCEPTED,
            session.acceptRecognition(result, expectedBytes = 32_000, startedAt = now, now = now))
        assertFalse(session.cleanupConfirmed)
        session.recognizerClosed(KoreanSpeechCleanup(true, true, 1))
        return requireNotNull(requireNotNull(session.complete(now)).gate).also {
            assertEquals(FrunkVoicePolicy.Phase.ACCEPTED, it.phase)
            assertNull(it.acceptedConfidence)
        }
    }

    private fun assertRetainedAfterCleanupFailure(fixture: Fixture) {
        val runtime = fixture.runtime
        assertSame(fixture.lease, runtimeField("productVoiceLease").get(runtime))
        assertTrue(fixture.sealed())
        assertTrue(runtime.serviceConnectionInFlight(fixture.request.owner,
            fixture.request.fieldToken, fixture.request.attempt))
        assertTrue(runtime.diagnosticCleanupUncertain)
        assertEquals(ProductVoiceStage.FAILED, runtime.state.value.productVoice.stage)
        assertEquals("LEASE_LOST", runtime.productVoiceAllowed(fixture.id))
    }

    private fun assertNoVehicleDispatch(runtime: DiagnosticRuntime) {
        assertFalse(runtime.teslaKeyReserved())
        assertFalse(runtime.teslaKeyState.value.transmissionAttempted)
        assertFalse(runtime.teslaKeyState.value.frunkCommandSent)
    }

    private class QueuedExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            synchronized(queue) { queue.addLast(command) }
        }

        fun runAll() {
            while (true) {
                val task = synchronized(queue) { queue.pollFirst() } ?: return
                task.run()
            }
        }
    }

    private fun transcriptDiagnostic(outcome: String = "GRAMMAR_NO_MATCH", captureMs: Long = 11L) =
        ProductVoiceDiagnostic(outcome, utteranceBytes = 32_000, voicedBytes = 16_000,
            silenceBytesAtEnd = 8_000, suppressedOnsets = 0, captureMs = captureMs,
            decodeMs = 1, cleanupMs = 1, modelReused = true, microphoneReleased = true,
            streamCleanupConfirmed = true, recognizerCleanupConfirmed = outcome == "ACCEPTED")

    private fun drainTranscripts(fixture: Fixture): List<JSONObject> {
        fixture.transcriptExecutor.runAll()
        instrumentation.waitForIdleSync()
        return fixture.transcriptRoot.listFiles().orEmpty().map { file ->
            assertTrue("완료 JSON 외 파일을 만들지 않아야 합니다.", file.name.endsWith(".json"))
            JSONObject(file.readText())
        }
    }

    private fun diagnosticRows(fixture: Fixture): List<JSONObject> {
        val sink = runtimeField("fieldLog").get(fixture.runtime) as FieldEventSink
        assertTrue(sink.sync())
        val file = FieldEventSink::class.java.getDeclaredField("file").run {
            isAccessible = true
            get(sink) as File
        }
        return file.readLines().map(::JSONObject).filter {
            it.getString("event") == "PRODUCT_VOICE_DIAGNOSTIC"
        }
    }

    private fun assertNoRawInGeneralStateOrLog(fixture: Fixture, text: String) {
        assertFalse(fixture.runtime.state.value.toString().contains(text))
        val sink = runtimeField("fieldLog").get(fixture.runtime) as FieldEventSink
        assertTrue(sink.sync())
        val file = FieldEventSink::class.java.getDeclaredField("file").run {
            isAccessible = true
            get(sink) as File
        }
        val log = file.readText()
        assertFalse(log.contains(text))
        assertFalse(log.contains(JSONObject.quote(text)))
    }

    private fun runtimeField(name: String) = DiagnosticRuntime::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }
}
