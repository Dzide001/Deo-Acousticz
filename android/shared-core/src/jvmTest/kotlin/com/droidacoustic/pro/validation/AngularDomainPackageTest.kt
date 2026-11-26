package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.*
import kotlin.test.*

class AngularDomainPackageTest {
    private fun domain(az:List<Float>,measured:List<Float>)=AngularDomain.parse(buildJsonObject {
        put("version",1);put("provenance","Princeton PRX635 pinned horizontal IR dataset: angular acquisition only; windowed/normalized band processing is not raw spectral data")
        putJsonArray("regions") {
            fun row(kind:String,a:List<Float>)=buildJsonObject {
                put("origin",kind);put("azimuths",JsonArray(a.map(::JsonPrimitive)));put("elevations",JsonArray(listOf(JsonPrimitive(0))))
                put("basis",if(kind=="MEASURED") "Directions present in the pinned original IR; training angular rows only" else "Computed only from retained training directions by dB-linear angular interpolation; no held-out measured levels used")
                if(kind=="INTERPOLATED") put("method","DB_LINEAR_ANGULAR")
            }
            add(row("MEASURED",measured));val derived=az.filter {it !in measured};if(derived.isNotEmpty()) add(row("INTERPOLATED",derived))
        }
    }.toString(),az,listOf(0f))
    @Test fun realMeasuredAndUpsampledStorageOriginsDoNotImproveHeldoutAccuracy() {
        val root=File(System.getProperty("numericalValidationDir")).parentFile
        val file=root.resolve("public-measurements/fixtures.json")
        val fixture=Json.parseToJsonElement(file.readText()).jsonObject
        val results=fixture.getValue("cases").jsonArray.flatMap {v ->
            val c=v.jsonObject;val original=ClfParser.parseJson(c.getValue("asset").toString())!!
            val samples=c.getValue("samples").jsonArray.map {s->s.jsonObject.let {p->SpeakerComparisonSample(p.getValue("id").jsonPrimitive.content,
                p.getValue("frequencyHz").jsonPrimitive.double,p.getValue("xM").jsonPrimitive.double,p.getValue("yM").jsonPrimitive.double,p.getValue("zM").jsonPrimitive.double,p.getValue("levelDb").jsonPrimitive.double)}}
            listOf(false,true).map {upsample ->
                val data=original.copy(patterns=original.patterns.map {p->
                    val az=if(upsample) (0 until 360 step 5).map {it.toFloat()} else p.azimuths
                    p.copy(azimuths=az,spl=az.map {floatArrayOf(p.at(it,0f))}.toTypedArray(),angularDomain=domain(az,p.azimuths))
                })
                val report=SpeakerAngularSupport.reportJson(data)
                val statuses=samples.map {p->
                    val angle=(atan2(p.z,p.x)*180/PI).toFloat()
                    // Snap only the diagnostic direction to its known experimental angle; prediction keeps original coordinates.
                    val experimental=round(angle/5)*5
                    val point=SpeakerAngularSupport.at(data,p.hz.toInt(),experimental,0f)!!
                    assertEquals("INTERPOLATED",point.origin);assertEquals(!upsample,point.runtimeInterpolation)
                    if(upsample) assertEquals(listOf(AngularSampleOrigin.INTERPOLATED),point.contributors)
                    else assertEquals(listOf(AngularSampleOrigin.MEASURED),point.contributors)
                    point
                }
                assertEquals(report,SpeakerAngularSupport.reportJson(ClfParser.parseJson(ClfParser.toJson(data))!!))
                assertNotEquals(ClfParser.toJson(original),ClfParser.toJson(data))
                val request=PredictionRequest(PredictionTicket(1,1,PredictionChannel.LISTENER),listOf(SourceSnapshot(0,0f,0f,0f,0f,
                    ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,FrozenResponse.measured(data,true),
                    comparisonData=ComparisonSourceData(SpeakerProvenance.hash(ClfParser.toJson(data).encodeToByteArray()),data.calibration,false))),
                    emptyList(),1000,listOf(500,1000,2000,4000,8000,16000),1f/3,emptyMap(),true,Atmosphere())
                val engine=request.captureComparison(samples,1,RelativeResponseNormalization(1.7,0.0,0.0,-200.0),"RELATIVE_TO_ON_AXIS")
                val expected=c.getValue("expectedEngineLevelsDb").jsonArray.map {it.jsonPrimitive.double}
                val numerical=engine.run.samples.zip(expected).maxOf {(p,e)->abs(p.levelDb!!-e)}
                assertTrue(numerical<1e-4)
                val errors=engine.run.samples.zip(samples).map {(p,r)->p.levelDb!!-r.levelDb!!}
                val rms=sqrt(errors.sumOf {it*it}/errors.size);val maximum=errors.maxOf {abs(it)}
                assertEquals(c.getValue("expectedRmsDb").jsonPrimitive.double,rms,1e-4)
                assertEquals(c.getValue("expectedMaxDb").jsonPrimitive.double,maximum,1e-4)
                assertTrue(maximum>1.0 && rms>.5) // Original screens stay FAILED, not a test-suite failure.
                buildJsonObject {
                    put("id",c.getValue("id"));put("storage",if(upsample) "5deg-upsampled-from-training-only" else "original-training-knots")
                    put("angularSupport",Json.parseToJsonElement(report));put("sampleCount",samples.size)
                    put("runtimeInterpolation",statuses.first().runtimeInterpolation);put("heldoutOrigin","INTERPOLATED")
                    put("heldoutContributors",JsonArray(statuses.first().contributors.map {JsonPrimitive(it.name)}))
                    put("rmsErrorDb",rms);put("maximumAbsoluteErrorDb",maximum);put("maximumNumericalReplayErrorDb",numerical)
                    put("originalAccuracyScreen","FAILED");put("warnings",JsonArray(SpeakerAngularSupport.warnings(data).map(::JsonPrimitive)))
                }
            }
        }
        val recovery=ClfParser.parseJson(root.resolve("angular-domains/recovery-fixture.json").readText())!!
        val output=File(System.getProperty("angularDomainReport"));output.parentFile.mkdirs()
        output.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("schema",1);put("sourceFixtureSha256",SpeakerProvenance.hash(file.readBytes()));put("cases",JsonArray(results))
            put("syntheticRecoveryFixture",Json.parseToJsonElement(SpeakerAngularSupport.reportJson(recovery)))
            put("scope","Declared angular provenance does not certify accuracy; denser storage does not add measured resolution")
        })+"\n")
    }
}
