package com.droidacoustic.pro.validation

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class NumericalValidationPackageTest {
    private val directory = File(requireNotNull(System.getProperty("numericalValidationDir")))
    private val fixtureFile = File(directory,"fixtures.json")
    private val referenceFile = File(directory,"results/reference.json")
    private val fixtures = Json.parseToJsonElement(fixtureFile.readText()).jsonObject
    private val references = Json.parseToJsonElement(referenceFile.readText()).jsonObject
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun sourceTreeSha(): String {
        val root = File(requireNotNull(System.getProperty("numericalValidationSourceDir")))
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown().filter { it.isFile && it.extension=="kt" }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach {
                digest.update(it.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte()); digest.update(it.readBytes()); digest.update(0.toByte())
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun fixturesMatchRetainedReferencesAndQuadratureConverges() {
        assertEquals(sha(File(directory,"reference.py")), references.getValue("referenceScriptSha256").jsonPrimitive.content,
            "Regenerate and review reference results after changing the reference script")
        assertEquals(sha(fixtureFile),references.getValue("fixtureSha256").jsonPrimitive.content,
            "Regenerate and review reference results after changing fixtures")
        references.getValue("cases").jsonArray.forEach { value ->
            val row = value.jsonObject
            val scale = row.getValue("scale").jsonPrimitive.double
            val target = row.getValue("power").jsonPrimitive.double
            val samples = row["convergence"]?.jsonArray.orEmpty()
            if (samples.isNotEmpty()) {
                val errors = samples.map { abs(it.jsonObject.getValue("power").jsonPrimitive.double-target)/scale }
                assertTrue(errors.last()<=2e-6,"Reference convergence: ${row["id"]}: $errors")
                assertTrue(errors.last()<=max(errors.first()/100,1e-12),"Reference refinement: ${row["id"]}: $errors")
            }
        }
    }

    @Test fun sharedEngineMatchesIndependentReferencesAndRefinementConverges() {
        val result = NumericalValidationSuite.evaluate(fixtures,references)
        val report = buildJsonObject {
            result.forEach { (key,value) -> put(key,value) }
            put("referenceSha256",sha(referenceFile))
            put("sharedCoreKotlinSha256",sourceTreeSha())
            put("runtime",System.getProperty("java.runtime.version"))
            put("platform",System.getProperty("os.name")+" "+System.getProperty("os.arch"))
        }
        val output = File(requireNotNull(System.getProperty("numericalValidationReport")))
        output.parentFile.mkdirs()
        output.writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report)+"\n")
        val failures = report.getValue("cases").jsonArray.filter { !it.jsonObject.getValue("passed").jsonPrimitive.boolean }
        assertTrue(failures.isEmpty(),"Independent validation failures (report retained): $failures")
    }
}
