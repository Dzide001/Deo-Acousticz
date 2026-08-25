package com.droidacoustic.pro.scene

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The distance kernel underneath every SPL figure in the app. The GPU path and
 * this CPU fallback must agree, so pinning the fallback pins the contract.
 */
class GeometryTest {

    private val vm = SceneViewModel()

    @Test
    fun `3-4-5 triangle resolves to 5 metres`() {
        val d = vm.computeDistancesCpuFallback(
            speakerPositions = floatArrayOf(0f, 0f, 0f),
            samplePoints = floatArrayOf(3f, 4f, 0f)
        )
        assertEquals(1, d.size)
        assertEquals(5f, d[0], 1e-4f)
    }

    @Test
    fun `distance is measured in three dimensions, not two`() {
        // A speaker flown 4 m above a listener 3 m away is 5 m distant, not 3 m.
        val d = vm.computeDistancesCpuFallback(
            speakerPositions = floatArrayOf(0f, 4f, 0f),
            samplePoints = floatArrayOf(3f, 0f, 0f)
        )
        assertEquals(5f, d[0], 1e-4f)
    }

    @Test
    fun `a point coincident with the source is zero, not NaN`() {
        val d = vm.computeDistancesCpuFallback(
            speakerPositions = floatArrayOf(2f, 2f, 2f),
            samplePoints = floatArrayOf(2f, 2f, 2f)
        )
        assertEquals(0f, d[0], 0f)
    }

    @Test
    fun `results are laid out point-major, speaker-minor`() {
        // Two speakers, two points. Layout is out[pointIndex * speakerCount + speakerIndex];
        // transposing it would silently attribute each level to the wrong box.
        val d = vm.computeDistancesCpuFallback(
            speakerPositions = floatArrayOf(
                0f, 0f, 0f,   // speaker 0 at origin
                10f, 0f, 0f   // speaker 1 at x = 10
            ),
            samplePoints = floatArrayOf(
                0f, 0f, 0f,   // point 0 sits on speaker 0
                10f, 0f, 0f   // point 1 sits on speaker 1
            )
        )
        assertEquals(4, d.size)
        assertEquals(0f, d[0], 1e-4f)   // point 0 -> speaker 0
        assertEquals(10f, d[1], 1e-4f)  // point 0 -> speaker 1
        assertEquals(10f, d[2], 1e-4f)  // point 1 -> speaker 0
        assertEquals(0f, d[3], 1e-4f)   // point 1 -> speaker 1
    }
}
