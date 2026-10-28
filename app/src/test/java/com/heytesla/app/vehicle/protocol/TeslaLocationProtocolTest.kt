package com.heytesla.app.vehicle.protocol

import com.heytesla.app.peer.OfficialTeslaPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaLocationProtocolTest {
    @Test fun locationQueryIsTheOnlyInfotainmentPayloadAcceptedByTheIndependentPeer() {
        for (query in listOf(TeslaBleQuery.LOCATION_STATE, TeslaBleQuery.DRIVE_STATE)) {
            TeslaBleProtocolTest.VehicleFixture(query).use { fixture ->
                OfficialTeslaPeer().use { peer ->
                    val hello = exchange(peer, fixture.conversation.start(false), fixture.clock) as OfficialTeslaPeer.Event.Handshake
                    assertEquals(3, hello.domain)
                    val framedReply = checkNotNull(hello.framedReply)
                    val reply = framedReply.copyOfRange(2, framedReply.size)
                    val request = try { fixture.conversation.receive(reply) }
                    finally { reply.fill(0); framedReply.fill(0) }
                    when (val accepted = exchange(peer, request, fixture.clock)) {
                        is OfficialTeslaPeer.Event.ClientRequest -> {
                            assertEquals(TeslaBleQuery.LOCATION_STATE, query)
                            assertTrue(accepted.request.locationQuery)
                            assertEquals(3, accepted.request.domain)
                            assertTrue(accepted.request.fira == null && accepted.request.deviceInfo == null)
                        }
                        is OfficialTeslaPeer.Event.Rejected -> {
                            assertEquals(TeslaBleQuery.DRIVE_STATE, query)
                            assertEquals("ONLY_LOCATION_READ_ALLOWED", accepted.reason)
                        }
                        else -> error("UNEXPECTED_PEER_EVENT")
                    }
                }
            }
        }
    }

    @Test fun locationCannotRegisterControlOrBecomeAnUwbConversation() {
        TeslaBleProtocolTest.VehicleFixture().use { fixture ->
            for (purpose in listOf(TeslaBlePurpose.FRUNK_OPEN, TeslaBlePurpose.UWB)) {
                TeslaBleConversation(fixture.client, fixture.vin, query = TeslaBleQuery.LOCATION_STATE,
                    purpose = purpose, now = { fixture.clock }).use { conversation ->
                    val result = conversation.start(false)
                    assertTrue(result is TeslaBleStep.Failed)
                    assertEquals("LOCATION_READ_ONLY_REQUIRED", (result as TeslaBleStep.Failed).reason)
                }
            }
            TeslaBleConversation(fixture.client, fixture.vin, query = TeslaBleQuery.LOCATION_STATE,
                now = { fixture.clock }).use { conversation ->
                assertTrue(conversation.start(true) is TeslaBleStep.Failed)
                assertFalse(conversation.registrationReported)
            }
        }
    }

    private fun exchange(peer: OfficialTeslaPeer, step: TeslaBleStep, at: Long): OfficialTeslaPeer.Event {
        check(step is TeslaBleStep.Send) { "EXPECTED_PROTOCOL_SEND" }
        val framed = OfficialTeslaPeer.frame(step.payload)
        return try { peer.consumeClientFramedBytes(framed, at).single() }
        finally { framed.fill(0); step.payload.fill(0) }
    }
}
