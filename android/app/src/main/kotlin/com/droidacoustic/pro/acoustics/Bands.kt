package com.droidacoustic.pro.acoustics

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The frequency grid the engine works on.
 *
 * Loudspeaker data arrives in third-octave bands (CLF type 2) or octave bands
 * (type 1), so third octaves are the native resolution: every analysis band is
 * expressed as one or more [Segment]s, each a stretch of spectrum that reads its
 * data from one third-octave band. An octave is three of them; a twelfth-octave
 * is a narrow slice of one.
 */
object Bands {

    /** Nominal third-octave centres, 25 Hz to 20 kHz. Index 16 is 1 kHz. */
    val THIRD_OCTAVE_HZ = intArrayOf(
        25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
        800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000,
        10000, 12500, 16000, 20000
    )

    private const val INDEX_1K = 16

    /** Exact base-two centre for a third-octave index - 1 kHz times 2^(n/3). */
    fun exactHz(index: Int): Double = 1000.0 * 2.0.pow((index - INDEX_1K) / 3.0)

    /** Index of the third-octave band whose centre is nearest [hz] on a log scale. */
    fun indexOf(hz: Double): Int {
        val n = (3.0 * ln(hz.coerceAtLeast(1.0) / 1000.0) / ln(2.0)).roundToInt() + INDEX_1K
        return n.coerceIn(0, THIRD_OCTAVE_HZ.size - 1)
    }

    fun indexOf(hz: Int): Int = indexOf(hz.toDouble())

    /**
     * A stretch of spectrum from [f1] to [f2], taking its loudspeaker data from
     * the third-octave band nominally centred on [dataHz].
     */
    data class Segment(val dataHz: Int, val f1: Double, val f2: Double) {
        val centreHz: Double get() = 0.5 * (f1 + f2)
        val widthHz: Double get() = f2 - f1
    }

    /**
     * Break an analysis band into segments.
     *
     * A band of a third of an octave or narrower is one segment. Anything wider
     * is tiled with whole third-octave bands, so an octave centred on 1 kHz is
     * 800, 1000 and 1250 Hz - each read from its own measured data rather than
     * all three borrowing the 1 kHz balloon.
     */
    fun segments(centreHz: Int, bandwidthOct: Float): List<Segment> {
        val centre = indexOf(centreHz)
        val bw = bandwidthOct.coerceIn(1f / 24f, 3f)
        if (bw <= 1f / 3f + 1e-3f) {
            val fc = exactHz(centre)
            val half = 2.0.pow(bw / 2.0)
            return listOf(Segment(THIRD_OCTAVE_HZ[centre], fc / half, fc * half))
        }
        val thirds = (bw * 3f).roundToInt().coerceAtLeast(1)
        val below = (thirds - 1) / 2
        val above = thirds - 1 - below
        val edge = 2.0.pow(1.0 / 6.0)
        return (centre - below..centre + above)
            .filter { it in THIRD_OCTAVE_HZ.indices }
            .map { i -> Segment(THIRD_OCTAVE_HZ[i], exactHz(i) / edge, exactHz(i) * edge) }
    }

    /** True when [a] and [b] name the same band, tolerating 31.5 written as 32. */
    fun sameBand(a: Int, b: Int): Boolean = abs(indexOf(a) - indexOf(b)) == 0
}
