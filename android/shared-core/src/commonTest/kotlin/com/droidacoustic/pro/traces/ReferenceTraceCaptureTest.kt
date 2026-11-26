package com.droidacoustic.pro.traces

import com.droidacoustic.pro.alignment.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import com.droidacoustic.pro.prediction.*
import kotlin.test.*

class ReferenceTraceCaptureTest {
    private fun refs()=AlignmentReferences.empty().add(AlignmentReceiver("FOH",BoundaryPoint(10.0,1.2,0.0)),null)
        .add(AlignmentReceiver("FOH",BoundaryPoint(20.0,4.0,0.0)),null)
    private val metadata=TraceMetadata(BoundaryPoint(0.0,0.0,0.0),1f,20f,50f,"a".repeat(64),42)
    private fun output(powers:DoubleArray)=PredictionOutput(PredictionTicket(1,1,PredictionChannel.LISTENER),FloatArray(powers.size),emptyArray(),bandPowers=mapOf(1000 to powers))
    @Test fun selectionUsesStableIdsOrderAndReconcilesRemovedReceivers() {
        val ids=mutableListOf(1,0);val choice=ReferenceTraceSelection(ids);ids.clear()
        assertEquals(listOf(1,0),choice.receivers(refs()).map {it.id})
        assertEquals(choice.ids,ReferenceTraceSelection.fromJsonString(choice.toJsonString()).ids)
        assertEquals(listOf(1),choice.reconcile(refs().remove(0)).ids)
        assertFails {choice.receivers(refs().remove(0))}
        assertFails {ReferenceTraceSelection(listOf(1,1))}
    }
    @Test fun batchPreservesIdentityPositionsPowersAndComparisonsAfterReopen() {
        val batch=ReferenceTraceCapture("A",ReferenceTraceSelection(listOf(1,0)).receivers(refs()),metadata)
        val a=batch.capture(SavedTraces.empty(),output(doubleArrayOf(1.0,4.0)))
        val b=ReferenceTraceCapture("B",batch.receivers,metadata.copy(inputHash="b".repeat(64)))
            .capture(a,output(doubleArrayOf(4.0,16.0)))
        val reopened=SavedTraces.fromJsonString(b.toJsonString()).traces
        assertEquals(listOf(1,0,1,0),reopened.map {it.metadata.referenceReceiverId})
        assertEquals(refs().receivers[1].receiver.position,reopened[0].metadata.receiver)
        assertEquals(6.0206,TraceComparisons.compare(reopened[0],reopened[2]).bands.single().deltaDb!!,.0001)
        assertFalse(TraceComparisons.compare(reopened[0],reopened[1]).compatible)
    }
    @Test fun batchCapacityAndInvalidResultsNeverPartiallyAppend() {
        var library=SavedTraces.empty()
        repeat(63) {library=library.capture("Existing",metadata,mapOf(1000 to 1.0))}
        val before=library.toJsonString()
        val batch=ReferenceTraceCapture("A",refs().receivers,metadata)
        assertFails {batch.capture(library,output(doubleArrayOf(1.0,2.0)))}
        assertEquals(before,library.toJsonString())
        assertFails {batch.capture(SavedTraces.empty(),output(doubleArrayOf(1.0)))}
        val captured=batch.capture(SavedTraces.empty(),output(doubleArrayOf(Double.NaN,0.0)))
        assertNull(captured.traces[0].powers[1000]);assertEquals(0.0,captured.traces[1].powers[1000])
    }
}
