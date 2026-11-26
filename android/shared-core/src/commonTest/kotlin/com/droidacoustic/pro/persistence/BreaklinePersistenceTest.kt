package com.droidacoustic.pro.persistence

import kotlinx.serialization.json.*
import kotlin.test.*

class BreaklinePersistenceTest {
    private fun scene(lines: String,version: Int=ProjectArchive.VERSION) = """{"schema":"droidacoustic.scene","version":$version,"audienceAreas":[{"id":0,"vertices":[{"x":0,"z":0},{"x":4,"z":0},{"x":4,"z":4},{"x":0,"z":4}],"vertexOffsetsM":[0,0,4,0],"breaklines":$lines}]}"""
    private val line="""{"start":{"kind":"BOUNDARY","index":0},"end":{"kind":"BOUNDARY","index":2}}"""
    @Test fun portableRoundTripRetainsConstraintReferencesAndVersion() {
        val encoded=ProjectArchive.encode(scene("[$line]"),emptyMap(),{null},true)
        val opened=Json.parseToJsonElement(ProjectArchive.open(encoded).sceneJson).jsonObject
        assertEquals(ProjectArchive.VERSION,opened.getValue("version").jsonPrimitive.int)
        assertEquals(Json.parseToJsonElement("[$line]"),opened.getValue("audienceAreas").jsonArray[0].jsonObject["breaklines"])
    }
    @Test fun malformedEndpointTypesAndCrossingsFailPreflight() {
        val invalid=listOf(line.replace("\"index\":0","\"index\":0.5"),line.replace("\"index\":0","\"index\":\"0\""),
            line.replace("\"index\":0","\"index\":-1"),line.replace("\"index\":0","\"index\":4"),
            line.replace("BOUNDARY","INTERIOR"),line.replace("BOUNDARY","UNKNOWN"),
            line.replace("\"start\":{\"kind\":\"BOUNDARY\",\"index\":0}","\"start\":null"))
        invalid.forEach { assertFails { ProjectArchive.validateScene(scene("[$it]")) } }
        val crossing="""{"start":{"kind":"BOUNDARY","index":1},"end":{"kind":"BOUNDARY","index":3}}"""
        assertFailsWith<IllegalArgumentException> { ProjectArchive.validateScene(scene("[$line,$crossing]")) }
        for (value in listOf("null","{}","[1]")) assertFails { ProjectArchive.validateScene(scene(value)) }
    }
    @Test fun previousHeightFieldSchemaOpensWithoutConstraintsAndFutureSchemaIsRejected() {
        val previous=Json.parseToJsonElement(scene("[]",10)).jsonObject.toMutableMap()
        val area=previous.getValue("audienceAreas").jsonArray[0].jsonObject.toMutableMap().apply { remove("breaklines") }
        previous["audienceAreas"]=JsonArray(listOf(JsonObject(area)))
        val opened=ProjectArchive.open(JsonObject(previous).toString())
        assertTrue(opened.problems.isEmpty())
        assertFailsWith<UnsupportedProjectVersion> { ProjectArchive.open(scene("[$line]",ProjectArchive.VERSION+1)) }
    }
}
