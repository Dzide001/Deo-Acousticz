package com.droidacoustic.pro.acoustics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Air absorption against the standard it claims to implement.
 *
 * The reference is ISO 9613-2 Table 2, which tabulates the ISO 9613-1 model at
 * the octave centres for six atmospheres. Those 48 numbers were not produced by
 * this code, which is what makes them worth testing against.
 */
class AtmosphereTest {

    private val octaves = listOf(63, 125, 250, 500, 1000, 2000, 4000, 8000)

    /** dB per kilometre, from ISO 9613-2 Table 2. */
    private val table = mapOf(
        (10f to 70f) to listOf(0.1, 0.4, 1.0, 1.9, 3.7, 9.7, 32.8, 117.0),
        (20f to 70f) to listOf(0.1, 0.3, 1.1, 2.8, 5.0, 9.0, 22.9, 76.6),
        (30f to 70f) to listOf(0.1, 0.3, 1.0, 3.1, 7.4, 12.7, 23.1, 59.3),
        (15f to 20f) to listOf(0.3, 0.6, 1.2, 2.7, 8.2, 28.2, 88.8, 202.0),
        (15f to 50f) to listOf(0.1, 0.5, 1.2, 2.2, 4.2, 10.8, 36.2, 129.0),
        (15f to 80f) to listOf(0.1, 0.3, 1.1, 2.4, 4.1, 8.3, 23.7, 82.8)
    )

    @Test
    fun `absorption matches the ISO table in all six atmospheres`() {
        table.forEach { (weather, row) ->
            val air = Atmosphere(weather.first, weather.second)
            octaves.zip(row).forEach { (hz, expected) ->
                val got = air.absorptionDbPerMetre(hz.toDouble()) * 1000.0
                // The table is rounded to one decimal and uses exact mid-band
                // frequencies, so allow for both.
                val tolerance = maxOf(0.06, expected * 0.03)
                assertEquals("${weather.first} C, ${weather.second} %, $hz Hz", expected, got, tolerance)
            }
        }
    }

    @Test
    fun `dry air absorbs far more of the top end than humid air`() {
        // The reason this cannot be a fixed curve scaled by a factor: at 15 C,
        // going from 80 % to 20 % humidity more than triples the loss at 4 kHz.
        val dry = Atmosphere(15f, 20f).absorptionDbPerMetre(4000.0)
        val humid = Atmosphere(15f, 80f).absorptionDbPerMetre(4000.0)
        assertTrue("dry $dry vs humid $humid", dry > humid * 3.0)
    }

    @Test
    fun `humidity does not act the same way at every frequency`() {
        // At 1 kHz the same change of weather roughly doubles the loss rather
        // than tripling it. A single humidity multiplier cannot be right at both.
        val ratio1k = Atmosphere(15f, 20f).absorptionDbPerMetre(1000.0) /
            Atmosphere(15f, 80f).absorptionDbPerMetre(1000.0)
        val ratio4k = Atmosphere(15f, 20f).absorptionDbPerMetre(4000.0) /
            Atmosphere(15f, 80f).absorptionDbPerMetre(4000.0)
        assertTrue("1 kHz x$ratio1k, 4 kHz x$ratio4k", ratio4k > ratio1k * 1.5)
    }

    @Test
    fun `loss rises with frequency in every atmosphere`() {
        listOf(0f to 30f, 20f to 50f, 35f to 90f).forEach { (t, rh) ->
            val air = Atmosphere(t, rh)
            var previous = 0.0
            Bands.THIRD_OCTAVE_HZ.forEach { hz ->
                val a = air.absorptionDbPerMetre(hz.toDouble())
                assertTrue("$t C $rh % at $hz Hz", a > previous)
                previous = a
            }
        }
    }

    @Test
    fun `a long throw loses several decibels of top end to the air alone`() {
        // 60 m at 8 kHz in ordinary conditions. The table this replaced gave 2.
        val loss = Atmosphere(20f, 50f).absorptionDbPerMetre(8000.0) * 60.0
        assertEquals(6.3, loss, 0.4)
    }

    @Test
    fun `speed of sound follows temperature`() {
        assertEquals(331.3, Atmosphere(0f).speedOfSound, 0.05)
        assertEquals(343.2, Atmosphere(20f).speedOfSound, 0.2)
        assertTrue(Atmosphere(35f).speedOfSound > Atmosphere(5f).speedOfSound)
    }
}
