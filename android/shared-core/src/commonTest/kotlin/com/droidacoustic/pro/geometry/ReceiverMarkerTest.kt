package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.prediction.AngularReceiver
import kotlin.test.*

class ReceiverMarkerTest {
    private fun receiver(id:String="listener",x:Double=0.0,y:Double=0.0,z:Double=0.0)=AngularReceiver(id,"Same name",BoundaryPoint(x,y,z))
    private val cameras=listOf(
        GizmoCamera(GizmoPoint3(0f,20f,0f),GizmoPoint3(0f,0f,0f),GizmoPoint3(0f,0f,-1f),800,600),
        GizmoCamera(GizmoPoint3(0f,0f,20f),GizmoPoint3(0f,0f,0f),GizmoPoint3(0f,1f,0f),800,600),
        GizmoCamera(GizmoPoint3(20f,0f,0f),GizmoPoint3(0f,0f,0f),GizmoPoint3(0f,1f,0f),800,600),
        GizmoCamera(GizmoPoint3(10f,8f,12f),GizmoPoint3(0f,0f,0f),GizmoPoint3(0f,1f,0f),800,600))
    private fun layout(rs:List<AngularReceiver>,camera:GizmoCamera=cameras[0],selected:String?="listener",density:Float=1f)=
        ReceiverMarkers.layout(rs,camera,density,selected) {GizmoPoint2(110f,16f)}
    @Test fun allViewDirectionsProjectPickAndLabelStableReceiverIdentities() {
        for(camera in cameras) {
            val l=layout(listOf(receiver()),camera)
            val marker=l.markers.single();val rect=marker.label!!
            assertEquals("listener",l.pick(marker.center,null))
            assertEquals("listener",l.pick(GizmoPoint2(rect.x+rect.width/2,rect.y+rect.height/2),null))
            assertTrue(marker.text.startsWith("L:"));assertNotNull(marker.leaderEnd)
            assertTrue(rect.x>=0 && rect.y>=0 && rect.x+rect.width<=800 && rect.y+rect.height<=600)
        }
    }
    @Test fun coincidentMarkersCycleButSeparatedLabelsSelectExactlyAndOrderIsDeterministic() {
        val rs=listOf(receiver(),receiver("reference:0"))
        val l=layout(rs)
        val center=l.markers.first().center
        assertEquals("listener",l.pick(center,null))
        assertEquals("reference:0",l.pick(center,"listener"));assertEquals("listener",l.pick(center,"reference:0"))
        assertEquals(l.markers,layout(rs.reversed()).markers)
        val labels=l.markers.map {it.label!!};assertFalse(labels[0].overlaps(labels[1]))
        l.markers.forEach {m->val r=m.label!!;assertEquals(m.id,l.pick(GizmoPoint2(r.x+3,r.y+3),"listener"))}
    }
    @Test fun clippingRejectsBehindCameraNearFarAndOffscreenMarkers() {
        val camera=cameras[1]
        val l=layout(listOf(receiver(),receiver("behind",z=21.0),receiver("near",z=19.95),receiver("far",z=-200.0),receiver("offscreen",x=1000.0)),camera)
        assertEquals(listOf("listener"),l.markers.map {it.id})
        assertNull(l.pick(GizmoPoint2(Float.NaN,0f),null));assertNull(l.pick(GizmoPoint2(-100f,-100f),null))
        assertTrue(layout(listOf(receiver()),camera.copy(up=GizmoPoint3(0f,0f,1f))).markers.isEmpty())
        assertTrue(layout(listOf(receiver()),density=Float.NaN).markers.isEmpty())
    }
    @Test fun denseLabelsStayBoundedNonoverlappingAndSelectedLabelHasPriority() {
        val rs=(0 until 65).map {receiver("reference:$it")}
        val camera=cameras[0].copy(viewportWidth=260,viewportHeight=180)
        val l=layout(rs,camera,"reference:64")
        assertEquals(65,l.markers.size);assertNotNull(l.markers.first {it.id=="reference:64"}.label)
        val labels=l.markers.mapNotNull {it.label};assertTrue(labels.size<65)
        labels.forEachIndexed {i,r->
            assertTrue(r.x>=0 && r.y>=0 && r.x+r.width<=260 && r.y+r.height<=180)
            labels.drop(i+1).forEach {assertFalse(r.overlaps(it))}
        }
        assertNotNull(l.pick(l.markers.first().center,"reference:64"))
    }
    @Test fun densityScalesTouchTargetsAndSelectionReconciliationNeverChangesIdentityByName() {
        val l=layout(listOf(receiver()),density=2f);assertEquals(44f,l.hitRadius)
        assertEquals("reference:1",ReceiverMarkerSelection.accept("reference:1",listOf("listener","reference:1")))
        assertNull(ReceiverMarkerSelection.accept("reference:99",listOf("listener")))
        assertEquals("listener",ReceiverMarkerSelection.reconcile("reference:99",listOf("listener")))
        assertNull(ReceiverMarkerSelection.reconcile("listener",emptyList()))
        assertNotEquals(ReceiverMarkerSelection.label(receiver()),ReceiverMarkerSelection.label(receiver("reference:0")))
    }
}
