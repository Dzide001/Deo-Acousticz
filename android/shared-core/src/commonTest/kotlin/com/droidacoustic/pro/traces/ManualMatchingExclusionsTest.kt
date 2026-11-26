package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class ManualMatchingExclusionsTest {
    private val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
    private val sample=MatchSampleSelection(0,1,1000)
    private fun library()=SavedTraces.empty().captureBatch((0..2).map {id ->
        SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),(900..1100 step 10).associateWith {hz ->
            PhaseSample(PhaseResponse.wrapped(if(id==0) 0.0 else 360*hz*.003+37+(if(hz==1000) 170 else 0)))
        },delayReferenceS=0.0)
    })
    @Test fun exclusionsBreakUnwrappingAndRecoverKnownDelayWithoutMutatingCaptures() {
        val original=library();val edited=original.withSampleExcluded(sample,true)
        val match=TraceReferenceMatching.preview(edited,0,1)
        assertTrue(match.available,match.reason)
        assertEquals(.003,match.correctionS!!,1e-12)
        assertEquals(2,match.segments);assertEquals(20,match.usedSamples)
        assertEquals(MatchSampleStatus.EXCLUDED,match.fitSamples.single {it.hz==1000}.status)
        assertFalse(1000 in match.rejectedFrequencies)
        assertEquals(original.traces[1].phases,TraceReferenceMatching.apply(edited,match).traces[1].phases)
        assertTrue(original.manualExclusions.isEmpty())
        assertTrue(edited.excludedFrequencies(0,2).isEmpty());assertTrue(edited.excludedFrequencies(1,0).isEmpty())
    }
    @Test fun persistenceRangeChangesDeletionAndReinclusion() {
        val edited=library().withSampleExcluded(sample,true).withInspectedSample(sample)
        val reopened=SavedTraces.fromJsonString(edited.toJsonString())
        assertEquals(edited.manualExclusions,reopened.manualExclusions)
        assertEquals(TraceReferenceMatching.preview(edited,0,1).fitSamples,TraceReferenceMatching.preview(reopened,0,1).fitSamples)
        assertEquals(setOf(sample),reopened.withMatchingFrequencyRange(MatchingFrequencyRange(1020,1100)).manualExclusions)
        assertEquals(setOf(sample),reopened.remove(2).manualExclusions)
        assertTrue(reopened.remove(1).manualExclusions.isEmpty())
        assertTrue(reopened.withSampleExcluded(sample,false).manualExclusions.isEmpty())
        assertEquals(TraceReferenceMatching.preview(library(),0,1).fitSamples,TraceReferenceMatching.preview(reopened.withSampleExcluded(sample,false),0,1).fitSamples)
        assertTrue(SavedTraces.fromJsonString(library().toJsonString().replace("\"manualExclusions\":[],", "")).manualExclusions.isEmpty())
        assertFails {SavedTraces.fromJsonString(edited.toJsonString().replace("\"hz\":1000","\"hz\":1001"))}
        assertFails {library().withSampleExcluded(MatchSampleSelection(0,9,1000),true)}
    }
    @Test fun changedExclusionsInvalidatePreviewAndTooManyExclusionsDisableFit() {
        val edited=library().withSampleExcluded(sample,true)
        val match=TraceReferenceMatching.preview(edited,0,1)
        assertFails {TraceReferenceMatching.apply(edited.withSampleExcluded(sample,false),match)}
        var empty=edited
        for(hz in 900..1100 step 10) empty=empty.withSampleExcluded(MatchSampleSelection(0,1,hz),true)
        val unavailable=TraceReferenceMatching.preview(empty,0,1)
        assertFalse(unavailable.available)
        assertTrue(unavailable.fitSamples.all {it.status==MatchSampleStatus.EXCLUDED})
        assertFails {TraceReferenceMatching.apply(empty,unavailable)}
    }
}
