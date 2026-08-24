package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.AudiencePoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Simple magenta markers for audience/sample points on the floor. */
object AudienceGlb {

    private const val CROSS_HALF = 0.28f
    private const val VERTS_PER_POINT = 4 // 2 line segments

    fun build(points: List<AudiencePoint>): ByteArray? {
        if (points.isEmpty()) return null

        val totalVerts = points.size * VERTS_PER_POINT
        val positions  = FloatArray(totalVerts * 3)
        val colors     = FloatArray(totalVerts * 4)

        points.forEachIndexed { pi, p ->
            val b = pi * VERTS_PER_POINT
            val y = p.earHeightM + 0.04f
            // X segment
            positions[(b + 0) * 3 + 0] = p.x - CROSS_HALF; positions[(b + 0) * 3 + 1] = y; positions[(b + 0) * 3 + 2] = p.z
            positions[(b + 1) * 3 + 0] = p.x + CROSS_HALF; positions[(b + 1) * 3 + 1] = y; positions[(b + 1) * 3 + 2] = p.z
            // Z segment
            positions[(b + 2) * 3 + 0] = p.x; positions[(b + 2) * 3 + 1] = y; positions[(b + 2) * 3 + 2] = p.z - CROSS_HALF
            positions[(b + 3) * 3 + 0] = p.x; positions[(b + 3) * 3 + 1] = y; positions[(b + 3) * 3 + 2] = p.z + CROSS_HALF

            for (k in 0 until VERTS_PER_POINT) {
                val ci = (b + k) * 4
                colors[ci + 0] = 0.95f
                colors[ci + 1] = 0.20f
                colors[ci + 2] = 0.75f
                colors[ci + 3] = 1.0f
            }
        }

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until totalVerts) {
            val x = positions[i*3+0]; val y = positions[i*3+1]; val z = positions[i*3+2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }

        val posLen = totalVerts * 3 * 4
        val colLen = totalVerts * 4 * 4
        val totalBin = posLen + colLen
        val colOffset = posLen

        fun f(v: Float) = String.format(Locale.US, "%.4f", v)
        val json = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"name":"audience","primitives":[{"attributes":{"POSITION":0,"COLOR_0":1},"material":0,"mode":1}]}],"materials":[{"name":"aud_mat","extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorFactor":[0.95,0.20,0.75,1.0]}}],"accessors":[{"bufferView":0,"componentType":5126,"count":$totalVerts,"type":"VEC3","min":[${f(minX)},${f(minY)},${f(minZ)}],"max":[${f(maxX)},${f(maxY)},${f(maxZ)}]},{"bufferView":1,"componentType":5126,"count":$totalVerts,"type":"VEC4"}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$posLen},{"buffer":0,"byteOffset":$colOffset,"byteLength":$colLen}],"buffers":[{"byteLength":$totalBin}]}"""

        fun align4(n: Int) = (n + 3) and -4
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPad = align4(jsonBytes.size)
        val binPad = align4(totalBin)
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
}
