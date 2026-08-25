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
 * Off-axis attenuation for a single box, horizontal and vertical.
 *
 * The model is a parabola in the off-axis ratio: 6 dB down at the stated
 * half-power angle, rising as the square, capped. The half-power angles are
 * hardcoded per model package rather than read from measured data - replacing
 * them with real CLF directivity is the point of Phase 2 - so these tests pin
 * the shape and the aiming behaviour, not the coefficients' correctness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectivityTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SceneViewModel()

    private fun box(
        panDeg: Float = 0f,
        aimDeg: Float = 0f,
        elements: Int = 1,
        pkg: String = "point_source"
    ) = PlacedSpeaker(id = 0, x = 0f, z = 0f, panDeg = panDeg, arrayAimDeg = aimDeg,
                      arrayElements = elements, modelPackageId = pkg)

    // ── Horizontal ────────────────────────────────────────────────────────────

    @Test
    fun `dead on axis costs nothing`() {
        val v = vm()
        // Pan 0 faces +X; the listener is straight down that axis.
        val loss = v.horizontalDirectivityAttenuationDb(box(), 0f, 0f, 10f, 0f, 0)
        assertEquals(0f, loss, 1e-3f)
    }

    @Test
    fun `at the half-power angle the loss is 6 dB`() {
        val v = vm()
        // point_source half-power is 60 deg. A listener at bearing 60 deg.
        val x = 10f * kotlin.math.cos(Math.toRadians(60.0)).toFloat()
        val z = 10f * kotlin.math.sin(Math.toRadians(60.0)).toFloat()
        assertEquals(6f, v.horizontalDirectivityAttenuationDb(box(), 0f, 0f, x, z, 0), 1e-2f)
    }

    @Test
    fun `loss grows as the square of the off-axis ratio`() {
        val v = vm()
        // Twice the half-power angle is four times the loss: 24 dB.
        val x = 10f * kotlin.math.cos(Math.toRadians(120.0)).toFloat()
        val z = 10f * kotlin.math.sin(Math.toRadians(120.0)).toFloat()
        assertEquals(24f, v.horizontalDirectivityAttenuationDb(box(), 0f, 0f, x, z, 0), 1e-2f)
    }

    @Test
    fun `loss is capped rather than running away behind the box`() {
        val v = vm()
        // Directly behind: 180 deg off axis would be 54 dB unclamped.
        val loss = v.horizontalDirectivityAttenuationDb(box(), 0f, 0f, -10f, 0f, 0)
        assertEquals(24f, loss, 1e-3f)
    }

    @Test
    fun `panning the box towards the listener recovers the loss`() {
        val v = vm()
        val listenerX = 0f
        val listenerZ = 10f  // bearing 90 deg
        val unaimed = v.horizontalDirectivityAttenuationDb(box(panDeg = 0f), 0f, 0f, listenerX, listenerZ, 0)
        val aimed = v.horizontalDirectivityAttenuationDb(box(panDeg = 90f), 0f, 0f, listenerX, listenerZ, 0)
        assertTrue("unaimed should be lossy, was $unaimed", unaimed > 5f)
        assertEquals(0f, aimed, 1e-3f)
    }

    @Test
    fun `reflected paths are treated as less directional than the direct one`() {
        val v = vm()
        val x = 10f * kotlin.math.cos(Math.toRadians(60.0)).toFloat()
        val z = 10f * kotlin.math.sin(Math.toRadians(60.0)).toFloat()
        val direct = v.horizontalDirectivityAttenuationDb(box(), 0f, 0f, x, z, 0)
        val firstOrder = v.horizontalDirectivityAttenuationDb(box(), 0f, 0f, x, z, 1)
        assertEquals(direct * 0.84f, firstOrder, 1e-2f)
        // The relaxation is floored, so a high-order path never goes free.
        val highOrder = v.horizontalDirectivityAttenuationDb(box(), 0f, 0f, x, z, 9)
        assertEquals(direct * 0.5f, highOrder, 1e-2f)
    }

    @Test
    fun `turning dispersion off removes directivity entirely`() {
        val v = vm().apply { setSignalDispersionEnabled(false) }
        assertEquals(0f, v.horizontalDirectivityAttenuationDb(box(), 0f, 0f, -10f, 0f, 0), 0f)
    }

    // ── Vertical ──────────────────────────────────────────────────────────────

    @Test
    fun `an untilted box loses level to a listener below it`() {
        val v = vm()
        // Flown 10 m up, listener 10 m out on the floor: 45 deg below the axis.
        // point_source half-power is 35 deg, so 6 * (45/35)^2 = 9.92 dB.
        val loss = v.verticalAimAttenuationDb(box(), 0f, 10f, 0f, 10f, 0f, 0f, 0)
        assertEquals(9.92f, loss, 5e-2f)
    }

    @Test
    fun `tilting the box onto the listener recovers the loss`() {
        val v = vm()
        // arrayAimDeg is down-positive, so 45 deg of tilt puts the axis on the listener.
        val loss = v.verticalAimAttenuationDb(box(aimDeg = 45f), 0f, 10f, 0f, 10f, 0f, 0f, 0)
        assertEquals(0f, loss, 1e-2f)
    }

    @Test
    fun `over-tilting past the listener costs level again`() {
        val v = vm()
        val onTarget = v.verticalAimAttenuationDb(box(aimDeg = 45f), 0f, 10f, 0f, 10f, 0f, 0f, 0)
        val past = v.verticalAimAttenuationDb(box(aimDeg = 80f), 0f, 10f, 0f, 10f, 0f, 0f, 0)
        assertTrue("over-tilting should cost level: $past vs $onTarget", past > onTarget)
    }

    @Test
    fun `arrays are excluded because the summation path models them instead`() {
        val v = vm()
        val single = v.verticalAimAttenuationDb(box(elements = 1), 0f, 10f, 0f, 10f, 0f, 0f, 0)
        val array = v.verticalAimAttenuationDb(box(elements = 4), 0f, 10f, 0f, 10f, 0f, 0f, 0)
        assertTrue("a single box should be attenuated off-axis", single > 1f)
        assertEquals("an array must not be attenuated twice", 0f, array, 0f)
    }

    // ── Angle helper ──────────────────────────────────────────────────────────

    @Test
    fun `angular difference takes the short way round`() {
        val v = vm()
        assertEquals(20f, v.angularDeltaDeg(350f, 10f), 1e-3f)
        assertEquals(20f, v.angularDeltaDeg(10f, 350f), 1e-3f)
        assertEquals(180f, v.angularDeltaDeg(0f, 180f), 1e-3f)
        assertEquals(0f, v.angularDeltaDeg(-90f, 270f), 1e-3f)
    }
}
