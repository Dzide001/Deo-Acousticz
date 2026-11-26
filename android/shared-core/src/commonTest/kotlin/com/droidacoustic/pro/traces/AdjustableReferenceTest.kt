package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class AdjustableReferenceTest {
    private val meta=TraceMetadata(BoundaryPoint(1.0,1.0,0.0),1f,20f,50f,"a".repeat(64),0)
    private fun trace(delay:Double=.021,missing:Boolean=false)=SavedTrace(0,"Test",meta,mapOf(1000 to 1.0),
        listOf(1000,1010,1020,1030).associateWith {hz ->
            if(missing && hz==1010) PhaseSample(null,"Missing complex data") else PhaseSample(PhaseResponse.wrapped(360*hz*delay))
        },delayReferenceS=.020)
    @Test fun manualReferenceChangesResidualButPreservesTotalAndRawCapture() {
        val a=trace();val b=a.withDelayReference(.019)
        assertEquals(a.phases,b.phases);assertEquals(a.powers,b.powers);assertEquals(.020,b.delayReferenceS)
        PhaseDerivedTraces.derive(b).delays.forEach {assertEquals(21.0,it.milliseconds!!,1e-8)}
        PhaseDerivedTraces.derive(a,true).delays.forEach {assertEquals(1.0,it.milliseconds!!,1e-8)}
        PhaseDerivedTraces.derive(b,true).delays.forEach {assertEquals(2.0,it.milliseconds!!,1e-8)}
        val residual=TraceComparisons.comparePhase(a,b,true)
        assertEquals(3.6,residual.bands[1].deltaDegrees!!,1e-8)
        assertEquals(0.0,TraceComparisons.comparePhase(a,b).bands[1].deltaDegrees!!,1e-8)
        assertEquals(a.effectiveDelayReferenceS,b.withDelayReference(null).effectiveDelayReferenceS)
    }
    @Test fun missingSamplesAndAmbiguousResidualStepsRemainUnavailable() {
        val adjusted=trace(missing=true).withDelayReference(.019)
        val d=PhaseDerivedTraces.derive(adjusted,true)
        assertNull(d.points[1].degrees);assertNull(d.delays[0].milliseconds);assertNull(d.delays[1].milliseconds)
        assertEquals(2.0,d.delays[2].milliseconds!!,1e-8)
        assertEquals("Missing complex data",TraceComparisons.comparePhase(adjusted,adjusted,true).bands[1].baseline!!.reason)
        assertNull(PhaseDerivedTraces.derive(trace().withDelayReference(-.029),true).delays.first().milliseconds)
    }
    @Test fun referencesAndDisplayModeSurviveLibraryChangesAndRoundTrip() {
        val original=trace();var lib=SavedTraces.empty().captureBatch(listOf(original,original))
        lib=lib.withDelayReference(0,-.005).withDelayReference(1,0.0).withRelativeDisplay(true)
        lib=lib.captureBatch(listOf(original)).remove(2)
        val reopened=SavedTraces.fromJsonString(lib.toJsonString())
        assertTrue(reopened.relativeToReference)
        assertEquals(-.005,reopened.traces[0].manualDelayReferenceS);assertEquals(0.0,reopened.traces[1].manualDelayReferenceS)
        assertEquals(PhaseDerivedTraces.compare(lib.traces[0],lib.traces[1],true),PhaseDerivedTraces.compare(reopened.traces[0],reopened.traces[1],true))
        assertEquals(original.phases,reopened.traces[0].phases)
        assertNull(reopened.withDelayReference(0,null).traces[0].manualDelayReferenceS)
    }
    @Test fun invalidAdjustmentsAreAtomicAndLegacyDefaultsRemainTotal() {
        val library=SavedTraces.empty().captureBatch(listOf(trace()))
        val before=library.toJsonString()
        for(v in listOf(Double.NaN,Double.POSITIVE_INFINITY,3600.1)) assertFails {library.withDelayReference(0,v)}
        assertFails {library.withDelayReference(99,0.0)}
        assertEquals(before,library.toJsonString())
        val legacy=before.replace("\"relativeToReference\":false,", "")
        assertFalse(SavedTraces.fromJsonString(legacy).relativeToReference)
    }
}
