package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.scene.ClfData
import kotlin.math.*
import kotlin.test.*

class DensePhaseCaptureTest {
    private val frequencies=(800..1200 step 2).toList()
    private val air=Atmosphere()
    private val metadata=TraceMetadata(BoundaryPoint(20.0,0.0,0.0),1f,20f,50f,"a".repeat(64),0)
    private fun source(id:Int=0, missing:Int?=null):SourceSnapshot {
        // Cabinet contributes an independent 0.7 ms group delay on top of propagation and DSP.
        val rows=frequencies.filter {it!=missing}.joinToString(",") {hz ->
            val angle=2*PI*hz*.0007
            """{"frequency":$hz,"azimuths":[0],"elevations":[-90,90],"real":[[${100*cos(angle)},${100*cos(angle)}]],"imaginary":[[${100*sin(angle)},${100*sin(angle)}]]}"""
        }
        val complex=ComplexSpeakerResponse.fromJsonString("""{"version":1,"convention":"${ComplexSpeakerResponse.CONVENTION}","provenance":"Synthetic delay test","referenceDrive":"1 V rms","patterns":[$rows]}""")
        return SourceSnapshot(id,0f,0f,0f,0f,listOf(ArrayGeometry.Pose(0f,0f,0f,0f)),0f,0f,
            FrozenResponse.measured(ClfData("test",complexResponse=complex),true),delayS=.050)
    }
    private fun calculate(sources:List<SourceSnapshot>)=PredictionRequest(
        PredictionTicket(1,1,PredictionChannel.LISTENER),sources,listOf(ReceiverSnapshot(20f,0f,0f)),
        1000,listOf(1000),1f,emptyMap(),true,air,retainBandPowers=true).calculate()
    private fun trace(output:PredictionOutput)=SavedTrace(0,"Dense",metadata,output.bandPowersAt(0),output.phasesAt(0),delayReferenceS=output.delayReferenceAt(0))
    @Test fun capturesActualDenseFrequenciesAndRecoversPropagationDspAndCabinetDelay() {
        val output=calculate(listOf(source()))
        assertEquals(frequencies.toSet(),output.phasesAt(0).keys)
        assertEquals(setOf(1000),output.bandPowersAt(0).keys)
        val t=trace(output);val reference=20/air.speedOfSound+.050
        assertEquals(reference,t.delayReferenceS!!,1e-9)
        val result=PhaseDerivedTraces.derive(t)
        assertEquals(201,result.points.size)
        result.delays.forEach {assertEquals((reference+.0007)*1000,it.milliseconds!!,1e-4)}
    }
    @Test fun missingFrequencyInOneSourceLeavesGapAndNeverUsesPartialSum() {
        val output=calculate(listOf(source(),source(1,1002)))
        assertNull(output.phasesAt(0).getValue(1002).degrees)
        val derived=PhaseDerivedTraces.derive(trace(output))
        assertNull(derived.delays.first {it.toHz==1002}.milliseconds)
        assertNull(derived.delays.first {it.fromHz==1002}.milliseconds)
        assertNotNull(derived.delays.first {it.fromHz==1004}.milliseconds)
    }
    @Test fun denseSamplesAndReferenceSurviveSaveReopenWithoutChangingBandPowers() {
        val t=trace(calculate(listOf(source())))
        val saved=SavedTraces.empty().captureBatch(listOf(t))
        val reopened=SavedTraces.fromJsonString(saved.toJsonString()).traces.single()
        assertEquals(t.phases,reopened.phases);assertEquals(t.powers,reopened.powers)
        assertEquals(t.delayReferenceS,reopened.delayReferenceS)
        assertEquals(PhaseDerivedTraces.derive(t),PhaseDerivedTraces.derive(reopened))
        assertFails {SavedTrace(0,"Invalid",metadata,t.powers,t.phases,delayReferenceS=Double.NaN)}
    }
    @Test fun delayReferenceRemovesMultipleTurnsPerStepAndLegacyStillUsesZero() {
        val reference=.120
        val phases=listOf(1000,1100,1200).associateWith {PhaseSample(PhaseResponse.wrapped(360*it*(reference+.001)))}
        val t=SavedTrace(0,"Long delay",metadata,mapOf(1000 to 1.0),phases,delayReferenceS=reference)
        PhaseDerivedTraces.derive(t).delays.forEach {assertEquals(121.0,it.milliseconds!!,1e-8)}
        val old=SavedTrace(0,"Legacy",metadata,t.powers,phases)
        PhaseDerivedTraces.derive(old).delays.forEach {assertEquals(1.0,it.milliseconds!!,1e-8)}
    }
}
