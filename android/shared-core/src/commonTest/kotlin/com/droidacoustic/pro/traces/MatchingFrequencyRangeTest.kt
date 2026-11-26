package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class MatchingFrequencyRangeTest {
    private val metadata=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
    private fun trace(id:Int,missing:Set<Int> = emptySet())=SavedTrace(id,"Trace $id",metadata,mapOf(1000 to 1.0),
        (100..1000 step 10).associateWith {hz ->
            val phase=if(id==0) 0.0 else if(hz<=500) 360*hz*.001 else 180+360*(hz-500)*.005
            if(hz in missing) PhaseSample(null,"Missing") else PhaseSample(PhaseResponse.wrapped(phase))
        },delayReferenceS=0.0)
    @Test fun selectedRegionRecoversItsDelayAndIgnoresOtherRegions() {
        val a=trace(0);val b=trace(1)
        val low=TraceReferenceMatching.preview(a,b,MatchingFrequencyRange(100,300))
        val high=TraceReferenceMatching.preview(a,b,MatchingFrequencyRange(600,1000))
        assertTrue(low.available);assertTrue(high.available)
        assertEquals(.001,low.proposedReferenceS!!,1e-10)
        assertEquals(.005,high.proposedReferenceS!!,1e-10)
        assertEquals(21,low.usedSamples);assertEquals(100,low.usedMinHz);assertEquals(300,low.usedMaxHz)
        assertFalse(TraceReferenceMatching.preview(a,b).available)
    }
    @Test fun endpointsAreInclusiveWithoutInventingBoundarySamples() {
        val a=trace(0);val b=trace(1)
        val exact=TraceReferenceMatching.preview(a,b,MatchingFrequencyRange(150,170))
        assertTrue(exact.available);assertEquals(3,exact.usedSamples)
        val between=TraceReferenceMatching.preview(a,b,MatchingFrequencyRange(151,179))
        assertFalse(between.available)
        assertFalse(TraceReferenceMatching.preview(a,b,MatchingFrequencyRange(2000,3000)).available)
    }
    @Test fun missingDataInsideRangeSplitsFitsAndOutsideRangeDoesNotContaminateThem() {
        val missing=setOf(100,200,210,220,1000)
        val a=trace(0,missing);val b=trace(1,missing)
        val result=TraceReferenceMatching.preview(a,b,MatchingFrequencyRange(150,300))
        assertTrue(result.available);assertEquals(2,result.segments);assertEquals(13,result.usedSamples)
        assertEquals(.001,result.proposedReferenceS!!,1e-10)
        assertFalse(TraceReferenceMatching.preview(a,b,MatchingFrequencyRange(190,230)).available)
        assertTrue(TraceReferenceMatching.preview(a,b,MatchingFrequencyRange(250,300)).available)
    }
    @Test fun rangePersistsThroughCaptureApplyRemoveAndResetAndRejectsStalePreview() {
        val range=MatchingFrequencyRange(100,300)
        val library=SavedTraces.empty().captureBatch(listOf(trace(0),trace(1))).withMatchingFrequencyRange(range)
        val preview=TraceReferenceMatching.preview(library.traces[0],library.traces[1],range)
        val changed=library.withMatchingFrequencyRange(MatchingFrequencyRange(600,1000))
        val before=changed.toJsonString()
        assertFails {TraceReferenceMatching.apply(changed,preview)};assertEquals(before,changed.toJsonString())
        val applied=TraceReferenceMatching.apply(library,preview).captureBatch(listOf(trace(2))).remove(2)
        val reopened=SavedTraces.fromJsonString(applied.toJsonString())
        assertEquals(range,reopened.matchingFrequencyRange)
        assertEquals(.001,reopened.traces[1].manualDelayReferenceS!!,1e-10)
        assertEquals(trace(1).phases,reopened.traces[1].phases)
        assertNull(SavedTraces.fromJsonString(reopened.withMatchingFrequencyRange(null).toJsonString()).matchingFrequencyRange)
        assertNull(SavedTraces.fromJsonString(SavedTraces.empty().toJsonString()).matchingFrequencyRange)
    }
    @Test fun invalidRangesAndMalformedSavedRangesAreRejected() {
        for((low,high) in listOf(0 to 100,-1 to 10,100 to 100,200 to 100)) assertFails {MatchingFrequencyRange(low,high)}
        val saved=SavedTraces.empty().withMatchingFrequencyRange(MatchingFrequencyRange(100,200)).toJsonString()
        assertFails {SavedTraces.fromJsonString(saved.replace("\"maxHz\":200","\"maxHz\":50"))}
    }
}
