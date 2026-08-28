package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.CoverageEdges
import com.droidacoustic.pro.scene.PlacedSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The aim-ray overlay.
 *
 * A sight line rather than a prediction, but it still has to be *right*: a ray
 * that points somewhere the box is not is worse than no ray at all, because the
 * user will trust it while placing boxes. These check the geometry lands where
 * the aim says, and that the container it is wrapped in is a valid GLB.
 */
class AimRaysGlbTest {

    private fun box(
        x: Float = 0f, z: Float = 0f, heightM: Float = 6f,
        panDeg: Float = 0f, aimDeg: Float = 0f,
        elements: Int = 1, splayDeg: Float = 0f
    ) = PlacedSpeaker(
        id = 0, x = x, z = z, heightM = heightM, panDeg = panDeg,
        arrayAimDeg = aimDeg, arrayElements = elements,
        arrayInterBoxSplayDeg = splayDeg, arraySpacingM = 0.3f
    )

    /** Pull the POSITION buffer back out of the GLB so the geometry can be checked. */
    private fun positionsOf(glb: ByteArray): List<Triple<Float, Float, Float>> {
        val buf = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("glTF magic", 0x46546C67, buf.getInt(0))
        assertEquals("glTF version", 2, buf.getInt(4))
        assertEquals("length field matches actual size", glb.size, buf.getInt(8))
        val jsonLen = buf.getInt(12)
        assertEquals("JSON chunk type", 0x4E4F534A, buf.getInt(16))
        val json = String(glb, 20, jsonLen, Charsets.UTF_8).trim()
        assertEquals("BIN chunk type", 0x004E4942, buf.getInt(20 + jsonLen + 4))
        val binStart = 20 + jsonLen + 8

        val count = Regex(""""count":(\d+),"type":"VEC3"""").find(json)!!.groupValues[1].toInt()
        return (0 until count).map { i ->
            val o = binStart + i * 12
            Triple(buf.getFloat(o), buf.getFloat(o + 4), buf.getFloat(o + 8))
        }
    }

    /** Every ray is a vertex pair; this returns (start, end) for each. */
    private fun rays(glb: ByteArray) = positionsOf(glb).chunked(2).map { it[0] to it[1] }

    @Test
    fun `no speakers means no overlay to draw`() {
        assertNull(AimRaysGlb.build(emptyList()))
    }

    @Test
    fun `a single box produces an axis, two vertical edges and two horizontal`() {
        val r = rays(AimRaysGlb.build(listOf(box()))!!)
        assertEquals(5, r.size)
    }

    @Test
    fun `an array produces a vertical set per element and one horizontal pair`() {
        // Splay is vertical, so every element shares one pan. A horizontal pair
        // per element would stack identical lines on top of each other.
        val r = rays(AimRaysGlb.build(listOf(box(elements = 8)))!!)
        assertEquals(8 * 3 + 2, r.size)
    }

    @Test
    fun `every ray starts at the box`() {
        val spk = box(x = 3f, z = -4f)
        rays(AimRaysGlb.build(listOf(spk))!!).forEach { (start, _) ->
            assertEquals(3f, start.first, 1e-3f)
            assertEquals(-4f, start.third, 1e-3f)
        }
    }

    @Test
    fun `a level box throws its axis horizontally down its pan`() {
        // Pan 0 faces +X. A level ray never meets the floor, so it runs to the
        // far limit at unchanged height.
        val (start, end) = rays(AimRaysGlb.build(listOf(box(aimDeg = 0f)))!!).first()
        assertTrue("should travel along +X", end.first > start.first + 10f)
        assertEquals("should not drift sideways", 0f, end.third, 1e-2f)
        assertEquals("should stay level", start.second, end.second, 1e-2f)
    }

    @Test
    fun `aiming down lands the axis on the floor`() {
        // 6 m up, aimed 45 degrees down: the axis should reach the floor 6 m out.
        val (_, end) = rays(AimRaysGlb.build(listOf(box(heightM = 6f, aimDeg = 45f)))!!).first()
        assertEquals("should meet the floor", 0f, end.second, 1e-2f)
        assertEquals("6 m up at 45 deg lands 6 m out", 6f, end.first, 0.1f)
    }

    @Test
    fun `panning turns the rays with the box`() {
        val (start, end) = rays(AimRaysGlb.build(listOf(box(panDeg = 90f)))!!).first()
        assertTrue("pan 90 should travel along +Z", end.third > start.third + 10f)
        assertEquals("and no longer along X", 0f, end.first, 1e-2f)
    }

    @Test
    fun `aiming up never drives a ray below the floor`() {
        rays(AimRaysGlb.build(listOf(box(heightM = 2f, aimDeg = -30f)))!!).forEach { (_, end) ->
            assertTrue("ray ended below the floor at ${end.second}", end.second >= 0f)
        }
    }

    @Test
    fun `splay fans the array's elements apart`() {
        // The point of drawing an array at all: with splay, the top and bottom
        // boxes must not land in the same place.
        fun axisEnds(splay: Float) = rays(AimRaysGlb.build(listOf(
            box(heightM = 8f, aimDeg = 10f, elements = 6, splayDeg = splay)
        ))!!).filterIndexed { i, _ -> i % 3 == 0 }.map { it.second }

        val straight = axisEnds(0f)
        val fanned = axisEnds(5f)
        val straightSpread = straight.maxOf { it.first } - straight.minOf { it.first }
        val fannedSpread = fanned.maxOf { it.first } - fanned.minOf { it.first }
        assertTrue(
            "splay should spread the landing points ($fannedSpread vs $straightSpread)",
            fannedSpread > straightSpread + 5f
        )
    }

    @Test
    fun `the container is a well-formed GLB with matching chunk sizes`() {
        val glb = AimRaysGlb.build(listOf(box(elements = 4), box(x = 5f)))!!
        assertNotNull(positionsOf(glb))
        assertEquals("total length must be 4-byte aligned", 0, glb.size % 4)
    }

    @Test
    fun `many speakers stay within a sane buffer size`() {
        val many = (0 until 40).map { box(x = it.toFloat(), elements = 12) }
        val glb = AimRaysGlb.build(many)!!
        assertEquals(40 * (12 * 3 + 2), rays(glb).size)
        assertTrue("overlay grew to ${glb.size} bytes", glb.size < 1_000_000)
    }

    // ── Clipping to the room ─────────────────────────────────────────────────

    @Test
    fun `rays stop at the walls rather than leaving the building`() {
        // A ray drawn through a wall reads as coverage that does not exist.
        val glb = AimRaysGlb.build(
            listOf(box(heightM = 4f, aimDeg = 0f)),
            venueWidthM = 20f, venueDepthM = 20f, venueHeightM = 8f
        )!!
        rays(glb).forEach { (_, end) ->
            assertTrue("x ${end.first} outside the room", end.first in -10.1f..10.1f)
            assertTrue("z ${end.third} outside the room", end.third in -10.1f..10.1f)
            assertTrue("y ${end.second} outside the room", end.second in -0.1f..8.1f)
        }
    }

    @Test
    fun `a level ray stops at the wall it is pointed at`() {
        // 20 m room, box on the centre line facing +X: the wall is 10 m away.
        val glb = AimRaysGlb.build(
            listOf(box(heightM = 4f, aimDeg = 0f)),
            venueWidthM = 20f, venueDepthM = 20f, venueHeightM = 8f
        )!!
        val (_, end) = rays(glb).first()
        assertEquals(10f, end.first, 0.1f)
    }

    @Test
    fun `an upward ray stops at the ceiling`() {
        val glb = AimRaysGlb.build(
            listOf(box(heightM = 2f, aimDeg = -45f)),
            venueWidthM = 40f, venueDepthM = 40f, venueHeightM = 8f
        )!!
        val (_, end) = rays(glb).first()
        assertEquals("should meet the 8 m ceiling", 8f, end.second, 0.1f)
    }

    @Test
    fun `the floor still wins when it is nearer than a wall`() {
        val glb = AimRaysGlb.build(
            listOf(box(heightM = 6f, aimDeg = 45f)),
            venueWidthM = 40f, venueDepthM = 40f, venueHeightM = 8f
        )!!
        val (_, end) = rays(glb).first()
        assertEquals(0f, end.second, 0.1f)
        assertEquals(6f, end.first, 0.15f)
    }

    @Test
    fun `no venue given means no clipping, so old callers are unaffected`() {
        val (_, end) = rays(AimRaysGlb.build(listOf(box(aimDeg = 0f)))!!).first()
        assertTrue("unbounded ray should run to the far limit", end.first > 50f)
    }

    // ── Horizontal edges ─────────────────────────────────────────────────────

    /** The last two rays of a box are its horizontal pair. */
    private fun horizontalPair(glb: ByteArray) = rays(glb).takeLast(2)

    @Test
    fun `the horizontal edges swing out either side of the pan`() {
        // Pan 0 faces +X, so a symmetric pair must straddle it in Z.
        val glb = AimRaysGlb.build(
            listOf(box(heightM = 4f)),
            edges = mapOf(0 to CoverageEdges(20f, 20f, 45f, 45f, measured = true))
        )!!
        val (a, b) = horizontalPair(glb)
        assertTrue("both should travel forwards", a.second.first > 0f && b.second.first > 0f)
        assertTrue("they should land on opposite sides", a.second.third * b.second.third < 0f)
    }

    @Test
    fun `a wider horizontal pattern spreads the edges further apart`() {
        fun spread(deg: Float): Float {
            val glb = AimRaysGlb.build(
                listOf(box(heightM = 4f)),
                edges = mapOf(0 to CoverageEdges(20f, 20f, deg, deg, measured = true))
            )!!
            val (a, b) = horizontalPair(glb)
            return kotlin.math.abs(a.second.third - b.second.third)
        }
        assertTrue("90 deg should spread wider than 30", spread(45f) > spread(15f))
    }

    @Test
    fun `an asymmetric horizontal pattern is not drawn symmetrically`() {
        val glb = AimRaysGlb.build(
            listOf(box(heightM = 4f)),
            edges = mapOf(0 to CoverageEdges(20f, 20f, leftDeg = 10f, rightDeg = 60f, measured = true))
        )!!
        val (a, b) = horizontalPair(glb)
        val nearer = kotlin.math.abs(a.second.third)
        val further = kotlin.math.abs(b.second.third)
        assertTrue("the two sides should differ ($nearer vs $further)",
                   kotlin.math.abs(nearer - further) > 1f)
    }

    @Test
    fun `the horizontal pair turns with the box`() {
        val glb = AimRaysGlb.build(
            listOf(box(heightM = 4f, panDeg = 90f)),
            edges = mapOf(0 to CoverageEdges(20f, 20f, 30f, 30f, measured = true))
        )!!
        horizontalPair(glb).forEach { (_, end) ->
            assertTrue("pan 90 should send them down +Z, got ${end.third}", end.third > 0f)
        }
    }

    @Test
    fun `horizontal edges start at the acoustic centre, not at an element`() {
        val spk = box(heightM = 7f, elements = 6)
        horizontalPair(AimRaysGlb.build(listOf(spk))!!).forEach { (start, _) ->
            assertEquals(7f, start.second, 1e-3f)
        }
    }

    @Test
    fun `horizontal edges are clipped to the room like everything else`() {
        val glb = AimRaysGlb.build(
            listOf(box(heightM = 4f)),
            edges = mapOf(0 to CoverageEdges(20f, 20f, 80f, 80f, measured = true)),
            venueWidthM = 20f, venueDepthM = 20f, venueHeightM = 8f
        )!!
        horizontalPair(glb).forEach { (_, end) ->
            assertTrue("x ${end.first} outside", end.first in -10.1f..10.1f)
            assertTrue("z ${end.third} outside", end.third in -10.1f..10.1f)
        }
    }
}
