package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.prediction.AngularReceiver
import kotlinx.serialization.json.*
import kotlin.math.*

enum class ReceiverFrameView { PLAN, SECTION, PERSPECTIVE }
data class ReceiverCameraFocus(val receiverId:String,val view:ReceiverFrameView,val spanM:Float=8f) {
    init {require(receiverId.isNotBlank() && spanM.isFinite() && spanM in 2f..64f)}
    fun reconcile(ids:Collection<String>)=takeIf {receiverId in ids}
    fun toJsonString()=buildJsonObject {put("version",1);put("receiverId",receiverId);put("view",view.name);put("spanM",spanM)}.toString()
    companion object {
        fun parse(text:String):ReceiverCameraFocus {
            val r=Json.parseToJsonElement(text).jsonObject
            require(r.keys==setOf("version","receiverId","view","spanM") && r.getValue("version").jsonPrimitive.int==1)
            return ReceiverCameraFocus(r.getValue("receiverId").jsonPrimitive.content,ReceiverFrameView.valueOf(r.getValue("view").jsonPrimitive.content),r.getValue("spanM").jsonPrimitive.float)
        }
    }
}
data class ReceiverCameraFrame(val camera:GizmoCamera,val usable:ReceiverLabelRect,val spanM:Float)

/** Conservative camera-space cube framing. No acoustic geometry or values are altered. */
object ReceiverCameraFraming {
    fun fit(focus:ReceiverCameraFocus,receiver:AngularReceiver,width:Int,height:Int,density:Float=1f,
        verticalFov:Float=45f,obstructions:List<ReceiverLabelRect> = emptyList()):ReceiverCameraFrame? {
        if(receiver.id!=focus.receiverId || width<2 || height<2 || !density.isFinite() || density<=0 ||
            !verticalFov.isFinite() || verticalFov !in 10f..120f || obstructions.size>8) return null
        val margin=16*density;val vertical=32*density
        val initial=ReceiverLabelRect(margin,vertical,width-2*margin,height-2*vertical)
        if(initial.width<24*density || initial.height<24*density) return null
        var free=listOf(initial)
        for(b in obstructions) {
            if(!listOf(b.x,b.y,b.width,b.height).all {it.isFinite()} || b.width<0 || b.height<0) return null
            if(b.width==0f || b.height==0f) continue
            free=free.flatMap {r->
                if(!r.overlaps(b)) listOf(r) else {
                    val left=max(r.x,b.x-8*density);val right=min(r.x+r.width,b.x+b.width+8*density)
                    val top=max(r.y,b.y-8*density);val bottom=min(r.y+r.height,b.y+b.height+8*density)
                    listOf(ReceiverLabelRect(r.x,r.y,left-r.x,r.height),ReceiverLabelRect(right,r.y,r.x+r.width-right,r.height),
                        ReceiverLabelRect(r.x,r.y,r.width,top-r.y),ReceiverLabelRect(r.x,bottom,r.width,r.y+r.height-bottom))
                }
            }.filter {it.width>=24*density && it.height>=24*density}.distinct()
                .sortedByDescending {it.width.toDouble()*it.height}.take(32)
        }
        val usable=free.maxByOrNull {it.width.toDouble()*it.height} ?: return null
        val direction=when(focus.view) {
            ReceiverFrameView.PLAN->doubleArrayOf(0.0,1.0,.02)
            ReceiverFrameView.SECTION->doubleArrayOf(1.0,.18,.001)
            ReceiverFrameView.PERSPECTIVE->doubleArrayOf(.55,.62,.80)
        }
        val norm=sqrt(direction.sumOf {it*it});val back=direction.map {it/norm}
        val forward=back.map {-it}
        // right = forward cross world-up; PLAN keeps a small nonzero horizontal component.
        val rightLength=sqrt(forward[2]*forward[2]+forward[0]*forward[0])
        val right=doubleArrayOf(-forward[2]/rightLength,0.0,forward[0]/rightLength)
        val up=doubleArrayOf(right[1]*forward[2]-right[2]*forward[1],right[2]*forward[0]-right[0]*forward[2],right[0]*forward[1]-right[1]*forward[0])
        val focal=height/(2*tan(verticalFov*PI/360))
        val sx=(usable.x+usable.width/2-width/2.0)/focal
        val sy=(height/2.0-usable.y-usable.height/2)/focal
        val radius=focus.spanM/2.0
        // Account for off-axis placement and the closest cube face, not just its centre.
        val distance=radius+max(focal*radius*(1+abs(sx))/(usable.width/2),focal*radius*(1+abs(sy))/(usable.height/2))*1.04
        if(!distance.isFinite() || distance-radius<=.1 || distance+radius>=199) return null
        val p=receiver.position
        if(!listOf(p.x,p.y,p.z).all {it.isFinite()}) return null
        val center=doubleArrayOf(p.x,p.y,p.z)
        val target=DoubleArray(3) {i->center[i]-distance*(sx*right[i]+sy*up[i])}
        val eye=DoubleArray(3) {i->target[i]+back[i]*distance}
        val camera=GizmoCamera(GizmoPoint3(eye[0].toFloat(),eye[1].toFloat(),eye[2].toFloat()),
            GizmoPoint3(target[0].toFloat(),target[1].toFloat(),target[2].toFloat()),GizmoPoint3(0f,1f,0f),width,height,verticalFov)
        // Verify actual Float camera projection; reject precision/clipping failures rather than apply a bad frame.
        for(x in listOf(-1,1)) for(y in listOf(-1,1)) for(z in listOf(-1,1)) {
            val corner=DoubleArray(3) {i->center[i]+radius*(x*right[i]+y*up[i]+z*back[i])}
            val point=camera.project(GizmoPoint3(corner[0].toFloat(),corner[1].toFloat(),corner[2].toFloat())) ?: return null
            if(!usable.contains(point)) return null
        }
        return ReceiverCameraFrame(camera,usable,focus.spanM)
    }
}
