package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.prediction.AngularReceiver
import kotlin.math.*
import kotlin.test.*

class ReceiverCameraFramingTest {
    private val receiver=AngularReceiver("reference:7","Balcony",BoundaryPoint(120.0,9.0,-75.0))
    @Test fun allViewsFrameTheReceiverAndPreserveRequestedContextAcrossAspectRatios() {
        for(view in ReceiverFrameView.entries) for((w,h) in listOf(1200 to 700,400 to 800)) for(density in listOf(1f,2f)) {
            val focus=ReceiverCameraFocus(receiver.id,view)
            val frame=ReceiverCameraFraming.fit(focus,receiver,w,h,density)!!
            val center=frame.camera.project(GizmoPoint3(120f,9f,-75f))!!
            assertTrue(frame.usable.contains(center));assertEquals(8f,frame.spanM)
            val layout=ReceiverMarkers.layout(listOf(receiver),frame.camera,density,receiver.id) {GizmoPoint2(120f,16f)}
            assertEquals(receiver.id,layout.pick(layout.markers.single().center,null))
            assertNotNull(layout.markers.single().label)
            val c=frame.camera;assertTrue(c.eye.isFinite() && c.target.isFinite())
            if(view==ReceiverFrameView.PLAN) assertTrue(c.eye.y>c.target.y+5)
            if(view==ReceiverFrameView.SECTION) assertTrue(c.eye.x>c.target.x+5)
        }
    }
    @Test fun obstructingPanelsAreAvoidedAndCompleteObstructionNeverProducesABadFrame() {
        for(view in ReceiverFrameView.entries) {
            val focus=ReceiverCameraFocus(receiver.id,view)
            val panel=ReceiverLabelRect(700f,180f,300f,280f)
            val frame=ReceiverCameraFraming.fit(focus,receiver,1000,700,1f,obstructions=listOf(panel))!!
            assertFalse(frame.usable.overlaps(panel))
            val labels=ReceiverMarkers.layout(listOf(receiver),frame.camera,1f,receiver.id,frame.usable) {GizmoPoint2(240f,16f)}
            val label=labels.markers.single().label!!
            assertTrue(frame.usable.contains(GizmoPoint2(label.x,label.y)))
            assertTrue(frame.usable.contains(GizmoPoint2(label.x+label.width,label.y+label.height)))
            assertTrue(frame.usable.contains(frame.camera.project(GizmoPoint3(120f,9f,-75f))!!))
            val portrait=ReceiverCameraFraming.fit(focus,receiver,400,800,1f,obstructions=listOf(ReceiverLabelRect(0f,620f,400f,180f)))!!
            assertTrue(portrait.usable.y+portrait.usable.height<620)
            assertNull(ReceiverCameraFraming.fit(focus,receiver,400,800,1f,obstructions=listOf(ReceiverLabelRect(0f,0f,400f,800f))))
        }
    }
    @Test fun intentRoundTripsAndStaleTargetInvalidSchemaAndDegenerateViewportsAreRejected() {
        val focus=ReceiverCameraFocus(receiver.id,ReceiverFrameView.SECTION,16f)
        assertEquals(focus,ReceiverCameraFocus.parse(focus.toJsonString()))
        assertNull(focus.reconcile(listOf("listener")))
        assertNull(ReceiverCameraFraming.fit(focus,receiver.copy(id="other"),800,600))
        assertNull(ReceiverCameraFraming.fit(focus,receiver,0,600))
        assertNull(ReceiverCameraFraming.fit(focus,receiver,800,600,Float.NaN))
        assertNull(ReceiverCameraFraming.fit(focus,receiver,800,600,verticalFov=0f))
        assertFails {ReceiverCameraFocus(receiver.id,ReceiverFrameView.PLAN,Float.NaN)}
        assertFails {ReceiverCameraFocus.parse(focus.toJsonString().replace("\"version\":1","\"version\":99"))}
    }
    @Test fun widerContextPullsBackAndFramingIsDeterministicAtTranslatedReceiverPositions() {
        for(view in ReceiverFrameView.entries) {
            val near=ReceiverCameraFraming.fit(ReceiverCameraFocus(receiver.id,view,4f),receiver,1000,700)!!
            val far=ReceiverCameraFraming.fit(ReceiverCameraFocus(receiver.id,view,16f),receiver,1000,700)!!
            fun distance(c:GizmoCamera)=sqrt((c.eye.x-c.target.x).toDouble().pow(2)+(c.eye.y-c.target.y).toDouble().pow(2)+(c.eye.z-c.target.z).toDouble().pow(2))
            assertTrue(distance(far.camera)>distance(near.camera))
            assertEquals(far,ReceiverCameraFraming.fit(ReceiverCameraFocus(receiver.id,view,16f),receiver,1000,700))
            val relocated=receiver.copy(position=BoundaryPoint(-30.0,17.0,40.0))
            assertNotNull(ReceiverCameraFraming.fit(ReceiverCameraFocus(receiver.id,view),relocated,1000,700))
        }
    }
}
