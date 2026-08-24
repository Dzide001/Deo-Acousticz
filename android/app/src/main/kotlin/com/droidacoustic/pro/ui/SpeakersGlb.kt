package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.PlacedSpeaker
import com.droidacoustic.pro.scene.SpeakerModelPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds a GLB (glTF Binary 2.0) containing to-scale solid speaker cabinets
 * for every placed speaker.
 *
 * Each speaker produces a closed box mesh (12 triangles / 36 vertices)
 * with package-specific width/depth/height and package color.
 *
 * Returns null when [speakers] is empty (caller removes the old asset).
 */
object SpeakersGlb {

    // 36 cabinet + 48 shaft (8 segments × 6 verts) + 24 head cone (8 × 3) + 24 head cap (8 × 3)
    private const val VERTS_PER_SPEAKER = 132

    fun build(speakers: List<PlacedSpeaker>, modelPackages: List<SpeakerModelPackage>): ByteArray? {
        if (speakers.isEmpty()) return null

        val packageById = modelPackages.associateBy { it.id }
        val totalVerts = speakers.size * VERTS_PER_SPEAKER
        val positions  = FloatArray(totalVerts * 3)
        val colours    = FloatArray(totalVerts * 4)

        // ── Fill vertex data ──────────────────────────────────────────────────
        speakers.forEachIndexed { si, spk ->
            val base = si * VERTS_PER_SPEAKER
            val pkg = packageById[spk.modelPackageId]
            val cabW = (pkg?.cabinetWidthM ?: 0.42f).coerceAtLeast(0.08f)
            val cabD = (pkg?.cabinetDepthM ?: 0.40f).coerceAtLeast(0.08f)
            val cabH = (pkg?.cabinetHeightM ?: 0.65f).coerceAtLeast(0.08f)
            val cR = pkg?.colorR ?: 0.0f
            val cG = pkg?.colorG ?: 0.9f
            val cB = pkg?.colorB ?: 0.9f

            val yaw = Math.toRadians(spk.panDeg.toDouble())
            val pitch = Math.toRadians(spk.arrayAimDeg.toDouble())
            val cyaw = cos(yaw).toFloat()
            val syaw = sin(yaw).toFloat()
            val cp = cos(pitch).toFloat()
            val sp = sin(pitch).toFloat()
            val cy = spk.heightM
            val hw = cabW * 0.5f
            val hd = cabD * 0.5f
            val hh = cabH * 0.5f

            fun transform(localX: Float, localY: Float, localZ: Float): FloatArray {
                // pitch about local Z, then yaw about Y
                val px = localX * cp - localY * sp
                val py = localX * sp + localY * cp
                val pz = localZ

                val wx = px * cyaw - pz * syaw + spk.x
                val wy = py + cy
                val wz = px * syaw + pz * cyaw + spk.z
                return floatArrayOf(wx, wy, wz)
            }

            val triangles = arrayOf(
                intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
                intArrayOf(0, 2, 1), intArrayOf(0, 3, 2),
                intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
                intArrayOf(3, 7, 6), intArrayOf(3, 6, 2),
                intArrayOf(0, 4, 7), intArrayOf(0, 7, 3),
                intArrayOf(1, 2, 6), intArrayOf(1, 6, 5)
            )

            var nextVertex = 0
            fun emitBox(centerX: Float, centerY: Float, centerZ: Float, boxW: Float, boxH: Float, boxD: Float, red: Float, green: Float, blue: Float) {
                val boxHw = boxW * 0.5f
                val boxHh = boxH * 0.5f
                val boxHd = boxD * 0.5f
                val corners = Array(8) { FloatArray(3) }
                val local = arrayOf(
                    floatArrayOf(-boxHw, -boxHh, -boxHd),
                    floatArrayOf(boxHw,  -boxHh, -boxHd),
                    floatArrayOf(boxHw,  -boxHh, boxHd),
                    floatArrayOf(-boxHw, -boxHh, boxHd),
                    floatArrayOf(-boxHw, boxHh, -boxHd),
                    floatArrayOf(boxHw,  boxHh, -boxHd),
                    floatArrayOf(boxHw,  boxHh, boxHd),
                    floatArrayOf(-boxHw, boxHh, boxHd)
                )
                for (ci in 0 until 8) {
                    val p = transform(centerX + local[ci][0], centerY + local[ci][1], centerZ + local[ci][2])
                    corners[ci][0] = p[0]
                    corners[ci][1] = p[1]
                    corners[ci][2] = p[2]
                }

                fun putVert(vIdx: Int, x: Float, y: Float, z: Float) {
                    positions[(base + nextVertex + vIdx) * 3 + 0] = x
                    positions[(base + nextVertex + vIdx) * 3 + 1] = y
                    positions[(base + nextVertex + vIdx) * 3 + 2] = z
                }

                var vi = 0
                triangles.forEach { tri ->
                    putVert(vi++, corners[tri[0]][0], corners[tri[0]][1], corners[tri[0]][2])
                    putVert(vi++, corners[tri[1]][0], corners[tri[1]][1], corners[tri[1]][2])
                    putVert(vi++, corners[tri[2]][0], corners[tri[2]][1], corners[tri[2]][2])
                }

                for (v in 0 until 36) {
                    colours[(base + nextVertex + v) * 4 + 0] = red
                    colours[(base + nextVertex + v) * 4 + 1] = green
                    colours[(base + nextVertex + v) * 4 + 2] = blue
                    colours[(base + nextVertex + v) * 4 + 3] = 1.0f
                }
                nextVertex += 36
            }

            fun emitArrow(baseX: Float, baseY: Float, baseZ: Float, shaftLen: Float, shaftRad: Float, headLen: Float, headRad: Float, red: Float, green: Float, blue: Float) {
                // Shaft: cylinder approximated as octagonal prism pointing in -Z
                val shaftSegments = 8
                val shaftBase = baseZ
                val shaftTip = baseZ - shaftLen
                val headBase = shaftTip
                val headTip = shaftTip - headLen

                // Shaft vertices: bottom ring, top ring (36 vertices total = 12 triangles)
                for (seg in 0 until shaftSegments) {
                    val angle1 = (seg * 360f / shaftSegments) * Math.PI / 180.0
                    val angle2 = ((seg + 1) * 360f / shaftSegments) * Math.PI / 180.0
                    val x1 = baseX + (shaftRad * cos(angle1)).toFloat()
                    val z1 = shaftBase + (shaftRad * sin(angle1)).toFloat()
                    val x2 = baseX + (shaftRad * cos(angle2)).toFloat()
                    val z2 = shaftBase + (shaftRad * sin(angle2)).toFloat()
                    val x1t = baseX + (shaftRad * cos(angle1)).toFloat()
                    val z1t = shaftTip + (shaftRad * sin(angle1)).toFloat()
                    val x2t = baseX + (shaftRad * cos(angle2)).toFloat()
                    val z2t = shaftTip + (shaftRad * sin(angle2)).toFloat()

                    val baseVi = nextVertex
                    // Bottom quad + top quad (2 triangles per segment = 6 vertices)
                    positions[(base + baseVi + 0) * 3 + 0] = x1
                    positions[(base + baseVi + 0) * 3 + 1] = baseY
                    positions[(base + baseVi + 0) * 3 + 2] = z1
                    positions[(base + baseVi + 1) * 3 + 0] = x2
                    positions[(base + baseVi + 1) * 3 + 1] = baseY
                    positions[(base + baseVi + 1) * 3 + 2] = z2
                    positions[(base + baseVi + 2) * 3 + 0] = x1t
                    positions[(base + baseVi + 2) * 3 + 1] = baseY
                    positions[(base + baseVi + 2) * 3 + 2] = z1t
                    positions[(base + baseVi + 3) * 3 + 0] = x2
                    positions[(base + baseVi + 3) * 3 + 1] = baseY
                    positions[(base + baseVi + 3) * 3 + 2] = z2
                    positions[(base + baseVi + 4) * 3 + 0] = x2t
                    positions[(base + baseVi + 4) * 3 + 1] = baseY
                    positions[(base + baseVi + 4) * 3 + 2] = z2t
                    positions[(base + baseVi + 5) * 3 + 0] = x1t
                    positions[(base + baseVi + 5) * 3 + 1] = baseY
                    positions[(base + baseVi + 5) * 3 + 2] = z1t

                    for (v in 0 until 6) {
                        colours[(base + baseVi + v) * 4 + 0] = red
                        colours[(base + baseVi + v) * 4 + 1] = green
                        colours[(base + baseVi + v) * 4 + 2] = blue
                        colours[(base + baseVi + v) * 4 + 3] = 1.0f
                    }
                    nextVertex += 6
                }

                // Arrow head: cone from headBase ring to headTip point (42 vertices = 14 triangles)
                for (seg in 0 until shaftSegments) {
                    val angle1 = (seg * 360f / shaftSegments) * Math.PI / 180.0
                    val angle2 = ((seg + 1) * 360f / shaftSegments) * Math.PI / 180.0
                    val x1 = baseX + (headRad * cos(angle1)).toFloat()
                    val z1 = headBase + (headRad * sin(angle1)).toFloat()
                    val x2 = baseX + (headRad * cos(angle2)).toFloat()
                    val z2 = headBase + (headRad * sin(angle2)).toFloat()

                    val baseVi = nextVertex
                    // 1 triangle per segment = 3 vertices per segment: base-edge to tip
                    positions[(base + baseVi + 0) * 3 + 0] = x1
                    positions[(base + baseVi + 0) * 3 + 1] = baseY
                    positions[(base + baseVi + 0) * 3 + 2] = z1
                    positions[(base + baseVi + 1) * 3 + 0] = x2
                    positions[(base + baseVi + 1) * 3 + 1] = baseY
                    positions[(base + baseVi + 1) * 3 + 2] = z2
                    positions[(base + baseVi + 2) * 3 + 0] = baseX
                    positions[(base + baseVi + 2) * 3 + 1] = baseY
                    positions[(base + baseVi + 2) * 3 + 2] = headTip

                    for (v in 0 until 3) {
                        colours[(base + baseVi + v) * 4 + 0] = red
                        colours[(base + baseVi + v) * 4 + 1] = green
                        colours[(base + baseVi + v) * 4 + 2] = blue
                        colours[(base + baseVi + v) * 4 + 3] = 1.0f
                    }
                    nextVertex += 3
                }

                // Head base circle (cap): shaftSegments triangles = 3*shaftSegments vertices
                for (seg in 0 until shaftSegments) {
                    val angle1 = (seg * 360f / shaftSegments) * Math.PI / 180.0
                    val angle2 = ((seg + 1) * 360f / shaftSegments) * Math.PI / 180.0
                    val x1 = baseX + (headRad * cos(angle1)).toFloat()
                    val z1 = headBase + (headRad * sin(angle1)).toFloat()
                    val x2 = baseX + (headRad * cos(angle2)).toFloat()
                    val z2 = headBase + (headRad * sin(angle2)).toFloat()

                    val baseVi = nextVertex
                    positions[(base + baseVi + 0) * 3 + 0] = baseX
                    positions[(base + baseVi + 0) * 3 + 1] = baseY
                    positions[(base + baseVi + 0) * 3 + 2] = headBase
                    positions[(base + baseVi + 1) * 3 + 0] = x2
                    positions[(base + baseVi + 1) * 3 + 1] = baseY
                    positions[(base + baseVi + 1) * 3 + 2] = z2
                    positions[(base + baseVi + 2) * 3 + 0] = x1
                    positions[(base + baseVi + 2) * 3 + 1] = baseY
                    positions[(base + baseVi + 2) * 3 + 2] = z1

                    for (v in 0 until 3) {
                        colours[(base + baseVi + v) * 4 + 0] = red
                        colours[(base + baseVi + v) * 4 + 1] = green
                        colours[(base + baseVi + v) * 4 + 2] = blue
                        colours[(base + baseVi + v) * 4 + 3] = 1.0f
                    }
                    nextVertex += 3
                }
            }

            emitBox(0f, 0f, 0f, cabW, cabH, cabD, cR, cG, cB)

            // ── Arrow pointing forward (in -Z direction) ──────────────────────
            val arrowShaftLen = hd * 1.2f
            val arrowShaftRad = (cabW * 0.06f).coerceAtLeast(0.015f)
            val arrowHeadLen = arrowShaftLen * 0.4f
            val arrowHeadRad = arrowShaftRad * 2.5f
            emitArrow(0f, 0f, 0f, arrowShaftLen, arrowShaftRad, arrowHeadLen, arrowHeadRad, 1.0f, 0.85f, 0.10f)
        }

        // ── Compute bounding box for accessor min/max ──────────────────────
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until totalVerts) {
            val x = positions[i*3+0]; val y = positions[i*3+1]; val z = positions[i*3+2]
            if (x < minX) minX=x; if (x > maxX) maxX=x
            if (y < minY) minY=y; if (y > maxY) maxY=y
            if (z < minZ) minZ=z; if (z > maxZ) maxZ=z
        }

        // ── Binary layout ──────────────────────────────────────────────────
        val posByteLen = totalVerts * 3 * 4
        val colByteLen = totalVerts * 4 * 4
        val totalBin   = posByteLen + colByteLen
        val colOffset  = posByteLen

        // ── glTF JSON ─────────────────────────────────────────────────────
        val json = buildJsonString(
            totalVerts, minX, minY, minZ, maxX, maxY, maxZ,
            posByteLen, colOffset, colByteLen, totalBin
        )

        // ── Assemble GLB ──────────────────────────────────────────────────
        val jsonBytes    = json.toByteArray(Charsets.UTF_8)
        val jsonPadded   = align4(jsonBytes.size)
        val binPadded    = align4(totalBin)
        val totalGlbSize = 12 + 8 + jsonPadded + 8 + binPadded

        val buf = ByteBuffer.allocate(totalGlbSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x46546C67); buf.putInt(2); buf.putInt(totalGlbSize)
        buf.putInt(jsonPadded); buf.putInt(0x4E4F534A)
        buf.put(jsonBytes)
        repeat(jsonPadded - jsonBytes.size) { buf.put(0x20) }
        buf.putInt(binPadded); buf.putInt(0x004E4942)
        for (v in positions) buf.putFloat(v)
        for (v in colours)   buf.putFloat(v)
        repeat(binPadded - totalBin) { buf.put(0) }

        return buf.array()
    }

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    private fun buildJsonString(
        totalVerts: Int,
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float,
        posByteLen: Int, colOffset: Int, colByteLen: Int, totalBin: Int
    ): String = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"name":"speakers","primitives":[{"attributes":{"POSITION":0,"COLOR_0":1},"material":0,"mode":4}]}],"materials":[{"name":"spk_mat","doubleSided":true,"extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorFactor":[0.0,0.9,0.9,1.0]}}],"accessors":[{"bufferView":0,"componentType":5126,"count":$totalVerts,"type":"VEC3","min":[${f(minX)},${f(minY)},${f(minZ)}],"max":[${f(maxX)},${f(maxY)},${f(maxZ)}]},{"bufferView":1,"componentType":5126,"count":$totalVerts,"type":"VEC4"}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$posByteLen},{"buffer":0,"byteOffset":$colOffset,"byteLength":$colByteLen}],"buffers":[{"byteLength":$totalBin}]}"""

    private fun align4(n: Int) = (n + 3) and -4
}
