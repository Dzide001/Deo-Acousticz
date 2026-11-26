package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class BroadbandSnapshotTest {
    private val targets=listOf(SpeakerComparisonSample("r",0.0,10.0,1.0,0.0,-999.0))
    private fun request(weight: String="Z",gain: Float=0f,response: FrozenResponse=FrozenResponse.generic(100f,"generic",false,false))=
        PredictionRequest(PredictionTicket(1,1,PredictionChannel.LISTENER),listOf(SourceSnapshot(0,0f,1f,0f,0f,
            ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,response,gainDb=gain,
            comparisonData=ComparisonSourceData("TEST ONLY",null,true,false))),emptyList(),1000,OctaveWeighting.bands,1f,
            OctaveWeighting.tables.getValue(weight),true,Atmosphere(),comparisonWeighting=weight)
    @Test fun weightedSnapshotUsesPowerSumAndPreservesUnflooredPerBandDiagnostics() {
        val captured=request("A").captureComparison(targets,1,levelConvention="BROADBAND_SPL",broadbandCapture=true)
        val root=Json.parseToJsonElement(captured.document).jsonObject
        val values=root.getValue("bandDiagnostics").jsonArray.single().jsonObject.getValue("bands").jsonObject
        val expected=10*log10(values.entries.sumOf {(hz,p)->p.jsonPrimitive.double*10.0.pow(OctaveWeighting.tables.getValue("A").getValue(hz.toInt())/10.0)})
        assertEquals(expected,captured.run.samples.single().levelDb!!,1e-10)
        assertEquals(8,values.size);assertEquals(0.0,captured.run.samples.single().hz)
        assertEquals(3,root.getValue("version").jsonPrimitive.int)
        assertEquals("dB_A",root.getValue("units").jsonObject.getValue("level").jsonPrimitive.content)
        val louder=request("A",6f).captureComparison(targets,1,levelConvention="BROADBAND_SPL",broadbandCapture=true)
        assertEquals(6.0,louder.run.samples.single().levelDb!!-captured.run.samples.single().levelDb!!,1e-7)
        val quiet=request("A",-120f).captureComparison(targets,1,levelConvention="BROADBAND_SPL",broadbandCapture=true)
        assertTrue(quiet.run.samples.single().levelDb!!<0)
        val state=ComparisonWorkspace.empty().withImported(captured,false)
        assertEquals(state.toJsonString(),ComparisonWorkspace.parse(state.toJsonString()).toJsonString())
    }
    @Test fun weightingAndRangeDifferencesCannotQualifyAndMissingBandsAreNotDropped() {
        fun capture(w: String)=request(w).captureComparison(targets,1,levelConvention="BROADBAND_SPL",broadbandCapture=true)
        val a=capture("A");val c=capture("C");val z=capture("Z")
        assertTrue(z.run.samples.single().levelDb!!>a.run.samples.single().levelDb!!)
        assertNotEquals(a.run.conditions,c.run.conditions)
        val compared=Json.parseToJsonElement(SpeakerComparisons.compare(a.run.copy(evidence=ComparisonEvidence.MANUFACTURER_SOFTWARE_EXPORT),c.run,0.1)).jsonObject
        assertEquals("NOT_COMPARABLE",compared.getValue("status").jsonPrimitive.content)
        val all=Bands.THIRD_OCTAVE_HZ.filter {it!=10000}
        val data=com.droidacoustic.pro.scene.ClfData("TEST",sensitivityDbByBand=all.associateWith {100f},patterns=all.map {
            com.droidacoustic.pro.scene.ClfPolarPattern(it,listOf(0f),listOf(-90f,90f),arrayOf(floatArrayOf(0f,0f)))})
        val missing=request("A",response=FrozenResponse.measured(data,true)).captureComparison(targets,1,levelConvention="BROADBAND_SPL",broadbandCapture=true)
        assertNull(missing.run.samples.single().levelDb)
        val relative=request("A",response=FrozenResponse.measured(data,true)).captureComparison(targets,1,
            RelativeResponseNormalization(10.0,1.0,0.0,-120.0,"weighted-broadband-level-difference"),"RELATIVE_BROADBAND_TO_REFERENCE",true)
        assertNull(relative.run.samples.single().levelDb)
        assertEquals(JsonNull,Json.parseToJsonElement(relative.document).jsonObject.getValue("referenceBandDiagnostics").jsonObject["8000"])
        assertEquals(JsonNull,Json.parseToJsonElement(missing.document).jsonObject.getValue("bandDiagnostics").jsonArray.single()
            .jsonObject.getValue("bands").jsonObject["8000"])
    }
    @Test fun ambiguousMetricsWeightsSchemasAndOverlappingDefinitionsAreRejected() {
        assertFails {request().captureComparison(targets.map {it.copy(hz=1000.0)},1,levelConvention="BROADBAND_SPL",broadbandCapture=true)}
        assertFails {request().captureComparison(targets,1)}
        assertFails {request().captureComparison(targets,1,RelativeResponseNormalization(10.0,1.0,0.0,-120.0),"BROADBAND_SPL",true)}
        assertFails {BroadbandDefinition.create("A",listOf(1000,2000),.5f,OctaveWeighting.tables.getValue("A"))}
        assertFails {BroadbandDefinition.create("A",listOf(1000,1000),1f,OctaveWeighting.tables.getValue("A"))}
        assertFails {BroadbandDefinition.create("A",listOf(1000,2000),1f,OctaveWeighting.tables.getValue("C"))}
        val captured=request().captureComparison(targets,1,levelConvention="BROADBAND_SPL",broadbandCapture=true)
        val root=Json.parseToJsonElement(captured.document).jsonObject.toMutableMap();root["version"]=JsonPrimitive(1)
        assertFails {ImportedComparison.parse(JsonObject(root).toString())}
    }
}
