package com.droidacoustic.pro.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sabine: RT60 = 0.161 * V / A, with A the total absorption in sabins.
 *
 * These pin the arithmetic as written. They deliberately do NOT assert the model
 * is the right one - it runs against a bounding box rather than the venue drawn,
 * uses one broadband coefficient per surface family, and has no air term. Those
 * are known gaps; the point here is that changing the formula cannot pass silently.
 */
class Rt60Test {

    private val vm = SceneViewModel()

    private fun room(w: Float, d: Float, h: Float) =
        RoomBounds(minX = 0f, maxX = w, minZ = 0f, maxZ = d, heightM = h)

    @Test
    fun `10m cube at alpha 0-1 gives the Sabine answer`() {
        // V = 1000, floor 100 + ceiling 100 + walls 400 = 600 m^2 at a = 0.1 -> 60 sabins
        // RT60 = 0.161 * 1000 / 60 = 2.683 s
        val est = vm.estimateRt60(room(10f, 10f, 10f), RoomMaterials(0.1f, 0.1f, 0.1f))
        assertEquals(1000f, est.volumeM3, 1e-2f)
        assertEquals(2.6833f, est.rt60S, 1e-3f)
    }

    @Test
    fun `doubling absorption halves the decay time`() {
        val soft = vm.estimateRt60(room(10f, 10f, 10f), RoomMaterials(0.1f, 0.1f, 0.1f))
        val softer = vm.estimateRt60(room(10f, 10f, 10f), RoomMaterials(0.2f, 0.2f, 0.2f))
        assertEquals(soft.rt60S / 2f, softer.rt60S, 1e-3f)
    }

    @Test
    fun `a bigger room of the same materials rings for longer`() {
        val small = vm.estimateRt60(room(10f, 10f, 5f), RoomMaterials(0.15f, 0.15f, 0.15f))
        val big = vm.estimateRt60(room(40f, 40f, 12f), RoomMaterials(0.15f, 0.15f, 0.15f))
        assertTrue("expected ${big.rt60S} > ${small.rt60S}", big.rt60S > small.rt60S)
    }

    @Test
    fun `a fully absorbent room does not divide by zero`() {
        val est = vm.estimateRt60(room(10f, 10f, 10f), RoomMaterials(0f, 0f, 0f))
        assertEquals(0f, est.rt60S, 0f)
    }

    @Test
    fun `dimensions are reported back as measured`() {
        val est = vm.estimateRt60(room(28f, 20f, 8f), RoomMaterials(0.15f, 0.55f, 0.25f))
        assertEquals(28f, est.widthM, 1e-4f)
        assertEquals(20f, est.depthM, 1e-4f)
        assertEquals(8f, est.heightM, 1e-4f)
        assertEquals(28f * 20f * 8f, est.volumeM3, 1e-2f)
    }
}
