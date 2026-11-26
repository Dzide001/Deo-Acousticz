package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.traces.*

@Composable
internal fun ExclusionHistoryFitOverlay(comparison:FitOverlayComparison,selectedHz:Int?,changedOnly:Boolean,onSelect:(Int?)->Unit,onFilter:(Boolean)->Unit) {
    if(!comparison.compatible) {Text("Overlay unavailable: ${comparison.reason}");return}
    if(comparison.rows.isEmpty()) {Text("No common-window phase samples to plot.");return}
    Text("Fit overlay · blue solid: current · orange dashed: preview")
    Text("Hollow circles: retained; crosses: rejected; bottom ticks: excluded/missing/insufficient. Whole-turn phase offsets are aligned where observations overlap. Gaps are not interpolated.")
    Text("${comparison.rows.count {it.fittedDifferenceDegrees!=null}} frequencies have fitted values in both states.")
    val visible=OverlaySampleInspection.rows(comparison,changedOnly)
    val selected=OverlaySampleInspection.resolve(comparison,selectedHz,changedOnly)
    Row {
        TextButton(onClick={onFilter(false)}) {Text(if(!changedOnly) "✓ All samples" else "All samples")}
        TextButton(onClick={onFilter(true)}) {Text(if(changedOnly) "✓ Changed samples" else "Changed samples")}
    }
    Text("Showing ${visible.size}/${comparison.rows.size} frequencies. Changed means a classification, value availability, or phase/residual change greater than 0.000001°. Segment renumbering is ignored.")
    if(visible.isEmpty()) Text("No changed samples in this comparison.")
    Text("Tap either series to inspect both states. Differences are preview minus current; unavailable values stay unavailable.")
    OverlayPlot(comparison,false,selected?.hz,changedOnly,onSelect)
    OverlayPlot(comparison,true,selected?.hz,changedOnly,onSelect)
    Row {
        TextButton(enabled=visible.isNotEmpty(),onClick={onSelect(OverlaySampleInspection.step(comparison,selected?.hz,-1,changedOnly)?.hz)}) {Text("Previous sample")}
        TextButton(enabled=visible.isNotEmpty(),onClick={onSelect(OverlaySampleInspection.step(comparison,selected?.hz,1,changedOnly)?.hz)}) {Text("Next sample")}
        TextButton(enabled=selectedHz!=null,onClick={onSelect(null)}) {Text("Clear selection")}
    }
    Text(selected?.let {"Selected overlay sample: ${it.hz} Hz"} ?: "Selected overlay sample: none")
    selected?.let {row ->
        fun value(v:Double?)=v?.let {"%+.6f°".format(it)} ?: "Unavailable"
        fun status(s:MatchFitSample?)=s?.status?.name?.lowercase() ?: "absent"
        Text("Current: ${status(row.current)} · Preview: ${status(row.proposed)}")
        Text("Observed phase: ${value(row.current?.observedDegrees)} → ${value(row.proposed?.observedDegrees)}")
        Text("Fitted phase: ${value(row.current?.fittedDegrees)} → ${value(row.proposed?.fittedDegrees)}")
        Text("Fitted difference: ${value(row.fittedDifferenceDegrees)}")
        Text("Residual: ${value(row.current?.residualDegrees)} → ${value(row.proposed?.residualDegrees)}")
        Text("Residual difference: ${value(row.residualDifferenceDegrees)}")
        Text(if(row.changed) "Changes: "+row.changes.joinToString {it.name.lowercase().replace('_',' ')} else "Unchanged within comparison tolerance.")
    }
}

@Composable
private fun OverlayPlot(comparison:FitOverlayComparison,residual:Boolean,selectedHz:Int?,changedOnly:Boolean,onSelect:(Int?)->Unit) {
    val axis=comparison.geometry(residual,100.0,100.0,8.0)
    Text(if(residual) "Current and previewed residuals" else "Current and previewed phase fits")
    Text("${axis.minHz}–${axis.maxHz} Hz (linear) · %.1f to %.1f°".format(axis.lowDegrees,axis.highDegrees))
    val padPx=with(LocalDensity.current) {8.dp.toPx()}
    val offsetPx=with(LocalDensity.current) {7.dp.toPx()}
    val hitPx=with(LocalDensity.current) {20.dp.toPx()}
    val currentSelect by rememberUpdatedState(onSelect)
    val visibleHz=OverlaySampleInspection.rows(comparison,changedOnly).map {it.hz}.toSet()
    Canvas(Modifier.fillMaxWidth().height(150.dp).pointerInput(comparison,residual,changedOnly,padPx,offsetPx,hitPx) {
        detectTapGestures {tap ->
            if(size.width>2*padPx && size.height>2*padPx) {
                val geometry=OverlayInspectionGeometry(comparison,residual,size.width.toDouble(),size.height.toDouble(),padPx.toDouble(),offsetPx.toDouble())
                currentSelect(geometry.hit(tap.x.toDouble(),tap.y.toDouble(),hitPx.toDouble(),changedOnly)?.hz)
            }
        }
    }.semantics {
        contentDescription="${if(residual) "Residual" else "Phase"} overlay: blue current, orange preview; ${visibleHz.size} visible frequencies"
    }) {
        val pad=8.dp.toPx();if(size.width<=2*pad || size.height<=2*pad) return@Canvas
        val inspection=OverlayInspectionGeometry(comparison,residual,size.width.toDouble(),size.height.toDouble(),pad.toDouble(),7.dp.toPx().toDouble())
        val geometry=inspection.axis
        fun point(hz:Int,degrees:Double)=Offset(geometry.x(hz).toFloat(),geometry.y(degrees).toFloat())
        if(geometry.lowDegrees<=0 && geometry.highDegrees>=0) drawLine(Color.Gray,point(geometry.minHz,0.0),point(geometry.maxHz,0.0))
        for(proposed in listOf(false,true)) {
            val color=if(proposed) Color(0xFFFF9800) else Color(0xFF448AFF)
            val dash=if(proposed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(),4.dp.toPx())) else null
            OverlaySampleInspection.lines(comparison,proposed,residual,changedOnly).forEach {line ->
                drawLine(color,point(line.fromHz,line.fromDegrees),point(line.toHz,line.toDegrees),2.dp.toPx(),pathEffect=dash)
            }
            comparison.samples(proposed).filter {it.hz in visibleHz}.forEach {s ->
                val value=if(residual) s.residualDegrees else s.observedDegrees
                val r=if(proposed) 5.dp.toPx() else 3.dp.toPx()
                val marker=inspection.marker(s,proposed)
                val markerPoint=Offset(marker.x.toFloat(),marker.y.toFloat())
                if(s.hz==selectedHz) drawCircle(Color.Yellow,9.dp.toPx(),markerPoint,style=Stroke(2.dp.toPx()))
                if(value==null) {
                    val x=markerPoint.x;val bottom=markerPoint.y
                    drawLine(color,Offset(x,bottom),Offset(x,bottom-4.dp.toPx()),2.dp.toPx())
                } else {
                    val p=markerPoint
                    if(s.status==MatchSampleStatus.REJECTED) {
                        drawLine(color,p-Offset(r,r),p+Offset(r,r),2.dp.toPx())
                        drawLine(color,p+Offset(-r,r),p+Offset(r,-r),2.dp.toPx())
                    } else drawCircle(color,r,p,style=Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}
