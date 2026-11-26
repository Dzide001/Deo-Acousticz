package com.droidacoustic.pro.acoustics

import kotlin.math.*
import kotlin.test.*

class SpeakerDataTest {
    private val flat: (Float, Float) -> Float = { _, _ -> 0f }
    private fun measured(levels: Map<Int, Float>, grid: MeasurementGrid = MeasurementGrid.THIRD_OCTAVE) =
        MeasuredRadiator(levels, levels.mapValues { flat }, grid)

    @Test fun voltageAndPowerReferencesHaveDifferentScaling() {
        val voltage = ReferenceCalibration(1.0, DriveUnit.VOLTS_RMS, 2.83)
        val power = ReferenceCalibration(1.0, DriveUnit.WATTS, 1.0)
        assertEquals(6.0206f, voltage.gainForDrive(DriveUnit.VOLTS_RMS, 5.66)!!, 1e-4f)
        assertEquals(3.0103f, power.gainForDrive(DriveUnit.WATTS, 2.0)!!, 1e-4f)
        assertNull(voltage.gainForDrive(DriveUnit.WATTS, 2.0))
        assertNull(ReferenceCalibration().gainForDrive(DriveUnit.VOLTS_RMS, 2.83))
    }
    @Test fun referenceDistanceNormalizesOnce() {
        val r = MeasuredRadiator(mapOf(1000 to 80f), mapOf(1000 to flat),
            calibration = ReferenceCalibration(10.0, DriveUnit.VOLTS_RMS, 2.83))
        assertEquals(100f, r.levelDb(1000, 0f, 0f), 1e-5f)
        val s = SoundSource(0, 0f, 0f, 0f, 0f, 0f, r)
        val air = Atmosphere()
        val band = listOf(Bands.Segment(1000, 999.0, 1001.0))
        val expected = 80 - air.absorptionDbPerMetre(1000.0) * 10
        assertEquals(expected.toFloat(), DirectField(air).bandLevelDb(listOf(s), 10f, 0f, 0f, band), 1e-4f)
    }
    @Test fun invalidCalibrationIsRejected() {
        assertFailsWith<IllegalArgumentException> { ReferenceCalibration(distanceM = 0.0) }
        assertFailsWith<IllegalArgumentException> { ReferenceCalibration(driveUnit = DriveUnit.WATTS) }
        assertFailsWith<IllegalArgumentException> { ReferenceCalibration(driveValue = 1.0) }
        assertFailsWith<IllegalArgumentException> { ReferenceCalibration(distanceM = Double.NaN) }
    }
    @Test fun interpolationIsLogFrequencyWithinDeclaredAdjacentBands() {
        val r = measured(mapOf(1000 to 90f, 2000 to 100f), MeasurementGrid.OCTAVE)
        assertEquals((90 + 10 * log2(1250.0 / 1000)).toFloat(), r.levelDb(1250, 0f, 0f), 1e-5f)
        assertTrue(r.levelDb(800, 0f, 0f).isNaN())
        assertTrue(r.levelDb(2500, 0f, 0f).isNaN())
    }
    @Test fun holesAreNotInterpolatedOrFilledWithGenericLevels() {
        val r = measured(mapOf(1000 to 90f, 1600 to 100f))
        assertTrue(r.levelDb(1250, 0f, 0f).isNaN())
        assertEquals(90f, r.levelDb(1000, 0f, 0f))
        val invalid = measured(mapOf(1000 to Float.NaN, 1250 to 90f))
        assertTrue(invalid.levelDb(1000, 0f, 0f).isNaN())
        assertTrue(invalid.levelDb(1100, 0f, 0f).isNaN())
    }
    @Test fun missingSensitivityAndDirectivityRemainDistinctFromZero() {
        assertTrue(MeasuredRadiator(emptyMap(), mapOf(1000 to flat)).levelDb(1000, 0f, 0f).isNaN())
        assertTrue(MeasuredRadiator(mapOf(1000 to 100f), emptyMap()).levelDb(1000, 0f, 0f).isNaN())
        assertEquals(100f, MeasuredRadiator(mapOf(1000 to 100f), emptyMap(),
            useDirectivity = false).levelDb(1000, 0f, 0f))
    }
    @Test fun incompleteSourceInvalidatesTotalIncludingMixedBandsAndFloor() {
        val known = SoundSource(1, 0f, 1f, 0f, 0f, 0f, Radiator { _, _, _ -> 100f })
        val missing = SoundSource(2, 0f, 1f, 0f, 0f, 0f, measured(mapOf(1000 to 90f)))
        val result = DirectField().predictBand(listOf(known, missing), 10f, 1f, 0f,
            Bands.segments(1000, 1f), floor = FloorReflection { 0f })
        assertNull(result.levelDb)
        assertEquals(setOf(MissingBand(2, 800), MissingBand(2, 1250)), result.missingBands)
        assertTrue(tonePressure(listOf(known, missing), 10f, 1f, 0f, 800, Atmosphere()).levelDb.isNaN())
    }
    @Test fun fullyAbsorbedImageDoesNotRequireUnmeasuredDepartureDirection() {
        val r = Radiator { _, _, el -> if (el < 0f) Float.NaN else 100f }
        val s = SoundSource(0, 0f, 1f, 0f, 0f, 0f, r)
        val field = DirectField()
        val band = Bands.segments(1000, 1f / 3)
        val free = field.predictBand(listOf(s), 10f, 1f, 0f, band)
        assertEquals(free, field.predictBand(listOf(s), 10f, 1f, 0f, band, floor = FloorReflection { 1f }))
        assertNull(field.predictBand(listOf(s), 10f, 1f, 0f, band, floor = FloorReflection { 0f }).levelDb)
    }
    @Test fun trueCancellationHasPowerButMissingDataDoesNot() {
        val a = SoundSource(0, 0f, 0f, 0f, 0f, 0f, Radiator { _, _, _ -> 100f })
        val b = SoundSource(1, 0f, 0f, 0f, 0f, 0f, a.radiator, inverted = true)
        val result = DirectField().predictBand(listOf(a, b), 10f, 0f, 0f, Bands.segments(1000, 1f/3))
        assertTrue(result.missingBands.isEmpty())
        assertEquals(0.0, result.meanSquarePressure)
    }
    @Test fun angularGridWrapsAndRejectsMalformedOrUnmeasuredDirections() {
        val grid = PolarGrid(listOf(0f, 180f, 270f), listOf(-90f, 90f),
            listOf(listOf(0f, 0f), listOf(-20f, -20f), listOf(-10f, -10f)))
        assertEquals(-5f, grid.at(315f, 0f), 1e-5f)
        assertEquals(grid.at(315f, 0f), grid.at(-45f, 0f))
        assertTrue(PolarGrid(listOf(0f), listOf(0f), emptyList()).at(0f, 0f).isNaN())
        assertTrue(PolarGrid(listOf(0f), listOf(0f), listOf(listOf(0f))).at(0f, 30f).isNaN())
    }
}
