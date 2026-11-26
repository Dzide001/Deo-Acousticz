package com.droidacoustic.pro.traces

import kotlin.math.*

data class FitOverlayRow(val hz:Int,val current:MatchFitSample?,val proposed:MatchFitSample?) {
    /** Segment IDs are local bookkeeping, not acoustic changes. Ignore sub-microdegree noise. */
    val changes:List<OverlaySampleChange> get() {
        val result=mutableListOf<OverlaySampleChange>()
        if(current?.status!=proposed?.status) result+=OverlaySampleChange.CLASSIFICATION
        fun compare(a:Double?,b:Double?,availability:OverlaySampleChange,value:OverlaySampleChange) {
            if((a==null)!=(b==null)) result+=availability
            else if(a!=null && b!=null && abs(a-b)>1e-6) result+=value
        }
        compare(current?.observedDegrees,proposed?.observedDegrees,OverlaySampleChange.OBSERVED_AVAILABILITY,OverlaySampleChange.OBSERVED_PHASE)
        compare(current?.fittedDegrees,proposed?.fittedDegrees,OverlaySampleChange.FIT_AVAILABILITY,OverlaySampleChange.FITTED_PHASE)
        compare(current?.residualDegrees,proposed?.residualDegrees,OverlaySampleChange.RESIDUAL_AVAILABILITY,OverlaySampleChange.RESIDUAL)
        return result
    }
    val changed:Boolean get()=changes.isNotEmpty()
    val fittedDifferenceDegrees:Double? get()=current?.fittedDegrees?.let {a ->proposed?.fittedDegrees?.minus(a)}
    val residualDifferenceDegrees:Double? get()=current?.residualDegrees?.let {a ->proposed?.residualDegrees?.minus(a)}
}
data class FitOverlayLine(val fromHz:Int,val fromDegrees:Double,val toHz:Int,val toDegrees:Double)

/** Shared comparison, whole-turn alignment, axes and gap-safe fitted lines for both platforms. */
class FitOverlayComparison private constructor(val reason:String?,rows:List<FitOverlayRow>) {
    private val data=rows.toList()
    val compatible:Boolean get()=reason==null
    val rows:List<FitOverlayRow> get()=data.toList()
    fun samples(proposed:Boolean):List<MatchFitSample> = data.mapNotNull {if(proposed) it.proposed else it.current}
    fun geometry(residual:Boolean,width:Double,height:Double,padding:Double)=
        MatchPlotGeometry(samples(false)+samples(true),residual,width,height,padding)
    fun lines(proposed:Boolean,residual:Boolean):List<FitOverlayLine> {
        val result=mutableListOf<FitOverlayLine>();var previous:MatchFitSample?=null
        for(row in data) {
            val s=if(proposed) row.proposed else row.current
            if(s?.fittedDegrees==null || s.segment==null) {previous=null;continue}
            val p=previous
            if(p!=null && p.segment==s.segment) result+=FitOverlayLine(p.hz,if(residual) 0.0 else p.fittedDegrees!!,s.hz,if(residual) 0.0 else s.fittedDegrees)
            previous=s
        }
        return result
    }
    companion object {
        fun compare(current:TraceReferenceMatch,proposed:TraceReferenceMatch):FitOverlayComparison {
            fun unavailable(reason:String)=FitOverlayComparison(reason,emptyList())
            if(current.baselineId!=proposed.baselineId || current.candidateId!=proposed.candidateId ||
                current.baselineFingerprint!=proposed.baselineFingerprint || current.candidateFingerprint!=proposed.candidateFingerprint ||
                current.frequencyRange!=proposed.frequencyRange) return unavailable("Fits use different traces, references or matching windows")
            val a=current.fitSamples.associateBy {it.hz};val b=proposed.fitSamples.associateBy {it.hz}
            val frequencies=(a.keys+b.keys).sorted()
            // Segment intercepts are arbitrary modulo 360°. Shared measured observations constrain
            // whole-turn offsets; aligning both sides avoids false differences when exclusions split a segment.
            val graph=linkedMapOf<String,MutableList<Pair<String,Double>>>()
            fun node(prefix:String,s:MatchFitSample?)=s?.segment?.let {"$prefix$it"}
            a.values.forEach {s ->node("a",s)?.let {graph.getOrPut(it) {mutableListOf()}}}
            b.values.forEach {s ->node("b",s)?.let {graph.getOrPut(it) {mutableListOf()}}}
            for(hz in frequencies) {
                val left=a[hz];val right=b[hz]
                val an=node("a",left);val bn=node("b",right)
                if(an==null || bn==null || left?.observedDegrees==null || right?.observedDegrees==null) continue
                val difference=left.observedDegrees-right.observedDegrees
                val turns=round(difference/360.0)*360.0
                if(abs(difference-turns)>1e-6) return unavailable("Common phase observations do not match")
                graph.getValue(an)+=bn to turns;graph.getValue(bn)+=an to -turns
            }
            val offsets=mutableMapOf<String,Double>()
            for(root in graph.keys) {
                if(root in offsets) continue
                offsets[root]=0.0;val queue=mutableListOf(root);var index=0
                while(index<queue.size) {
                    val key=queue[index++];val offset=offsets.getValue(key)
                    for((next,delta) in graph.getValue(key)) {
                        val expected=offset+delta;val known=offsets[next]
                        if(known==null) {offsets[next]=expected;queue+=next}
                        else if(abs(known-expected)>1e-6) return unavailable("Inconsistent phase branches between fits")
                    }
                }
            }
            fun aligned(prefix:String,s:MatchFitSample?):MatchFitSample? {
                if(s==null) return null
                val offset=node(prefix,s)?.let {offsets[it]} ?: 0.0
                return s.copy(observedDegrees=s.observedDegrees?.plus(offset),fittedDegrees=s.fittedDegrees?.plus(offset))
            }
            return FitOverlayComparison(null,frequencies.map {FitOverlayRow(it,aligned("a",a[it]),aligned("b",b[it]))})
        }
    }
}

enum class OverlaySampleChange { CLASSIFICATION, OBSERVED_AVAILABILITY, OBSERVED_PHASE, FIT_AVAILABILITY, FITTED_PHASE, RESIDUAL_AVAILABILITY, RESIDUAL }
