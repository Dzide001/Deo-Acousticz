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
 * The per-element signal chain: sensitivity, gain trim, per-band EQ, geometric
 * spreading, air.
 *
 * This is the one part of the acoustics with no invented coefficients at all -
 * it is four terms added and subtracted in dB - so almost everything here is an
 * exact assertion. The inverse-square law is the anchor: doubling the distance
 * costs 6.02 dB and nothing else in the chain gets to change that.
 *
 * The EQ is a single offset per octave band, applied only when that band is the
 * one being analysed. It is not a filter: it has no bandwidth, no slope and no
 * effect on its neighbours, so a +6 dB entry at 1 kHz changes the 1 kHz map and
 * leaves the 2 kHz map untouched. Pinning that is the point of half these tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DspChainTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(bandHz: Int = 1000) = SceneViewModel().apply {
        setBandHz(bandHz)
        setTemperatureC(20f)
        setHumidityPct(50f)
    }

    private fun dsp(gainDb: Float = 0f, eq: Map<Int, Float> = emptyMap()) =
        SpeakerDsp(speakerId = 0, gainDb = gainDb, eqBands = eq)

    // ── Geometric spreading ──────────────────────────────────────────────────

    @Test
    fun `at one metre a box delivers its rated sensitivity`() {
        // 20*log10(1) = 0 dB of spreading; only air loss stands between the
        // rating and the reading, and over 1 m that is negligible.
        assertEquals(100f, vm().elementSplDb(100f, dsp(), 1f), 0.05f)
    }

    @Test
    fun `doubling the distance costs six decibels`() {
        val v = vm()
        val near = v.elementSplDb(100f, dsp(), 4f)
        val far = v.elementSplDb(100f, dsp(), 8f)
        // 6.02 dB of spreading plus the air loss over the extra 4 m.
        assertTrue("doubling cost ${near - far} dB", (near - far) in 6.02f..6.3f)
    }

    @Test
    fun `ten times the distance costs twenty decibels`() {
        val v = vm()
        val near = v.elementSplDb(100f, dsp(), 1f)
        val far = v.elementSplDb(100f, dsp(), 10f)
        assertTrue("ten-fold cost ${near - far} dB", (near - far) in 20f..20.5f)
    }

    @Test
    fun `every doubling costs the same six decibels`() {
        // Inverse square is scale-free: the octave from 2 to 4 m must cost what
        // the octave from 32 to 64 m costs, air loss aside.
        val v = vm(bandHz = 63)   // 63 Hz so air loss stays out of the way
        listOf(2f, 8f, 32f).forEach { d ->
            val drop = v.elementSplDb(100f, dsp(), d) - v.elementSplDb(100f, dsp(), d * 2f)
            assertEquals("doubling from $d m", 6.02f, drop, 0.1f)
        }
    }

    @Test
    fun `sensitivity passes straight through`() {
        val v = vm()
        val quiet = v.elementSplDb(96f, dsp(), 12f)
        val loud = v.elementSplDb(105f, dsp(), 12f)
        assertEquals(9f, loud - quiet, 1e-3f)
    }

    // ── Gain trim ────────────────────────────────────────────────────────────

    @Test
    fun `gain trim adds decibel for decibel`() {
        val v = vm()
        val flat = v.elementSplDb(100f, dsp(), 12f)
        assertEquals(4.5f, v.elementSplDb(100f, dsp(gainDb = 4.5f), 12f) - flat, 1e-3f)
        assertEquals(-7f, v.elementSplDb(100f, dsp(gainDb = -7f), 12f) - flat, 1e-3f)
    }

    @Test
    fun `gain trim is clamped to plus or minus twelve`() {
        val v = vm()
        v.addSpeaker(0f, 0f)
        val id = v.speakers.value.first().id
        v.setGain(id, 40f)
        assertEquals(12f, v.dspMap.value.getValue(id).gainDb, 1e-4f)
        v.setGain(id, -40f)
        assertEquals(-12f, v.dspMap.value.getValue(id).gainDb, 1e-4f)
    }

    // ── Per-band EQ ──────────────────────────────────────────────────────────

    @Test
    fun `an EQ entry on the analysed band lands exactly`() {
        val v = vm(bandHz = 1000)
        val flat = v.elementSplDb(100f, dsp(), 12f)
        val lifted = v.elementSplDb(100f, dsp(eq = mapOf(1000 to 6f)), 12f)
        assertEquals(6f, lifted - flat, 1e-3f)
    }

    @Test
    fun `an EQ entry on another band does nothing to this one`() {
        // The heart of the per-band model: octaves are independent, so boosting
        // 2 kHz must not move the 1 kHz map by a hair.
        val v = vm(bandHz = 1000)
        val flat = v.elementSplDb(100f, dsp(), 12f)
        val other = v.elementSplDb(100f, dsp(eq = mapOf(2000 to 6f, 125 to -6f)), 12f)
        assertEquals(0f, other - flat, 1e-4f)
    }

    @Test
    fun `each band picks up its own entry as the analysis band moves`() {
        val curve = mapOf(63 to -6f, 125 to -3f, 250 to 0f, 500 to 2f,
                          1000 to 4f, 2000 to 6f, 4000 to -4f, 8000 to -6f)
        SceneViewModel.SUPPORTED_BANDS_HZ.forEach { band ->
            val v = vm(bandHz = band)
            val flat = v.elementSplDb(100f, dsp(), 12f)
            val shaped = v.elementSplDb(100f, dsp(eq = curve), 12f)
            assertEquals("$band Hz", curve.getValue(band), shaped - flat, 1e-3f)
        }
    }

    @Test
    fun `a band with no entry is flat rather than missing`() {
        val v = vm(bandHz = 4000)
        val flat = v.elementSplDb(100f, dsp(), 12f)
        val partial = v.elementSplDb(100f, dsp(eq = mapOf(1000 to 6f)), 12f)
        assertEquals(0f, partial - flat, 1e-4f)
    }

    @Test
    fun `gain and EQ stack`() {
        val v = vm(bandHz = 1000)
        val flat = v.elementSplDb(100f, dsp(), 12f)
        val both = v.elementSplDb(100f, dsp(gainDb = 3f, eq = mapOf(1000 to 6f)), 12f)
        assertEquals(9f, both - flat, 1e-3f)
    }

    // ── The EQ setter ────────────────────────────────────────────────────────

    @Test
    fun `EQ is clamped to plus or minus six`() {
        val v = vm()
        v.addSpeaker(0f, 0f)
        val id = v.speakers.value.first().id
        v.setEqBand(id, 1000, 25f)
        assertEquals(6f, v.dspMap.value.getValue(id).eqBands.getValue(1000), 1e-4f)
        v.setEqBand(id, 1000, -25f)
        assertEquals(-6f, v.dspMap.value.getValue(id).eqBands.getValue(1000), 1e-4f)
    }

    @Test
    fun `setting one band leaves the others alone`() {
        val v = vm()
        v.addSpeaker(0f, 0f)
        val id = v.speakers.value.first().id
        v.setEqBand(id, 125, -4f)
        v.setEqBand(id, 4000, 5f)
        v.setEqBand(id, 125, 2f)
        val bands = v.dspMap.value.getValue(id).eqBands
        assertEquals(2f, bands.getValue(125), 1e-4f)
        assertEquals(5f, bands.getValue(4000), 1e-4f)
        assertEquals("only the bands that were touched should exist", 2, bands.size)
    }

    @Test
    fun `EQ set through the setter reaches the SPL calculation`() {
        val v = vm(bandHz = 2000)
        v.addSpeaker(0f, 0f)
        val id = v.speakers.value.first().id
        val flat = v.elementSplDb(100f, v.dspMap.value[id] ?: dsp(), 12f)
        v.setEqBand(id, 2000, -5f)
        val cut = v.elementSplDb(100f, v.dspMap.value.getValue(id), 12f)
        assertEquals(-5f, cut - flat, 1e-3f)
    }

    // ── Guard rails ──────────────────────────────────────────────────────────

    @Test
    fun `a listener at the box does not produce infinity`() {
        val v = vm()
        val spl = v.elementSplDb(100f, dsp(), 0.001f)
        assertTrue("degenerate distance gave $spl", spl.isFinite())
    }

    @Test
    fun `level falls monotonically with distance in every band`() {
        SceneViewModel.SUPPORTED_BANDS_HZ.forEach { band ->
            val v = vm(bandHz = band)
            var previous = Float.MAX_VALUE
            listOf(1f, 2f, 5f, 12f, 30f, 80f, 200f).forEach { d ->
                val spl = v.elementSplDb(100f, dsp(), d)
                assertTrue("$band Hz at $d m gave $spl, above $previous", spl < previous)
                previous = spl
            }
        }
    }
}
