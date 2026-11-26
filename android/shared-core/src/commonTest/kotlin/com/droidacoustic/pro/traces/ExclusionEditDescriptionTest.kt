package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlinx.serialization.json.*
import kotlin.test.*

class ExclusionEditDescriptionTest {
    private val range=MatchRangeExclusion(0,1,940,960)
    private fun library():SavedTraces {
        val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
        return SavedTraces.empty().captureBatch((0..2).map {id ->SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),
            (900..1100 step 10).associateWith {PhaseSample(0.0)},delayReferenceS=0.0)})
    }
    @Test fun sampleAndRangeActionsDescribeExactFrequencies() {
        val sample=MatchSampleSelection(0,1,1000)
        var saved=library()
        assertNull(saved.undoExclusionDescription(0,1));assertNull(saved.redoExclusionDescription(0,1))
        saved=saved.withSampleExcluded(sample,true)
        assertEquals("Exclude sample at 1000 Hz",saved.undoExclusionDescription(0,1))
        saved=saved.withSampleExcluded(sample,false)
        assertEquals("Include sample at 1000 Hz",saved.undoExclusionDescription(0,1))
        saved=saved.withRangeExcluded(range,true)
        assertEquals("Exclude range 940–960 Hz",saved.undoExclusionDescription(0,1))
        saved=saved.withRangeExcluded(range,false)
        assertEquals("Remove range exclusion 940–960 Hz",saved.undoExclusionDescription(0,1))
    }
    @Test fun moveResizeAndDeduplicationDescribeActualStateChanges() {
        val saved=library().withRangeExcluded(range,true)
        val moved=MatchRangeExclusion(0,1,970,990)
        val resized=MatchRangeExclusion(0,1,930,980)
        assertEquals("Move range 940–960 Hz → 970–990 Hz",saved.replaceRangeExclusion(range,moved).undoExclusionDescription(0,1))
        assertEquals("Resize range 940–960 Hz → 930–980 Hz",saved.replaceRangeExclusion(range,resized).undoExclusionDescription(0,1))
        assertEquals("Exclude range 940–960 Hz",saved.replaceRangeExclusion(range,range).undoExclusionDescription(0,1))
        val duplicate=saved.withRangeExcluded(moved,true).replaceRangeExclusion(range,moved)
        assertEquals("Remove range exclusion 940–960 Hz",duplicate.undoExclusionDescription(0,1))
    }
    @Test fun historyNavigationBranchingAndPairIsolationKeepLabelsAccurate() {
        val other=MatchRangeExclusion(0,2,1000,1020)
        val edited=library().withRangeExcluded(range,true).withRangeExcluded(other,true).withRangeExcluded(range,false)
        val undone=edited.undoExclusion(0,1)
        assertEquals("Exclude range 940–960 Hz",undone.undoExclusionDescription(0,1))
        assertEquals("Remove range exclusion 940–960 Hz",undone.redoExclusionDescription(0,1))
        assertEquals("Exclude range 1000–1020 Hz",undone.undoExclusionDescription(0,2))
        assertNull(undone.undoExclusionDescription(1,0))
        assertEquals(edited.undoExclusionDescription(0,1),undone.redoExclusion(0,1).undoExclusionDescription(0,1))
        assertEquals(undone.redoExclusionDescription(0,1),undone.withRangeExcluded(range,true).redoExclusionDescription(0,1))
        assertNull(undone.withSampleExcluded(MatchSampleSelection(0,1,1000),true).redoExclusionDescription(0,1))
        assertNull(undone.remove(1).redoExclusionDescription(0,1))
    }
    @Test fun descriptionsAreDerivedAfterReopenIncludingExistingHistoryFormat() {
        val edited=library().withRangeExcluded(range,true).replaceRangeExclusion(range,MatchRangeExclusion(0,1,970,990)).undoExclusion(0,1)
        val reopened=SavedTraces.fromJsonString(edited.toJsonString())
        assertEquals("Exclude range 940–960 Hz",reopened.undoExclusionDescription(0,1))
        assertEquals("Move range 940–960 Hz → 970–990 Hz",reopened.redoExclusionDescription(0,1))
        assertEquals(edited.toJsonString(),reopened.toJsonString())
        val old=Json.parseToJsonElement(edited.toJsonString()).jsonObject
        val noHistory=SavedTraces.fromJsonString(JsonObject(old-setOf("exclusionUndo","exclusionRedo")).toString())
        assertNull(noHistory.undoExclusionDescription(0,1));assertNull(noHistory.redoExclusionDescription(0,1))
    }
    @Test fun compoundImportedChangesUseDeterministicCountSummary() {
        val empty=ExclusionState(emptySet(),emptySet())
        val samples=setOf(MatchSampleSelection(0,1,1000),MatchSampleSelection(0,1,900))
        val next=ExclusionState(samples,setOf(range))
        val edit=ExclusionEdit(0,1,empty,next)
        val label="Change exclusions (2 samples excluded, 1 range exclusion added)"
        assertEquals(label,edit.description())
        assertEquals(label,parseHistory(historyJson(listOf(edit))).single().description())
    }
}
