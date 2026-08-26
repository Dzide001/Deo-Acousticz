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
import kotlin.math.sqrt

/**
 * Floor-bounce comb filtering - the two-ray image-source model.
 *
 * This one is real physics with an exact answer, so these tests assert values
 * rather than brackets. A mirror source sits at -y, its path is longer by
 * `delta`, and the two arrivals sum with a phase difference of 2*pi*f*delta/c.
 * Constructive peaks land where delta is a whole wavelength, nulls where it is
 * an odd half wavelength, and the null depth is set by how much pressure the
 * floor returns - r = sqrt(1 - alpha).
 *
 * The one invented part is the [-9, +3] dB clamp, which the source explains as
 * keeping coarse grids from combing wildly. It is a display guard, not physics:
 * a hard floor really does give +6 dB in phase and a near-total null out of
 * phase. Tests that would exceed the clamp assert the clamp and say so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FloorBounceTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(bandHz: Int = 1000, floorAlpha: Float = 0.15f) = SceneViewModel().apply {
        setBandHz(bandHz)
        setTemperatureC(20f)
        setFloorAbsorption(floorAlpha)
    }

    /** Speed of sound the model uses at 20 C. */
    private val c = 331.3f + 0.606f * 20f

    /** Both ends at height [h], separated by [d] along z: the classic two-ray case. */
    private fun bounce(v: SceneViewModel, h: Float, d: Float) =
        v.floorBoundaryInterferenceDb(0f, h, 0f, 0f, h, d)

    /** Path-length excess of the floor-reflected ray for that same geometry. */
    private fun excessFor(h: Float, d: Float) = sqrt(d * d + 4f * h * h) - d

    /** The height that makes the reflected ray arrive [excess] metres late. */
    private fun heightForExcess(excess: Float, d: Float): Float {
        val reflected = d + excess
        return sqrt((reflected * reflected - d * d) / 4f)
    }

    // ── Constructive and destructive extremes ────────────────────────────────

    @Test
    fun `a source on the floor has no path difference to cancel against`() {
        // The image source is co-located with the real one, so the two arrivals
        // are always in phase - the boundary can only add level, never subtract.
        // With a reflective floor that is +6 dB, which the clamp trims to +3.
        val v = vm(floorAlpha = 0.01f)
        assertEquals(3f, bounce(v, h = 0f, d = 10f), 1e-3f)
    }

    @Test
    fun `a half wavelength of extra path puts the reflection in anti-phase`() {
        // alpha 0.75 returns half the pressure (r = sqrt(0.25) = 0.5), so the
        // null is 20*log10(1 - 0.5) = -6.02 dB - deep, but inside the clamp,
        // which lets this assert the real number instead of the guard rail.
        val v = vm(floorAlpha = 0.75f)
        val h = heightForExcess(c / 1000f / 2f, d = 10f)
        assertEquals(-6.02f, bounce(v, h, d = 10f), 0.05f)
    }

    @Test
    fun `a whole wavelength of extra path brings the reflection back in phase`() {
        val v = vm(floorAlpha = 0.75f)
        val h = heightForExcess(c / 1000f, d = 10f)
        // 20*log10(1 + 0.5) = +3.52 dB, which the clamp trims to +3.
        assertEquals(3f, bounce(v, h, d = 10f), 1e-3f)
    }

    // ── The comb is at the right frequency ───────────────────────────────────

    /**
     * Height of the first null as the source rises off the floor at a fixed
     * distance.
     *
     * Every null in the comb is the same depth, so "the lowest point in the
     * sweep" picks an arbitrary one - whichever the sampling grid happens to
     * catch closest to its centre. The first null is the one with a known
     * answer, so this walks up from the constructive peak at h = 0 and stops at
     * the first turn back upwards.
     */
    private fun firstNullHeight(v: SceneViewModel, d: Float): Float {
        val step = 0.0025f
        var h = step
        var previousH = 0f
        var previousDb = bounce(v, 0f, d)
        while (h <= 4f) {
            val db = bounce(v, h, d)
            if (db > previousDb && previousDb < -3f) return previousH
            previousH = h
            previousDb = db
            h += step
        }
        throw AssertionError("no null found below 4 m")
    }

    @Test
    fun `the first null sits where the extra path is half a wavelength`() {
        // The physical content of a two-ray model is where the notches land.
        // alpha 0.75 keeps the null at -6.02 dB, inside the display clamp; with a
        // harder floor it bottoms out on the clamp and spreads into a flat
        // plateau with no single well-defined minimum.
        val v = vm(bandHz = 1000, floorAlpha = 0.75f)
        val d = 12f
        val excess = excessFor(firstNullHeight(v, d), d)
        val halfWave = c / 1000f / 2f
        assertEquals("first null should be at half a wavelength of excess path",
                     halfWave, excess, halfWave * 0.05f)
    }

    @Test
    fun `the comb scales with wavelength across the spectrum`() {
        // Same geometry, every band: the first null must always sit at half a
        // wavelength of excess path, which is the whole claim a two-ray model
        // makes about frequency.
        val d = 12f
        SceneViewModel.SUPPORTED_BANDS_HZ.filter { it >= 250 }.forEach { band ->
            val v = vm(bandHz = band, floorAlpha = 0.75f)
            val excess = excessFor(firstNullHeight(v, d), d)
            val halfWave = c / band.toFloat() / 2f
            assertEquals("$band Hz", halfWave, excess, halfWave * 0.06f)
        }
    }

    @Test
    fun `an octave up moves the first null down towards the floor`() {
        val d = 12f
        val at1k = firstNullHeight(vm(bandHz = 1000, floorAlpha = 0.75f), d)
        val at2k = firstNullHeight(vm(bandHz = 2000, floorAlpha = 0.75f), d)
        assertTrue("2 kHz null at $at2k should be below the 1 kHz null at $at1k", at2k < at1k)
        // Excess path grows as roughly h^2 at these ranges, so halving it moves
        // the null to about 1/sqrt(2) of the height, not to half of it.
        assertEquals(at1k / sqrt(2f), at2k, at1k * 0.08f)
    }

    // ── Absorption sets the depth of the notch ───────────────────────────────

    @Test
    fun `an absorptive floor barely combs at all`() {
        // alpha 0.99 returns r = 0.1 of the pressure: 20*log10(1.1) = +0.83 dB
        // at the peak, and the whole comb collapses towards flat.
        val v = vm(floorAlpha = 0.99f)
        assertEquals(0.83f, bounce(v, h = 0f, d = 10f), 0.02f)
    }

    @Test
    fun `a more absorptive floor shallows the null it produces`() {
        val h = heightForExcess(c / 1000f / 2f, d = 10f)
        val hard = bounce(vm(floorAlpha = 0.3f), h, d = 10f)
        val soft = bounce(vm(floorAlpha = 0.9f), h, d = 10f)
        assertTrue("softer floor should null less deeply: $soft vs $hard", soft > hard)
    }

    @Test
    fun `absorption moves the peak down monotonically`() {
        var previous = Float.MAX_VALUE
        listOf(0.01f, 0.2f, 0.4f, 0.6f, 0.8f, 0.99f).forEach { alpha ->
            val db = bounce(vm(floorAlpha = alpha), h = 0f, d = 10f)
            assertTrue("alpha $alpha gave $db, not below $previous", db <= previous + 1e-4f)
            previous = db
        }
    }

    // ── Guard rails ──────────────────────────────────────────────────────────

    @Test
    fun `the result never escapes the display clamp`() {
        val heights = listOf(0f, 0.2f, 0.7f, 1.4f, 3f, 8f)
        val distances = listOf(0.5f, 2f, 7f, 25f, 90f)
        SceneViewModel.SUPPORTED_BANDS_HZ.forEach { band ->
            listOf(0.01f, 0.5f, 0.99f).forEach { alpha ->
                val v = vm(bandHz = band, floorAlpha = alpha)
                heights.forEach { h ->
                    distances.forEach { d ->
                        val db = bounce(v, h, d)
                        assertTrue("$band Hz, alpha $alpha, h $h, d $d gave $db",
                                   db >= -9f && db <= 3f)
                        assertTrue("$band Hz, alpha $alpha, h $h, d $d gave NaN", !db.isNaN())
                    }
                }
            }
        }
    }

    @Test
    fun `at long range the two paths converge and the notches stop`() {
        // A documented limit of the model, not a bug: with the source and
        // receiver at the same height, the excess path tends to zero with
        // distance, so a far listener always sees the in-phase sum. Real systems
        // avoid this through directivity, which this function does not model.
        val v = vm(floorAlpha = 0.01f)
        assertEquals(3f, bounce(v, h = 1.6f, d = 400f), 1e-3f)
    }

    @Test
    fun `a receiver at the source position is handled rather than dividing by zero`() {
        val v = vm()
        val db = v.floorBoundaryInterferenceDb(0f, 1.6f, 0f, 0f, 1.6f, 0f)
        assertTrue("degenerate geometry gave $db", !db.isNaN() && db >= -9f && db <= 3f)
    }
}
