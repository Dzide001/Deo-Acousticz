package com.droidacoustic.pro.scene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Per-octave absorption, and the three things that read it.
 *
 * One broadband coefficient could not describe a real surface: heavy carpet is
 * 0.02 at 125 Hz and 0.65 at 4 kHz, a factor of thirty across the range a
 * loudspeaker covers, so whichever single number was chosen was wrong at one
 * end or the other. These tests pin the published values, and then check that
 * RT60, the loss at each reflection and the floor bounce all actually vary with
 * frequency now rather than only claiming to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceMaterialTest {

    private val live = mutableListOf<SceneViewModel>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        live.forEach { it.cancelAnalysisJobs() }
        live.clear()
        Dispatchers.resetMain()
    }

    private fun track(vm: SceneViewModel) = vm.also { live += it }

    private fun room() = RoomBounds(minX = 0f, maxX = 20f, minZ = 0f, maxZ = 20f, heightM = 8f)

    // ── The curves ───────────────────────────────────────────────────────────

    @Test
    fun `carpet absorbs the top end and almost none of the bottom`() {
        val c = SurfaceMaterial.CARPET
        assertEquals(0.02f, c.alphaAt(125), 1e-3f)
        assertEquals(0.37f, c.alphaAt(1000), 1e-3f)
        assertEquals(0.65f, c.alphaAt(4000), 1e-3f)
        assertTrue("carpet should rise steeply with frequency",
                   c.alphaAt(4000) > c.alphaAt(125) * 20f)
    }

    @Test
    fun `gypsum does the opposite, absorbing most at the bottom`() {
        // A stud wall is a panel absorber: it works low and reflects high. A
        // single number cannot express a curve that runs the other way from
        // carpet's, which is the whole argument for doing this per band.
        val g = SurfaceMaterial.GYPSUM
        assertEquals(0.29f, g.alphaAt(125), 1e-3f)
        assertEquals(0.04f, g.alphaAt(1000), 1e-3f)
        assertTrue("gypsum should fall with frequency", g.alphaAt(125) > g.alphaAt(1000) * 5f)
    }

    @Test
    fun `the end bands hold the nearest published value rather than inventing one`() {
        // Published tables run 125 Hz to 4 kHz. Holding is a stated
        // approximation; extrapolating a curve nobody measured would not be.
        SurfaceMaterial.CATALOGUE.forEach { m ->
            assertEquals("${m.name} at 63 Hz", m.alphaAt(125), m.alphaAt(63), 1e-4f)
            assertEquals("${m.name} at 8 kHz", m.alphaAt(4000), m.alphaAt(8000), 1e-4f)
        }
    }

    @Test
    fun `every catalogue entry is a usable coefficient in every band`() {
        SurfaceMaterial.CATALOGUE.forEach { m ->
            SceneViewModel.SUPPORTED_BANDS_HZ.forEach { band ->
                val a = m.alphaAt(band)
                assertTrue("${m.name} at $band Hz gave $a", a in 0.01f..1f)
            }
        }
    }

    @Test
    fun `a flat surface is the old behaviour, available for a typed-in number`() {
        val f = SurfaceMaterial.flat(0.42f)
        SceneViewModel.SUPPORTED_BANDS_HZ.forEach { assertEquals(0.42f, f.alphaAt(it), 1e-4f) }
    }

    @Test
    fun `an unknown id is refused rather than silently substituted`() {
        assertNull(SurfaceMaterial.byId("shag_pile"))
        assertEquals(SurfaceMaterial.CARPET, SurfaceMaterial.byId("carpet"))
    }

    // ── RT60 ─────────────────────────────────────────────────────────────────

    @Test
    fun `RT60 now varies with frequency, which is the point of all this`() {
        val v = track(SceneViewModel())
        val carpeted = RoomMaterials(
            floor = SurfaceMaterial.CARPET,
            ceiling = SurfaceMaterial.ACOUSTIC_TILE,
            wall = SurfaceMaterial.GYPSUM
        )
        val low = v.estimateRt60(room(), carpeted, 125).rt60S
        val high = v.estimateRt60(room(), carpeted, 4000).rt60S
        assertTrue("a carpeted, tiled room should ring longer low than high: $low vs $high",
                   low > high * 1.5f)
    }

    @Test
    fun `a hard room rings longer than a soft one at every band`() {
        val v = track(SceneViewModel())
        val hard = RoomMaterials(SurfaceMaterial.CONCRETE, SurfaceMaterial.CONCRETE, SurfaceMaterial.BRICK)
        val soft = RoomMaterials(SurfaceMaterial.CARPET, SurfaceMaterial.ACOUSTIC_PANEL, SurfaceMaterial.CURTAIN)
        SceneViewModel.SUPPORTED_BANDS_HZ.forEach { band ->
            val h = v.estimateRt60(room(), hard, band).rt60S
            val s = v.estimateRt60(room(), soft, band).rt60S
            assertTrue("at $band Hz hard=$h soft=$s", h > s)
        }
    }

    // ── The floor bounce ─────────────────────────────────────────────────────

    @Test
    fun `the floor bounce follows the floor's own curve`() {
        // Measured with the source and the ear both at floor level, so the image
        // is co-located and the two arrivals are always in phase. That removes
        // the comb from the question entirely and leaves only how much the floor
        // reflects, which is what varies with band.
        //
        // Sweeping for "the deepest null" was the wrong test and failed for a
        // good reason: over a 10 m run the geometry cannot produce enough excess
        // path to reach a null at 125 Hz at all, so the sweep was comparing a
        // null against a non-null rather than two reflection strengths.
        val v = track(SceneViewModel()).apply { setTemperatureC(20f) }
        v.setSurfaceMaterial("FLOOR", SurfaceMaterial.ACOUSTIC_PANEL.id)
        fun inPhaseBoost(band: Int) =
            v.floorBoundaryInterferenceDb(0f, 0f, 0f, 0f, 0f, 10f, band)

        // The panel absorbs 0.20 at 125 Hz and 0.95 at 1 kHz, so it returns most
        // of the low end and almost none of the top.
        val low = inPhaseBoost(125)
        val high = inPhaseBoost(1000)
        assertTrue("a reflective floor should add more than an absorbent one: $low vs $high",
                   low > high + 1f)
        assertEquals("0.95 absorption leaves almost nothing to add back", 1.76f, high, 0.15f)
    }

    // ── Reflections ──────────────────────────────────────────────────────────

    @Test
    fun `a reflection off carpet loses more at the top than at the bottom`() {
        val v = track(SceneViewModel()).apply {
            setVenueSize(30f, 30f)
            moveListener(10f, 0f)
            addSpeaker(0f, 0f)
        }
        v.setSurfaceMaterial("FLOOR", SurfaceMaterial.CARPET.id)
        fun floorLevel(band: Int): Float {
            val spk = v.speakers.value.single()
            val listener = v.listener.value
            val r = v.estimateRoomBounds(v.speakers.value, listener, v.audience.value, v.audienceAreas.value)
            val results = listOf(SpeakerResult(spk, 10f, 100f, 0f))
            return v.buildEarlyReflections(results, r, listener, band)
                .first { it.surfaceName == "Floor" }.splDb
        }
        assertTrue("carpet absorbs the top end, so the 4 kHz bounce must be quieter",
                   floorLevel(4000) < floorLevel(125))
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    @Test
    fun `materials survive a scene round trip`() {
        val v = track(SceneViewModel())
        v.setSurfaceMaterial("FLOOR", SurfaceMaterial.WOOD_FLOOR.id)
        v.setSurfaceMaterial("CEILING", SurfaceMaterial.OPEN_TRUSS.id)
        v.setSurfaceMaterial("WALL", SurfaceMaterial.CURTAIN.id)
        val restored = track(SceneViewModel())
        restored.importSceneJson(v.exportSceneJson(includeClfRegistry = false))
        assertEquals(SurfaceMaterial.WOOD_FLOOR.id, restored.roomMaterials.value.floor.id)
        assertEquals(SurfaceMaterial.OPEN_TRUSS.id, restored.roomMaterials.value.ceiling.id)
        assertEquals(SurfaceMaterial.CURTAIN.id, restored.roomMaterials.value.wall.id)
    }

    @Test
    fun `a scene from before per-band absorption still opens`() {
        // Older scenes carry one number per surface. It becomes a flat curve,
        // which is exactly the answer that version of the app would have given.
        val v = track(SceneViewModel())
        v.addSpeaker(0f, 0f)
        // A real older scene has neither the id nor the curve - only the one
        // number. Editing the JSON as text got this wrong twice: stripping just
        // the id left the curve behind, and the curve rightly won; then the
        // comma-anchored regex missed because JSONObject does not promise key
        // order. Take the keys out properly instead.
        val doc = org.json.JSONObject(v.exportSceneJson(includeClfRegistry = false))
        val rm = doc.getJSONObject("roomMaterials")
        rm.remove("floorMaterial")
        rm.remove("floorAlphaByBand")
        rm.put("floorAlpha", 0.33)
        val legacy = doc.toString()
        val restored = track(SceneViewModel())
        assertTrue("an older scene must still load", restored.importSceneJson(legacy))
        assertEquals(0.33f, restored.roomMaterials.value.floor.alphaAt(1000), 1e-3f)
        assertEquals("and be flat, as it was then",
                     restored.roomMaterials.value.floor.alphaAt(125),
                     restored.roomMaterials.value.floor.alphaAt(4000), 1e-4f)
    }

    @Test
    fun `a custom surface keeps its curve, not just its name`() {
        val v = track(SceneViewModel())
        v.setFloorAbsorption(0.5f)
        val restored = track(SceneViewModel())
        restored.importSceneJson(v.exportSceneJson(includeClfRegistry = false))
        assertEquals(0.5f, restored.roomMaterials.value.floor.alphaAt(2000), 1e-3f)
    }

    @Test
    fun `choosing a material marks the scene for saving`() {
        val v = track(SceneViewModel())
        val before = v.revision.value
        v.setSurfaceMaterial("WALL", SurfaceMaterial.BRICK.id)
        assertNotEquals(before, v.revision.value)
    }
}
