package com.heytesla.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FrunkVoiceProgressTest {
    private val active = FrunkVoiceState(stage = FrunkVoiceStage.CORE, active = true, coreStarted = true)

    @Test fun actualPhaseAndEvidenceSeparateAuthenticationParkingSendAndAcknowledgement() {
        assertEquals(FrunkVoiceStep.CONNECTING, active.copy(coreStage = TeslaKeyStage.CONNECTING).progressStep())
        assertEquals(FrunkVoiceStep.AUTHENTICATING, active.copy(coreStage = TeslaKeyStage.HANDSHAKING).progressStep())
        val reading = active.copy(coreStage = TeslaKeyStage.READING_STATUS)
        assertEquals(FrunkVoiceStep.READING_STATE, reading.progressStep())
        assertEquals(FrunkVoiceStep.FRESH_P, reading.copy(frunkFreshParkVerified = true).progressStep())
        val sent = reading.copy(frunkFreshParkVerified = true, frunkCommandSent = true)
        assertEquals(FrunkVoiceStep.COMMAND_SENT, sent.progressStep())
        assertEquals(FrunkVoiceStep.ACK, sent.copy(frunkCommandAcknowledged = true).progressStep())
    }

    @Test fun terminalSuccessNeedsBothAcknowledgementAndOpenReport() {
        val complete = active.copy(active = false, stage = FrunkVoiceStage.COMPLETE, coreStage = TeslaKeyStage.COMPLETE)
        for ((ack, open) in listOf(false to false, true to false, false to true)) {
            assertEquals(FrunkVoiceStep.UNKNOWN, complete.copy(frunkCommandAcknowledged = ack, frunkOpenObserved = open).progressStep())
        }
        assertEquals(FrunkVoiceStep.COMPLETE, complete.copy(frunkCommandAcknowledged = true, frunkOpenObserved = true).progressStep())
    }

    @Test fun cleanupUncertaintyAndPostSendUnknownCannotBecomeSuccessOrCleanCancellation() {
        val observed = active.copy(frunkCommandSent = true, frunkCommandAcknowledged = true, frunkOpenObserved = true,
            coreStage = TeslaKeyStage.CLEANUP_FAILED, coreOutcome = TeslaKeyOutcome.UNKNOWN)
        assertEquals(FrunkVoiceStep.CLEANUP_FAILED, observed.progressStep())
        assertEquals(FrunkVoiceStep.UNKNOWN, observed.copy(coreStage = TeslaKeyStage.CANCELED, cancelRequested = true).progressStep())
        assertEquals(FrunkVoiceStep.STOPPED, active.copy(coreStage = TeslaKeyStage.CANCELED, coreOutcome = TeslaKeyOutcome.CANCELED).progressStep())
        assertEquals(FrunkVoiceStep.TIMED_OUT, active.copy(coreStage = TeslaKeyStage.TIMED_OUT).progressStep())
    }
}
