package com.droidacoustic.pro.traces

import kotlinx.serialization.json.*

internal data class ExclusionState(val samples:Set<MatchSampleSelection>,val ranges:Set<MatchRangeExclusion>)
internal data class ExclusionEdit(val a:Int,val b:Int,val before:ExclusionState,val after:ExclusionState) {
    /** Derived from saved states, so labels remain truthful for legacy and deduplicated edits. */
    fun description():String {
        val addedSamples=after.samples-before.samples;val removedSamples=before.samples-after.samples
        val addedRanges=after.ranges-before.ranges;val removedRanges=before.ranges-after.ranges
        fun bounds(r:MatchRangeExclusion)="${r.minHz}–${r.maxHz} Hz"
        if(addedRanges.isEmpty() && removedRanges.isEmpty()) {
            if(addedSamples.size==1 && removedSamples.isEmpty()) return "Exclude sample at ${addedSamples.single().hz} Hz"
            if(removedSamples.size==1 && addedSamples.isEmpty()) return "Include sample at ${removedSamples.single().hz} Hz"
        }
        if(addedSamples.isEmpty() && removedSamples.isEmpty()) {
            if(addedRanges.size==1 && removedRanges.isEmpty()) return "Exclude range ${bounds(addedRanges.single())}"
            if(removedRanges.size==1 && addedRanges.isEmpty()) return "Remove range exclusion ${bounds(removedRanges.single())}"
            if(addedRanges.size==1 && removedRanges.size==1) {
                val old=removedRanges.single();val next=addedRanges.single()
                val verb=if(old.maxHz-old.minHz==next.maxHz-next.minHz) "Move" else "Resize"
                return "$verb range ${bounds(old)} → ${bounds(next)}"
            }
        }
        // Imported history may contain a compound change that has no single-edit description.
        val changes=listOf(
            Triple(addedSamples.size,"sample excluded","samples excluded"),Triple(removedSamples.size,"sample included","samples included"),
            Triple(addedRanges.size,"range exclusion added","range exclusions added"),Triple(removedRanges.size,"range exclusion removed","range exclusions removed"))
        return "Change exclusions ("+changes.filter {it.first>0}.joinToString(", ") {"${it.first} ${if(it.first==1) it.second else it.third}"}+")"
    }
    init {
        require(a>=0 && b>=0 && a!=b && before!=after)
        listOf(before,after).forEach {s ->
            require(s.samples.all {it.baselineId==a && it.candidateId==b})
            require(s.ranges.all {it.baselineId==a && it.candidateId==b})
        }
    }
}
internal fun historyJson(edits:List<ExclusionEdit>)=buildJsonArray {edits.forEach {e ->add(buildJsonObject {
    put("a",e.a);put("b",e.b)
    fun state(s:ExclusionState)=buildJsonObject {
        put("samples",buildJsonArray {s.samples.sortedBy {it.hz}.forEach {add(it.hz)}})
        put("ranges",buildJsonArray {s.ranges.sortedWith(compareBy<MatchRangeExclusion> {it.minHz}.thenBy {it.maxHz}).forEach {r ->add(buildJsonArray {add(r.minHz);add(r.maxHz)})}})
    }
    put("before",state(e.before));put("after",state(e.after))
})}}
internal fun parseHistory(value:JsonElement?):List<ExclusionEdit> {
    if(value==null) return emptyList()
    val list=value.jsonArray;require(list.size<=64)
    return list.map {item ->val e=item.jsonObject;val a=e.getValue("a").jsonPrimitive.int;val b=e.getValue("b").jsonPrimitive.int
        fun state(key:String):ExclusionState {
            val s=e.getValue(key).jsonObject
            val samples=s.getValue("samples").jsonArray;val ranges=s.getValue("ranges").jsonArray
            require(samples.size<=8448 && ranges.size<=1024)
            return ExclusionState(samples.map {MatchSampleSelection(a,b,it.jsonPrimitive.int)}.toSet(),ranges.map {
                val r=it.jsonArray;require(r.size==2);MatchRangeExclusion(a,b,r[0].jsonPrimitive.int,r[1].jsonPrimitive.int)
            }.toSet())
        }
        ExclusionEdit(a,b,state("before"),state("after"))
    }
}
