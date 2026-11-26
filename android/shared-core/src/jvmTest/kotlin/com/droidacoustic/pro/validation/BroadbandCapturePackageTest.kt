package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs
import kotlin.test.*

class BroadbandCapturePackageTest {
    @Test fun weightedSnapshotsMatchIndependentFiniteBandPowerReferences() {
        val dir=File(System.getProperty("numericalValidationDir")).parentFile.resolve("broadband-capture")
        val file=dir.resolve("results/reference.json");val ref=Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(ref.getValue("scriptSha256").jsonPrimitive.content,SpeakerProvenance.hash(dir.resolve("reference.py").readBytes()))
        val results=ref.getValue("cases").jsonArray.map {row -> val expected=row.jsonObject
            val name=expected.getValue("weighting").jsonPrimitive.content;val x=expected.getValue("distanceM").jsonPrimitive.double
            val source=SourceSnapshot(0,0f,1f,0f,0f,ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,
                FrozenResponse.generic(100f,"generic",false,false),comparisonData=ComparisonSourceData("SYNTHETIC 100dB omni",null,true,false))
            val request=PredictionRequest(PredictionTicket(1,1,PredictionChannel.LISTENER),listOf(source),emptyList(),1000,OctaveWeighting.bands,
                1f,OctaveWeighting.tables.getValue(name),true,Atmosphere(),comparisonWeighting=name)
            val snapshot=request.captureComparison(listOf(SpeakerComparisonSample("r",0.0,x,1.0,0.0,-999.0)),1,
                levelConvention="BROADBAND_SPL",broadbandCapture=true)
            val residual=snapshot.run.samples.single().levelDb!!-expected.getValue("levelDb").jsonPrimitive.double
            assertTrue(abs(residual)<=ref.getValue("toleranceDb").jsonPrimitive.double,"Broadband residual $name at $x: $residual")
            val state=ComparisonWorkspace.empty().withImported(snapshot,false)
            assertEquals(snapshot.document,ComparisonWorkspace.parse(state.toJsonString()).engine!!.document)
            buildJsonObject {put("id",expected.getValue("id"));put("residualDb",residual);put("snapshot",Json.parseToJsonElement(snapshot.document));put("roundTripPassed",true)}
        }
        val out=File(System.getProperty("broadbandCaptureReport"));out.parentFile.mkdirs()
        out.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("scope",ref.getValue("scope"));put("referenceSha256",SpeakerProvenance.hash(file.readBytes()));put("cases",JsonArray(results));put("allPassed",true)
        })+"\n")
    }
}
