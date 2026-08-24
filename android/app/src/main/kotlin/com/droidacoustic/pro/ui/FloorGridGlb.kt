package com.droidacoustic.pro.ui

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a minimal GLB (glTF Binary 2.0) for the Phase 1 3D viewport.
 *
 * Scene contents
 * ──────────────
 *   • Dynamic floor grid (1 m spacing) matching venue geometry, rendered as
 *     LINES with a green KHR_materials_unlit material.
 *   • Three axis markers at the origin:
 *       – X axis: red   (−2 → +2 along X)
 *       – Y axis: white (  0 → +2 along Y)
 *       – Z axis: blue  (−2 → +2 along Z)
 *
 * The GLB is generated entirely in RAM so no file assets are needed.
 */
object FloorGridGlb {

    fun build(widthM: Float = 20f, depthM: Float = 20f): ByteArray {
        // ── 1. Vertex data ────────────────────────────────────────────────────
        val gridVerts  = buildGridVertices(widthM, depthM)   // dynamic × vec3
        val axisVerts  = buildAxisVertices()   //  6 × vec3
        val axisColors = buildAxisColors()     //  6 × vec4 (RGBA float)

        val gridByteLen  = gridVerts.size  * Float.SIZE_BYTES     // 1008
        val axisPosByteLen = axisVerts.size  * Float.SIZE_BYTES   //   72
        val axisColByteLen = axisColors.size * Float.SIZE_BYTES   //   96

        // Binary chunk: grid positions | axis positions | axis colours
        val totalBinSize = gridByteLen + axisPosByteLen + axisColByteLen  // 1176

        // ── 2. glTF JSON ──────────────────────────────────────────────────────
        val json = buildJson(
            gridVertCount  = gridVerts.size  / 3,  // 84
            axisVertCount  = axisVerts.size  / 3,  //  6
            gridByteLen    = gridByteLen,
            axisPosByteLen = axisPosByteLen,
            axisColByteLen = axisColByteLen,
            totalBinSize   = totalBinSize
        )

        // ── 3. Assemble GLB ───────────────────────────────────────────────────
        val jsonBytes    = json.toByteArray(Charsets.UTF_8)
        val jsonPadded   = align4(jsonBytes.size)
        val binaryPadded = align4(totalBinSize)
        val totalGlbSize = 12 + 8 + jsonPadded + 8 + binaryPadded

        val buf = ByteBuffer.allocate(totalGlbSize).order(ByteOrder.LITTLE_ENDIAN)

        // GLB header
        buf.putInt(0x46546C67)   // magic = "glTF"
        buf.putInt(2)            // version 2
        buf.putInt(totalGlbSize)

        // JSON chunk
        buf.putInt(jsonPadded)
        buf.putInt(0x4E4F534A)   // "JSON"
        buf.put(jsonBytes)
        repeat(jsonPadded - jsonBytes.size) { buf.put(0x20) }  // pad with spaces

        // BIN chunk
        buf.putInt(binaryPadded)
        buf.putInt(0x004E4942)   // "BIN\0"
        for (v in gridVerts)   buf.putFloat(v)
        for (v in axisVerts)   buf.putFloat(v)
        for (v in axisColors)  buf.putFloat(v)
        repeat(binaryPadded - totalBinSize) { buf.put(0) }

        return buf.array()
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Dynamic floor grid matching venue dimensions, 1 m spacing. */
    private fun buildGridVertices(widthM: Float, depthM: Float): FloatArray {
        val halfWidth = widthM * 0.5f
        val halfDepth = depthM * 0.5f
        val verts = ArrayList<Float>()
        
        // Lines along X axis (Z varies)
        var z = -halfDepth
        while (z <= halfDepth + 0.1f) {
            verts += listOf(-halfWidth, 0f, z)
            verts += listOf(halfWidth, 0f, z)
            z += 1f
        }
        
        // Lines along Z axis (X varies)
        var x = -halfWidth
        while (x <= halfWidth + 0.1f) {
            verts += listOf(x, 0f, -halfDepth)
            verts += listOf(x, 0f, halfDepth)
            x += 1f
        }
        
        return verts.toFloatArray()
    }

    /** X, Y, Z axis lines (3 lines → 6 endpoints). */
    private fun buildAxisVertices(): FloatArray = floatArrayOf(
        -2f,  0f,  0f,   2f,  0f,  0f,   // X axis
         0f,  0f,  0f,   0f,  2f,  0f,   // Y axis (up)
         0f,  0f, -2f,   0f,  0f,  2f    // Z axis
    )

    /**
     * Per-vertex RGBA colours for the axis lines (6 verts × 4 floats).
     * X=red, Y=white, Z=blue.
     */
    private fun buildAxisColors(): FloatArray = floatArrayOf(
        0.9f, 0.2f, 0.2f, 1f,   0.9f, 0.2f, 0.2f, 1f,  // X – red
        0.9f, 0.9f, 0.9f, 1f,   0.9f, 0.9f, 0.9f, 1f,  // Y – white
        0.2f, 0.4f, 0.9f, 1f,   0.2f, 0.4f, 0.9f, 1f   // Z – blue
    )

    private fun align4(n: Int) = (n + 3) and -4

    // ── glTF JSON ─────────────────────────────────────────────────────────────
    // Two meshes, two materials (both KHR_materials_unlit).
    // Accessors encode byte offsets into the single binary buffer.
    private fun buildJson(
        gridVertCount  : Int,
        axisVertCount  : Int,
        gridByteLen    : Int,
        axisPosByteLen : Int,
        axisColByteLen : Int,
        totalBinSize   : Int
    ): String {
        val axisPosByteOffset = gridByteLen
        val axisColByteOffset = gridByteLen + axisPosByteLen
        return """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],"scene":0,"scenes":[{"nodes":[0,1]}],"nodes":[{"mesh":0},{"mesh":1}],"meshes":[{"name":"floor_grid","primitives":[{"attributes":{"POSITION":0},"material":0,"mode":1}]},{"name":"axes","primitives":[{"attributes":{"POSITION":1,"COLOR_0":2},"material":1,"mode":1}]}],"materials":[{"name":"grid_mat","extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorFactor":[0.18,0.60,0.22,1.0]}},{"name":"axis_mat","extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorFactor":[1.0,1.0,1.0,1.0]}}],"accessors":[{"bufferView":0,"componentType":5126,"count":$gridVertCount,"type":"VEC3","min":[-10.0,0.0,-10.0],"max":[10.0,0.0,10.0]},{"bufferView":1,"componentType":5126,"count":$axisVertCount,"type":"VEC3","min":[-2.0,0.0,-2.0],"max":[2.0,2.0,2.0]},{"bufferView":2,"componentType":5126,"count":$axisVertCount,"type":"VEC4","min":[0.2,0.2,0.2,1.0],"max":[0.9,0.9,0.9,1.0]}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$gridByteLen},{"buffer":0,"byteOffset":$axisPosByteOffset,"byteLength":$axisPosByteLen},{"buffer":0,"byteOffset":$axisColByteOffset,"byteLength":$axisColByteLen}],"buffers":[{"byteLength":$totalBinSize}]}"""
    }
}

// Operator to make building vertex lists readable
private operator fun ArrayList<Float>.plusAssign(values: List<Float>) { addAll(values) }
