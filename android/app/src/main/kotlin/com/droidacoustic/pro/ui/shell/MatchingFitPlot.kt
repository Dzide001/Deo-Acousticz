package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.traces.*

/** Rendering only: sample classification, fitted phase and residuals come from shared-core. */
@Composable
internal fun MatchingFitPlot(match:TraceReferenceMatch,selection:MatchSampleSelection?,onSelect:(MatchSampleSelection?)->Unit,rangeExcluded:Boolean=false,ranges:Set<MatchRangeExclusion>,onRangeEdit:(MatchRangeExclusion,MatchRangeExclusion)->Boolean,onRangeExclude:(MatchRangeExclusion)->Unit,onExclude:(MatchSampleSelection,Boolean)->Unit) {
    val samples=match.fitSamples
    if(samples.isEmpty()) return
    Text("Fit diagnostics · B-relative minus A-relative phase")
    Text("Blue circles: retained · red crosses: rejected · white line: fit · orange bottom ticks: manually excluded · grey bottom ticks: missing or insufficient. Separate segments have independent phase offsets.")
    val selected=selection?.resolve(match)
    var editing by remember(match.baselineId,match.candidateId,match.frequencyRange) {mutableStateOf(false)}
    var moveMode by remember(match.baselineId,match.candidateId) {mutableStateOf(false)}
    var original by remember(match.baselineId,match.candidateId,match.frequencyRange) {mutableStateOf<MatchRangeExclusion?>(null)}
    var editDraft by remember(match.baselineId,match.candidateId,match.frequencyRange) {mutableStateOf<MatchingFrequencyRange?>(null)}
    var editError by remember {mutableStateOf<String?>(null)}
    val pairRanges=ranges.filter {it.baselineId==match.baselineId && it.candidateId==match.candidateId}.toSet()
    var rangeMode by remember(match.baselineId,match.candidateId) {mutableStateOf(false)}
    var draft by remember(match.baselineId,match.candidateId,match.frequencyRange) {mutableStateOf<MatchingFrequencyRange?>(null)}
    TextButton(onClick={rangeMode=!rangeMode;draft=null;editing=false;original=null;editDraft=null}) {Text(if(rangeMode) "Range selection on · Switch to sample inspection" else "Select exclusion range on plots")}
    TextButton(onClick={editing=!editing;rangeMode=false;draft=null;original=null;editDraft=null;editError=null}) {Text(if(editing) "Finish editing exclusions" else "Edit exclusions on plots")}
    fun selectExisting(range:MatchRangeExclusion?) {original=range;editDraft=range?.let {MatchingFrequencyRange(it.minHz,it.maxHz)};editError=null}
    if(editing) {
        Text("Tap an orange band to select it. Choose Resize edges or Move whole range, then drag in either plot. For overlapping bands, select a range below. Edits are saved only when you choose Save range edit. To reach edges outside the plot, widen the matching frequency window.")
        Row {
            TextButton(onClick={moveMode=false}) {Text(if(!moveMode) "✓ Resize edges" else "Resize edges")}
            TextButton(onClick={moveMode=true}) {Text(if(moveMode) "✓ Move whole range" else "Move whole range")}
        }
        if(moveMode) Text("Drag inside the selected band. Width in Hz stays fixed; movement uses whole-Hz steps and stops at plot limits. The entire range must be visible.")
        pairRanges.sortedBy {it.minHz}.forEach {range ->
            TextButton(onClick={selectExisting(range)}) {Text("${range.minHz}–${range.maxHz} Hz"+if(original==range) " · selected" else " · Select")}
        }
        Text(editDraft?.let {"Edited range: ${it.minHz}–${it.maxHz} Hz"} ?: "Select an existing exclusion.")
        Row {
            TextButton(enabled=original!=null && editDraft!=null,onClick={
                val old=original!!;val bounds=editDraft!!
                if(onRangeEdit(old,MatchRangeExclusion(old.baselineId,old.candidateId,bounds.minHz,bounds.maxHz))) selectExisting(null)
                else editError="Exclusion changed. Select it again."
            }) {Text("Save range edit")}
            TextButton(onClick={selectExisting(null)}) {Text("Cancel edit")}
        }
        editError?.let {Text(it)}
    }
    if(rangeMode) {
        Text("Drag horizontally on either plot. Endpoints snap to captured frequencies. Vertical swipes still scroll. The highlighted range is not saved until you exclude it.")
        Text(draft?.let {"Selected range: ${it.minHz}–${it.maxHz} Hz (inclusive)"} ?: "Select at least two different frequencies.")
        Row {
            TextButton(enabled=draft!=null,onClick={draft?.let {onRangeExclude(MatchRangeSelection.exclusion(match,it))};draft=null}) {Text("Exclude selected range")}
            TextButton(enabled=draft!=null,onClick={draft=null}) {Text("Clear range")}
        }
    }
    Text(if(editing) (if(moveMode) "Drag inside the selected band to move it without changing its width." else "Drag a yellow edge to resize the selected exclusion in either plot.") else if(rangeMode) "The selected range is highlighted in both plots." else "Tap a marker to inspect it. The selected frequency is highlighted in both plots.")
    DiagnosticPlot(samples,false,selected?.hz,rangeMode,draft,{draft=it},editing,moveMode,pairRanges,editDraft,{selectExisting(it)},{editDraft=it}) {hz ->onSelect(hz?.let {MatchSampleInspection.select(match,it)})}
    Text("Residuals · observed minus fitted phase")
    DiagnosticPlot(samples,true,selected?.hz,rangeMode,draft,{draft=it},editing,moveMode,pairRanges,editDraft,{selectExisting(it)},{editDraft=it}) {hz ->onSelect(hz?.let {MatchSampleInspection.select(match,it)})}
    Row {
        TextButton(onClick={onSelect(MatchSampleInspection.step(match,selection,-1))}) {Text("Previous sample")}
        TextButton(onClick={onSelect(MatchSampleInspection.step(match,selection,1))}) {Text("Next sample")}
        TextButton(onClick={onSelect(null)},enabled=selected!=null) {Text("Clear")}
    }
    Text(selected?.let {"Selected: ${it.hz} Hz · ${it.status.name.lowercase()}"} ?: "Selected: tap a sample")
    if(rangeExcluded) Text("This sample is covered by a frequency-range exclusion. Remove the covering range(s) to include it.")
    if(selected!=null) TextButton(enabled=!rangeExcluded,onClick={onExclude(MatchSampleInspection.select(match,selected.hz),selected.status!=MatchSampleStatus.EXCLUDED)}) {
        Text(if(selected.status==MatchSampleStatus.EXCLUDED) "Include sample in matching" else "Exclude sample from matching")
    }
    if(selected?.status==MatchSampleStatus.EXCLUDED) Text("Manually excluded for this A/B comparison. Exclusions split phase-unwrapping segments; raw trace data stays unchanged.")
    fun degrees(value:Double?)=value?.let {"%+.3f°".format(it)} ?: "Unavailable"
    Text("Observed phase difference: ${degrees(selected?.observedDegrees)}")
    Text("Fitted phase difference: ${degrees(selected?.fittedDegrees)}")
    Text("Residual: ${degrees(selected?.residualDegrees)} · Segment: ${selected?.segment?.let {it+1} ?: "none"}")
    if(selected?.status==MatchSampleStatus.MISSING) Text("A or B has no valid complex phase at this frequency.")
    if(selected?.status==MatchSampleStatus.INSUFFICIENT) Text("Not enough consecutive valid samples to fit this segment.")
}

@Composable
private fun DiagnosticPlot(samples:List<MatchFitSample>,residual:Boolean,selectedHz:Int?,rangeMode:Boolean,draft:MatchingFrequencyRange?,onRange:(MatchingFrequencyRange?)->Unit,editing:Boolean,moveMode:Boolean,ranges:Set<MatchRangeExclusion>,editDraft:MatchingFrequencyRange?,onExisting:(MatchRangeExclusion?)->Unit,onResize:(MatchingFrequencyRange)->Unit,onSelect:(Int?)->Unit) {
    fun observed(s:MatchFitSample)=if(residual) s.residualDegrees else s.observedDegrees
    val axis=MatchPlotGeometry(samples,residual,100.0,100.0,8.0)
    val low=axis.lowDegrees;val high=axis.highDegrees
    val minHz=axis.minHz;val maxHz=axis.maxHz
    val paddingPx=with(LocalDensity.current) {8.dp.toPx()}
    val radiusPx=with(LocalDensity.current) {20.dp.toPx()}
    Text("$minHz–$maxHz Hz (linear) · %.1f to %.1f°".format(low,high))
    val retained=samples.count {it.status==MatchSampleStatus.RETAINED}
    val rejected=samples.count {it.status==MatchSampleStatus.REJECTED}
    val currentOnRange by rememberUpdatedState(onRange)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentExisting by rememberUpdatedState(onExisting)
    val currentResize by rememberUpdatedState(onResize)
    val currentEdit by rememberUpdatedState(editDraft)
    Canvas(Modifier.fillMaxWidth().height(140.dp).pointerInput(samples,residual,paddingPx,radiusPx,rangeMode,editing,moveMode,ranges) {
        if(editing) {
            var edge:RangeEdge?=null;var initial:MatchingFrequencyRange?=null;var startX=0.0
            detectHorizontalDragGestures(onDragStart={point ->
                initial=currentEdit;startX=point.x.toDouble()
                edge=initial?.let {MatchPlotGeometry(samples,residual,size.width.toDouble(),size.height.toDouble(),paddingPx.toDouble()).edgeAt(point.x.toDouble(),it,radiusPx.toDouble())}
            },onHorizontalDrag={change,_ ->
                if(initial!=null) {
                    val geometry=MatchPlotGeometry(samples,residual,size.width.toDouble(),size.height.toDouble(),paddingPx.toDouble())
                    val result=if(moveMode) geometry.move(initial!!,startX,change.position.x.toDouble())
                        else edge?.let {geometry.resize(initial!!,it,change.position.x.toDouble())}
                    result?.let {change.consume();currentResize(it)}
                }
            },onDragCancel={initial?.let(currentResize)})
        } else if(rangeMode) {
            var start=0.0;var end=0.0
            fun updateRange() {
                if(size.width>2*paddingPx && size.height>2*paddingPx) currentOnRange(
                    MatchPlotGeometry(samples,residual,size.width.toDouble(),size.height.toDouble(),paddingPx.toDouble()).selectRange(start,end))
            }
            detectHorizontalDragGestures(onDragStart={start=it.x.toDouble();end=start;currentOnRange(null)},
                onHorizontalDrag={change,_ ->change.consume();end=change.position.x.toDouble();updateRange()},
                onDragEnd={updateRange()},onDragCancel={currentOnRange(null)})
        } else detectTapGestures {tap ->
            if(size.width>2*paddingPx && size.height>2*paddingPx) {
                val geometry=MatchPlotGeometry(samples,residual,size.width.toDouble(),size.height.toDouble(),paddingPx.toDouble())
                currentOnSelect(geometry.hit(tap.x.toDouble(),tap.y.toDouble(),radiusPx.toDouble())?.hz)
            }
        }
    }.pointerInput(samples,residual,paddingPx,editing,ranges) {
        if(editing) detectTapGestures {tap ->
            if(size.width>2*paddingPx && size.height>2*paddingPx) currentExisting(
                MatchPlotGeometry(samples,residual,size.width.toDouble(),size.height.toDouble(),paddingPx.toDouble()).rangeAt(tap.x.toDouble(),ranges))
        }
    }.semantics {
        contentDescription="${if(residual) "Residual" else "Phase difference"} plot: $retained retained, $rejected rejected samples, $minHz to $maxHz Hz"
    }) {
        val pad=8.dp.toPx()
        if(size.width<=2*pad || size.height<=2*pad) return@Canvas
        val geometry=MatchPlotGeometry(samples,residual,size.width.toDouble(),size.height.toDouble(),pad.toDouble())
        fun x(hz:Int)=geometry.x(hz).toFloat().coerceIn(pad,size.width-pad)
        fun y(degrees:Double)=geometry.y(degrees).toFloat()
        if(editing) ranges.forEach {range ->
            val left=x(range.minHz).coerceIn(pad,size.width-pad);val right=x(range.maxHz).coerceIn(pad,size.width-pad)
            if(right>left) drawRect(Color(0xFFFF9800).copy(alpha=.18f),Offset(left,pad),Size(right-left,size.height-2*pad))
        }
        (if(editing) editDraft else draft)?.let {range ->
            drawRect(Color(0xFFFF9800).copy(alpha=.20f),Offset(x(range.minHz),pad),Size(x(range.maxHz)-x(range.minHz),size.height-2*pad))
            drawLine(Color.Yellow,Offset(x(range.minHz),pad),Offset(x(range.minHz),size.height-pad),2.dp.toPx())
            drawLine(Color.Yellow,Offset(x(range.maxHz),pad),Offset(x(range.maxHz),size.height-pad),2.dp.toPx())
        }
        if(low<=0 && high>=0) drawLine(Color.Gray,Offset(pad,y(0.0)),Offset(size.width-pad,y(0.0)))
        // Connect only model predictions within an original contiguous segment, never over a missing gap.
        var previous:MatchFitSample?=null
        samples.filter {it.status==MatchSampleStatus.RETAINED && it.fittedDegrees!=null}.forEach {s ->
            val p=previous
            if(p!=null && p.segment==s.segment) drawLine(Color.White,
                Offset(x(p.hz),y(if(residual) 0.0 else p.fittedDegrees!!)),
                Offset(x(s.hz),y(if(residual) 0.0 else s.fittedDegrees!!)),2.dp.toPx())
            previous=s
        }
        samples.forEach {s ->
            if(s.hz==selectedHz) drawCircle(Color.Yellow,7.dp.toPx(),Offset(x(s.hz),geometry.markerY(s).toFloat()),style=Stroke(2.dp.toPx()))
            val value=observed(s)
            if(value==null) drawLine(if(s.status==MatchSampleStatus.EXCLUDED) Color(0xFFFF9800) else Color.Gray,Offset(x(s.hz),size.height-pad),Offset(x(s.hz),size.height-pad-5.dp.toPx()),2.dp.toPx())
            else {
                val point=Offset(x(s.hz),y(value));val r=3.dp.toPx()
                if(s.status==MatchSampleStatus.REJECTED) {
                    drawLine(Color(0xFFFF5252),point-Offset(r,r),point+Offset(r,r),2.dp.toPx())
                    drawLine(Color(0xFFFF5252),point+Offset(-r,r),point+Offset(r,-r),2.dp.toPx())
                } else drawCircle(Color(0xFF448AFF),r,point)
            }
        }
    }
}
