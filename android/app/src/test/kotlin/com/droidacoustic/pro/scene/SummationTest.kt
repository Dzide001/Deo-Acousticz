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

/**
 * Summation of multiple arrivals at a point.
 *
 * The textbook answers are the anchors: two equal, in-phase sources sum to +6 dB
 * coherently and +3 dB incoherently, and two equal sources in anti-phase cancel.
 * What the app actually returns is a blend of the two, weighted by a
 * `coherentWeight` derived from the UI's bandwidth or resolution setting. That
 * weight has no physical derivation - it is a smoothing control - so these tests
 * bracket the result between the two physical answers rather than pretending the
 * blend is itself physics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummationTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SceneViewModel().apply {
        setBandHz(1000)
        setSignalType("BAND")
        setSignalInterferenceEnabled(true)
    }

    private fun arrival(splDb: Float, distanceM: Float = 10f, delayMs: Float = 0f, inverted: Boolean = false) =
        SceneViewModel.CoherentContribution(splDb, distanceM, delayMs, inverted)

    @Test
    fun `nothing arriving is silence, not a crash`() {
        assertEquals(0f, vm().coherentSumDb(emptyList()), 0f)
    }

    @Test
    fun `a single arrival passes through at its own level`() {
        // Coherent and incoherent agree for one source, so the blend weight cannot
        // matter here - any answer other than the input level is a bug.
        val v = vm()
        assertEquals(94f, v.coherentSumDb(listOf(arrival(94f))), 1e-2f)
    }

    @Test
    fun `two identical arrivals land between the incoherent and coherent answers`() {
        val v = vm()
        val one = v.coherentSumDb(listOf(arrival(90f)))
        val two = v.coherentSumDb(listOf(arrival(90f), arrival(90f)))
        val gain = two - one
        // +3 dB is power summation, +6 dB is pressure summation. The blend must sit
        // in between, inclusive, and must never lose level by adding a source.
        assertTrue("expected +3..+6 dB from doubling, got $gain", gain >= 2.9f && gain <= 6.1f)
    }

    @Test
    fun `an anti-phase twin cancels rather than reinforcing`() {
        val v = vm()
        val single = v.coherentSumDb(listOf(arrival(90f)))
        val opposed = v.coherentSumDb(listOf(arrival(90f), arrival(90f, inverted = true)))
        assertTrue("polarity inversion should not raise level: $opposed vs $single", opposed < single)
    }

    @Test
    fun `below 163 Hz with interference disabled the result is pure power summation`() {
        // The documented escape hatch: no phase modelling in the low end.
        val v = SceneViewModel().apply {
            setBandHz(63)
            setSignalInterferenceEnabled(false)
        }
        val one = v.coherentSumDb(listOf(arrival(90f)))
        val two = v.coherentSumDb(listOf(arrival(90f), arrival(90f)))
        assertEquals("expected exactly +3.01 dB", 3.0103f, two - one, 1e-2f)
    }

    @Test
    fun `adding a much quieter source barely moves the total`() {
        val v = vm()
        val loud = v.coherentSumDb(listOf(arrival(100f)))
        val both = v.coherentSumDb(listOf(arrival(100f), arrival(60f)))
        assertTrue("a 40 dB quieter source moved the sum by ${both - loud} dB", kotlin.math.abs(both - loud) < 1f)
    }
}
