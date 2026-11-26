package com.droidacoustic.pro.acoustics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandsTest {

    @Test
    fun `nominal centres map to their own index and back`() {
        Bands.THIRD_OCTAVE_HZ.forEachIndexed { i, hz ->
            assertEquals("$hz Hz", i, Bands.indexOf(hz))
        }
        assertEquals(1000.0, Bands.exactHz(16), 1e-9)
        assertEquals(2000.0, Bands.exactHz(19), 1e-6)
    }

    @Test
    fun `thirty-one and a half hertz is found whichever way it is written`() {
        assertTrue(Bands.sameBand(32, 31))
        assertEquals(Bands.indexOf(31.5), Bands.indexOf(32))
    }

    @Test
    fun `an octave is three third-octaves, each with its own data`() {
        val s = Bands.segments(1000, 1f)
        assertEquals(listOf(800, 1000, 1250), s.map { it.dataHz })
        // And they tile the octave without gap or overlap.
        assertEquals(s[0].f2, s[1].f1, 1e-6)
        assertEquals(s[1].f2, s[2].f1, 1e-6)
        assertEquals(2.0, s.last().f2 / s.first().f1, 1e-6)
    }

    @Test
    fun `a third-octave is one segment a third of an octave wide`() {
        val s = Bands.segments(2000, 1f / 3f).single()
        assertEquals(2000, s.dataHz)
        assertEquals(Math.pow(2.0, 1.0 / 3.0), s.f2 / s.f1, 1e-6)
    }

    @Test
    fun `narrower bands stay centred and read the same data`() {
        val s = Bands.segments(4000, 1f / 12f).single()
        assertEquals(4000, s.dataHz)
        assertEquals(Math.pow(2.0, 1.0 / 12.0), s.f2 / s.f1, 1e-6)
        assertTrue(s.f1 < 4000 && s.f2 > 4000)
    }

    @Test
    fun `an octave at the edge of the range drops the part that does not exist`() {
        assertEquals(listOf(25, 32), Bands.segments(25, 1f).map { it.dataHz })
        assertEquals(listOf(16000, 20000), Bands.segments(20000, 1f).map { it.dataHz })
    }
}
