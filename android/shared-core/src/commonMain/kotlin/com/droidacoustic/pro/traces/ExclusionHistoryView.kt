package com.droidacoustic.pro.traces

/** A state after the described edit, not a command to remove only that edit. */
data class ExclusionHistoryEntry(
    val position:Int,val description:String,val isCurrent:Boolean,
    val sampleExclusions:Int,val rangeExclusions:Int
)

/** Immutable pair-scoped view; retained internally for stale-navigation checks. */
class ExclusionHistoryView internal constructor(
    val baselineId:Int,val candidateId:Int,
    internal val undo:List<ExclusionEdit>,internal val redo:List<ExclusionEdit>,
    internal val current:ExclusionState
) {
    val currentPosition:Int=undo.size
    private val timeline=undo+redo.asReversed()
    val entries:List<ExclusionHistoryEntry> get() {
        val initial=timeline.firstOrNull()?.before ?: current
        return listOf(ExclusionHistoryEntry(0,if(timeline.isEmpty()) "Current saved exclusions (no history)" else "Before available edits",
            currentPosition==0,initial.samples.size,initial.ranges.size))+
            timeline.mapIndexed {index,edit ->ExclusionHistoryEntry(index+1,edit.description(),currentPosition==index+1,edit.after.samples.size,edit.after.ranges.size)}
    }
}
