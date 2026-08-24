package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.ListenerPos
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Builds a GLB (glTF Binary 2.0) for the movable listener marker.
 *
 * Gold colour (1.0, 0.82, 0.0).  8 vertices as 4 LINE pairs (mode = 1):
 *   0,1 — vertical pole    (y: 0.02 → earHeight + 0.3 m)
 *   2,3 — floor cross X    (x ±0.40 m,  y = 0.05 m)
 *   4,5 — floor cross Z    (z ±0.40 m,  y = 0.05 m)
 *   6,7 — ear-level bar    (x ±0.25 m,  y = earHeight)
 *
 * Returns null when [pos] is null (caller destroys old asset).
 */
object ListenerGlb {

    private const val TOTAL_VERTS = 8

    fun build(pos: ListenerPos?): ByteArray? {
        if (pos == null) return null
        val x    = pos.x
        val z    = pos.z
        val earY = pos.earHeightM

        // Gold: R=1.0  G=0.82  B=0.0
        val cr = 1.0f; val cg = 0.82f; val cb = 0.0f

        val positions = floatArrayOf(
            // vertical pole
            x, 0.02f, z,
            x, earY + 0.3f, z,
            // floor cross X
            x - 0.40f, 0.05f, z,
            x + 0.40f, 0.05f, z,
            // floor cross Z
            x, 0.05f, z - 0.40f,
            x, 0.05f, z + 0.40f,
            // ear-level bar
            x - 0.25f, earY, z,
            x + 0.25f, earY, z
        )

        val colours = FloatArray(TOTAL_VERTS * 4)
        for (i in colours.indices step 4) {
            colours[i]     = cr
            colours[i + 1] = cg
            colours[i + 2] = cb
            colours[i + 3] = 1f
        }

        // ── Bounding box ──────────────────────────────────────────────────────
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until TOTAL_VERTS) {
            val px = positions[i * 3]; val py = positions[i * 3 + 1]; val pz = positions[i * 3 + 2]
            if (px < minX) minX = px; if (px > maxX) maxX = px
            if (py < minY) minY = py; if (py > maxY) maxY = py
            if (pz < minZ) minZ = pz; if (pz > maxZ) maxZ = pz
        }

        // ── Binary layout ─────────────────────────────────────────────────────
        val posByteLen = TOTAL_VERTS * 3 * 4
        val colByteLen = TOTAL_VERTS * 4 * 4
        val totalBin   = posByteLen + colByteLen
        val colOffset  = posByteLen

        // ── glTF JSON ─────────────────────────────────────────────────────────
        fun f(v: Float) = String.format(Locale.US, "%.4f", v)
        val json = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"name":"listener","primitives":[{"attributes":{"POSITION":0,"COLOR_0":1},"material":0,"mode":1}]}],"materials":[{"name":"lis_mat","extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorFactor":[1.0,0.82,0.0,1.0]}}],"accessors":[{"bufferView":0,"componentType":5126,"count":$TOTAL_VERTS,"type":"VEC3","min":[${f(minX)},${f(minY)},${f(minZ)}],"max":[${f(maxX)},${f(maxY)},${f(maxZ)}]},{"bufferView":1,"componentType":5126,"count":$TOTAL_VERTS,"type":"VEC4","min":[1.0,0.82,0.0,1.0],"max":[1.0,0.82,0.0,1.0]}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$posByteLen},{"buffer":0,"byteOffset":$colOffset,"byteLength":$colByteLen}],"buffers":[{"byteLength":$totalBin}]}"""

        // ── GLB assembly ──────────────────────────────────────────────────────
        fun align4(n: Int) = (n + 3) and -4
        val jsonBytes  = json.toByteArray(Charsets.UTF_8)
        val jsonPadded = align4(jsonBytes.size)
        val binPadded  = align4(totalBin)
        val totalSize  = 12 + 8 + jsonPadded + 8 + binPadded

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        // GLB header
        buf.putInt(0x46546C67); buf.putInt(2); buf.putInt(totalSize)
        // JSON chunk
        buf.putInt(jsonPadded); buf.putInt(0x4E4F534A)
        buf.put(jsonBytes); repeat(jsonPadded - jsonBytes.size) { buf.put(0x20) }
        // BIN chunk
        buf.putInt(binPadded); buf.putInt(0x004E4942)
        for (v in positions) buf.putFloat(v)
        for (v in colours)   buf.putFloat(v)
        repeat(binPadded - totalBin) { buf.put(0) }

        return buf.array()
    }
}
