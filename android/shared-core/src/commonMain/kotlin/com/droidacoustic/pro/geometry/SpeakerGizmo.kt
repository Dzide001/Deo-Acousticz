package com.droidacoustic.pro.geometry

import kotlin.math.*

data class GizmoPoint2(val x: Float, val y: Float) {
    fun isFinite() = x.isFinite() && y.isFinite()
}
data class GizmoPoint3(val x: Float, val y: Float, val z: Float) {
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
}

/** Camera-space projection, matching Filament's vertical perspective field of view. */
data class GizmoCamera(
    val eye: GizmoPoint3, val target: GizmoPoint3, val up: GizmoPoint3,
    val viewportWidth: Int, val viewportHeight: Int, val verticalFovDegrees: Float = 45f,
) {
    fun project(point: GizmoPoint3): GizmoPoint2? {
        if (!eye.isFinite() || !target.isFinite() || !up.isFinite() || !point.isFinite() ||
            viewportWidth < 2 || viewportHeight < 2 || !verticalFovDegrees.isFinite() ||
            verticalFovDegrees <= 0f || verticalFovDegrees >= 180f) return null
        // Double intermediates also reject very large finite values without Float overflow.
        val fx = target.x.toDouble() - eye.x; val fy = target.y.toDouble() - eye.y; val fz = target.z.toDouble() - eye.z
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        if (fl < 1e-6) return null
        val ax = fx / fl; val ay = fy / fl; val az = fz / fl
        var rx = ay * up.z - az * up.y; var ry = az * up.x - ax * up.z; var rz = ax * up.y - ay * up.x
        val rl = sqrt(rx * rx + ry * ry + rz * rz)
        if (rl < 1e-6) return null
        rx /= rl; ry /= rl; rz /= rl
        val ux = ry * az - rz * ay; val uy = rz * ax - rx * az; val uz = rx * ay - ry * ax
        val dx = point.x.toDouble() - eye.x; val dy = point.y.toDouble() - eye.y; val dz = point.z.toDouble() - eye.z
        val depth = dx * ax + dy * ay + dz * az
        if (depth <= .1) return null
        val scale = viewportHeight / (2 * tan(verticalFovDegrees * PI / 360)) / depth
        return GizmoPoint2((viewportWidth / 2.0 + (dx * rx + dy * ry + dz * rz) * scale).toFloat(),
            (viewportHeight / 2.0 - (dx * ux + dy * uy + dz * uz) * scale).toFloat()).takeIf { it.isFinite() }
    }
}

data class SpeakerGizmoTransform(val x: Float, val y: Float, val z: Float, val panDeg: Float, val tiltDeg: Float) {
    fun isFinite() = listOf(x, y, z, panDeg, tiltDeg).all { it.isFinite() }
}
enum class SpeakerGizmoPreset { PLAN, SECTION, PERSPECTIVE }
enum class SpeakerGizmoHandleKind { X, Y, Z, PAN, TILT }
data class SpeakerGizmoHandle(
    val kind: SpeakerGizmoHandleKind, val center: GizmoPoint2,
    val direction: GizmoPoint2, val pixelsPerMetre: Float,
)
data class SpeakerGizmoLayout(val origin: GizmoPoint2, val handles: List<SpeakerGizmoHandle>, val hitRadius: Float) {
    fun hitTest(point: GizmoPoint2): SpeakerGizmoHandle? = if (!point.isFinite()) null else
        handles.minByOrNull { distance(point, it.center) }?.takeIf { distance(point, it.center) <= hitRadius }
}

object SpeakerGizmo {
    /** Display offsets can fan out, but the drag direction always remains the projected world axis. */
    fun layout(
        transform: SpeakerGizmoTransform, preset: SpeakerGizmoPreset,
        width: Int, height: Int, density: Float,
        allowedKinds: Set<SpeakerGizmoHandleKind>? = null,
        project: (GizmoPoint3) -> GizmoPoint2?,
    ): SpeakerGizmoLayout? {
        if (!transform.isFinite() || !density.isFinite() || density <= 0f || width < 2 || height < 2) return null
        val radius = 26f * density
        if (width < radius * 2 || height < radius * 2) return null
        val point = GizmoPoint3(transform.x, transform.y, transform.z)
        val origin = project(point)?.takeIf { it.isFinite() } ?: return null
        if (origin.x < 0 || origin.x > width || origin.y < 0 || origin.y > height) return null
        val kinds = when (preset) {
            SpeakerGizmoPreset.PLAN -> listOf(SpeakerGizmoHandleKind.X, SpeakerGizmoHandleKind.Z, SpeakerGizmoHandleKind.PAN)
            SpeakerGizmoPreset.SECTION -> listOf(SpeakerGizmoHandleKind.Y, SpeakerGizmoHandleKind.Z, SpeakerGizmoHandleKind.PAN, SpeakerGizmoHandleKind.TILT)
            SpeakerGizmoPreset.PERSPECTIVE -> SpeakerGizmoHandleKind.entries.toList()
        }
        val handles = mutableListOf<SpeakerGizmoHandle>()
        for (kind in kinds.filter { allowedKinds == null || it in allowedKinds }) {
            val axis = kind == SpeakerGizmoHandleKind.X || kind == SpeakerGizmoHandleKind.Y || kind == SpeakerGizmoHandleKind.Z
            val fallback = when (kind) {
                SpeakerGizmoHandleKind.X -> GizmoPoint2(1f, 0f)
                SpeakerGizmoHandleKind.Y -> GizmoPoint2(0f, -1f)
                SpeakerGizmoHandleKind.Z -> GizmoPoint2(-.70710677f, .70710677f)
                else -> GizmoPoint2(1f, 0f)
            }
            val projected = if (axis) project(when (kind) {
                SpeakerGizmoHandleKind.X -> point.copy(x = point.x + 1f)
                SpeakerGizmoHandleKind.Y -> point.copy(y = point.y + 1f)
                else -> point.copy(z = point.z + 1f)
            })?.takeIf { it.isFinite() } else null
            val delta = projected?.let { GizmoPoint2(it.x - origin.x, it.y - origin.y) }
            val length = delta?.let { hypot(it.x, it.y) } ?: 0f
            val usable = length.isFinite() && length >= density * 2f
            val direction = if (usable) GizmoPoint2(delta!!.x / length, delta.y / length) else fallback
            val ppm = if (usable) length else 32f * density
            val preferred = when (kind) {
                SpeakerGizmoHandleKind.PAN -> GizmoPoint2(origin.x + 50f * density, origin.y - 65f * density)
                SpeakerGizmoHandleKind.TILT -> GizmoPoint2(origin.x - 50f * density, origin.y - 65f * density)
                else -> GizmoPoint2(origin.x + direction.x * 70f * density, origin.y + direction.y * 70f * density)
            }
            fun bounded(p: GizmoPoint2) = GizmoPoint2(p.x.coerceIn(radius, width - radius), p.y.coerceIn(radius, height - radius))
            val candidates = mutableListOf(bounded(preferred))
            // Nearby rings resolve foreshortened/overlapping axes and fan handles inward at viewport edges.
            for (ring in 1..4) for (step in 0 until 24) {
                val angle = step * PI / 12
                candidates += bounded(GizmoPoint2(preferred.x + (cos(angle) * ring * radius * 2.1).toFloat(),
                    preferred.y + (sin(angle) * ring * radius * 2.1).toFloat()))
            }
            val center = candidates.filter { candidate -> handles.all { distance(candidate, it.center) >= radius * 2f + density } }
                .minByOrNull { distance(it, preferred) } ?: continue
            handles += SpeakerGizmoHandle(kind, center, direction, ppm)
        }
        return SpeakerGizmoLayout(origin, handles.toList(), radius)
    }
}

/** The original and drag basis stay frozen for the whole gesture. Only commit exposes a changed value. */
data class SpeakerGizmoDragSession private constructor(
    val original: SpeakerGizmoTransform, val handle: SpeakerGizmoHandle,
    val start: GizmoPoint2, val density: Float,
    val preview: SpeakerGizmoTransform, val amount: Float = 0f, val cancelled: Boolean = false,
) {
    fun update(pointer: GizmoPoint2): SpeakerGizmoDragSession {
        if (cancelled || !pointer.isFinite()) return this
        val dx = pointer.x.toDouble() - start.x; val dy = pointer.y.toDouble() - start.y
        val axis = handle.kind == SpeakerGizmoHandleKind.X || handle.kind == SpeakerGizmoHandleKind.Y || handle.kind == SpeakerGizmoHandleKind.Z
        val delta = if (axis) (dx * handle.direction.x + dy * handle.direction.y) / handle.pixelsPerMetre else dx / (2 * density)
        if (!delta.isFinite()) return this
        val next = when (handle.kind) {
            SpeakerGizmoHandleKind.X -> original.copy(x = (original.x + delta).coerceIn(-200.0, 200.0).toFloat())
            SpeakerGizmoHandleKind.Y -> original.copy(y = (original.y + delta).coerceIn(0.0, 40.0).toFloat())
            SpeakerGizmoHandleKind.Z -> original.copy(z = (original.z + delta).coerceIn(-200.0, 200.0).toFloat())
            SpeakerGizmoHandleKind.PAN -> original.copy(panDeg = ((((original.panDeg + delta + 180) % 360 + 360) % 360) - 180).toFloat())
            SpeakerGizmoHandleKind.TILT -> original.copy(tiltDeg = (original.tiltDeg + delta).coerceIn(-30.0, 30.0).toFloat())
        }
        val actual = when (handle.kind) {
            SpeakerGizmoHandleKind.X -> next.x - original.x
            SpeakerGizmoHandleKind.Y -> next.y - original.y
            SpeakerGizmoHandleKind.Z -> next.z - original.z
            SpeakerGizmoHandleKind.PAN -> delta.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
            SpeakerGizmoHandleKind.TILT -> next.tiltDeg - original.tiltDeg
        }
        return copy(preview = next, amount = actual)
    }
    fun commit(): SpeakerGizmoTransform? = preview.takeIf { !cancelled && it != original }
    fun cancel(): SpeakerGizmoDragSession = copy(preview = original, amount = 0f, cancelled = true)

    companion object {
        fun begin(original: SpeakerGizmoTransform, handle: SpeakerGizmoHandle, pointer: GizmoPoint2, density: Float): SpeakerGizmoDragSession? {
            if (!original.isFinite() || !pointer.isFinite() || !handle.center.isFinite() || !handle.direction.isFinite() ||
                !handle.pixelsPerMetre.isFinite() || handle.pixelsPerMetre <= 0f || !density.isFinite() || density <= 0f ||
                abs(hypot(handle.direction.x, handle.direction.y) - 1f) > .001f) return null
            return SpeakerGizmoDragSession(original, handle, pointer, density, original)
        }
    }
}

private fun distance(a: GizmoPoint2, b: GizmoPoint2) = hypot(a.x - b.x, a.y - b.y)
