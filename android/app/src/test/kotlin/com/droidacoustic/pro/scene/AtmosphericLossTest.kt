package com.droidacoustic.pro.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphericLossTest {
    private val vm = SceneViewModel()
    private fun loss(distance: Float, hz: Int) = vm.atmosphericLossDb(distance, hz, 20f, 50f)

    @Test fun referenceAtmosphereReachesProduction() {
        assertEquals(6.31746f, loss(60f, 8000), 0.002f)
        // ISO 9613-2 table: 5.0 dB/km at 20 C and 70% RH.
        assertEquals(0.50f, vm.atmosphericLossDb(100f, 1000, 20f, 70f), 0.02f)
    }
    @Test fun arbitraryBandsHaveTheirOwnAbsorption() {
        assertTrue(loss(100f, 3150) > loss(100f, 1000))
    }
    @Test fun distanceScalingAndZero() {
        assertEquals(2f * loss(50f, 4000), loss(100f, 4000), 0.0001f)
        assertEquals(0f, loss(-1f, 8000), 0f)
    }
}
