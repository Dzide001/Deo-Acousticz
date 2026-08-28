package com.droidacoustic.pro.scene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.log10

/**
 * Frequency weighting and the broadband sum it needs to mean anything.
 *
 * The weighting curves are IEC 61672 and their values are known, so those are
 * asserted outright. The summation has a known answer too: N equal bands power
 * sum to 10*log10(N) over one of them - never 20*log10(N), because separate
 * bands are not phase-related and adding them as pressure would invent
 * interference between frequencies that does not exist.
 *
 * The reason weighting needed the broadband work at all is pinned here as well:
 * on a single band it is a constant offset and changes nothing about the map.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeightingTest {

    private val live = mutableListOf<SceneViewModel>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        live.forEach { it.cancelAnalysisJobs() }
        live.clear()
        Dispatchers.resetMain()
    }

    private fun track(vm: SceneViewModel) = vm.also { live += it }

    private fun splOf(v: SceneViewModel): Float {
        v.recalculateSignal()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            v.combinedSplDb.value?.let { return it }
            Thread.sleep(10)
        }
        error("no SPL computed")
    }

    private fun scene(broadband: Boolean, weighting: String = SceneViewModel.WEIGHTING_Z) =
        track(SceneViewModel()).apply {
            setBandHz(1000)
            if (broadband) setSignalType("SPECTRUM")
            setWeighting(weighting)
            moveListener(12f, 0f)
            addSpeaker(0f, 0f)
        }

    private val bands = SceneViewModel.SUPPORTED_BANDS_HZ

    // ── The curves ───────────────────────────────────────────────────────────

    @Test
    fun `A-weighting matches the standard at the octave centres`() {
        val a = SceneViewModel.WEIGHTING_A
        assertEquals(-26.2f, SceneViewModel.weightingDb(a, 63), 0.05f)
        assertEquals(-16.1f, SceneViewModel.weightingDb(a, 125), 0.05f)
        assertEquals(-8.6f, SceneViewModel.weightingDb(a, 250), 0.05f)
        assertEquals(-3.2f, SceneViewModel.weightingDb(a, 500), 0.05f)
        assertEquals(0.0f, SceneViewModel.weightingDb(a, 1000), 0.05f)
        assertEquals(1.2f, SceneViewModel.weightingDb(a, 2000), 0.05f)
        assertEquals(1.0f, SceneViewModel.weightingDb(a, 4000), 0.05f)
        assertEquals(-1.1f, SceneViewModel.weightingDb(a, 8000), 0.05f)
    }

    @Test
    fun `C-weighting is nearly flat and only rolls off at the ends`() {
        val c = SceneViewModel.WEIGHTING_C
        listOf(250, 500, 1000).forEach {
            assertEquals("flat through the middle at $it Hz", 0f, SceneViewModel.weightingDb(c, it), 0.05f)
        }
        assertEquals(-0.8f, SceneViewModel.weightingDb(c, 63), 0.05f)
        assertEquals(-3.0f, SceneViewModel.weightingDb(c, 8000), 0.05f)
    }

    @Test
    fun `Z is no weighting at all`() {
        bands.forEach {
            assertEquals(0f, SceneViewModel.weightingDb(SceneViewModel.WEIGHTING_Z, it), 1e-6f)
        }
    }

    @Test
    fun `A discounts the low end far harder than C does`() {
        // This is the whole point of having both: at 63 Hz they differ by 25 dB.
        val a = SceneViewModel.weightingDb(SceneViewModel.WEIGHTING_A, 63)
        val c = SceneViewModel.weightingDb(SceneViewModel.WEIGHTING_C, 63)
        assertTrue("A should be far below C at 63 Hz: $a vs $c", c - a > 24f)
    }

    @Test
    fun `an unknown band or weighting contributes nothing rather than failing`() {
        assertEquals(0f, SceneViewModel.weightingDb(SceneViewModel.WEIGHTING_A, 37), 1e-6f)
        assertEquals(0f, SceneViewModel.weightingDb("Q", 1000), 1e-6f)
    }

    // ── The sum ──────────────────────────────────────────────────────────────

    @Test
    fun `equal bands add as power, not as pressure`() {
        // Eight equal bands are +9.03 dB over one of them. Twenty log eight is
        // +18.06, and getting that would mean the model had started treating
        // separate frequencies as phase-related.
        val perBand = bands.associateWith { 80f }
        val total = SceneViewModel.broadbandSplDb(perBand, SceneViewModel.WEIGHTING_Z)!!
        assertEquals(80f + 10f * log10(8f), total, 0.05f)
        assertTrue("pressure summation would give about 98 dB, got $total", total < 92f)
    }

    @Test
    fun `one loud band dominates the total`() {
        val perBand = bands.associateWith { 40f } + (1000 to 100f)
        val total = SceneViewModel.broadbandSplDb(perBand, SceneViewModel.WEIGHTING_Z)!!
        assertEquals("60 dB of headroom leaves the rest inaudible", 100f, total, 0.1f)
    }

    @Test
    fun `A-weighting pulls down a bass-heavy spectrum and barely touches a mid one`() {
        val bass = bands.associateWith { if (it <= 125) 100f else 60f }
        val mid = bands.associateWith { if (it in 500..2000) 100f else 60f }
        val a = SceneViewModel.WEIGHTING_A
        val z = SceneViewModel.WEIGHTING_Z

        val bassDrop = SceneViewModel.broadbandSplDb(bass, z)!! - SceneViewModel.broadbandSplDb(bass, a)!!
        val midDrop = SceneViewModel.broadbandSplDb(mid, z)!! - SceneViewModel.broadbandSplDb(mid, a)!!
        assertTrue("bass-heavy should lose a lot to A: $bassDrop dB", bassDrop > 14f)
        assertTrue("mid-heavy should lose almost nothing: $midDrop dB", midDrop < 1.5f)
    }

    @Test
    fun `the C minus A gap measures how much low end there is`() {
        // The reason both curves exist. A bass-heavy spectrum shows a wide gap;
        // a mid-dominated one shows almost none.
        fun gap(spectrum: Map<Int, Float>) =
            SceneViewModel.broadbandSplDb(spectrum, SceneViewModel.WEIGHTING_C)!! -
                SceneViewModel.broadbandSplDb(spectrum, SceneViewModel.WEIGHTING_A)!!
        val bass = bands.associateWith { if (it <= 125) 100f else 60f }
        val mid = bands.associateWith { if (it in 500..2000) 100f else 60f }
        assertTrue("bass-heavy gap ${gap(bass)}", gap(bass) > 14f)
        assertTrue("mid-heavy gap ${gap(mid)}", gap(mid) < 2f)
    }

    @Test
    fun `nothing to sum is nothing, not silence at zero decibels`() {
        assertNull(SceneViewModel.broadbandSplDb(emptyMap(), SceneViewModel.WEIGHTING_Z))
    }

    @Test
    fun `an infinite band is skipped rather than poisoning the total`() {
        val perBand = mapOf(1000 to 90f, 2000 to Float.NEGATIVE_INFINITY, 4000 to 90f)
        val total = SceneViewModel.broadbandSplDb(perBand, SceneViewModel.WEIGHTING_Z)!!
        assertEquals(90f + 10f * log10(2f), total, 0.05f)
    }

    // ── Wiring ───────────────────────────────────────────────────────────────

    @Test
    fun `broadband analysis covers every band, single-band covers one`() {
        val v = SceneViewModel()
        v.setBandHz(2000)
        assertEquals(listOf(2000), v.analysisBands())
        v.setSignalType("SPECTRUM")
        assertEquals(bands, v.analysisBands())
    }

    @Test
    fun `weighting is remembered and only known curves are accepted`() {
        val v = SceneViewModel()
        assertEquals(SceneViewModel.WEIGHTING_Z, v.weighting.value)
        v.setWeighting(SceneViewModel.WEIGHTING_A)
        assertEquals(SceneViewModel.WEIGHTING_A, v.weighting.value)
        v.setWeighting("dB(HL)")
        assertEquals(SceneViewModel.WEIGHTING_A, v.weighting.value)

        v.addSpeaker(1f, 1f)
        val restored = SceneViewModel()
        restored.importSceneJson(v.exportSceneJson())
        assertEquals(SceneViewModel.WEIGHTING_A, restored.weighting.value)
    }

    @Test
    fun `on a single band a weighting is only an offset, which is why it needed broadband`() {
        // Weighting one band shifts every point by the same amount, so the map
        // is identical and only the printed number moves. That is the reason
        // this feature required summing the spectrum to be worth having.
        val perBand = mapOf(125 to 90f)
        val z = SceneViewModel.broadbandSplDb(perBand, SceneViewModel.WEIGHTING_Z)!!
        val a = SceneViewModel.broadbandSplDb(perBand, SceneViewModel.WEIGHTING_A)!!
        assertEquals(SceneViewModel.weightingDb(SceneViewModel.WEIGHTING_A, 125), a - z, 0.05f)
    }

    // ── End to end ───────────────────────────────────────────────────────────

    @Test
    fun `broadband reads higher than one band, because it is summing the rest`() {
        val oneBand = splOf(scene(broadband = false))
        val broad = splOf(scene(broadband = true))
        assertTrue("broadband ($broad) should exceed a single band ($oneBand)", broad > oneBand)
        assertTrue("but not by more than eight equal bands could give",
                   broad - oneBand < 10f * log10(8f) + 3f)
    }

    @Test
    fun `A-weighting lowers a broadband reading and Z does not`() {
        val z = splOf(scene(broadband = true, weighting = SceneViewModel.WEIGHTING_Z))
        val a = splOf(scene(broadband = true, weighting = SceneViewModel.WEIGHTING_A))
        val c = splOf(scene(broadband = true, weighting = SceneViewModel.WEIGHTING_C))
        assertTrue("A should sit below Z: $a vs $z", a < z)
        assertTrue("C should sit between them: $c", c in a..z)
    }

    @Test
    fun `weighting leaves a single-band map exactly where it was`() {
        // The map is per band, so a weighting cannot move it. This is the test
        // that would have caught shipping weighting without the broadband work
        // and calling it done.
        val z = splOf(scene(broadband = false, weighting = SceneViewModel.WEIGHTING_Z))
        val a = splOf(scene(broadband = false, weighting = SceneViewModel.WEIGHTING_A))
        assertEquals(z, a, 1e-3f)
    }
}
