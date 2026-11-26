package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.VenueGeometry
import com.droidacoustic.pro.geometry.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Wireframe room + stage geometry for architectural context in the 3D viewport. */
object VenueGeometryGlb {

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

        fun boundaryColor(role: AcousticBoundaryRole)=when(role) {
            AcousticBoundaryRole.NONE -> floatArrayOf(0.45f,0.45f,0.45f,1f)
            AcousticBoundaryRole.OCCLUDING -> floatArrayOf(1f,0.25f,0.2f,1f)
            AcousticBoundaryRole.RIGID_REFLECTOR -> floatArrayOf(0.25f,0.55f,1f,1f)
        }
        fun shape(shape: BoundaryShape,role: AcousticBoundaryRole) {
            if (shape.vertices.isEmpty()) return
            val edges=listOf(0 to 1,1 to 2,2 to 3,3 to 0,4 to 5,5 to 6,6 to 7,7 to 4,0 to 4,1 to 5,2 to 6,3 to 7)
            for ((a,b) in edges) {
                val p=shape.vertices[a]; val q=shape.vertices[b]
                if (p==q) continue
                addLine(floatArrayOf(p.x.toFloat(),p.y.toFloat(),p.z.toFloat()),
                    floatArrayOf(q.x.toFloat(),q.y.toFloat(),q.z.toFloat()),boundaryColor(role))
            }
        }
        shape(VenueBoundaryGeometry.stage(venue),venue.stageBoundaryRole)
        venue.blocks.forEach {shape(VenueBoundaryGeometry.block(it),it.boundaryRole)}

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
