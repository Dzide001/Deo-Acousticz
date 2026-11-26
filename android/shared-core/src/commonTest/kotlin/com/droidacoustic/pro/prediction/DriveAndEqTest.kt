package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class DriveAndEqTest {
    private fun source(eq:Map<Int,Float> = emptyMap(),complex:Boolean=false):SourceSnapshot {
        val response=if(!complex) FrozenResponse.generic(100f,"generic",false,false) else {
            val rows=(listOf(25)+OctaveWeighting.bands+(1000..20000 step 1000)).distinct().sorted().joinToString {hz ->
                """{"frequency":$hz,"azimuths":[0],"elevations":[-90,90],"real":[[100,100]],"imaginary":[[0,0]]}"""
            }
            val json="""{"version":2,"convention":"${ComplexSpeakerResponse.CONVENTION}","provenance":"synthetic drive test","referenceDrive":"1 V rms","frequencyInterpolation":"cartesian-linear-hz","maximumInterpolationGapHz":2000,"patterns":[$rows]}"""
            FrozenResponse.measured(ClfData("complex",complexResponse=ComplexSpeakerResponse.fromJsonString(json)),true)
        }
        return SourceSnapshot(0,0f,1f,0f,0f,ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,response,
            delayS=.007,eqDb=eq,filters=if(complex) DspFilterChain(listOf(DspFilter(FilterKind.LOW_PASS,1000.0))) else DspFilterChain())
    }
    private fun request(s:SourceSnapshot=source(),bands:List<Int> = OctaveWeighting.bands,
        drive:BroadbandDrive?=null,weighting:String="Z",phase:Boolean=false)=PredictionRequest(
        PredictionTicket(1,1,PredictionChannel.LISTENER),listOf(s),listOf(ReceiverSnapshot(1f,1f,0f)),bands.first(),bands,
        if(bands.size==1) 1f/3 else 1f,OctaveWeighting.tables.getValue(weighting),true,Atmosphere(),
        retainBandPowers=true,retainPhase=phase,broadbandDrive=drive)
    @Test fun sparseLegacyEqOnlyChangesItsStoredBand() {
        for(hz in OctaveWeighting.bands) {
            val base=request(bands=listOf(hz)).calculate().levelAt(0)
            val edited=request(source(mapOf(1000 to 6f)),listOf(hz)).calculate().levelAt(0)
            assertEquals(if(hz==1000) 6f else 0f,edited-base,2e-5f,"$hz Hz")
        }
        val s=source(mapOf(1000 to 6f,4000 to -3f)).emitters(0,Atmosphere()).single()
        assertEquals(0f,s.eqDb(125));assertEquals(0f,s.eqDb(2000));assertEquals(-3f,s.eqDb(4000))
    }
    @Test fun fixedTotalRemovesExactlyEightBandDriveGainForEveryWeighting() {
        val delta=(10*log10(8.0)).toFloat()
        for(w in listOf("Z","A","C")) {
            val legacy=request(drive=BroadbandDrive.PER_BAND_REFERENCE,weighting=w).calculate()
            val fixed=request(drive=BroadbandDrive.TOTAL_EQUAL_BAND_POWER,weighting=w).calculate()
            assertEquals(delta,legacy.levelAt(0)-fixed.levelAt(0),2e-5f)
            // Independent single-path energy equation at one metre, including the known air model.
            val expectedPower=OctaveWeighting.bands.sumOf {hz ->
                val segments=Bands.segments(hz,1f)
                val power=segments.sumOf {s->s.widthHz*10.0.pow((100-Atmosphere().absorptionDbPerMetre(s.centreHz))/10)} / segments.sumOf {it.widthHz}
                power/8*10.0.pow(OctaveWeighting.tables.getValue(w).getValue(hz)/10.0)
            }
            assertEquals((10*log10(expectedPower)).toFloat(),fixed.levelAt(0),2e-5f)
            for(hz in OctaveWeighting.bands) assertEquals(legacy.bandPowersAt(0).getValue(hz)!!/8,fixed.bandPowersAt(0).getValue(hz)!!,1e-6)
        }
        val f=BroadbandDrive.DEFAULT.fractions(OctaveWeighting.bands)
        assertEquals(1.0,f.values.sum(),1e-12);assertTrue(f.values.all {it==.125})
    }
    @Test fun singleBandAndPerBandCompatibilityAreUnchanged() {
        val base=request(bands=listOf(1000)).calculate().levelAt(0)
        assertEquals(base,request(bands=listOf(1000),drive=BroadbandDrive.DEFAULT).calculate().levelAt(0))
        assertEquals(request().calculate().levelAt(0),request(drive=BroadbandDrive.PER_BAND_REFERENCE).calculate().levelAt(0))
    }
    @Test fun normalizedDrivePreservesComplexAndValidatedFilterPhase() {
        val s=source(complex=true)
        val base=request(s,phase=true).calculate();val fixed=request(s,drive=BroadbandDrive.DEFAULT,phase=true).calculate()
        for(hz in OctaveWeighting.bands) {
            assertNotNull(base.phasesAt(0).getValue(hz).degrees,"$hz Hz: ${base.phasesAt(0).getValue(hz).reason}")
            assertEquals(base.phasesAt(0).getValue(hz),fixed.phasesAt(0).getValue(hz))
        }
        assertEquals((10*log10(8.0)).toFloat(),base.levelAt(0)-fixed.levelAt(0),3e-5f)
    }
    @Test fun missingBroadbandResponseIsNotRenormalizedAway() {
        val data=ClfData("missing",sensitivityDbByBand=mapOf(1000 to 100f),patterns=listOf(
            ClfPolarPattern(1000,listOf(0f),listOf(-90f,90f),arrayOf(floatArrayOf(0f,0f)))))
        val s=SourceSnapshot(0,0f,1f,0f,0f,ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,FrozenResponse.measured(data,true))
        val out=request(s,drive=BroadbandDrive.DEFAULT).calculate()
        assertTrue(out.levelAt(0).isNaN());assertEquals(PredictionAvailability.UNAVAILABLE,out.validity.availability)
    }
    @Test fun absentPolicyAlwaysMeansLegacyAndBadPolicyIsRejected() {
        assertEquals(BroadbandDrive.PER_BAND_REFERENCE,BroadbandDrive.fromScene(buildJsonObject {put("version",30)}))
        assertEquals(BroadbandDrive.DEFAULT,BroadbandDrive.fromScene(buildJsonObject {put("broadbandDrive",BroadbandDrive.DEFAULT.name)}))
        assertFails {BroadbandDrive.fromScene(buildJsonObject {put("broadbandDrive","invented")})}
    }
}
