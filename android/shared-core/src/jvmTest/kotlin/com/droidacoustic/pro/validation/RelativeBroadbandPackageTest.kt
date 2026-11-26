package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs
import kotlin.test.*

class RelativeBroadbandPackageTest {
    @Test fun weightedTotalRatiosMatchIndependentReferencesAndPersist() {
        val dir=File(System.getProperty("numericalValidationDir")).parentFile.resolve("relative-broadband")
        val file=dir.resolve("results/reference.json");val ref=Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(ref.getValue("scriptSha256").jsonPrimitive.content,SpeakerProvenance.hash(dir.resolve("reference.py").readBytes()))
        val norm=RelativeResponseNormalization(10.0,1.0,0.0,-120.0,"weighted-broadband-level-difference")
        val results=ref.getValue("cases").jsonArray.flatMap {row -> val expected=row.jsonObject
            listOf(0f,12f).map {gain ->
                val name=expected.getValue("weighting").jsonPrimitive.content;val x=expected.getValue("distanceM").jsonPrimitive.double
                val source=SourceSnapshot(0,0f,1f,0f,0f,ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,
                    FrozenResponse.generic(100f,"generic",false,false),gainDb=gain,comparisonData=ComparisonSourceData("SYNTHETIC 100dB omni",null,true,false))
                val request=PredictionRequest(PredictionTicket(1,1,PredictionChannel.LISTENER),listOf(source),emptyList(),1000,OctaveWeighting.bands,
                    1f,OctaveWeighting.tables.getValue(name),true,Atmosphere(),comparisonWeighting=name)
                val capture=request.captureComparison(listOf(SpeakerComparisonSample("r",0.0,x,1.0,0.0,-999.0)),1,norm,"RELATIVE_BROADBAND_TO_REFERENCE",true)
                val residual=capture.run.samples.single().levelDb!!-expected.getValue("relativeDb").jsonPrimitive.double
                assertTrue(abs(residual)<=ref.getValue("toleranceDb").jsonPrimitive.double,"Relative weighted residual: $residual")
                val saved=ComparisonWorkspace.empty().withImported(capture,false)
                assertEquals(capture.document,ComparisonWorkspace.parse(saved.toJsonString()).engine!!.document)
                buildJsonObject {put("weighting",name);put("distanceM",x);put("gainDb",gain);put("residualDb",residual)
                    put("snapshot",Json.parseToJsonElement(capture.document));put("roundTripPassed",true)}
            }
        }
        val out=File(System.getProperty("relativeBroadbandReport"));out.parentFile.mkdirs()
        out.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("scope",ref.getValue("scope"));put("referenceSha256",SpeakerProvenance.hash(file.readBytes()));put("cases",JsonArray(results));put("allPassed",true)
        })+"\n")
    }
}
