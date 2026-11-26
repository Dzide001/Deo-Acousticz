package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class TraceReferenceMatchingTest {
    private val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
    private fun trace(id:Int,delay:Double,offset:Double=0.0,missing:Set<Int> = emptySet(),frequencies:List<Int> =(800..1200 step 5).toList(),reference:Double=.020)=
        SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),frequencies.associateWith {hz ->
            if(hz in missing) PhaseSample(null,"Missing complex data") else PhaseSample(PhaseResponse.wrapped(360*hz*delay+offset))
        },delayReferenceS=reference)
    @Test fun recoversPositiveAndNegativeDelayDifferencesWithoutRemovingConstantPhase() {
        for(delay in listOf(.023,.017)) {
            val a=trace(0,.020);val b=trace(1,delay,37.0)
            val preview=TraceReferenceMatching.preview(a,b)
            assertTrue(preview.available,preview.reason)
            assertEquals(delay,preview.proposedReferenceS!!,1e-10)
            assertEquals(delay-.020,preview.correctionS!!,1e-10)
            assertEquals(0.0,preview.residualRmsDegrees!!,1e-8)
            val library=TraceReferenceMatching.apply(SavedTraces.empty().captureBatch(listOf(a,b)),preview)
            val result=TraceComparisons.comparePhase(library.traces[0],library.traces[1],true)
            result.bands.forEach {assertEquals(37.0,it.deltaDegrees!!,1e-8)}
            assertEquals(b.phases,library.traces[1].phases)
            assertEquals(a.delayReferenceS,library.traces[0].delayReferenceS)
        }
    }
    @Test fun fitsSeparateSegmentsWithoutBridgingMissingFrequencies() {
        val missing=setOf(1000,1005,1010)
        val a=trace(0,.020,missing=missing);val b=trace(1,.022,missing=missing)
        val preview=TraceReferenceMatching.preview(a,b)
        assertTrue(preview.available);assertEquals(2,preview.segments)
        assertEquals(78,preview.usedSamples);assertEquals(.022,preview.proposedReferenceS!!,1e-10)
        val library=TraceReferenceMatching.apply(SavedTraces.empty().captureBatch(listOf(a,b)),preview)
        assertNull(library.traces[1].phases.getValue(1000).degrees)
        assertNull(PhaseDerivedTraces.derive(library.traces[1],true).delays.first {it.toHz==1000}.milliseconds)
    }
    @Test fun rejectsInsufficientAmbiguousIncompatibleAndNonlinearComparisons() {
        val a=trace(0,.020);assertFalse(TraceReferenceMatching.preview(a,a).available)
        assertFalse(TraceReferenceMatching.preview(a,trace(1,.022,frequencies=listOf(1000,1005))).available)
        assertFalse(TraceReferenceMatching.preview(a,trace(1,.120)).available) // 180° every 5 Hz
        val moved=SavedTrace(1,"Moved",meta.copy(receiver=BoundaryPoint(2.0,1.2,0.0)),a.powers,a.phases)
        assertFalse(TraceReferenceMatching.preview(a,moved).available)
        val nonlinear=SavedTrace(1,"Curved",meta,a.powers,a.phases.mapValues {(hz,p)->
            PhaseSample(PhaseResponse.wrapped(p.degrees!!+.002*(hz-1000)*(hz-1000)))
        },delayReferenceS=.020)
        assertFalse(TraceReferenceMatching.preview(a,nonlinear).available)
        val empty=SavedTrace(1,"Missing",meta,a.powers,a.phases.mapValues {PhaseSample(null,"Missing")})
        assertFalse(TraceReferenceMatching.preview(a,empty).available)
    }
    @Test fun appliedReferenceRoundTripsAndStalePreviewCannotOverwriteEdits() {
        val a=trace(0,.020);val b=trace(1,.024)
        val library=SavedTraces.empty().captureBatch(listOf(a,b))
        val preview=TraceReferenceMatching.preview(library.traces[0],library.traces[1])
        val edited=library.withDelayReference(1,.019)
        val before=edited.toJsonString()
        assertFails {TraceReferenceMatching.apply(edited,preview)};assertEquals(before,edited.toJsonString())
        assertFails {TraceReferenceMatching.apply(library.remove(0),preview)}
        val matched=TraceReferenceMatching.apply(library,preview)
        val reopened=SavedTraces.fromJsonString(matched.toJsonString())
        assertTrue(reopened.relativeToReference)
        assertEquals(.024,reopened.traces[1].manualDelayReferenceS!!,1e-10)
        assertEquals(PhaseDerivedTraces.compare(matched.traces[0],matched.traces[1],true),PhaseDerivedTraces.compare(reopened.traces[0],reopened.traces[1],true))
    }
    @Test fun usesCurrentManualReferencesAndNonuniformMeasuredFrequencies() {
        val f=listOf(801,813,830,853,877,911,1000)
        val a=trace(0,.150,frequencies=f,reference=.149).withDelayReference(.148)
        val b=trace(1,.175,frequencies=f,reference=.174).withDelayReference(.174)
        val preview=TraceReferenceMatching.preview(a,b)
        assertTrue(preview.available);assertEquals(.173,preview.proposedReferenceS!!,1e-10)
        assertEquals(-.001,preview.correctionS!!,1e-10)
    }
}
