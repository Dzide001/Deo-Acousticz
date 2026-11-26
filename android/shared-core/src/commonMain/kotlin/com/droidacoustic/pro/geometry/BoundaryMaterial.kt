package com.droidacoustic.pro.geometry

import kotlin.math.*
import kotlinx.serialization.json.*

/** Immutable energy absorption. Log-frequency interpolation and endpoint hold. */
class BoundaryMaterial private constructor(val name: String, val label: String,
    private val samples: List<Pair<Int, Float>>, val source: String = "",
    private val angles: List<Pair<Float, BoundaryMaterial>> = emptyList(),
    val reflectionPhase: ReflectionPhase? = null,
    val scattering: MaterialScattering? = null) {
    val isCustom get() = name == "CUSTOM" || isAngleDependent
    val isAngleDependent get() = angles.isNotEmpty()
    fun angleCurves(): List<Pair<Float, BoundaryMaterial>> = angles.toList()
    fun points(): List<Pair<Int, Float>> = samples.toList()
    fun absorptionAt(hz: Int, incidenceDeg: Float = 0f): Float {
        require(hz > 0)
        require(incidenceDeg.isFinite() && incidenceDeg in 0f..90f) {"Incidence must be 0–90 degrees from the normal"}
        if (angles.isNotEmpty()) {
            if (incidenceDeg <= angles.first().first) return angles.first().second.absorptionAt(hz)
            if (incidenceDeg >= angles.last().first) return angles.last().second.absorptionAt(hz)
            val i=angles.indexOfFirst {it.first >= incidenceDeg}
            val (lo,a)=angles[i-1]; val (hi,b)=angles[i]
            val x=a.absorptionAt(hz); val y=b.absorptionAt(hz)
            return x+(y-x)*(incidenceDeg-lo)/(hi-lo)
        }
        if (hz <= samples.first().first) return samples.first().second
        if (hz >= samples.last().first) return samples.last().second
        val i = samples.indexOfFirst { it.first >= hz }
        val (lo, a) = samples[i-1]; val (hi, b) = samples[i]
        return (a + (b-a) * ln(hz.toDouble()/lo) / ln(hi.toDouble()/lo)).toFloat()
    }
    fun withReflectionPhase(phase: ReflectionPhase?) = BoundaryMaterial(
        if(!isCustom && phase!=null) "CUSTOM" else name,label,samples,source,angles,phase,scattering)
    fun withScattering(value: MaterialScattering?) = BoundaryMaterial(
        if(!isCustom && value!=null) "CUSTOM" else name,label,samples,source,angles,reflectionPhase,value)
    fun energyPartition(hz: Int,angle: Float=0f): BoundaryEnergyPartition {
        val absorbed=absorptionAt(hz,angle).toDouble()
        val scatteredFraction=scattering?.coefficientAt(hz,angle)?.toDouble() ?: 0.0
        val reflected=1.0-absorbed
        return BoundaryEnergyPartition(absorbed,reflected*(1.0-scatteredFraction),reflected*scatteredFraction)
    }
    internal val stableKey: String by lazy {toJsonString()}
    fun toJson(): JsonElement {
        val base=baseJson()
        if(reflectionPhase==null && scattering==null) return base
        val fields=base.jsonObject.toMutableMap()
        reflectionPhase?.let {fields["reflectionPhase"]=it.toJson()}
        scattering?.let {fields["scattering"]=it.toJson()}
        return JsonObject(fields)
    }
    fun toJsonString(): String = toJson().toString()
    private fun baseJson(): JsonElement = if (!isCustom) JsonPrimitive(name) else if (isAngleDependent) buildJsonObject {
        put("type", "angular"); put("label",label); put("source",source)
        put("curves",JsonArray(angles.map {(angle,curve) -> buildJsonObject {
            put("angleDegrees",angle); put("points",curve.toJson().jsonObject.getValue("points"))
        }}))
    } else buildJsonObject {
        put("type", "custom"); put("label", label); put("source", source)
        put("points", JsonArray(samples.map { (hz, alpha) -> buildJsonObject {
            put("hz", hz); put("alpha", alpha)
        } }))
    }
    override fun equals(other: Any?) = other is BoundaryMaterial && name == other.name &&
        label == other.label && source == other.source && samples == other.samples && angles == other.angles && reflectionPhase == other.reflectionPhase && scattering == other.scattering
    override fun hashCode() = listOf(name, label, source, samples, angles,reflectionPhase,scattering).hashCode()
    companion object {
        private fun preset(name: String, label: String, vararg alpha: Float) =
            BoundaryMaterial(name, label, listOf(125,250,500,1000,2000,4000).zip(alpha.toList()))
        val IDEAL_RIGID = preset("IDEAL_RIGID","Ideal rigid",0f,0f,0f,0f,0f,0f)
        val CONCRETE = preset("CONCRETE","Sealed concrete",.01f,.01f,.02f,.02f,.02f,.03f)
        val CARPET = preset("CARPET","Heavy carpet",.02f,.06f,.14f,.37f,.60f,.65f)
        val GYPSUM = preset("GYPSUM","Gypsum board",.29f,.10f,.05f,.04f,.07f,.09f)
        val PANEL = preset("PANEL","Acoustic panel",.20f,.55f,.90f,.95f,.90f,.85f)
        val ABSORBING = preset("ABSORBING","Ideal absorber",1f,1f,1f,1f,1f,1f)
        val entries get() = listOf(IDEAL_RIGID,CONCRETE,CARPET,GYPSUM,PANEL,ABSORBING)
        fun valueOf(name: String) = entries.firstOrNull {it.name == name}
            ?: throw IllegalArgumentException("Unknown boundary material")
        fun custom(label: String, points: List<Pair<Int, Float>>, source: String = ""): BoundaryMaterial {
            require(label.isNotBlank() && label.length <= 120) {"Enter a material name (up to 120 characters)"}
            require(source.length <= 2000) {"Measurement notes must be at most 2000 characters"}
            require(points.size in 2..64) {"Enter 2–64 frequency / absorption pairs"}
            require(points.all {it.first in 1..200000 && it.second.isFinite() && it.second in 0f..1f}) {
                "Frequency must be 1–200000 Hz and absorption 0–1"
            }
            require(points.zipWithNext().all {it.first.first < it.second.first}) {"Frequencies must increase without duplicates"}
            return BoundaryMaterial("CUSTOM",label,points.toList(),source)
        }
        /** Angle is measured from the surface normal: 0 normal, 90 grazing. Linear angle interpolation. */
        fun angular(label: String, curves: List<Pair<Float,List<Pair<Int,Float>>>>, source: String = ""): BoundaryMaterial {
            require(curves.size in 2..19) {"Enter 2–19 angle curves"}
            require(curves.all {it.first.isFinite() && it.first in 0f..90f}) {"Angles must be 0–90 degrees from the normal"}
            require(curves.zipWithNext().all {it.first.first < it.second.first}) {"Angles must increase without duplicates"}
            val copied=curves.map {(angle,points) -> angle to custom(label,points,source)}
            return BoundaryMaterial("ANGULAR",label,copied.first().second.points(),source,copied)
        }
        fun parseAngular(label: String, text: String, source: String = ""): BoundaryMaterial {
            val curves=mutableListOf<Pair<Float,MutableList<Pair<Int,Float>>>>()
            text.trim().lines().filter {it.isNotBlank()}.forEach {line ->
                val p=line.trim().split(Regex("[,;\\s]+"))
                require(p.size==3) {"Use angle degrees, Hz, absorption on each line"}
                val angle=p[0].toFloatOrNull() ?: error("Invalid angle")
                val hz=p[1].toIntOrNull() ?: error("Invalid frequency")
                val alpha=p[2].toFloatOrNull() ?: error("Invalid absorption")
                if(curves.lastOrNull()?.first != angle) curves+=angle to mutableListOf<Pair<Int,Float>>()
                curves.last().second+=hz to alpha
            }
            return angular(label,curves,source)
        }
        fun fromJson(value: JsonElement): BoundaryMaterial {
            var material=fromBaseJson(value)
            if(value is JsonObject) {
                value["reflectionPhase"]?.let {material=material.withReflectionPhase(ReflectionPhase.fromJson(it))}
                value["scattering"]?.let {material=material.withScattering(MaterialScattering.fromJson(it))}
            }
            return material
        }
        /** One physical reflection: lowest specular energy wins; ties use stable serialized material order. */
        fun reflectionMaterial(materials: Set<BoundaryMaterial>,hz: Int,angle: Float): BoundaryMaterial? =
            materials.minWithOrNull(compareBy<BoundaryMaterial> {it.energyPartition(hz,angle).specular}.thenBy {it.stableKey})
        private fun fromBaseJson(value: JsonElement): BoundaryMaterial {
            if (value is JsonPrimitive) { require(value.isString); return valueOf(value.content) }
            require(value is JsonObject && value["type"] in listOf(JsonPrimitive("custom"),JsonPrimitive("angular"))) {"Invalid material curve"}
            fun string(key: String): String { val p = value[key]; require(p is JsonPrimitive && p.isString); return p.content }
            if(value["type"] == JsonPrimitive("angular")) {
                val curves=value["curves"]; require(curves is JsonArray && curves.size in 2..19)
                return angular(string("label"),curves.map {c ->
                    require(c is JsonObject)
                    val angle=c["angleDegrees"]; require(angle is JsonPrimitive && !angle.isString && angle.floatOrNull!=null)
                    val curve=fromJson(buildJsonObject {
                        put("type","custom"); put("label",string("label")); put("source",string("source"))
                        put("points",c.getValue("points"))
                    })
                    angle.float to curve.points()
                },string("source"))
            }
            val points = value["points"]; require(points is JsonArray && points.size in 2..64)
            return custom(string("label"), points.map { p ->
                require(p is JsonObject)
                val hz=p["hz"]; val a=p["alpha"]
                require(hz is JsonPrimitive && !hz.isString && hz.intOrNull != null)
                require(a is JsonPrimitive && !a.isString && a.floatOrNull != null)
                hz.int to a.float
            }, string("source"))
        }
        fun fromJsonString(json: String) = fromJson(Json.parseToJsonElement(json))
        fun parseMeasured(label: String, text: String, source: String = "") = custom(label,
            text.trim().lines().filter {it.isNotBlank()}.map { line ->
                val parts=line.trim().split(Regex("[,;\\s]+"))
                require(parts.size == 2) {"Use one Hz, absorption pair per line"}
                (parts[0].toIntOrNull() ?: error("Invalid frequency")) to
                    (parts[1].toFloatOrNull() ?: error("Invalid absorption"))
            }, source)
    }
}
