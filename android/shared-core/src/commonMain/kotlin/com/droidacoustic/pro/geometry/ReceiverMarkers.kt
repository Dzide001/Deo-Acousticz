package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.prediction.AngularReceiver
import kotlin.math.*

object ReceiverMarkerSelection {
    fun accept(id:String,available:Collection<String>):String?=id.takeIf {it in available}
    fun reconcile(id:String?,available:Collection<String>):String?=id?.takeIf {it in available}
        ?: "listener".takeIf {it in available} ?: available.firstOrNull()
    fun label(receiver:AngularReceiver):String {
        val prefix=when {receiver.id=="listener"->"L";receiver.id.startsWith("reference:")->"R"+receiver.id.removePrefix("reference:");else->receiver.id}
        return "$prefix: ${receiver.name.replace('\n',' ').replace('\r',' ').replace('\t',' ')}"
    }
}
data class ReceiverLabelRect(val x:Float,val y:Float,val width:Float,val height:Float) {
    fun contains(p:GizmoPoint2)=p.x>=x && p.x<=x+width && p.y>=y && p.y<=y+height
    fun overlaps(b:ReceiverLabelRect)=x<b.x+b.width && b.x<x+width && y<b.y+b.height && b.y<y+height
}
data class ReceiverMarkerScreen(val id:String,val text:String,val center:GizmoPoint2,val depth:Double,val label:ReceiverLabelRect?) {
    val leaderEnd:GizmoPoint2? get()=label?.let {GizmoPoint2(center.x.coerceIn(it.x,it.x+it.width),center.y.coerceIn(it.y,it.y+it.height))}
}
class ReceiverMarkerLayout(markers:List<ReceiverMarkerScreen>,val hitRadius:Float,private val coincidencePixels:Float) {
    val markers=markers.toList()
    fun pick(point:GizmoPoint2,previousId:String?):String? {
        if(!point.isFinite()) return null
        markers.firstOrNull {it.label?.contains(point)==true}?.let {return it.id}
        fun distance(a:GizmoPoint2,b:GizmoPoint2)=hypot(a.x-b.x,a.y-b.y)
        val hits=markers.filter {distance(point,it.center)<=hitRadius}
            .sortedWith(compareBy({distance(point,it.center)},{it.depth},{it.id}))
        val first=hits.firstOrNull() ?: return null
        val coincident=hits.filter {distance(it.center,first.center)<=coincidencePixels}
        val prior=coincident.indexOfFirst {it.id==previousId}
        return if(coincident.size>1 && prior>=0) coincident[(prior+1)%coincident.size].id else first.id
    }
}

/** Pixel projection/layout/picking shared by Android and future renderers. No scene occlusion is inferred. */
object ReceiverMarkers {
    fun layout(receivers:List<AngularReceiver>,camera:GizmoCamera,density:Float,selectedId:String?,
        labelBounds:ReceiverLabelRect?=null,measure:(String)->GizmoPoint2):ReceiverMarkerLayout {
        fun empty()=ReceiverMarkerLayout(emptyList(),0f,0f)
        if(!density.isFinite() || density<=0 || camera.viewportWidth<2 || camera.viewportHeight<2) return empty()
        require(receivers.size<=65 && receivers.map {it.id}.distinct().size==receivers.size)
        val fx=camera.target.x.toDouble()-camera.eye.x;val fy=camera.target.y.toDouble()-camera.eye.y;val fz=camera.target.z.toDouble()-camera.eye.z
        val length=sqrt(fx*fx+fy*fy+fz*fz)
        if(!length.isFinite() || length<1e-6) return empty()
        val projected=receivers.mapNotNull {r->
            val p=r.position
            val depth=((p.x-camera.eye.x)*fx+(p.y-camera.eye.y)*fy+(p.z-camera.eye.z)*fz)/length
            if(!depth.isFinite() || depth<=.1 || depth>200) return@mapNotNull null
            val center=camera.project(GizmoPoint3(p.x.toFloat(),p.y.toFloat(),p.z.toFloat())) ?: return@mapNotNull null
            if(center.x !in 0f..camera.viewportWidth.toFloat() || center.y !in 0f..camera.viewportHeight.toFloat()) return@mapNotNull null
            ReceiverMarkerScreen(r.id,ReceiverMarkerSelection.label(r),center,depth,null)
        }.sortedWith(compareBy({if(it.id==selectedId) 0 else 1},{it.depth},{it.id}))
        val padding=2*density;val hit=22*density
        if(!padding.isFinite() || !hit.isFinite()) return empty()
        val bounds=labelBounds ?: ReceiverLabelRect(0f,0f,camera.viewportWidth.toFloat(),camera.viewportHeight.toFloat())
        val left=max(padding,bounds.x);val top=max(padding,bounds.y)
        val right=min(camera.viewportWidth-padding,bounds.x+bounds.width);val bottom=min(camera.viewportHeight-padding,bounds.y+bounds.height)
        val usableLabels=listOf(left,top,right,bottom).all {it.isFinite()} && right>left && bottom>top
        val protectedMarkers=projected.map {p->ReceiverLabelRect(p.center.x-7*density,p.center.y-7*density,14*density,14*density)}
        val placed=mutableListOf<ReceiverLabelRect>()
        val markers=projected.map {m->
            if(!usableLabels) return@map m
            val measured=measure(m.text)
            if(!measured.isFinite() || measured.x<=0 || measured.y<=0) return@map m
            val w=min(measured.x+12*density,right-left)
            val h=min(measured.y+8*density,bottom-top)
            if(w<=0 || h<=0) return@map m
            fun rect(x:Float,y:Float)=ReceiverLabelRect(x.coerceIn(left,right-w),y.coerceIn(top,bottom-h),w,h)
            val c=m.center;val gap=hit+3*density
            val candidates=mutableListOf(rect(c.x+gap,c.y-h/2),rect(c.x-gap-w,c.y-h/2),rect(c.x-w/2,c.y-gap-h),rect(c.x-w/2,c.y+gap))
            for(i in 1..12) {
                val dy=i*(h+3*density)
                candidates+=rect(c.x+gap,c.y-h/2+dy);candidates+=rect(c.x+gap,c.y-h/2-dy)
                candidates+=rect(c.x-gap-w,c.y-h/2+dy);candidates+=rect(c.x-gap-w,c.y-h/2-dy)
            }
            val chosen=candidates.firstOrNull {r->placed.none(r::overlaps) && protectedMarkers.none(r::overlaps)} ?: candidates.firstOrNull {r->m.id==selectedId && placed.none(r::overlaps)}
            chosen?.let {placed+=it};m.copy(label=chosen)
        }
        return ReceiverMarkerLayout(markers,hit,density)
    }
}
