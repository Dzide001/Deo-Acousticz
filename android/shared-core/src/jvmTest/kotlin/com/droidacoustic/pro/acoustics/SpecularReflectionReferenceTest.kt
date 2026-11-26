package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.geometry.AcousticBoundaryRole
import com.droidacoustic.pro.geometry.BoundaryGeometry
import com.droidacoustic.pro.scene.VenueBlock
import com.droidacoustic.pro.scene.VenueGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Specular reflection from a finite rigid plate, against a physical-optics
 * (Kirchhoff) integral computed independently here.
 *
 * ## The reference
 *
 * The plate's surface pressure is taken as twice the incident pressure on the lit
 * side and zero elsewhere, and the reflected field is the Kirchhoff integral of
 * that over the plate:
 *
 *     p_r(R) = (i k / 4 pi) A ∫ exp(-i k (r1 + r2)) / (r1 r2) (cos1 + cos2) dS
 *
 * For an infinite plate, stationary phase reduces this to the image source
 * A exp(-i k L) / L, which [referenceReducesToTheImageSourceOnALargePlate] checks.
 * Band levels use the engine's own band definition: flat spectral density over
 * each segment of [Bands.segments], width-weighted. Air absorption is applied at
 * each segment's centre, as the engine does; it is under 0.1 dB at these ranges.
 *
 * ## Where the reference means anything
 *
 * Physical optics ignores the way the plate's edges change the surface pressure,
 * so it holds only when the plate is several wavelengths across and the receiver
 * is not near the edge of the geometric reflection zone. Every assertion below
 * stays inside that range: plates of at least 6 wavelengths, receivers at least
 * about 0.7 m inside the zone edge. No statement is made here about reflections
 * from plates comparable to or smaller than a wavelength - that needs an exact
 * reference (BEM, or edge diffraction after Biot-Tolstoy-Medwin), not this one.
 *
 * The result is a statement about these geometries, not a general accuracy
 * threshold for the engine. See docs/reflection_accuracy_review.md.
 */
class SpecularReflectionReferenceTest {

    private val air = Atmosphere(20f, 50f)
    private val field = DirectField(air)
    private val c = air.speedOfSound

    /** 100 dB at 1 m in every direction: pressure amplitude 1e5 in the engine's units. */
    private val omni = Radiator { _, _, _ -> 100f }
    private val amplitude = 1e5

    /** A rigid plate in the plane x = [x], spanning [y0]..[y1] and [z0]..[z1], facing -x. */
    private data class Plate(val x: Double, val y0: Double, val y1: Double, val z0: Double, val z1: Double)

    private fun geometry(p: Plate): BoundaryGeometry {
        // A 0.2 m thick rigid block whose front face is the plate. Its side and top
        // faces cannot produce a valid image path for receivers in front of it.
        val wall = VenueBlock(
            1, centerX = (p.x + 0.1).toFloat(), centerZ = ((p.z0 + p.z1) / 2).toFloat(),
            widthM = 0.2f, depthM = (p.z1 - p.z0).toFloat(), heightM = p.y1.toFloat(),
            blockHeightM = (p.y1 - p.y0).toFloat(), boundaryRole = AcousticBoundaryRole.RIGID_REFLECTOR
        )
        val venue = VenueGeometry(stageBoundaryRole = AcousticBoundaryRole.NONE, blocks = listOf(wall))
        return BoundaryGeometry.prepare(emptyList(), venue = venue)
    }

    /**
     * Band mean-square pressure from the Kirchhoff reference.
     *
     * @param cellsPerWavelength mesh density at the top of each segment.
     * @param frequenciesPerSegment midpoint-rule samples, linear in frequency.
     */
    private fun reference(
        s: DoubleArray, r: DoubleArray, plate: Plate, bandHz: Int,
        includeDirect: Boolean = true,
        cellsPerWavelength: Int = 10, frequenciesPerSegment: Int = 32
    ): Double {
        val segments = Bands.segments(bandHz, 1f)
        var total = 0.0
        for (seg in segments) {
            val alpha = air.absorptionDbPerMetre(seg.centreHz)
            val h = c / seg.f2 / cellsPerWavelength
            val ny = ((plate.y1 - plate.y0) / h).toInt().coerceAtLeast(1)
            val nz = ((plate.z1 - plate.z0) / h).toInt().coerceAtLeast(1)
            val hy = (plate.y1 - plate.y0) / ny
            val hz = (plate.z1 - plate.z0) / nz
            val count = ny * nz
            val path = DoubleArray(count)
            val weight = DoubleArray(count)
            var n = 0
            for (iy in 0 until ny) for (iz in 0 until nz) {
                val y = plate.y0 + (iy + 0.5) * hy
                val z = plate.z0 + (iz + 0.5) * hz
                val r1 = sqrt((plate.x - s[0]).pow(2) + (y - s[1]).pow(2) + (z - s[2]).pow(2))
                val r2 = sqrt((plate.x - r[0]).pow(2) + (y - r[1]).pow(2) + (z - r[2]).pow(2))
                val cosines = (plate.x - s[0]) / r1 + (plate.x - r[0]) / r2
                path[n] = r1 + r2
                weight[n] = cosines / (r1 * r2) * hy * hz * 10.0.pow(-alpha * (r1 + r2) / 20.0)
                n++
            }
            val rd = sqrt((r[0] - s[0]).pow(2) + (r[1] - s[1]).pow(2) + (r[2] - s[2]).pow(2))
            val directAmp = 10.0.pow(-alpha * rd / 20.0) / rd
            var power = 0.0
            for (j in 0 until frequenciesPerSegment) {
                val f = seg.f1 + (j + 0.5) * seg.widthHz / frequenciesPerSegment
                val k = 2 * PI * f / c
                var sr = 0.0; var si = 0.0
                for (q in 0 until count) {
                    val phase = -k * path[q]
                    sr += weight[q] * cos(phase); si += weight[q] * sin(phase)
                }
                // (i k / 4 pi) (sr + i si)
                var re = -k * si / (4 * PI)
                var im = k * sr / (4 * PI)
                if (includeDirect) { re += directAmp * cos(-k * rd); im += directAmp * sin(-k * rd) }
                power += re * re + im * im
            }
            total += power / frequenciesPerSegment * seg.widthHz
        }
        return amplitude * amplitude * total / segments.sumOf { it.widthHz }
    }

    private fun db(power: Double) = 10 * log10(power)

    private fun engineDb(s: DoubleArray, r: DoubleArray, geometry: BoundaryGeometry, bandHz: Int): Double {
        val src = SoundSource(0, s[0].toFloat(), s[1].toFloat(), s[2].toFloat(), 0f, 0f, omni)
        return db(field.bandPower(listOf(src), r[0].toFloat(), r[1].toFloat(), r[2].toFloat(),
            Bands.segments(bandHz, 1f), boundaries = geometry))
    }

    // ---------------------------------------------------------------- reference

    @Test
    fun referenceReducesToTheImageSourceOnALargePlate() {
        // 20 m x 20 m plate, about 40 wavelengths at 500 Hz. Reflected field only.
        val plate = Plate(10.0, -10.0, 10.0, -10.0, 10.0)
        val s = doubleArrayOf(0.0, 0.0, 0.0)
        val r = doubleArrayOf(6.0, 0.5, 1.0)
        val image = sqrt((20.0 - 6.0).pow(2) + 0.5.pow(2) + 1.0.pow(2))
        val segments = Bands.segments(500, 1f)
        val expected = segments.sumOf { seg ->
            val a = amplitude / image * 10.0.pow(-air.absorptionDbPerMetre(seg.centreHz) * image / 20.0)
            a * a * seg.widthHz
        } / segments.sumOf { it.widthHz }
        val got = reference(s, r, plate, 500, includeDirect = false)
        assertEquals("Kirchhoff reference vs image source", db(expected), db(got), 0.2)
    }

    @Test
    fun referenceIsConvergedInMeshAndFrequency() {
        val plate = Plate(10.0, 0.0, 4.0, -2.0, 2.0)
        val s = doubleArrayOf(0.0, 2.0, 0.0)
        for ((band, r) in listOf(1000 to doubleArrayOf(9.0, 2.0, 1.0), 500 to doubleArrayOf(9.0, 2.0, 0.0))) {
            val coarse = db(reference(s, r, plate, band))
            val fine = db(reference(s, r, plate, band, cellsPerWavelength = 20, frequenciesPerSegment = 64))
            assertEquals("$band Hz: doubling mesh density and frequency samples", coarse, fine, 0.05)
        }
    }

    // ------------------------------------------------------------------- engine

    /**
     * Source 10 m from a 4 m x 4 m plate, receiver 1 m in front of it, so the
     * reflection is within 2 dB of the direct sound. The geometric zone edge is
     * at |z| = 2.2 m; receivers stay at |z| <= 1.5 m.
     */
    @Test
    fun matchesTheReferenceInsideTheZoneOfASixWavelengthPlate() {
        val plate = Plate(10.0, 0.0, 4.0, -2.0, 2.0)
        val g = geometry(plate)
        val s = doubleArrayOf(0.0, 2.0, 0.0)
        for (band in listOf(500, 1000)) for (z in listOf(0.0, 0.5, 1.0, 1.5)) {
            val r = doubleArrayOf(9.0, 2.0, z)
            val engine = engineDb(s, r, g, band)
            val ref = db(reference(s, r, plate, band))
            assertEquals("$band Hz at z = $z", ref, engine, 1.0)
            // And the reflection is really there: well above the direct sound alone.
            val directOnly = db(amplitude * amplitude / (81.0 + z * z))
            assertTrue("$band Hz at z = $z: reflection adds level", engine - directOnly > 1.5)
        }
    }

    /** A 12 m x 8 m plate at 250 Hz: 6 to 9 wavelengths, zone edge at |z| = 7.8 m. */
    @Test
    fun matchesTheReferenceForALargePlateAt250Hz() {
        val plate = Plate(10.0, 0.0, 8.0, -6.0, 6.0)
        val g = geometry(plate)
        val s = doubleArrayOf(0.0, 4.0, 0.0)
        for (z in listOf(0.0, 1.5, 3.0)) {
            val r = doubleArrayOf(7.0, 4.0, z)
            assertEquals("250 Hz at z = $z", db(reference(s, r, plate, 250)), engineDb(s, r, g, 250), 1.0)
        }
    }

    /**
     * Known limitation, pinned so it is not forgotten: the engine treats a
     * reflector as a mirror up to its edge and nothing beyond, so the level steps
     * at the geometric zone boundary where the physical field changes smoothly.
     * When finite-reflector diffraction is modelled this test should be replaced by
     * one asserting agreement across the edge.
     */
    @Test
    fun knownLimitationLevelStepsAtTheZoneEdge() {
        val plate = Plate(10.0, 0.0, 4.0, -2.0, 2.0)
        val g = geometry(plate)
        val s = doubleArrayOf(0.0, 2.0, 0.0)
        // Zone edge for a receiver at x = 9 is |z| = 2.2 m.
        val inside = doubleArrayOf(9.0, 2.0, 2.1)
        val outside = doubleArrayOf(9.0, 2.0, 2.3)
        val engineStep = engineDb(s, inside, g, 1000) - engineDb(s, outside, g, 1000)
        val referenceStep = db(reference(s, inside, plate, 1000)) - db(reference(s, outside, plate, 1000))
        assertTrue("engine step $engineStep dB", engineStep > 1.5)
        assertTrue("reference step $referenceStep dB", kotlin.math.abs(referenceStep) < 1.0)
    }
}
