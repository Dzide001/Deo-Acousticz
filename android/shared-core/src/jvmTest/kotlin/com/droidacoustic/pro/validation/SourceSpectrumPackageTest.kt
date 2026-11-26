package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs
import kotlin.test.*

class SourceSpectrumPackageTest {
    @Test fun normalizedSpectrumSnapshotsMatchIndependentReferencesAndPersist() {
        val dir=File(System.getProperty("numericalValidationDir")).parentFile.resolve("source-spectrum")
        val file=dir.resolve("results/reference.json");val ref=Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(ref.getValue("scriptSha256").jsonPrimitive.content,SpeakerProvenance.hash(dir.resolve("reference.py").readBytes()))
        val cases=ref.getValue("cases").jsonArray.map {row -> val expected=row.jsonObject
            val mode=expected.getValue("mode").jsonPrimitive.content
            val spectrum=if(mode=="TOTAL_CUSTOM_BAND_POWER") BroadbandSourceSpectrum.custom(OctaveWeighting.bands.zip(listOf(8.0,4.0,2.0,1.0,1.0,2.0,4.0,8.0)).toMap()) else BroadbandSourceSpectrum.preset(mode)
            val weight=expected.getValue("weighting").jsonPrimitive.content;val x=expected.getValue("distanceM").jsonPrimitive.double
            val source=SourceSnapshot(0,0f,1f,0f,0f,ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,FrozenResponse.generic(100f,"generic",false,false),
                comparisonData=ComparisonSourceData("SYNTHETIC 100dB omni",null,true,false))
            val request=PredictionRequest(PredictionTicket(1,1,PredictionChannel.LISTENER),listOf(source),emptyList(),1000,OctaveWeighting.bands,
                1f,OctaveWeighting.tables.getValue(weight),true,Atmosphere(),comparisonWeighting=weight,comparisonSpectrum=spectrum)
            val capture=request.captureComparison(listOf(SpeakerComparisonSample("r",0.0,x,1.0,0.0,-999.0)),1,levelConvention="BROADBAND_SPL",broadbandCapture=true)
            val residual=capture.run.samples.single().levelDb!!-expected.getValue("levelDb").jsonPrimitive.double
            assertTrue(abs(residual)<=ref.getValue("toleranceDb").jsonPrimitive.double,"Source spectrum residual $mode: $residual")
            expected.getValue("fractions").jsonObject.forEach {(hz,p)->assertEquals(p.jsonPrimitive.double,capture.broadband!!.driveFractions.getValue(hz.toInt()),1e-12)}
            assertEquals(capture.document,ComparisonWorkspace.parse(ComparisonWorkspace.empty().withImported(capture,false).toJsonString()).engine!!.document)
            val relative=request.captureComparison(listOf(SpeakerComparisonSample("r",0.0,x,1.0,0.0,null)),1,
                RelativeResponseNormalization(10.0,1.0,0.0,-120.0,"weighted-broadband-level-difference"),"RELATIVE_BROADBAND_TO_REFERENCE",true)
            val denominator=ref.getValue("cases").jsonArray.map {it.jsonObject}.single {
                it.getValue("mode").jsonPrimitive.content==mode && it.getValue("weighting").jsonPrimitive.content==weight && it.getValue("distanceM").jsonPrimitive.double==10.0
            }.getValue("levelDb").jsonPrimitive.double
            val relativeResidual=relative.run.samples.single().levelDb!!-(expected.getValue("levelDb").jsonPrimitive.double-denominator)
            assertTrue(abs(relativeResidual)<=1e-7,"Relative source spectrum residual $mode: $relativeResidual")
            assertEquals(relative.document,ComparisonWorkspace.parse(ComparisonWorkspace.empty().withImported(relative,false).toJsonString()).engine!!.document)
            buildJsonObject {put("mode",mode);put("weighting",weight);put("distanceM",x);put("residualDb",residual)
                put("snapshot",Json.parseToJsonElement(capture.document));put("relativeSnapshot",Json.parseToJsonElement(relative.document));put("relativeResidualDb",relativeResidual);put("roundTripPassed",true)}
        }
        val out=File(System.getProperty("sourceSpectrumReport"));out.parentFile.mkdirs()
        out.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("scope",ref.getValue("scope"));put("referenceSha256",SpeakerProvenance.hash(file.readBytes()));put("cases",JsonArray(cases));put("allPassed",true)
        })+"\n")
    }
}
