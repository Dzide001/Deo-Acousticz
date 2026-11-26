package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.math.*
import kotlin.test.*

class RobustReferenceMatchingTest {
    private val metadata=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
    private fun trace(id:Int, spikes:Set<Int> = emptySet(),missing:Set<Int> = emptySet(),noise:Boolean=false)=SavedTrace(id,"Trace $id",metadata,mapOf(1000 to 1.0),
        (800..1200 step 5).associateWith {hz ->
            val degrees=if(id==0) 0.0 else 360*hz*.003+37+(if(hz in spikes) 100 else 0)+(if(noise) sin(hz.toDouble()) else 0.0)
            if(hz in missing) PhaseSample(null,"Missing") else PhaseSample(PhaseResponse.wrapped(degrees))
        },delayReferenceS=0.0)
    @Test fun cleanFitScoresHighlyAndSparseFitHasLowerConfidence() {
        val a=trace(0);val b=trace(1)
        val full=TraceReferenceMatching.preview(a,b)
        val sparse=TraceReferenceMatching.preview(a,b,MatchingFrequencyRange(800,810))
        assertTrue(full.available);assertEquals(100,full.confidenceScore)
        assertEquals("High",full.confidenceLabel);assertTrue(full.rejectedFrequencies.isEmpty())
        assertTrue(sparse.available);assertTrue(sparse.confidenceScore<full.confidenceScore)
        assertEquals("Low",sparse.confidenceLabel)
    }
    @Test fun isolatedAndEndpointOutliersAreRejectedWithoutBiasingDelay() {
        val spikes=setOf(800,905,1000,1200)
        val a=trace(0);val b=trace(1,spikes)
        val preview=TraceReferenceMatching.preview(a,b)
        assertTrue(preview.available,preview.reason)
        assertEquals(.003,preview.proposedReferenceS!!,1e-10)
        assertEquals(spikes.sorted(),preview.rejectedFrequencies)
        assertEquals(77,preview.usedSamples);assertEquals(81,preview.eligibleSamples)
        assertTrue(preview.confidenceScore in 1..99)
        assertEquals(0.0,preview.residualRmsDegrees!!,1e-8)
    }
    @Test fun missingDataKeepsSegmentsSeparateAndDoesNotBecomeAnOutlier() {
        val missing=setOf(990,995,1000);val spikes=setOf(850,1100)
        val a=trace(0,missing=missing);val b=trace(1,spikes,missing)
        val preview=TraceReferenceMatching.preview(a,b)
        assertTrue(preview.available,preview.reason);assertEquals(2,preview.segments)
        assertEquals(spikes.sorted(),preview.rejectedFrequencies)
        assertEquals(78,preview.eligibleSamples);assertEquals(76,preview.usedSamples)
        assertEquals(.003,preview.proposedReferenceS!!,1e-10)
        val library=TraceReferenceMatching.apply(SavedTraces.empty().captureBatch(listOf(a,b)),preview)
        assertNull(library.traces[1].phases.getValue(995).degrees)
        assertEquals(b.phases,library.traces[1].phases)
    }
    @Test fun widespreadContaminationIsRejectedAndNoiseLowersConfidence() {
        val a=trace(0)
        val contaminated=trace(1,(800..1200 step 15).toSet())
        val bad=TraceReferenceMatching.preview(a,contaminated)
        assertFalse(bad.available);assertEquals(0,bad.confidenceScore)
        assertTrue(bad.rejectedFrequencies.isNotEmpty())
        assertFails {TraceReferenceMatching.apply(SavedTraces.empty().captureBatch(listOf(a,contaminated)),bad)}
        val noisy=TraceReferenceMatching.preview(a,trace(1,noise=true))
        assertTrue(noisy.available);assertTrue(noisy.confidenceScore<100)
        assertEquals(.003,noisy.proposedReferenceS!!,1e-5)
    }
    @Test fun roundTripReproducesDiagnosticsAndPreservesRejectedRawSamples() {
        val a=trace(0);val b=trace(1,setOf(900,1100),setOf(1000))
        val range=MatchingFrequencyRange(850,1150)
        val saved=SavedTraces.empty().captureBatch(listOf(a,b)).withMatchingFrequencyRange(range)
        val first=TraceReferenceMatching.preview(saved.traces[0],saved.traces[1],range)
        val reopened=SavedTraces.fromJsonString(saved.toJsonString())
        val second=TraceReferenceMatching.preview(reopened.traces[0],reopened.traces[1],reopened.matchingFrequencyRange)
        assertEquals(first.confidenceScore,second.confidenceScore)
        assertEquals(first.rejectedFrequencies,second.rejectedFrequencies)
        assertEquals(first.proposedReferenceS,second.proposedReferenceS)
        assertEquals(first.residualRmsDegrees,second.residualRmsDegrees)
        val applied=SavedTraces.fromJsonString(TraceReferenceMatching.apply(reopened,second).toJsonString())
        assertEquals(b.phases,applied.traces[1].phases)
        assertEquals(.003,applied.traces[1].manualDelayReferenceS!!,1e-10)
    }
}
