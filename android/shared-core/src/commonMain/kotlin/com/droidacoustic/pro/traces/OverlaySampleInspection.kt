package com.droidacoustic.pro.traces

import kotlin.math.*

data class OverlayMarker(val hz:Int,val proposed:Boolean,val x:Double,val y:Double,val available:Boolean)

/** Coordinates are shared by rendering and hit testing, including offset missing-data ticks. */
class OverlayInspectionGeometry(
    private val comparison:FitOverlayComparison,private val residual:Boolean,
    val width:Double,val height:Double,val padding:Double,unavailableOffset:Double
) {
    val axis=comparison.geometry(residual,width,height,padding)
    private val offset=unavailableOffset.coerceAtMost(height-2*padding)
    init {require(unavailableOffset.isFinite() && unavailableOffset>=0)}
    fun marker(sample:MatchFitSample,proposed:Boolean):OverlayMarker {
        val value=if(residual) sample.residualDegrees else sample.observedDegrees
        return OverlayMarker(sample.hz,proposed,axis.x(sample.hz),value?.let(axis::y) ?: (height-padding-if(proposed) offset else 0.0),value!=null)
    }
    fun hit(x:Double,y:Double,radius:Double,changedOnly:Boolean=false):FitOverlayRow? {
        require(radius.isFinite() && radius>=0)
        if(!x.isFinite() || !y.isFinite() || x !in 0.0..width || y !in 0.0..height) return null
        val rows=OverlaySampleInspection.rows(comparison,changedOnly)
        val candidates=rows.flatMap {row ->listOfNotNull(row.current?.let {marker(it,false)},row.proposed?.let {marker(it,true)})}
        val hit=candidates.map {it to hypot(it.x-x,it.y-y)}.filter {it.second<=radius}
            .minWithOrNull(compareBy<Pair<OverlayMarker,Double>> {it.second}.thenBy {it.first.hz}.thenBy {it.first.proposed})?.first
        return hit?.let {m ->rows.first {it.hz==m.hz}}
    }
}

object OverlaySampleInspection {
    fun rows(comparison:FitOverlayComparison,changedOnly:Boolean)=comparison.rows.filter {!changedOnly || it.changed}
    fun resolve(comparison:FitOverlayComparison,hz:Int?,changedOnly:Boolean=false)=rows(comparison,changedOnly).firstOrNull {it.hz==hz}
    fun select(comparison:FitOverlayComparison,hz:Int,changedOnly:Boolean=false):FitOverlayRow =
        requireNotNull(resolve(comparison,hz,changedOnly)) {"Sample is not visible in this comparison"}
    fun step(comparison:FitOverlayComparison,hz:Int?,direction:Int,changedOnly:Boolean=false):FitOverlayRow? {
        require(direction==1 || direction==-1)
        val rows=rows(comparison,changedOnly);if(rows.isEmpty()) return null
        val index=rows.indexOfFirst {it.hz==hz}
        return if(index<0) (if(direction>0) rows.first() else rows.last()) else rows[(index+direction).coerceIn(0,rows.lastIndex)]
    }
    /** Keep original axes/topology: filtering never connects across hidden samples. */
    fun lines(comparison:FitOverlayComparison,proposed:Boolean,residual:Boolean,changedOnly:Boolean):List<FitOverlayLine> {
        val visible=rows(comparison,changedOnly).map {it.hz}.toSet()
        return comparison.lines(proposed,residual).filter {it.fromHz in visible && it.toHz in visible}
    }
}
