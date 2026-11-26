package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import java.io.File
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.test.*

class EngineSceneCapturePackageTest {
    @Test fun capturedAndReopenedEngineSnapshotsMatchIndependentPointSourceReference() {
        val directory=File(System.getProperty("numericalValidationDir")).parentFile.resolve("engine-capture")
        val referenceFile=directory.resolve("results/reference.json")
        val ref=Json.parseToJsonElement(referenceFile.readText()).jsonObject
        assertEquals(ref.getValue("referenceScriptSha256").jsonPrimitive.content,SpeakerProvenance.hash(directory.resolve("reference.py").readBytes()))
        val rows=ref.getValue("samples").jsonArray.map {it.jsonObject}
        val targets=rows.map {p -> SpeakerComparisonSample(p.getValue("id").jsonPrimitive.content,p.getValue("hz").jsonPrimitive.double,
            p.getValue("x").jsonPrimitive.double,1.0,0.0,-999.0)}
        fun capture(gain: Float): ImportedComparison {
            val source=SourceSnapshot(0,0f,1f,0f,0f,ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,
                FrozenResponse.generic(100f,"generic",false,false),gainDb=gain,
                comparisonData=ComparisonSourceData("synthetic-test-100dB-omni",ReferenceCalibration(1.0,DriveUnit.WATTS,1.0,"TEST ONLY"),true,false))
            return PredictionRequest(PredictionTicket(7,1,PredictionChannel.LISTENER),listOf(source),emptyList(),1000,listOf(1000),
                1f/3,emptyMap(),true,Atmosphere()).captureComparison(targets,7)
        }
        val snapshots=listOf(0f,6f).map {gain ->
            val data=capture(gain)
            val state=ComparisonWorkspace.empty().withImported(data,false)
            val restored=ComparisonWorkspace.parse(state.toJsonString())
            assertEquals(data.document,restored.engine!!.document)
            val residuals=data.run.samples.zip(rows).map {(actual,expected) -> actual.levelDb!!-
                expected.getValue(if(gain==0f) "levelDb" else "levelDbWithSixDbGain").jsonPrimitive.double}
            val max=residuals.maxOf {abs(it)}
            assertTrue(max<=ref.getValue("toleranceDb").jsonPrimitive.double,"Capture accuracy: $residuals")
            assertTrue(data.synthetic)
            buildJsonObject {
                put("gainDb",gain);put("snapshot",Json.parseToJsonElement(data.document));put("roundTripPassed",true)
                put("residualsDb",JsonArray(residuals.map(::JsonPrimitive)));put("maxAbsoluteErrorDb",max)
            }
        }
        val out=File(System.getProperty("engineCaptureReport"));out.parentFile.mkdirs()
        out.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("scope",ref.getValue("scope"));put("referenceSha256",SpeakerProvenance.hash(referenceFile.readBytes()))
            put("cases",JsonArray(snapshots));put("allPassed",true)
        })+"\n")
    }
}
