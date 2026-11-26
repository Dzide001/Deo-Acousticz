package com.droidacoustic.pro.traces

/** A transient calculation; only apply changes saved exclusions/history. */
class ExclusionHistoryPreview internal constructor(
    internal val view:ExclusionHistoryView,
    val target:ExclusionHistoryEntry,
    val currentFit:TraceReferenceMatch,
    val proposedFit:TraceReferenceMatch,
    added:Set<Int>,removed:Set<Int>,
    internal val sourceFingerprint:String
) {
    val overlay:FitOverlayComparison=FitOverlayComparison.compare(currentFit,proposedFit)
    val current:ExclusionHistoryEntry=view.entries[view.currentPosition]
    private val addedHz=added.toSet()
    private val removedHz=removed.toSet()
    /** Captured frequencies across the full trace, not just the active matching window. */
    val newlyExcludedHz:Set<Int> get()=addedHz.toSet()
    val newlyIncludedHz:Set<Int> get()=removedHz.toSet()
}

object ExclusionHistoryPreviews {
    private fun fingerprint(library:SavedTraces)=TraceComparisons.fingerprint(library.withInspectedSample(null).withOverlayChangedOnly(false).toJsonString())
    fun preview(library:SavedTraces,view:ExclusionHistoryView,position:Int):ExclusionHistoryPreview {
        val proposed=library.navigateExclusionHistory(view,position)
        val a=view.baselineId;val b=view.candidateId
        val before=library.excludedFrequencies(a,b);val after=proposed.excludedFrequencies(a,b)
        return ExclusionHistoryPreview(view,view.entries[position],TraceReferenceMatching.preview(library,a,b),
            TraceReferenceMatching.preview(proposed,a,b),after-before,before-after,fingerprint(library))
    }
    /** History, traces, references or settings changed since preview: reject without mutation. */
    fun apply(library:SavedTraces,preview:ExclusionHistoryPreview):SavedTraces {
        require(fingerprint(library)==preview.sourceFingerprint) {"Project traces or history changed; preview again"}
        return library.navigateExclusionHistory(preview.view,preview.target.position)
    }
}
