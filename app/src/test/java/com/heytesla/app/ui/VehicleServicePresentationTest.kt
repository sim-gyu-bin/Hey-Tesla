package com.heytesla.app.ui

import com.heytesla.app.DiagnosticState
import com.heytesla.app.speech.product.ProductVoiceStage
import com.heytesla.app.speech.product.ProductVoiceState
import com.heytesla.app.vehicle.connection.TeslaConnectionStage
import com.heytesla.app.vehicle.connection.TeslaConnectionState
import com.heytesla.app.vehicle.protocol.TeslaGear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleServicePresentationTest {
    private fun listening() = DiagnosticState(
        bleDetectionEnabled = true, bleFieldTrialActive = true,
        observationServiceRunning = true, bleFieldScanRunning = true,
        productVoiceConsent = true, assistant = true, microphonePermission = true,
        vehicleConnection = TeslaConnectionState(
            stage = TeslaConnectionStage.MONITORING, gear = TeslaGear.P, parkFresh = true,
        ),
        productVoice = ProductVoiceState(stage = ProductVoiceStage.LISTENING),
    )

    @Test fun completedNoMatchOrEmptyIsVisibleOnlyInCurrentListeningNotification() {
        for ((outcome, expected) in listOf(
            "GRAMMAR_NO_MATCH" to "명령 불일치 · 음성 대기",
            "EMPTY" to "음성 확인 안 됨 · 음성 대기",
        )) {
            val current = listening().copy(productVoiceRecognitionOutcome = outcome)
            val result = vehicleServicePresentation(current)
            assertEquals(VehicleServiceStatus.LISTENING, result.kind)
            assertTrue(result.text.contains(expected))
            assertTrue(vehicleServiceNotificationText(current).contains(expected))
            assertTrue(productMicrophoneNotificationText(current.productVoice, outcome).contains(expected))
            for (stage in ProductVoiceStage.entries.filter { it != ProductVoiceStage.LISTENING }) {
                val next = current.copy(productVoice = ProductVoiceState(stage))
                assertEquals(false, vehicleServiceNotificationText(next).contains(expected))
                assertEquals(false, productMicrophoneNotificationText(next.productVoice, outcome).contains(expected))
            }
            for (next in listOf(
                current.copy(bleDetectionEnabled = false),
                current.copy(observationServiceRunning = false),
                current.copy(teslaKeyCleanupFailed = true),
                current.copy(vehicleConnection = current.vehicleConnection.copy(parkFresh = false)),
                current.copy(vehicleConnection = current.vehicleConnection.copy(stage = TeslaConnectionStage.CONNECTING)),
            )) assertEquals(false, vehicleServiceNotificationText(next).contains(expected))
        }
    }

    @Test fun arbitraryRecognitionOutcomeCannotReachEitherNotification() {
        val text = "SYNTHETIC_PRIVATE_NOT_A_FIXED_OUTCOME"
        val current = listening().copy(productVoiceRecognitionOutcome = text)
        assertEquals(false, vehicleServiceNotificationText(current).contains(text))
        assertEquals(false, productMicrophoneNotificationText(current.productVoice, text).contains(text))
    }

    @Test fun truncationFailureRemainsVisibleAfterNormalMicrophoneForegroundCleanup() {
        val reason = "PRODUCT_VOICE_UTTERANCE_TRUNCATED"
        val stopped = listening().copy(
            productVoice = ProductVoiceState(ProductVoiceStage.STOPPED, reason),
            vehicleConnection = TeslaConnectionState(TeslaConnectionStage.STOPPED, reason = reason),
        )
        assertEquals(VehicleServiceStatus.VOICE_FAILED, vehicleServicePresentation(stopped).kind)
        assertTrue(vehicleServiceNotificationText(stopped).contains("발화 길이 초과"))
        assertEquals(VehicleServiceStatus.OFF,
            vehicleServicePresentation(stopped.copy(bleDetectionEnabled = false)).kind)
        assertEquals(VehicleServiceStatus.RESTART_REQUIRED,
            vehicleServicePresentation(stopped.copy(teslaKeyCleanupFailed = true)).kind)
        assertEquals(VehicleServiceStatus.CONNECTING, vehicleServicePresentation(stopped.copy(
            vehicleConnection = TeslaConnectionState(TeslaConnectionStage.PREPARING),
        )).kind)
    }

    @Test fun savedOnWithoutConsentCannotPresentMicrophoneReadiness() {
        assertEquals(VehicleServiceStatus.CONSENT_REQUIRED,
            vehicleServicePresentation(listening().copy(productVoiceConsent = false)).kind)
    }

    @Test fun oldListeningAndParkCannotSurviveOffOrDeadServiceInPresentation() {
        assertEquals(VehicleServiceStatus.OFF,
            vehicleServicePresentation(listening().copy(bleDetectionEnabled = false)).kind)
        assertEquals(VehicleServiceStatus.STOPPED,
            vehicleServicePresentation(listening().copy(observationServiceRunning = false)).kind)
    }

    @Test fun everyNonParkGearAndExpiredParkSuppressListening() {
        val current = listening()
        for (gear in TeslaGear.entries.filter { it != TeslaGear.P } + listOf(null)) {
            assertEquals(VehicleServiceStatus.PARK_REQUIRED, vehicleServicePresentation(current.copy(
                vehicleConnection = current.vehicleConnection.copy(gear = gear),
            )).kind)
        }
        assertEquals(VehicleServiceStatus.PARK_REQUIRED, vehicleServicePresentation(current.copy(
            vehicleConnection = current.vehicleConnection.copy(parkFresh = false),
        )).kind)
    }

    @Test fun connectingAndAuthenticatingCannotReuseListeningFromPreviousRound() {
        val current = listening()
        for ((stage, expected) in listOf(
            TeslaConnectionStage.CONNECTING to VehicleServiceStatus.CONNECTING,
            TeslaConnectionStage.CHECKING_VEHICLE to VehicleServiceStatus.CHECKING_VEHICLE,
            TeslaConnectionStage.WAKING_VEHICLE to VehicleServiceStatus.WAKING_VEHICLE,
            TeslaConnectionStage.AUTHENTICATING to VehicleServiceStatus.AUTHENTICATING,
            TeslaConnectionStage.IDLE to VehicleServiceStatus.WAITING,
            TeslaConnectionStage.STOPPED to VehicleServiceStatus.STOPPED,
        )) {
            assertEquals(expected, vehicleServicePresentation(current.copy(
                vehicleConnection = current.vehicleConnection.copy(stage = stage),
            )).kind)
        }
    }

    @Test fun selectedAssistantAndPermissionRemainNecessaryForListeningPresentation() {
        assertEquals(VehicleServiceStatus.ASSISTANT_REQUIRED,
            vehicleServicePresentation(listening().copy(assistant = false)).kind)
        assertEquals(VehicleServiceStatus.MICROPHONE_REQUIRED,
            vehicleServicePresentation(listening().copy(microphonePermission = false)).kind)
        assertEquals(VehicleServiceStatus.LISTENING, vehicleServicePresentation(listening()).kind)
    }

    @Test fun recognizingAndCommandReadyDoNotPresentLiveMicrophoneOrPhysicalCompletion() {
        for ((stage, expected) in listOf(
            ProductVoiceStage.RECOGNIZING to VehicleServiceStatus.RECOGNIZING,
            ProductVoiceStage.COMMAND_READY to VehicleServiceStatus.COMMAND_READY,
            ProductVoiceStage.STOPPING to VehicleServiceStatus.STOPPING,
        )) {
            assertEquals(expected, vehicleServicePresentation(listening().copy(
                productVoice = ProductVoiceState(stage = stage),
            )).kind)
        }
    }

    @Test fun cleanupFailureHasPriorityOverOffStoppingAndHistoricalSuccess() {
        val failed = listening().copy(bleDetectionEnabled = false, bleFieldTrialStopping = true,
            productVoice = ProductVoiceState(stage = ProductVoiceStage.FAILED,
                reason = "LOCAL_CLEANUP_FAILED_RESTART_REQUIRED"))
        assertEquals(VehicleServiceStatus.RESTART_REQUIRED, vehicleServicePresentation(failed).kind)
        assertTrue(vehicleServicePresentation(failed).error)
        assertEquals(VehicleServiceStatus.RESTART_REQUIRED,
            vehicleServicePresentation(listening().copy(teslaKeyCleanupFailed = true)).kind)
    }

    @Test fun connectionFailureWithoutVoiceFailureDoesNotBecomeVoiceFailure() {
        val current = listening()
        for (reason in listOf(null, "DEADLINE_EXPIRED")) {
            for (stage in listOf(ProductVoiceStage.IDLE, ProductVoiceStage.STOPPED, ProductVoiceStage.LISTENING)) {
                val result = vehicleServicePresentation(current.copy(
                    vehicleConnection = TeslaConnectionState(
                        stage = TeslaConnectionStage.FAILED, reason = reason,
                    ),
                    productVoice = ProductVoiceState(stage = stage),
                ))
                assertEquals(VehicleServiceStatus.CONNECTION_FAILED, result.kind)
                assertTrue(result.error)
            }
        }
    }

    @Test fun voiceFailureWithoutConnectionFailureRemainsVoiceFailure() {
        val result = vehicleServicePresentation(listening().copy(
            productVoice = ProductVoiceState(stage = ProductVoiceStage.FAILED),
        ))
        assertEquals(VehicleServiceStatus.VOICE_FAILED, result.kind)
        assertTrue(result.error)
    }

    @Test fun modelAndDecodeFailuresRetainPriorityOverConnectionFailureButNotCleanupFailure() {
        for (reason in listOf(
            "PRODUCT_VOICE_MODEL_MISSING", "PRODUCT_VOICE_MODEL_LOAD_FAILED", "PRODUCT_VOICE_DECODE_FAILED",
        )) {
            val failed = listening().copy(
                vehicleConnection = TeslaConnectionState(
                    stage = TeslaConnectionStage.FAILED, reason = "DEADLINE_EXPIRED",
                ),
                productVoice = ProductVoiceState(stage = ProductVoiceStage.FAILED, reason = reason),
            )
            assertEquals(VehicleServiceStatus.VOICE_FAILED, vehicleServicePresentation(failed).kind)
            assertEquals(VehicleServiceStatus.RESTART_REQUIRED,
                vehicleServicePresentation(failed.copy(teslaKeyCleanupFailed = true)).kind)
        }
    }

    @Test fun completedModelAndDecodeFailuresRemainErrorsAfterBothStagesStop() {
        for (reason in listOf(
            "PRODUCT_VOICE_MODEL_MISSING", "PRODUCT_VOICE_MODEL_LOAD_FAILED", "PRODUCT_VOICE_DECODE_FAILED",
        )) {
            val stopped = listening().copy(
                vehicleConnection = TeslaConnectionState(stage = TeslaConnectionStage.STOPPED, reason = reason),
                productVoice = ProductVoiceState(stage = ProductVoiceStage.STOPPED, reason = reason),
            )
            val result = vehicleServicePresentation(stopped)
            assertEquals(VehicleServiceStatus.VOICE_FAILED, result.kind)
            assertTrue(result.error)
            assertEquals(VehicleServiceStatus.VOICE_FAILED, vehicleServicePresentation(stopped.copy(
                vehicleConnection = stopped.vehicleConnection.copy(stage = TeslaConnectionStage.FAILED),
            )).kind)
            assertEquals(VehicleServiceStatus.RESTART_REQUIRED,
                vehicleServicePresentation(stopped.copy(teslaKeyCleanupFailed = true)).kind)
            assertEquals(VehicleServiceStatus.OFF,
                vehicleServicePresentation(stopped.copy(bleDetectionEnabled = false)).kind)
            assertEquals(VehicleServiceStatus.STOPPING,
                vehicleServicePresentation(stopped.copy(bleFieldTrialStopping = true)).kind)
        }
    }

    @Test fun stoppedModelFailureCannotOverrideANewConnectionAttempt() {
        for (reason in listOf(
            "PRODUCT_VOICE_MODEL_MISSING", "PRODUCT_VOICE_MODEL_LOAD_FAILED", "PRODUCT_VOICE_DECODE_FAILED",
        )) {
            for ((stage, expected) in listOf(
                TeslaConnectionStage.PREPARING to VehicleServiceStatus.CONNECTING,
                TeslaConnectionStage.CONNECTING to VehicleServiceStatus.CONNECTING,
                TeslaConnectionStage.CHECKING_VEHICLE to VehicleServiceStatus.CHECKING_VEHICLE,
                TeslaConnectionStage.WAKING_VEHICLE to VehicleServiceStatus.WAKING_VEHICLE,
                TeslaConnectionStage.AUTHENTICATING to VehicleServiceStatus.AUTHENTICATING,
            )) {
                val retry = listening().copy(
                    vehicleConnection = TeslaConnectionState(stage = stage),
                    productVoice = ProductVoiceState(stage = ProductVoiceStage.STOPPED, reason = reason),
                )
                val result = vehicleServicePresentation(retry)
                assertEquals(expected, result.kind)
                assertEquals(false, result.error)
                for (connectionReason in listOf("DEADLINE_EXPIRED", "GATT_CONNECT_FAILED")) {
                    val failed = vehicleServicePresentation(retry.copy(
                        vehicleConnection = TeslaConnectionState(
                            stage = TeslaConnectionStage.FAILED, reason = connectionReason,
                        ),
                    ))
                    assertEquals(VehicleServiceStatus.CONNECTION_FAILED, failed.kind)
                    assertTrue(failed.error)
                }
            }
        }
    }

    @Test fun retainedModelReasonCannotPromoteAnInProgressVoiceStageToFailure() {
        for ((stage, expected) in listOf(
            ProductVoiceStage.IDLE to VehicleServiceStatus.WAITING,
            ProductVoiceStage.PREPARING to VehicleServiceStatus.VOICE_PREPARING,
            ProductVoiceStage.LISTENING to VehicleServiceStatus.LISTENING,
            ProductVoiceStage.RECOGNIZING to VehicleServiceStatus.RECOGNIZING,
            ProductVoiceStage.COMMAND_READY to VehicleServiceStatus.COMMAND_READY,
            ProductVoiceStage.STOPPING to VehicleServiceStatus.STOPPING,
        )) {
            val result = vehicleServicePresentation(listening().copy(
                productVoice = ProductVoiceState(stage = stage, reason = "PRODUCT_VOICE_MODEL_LOAD_FAILED"),
            ))
            assertEquals(expected, result.kind)
            assertEquals(false, result.error)
        }
    }

    @Test fun normalStopAndStaleParkReasonsDoNotBecomeVoiceFailures() {
        for (reason in listOf(null, "PRODUCT_VOICE_STOPPED", "PARK_STATE_STALE", "CONNECTION_P_RECEIPT_STALE")) {
            val result = vehicleServicePresentation(listening().copy(
                vehicleConnection = TeslaConnectionState(stage = TeslaConnectionStage.STOPPED, reason = reason),
                productVoice = ProductVoiceState(stage = ProductVoiceStage.STOPPED, reason = reason),
            ))
            assertEquals(VehicleServiceStatus.STOPPED, result.kind)
            assertEquals(false, result.error)
        }
    }

    @Test fun terminalFrunkEvidenceSurvivesConnectionAndVoiceFailureWithoutClaimingCompletion() {
        val current = listening()
        for (stage in listOf(TeslaConnectionStage.EXECUTING, TeslaConnectionStage.STOPPED,
            TeslaConnectionStage.FAILED, TeslaConnectionStage.BLOCKED)) {
            val sent = current.copy(vehicleConnection = current.vehicleConnection.copy(
                stage = stage, frunkCommandSent = true,
            ))
            assertEquals(ProductFrunkResult.SENT, vehicleServicePresentation(sent).frunkResult)
            val acknowledged = sent.copy(vehicleConnection = sent.vehicleConnection.copy(
                frunkCommandAcknowledged = true,
            ))
            assertEquals(ProductFrunkResult.ACKNOWLEDGED, vehicleServicePresentation(acknowledged).frunkResult)
            val reported = acknowledged.copy(vehicleConnection = acknowledged.vehicleConnection.copy(
                frunkOpenObserved = true,
            ))
            assertEquals(ProductFrunkResult.OPEN_REPORTED, vehicleServicePresentation(reported).frunkResult)
            assertEquals(ProductFrunkResult.SENT, vehicleServicePresentation(sent.copy(
                productVoice = ProductVoiceState(stage = ProductVoiceStage.FAILED),
            )).frunkResult)
            if (stage == TeslaConnectionStage.FAILED) {
                assertEquals(VehicleServiceStatus.CONNECTION_FAILED, vehicleServicePresentation(reported).kind)
            }
        }
    }

    @Test fun offStoppingAndCleanupKeepPriorityOverFrunkEvidence() {
        val current = listening()
        val reported = current.copy(vehicleConnection = current.vehicleConnection.copy(
            stage = TeslaConnectionStage.STOPPED, frunkCommandSent = true,
            frunkCommandAcknowledged = true, frunkOpenObserved = true,
        ))
        for ((snapshot, expected) in listOf(
            reported.copy(bleDetectionEnabled = false) to VehicleServiceStatus.OFF,
            reported.copy(bleFieldTrialStopping = true) to VehicleServiceStatus.STOPPING,
            reported.copy(teslaKeyCleanupFailed = true) to VehicleServiceStatus.RESTART_REQUIRED,
            reported.copy(productVoice = ProductVoiceState(stage = ProductVoiceStage.STOPPING)) to VehicleServiceStatus.STOPPING,
        )) {
            val result = vehicleServicePresentation(snapshot)
            assertEquals(expected, result.kind)
            assertEquals(ProductFrunkResult.OPEN_REPORTED, result.frunkResult)
        }
    }

    @Test fun newPreparationCannotExposePreviousOperationResult() {
        val current = listening()
        assertEquals(ProductFrunkResult.NONE, vehicleServicePresentation(current.copy(
            vehicleConnection = current.vehicleConnection.copy(stage = TeslaConnectionStage.PREPARING,
                frunkCommandSent = true, frunkCommandAcknowledged = true, frunkOpenObserved = true),
        )).frunkResult)
    }

    @Test fun preparationReasonsAreSpecificButCannotOverrideSafetyFailures() {
        val current = listening()
        for ((reason, expected) in listOf(
            "PRODUCT_VOICE_CONSENT_REQUIRED" to VehicleServiceStatus.CONSENT_REQUIRED,
            "DEFAULT_ASSISTANT_REQUIRED" to VehicleServiceStatus.ASSISTANT_REQUIRED,
            "ASSISTANT_NOT_READY" to VehicleServiceStatus.ASSISTANT_NOT_READY,
            "MIC_PERMISSION_REQUIRED" to VehicleServiceStatus.MICROPHONE_REQUIRED,
        )) {
            val blocked = current.copy(productVoice = ProductVoiceState(
                stage = ProductVoiceStage.BLOCKED, reason = reason,
            ))
            assertEquals(expected, vehicleServicePresentation(blocked).kind)
            assertEquals(VehicleServiceStatus.RESTART_REQUIRED,
                vehicleServicePresentation(blocked.copy(teslaKeyCleanupFailed = true)).kind)
            assertEquals(VehicleServiceStatus.CONNECTION_FAILED, vehicleServicePresentation(blocked.copy(
                vehicleConnection = current.vehicleConnection.copy(stage = TeslaConnectionStage.FAILED),
            )).kind)
            assertEquals(VehicleServiceStatus.BLOCKED, vehicleServicePresentation(blocked.copy(
                vehicleConnection = current.vehicleConnection.copy(stage = TeslaConnectionStage.BLOCKED,
                    reason = "PARK_STATE_STALE"),
            )).kind)
            assertEquals(expected, vehicleServicePresentation(current.copy(
                vehicleConnection = current.vehicleConnection.copy(stage = TeslaConnectionStage.BLOCKED, reason = reason),
            )).kind)
        }
    }

    @Test fun preferenceFailureRemainsVisibleAfterRevocationOffAndCleanup() {
        val failed = listening().copy(productVoiceConsent = false,
            productVoice = ProductVoiceState(stage = ProductVoiceStage.STOPPED, reason = "VOICE_PREFERENCE_FAILED"))
        assertEquals(VehicleServiceStatus.VOICE_PREFERENCE_FAILED, vehicleServicePresentation(failed).kind)
        for ((snapshot, expected) in listOf(
            failed.copy(bleDetectionEnabled = false) to VehicleServiceStatus.OFF,
            failed.copy(bleFieldTrialStopping = true) to VehicleServiceStatus.STOPPING,
            failed.copy(teslaKeyCleanupFailed = true) to VehicleServiceStatus.RESTART_REQUIRED,
        )) {
            val result = vehicleServicePresentation(snapshot)
            assertEquals(expected, result.kind)
            assertTrue(result.voicePreferenceFailed)
            assertTrue(result.error)
        }
    }

    @Test fun lateVoiceStopCannotErasePersistedConsentFailureWarningWhileOff() {
        val failed = listening().copy(
            bleDetectionEnabled = false, productVoiceConsent = false,
            productVoiceConsentSaveFailed = true,
            productVoice = ProductVoiceState(stage = ProductVoiceStage.STOPPING,
                reason = "VOICE_PREFERENCE_FAILED"),
        )
        val stopped = failed.copy(productVoice = ProductVoiceState(stage = ProductVoiceStage.STOPPED,
            reason = "PRODUCT_VOICE_CONSENT_REQUIRED"))
        val result = vehicleServicePresentation(stopped)
        assertEquals(VehicleServiceStatus.OFF, result.kind)
        assertTrue(result.voicePreferenceFailed)
        assertTrue(result.error)
        assertEquals(false, vehicleServicePresentation(stopped.copy(
            productVoiceConsentSaveFailed = false,
        )).voicePreferenceFailed)
    }
}
