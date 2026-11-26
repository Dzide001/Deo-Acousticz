package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs
import kotlin.test.*

class AsymmetricDirectivityPackageTest {
    @Test fun productionBandPredictionsRetainHandednessAcrossPosesInterpolationAndRecovery() {
        val dir=File(System.getProperty("numericalValidationDir")).parentFile.resolve("asymmetric-directivity")
        val referenceFile=dir.resolve("results/reference.json")
        val ref=Json.parseToJsonElement(referenceFile.readText()).jsonObject
        assertEquals(ref.getValue("scriptSha256").jsonPrimitive.content,SpeakerProvenance.hash(dir.resolve("reference.py").readBytes()))
        val original=assertNotNull(ClfParser.parseJson(dir.resolve("speaker.json").readText()))
        val restored=assertNotNull(ClfParser.parseJson(ClfParser.toJson(original)))
        assertEquals(original.patterns,restored.patterns)
        val mirrored=original.copy(patterns=original.patterns.map {p->p.copy(azimuths=p.azimuths.map {-it})})
        var rejectedMirrors=0
        val cases=ref.getValue("cases").jsonArray.map {element->
            val row=element.jsonObject
            fun f(key:String)=row.getValue(key).jsonPrimitive.float
            fun receiver(key:String)=row.getValue(key).jsonArray.let {ReceiverSnapshot(it[0].jsonPrimitive.float,it[1].jsonPrimitive.float,it[2].jsonPrimitive.float)}
            fun level(data:ClfData):Float {
                val source=SourceSnapshot(42,2f,3f,-4f,f("yaw"),listOf(ArrayGeometry.Pose(0f,0f,f("tiltDown"),0f)),0f,0f,FrozenResponse.measured(data,true))
                val hz=row.getValue("frequency").jsonPrimitive.int
                val output=PredictionRequest(PredictionTicket(1,1,PredictionChannel.COVERAGE),listOf(source),
                    listOf(receiver("receiver"),receiver("onAxis")),hz,listOf(hz),1f/3,emptyMap(),true,Atmosphere()).calculate()
                return output.levelAt(0)-output.levelAt(1)
            }
            val actual=level(original);val recovered=level(restored);val mutant=level(mirrored)
            val residual=actual-f("relativeDb")
            val tolerance=ref.getValue("toleranceDb").jsonPrimitive.double
            assertTrue(abs(residual)<=tolerance,"Direction residual $row: $residual")
            assertEquals(actual,recovered,0f)
            assertTrue(abs(mutant-f("mirroredDb"))<=tolerance)
            val rejected=abs(mutant-f("relativeDb"))>tolerance
            if(rejected) rejectedMirrors++
            buildJsonObject {
                row.forEach {(k,v)->put(k,v)}
                put("actualRelativeDb",actual);put("residualDb",residual);put("reopenedRelativeDb",recovered)
                put("mirroredRelativeDb",mutant);put("mirrorRejected",rejected)
            }
        }
        assertEquals(180,rejectedMirrors) // On-axis alone cannot detect a horizontal mirror.
        val report=File(System.getProperty("asymmetricDirectivityReport"));report.parentFile.mkdirs()
        report.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("scope",ref.getValue("scope"));put("referenceSha256",SpeakerProvenance.hash(referenceFile.readBytes()))
            put("speakerSha256",SpeakerProvenance.hash(dir.resolve("speaker.json").readBytes()))
            put("allPassed",true);put("rejectedMirrors",rejectedMirrors);put("cases",JsonArray(cases))
        })+"\n")
    }
}
