package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.*
import kotlin.test.*

class ReceiverAngularPackageTest {
    @Test fun realReceiverDirectionsUseFrozenGeometryAndDoNotPromoteUpsampledDataToMeasurements() {
        val root=File(System.getProperty("numericalValidationDir")).parentFile
        val file=root.resolve("public-measurements/fixtures.json")
        val fixture=Json.parseToJsonElement(file.readText()).jsonObject
        val outputs=fixture.getValue("cases").jsonArray.flatMap {value->
            val c=value.jsonObject;val original=ClfParser.parseJson(c.getValue("asset").toString())!!
            listOf(false,true).map {dense->
                val data=original.copy(patterns=original.patterns.map {p->
                    val az=if(dense) (0 until 360 step 5).map {it.toFloat()} else p.azimuths
                    val declaration=AngularDomain.parse(buildJsonObject {
                        put("version",1);put("provenance","Pinned Princeton PRX635 angular training rows; no held-out measured values added")
                        putJsonArray("regions") {
                            fun block(kind:String,values:List<Float>)=buildJsonObject {
                                put("origin",kind);put("azimuths",JsonArray(values.map(::JsonPrimitive)));put("elevations",JsonArray(listOf(JsonPrimitive(0))))
                                put("basis",if(kind=="MEASURED") "Angular rows present in original calibrated IR dataset" else "Angular dB interpolation from training samples only")
                                if(kind=="INTERPOLATED") put("method","DB_LINEAR_ANGULAR")
                            }
                            add(block("MEASURED",p.azimuths));val added=az.filter {it !in p.azimuths};if(added.isNotEmpty()) add(block("INTERPOLATED",added))
                        }
                    }.toString(),az,listOf(0f))
                    p.copy(azimuths=az,spl=az.map {floatArrayOf(p.at(it,0f))}.toTypedArray(),angularDomain=declaration)
                })
                val reports=c.getValue("samples").jsonArray.groupBy {it.jsonObject.getValue("frequencyHz").jsonPrimitive.int}.map {(hz,points)->
                    val request=PredictionRequest(PredictionTicket(8,1,PredictionChannel.LISTENER),listOf(SourceSnapshot(9,0f,0f,0f,0f,
                        ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,FrozenResponse.measured(data,true),
                        comparisonData=ComparisonSourceData(SpeakerProvenance.hash(ClfParser.toJson(data).encodeToByteArray()),data.calibration,false))),
                        emptyList(),hz,listOf(hz),1f/3,emptyMap(),true,Atmosphere())
                    val samples=points.map {v->v.jsonObject.let {p->SpeakerComparisonSample(p.getValue("id").jsonPrimitive.content,hz.toDouble(),
                        p.getValue("xM").jsonPrimitive.double,p.getValue("yM").jsonPrimitive.double,p.getValue("zM").jsonPrimitive.double,p.getValue("levelDb").jsonPrimitive.double)}}
                    val report=request.receiverAngularSupport(samples.map {AngularReceiver(it.id,it.id,BoundaryPoint(it.x,it.y,it.z))})
                    assertEquals(12,report.receivers.size);assertEquals(288,report.markerLines().size)
                    report.receivers.forEach {r->
                        assertEquals("INTERPOLATED",r.origin)
                        assertTrue(r.rows.all {it.sourceId==9 && it.elementIndex==0 && it.dataHz==hz})
                        if(!dense) assertEquals("SPARSE",r.support) else assertTrue(r.support in listOf("STORED","INTERPOLATED"))
                        assertTrue(r.rows.all {AngularSampleOrigin.UNDECLARED !in it.probe.point!!.contributors})
                    }
                    val before=report.toJsonString()
                    val engine=request.captureComparison(samples,8,RelativeResponseNormalization(1.7,0.0,0.0,-200.0),"RELATIVE_TO_ON_AXIS")
                    assertEquals(before,request.receiverAngularSupport(samples.map {AngularReceiver(it.id,it.id,BoundaryPoint(it.x,it.y,it.z))}).toJsonString())
                    val all=c.getValue("samples").jsonArray.map {it.jsonObject.getValue("id").jsonPrimitive.content}
                    val expected=c.getValue("expectedEngineLevelsDb").jsonArray.map {it.jsonPrimitive.double}
                    engine.run.samples.forEach {p->assertEquals(expected[all.indexOf(p.id)],p.levelDb!!,1e-4)}
                    Json.parseToJsonElement(before)
                }
                buildJsonObject {put("id",c.getValue("id"));put("storage",if(dense) "upsampled5deg" else "originalTrainingGrid")
                    put("bands",JsonArray(reports));put("originalRmsErrorDb",c.getValue("expectedRmsDb"));put("originalMaxErrorDb",c.getValue("expectedMaxDb"));put("originalAccuracyScreen","FAILED")}
            }
        }
        val output=File(System.getProperty("receiverAngularReport"));output.parentFile.mkdirs()
        output.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("schema",1);put("sourceFixtureSha256",SpeakerProvenance.hash(file.readBytes()));put("cases",JsonArray(outputs))
            put("scope","Receiver-level direct-ray metadata and marker geometry; original real-data accuracy failures unchanged")
        })+"\n")
    }
}
