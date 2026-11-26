package com.droidacoustic.pro.acoustics

import kotlinx.serialization.json.*
import kotlin.test.*

class ComparisonImportsTest {
    private fun document(payload: String="Hz,Level\r\n1000,80\r\n2000,\r\n",evidence: String="MANUFACTURER_SOFTWARE_EXPORT",
        synthetic: Boolean=false,calibration: String="DECLARED",format: String="CSV"): String = buildJsonObject {
        put("schema","droidacoustic.comparison-import");put("version",1);put("evidence",evidence);put("synthetic",synthetic)
        putJsonObject("source") {put("producer","TEST-ONLY declaration");put("version","1");put("fileName","test.csv")
            put("instrument","test mic");put("method","test method");put("smoothing","none");put("timeWindow","test")
            put("calibrationStatus",calibration);put("calibrationReference","test only")}
        putJsonObject("conditions") {
            listOf("modelRevision","preset","geometry","frequencyConvention","atmosphere","ground").forEach {put(it,"test-protocol")}
            put("levelConvention","ABSOLUTE_SPL");put("driveUnit","WATTS");put("driveValue",1.0)
        }
        putJsonObject("units") {put("frequency","Hz");put("position","m");put("level","dB_SPL")}
        put("toleranceDb",0.1);put("format",format);put("delimiter",",");put("payload",payload)
        putJsonObject("columns") {put("frequencyHz","Hz");put("levelDb","Level")}
        putJsonObject("receiver") {put("id","r");put("xM",10);put("yM",1);put("zM",0)}
    }.toString()
    private fun modify(text: String,key: String,value: JsonElement) = JsonObject(Json.parseToJsonElement(text).jsonObject+ (key to value)).toString()
    private fun status(w: ComparisonWorkspace)=Json.parseToJsonElement(w.reportJson()).jsonObject.getValue("comparison").jsonObject.getValue("status").jsonPrimitive.content

    @Test fun csvAndJsonPreserveMissingLevelsCoordinatesRawPayloadAndIdentity() {
        val original=document()
        val imported=ImportedComparison.parse(original)
        assertEquals(original,imported.document)
        assertEquals(listOf("r:1000.0","r:2000.0"),imported.run.samples.map {it.id})
        assertNull(imported.run.samples.last().levelDb)
        assertEquals(10.0,imported.run.samples.first().x)
        val json="""[{"id":"r:1000.0","frequencyHz":1000,"xM":10,"yM":1,"zM":0,"levelDb":80}]"""
        assertEquals(80.0,ImportedComparison.parse(document(json,format="JSON_SAMPLES")).run.samples.single().levelDb)
        assertNotEquals(imported.documentSha256,ImportedComparison.parse(modify(original,"toleranceDb",JsonPrimitive(0.2))).documentSha256)
        assertEquals(imported.run.artifactSha256,ImportedComparison.parse(modify(original,"toleranceDb",JsonPrimitive(0.2))).run.artifactSha256)
    }
    @Test fun quotedCsvSupportsDelimitersNewlinesBomAndExplicitCoordinateColumns() {
        assertEquals(listOf(listOf("a","b"),listOf("x,y","q\"z\nnext")),ComparisonCsv.rows("a,b\r\n\"x,y\",\"q\"\"z\nnext\"\r\n",','))
        assertEquals(listOf(listOf("a","b"),listOf("1","2")),ComparisonCsv.rows("a;b\n1;2",';'))
        val d=document("\uFEFFID,Hz,Level,X,Y,Z\n\"a,b\",1000,80,2,3,4\n")
        val root=Json.parseToJsonElement(d).jsonObject.toMutableMap();root.remove("receiver")
        root["columns"]=buildJsonObject {put("id","ID");put("frequencyHz","Hz");put("levelDb","Level");put("xM","X");put("yM","Y");put("zM","Z")}
        val sample=ImportedComparison.parse(JsonObject(root).toString()).run.samples.single()
        assertEquals("a,b",sample.id);assertEquals(4.0,sample.z)
    }
    @Test fun malformedAmbiguousAndUnsupportedInputsAreRejected() {
        val d=document()
        for(csv in listOf("Hz,Hz,Level\n1,2,3", "Hz,Level\n1000,80,2", "Hz,Level\n1000,NaN", "Hz,Level\nNaN,80",
            "Hz,Level\n1000,80\n1000,81", "Hz,Level\n\"1000,80", "Hz,Level\n\"1000\"x,80"))
            assertFailsWith<IllegalArgumentException>(csv) {ImportedComparison.parse(document(csv))}
        assertFails {ImportedComparison.parse(modify(d,"sourceSha256",JsonPrimitive("a".repeat(64))))}
        assertFails {ImportedComparison.parse(modify(d,"units",buildJsonObject {put("frequency","kHz");put("position","ft");put("level","dBu")}))}
        assertFails {ImportedComparison.parse(modify(d,"format",JsonPrimitive("MAPP_BINARY")))}
        assertFails {ImportedComparison.parse(modify(d,"version",JsonPrimitive(2)))}
        assertFails {ImportedComparison.parse(" ".repeat(ImportedComparison.MAX_DOCUMENT_CHARS+1))}
        assertFails {ComparisonCsv.rows((0..ImportedComparison.MAX_SAMPLES+1).joinToString("\n") {"a,b"},',')}
    }
    @Test fun workspaceMatchesSnapshotsPreservesUnavailableAndBlocksSyntheticOrUncalibratedEvidence() {
        val data="Hz,Level\n1000,80\n2000,81\n"
        val reference=document(data);val engine=document(data,evidence="ENGINE")
        val w=ComparisonWorkspace.empty().import(reference,true).import(engine,false)
        assertEquals("PASSED",status(w)) // Test-only declarations exercise matching, not external validation.
        val copy=ComparisonWorkspace.parse(w.toJsonString())
        assertEquals(w.toJsonString(),copy.toJsonString());assertEquals(w.reportJson(),copy.reportJson())
        assertEquals("FAILED",status(w.import(document("Hz,Level\n1000,81\n2000,82\n",evidence="ENGINE"),false)))
        assertEquals("NOT_COMPARABLE",status(w.import(document(evidence="ENGINE"),false)))
        assertEquals("NOT_COMPARABLE",status(w.import(document(data,synthetic=true),true)))
        assertEquals("NOT_COMPARABLE",status(w.import(document(data,evidence="CONTROLLED_MEASUREMENT",calibration="UNKNOWN"),true)))
        assertFails {w.import(engine,true)}
        assertEquals("WAITING_FOR_DATA",status(w.clear()))
        val before=w.toJsonString();assertFails {w.import("broken",true)};assertEquals(before,w.toJsonString())
    }
}
