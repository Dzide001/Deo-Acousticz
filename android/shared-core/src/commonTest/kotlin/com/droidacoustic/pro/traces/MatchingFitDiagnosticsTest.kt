package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.math.*
import kotlin.test.*

class MatchingFitDiagnosticsTest {
    private val metadata=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
    private fun trace(id:Int)=SavedTrace(id,"Trace $id",metadata,mapOf(1000 to 1.0),
        (900..1100 step 5).associateWith {hz ->
            if(hz==1000) PhaseSample(null,"Missing") else PhaseSample(PhaseResponse.wrapped(
                if(id==0) 0.0 else 360*hz*.003+37+(if(hz==950 || hz==1050) 100 else 0)))
        },delayReferenceS=0.0)
    @Test fun plottedSamplesMatchFitCountsValuesSlopeAndRms() {
        val match=TraceReferenceMatching.preview(trace(0),trace(1))
        assertTrue(match.available)
        val samples=match.fitSamples
        val retained=samples.filter {it.status==MatchSampleStatus.RETAINED}
        val rejected=samples.filter {it.status==MatchSampleStatus.REJECTED}
        assertEquals(match.usedSamples,retained.size)
        assertEquals(match.rejectedFrequencies,rejected.map {it.hz})
        retained.forEach {assertEquals(it.observedDegrees!!,it.fittedDegrees!!,1e-8)}
        rejected.forEach {assertEquals(100.0,it.residualDegrees!!,1e-8)}
        assertEquals(match.residualRmsDegrees!!,sqrt(retained.map {it.residualDegrees!!.pow(2)}.average()),1e-8)
        retained.groupBy {it.segment}.values.forEach {segment ->
            segment.zipWithNext().forEach {(p,q)->
                assertEquals(match.correctionS!!,(q.fittedDegrees!!-p.fittedDegrees!!)/(360*(q.hz-p.hz)),1e-10)
            }
        }
    }
    @Test fun missingSamplesAndRangeBoundariesDoNotCreateModelConnections() {
        val range=MatchingFrequencyRange(940,1060)
        val samples=TraceReferenceMatching.preview(trace(0),trace(1),range).fitSamples
        assertTrue(samples.all {it.hz in 940..1060})
        val missing=samples.single {it.hz==1000}
        assertEquals(MatchSampleStatus.MISSING,missing.status)
        assertNull(missing.observedDegrees);assertNull(missing.fittedDegrees);assertNull(missing.segment)
        assertNotEquals(samples.single {it.hz==995}.segment,samples.single {it.hz==1005}.segment)
        val short=TraceReferenceMatching.preview(trace(0),trace(1),MatchingFrequencyRange(901,914))
        assertFalse(short.available)
        assertEquals(2,short.fitSamples.size)
        assertTrue(short.fitSamples.all {it.status==MatchSampleStatus.INSUFFICIENT && it.fittedDegrees==null})
    }
    @Test fun diagnosticsAreReproducibleBeforeAndAfterAppliedMatchReopen() {
        val library=SavedTraces.empty().captureBatch(listOf(trace(0),trace(1)))
        val match=TraceReferenceMatching.preview(library.traces[0],library.traces[1])
        val reopened=SavedTraces.fromJsonString(library.toJsonString())
        assertEquals(match.fitSamples,TraceReferenceMatching.preview(reopened.traces[0],reopened.traces[1]).fitSamples)
        val applied=TraceReferenceMatching.apply(reopened,match)
        val appliedReopened=SavedTraces.fromJsonString(applied.toJsonString())
        assertEquals(TraceReferenceMatching.preview(applied.traces[0],applied.traces[1]).fitSamples,
            TraceReferenceMatching.preview(appliedReopened.traces[0],appliedReopened.traces[1]).fitSamples)
        assertEquals(trace(1).phases,appliedReopened.traces[1].phases)
    }
    @Test fun diagnosticsRemainAvailableWhenFitIsRejected() {
        val a=trace(0);val original=trace(1)
        val b=SavedTrace(1,"Contaminated",metadata,original.powers,original.phases.mapValues {(hz,p)->
            if(p.degrees==null) p else PhaseSample(PhaseResponse.wrapped(360*hz*.003+37+(if(hz%15==0) 100 else 0)))
        },delayReferenceS=0.0)
        val match=TraceReferenceMatching.preview(a,b)
        assertFalse(match.available)
        assertTrue(match.fitSamples.any {it.status==MatchSampleStatus.REJECTED})
        assertEquals(match.rejectedFrequencies,match.fitSamples.filter {it.status==MatchSampleStatus.REJECTED}.map {it.hz})
    }
}
