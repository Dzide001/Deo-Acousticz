package com.droidacoustic.pro.acoustics

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the boxes of a line array actually hang.
 *
 * An array is a chain. Each box hangs from the one above it, hinged at the
 * front, and the splay between them opens at the back - so as the angles
 * accumulate the front faces trace a curve and the lower boxes swing back
 * underneath the upper ones. That curve is the array: it sets both where each
 * element is and where it points, and the two cannot be separated.
 *
 * Box 0 is the top. Tilt is positive downward and grows down the array, so the
 * top box throws furthest and the bottom box covers the front rows.
 */
object ArrayGeometry {

    /**
     * One box, relative to the array's centroid, in the vertical plane the
     * array faces along.
     *
     * @param forwardM distance in front of the centroid, along the array's pan.
     * @param upM height above the centroid.
     * @param tiltDownDeg how far below horizontal this box's axis points.
     * @param alongM position along the chain, measured upward from its middle -
     *   retained for chain-length inspection; steering uses actual spatial positions.
     */
    data class Pose(val forwardM: Float, val upM: Float, val tiltDownDeg: Float, val alongM: Float)

    /**
     * Mechanical tilt of each box.
     *
     * Splay accumulates downward from the top box, then the whole fan is
     * re-centred so its mean sits on [aimDownDeg] - opening a joint spreads the
     * array about where it was pointed rather than swinging it.
     */
    fun tilts(count: Int, aimDownDeg: Float, splays: List<Float>): FloatArray {
        val n = count.coerceAtLeast(1)
        val t = FloatArray(n)
        for (i in 1 until n) t[i] = t[i - 1] + (splays.getOrNull(i - 1) ?: 0f)
        val offset = aimDownDeg - t.average().toFloat()
        for (i in 0 until n) t[i] += offset
        return t
    }

    /**
     * Position and tilt of each box's acoustic centre.
     *
     * The chain is built down the front faces: a box of height [boxHeightM]
     * tilted down by theta has its face running along (-sin theta, -cos theta)
     * in (forward, up), so its lower edge sits back from its upper edge. The
     * acoustic centre is taken as the middle of the face.
     */
    fun poses(count: Int, boxHeightM: Float, aimDownDeg: Float, splays: List<Float>): List<Pose> {
        val n = count.coerceAtLeast(1)
        val tilt = tilts(n, aimDownDeg, splays)
        val fwd = FloatArray(n)
        val up = FloatArray(n)
        var topF = 0.0
        var topU = 0.0
        for (i in 0 until n) {
            val th = radians(tilt[i].toDouble())
            val dirF = -sin(th)
            val dirU = -cos(th)
            fwd[i] = (topF + 0.5 * boxHeightM * dirF).toFloat()
            up[i] = (topU + 0.5 * boxHeightM * dirU).toFloat()
            topF += boxHeightM * dirF
            topU += boxHeightM * dirU
        }
        val meanF = fwd.average().toFloat()
        val meanU = up.average().toFloat()
        return List(n) { i ->
            Pose(
                forwardM = fwd[i] - meanF,
                upM = up[i] - meanU,
                tiltDownDeg = tilt[i],
                alongM = ((n - 1) * 0.5f - i) * boxHeightM
            )
        }
    }

    /** Build physical emitters from the same poses used for drawing. */
    fun sources(poses: List<Pose>, group: Int, x: Float, y: Float, z: Float,
        yawDeg: Float, steerDownDeg: Float, speedOfSound: Double, radiator: Radiator,
        gainDb: Float = 0f, delayS: Double = 0.0, inverted: Boolean = false,
        edgeTaperDb: Float = 0f, eqDb: (Int) -> Float = { 0f }, filters: DspFilterChain = DspFilterChain()): List<SoundSource> {
        val yaw = radians(yawDeg.toDouble())
        val delays = steeringDelays(poses, steerDownDeg, speedOfSound)
        return poses.mapIndexed { i, pose ->
            val edge = kotlin.math.abs(i - (poses.size - 1) * 0.5f) /
                ((poses.size - 1) * 0.5f).coerceAtLeast(1f)
            SoundSource(group, x + pose.forwardM * cos(yaw).toFloat(), y + pose.upM,
                z + pose.forwardM * sin(yaw).toFloat(), yawDeg, -pose.tiltDownDeg,
                radiator, gainDb - edgeTaperDb * edge, delayS + delays[i], inverted, eqDb, filters)
        }
    }

    /**
     * Per-box delays that steer the beam electronically by [steerDownDeg].
     *
     * Steering is delay and nothing else: the boxes stay where the rigging put
     * them and keep pointing where they point, and the wavefront is leaned over
     * by firing one end of the array ahead of the other. Returned in seconds,
     * shifted so the earliest box is at zero.
     */
    fun steeringDelays(poses: List<Pose>, steerDownDeg: Float, speedOfSound: Double): DoubleArray {
        require(speedOfSound > 0.0 && speedOfSound.isFinite())
        // Steering is an angular offset from the mean mechanical axis. Apply
        // only the change in spatial projection; zero steering adds no delay.
        val aim = poses.map { it.tiltDownDeg.toDouble() }.average().takeIf { it.isFinite() } ?: 0.0
        val base = radians(aim)
        val target = radians(aim + steerDownDeg)
        val df = cos(target) - cos(base)
        val du = -sin(target) + sin(base)
        val raw = DoubleArray(poses.size) { i ->
            (poses[i].forwardM * df + poses[i].upM * du) / speedOfSound
        }
        val earliest = raw.minOrNull() ?: 0.0
        return DoubleArray(raw.size) { raw[it] - earliest }
    }
}
