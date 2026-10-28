package com.heytesla.app.vehicle.protocol

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** Pure consumer boundary tests: no Android services, registered keys, RF or vehicle traffic. */
class TeslaFiraProtocolTest {
    @Test fun consumesEveryRequestSessionAndPeerWithIndependentFiraIds() {
        val request = TeslaFiraProtocol.decode(firaTestRequest(listOf(
            FiraTestSession(2, peers = byteArrayOf(1, 2, 3, 4)),
            FiraTestSession(7, mode = 2, peers = byteArrayOf(5, 6)),
        ))) as TeslaFiraMessage.Request
        request.use {
            TeslaFiraNegotiation(capabilities()).use { negotiation ->
                val plans = negotiation.prepare(request)
                assertEquals(listOf(2L, 7L), plans.map { it.requestSessionId })
                assertEquals(listOf(2, 4), plans.map { it.frameworkConfigId })
                assertEquals(listOf(0, 2), plans.map { it.peerOffset })
                assertEquals(2, plans[0].peers.size)
                assertArrayEquals(byteArrayOf(1, 2), plans[0].peers[0])
                assertArrayEquals(byteArrayOf(3, 4), plans[0].peers[1])
                assertArrayEquals(byteArrayOf(5, 6), plans[1].peers[0])
                assertTrue(plans.all { it.firaSessionId != 0 })
                assertNotEquals(plans[0].firaSessionId, plans[1].firaSessionId)
                assertEquals(listOf(8, 16), plans.map { it.sts.size })
                assertEquals(listOf(3, 3), plans.map { it.updateRate })
                assertEquals(listOf(5, 5), plans.map { it.channel })
                plans.forEach { plan ->
                    assertTrue(plan.preamble in 9..12)
                    assertFalse(plan.localAddress.all { it == 0.toByte() })
                    assertFalse(plan.localAddress.all { it == 255.toByte() })
                    assertFalse(plan.peers.any { it.contentEquals(plan.localAddress) })
                }
                val selected = negotiation.selectStop(listOf(7), setOf(2L, 7L))
                assertEquals(listOf(plans[1]), selected)
                assertEquals("UNKNOWN_STOP_ENDPOINT", rejection {
                    negotiation.selectStop(listOf(3), setOf(2L, 7L))
                })
            }
        }
    }

    @Test fun uint32RequestIdsSurviveDecodePlanAndResponseIncludingOmittedDefaultZero() {
        val cases = listOf(0L, 11L, 12L, 0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL)
            .map { FiraTestSession(it) } + FiraTestSession(0L, omitRequestSessionId = true)
        for (session in cases) {
            val decoded = TeslaFiraProtocol.decode(firaTestRequest(listOf(session))) as TeslaFiraMessage.Request
            decoded.use { request ->
                assertEquals(session.requestSessionId, request.sessions.single().requestSessionId)
                val random = object : SecureRandom() {
                    override fun nextInt(): Int = -2
                    override fun nextInt(bound: Int): Int = 0
                    override fun nextBytes(bytes: ByteArray) { bytes.fill(51) }
                }
                TeslaFiraNegotiation(capabilities(), random).use { negotiation ->
                    val plan = negotiation.prepare(request).single()
                    assertEquals(session.requestSessionId, plan.requestSessionId)
                    assertEquals(-2, plan.firaSessionId)
                    val root = CodedInputStream.newInstance(TeslaFiraProtocol.response(listOf(plan), 1L))
                    assertEquals((59 shl 3) or 2, root.readTag())
                    val response = CodedInputStream.newInstance(root.readByteArray())
                    assertEquals(10, response.readTag())
                    val encodedSession = CodedInputStream.newInstance(response.readByteArray())
                    var responseId = 0L
                    var backendId = 0L
                    var hasRequestId = false
                    while (!encodedSession.isAtEnd) {
                        when (val tag = encodedSession.readTag()) {
                            8 -> {
                                hasRequestId = true
                                responseId = encodedSession.readUInt32().toLong() and 0xffff_ffffL
                            }
                            18 -> {
                                val data = CodedInputStream.newInstance(encodedSession.readByteArray())
                                while (!data.isAtEnd) {
                                    when (val dataTag = data.readTag()) {
                                        32 -> backendId = data.readUInt32().toLong() and 0xffff_ffffL
                                        else -> assertTrue(data.skipField(dataTag))
                                    }
                                }
                            }
                            else -> fail("Unexpected response session field $tag")
                        }
                    }
                    assertEquals(session.requestSessionId, responseId)
                    assertEquals(session.requestSessionId != 0L, hasRequestId)
                    assertEquals(0xffff_fffeL, backendId)
                    assertNotEquals(responseId, backendId)
                }
            }
        }
    }

    @Test fun duplicateUint32IdsAndOutOfRangeDirectRequestsCannotProducePlans() {
        for (id in listOf(0L, 11L, 12L, 0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL)) {
            val duplicate = TeslaFiraProtocol.decode(firaTestRequest(listOf(
                FiraTestSession(id), FiraTestSession(id, omitRequestSessionId = id == 0L),
            ))) as TeslaFiraMessage.Request
            duplicate.use { request -> TeslaFiraNegotiation(capabilities()).use { negotiation ->
                assertEquals("DUPLICATE_ENDPOINT", rejection { negotiation.prepare(request) })
            } }
        }
        for (id in listOf(-1L, 0x1_0000_0000L, Long.MAX_VALUE)) {
            TeslaFiraMessage.Request(listOf(TeslaFiraRequest(id, byteArrayOf(1, 2), 1, byteArrayOf(), 0))).use { request ->
                TeslaFiraNegotiation(capabilities()).use { negotiation ->
                    assertEquals("UNSUPPORTED_ENDPOINT", rejection { negotiation.prepare(request) })
                }
            }
        }
    }

    @Test fun stopSelectsExactKnownEnumValuesWithoutLowBitOrBackendIdMapping() {
        val largeId = 0x8000_0007L
        val ids = (0L..11L).toList() + largeId
        val decoded = TeslaFiraProtocol.decode(firaTestRequest(ids.map { FiraTestSession(it) }))
            as TeslaFiraMessage.Request
        decoded.use { request -> TeslaFiraNegotiation(capabilities()).use { negotiation ->
            val plans = negotiation.prepare(request)
            for (endpoint in 0..11) {
                assertEquals(listOf(plans[endpoint]), negotiation.selectStop(listOf(endpoint), ids.toSet()))
            }
            assertEquals(listOf(plans[0], plans[11]), negotiation.selectStop(listOf(11, 0), ids.toSet()))
            for (endpoints in listOf(emptyList(), listOf(-1), listOf(12), listOf(Int.MIN_VALUE), listOf(Int.MAX_VALUE))) {
                assertEquals("INVALID_STOP_ENDPOINT", rejection { negotiation.selectStop(endpoints, ids.toSet()) })
            }
            // No partial success if any value is unmatched: our fail-closed policy.
            assertEquals("UNKNOWN_STOP_ENDPOINT", rejection {
                negotiation.selectStop(listOf(0, 7), setOf(0L, largeId))
            })
            assertEquals("UNKNOWN_STOP_ENDPOINT", rejection { negotiation.selectStop(listOf(7), setOf(largeId)) })
        } }
    }

    @Test fun stoppedConversionKeepsOnlyExactlyRepresentableOriginalIds() {
        assertEquals(emptyList<Int>(), TeslaFiraProtocol.stoppedEndpoints(
            listOf(12L, 0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL)))
        val endpoints = TeslaFiraProtocol.stoppedEndpoints(listOf(0L, 12L, 11L, 0x8000_000bL, 0xffff_ffffL))
        assertEquals(listOf(0, 11), endpoints)
        val root = CodedInputStream.newInstance(TeslaFiraProtocol.stopped(endpoints))
        assertEquals((43 shl 3) or 2, root.readTag())
        val payload = CodedInputStream.newInstance(root.readByteArray())
        assertEquals(8, payload.readTag()); assertEquals(0, payload.readEnum())
        assertEquals(8, payload.readTag()); assertEquals(11, payload.readEnum())
        assertTrue(payload.isAtEnd)
        assertTrue(root.isAtEnd)
    }

    @Test fun mapsFrameworkModesAndNeverDropsPeersForUnicastFallback() {
        for ((mode, multicast, unicast) in listOf(Triple(1, 2, 1), Triple(2, 4, 3), Triple(3, 5, 0))) {
            TeslaFiraNegotiation(capabilities(configs = listOf(multicast))).use { n ->
                request(mode, byteArrayOf(1, 2, 3, 4)).use { r ->
                    assertEquals(multicast, n.prepare(r).single().frameworkConfigId)
                }
            }
            if (unicast != 0) {
                TeslaFiraNegotiation(capabilities(configs = listOf(unicast))).use { n ->
                    request(mode, byteArrayOf(1, 2)).use { r ->
                        assertEquals(unicast, n.prepare(r).single().frameworkConfigId)
                    }
                }
                TeslaFiraNegotiation(capabilities(configs = listOf(unicast))).use { n ->
                    request(mode, byteArrayOf(1, 2, 3, 4)).use { r ->
                        assertEquals("CONFIG_UNSUPPORTED", rejection { n.prepare(r) })
                    }
                }
            } else {
                TeslaFiraNegotiation(capabilities(configs = listOf(4))).use { n ->
                    request(mode, byteArrayOf(1, 2)).use { r ->
                        assertEquals("CONFIG_UNSUPPORTED", rejection { n.prepare(r) })
                    }
                }
            }
        }
    }

    @Test fun capabilityIntersectionUsesOnlySupportedChannelPreambleSlotRateAndMode() {
        val good = capabilities()
        for ((cap, reason) in listOf(
            good.copy(enabled = false) to "UWB_NOT_ENABLED",
            good.copy(distance = false) to "DISTANCE_UNSUPPORTED",
            good.copy(channels = listOf(6)) to "CHANNEL_INTERSECTION_EMPTY",
            good.copy(preambles = listOf(8, 13)) to "PREAMBLE_INTERSECTION_EMPTY",
            good.copy(slotDurations = listOf(1)) to "SLOT_DURATION_UNSUPPORTED",
            good.copy(configIds = listOf(6)) to "CONFIG_UNSUPPORTED",
            good.copy(updateRates = listOf(9)) to "UPDATE_RATE_UNSUPPORTED",
        )) {
            TeslaFiraNegotiation(cap).use { n -> assertEquals(reason, rejection { n.validateCapabilities() }) }
        }
        TeslaFiraNegotiation(good.copy(channels = listOf(9), preambles = listOf(12))).use { n ->
            request(1, byteArrayOf(1, 2)).use { r ->
                val plan = n.prepare(r).single()
                assertEquals(9, plan.channel)
                assertEquals(12, plan.preamble)
            }
        }
        TeslaFiraNegotiation(good.copy(updateRates = listOf(1))).use { n ->
            request(1, byteArrayOf(1, 2)).use { r ->
                assertEquals("UPDATE_RATE_UNSUPPORTED", rejection { n.prepare(r) })
            }
        }
    }

    @Test fun rejectsUnsupportedFirmwareAndInvalidPeerConfigurationBeforeProducingSessionData() {
        for ((session, reason) in listOf(
            FiraTestSession(1, peers = byteArrayOf()) to "INVALID_PEER_ADDRESSES",
            FiraTestSession(1, peers = byteArrayOf(1)) to "INVALID_PEER_ADDRESSES",
            FiraTestSession(1, peers = byteArrayOf(1, 2, 1, 2)) to "INVALID_PEER_ADDRESSES",
            FiraTestSession(1, peers = byteArrayOf(0, 0)) to "INVALID_PEER_ADDRESSES",
            FiraTestSession(1, mode = 4) to "STS_MODE_UNSUPPORTED",
            FiraTestSession(1, mode = 0) to "STS_MODE_UNSUPPORTED",
            FiraTestSession(1, vehicleSts = byteArrayOf(99)) to "VEHICLE_STS_UNSUPPORTED",
            FiraTestSession(1, rate = 99) to "UPDATE_RATE_UNSUPPORTED",
        )) {
            val decoded = TeslaFiraProtocol.decode(firaTestRequest(listOf(FiraTestSession(2), session)))
                as TeslaFiraMessage.Request
            decoded.use { r -> TeslaFiraNegotiation(capabilities()).use { n ->
                assertEquals(reason, rejection { n.prepare(r) })
            } }
        }
        val duplicate = TeslaFiraProtocol.decode(firaTestRequest(listOf(FiraTestSession(1), FiraTestSession(1))))
            as TeslaFiraMessage.Request
        duplicate.use { r -> TeslaFiraNegotiation(capabilities()).use { n ->
            assertEquals("DUPLICATE_ENDPOINT", rejection { n.prepare(r) })
        } }
    }

    @Test fun successfulResponseHasAllDataAndAbsentErrorAndKeyIdAndUsesUint32FiraId() {
        TeslaFiraNegotiation(capabilities()).use { n -> request(2, byteArrayOf(1, 2)).use { r ->
            val plan = n.prepare(r).single()
            val body = TeslaFiraProtocol.response(listOf(plan), 1_792_360_988L)
            val root = CodedInputStream.newInstance(body)
            assertEquals((59 shl 3) or 2, root.readTag())
            val response = CodedInputStream.newInstance(root.readByteArray())
            assertEquals(10, response.readTag())
            val session = CodedInputStream.newInstance(response.readByteArray())
            assertEquals(8, session.readTag()); assertEquals(1, session.readUInt32())
            assertEquals(18, session.readTag())
            val data = CodedInputStream.newInstance(session.readByteArray())
            assertEquals(10, data.readTag()); assertArrayEquals(plan.localAddress, data.readByteArray())
            assertEquals(16, data.readTag()); assertEquals(plan.preamble, data.readUInt32())
            assertEquals(24, data.readTag()); assertEquals(plan.channel, data.readUInt32())
            assertEquals(32, data.readTag()); assertEquals(plan.firaSessionId, data.readUInt32())
            assertEquals(42, data.readTag()); assertArrayEquals(plan.sts, data.readByteArray())
            assertTrue(data.isAtEnd)
            assertTrue(session.isAtEnd) // No errorCode=0.
            assertEquals(16, response.readTag()); assertEquals(1_792_360_988, response.readUInt32())
            assertTrue(response.isAtEnd) // No keyId/long-term key.
            assertTrue(root.isAtEnd)
            n.close()
            assertTrue(plan.sts.all { it == 0.toByte() })
            assertTrue(plan.localAddress.all { it == 0.toByte() })
            assertTrue(plan.peers.all { bytes -> bytes.all { it == 0.toByte() } })
        } }
    }

    @Test fun stopEnumCodecAcceptsPackedInputIncludingNoneAndEmitsUnpackedValues() {
        for (packed in listOf(false, true)) {
            val stop = TeslaFiraProtocol.decode(firaTestMessage {
                writeByteArray(48, firaTestMessage {
                    if (packed) writeByteArray(1, byteArrayOf(0, 2, 7, 11))
                    else { writeEnum(1, 0); writeEnum(1, 2); writeEnum(1, 7); writeEnum(1, 11) }
                })
            }) as TeslaFiraMessage.Stop
            assertEquals(listOf(0, 2, 7, 11), stop.endpoints)
        }
        val sent = CodedInputStream.newInstance(TeslaFiraProtocol.stopped(listOf(0, 2, 7, 11, 0)))
        assertEquals((43 shl 3) or 2, sent.readTag())
        val payload = CodedInputStream.newInstance(sent.readByteArray())
        assertEquals(8, payload.readTag()); assertEquals(0, payload.readEnum())
        assertEquals(8, payload.readTag()); assertEquals(2, payload.readEnum())
        assertEquals(8, payload.readTag()); assertEquals(7, payload.readEnum())
        assertEquals(8, payload.readTag()); assertEquals(11, payload.readEnum())
        assertTrue(payload.isAtEnd)
    }

    @Test fun unknownFieldsDoNotInventFieldFourSemanticsAndMalformedBodiesDoNotEscapeDetails() {
        val decoded = TeslaFiraProtocol.decode(firaTestRequest(listOf(FiraTestSession(1)), unknown = true))
            as TeslaFiraMessage.Request
        decoded.use { r -> TeslaFiraNegotiation(capabilities()).use { n -> assertEquals(1, n.prepare(r).size) } }
        assertEquals("INVALID_FIRA_MESSAGE", rejection {
            TeslaFiraProtocol.decode(byteArrayOf(0xb2.toByte(), 3, 127, 1))
        })
        assertNull(TeslaFiraProtocol.decode(byteArrayOf()))
    }

    @Test fun unknownRangingTypeDoesNotInventConfigMappingOrDiscardPeers() {
        for (rangingType in listOf(0, -1, Int.MAX_VALUE)) {
            val request = TeslaFiraProtocol.decode(firaTestRequest(listOf(
                FiraTestSession(1, rangingType = rangingType, peers = byteArrayOf(1, 2, 3, 4)),
            ))) as TeslaFiraMessage.Request
            request.use { r -> TeslaFiraNegotiation(capabilities()).use { n ->
                val plan = n.prepare(r).single()
                assertEquals(2, plan.frameworkConfigId)
                assertEquals(2, plan.peers.size)
            } }
        }
    }

    @Test fun closingRequestAndOneSessionDoesNotEraseAnotherActiveSessionsData() {
        val body = firaTestRequest(listOf(
            FiraTestSession(2, peers = byteArrayOf(1, 2)),
            FiraTestSession(0x8000_0000L, mode = 2, peers = byteArrayOf(3, 4)),
        ))
        val originalBody = body.copyOf()
        val request = TeslaFiraProtocol.decode(body) as TeslaFiraMessage.Request
        request.use { r -> TeslaFiraNegotiation(capabilities()).use { n ->
            val plans = n.prepare(r)
            r.close()
            assertTrue(r.sessions.all { session -> session.addresses.all { it == 0.toByte() } })
            assertArrayEquals(originalBody, body)
            assertArrayEquals(byteArrayOf(1, 2), plans[0].peers.single())
            assertArrayEquals(byteArrayOf(3, 4), plans[1].peers.single())

            val remainingAddress = plans[1].localAddress.copyOf()
            val remainingSts = plans[1].sts.copyOf()
            n.selectStop(listOf(2), setOf(2L, 0x8000_0000L)).single().close()
            assertTrue(plans[0].localAddress.all { it == 0.toByte() })
            assertTrue(plans[0].sts.all { it == 0.toByte() })
            assertTrue(plans[0].peers.single().all { it == 0.toByte() })
            assertArrayEquals(remainingAddress, plans[1].localAddress)
            assertArrayEquals(remainingSts, plans[1].sts)
            assertArrayEquals(byteArrayOf(3, 4), plans[1].peers.single())
            assertEquals("UNKNOWN_STOP_ENDPOINT", rejection { n.selectStop(listOf(2), setOf(0x8000_0000L)) })
            assertEquals("UNKNOWN_STOP_ENDPOINT", rejection { n.selectStop(listOf(0), setOf(0x8000_0000L)) })

            n.close()
            assertTrue(plans[1].localAddress.all { it == 0.toByte() })
            assertTrue(plans[1].sts.all { it == 0.toByte() })
            assertTrue(plans[1].peers.single().all { it == 0.toByte() })
            assertEquals("STOP_WITHOUT_SESSION", rejection { n.selectStop(listOf(0), setOf(0x8000_0000L)) })
        } }
    }

    private fun request(mode: Int, peers: ByteArray): TeslaFiraMessage.Request =
        TeslaFiraProtocol.decode(firaTestRequest(listOf(FiraTestSession(1, mode, peers)))) as TeslaFiraMessage.Request

    private fun capabilities(configs: List<Int> = listOf(1, 2, 3, 4, 5)) = TeslaFiraCapabilities(
        true, true, listOf(5, 9), listOf(9, 10, 11, 12), configs, listOf(2), listOf(1, 2, 3))

    private fun rejection(action: () -> Any?): String {
        try { action(); fail("Expected a bounded protocol rejection") }
        catch (failure: TeslaFiraException) { return failure.reason }
        throw AssertionError("Expected a protocol rejection")
    }
}

internal data class FiraTestSession(
    val requestSessionId: Long,
    val mode: Int = 1,
    val peers: ByteArray = byteArrayOf(1, 2),
    val vehicleSts: ByteArray = byteArrayOf(),
    val rangingType: Int = 1,
    val rate: Int = 0,
    val omitRequestSessionId: Boolean = false,
)

internal fun firaTestMessage(build: CodedOutputStream.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    val output = CodedOutputStream.newInstance(bytes)
    output.build(); output.flush()
    return bytes.toByteArray()
}

internal fun firaTestRequest(sessions: List<FiraTestSession>, unknown: Boolean = false): ByteArray = firaTestMessage {
    writeByteArray(54, firaTestMessage {
        sessions.forEach { session -> writeByteArray(1, firaTestMessage {
            if (!session.omitRequestSessionId) writeUInt32(1, session.requestSessionId.toInt())
            writeEnum(2, session.rangingType)
            writeByteArray(3, session.peers)
            if (unknown) writeByteArray(4, byteArrayOf(12, 34))
            writeEnum(7, session.mode)
            writeByteArray(8, session.vehicleSts)
            writeEnum(9, session.rate)
        }) }
    })
}
