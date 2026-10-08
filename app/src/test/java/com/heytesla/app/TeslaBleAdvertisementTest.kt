package com.heytesla.app

import org.junit.Assert.*
import org.junit.Test

class TeslaBleAdvertisementTest {
    @Test fun officialNameUsesTheFirstEightSha1BytesWithLowercaseHex() {
        // SHA-1 vectors from independent shasum, synthetic inputs only.
        assertEquals("Sc1f6ea8523102b1bC", TeslaBleAdvertisement.localName("00000000000000000"))
        assertEquals("See13c959535a3b7dC", TeslaBleAdvertisement.localName("5YJ3E1EA7KF000000"))
        assertEquals("See13c959535a3b7dC", TeslaBleAdvertisement.localName(" \t5yj3e1ea7kf000000\n"))
    }

    @Test fun invalidVinCannotProduceAnAdvertisementTarget() {
        for (input in listOf("", "0000000000000000", "000000000000000000", "5YJ3E1EA7KF00000I",
            "5YJ3E1EA7KF00000O", "5YJ3E1EA7KF00000Q", "5YJ3E1EA7KF0000 0", "5YJ3E1EA7KF00000가")) {
            assertNull(TeslaBleAdvertisement.localName(input))
        }
    }

    @Test fun exactAdvertisedNameDoesNotRequireAnAssociationAddress() {
        val name = requireNotNull(TeslaBleAdvertisement.localName("00000000000000000"))
        val target = BleScanTarget.vehicleName(name)
        assertTrue(target.matches(null, name))
        assertTrue(target.matches("AA:BB:CC:DD:EE:01", name))
        assertTrue(target.matches("AA:BB:CC:DD:EE:02", name))
        assertFalse(target.matches("AA:BB:CC:DD:EE:01", "SffffffffffffffffC"))
        assertFalse(target.matches("AA:BB:CC:DD:EE:01", null))
        assertFalse(target.matches(null, name.lowercase()))
        assertFalse(target.matches(null, name + " "))
        assertFalse(target.toString().contains(name))
        target.clear()
        assertFalse(target.matches(null, name))
    }

    @Test fun associationAddressStillRequiresTheSelectedAddressAndIgnoresNames() {
        val address = "AA:BB:CC:DD:EE:01"
        val target = BleScanTarget.associationAddress(address)
        assertTrue(target.matches("aa:bb:cc:dd:ee:01", null))
        assertTrue(target.matches(address, "unrelated"))
        assertFalse(target.matches("AA:BB:CC:DD:EE:02", "same-name"))
        assertFalse(target.matches(null, "same-name"))
        assertFalse(target.toString().contains(address))
        target.clear()
        assertFalse(target.matches(address, null))
    }

    @Test fun unorderedBatchSelectsOnlyTheLatestExactMatch() {
        val name = requireNotNull(TeslaBleAdvertisement.localName("00000000000000000"))
        val target = BleScanTarget.vehicleName(name)
        val latestMatch = Sample("AA:BB:CC:DD:EE:02", name, 30)
        val samples = listOf(Sample("AA:BB:CC:DD:EE:01", name, 10),
            Sample("AA:BB:CC:DD:EE:01", "unrelated", 50), latestMatch,
            Sample("AA:BB:CC:DD:EE:03", name, 20), Sample("AA:BB:CC:DD:EE:01", null, 40))
        assertSame(latestMatch, target.latestMatching(samples, { it.address }, { it.name }, { it.at }))
        assertNull(target.latestMatching(samples.filter { it.name != name }, { it.address }, { it.name }, { it.at }))
        target.clear()
        assertNull(target.latestMatching(samples, { it.address }, { it.name }, { it.at }))
    }

    @Test fun handoffRequiresTheSameTokenAndOwnedAssociationAndIsOneShot() {
        val pending = BlePendingAdvertisedName()
        val name = requireNotNull(TeslaBleAdvertisement.localName("00000000000000000"))
        pending.arm("first-request", name)
        assertNull(pending.consume("stale-request", ownsAssociation = true))
        assertNull(pending.consume("first-request", ownsAssociation = false))
        assertFalse(pending.toString().contains(name))
        assertEquals(name, pending.consume("first-request", ownsAssociation = true))
        assertNull(pending.consume("first-request", ownsAssociation = true))
    }

    @Test fun cancellationDiscardsTheOldTargetAndStaleCleanupCannotEraseRearm() {
        val pending = BlePendingAdvertisedName()
        val first = requireNotNull(TeslaBleAdvertisement.localName("00000000000000000"))
        val second = requireNotNull(TeslaBleAdvertisement.localName("5YJ3E1EA7KF000000"))
        pending.arm("first", first)
        pending.clear("first")
        assertNull(pending.consume("first", ownsAssociation = true))
        pending.arm("second", second)
        pending.clear("first")
        assertNull(pending.consume("first", ownsAssociation = true))
        assertEquals(second, pending.consume("second", ownsAssociation = true))
        pending.arm("third", first)
        pending.clear()
        assertNull(pending.consume("third", ownsAssociation = true))
    }

    @Test(expected = IllegalStateException::class)
    fun anotherRequestCannotOverwriteAnUnconsumedTarget() {
        val pending = BlePendingAdvertisedName()
        pending.arm("first", "synthetic-first")
        pending.arm("second", "synthetic-second")
    }

    @Test fun nameDetectionRejectsEveryUnsafeCombinationRatherThanCorrectingIt() {
        val detection = BleFieldConfig.VEHICLE_NAME_DETECTION
        for (unsafe in listOf(detection.copy(observationOnly = false), detection.copy(supplementalScan = false),
            detection.copy(btAssist = true), detection.copy(backgroundConnect = true), detection.copy(retryEnabled = true))) {
            assertEquals("BLE_NAME_DETECTION_OPTIONS_INVALID", unsafe.safetyBlockedReason())
        }
    }

    private class Sample(val address: String?, val name: String?, val at: Long)
}
