package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class MatchRangeSelectionTest {
    private val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
    private fun library()=SavedTraces.empty().captureBatch((0..1).map {id ->
        SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),(900..1100 step 10).associateWith {hz ->
            if(hz==1000) PhaseSample(null,"missing") else PhaseSample(PhaseResponse.wrapped(if(id==0) 0.0 else 360*hz*.003+37))
        },delayReferenceS=0.0)
    })
    @Test fun bothPlotsSnapClampAndNormalizeDirectionIncludingMissingSamples() {
        val match=TraceReferenceMatching.preview(library(),0,1)
        for(residual in listOf(false,true)) {
            val plot=MatchPlotGeometry(match.fitSamples,residual,600.0,240.0,12.0)
            assertEquals(MatchingFrequencyRange(940,1000),plot.selectRange(plot.x(940),plot.x(1000)))
            assertEquals(MatchingFrequencyRange(940,1000),plot.selectRange(plot.x(1000),plot.x(940)))
            assertEquals(MatchingFrequencyRange(900,1100),plot.selectRange(-100.0,900.0))
            assertNull(plot.selectRange(plot.x(1000),plot.x(1000)+1))
            assertNull(plot.selectRange(Double.NaN,100.0));assertNull(plot.selectRange(100.0,Double.POSITIVE_INFINITY))
        }
    }
    @Test fun tiesChooseLowerFrequencyAndDegeneratePlotsCannotSelectRange() {
        val samples=listOf(100,200,300).map {MatchFitSample(it,null,null,null,MatchSampleStatus.MISSING)}
        val plot=MatchPlotGeometry(samples,false,220.0,100.0,10.0)
        assertEquals(MatchingFrequencyRange(100,200),plot.selectRange(60.0,160.0))
        assertNull(MatchPlotGeometry(emptyList(),false,100.0,100.0,0.0).selectRange(0.0,100.0))
        assertNull(MatchPlotGeometry(samples.take(1),false,100.0,100.0,0.0).selectRange(0.0,100.0))
    }
    @Test fun selectedExclusionPreservesAccuracyAndSurvivesReopen() {
        val library=library();val match=TraceReferenceMatching.preview(library,0,1)
        val plot=MatchPlotGeometry(match.fitSamples,false,600.0,240.0,12.0)
        val range=MatchRangeSelection.exclusion(match,plot.selectRange(plot.x(940),plot.x(960))!!)
        val edited=library.withRangeExcluded(range,true)
        val reopened=SavedTraces.fromJsonString(edited.toJsonString())
        assertEquals(setOf(range),reopened.rangeExclusions)
        val result=TraceReferenceMatching.preview(reopened,0,1)
        assertEquals(setOf(940,950,960),result.excludedFrequencies)
        assertTrue(result.available,result.reason);assertEquals(.003,result.correctionS!!,1e-12)
        assertEquals(library.traces[1].phases,TraceReferenceMatching.apply(reopened,result).traces[1].phases)
        assertFails {TraceReferenceMatching.apply(reopened,match)}
        assertFails {MatchRangeSelection.exclusion(match,MatchingFrequencyRange(941,960))}
        assertFails {MatchRangeSelection.exclusion(match,MatchingFrequencyRange(800,960))}
    }
}
