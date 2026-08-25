package com.droidacoustic.pro.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Air absorption, as currently implemented: an eight-entry table of dB/metre with
 * linear temperature and humidity fudge factors.
 *
 * This is NOT ISO 9613-1 and these tests do not claim it is. They pin the present
 * behaviour so that replacing it with the closed-form - a planned change - is a
 * deliberate act with a visible diff, rather than a silent drift in every
 * predicted level.
 */
class AtmosphericLossTest {

    private val vm = SceneViewModel()

    /** The reference condition: both fudge factors resolve to 1.0. */
    private fun atRef(distanceM: Float, bandHz: Int) =
        vm.atmosphericLossDb(distanceM, bandHz, temperatureC = 20f, humidityPct = 50f)

    @Test
    fun `at the reference condition loss is the tabulated rate times distance`() {
        assertEquals(0.20f, atRef(100f, 1000), 1e-4f)   // 0.0020 dB/m
        assertEquals(3.50f, atRef(100f, 8000), 1e-4f)   // 0.0350 dB/m
        assertEquals(0.02f, atRef(100f, 63), 1e-4f)     // 0.0002 dB/m
    }

    @Test
    fun `loss rises monotonically with frequency`() {
        val bands = listOf(63, 125, 250, 500, 1000, 2000, 4000, 8000)
        val losses = bands.map { atRef(50f, it) }
        losses.zipWithNext().forEach { (lo, hi) ->
            assertTrue("expected $hi > $lo across $bands", hi > lo)
        }
    }

    @Test
    fun `an unlisted band falls back to the 1 kHz rate`() {
        assertEquals(atRef(100f, 1000), atRef(100f, 3150), 1e-6f)
    }

    @Test
    fun `loss is proportional to distance`() {
        assertEquals(2f * atRef(50f, 4000), atRef(100f, 4000), 1e-4f)
    }

    @Test
    fun `zero distance costs nothing and negative distance is clamped`() {
        assertEquals(0f, atRef(0f, 8000), 0f)
        assertEquals(0f, atRef(-25f, 8000), 0f)
    }

    @Test
    fun `the temperature and humidity scale is clamped to between half and double`() {
        // Extreme inputs must not invert the sign or run away.
        val cold = vm.atmosphericLossDb(100f, 4000, temperatureC = -100f, humidityPct = 0f)
        val hot = vm.atmosphericLossDb(100f, 4000, temperatureC = 200f, humidityPct = 100f)
        val ref = atRef(100f, 4000)
        assertEquals(2.0f * ref, cold, 1e-3f)
        assertEquals(0.5f * ref, hot, 1e-3f)
    }
}
