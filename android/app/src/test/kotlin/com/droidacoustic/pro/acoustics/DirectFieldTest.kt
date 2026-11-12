package com.droidacoustic.pro.acoustics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * The direct-field sum, against answers that do not depend on this code.
 *
 * Inverse square, pressure doubling, power summation and the position of a comb
 * null are all textbook results. If the engine disagrees with any of them it is
 * the engine that is wrong, so these assert numbers rather than directions.
 */
class DirectFieldTest {

    private val air = Atmosphere(20f, 50f)
    private val field = DirectField(air)

    /** A box that puts out [level] in every direction at every frequency. */
    private fun omni(level: Float = 100f) = Radiator { _, _, _ -> level }

    private fun source(
        x: Float = 0f, y: Float = 0f, z: Float = 0f,
        radiator: Radiator = omni(),
        group: Int = 0, gainDb: Float = 0f, delayS: Double = 0.0, inverted: Boolean = false,
        yaw: Float = 0f, elevation: Float = 0f,
        eq: (Int) -> Float = { 0f }
    ) = SoundSource(group, x, y, z, yaw, elevation, radiator, gainDb, delayS, inverted, eq)

    private fun third(hz: Int) = Bands.segments(hz, 1f / 3f)
    private fun octave(hz: Int) = Bands.segments(hz, 1f)
    private fun narrow(hz: Int) = Bands.segments(hz, 1f / 24f)

    private fun level(
        sources: List<SoundSource>, x: Float, y: Float = 0f, z: Float = 0f,
        band: List<Bands.Segment> = third(100),
        coherent: Boolean = true, loss: FloatArray? = null, floor: FloorReflection? = null
    ) = field.bandLevelDb(sources, x, y, z, band, coherent, loss, floor)

    // ── One source ───────────────────────────────────────────────────────────

    @Test
    fun `at one metre a source delivers its rated level`() {
        assertEquals(100f, level(listOf(source()), 1f), 0.01f)
    }

    @Test
    fun `doubling the distance costs six decibels`() {
        val near = level(listOf(source()), 4f)
        val far = level(listOf(source()), 8f)
        assertEquals(6.02f, near - far, 0.02f)
    }

    @Test
    fun `ten times the distance costs twenty`() {
        assertEquals(20f, level(listOf(source()), 1f) - level(listOf(source()), 10f), 0.02f)
    }

    @Test
    fun `the air takes its share on top of spreading`() {
        val d = 50f
        val got = level(listOf(source()), d, band = third(8000))
        val seg = third(8000).single()
        val expected = 100.0 - 20.0 * log10(d.toDouble()) - air.absorptionDbPerMetre(seg.centreHz) * d
        assertEquals(expected.toFloat(), got, 0.02f)
        assertTrue("air alone should cost about 5 dB here", 100f - 20f * log10(d) - got > 4.5f)
    }

    @Test
    fun `gain adds decibel for decibel`() {
        val flat = level(listOf(source()), 5f)
        assertEquals(4.5f, level(listOf(source(gainDb = 4.5f)), 5f) - flat, 0.01f)
    }

    @Test
    fun `a box with no data in a band is silent there, not guessed at`() {
        val bassless = Radiator { hz, _, _ -> if (hz < 100) Float.NaN else 100f }
        assertEquals(DirectField.FLOOR_DB, level(listOf(source(radiator = bassless)), 2f, band = third(63)), 0f)
        assertEquals(94f, level(listOf(source(radiator = bassless)), 2f, band = third(125)), 0.05f)
    }

    // ── Many sources ─────────────────────────────────────────────────────────

    @Test
    fun `two sources in the same place add six decibels`() {
        val one = level(listOf(source()), 10f)
        val two = level(listOf(source(), source()), 10f)
        assertEquals(6.02f, two - one, 0.02f)
    }

    @Test
    fun `N in the same place add twenty log N`() {
        val one = level(listOf(source()), 10f)
        listOf(3, 8, 16).forEach { n ->
            val many = level(List(n) { source() }, 10f)
            assertEquals("$n sources", 20f * log10(n.toFloat()), many - one, 0.02f)
        }
    }

    @Test
    fun `two sources far apart in time add three decibels across a band`() {
        // 40 ms apart, heard through an octave at 1 kHz: dozens of comb teeth
        // inside the band, so the phase relationship averages away and what is
        // left is power. Nothing was told to be incoherent - it falls out.
        val near = source(x = 0f)
        val late = source(x = 0f, delayS = 0.040)
        val one = level(listOf(near), 10f, band = octave(1000))
        val two = level(listOf(near, late), 10f, band = octave(1000))
        assertEquals(3.01f, two - one, 0.15f)
    }

    @Test
    fun `half a wavelength of delay is a null in a narrow band`() {
        val f = Bands.exactHz(16)
        val a = source()
        val b = source(delayS = 0.5 / f)
        val one = level(listOf(a), 10f, band = narrow(1000))
        val two = level(listOf(a, b), 10f, band = narrow(1000))
        assertTrue("expected a deep null, got ${two - one} dB", two - one < -25f)
    }

    @Test
    fun `the same null fills in as the band widens`() {
        // The comb is still there; a wider band just sees more than the notch.
        val f = Bands.exactHz(16)
        val pair = listOf(source(), source(delayS = 0.5 / f))
        val single = level(listOf(source()), 10f, band = third(1000))
        val inThird = level(pair, 10f, band = third(1000)) - single
        val inOctave = level(pair, 10f, band = octave(1000)) - level(listOf(source()), 10f, band = octave(1000))
        assertEquals(-13.6f, inThird, 0.5f)
        assertTrue("an octave should fill it further: $inOctave vs $inThird", inOctave > inThird + 5f)
    }

    @Test
    fun `a whole wavelength of delay is back in phase`() {
        val f = Bands.exactHz(16)
        val one = level(listOf(source()), 10f, band = narrow(1000))
        val two = level(listOf(source(), source(delayS = 1.0 / f)), 10f, band = narrow(1000))
        assertEquals(6.0f, two - one, 0.1f)
    }

    @Test
    fun `an inverted twin cancels`() {
        val got = level(listOf(source(), source(inverted = true)), 10f)
        assertEquals(DirectField.FLOOR_DB, got, 0f)
    }

    @Test
    fun `path difference does what delay does`() {
        // Same half-wavelength offset, made with distance instead of time.
        val f = Bands.exactHz(16)
        val half = (air.speedOfSound / f / 2.0).toFloat()
        val one = level(listOf(source()), 20f, band = narrow(1000))
        val two = level(listOf(source(x = 0f), source(x = -half)), 20f, band = narrow(1000))
        assertTrue("expected a null, got ${two - one} dB", two - one < -20f)
    }

    @Test
    fun `asked to ignore phase, two sources add three decibels wherever they are`() {
        val one = level(listOf(source()), 10f)
        val two = level(listOf(source(), source()), 10f, coherent = false)
        assertEquals(3.01f, two - one, 0.02f)
    }

    @Test
    fun `a line of elements sums in pressure on its axis at low frequency`() {
        // Eight boxes over two metres, heard from far away at 63 Hz: every path
        // is the same to within a small fraction of a wavelength.
        val line = List(8) { i -> source(y = 5f + (i - 3.5f) * 0.28f) }
        val one = level(listOf(source(y = 5f)), 150f, y = 5f, band = third(63))
        val all = level(line, 150f, y = 5f, band = third(63))
        assertEquals(20f * log10(8f), all - one, 0.1f)
    }

    // ── Bands ────────────────────────────────────────────────────────────────

    @Test
    fun `a flat source reads the same in an octave as in a third`() {
        // The band level is the mean over the band, not the sum of its parts:
        // the same drive spread across more spectrum is not more level.
        val s = listOf(source())
        assertEquals(level(s, 5f, band = third(500)), level(s, 5f, band = octave(500)), 0.05f)
    }

    @Test
    fun `EQ in one third-octave lifts an octave by its share`() {
        val s = listOf(source(eq = { hz -> if (hz == 1000) 6f else 0f }))
        val flat = level(listOf(source()), 5f, band = octave(1000))
        val lifted = level(s, 5f, band = octave(1000))
        // One third at four times the power, two untouched: mean of 4, 1, 1.
        assertEquals(10f * log10(2f), lifted - flat, 0.05f)
        // And a neighbouring octave does not hear it at all.
        assertEquals(
            level(listOf(source()), 5f, band = octave(4000)),
            level(s, 5f, band = octave(4000)), 0.001f
        )
    }

    // ── Obstruction hook ─────────────────────────────────────────────────────

    @Test
    fun `a loss on one loudspeaker leaves the others alone`() {
        val a = source(group = 0)
        val b = source(group = 1, x = 0f, delayS = 0.050)
        val open = level(listOf(a), 10f, band = octave(1000))
        val shadowed = level(listOf(a), 10f, band = octave(1000), loss = floatArrayOf(6f, 0f))
        assertEquals(6f, open - shadowed, 0.01f)
        val both = level(listOf(a, b), 10f, band = octave(1000), loss = floatArrayOf(60f, 0f))
        assertEquals(level(listOf(b), 10f, band = octave(1000)), both, 0.05f)
    }

    // ── What the box sees ────────────────────────────────────────────────────

    private fun directionFrom(s: SoundSource, x: Float, y: Float, z: Float): Triple<Double, Double, Double> {
        val out = DoubleArray(3)
        s.toward(x, y, z, out)
        return Triple(out[0], out[1], out[2])
    }

    @Test
    fun `dead ahead is zero azimuth and zero elevation`() {
        val (d, az, el) = directionFrom(source(), 7f, 0f, 0f)
        assertEquals(7.0, d, 1e-6)
        assertEquals(0.0, az, 1e-6)
        assertEquals(0.0, el, 1e-6)
    }

    @Test
    fun `plus Z is ninety degrees round, and above is ninety up`() {
        assertEquals(90.0, directionFrom(source(), 0f, 0f, 5f).second, 1e-6)
        assertEquals(-90.0, directionFrom(source(), 0f, 0f, -5f).second, 1e-6)
        assertEquals(90.0, directionFrom(source(), 0f, 5f, 0f).third, 1e-6)
    }

    @Test
    fun `turning and tilting the box onto a point puts it back on axis`() {
        val s = source(y = 6f, yaw = 35f, elevation = -20f)
        // A point 10 m down that axis.
        val yaw = Math.toRadians(35.0); val el = Math.toRadians(-20.0)
        val px = (10 * Math.cos(el) * Math.cos(yaw)).toFloat()
        val py = (6 + 10 * Math.sin(el)).toFloat()
        val pz = (10 * Math.cos(el) * Math.sin(yaw)).toFloat()
        val (d, az, elev) = directionFrom(s, px, py, pz)
        assertEquals(10.0, d, 1e-4)
        assertEquals(0.0, az, 1e-3)
        assertEquals(0.0, elev, 1e-3)
    }

    @Test
    fun `a steeply tilted box reads a side listener correctly`() {
        // Tilted 60 degrees down, listener level with the box and out to the
        // side. Subtracting angles separately calls that 90 degrees round and 60
        // above; in the box's own frame it is exactly beside it, on its horizon.
        val s = source(y = 5f, elevation = -60f)
        val (_, az, el) = directionFrom(s, 0f, 5f, 8f)
        assertEquals(90.0, az, 1e-3)
        assertEquals(0.0, el, 1e-3)
    }

    @Test
    fun `directivity is read in the box's frame`() {
        // A box that only radiates forward, panned 90 degrees to face +Z.
        val forward = Radiator { _, az, _ -> if (kotlin.math.abs(az) < 45f) 100f else 60f }
        val s = listOf(source(radiator = forward, yaw = 90f))
        assertEquals(100f - 20f, level(s, 0f, z = 10f), 0.05f)
        assertEquals(60f - 20f, level(s, 10f, z = 0f), 0.05f)
    }

    // ── The floor ────────────────────────────────────────────────────────────

    @Test
    fun `there is no floor unless one is asked for`() {
        val s = listOf(source(y = 3f))
        assertEquals(100f - 20f * log10(10f), level(s, 10f, y = 3f), 0.02f)
    }

    @Test
    fun `source and ear both on a hard floor hear the image in phase`() {
        val hard = FloorReflection { 0f }
        val free = level(listOf(source()), 10f)
        assertEquals(6.02f, level(listOf(source()), 10f, floor = hard) - free, 0.02f)
    }

    @Test
    fun `an absorbent floor returns less`() {
        // Three quarters absorbed leaves half the pressure: 20 log 1.5.
        val soft = FloorReflection { 0.75f }
        val free = level(listOf(source()), 10f)
        assertEquals(3.52f, level(listOf(source()), 10f, floor = soft) - free, 0.02f)
    }

    @Test
    fun `the floor's absorption is read per band`() {
        val carpet = FloorReflection { hz -> if (hz >= 2000) 0.75f else 0f }
        val s = listOf(source())
        val low = level(s, 10f, band = third(125), floor = carpet) - level(s, 10f, band = third(125))
        val high = level(s, 10f, band = third(4000), floor = carpet) - level(s, 10f, band = third(4000))
        assertEquals(6.02f, low, 0.05f)
        assertEquals(3.52f, high, 0.05f)
    }

    @Test
    fun `a raised source and ear put the first floor null at half a wavelength of extra path`() {
        val hard = FloorReflection { 0f }
        val f = Bands.exactHz(16)
        val lambda = air.speedOfSound / f
        // Equal heights h over a run d: excess path = sqrt(d^2 + 4h^2) - d.
        val d = 12.0
        val h = Math.sqrt(((d + lambda / 2) * (d + lambda / 2) - d * d) / 4.0).toFloat()
        val s = listOf(source(y = h))
        val withFloor = level(s, d.toFloat(), y = h, band = narrow(1000), floor = hard)
        val free = level(s, d.toFloat(), y = h, band = narrow(1000))
        assertTrue("expected a notch, got ${withFloor - free} dB", withFloor - free < -15f)
    }
}
