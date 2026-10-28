package com.heytesla.app.vehicle.uwb

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TeslaUwbPeerIntegrationTest(private val name: String, private val scenario: () -> Unit) {
    @Test fun productionConsumerAgainstIndependentPeer() = scenario()

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> = VehiclePeerScenarios.cases.map { arrayOf(it.first, it.second) }
    }
}
