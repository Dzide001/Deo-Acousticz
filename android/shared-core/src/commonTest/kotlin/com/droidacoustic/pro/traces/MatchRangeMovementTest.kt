package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class MatchRangeMovementTest {
    private val samples=listOf(900,930,970,1000,1100).map {MatchFitSample(it,null,null,null,MatchSampleStatus.MISSING)}
    @Test fun bothPlotsPreserveOffGridWidthAndClampWholeRange() {
        for(residual in listOf(false,true)) {
            val plot=MatchPlotGeometry(samples,residual,220.0,100.0,10.0)
            val range=MatchingFrequencyRange(941,978)
            assertEquals(range,plot.move(range,70.0,70.0))
            assertEquals(MatchingFrequencyRange(964,1001),plot.move(range,70.0,93.0))
            assertEquals(MatchingFrequencyRange(921,958),plot.move(range,70.0,50.0))
            assertEquals(MatchingFrequencyRange(900,937),plot.move(range,70.0,-1000.0))
            assertEquals(MatchingFrequencyRange(1063,1100),plot.move(range,70.0,1000.0))
            assertEquals(MatchingFrequencyRange(942,979),plot.move(range,70.0,70.6))
        }
    }
    @Test fun invalidStartsAndHiddenOrOversizedRangesDoNotMove() {
        val plot=MatchPlotGeometry(samples,false,220.0,100.0,10.0)
        val range=MatchingFrequencyRange(940,980)
        assertNull(plot.move(range,10.0,100.0))
        assertNull(plot.move(range,70.0,Double.NaN))
        assertNull(plot.move(range,Double.POSITIVE_INFINITY,100.0))
        assertNull(plot.move(MatchingFrequencyRange(890,980),70.0,90.0))
        assertNull(plot.move(MatchingFrequencyRange(900,1200),70.0,90.0))
        val full=MatchingFrequencyRange(900,1100)
        assertEquals(full,plot.move(full,100.0,1000.0))
        assertNull(MatchPlotGeometry(emptyList(),false,220.0,100.0,10.0).move(range,70.0,90.0))
    }
    @Test fun movedExclusionPersistsWithoutAffectingOtherExclusionsOrTraceData() {
        val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
        val original=MatchRangeExclusion(0,1,940,960)
        val other=MatchRangeExclusion(0,1,1070,1080)
        val sample=MatchSampleSelection(0,1,940)
        val library=SavedTraces.empty().captureBatch((0..1).map {id ->
            SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),(900..1100 step 10).associateWith {hz ->
                PhaseSample(PhaseResponse.wrapped(if(id==0) 0.0 else 360*hz*.003+37))
            },delayReferenceS=0.0)
        }).withRangeExcluded(original,true).withRangeExcluded(other,true).withSampleExcluded(sample,true)
        val before=TraceReferenceMatching.preview(library,0,1)
        val plot=MatchPlotGeometry(before.fitSamples,false,220.0,100.0,10.0)
        val moved=plot.move(MatchingFrequencyRange(940,960),60.0,100.0)!!
        val replacement=MatchRangeExclusion(0,1,moved.minHz,moved.maxHz)
        val reopened=SavedTraces.fromJsonString(library.replaceRangeExclusion(original,replacement).toJsonString())
        assertEquals(setOf(replacement,other),reopened.rangeExclusions)
        assertEquals(setOf(940,980,990,1000,1070,1080),reopened.excludedFrequencies(0,1))
        assertEquals(library.traces[1].phases,reopened.traces[1].phases)
        assertEquals(.003,TraceReferenceMatching.preview(reopened,0,1).correctionS!!,1e-12)
        assertFails {TraceReferenceMatching.apply(reopened,before)}
    }
}
