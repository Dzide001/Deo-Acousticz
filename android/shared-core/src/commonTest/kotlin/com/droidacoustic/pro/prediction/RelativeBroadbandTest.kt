package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class RelativeBroadbandTest {
    private val targets=listOf(SpeakerComparisonSample("same",0.0,10.0,1.0,0.0,999.0),SpeakerComparisonSample("far",0.0,20.0,1.0,0.0,-999.0))
    private val norm=RelativeResponseNormalization(10.0,1.0,0.0,-120.0,"weighted-broadband-level-difference")
    private fun source(x: Float=0f,gain: Float=0f,inverted: Boolean=false)=SourceSnapshot(0,x,1f,0f,0f,
        ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,FrozenResponse.generic(100f,"generic",false,false),gainDb=gain,inverted=inverted,
        comparisonData=ComparisonSourceData("TEST ONLY",null,true,false))
    private fun request(weight: String="A",sources: List<SourceSnapshot> = listOf(source()))=PredictionRequest(
        PredictionTicket(1,1,PredictionChannel.LISTENER),sources,emptyList(),1000,OctaveWeighting.bands,1f,
        OctaveWeighting.tables.getValue(weight),true,Atmosphere(),comparisonWeighting=weight)
    private fun capture(w: String="A",gain: Float=0f,n: RelativeResponseNormalization=norm)=
        request(w,listOf(source(gain=gain))).captureComparison(targets,1,n,"RELATIVE_BROADBAND_TO_REFERENCE",true)
    @Test fun normalizesWeightedTotalsRatherThanAveragingBandRatios() {
        val a=capture();val root=Json.parseToJsonElement(a.document).jsonObject
        val reference=root.getValue("referenceBandDiagnostics").jsonObject
        val far=root.getValue("bandDiagnostics").jsonArray[1].jsonObject.getValue("bands").jsonObject
        fun total(p: JsonObject)=p.entries.sumOf {(hz,value)->value.jsonPrimitive.double*10.0.pow(OctaveWeighting.tables.getValue("A").getValue(hz.toInt())/10.0)}
        val expected=10*log10(total(far)/total(reference))
        assertEquals(expected,a.run.samples[1].levelDb!!,1e-10)
        val wrong=far.entries.map {(hz,value)->10*log10(value.jsonPrimitive.double/reference.getValue(hz).jsonPrimitive.double)}.average()
        assertTrue(abs(wrong-expected)>1e-4)
        assertEquals(0.0,a.run.samples[0].levelDb!!,1e-10)
        val gained=capture(gain=12f)
        a.run.samples.zip(gained.run.samples).forEach {(x,y)->assertEquals(x.levelDb!!,y.levelDb!!,1e-7)}
        assertEquals(4,root.getValue("version").jsonPrimitive.int)
        val saved=ComparisonWorkspace.empty().withImported(a,false)
        assertEquals(a.document,ComparisonWorkspace.parse(saved.toJsonString()).engine!!.document)
    }
    @Test fun invalidWeightedReferenceAndChangedProtocolsCannotQualify() {
        assertTrue(capture(n=norm.copy(minimumReferenceLevelDb=150.0)).run.samples.all {it.levelDb==null})
        val cancelled=request(sources=listOf(source(-1f),source(1f,inverted=true)))
        val nullNorm=norm.copy(xM=0.0,zM=10.0,minimumReferenceLevelDb=-60.0)
        assertTrue(cancelled.captureComparison(targets,1,levelConvention="BROADBAND_SPL",broadbandCapture=true).run.samples.all {it.levelDb!=null})
        assertTrue(cancelled.captureComparison(targets,1,nullNorm,"RELATIVE_BROADBAND_TO_REFERENCE",true).run.samples.all {it.levelDb==null})
        val a=capture();val c=capture("C")
        val status=Json.parseToJsonElement(SpeakerComparisons.compare(a.run.copy(evidence=ComparisonEvidence.MANUFACTURER_SOFTWARE_EXPORT),c.run,.1))
            .jsonObject.getValue("status").jsonPrimitive.content
        assertEquals("NOT_COMPARABLE",status)
        assertFails {capture(n=norm.copy(method="per-frequency-level-difference"))}
        val root=Json.parseToJsonElement(a.document).jsonObject.toMutableMap();root["version"]=JsonPrimitive(3)
        assertFails {ImportedComparison.parse(JsonObject(root).toString())}
        val units=root.getValue("units").jsonObject.toMutableMap();units["level"]=JsonPrimitive("dB_A");root["units"]=JsonObject(units);root["version"]=JsonPrimitive(4)
        assertFails {ImportedComparison.parse(JsonObject(root).toString())}
    }
}
