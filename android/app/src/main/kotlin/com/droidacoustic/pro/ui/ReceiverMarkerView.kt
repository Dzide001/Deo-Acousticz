package com.droidacoustic.pro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.text.TextPaint
import android.view.View
import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.prediction.ReceiverAngularReport

/** Native text painting only. Shared code owns projection, collision layout, selection and clipping. */
internal class ReceiverMarkerView(context:Context,private val scene:FilamentContext):View(context) {
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val text=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        // TextPaint.density defaults to 1 and shadows the View's density in this scope.
        textSize=12*resources.displayMetrics.scaledDensity
        color=Color.WHITE
    }
    private var priorCamera:GizmoCamera?=null
    private var priorReport:ReceiverAngularReport?=null
    private var priorSelection:String?=null
    private var priorBounds:ReceiverLabelRect?=null
    private var cached=ReceiverMarkerLayout(emptyList(),0f,0f)
    init {
        isClickable=false
        scene.receiverMarkerLayoutProvider={layout()}
        scene.invalidateReceiverMarkers={postInvalidateOnAnimation()}
    }
    fun refresh() {invalidate()}
    private fun layout():ReceiverMarkerLayout {
        val camera=scene.gizmoCamera();val report=scene.receiverMarkerReport;val selected=scene.selectedReceiverMarkerId
        if(camera!=priorCamera || report!==priorReport || selected!=priorSelection || priorBounds!=scene.receiverLabelSafeRect) {
            cached=ReceiverMarkers.layout(report?.receivers?.map {it.receiver}.orEmpty(),camera,density,selected,scene.receiverLabelSafeRect) {value->
                GizmoPoint2(text.measureText(value).coerceAtMost(180*density),text.fontMetrics.let {it.descent-it.ascent})
            }
            priorCamera=camera;priorReport=report;priorSelection=selected;priorBounds=scene.receiverLabelSafeRect
        }
        return cached
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val current=layout()
        current.markers.forEach {m->
            val selected=m.id==scene.selectedReceiverMarkerId
            paint.color=if(selected) Color.WHITE else Color.LTGRAY;paint.strokeWidth=if(selected) 2*density else density
            paint.style=Paint.Style.STROKE
            canvas.drawCircle(m.center.x,m.center.y,if(selected) 6*density else 3*density,paint)
            paint.style=Paint.Style.FILL
            m.label?.let {r->
                paint.color=0xe8202630.toInt();paint.style=Paint.Style.FILL
                canvas.drawRoundRect(r.x,r.y,r.x+r.width,r.y+r.height,4*density,4*density,paint)
                paint.color=if(selected) Color.WHITE else Color.GRAY;paint.style=Paint.Style.STROKE
                canvas.drawRoundRect(r.x,r.y,r.x+r.width,r.y+r.height,4*density,4*density,paint);paint.style=Paint.Style.FILL
                m.leaderEnd?.let {canvas.drawLine(m.center.x,m.center.y,it.x,it.y,paint)}
                val label=TextUtils.ellipsize(m.text,text,(r.width-12*density).coerceAtLeast(0f),TextUtils.TruncateAt.END).toString()
                canvas.save();canvas.clipRect(r.x,r.y,r.x+r.width,r.y+r.height)
                canvas.drawText(label,r.x+6*density,r.y+4*density-text.fontMetrics.ascent,text);canvas.restore()
            }
        }
        if(scene.receiverMarkerReport!=null) postInvalidateOnAnimation()
    }
}
