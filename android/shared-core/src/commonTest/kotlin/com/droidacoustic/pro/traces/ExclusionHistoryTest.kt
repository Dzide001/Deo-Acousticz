package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlinx.serialization.json.*
import kotlin.test.*

class ExclusionHistoryTest {
    private val range=MatchRangeExclusion(0,1,940,960)
    private val moved=MatchRangeExclusion(0,1,970,990)
    private fun library():SavedTraces {
        val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
        return SavedTraces.empty().captureBatch((0..2).map {id ->SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),
            (900..1100 step 10).associateWith {hz ->PhaseSample(PhaseResponse.wrapped(if(id==0) 0.0 else hz*360*.003))},delayReferenceS=0.0)})
    }
    @Test fun undoRedoIncludesSampleRangeMoveAndRemovalWithPairIsolation() {
        val sample=MatchSampleSelection(0,1,1000)
        val other=MatchRangeExclusion(0,2,1020,1040)
        val edited=library().withRangeExcluded(range,true).withRangeExcluded(other,true)
            .replaceRangeExclusion(range,moved).withSampleExcluded(sample,true).withRangeExcluded(moved,false)
        val undo=edited.undoExclusion(0,1).undoExclusion(0,1).undoExclusion(0,1)
        assertEquals(setOf(range,other),undo.rangeExclusions);assertTrue(undo.manualExclusions.isEmpty())
        val redo=undo.redoExclusion(0,1).redoExclusion(0,1).redoExclusion(0,1)
        assertEquals(edited.toJsonString(),redo.toJsonString())
        assertEquals(setOf(other),undo.undoExclusion(0,1).rangeExclusions)
        assertTrue(undo.canUndoExclusion(0,2));assertFalse(undo.canUndoExclusion(1,0))
    }
    @Test fun historyAndRedoSurviveReopenWithoutChangingReferencesOrRawData() {
        val start=library().withRangeExcluded(range,true).replaceRangeExclusion(range,moved).withDelayReference(1,.001)
        val undone=start.undoExclusion(0,1).withMatchingFrequencyRange(MatchingFrequencyRange(900,1100))
        val reopened=SavedTraces.fromJsonString(undone.toJsonString())
        assertTrue(reopened.canRedoExclusion(0,1));assertTrue(reopened.canUndoExclusion(0,1))
        val redone=reopened.redoExclusion(0,1)
        assertEquals(setOf(moved),redone.rangeExclusions)
        assertEquals(.001,redone.traces[1].manualDelayReferenceS!!,0.0)
        assertEquals(start.traces[1].phases,redone.traces[1].phases)
        val fit=TraceReferenceMatching.preview(redone,0,1)
        assertEquals(.003,fit.proposedReferenceS!!,1e-12)
        assertFails {TraceReferenceMatching.apply(redone,TraceReferenceMatching.preview(reopened,0,1))}
    }
    @Test fun noOpsPreserveRedoNewEditsClearOnlyTheirPairAndDeletionDropsHistory() {
        val other=MatchRangeExclusion(0,2,1000,1020)
        val undone=library().withRangeExcluded(range,true).withRangeExcluded(other,true).undoExclusion(0,1).undoExclusion(0,2)
        assertTrue(undone.withRangeExcluded(range,false).canRedoExclusion(0,1))
        val branch=undone.withRangeExcluded(moved,true)
        assertFalse(branch.canRedoExclusion(0,1));assertTrue(branch.canRedoExclusion(0,2))
        val deleted=branch.remove(1)
        assertFalse(deleted.canUndoExclusion(0,1));assertTrue(deleted.canRedoExclusion(0,2))
        assertSame(deleted,deleted.undoExclusion(0,1))
    }
    @Test fun legacyImportsAndMalformedHistoryAndBoundedRetention() {
        val edited=library().withRangeExcluded(range,true)
        val json=Json.parseToJsonElement(edited.toJsonString()).jsonObject
        val legacy=SavedTraces.fromJsonString(JsonObject(json-filterOfHistory()).toString())
        assertFalse(legacy.canUndoExclusion(0,1));assertEquals(setOf(range),legacy.rangeExclusions)
        val bad=edited.toJsonString().replace("\"after\":{\"samples\":[],\"ranges\":[[940,960]]}","\"after\":{\"samples\":[],\"ranges\":[[941,960]]}")
        assertNotEquals(edited.toJsonString(),bad);assertFails {SavedTraces.fromJsonString(bad)}
        var many=library()
        repeat(70) {many=many.withRangeExcluded(range,it%2==0)}
        var count=0
        while(many.canUndoExclusion(0,1)) {many=many.undoExclusion(0,1);count++}
        assertEquals(64,count)
        assertTrue(SavedTraces.fromJsonString(many.toJsonString()).canRedoExclusion(0,1))
    }
    private fun filterOfHistory()=setOf("exclusionUndo","exclusionRedo")
}
