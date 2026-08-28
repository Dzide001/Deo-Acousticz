package com.droidacoustic.pro.scene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.log10

/**
 * Coherent summation across the elements of a line array.
 *
 * The anchor is the one number an array must get right: N elements radiating in
 * phase into the far field sum to 20*log10(N) over one of them, not 10*log10(N).
 * Eight boxes are +18.06 dB, and anything near +9 would mean the model had
 * quietly fallen back to adding power instead of pressure.
 *
 * The rest of the model is less firm. The steering phase term is a far-field
 * linear-array approximation, the edge taper is a shading control rather than a
 * measurement, and electronic steering and mechanical tilt are modelled as the
 * same thing - which is not true of a real system and is pinned here as the
 * documented limitation it is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LineArraySummationTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(bandHz: Int = 63) = SceneViewModel().apply {
        setBandHz(bandHz)
        setTemperatureC(20f)
    }

    private fun array(
        elements: Int = 8,
        spacingM: Float = 0.2f,
        heightM: Float = 6f,
        steerDeg: Float = 0f,
        aimDeg: Float = 0f,
        splayDeg: Float = 0f,
        taperDb: Float = 0f
    ) = PlacedSpeaker(
        id = 0, x = 0f, z = 0f, heightM = heightM, sensitivity = 100f,
        arrayElements = elements, arraySpacingM = spacingM,
        arraySteerDeg = steerDeg, arrayAimDeg = aimDeg,
        arrayInterBoxSplayDeg = splayDeg, arrayEdgeTaperDb = taperDb
    )

    private val dsp = SpeakerDsp(speakerId = 0)

    /** On-axis in the far field, where the elements are effectively equidistant. */
    private fun onAxis(v: SceneViewModel, spk: PlacedSpeaker, distM: Float = 200f) =
        v.lineArraySplDb(spk, dsp, distM, spk.heightM)

    // ── The anchor ───────────────────────────────────────────────────────────

    @Test
    fun `eight elements in phase sum to plus eighteen, not plus nine`() {
        val v = vm()
        val spk = array(elements = 8)
        val one = v.elementSplDb(100f, dsp, 200f)
        val gain = onAxis(v, spk) - one
        assertEquals("pressure summation is 20*log10(8)", 20f * log10(8f), gain, 0.5f)
        assertTrue("power summation would be about +9 dB; got $gain", gain > 15f)
    }

    @Test
    fun `doubling the box count adds six decibels`() {
        val v = vm()
        val four = onAxis(v, array(elements = 4))
        val eight = onAxis(v, array(elements = 8))
        assertEquals(6.02f, eight - four, 0.4f)
    }

    @Test
    fun `the coherent gain follows twenty log N across sizes`() {
        val v = vm()
        val one = v.elementSplDb(100f, dsp, 200f)
        listOf(2, 3, 6, 12, 16).forEach { n ->
            val gain = onAxis(v, array(elements = n)) - one
            assertEquals("$n elements", 20f * log10(n.toFloat()), gain, 0.6f)
        }
    }

    @Test
    fun `more boxes never means less level on axis`() {
        val v = vm()
        var previous = -Float.MAX_VALUE
        (1..16).forEach { n ->
            val spl = onAxis(v, array(elements = n))
            assertTrue("$n elements gave $spl, below $previous", spl >= previous - 1e-3f)
            previous = spl
        }
    }

    // ── Shading and polarity ─────────────────────────────────────────────────

    @Test
    fun `edge taper trades level for shading`() {
        val v = vm()
        val flat = onAxis(v, array(taperDb = 0f))
        val shaded = onAxis(v, array(taperDb = 6f))
        assertTrue("tapering the ends should cost level: $shaded vs $flat", shaded < flat - 1f)
    }

    @Test
    fun `inverting the whole array changes nothing about its own level`() {
        // Every element flips together, so the magnitude of the sum is
        // untouched. Polarity only matters against something else.
        val v = vm()
        val spk = array()
        val normal = v.lineArraySplDb(spk, dsp, 200f, spk.heightM)
        val inverted = v.lineArraySplDb(spk, dsp.copy(polarity = true), 200f, spk.heightM)
        assertEquals(normal, inverted, 1e-2f)
    }

    @Test
    fun `gain trim moves the whole array together`() {
        val v = vm()
        val spk = array()
        val flat = v.lineArraySplDb(spk, dsp, 200f, spk.heightM)
        val lifted = v.lineArraySplDb(spk, dsp.copy(gainDb = 5f), 200f, spk.heightM)
        assertEquals(5f, lifted - flat, 1e-2f)
    }

    // ── Steering ─────────────────────────────────────────────────────────────

    @Test
    fun `electronic steering and mechanical tilt are modelled as the same thing`() {
        // globalSteerDeg is just their sum, so 6 degrees of either, or three of
        // each, all land in the same place. A real system does not behave that
        // way - electronic steering costs level and changes the lobing - and
        // this test exists to make that assumption visible rather than true.
        val v = vm(bandHz = 1000)
        val spk = array()
        val listenerY = 1.2f
        val electronic = v.lineArraySplDb(spk.copy(arraySteerDeg = 6f), dsp, 40f, listenerY)
        val mechanical = v.lineArraySplDb(spk.copy(arrayAimDeg = 6f), dsp, 40f, listenerY)
        val split = v.lineArraySplDb(spk.copy(arraySteerDeg = 3f, arrayAimDeg = 3f), dsp, 40f, listenerY)
        assertEquals(electronic, mechanical, 1e-2f)
        assertEquals(electronic, split, 1e-2f)
    }

    @Test
    fun `splaying the array spreads its energy and costs on-axis level`() {
        val v = vm(bandHz = 1000)
        val straight = onAxis(v, array(splayDeg = 0f), distM = 60f)
        val splayed = onAxis(v, array(splayDeg = 6f), distM = 60f)
        assertTrue("splay should cost level on the axis: $splayed vs $straight",
                   splayed < straight)
    }

    // ── Interference ─────────────────────────────────────────────────────────

    @Test
    fun `the high end lobes and the low end does not`() {
        // Element spacing is a fixed fraction of a long wavelength and a large
        // fraction of a short one, so the vertical response gets ragged with
        // frequency. That raggedness is the whole reason arrays are splayed.
        fun raggedness(bandHz: Int): Float {
            val v = vm(bandHz)
            val spk = array(elements = 12, heightM = 8f)
            val levels = (0..40).map { i ->
                v.lineArraySplDb(spk, dsp, 30f, 0.5f + i * 0.35f)
            }
            return levels.zipWithNext().map { (a, b) -> kotlin.math.abs(a - b) }.average().toFloat()
        }
        val low = raggedness(63)
        val high = raggedness(8000)
        assertTrue("8 kHz ($high) should be raggeder than 63 Hz ($low)", high > low * 2f)
    }

    // ── Guard rails ──────────────────────────────────────────────────────────

    @Test
    fun `a listener at the array does not produce infinity`() {
        val v = vm(bandHz = 1000)
        val spl = v.lineArraySplDb(array(), dsp, 0.001f, 6f)
        assertTrue("degenerate distance gave $spl", spl.isFinite())
    }

    @Test
    fun `level stays finite over bands, sizes and spacings`() {
        SceneViewModel.SUPPORTED_BANDS_HZ.forEach { band ->
            val v = vm(band)
            listOf(2, 8, 16).forEach { n ->
                listOf(0.05f, 0.2f, 0.6f).forEach { spacing ->
                    listOf(1f, 25f, 150f).forEach { d ->
                        val spl = v.lineArraySplDb(array(elements = n, spacingM = spacing), dsp, d, 1.2f)
                        assertTrue("$band Hz, $n boxes, $spacing m, $d m gave $spl", spl.isFinite())
                    }
                }
            }
        }
    }
}
