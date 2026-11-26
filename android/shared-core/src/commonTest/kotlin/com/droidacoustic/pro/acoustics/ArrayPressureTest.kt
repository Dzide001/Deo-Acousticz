package com.droidacoustic.pro.acoustics

import kotlin.test.*
import kotlin.math.*

class ArrayPressureTest {
    private val air = Atmosphere()
    private val omni = Radiator { _, _, _ -> 100f }
    private fun emit(x: Float, y: Float, delay: Double = 0.0) =
        SoundSource(0, x, y, 0f, 0f, 0f, omni, delayS = delay)

    @Test fun actualElementDistancesAndComplexGrouping() {
        val sources = listOf(emit(0f, 4f), emit(0.25f, 5f), emit(-1f, 2f))
        val result = tonePressure(sources, 3f, 0f, 0f, 1000, air)
        var expected = ComplexPressure.ZERO
        sources.forEach { source ->
            val d = sqrt((3.0 - source.x).pow(2) + source.y.toDouble().pow(2))
            val level = 100.0 - 20 * log10(d) - air.absorptionDbPerMetre(1000.0) * d
            expected += ComplexPressure.arrival(level.toFloat(), d / air.speedOfSound, 1000.0)
        }
        assertEquals(expected.real, result.real, 0.001)
        assertEquals(expected.imaginary, result.imaginary, 0.001)
        val grouped = tonePressure(sources.take(2), 3f, 0f, 0f, 1000, air) +
            tonePressure(sources.drop(2), 3f, 0f, 0f, 1000, air)
        assertEquals(result.real, grouped.real, 0.001)
        assertEquals(result.imaginary, grouped.imaginary, 0.001)
    }

    @Test fun halfPeriodDelayCancelsAndFullPeriodRestores() {
        val a = tonePressure(listOf(emit(0f, 4f)), 3f, 0f, 0f, 1000, air)
        val half = tonePressure(listOf(emit(0f, 4f, 0.0005)), 3f, 0f, 0f, 1000, air)
        val full = tonePressure(listOf(emit(0f, 4f, 0.001)), 3f, 0f, 0f, 1000, air)
        assertTrue((a + half).power < a.power * 1e-12)
        assertEquals(4.0, (a + full).power / a.power, 1e-10)
    }

    @Test fun steeringDownFocusesBelowWhileCabinetsStayFixed() {
        val poses = ArrayGeometry.poses(8, 0.3f, 0f, emptyList())
        fun sources(steer: Float) = ArrayGeometry.sources(poses, 0, 0f, 0f, 0f,
            0f, steer, air.speedOfSound, omni)
        val straight = sources(0f)
        val steered = sources(20f)
        assertEquals(straight.map { Triple(it.x, it.y, it.z) }, steered.map { Triple(it.x, it.y, it.z) })
        val x = (1000 * cos(20 * PI / 180)).toFloat()
        val y = (-1000 * sin(20 * PI / 180)).toFloat()
        val correct = tonePressure(steered, x, y, 0f, 1000, air)
        val wrong = tonePressure(sources(-20f), x, y, 0f, 1000, air)
        assertTrue(correct.power > wrong.power * 10)
    }

    @Test fun curvedSteeringUsesSpatialProjectionAndDoesNotMutatePoses() {
        val poses = ArrayGeometry.poses(6, 0.3f, 20f, List(5) { 7f })
        val before = poses.toList()
        val delays = ArrayGeometry.steeringDelays(poses, 15f, air.speedOfSound)
        val df = cos(35 * PI / 180) - cos(20 * PI / 180)
        val du = -sin(35 * PI / 180) + sin(20 * PI / 180)
        for (i in 1 until poses.size) {
            val expected = ((poses[i].forwardM - poses[0].forwardM) * df +
                (poses[i].upM - poses[0].upM) * du) / air.speedOfSound
            assertEquals(expected, delays[i] - delays[0], 1e-9)
        }
        assertEquals(before, poses)
        ArrayGeometry.steeringDelays(poses, 0f, air.speedOfSound).forEach { assertEquals(0.0, it) }
    }
}
