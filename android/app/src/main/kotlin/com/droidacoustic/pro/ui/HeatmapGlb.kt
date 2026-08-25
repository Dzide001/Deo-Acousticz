package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.HeatCell
import com.droidacoustic.pro.ui.theme.Instrument
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Builds a GLB containing smooth directional contour blobs for SPL heatmap cells.
 * One cell = triangle fan rendered as TRIANGLES.
 */
object HeatmapGlb {

    // Opacity of the continuous field. The old blob mesh faded each cell from a
    // 0.92 core to a 0.16 rim, which is what produced the interlocking-oval look:
    // neighbouring rims cross-faded instead of joining. A continuous surface has no
    // rim to fade, so one weight carries the whole field - set high enough that the
    // muted theme ramp still reads at a glance.
    private const val FIELD_ALPHA = 0.72f

    // Fallback blob rim. Raised from 0.16 so the softer palette keeps its contrast
    // if we ever fall back to that path.
    private const val BLOB_RIM_ALPHA = 0.34f
    private const val BLOB_CORE_ALPHA = 0.94f

    /**
     * @param minDb / @param maxDb the dB window the colour ramp spans. Passed in
     * rather than derived from the cells so the mesh and the legend agree, and so
     * an absolute scale can be held steady across separate calculations.
     *
     * Samples that sit on a regular lattice - which is how both the default floor
     * grid and the zone grids are generated - are drawn as one continuous surface,
     * letting the GPU interpolate colour across each quad. That is what makes the
     * field read as a smooth gradient rather than a raft of overlapping ovals.
     * Anything that is not a lattice falls back to the original blob renderer.
     */
    fun build(cells: List<HeatCell>, minDb: Float, maxDb: Float): ByteArray? {
        if (cells.isEmpty()) return null
        return buildField(cells, minDb, maxDb) ?: buildBlobs(cells, minDb, maxDb)
    }

    /**
     * Reconstructs lattice topology from the sample coordinates and emits two
     * triangles per complete quad. Returns null when the samples do not form a
     * lattice, or when no complete quad exists.
     */
    private fun buildField(cells: List<HeatCell>, minDb: Float, maxDb: Float): ByteArray? {
        val xs = cells.map { it.x }.distinct().sorted()
        val zs = cells.map { it.z }.distinct().sorted()
        if (xs.size < 2 || zs.size < 2) return null

        val step = minOf(minGap(xs) ?: return null, minGap(zs) ?: return null)
        if (step <= 1e-3f) return null

        val x0 = xs.first()
        val z0 = zs.first()
        fun ix(c: HeatCell) = ((c.x - x0) / step).roundToInt()
        fun iz(c: HeatCell) = ((c.z - z0) / step).roundToInt()

        // Snapping to the lattice must be exact, or the quad lookup silently misses.
        val grid = HashMap<Long, HeatCell>(cells.size * 2)
        cells.forEach { c ->
            val gx = ix(c)
            val gz = iz(c)
            if (abs(x0 + gx * step - c.x) > step * 0.25f) return null
            if (abs(z0 + gz * step - c.z) > step * 0.25f) return null
            grid[key(gx, gz)] = c
        }

        data class Quad(val a: HeatCell, val b: HeatCell, val c: HeatCell, val d: HeatCell)
        val quads = ArrayList<Quad>()
        cells.forEach { c ->
            val gx = ix(c)
            val gz = iz(c)
            val right = grid[key(gx + 1, gz)] ?: return@forEach
            val up = grid[key(gx, gz + 1)] ?: return@forEach
            val diag = grid[key(gx + 1, gz + 1)] ?: return@forEach
            quads += Quad(c, right, diag, up)
        }
        if (quads.isEmpty()) return null

        val vertCount = quads.size * 6
        val positions = FloatArray(vertCount * 3)
        val colors = FloatArray(vertCount * 4)
        val span = (maxDb - minDb).coerceAtLeast(0.1f)

        var v = 0
        fun put(c: HeatCell) {
            positions[v * 3 + 0] = c.x
            positions[v * 3 + 1] = c.renderY + 0.03f
            positions[v * 3 + 2] = c.z
            val t = ((c.splDb - minDb) / span).coerceIn(0f, 1f)
            val (r, g, b) = heatRgb(t)
            colors[v * 4 + 0] = r
            colors[v * 4 + 1] = g
            colors[v * 4 + 2] = b
            colors[v * 4 + 3] = FIELD_ALPHA
            v++
        }
        quads.forEach { q ->
            put(q.a); put(q.b); put(q.c)
            put(q.a); put(q.c); put(q.d)
        }

        return assembleGlb(positions, colors, vertCount)
    }

    /** Smallest positive gap between consecutive sorted values. */
    private fun minGap(sorted: List<Float>): Float? {
        var best = Float.MAX_VALUE
        for (i in 1 until sorted.size) {
            val d = sorted[i] - sorted[i - 1]
            if (d > 1e-4f && d < best) best = d
        }
        return if (best == Float.MAX_VALUE) null else best
    }

    private fun key(gx: Int, gz: Int): Long = (gx.toLong() shl 32) xor (gz.toLong() and 0xffffffffL)

    private fun buildBlobs(cells: List<HeatCell>, minDb: Float, maxDb: Float): ByteArray? {
        val fanSegments = 18
        val vertsPerCell = fanSegments * 3
        val vertCount = cells.size * vertsPerCell
        val positions = FloatArray(vertCount * 3)
        val colors    = FloatArray(vertCount * 4)

        val span = (maxDb - minDb).coerceAtLeast(0.1f)

        cells.forEachIndexed { i, c ->
            val v0 = i * vertsPerCell
            val t = ((c.splDb - minDb) / span).coerceIn(0f, 1f)
            val (r, g, b) = heatRgb(t)
            val y = c.renderY + 0.03f
            val zoneType = c.sourceZoneType ?: "AUDIENCE_SEATED"
            val rakeInfluence = (abs(c.sourceRakeDeg).coerceAtMost(30f) / 30f).coerceIn(0f, 1f)
            val baseRadius = 1.02f + t * 0.08f
            val longAxis = baseRadius * when (zoneType) {
                "AUDIENCE_STANDING" -> 1.18f
                "STAGE" -> 1.06f
                else -> 1.34f
            } * (1f + rakeInfluence * 0.18f)
            val shortAxis = (baseRadius * when (zoneType) {
                "AUDIENCE_STANDING" -> 0.88f
                "STAGE" -> 0.94f
                else -> 0.74f
            } * (1f - rakeInfluence * 0.08f)).coerceAtLeast(0.52f)
            val rotationRad = Math.toRadians(c.sourceRakeDirectionDeg.toDouble())
            val shapeExponent = when (zoneType) {
                "AUDIENCE_STANDING" -> 2.6
                "STAGE" -> 2.4
                else -> 3.2
            }

            for (seg in 0 until fanSegments) {
                val a0 = (seg.toFloat() / fanSegments.toFloat()) * (PI * 2.0)
                val a1 = ((seg + 1).toFloat() / fanSegments.toFloat()) * (PI * 2.0)
                val x0 = c.x + footprintX(a0, longAxis, shortAxis, rotationRad, shapeExponent)
                val z0 = c.z + footprintZ(a0, longAxis, shortAxis, rotationRad, shapeExponent)
                val x1 = c.x + footprintX(a1, longAxis, shortAxis, rotationRad, shapeExponent)
                val z1 = c.z + footprintZ(a1, longAxis, shortAxis, rotationRad, shapeExponent)

                val base = v0 + seg * 3

                // center
                positions[(base + 0) * 3 + 0] = c.x
                positions[(base + 0) * 3 + 1] = y
                positions[(base + 0) * 3 + 2] = c.z
                // edge 0
                positions[(base + 1) * 3 + 0] = x0
                positions[(base + 1) * 3 + 1] = y
                positions[(base + 1) * 3 + 2] = z0
                // edge 1
                positions[(base + 2) * 3 + 0] = x1
                positions[(base + 2) * 3 + 1] = y
                positions[(base + 2) * 3 + 2] = z1

                // Center strong alpha, edge soft alpha for contour blending
                val c0 = (base + 0) * 4
                colors[c0 + 0] = r
                colors[c0 + 1] = g
                colors[c0 + 2] = b
                colors[c0 + 3] = BLOB_CORE_ALPHA

                val c1 = (base + 1) * 4
                colors[c1 + 0] = r
                colors[c1 + 1] = g
                colors[c1 + 2] = b
                colors[c1 + 3] = BLOB_RIM_ALPHA

                val c2 = (base + 2) * 4
                colors[c2 + 0] = r
                colors[c2 + 1] = g
                colors[c2 + 2] = b
                colors[c2 + 3] = BLOB_RIM_ALPHA
            }
        }

        return assembleGlb(positions, colors, vertCount)
    }


    /** Packs positions + vertex colours into a single unlit GLB. */
    private fun assembleGlb(positions: FloatArray, colors: FloatArray, vertCount: Int): ByteArray {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until vertCount) {
            val x = positions[i * 3 + 0]
            val y = positions[i * 3 + 1]
            val z = positions[i * 3 + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }

        val posByteLen = vertCount * 3 * 4
        val colByteLen = vertCount * 4 * 4
        val totalBin   = posByteLen + colByteLen
        val colOffset  = posByteLen

        fun f(v: Float) = String.format(Locale.US, "%.4f", v)
        val json = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"name":"heatmap","primitives":[{"attributes":{"POSITION":0,"COLOR_0":1},"material":0,"mode":4}]}],"materials":[{"name":"heat_mat","doubleSided":true,"alphaMode":"BLEND","extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1]}}],"accessors":[{"bufferView":0,"componentType":5126,"count":$vertCount,"type":"VEC3","min":[${f(minX)},${f(minY)},${f(minZ)}],"max":[${f(maxX)},${f(maxY)},${f(maxZ)}]},{"bufferView":1,"componentType":5126,"count":$vertCount,"type":"VEC4"}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$posByteLen},{"buffer":0,"byteOffset":$colOffset,"byteLength":$colByteLen}],"buffers":[{"byteLength":$totalBin}]}"""

        fun align4(n: Int) = (n + 3) and -4
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPad   = align4(jsonBytes.size)
        val binPad    = align4(totalBin)
        val totalSize = 12 + 8 + jsonPad + 8 + binPad

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x46546C67); buf.putInt(2); buf.putInt(totalSize)
        buf.putInt(jsonPad); buf.putInt(0x4E4F534A)
        buf.put(jsonBytes); repeat(jsonPad - jsonBytes.size) { buf.put(0x20) }
        buf.putInt(binPad); buf.putInt(0x004E4942)
        for (v in positions) buf.putFloat(v)
        for (v in colors) buf.putFloat(v)
        repeat(binPad - totalBin) { buf.put(0) }
        return buf.array()
    }

    // The one ramp. Instrument.Spl is what the legend gradient is drawn from, so
    // sampling it here is what makes the key describe the mesh it explains - the
    // mesh used to carry its own fully-saturated blue→red ramp that matched
    // nothing else on screen.
    private val RAMP: List<Triple<Float, Float, Float>> =
        Instrument.Spl.map { Triple(toLinear(it.red), toLinear(it.green), toLinear(it.blue)) }

    /**
     * glTF COLOR_0 is defined in linear space, but Compose hands back sRGB-encoded
     * components. Passing those straight through lifts every mid-tone toward white,
     * which is why the field used to render noticeably paler than the legend drawn
     * from the very same colours.
     */
    private fun toLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    private fun heatRgb(t: Float): Triple<Float, Float, Float> {
        val scaled = t.coerceIn(0f, 1f) * (RAMP.size - 1)
        val i = scaled.toInt().coerceIn(0, RAMP.size - 2)
        val u = scaled - i
        val a = RAMP[i]
        val b = RAMP[i + 1]
        return Triple(
            a.first  + (b.first  - a.first)  * u,
            a.second + (b.second - a.second) * u,
            a.third  + (b.third  - a.third)  * u
        )
    }

    private fun footprintX(angleRad: Double, majorAxis: Float, minorAxis: Float, rotationRad: Double, exponent: Double): Float {
        val cosTheta = cos(angleRad)
        val sinTheta = sin(angleRad)
        val shapeX = signedPow(abs(cosTheta), 2.0 / exponent) * sign(cosTheta)
        val shapeZ = signedPow(abs(sinTheta), 2.0 / exponent) * sign(sinTheta)
        val localX = shapeX * majorAxis
        val localZ = shapeZ * minorAxis
        val rotatedX = localX * cos(rotationRad) - localZ * sin(rotationRad)
        return rotatedX.toFloat()
    }

    private fun footprintZ(angleRad: Double, majorAxis: Float, minorAxis: Float, rotationRad: Double, exponent: Double): Float {
        val cosTheta = cos(angleRad)
        val sinTheta = sin(angleRad)
        val shapeX = signedPow(abs(cosTheta), 2.0 / exponent) * sign(cosTheta)
        val shapeZ = signedPow(abs(sinTheta), 2.0 / exponent) * sign(sinTheta)
        val localX = shapeX * majorAxis
        val localZ = shapeZ * minorAxis
        val rotatedZ = localX * sin(rotationRad) + localZ * cos(rotationRad)
        return rotatedZ.toFloat()
    }

    private fun signedPow(value: Double, exponent: Double): Double = value.pow(exponent)

    private fun sign(value: Double): Double = if (value < 0.0) -1.0 else 1.0
}
