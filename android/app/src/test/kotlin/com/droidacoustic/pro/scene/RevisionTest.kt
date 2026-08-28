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
 * The revision counter that decides when the scene is worth saving.
 *
 * The autosave used to fire off a hand-written list of the flows it cared
 * about, and the list fell behind: analysis type, weighting, contour mode and
 * the aim-ray toggle were all written into the scene JSON, but changing one
 * never triggered a write, so every one of them came back at its default after
 * a restart. This is the test that the counter does not have that failure mode -
 * anything the export records must move it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RevisionTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    /** Asserts that [change] both moves the revision and alters the saved JSON. */
    private fun marksTheScene(name: String, change: (SceneViewModel) -> Unit) {
        val v = SceneViewModel()
        val beforeRev = v.revision.value
        val beforeJson = v.exportSceneJson(includeClfRegistry = false)
        change(v)
        assertTrue(
            "$name did not move the revision, so it would never be autosaved",
            v.revision.value > beforeRev
        )
        assertTrue(
            "$name did not change the saved scene, so the test proves nothing",
            v.exportSceneJson(includeClfRegistry = false) != beforeJson
        )
    }

    @Test
    fun `the settings that used to be lost on restart now mark the scene`() {
        marksTheScene("broadband") { it.setSignalType("SPECTRUM") }
        marksTheScene("weighting") { it.setWeighting(SceneViewModel.WEIGHTING_A) }
        marksTheScene("contour mode") { it.setContourMode(SceneViewModel.CONTOUR_BANDS) }
        marksTheScene("aim rays") { it.setAimRaysEnabled(true) }
    }

    @Test
    fun `scene edits mark it too`() {
        marksTheScene("adding a speaker") { it.addSpeaker(1f, 2f) }
        marksTheScene("moving the listener") { it.moveListener(4f, 5f) }
        marksTheScene("band") { it.setBandHz(4000) }
        marksTheScene("venue size") { it.setVenueSize(40f, 40f) }
        marksTheScene("temperature") { it.setTemperatureC(28f) }
        marksTheScene("analysis profile") { it.setAnalysisProfile("Precision") }
        marksTheScene("reflection order") { it.setReflectionOrder(3) }
        marksTheScene("spl target") { it.setSplTargetDb(101f) }
    }

    @Test
    fun `setting a value to what it already is does not churn the autosave`() {
        val v = SceneViewModel()
        v.setContourMode(SceneViewModel.CONTOUR_LINES)
        v.setAimRaysEnabled(true)
        val settled = v.revision.value
        v.setContourMode(SceneViewModel.CONTOUR_LINES)
        v.setAimRaysEnabled(true)
        assertEquals("a no-op change should not trigger a write", settled, v.revision.value)
    }

    @Test
    fun `a rejected value moves nothing`() {
        val v = SceneViewModel()
        val before = v.revision.value
        v.setContourMode("NOT_A_MODE")
        v.setWeighting("dB(HL)")
        assertEquals(before, v.revision.value)
    }

    @Test
    fun `broadband and its weighting survive a save and reload`() {
        // The end of the chain the counter exists to serve: what was on screen
        // is what comes back.
        val v = SceneViewModel()
        v.setSignalType("SPECTRUM")
        v.setWeighting(SceneViewModel.WEIGHTING_A)
        v.setContourMode(SceneViewModel.CONTOUR_BANDS)
        v.setAimRaysEnabled(true)
        v.addSpeaker(0f, 0f)

        val restored = SceneViewModel()
        restored.importSceneJson(v.exportSceneJson(includeClfRegistry = false))
        assertEquals("SPECTRUM", restored.signalType.value)
        assertEquals(SceneViewModel.WEIGHTING_A, restored.weighting.value)
        assertEquals(SceneViewModel.CONTOUR_BANDS, restored.contourMode.value)
        assertEquals(true, restored.aimRaysEnabled.value)
    }
}
