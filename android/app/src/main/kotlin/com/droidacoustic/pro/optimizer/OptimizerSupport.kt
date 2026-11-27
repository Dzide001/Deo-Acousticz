package com.droidacoustic.pro.optimizer

import com.droidacoustic.pro.scene.AudiencePoint
import com.droidacoustic.pro.scene.sharedSurface
import com.droidacoustic.pro.scene.AudienceArea
import kotlin.math.min
import kotlin.math.max

/**
 * Per-speaker optimization parameters (DSP array calibration).
 * Compatibility model for the disabled optimizer; no automatic solver is active.
 */
data class OptimizerParam(
    val speakerId: Int,
    val shadingGainDb: Float = 0f,       // -20 to +6 dB gain adjustment
    val delayMs: Float = 0f,             // 0 to 200 ms arrival-time shift
    val crossoverFreqHz: Int = 0,        // 0 = disabled, else 20-20000 Hz
    val crossoverSlopeDb: Float = 12f    // 12, 24, 48 dB/octave
)

/**
 * Optimizer objective function result.
 */
data class OptimizerResult(
    val params: List<OptimizerParam>,
    val objective: Float,               // Lower is better for variance; higher for coverage
    val optimizationMode: String,       // "MINIMIZE_VARIANCE" or "MAXIMIZE_COVERAGE"
    val iterationCount: Int = 0
)

/**
 * Coverage point for optimization evaluation (audience position or grid point).
 */
data class CoveragePoint(
    val x: Float,
    val z: Float,
    val earHeightM: Float = 1.2f,
    val weight: Float = 1f  // Relative importance for weighted optimization
)

/**
 * Utility to convert audience points and areas to coverage grid.
 */
object CoverageGridBuilder {

    /**
     * Build coverage grid from audience points and areas.
     * Generates dense grid within audience areas for comprehensive coverage analysis.
     */
    fun buildGrid(
        points: List<AudiencePoint>,
        areas: List<AudienceArea>,
        gridSpacingM: Float = 1.0f
    ): List<CoveragePoint> {
        val grid = mutableListOf<CoveragePoint>()

        // Add explicit points
        for (pt in points) {
            grid.add(CoveragePoint(pt.x, pt.z, pt.earHeightM, weight = 2f))
        }

        // Add grid points within areas
        for (area in areas) {
            if (area.vertices.isEmpty()) continue
            val surface=area.sharedSurface()
            if (!surface.isAudience) continue

            if (area.importedMesh!=null) {
                val mesh=com.droidacoustic.pro.geometry.CoveragePlan(listOf(surface),emptyList(),28f,28f,gridSpacingM).prepare()
                mesh.receivers().filter { surface.containsAudiencePoint(it.x,it.z) }.forEach {
                    grid.add(CoveragePoint(it.x,it.z,it.y,weight=1f))
                }
                continue
            }
            val bbox = computeBoundingBox(area.vertices)
            val (minX, minZ, maxX, maxZ) = bbox

            val rows = ((maxZ - minZ) / gridSpacingM).toInt() + 1
            val cols = ((maxX - minX) / gridSpacingM).toInt() + 1

            for (row in 0..rows) {
                for (col in 0..cols) {
                    val x = minX + col * gridSpacingM
                    val z = minZ + row * gridSpacingM

                    if (surface.containsAudiencePoint(x,z)) {
                        grid.add(CoveragePoint(x, z, surface.receiverY(x,z), weight = 1f))
                    }
                }
            }
        }

        return grid
    }

    private fun computeBoundingBox(vertices: List<Pair<Float, Float>>): Quadruple<Float, Float, Float, Float> {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        for ((x, z) in vertices) {
            minX = min(minX, x)
            maxX = max(maxX, x)
            minZ = min(minZ, z)
            maxZ = max(maxZ, z)
        }

        return Quadruple(minX, minZ, maxX, maxZ)
    }


}

/**
 * Simple quadruple data class for bounding box.
 */
data class Quadruple<T, U, V, W>(val first: T, val second: U, val third: V, val fourth: W)
