package com.heytesla.app

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class VehicleVinTest {
    @Test fun normalizationMatchesTheExistingAdvertisementConsumerUnderTurkishLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val parsed = requireNotNull(VehicleVin.parse(" \t5yj3e1ea7kf000000\n"))
            val target = BleScanTarget.vehicleName(requireNotNull(TeslaBleAdvertisement.localName(parsed.snapshot())))
            try {
                assertTrue(target.matches(null, TeslaBleAdvertisement.localName("5YJ3E1EA7KF000000")))
                assertFalse(target.matches(null, TeslaBleAdvertisement.localName("5YJ3E1EA7KF000001")))
                assertTrue(parsed.masked == "*************0000")
                assertFalse(parsed.toString().contains("5YJ3E1EA7KF000000"))
            } finally { target.clear() }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun invalidInputsNeverProduceAStoredValueOrAConsumerTarget() {
        for (input in listOf("", "0000000000000000", "000000000000000000", "5YJ3E1EA7KF00000I",
            "5YJ3E1EA7KF00000O", "5YJ3E1EA7KF00000Q", "5YJ3E1EA7KF0000 0", "5YJ3E1EA7KF00000가")) {
            assertNull(VehicleVin.parse(input))
            assertNull(TeslaBleAdvertisement.localName(input))
        }
    }

    @Test fun unicodeCaseFoldingCannotTurnAnInvalidInputIntoAnAsciiVehicleTarget() {
        for (input in listOf("5YJ3E1EA7KF0000ß", "5YJ3E1EA7KF00000ſ")) {
            assertFalse(VehicleVin.isValid(input))
            assertNull(VehicleVin.parse(input))
        }
    }

}
