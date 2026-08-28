package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.HeatCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Iso-level contours.
 *
 * The point of a contour is that it is a measurement, not an impression: a line
 * at -6 dB has to actually sit where the level is 6 dB down. These tests build
 * fields whose answer is known by construction - a flat ramp along x, a radial
 * cone - and check the line lands where the arithmetic says it should.
 */
class ContoursGlbTest {

    /** A grid whose level is a function of position, so the answer is known. */
    private fun field(
        nx: Int = 21, nz: Int = 21, step: Float = 1f,
        level: (Float, Float) -> Float
    ): List<HeatCell> = buildList {
        for (ix in 0 until nx) for (iz in 0 until nz) {
            val x = ix * step
            val z = iz * step
            add(HeatCell(x = x, z = z, splDb = level(x, z)))
        }
    }

    private fun segments(glb: ByteArray): List<Pair<Triple<Float, Float, Float>, Triple<Float, Float, Float>>> {
        val buf = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("glTF magic", 0x46546C67, buf.getInt(0))
        assertEquals("length matches", glb.size, buf.getInt(8))
        val jsonLen = buf.getInt(12)
        val json = String(glb, 20, jsonLen, Charsets.UTF_8)
        assertTrue("should be triangles", json.contains("\"mode\":4"))
        val binStart = 20 + jsonLen + 8
        val count = Regex(""""count":(\d+),"type":"VEC3"""").find(json)!!.groupValues[1].toInt()
        val pts = (0 until count).map { i ->
            val o = binStart + i * 12
            Triple(buf.getFloat(o), buf.getFloat(o + 4), buf.getFloat(o + 8))
        }
        // Six vertices per segment: two triangles forming a ribbon. Corner 0 is
        // one end, corner 5 the other, which is enough to recover the centreline.
        return pts.chunked(6).map { c ->
            Triple((c[0].first + c[1].first) / 2f, (c[0].second + c[1].second) / 2f, (c[0].third + c[1].third) / 2f) to
                Triple((c[4].first + c[5].first) / 2f, (c[4].second + c[5].second) / 2f, (c[4].third + c[5].third) / 2f)
        }
    }

    // ── Placement ────────────────────────────────────────────────────────────

    @Test
    fun `a contour lands where the level actually is`() {
        // Level falls 1 dB per metre along x from 100 dB, so the 94 dB line must
        // sit at x = 6 and run straight across z.
        val cells = field { x, _ -> 100f - x }
        val segs = segments(ContoursGlb.build(cells, listOf(94f))!!)
        assertTrue("expected a contour", segs.isNotEmpty())
        segs.forEach { (a, b) ->
            assertEquals("start x", 6f, a.first, 0.05f)
            assertEquals("end x", 6f, b.first, 0.05f)
        }
    }

    @Test
    fun `each threshold gets its own line at its own place`() {
        val cells = field { x, _ -> 100f - x }
        val segs = segments(ContoursGlb.build(cells, listOf(97f, 94f, 91f))!!)
        val xs = segs.flatMap { listOf(it.first.first, it.second.first) }.distinct().sorted()
        // 3, 6 and 9 metres out.
        listOf(3f, 6f, 9f).forEach { expected ->
            assertTrue("no contour near x=$expected in $xs", xs.any { kotlin.math.abs(it - expected) < 0.06f })
        }
    }

    @Test
    fun `a radial field produces a closed ring at the right radius`() {
        // 100 dB at the centre falling 1 dB per metre: the 94 dB contour is a
        // circle of radius 6 about (10, 10).
        val cells = field(nx = 21, nz = 21) { x, z ->
            val dx = x - 10f; val dz = z - 10f
            100f - kotlin.math.sqrt(dx * dx + dz * dz)
        }
        val segs = segments(ContoursGlb.build(cells, listOf(94f))!!)
        assertTrue("expected a ring of segments, got ${segs.size}", segs.size > 12)
        segs.forEach { (a, _) ->
            val r = kotlin.math.sqrt((a.first - 10f) * (a.first - 10f) + (a.third - 10f) * (a.third - 10f))
            assertEquals("segment off the ring", 6f, r, 0.35f)
        }
    }

    @Test
    fun `contours sit above the field they describe`() {
        val cells = field { x, _ -> 100f - x }
        segments(ContoursGlb.build(cells, listOf(94f))!!).forEach { (a, b) ->
            assertTrue("y ${a.second} should clear the heat field", a.second > 0.2f)
            assertEquals(a.second, b.second, 1e-4f)
        }
    }

    // ── Nothing to draw ──────────────────────────────────────────────────────

    @Test
    fun `a threshold outside the data draws nothing`() {
        val cells = field { x, _ -> 100f - x }
        assertNull(ContoursGlb.build(cells, listOf(140f)))
        assertNull(ContoursGlb.build(cells, listOf(10f)))
    }

    @Test
    fun `a flat field has no contour to draw`() {
        assertNull(ContoursGlb.build(field { _, _ -> 90f }, listOf(90f)))
    }

    @Test
    fun `too little data is handled rather than crashing`() {
        assertNull(ContoursGlb.build(emptyList(), listOf(90f)))
        assertNull(ContoursGlb.build(field(nx = 1, nz = 1) { _, _ -> 90f }, listOf(90f)))
        assertNull(ContoursGlb.build(field { x, _ -> 100f - x }, emptyList()))
    }

    @Test
    fun `an irregular scatter is refused rather than drawn wrongly`() {
        // Marching squares needs a lattice. Points that do not sit on one must
        // produce nothing rather than a plausible-looking wrong answer.
        val scattered = listOf(
            HeatCell(0f, 0f, 100f), HeatCell(1.3f, 0.7f, 96f),
            HeatCell(2.9f, 1.1f, 92f), HeatCell(4.1f, 2.6f, 88f)
        )
        assertNull(ContoursGlb.build(scattered, listOf(94f)))
    }

    // ── Emphasis ─────────────────────────────────────────────────────────────

    @Test
    fun `the emphasised threshold is drawn more strongly than the others`() {
        val cells = field { x, _ -> 100f - x }
        val glb = ContoursGlb.build(cells, listOf(97f, 94f), emphasisDb = 94f)!!
        val buf = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLen = buf.getInt(12)
        val json = String(glb, 20, jsonLen, Charsets.UTF_8)
        val count = Regex(""""count":(\d+),"type":"VEC3"""").find(json)!!.groupValues[1].toInt()
        val colOffset = Regex(""""byteOffset":(\d+),"byteLength":\d+\}\],"buffers"""")
            .find(json)!!.groupValues[1].toInt()
        val binStart = 20 + jsonLen + 8
        val alphas = (0 until count).map { buf.getFloat(binStart + colOffset + it * 16 + 12) }
            .distinct()
        assertEquals("expected two strengths, got $alphas", 2, alphas.size)
        assertTrue("emphasis should be the stronger", alphas.max() > alphas.min() + 0.2f)
    }
}
