package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

class ComparisonImportPackageTest {
    @Test fun retainedSyntheticFixturesImportRestoreAndStayUnqualified() {
        val root=File(System.getProperty("numericalValidationDir")).parentFile.resolve("comparison-import")
        val engine=root.resolve("fixtures/engine.json").readText()
        val rows=listOf("manufacturer","measurement").map {name ->
            val reference=root.resolve("fixtures/$name.json").readText()
            val workspace=ComparisonWorkspace.empty().import(reference,true).import(engine,false)
            val restored=ComparisonWorkspace.parse(workspace.toJsonString())
            assertEquals(workspace.toJsonString(),restored.toJsonString())
            assertEquals(workspace.reportJson(),restored.reportJson())
            val report=Json.parseToJsonElement(restored.reportJson()).jsonObject
            assertEquals("NOT_COMPARABLE",report.getValue("comparison").jsonObject.getValue("status").jsonPrimitive.content)
            assertTrue(report.getValue("synthetic").jsonPrimitive.boolean)
            buildJsonObject {
                put("case",name);put("sampleCount",restored.reference!!.run.samples.size)
                put("sourcePayloadSha256",restored.reference!!.run.artifactSha256)
                put("referenceDocumentSha256",restored.reference!!.documentSha256)
                put("report",report);put("testPassed",true)
            }
        }
        val output=File(System.getProperty("comparisonImportReport"));output.parentFile.mkdirs()
        output.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("scope","SYNTHETIC_IMPORT_AND_RESTORE_TESTS_ONLY");put("cases",JsonArray(rows))
        })+"\n")
    }
}
