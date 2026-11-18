package com.heytesla.app.vehicle.connection

import com.heytesla.app.logging.FieldEvents
import com.heytesla.app.logging.FieldJson
import com.heytesla.app.logging.FieldRecord
import com.heytesla.app.logging.FieldStateKeys
import com.heytesla.app.logging.ProductServiceLog
import com.heytesla.app.vehicle.protocol.TeslaBleRxStep
import com.tesla.generated.carserver.common.Common
import com.tesla.generated.carserver.vehicle.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaConnectionWakeLogTest {
    @Test fun actualWakeTerminalObservationHasSchema35OnlyClassificationWithoutControlSuccess() {
        VehiclePeerHarness().use { h ->
            h.start(); h.replyBody(sleepStatus = 2); h.replyWake()
            val actual = h.gattObservations.last {
                it.event == TeslaGattEvent.PROTOCOL && it.rxResponse?.step == TeslaBleRxStep.WAKE_RESULT
            }
            val record = record(h, "TESLA_CONNECTION_GATT_OBSERVATION").copy(connectionObservation = actual)
            val current = FieldJson.encode(record)
            assertTrue(current.contains("\"step\":\"WAKE_RESULT\""))
            assertTrue(current.contains("\"disposition\":\"WAKE_ACKNOWLEDGED\""))
            assertTrue(current.contains("\"aead\":\"VERIFIED\""))
            assertFalse(current.contains("\"frunkCommandSent\":true"))
            assertFalse(current.contains("\"connectionParkFresh\":true"))
            val previous = FieldJson.encode(record.copy(version = 34))
            assertTrue(previous.contains("\"step\":null"))
            assertTrue(previous.contains("\"disposition\":null"))
            assertTrue(previous.contains("\"aead\":\"VERIFIED\""))
            assertFalse(previous.contains("WAKE_RESULT"))
            assertFalse(previous.contains("WAKE_ACKNOWLEDGED"))
        }
    }

    @Test fun nominalWakeObservationAndEventRemainUnauthenticatedAndCannotBackportIntoSchema34() {
        VehiclePeerHarness().use { h ->
            h.start(); h.replyBody(sleepStatus = 2)
            h.inject(h.peer.nominalWakeResponse(h.requests.last().id)); h.settle()
            val actual = h.gattObservations.last {
                it.event == TeslaGattEvent.PROTOCOL && it.rxResponse?.step == TeslaBleRxStep.WAKE_RESULT
            }
            assertEquals("WAKE_NOMINAL_ACK", actual.rxResponse?.disposition)
            assertEquals(TeslaConnectionPreparationPhase.DRIVE, h.session.preparationPhase)
            assertTrue(h.parkStates.isEmpty())
            assertFalse(h.command())
            assertTrue(FieldEvents.isAllowed("TESLA_CONNECTION_WAKE_NOMINAL_ACK"))
            assertEquals(1, h.events.count { it == "TESLA_CONNECTION_WAKE_NOMINAL_ACK" })
            assertFalse("TESLA_CONNECTION_WAKE_ACKNOWLEDGED" in h.events)
            val record = record(h, "TESLA_CONNECTION_GATT_OBSERVATION").copy(connectionObservation = actual)
            val current = FieldJson.encode(record)
            assertTrue(current.contains("\"step\":\"WAKE_RESULT\""))
            assertTrue(current.contains("\"disposition\":\"WAKE_NOMINAL_ACK\""))
            assertTrue(current.contains("\"aead\":\"NOT_ATTEMPTED\""))
            assertFalse(current.contains("\"aead\":\"VERIFIED\""))
            assertFalse(current.contains("\"authEvidence\":\"ACKNOWLEDGED\""))
            assertFalse(current.contains("\"connectionParkFresh\":true"))
            assertFalse(current.contains("\"frunkCommandSent\":true"))
            val old = FieldJson.encode(record.copy(version = 34))
            assertTrue(old.contains("\"step\":null"))
            assertTrue(old.contains("\"disposition\":null"))
            assertFalse(old.contains("WAKE_NOMINAL_ACK"))
        }
    }

    @Test fun newStagesAndReasonsCannotBeBackportedIntoHistoricalSnapshots() {
        VehiclePeerHarness().use { h ->
            for (stage in ProductServiceLog.wakeStages) {
                for (reason in ProductServiceLog.wakeReasons) {
                    val record = record(h, "TESLA_CONNECTION_STATE_CHANGED").copy(state = mapOf(
                        FieldStateKeys.CONNECTION_STAGE to stage,
                        FieldStateKeys.CONNECTION_REASON to reason,
                    ))
                    val current = FieldJson.encode(record)
                    assertTrue(current.contains("\"connectionStage\":\"$stage\""))
                    assertTrue(current.contains("\"connectionReason\":\"$reason\""))
                    for (version in 1..34) {
                        val old = FieldJson.encode(record.copy(version = version))
                        assertTrue(old.contains("\"connectionStage\":null"))
                        assertTrue(old.contains("\"connectionReason\":null"))
                        assertFalse(old.contains(stage))
                        assertFalse(old.contains(reason))
                    }
                }
            }
        }
    }

    @Test fun observedWakeEventsAreFixedCodesAndNoRawOriginChannelIsAdded() {
        VehiclePeerHarness().use { h ->
            h.start(); h.replyBody(sleepStatus = 2); h.replyWake()
            val wakeEvents = h.events.filter { it.startsWith("TESLA_CONNECTION_") }
            assertTrue(wakeEvents.containsAll(listOf("TESLA_CONNECTION_BODY_VERIFIED",
                "TESLA_CONNECTION_WAKE_STARTED", "TESLA_CONNECTION_WAKE_ACKNOWLEDGED")))
            for (event in wakeEvents) assertTrue(FieldEvents.isAllowed(event))
            val secret = "PRIVATE-VIN-KEY-ROUTE-UUID-PACKET-PCM"
            assertFalse(FieldEvents.isAllowed("TESLA_CONNECTION_WAKE_$secret"))
            val current = FieldJson.encode(record(h, "TESLA_CONNECTION_STATE_CHANGED").copy(state = mapOf(
                FieldStateKeys.CONNECTION_STAGE to secret,
                FieldStateKeys.CONNECTION_REASON to secret,
                "origin" to secret, "packet" to secret, "route" to secret, "hash" to secret,
            )))
            assertFalse(current.contains(secret))
            assertFalse(current.contains("\"origin\""))
            assertFalse(current.contains("\"route\""))
            assertFalse(current.contains("\"hash\""))
        }
    }
    @Test fun actualInitialInvalidRetryLogsOnlyIssuedReadWithoutParkOrControlSuccess() {
        VehiclePeerHarness().use { h ->
            h.start(); h.replyBody(sleepStatus = 2); h.replyWake()
            h.replyDriveShift(Vehicle.ShiftState.newBuilder().setInvalid(Common.Void.getDefaultInstance()).build())
            assertTrue(h.parkStates.isEmpty())
            assertFalse(h.command())
            assertFalse("TESLA_CONNECTION_DRIVE_RETRY" in h.events)
            h.advance(1_999)
            assertFalse("TESLA_CONNECTION_DRIVE_RETRY" in h.events)
            h.advance(1)
            val event = h.events.single { it == "TESLA_CONNECTION_DRIVE_RETRY" }
            assertTrue(FieldEvents.isAllowed(event))
            assertEquals(2, h.driveRequests.size)
            assertEquals(listOf(true, false), h.driveRetransmitFlags)
            val json = FieldJson.encode(record(h, event))
            assertTrue(json.contains("\"event\":\"TESLA_CONNECTION_DRIVE_RETRY\""))
            assertFalse(json.contains("\"connectionParkFresh\":true"))
            assertFalse(json.contains("\"frunkCommandSent\":true"))
            assertFalse(json.contains("\"frunkCommandAcknowledged\":true"))
            assertFalse(json.contains("\"frunkOpenObserved\":true"))
            assertFalse(json.contains("\"route\""))
            assertFalse(json.contains("\"uuid\""))
            assertFalse(json.contains("\"packet\""))
            assertTrue(h.parkStates.isEmpty())
            assertFalse(h.command())
        }
    }

    @Test fun canceledReservedInitialInvalidReadDoesNotInventIssuedRetryLog() {
        VehiclePeerHarness().use { h ->
            h.start(); h.replyBody(sleepStatus = 2); h.replyWake()
            h.replyDriveShift(Vehicle.ShiftState.newBuilder().setInvalid(Common.Void.getDefaultInstance()).build())
            h.session.cancel(reason = "SERVICE_OFF")
            h.advance(30_000)
            assertTrue(h.session.sealed)
            assertEquals(1, h.driveRequests.size)
            assertFalse("TESLA_CONNECTION_DRIVE_RETRY" in h.events)
            assertTrue(h.parkStates.none { it.second == null })
            assertFalse(h.command())
        }
    }

    private fun record(h: VehiclePeerHarness, event: String) = FieldRecord(
        processId = "11111111-1111-4111-8111-111111111111", trialId = null,
        wallTimeMs = h.wallEpochMs, elapsedRealtimeMs = h.clock.elapsed,
        appVersion = "test", event = event, state = emptyMap(),
    )
}
