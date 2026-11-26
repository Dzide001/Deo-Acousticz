package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.ClfParser
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.*
import kotlin.test.*

class AngularSupportPackageTest {
    @Test fun measuredHoldoutsRemainSparseAndUnsupportedDirectionsAreExplicit() {
        val root=File(System.getProperty("numericalValidationDir")).parentFile
        val input=root.resolve("public-measurements/fixtures.json")
        val fixture=Json.parseToJsonElement(input.readText()).jsonObject
        val cases=fixture.getValue("cases").jsonArray.map {row ->
            val c=row.jsonObject;val data=ClfParser.parseJson(c.getValue("asset").toString())!!
            val step=c.getValue("trainingStepDeg").jsonPrimitive.float
            val samples=c.getValue("samples").jsonArray.map {v ->
                val p=v.jsonObject;val hz=p.getValue("frequencyHz").jsonPrimitive.int
                val az=(atan2(p.getValue("zM").jsonPrimitive.double,p.getValue("xM").jsonPrimitive.double)*180/PI).toFloat()
                val support=data.patternAt(hz)!!.sharedGrid.angularSupport
                val s=support.at(az,0f)
                assertEquals("SPARSE_INTERPOLATION",s.status);assertEquals(step,s.azimuthBracketDeg)
                assertEquals("OUTSIDE_ELEVATION",support.at(az,1f).status)
                assertTrue(data.measuredRadiator().levelDb(hz,az,1f).isNaN())
                buildJsonObject {put("sampleId",p.getValue("id"));put("status",s.status);put("azimuthBracketDeg",s.azimuthBracketDeg!!)}
            }
            val warning=SpeakerAngularSupport.warnings(data)
            assertTrue(warning.any {"azimuth gaps" in it});assertTrue(warning.any {"other elevations are unsupported" in it})
            assertEquals(SpeakerAngularSupport.reportJson(data),SpeakerAngularSupport.reportJson(ClfParser.parseJson(ClfParser.toJson(data))!!))
            buildJsonObject {
                put("id",c.getValue("id"));put("support",Json.parseToJsonElement(SpeakerAngularSupport.reportJson(data)))
                put("warnings",JsonArray(warning.map(::JsonPrimitive)));put("samples",JsonArray(samples))
                put("retainedMeasuredAccuracyRmsDb",c.getValue("expectedRmsDb"));put("retainedMeasuredAccuracyMaxDb",c.getValue("expectedMaxDb"))
                put("measuredAccuracyScreen","FAILED; warnings do not correct interpolation error")
            }
        }
        val output=File(System.getProperty("angularSupportReport"));output.parentFile.mkdirs()
        output.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("schema",1);put("sourceFixtureSha256",SpeakerProvenance.hash(input.readBytes()))
            put("scope","Sampling diagnostics; source values and original accuracy thresholds unchanged");put("cases",JsonArray(cases))
        })+"\n")
    }
}
