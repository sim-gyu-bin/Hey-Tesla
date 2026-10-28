package com.heytesla.app.peer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.interfaces.ECPublicKey
import javax.crypto.AEADBadTagException

/**
 * Public expected answers, NOT a peer encrypt/decrypt roundtrip masquerading as an oracle.
 * All crypto literals below are published at this exact revision:
 * https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/pkg/protocol/protocol.md#test-keys
 * The public HVAC plaintext is used only as a primitive vector; OfficialTeslaPeer never accepts
 * that command. No private PEM/scalar or user VIN/key is embedded or loaded.
 */
class OfficialTeslaPeerKatTest {
    @Test fun publishedSessionInfoDerivedKey() {
        assertArrayEquals(hex("fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300"),
            OfficialPeerCrypto.sessionInfoKey(publicKey()))
    }

    @Test fun publishedSessionInfoHmacBindsOriginalBytes() {
        val info = hex("0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d971a104c463f9cc0d3d26906e982ed224adde6255a0a0000")
        val uuid = hex("1588d5a30eabc6f8fc9a951b11f6fd11")
        val expected = hex("996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002")
        assertArrayEquals(expected, OfficialPeerCrypto.sessionInfoTag(publicKey(), publicVin(), uuid, info))
        // Unknown field99=1 is included in HMAC, not discarded by a protobuf reserialization.
        val changed = OfficialPeerCrypto.sessionInfoTag(publicKey(), publicVin(), uuid, info + hex("980601"))
        assertFalse(java.security.MessageDigest.isEqual(expected, changed))
    }

    @Test fun publishedRequestMetadataIsExactCanonicalTlv() {
        assertArrayEquals(publicMetadata(), OfficialPeerCrypto.requestMetadata(3, publicVin(),
            hex("4c463f9cc0d3d26906e982ed224adde6"), 2655, 7, 2))
        assertArrayEquals(hex("000106021135594a333031323334353637383941424306101588d5a30eabc6f8fc9a951b11f6fd11ff"),
            OfficialPeerCrypto.tlv(0 to byteArrayOf(6), 2 to publicVin(),
                6 to hex("1588d5a30eabc6f8fc9a951b11f6fd11")))
    }

    @Test fun publishedRequestCiphertextAndTag() {
        val encrypted = OfficialPeerCrypto.aesGcm(true, publicKey(), hex("dbf79447fa156674dae1caed"),
            publicMetadata(), hex("120452020801"))
        assertArrayEquals(hex("38038e8c0f2ec228e0ff64991481db3a7bbc133696c5"), encrypted)
        // Decrypt the PUBLISHED bytes, not the output of the preceding encryption.
        assertArrayEquals(hex("120452020801"), OfficialPeerCrypto.aesGcm(false, publicKey(),
            hex("dbf79447fa156674dae1caed"), publicMetadata(),
            hex("38038e8c0f2ec228e0ff64991481db3a7bbc133696c5")))
        assertArrayEquals(hex("05c228e0ff64991481db3a7bbc133696c5"),
            OfficialPeerCrypto.requestHash(hex("c228e0ff64991481db3a7bbc133696c5")))
    }

    @Test fun publishedCiphertextCannotAuthenticateUnderChangedMetadataOrTag() {
        val changedMetadata = publicMetadata().also { it[3] = (it[3].toInt() xor 1).toByte() }
        expectBadTag {
            OfficialPeerCrypto.aesGcm(false, publicKey(), hex("dbf79447fa156674dae1caed"), changedMetadata,
                hex("38038e8c0f2ec228e0ff64991481db3a7bbc133696c5"))
        }
        expectBadTag {
            OfficialPeerCrypto.aesGcm(false, publicKey(), hex("dbf79447fa156674dae1caed"), publicMetadata(),
                hex("38038e8c0f2ec228e0ff64991481db3a7bbc133696c4"))
        }
    }

    @Test fun responseMetadataAlwaysIncludesZeroFlagsAndFaultAndExactRequestTag() {
        // Literal wire oracle from protocol.md#response-metadata; not produced by another encoder.
        assertArrayEquals(hex("000109010102021135594a3330313233343536373839414243050480000000070400000000081105c228e0ff64991481db3a7bbc133696c5090400000000ff"),
            OfficialPeerCrypto.responseMetadata(2, publicVin(),
                0x8000_0000L, 0, hex("05c228e0ff64991481db3a7bbc133696c5"), 0))
    }

    @Test fun uint32IsBigEndianWithoutSignExtension() {
        for ((number, expected) in listOf(0L to "00000000", 11L to "0000000b", 12L to "0000000c",
            0x7fff_ffffL to "7fffffff", 0x8000_0000L to "80000000", 0xffff_ffffL to "ffffffff")) {
            assertArrayEquals(hex(expected), OfficialPeerCrypto.uint32(number))
        }
    }

    @Test fun publishedPublicPointHasExactCoordinatesAndRejectsInvalidPoints() {
        val wire = hex("04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97")
        val key = OfficialPeerCrypto.decodePublic(wire) as ECPublicKey
        assertEquals(java.math.BigInteger("c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3", 16), key.w.affineX)
        assertEquals(java.math.BigInteger("a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97", 16), key.w.affineY)
        assertArrayEquals(wire, OfficialPeerCrypto.encodePublic(key))
        for (invalid in listOf(wire.copyOf(64), wire.copyOf(66), ByteArray(65).also { it[0] = 4 },
            ByteArray(65) { 255.toByte() }.also { it[0] = 4 })) {
            expectInvalid { OfficialPeerCrypto.decodePublic(invalid) }
        }
    }

    @Test fun independentBe16FramerUsesLiteralLengthsAcrossEverySplit() {
        val wire = hex("0002aabb0001010000")
        for (split in 0..wire.size) PeerBe16Framer().use { decoder ->
            val received = decoder.append(wire.copyOfRange(0, split)) + decoder.append(wire.copyOfRange(split, wire.size))
            assertEquals(3, received.size)
            assertArrayEquals(hex("aabb"), received[0])
            assertArrayEquals(hex("01"), received[1])
            assertArrayEquals(byteArrayOf(), received[2])
        }
        assertArrayEquals(hex("0002aabb"), OfficialTeslaPeer.frame(hex("aabb")))
        PeerBe16Framer().use { decoder ->
            val received = wire.flatMap { decoder.append(byteArrayOf(it)) }
            assertEquals(3, received.size)
            assertArrayEquals(hex("aabb"), received[0])
        }
    }

    @Test fun privateRequestWireHasExactUint32AndMultiplePeerAddresses() {
        // REA u0/q0 wire ledger: outer54, repeated1, uint32 ID1, bytes addresses3, STS mode7.
        assertArrayEquals(hex("b203100a0e08ffffffff0f1a041234abcd3801"), FiraWire.request(listOf(
            FiraWire.SessionRequest(0xffff_ffffL, hex("1234abcd")))))
        for ((id, literal) in listOf(
            0L to "b2030a0a081a041234abcd3801",
            11L to "b2030c0a0a080b1a041234abcd3801",
            12L to "b2030c0a0a080c1a041234abcd3801",
            0x8000_0000L to "b203100a0e0880808080081a041234abcd3801",
            0xffff_ffffL to "b203100a0e08ffffffff0f1a041234abcd3801")) {
            assertArrayEquals(hex(literal), FiraWire.request(listOf(FiraWire.SessionRequest(id, hex("1234abcd")))))
            val parsed = FiraWire.decodeVehiclePayload(hex(literal)) as FiraWire.VehiclePayload.Request
            parsed.use {
                assertEquals(id, it.sessions.single().requestSessionId)
                assertArrayEquals(hex("1234abcd"), it.sessions.single().addresses)
            }
        }
        assertArrayEquals(hex("b2030c0a0a08001a041234abcd3801"),
            FiraWire.request(listOf(FiraWire.SessionRequest(0, hex("1234abcd"), explicitZeroId = true))))
    }

    @Test fun privateResponseDecodesLiteralHighBitIdsAndOptionalSuccessFields() {
        // r0/v0/t0 literal: request 0xffffffff, backend 0x80000000, static STS8, epochSeconds=1.
        val literal = hex("da03240a2008ffffffff0f12180a021122100918052080808080082a0801020304050607081001")
        val response = FiraWire.decodeClientPayload(literal) as FiraWire.ClientPayload.Response
        response.use {
            assertEquals(1L, it.epochSeconds)
            assertFalse(it.keyIdPresent)
            val session = it.sessions.single()
            assertEquals(0xffff_ffffL, session.requestSessionId)
            assertEquals(null, session.errorCode)
            val data = session.data!!
            assertEquals(0x8000_0000L, data.firaSessionId)
            assertEquals(9L, data.preamble)
            assertEquals(5L, data.channel)
            assertArrayEquals(hex("1122"), data.localAddress)
            assertArrayEquals(hex("0102030405060708"), data.sts)
            assertTrue(data.responderAddresses.isEmpty())
        }
    }

    @Test fun privateCapabilitiesStopAndStoppedAreDifferentMessages() {
        assertArrayEquals(hex("d20300"), FiraWire.capabilities())
        assertArrayEquals(hex("8203040800080b"), FiraWire.stop(listOf(0, 11)))
        assertArrayEquals(hex("8203040a02000b"), FiraWire.stop(listOf(0, 11), packed = true))
        val stop = FiraWire.decodeVehiclePayload(hex("8203040800080b")) as FiraWire.VehiclePayload.Stop
        assertEquals(listOf(0, 11), stop.endpoints)
        val stopped = FiraWire.decodeClientPayload(hex("da02040800080b")) as FiraWire.ClientPayload.Stopped
        assertEquals(listOf(0, 11), stopped.endpoints)
        val packed = FiraWire.decodeClientPayload(hex("da02040a02000b")) as FiraWire.ClientPayload.Stopped
        assertEquals(listOf(0, 11), packed.endpoints)
        expectInvalid { FiraWire.decodeClientPayload(hex("da0202080c")) }
        // Published actuator command and whitelist query are never part of this UWB peer's allow-list.
        expectInvalid { FiraWire.decodeClientPayload(hex("120452020801")) }
        expectInvalid { FiraWire.decodeClientPayload(hex("0a020805")) }
    }

    private fun publicKey(): ByteArray = hex("1b2fce19967b79db696f909cff89ea9a")
    private fun publicVin(): ByteArray = "5YJ30123456789ABC".toByteArray(Charsets.US_ASCII)
    private fun publicMetadata(): ByteArray = hex("000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6040400000a5f050400000007070400000002ff")
    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    private fun expectBadTag(block: () -> Unit) {
        try { block(); fail("Changed public vector must fail authentication") }
        catch (_: AEADBadTagException) { }
    }
    private fun expectInvalid(block: () -> Unit) {
        try { block(); fail("Invalid wire input must be rejected") }
        catch (_: IllegalArgumentException) { }
    }
}
