package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.HeatCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shaded bands: the contour drawn as the field itself.
 *
 * Quantising the level before it is coloured turns the ramp into a staircase,
 * so every colour change is an iso-level and nothing is drawn on top. The test
 * of it is countable: a smooth ramp produces a different colour for every cell,
 * a banded one produces as many colours as there are bands.
 */
class HeatmapBandingTest {

    private fun ramp(n: Int = 40): List<HeatCell> = buildList {
        for (ix in 0 until n) for (iz in 0 until 4) {
            add(HeatCell(x = ix.toFloat(), z = iz.toFloat(), splDb = 60f + ix))
        }
    }

    /** Distinct vertex colours in the built mesh. */
    private fun colourCount(glb: ByteArray): Int {
        val buf = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLen = buf.getInt(12)
        val json = String(glb, 20, jsonLen, Charsets.UTF_8)
        val count = Regex(""""count":(\d+),"type":"VEC3"""").find(json)!!.groupValues[1].toInt()
        val colOffset = Regex(""""byteOffset":(\d+),"byteLength":\d+\}\],"buffers"""")
            .find(json)!!.groupValues[1].toInt()
        val binStart = 20 + jsonLen + 8
        return (0 until count).map {
            val o = binStart + colOffset + it * 16
            Triple(
                Math.round(buf.getFloat(o) * 500f),
                Math.round(buf.getFloat(o + 4) * 500f),
                Math.round(buf.getFloat(o + 8) * 500f)
            )
        }.distinct().size
    }

    @Test
    fun `banding collapses a smooth ramp into steps`() {
        val cells = ramp()
        val smooth = colourCount(HeatmapGlb.build(cells, 60f, 99f)!!)
        val banded = colourCount(
            HeatmapGlb.build(cells, 60f, 99f, bandStepDb = 3f, bandReferenceDb = 99f)!!
        )
        assertTrue("a smooth ramp should use many colours, got $smooth", smooth > 20)
        assertTrue("bands should collapse it, got $banded against $smooth", banded < smooth / 2)
    }

    @Test
    fun `the number of bands follows the step size`() {
        val cells = ramp()                       // 39 dB of range
        val coarse = colourCount(
            HeatmapGlb.build(cells, 60f, 99f, bandStepDb = 12f, bandReferenceDb = 99f)!!
        )
        val fine = colourCount(
            HeatmapGlb.build(cells, 60f, 99f, bandStepDb = 3f, bandReferenceDb = 99f)!!
        )
        assertTrue("12 dB steps should be coarser than 3 dB: $coarse vs $fine", coarse < fine)
        assertTrue("39 dB in 12 dB steps is a handful of bands, got $coarse", coarse in 2..6)
    }

    @Test
    fun `a band boundary sits on the reference, not on an arbitrary level`() {
        // With the reference at 99 and a 3 dB step, boundaries fall at 99, 96,
        // 93 ... so a cell at 96.0 and one at 95.9 must colour differently.
        val cells = listOf(
            HeatCell(0f, 0f, 96.0f), HeatCell(1f, 0f, 95.9f),
            HeatCell(0f, 1f, 96.0f), HeatCell(1f, 1f, 95.9f)
        )
        val banded = colourCount(
            HeatmapGlb.build(cells, 90f, 100f, bandStepDb = 3f, bandReferenceDb = 99f)!!
        )
        assertEquals("the boundary should split them", 2, banded)
    }

    @Test
    fun `without a step the field is left alone`() {
        val cells = ramp()
        val a = colourCount(HeatmapGlb.build(cells, 60f, 99f)!!)
        val b = colourCount(HeatmapGlb.build(cells, 60f, 99f, bandStepDb = null, bandReferenceDb = 99f)!!)
        val c = colourCount(HeatmapGlb.build(cells, 60f, 99f, bandStepDb = 3f, bandReferenceDb = null)!!)
        assertEquals(a, b)
        assertEquals("a step with no reference cannot band, so it must not try", a, c)
    }
}
