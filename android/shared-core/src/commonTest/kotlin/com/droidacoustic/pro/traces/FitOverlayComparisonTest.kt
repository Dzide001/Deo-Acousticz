package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class FitOverlayComparisonTest {
    private val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
    private fun library()=SavedTraces.empty().captureBatch((0..1).map {id ->SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),
        (900..1100 step 10).associateWith {hz ->if(hz==1000) PhaseSample(null,"missing") else PhaseSample(PhaseResponse.wrapped(if(id==0) 0.0 else hz*360*.003+37))},delayReferenceS=0.0)})
    @Test fun comparisonAlignsSplitSegmentsWithoutChangingResidualsOrSourceData() {
        val library=library().withRangeExcluded(MatchRangeExclusion(0,1,940,960),true)
        val p=ExclusionHistoryPreviews.preview(library,library.exclusionHistory(0,1),0)
        assertTrue(p.overlay.compatible,p.overlay.reason)
        val common=p.overlay.rows.filter {it.fittedDifferenceDegrees!=null}
        assertTrue(common.isNotEmpty());common.forEach {assertEquals(0.0,it.fittedDifferenceDegrees!!,1e-8)}
        for(proposed in listOf(false,true)) {
            val original=(if(proposed) p.proposedFit else p.currentFit).fitSamples.associateBy {it.hz}
            p.overlay.samples(proposed).forEach {s ->
                val before=original.getValue(s.hz)
                if(before.residualDegrees!=null) assertEquals(before.residualDegrees!!,s.residualDegrees!!,1e-8)
                assertEquals(before.status,s.status)
            }
        }
    }
    @Test fun missingExcludedAndInsufficientSamplesStayAbsentAndLinesNeverBridgeGaps() {
        val library=library().withRangeExcluded(MatchRangeExclusion(0,1,940,980),true)
        val p=ExclusionHistoryPreviews.preview(library,library.exclusionHistory(0,1),0)
        val missing=p.overlay.rows.single {it.hz==1000}
        assertNull(missing.fittedDifferenceDegrees);assertNull(missing.residualDifferenceDegrees)
        assertEquals(MatchSampleStatus.MISSING,missing.current!!.status)
        val excluded=p.overlay.rows.single {it.hz==950}
        assertNull(excluded.current!!.observedDegrees);assertNotNull(excluded.proposed!!.observedDegrees)
        assertNull(excluded.fittedDifferenceDegrees)
        assertEquals(MatchSampleStatus.INSUFFICIENT,p.overlay.rows.single {it.hz==990}.current!!.status)
        for(proposed in listOf(false,true)) for(residual in listOf(false,true)) {
            val lines=p.overlay.lines(proposed,residual)
            assertTrue(lines.none {it.fromHz<1000 && it.toHz>1000})
            if(!proposed) assertTrue(lines.none {it.fromHz<940 && it.toHz>940})
        }
        val geometry=p.overlay.geometry(false,600.0,240.0,12.0)
        (p.overlay.samples(false)+p.overlay.samples(true)).forEach {s ->
            listOfNotNull(s.observedDegrees,s.fittedDegrees).forEach {assertTrue(geometry.y(it) in 12.0..228.0)}
        }
    }
    @Test fun incompatibleReferencesAreRejectedAndUnavailableFitsRemainRepresentable() {
        val library=library();val fit=TraceReferenceMatching.preview(library,0,1)
        assertFalse(FitOverlayComparison.compare(fit,TraceReferenceMatching.preview(library.withDelayReference(1,.001),0,1)).compatible)
        val excluded=library.withRangeExcluded(MatchRangeExclusion(0,1,900,1100),true)
        val p=ExclusionHistoryPreviews.preview(excluded,excluded.exclusionHistory(0,1),0)
        assertFalse(p.currentFit.available);assertTrue(p.overlay.compatible)
        assertTrue(p.overlay.lines(false,false).isEmpty());assertTrue(p.overlay.lines(true,false).isNotEmpty())
        assertTrue(p.overlay.rows.all {it.fittedDifferenceDegrees==null})
    }
    @Test fun overlayComparisonsSurviveHistorySaveReopenWithoutMutatingTheProject() {
        val library=library().withRangeExcluded(MatchRangeExclusion(0,1,940,960),true)
        val before=library.toJsonString();val p=ExclusionHistoryPreviews.preview(library,library.exclusionHistory(0,1),0)
        val reopened=SavedTraces.fromJsonString(before)
        val next=ExclusionHistoryPreviews.preview(reopened,reopened.exclusionHistory(0,1),0)
        assertEquals(p.overlay.rows,next.overlay.rows)
        assertEquals(p.overlay.lines(true,false),next.overlay.lines(true,false))
        assertEquals(before,library.toJsonString())
    }
}
