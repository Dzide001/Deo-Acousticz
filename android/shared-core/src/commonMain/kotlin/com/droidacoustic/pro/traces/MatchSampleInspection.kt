package com.droidacoustic.pro.traces

import kotlin.math.*

data class MatchSampleSelection(val baselineId:Int,val candidateId:Int,val hz:Int) {
    init {require(baselineId>=0 && candidateId>=0 && baselineId!=candidateId && hz>0)}
    fun resolve(match:TraceReferenceMatch):MatchFitSample? = if(baselineId==match.baselineId && candidateId==match.candidateId)
        match.fitSamples.firstOrNull {it.hz==hz} else null
}

/** Shared pixel geometry for rendering and hit testing; y points down as in platform canvases. */
class MatchPlotGeometry(samples:List<MatchFitSample>,val residual:Boolean,val width:Double,val height:Double,val padding:Double) {
    private val data=samples.sortedBy {it.hz}.toList()
    init {require(listOf(width,height,padding).all {it.isFinite()} && padding>=0 && width>2*padding && height>2*padding)}
    private fun value(s:MatchFitSample)=if(residual) s.residualDegrees else s.observedDegrees
    private val values=data.flatMap {listOfNotNull(value(it),if(residual) it.fittedDegrees?.let {0.0} else it.fittedDegrees)}
    val lowDegrees=(values.minOrNull() ?: 0.0)-2
    val highDegrees=(values.maxOrNull() ?: 0.0)+2
    val minHz=data.firstOrNull()?.hz ?: 0
    val maxHz=data.lastOrNull()?.hz ?: 0
    fun x(hz:Int):Double=if(minHz==maxHz) width/2 else padding+(width-2*padding)*(hz.toDouble()-minHz)/(maxHz.toDouble()-minHz)
    fun y(degrees:Double):Double=padding+(height-2*padding)*(highDegrees-degrees)/(highDegrees-lowDegrees)
    fun markerY(sample:MatchFitSample):Double=value(sample)?.let(::y) ?: (height-padding)
    /** Drag endpoints snap to the nearest captured frequencies; ties choose the lower Hz.
     * Direction is irrelevant. Off-plot endpoints clamp; a one-frequency span is not a range.
     */
    fun selectRange(startX:Double,endX:Double):MatchingFrequencyRange? {
        if(!startX.isFinite() || !endX.isFinite() || data.size<2) return null
        fun nearest(pixel:Double)=data.minWithOrNull(compareBy<MatchFitSample> {abs(x(it.hz)-pixel.coerceIn(padding,width-padding))}.thenBy {it.hz})!!.hz
        val a=nearest(startX);val b=nearest(endX)
        return if(a==b) null else MatchingFrequencyRange(min(a,b),max(a,b))
    }
    /** Overlapping bands select the narrowest, then lowest-frequency range deterministically. */
    fun rangeAt(pixel:Double,ranges:Collection<MatchRangeExclusion>):MatchRangeExclusion? {
        if(!pixel.isFinite() || pixel !in padding..width-padding) return null
        return ranges.filter {pixel>=x(it.minHz) && pixel<=x(it.maxHz)}
            .minWithOrNull(compareBy<MatchRangeExclusion> {it.maxHz.toLong()-it.minHz}.thenBy {it.minHz}.thenBy {it.maxHz})
    }
    fun edgeAt(pixel:Double,range:MatchingFrequencyRange,radius:Double):RangeEdge? {
        require(radius.isFinite() && radius>=0)
        if(!pixel.isFinite() || pixel !in 0.0..width) return null
        return listOf(RangeEdge.LOWER to range.minHz,RangeEdge.UPPER to range.maxHz)
            .filter {it.second in minHz..maxHz && abs(x(it.second)-pixel)<=radius}
            .minByOrNull {abs(x(it.second)-pixel)}?.first
    }
    /** Only the dragged edge snaps; the opposite endpoint remains exact, including off-grid bounds. */
    fun resize(range:MatchingFrequencyRange,edge:RangeEdge,pixel:Double):MatchingFrequencyRange? {
        if(!pixel.isFinite() || data.isEmpty()) return null
        val hz=data.minWithOrNull(compareBy<MatchFitSample> {abs(x(it.hz)-pixel.coerceIn(padding,width-padding))}.thenBy {it.hz})!!.hz
        val low=if(edge==RangeEdge.LOWER) hz else range.minHz
        val high=if(edge==RangeEdge.UPPER) hz else range.maxHz
        return if(low<high) MatchingFrequencyRange(low,high) else null
    }
    /** Whole-band movement uses integer-Hz translation, never independent endpoint snapping. */
    fun move(range:MatchingFrequencyRange,startX:Double,endX:Double):MatchingFrequencyRange? {
        if(!startX.isFinite() || !endX.isFinite() || data.size<2 ||
            range.minHz<minHz || range.maxHz>maxHz || startX !in x(range.minHz)..x(range.maxHz)) return null
        val delta=(endX-startX)/(width-2*padding)*(maxHz.toDouble()-minHz)
        if(!delta.isFinite()) return null
        val shift=delta.coerceIn((minHz-range.minHz).toDouble(),(maxHz-range.maxHz).toDouble()).roundToInt()
        return MatchingFrequencyRange(range.minHz+shift,range.maxHz+shift)
    }
    /** Nearest actual marker within the supplied radius; empty space never invents a sample. */
    fun hit(x:Double,y:Double,radius:Double):MatchFitSample? {
        require(radius.isFinite() && radius>=0)
        if(!x.isFinite() || !y.isFinite() || x !in 0.0..width || y !in 0.0..height) return null
        return data.map {it to hypot(x-this.x(it.hz),y-markerY(it))}
            .filter {it.second<=radius}.minWithOrNull(compareBy<Pair<MatchFitSample,Double>> {it.second}.thenBy {it.first.hz})?.first
    }
}

object MatchSampleInspection {
    fun select(match:TraceReferenceMatch,hz:Int):MatchSampleSelection {
        require(match.fitSamples.any {it.hz==hz}) {"Sample is not in this preview"}
        return MatchSampleSelection(match.baselineId,match.candidateId,hz)
    }
    /** Sorted-frequency navigation includes missing and insufficient samples; ends do not wrap. */
    fun step(match:TraceReferenceMatch,selection:MatchSampleSelection?,direction:Int):MatchSampleSelection? {
        require(direction==1 || direction==-1)
        val samples=match.fitSamples.sortedBy {it.hz}
        if(samples.isEmpty()) return null
        val index=selection?.resolve(match)?.let {s -> samples.indexOfFirst {it.hz==s.hz}}
        val next=if(index==null) (if(direction>0) 0 else samples.lastIndex) else (index+direction).coerceIn(0,samples.lastIndex)
        return select(match,samples[next].hz)
    }
}

/** A plot selection must resolve to actual endpoints in this preview before it becomes an exclusion. */
object MatchRangeSelection {
    fun exclusion(match:TraceReferenceMatch,range:MatchingFrequencyRange):MatchRangeExclusion {
        val frequencies=match.fitSamples.map {it.hz}.toSet()
        require(range.minHz in frequencies && range.maxHz in frequencies) {"Selection is outside this preview"}
        return MatchRangeExclusion(match.baselineId,match.candidateId,range.minHz,range.maxHz)
    }
}

enum class RangeEdge { LOWER, UPPER }
