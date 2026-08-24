package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.AudienceArea
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Renders audience area outlines (including current draft polygon). */
object AudienceAreasGlb {

    fun build(
        areas: List<AudienceArea>,
        draft: List<Pair<Float, Float>>,
        draftZoneType: String = "AUDIENCE_SEATED",
        draftBaseHeightM: Float = 0f,
        draftRakeDeg: Float = 0f,
        draftRakeDirectionDeg: Float = 0f
    ): ByteArray? {
        val all = if (draft.size >= 2) {
            areas + AudienceArea(
                id = -1,
                name = "DRAFT",
                zoneType = draftZoneType,
                baseHeightM = draftBaseHeightM,
                rakeDeg = draftRakeDeg,
                rakeDirectionDeg = draftRakeDirectionDeg,
                vertices = draft
            )
        } else {
            areas
        }
        if (all.isEmpty()) return null

        var totalVerts = 0
        all.forEach { a ->
            val n = a.vertices.size
            if (n >= 2) totalVerts += if (a.id == -1 || n < 3) (n - 1) * 2 else n * 2
        }
        if (totalVerts == 0) return null

        val positions = FloatArray(totalVerts * 3)
        val colors = FloatArray(totalVerts * 4)
        var cursor = 0

        all.forEach { area ->
            val v = area.vertices
            if (v.size < 2) return@forEach

            val pairs = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
            for (i in 0 until v.lastIndex) pairs += v[i] to v[i + 1]
            if (area.id != -1 && v.size >= 3) pairs += v.last() to v.first()

            val cx = v.sumOf { it.first.toDouble() }.toFloat() / v.size
            val cz = v.sumOf { it.second.toDouble() }.toFloat() / v.size
            val rakeSlope = tan(Math.toRadians(area.rakeDeg.toDouble())).toFloat()
            val rakeDirRad = Math.toRadians(area.rakeDirectionDeg.toDouble())
            val rakeDx = sin(rakeDirRad).toFloat()
            val rakeDz = cos(rakeDirRad).toFloat()

            fun yForPoint(x: Float, z: Float): Float {
                val d = (x - cx) * rakeDx + (z - cz) * rakeDz
                return area.baseHeightM + rakeSlope * d + 0.06f
            }

            val color = when {
                area.id == -1 -> Triple(0.2f, 0.9f, 1f)
                area.zoneType == "AUDIENCE_SEATED" -> Triple(0.95f, 0.6f, 0.12f)
                area.zoneType == "AUDIENCE_STANDING" -> Triple(0.98f, 0.9f, 0.2f)
                area.zoneType == "STAGE" -> Triple(0.85f, 0.2f, 0.95f)
                area.zoneType == "OBSTACLE" -> Triple(0.95f, 0.25f, 0.25f)
                area.zoneType == "WALL" -> Triple(0.45f, 0.75f, 0.95f)
                else -> Triple(0.9f, 0.35f, 0.15f)
            }
            for ((a, b) in pairs) {
                positions[cursor * 3 + 0] = a.first; positions[cursor * 3 + 1] = yForPoint(a.first, a.second); positions[cursor * 3 + 2] = a.second
                colors[cursor * 4 + 0] = color.first; colors[cursor * 4 + 1] = color.second; colors[cursor * 4 + 2] = color.third; colors[cursor * 4 + 3] = 1f
                cursor++

                positions[cursor * 3 + 0] = b.first; positions[cursor * 3 + 1] = yForPoint(b.first, b.second); positions[cursor * 3 + 2] = b.second
                colors[cursor * 4 + 0] = color.first; colors[cursor * 4 + 1] = color.second; colors[cursor * 4 + 2] = color.third; colors[cursor * 4 + 3] = 1f
                cursor++
            }
        }

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until totalVerts) {
            val x = positions[i * 3 + 0]
            val y = positions[i * 3 + 1]
            val z = positions[i * 3 + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }

        val posLen = totalVerts * 3 * 4
        val colLen = totalVerts * 4 * 4
        val totalBin = posLen + colLen
        val colOffset = posLen

        fun f(v: Float) = String.format(Locale.US, "%.4f", v)
        val json = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"name":"audience_areas","primitives":[{"attributes":{"POSITION":0,"COLOR_0":1},"material":0,"mode":1}]}],"materials":[{"name":"area_mat","extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1]}}],"accessors":[{"bufferView":0,"componentType":5126,"count":$totalVerts,"type":"VEC3","min":[${f(minX)},${f(minY)},${f(minZ)}],"max":[${f(maxX)},${f(maxY)},${f(maxZ)}]},{"bufferView":1,"componentType":5126,"count":$totalVerts,"type":"VEC4"}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$posLen},{"buffer":0,"byteOffset":$colOffset,"byteLength":$colLen}],"buffers":[{"byteLength":$totalBin}]}"""

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
