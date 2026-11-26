package com.droidacoustic.pro.alignment

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import com.droidacoustic.pro.prediction.*
import kotlin.test.*

class SystemAdjustmentTest {
    private val zero=SystemDsp(0f,0f,false)
    private val air=Atmosphere(20f,50f)
    private val references=AlignmentReferences.empty()
        .add(AlignmentReceiver("Front",BoundaryPoint(0.0,0.0,10.0)),1)
        .add(AlignmentReceiver("Rear",BoundaryPoint(0.0,0.0,-20.0)),99)
    private val sources=listOf(AlignmentSource(1,listOf(SoundSource(0,0f,0f,0f,0f,0f,Radiator {_,_,_->90f})),0.0))
    private fun request(dsp:SystemDsp,missing:Boolean=false):PredictionRequest = PredictionRequest(
        PredictionTicket(1,1,PredictionChannel.LISTENER),listOf(SourceSnapshot(1,0f,0f,0f,0f,
            listOf(ArrayGeometry.Pose(0f,0f,0f,0f)),0f,0f,
            if(missing) FrozenResponse.missing() else FrozenResponse.generic(90f,"point_small",false,false),
            dsp.gainDb,dsp.delayMs/1000.0,dsp.inverted)),
        listOf(ReceiverSnapshot(0f,0f,10f),ReceiverSnapshot(0f,0f,-20f)),1000,listOf(1000),1f/3,
        emptyMap(),true,air,retainBandPowers=true)
    @Test fun combinedChangePreservesSingleSourcePowerExceptGainAndChangesArrival() {
        val after=SystemDsp(6f,12f,true)
        val result=SystemAdjustmentComparison.calculate(SystemAdjustment(1,zero,after),"scene",references,sources,air.speedOfSound,request(zero),request(after))
        assertEquals(2,result.receivers.size)
        result.receivers.forEach {r -> assertEquals(6.0,r.total.differenceDb!!,1e-4);assertEquals(6.0,r.bands.getValue(1000).differenceDb!!,1e-4)}
        val row=result.receivers.first()
        assertEquals(12.0,row.afterArrivals.first().earliestMs-row.beforeArrivals.first().earliestMs,1e-8)
        assertTrue(result.receivers.last().beforeArrivals.isEmpty())
        assertTrue(result.applicable("scene",zero));assertFalse(result.applicable("edited",zero));assertFalse(result.applicable("scene",after))
    }
    @Test fun missingPowerNeverProducesInventedDifferencesAndCancellationPropagates() {
        val change=SystemAdjustment(1,zero,SystemDsp(1f,0f,false))
        val result=SystemAdjustmentComparison.calculate(change,"scene",references,sources,air.speedOfSound,request(zero,true),request(change.after,true))
        result.receivers.forEach {assertNull(it.total.differenceDb);assertNull(it.bands.getValue(1000).beforeDb)}
        assertNull(LevelDifference.fromPower(0.0,1.0).differenceDb)
        assertFailsWith<IllegalStateException> {SystemAdjustmentComparison.calculate(change,"scene",references,sources,air.speedOfSound,request(zero),request(change.after)) {error("cancel")}}
    }
    @Test fun commandValidationAtomicApplyUndoRedoAndNoOp() {
        val after=SystemDsp(-12f,200f,true);val edit=SystemAdjustment(1,zero,after)
        assertEquals(after,edit.apply(zero));assertNull(edit.apply(after))
        assertEquals(zero,edit.inverse().apply(after));assertEquals(after,edit.apply(edit.inverse().apply(after)!!))
        assertNull(SystemAdjustment(1,zero,zero).apply(zero))
        assertFails {SystemDsp(Float.NaN,0f,false)};assertFails {SystemDsp(0f,Float.POSITIVE_INFINITY,false)}
        assertFails {SystemDsp(12.01f,0f,false)};assertFails {SystemDsp(0f,-1f,false)}
        val restored=AlignmentReferences.fromJsonString(references.toJsonString())
        assertEquals(references.compare(sources,air.speedOfSound),restored.compare(sources,air.speedOfSound))
    }
}
