package com.droidacoustic.pro.validation

import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.prediction.AngularReceiver
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class ReceiverMarkerPackageTest {
    @Test fun retainedGeometryCasesPickAndLabelReceiversInAllViews() {
        val rs=listOf(AngularReceiver("listener","Listener",BoundaryPoint(0.0,0.0,0.0)),AngularReceiver("reference:0","Same position",BoundaryPoint(0.0,0.0,0.0)),
            AngularReceiver("reference:1","Balcony",BoundaryPoint(1.0,2.0,1.0)))
        val target=GizmoPoint3(0f,0f,0f)
        val cameras=listOf("PLAN" to GizmoCamera(GizmoPoint3(0f,20f,0f),target,GizmoPoint3(0f,0f,-1f),800,600),
            "FRONT" to GizmoCamera(GizmoPoint3(0f,0f,20f),target,GizmoPoint3(0f,1f,0f),800,600),
            "SIDE" to GizmoCamera(GizmoPoint3(20f,0f,0f),target,GizmoPoint3(0f,1f,0f),800,600),
            "PERSPECTIVE" to GizmoCamera(GizmoPoint3(10f,8f,12f),target,GizmoPoint3(0f,1f,0f),800,600))
        val cases=cameras.map {(name,camera)->
            val layout=ReceiverMarkers.layout(rs,camera,1f,"listener") {GizmoPoint2(140f,16f)}
            assertEquals(3,layout.markers.size);assertTrue(layout.markers.all {it.label!=null})
            val labelPicks=layout.markers.map {m->val r=m.label!!;val picked=layout.pick(GizmoPoint2(r.x+r.width/2,r.y+r.height/2),null)
                assertEquals(m.id,picked);picked!!}
            val center=layout.markers.first {it.id=="listener"}.center
            assertEquals("reference:0",layout.pick(center,"listener"))
            buildJsonObject {put("view",name);put("labelPicks",JsonArray(labelPicks.map(::JsonPrimitive)));put("coincidentNextPick","reference:0")
                put("markers",JsonArray(layout.markers.map {m->buildJsonObject {
                    put("id",m.id);put("text",m.text);put("screenX",m.center.x);put("screenY",m.center.y);put("depth",m.depth)
                    val r=m.label!!;put("labelRect",buildJsonArray {add(r.x);add(r.y);add(r.width);add(r.height)})
                }}))}
        }
        val file=File(System.getProperty("receiverMarkerReport"));file.parentFile.mkdirs()
        file.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("schema",1);put("scope","Synthetic camera/selection geometry verification; no acoustic accuracy claim")
            put("cases",JsonArray(cases))
        })+"\n")
    }
}
