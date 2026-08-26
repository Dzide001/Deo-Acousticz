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
import kotlin.math.exp

/**
 * The intelligibility readout - stored and displayed as "STI", which it is not.
 *
 * Real STI (IEC 60268-16) measures how much of a modulated signal's envelope
 * survives the room, across seven octave bands and fourteen modulation rates.
 * This function takes three summary numbers - average coverage level, coverage
 * standard deviation, and RT60 - and blends them 45/25/30 into a 0..1 score.
 * There is no modulation transfer function anywhere in it.
 *
 * That makes these tests a specification of a house heuristic rather than of an
 * acoustic standard, and they are written to say so: they pin the weighting, the
 * clamps, and the monotonic directions, so that a real STI implementation later
 * has something explicit to replace. Anything reported to a user as "STI 0.62"
 * from this function is a house number wearing a standard's name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntelligibilityScoreTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val vm = SceneViewModel()

    private fun score(avgDb: Float, devDb: Float, rt60S: Float) =
        vm.intelligibilityScore(avgDb, devDb, rt60S)

    // The inputs that zero and max out each of the three axes.
    private val quietest = 58f      // level score floor
    private val loudest = 80f       // level score ceiling: (80 - 58) / 22 = 1
    private val patchiest = 10f     // uniformity floor: 1 - 10/10 = 0
    private val idealRt = 0.9f
    private val worstRt = 2.5f      // 0.9 + 1.6, the far end of the penalty ramp

    // ── The three weights ────────────────────────────────────────────────────

    @Test
    fun `everything ideal scores a perfect one`() {
        assertEquals(1f, score(loudest, 0f, idealRt).sti, 1e-4f)
    }

    @Test
    fun `everything at its worst scores zero`() {
        assertEquals(0f, score(quietest, patchiest, worstRt).sti, 1e-4f)
    }

    @Test
    fun `level alone is worth forty-five percent`() {
        assertEquals(0.45f, score(loudest, patchiest, worstRt).sti, 1e-4f)
    }

    @Test
    fun `evenness alone is worth twenty-five percent`() {
        assertEquals(0.25f, score(quietest, 0f, worstRt).sti, 1e-4f)
    }

    @Test
    fun `reverberation alone is worth thirty percent`() {
        assertEquals(0.30f, score(quietest, patchiest, idealRt).sti, 1e-4f)
    }

    // ── Directions ───────────────────────────────────────────────────────────

    @Test
    fun `louder coverage scores better until the ceiling`() {
        var previous = -1f
        listOf(50f, 58f, 63f, 70f, 76f, 80f).forEach { avg ->
            val sti = score(avg, 3f, idealRt).sti
            assertTrue("$avg dB scored $sti, below $previous", sti >= previous)
            previous = sti
        }
        // Past 80 dB the level term is pinned - more level cannot buy more score.
        assertEquals(score(80f, 3f, idealRt).sti, score(115f, 3f, idealRt).sti, 1e-5f)
    }

    @Test
    fun `patchier coverage scores worse`() {
        var previous = Float.MAX_VALUE
        listOf(0f, 2f, 5f, 8f, 10f, 25f).forEach { dev ->
            val sti = score(72f, dev, idealRt).sti
            assertTrue("deviation $dev scored $sti, above $previous", sti <= previous)
            previous = sti
        }
    }

    @Test
    fun `the reverberation penalty is symmetric about its target`() {
        // Too dry is penalised exactly as much as too live, which is a choice the
        // model makes rather than something rooms actually do.
        val dry = score(72f, 3f, idealRt - 0.5f).sti
        val live = score(72f, 3f, idealRt + 0.5f).sti
        assertEquals(dry, live, 1e-4f)
    }

    @Test
    fun `reverberation further from target scores worse`() {
        var previous = Float.MAX_VALUE
        listOf(0.9f, 1.2f, 1.6f, 2.1f, 2.5f).forEach { rt ->
            val sti = score(72f, 3f, rt).sti
            assertTrue("RT60 $rt scored $sti, above $previous", sti <= previous)
            previous = sti
        }
    }

    // ── Clamps ───────────────────────────────────────────────────────────────

    @Test
    fun `absurd inputs still produce a score inside zero to one`() {
        listOf(-200f, 0f, 58f, 90f, 400f).forEach { avg ->
            listOf(0f, 10f, 500f).forEach { dev ->
                listOf(0f, 0.9f, 12f).forEach { rt ->
                    val s = score(avg, dev, rt)
                    assertTrue("($avg, $dev, $rt) gave ${s.sti}", s.sti in 0f..1f)
                    assertTrue("($avg, $dev, $rt) gave ${s.alconsPct}", s.alconsPct in 0f..100f)
                }
            }
        }
    }

    // ── Quality labels ───────────────────────────────────────────────────────

    @Test
    fun `the quality label tracks the bands it is cut into`() {
        // Probed comfortably inside each band rather than on its edges: the score
        // is assembled from float arithmetic, so inputs engineered to land exactly
        // on 0.60 can arrive as 0.59999999 and legitimately read as the band below.
        // Edge behaviour is left to the monotonicity test rather than pinned here.
        assertEquals("Excellent", score(loudest, 0f, idealRt).quality)      // 1.00
        assertEquals("Good", score(loudest, 0f, worstRt).quality)           // 0.70
        assertEquals("Fair", score(loudest, 7f, worstRt).quality)           // 0.525
        assertEquals("Poor", score(69f, 4f, worstRt).quality)               // 0.375
        assertEquals("Bad", score(quietest, 8f, worstRt).quality)           // 0.05
    }

    @Test
    fun `a better score never gets a worse label`() {
        val order = listOf("Bad", "Poor", "Fair", "Good", "Excellent")
        var previousRank = 0
        var previousSti = -1f
        var avg = 40f
        while (avg <= 100f) {
            val s = score(avg, 3f, idealRt)
            val rank = order.indexOf(s.quality)
            assertTrue("unknown label ${s.quality}", rank >= 0)
            assertTrue("score went backwards at $avg dB", s.sti >= previousSti)
            assertTrue("label went backwards at $avg dB: ${s.quality} after ${order[previousRank]}",
                       rank >= previousRank)
            previousRank = rank
            previousSti = s.sti
            avg += 0.5f
        }
    }

    // ── %ALcons ──────────────────────────────────────────────────────────────

    @Test
    fun `alcons follows the Farrell-Becker relation for the score it is given`() {
        // The relation itself is genuine. It is only meaningful applied to a real
        // STI value, so what this pins is the conversion, not its validity here.
        listOf(0.2f, 0.45f, 0.6f, 0.75f, 0.9f).forEach { target ->
            val s = score(58f + 22f * target, patchiest, worstRt)
            val expected = (170f * exp((-5.4f * s.sti).toDouble()).toFloat()).coerceIn(0f, 100f)
            assertEquals("at sti ${s.sti}", expected, s.alconsPct, 1e-3f)
        }
    }

    @Test
    fun `a higher score always means fewer lost consonants`() {
        var previous = Float.MAX_VALUE
        listOf(50f, 60f, 66f, 72f, 78f, 80f).forEach { avg ->
            val alcons = score(avg, 2f, idealRt).alconsPct
            assertTrue("$avg dB gave $alcons%, above $previous", alcons <= previous)
            previous = alcons
        }
    }

    @Test
    fun `a perfect score still reports a small residual loss`() {
        // 170 * exp(-5.4) = 0.77%, the floor of the relation.
        assertEquals(0.77f, score(loudest, 0f, idealRt).alconsPct, 0.02f)
    }

    @Test
    fun `a hopeless score is capped at total loss rather than the relation's 170 percent`() {
        assertEquals(100f, score(quietest, patchiest, worstRt).alconsPct, 1e-3f)
    }
}
