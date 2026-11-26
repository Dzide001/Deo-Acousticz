package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class ExclusionHistoryNavigationTest {
    private val range=MatchRangeExclusion(0,1,940,960)
    private val moved=MatchRangeExclusion(0,1,970,990)
    private fun library():SavedTraces {
        val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
        return SavedTraces.empty().captureBatch((0..2).map {id ->SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),
            (900..1100 step 10).associateWith {hz ->PhaseSample(PhaseResponse.wrapped(if(id==0) 0.0 else hz*360*.003))},delayReferenceS=0.0)})
    }
    private fun edited()=library().withRangeExcluded(range,true).replaceRangeExclusion(range,moved).withSampleExcluded(MatchSampleSelection(0,1,1000),true)
    @Test fun timelineNavigationMatchesSequentialUndoRedoAndPreservesOtherPairs() {
        val other=MatchRangeExclusion(0,2,1020,1040)
        val original=edited().withRangeExcluded(other,true)
        val view=original.exclusionHistory(0,1)
        assertEquals(3,view.currentPosition);assertEquals(listOf(0,1,2,3),view.entries.map {it.position})
        assertEquals(1,view.entries[3].sampleExclusions);assertEquals(1,view.entries[3].rangeExclusions)
        val earlier=original.navigateExclusionHistory(view,1)
        assertEquals(original.undoExclusion(0,1).undoExclusion(0,1).toJsonString(),earlier.toJsonString())
        assertEquals(setOf(range,other),earlier.rangeExclusions)
        assertEquals(1,earlier.exclusionHistory(0,1).currentPosition)
        val latest=earlier.navigateExclusionHistory(earlier.exclusionHistory(0,1),3)
        assertEquals(original.exclusionHistory(0,1).entries,latest.exclusionHistory(0,1).entries)
        assertEquals(original.exclusionHistory(0,2).entries,latest.exclusionHistory(0,2).entries)
        assertEquals(original.rangeExclusions,latest.rangeExclusions)
        assertEquals(original.manualExclusions,latest.manualExclusions)
        assertEquals(original.traces[1].phases,earlier.traces[1].phases)
        assertSame(original,original.navigateExclusionHistory(view,3))
    }
    @Test fun reopeningPreservesCurrentPositionAndAllowsForwardNavigation() {
        val original=edited();val earlier=original.navigateExclusionHistory(original.exclusionHistory(0,1),1)
        val reopened=SavedTraces.fromJsonString(earlier.toJsonString())
        assertEquals(earlier.exclusionHistory(0,1).entries,reopened.exclusionHistory(0,1).entries)
        val redone=reopened.navigateExclusionHistory(reopened.exclusionHistory(0,1),3)
        assertEquals(original.excludedFrequencies(0,1),redone.excludedFrequencies(0,1))
        assertEquals(.003,TraceReferenceMatching.preview(redone,0,1).proposedReferenceS!!,1e-12)
        assertEquals("Exclude sample at 1000 Hz",redone.undoExclusionDescription(0,1))
        assertFalse(redone.canRedoExclusion(0,1))
    }
    @Test fun staleViewsInvalidPositionsAndDeletedTracesFailAtomically() {
        val original=edited();val view=original.exclusionHistory(0,1);val json=original.toJsonString()
        assertFails {original.navigateExclusionHistory(view,-1)};assertFails {original.navigateExclusionHistory(view,4)}
        assertEquals(json,original.toJsonString())
        assertFails {original.undoExclusion(0,1).navigateExclusionHistory(view,0)}
        assertFails {original.remove(1).navigateExclusionHistory(view,0)}
        assertFails {original.exclusionHistory(0,0)}
        val unrelated=original.withRangeExcluded(MatchRangeExclusion(0,2,900,920),true).withDelayReference(1,.001)
        val result=unrelated.navigateExclusionHistory(view,0)
        assertTrue(result.excludedFrequencies(0,1).isEmpty());assertEquals(.001,result.traces[1].manualDelayReferenceS!!,0.0)
        assertEquals(setOf(900,910,920),result.excludedFrequencies(0,2))
    }
    @Test fun branchingAndBoundedHistoryExposeOnlyAvailableStates() {
        val original=edited();val base=original.navigateExclusionHistory(original.exclusionHistory(0,1),0)
        val branch=base.withRangeExcluded(MatchRangeExclusion(0,1,1020,1040),true)
        assertEquals(2,branch.exclusionHistory(0,1).entries.size);assertFalse(branch.canRedoExclusion(0,1))
        var bounded=library()
        repeat(70) {bounded=bounded.withRangeExcluded(range,it%2==0)}
        val view=bounded.exclusionHistory(0,1)
        assertEquals(65,view.entries.size);assertEquals("Before available edits",view.entries[0].description)
        val oldest=bounded.navigateExclusionHistory(view,0)
        assertFalse(oldest.canUndoExclusion(0,1));assertTrue(oldest.canRedoExclusion(0,1))
        assertEquals(bounded.toJsonString(),oldest.navigateExclusionHistory(oldest.exclusionHistory(0,1),64).toJsonString())
    }
    @Test fun emptyHistoryShowsOneCurrentState() {
        val library=library();val view=library.exclusionHistory(0,1)
        assertEquals(1,view.entries.size);assertTrue(view.entries.single().isCurrent)
        assertEquals("Current saved exclusions (no history)",view.entries.single().description)
        assertSame(library,library.navigateExclusionHistory(view,0))
    }
}
