package com.droidacoustic.pro.validation

import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.prediction.AngularReceiver
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class ReceiverFramingPackageTest {
    @Test fun retainedFramesKeepReceiverPickableInAllViewsAndRestoreTheSameIntent() {
        val receiver=AngularReceiver("reference:4","Upper balcony",BoundaryPoint(38.0,12.0,-17.0))
        val cases=ReceiverFrameView.entries.flatMap {view->listOf(1200 to 700,400 to 800).map {(w,h)->
            val focus=ReceiverCameraFocus(receiver.id,view)
            val panel=ReceiverLabelRect((w-280).coerceAtLeast(0).toFloat(),(h-180).toFloat(),280f,180f)
            val frame=ReceiverCameraFraming.fit(focus,receiver,w,h,obstructions=listOf(panel))!!
            assertEquals(frame,ReceiverCameraFraming.fit(ReceiverCameraFocus.parse(focus.toJsonString()),receiver,w,h,obstructions=listOf(panel)))
            val layout=ReceiverMarkers.layout(listOf(receiver),frame.camera,1f,receiver.id) {GizmoPoint2(180f,16f)}
            val marker=layout.markers.single();assertEquals(receiver.id,layout.pick(marker.center,null));assertNotNull(marker.label)
            buildJsonObject {
                put("view",view.name);put("width",w);put("height",h);put("focus",Json.parseToJsonElement(focus.toJsonString()))
                put("receiverScreen",buildJsonArray {add(marker.center.x);add(marker.center.y)})
                val c=frame.camera;put("eye",buildJsonArray {add(c.eye.x);add(c.eye.y);add(c.eye.z)})
                put("target",buildJsonArray {add(c.target.x);add(c.target.y);add(c.target.z)})
                val u=frame.usable;put("usableRect",buildJsonArray {add(u.x);add(u.y);add(u.width);add(u.height)})
                put("selectedPick",receiver.id)
            }
        }}
        val out=File(System.getProperty("receiverFramingReport"));out.parentFile.mkdirs()
        out.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("schema",1);put("scope","Synthetic camera framing/recovery geometry; no acoustic accuracy claim");put("cases",JsonArray(cases))
        })+"\n")
    }
}
