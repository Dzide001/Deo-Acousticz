package com.droidacoustic.pro.validation

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.*
import kotlinx.serialization.json.*
import kotlin.math.*

/** Portable test harness: all engine evaluation stays in shared-core; the JVM adapter only does I/O.
 * Reference values are generated outside Kotlin by the retained independent Python script.
 */
object NumericalValidationSuite {
    private fun JsonObject.number(key: String, fallback: Double = 0.0) = this[key]?.jsonPrimitive?.double ?: fallback
    private fun JsonObject.vector(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.double }

    fun evaluate(fixtures: JsonObject, references: JsonObject): JsonObject {
        require(fixtures.getValue("schema").jsonPrimitive.int == 1)
        require(references.getValue("schema").jsonPrimitive.int == 1)
        val cases = fixtures.getValue("cases").jsonArray.map { it.jsonObject }
        val refs = references.getValue("cases").jsonArray.map { it.jsonObject }
        require(cases.map { it.getValue("id") }.toSet().size == cases.size)
        require(cases.map { it.getValue("id") } == refs.map { it.getValue("id") })
        val environment = fixtures.getValue("environment").jsonObject
        val air = Atmosphere(environment.number("temperatureC").toFloat(),
            environment.number("humidityPct").toFloat(), environment.number("pressureKPa").toFloat())
        val field = DirectField(air)
        val rows = cases.zip(refs).map { (case, ref) ->
            val receiver = case.vector("receiver")
            val hz = case.number("hz").toInt()
            val sources = case.getValue("sources").jsonArray.mapIndexed { index, item ->
                val source = item.jsonObject
                val p = source.vector("position")
                SoundSource(index, p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), 0f, 0f,
                    Radiator { _, _, _ -> 100f }, gainDb = source.number("gainDb").toFloat(),
                    delayS = source.number("delayS"), inverted = source["inverted"]?.jsonPrimitive?.boolean ?: false)
            }
            val wall = case["wall"]?.jsonObject
            val boundaries = wall?.let {
                val h = it.number("height").toFloat()
                val w = it.number("halfWidth")
                val mesh = AudienceMesh.create(listOf(AudienceMeshVertex(0.0, 0f, -w),
                    AudienceMeshVertex(0.0, 0f, w), AudienceMeshVertex(0.0, h, w), AudienceMeshVertex(0.0, h, -w)),
                    listOf(SurfaceTriangle(0,1,2), SurfaceTriangle(0,2,3)))
                    .withBoundaryRoles(List(2) { _ -> AcousticBoundaryRole.valueOf(it.getValue("role").jsonPrimitive.content) })
                BoundaryGeometry.prepare(listOf(AudienceSurface(0,"Validation wall","STAGE",emptyList(),importedMesh=mesh)))
            }
            val floor = case["groundAbsorption"]?.jsonPrimitive?.float?.let { alpha -> FloorReflection { alpha } }
            val kind = case.getValue("kind").jsonPrimitive.content
            require(kind in listOf("tone", "band", "band-refinement"))
            fun band(n: Int): Double {
                val edges = case.vector("band")
                return field.bandPower(sources,receiver[0].toFloat(),receiver[1].toFloat(),receiver[2].toFloat(),
                    (0 until n).map { i -> Bands.Segment(hz, edges[0]+(edges[1]-edges[0])*i/n,
                        edges[0]+(edges[1]-edges[0])*(i+1)/n) }, floor=floor, boundaries=boundaries)
            }
            val pressure = if (kind=="tone") tonePressure(sources,receiver[0].toFloat(),receiver[1].toFloat(),
                receiver[2].toFloat(),hz,air) else null
            val refinement = if (kind=="band-refinement") listOf(1,4,16,64,256).map { it to band(it) } else emptyList()
            val actual = pressure?.power ?: refinement.lastOrNull()?.second ?: band(1)
            val expected = ref.number("power")
            val scale = ref.number("scale")
            val tolerance = case.number("normalizedTolerance")
            require(scale.isFinite() && scale>=1 && tolerance>0 && tolerance==ref.number("normalizedTolerance"))
            require(case.getValue("classification")==ref.getValue("classification"))
            val error = abs(actual-expected)/scale
            val pressureError = pressure?.let {
                val p = ref.vector("complexPressure")
                max(abs(it.real-p[0]),abs(it.imaginary-p[1]))/sqrt(scale)
            }
            val convergencePassed = refinement.isEmpty() ||
                error <= max(abs(refinement.first().second-expected)/scale/100,1e-12)
            val passed = actual.isFinite() && error<=tolerance && convergencePassed &&
                (pressureError==null || pressureError<=3e-6)
            buildJsonObject {
                put("id",case.getValue("id")); put("classification",case.getValue("classification"))
                put("referencePower",expected); put("enginePower",actual)
                put("normalizedError",error); put("normalizedTolerance",tolerance); put("passed",passed)
                pressureError?.let { put("normalizedComplexPressureError",it) }
                if (refinement.isNotEmpty()) put("engineConvergence",JsonArray(refinement.map { (n,p) -> buildJsonObject {
                    put("segments",n); put("power",p); put("normalizedError",abs(p-expected)/scale)
                } }))
            }
        }
        return buildJsonObject {
            put("schema",1)
            put("fixtureSha256",references.getValue("fixtureSha256"))
            put("allPassed",rows.all { it.getValue("passed").jsonPrimitive.boolean })
            put("cases",JsonArray(rows))
        }
    }
}
