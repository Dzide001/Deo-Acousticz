package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.PlacedSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cabinet orientation.
 *
 * The mesh and the aim rays have to agree about which way a box is pointed.
 * They did not: `arrayAimDeg` is down-positive, the rays negated it and the
 * cabinet transform did not, so asking for 20 degrees of up-tilt drew the rays
 * going up and the box pointing down. These tests pin both to the same
 * convention so they cannot drift apart again.
 */
class SpeakersGlbTest {

    private fun box(panDeg: Float = 0f, aimDeg: Float = 0f, steerDeg: Float = 0f) =
        PlacedSpeaker(
            id = 0, x = 0f, z = 0f, heightM = 5f,
            panDeg = panDeg, arrayAimDeg = aimDeg, arraySteerDeg = steerDeg
        )

    private fun positions(glb: ByteArray): List<Triple<Float, Float, Float>> {
        val buf = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, buf.getInt(0))
        val jsonLen = buf.getInt(12)
        val json = String(glb, 20, jsonLen, Charsets.UTF_8)
        val binStart = 20 + jsonLen + 8
        val count = Regex(""""count":(\d+),"type":"VEC3"""").find(json)!!.groupValues[1].toInt()
        return (0 until count).map { i ->
            val o = binStart + i * 12
            Triple(buf.getFloat(o), buf.getFloat(o + 4), buf.getFloat(o + 8))
        }
    }

    /** Mean height of the half of the cabinet that faces forward. */
    private fun frontHeight(spk: PlacedSpeaker): Float {
        val verts = positions(SpeakersGlb.build(listOf(spk), emptyList())!!)
        val cabinet = verts.take(36)                  // the closed box, before the arrow
        // Midpoint of the extent, not the median: an untilted box has only two
        // distinct X values, and the median is then the maximum, which selects
        // nothing and averages to NaN.
        val midX = (cabinet.minOf { it.first } + cabinet.maxOf { it.first }) / 2f
        val front = cabinet.filter { it.first > midX }
        check(front.isNotEmpty()) { "no forward face found" }
        return front.map { it.second }.average().toFloat()
    }

    @Test
    fun `tilting up lifts the front of the cabinet`() {
        // arrayAimDeg is down-positive, so -20 is 20 degrees of up-tilt and the
        // nose of the box must rise above the level case.
        val level = frontHeight(box(aimDeg = 0f))
        val up = frontHeight(box(aimDeg = -20f))
        assertTrue("up-tilt should raise the front: $up vs $level", up > level + 0.02f)
    }

    @Test
    fun `tilting down drops the front of the cabinet`() {
        val level = frontHeight(box(aimDeg = 0f))
        val down = frontHeight(box(aimDeg = 20f))
        assertTrue("down-tilt should drop the front: $down vs $level", down < level - 0.02f)
    }

    @Test
    fun `the cabinet and the aim rays agree about which way is up`() {
        // The bug this file exists for: the two disagreed by a sign.
        listOf(-25f, -10f, 10f, 25f).forEach { aim ->
            val spk = box(aimDeg = aim)
            val rayEnd = run {
                val glb = AimRaysGlb.build(listOf(spk))!!
                positions(glb).let { it[1] }          // end of the first ray, the axis
            }
            val rayRises = rayEnd.second > spk.heightM
            val noseRises = frontHeight(spk) > frontHeight(box(aimDeg = 0f))
            assertEquals("aim $aim: ray and cabinet disagree", rayRises, noseRises)
        }
    }

    @Test
    fun `panning turns the cabinet the same way it turns the rays`() {
        val spk = box(panDeg = 90f)
        val cabinet = positions(SpeakersGlb.build(listOf(spk), emptyList())!!).take(36)
        // Pan 90 faces +Z, so the box should now be longer in Z than in X.
        val spanX = cabinet.maxOf { it.first } - cabinet.minOf { it.first }
        val spanZ = cabinet.maxOf { it.third } - cabinet.minOf { it.third }
        assertTrue("pan 90 should swing the cabinet round: spanX=$spanX spanZ=$spanZ", spanZ > spanX)
    }

    @Test
    fun `electronic steering does not move the cabinet`() {
        // Steering swings the beam without touching the box, so the mesh must
        // ignore it while the rays do not.
        val still = frontHeight(box(steerDeg = 0f))
        val steered = frontHeight(box(steerDeg = 20f))
        assertEquals("steering must not tilt the physical box", still, steered, 1e-4f)
    }

    @Test
    fun `no speakers means no mesh`() {
        assertNull(SpeakersGlb.build(emptyList(), emptyList()))
    }

    @Test
    fun `the cabinet sits at the speaker's position and height`() {
        val spk = PlacedSpeaker(id = 0, x = 4f, z = -3f, heightM = 6f)
        val cabinet = positions(SpeakersGlb.build(listOf(spk), emptyList())!!).take(36)
        val cx = (cabinet.maxOf { it.first } + cabinet.minOf { it.first }) / 2f
        val cy = (cabinet.maxOf { it.second } + cabinet.minOf { it.second }) / 2f
        val cz = (cabinet.maxOf { it.third } + cabinet.minOf { it.third }) / 2f
        assertEquals(4f, cx, 0.05f)
        assertEquals(6f, cy, 0.05f)
        assertEquals(-3f, cz, 0.05f)
    }
}
