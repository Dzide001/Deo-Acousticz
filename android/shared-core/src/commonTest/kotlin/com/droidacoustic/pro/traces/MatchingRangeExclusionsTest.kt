package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class MatchingRangeExclusionsTest {
    private val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
    private val range=MatchRangeExclusion(0,1,980,1020)
    private fun library()=SavedTraces.empty().captureBatch((0..2).map {id ->
        SavedTrace(id,"Trace $id",meta,mapOf(1000 to 1.0),(900..1100 step 10).associateWith {hz ->
            PhaseSample(PhaseResponse.wrapped(if(id==0) 0.0 else 360*hz*.003+37+(if(hz in 980..1020) 170 else 0)))
        },delayReferenceS=0.0)
    })
    @Test fun inclusiveExclusionRecoversKnownDelayAndSplitsSegments() {
        val original=library();val edited=original.withRangeExcluded(range,true)
        val match=TraceReferenceMatching.preview(edited,0,1)
        assertTrue(match.available,match.reason);assertEquals(.003,match.correctionS!!,1e-12)
        assertEquals(16,match.usedSamples);assertEquals(2,match.segments)
        assertEquals((980..1020 step 10).toSet(),match.fitSamples.filter {it.status==MatchSampleStatus.EXCLUDED}.map {it.hz}.toSet())
        assertTrue(match.rejectedFrequencies.isEmpty())
        assertEquals(original.traces[1].phases,TraceReferenceMatching.apply(edited,match).traces[1].phases)
        assertTrue(edited.excludedFrequencies(0,2).isEmpty());assertTrue(edited.excludedFrequencies(1,0).isEmpty())
        assertTrue(original.rangeExclusions.isEmpty())
    }
    @Test fun overlappingRangesAndIndividualSamplesFormUnionAndRemoveIndependently() {
        val sample=MatchSampleSelection(0,1,1000)
        val overlap=MatchRangeExclusion(0,1,1000,1040)
        val edited=library().withRangeExcluded(range,true).withRangeExcluded(overlap,true).withSampleExcluded(sample,true)
        assertEquals((980..1040 step 10).toSet(),edited.excludedFrequencies(0,1))
        val removed=edited.withRangeExcluded(range,false)
        assertEquals((1000..1040 step 10).toSet(),removed.excludedFrequencies(0,1))
        assertEquals(setOf(1000),removed.withRangeExcluded(overlap,false).excludedFrequencies(0,1))
        assertTrue(removed.withRangeExcluded(overlap,false).withSampleExcluded(sample,false).excludedFrequencies(0,1).isEmpty())
        assertEquals(setOf(range),library().withRangeExcluded(range,true).withRangeExcluded(range,true).rangeExclusions)
    }
    @Test fun persistenceRangeChangesAndDeletion() {
        val edited=library().withRangeExcluded(range,true)
        val reopened=SavedTraces.fromJsonString(edited.toJsonString())
        assertEquals(setOf(range),reopened.rangeExclusions)
        assertEquals(TraceReferenceMatching.preview(edited,0,1).fitSamples,TraceReferenceMatching.preview(reopened,0,1).fitSamples)
        val window=reopened.withMatchingFrequencyRange(MatchingFrequencyRange(1030,1100))
        assertEquals(setOf(range),window.rangeExclusions)
        assertTrue(TraceReferenceMatching.preview(window,0,1).fitSamples.none {it.status==MatchSampleStatus.EXCLUDED})
        assertEquals(setOf(range),reopened.withDelayReference(1,.001).withRelativeDisplay(true).withInspectedSample(MatchSampleSelection(0,1,1000)).remove(2).rangeExclusions)
        assertTrue(reopened.remove(1).rangeExclusions.isEmpty())
        assertTrue(SavedTraces.fromJsonString(library().toJsonString().replace("\"rangeExclusions\":[],", "")).rangeExclusions.isEmpty())
        assertFails {SavedTraces.fromJsonString(edited.toJsonString().replace("\"maxHz\":1020","\"maxHz\":970"))}
        assertFails {library().withRangeExcluded(MatchRangeExclusion(0,9,900,1100),true)}
        assertFails {MatchRangeExclusion(0,1,0,1000)};assertFails {MatchRangeExclusion(0,1,1000,1000)}
    }
    @Test fun stalePreviewsAllExcludedAndOffGridBounds() {
        assertFalse(TraceReferenceMatching.preview(library(),0,0).available)
        val edited=library().withRangeExcluded(range,true);val match=TraceReferenceMatching.preview(edited,0,1)
        assertFails {TraceReferenceMatching.apply(edited.withRangeExcluded(range,false),match)}
        val offGrid=library().withRangeExcluded(MatchRangeExclusion(0,1,979,1021),true)
        assertEquals(match.fitSamples,TraceReferenceMatching.preview(offGrid,0,1).fitSamples)
        val empty=library().withRangeExcluded(MatchRangeExclusion(0,1,1,20000),true)
        assertFalse(TraceReferenceMatching.preview(empty,0,1).available)
        assertTrue(TraceReferenceMatching.preview(empty,0,1).fitSamples.all {it.status==MatchSampleStatus.EXCLUDED})
        assertTrue(library().withRangeExcluded(MatchRangeExclusion(0,1,2000,3000),true).excludedFrequencies(0,1).isEmpty())
    }
}
