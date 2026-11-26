package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.scene.ClfParser
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.*
import kotlin.test.*

class PublicMeasurementComparisonTest {
    @Test fun realCalibratedPaMeasurementsAreHeldOutAndReplayedThroughProductionCapture() {
        val root=File(System.getProperty("numericalValidationDir")).parentFile.resolve("public-measurements")
        val fixtures=Json.parseToJsonElement(root.resolve("fixtures.json").readText()).jsonObject
        val protocol=Json.parseToJsonElement(root.resolve("protocol.json").readText()).jsonObject
        assertEquals(fixtures.getValue("preparationScriptSha256").jsonPrimitive.content,SpeakerProvenance.hash(root.resolve("prepare.py").readBytes()))
        assertEquals(fixtures.getValue("protocolSha256").jsonPrimitive.content,SpeakerProvenance.hash(root.resolve("protocol.json").readBytes()))
        val reviews=Json.parseToJsonElement(root.resolve("reviewed-protocols.json").readText()).jsonObject
        val result=fixtures.getValue("cases").jsonArray.map {item ->
            val case=item.jsonObject;val id=case.getValue("id").jsonPrimitive.content
            val step=case.getValue("trainingStepDeg").jsonPrimitive.int
            val held=protocol.getValue("holdoutAnglesDeg").jsonArray.map {((it.jsonPrimitive.int%360)+360)%360}.toSet()
            assertTrue(case.getValue("trainingAnglesDeg").jsonArray.none {it.jsonPrimitive.int in held})
            val data=ClfParser.parseJson(case.getValue("asset").toString())!!
            assertEquals(DriveUnit.UNKNOWN,data.calibration.driveUnit)
            assertTrue(data.patterns.all {it.elevations==listOf(0f) && it.azimuths.size==360/step})
            val samples=case.getValue("samples").jsonArray.map {s -> s.jsonObject.let {
                SpeakerComparisonSample(it.getValue("id").jsonPrimitive.content,it.getValue("frequencyHz").jsonPrimitive.double,
                    it.getValue("xM").jsonPrimitive.double,it.getValue("yM").jsonPrimitive.double,it.getValue("zM").jsonPrimitive.double,
                    it.getValue("levelDb").jsonPrimitive.double)
            }}
            val norm=RelativeResponseNormalization(1.7,0.0,0.0,-200.0)
            val request=PredictionRequest(PredictionTicket(1,1,PredictionChannel.LISTENER),listOf(SourceSnapshot(0,0f,0f,0f,0f,
                ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,FrozenResponse.measured(data,true),
                comparisonData=ComparisonSourceData(SpeakerProvenance.hash(ClfParser.toJson(data).encodeToByteArray()),data.calibration,false))),
                emptyList(),1000,protocol.getValue("bandsHz").jsonArray.map {it.jsonPrimitive.int},1f/3,emptyMap(),true,Atmosphere())
            val engine=request.captureComparison(samples,1,norm,"RELATIVE_TO_ON_AXIS")
            assertFalse(engine.synthetic)
            val expected=case.getValue("expectedEngineLevelsDb").jsonArray.map {it.jsonPrimitive.double}
            val errors=engine.run.samples.zip(expected).map {(s,e)->abs(s.levelDb!!-e)}
            assertTrue(errors.max()<0.0001,"Production replay differs from independent interpolation: ${errors.max()}")
            val source=buildJsonObject {
                put("producer","Princeton University 3D3A Laboratory");put("version","2015-03-31; archive 2024-03-25")
                put("fileName","JBLPro_PRX635_H_IR.mat; prepare.py finite-band holdout ratios")
                put("instrument","B&K Type 4189-A-021 microphone");put("calibrationStatus","DECLARED")
                put("calibrationReference","B&K 4231: -11 dBFS=94 dB SPL, per 3D3A manual; no electrical-drive claim")
                put("method","Anechoic ESS; separate held-out directions from the same session, not independent-session evidence")
                put("smoothing","No published 1/24-octave plot smoothing; clipped-bin mean band power from windowed original IR")
                put("timeWindow","Tukey 0.5 ms rise, 3 ms flat, 0.5 ms fall; documented onset sample 4096")
                put("sourceUrl",protocol.getValue("sourceUrl"));put("originalMatSha256",fixtures.getValue("sourceMatSha256"))
                put("license","CC-BY-4.0");put("preparationScriptSha256",fixtures.getValue("preparationScriptSha256"))
            }
            val rawReference=ImportedComparison.parse(buildJsonObject {
                put("schema","droidacoustic.comparison-import");put("version",2);put("evidence","CONTROLLED_MEASUREMENT");put("synthetic",false)
                put("source",source);put("normalization",norm.json());put("toleranceDb",protocol.getValue("maximumSampleErrorDb"))
                putJsonObject("conditions") {
                    put("modelRevision","Princeton PRX635 2015-03-31; raw IR ${fixtures.getValue("sourceMatSha256").jsonPrimitive.content}")
                    put("preset","Observed measured system; original operating preset undocumented; training ${step}deg")
                    put("geometry","Horizontal orbit 1.7 m; microphone at tweeter; rotation through cabinet centre; no boundaries")
                    put("frequencyConvention","Windowed IR; independent clipped FFT-bin integration over one-third-octave bands")
                    put("levelConvention","RELATIVE_TO_ON_AXIS");put("atmosphere","Not recorded; equal-radius direct/on-axis ratios cancel path air loss")
                    put("ground","Anechoic time-windowed direct sound");put("driveUnit","UNKNOWN")
                }
                putJsonObject("units") {put("frequency","Hz");put("position","m");put("level","dB_relative")}
                put("format","JSON_SAMPLES");put("payload",case.getValue("samples").toString())
            }.toString())
            val reference=reviews.getValue(id).jsonObject.let {review ->
                val fields=review.getValue("fields").jsonArray.map {v -> v.jsonObject.let {f ->
                    ComparisonProtocolFieldReview(f.getValue("field").jsonPrimitive.content,f.getValue("originalValue").jsonPrimitive.content,
                        f.getValue("reviewedValue").jsonPrimitive.content,f.getValue("rationale").jsonPrimitive.content,f.getValue("evidenceUrl").jsonPrimitive.content)
                }}
                ComparisonProtocolReview.annotate(rawReference,review.getValue("originalDocumentSha256").jsonPrimitive.content,review.getValue("reviewer").jsonPrimitive.content,fields)
            }
            val savedReference=ImportedComparison.parse(root.resolve("imports/$id-reference.json").readText())
            assertEquals(reference.run,savedReference.run)
            assertEquals(reference.normalization,savedReference.normalization)
            val savedEngine=ImportedComparison.parse(root.resolve("imports/$id-engine.json").readText())
            assertEquals(engine.run.conditions,savedEngine.run.conditions)
            savedEngine.run.samples.zip(engine.run.samples).forEach {(saved,actual)->assertEquals(saved.levelDb!!,actual.levelDb!!,1e-4)}
            val w=ComparisonWorkspace.empty().withImported(reference,true).withImported(engine,false)
            val validation=Json.parseToJsonElement(w.validationReportJson()).jsonObject
            assertEquals(w.validationReportJson(),ComparisonWorkspace.parse(w.toJsonString()).validationReportJson())
            val m=validation.getValue("metrics").jsonObject
            assertEquals(case.getValue("expectedRmsDb").jsonPrimitive.double,m.getValue("rmsErrorDb").jsonPrimitive.double,1e-4)
            assertEquals(case.getValue("expectedMaxDb").jsonPrimitive.double,m.getValue("maximumAbsoluteErrorDb").jsonPrimitive.double,1e-4)
            run {
                assertEquals("FAILED",validation.getValue("summary").jsonObject.getValue("comparison").jsonObject.getValue("status").jsonPrimitive.content)
                assertEquals(0,validation.getValue("protocolDifferenceCount").jsonPrimitive.int)
                assertTrue(m.getValue("qualified").jsonPrimitive.boolean) // Comparable, NOT an accuracy pass.
            }
            buildJsonObject {
                put("id",id);put("rawReferenceDocument",rawReference.document);put("engineDocument",engine.document)
                put("validation",validation);put("maxNumericalReplayErrorDb",errors.max())
                put("rmsAcceptancePassed",m.getValue("rmsErrorDb").jsonPrimitive.double<=protocol.getValue("maximumRmsErrorDb").jsonPrimitive.double)
            }
        }
        val output=File(System.getProperty("publicMeasurementReport"));output.parentFile.mkdirs()
        output.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("scope",fixtures.getValue("scope"));put("protocolReviewed",true);put("cases",JsonArray(result));put("limitations",protocol.getValue("limitations"))
        })+"\n")
    }
}
