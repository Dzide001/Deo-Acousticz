package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs
import kotlin.test.*

class RelativeResponsePackageTest {
    @Test fun retainedNormalizedSnapshotsMatchIndependentReferencesAndGainInvariance() {
        val dir=File(System.getProperty("numericalValidationDir")).parentFile.resolve("relative-response")
        val referenceFile=dir.resolve("results/reference.json");val ref=Json.parseToJsonElement(referenceFile.readText()).jsonObject
        assertEquals(ref.getValue("referenceScriptSha256").jsonPrimitive.content,SpeakerProvenance.hash(dir.resolve("reference.py").readBytes()))
        val points=ref.getValue("samples").jsonArray.map {it.jsonObject}
        val targets=points.map {p -> SpeakerComparisonSample(p.getValue("id").jsonPrimitive.content,p.getValue("frequencyHz").jsonPrimitive.double,
            p.getValue("xM").jsonPrimitive.double,1.0,0.0,-999.0)}
        val norm=RelativeResponseNormalization.parse(ref.getValue("normalization").jsonObject)
        val captures=listOf(0f,12f).map {gain ->
            val source=SourceSnapshot(0,0f,1f,0f,0f,ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,
                FrozenResponse.generic(100f,"generic",false,false),gainDb=gain,
                comparisonData=ComparisonSourceData("SYNTHETIC 100dB omni",null,true,false))
            val request=PredictionRequest(PredictionTicket(1,1,PredictionChannel.LISTENER),listOf(source),emptyList(),1000,listOf(1000),
                1f/3,emptyMap(),true,Atmosphere())
            val captured=request.captureComparison(targets,1,norm,"RELATIVE_TO_ON_AXIS")
            val restored=ComparisonWorkspace.parse(ComparisonWorkspace.empty().withImported(captured,false).toJsonString())
            assertEquals(captured.document,restored.engine!!.document)
            val residuals=captured.run.samples.zip(points).map {(p,r)->p.levelDb!!-r.getValue("relativeDb").jsonPrimitive.double}
            val max=residuals.maxOf {abs(it)};assertTrue(max<=ref.getValue("toleranceDb").jsonPrimitive.double)
            buildJsonObject {
                put("gainDb",gain);put("snapshot",Json.parseToJsonElement(captured.document));put("residualsDb",JsonArray(residuals.map(::JsonPrimitive)))
                put("maximumErrorDb",max);put("roundTripPassed",true)
            }
        }
        val out=File(System.getProperty("relativeResponseReport"));out.parentFile.mkdirs()
        out.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("scope",ref.getValue("scope"));put("referenceSha256",SpeakerProvenance.hash(referenceFile.readBytes()))
            put("cases",JsonArray(captures));put("allPassed",true)
        })+"\n")
    }
}
