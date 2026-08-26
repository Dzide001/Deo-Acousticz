package com.droidacoustic.pro.scene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Obstruction shadowing and edge diffraction.
 *
 * The model marches the source-to-listener ray through the venue's solids,
 * counts how many samples land inside one, how deep the deepest sample sits
 * below a top surface, and turns that into a loss which is then partly refunded
 * by three separate recovery terms - low frequency, shallow shadow, and a
 * knife-edge path over the nearest top edge.
 *
 * None of those coefficients come from a diffraction theory. Maekawa's number,
 * the actual textbook answer here, is a function of the Fresnel number
 * N = 2*delta/lambda, and does not appear anywhere in this code. So these tests
 * pin the behaviours that a diffraction model must have - low frequencies bend
 * around obstacles, deeper shadows cost more, clear paths cost nothing, results
 * stay bounded - and deliberately do not pin the numbers, which are placeholders
 * for a Fresnel-based replacement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObstructionTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    /**
     * A venue whose only solid is one wall across the middle. The default stage
     * sits at z = -10 and is nowhere near the test path, which runs along z at
     * x = 0 from z = -5 to z = +5.
     */
    private fun venueWithWall(
        bandHz: Int = 1000,
        wallHeightM: Float = 4f
    ) = SceneViewModel().apply {
        setBandHz(bandHz)
        setVenueSize(40f, 40f)
        setStageCenter(0f, -18f)          // shove the stage clear of the path
        addVenueBlock("WALL", 0f, 0f)     // 10 m wide, 0.5 m deep, 4 m tall
        val id = venueGeometry.value.blocks.first().id
        setVenueBlockHeight(id, wallHeightM)
        setVenueBlockThickness(id, wallHeightM)   // sits on the floor
    }

    private fun emptyVenue(bandHz: Int = 1000) = SceneViewModel().apply {
        setBandHz(bandHz)
        setVenueSize(40f, 40f)
        setStageCenter(0f, -18f)
    }

    /** Straight shot along z at height [y], crossing x = 0 where the wall stands. */
    private fun across(v: SceneViewModel, y: Float) =
        v.estimateObstructionAttenuationDb(0f, y, -5f, 0f, y, 5f)

    // ── Clear paths ──────────────────────────────────────────────────────────

    @Test
    fun `an empty room costs nothing`() {
        assertEquals(0f, across(emptyVenue(), y = 2f), 1e-4f)
    }

    @Test
    fun `a wall off to the side is not in the way`() {
        val v = emptyVenue()
        v.addVenueBlock("WALL", 25f, 25f)
        assertEquals(0f, across(v, y = 2f), 1e-4f)
    }

    @Test
    fun `removing the wall restores the clear-path answer`() {
        val v = venueWithWall()
        assertTrue(across(v, y = 2f) > 0f)
        v.removeVenueBlock(v.venueGeometry.value.blocks.first().id)
        assertEquals(0f, across(v, y = 2f), 1e-4f)
    }

    @Test
    fun `flying well over the wall costs nothing`() {
        // 0.45 m of clearance is where the grazing term runs out.
        assertEquals(0f, across(venueWithWall(), y = 4.8f), 1e-4f)
    }

    // ── Grazing the top edge ─────────────────────────────────────────────────

    @Test
    fun `skimming just above the top edge costs something but not much`() {
        val loss = across(venueWithWall(), y = 4.2f)
        assertTrue("grazing loss was $loss", loss > 0f && loss <= 8f)
    }

    @Test
    fun `the grazing penalty fades as the path climbs away from the edge`() {
        val v = venueWithWall()
        val tight = across(v, y = 4.05f)
        val looser = across(v, y = 4.25f)
        val clear = across(v, y = 4.5f)
        assertTrue("expected $tight > $looser > $clear", tight > looser && looser > clear)
    }

    // ── Blocked paths ────────────────────────────────────────────────────────

    @Test
    fun `a wall through the path shadows it substantially`() {
        val loss = across(venueWithWall(), y = 2f)
        assertTrue("a 4 m wall across the path only cost $loss dB", loss > 15f)
    }

    @Test
    fun `line of sight agrees with the attenuation it is derived from`() {
        val v = venueWithWall()
        assertTrue("blocked path should read as blocked",
                   v.isLineOfSightBlocked(0f, 2f, -5f, 0f, 2f, 5f))
        assertFalse("path over the wall should read as clear",
                    v.isLineOfSightBlocked(0f, 4.8f, -5f, 0f, 4.8f, 5f))
    }

    @Test
    fun `a taller wall casts a deeper shadow`() {
        val short = across(venueWithWall(wallHeightM = 2.5f), y = 2f)
        val tall = across(venueWithWall(wallHeightM = 6f), y = 2f)
        assertTrue("6 m wall ($tall dB) should beat 2.5 m wall ($short dB)", tall > short)
    }

    @Test
    fun `deeper below the top edge costs more than just under it`() {
        val v = venueWithWall(wallHeightM = 6f)
        val justUnder = across(v, y = 5.9f)
        val wellUnder = across(v, y = 1.5f)
        assertTrue("expected $wellUnder > $justUnder", wellUnder > justUnder)
    }

    // ── Frequency dependence: the point of a diffraction model ───────────────

    @Test
    fun `low frequencies bend around the wall and high frequencies do not`() {
        val low = across(venueWithWall(bandHz = 63), y = 2f)
        val mid = across(venueWithWall(bandHz = 1000), y = 2f)
        val high = across(venueWithWall(bandHz = 8000), y = 2f)
        assertTrue("63 Hz cost $low, 1 kHz cost $mid", low < mid)
        assertTrue("1 kHz cost $mid, 8 kHz cost $high", mid < high)
    }

    @Test
    fun `shadowing rises with frequency across every band`() {
        var previous = -1f
        SceneViewModel.SUPPORTED_BANDS_HZ.forEach { band ->
            val loss = across(venueWithWall(bandHz = band), y = 2f)
            assertTrue("$band Hz cost $loss, below the band under it ($previous)",
                       loss >= previous - 1e-3f)
            previous = loss
        }
    }

    @Test
    fun `the deep bass barely notices a domestic-sized obstacle`() {
        // 63 Hz is a 5.4 m wavelength; a 4 m wall is not an obstacle to it.
        val loss = across(venueWithWall(bandHz = 63), y = 2f)
        assertTrue("63 Hz lost $loss dB behind a 4 m wall", loss < 6f)
    }

    // ── Sampling and guard rails ─────────────────────────────────────────────

    @Test
    fun `the analysis profile changes sampling density, not the physics`() {
        val losses = SceneViewModel.ANALYSIS_PROFILES.map { profile ->
            val v = venueWithWall()
            v.setAnalysisProfile(profile)
            profile to across(v, y = 2f)
        }
        val spread = losses.maxOf { it.second } - losses.minOf { it.second }
        assertTrue("profiles disagreed by $spread dB: $losses", spread < 3f)
    }

    @Test
    fun `the result stays inside its bounds over a sweep of geometry`() {
        SceneViewModel.SUPPORTED_BANDS_HZ.forEach { band ->
            listOf(1f, 4f, 9f).forEach { wallH ->
                val v = venueWithWall(bandHz = band, wallHeightM = wallH)
                listOf(0.1f, 0.9f, 2f, 3.5f, 5f, 9.5f, 14f).forEach { y ->
                    val loss = across(v, y)
                    assertTrue("$band Hz, wall $wallH m, y $y gave $loss", loss in 0f..55f)
                    assertTrue("$band Hz, wall $wallH m, y $y gave NaN", !loss.isNaN())
                }
            }
        }
    }

    @Test
    fun `a zero-length path is handled rather than dividing by zero`() {
        val v = venueWithWall()
        val loss = v.estimateObstructionAttenuationDb(0f, 2f, 0f, 0f, 2f, 0f)
        assertTrue("degenerate path gave $loss", !loss.isNaN() && loss in 0f..55f)
    }

    @Test
    fun `shadowing is symmetric when source and listener swap`() {
        // Reciprocity: sound does not care which end it started from.
        val v = venueWithWall()
        val forward = v.estimateObstructionAttenuationDb(0f, 2f, -5f, 0f, 3f, 5f)
        val reverse = v.estimateObstructionAttenuationDb(0f, 3f, 5f, 0f, 2f, -5f)
        assertEquals(forward, reverse, 0.5f)
    }
}
