package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class MatchSampleInspectionTest {
    private val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
    private fun trace(id:Int)=SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),
        (900..1100 step 10).associateWith {hz ->if(hz==1000) PhaseSample(null,"Missing") else
            PhaseSample(PhaseResponse.wrapped(if(id==0) 0.0 else 360*hz*.003+37+(if(hz==950) 100 else 0)))},delayReferenceS=0.0)
    private fun library()=SavedTraces.empty().captureBatch(listOf(trace(0),trace(1)))
    @Test fun hitTestingUsesActualRetainedRejectedAndMissingMarkerPositions() {
        val match=TraceReferenceMatching.preview(trace(0),trace(1))
        for(residual in listOf(false,true)) {
            val plot=MatchPlotGeometry(match.fitSamples,residual,600.0,240.0,12.0)
            for(hz in listOf(900,950,1000)) {
                val sample=match.fitSamples.single {it.hz==hz}
                assertEquals(hz,plot.hit(plot.x(hz),plot.markerY(sample),8.0)!!.hz)
                assertEquals(sample,MatchSampleInspection.select(match,hz).resolve(match))
            }
            assertNull(plot.hit(-1.0,50.0,10.0));assertNull(plot.hit(Double.NaN,0.0,10.0))
            assertNull(plot.hit(1.0,1.0,1.0))
        }
        assertFails {MatchPlotGeometry(match.fitSamples,false,0.0,100.0,0.0)}
    }
    @Test fun tieBreakingNavigationAndSelectionAreDeterministic() {
        val samples=listOf(MatchFitSample(100,0.0,0.0,0,MatchSampleStatus.RETAINED),MatchFitSample(200,0.0,0.0,0,MatchSampleStatus.REJECTED))
        val plot=MatchPlotGeometry(samples,false,100.0,100.0,10.0)
        assertEquals(100,plot.hit(50.0,50.0,40.0)!!.hz)
        val match=TraceReferenceMatching.preview(trace(0),trace(1))
        val missing=MatchSampleInspection.step(match,MatchSampleInspection.select(match,990),1)!!
        assertEquals(MatchSampleStatus.MISSING,missing.resolve(match)!!.status)
        assertEquals(900,MatchSampleInspection.step(match,null,1)!!.hz)
        assertEquals(1100,MatchSampleInspection.step(match,null,-1)!!.hz)
        assertEquals(1100,MatchSampleInspection.step(match,MatchSampleInspection.select(match,1100),1)!!.hz)
        assertFails {MatchSampleInspection.select(match,901)}
        assertNull(MatchSampleSelection(1,0,950).resolve(match))
    }
    @Test fun selectedSampleAndDetailsSurviveSaveReopenAndReferenceChanges() {
        val library=library();val match=TraceReferenceMatching.preview(library.traces[0],library.traces[1])
        val selection=MatchSampleInspection.select(match,950)
        val selected=library.withInspectedSample(selection)
        val reopened=SavedTraces.fromJsonString(selected.toJsonString())
        assertEquals(selection,reopened.inspectedSample)
        val preview=TraceReferenceMatching.preview(reopened.traces[0],reopened.traces[1])
        assertEquals(selection.resolve(match),reopened.inspectedSample!!.resolve(preview))
        val applied=TraceReferenceMatching.apply(reopened,preview)
        assertEquals(selection,applied.inspectedSample)
        assertEquals(100.0,selection.resolve(TraceReferenceMatching.preview(applied.traces[0],applied.traces[1]))!!.residualDegrees!!,1e-8)
        assertEquals(trace(1).phases,applied.traces[1].phases)
    }
    @Test fun rangeAndDeletionReconcileSelectionAndInvalidStoredSelectionIsRejected() {
        val selected=library().withInspectedSample(MatchSampleSelection(0,1,950))
        assertNull(selected.remove(1).inspectedSample)
        assertNull(selected.withMatchingFrequencyRange(MatchingFrequencyRange(1000,1100)).inspectedSample)
        assertNotNull(selected.withMatchingFrequencyRange(MatchingFrequencyRange(900,1000)).inspectedSample)
        assertNotNull(selected.captureBatch(listOf(trace(2))).remove(2).inspectedSample)
        assertNull(SavedTraces.fromJsonString(library().toJsonString()).inspectedSample)
        assertFails {selected.withInspectedSample(MatchSampleSelection(0,1,951))}
        assertFails {SavedTraces.fromJsonString(selected.toJsonString().replace("\"hz\":950","\"hz\":951"))}
    }
}
