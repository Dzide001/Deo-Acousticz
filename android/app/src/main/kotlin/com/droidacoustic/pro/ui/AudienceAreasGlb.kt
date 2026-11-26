package com.droidacoustic.pro.ui

import com.droidacoustic.pro.geometry.AcousticBoundaryRole
import com.droidacoustic.pro.geometry.MeshFaceAssignment
import com.droidacoustic.pro.geometry.PlanPoint
import com.droidacoustic.pro.geometry.AudienceLineSegment
import com.droidacoustic.pro.scene.AudienceArea
import com.droidacoustic.pro.scene.sharedSurface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Renders audience area outlines (including current draft polygon). */
object AudienceAreasGlb {

    fun build(
        areas: List<AudienceArea>,
        draft: List<Pair<Float, Float>>,
        draftZoneType: String = "AUDIENCE_SEATED",
        draftBaseHeightM: Float = 0f,
        draftRakeDeg: Float = 0f,
        draftRakeDirectionDeg: Float = 0f,
        draftSurface: AudienceArea? = null,
        selectedAreaId: Int? = null, selectedFaces: Set<Int> = emptySet()
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

        val positionList=mutableListOf<Float>()
        val colorList=mutableListOf<Float>()
        all.forEach { area ->
            val v = area.vertices
            if (v.size < 2) return@forEach

            val pairs = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
            if (area.importedMesh==null) {
                for (i in 0 until v.lastIndex) pairs += v[i] to v[i + 1]
                if (area.id != -1 && v.size >= 3) pairs += v.last() to v.first()
            }

            (area.holes+area.exclusions).filter { it.size>=3 }.forEach { ring ->
                for (i in ring.indices) pairs+=ring[i] to ring[(i+1)%ring.size]
            }
            val plane = (if (area.id == -1) draftSurface ?: area else area).sharedSurface()

            val color = when {
                area.id == -1 -> Triple(0.2f, 0.9f, 1f)
                area.zoneType == "AUDIENCE_SEATED" -> Triple(0.95f, 0.6f, 0.12f)
                area.zoneType == "AUDIENCE_STANDING" -> Triple(0.98f, 0.9f, 0.2f)
                area.zoneType == "STAGE" -> Triple(0.85f, 0.2f, 0.95f)
                area.zoneType == "OBSTACLE" -> Triple(0.95f, 0.25f, 0.25f)
                area.zoneType == "WALL" -> Triple(0.45f, 0.75f, 0.95f)
                else -> Triple(0.9f, 0.35f, 0.15f)
            }
            fun addSegments(segments: List<AudienceLineSegment>,rgb: Triple<Float,Float,Float>,offset: Float=0.07f) {
                for (segment in segments) {
                    positionList.addAll(listOf(segment.x0,segment.y0+offset,segment.z0,segment.x1,segment.y1+offset,segment.z1))
                    repeat(2) {colorList.addAll(listOf(rgb.first,rgb.second,rgb.third,1f))}
                }
            }
            if (area.id!=-1) {
                addSegments(plane.breaklineSegments(),Triple(0.2f,0.9f,1f))
                area.importedMesh?.let {mesh ->
                    val assignments=mesh.faceAssignments()
                    for (role in MeshFaceAssignment.entries) {
                        val rgb=when(role) {
                            MeshFaceAssignment.FOLLOW_ZONE -> Triple(0.2f,0.9f,1f)
                            MeshFaceAssignment.SEATED -> Triple(0.3f,0.8f,0.35f)
                            MeshFaceAssignment.STANDING -> Triple(0.8f,0.45f,1f)
                            MeshFaceAssignment.EXCLUDED -> Triple(0.45f,0.45f,0.45f)
                        }
                        addSegments(plane.importedEdgeSegments(assignments.indices.filter {assignments[it]==role},
                            clipCutouts=role!=MeshFaceAssignment.EXCLUDED),rgb)
                    }
                    for (role in listOf(AcousticBoundaryRole.OCCLUDING,AcousticBoundaryRole.RIGID_REFLECTOR)) {
                        val rgb=if (role==AcousticBoundaryRole.OCCLUDING) Triple(1f,0.25f,0.2f) else Triple(0.25f,0.55f,1f)
                        addSegments(plane.importedEdgeSegments(assignments.indices.filter {mesh.boundaryRole(it)==role},clipCutouts=false),rgb,0.08f)
                    }
                    if (area.id==selectedAreaId) addSegments(plane.importedEdgeSegments(selectedFaces.filter {it in assignments.indices},
                        clipCutouts=false),Triple(1f,0.95f,0.1f),0.09f)
                }
            }
            for ((a, b) in pairs) {
                val segments = if (plane.isNonPlanar) runCatching {
                    plane.traceSegment(PlanPoint(a.first.toDouble(),a.second.toDouble()),
                        PlanPoint(b.first.toDouble(),b.second.toDouble()))
                }.getOrDefault(emptyList()) else listOf(AudienceLineSegment(a.first,plane.surfaceY(a.first,a.second),a.second,
                    b.first,plane.surfaceY(b.first,b.second),b.second))
                for (segment in segments) {
                    positionList.addAll(listOf(segment.x0,segment.y0+0.06f,segment.z0,
                        segment.x1,segment.y1+0.06f,segment.z1))
                    repeat(2) { colorList.addAll(listOf(color.first,color.second,color.third,1f)) }
                }
            }
        }
        val totalVerts=positionList.size/3
        if (totalVerts == 0) return null
        val positions=positionList.toFloatArray()
        val colors=colorList.toFloatArray()

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
