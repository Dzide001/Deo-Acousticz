package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.HeatCell
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Iso-level contours over the coverage map.
 *
 * A colour ramp answers "roughly how loud is it here". It cannot answer "where
 * does the coverage stop", because the answer depends on a threshold and the
 * ramp has none - in Auto scaling a 2 dB spread and a 40 dB spread paint the
 * same picture. A contour puts a line at a stated level and lets the map be
 * read as a measurement rather than an impression.
 *
 * The lines are drawn relative to a reference: the design target where one is
 * set, otherwise the loudest point on the plane. -6 dB is drawn brightest
 * because that is the conventional edge of coverage; the others are context.
 *
 * Marching squares over the same lattice the heat field is built from. The
 * segments are emitted as thin ribbons rather than glTF LINES: a line is one
 * device pixel wide however far away it is, which on a tablet at venue scale is
 * a hairline you cannot follow. A ribbon has a width in metres, so it stays
 * readable and thickens as you zoom in, like every other thing on the plan.
 */
object ContoursGlb {

    /**
     * Half-width of a contour ribbon, in metres. The emphasised line is drawn
     * thicker as well as brighter - at a glance thickness reads faster than
     * brightness, and four lines of equal weight are a maze rather than a map.
     */
    private const val HALF_WIDTH_M = 0.022f
    private const val HALF_WIDTH_EMPHASIS_M = 0.045f

    /** Levels below the reference, and how strongly each is drawn. */
    val DEFAULT_STEPS_DB = listOf(-3f, -6f, -9f, -12f)

    private data class Seg(
        val x0: Float, val z0: Float, val x1: Float, val z1: Float,
        val y: Float, val r: Float, val g: Float, val b: Float, val a: Float,
        val halfWidth: Float
    )

    /**
     * @param thresholds absolute dB levels to draw a line at.
     * @param emphasisDb the threshold drawn brightest, usually reference - 6.
     */
    fun build(
        cells: List<HeatCell>,
        thresholds: List<Float>,
        emphasisDb: Float? = null
    ): ByteArray? {
        if (cells.size < 4 || thresholds.isEmpty()) return null

        val xs = cells.map { it.x }.distinct().sorted()
        val zs = cells.map { it.z }.distinct().sorted()
        if (xs.size < 2 || zs.size < 2) return null
        val step = minOf(minGap(xs) ?: return null, minGap(zs) ?: return null)
        if (step <= 1e-3f) return null

        val x0 = xs.first()
        val z0 = zs.first()
        val grid = HashMap<Long, HeatCell>(cells.size * 2)
        cells.forEach { c ->
            val gx = ((c.x - x0) / step).roundToInt()
            val gz = ((c.z - z0) / step).roundToInt()
            if (abs(x0 + gx * step - c.x) > step * 0.25f) return null
            if (abs(z0 + gz * step - c.z) > step * 0.25f) return null
            grid[key(gx, gz)] = c
        }

        val segs = ArrayList<Seg>()
        cells.forEach { c ->
            val gx = ((c.x - x0) / step).roundToInt()
            val gz = ((c.z - z0) / step).roundToInt()
            val right = grid[key(gx + 1, gz)] ?: return@forEach
            val diag = grid[key(gx + 1, gz + 1)] ?: return@forEach
            val up = grid[key(gx, gz + 1)] ?: return@forEach
            thresholds.forEach { t ->
                val emphasised = emphasisDb != null && abs(t - emphasisDb) < 1e-3f
                marchQuad(c, right, diag, up, t, emphasised, segs)
            }
        }
        if (segs.isEmpty()) return null

        // Two triangles per segment, laid flat in the XZ plane.
        val vertCount = segs.size * 6
        val positions = FloatArray(vertCount * 3)
        val colours = FloatArray(vertCount * 4)
        var v = 0
        segs.forEach { seg ->
            val dx = seg.x1 - seg.x0
            val dz = seg.z1 - seg.z0
            val len = kotlin.math.sqrt(dx * dx + dz * dz)
            if (len < 1e-6f) return@forEach
            // Perpendicular in the plane, scaled to the ribbon's half width.
            val nx = -dz / len * seg.halfWidth
            val nz = dx / len * seg.halfWidth
            val corners = listOf(
                Triple(seg.x0 - nx, seg.z0 - nz, 0),
                Triple(seg.x0 + nx, seg.z0 + nz, 1),
                Triple(seg.x1 + nx, seg.z1 + nz, 2),
                Triple(seg.x0 - nx, seg.z0 - nz, 3),
                Triple(seg.x1 + nx, seg.z1 + nz, 4),
                Triple(seg.x1 - nx, seg.z1 - nz, 5)
            )
            corners.forEach { (cx, cz, _) ->
                positions[v * 3] = cx
                positions[v * 3 + 1] = seg.y
                positions[v * 3 + 2] = cz
                colours[v * 4] = seg.r
                colours[v * 4 + 1] = seg.g
                colours[v * 4 + 2] = seg.b
                colours[v * 4 + 3] = seg.a
                v++
            }
        }
        if (v == 0) return null
        return assemble(positions.copyOf(v * 3), colours.copyOf(v * 4), v)
    }

    /**
     * One cell of marching squares.
     *
     * Corners run anticlockwise from the near-left: a, b, c, d. Each edge whose
     * endpoints straddle the threshold contributes one crossing, linearly
     * interpolated. Two crossings make one segment; four make two, and the
     * ambiguous saddle is resolved by the mean of the corners, which picks the
     * pairing that keeps the contour closed around the louder side.
     */
    private fun marchQuad(
        a: HeatCell, b: HeatCell, c: HeatCell, d: HeatCell,
        t: Float, emphasised: Boolean, out: MutableList<Seg>
    ) {
        val va = a.splDb; val vb = b.splDb; val vc = c.splDb; val vd = d.splDb
        val lo = minOf(va, vb, vc, vd)
        val hi = maxOf(va, vb, vc, vd)
        if (t < lo || t > hi) return
        // A contour needs a gradient to sit on. Without this, a perfectly flat
        // cell sitting exactly on the threshold reports a crossing on all four
        // edges and paints lines across ground that has no boundary in it.
        if (hi - lo < 1e-6f) return

        val pts = ArrayList<Pair<Float, Float>>(4)
        fun edge(p: HeatCell, q: HeatCell) {
            val vp = p.splDb; val vq = q.splDb
            if ((vp < t && vq < t) || (vp > t && vq > t)) return
            val span = vq - vp
            val f = if (abs(span) < 1e-6f) 0.5f else ((t - vp) / span).coerceIn(0f, 1f)
            pts += (p.x + (q.x - p.x) * f) to (p.z + (q.z - p.z) * f)
        }
        edge(a, b); edge(b, c); edge(c, d); edge(d, a)
        if (pts.size < 2) return

        // Well clear of the heat field. At 6 cm the lines fought the surface for
        // depth and came through as dashes that read as noise rather than as
        // contours; the map is metres across, so a quarter of a metre costs
        // nothing in position and buys a line you can actually follow.
        val y = maxOf(a.renderY, b.renderY, c.renderY, d.renderY) + 0.25f
        val alpha = if (emphasised) 0.95f else 0.5f
        val grey = if (emphasised) 1.00f else 0.88f
        val hw = if (emphasised) HALF_WIDTH_EMPHASIS_M else HALF_WIDTH_M
        fun emit(p: Pair<Float, Float>, q: Pair<Float, Float>) {
            out += Seg(p.first, p.second, q.first, q.second, y, grey, grey, grey, alpha, hw)
        }
        when (pts.size) {
            2 -> emit(pts[0], pts[1])
            4 -> {
                // Saddle. Pair the crossings so the contour wraps the louder
                // corners rather than cutting across them.
                if ((va + vb + vc + vd) / 4f >= t) {
                    emit(pts[0], pts[3]); emit(pts[1], pts[2])
                } else {
                    emit(pts[0], pts[1]); emit(pts[2], pts[3])
                }
            }
            else -> emit(pts[0], pts[1])
        }
    }

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)

    private fun minGap(sorted: List<Float>): Float? =
        sorted.zipWithNext().minOfOrNull { (a, b) -> b - a }?.takeIf { it > 0f }

    private fun assemble(positions: FloatArray, colours: FloatArray, vertCount: Int): ByteArray {
        val posBytes = positions.size * 4
        val colBytes = colours.size * 4
        val colOffset = align4(posBytes)
        val binLen = align4(colOffset + colBytes)
        fun axis(o: Int) = (0 until vertCount).map { positions[it * 3 + o] }
        fun f(v: Float) = String.format(Locale.US, "%.4f", v)

        val json = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],""" +
            """"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],""" +
            """"meshes":[{"name":"contours","primitives":[{"attributes":{"POSITION":0,"COLOR_0":1},"material":0,"mode":4}]}],""" +
            """"materials":[{"name":"contour_mat","extensions":{"KHR_materials_unlit":{}},""" +
            """"pbrMetallicRoughness":{"baseColorFactor":[1.0,1.0,1.0,1.0]},"alphaMode":"BLEND","doubleSided":true}],""" +
            """"accessors":[{"bufferView":0,"componentType":5126,"count":$vertCount,"type":"VEC3",""" +
            """"min":[${f(axis(0).min())},${f(axis(1).min())},${f(axis(2).min())}],""" +
            """"max":[${f(axis(0).max())},${f(axis(1).max())},${f(axis(2).max())}]},""" +
            """{"bufferView":1,"componentType":5126,"count":$vertCount,"type":"VEC4",""" +
            """"min":[0.0,0.0,0.0,0.0],"max":[1.0,1.0,1.0,1.0]}],""" +
            """"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$posBytes},""" +
            """{"buffer":0,"byteOffset":$colOffset,"byteLength":$colBytes}],""" +
            """"buffers":[{"byteLength":$binLen}]}"""

        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPadded = align4(jsonBytes.size)
        val total = 12 + 8 + jsonPadded + 8 + binLen
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x46546C67); buf.putInt(2); buf.putInt(total)
        buf.putInt(jsonPadded); buf.putInt(0x4E4F534A)
        buf.put(jsonBytes); repeat(jsonPadded - jsonBytes.size) { buf.put(0x20) }
        buf.putInt(binLen); buf.putInt(0x004E4942)
        val binStart = buf.position()
        positions.forEach { buf.putFloat(it) }
        buf.position(binStart + colOffset)
        colours.forEach { buf.putFloat(it) }
        buf.position(binStart + binLen)
        return buf.array()
    }

    private fun align4(v: Int) = (v + 3) and 3.inv()
}
