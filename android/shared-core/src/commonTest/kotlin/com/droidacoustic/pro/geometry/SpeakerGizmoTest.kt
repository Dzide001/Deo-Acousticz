package com.droidacoustic.pro.geometry

import kotlin.math.hypot
import kotlin.test.*

class SpeakerGizmoTest {
    private val original = SpeakerGizmoTransform(1f, 2f, 3f, 175f, 5f)
    private fun handle(kind: SpeakerGizmoHandleKind, direction: GizmoPoint2 = GizmoPoint2(.6f, -.8f)) =
        SpeakerGizmoHandle(kind, GizmoPoint2(100f, 100f), direction, 20f)
    private fun drag(kind: SpeakerGizmoHandleKind) =
        SpeakerGizmoDragSession.begin(original, handle(kind), GizmoPoint2(100f, 100f), 1f)!!

    @Test fun sceneObjectsExposeMoveAndYawWithoutTilt() {
        val allowed=SpeakerGizmoHandleKind.entries.filter {it!=SpeakerGizmoHandleKind.TILT}.toSet()
        for(preset in SpeakerGizmoPreset.entries) {
            val layout=SpeakerGizmo.layout(original,preset,600,600,1f,allowed) {GizmoPoint2(300f,300f)}!!
            assertFalse(layout.handles.any {it.kind==SpeakerGizmoHandleKind.TILT})
            assertTrue(layout.handles.any {it.kind==SpeakerGizmoHandleKind.PAN})
            layout.handles.forEach { assertEquals(it,layout.hitTest(it.center)) }
        }
    }

    @Test fun projectionMatchesSharedPickingRaysAwayFromScreenCentre() {
        val eye=PickPoint3D(8.0,12.0,15.0);val target=PickPoint3D(0.0,1.0,0.0);val up=PickPoint3D(0.0,1.0,0.0)
        val camera=GizmoCamera(GizmoPoint3(8f,12f,15f),GizmoPoint3(0f,1f,0f),GizmoPoint3(0f,1f,0f),1200,800)
        for((x,y) in listOf(100.0 to 100.0,1100.0 to 700.0,300.0 to 550.0)) {
            val ray=ViewportRay.perspective(x,y,1200.0,800.0,eye,target,up,45.0)!!
            val p=GizmoPoint3((eye.x+ray.direction.x*15).toFloat(),(eye.y+ray.direction.y*15).toFloat(),(eye.z+ray.direction.z*15).toFloat())
            val projected=camera.project(p)!!
            assertEquals(x.toFloat(),projected.x,.001f);assertEquals(y.toFloat(),projected.y,.001f)
        }
    }

    @Test fun projectionUsesCameraBasisAndRejectsInvalidOrBehindCameraPoints() {
        val camera = GizmoCamera(GizmoPoint3(0f, 0f, 10f), GizmoPoint3(0f, 0f, 0f),
            GizmoPoint3(0f, 1f, 0f), 800, 600, 90f)
        assertEquals(GizmoPoint2(400f, 300f), camera.project(GizmoPoint3(0f, 0f, 0f)))
        assertEquals(430f, camera.project(GizmoPoint3(1f, 0f, 0f))!!.x, .001f)
        assertEquals(270f, camera.project(GizmoPoint3(0f, 1f, 0f))!!.y, .001f)
        assertNull(camera.project(GizmoPoint3(0f, 0f, 11f)))
        assertNull(camera.project(GizmoPoint3(Float.NaN, 0f, 0f)))
        assertNull(camera.copy(target = camera.eye).project(GizmoPoint3(0f, 0f, 0f)))
        assertNull(camera.copy(up = GizmoPoint3(0f, 0f, 1f)).project(GizmoPoint3(0f, 0f, 0f)))
        assertNull(camera.copy(verticalFovDegrees = Float.NaN).project(GizmoPoint3(0f, 0f, 0f)))
    }

    @Test fun presetsExposeExpectedAxesAndRotationControls() {
        val expected = mapOf(
            SpeakerGizmoPreset.PLAN to setOf(SpeakerGizmoHandleKind.X, SpeakerGizmoHandleKind.Z, SpeakerGizmoHandleKind.PAN),
            SpeakerGizmoPreset.SECTION to setOf(SpeakerGizmoHandleKind.Y, SpeakerGizmoHandleKind.Z, SpeakerGizmoHandleKind.PAN, SpeakerGizmoHandleKind.TILT),
            SpeakerGizmoPreset.PERSPECTIVE to SpeakerGizmoHandleKind.entries.toSet(),
        )
        expected.forEach { (preset, kinds) ->
            val layout = SpeakerGizmo.layout(original, preset, 600, 600, 1f) { GizmoPoint2(300f, 300f) }!!
            assertEquals(kinds, layout.handles.map { it.kind }.toSet())
            layout.handles.forEach { assertEquals(it, layout.hitTest(it.center)) }
        }
    }

    @Test fun coincidentAndDegenerateAxesRemainDistinctAndReachableAtViewportCorners() {
        for (origin in listOf(GizmoPoint2(0f, 0f), GizmoPoint2(400f, 0f), GizmoPoint2(0f, 400f), GizmoPoint2(400f, 400f))) {
            val layout = SpeakerGizmo.layout(original, SpeakerGizmoPreset.PERSPECTIVE, 400, 400, 1f) { origin }!!
            assertEquals(5, layout.handles.size)
            for (h in layout.handles) {
                assertTrue(h.center.x in 26f..374f && h.center.y in 26f..374f)
                assertEquals(h, layout.hitTest(h.center))
                assertTrue(h.pixelsPerMetre > 0f)
                layout.handles.filter { it != h }.forEach {
                    assertTrue(hypot(it.center.x - h.center.x, it.center.y - h.center.y) > layout.hitRadius * 2f)
                }
            }
        }
        val overlapping = SpeakerGizmo.layout(original, SpeakerGizmoPreset.PERSPECTIVE, 600, 600, 1f) {
            GizmoPoint2(300f + (it.x + it.y + it.z - 6f) * 20f, 300f)
        }!!
        assertEquals(5, overlapping.handles.size)
        overlapping.handles.filter { it.kind in listOf(SpeakerGizmoHandleKind.X, SpeakerGizmoHandleKind.Y, SpeakerGizmoHandleKind.Z) }.forEach {
            assertEquals(GizmoPoint2(1f, 0f), it.direction)
        }
        assertNull(overlapping.hitTest(GizmoPoint2(Float.NaN, 1f)))
    }

    @Test fun axisDragsUseFrozenProjectionAndClampWorldCoordinates() {
        val session = drag(SpeakerGizmoHandleKind.X)
        val moved = session.update(GizmoPoint2(112f, 84f))
        assertEquals(original.copy(x = 2f), moved.preview)
        assertEquals(1f, moved.amount, .0001f)
        assertEquals(original, session.preview)
        assertEquals(original.copy(x = 200f), session.update(GizmoPoint2(100000f, -100000f)).preview)
        assertEquals(-200f, drag(SpeakerGizmoHandleKind.Z).update(GizmoPoint2(-100000f, 100000f)).preview.z)
        assertEquals(40f, drag(SpeakerGizmoHandleKind.Y).update(GizmoPoint2(100000f, -100000f)).preview.y)
        assertEquals(0f, drag(SpeakerGizmoHandleKind.Y).update(GizmoPoint2(-100000f, 100000f)).preview.y)
        assertEquals(session, session.update(GizmoPoint2(Float.POSITIVE_INFINITY, 0f)))
        assertEquals(session, session.update(GizmoPoint2(Float.NaN, 0f)))
    }

    @Test fun rotationWrapsPanClampsTiltAndCancelCannotCommitOrResume() {
        val pan = drag(SpeakerGizmoHandleKind.PAN).update(GizmoPoint2(140f, 100f))
        assertEquals(-165f, pan.preview.panDeg)
        assertEquals(20f, pan.amount)
        assertEquals(pan.preview, pan.commit())
        assertEquals(30f, drag(SpeakerGizmoHandleKind.TILT).update(GizmoPoint2(200f, 100f)).preview.tiltDeg)
        assertEquals(-30f, drag(SpeakerGizmoHandleKind.TILT).update(GizmoPoint2(-200f, 100f)).preview.tiltDeg)
        val cancelled = pan.cancel()
        assertEquals(original, cancelled.preview)
        assertNull(cancelled.commit())
        assertEquals(cancelled, cancelled.update(GizmoPoint2(160f, 100f)))
        assertNull(drag(SpeakerGizmoHandleKind.X).commit())
        assertNull(SpeakerGizmoDragSession.begin(original.copy(x = Float.NaN), handle(SpeakerGizmoHandleKind.X), GizmoPoint2(0f, 0f), 1f))
    }
}
