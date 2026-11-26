package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class MatchRangeEditingTest {
    private val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
    private val original=MatchRangeExclusion(0,1,940,980)
    private fun library()=SavedTraces.empty().captureBatch((0..1).map {id ->
        SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),(900..1100 step 10).associateWith {hz ->
            PhaseSample(PhaseResponse.wrapped(if(id==0) 0.0 else 360*hz*.003+37))
        },delayReferenceS=0.0)
    }).withRangeExcluded(original,true)
    @Test fun selectingOverlapsAndResizingEitherEdgeIsDeterministic() {
        val samples=TraceReferenceMatching.preview(library(),0,1).fitSamples
        val narrow=MatchRangeExclusion(0,1,950,970)
        for(residual in listOf(false,true)) {
            val plot=MatchPlotGeometry(samples,residual,600.0,240.0,12.0)
            assertEquals(narrow,plot.rangeAt(plot.x(960),setOf(original,narrow)))
            assertNull(plot.rangeAt(plot.x(1000),setOf(original)));assertNull(plot.rangeAt(Double.NaN,setOf(original)))
            val bounds=MatchingFrequencyRange(940,980)
            assertEquals(RangeEdge.LOWER,plot.edgeAt(plot.x(940),bounds,12.0))
            assertEquals(RangeEdge.UPPER,plot.edgeAt(plot.x(980),bounds,12.0))
            assertNull(plot.edgeAt(plot.x(960),bounds,12.0))
            assertEquals(MatchingFrequencyRange(930,980),plot.resize(bounds,RangeEdge.LOWER,plot.x(930)))
            assertEquals(MatchingFrequencyRange(940,990),plot.resize(bounds,RangeEdge.UPPER,plot.x(990)))
            assertEquals(MatchingFrequencyRange(900,980),plot.resize(bounds,RangeEdge.LOWER,-20.0))
            assertNull(plot.resize(bounds,RangeEdge.LOWER,plot.x(1000)))
            assertNull(plot.resize(bounds,RangeEdge.UPPER,plot.x(940)))
            assertNull(plot.resize(bounds,RangeEdge.UPPER,Double.NaN))
            assertEquals(MatchingFrequencyRange(930,983),plot.resize(MatchingFrequencyRange(941,983),RangeEdge.LOWER,plot.x(930)))
        }
    }
    @Test fun replacementIsAtomicPreservesOtherExclusionsAndRejectsStaleEdits() {
        val overlap=MatchRangeExclusion(0,1,970,1000)
        val individual=MatchSampleSelection(0,1,950)
        val library=library().withRangeExcluded(overlap,true).withSampleExcluded(individual,true)
        val replacement=MatchRangeExclusion(0,1,920,940)
        val edited=library.replaceRangeExclusion(original,replacement)
        assertEquals(setOf(replacement,overlap),edited.rangeExclusions)
        assertEquals(setOf(920,930,940,950,970,980,990,1000),edited.excludedFrequencies(0,1))
        assertEquals(setOf(original,overlap),library.rangeExclusions)
        assertFails {edited.replaceRangeExclusion(original,replacement)}
        assertFails {library.replaceRangeExclusion(original,MatchRangeExclusion(1,0,920,940))}
        assertEquals(setOf(overlap),library.replaceRangeExclusion(original,overlap).rangeExclusions)
    }
    @Test fun editedRangePersistsAndFitRemainsAccurate() {
        val library=library();val before=TraceReferenceMatching.preview(library,0,1)
        val replacement=MatchRangeExclusion(0,1,940,960)
        val edited=library.replaceRangeExclusion(original,replacement)
        val reopened=SavedTraces.fromJsonString(edited.toJsonString())
        assertEquals(setOf(replacement),reopened.rangeExclusions)
        val match=TraceReferenceMatching.preview(reopened,0,1)
        assertEquals(setOf(940,950,960),match.excludedFrequencies)
        assertTrue(match.available,match.reason);assertEquals(.003,match.correctionS!!,1e-12)
        assertEquals(library.traces[1].phases,reopened.traces[1].phases)
        assertFails {TraceReferenceMatching.apply(reopened,before)}
    }
}
