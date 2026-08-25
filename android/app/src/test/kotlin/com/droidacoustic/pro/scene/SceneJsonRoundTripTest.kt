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
 * Scene JSON is the persistence format: project export, the autosave recovery
 * snapshot, and every undo/redo checkpoint all go through it. Anything that
 * fails to survive a round trip is silently lost the next time the app restarts
 * or the user presses undo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SceneJsonRoundTripTest {

    // importSceneJson triggers a forced recalculation on viewModelScope.
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun populated() = SceneViewModel().apply {
        addSpeaker(3f, -4f)
        addSpeaker(-6f, 8f)
        setVenueSize(30f, 22f)
        setVenueWallHeight(12f)
        setTemperatureC(26f)
        setHumidityPct(35f)
        setSplScaleMode(SceneViewModel.SPL_SCALE_TARGET)
        setSplTargetDb(97f)
        setSplSpanDb(4f)
    }

    @Test
    fun `a populated scene survives export and import`() {
        val original = populated()
        val json = original.exportSceneJson(includeClfRegistry = false)

        val restored = SceneViewModel()
        assertTrue("import rejected the JSON we just exported", restored.importSceneJson(json))

        assertEquals(2, restored.speakers.value.size)
        assertEquals(30f, restored.venueGeometry.value.widthM, 1e-3f)
        assertEquals(22f, restored.venueGeometry.value.depthM, 1e-3f)
        assertEquals(26f, restored.temperatureC.value, 1e-3f)
        assertEquals(35f, restored.humidityPct.value, 1e-3f)
    }

    @Test
    fun `speaker positions survive exactly`() {
        val original = populated()
        val restored = SceneViewModel()
        restored.importSceneJson(original.exportSceneJson(includeClfRegistry = false))

        val before = original.speakers.value.map { it.x to it.z }.sortedBy { it.first }
        val after = restored.speakers.value.map { it.x to it.z }.sortedBy { it.first }
        assertEquals(before, after)
    }

    @Test
    fun `the SPL colour scale survives`() {
        // This is what made the scale silently reset to Auto on every restart
        // until the settings were added to the autosave key.
        val original = populated()
        val restored = SceneViewModel()
        restored.importSceneJson(original.exportSceneJson(includeClfRegistry = false))

        assertEquals(SceneViewModel.SPL_SCALE_TARGET, restored.splScaleMode.value)
        assertEquals(97f, restored.splTargetDb.value, 1e-3f)
        assertEquals(4f, restored.splSpanDb.value, 1e-3f)
    }

    @Test
    fun `the document converges after one round trip`() {
        // The first export is NOT byte-identical to the second. Scene values are
        // Float, JSON carries them as double, and widening 0.1f prints as 0.099 -
        // so the first trip re-quantises a few numbers. What must hold is that it
        // settles: once a document has been through import, exporting it again
        // reproduces it exactly. Anything else means values drift on every save.
        val first = populated().exportSceneJson(includeClfRegistry = false)

        val second = SceneViewModel().apply { importSceneJson(first) }
            .exportSceneJson(includeClfRegistry = false)
        val third = SceneViewModel().apply { importSceneJson(second) }
            .exportSceneJson(includeClfRegistry = false)

        assertEquals("scene JSON does not converge; values drift on every save", second, third)
    }

    @Test
    fun `an empty scene round trips without inventing content`() {
        val empty = SceneViewModel()
        val json = empty.exportSceneJson(includeClfRegistry = false)
        val restored = SceneViewModel()
        assertTrue(restored.importSceneJson(json))
        assertTrue(restored.speakers.value.isEmpty())
        assertTrue(restored.audienceAreas.value.isEmpty())
    }

    @Test
    fun `malformed JSON is rejected rather than half-applied`() {
        val vm = populated()
        val speakersBefore = vm.speakers.value.size
        assertEquals(false, vm.importSceneJson("{ this is not json"))
        assertEquals(speakersBefore, vm.speakers.value.size)
    }
}
