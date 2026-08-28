package com.droidacoustic.pro.scene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Coverage edges for the aim-ray overlay, read off the balloon.
 *
 * The overlay used to draw a flat +/-20 degrees for everything, which is a
 * drawing rather than a measurement: it says the same thing about a 90 degree
 * box and a 30 degree one, at every frequency. These pin the real behaviour -
 * the edge sits where the response has fallen 6 dB, it is asymmetric because
 * real boxes are, and it narrows with frequency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverageEdgesTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    /** A CLF2 document from a function of polar angle and arc. */
    private fun tab(model: String, value: (thetaDeg: Int, phiDeg: Int) -> Double): String =
        buildString {
            appendLine("<CLF2>")
            appendLine("<MODELNAME>\t$model")
            appendLine("<SENSITIVITY>\t100.0")
            appendLine("<BALLOON-SYMMETRY>\t<none>")
            appendLine("<BALLOON-ARC-ORDER>\t<normal>")
            SceneViewModel.SUPPORTED_BANDS_HZ.forEach { hz ->
                appendLine("<BAND>\t$hz")
                (0 until 72).forEach { arc ->
                    appendLine((0..36).joinToString("\t") { t -> "%.2f".format(value(t * 5, arc * 5)) })
                }
            }
        }

    /** Falls 6 dB at exactly [edgeDeg] off axis, in every direction. */
    private fun coneTab(model: String, edgeDeg: Int) =
        tab(model) { theta, _ -> -6.0 * theta / edgeDeg }

    private fun placedFrom(v: SceneViewModel): PlacedSpeaker {
        v.addSpeaker(0f, 0f)
        return v.speakers.value.last()
    }

    private fun vmWith(doc: String, band: Int = 1000): SceneViewModel =
        SceneViewModel().apply {
            setBandHz(band)
            importClfTabText(doc)
        }

    // ── Measured ─────────────────────────────────────────────────────────────

    @Test
    fun `the edge lands where the balloon has fallen six decibels`() {
        val v = vmWith(coneTab("Thirty Box", edgeDeg = 30))
        val e = v.coverageEdgesFor(placedFrom(v))
        assertTrue("should be reported as measured", e.measured)
        assertEquals(30f, e.upDeg, 1.5f)
        assertEquals(30f, e.downDeg, 1.5f)
    }

    @Test
    fun `a narrow box reports a narrow edge and a wide one a wide edge`() {
        val narrow = vmWith(coneTab("Narrow", edgeDeg = 12)).let { it.coverageEdgesFor(placedFrom(it)) }
        val wide = vmWith(coneTab("Wide", edgeDeg = 55)).let { it.coverageEdgesFor(placedFrom(it)) }
        assertEquals(12f, narrow.upDeg, 1.5f)
        assertEquals(55f, wide.upDeg, 2f)
    }

    @Test
    fun `an asymmetric box reports different angles up and down`() {
        // Tight above the axis, wide below it - the usual shape for a box with
        // its horn on top. A single symmetric number cannot express this.
        // Floored at -25 dB so both poles agree across every arc. Without that
        // the rear pole differs by direction and the import rejects the file -
        // which is the balloon integrity check doing its job, not a bug.
        val doc = tab("Asym") { theta, phi ->
            val edge = if (phi in 91..269) 40.0 else 12.0   // phi > 90 is below
            maxOf(-6.0 * theta / edge, -25.0)
        }
        val v = vmWith(doc)
        val e = v.coverageEdgesFor(placedFrom(v))
        assertTrue("measured", e.measured)
        assertEquals(12f, e.upDeg, 1.5f)
        assertEquals(40f, e.downDeg, 2f)
        assertTrue("up and down must not be collapsed together", e.downDeg - e.upDeg > 20f)
    }

    @Test
    fun `an omnidirectional balloon has no edge to draw`() {
        // Never falls 6 dB, so the honest answer is the full hemisphere rather
        // than an invented boundary.
        val v = vmWith(tab("Omni") { _, _ -> 0.0 })
        val e = v.coverageEdgesFor(placedFrom(v))
        assertEquals(90f, e.upDeg, 0.01f)
        assertEquals(90f, e.downDeg, 0.01f)
    }

    @Test
    fun `the edge follows the band selector`() {
        // A box that narrows with frequency, as every real one does.
        val doc = buildString {
            appendLine("<CLF2>")
            appendLine("<MODELNAME>\tNarrowing")
            appendLine("<SENSITIVITY>\t100.0")
            appendLine("<BALLOON-SYMMETRY>\t<none>")
            appendLine("<BALLOON-ARC-ORDER>\t<normal>")
            SceneViewModel.SUPPORTED_BANDS_HZ.forEach { hz ->
                appendLine("<BAND>\t$hz")
                val edge = when (hz) { 125 -> 70.0; 1000 -> 40.0; else -> 15.0 }
                (0 until 72).forEach { _ ->
                    appendLine((0..36).joinToString("\t") { t -> "%.2f".format(-6.0 * (t * 5) / edge) })
                }
            }
        }
        val low = vmWith(doc, band = 125).let { it.coverageEdgesFor(placedFrom(it)) }
        val mid = vmWith(doc, band = 1000).let { it.coverageEdgesFor(placedFrom(it)) }
        val high = vmWith(doc, band = 8000).let { it.coverageEdgesFor(placedFrom(it)) }
        assertTrue("125 Hz (${low.upDeg}) should be wider than 1 kHz (${mid.upDeg})",
                   low.upDeg > mid.upDeg + 10f)
        assertTrue("1 kHz (${mid.upDeg}) should be wider than 8 kHz (${high.upDeg})",
                   mid.upDeg > high.upDeg + 10f)
    }

    // ── Fallback ─────────────────────────────────────────────────────────────

    @Test
    fun `without measured data the edge matches what the model itself predicts`() {
        // The synthetic vertical model puts a point source's -6 dB point at 35
        // degrees. Drawing anything else would show a coverage the calculation
        // does not believe in.
        val v = SceneViewModel().apply { setBandHz(1000) }
        val spk = placedFrom(v).copy(modelPackageId = "point_source", arrayElements = 1)
        val e = v.coverageEdgesFor(spk)
        assertFalse("nothing was imported, so this is not measured", e.measured)
        assertEquals(35f, e.upDeg, 0.01f)
        assertEquals(e.upDeg, e.downDeg, 0.01f)
    }

    @Test
    fun `an unmeasured array narrows with frequency and with element count`() {
        val v = SceneViewModel()
        fun edge(elements: Int, band: Int): Float {
            v.setBandHz(band)
            return v.coverageEdgesFor(
                placedFrom(v).copy(arrayElements = elements)
            ).upDeg
        }
        assertTrue("more boxes should narrow the array", edge(12, 1000) < edge(4, 1000))
        assertTrue("higher frequency should narrow it", edge(8, 8000) < edge(8, 125))
    }

    @Test
    fun `importing measured data changes the edge from the synthetic guess`() {
        val bare = SceneViewModel().apply { setBandHz(1000) }
        val guess = bare.coverageEdgesFor(placedFrom(bare).copy(modelPackageId = "point_source"))
        val v = vmWith(coneTab("Twelve", edgeDeg = 12))
        val measured = v.coverageEdgesFor(placedFrom(v))
        assertNotEquals(guess.upDeg, measured.upDeg, 5f)
        assertTrue(measured.measured && !guess.measured)
    }

    // ── The real file ────────────────────────────────────────────────────────

    @Test
    fun `the published XD12 reports its own asymmetry`() {
        val f = listOf(
            "../corpus/clf/martin_audio/CLF2_XD12.tab",
            "../../corpus/clf/martin_audio/CLF2_XD12.tab"
        ).map(::File).firstOrNull { it.exists() }
        assumeTrue("corpus not present - third-party data, not in the repo", f != null)

        val v = SceneViewModel().apply { setBandHz(2000) }
        v.importClfTabText(f!!.readText(Charsets.ISO_8859_1))
        val e = v.coverageEdgesFor(placedFrom(v))
        assertTrue("should come from the file", e.measured)
        // Measured directly out of the TAB earlier: about 10 degrees up and 25
        // down at 2 kHz. A symmetric model cannot produce that.
        assertTrue("up ${e.upDeg} / down ${e.downDeg} should differ", e.downDeg > e.upDeg + 5f)
        assertTrue("up edge ${e.upDeg} out of range", e.upDeg in 5f..20f)
        assertTrue("down edge ${e.downDeg} out of range", e.downDeg in 15f..35f)
    }
}
