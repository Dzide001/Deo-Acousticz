package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class OverlayInspectionBatchTest {
    private fun fit(samples:List<MatchFitSample>)=TraceReferenceMatch(0,1,0.0,0.0,samples.size,1,0.0,1.0,null,"a","b",fitSamples=samples)
    private fun sample(hz:Int,observed:Double?,fitted:Double?,status:MatchSampleStatus=MatchSampleStatus.RETAINED,segment:Int?=0)=MatchFitSample(hz,observed,fitted,segment,status)
    private fun comparison():FitOverlayComparison {
        val a=listOf(sample(100,10.0,9.0),sample(200,20.0,19.0),sample(300,null,null,MatchSampleStatus.MISSING,null),
            sample(400,null,null,MatchSampleStatus.MISSING,null),sample(500,null,null,MatchSampleStatus.EXCLUDED,null),sample(600,400.0,399.0,segment=1))
        val b=listOf(sample(100,10.0,8.0),sample(200,20.0,19.0+1e-8),sample(300,20.0,20.0),
            sample(400,null,null,MatchSampleStatus.MISSING,null),sample(500,30.0,20.0,MatchSampleStatus.REJECTED),sample(600,40.0,39.0,segment=1))
        return FitOverlayComparison.compare(fit(a),fit(b))
    }
    @Test fun differencesUsePreviewMinusCurrentAndMissingValuesStayUnavailable() {
        val comparison=comparison();assertTrue(comparison.compatible)
        val row=OverlaySampleInspection.select(comparison,100)
        assertEquals(-1.0,row.fittedDifferenceDegrees!!,0.0);assertEquals(1.0,row.residualDifferenceDegrees!!,0.0)
        assertEquals(setOf(OverlaySampleChange.FITTED_PHASE,OverlaySampleChange.RESIDUAL),row.changes.toSet())
        val missing=OverlaySampleInspection.select(comparison,300)
        assertNull(missing.fittedDifferenceDegrees);assertNull(missing.residualDifferenceDegrees)
        assertTrue(OverlaySampleChange.CLASSIFICATION in missing.changes)
        assertTrue(OverlaySampleChange.FIT_AVAILABILITY in missing.changes)
        assertFalse(OverlaySampleInspection.select(comparison,400).changed)
        assertFalse(OverlaySampleInspection.select(comparison,600).changed)
    }
    @Test fun filteringNavigationAndEmptyResultsAreDeterministic() {
        val c=comparison()
        assertEquals(listOf(100,300,500),OverlaySampleInspection.rows(c,true).map {it.hz})
        assertNull(OverlaySampleInspection.resolve(c,200,true))
        assertFails {OverlaySampleInspection.select(c,200,true)}
        assertEquals(300,OverlaySampleInspection.step(c,100,1,true)!!.hz)
        assertEquals(100,OverlaySampleInspection.step(c,100,-1,true)!!.hz)
        assertEquals(500,OverlaySampleInspection.step(c,null,-1,true)!!.hz)
        assertEquals(500,OverlaySampleInspection.step(c,500,1,true)!!.hz)
        val unchanged=FitOverlayComparison.compare(fit(listOf(sample(100,0.0,0.0))),fit(listOf(sample(100,0.0,0.0))))
        assertTrue(OverlaySampleInspection.rows(unchanged,true).isEmpty())
        assertNull(OverlaySampleInspection.step(unchanged,null,1,true))
        assertFails {OverlaySampleInspection.step(c,null,0)}
    }
    @Test fun hitTestingMatchesBothSeriesIncludingMissingTicksOnBothPlots() {
        val c=comparison()
        for(residual in listOf(false,true)) {
            val g=OverlayInspectionGeometry(c,residual,700.0,260.0,12.0,10.0)
            for(proposed in listOf(false,true)) for(hz in listOf(100,300,500)) {
                val s=c.samples(proposed).single {it.hz==hz};val point=g.marker(s,proposed)
                assertEquals(hz,g.hit(point.x,point.y,1.0,true)!!.hz)
            }
            val hidden=g.marker(c.samples(false).single {it.hz==200},false)
            assertNull(g.hit(hidden.x,hidden.y,.01,true))
            assertNull(g.hit(-1.0,0.0,20.0));assertNull(g.hit(Double.NaN,0.0,20.0))
            assertNull(g.hit(1.0,1.0,.1));assertFails {g.hit(0.0,0.0,-1.0)}
            assertFails {OverlayInspectionGeometry(c,residual,10.0,10.0,6.0,7.0)}
        }
        val samples=listOf(sample(100,0.0,0.0),sample(200,0.0,0.0))
        val tied=OverlayInspectionGeometry(FitOverlayComparison.compare(fit(samples),fit(samples)),false,100.0,100.0,10.0,7.0)
        assertEquals(100,tied.hit(50.0,50.0,40.0)!!.hz)
    }
    @Test fun filteredLinesNeverBridgeHiddenRowsAndSegmentIdsDoNotCountAsChanges() {
        val c=comparison();val visible=OverlaySampleInspection.rows(c,true).map {it.hz}.toSet()
        for(proposed in listOf(false,true)) for(residual in listOf(false,true)) {
            val lines=OverlaySampleInspection.lines(c,proposed,residual,true)
            assertTrue(lines.all {it.fromHz in visible && it.toHz in visible})
            assertTrue(lines.none {it.fromHz==100 && it.toHz==300})
        }
        val current=sample(100,10.0,9.0,segment=0);val proposed=current.copy(segment=7)
        assertFalse(FitOverlayRow(100,current,proposed).changed)
        assertFalse(FitOverlayRow(100,current,current.copy(fittedDegrees=9.0+5e-7)).changed)
        assertTrue(FitOverlayRow(100,current,current.copy(fittedDegrees=9.0+2e-6)).changed)
    }
    private fun library():SavedTraces {
        val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
        return SavedTraces.empty().captureBatch((0..1).map {id ->SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),
            (900..1100 step 10).associateWith {hz ->PhaseSample(PhaseResponse.wrapped(if(id==0) 0.0 else hz*360*.003))},delayReferenceS=0.0)})
            .withRangeExcluded(MatchRangeExclusion(0,1,940,960),true)
    }
    @Test fun selectionAndFilterReopenAndDoNotInvalidateHistoryPreview() {
        val original=library();val preview=ExclusionHistoryPreviews.preview(original,original.exclusionHistory(0,1),0)
        val selection=MatchSampleSelection(0,1,950)
        val inspected=original.withInspectedSample(selection).withOverlayChangedOnly(true)
        val reopened=SavedTraces.fromJsonString(inspected.toJsonString())
        assertEquals(selection,reopened.inspectedSample);assertTrue(reopened.overlayChangedOnly)
        val newPreview=ExclusionHistoryPreviews.preview(reopened,reopened.exclusionHistory(0,1),0)
        assertEquals(OverlaySampleInspection.rows(preview.overlay,true),OverlaySampleInspection.rows(newPreview.overlay,true))
        assertNotNull(OverlaySampleInspection.resolve(newPreview.overlay,reopened.inspectedSample!!.hz,reopened.overlayChangedOnly))
        val applied=ExclusionHistoryPreviews.apply(reopened,preview)
        assertTrue(applied.overlayChangedOnly);assertEquals(selection,applied.inspectedSample)
        assertTrue(applied.rangeExclusions.isEmpty());assertEquals(original.traces[1].phases,applied.traces[1].phases)
        assertFails {ExclusionHistoryPreviews.apply(reopened.withDelayReference(1,.001),preview)}
        assertNull(reopened.remove(1).inspectedSample)
        assertTrue(reopened.withMatchingFrequencyRange(MatchingFrequencyRange(1000,1100)).overlayChangedOnly)
        assertFalse(SavedTraces.fromJsonString(reopened.toJsonString().replace("\"overlayChangedOnly\":true,", "")).overlayChangedOnly)
    }
}
