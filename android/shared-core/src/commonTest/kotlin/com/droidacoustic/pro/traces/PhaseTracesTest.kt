package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.math.*
import kotlin.test.*

class PhaseTracesTest {
    private fun complex(degrees:Double=0.0,drive:String="1 V rms")=ComplexSpeakerResponse.fromJsonString("""{"version":1,"convention":"${ComplexSpeakerResponse.CONVENTION}","provenance":"Test fixture","referenceDrive":"$drive","patterns":[{"frequency":1000,"azimuths":[0],"elevations":[-90,90],"real":[[${100*cos(degrees*PI/180)},${100*cos(degrees*PI/180)}]],"imaginary":[[${100*sin(degrees*PI/180)},${100*sin(degrees*PI/180)}]]}]}""")
    private fun source(degrees:Double=0.0,delay:Double=0.0,inverted:Boolean=false,eq:Float=0f,drive:String="1 V rms")=SoundSource(0,0f,0f,0f,0f,0f,
        MeasuredRadiator(emptyMap(),emptyMap(),complexResponse=complex(degrees,drive)),delayS=delay,inverted=inverted,eqDb={eq})
    private val air=Atmosphere(20f,50f)
    private fun phase(sources:List<SoundSource>,hz:Int=1000)=PhaseResponse.at(sources,10f,0f,0f,hz,air)
    @Test fun cabinetPhaseDelayAndPolarityFollowExplicitConvention() {
        val base=phase(listOf(source())).degrees!!
        assertEquals(90.0,PhaseResponse.wrapped(phase(listOf(source(90.0))).degrees!!-base),.0001)
        assertEquals(90.0,PhaseResponse.wrapped(phase(listOf(source(delay=.00025))).degrees!!-base),.0001)
        assertEquals(-180.0,PhaseResponse.wrapped(phase(listOf(source(inverted=true))).degrees!!-base),.0001)
        assertEquals(PhaseResponse.wrapped(360*1000*10/air.speedOfSound),base,.0001)
    }
    @Test fun missingBandsMagnitudeOnlyEqAndCancellationAreUnavailable() {
        assertNull(phase(listOf(source()),500).degrees)
        assertNull(phase(listOf(SoundSource(0,0f,0f,0f,0f,0f,Radiator {_,_,_->90f}))).degrees)
        assertNull(phase(listOf(source(eq=1f))).degrees)
        assertNull(phase(listOf(source(),source(inverted=true))).degrees)
        assertNull(phase(listOf(source(),source(drive="2 V rms"))).degrees)
    }
    @Test fun portableSpeakerDataPreservesComplexDataAndRejectsUnknownReferences() {
        val data=ClfData("test",complexResponse=complex(45.0))
        val restored=ClfParser.parseJson(ClfParser.toJson(data))!!
        assertEquals(45.0,atan2(restored.complexResponse!!.at(1000,0f,0f)!!.imaginary,restored.complexResponse!!.at(1000,0f,0f)!!.real)*180/PI,.0001)
        assertNull(ClfParser.parseJson(ClfParser.toJson(data).replace(ComplexSpeakerResponse.CONVENTION,"unknown")))
        assertNull(ClfParser.parseJson("""{"speakerId":"bad","complexResponse":{}}"""))
    }
    @Test fun tracePhaseRoundTripComparisonWrapsAndKeepsMissingSamples() {
        val meta=TraceMetadata(BoundaryPoint(1.0,1.2,0.0),1f,20f,50f,"a".repeat(64),0)
        val a=SavedTrace(0,"A",meta,mapOf(1000 to 1.0,500 to null),mapOf(1000 to PhaseSample(170.0),500 to PhaseSample(null,"Missing")))
        val b=SavedTrace(1,"B",meta,mapOf(1000 to 1.0),mapOf(1000 to PhaseSample(-170.0)))
        val saved=SavedTraces.empty().captureBatch(listOf(a,b))
        val reopened=SavedTraces.fromJsonString(saved.toJsonString()).traces
        val result=TraceComparisons.comparePhase(reopened[0],reopened[1])
        assertTrue(result.compatible);assertEquals(20.0,result.bands.last().deltaDegrees!!,.0001)
        assertNull(result.bands.first().deltaDegrees)
        assertEquals("Missing",reopened[0].phases[500]!!.reason)
        assertFalse(TraceComparisons.comparePhase(a,SavedTrace(2,"Legacy",meta,mapOf(1000 to 1.0))).compatible)
        assertFalse(TraceComparisons.comparePhase(a,SavedTrace(3,"Moved",meta.copy(receiver=BoundaryPoint(2.0,1.2,0.0)),b.powers,b.phases)).compatible)
    }
}
