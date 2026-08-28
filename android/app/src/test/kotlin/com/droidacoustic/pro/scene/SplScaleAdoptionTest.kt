package com.droidacoustic.pro.scene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Setting a design target should make the map honour it.
 *
 * Auto restretches the ramp to whatever each calculation happens to contain, so
 * 95-98 dB and 85-105 dB paint the same picture and cannot be compared between
 * runs. Stating a target is a request to be able to compare, so the scale
 * follows - but only from Auto. A deliberate choice of Fixed window is a
 * decision, and decisions are left alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SplScaleAdoptionTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `setting a target moves the scale off Auto`() {
        val v = SceneViewModel()
        assertEquals(SceneViewModel.SPL_SCALE_AUTO, v.splScaleMode.value)
        v.setSplTargetDb(97f)
        assertEquals(SceneViewModel.SPL_SCALE_TARGET, v.splScaleMode.value)
        assertEquals(97f, v.splTargetDb.value, 1e-3f)
    }

    @Test
    fun `a fixed window is a decision and is not overridden`() {
        val v = SceneViewModel()
        v.setSplScaleMode(SceneViewModel.SPL_SCALE_FIXED)
        v.setSplTargetDb(97f)
        assertEquals(SceneViewModel.SPL_SCALE_FIXED, v.splScaleMode.value)
        assertEquals("the target is still recorded", 97f, v.splTargetDb.value, 1e-3f)
    }

    @Test
    fun `re-entering the same target still adopts the scale`() {
        // The default target is 95. Typing 95 while on Auto is a statement of
        // intent, and returning early on "no change" would have ignored it.
        val v = SceneViewModel()
        v.setSplTargetDb(v.splTargetDb.value)
        assertEquals(SceneViewModel.SPL_SCALE_TARGET, v.splScaleMode.value)
    }

    @Test
    fun `already on target, setting one just changes the level`() {
        val v = SceneViewModel()
        v.setSplScaleMode(SceneViewModel.SPL_SCALE_TARGET)
        v.setSplTargetDb(102f)
        assertEquals(SceneViewModel.SPL_SCALE_TARGET, v.splScaleMode.value)
        assertEquals(102f, v.splTargetDb.value, 1e-3f)
    }

    @Test
    fun `the target window is centred on the target`() {
        val (lo, hi) = SceneViewModel.splScaleWindow(
            SceneViewModel.SPL_SCALE_TARGET, targetDb = 97f, spanDb = 6f,
            fixedMinDb = 0f, fixedMaxDb = 0f, cells = emptyList()
        )
        assertEquals(91f, lo, 1e-3f)
        assertEquals(103f, hi, 1e-3f)
    }

    // ── Contour mode ─────────────────────────────────────────────────────────

    @Test
    fun `contours are off until asked for, and only known modes are accepted`() {
        val v = SceneViewModel()
        assertEquals(SceneViewModel.CONTOUR_OFF, v.contourMode.value)
        v.setContourMode(SceneViewModel.CONTOUR_BANDS)
        assertEquals(SceneViewModel.CONTOUR_BANDS, v.contourMode.value)
        v.setContourMode("SOMETHING_ELSE")
        assertEquals("an unknown mode should be ignored",
                     SceneViewModel.CONTOUR_BANDS, v.contourMode.value)
    }

    @Test
    fun `contour mode survives a scene round trip`() {
        val v = SceneViewModel()
        v.setContourMode(SceneViewModel.CONTOUR_LINES)
        v.addSpeaker(1f, 1f)
        val restored = SceneViewModel()
        restored.importSceneJson(v.exportSceneJson())
        assertEquals(SceneViewModel.CONTOUR_LINES, restored.contourMode.value)
    }

    @Test
    fun `the contour reference prefers a stated target over the data`() {
        val v = SceneViewModel()
        val cells = (0..20).map { HeatCell(x = it.toFloat(), z = 0f, splDb = 80f + it) }
        val fromData = v.contourReferenceDb(cells)!!
        v.setSplTargetDb(97f)                       // also adopts Target mode
        assertEquals(97f, v.contourReferenceDb(cells)!!, 1e-3f)
        assertEquals("without a target it comes off the data, not the peak",
                     99f, fromData, 1.5f)
    }

    @Test
    fun `loading a scene saved on Auto does not silently switch it to Target`() {
        // Restoring state must not trip the adoption rule. The scale is part of
        // what was saved, and reopening a design should show it as it was left.
        val saved = SceneViewModel()
        saved.addSpeaker(2f, 2f)
        assertEquals(SceneViewModel.SPL_SCALE_AUTO, saved.splScaleMode.value)
        val json = saved.exportSceneJson()

        val restored = SceneViewModel()
        restored.setSplScaleMode(SceneViewModel.SPL_SCALE_FIXED)   // something else first
        restored.importSceneJson(json)
        assertEquals(SceneViewModel.SPL_SCALE_AUTO, restored.splScaleMode.value)
    }

    @Test
    fun `a scene saved on Target comes back on Target`() {
        val saved = SceneViewModel()
        saved.setSplTargetDb(101f)
        saved.addSpeaker(0f, 0f)
        val restored = SceneViewModel()
        restored.importSceneJson(saved.exportSceneJson())
        assertEquals(SceneViewModel.SPL_SCALE_TARGET, restored.splScaleMode.value)
        assertEquals(101f, restored.splTargetDb.value, 1e-3f)
    }
}
