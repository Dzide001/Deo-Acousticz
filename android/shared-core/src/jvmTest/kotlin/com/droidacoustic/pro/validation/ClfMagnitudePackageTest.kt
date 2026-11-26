package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.*
import kotlin.test.*

class ClfMagnitudePackageTest {
    @Test fun officialCf1TabDeclarationsReachProductionPredictionsAndPortableRecovery() {
        val dir=File(System.getProperty("numericalValidationDir")).parentFile.resolve("clf-magnitude")
        val ref=Json.parseToJsonElement(dir.resolve("results/reference.json").readText()).jsonObject
        assertEquals(ref.getValue("scriptSha256").jsonPrimitive.content,SpeakerProvenance.hash(dir.resolve("reference.py").readBytes()))
        assertEquals(ref.getValue("provenanceSha256").jsonPrimitive.content,SpeakerProvenance.hash(dir.resolve("provenance.json").readBytes()))
        val reports=ref.getValue("cases").jsonArray.flatMap {element->listOf(0f,90f).map {yaw->
            val row=element.jsonObject
            val data=ClfTabParser.toClfData(ClfTabParser.parse(dir.resolve("fixtures/"+row.getValue("file").jsonPrimitive.content).readText()))
            val reopened=assertNotNull(ClfParser.parseJson(ClfParser.toJson(data)))
            val az=(row.getValue("azimuth").jsonPrimitive.double+yaw)*PI/180
            val el=row.getValue("elevation").jsonPrimitive.double*PI/180
            val y=yaw*PI/180
            fun predict(d:ClfData):Float {
                val source=SourceSnapshot(0,0f,15f,0f,yaw,listOf(ArrayGeometry.Pose(0f,0f,0f,0f)),0f,0f,FrozenResponse.measured(d,true))
                val result=PredictionRequest(PredictionTicket(1,1,PredictionChannel.COVERAGE),listOf(source),
                    listOf(ReceiverSnapshot((10*cos(el)*cos(az)).toFloat(),(15+10*sin(el)).toFloat(),(10*cos(el)*sin(az)).toFloat()),
                        ReceiverSnapshot((10*cos(y)).toFloat(),15f,(10*sin(y)).toFloat())),1000,listOf(1000),1f/3,emptyMap(),true,Atmosphere()).calculate()
                return result.levelAt(0)-result.levelAt(1)
            }
            val actual=predict(data);val restored=predict(reopened);val expected=row.getValue("relativeDb").jsonPrimitive.float
            assertEquals(expected,actual,.001f,"$row yaw=$yaw");assertEquals(actual,restored,0f)
            buildJsonObject {row.forEach {(k,v)->put(k,v)};put("yaw",yaw);put("actualDb",actual);put("residualDb",actual-expected);put("reopenedDb",restored)}
        }}
        val out=File(System.getProperty("clfMagnitudeReport"));out.parentFile.mkdirs()
        out.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("scope",ref.getValue("scope"));put("referenceSha256",SpeakerProvenance.hash(dir.resolve("results/reference.json").readBytes()))
            put("cases",JsonArray(reports));put("allPassed",true)
        })+"\n")
    }
}
