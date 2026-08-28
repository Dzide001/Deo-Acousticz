package com.droidacoustic.pro.ui

import com.droidacoustic.pro.scene.CoverageEdges
import com.droidacoustic.pro.scene.PlacedSpeaker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Aim rays - the ArrayCalc-style view of where a box is actually pointed.
 *
 * The cabinet mesh carries a small arrow, but at venue scale it is a few pixels
 * and tells you nothing about where the energy lands. These are lines thrown
 * from each box out to the floor, which is the question a designer is actually
 * asking: not "which way is it facing" but "what does it cover".
 *
 * Per radiating element: the acoustic axis, bright, and the two vertical
 * coverage edges at the -6 dB angle, dim. For an array every element gets its
 * own set, drawn from its own position and its own aim, so the splay opens as a
 * visible fan - which is the whole reason to look at an array in section.
 *
 * The horizontal edges are drawn once per box rather than per element, because
 * splay is vertical: every element in an array shares one pan, so a set per
 * element would stack the same two lines on top of each other. One pair from
 * the acoustic centre gives the plan-view wedge instead.
 *
 * glTF LINES (mode 1) with per-vertex colour and an unlit material, same
 * approach as [FloorGridGlb].
 */
object AimRaysGlb {

    /** Where a ray stops if it never meets the floor. */
    private const val MAX_RAY_M = 60f
    private const val MIN_RAY_M = 1.5f

    private data class Ray(
        val x0: Float, val y0: Float, val z0: Float,
        val x1: Float, val y1: Float, val z1: Float,
        val r: Float, val g: Float, val b: Float, val a: Float
    )

    /** Used when a caller supplies no edges for a speaker. */
    private val FALLBACK_EDGES = CoverageEdges(20f, 20f, 45f, 45f, measured = false)

    /**
     * @param edges per-speaker vertical -6 dB angles, keyed by speaker id.
     *   Supplied by the scene layer, which reads them off the measured balloon
     *   when there is one. Missing entries fall back to a symmetric guess.
     */
    fun build(
        speakers: List<PlacedSpeaker>,
        edges: Map<Int, CoverageEdges> = emptyMap(),
        venueWidthM: Float = 0f,
        venueDepthM: Float = 0f,
        venueHeightM: Float = 0f
    ): ByteArray? {
        if (speakers.isEmpty()) return null

        val rays = mutableListOf<Ray>()
        speakers.forEach { spk ->
            rays += raysFor(
                spk,
                edges[spk.id] ?: FALLBACK_EDGES,
                venueWidthM, venueDepthM, venueHeightM
            )
        }
        if (rays.isEmpty()) return null

        val vertCount = rays.size * 2
        val positions = FloatArray(vertCount * 3)
        val colours = FloatArray(vertCount * 4)
        rays.forEachIndexed { i, ray ->
            val p = i * 6
            positions[p] = ray.x0; positions[p + 1] = ray.y0; positions[p + 2] = ray.z0
            positions[p + 3] = ray.x1; positions[p + 4] = ray.y1; positions[p + 5] = ray.z1
            val c = i * 8
            for (v in 0..1) {
                colours[c + v * 4] = ray.r
                colours[c + v * 4 + 1] = ray.g
                colours[c + v * 4 + 2] = ray.b
                colours[c + v * 4 + 3] = ray.a
            }
        }
        return assemble(positions, colours, vertCount)
    }

    /**
     * One set of rays per radiating element.
     *
     * Element positions and aims mirror the summation model: elements stack
     * along Y about the cabinet height, and cumulative splay is re-centred on
     * the global aim so the array points where it says it does.
     */
    private fun raysFor(
        spk: PlacedSpeaker,
        edges: CoverageEdges,
        venueWidthM: Float,
        venueDepthM: Float,
        venueHeightM: Float
    ): List<Ray> {
        val n = spk.arrayElements.coerceAtLeast(1)
        val spacing = spk.arraySpacingM
        val globalAim = spk.arraySteerDeg + spk.arrayAimDeg
        val joints = (n - 1).coerceAtLeast(0)
        val splay = if (spk.arraySplayByBoxDeg.size == joints) {
            spk.arraySplayByBoxDeg
        } else {
            List(joints) { spk.arrayInterBoxSplayDeg }
        }

        val aims = DoubleArray(n) { globalAim.toDouble() }
        for (i in 1 until n) aims[i] = aims[i - 1] + splay[i - 1].toDouble()
        if (n > 1) {
            val offset = globalAim.toDouble() - aims.average()
            for (i in 0 until n) aims[i] += offset
        }

        val up = edges.upDeg.coerceIn(1f, 90f)
        val down = edges.downDeg.coerceIn(1f, 90f)
        // Measured edges are drawn brighter than guessed ones, so the overlay
        // says which it is without a legend.
        val edgeAlpha = if (edges.measured) 0.65f else 0.35f
        val edgeG = if (edges.measured) 0.75f else 0.55f
        val out = mutableListOf<Ray>()
        for (elem in 0 until n) {
            val relIdx = elem - (n - 1) * 0.5f
            val ey = spk.heightM + relIdx * spacing
            // arrayAimDeg is down-positive, so the axis elevation is its negation.
            val axisElevation = -aims[elem].toFloat()
            val room = Room(venueWidthM, venueDepthM, venueHeightM)
            out += ray(spk, ey, axisElevation, 0f, room, 0.10f, 0.95f, 1.00f, 0.95f)
            out += ray(spk, ey, axisElevation + up, 0f, room, 0.10f, edgeG, 0.75f, edgeAlpha)
            out += ray(spk, ey, axisElevation - down, 0f, room, 0.10f, edgeG, 0.75f, edgeAlpha)
        }

        // Horizontal edges: one pair for the whole box, from its acoustic
        // centre along the mean axis, swung out to either side.
        val room = Room(venueWidthM, venueDepthM, venueHeightM)
        val meanElevation = -globalAim
        val left = edges.leftDeg.coerceIn(1f, 90f)
        val right = edges.rightDeg.coerceIn(1f, 90f)
        out += ray(spk, spk.heightM, meanElevation, -left, room, 0.10f, edgeG, 0.75f, edgeAlpha)
        out += ray(spk, spk.heightM, meanElevation, right, room, 0.10f, edgeG, 0.75f, edgeAlpha)
        return out
    }

    /** Venue bounds a ray is clipped to. A zero extent means "not bounded". */
    private data class Room(val widthM: Float, val depthM: Float, val heightM: Float)

    /**
     * A single ray from the box, stopped where it leaves the room.
     *
     * A ray that carries on through a wall and out over the car park is worse
     * than useless - it reads as coverage that does not exist - so this walks
     * the slab intersections and takes the nearest wall, floor or ceiling.
     */
    private fun ray(
        spk: PlacedSpeaker,
        startY: Float,
        elevationDeg: Float,
        yawOffsetDeg: Float,
        room: Room,
        r: Float, g: Float, b: Float, a: Float
    ): Ray {
        val yaw = Math.toRadians((spk.panDeg + yawOffsetDeg).toDouble())
        val pitch = Math.toRadians(elevationDeg.toDouble())
        val horiz = cos(pitch).toFloat()
        val dx = (horiz * cos(yaw)).toFloat()
        val dz = (horiz * sin(yaw)).toFloat()
        val dy = sin(pitch).toFloat()

        // Nearest surface the ray meets: floor, ceiling or one of the four walls.
        var limit = MAX_RAY_M
        fun slab(origin: Float, dir: Float, lo: Float, hi: Float) {
            if (abs(dir) < 1e-4f) return
            val t = if (dir > 0f) (hi - origin) / dir else (lo - origin) / dir
            if (t > 0f && t < limit) limit = t
        }
        slab(startY, dy, 0f, if (room.heightM > 0f) room.heightM else MAX_RAY_M)
        if (room.widthM > 0f) slab(spk.x, dx, -room.widthM * 0.5f, room.widthM * 0.5f)
        if (room.depthM > 0f) slab(spk.z, dz, -room.depthM * 0.5f, room.depthM * 0.5f)
        val length = limit.coerceIn(MIN_RAY_M, MAX_RAY_M)
        return Ray(
            spk.x, startY, spk.z,
            spk.x + dx * length, (startY + dy * length).coerceAtLeast(0f), spk.z + dz * length,
            r, g, b, a
        )
    }

    private fun assemble(positions: FloatArray, colours: FloatArray, vertCount: Int): ByteArray {
        val posBytes = positions.size * 4
        val colBytes = colours.size * 4
        val colOffset = align4(posBytes)
        val binLen = align4(colOffset + colBytes)

        fun minOf3(o: Int) = (0 until vertCount).minOf { positions[it * 3 + o] }
        fun maxOf3(o: Int) = (0 until vertCount).maxOf { positions[it * 3 + o] }

        val json = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],""" +
            """"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],""" +
            """"meshes":[{"name":"aim_rays","primitives":[{"attributes":{"POSITION":0,"COLOR_0":1},"material":0,"mode":1}]}],""" +
            """"materials":[{"name":"aim_mat","extensions":{"KHR_materials_unlit":{}},""" +
            """"pbrMetallicRoughness":{"baseColorFactor":[1.0,1.0,1.0,1.0]},"alphaMode":"BLEND","doubleSided":true}],""" +
            """"accessors":[{"bufferView":0,"componentType":5126,"count":$vertCount,"type":"VEC3",""" +
            """"min":[${f(minOf3(0))},${f(minOf3(1))},${f(minOf3(2))}],""" +
            """"max":[${f(maxOf3(0))},${f(maxOf3(1))},${f(maxOf3(2))}]},""" +
            """{"bufferView":1,"componentType":5126,"count":$vertCount,"type":"VEC4",""" +
            """"min":[0.0,0.0,0.0,0.0],"max":[1.0,1.0,1.0,1.0]}],""" +
            """"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$posBytes},""" +
            """{"buffer":0,"byteOffset":$colOffset,"byteLength":$colBytes}],""" +
            """"buffers":[{"byteLength":$binLen}]}"""

        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPadded = align4(jsonBytes.size)
        val total = 12 + 8 + jsonPadded + 8 + binLen

        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x46546C67); buf.putInt(2); buf.putInt(total)
        buf.putInt(jsonPadded); buf.putInt(0x4E4F534A)
        buf.put(jsonBytes); repeat(jsonPadded - jsonBytes.size) { buf.put(0x20) }
        buf.putInt(binLen); buf.putInt(0x004E4942)
        val binStart = buf.position()
        positions.forEach { buf.putFloat(it) }
        buf.position(binStart + colOffset)
        colours.forEach { buf.putFloat(it) }
        buf.position(binStart + binLen)
        return buf.array()
    }

    private fun align4(v: Int) = (v + 3) and 3.inv()
    private fun f(v: Float) = String.format(java.util.Locale.US, "%.4f", v)
}
