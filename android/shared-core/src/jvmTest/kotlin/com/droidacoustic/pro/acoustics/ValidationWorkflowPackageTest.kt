package com.droidacoustic.pro.acoustics

import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

/** Retained examples are synthetic; they verify the workflow, not vendor agreement. */
class ValidationWorkflowPackageTest {
    @Test fun retainedSyntheticWorkflowReportsRecomputeAfterReopen() {
        val root=File(System.getProperty("numericalValidationDir")).parentFile
        val reference=File(root,"comparison-import/fixtures/manufacturer.json").readText()
        val raw=Json.parseToJsonElement(File(root,"comparison-import/fixtures/engine.json").readText()).jsonObject.toMutableMap()
        raw.remove("sourceSha256");raw["payload"]=JsonPrimitive("Frequency,Level\r\n1000,81\r\n2000,79\r\n")
        val matched=ComparisonWorkspace.empty().import(reference,true).import(JsonObject(raw).toString(),false)
        val c=raw.getValue("conditions").jsonObject.toMutableMap();c["ground"]=JsonPrimitive("ideal-rigid ground ON - synthetic mismatch")
        raw["conditions"]=JsonObject(c)
        val mismatched=matched.import(JsonObject(raw).toString(),false)
        val reports=listOf("synthetic-residuals" to matched,"protocol-mismatch" to mismatched).associate {(name,w)->
            val text=w.validationReportJson();assertEquals(text,ComparisonWorkspace.parse(w.toJsonString()).validationReportJson())
            val report=Json.parseToJsonElement(text).jsonObject
            assertFalse(report.getValue("metrics").jsonObject.getValue("qualified").jsonPrimitive.boolean)
            assertEquals(2.0,report.getValue("metrics").jsonObject.getValue("maximumAbsoluteErrorDb").jsonPrimitive.double)
            name to report
        }
        assertEquals("ground",reports.getValue("protocol-mismatch").getValue("protocolDifferences").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content)
        val output=File(System.getProperty("validationWorkflowReport"));output.parentFile.mkdirs()
        output.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),JsonObject(reports)))
    }
}
