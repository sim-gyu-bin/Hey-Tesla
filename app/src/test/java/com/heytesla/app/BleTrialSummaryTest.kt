package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 소비자가 회수한 행의 오염·회차 경계·enum 경계만 검사한다. 테스트 파일은 삭제하지 않는다. */
class BleTrialSummaryTest {
    @Test fun untrustedStringsCannotContaminateBleSummary() {
        val secret = "VIN-MAC-RX-raw\nexception"
        val encoded = FieldJson.encode(record("BLE_FIELD_TRIAL_FINISHED", summary().copy(status = secret, reason = secret)).copy(
            state = mapOf(FieldStateKeys.BLE_FIELD_TRIAL_STOP_REASON to secret, "rawMac" to secret),
        ))
        assertFalse(encoded.contains("VIN-MAC-RX"))
        assertTrue(encoded.contains("\"status\":null"))
        assertTrue(encoded.contains("\"reason\":null"))
        assertTrue(encoded.contains("\"gattStatus\":133"))
        assertTrue(encoded.contains("\"subscriptionConfirmed\":true"))
        assertTrue(encoded.contains("\"bleFieldTrialStopReason\":null"))
        assertFalse(encoded.contains("rawMac"))
    }

    @Test fun nextEventHasNoPreviousAttemptAndLegacyBytesRemainUnchanged() {
        val file = File(Files.createTempDirectory("ble-summary-test").toFile(), "events.jsonl")
        val legacy = (1..4).joinToString("") { "{\"version\":$it,\"event\":\"PROCESS_START_OFF\",\"state\":{}}\n" }
        file.writeText(legacy)
        val sink = FieldEventSink(file)
        sink.start()
        assertTrue(sink.enqueue(record("BLE_FIELD_TRIAL_FINISHED", summary())))
        assertTrue(sink.enqueue(record("BLE_FIELD_WAITING")))
        assertTrue(sink.sync())
        val bytes = file.readBytes()
        assertArrayEquals(legacy.toByteArray(), bytes.copyOfRange(0, legacy.toByteArray().size))
        val rows = file.readLines().drop(4)
        assertTrue(rows[0].contains("\"bleTrial\":{\"attempt\":2"))
        assertTrue(rows[0].contains("\"primaryGattStatus\":133"))
        assertTrue(rows[0].contains("\"cleanupGattStatus\":8"))
        assertTrue(rows[0].contains("\"firstRxElapsedMs\":null"))
        assertTrue(rows[0].contains("\"backgroundConnect\":true"))
        assertTrue(rows[1].contains("\"bleTrial\":null"))
        assertFalse(rows[1].contains("\"attempt\""))
        assertFalse(rows[1].contains("\"primaryGattStatus\""))
        assertFalse(rows[1].contains("\"firstRxElapsedMs\""))
        assertTrue(rows[0].contains("\"speechTrial\":null"))
    }

    @Test fun enumAndEventBoundariesRejectLookalikesWithoutLosingSdkCode() {
        val accepted = FieldJson.encode(record("BLE_FIELD_TRIAL_FINISHED", summary().copy(
            status = "CLEANUP_FAILED", reason = "LOCAL_CLOSE_FAILED_RESTART_REQUIRED",
            gattStatus = -1, primaryGattStatus = -1, cleanupGattStatus = 8, firstRxElapsedMs = 56,
        )).copy(
            state = mapOf(FieldStateKeys.BLE_FIELD_TRIAL_STOP_REASON to "LOCAL_CLOSE_FAILED_RESTART_REQUIRED"),
        ))
        assertTrue(accepted.contains("\"status\":\"CLEANUP_FAILED\""))
        assertTrue(accepted.contains("\"reason\":\"LOCAL_CLOSE_FAILED_RESTART_REQUIRED\""))
        assertTrue(accepted.contains("\"gattStatus\":-1"))
        assertTrue(accepted.contains("\"primaryGattStatus\":-1"))
        assertTrue(accepted.contains("\"cleanupGattStatus\":8"))
        assertTrue(accepted.contains("\"firstRxElapsedMs\":56"))
        assertTrue(accepted.contains("\"bleFieldTrialStopReason\":\"LOCAL_CLOSE_FAILED_RESTART_REQUIRED\""))
        val rejected = BleTrialSummaryJson.encode(summary().copy(status = "complete", reason = "SUBSCRIPTION_OBSERVED_extra", gattStatus = null, batteryPercent = 101))
        assertTrue(rejected.contains("\"status\":null"))
        assertTrue(rejected.contains("\"reason\":null"))
        assertTrue(rejected.contains("\"gattStatus\":null"))
        assertTrue(rejected.contains("\"batteryPercent\":null"))
        assertTrue(rejected.contains("\"phaseElapsedMs\":null"))
        assertTrue(FieldEvents.isAllowed("BLE_FIELD_TRIAL_FINISHED"))
        assertTrue(FieldEvents.isAllowed("BLE_PROBE_CLEANUP_FAILED"))
        assertFalse(FieldEvents.isAllowed("BLE_FIELD_TRIAL_FINISHED_extra"))
        assertFalse(FieldEvents.isAllowed("BLE_PROBE_FAILED_raw"))
    }

    private fun record(event: String, summary: BleTrialSummary? = null) = FieldRecord(
        processId = "process", trialId = "root-run", wallTimeMs = 1L, elapsedRealtimeMs = 2L,
        appVersion = "0.13.0-probe", event = event, state = emptyMap(), bleTrial = summary,
    )

    private fun summary() = BleTrialSummary(
        attempt = 2L, status = "COMPLETE", reason = "SUBSCRIPTION_OBSERVED", gattStatus = 133,
        elapsedMs = 100L, connected = true, serviceFound = true, txFound = true, rxFound = true,
        subscriptionConfirmed = true, remoteUnsubscribeConfirmed = false, disconnectConfirmed = false,
        localClosed = true, notificationCount = 0, leaseRetained = false,
        primaryGattStatus = 133, cleanupGattStatus = 8, backgroundConnect = true,
    )
}
