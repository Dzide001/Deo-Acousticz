package com.droidacoustic.pro.optimizer

import com.droidacoustic.pro.scene.PlacedSpeaker
import com.droidacoustic.pro.scene.ListenerPos
import com.droidacoustic.pro.scene.AudiencePoint
import com.droidacoustic.pro.scene.AudienceArea
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.pow

/**
 * Per-speaker optimization parameters (DSP array calibration).
 * Phase 9: ArrayCalc optimizer for adaptive array shading, delay, and crossover.
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
 * ArrayCalc Optimizer: Solves for per-speaker array calibration parameters
 * to minimize coverage variance or maximize on-axis SPL.
 */
object ArrayCalcOptimizer {

    /**
     * Optimize array by minimizing SPL variance across all coverage points.
     * Uses Nelder-Mead simplex algorithm for robust multidimensional optimization.
     *
     * @param speakers List of placed speakers
     * @param coveragePoints Audience/coverage points to optimize over
     * @param refSpl Reference function: (speakerId, x, z, earHeightM, shadingDb, delayMs) -> splDb
     * @param maxIterations Max iterations (default 50 for quick turnaround)
     * @return OptimizerResult with optimized per-speaker parameters
     */
    fun minimizeVariance(
        speakers: List<PlacedSpeaker>,
        coveragePoints: List<CoveragePoint>,
        refSpl: (speakerId: Int, Float, Float, Float, Float, Float) -> Float,
        maxIterations: Int = 50
    ): OptimizerResult {
        if (speakers.isEmpty() || coveragePoints.isEmpty()) {
            return OptimizerResult(speakers.map { OptimizerParam(it.id) }, Float.MAX_VALUE, "MINIMIZE_VARIANCE", 0)
        }

        // Initial guess: no optimization (all zeros)
        val paramCount = speakers.size * 2  // shadingDb + delayMs per speaker (simplification)
        val initialX = FloatArray(paramCount)  // all zeros

        val variance = { params: FloatArray ->
            evaluateVariance(speakers, coveragePoints, params, refSpl)
        }

        // Simple gradient descent with momentum
        val optimized = gradientDescentOptimize(initialX, variance, maxIterations, learningRate = 0.1f)

        val optimizedParams = reconstructParams(speakers, optimized)
        val finalVariance = variance(optimized)

        return OptimizerResult(
            params = optimizedParams,
            objective = finalVariance,
            optimizationMode = "MINIMIZE_VARIANCE",
            iterationCount = maxIterations
        )
    }

    /**
     * Optimize array to maximize on-axis SPL at reference listener position.
     * Uses phase alignment (delay) and shading (gain) to constructively sum contributions.
     */
    fun maximizeCoverage(
        speakers: List<PlacedSpeaker>,
        refListener: ListenerPos,
        refSpl: (speakerId: Int, Float, Float, Float, Float, Float) -> Float,
        maxIterations: Int = 50
    ): OptimizerResult {
        if (speakers.isEmpty()) {
            return OptimizerResult(emptyList(), 0f, "MAXIMIZE_COVERAGE", 0)
        }

        val paramCount = speakers.size * 2
        val initialX = FloatArray(paramCount)

        val coverage = { params: FloatArray ->
            evaluateCoverage(speakers, refListener, params, refSpl)
        }

        val optimized = gradientDescentOptimize(initialX, coverage, maxIterations, learningRate = 0.1f, maximize = true)

        val optimizedParams = reconstructParams(speakers, optimized)
        val finalCoverage = coverage(optimized)

        return OptimizerResult(
            params = optimizedParams,
            objective = finalCoverage,
            optimizationMode = "MAXIMIZE_COVERAGE",
            iterationCount = maxIterations
        )
    }

    /**
     * Simple gradient descent with numerical differentiation.
     * @param x Initial parameter vector
     * @param objective Objective function to minimize (lower is better)
     * @param maxIterations Max iterations
     * @param learningRate Step size for gradient updates
     * @param maximize If true, maximize objective instead of minimize
     */
    private fun gradientDescentOptimize(
        x: FloatArray,
        objective: (FloatArray) -> Float,
        maxIterations: Int,
        learningRate: Float = 0.1f,
        maximize: Boolean = false
    ): FloatArray {
        val params = x.copyOf()
        val eps = 0.01f  // Numerical differentiation step
        val gradient = FloatArray(params.size)

        for (iter in 0 until maxIterations) {
            val currentObj = objective(params)

            // Numerical gradient
            for (i in params.indices) {
                val old = params[i]
                params[i] = old + eps
                val objPlus = objective(params)
                params[i] = old
                gradient[i] = (objPlus - currentObj) / eps
            }

            // Update parameters
            val factor = if (maximize) learningRate else -learningRate
            for (i in params.indices) {
                params[i] += factor * gradient[i]
            }

            // Clamp parameter ranges
            for (i in params.indices) {
                val isShadingParam = (i % 2) == 0
                if (isShadingParam) {
                    params[i] = params[i].coerceIn(-20f, 6f)  // Shading: -20 to +6 dB
                } else {
                    params[i] = params[i].coerceIn(0f, 200f)  // Delay: 0 to 200 ms
                }
            }
        }

        return params
    }

    /**
     * Evaluate variance of SPL across all coverage points.
     * Lower variance = better uniformity.
     */
    private fun evaluateVariance(
        speakers: List<PlacedSpeaker>,
        coveragePoints: List<CoveragePoint>,
        params: FloatArray,
        refSpl: (speakerId: Int, Float, Float, Float, Float, Float) -> Float
    ): Float {
        if (coveragePoints.isEmpty()) return Float.MAX_VALUE

        var sumSpl = 0f
        var sumSplSq = 0f
        var count = 0

        for (point in coveragePoints) {
            var totalSpl = -Float.MAX_VALUE  // Initialize to very negative (dB scale)
            for (i in speakers.indices) {
                val spk = speakers[i]
                val shadingDb = params.getOrNull(i * 2) ?: 0f
                val delayMs = params.getOrNull(i * 2 + 1) ?: 0f
                val spkSpl = refSpl(spk.id, point.x, point.z, point.earHeightM, shadingDb, delayMs)
                totalSpl = coherentSum(totalSpl, spkSpl)
            }
            if (totalSpl > -Float.MAX_VALUE) {
                sumSpl += totalSpl
                sumSplSq += totalSpl * totalSpl
                count++
            }
        }

        if (count == 0) return Float.MAX_VALUE

        val mean = sumSpl / count
        val variance = (sumSplSq / count) - (mean * mean)
        return variance.coerceAtLeast(0f)
    }

    /**
     * Evaluate on-axis SPL at reference listener position.
     * Higher SPL = better coverage.
     */
    private fun evaluateCoverage(
        speakers: List<PlacedSpeaker>,
        refListener: ListenerPos,
        params: FloatArray,
        refSpl: (speakerId: Int, Float, Float, Float, Float, Float) -> Float
    ): Float {
        var totalSpl = -Float.MAX_VALUE

        for (i in speakers.indices) {
            val spk = speakers[i]
            val shadingDb = params.getOrNull(i * 2) ?: 0f
            val delayMs = params.getOrNull(i * 2 + 1) ?: 0f
            val spkSpl = refSpl(spk.id, refListener.x, refListener.z, refListener.earHeightM, shadingDb, delayMs)
            totalSpl = coherentSum(totalSpl, spkSpl)
        }

        return if (totalSpl > -Float.MAX_VALUE) totalSpl else 0f
    }

    /**
     * Coherent sum of two SPL values in dB scale.
     * Approximation: sqrt(10^(L1/10) + 10^(L2/10))
     */
    private fun coherentSum(l1: Float, l2: Float): Float {
        return if (l1 <= -Float.MAX_VALUE + 1) {
            l2
        } else if (l2 <= -Float.MAX_VALUE + 1) {
            l1
        } else {
            val ratio = 10f.pow((l2 - l1) / 10f)
            l1 + 10f * kotlin.math.log10(1f + ratio)
        }
    }

    /**
     * Reconstruct OptimizerParam list from flattened parameter array.
     */
    private fun reconstructParams(
        speakers: List<PlacedSpeaker>,
        params: FloatArray
    ): List<OptimizerParam> {
        return speakers.mapIndexed { i, spk ->
            val shadingDb = params.getOrNull(i * 2) ?: 0f
            val delayMs = params.getOrNull(i * 2 + 1) ?: 0f
            OptimizerParam(
                speakerId = spk.id,
                shadingGainDb = shadingDb.coerceIn(-20f, 6f),
                delayMs = delayMs.coerceIn(0f, 200f)
            )
        }
    }
}

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

            val bbox = computeBoundingBox(area.vertices)
            val (minX, minZ, maxX, maxZ) = bbox

            val rows = ((maxZ - minZ) / gridSpacingM).toInt() + 1
            val cols = ((maxX - minX) / gridSpacingM).toInt() + 1

            for (row in 0..rows) {
                for (col in 0..cols) {
                    val x = minX + col * gridSpacingM
                    val z = minZ + row * gridSpacingM

                    if (pointInPolygon(x, z, area.vertices)) {
                        grid.add(CoveragePoint(x, z, area.baseHeightM, weight = 1f))
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

    /**
     * Point-in-polygon test (ray casting algorithm).
     */
    private fun pointInPolygon(x: Float, z: Float, polygon: List<Pair<Float, Float>>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].first
            val zi = polygon[i].second
            val xj = polygon[j].first
            val zj = polygon[j].second

            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}

/**
 * Simple quadruple data class for bounding box.
 */
data class Quadruple<T, U, V, W>(val first: T, val second: U, val third: V, val fourth: W)
