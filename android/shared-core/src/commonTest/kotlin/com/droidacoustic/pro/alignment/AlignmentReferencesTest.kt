package com.droidacoustic.pro.alignment

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class AlignmentReferencesTest {
    private fun receiver(x:Double)=AlignmentReceiver("Same name",BoundaryPoint(x,0.0,0.0))
    private fun sources(delay:Double=0.0)=listOf(
        AlignmentSource(1,listOf(SoundSource(1,10f,0f,0f,0f,0f,Radiator {_,_,_->90f})),0.0),
        AlignmentSource(2,listOf(SoundSource(2,0f,0f,0f,0f,0f,Radiator {_,_,_->90f},delayS=delay/1000)),delay))
    @Test fun stableIdsSelectionAndRemovalAreIndependentOfNames() {
        val original=AlignmentReferences.empty().add(receiver(0.0),1).add(receiver(8.0),2)
        assertEquals(1,original.selectedId)
        val selected=original.select(0)
        assertEquals(0,selected.selectedId);assertEquals(1,original.selectedId)
        assertEquals(1,selected.remove(0).selectedId)
        val next=selected.remove(1).add(receiver(4.0),1)
        assertEquals(listOf(0,2),next.receivers.map {it.id})
        assertFails {next.select(1)}
        assertNull(AlignmentReferences.empty().add(receiver(0.0),1).remove(0).selected)
    }
    @Test fun selectionDoesNotChangeOtherComparisonsAndDelayHasDifferentEffectsAtDifferentPositions() {
        val saved=AlignmentReferences.empty().add(receiver(0.0),1).add(receiver(8.0),1)
        val before=saved.compare(sources(),1000.0)
        assertEquals(10.0,before[0].arrivals[1].proposedDelayMs!!,1e-9)
        assertNull(before[1].arrivals[1].proposedDelayMs)
        assertEquals(before,saved.select(0).compare(sources(),1000.0))
        val after=saved.compare(sources(10.0),1000.0)
        assertEquals(0.0,after[0].arrivals[1].relativeArrivalMs,1e-9)
        assertEquals(16.0,after[1].arrivals[1].relativeArrivalMs,1e-9)
    }
    @Test fun sharedSerializationPreservesIdsSelectionCoordinatesAndPerReceiverSpeakers() {
        val saved=AlignmentReferences.empty().add(receiver(0.0),1)
            .add(AlignmentReceiver("Balcony",BoundaryPoint(3.0,8.0,-2.0)),2).select(0)
        val reopened=AlignmentReferences.fromJsonString(saved.toJsonString())
        assertEquals(saved.toJsonString(),reopened.toJsonString())
        assertEquals(saved.compare(sources(),343.0),reopened.compare(sources(),343.0))
        assertTrue(reopened.update(0,receiver(0.0),99).compare(sources(),343.0)[0].arrivals.isEmpty())
        assertEquals(2,reopened.receivers[1].referenceSourceId)
        assertFails {AlignmentReferences.fromJsonString(saved.toJsonString().replace("\"selectedId\":0","\"selectedId\":99"))}
        assertFails {AlignmentReferences.fromJsonString(saved.toJsonString().replace("\"version\":1","\"version\":2"))}
    }
    @Test fun previewIsPureAndReportsWorseningAtOtherReceiversIncludingChangedReferenceSpeaker() {
        val saved=AlignmentReferences.empty().add(receiver(0.0),1).add(receiver(8.0),2).select(0)
        val original=sources();val before=saved.compare(original,1000.0)
        val preview=saved.previewDelay(original,1000.0,2)!!
        assertEquals(before,preview.before)
        assertEquals(before,saved.compare(original,1000.0))
        assertEquals(0.0,original[1].delayMs)
        assertEquals(10.0,preview.proposedDelayMs)
        assertEquals(0.0,preview.after[0].arrivals[1].relativeArrivalMs,1e-9)
        // At the second receiver the changed source is the reference: every relative arrival changes.
        assertEquals(-6.0,preview.before[1].arrivals[0].relativeArrivalMs,1e-9)
        assertEquals(-16.0,preview.after[1].arrivals[0].relativeArrivalMs,1e-9)
        assertEquals(0.0,preview.after[1].arrivals[1].relativeArrivalMs,1e-9)
        assertEquals(saved.compare(sources(10.0),1000.0),preview.after)
        assertNull(saved.previewDelay(original,1000.0,1))
        assertNull(saved.previewDelay(sources(10.0),1000.0,2))
    }
    @Test fun impossibleAndMissingProposalsHaveNoPreview() {
        val saved=AlignmentReferences.empty().add(receiver(8.0),1)
        assertNull(saved.previewDelay(sources(),1000.0,2))
        assertNull(saved.previewDelay(sources(),1000.0,99))
        val missing=saved.update(0,receiver(8.0),99)
        assertNull(missing.previewDelay(sources(),1000.0,2))
        assertFails {saved.compare(sources(),1000.0,mapOf(2 to -1.0))}
    }
    @Test fun savedReceiverLimitFailsWithoutChangingExistingState() {
        var saved=AlignmentReferences.empty()
        repeat(64) {saved=saved.add(receiver(it.toDouble()),1)}
        assertFails {saved.add(receiver(99.0),1)}
        assertEquals(64,saved.receivers.size)
    }
}
