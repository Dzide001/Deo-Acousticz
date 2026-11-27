package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs
import kotlin.test.*

class ClfHandednessPackageTest {
    @Test fun declaredAsymmetricTextMagnitudesReachProductionPredictionsWithCorrectHandedness() {
        val dir=File(System.getProperty("clfFixtureValidationRoot"),"validation").resolve("clf-handedness")
        val ref=Json.parseToJsonElement(dir.resolve("results/reference.json").readText()).jsonObject
        val provenance=Json.parseToJsonElement(dir.resolve("provenance.json").readText()).jsonObject
        assertEquals(ref.getValue("referenceScriptSha256").jsonPrimitive.content,SpeakerProvenance.hash(dir.resolve("reference.py").readBytes()))
        assertEquals(ref.getValue("provenanceSha256").jsonPrimitive.content,SpeakerProvenance.hash(dir.resolve("provenance.json").readBytes()))
        provenance.getValue("fixtureSha256").jsonObject.forEach {(name,hash)->assertEquals(hash.jsonPrimitive.content,SpeakerProvenance.hash(dir.resolve("fixtures/$name").readBytes()))}
        val text=dir.resolve("fixtures/"+(provenance["textFile"]?.jsonPrimitive?.content ?: "official-1000.tab")).readText()
        val lines=text.lines();val first=lines.indexOf("<BAND>\t1000")+1
        val arcs=lines.drop(first).filter {it.isNotBlank()}
        var rejected=0
        val reports=mutableListOf<JsonElement>()
        for((reverse,rotation) in listOf(false to 0,true to 0,false to 90,true to -90)) {
            // Alternate encodings of the same fixture balloon. The retained default
            // sample and coordinate references remain independent of these adaptations.
            val variant=lines.take(first).map {if(it.startsWith("<BALLOON-ARC-ORDER>"))
                "<BALLOON-ARC-ORDER>\t${if(reverse) "<reversed>" else "<default>"}\t<ROTATE>\t$rotation" else it}
                .plus(List(72) {i->arcs[(((if(reverse) -i else i)+rotation/5)%72+72)%72]}).joinToString("\n")
            val data=ClfTabParser.toClfData(ClfTabParser.parse(variant))
            assertEquals("clf-subset-v5:TAB",data.provenance.importer)
            val restored=assertNotNull(ClfParser.parseJson(ClfParser.toJson(data)))
            val legacy=data.copy(patterns=data.patterns.map {it.copy(azimuths=it.azimuths.map {a->-a})})
            for(element in ref.getValue("cases").jsonArray) {
                val row=element.jsonObject
                fun f(k:String)=row.getValue(k).jsonPrimitive.float
                fun receiver(k:String)=row.getValue(k).jsonArray.let {ReceiverSnapshot(it[0].jsonPrimitive.float,it[1].jsonPrimitive.float,it[2].jsonPrimitive.float)}
                fun predict(d:ClfData):Float {
                    val source=SourceSnapshot(0,2f,3f,-4f,f("yaw"),listOf(ArrayGeometry.Pose(0f,0f,f("tiltDown"),0f)),0f,0f,FrozenResponse.measured(d,true))
                    val out=PredictionRequest(PredictionTicket(1,1,PredictionChannel.COVERAGE),listOf(source),
                        listOf(receiver("receiver"),receiver("onAxis")),1000,listOf(1000),1f/3,emptyMap(),true,Atmosphere()).calculate()
                    return out.levelAt(0)-out.levelAt(1)
                }
                val actual=predict(data);val reopened=predict(restored);val old=predict(legacy)
                assertEquals(f("relativeDb"),actual,.001f,"$row reverse=$reverse rotation=$rotation")
                assertEquals(actual,reopened,0f);assertEquals(f("mirroredDb"),old,.001f)
                val mirrorRejected=abs(old-f("relativeDb"))>.001
                if(mirrorRejected) rejected++
                reports+=buildJsonObject {
                    row.forEach {(k,v)->put(k,v)};put("reversed",reverse);put("rotation",rotation)
                    put("actualDb",actual);put("residualDb",actual-f("relativeDb"));put("reopenedDb",reopened)
                    put("oldMappingDb",old);put("mirrorRejected",mirrorRejected)
                }
            }
        }
        assertEquals(128,rejected)
        val report=File(System.getProperty("clfHandednessReport"));report.parentFile.mkdirs()
        report.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("scope",ref.getValue("scope"));put("referenceSha256",SpeakerProvenance.hash(dir.resolve("results/reference.json").readBytes()))
            put("cases",JsonArray(reports));put("rejectedOldMappings",rejected);put("allPassed",true)
        })+"\n")
    }
}
