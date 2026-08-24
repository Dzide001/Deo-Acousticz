package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.VenueGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.tan

import kotlin.math.cos
import kotlin.math.sin
/** Wireframe room + stage geometry for architectural context in the 3D viewport. */
object VenueGeometryGlb {

    /**
     * Phase 6: Rotate a 2D point (x, z) around a center by given angle in degrees.
     * Returns rotated (x, z) coordinates in XZ plane.
     */
    private fun rotatePoint(x: Float, z: Float, centerX: Float, centerZ: Float, angleDegreesFromNorth: Float): Pair<Float, Float> {
        val rad = Math.toRadians(angleDegreesFromNorth.toDouble())
        val cos = cos(rad).toFloat()
        val sin = sin(rad).toFloat()
        val dx = x - centerX
        val dz = z - centerZ
        val rotX = dx * cos - dz * sin
        val rotZ = dx * sin + dz * cos
        return Pair(centerX + rotX, centerZ + rotZ)
    }

    fun build(venue: VenueGeometry): ByteArray? {
        val lines = mutableListOf<Pair<FloatArray, FloatArray>>()
        val lineColors = mutableListOf<FloatArray>()

        val minX = -venue.widthM * 0.5f
        val maxX = venue.widthM * 0.5f
        val minZ = -venue.depthM * 0.5f
        val maxZ = venue.depthM * 0.5f
        val h = venue.wallHeightM

        // Floor rectangle
        fun addLine(a: FloatArray, b: FloatArray, color: FloatArray) {
            lines += a to b
            lineColors += color
        }

        val roomColor = floatArrayOf(0.35f, 0.72f, 0.95f, 1f)
        val stageColor = floatArrayOf(0.85f, 0.2f, 0.95f, 1f)

        addLine(floatArrayOf(minX, 0f, minZ), floatArrayOf(maxX, 0f, minZ), roomColor)
        addLine(floatArrayOf(maxX, 0f, minZ), floatArrayOf(maxX, 0f, maxZ), roomColor)
        addLine(floatArrayOf(maxX, 0f, maxZ), floatArrayOf(minX, 0f, maxZ), roomColor)
        addLine(floatArrayOf(minX, 0f, maxZ), floatArrayOf(minX, 0f, minZ), roomColor)

        // Ceiling rectangle
        addLine(floatArrayOf(minX, h, minZ), floatArrayOf(maxX, h, minZ), roomColor)
        addLine(floatArrayOf(maxX, h, minZ), floatArrayOf(maxX, h, maxZ), roomColor)
        addLine(floatArrayOf(maxX, h, maxZ), floatArrayOf(minX, h, maxZ), roomColor)
        addLine(floatArrayOf(minX, h, maxZ), floatArrayOf(minX, h, minZ), roomColor)

        // Vertical corners
        addLine(floatArrayOf(minX, 0f, minZ), floatArrayOf(minX, h, minZ), roomColor)
        addLine(floatArrayOf(maxX, 0f, minZ), floatArrayOf(maxX, h, minZ), roomColor)
        addLine(floatArrayOf(maxX, 0f, maxZ), floatArrayOf(maxX, h, maxZ), roomColor)
        addLine(floatArrayOf(minX, 0f, maxZ), floatArrayOf(minX, h, maxZ), roomColor)

        // Stage top (with slope along depth axis)
        val sx0 = venue.stageCenterX - venue.stageWidthM * 0.5f
        val sx1 = venue.stageCenterX + venue.stageWidthM * 0.5f
        val sz0 = venue.stageCenterZ - venue.stageDepthM * 0.5f
        val sz1 = venue.stageCenterZ + venue.stageDepthM * 0.5f
        val rise = tan(Math.toRadians(venue.stageSlopeDeg.toDouble())).toFloat() * venue.stageDepthM
        val yFront = venue.stageHeightM - rise * 0.5f
        val yBack = venue.stageHeightM + rise * 0.5f

        addLine(floatArrayOf(sx0, yFront, sz0), floatArrayOf(sx1, yFront, sz0), stageColor)
        addLine(floatArrayOf(sx1, yFront, sz0), floatArrayOf(sx1, yBack, sz1), stageColor)
        addLine(floatArrayOf(sx1, yBack, sz1), floatArrayOf(sx0, yBack, sz1), stageColor)
        addLine(floatArrayOf(sx0, yBack, sz1), floatArrayOf(sx0, yFront, sz0), stageColor)

        // Stage verticals
        addLine(floatArrayOf(sx0, 0f, sz0), floatArrayOf(sx0, yFront, sz0), stageColor)
        addLine(floatArrayOf(sx1, 0f, sz0), floatArrayOf(sx1, yFront, sz0), stageColor)
        addLine(floatArrayOf(sx1, 0f, sz1), floatArrayOf(sx1, yBack, sz1), stageColor)
        addLine(floatArrayOf(sx0, 0f, sz1), floatArrayOf(sx0, yBack, sz1), stageColor)

        fun blockColor(type: String): FloatArray = when (type) {
            "SEATING_BANK" -> floatArrayOf(0.95f, 0.72f, 0.2f, 1f)
            "BALCONY" -> floatArrayOf(0.55f, 0.95f, 0.65f, 1f)
            "WALL" -> floatArrayOf(0.45f, 0.75f, 0.95f, 1f)
            "STAGE" -> floatArrayOf(0.85f, 0.2f, 0.95f, 1f)
            else -> floatArrayOf(0.95f, 0.25f, 0.25f, 1f)
        }

        venue.blocks.forEach { b ->
            val bx0 = b.centerX - b.widthM * 0.5f
            val bx1 = b.centerX + b.widthM * 0.5f
            val bz0 = b.centerZ - b.depthM * 0.5f
            val bz1 = b.centerZ + b.depthM * 0.5f
            val riseB = tan(Math.toRadians(b.slopeDeg.toDouble())).toFloat() * b.depthM
            val byTopF = b.heightM - riseB * 0.5f
            val byTopB = b.heightM + riseB * 0.5f
            val byBottomF = byTopF - b.blockHeightM
            val byBottomB = byTopB - b.blockHeightM
            val c = blockColor(b.type)

            // Phase 6: Apply rotation around block center
            val (rbx0, rbz0) = rotatePoint(bx0, bz0, b.centerX, b.centerZ, b.rotationDeg)
            val (rbx1, rbz0b) = rotatePoint(bx1, bz0, b.centerX, b.centerZ, b.rotationDeg)
            val (rbx1b, rbz1) = rotatePoint(bx1, bz1, b.centerX, b.centerZ, b.rotationDeg)
            val (rbx0b, rbz1b) = rotatePoint(bx0, bz1, b.centerX, b.centerZ, b.rotationDeg)

            // Top face
            addLine(floatArrayOf(rbx0, byTopF, rbz0), floatArrayOf(rbx1, byTopF, rbz0b), c)
            addLine(floatArrayOf(rbx1, byTopF, rbz0b), floatArrayOf(rbx1b, byTopB, rbz1), c)
            addLine(floatArrayOf(rbx1b, byTopB, rbz1), floatArrayOf(rbx0b, byTopB, rbz1b), c)
            addLine(floatArrayOf(rbx0b, byTopB, rbz1b), floatArrayOf(rbx0, byTopF, rbz0), c)

            // Bottom face
            addLine(floatArrayOf(rbx0, byBottomF, rbz0), floatArrayOf(rbx1, byBottomF, rbz0b), c)
            addLine(floatArrayOf(rbx1, byBottomF, rbz0b), floatArrayOf(rbx1b, byBottomB, rbz1), c)
            addLine(floatArrayOf(rbx1b, byBottomB, rbz1), floatArrayOf(rbx0b, byBottomB, rbz1b), c)
            addLine(floatArrayOf(rbx0b, byBottomB, rbz1b), floatArrayOf(rbx0, byBottomF, rbz0), c)

            // Vertical edges
            addLine(floatArrayOf(rbx0, byBottomF, rbz0), floatArrayOf(rbx0, byTopF, rbz0), c)
            addLine(floatArrayOf(rbx1, byBottomF, rbz0b), floatArrayOf(rbx1, byTopF, rbz0b), c)
            addLine(floatArrayOf(rbx1b, byBottomB, rbz1), floatArrayOf(rbx1b, byTopB, rbz1), c)
            addLine(floatArrayOf(rbx0b, byBottomB, rbz1b), floatArrayOf(rbx0b, byTopB, rbz1b), c)
        }

        val totalVerts = lines.size * 2
        if (totalVerts == 0) return null

        val positions = FloatArray(totalVerts * 3)
        val colors = FloatArray(totalVerts * 4)
        var vi = 0
        lines.forEachIndexed { idx, (a, b) ->
            val col = lineColors[idx]

            positions[vi * 3 + 0] = a[0]
            positions[vi * 3 + 1] = a[1]
            positions[vi * 3 + 2] = a[2]
            colors[vi * 4 + 0] = col[0]
            colors[vi * 4 + 1] = col[1]
            colors[vi * 4 + 2] = col[2]
            colors[vi * 4 + 3] = col[3]
            vi++

            positions[vi * 3 + 0] = b[0]
            positions[vi * 3 + 1] = b[1]
            positions[vi * 3 + 2] = b[2]
            colors[vi * 4 + 0] = col[0]
            colors[vi * 4 + 1] = col[1]
            colors[vi * 4 + 2] = col[2]
            colors[vi * 4 + 3] = col[3]
            vi++
        }

        var minPx = Float.MAX_VALUE; var maxPx = -Float.MAX_VALUE
        var minPy = Float.MAX_VALUE; var maxPy = -Float.MAX_VALUE
        var minPz = Float.MAX_VALUE; var maxPz = -Float.MAX_VALUE
        for (i in 0 until totalVerts) {
            val x = positions[i * 3 + 0]
            val y = positions[i * 3 + 1]
            val z = positions[i * 3 + 2]
            if (x < minPx) minPx = x; if (x > maxPx) maxPx = x
            if (y < minPy) minPy = y; if (y > maxPy) maxPy = y
            if (z < minPz) minPz = z; if (z > maxPz) maxPz = z
        }

        val posLen = totalVerts * 3 * 4
        val colLen = totalVerts * 4 * 4
        val totalBin = posLen + colLen
        val colOffset = posLen

        fun f(v: Float) = String.format(Locale.US, "%.4f", v)
        val json = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"name":"venue_wire","primitives":[{"attributes":{"POSITION":0,"COLOR_0":1},"material":0,"mode":1}]}],"materials":[{"name":"venue_mat","extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1]}}],"accessors":[{"bufferView":0,"componentType":5126,"count":$totalVerts,"type":"VEC3","min":[${f(minPx)},${f(minPy)},${f(minPz)}],"max":[${f(maxPx)},${f(maxPy)},${f(maxPz)}]},{"bufferView":1,"componentType":5126,"count":$totalVerts,"type":"VEC4"}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$posLen},{"buffer":0,"byteOffset":$colOffset,"byteLength":$colLen}],"buffers":[{"byteLength":$totalBin}]}"""

        fun align4(n: Int) = (n + 3) and -4
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPad = align4(jsonBytes.size)
        val binPad = align4(totalBin)
        val totalSize = 12 + 8 + jsonPad + 8 + binPad

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x46546C67)
        buf.putInt(2)
        buf.putInt(totalSize)

        buf.putInt(jsonPad)
        buf.putInt(0x4E4F534A)
        buf.put(jsonBytes)
        repeat(jsonPad - jsonBytes.size) { buf.put(0x20) }

        buf.putInt(binPad)
        buf.putInt(0x004E4942)
        for (v in positions) buf.putFloat(v)
        for (v in colors) buf.putFloat(v)
        repeat(binPad - totalBin) { buf.put(0) }

        return buf.array()
    }
}
