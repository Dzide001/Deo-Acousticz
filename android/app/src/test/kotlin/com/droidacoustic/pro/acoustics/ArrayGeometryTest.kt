package com.droidacoustic.pro.acoustics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hang of a line array.
 *
 * The model this replaced kept every box in a straight vertical stack whatever
 * the splay, and had the tilt growing *up* the array, so the top box pointed at
 * the front row and the bottom box at the back of the room - the curve of a
 * real array, upside down. These pin the chain the right way up.
 */
class ArrayGeometryTest {

    private fun uniform(n: Int, splay: Float) = List((n - 1).coerceAtLeast(0)) { splay }

    @Test
    fun `an unsplayed, untilted array is a straight vertical line`() {
        val poses = ArrayGeometry.poses(6, 0.3f, 0f, uniform(6, 0f))
        poses.forEach {
            assertEquals(0f, it.forwardM, 1e-4f)
            assertEquals(0f, it.tiltDownDeg, 1e-4f)
        }
        // Box centres one box height apart.
        poses.zipWithNext().forEach { (a, b) -> assertEquals(0.3f, a.upM - b.upM, 1e-4f) }
    }

    @Test
    fun `box zero is the top of the array`() {
        val poses = ArrayGeometry.poses(8, 0.25f, 5f, uniform(8, 3f))
        assertTrue("first box should be highest", poses.first().upM > poses.last().upM)
        poses.zipWithNext().forEach { (a, b) -> assertTrue(a.upM > b.upM) }
    }

    @Test
    fun `tilt grows down the array, so the top box throws furthest`() {
        val poses = ArrayGeometry.poses(8, 0.25f, 5f, uniform(8, 3f))
        poses.zipWithNext().forEach { (upper, lower) ->
            assertEquals(3f, lower.tiltDownDeg - upper.tiltDownDeg, 1e-3f)
        }
    }

    @Test
    fun `the fan is centred on the stated aim`() {
        listOf(0f, 7f, -4f).forEach { aim ->
            val tilts = ArrayGeometry.tilts(7, aim, listOf(1f, 2f, 2f, 4f, 6f, 8f))
            assertEquals(aim, tilts.average().toFloat(), 1e-3f)
        }
    }

    @Test
    fun `splay curls the bottom of the array back underneath the top`() {
        // The J. With every box tilted down, each face runs down and back.
        val poses = ArrayGeometry.poses(10, 0.3f, 12f, uniform(10, 4f))
        assertTrue(
            "bottom box should hang behind the top one",
            poses.last().forwardM < poses.first().forwardM - 0.1f
        )
    }

    @Test
    fun `a splayed array is shorter than a straight one`() {
        // Curving a chain of fixed length brings its ends closer together.
        fun height(splay: Float) = ArrayGeometry.poses(12, 0.3f, 0f, uniform(12, splay))
            .let { it.first().upM - it.last().upM }
        assertTrue(height(6f) < height(0f) - 0.05f)
    }

    @Test
    fun `the array is placed by its centroid`() {
        val poses = ArrayGeometry.poses(9, 0.28f, 6f, uniform(9, 2.5f))
        assertEquals(0f, poses.map { it.forwardM }.average().toFloat(), 1e-4f)
        assertEquals(0f, poses.map { it.upM }.average().toFloat(), 1e-4f)
    }

    @Test
    fun `tilting a straight array leans it without bending it`() {
        val poses = ArrayGeometry.poses(5, 0.4f, 20f, uniform(5, 0f))
        poses.forEach { assertEquals(20f, it.tiltDownDeg, 1e-3f) }
        // Still collinear: constant slope between neighbours.
        val slopes = poses.zipWithNext().map { (a, b) -> (a.forwardM - b.forwardM) / (a.upM - b.upM) }
        slopes.forEach { assertEquals(slopes.first(), it, 1e-3f) }
    }

    @Test
    fun `a single box is just a box`() {
        val p = ArrayGeometry.poses(1, 0.5f, 9f, emptyList()).single()
        assertEquals(0f, p.forwardM, 1e-5f)
        assertEquals(0f, p.upM, 1e-5f)
        assertEquals(9f, p.tiltDownDeg, 1e-5f)
    }

    // ── Electronic steering ──────────────────────────────────────────────────

    @Test
    fun `no steering means no delay`() {
        val poses = ArrayGeometry.poses(8, 0.25f, 5f, uniform(8, 3f))
        ArrayGeometry.steeringDelays(poses, 0f, 343.0).forEach { assertEquals(0.0, it, 1e-12) }
    }

    @Test
    fun `steering down fires the top of the array first`() {
        // The wavefront leans toward whichever end is delayed.
        val poses = ArrayGeometry.poses(8, 0.25f, 0f, uniform(8, 0f))
        val d = ArrayGeometry.steeringDelays(poses, 10f, 343.0)
        assertEquals(0.0, d.first(), 1e-12)
        d.toList().zipWithNext().forEach { (upper, lower) -> assertTrue(lower > upper) }
    }

    @Test
    fun `steering up reverses the order`() {
        val poses = ArrayGeometry.poses(8, 0.25f, 0f, uniform(8, 0f))
        val d = ArrayGeometry.steeringDelays(poses, -10f, 343.0)
        assertEquals(0.0, d.last(), 1e-12)
        assertTrue(d.first() > d.last())
    }

    @Test
    fun `the delay across the array is its length times the sine of the angle`() {
        val poses = ArrayGeometry.poses(8, 0.25f, 0f, uniform(8, 0f))
        val d = ArrayGeometry.steeringDelays(poses, 30f, 343.0)
        // Seven box heights between first and last centre, sin 30 = 0.5.
        assertEquals(7 * 0.25 * 0.5 / 343.0, d.last() - d.first(), 1e-9)
    }
}
