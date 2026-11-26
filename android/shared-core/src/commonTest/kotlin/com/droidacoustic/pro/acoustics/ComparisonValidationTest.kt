package com.droidacoustic.pro.acoustics

import kotlinx.serialization.json.*
import kotlin.math.sqrt
import kotlin.test.*

class ComparisonValidationTest {
    private fun document(engine: Boolean=false, payload: String="Hz,Level\n1000,80\n2000,81\n4000,82", preset: String="{\"gainDb\":0,\"delayMs\":0}", synthetic: Boolean=false): String = buildJsonObject {
        put("schema","droidacoustic.comparison-import");put("version",1);put("evidence",if(engine) "ENGINE" else "MANUFACTURER_SOFTWARE_EXPORT")
        put("synthetic",synthetic)
        putJsonObject("source") {put("producer","TEST ONLY - no manufacturer validation");put("version","1");put("fileName","test.csv")}
        putJsonObject("conditions") {
            listOf("modelRevision","geometry","frequencyConvention","atmosphere","ground").forEach {put(it,"test-protocol")}
            put("preset",preset);put("levelConvention","ABSOLUTE_SPL");put("driveUnit","WATTS");put("driveValue",1)
        }
        putJsonObject("units") {put("frequency","Hz");put("position","m");put("level","dB_SPL")}
        put("toleranceDb",0.1);put("format","CSV");put("delimiter",",");put("payload",payload)
        putJsonObject("columns") {put("frequencyHz","Hz");put("levelDb","Level")}
        putJsonObject("receiver") {put("id","r");put("xM",10);put("yM",1);put("zM",0)}
    }.toString()
    private fun workspace(engine: String=document(true),reference: String=document())=ComparisonWorkspace.empty().import(reference,true).import(engine,false)
    private fun report(w: ComparisonWorkspace)=Json.parseToJsonElement(w.validationReportJson()).jsonObject
    private fun status(r: JsonObject)=r.getValue("summary").jsonObject.getValue("comparison").jsonObject.getValue("status").jsonPrimitive.content
    @Test fun residualMetricsPlotsAndOriginalDocumentsAreReproducible() {
        val w=workspace(document(true,"Hz,Level\n1000,81\n2000,79\n4000,82"));val r=report(w)
        assertEquals("FAILED",status(r));assertEquals("COMPARABLE_SAMPLES_ONLY",r.getValue("scope").jsonPrimitive.content)
        val m=r.getValue("metrics").jsonObject
        assertTrue(m.getValue("qualified").jsonPrimitive.boolean)
        assertEquals(sqrt(5.0/3),m.getValue("rmsErrorDb").jsonPrimitive.double,1e-12)
        assertEquals(-1.0/3,m.getValue("biasDb").jsonPrimitive.double,1e-12)
        assertEquals(2.0,m.getValue("maximumAbsoluteErrorDb").jsonPrimitive.double)
        assertEquals(listOf(1.0,-2.0,0.0),r.getValue("samples").jsonArray.map {it.jsonObject.getValue("residualDb").jsonPrimitive.double})
        val plots=r.getValue("plots").jsonArray
        assertEquals(2,plots.size)
        val points=plots[1].jsonObject.getValue("points").jsonArray
        assertEquals(listOf(0.0,.5,1.0),points.map {it.jsonObject.getValue("xNormalized").jsonPrimitive.double})
        assertTrue(points.all {it.jsonObject.getValue("yNormalized").jsonPrimitive.double in 0.0..1.0})
        assertEquals(w.reference!!.document,r.getValue("inputs").jsonObject.getValue("referenceDocument").jsonPrimitive.content)
        assertEquals(w.validationReportJson(),ComparisonWorkspace.parse(w.toJsonString()).validationReportJson())
        assertFalse(Json.parseToJsonElement(w.validationReportJson(false)).jsonObject.containsKey("inputs"))
    }
    @Test fun nestedProtocolDifferencesRemainDiagnosticAndEncodingDifferencesAreExplained() {
        val r=report(workspace(document(true,preset="{\"gainDb\":0,\"delayMs\":4}")))
        assertEquals("NOT_COMPARABLE",status(r));assertFalse(r.getValue("metrics").jsonObject.getValue("qualified").jsonPrimitive.boolean)
        assertEquals("preset.delayMs",r.getValue("protocolDifferences").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(3,r.getValue("metrics").jsonObject.getValue("pairedCount").jsonPrimitive.int)
        val encoded=report(workspace(document(true,preset="{ \"delayMs\":0, \"gainDb\":0 }")))
        assertEquals("NOT_COMPARABLE",status(encoded))
        assertEquals("ENCODING_DIFFERENCE",encoded.getValue("protocolDifferences").jsonArray.single().jsonObject.getValue("kind").jsonPrimitive.content)
    }
    @Test fun reorderMissingAndExtraSamplesNeverRelaxStrictMatching() {
        val reordered=report(workspace(document(true,"Hz,Level\n4000,82\n1000,80\n2000,81")))
        assertEquals("NOT_COMPARABLE",status(reordered));assertEquals(3,reordered.getValue("metrics").jsonObject.getValue("pairedCount").jsonPrimitive.int)
        val missing=report(workspace(document(true,"Hz,Level\n1000,80\n2000,\n8000,83")))
        assertEquals(listOf("PAIRED","UNAVAILABLE","REFERENCE_ONLY","ENGINE_ONLY"),missing.getValue("samples").jsonArray.map {it.jsonObject.getValue("status").jsonPrimitive.content})
        assertEquals("NOT_COMPARABLE",status(missing))
        val root=Json.parseToJsonElement(document(true)).jsonObject.toMutableMap()
        root["receiver"]=buildJsonObject {put("id","r");put("xM",11);put("yM",1);put("zM",0)}
        val moved=report(workspace(JsonObject(root).toString()))
        assertEquals(0,moved.getValue("metrics").jsonObject.getValue("pairedCount").jsonPrimitive.int)
        assertTrue(moved.getValue("samples").jsonArray.all {it.jsonObject.getValue("status").jsonPrimitive.content=="POSITION_MISMATCH"})
    }
    @Test fun syntheticAndAbsentDataStayUnqualifiedAndExtremeLevelsStayFinite() {
        val synthetic=report(workspace(reference=document(synthetic=true)))
        assertEquals("NOT_COMPARABLE",status(synthetic));assertEquals("DIAGNOSTIC_ONLY_NOT_QUALIFIED",synthetic.getValue("scope").jsonPrimitive.content)
        assertEquals("WAITING_FOR_DATA",status(report(ComparisonWorkspace.empty())))
        val overflow=report(workspace(document(true,"Hz,Level\n1000,1e308"),document(payload="Hz,Level\n1000,-1e308")))
        assertEquals("NOT_COMPARABLE",status(overflow));assertEquals("NUMERICAL_OVERFLOW",overflow.getValue("samples").jsonArray.single().jsonObject.getValue("status").jsonPrimitive.content)
        val large=report(workspace(document(true,"Hz,Level\n1000,1e308\n2000,1e308"),document(payload="Hz,Level\n1000,0\n2000,0")))
        assertEquals(1e308,large.getValue("metrics").jsonObject.getValue("rmsErrorDb").jsonPrimitive.double)
    }
    private fun explicitSample(engine: Boolean,hz: Double,broadband: Boolean=false): String {
        val root=Json.parseToJsonElement(document(engine)).jsonObject.toMutableMap()
        root["format"]=JsonPrimitive("JSON_SAMPLES")
        root["payload"]=JsonPrimitive(buildJsonArray {add(buildJsonObject {
            put("id","receiver-sample");put("frequencyHz",hz);put("xM",10);put("yM",1);put("zM",0);put("levelDb",80)
        })}.toString())
        if(broadband) {
            root["version"]=JsonPrimitive(3)
            root["broadband"]=BroadbandDefinition.create("Z",listOf(1000,2000),1f,mapOf(1000 to 0f,2000 to 0f)).json()
            root["conditions"]=JsonObject(root.getValue("conditions").jsonObject+("levelConvention" to JsonPrimitive("BROADBAND_SPL")))
            root["units"]=buildJsonObject {put("frequency","aggregate");put("position","m");put("level","dB_Z")}
        }
        return JsonObject(root).toString()
    }
    @Test fun sameIdAtDifferentFrequencyCannotProduceResidual() {
        val r=report(workspace(explicitSample(true,2000.0),explicitSample(false,1000.0)))
        assertEquals("NOT_COMPARABLE",status(r))
        assertEquals("FREQUENCY_MISMATCH",r.getValue("samples").jsonArray.single().jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(0,r.getValue("metrics").jsonObject.getValue("pairedCount").jsonPrimitive.int)
    }
    @Test fun broadbandAggregatesNeverGenerateLogFrequencyPlots() {
        val r=report(workspace(explicitSample(true,0.0,true),explicitSample(false,0.0,true)))
        assertEquals("PASSED",status(r));assertEquals(1,r.getValue("plots").jsonArray.size)
        assertEquals("sample-order",r.getValue("plots").jsonArray.single().jsonObject.getValue("xScale").jsonPrimitive.content)
    }
    @Test fun unknownMeasurementCalibrationStillBlocksQualificationWithZeroResidual() {
        val root=Json.parseToJsonElement(document()).jsonObject.toMutableMap()
        root["evidence"]=JsonPrimitive("CONTROLLED_MEASUREMENT")
        root["source"]=JsonObject(root.getValue("source").jsonObject+mapOf("instrument" to JsonPrimitive("test mic"),
            "method" to JsonPrimitive("test"),"smoothing" to JsonPrimitive("none"),"timeWindow" to JsonPrimitive("test"),
            "calibrationStatus" to JsonPrimitive("UNKNOWN"),"calibrationReference" to JsonPrimitive("test")))
        val r=report(workspace(reference=JsonObject(root).toString()))
        assertEquals("NOT_COMPARABLE",status(r));assertFalse(r.getValue("metrics").jsonObject.getValue("qualified").jsonPrimitive.boolean)
        assertEquals(0.0,r.getValue("metrics").jsonObject.getValue("maximumAbsoluteErrorDb").jsonPrimitive.double)
    }

}
