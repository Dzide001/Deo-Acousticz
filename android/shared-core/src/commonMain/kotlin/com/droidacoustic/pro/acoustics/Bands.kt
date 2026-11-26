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
     * intersects third-octave data cells, clipping the outer cells to the exact
     * requested edges. An octave centred on 1 kHz uses 800, 1000 and 1250 Hz data;
     * arbitrary fractional widths can include partial cells at either edge.
     */
    fun segments(centreHz: Int, bandwidthOct: Float): List<Segment> {
        val centre = exactHz(indexOf(centreHz))
        val half = 2.0.pow(bandwidthOct.coerceIn(1f / 24f, 3f) / 2.0)
        val low = centre / half
        val high = centre * half
        val edge = 2.0.pow(1.0 / 6.0)
        return THIRD_OCTAVE_HZ.indices.mapNotNull { i ->
            val f1 = maxOf(low, exactHz(i) / edge)
            val f2 = minOf(high, exactHz(i) * edge)
            if (f2 - f1 > 1e-6 * centre) Segment(THIRD_OCTAVE_HZ[i], f1, f2) else null
        }
    }

    /** True when [a] and [b] name the same band, tolerating 31.5 written as 32. */
    fun sameBand(a: Int, b: Int): Boolean = abs(indexOf(a) - indexOf(b)) == 0
}
