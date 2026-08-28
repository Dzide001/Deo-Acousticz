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
 * First and later reflections, by the image-source method.
 *
 * The geometry here is exact and checkable by hand, so most of these assert
 * numbers: a floor bounce from a box at height h to an ear at height e over a
 * horizontal run d travels sqrt(d^2 + (h+e)^2), and arrives late by the
 * difference over the speed of sound. That much is real physics and worth
 * pinning hard.
 *
 * What sits on top of it is not. Surface loss is -10*log10(1-alpha) with a
 * frequency tilt whose coefficients are chosen rather than derived, and the
 * grazing-incidence term is an invented shaping function. Those get direction
 * and bounds, not values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EarlyReflectionsTest {

    private val live = mutableListOf<SceneViewModel>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        live.forEach { it.cancelAnalysisJobs() }
        live.clear()
        Dispatchers.resetMain()
    }

    private fun track(vm: SceneViewModel) = vm.also { live += it }

    /** Box on the centre line, listener straight out in front of it. */
    private fun scene(
        speakerHeightM: Float = 4f,
        distM: Float = 12f,
        bandHz: Int = 1000,
        order: Int = 1,
        floorAlpha: Float = 0.15f
    ): SceneViewModel = track(SceneViewModel()).apply {
        setBandHz(bandHz)
        setTemperatureC(20f)
        setReflectionOrder(order)
        setFloorAbsorption(floorAlpha)
        setVenueSize(30f, 30f)
        moveListener(distM, 0f)
        addSpeaker(0f, 0f)
        val id = speakers.value.single().id
        setSpeakerPosition(id, 0f, speakerHeightM, 0f)
    }

    private fun reflectionsOf(v: SceneViewModel): List<EarlyReflection> {
        val spk = v.speakers.value.single()
        val listener = v.listener.value
        val direct = sqrt(
            (listener.x - spk.x) * (listener.x - spk.x) +
                (listener.earHeightM - spk.heightM) * (listener.earHeightM - spk.heightM) +
                (listener.z - spk.z) * (listener.z - spk.z)
        )
        val room = v.estimateRoomBounds(
            v.speakers.value, listener, v.audience.value, v.audienceAreas.value
        )
        val results = listOf(SpeakerResult(spk, direct, 100f, 0f))
        return v.buildEarlyReflections(results, room, listener)
    }

    private val speedOfSound = 331.3f + 0.606f * 20f

    // ── Geometry ─────────────────────────────────────────────────────────────

    @Test
    fun `the floor bounce travels the mirror-source distance`() {
        // Box at 4 m, ear at 1.2 m, 12 m apart: the image sits 4 m below the
        // floor, so the path is sqrt(12^2 + 5.2^2).
        val v = scene(speakerHeightM = 4f, distM = 12f)
        val floor = reflectionsOf(v).first { it.surfaceName == "Floor" }
        val expected = sqrt(12f * 12f + (4f + 1.2f) * (4f + 1.2f))
        assertEquals(expected, floor.pathLengthM, 0.05f)
    }

    @Test
    fun `the ceiling bounce mirrors about the ceiling, not the floor`() {
        val v = scene(speakerHeightM = 4f, distM = 12f)
        val ceiling = reflectionsOf(v).first { it.surfaceName == "Ceiling" }
        val room = v.estimateRoomBounds(
            v.speakers.value, v.listener.value, v.audience.value, v.audienceAreas.value
        )!!
        val imageY = 2f * room.heightM - 4f
        val expected = sqrt(12f * 12f + (imageY - 1.2f) * (imageY - 1.2f))
        assertEquals(expected, ceiling.pathLengthM, 0.05f)
    }

    @Test
    fun `the delay is the extra path over the speed of sound`() {
        val v = scene(speakerHeightM = 4f, distM = 12f)
        val direct = sqrt(12f * 12f + (4f - 1.2f) * (4f - 1.2f))
        reflectionsOf(v).forEach { r ->
            val expectedMs = (r.pathLengthM - direct) / speedOfSound * 1000f
            assertEquals("${r.surfaceName} delay", expectedMs, r.delayMs, 0.05f)
        }
    }

    @Test
    fun `every reflection arrives after the direct sound`() {
        // A reflection that arrives first would mean the image source is nearer
        // than the real one, which cannot happen.
        reflectionsOf(scene()).forEach {
            assertTrue("${it.surfaceName} arrived at ${it.delayMs} ms", it.delayMs >= 0f)
        }
    }

    @Test
    fun `a longer throw brings the floor bounce closer to the direct sound`() {
        // The two paths converge with distance, which is why floor bounce is a
        // near-field problem.
        fun floorDelay(d: Float) =
            reflectionsOf(scene(distM = d)).first { it.surfaceName == "Floor" }.delayMs
        assertTrue("delay should shrink with distance", floorDelay(30f) < floorDelay(8f))
    }

    // ── Surfaces and order ───────────────────────────────────────────────────

    @Test
    fun `first order gives the six surfaces of the room and no compound paths`() {
        val names = reflectionsOf(scene(order = 1)).map { it.surfaceName }
        assertTrue("expected no compound paths, got $names", names.none { it.contains("→") })
        assertTrue("expected the room's own surfaces, got $names",
                   names.toSet().all { it in setOf("Floor", "Ceiling", "Left wall", "Right wall", "Front wall", "Back wall") })
    }

    @Test
    fun `raising the order admits compound paths`() {
        val second = reflectionsOf(scene(order = 2)).map { it.surfaceName }
        assertTrue("second order should include a two-surface path, got $second",
                   second.any { it.contains("→") })
    }

    @Test
    fun `a path never bounces off the same surface twice in a row`() {
        // Physically impossible, and the usual bug in an image-source walk.
        reflectionsOf(scene(order = 3)).forEach { r ->
            val hops = r.surfaceName.split("→")
            hops.zipWithNext().forEach { (a, b) ->
                assertTrue("${r.surfaceName} repeats $a", a != b)
            }
        }
    }

    @Test
    fun `the list is capped and ordered loudest first`() {
        val all = reflectionsOf(scene(order = 3))
        assertTrue("should be capped, got ${all.size}", all.size <= 16)
        all.zipWithNext().forEach { (a, b) ->
            assertTrue("out of order: ${a.splDb} then ${b.splDb}", a.splDb >= b.splDb)
        }
    }

    @Test
    fun `an empty room produces nothing rather than failing`() {
        val v = track(SceneViewModel())
        assertTrue(v.buildEarlyReflections(emptyList(), null, ListenerPos()).isEmpty())
    }

    // ── Absorption ───────────────────────────────────────────────────────────

    @Test
    fun `an absorptive floor returns a quieter bounce`() {
        fun floorLevel(alpha: Float) =
            reflectionsOf(scene(floorAlpha = alpha)).first { it.surfaceName == "Floor" }.splDb
        val hard = floorLevel(0.05f)
        val soft = floorLevel(0.9f)
        assertTrue("soft floor ($soft) should be quieter than hard ($hard)", soft < hard - 3f)
    }

    @Test
    fun `absorption only ever costs level`() {
        var previous = Float.MAX_VALUE
        listOf(0.02f, 0.2f, 0.5f, 0.8f, 0.95f).forEach { alpha ->
            val db = reflectionsOf(scene(floorAlpha = alpha)).first { it.surfaceName == "Floor" }.splDb
            assertTrue("alpha $alpha gave $db, above $previous", db <= previous + 1e-3f)
            previous = db
        }
    }

    @Test
    fun `a reflection is never louder than the direct sound that caused it`() {
        val v = scene(floorAlpha = 0.01f)
        val spk = v.speakers.value.single()
        val direct = sqrt(12f * 12f + (spk.heightM - 1.2f) * (spk.heightM - 1.2f))
        val directSpl = 100f
        reflectionsOf(v).forEach {
            assertTrue("${it.surfaceName} at ${it.splDb} exceeds the direct $directSpl",
                       it.splDb <= directSpl)
        }
        assertTrue(direct > 0f)
    }

    @Test
    fun `results stay finite across bands, orders and materials`() {
        SceneViewModel.SUPPORTED_BANDS_HZ.forEach { band ->
            listOf(1, 2, 3).forEach { order ->
                listOf(0.02f, 0.5f, 0.95f).forEach { alpha ->
                    reflectionsOf(scene(bandHz = band, order = order, floorAlpha = alpha)).forEach {
                        assertTrue("$band Hz order $order alpha $alpha gave ${it.splDb}",
                                   it.splDb.isFinite() && it.delayMs.isFinite() && it.pathLengthM > 0f)
                    }
                }
            }
        }
    }
}
