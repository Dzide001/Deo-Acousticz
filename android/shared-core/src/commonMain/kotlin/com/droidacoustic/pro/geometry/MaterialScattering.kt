package com.droidacoustic.pro.geometry

import kotlinx.serialization.json.*

/** Fraction of reflected (not incident) energy redirected out of the specular path.
 * This is not a diffusion coefficient or an angular radiation distribution. */
class MaterialScattering private constructor(private val curves: List<Pair<Float,BoundaryMaterial>>,val source: String) {
    fun curves(): List<Pair<Float,List<Pair<Int,Float>>>> = curves.map {(a,c)->a to c.points()}
    fun coefficientAt(hz: Int,angle: Float=0f): Float {
        require(hz>0 && angle.isFinite() && angle in 0f..90f)
        if(angle<=curves.first().first) return curves.first().second.absorptionAt(hz)
        if(angle>=curves.last().first) return curves.last().second.absorptionAt(hz)
        val i=curves.indexOfFirst {it.first>=angle};val (lo,a)=curves[i-1];val (hi,b)=curves[i]
        val x=a.absorptionAt(hz);val y=b.absorptionAt(hz)
        return x+(y-x)*(angle-lo)/(hi-lo)
    }
    fun toJson()=buildJsonObject {
        put("definition","fraction-of-reflected-energy");put("source",source)
        put("curves",JsonArray(curves.map {(angle,c)->buildJsonObject {
            put("angleDegrees",angle);put("points",JsonArray(c.points().map {(hz,s)->buildJsonObject {
                put("hz",hz);put("scattering",s)
            }}))
        }}))
    }
    override fun equals(other: Any?)=other is MaterialScattering && source==other.source && curves==other.curves
    override fun hashCode()=31*curves.hashCode()+source.hashCode()
    companion object {
        fun create(curves: List<Pair<Float,List<Pair<Int,Float>>>>,source: String=""): MaterialScattering {
            require(source.length<=2000) {"Scattering notes must be at most 2000 characters"}
            require(curves.size in 1..19) {"Enter 1–19 scattering angle curves"}
            require(curves.all {it.first.isFinite() && it.first in 0f..90f} && curves.zipWithNext().all {it.first.first<it.second.first}) {"Scattering angles must increase within 0–90 degrees"}
            // Reuse the shared immutable coefficient validator and log-frequency interpolation.
            return MaterialScattering(curves.map {(a,p)->a to BoundaryMaterial.custom("Scattering",p)},source)
        }
        fun parse(text: String,source: String=""): MaterialScattering {
            val curves=mutableListOf<Pair<Float,MutableList<Pair<Int,Float>>>>()
            text.trim().lines().filter {it.isNotBlank()}.forEach {line ->
                val p=line.trim().split(Regex("[,;\\s]+"));require(p.size==3) {"Use angle, Hz, scattering per line"}
                val a=p[0].toFloatOrNull() ?: error("Invalid angle")
                val hz=p[1].toIntOrNull() ?: error("Invalid frequency")
                val s=p[2].toFloatOrNull() ?: error("Invalid scattering")
                if(curves.lastOrNull()?.first!=a) curves+=a to mutableListOf<Pair<Int,Float>>()
                curves.last().second+=hz to s
            }
            return create(curves,source)
        }
        fun fromJson(value: JsonElement): MaterialScattering {
            require(value is JsonObject && value["definition"]==JsonPrimitive("fraction-of-reflected-energy")) {"Unsupported scattering definition"}
            val source=value["source"];require(source is JsonPrimitive && source.isString)
            val curves=value["curves"];require(curves is JsonArray && curves.size in 1..19)
            return create(curves.map {c ->
                require(c is JsonObject);val a=c["angleDegrees"]
                require(a is JsonPrimitive && !a.isString && a.floatOrNull!=null)
                val points=c["points"];require(points is JsonArray && points.size in 2..64)
                a.float to points.map {p ->
                    require(p is JsonObject);val hz=p["hz"];val s=p["scattering"]
                    require(hz is JsonPrimitive && !hz.isString && hz.intOrNull!=null)
                    require(s is JsonPrimitive && !s.isString && s.floatOrNull!=null)
                    hz.int to s.float
                }
            },source.content)
        }
    }
}

/** Incident energy fractions. Scattered energy feeds first-bounce diffuse propagation. */
data class BoundaryEnergyPartition(val absorbed: Double,val specular: Double,val scattered: Double)
