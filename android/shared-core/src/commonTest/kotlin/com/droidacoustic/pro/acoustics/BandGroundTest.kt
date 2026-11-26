package com.droidacoustic.pro.acoustics

import kotlin.test.*
import kotlin.math.*

class BandGroundTest {
    private val air = Atmosphere()
    private val field = DirectField(air)
    private val omni = Radiator { _, _, _ -> 100f }
    private fun source(y: Float = 1f, delay: Double = 0.0, group: Int = 0, radiator: Radiator = omni) =
        SoundSource(group, 0f, y, 0f, 0f, 0f, radiator, delayS = delay)
    private val band = listOf(Bands.Segment(1000, 800.0, 1200.0))

    @Test fun sincBandPowerMatchesDenseIndependentQuadrature() {
        val delay = 0.0023
        val a = source()
        val one = field.bandPower(listOf(a), 10f, 1f, 0f, band)
        val actual = field.bandPower(listOf(a, source(delay = delay)), 10f, 1f, 0f, band)
        val n = 20000
        val ratio = (0 until n).sumOf { i ->
            val f = 800.0 + (i + 0.5) * 400.0 / n
            2 + 2 * cos(2 * PI * f * delay)
        } / n
        assertEquals(ratio, actual / one, 1e-7)
    }

    @Test fun unequalWidthSegmentsIntegrateByBandwidth() {
        val radiator = Radiator { hz, _, _ -> if (hz == 1000) 100f else 106f }
        val s = listOf(source(radiator = radiator))
        val lo = Bands.Segment(1000, 900.0, 1000.0)
        val hi = Bands.Segment(1250, 1000.0, 1300.0)
        val expected = (field.bandPower(s, 10f, 1f, 0f, listOf(lo)) +
            3 * field.bandPower(s, 10f, 1f, 0f, listOf(hi))) / 4
        assertEquals(expected, field.bandPower(s, 10f, 1f, 0f, listOf(lo, hi)), expected * 1e-10)
    }

    @Test fun hardGroundAddsSixDbAndAbsorbingGroundAddsNothing() {
        val s = listOf(source(y = 0f))
        val free = field.bandPower(s, 10f, 0f, 0f, band)
        val hard = field.bandPower(s, 10f, 0f, 0f, band, floor = FloorReflection { 0f })
        val soft = field.bandPower(s, 10f, 0f, 0f, band, floor = FloorReflection { 1f })
        assertEquals(4.0, hard / free, 1e-10)
        assertEquals(1.0, soft / free, 1e-10)
    }

    @Test fun independentSourcesRetainCoherentSelfReflections() {
        val a = source(group = 0)
        val b = source(group = 1)
        val ground = FloorReflection { 0.2f }
        val one = field.bandPower(listOf(a), 10f, 1f, 0f, band, floor = ground)
        val two = field.bandPower(listOf(a, b), 10f, 1f, 0f, band,
            coherent = false, floor = ground, correlateByGroup = true)
        assertEquals(2.0, two / one, 1e-10)
        val array = field.bandPower(listOf(a, source(group = 0)), 10f, 1f, 0f, band,
            coherent = false, floor = ground, correlateByGroup = true)
        assertEquals(4.0, array / one, 1e-10)
    }

    @Test fun floorPathUsesOwnDistanceAirLossAndDepartureDirection() {
        val y = 3f
        val direct = 4.0
        val reflected = sqrt(4.0 * 4.0 + 6.0 * 6.0)
        val alpha = air.absorptionDbPerMetre(1000.0)
        val a = 10.0.pow((100 - 20 * log10(direct) - alpha * direct) / 20)
        // Only downward rays get a -12 dB cabinet attenuation.
        val b = 10.0.pow((88 - 20 * log10(reflected) - alpha * reflected) / 20) * sqrt(0.5)
        val seg = listOf(Bands.Segment(1000, 999.0, 1001.0))
        val directional = Radiator { _, _, el -> if (el < -1) 88f else 100f }
        val got = field.bandPower(listOf(source(y, radiator = directional)), 4f, y, 0f,
            seg, floor = FloorReflection { 0.5f })
        val tau = (reflected - direct) / air.speedOfSound
        val sinc = sin(PI * 2 * tau) / (PI * 2 * tau)
        val expected = a*a + b*b + 2*a*b*cos(2*PI*1000*tau)*sinc
        assertEquals(expected, got, expected * 1e-6)
    }

    @Test fun fractionalBandEdgesAreNotRoundedToWholeThirds() {
        val segments = Bands.segments(1000, 0.5f)
        assertEquals(1000 / 2.0.pow(0.25), segments.first().f1, 1e-7)
        assertEquals(1000 * 2.0.pow(0.25), segments.last().f2, 1e-7)
    }
}
