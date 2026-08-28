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
 * Imported CLF data actually driving the prediction.
 *
 * This is the join that makes the whole CLF effort worth anything. Everything
 * upstream of it - the TAB reader, the file picker, the registry - was inert
 * until a placed box could be traced back to its measured pattern, because
 * [PlacedSpeaker] recorded only `modelPackageId`, which selects the synthetic
 * fallback coefficients and says nothing about which loudspeaker it is. The
 * lookup was passing the placed box's integer id against a registry keyed by
 * model, so it could never match and the fallback ran every time.
 *
 * These tests pin the join: same geometry, same everything, one with measured
 * data and one without, and the answers must differ in the direction the
 * measured file says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeasuredDirectivityTest {

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

    /**
     * A CLF2 document with a deliberately extreme pattern: flat to 30 degrees
     * off axis, then a cliff to -30 dB. Nothing the parabola model can produce,
     * which is what makes it usable as a fingerprint.
     */
    private fun cliffPatternTab(model: String = "Cliff Box"): String = buildString {
        appendLine("<CLF2>")
        appendLine("<MODELNAME>\t$model")
        appendLine("<MANUFACTURER>\tSynthetic Audio")
        appendLine("<SENSITIVITY>\t100.0")
        appendLine("<BALLOON-SYMMETRY>\t<none>")
        appendLine("<BALLOON-ARC-ORDER>\t<normal>")
        SceneViewModel.SUPPORTED_BANDS_HZ.forEach { hz ->
            appendLine("<BAND>\t$hz")
            repeat(72) {
                appendLine((0..36).joinToString("\t") { t ->
                    if (t * 5 <= 30) "0.00" else "-30.00"
                })
            }
        }
    }

    /** A speaker at the origin facing +X, listener [d] metres away, [offAxis] degrees round. */
    private fun vmWithGeometry(offAxisDeg: Float, d: Float = 10f): SceneViewModel =
        track(SceneViewModel()).apply {
            setBandHz(1000)
            val rad = Math.toRadians(offAxisDeg.toDouble())
            moveListener((d * kotlin.math.cos(rad)).toFloat(), (d * kotlin.math.sin(rad)).toFloat())
        }

    /**
     * Place a speaker, recalculate, and wait for the answer.
     *
     * The analysis runs on `Dispatchers.Default`, which `setMain` does not
     * replace, so the result lands on a real background thread some time after
     * `recalculateSignal` returns. Polling is the honest way to wait for it
     * without reshaping production code around the test.
     */
    private fun splAt(v: SceneViewModel): Float {
        v.addSpeaker(0f, 0f)
        v.recalculateSignal()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            v.combinedSplDb.value?.let { return it }
            Thread.sleep(10)
        }
        error("no SPL computed within 5 s")
    }

    // ── The join ─────────────────────────────────────────────────────────────

    @Test
    fun `a placed speaker remembers which preset it came from`() {
        // Without this the registry lookup has nothing to match on.
        val v = track(SceneViewModel())
        v.importClfTabText(cliffPatternTab())
        v.addSpeaker(0f, 0f)
        assertEquals("cliff_box", v.speakers.value.single().presetId)
    }

    @Test
    fun `measured data changes the prediction`() {
        // 45 degrees off axis is past the fixture's cliff, so measured and
        // synthetic must disagree. If they agree, the lookup silently fell back.
        val withClf = vmWithGeometry(45f).also { it.importClfTabText(cliffPatternTab()) }
        val without = vmWithGeometry(45f)
        val a = splAt(withClf)
        val b = splAt(without)
        assertNotEquals("measured data made no difference to the answer", b, a, 0.5f)
    }

    @Test
    fun `the prediction follows the measured pattern's own shape`() {
        // Inside the plateau the file says 0 dB down; past the cliff it says 30.
        // The difference between those two directions must be about 30 dB.
        val inside = vmWithGeometry(20f).also { it.importClfTabText(cliffPatternTab()) }
        val outside = vmWithGeometry(45f).also { it.importClfTabText(cliffPatternTab()) }
        val drop = splAt(inside) - splAt(outside)
        assertTrue("expected about 30 dB across the cliff, got $drop", drop in 25f..35f)
    }

    @Test
    fun `inside the plateau the measured pattern costs nothing`() {
        // The fixture is flat out to 30 degrees, so 10 and 25 degrees off axis
        // should differ only by the geometry, not by directivity.
        val near = vmWithGeometry(10f).also { it.importClfTabText(cliffPatternTab()) }
        val far = vmWithGeometry(25f).also { it.importClfTabText(cliffPatternTab()) }
        assertEquals(splAt(near), splAt(far), 0.5f)
    }

    @Test
    fun `a speaker with no measured data still gets the synthetic fallback`() {
        // Removing CLF must not silently zero the directivity model.
        val v = vmWithGeometry(45f)
        val spl = splAt(v)
        assertTrue("fallback produced no usable level: $spl", spl.isFinite() && spl > 0f)
    }

    @Test
    fun `importing data for one model does not affect a different one`() {
        val v = vmWithGeometry(45f)
        v.importClfTabText(cliffPatternTab(model = "Some Other Box"))
        // Place from the default preset, not the imported one.
        v.setSpeakerPreset(v.speakerPresets.value.first { it.id != "some_other_box" }.id)
        val withUnrelated = splAt(v)
        val bare = splAt(vmWithGeometry(45f))
        assertEquals("unrelated CLF data leaked into the prediction", bare, withUnrelated, 0.5f)
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    @Test
    fun `preset identity survives a scene round trip`() {
        // A saved scene that forgets which box it held would quietly downgrade
        // to synthetic directivity on reload.
        val v = track(SceneViewModel())
        v.importClfTabText(cliffPatternTab())
        v.addSpeaker(3f, 4f)
        val json = v.exportSceneJson()

        val restored = track(SceneViewModel())
        assertTrue(restored.importSceneJson(json))
        assertEquals("cliff_box", restored.speakers.value.single().presetId)
    }

    @Test
    fun `a scene saved before preset identity existed still loads`() {
        val v = track(SceneViewModel())
        v.addSpeaker(1f, 2f)
        val legacy = v.exportSceneJson().replace(Regex("\"presetId\":\"[^\"]*\","), "")

        val restored = track(SceneViewModel())
        assertTrue("older scenes must still open", restored.importSceneJson(legacy))
        assertEquals("", restored.speakers.value.single().presetId)
    }
}
