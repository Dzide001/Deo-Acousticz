package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import kotlinx.serialization.json.*
import kotlin.test.*

class EngineSceneSnapshotTest {
    private val calibration=ReferenceCalibration(1.0,DriveUnit.WATTS,1.0,"TEST ONLY")
    private val targets=listOf(SpeakerComparisonSample("r1",1000.0,10.0,1.0,0.0,-999.0),
        SpeakerComparisonSample("r2",2000.0,20.0,1.0,0.0,999.0))
    private fun request(gain: Float=0f,x: Float=0f,width: Float=1f/3,air: Atmosphere=Atmosphere(),
        floor: Map<Int,Float>?=null,cal: ReferenceCalibration?=calibration)=PredictionRequest(
        PredictionTicket(7,1,PredictionChannel.LISTENER),
        listOf(SourceSnapshot(0,x,1f,0f,0f,ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,
            FrozenResponse.generic(100f,"generic",false,false),gainDb=gain,
            comparisonData=ComparisonSourceData("TEST-ONLY generic source",cal,true,false))),emptyList(),1000,listOf(1000),width,
        emptyMap(),true,air,floor)
    @Test fun captureUsesSceneInputsNotReferenceLevelsAndRetainsUnflooredPower() {
        val original=request().captureComparison(targets,7)
        val changedReference=request().captureComparison(targets.map {it.copy(levelDb=-250.0)},7)
        assertEquals(original.document,changedReference.document)
        assertEquals(80.0,original.run.samples.first().levelDb!!,.1)
        assertTrue(original.synthetic)
        val louder=request(gain=6f).captureComparison(targets,7)
        assertEquals(6.0,louder.run.samples.first().levelDb!!-original.run.samples.first().levelDb!!,1e-4)
        assertEquals(3.9810717055,louder.run.conditions.driveValue!!,1e-8)
        assertNotEquals(original.run.conditions.preset,louder.run.conditions.preset)
        val quiet=request(gain=-100f).captureComparison(targets,7)
        assertTrue(quiet.run.samples.first().levelDb!!<0) // Never use the display's zero-dB floor.
        val copy=ComparisonWorkspace.empty().withImported(original,false)
        assertEquals(copy.toJsonString(),ComparisonWorkspace.parse(copy.toJsonString()).toJsonString())
    }
    @Test fun geometryBandwidthAtmosphereAndGroundAreDerivedAndRequestsStayFrozen() {
        val base=request().captureComparison(targets,7).run.conditions
        assertNotEquals(base.geometry,request(x=1f).captureComparison(targets,7).run.conditions.geometry)
        assertNotEquals(base.frequencyConvention,request(width=1f).captureComparison(targets,7).run.conditions.frequencyConvention)
        assertNotEquals(base.atmosphere,request(air=Atmosphere(30f)).captureComparison(targets,7).run.conditions.atmosphere)
        assertNotEquals(base.ground,request(floor=mapOf(1000 to 0f,2000 to 0f)).captureComparison(targets,7).run.conditions.ground)
        assertEquals(DriveUnit.UNKNOWN,request(cal=null).captureComparison(targets,7).run.conditions.driveUnit)
        assertNotEquals(ComparisonSourceData.generic("g",100f,"generic",false,true).json(),
            ComparisonSourceData.generic("g",110f,"generic",false,true).json())
        assertNotEquals(ComparisonSourceData("asset",calibration,false,true).json(),
            ComparisonSourceData("asset",calibration,false,false).json())
        val mutable=targets.toMutableList();var first=true
        val captured=request().captureComparison(mutable,7) {if(first) {first=false;mutable.clear()}}
        assertEquals(2,captured.run.samples.size)
    }
    @Test fun invalidTargetsCancellationAndMissingDataCannotProduceInventedLevels() {
        assertFails {request().captureComparison(listOf(targets[0].copy(hz=1000.5)),7)}
        assertFails {request().captureComparison(listOf(targets[0].copy(x=1e12+.1)),7)}
        assertFails {request().captureComparison(List(513) {targets[0].copy(id=it.toString())},7)}
        assertFails {request().captureComparison(targets,7) {error("cancelled")}}
        val missing=PredictionRequest(PredictionTicket(1,1,PredictionChannel.LISTENER),listOf(SourceSnapshot(0,0f,1f,0f,0f,
            ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,FrozenResponse.missing(),
            comparisonData=ComparisonSourceData("missing-data",null,false))),emptyList(),1000,listOf(1000),1f/3,emptyMap(),true,Atmosphere())
        assertNull(missing.captureComparison(targets,1).run.samples.first().levelDb)
    }
}
