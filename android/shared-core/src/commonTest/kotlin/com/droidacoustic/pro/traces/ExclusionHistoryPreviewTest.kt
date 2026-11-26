package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class ExclusionHistoryPreviewTest {
    private val range=MatchRangeExclusion(0,1,940,960)
    private val moved=MatchRangeExclusion(0,1,970,990)
    private fun library():SavedTraces {
        val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
        return SavedTraces.empty().captureBatch((0..2).map {id ->SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),
            (900..1100 step 10).associateWith {hz ->PhaseSample(PhaseResponse.wrapped(if(id==0) 0.0 else hz*360*.003))},delayReferenceS=0.0)})
    }
    private fun edited()=library().withRangeExcluded(range,true).replaceRangeExclusion(range,moved)
    @Test fun previewShowsFitAndDifferencesWithoutChangingSavedState() {
        val library=edited();val before=library.toJsonString()
        val preview=ExclusionHistoryPreviews.preview(library,library.exclusionHistory(0,1),1)
        assertEquals(before,library.toJsonString())
        assertEquals(2,preview.current.position);assertEquals(1,preview.target.position)
        assertEquals(setOf(940,950,960),preview.newlyExcludedHz)
        assertEquals(setOf(970,980,990),preview.newlyIncludedHz)
        assertEquals(.003,preview.proposedFit.proposedReferenceS!!,1e-12)
        val candidate=library.navigateExclusionHistory(library.exclusionHistory(0,1),1)
        assertEquals(TraceReferenceMatching.preview(candidate,0,1).fitSamples,preview.proposedFit.fitSamples)
        assertEquals(candidate.toJsonString(),ExclusionHistoryPreviews.apply(library,preview).toJsonString())
        assertEquals(library.traces[1].manualDelayReferenceS,candidate.traces[1].manualDelayReferenceS)
    }
    @Test fun staleHistoryReferencesAndWindowsRejectApplyAtomically() {
        val library=edited();val preview=ExclusionHistoryPreviews.preview(library,library.exclusionHistory(0,1),0)
        val changes=listOf(library.undoExclusion(0,1),library.withDelayReference(1,.001),
            library.withMatchingFrequencyRange(MatchingFrequencyRange(900,1000)),library.remove(1))
        for(changed in changes) {
            val before=changed.toJsonString()
            assertFails {ExclusionHistoryPreviews.apply(changed,preview)}
            assertEquals(before,changed.toJsonString())
        }
        assertFails {ExclusionHistoryPreviews.preview(library,library.exclusionHistory(0,1),9)}
    }
    @Test fun unavailableCandidateFitStillPreviewsAndCanRestoreHistory() {
        val library=library().withRangeExcluded(MatchRangeExclusion(0,1,900,1100),true).undoExclusion(0,1)
        val preview=ExclusionHistoryPreviews.preview(library,library.exclusionHistory(0,1),1)
        assertTrue(preview.currentFit.available);assertFalse(preview.proposedFit.available)
        assertEquals(21,preview.newlyExcludedHz.size)
        val applied=ExclusionHistoryPreviews.apply(library,preview)
        assertEquals(1,applied.exclusionHistory(0,1).currentPosition)
        assertNull(applied.traces[1].manualDelayReferenceS)
    }
    @Test fun saveReopenPreservesAppliedPositionAndUnappliedPreviewHasNoEffect() {
        val original=edited();val preview=ExclusionHistoryPreviews.preview(original,original.exclusionHistory(0,1),1)
        val untouched=SavedTraces.fromJsonString(original.toJsonString())
        assertEquals(2,untouched.exclusionHistory(0,1).currentPosition)
        val applied=ExclusionHistoryPreviews.apply(untouched,preview)
        val reopened=SavedTraces.fromJsonString(applied.toJsonString())
        assertEquals(1,reopened.exclusionHistory(0,1).currentPosition)
        assertTrue(reopened.canUndoExclusion(0,1));assertTrue(reopened.canRedoExclusion(0,1))
        val forward=ExclusionHistoryPreviews.preview(reopened,reopened.exclusionHistory(0,1),2)
        assertEquals(original.excludedFrequencies(0,1),ExclusionHistoryPreviews.apply(reopened,forward).excludedFrequencies(0,1))
    }
    @Test fun overlapsPairIsolationAndMatchingWindowAreRespected() {
        val other=MatchRangeExclusion(0,2,900,920)
        val library=edited().withRangeExcluded(other,true).withSampleExcluded(MatchSampleSelection(0,1,940),true)
            .withMatchingFrequencyRange(MatchingFrequencyRange(1000,1100))
        val preview=ExclusionHistoryPreviews.preview(library,library.exclusionHistory(0,1),1)
        assertEquals(setOf(950,960),preview.newlyExcludedHz)
        assertEquals(preview.currentFit.fitSamples,preview.proposedFit.fitSamples)
        assertEquals(setOf(900,910,920),ExclusionHistoryPreviews.apply(library,preview).excludedFrequencies(0,2))
    }
}
