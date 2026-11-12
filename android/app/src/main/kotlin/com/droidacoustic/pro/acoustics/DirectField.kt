package com.droidacoustic.pro.acoustics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One radiating element, placed and aimed.
 *
 * A single box is one of these. A line array is one per box, each at its own
 * position on the curve and pointing its own way - the array is never treated
 * as a single lumped source, because what an array does is exactly the sum of
 * what its boxes do.
 *
 * @param group which loudspeaker in the scene this element belongs to.
 * @param yawDeg heading in plan: 0 faces +X, positive turns toward +Z.
 * @param axisElevationDeg where the acoustic axis points, above horizontal.
 * @param eqDb gain applied in a given third-octave band, on top of [gainDb].
 */
class SoundSource(
    val group: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    yawDeg: Float,
    axisElevationDeg: Float,
    val radiator: Radiator,
    val gainDb: Float = 0f,
    val delayS: Double = 0.0,
    val inverted: Boolean = false,
    val eqDb: (dataHz: Int) -> Float = { 0f }
) {
    // Orthonormal frame of the box: forward along the axis, right in plan, up.
    private val fx: Double; private val fy: Double; private val fz: Double
    private val rx: Double; private val rz: Double
    private val ux: Double; private val uy: Double; private val uz: Double

    init {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val el = Math.toRadians(axisElevationDeg.toDouble())
        fx = cos(el) * cos(yaw); fy = sin(el); fz = cos(el) * sin(yaw)
        rx = -sin(yaw); rz = cos(yaw)
        ux = -sin(el) * cos(yaw); uy = cos(el); uz = -sin(el) * sin(yaw)
    }

    /**
     * Direction of a point as the box sees it.
     *
     * A true rotation into the box's frame. Subtracting the pan from the
     * bearing and the tilt from the elevation separately - which is what this
     * replaced - is only right for a listener near the axis: for a box tilted
     * well down and a listener well off to the side it reads the balloon in the
     * wrong place.
     *
     * @return distance in metres, azimuth and elevation in degrees.
     */
    internal fun toward(px: Float, py: Float, pz: Float, out: DoubleArray) {
        val dx = (px - x).toDouble()
        val dy = (py - y).toDouble()
        val dz = (pz - z).toDouble()
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1e-6) {
            out[0] = 0.0; out[1] = 0.0; out[2] = 0.0
            return
        }
        val lf = dx * fx + dy * fy + dz * fz
        val lr = dx * rx + dz * rz
        val lu = dx * ux + dy * uy + dz * uz
        out[0] = d
        out[1] = Math.toDegrees(atan2(lr, lf))
        out[2] = Math.toDegrees(asin((lu / d).coerceIn(-1.0, 1.0)))
    }
}

/**
 * A reflecting floor at y = 0.
 *
 * Off by default, as it is in the tools this app is measured against: a
 * direct-field prediction is free-field unless told otherwise. When enabled the
 * floor is a mirror - each element gains an image arrival, travelling the longer
 * path and weakened by whatever the floor absorbed in that band.
 */
class FloorReflection(val absorptionAt: (dataHz: Int) -> Float)

/**
 * Direct-field level at a point: every element's arrival, summed with phase.
 *
 * ## How a band is summed
 *
 * At a single frequency, arrivals add as complex pressures and the result combs
 * violently with position. Nobody listens at a single frequency. The level in a
 * band is the average of that squared sum across the band, and for arrivals of
 * amplitude a and travel time t it has an exact closed form - for each pair,
 *
 *     2 a_i a_j cos(2 pi f_c tau) sinc(pi B tau),    tau = t_i - t_j
 *
 * with f_c the centre and B the width of the band. The sinc is the whole story
 * of coherence: two boxes of the same array, with tau a fraction of a
 * millisecond, sum in pressure; a delay tower forty milliseconds away sums in
 * power; everything in between falls out of the same expression. There is no
 * blend factor to choose, and narrowing the analysis bandwidth brings the comb
 * back, as it should.
 *
 * Loudspeaker data has no phase in CLF, so each element is a point source with
 * measured magnitude and geometric phase. That is the accepted reading of the
 * format, and its limit is worth knowing: it is right for how elements combine
 * across an array and between arrays, and blind to phase behaviour inside one
 * box, such as through a crossover.
 */
class DirectField(val atmosphere: Atmosphere = Atmosphere()) {

    /**
     * Mean-square pressure at a point, in the units where 0 dB SPL is 1.
     *
     * @param segments the analysis band, see [Bands.segments]. The result is the
     *   mean over them: a signal at the reference drive spread across the band.
     * @param coherent false sums arrivals as power, ignoring phase altogether.
     * @param groupLossDb extra loss per loudspeaker, indexed by [SoundSource.group] -
     *   an obstruction in the path, for instance.
     */
    fun bandPower(
        sources: List<SoundSource>,
        px: Float,
        py: Float,
        pz: Float,
        segments: List<Bands.Segment>,
        coherent: Boolean = true,
        groupLossDb: FloatArray? = null,
        floor: FloorReflection? = null
    ): Double {
        val n = sources.size
        if (n == 0 || segments.isEmpty()) return 0.0
        val m = if (floor != null) 2 * n else n

        val dist = DoubleArray(m)
        val time = DoubleArray(m)
        val az = FloatArray(m)
        val el = FloatArray(m)
        val scratch = DoubleArray(3)
        val c = atmosphere.speedOfSound

        for (i in 0 until n) {
            val s = sources[i]
            s.toward(px, py, pz, scratch)
            dist[i] = max(scratch[0], MIN_DISTANCE_M)
            time[i] = scratch[0] / c + s.delayS
            az[i] = scratch[1].toFloat()
            el[i] = scratch[2].toFloat()
            if (floor != null) {
                // The image: same element, the listener mirrored in the floor.
                s.toward(px, -py, pz, scratch)
                dist[n + i] = max(scratch[0], MIN_DISTANCE_M)
                time[n + i] = scratch[0] / c + s.delayS
                az[n + i] = scratch[1].toFloat()
                el[n + i] = scratch[2].toFloat()
            }
        }

        val amp = DoubleArray(m)
        var total = 0.0
        for (seg in segments) {
            val alpha = atmosphere.absorptionDbPerMetre(seg.centreHz)
            val reflected = floor?.let { sqrt((1f - it.absorptionAt(seg.dataHz)).coerceIn(0f, 1f).toDouble()) }

            for (k in 0 until m) {
                val s = sources[if (k < n) k else k - n]
                val level = s.radiator.levelDb(seg.dataHz, az[k], el[k])
                if (level.isNaN()) {
                    amp[k] = 0.0
                    continue
                }
                var db = level + s.gainDb + s.eqDb(seg.dataHz) -
                    20.0 * log10(dist[k]) - alpha * dist[k]
                if (groupLossDb != null && s.group in groupLossDb.indices) db -= groupLossDb[s.group]
                var a = Math.pow(10.0, db / 20.0)
                if (k >= n) a *= reflected ?: 0.0
                amp[k] = if (s.inverted) -a else a
            }

            var p = 0.0
            if (!coherent) {
                for (k in 0 until m) p += amp[k] * amp[k]
            } else {
                val w0 = 2.0 * PI * seg.centreHz
                val w1 = PI * seg.widthHz
                for (i in 0 until m) {
                    val ai = amp[i]
                    if (ai == 0.0) continue
                    p += ai * ai
                    val ti = time[i]
                    for (j in 0 until i) {
                        val aj = amp[j]
                        if (aj == 0.0) continue
                        val tau = ti - time[j]
                        val x = w1 * tau
                        val sinc = if (abs(x) < 1e-8) 1.0 else sin(x) / x
                        p += 2.0 * ai * aj * cos(w0 * tau) * sinc
                    }
                }
            }
            total += max(p, 0.0)
        }
        return total / segments.size
    }

    /** [bandPower] as a level in dB SPL, floored at [FLOOR_DB]. */
    fun bandLevelDb(
        sources: List<SoundSource>,
        px: Float,
        py: Float,
        pz: Float,
        segments: List<Bands.Segment>,
        coherent: Boolean = true,
        groupLossDb: FloatArray? = null,
        floor: FloorReflection? = null
    ): Float = toDb(bandPower(sources, px, py, pz, segments, coherent, groupLossDb, floor))

    companion object {
        /** Inside this the inverse-square law stops meaning anything. */
        const val MIN_DISTANCE_M = 0.1

        /**
         * The quietest level reported. A point no loudspeaker reaches - no data
         * in this band, say - reads as this rather than as minus infinity, which
         * no colour scale or average survives.
         */
        const val FLOOR_DB = 0f

        fun toDb(power: Double): Float =
            if (power <= 1.0) FLOOR_DB else (10.0 * log10(power)).toFloat()
    }
}
