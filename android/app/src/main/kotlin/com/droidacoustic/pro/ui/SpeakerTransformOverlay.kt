package com.droidacoustic.pro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.scene.PlacedSpeaker

/** Android touch/render adapter; projection, layout, hit testing and drag state live in shared core. */
internal class SpeakerTransformOverlay(context: Context, private val scene: FilamentContext) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density=resources.displayMetrics.density
    private var drag: SpeakerGizmoDragSession?=null
    private var original: PlacedSpeaker?=null
    private var originalObject: SceneTransformTarget?=null
    private var dragPreset: ViewPreset?=null
    private var dragWidth=0; private var dragHeight=0
    private var suppressUntilUp=false
    private fun PlacedSpeaker.transform()=SpeakerGizmoTransform(x,heightM,z,panDeg,arrayAimDeg)
    private fun PlacedSpeaker.withTransform(t:SpeakerGizmoTransform)=copy(x=t.x,heightM=t.y,z=t.z,panDeg=t.panDeg,arrayAimDeg=t.tiltDeg)
    private fun layout(t:SpeakerGizmoTransform)=SpeakerGizmo.layout(t,SpeakerGizmoPreset.valueOf(scene.gizmoPreset.name),width,height,density,allowedKinds=if(scene.gizmoObject==null) null else SpeakerGizmoHandleKind.entries.filter { it!=SpeakerGizmoHandleKind.TILT }.toSet(),project=scene.gizmoCamera()::project)
    private fun current():Boolean = original==scene.gizmoSpeaker && originalObject==scene.gizmoObject && dragPreset==scene.gizmoPreset && dragWidth==width && dragHeight==height
    private fun cancel() {
        drag=drag?.cancel();scene.previewGizmo(null);scene.previewObjectGizmo(null,null);drag=null;original=null;originalObject=null
        parent?.requestDisallowInterceptTouchEvent(false);invalidate()
    }
    private fun color(kind:SpeakerGizmoHandleKind)=when(kind) {
        SpeakerGizmoHandleKind.X->Color.rgb(255,100,100)
        SpeakerGizmoHandleKind.Y->Color.rgb(120,230,140)
        SpeakerGizmoHandleKind.Z->Color.rgb(100,170,255)
        SpeakerGizmoHandleKind.PAN->Color.rgb(255,205,100)
        SpeakerGizmoHandleKind.TILT->Color.rgb(230,160,255)
    }
    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        if(drag!=null && !current()) {cancel();suppressUntilUp=true}
        val speaker=scene.gizmoSpeaker
        val transform=drag?.preview ?: speaker?.transform() ?: scene.gizmoObject?.transform
        val l=transform?.let(::layout)
        if(l!=null) {
            for(h in l.handles) {
                paint.color=color(h.kind);paint.strokeWidth=3*density
                c.drawLine(l.origin.x,l.origin.y,h.center.x,h.center.y,paint)
                c.drawCircle(h.center.x,h.center.y,22*density,paint)
                paint.color=Color.BLACK;paint.textSize=12*density;paint.textAlign=Paint.Align.CENTER
                val label=when(h.kind) {SpeakerGizmoHandleKind.PAN->if(scene.gizmoObject!=null) "Rotate" else "Pan";SpeakerGizmoHandleKind.TILT->"Tilt";else->h.kind.name}
                c.drawText(label,h.center.x,h.center.y+4*density,paint)
            }
            drag?.let {d ->
                paint.color=Color.WHITE;paint.textSize=14*density;paint.textAlign=Paint.Align.CENTER
                val axis=d.handle.kind in listOf(SpeakerGizmoHandleKind.X,SpeakerGizmoHandleKind.Y,SpeakerGizmoHandleKind.Z)
                val text=if(axis) "${d.handle.kind}: ${"%.2f".format(d.amount)} m" else "${d.handle.kind}: ${"%.1f".format(d.amount)}°"
                c.drawText(text,l.origin.x.coerceIn(minOf(100*density,width/2f),maxOf(width-100*density,width/2f)),
                    (l.origin.y+40*density).coerceIn(20*density,height-20*density),paint)
                c.drawText("Preview · release to apply",width/2f,28*density,paint)
            }
        }
        // Camera orbit can move handles without a Compose state update.
        if(isAttachedToWindow && visibility==VISIBLE && (speaker!=null || scene.gizmoObject!=null)) postInvalidateOnAnimation()
    }
    fun refresh() { invalidate() }
    override fun onDetachedFromWindow() {cancel();super.onDetachedFromWindow()}
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(e.actionMasked==MotionEvent.ACTION_DOWN) {
            suppressUntilUp=false
            val s=scene.gizmoSpeaker
            val obj=scene.gizmoObject
            val transform=s?.transform() ?: obj?.transform ?: return false
            val h=layout(transform)?.hitTest(GizmoPoint2(e.x,e.y)) ?: return false
            drag=SpeakerGizmoDragSession.begin(transform,h,GizmoPoint2(e.x,e.y),density) ?: return false
            original=s;originalObject=obj;dragPreset=scene.gizmoPreset;dragWidth=width;dragHeight=height
            parent.requestDisallowInterceptTouchEvent(true);invalidate();return true
        }
        if(suppressUntilUp) {if(e.actionMasked==MotionEvent.ACTION_UP) suppressUntilUp=false;return true}
        val d=drag ?: return false
        if(!current() || e.actionMasked==MotionEvent.ACTION_CANCEL || e.actionMasked==MotionEvent.ACTION_POINTER_DOWN) {
            cancel();suppressUntilUp=e.actionMasked!=MotionEvent.ACTION_CANCEL;return true
        }
        when(e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                drag=d.update(GizmoPoint2(e.x,e.y))
                original?.let { scene.previewGizmo(it.withTransform(drag!!.preview)) }
                originalObject?.let { scene.previewObjectGizmo(it,drag!!.preview) }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val final=d.update(GizmoPoint2(e.x,e.y));val s=original;val obj=originalObject;val target=final.commit()
                cancel()
                if(target!=null && obj!=null) scene.onObjectCommit(obj,target)
                if(target!=null && s!=null) when(final.handle.kind) {
                    SpeakerGizmoHandleKind.X,SpeakerGizmoHandleKind.Y,SpeakerGizmoHandleKind.Z->scene.onGizmoMove(s.id,target.x,target.y,target.z)
                    SpeakerGizmoHandleKind.PAN->scene.onGizmoRotate(s.id,target.panDeg)
                    SpeakerGizmoHandleKind.TILT->scene.onGizmoTilt(s.id,target.tiltDeg)
                }
            }
        }
        return true
    }
}
