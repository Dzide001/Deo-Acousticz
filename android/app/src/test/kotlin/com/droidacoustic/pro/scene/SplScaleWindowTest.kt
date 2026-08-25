package com.droidacoustic.pro.scene

import com.droidacoustic.pro.scene.SceneViewModel.Companion.SPL_SCALE_AUTO
import com.droidacoustic.pro.scene.SceneViewModel.Companion.SPL_SCALE_FIXED
import com.droidacoustic.pro.scene.SceneViewModel.Companion.SPL_SCALE_TARGET
import com.droidacoustic.pro.scene.SceneViewModel.Companion.splScaleWindow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dB window the colour ramp spans. Both the 3D mesh and the legend read it,
 * so a disagreement here means the key stops describing the map.
 */
class SplScaleWindowTest {

    private fun cells(vararg spl: Float) = spl.map { HeatCell(x = 0f, z = 0f, splDb = it) }

    @Test
    fun `auto spans the data`() {
        val (lo, hi) = splScaleWindow(SPL_SCALE_AUTO, 95f, 6f, 70f, 105f, cells(62f, 88f, 74f))
        assertEquals(62f, lo, 1e-4f)
        assertEquals(88f, hi, 1e-4f)
    }

    @Test
    fun `auto on an empty scene falls back to a sane window rather than zero width`() {
        val (lo, hi) = splScaleWindow(SPL_SCALE_AUTO, 95f, 6f, 70f, 105f, emptyList())
        assertEquals(70f, lo, 1e-4f)
        assertEquals(100f, hi, 1e-4f)
    }

    @Test
    fun `auto over a flat field still yields a non-degenerate window`() {
        // Every cell identical: a zero-width window would divide by zero when
        // normalising, or paint the whole field one arbitrary colour.
        val (lo, hi) = splScaleWindow(SPL_SCALE_AUTO, 95f, 6f, 70f, 105f, cells(80f, 80f, 80f))
        assertEquals(80f, lo, 1e-4f)
        assertEquals(80.1f, hi, 1e-4f)
    }

    @Test
    fun `target brackets the target by the span and ignores the data`() {
        val (lo, hi) = splScaleWindow(SPL_SCALE_TARGET, 95f, 6f, 70f, 105f, cells(20f, 30f))
        assertEquals(89f, lo, 1e-4f)
        assertEquals(101f, hi, 1e-4f)
    }

    @Test
    fun `fixed uses its own bounds and ignores the data`() {
        val (lo, hi) = splScaleWindow(SPL_SCALE_FIXED, 95f, 6f, 70f, 105f, cells(20f, 30f))
        assertEquals(70f, lo, 1e-4f)
        assertEquals(105f, hi, 1e-4f)
    }

    @Test
    fun `an unrecognised mode behaves as auto rather than throwing`() {
        val (lo, hi) = splScaleWindow("NONSENSE", 95f, 6f, 70f, 105f, cells(40f, 60f))
        assertEquals(40f, lo, 1e-4f)
        assertEquals(60f, hi, 1e-4f)
    }
}
