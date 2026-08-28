package com.droidacoustic.pro.scene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Measured directivity through a line array, and the aiming that points it.
 *
 * An array is not one loudspeaker. Each box is aimed differently by the splay
 * between it and its neighbour, so each one meets the listener at its own angle
 * and must be read off the balloon at that angle. Reading the array once at its
 * centre throws away the splay, which is the only thing an array is really for.
 *
 * These tests also pin two sign conventions that were wrong, both of which are
 * invisible to a symmetric model and only bite once real measured data arrives:
 * the aim direction, and whether the listener is above or below the axis.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LineArrayDirectivityTest {

    private val live = mutableListOf<SceneViewModel>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        // These tests drive real recalculation, which runs on Dispatchers.Default
        // and resumes on Main. Leaving that in flight breaks the *next* test
        // class's setMain, so every view model created here is stopped first.
        live.forEach { it.cancelAnalysisJobs() }
        live.clear()
        Dispatchers.resetMain()
    }

    private fun track(vm: SceneViewModel): SceneViewModel = vm.also { live += it }

    /** Builds a CLF2 document from a function of polar angle and arc index. */
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
                    appendLine((0..36).joinToString("\t") { t ->
                        "%.2f".format(value(t * 5, arc * 5))
                    })
                }
            }
        }

    /** Same everywhere, poles included: isolates "how many times was this applied". */
    private fun flatTab(model: String, db: Double) = tab(model) { _, _ -> db }

    /** Narrows with polar angle, so each element's own aim changes its answer. */
    private fun conicalTab(model: String) = tab(model) { theta, _ -> -0.5 * theta }

    /** Flat above the axis, 20 dB down below it. Asymmetric on purpose. */
    private fun asymmetricTab(model: String) = tab(model) { theta, phi ->
        when {
            theta == 0 -> 0.0                       // on-axis pole, shared by all arcs
            theta == 180 -> -20.0                   // rear pole, shared by all arcs
            phi in 91..269 -> -20.0                 // below the axis
            else -> 0.0                             // above it
        }
    }

    private fun spl(v: SceneViewModel): Float {
        v.recalculateSignal()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            v.combinedSplDb.value?.let { return it }
            Thread.sleep(10)
        }
        error("no SPL computed within 5 s")
    }

    /** An array flown at [heightM], listener [distM] away at ear height. */
    private fun arrayScene(
        tabDoc: String? = null,
        elements: Int = 8,
        aimDeg: Float = 0f,
        splayDeg: Float = 0f,
        heightM: Float = 6f,
        distM: Float = 20f
    ): SceneViewModel = track(SceneViewModel()).apply {
        setBandHz(1000)
        tabDoc?.let { importClfTabText(it) }
        moveListener(distM, 0f)
        addSpeaker(0f, 0f)
        val id = speakers.value.single().id
        setSpeakerArrayElements(id, elements)
        setSpeakerPosition(id, 0f, heightM, 0f)
        setSpeakerArrayAim(id, aimDeg)
        if (splayDeg != 0f) setSpeakerArraySplay(id, splayDeg)
    }

    // ── Aiming ───────────────────────────────────────────────────────────────

    @Test
    fun `aiming an array down points it at an audience below`() {
        // The listener sits about 13.5 degrees below a box flown at 6 m, 20 m
        // away. arrayAimDeg is down-positive, so the level must peak near +13.5
        // and fall away either side. Subtracting the aim rather than the axis
        // inverted this: aiming at the audience aimed away from them.
        val levels = listOf(-20f, -10f, 0f, 10f, 13.5f, 20f, 30f).map { aim ->
            aim to spl(arrayScene(aimDeg = aim))
        }
        val best = levels.maxByOrNull { it.second }!!
        assertTrue("expected the peak near +13.5 deg, got $levels", best.first in 10f..20f)
    }

    @Test
    fun `aiming away from the listener costs level`() {
        val at = spl(arrayScene(aimDeg = 13.5f))
        val away = spl(arrayScene(aimDeg = -13.5f))
        assertTrue("aiming away ($away) should be quieter than aiming at ($at)", away < at - 6f)
    }

    // ── Measured data reaches each element ───────────────────────────────────

    @Test
    fun `an array uses measured data at all`() {
        val measured = spl(arrayScene(conicalTab("Cone Box"), aimDeg = 13.5f))
        val synthetic = spl(arrayScene(null, aimDeg = 13.5f))
        assertNotEquals("measured data made no difference to an array", synthetic, measured, 0.5f)
    }

    @Test
    fun `splay changes the answer, so each element is read at its own angle`() {
        // Splay only exists per element. If the balloon were read once at the
        // array's centre, opening the splay could not change anything.
        val straight = spl(arrayScene(conicalTab("Cone Box"), aimDeg = 13.5f, splayDeg = 0f))
        val splayed = spl(arrayScene(conicalTab("Cone Box"), aimDeg = 13.5f, splayDeg = 6f))
        assertNotEquals("splay was ignored: the balloon was read once, not per element",
                        straight, splayed, 0.5f)
    }

    @Test
    fun `measured attenuation is applied once, not once per path`() {
        // Two flat balloons 10 dB apart. Everything else about the two scenes is
        // identical, so the totals must differ by exactly 10 dB. Twenty would
        // mean the balloon was applied both per element and again at box level.
        val loud = spl(arrayScene(flatTab("Flat Zero", 0.0), aimDeg = 13.5f))
        val quiet = spl(arrayScene(flatTab("Flat Down Ten", -10.0), aimDeg = 13.5f))
        assertEquals("expected exactly 10 dB between the two balloons",
                     10f, loud - quiet, 0.2f)
    }

    // ── Signed angles ────────────────────────────────────────────────────────

    @Test
    fun `a single box tells above the axis from below it`() {
        // Same 13.5 degrees off axis either way. The fixture is 20 dB down below
        // the axis and flat above it, so mirroring the sign would return the
        // same number for both.
        val below = spl(arrayScene(asymmetricTab("Asym Box"), elements = 1, aimDeg = 0f))
        val above = spl(arrayScene(asymmetricTab("Asym Box"), elements = 1, aimDeg = 27f))
        assertTrue("asymmetry was lost: below=$below above=$above", above - below > 10f)
    }

    @Test
    fun `a signed angular delta keeps its sign while the unsigned one does not`() {
        val v = track(SceneViewModel())
        assertEquals(-30f, v.signedAngularDeltaDeg(0f, 30f), 1e-3f)
        assertEquals(30f, v.signedAngularDeltaDeg(30f, 0f), 1e-3f)
        assertEquals(30f, v.angularDeltaDeg(0f, 30f), 1e-3f)
        // Still takes the short way round the circle.
        assertEquals(-20f, v.signedAngularDeltaDeg(350f, 10f), 1e-3f)
    }

    @Test
    fun `horizontal off-axis angle is measured from where the box is panned`() {
        val v = track(SceneViewModel())
        val spk = PlacedSpeaker(id = 0, x = 0f, z = 0f, panDeg = 0f)
        // Pan 0 faces +X. A listener at +Z is 90 degrees round.
        assertEquals(90f, v.horizontalOffAxisDeg(spk, 0f, 0f, 0f, 10f), 1e-2f)
        assertEquals(-90f, v.horizontalOffAxisDeg(spk, 0f, 0f, 0f, -10f), 1e-2f)
        assertEquals(0f, v.horizontalOffAxisDeg(spk, 0f, 0f, 10f, 0f), 1e-2f)
        // Panning onto the listener brings them back on axis.
        val panned = spk.copy(panDeg = 90f)
        assertEquals(0f, v.horizontalOffAxisDeg(panned, 0f, 0f, 0f, 10f), 1e-2f)
    }
}
