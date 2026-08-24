package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.HeatCell
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Builds a GLB containing smooth directional contour blobs for SPL heatmap cells.
 * One cell = triangle fan rendered as TRIANGLES.
 */
object HeatmapGlb {

    fun build(cells: List<HeatCell>): ByteArray? {
        if (cells.isEmpty()) return null

        val fanSegments = 18
        val vertsPerCell = fanSegments * 3
        val vertCount = cells.size * vertsPerCell
        val positions = FloatArray(vertCount * 3)
        val colors    = FloatArray(vertCount * 4)

        val minSpl = cells.minOf { it.splDb }
        val maxSpl = cells.maxOf { it.splDb }
        val span   = (maxSpl - minSpl).coerceAtLeast(0.1f)

        cells.forEachIndexed { i, c ->
            val v0 = i * vertsPerCell
            val t = ((c.splDb - minSpl) / span).coerceIn(0f, 1f)
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
                colors[c0 + 3] = 0.92f

                val c1 = (base + 1) * 4
                colors[c1 + 0] = r
                colors[c1 + 1] = g
                colors[c1 + 2] = b
                colors[c1 + 3] = 0.16f

                val c2 = (base + 2) * 4
                colors[c2 + 0] = r
                colors[c2 + 1] = g
                colors[c2 + 2] = b
                colors[c2 + 3] = 0.16f
            }
        }

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

    private fun heatRgb(t: Float): Triple<Float, Float, Float> {
        // Industry-standard acoustic palette: quiet=blue → cyan → green → yellow → loud=red
        return when {
            t < 0.25f -> {
                val u = t / 0.25f
                Triple(0f, u, 1f)               // blue → cyan
            }
            t < 0.5f -> {
                val u = (t - 0.25f) / 0.25f
                Triple(0f, 1f, 1f - u)          // cyan → green
            }
            t < 0.75f -> {
                val u = (t - 0.5f) / 0.25f
                Triple(u, 1f, 0f)               // green → yellow
            }
            else -> {
                val u = (t - 0.75f) / 0.25f
                Triple(1f, 1f - u, 0f)          // yellow → red (loudest)
            }
        }
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
