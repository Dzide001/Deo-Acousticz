package com.droidacoustic.pro.geometry

import kotlinx.serialization.json.*

/** Pressure reflection phase in unwrapped degrees, using exp(+i omega t).
 * Linear frequency/angle interpolation preserves measured delay; endpoints are held. */
class ReflectionPhase private constructor(private val curves: List<Pair<Float,List<Pair<Int,Double>>>>, val source: String) {
    fun curves() = curves.map {(a,p) -> a to p.toList()}
    fun frequencies() = curves.flatMap {it.second.map {p -> p.first}}.distinct()
    fun degreesAt(hz: Double, angle: Float): Double {
        require(hz.isFinite() && hz>0 && angle.isFinite() && angle in 0f..90f)
        fun at(points: List<Pair<Int,Double>>): Double {
            if(hz<=points.first().first) return points.first().second
            if(hz>=points.last().first) return points.last().second
            val i=points.indexOfFirst {it.first>=hz}; val (lo,a)=points[i-1]; val (hi,b)=points[i]
            return a+(b-a)*(hz-lo)/(hi-lo)
        }
        if(angle<=curves.first().first) return at(curves.first().second)
        if(angle>=curves.last().first) return at(curves.last().second)
        val i=curves.indexOfFirst {it.first>=angle}; val (lo,a)=curves[i-1]; val (hi,b)=curves[i]
        return at(a)+(at(b)-at(a))*(angle-lo)/(hi-lo)
    }
    fun toJsonString()=toJson().toString()
    fun toJson()=buildJsonObject {
        put("source",source); put("convention","exp(+iwt);unwrapped-degrees")
        put("curves",JsonArray(curves.map {(angle,points) -> buildJsonObject {
            put("angleDegrees",angle); put("points",JsonArray(points.map {(hz,phase) -> buildJsonObject {
                put("hz",hz); put("phaseDegrees",phase)
            }}))
        }}))
    }
    override fun equals(other: Any?)=other is ReflectionPhase && source==other.source && curves==other.curves
    override fun hashCode()=31*curves.hashCode()+source.hashCode()
    companion object {
        fun fromJsonString(text:String)=fromJson(Json.parseToJsonElement(text))
        fun create(curves: List<Pair<Float,List<Pair<Int,Double>>>>,source: String=""): ReflectionPhase {
            require(source.length<=2000) {"Phase measurement notes must be at most 2000 characters"}
            require(curves.size in 1..19) {"Enter 1–19 phase angle curves"}
            require(curves.all {it.first.isFinite() && it.first in 0f..90f} && curves.zipWithNext().all {it.first.first<it.second.first}) {"Phase angles must increase within 0–90 degrees"}
            curves.forEach {(_,p) ->
                require(p.size in 2..64) {"Each phase angle needs 2–64 frequency samples"}
                require(p.all {it.first in 1..200000 && it.second.isFinite() && it.second in -360000.0..360000.0}) {"Invalid phase frequency or phase (limit ±360000 degrees)"}
                require(p.zipWithNext().all {it.first.first<it.second.first}) {"Phase frequencies must increase without duplicates"}
            }
            return ReflectionPhase(curves.map {(a,p)->a to p.toList()},source)
        }
        fun parse(text: String,source: String=""): ReflectionPhase {
            val curves=mutableListOf<Pair<Float,MutableList<Pair<Int,Double>>>>()
            text.trim().lines().filter {it.isNotBlank()}.forEach {line ->
                val p=line.trim().split(Regex("[,;\\s]+")); require(p.size==3) {"Use angle, Hz, unwrapped phase degrees per line"}
                val a=p[0].toFloatOrNull() ?: error("Invalid angle")
                val hz=p[1].toIntOrNull() ?: error("Invalid frequency")
                val phase=p[2].toDoubleOrNull() ?: error("Invalid phase")
                if(curves.lastOrNull()?.first!=a) curves+=a to mutableListOf<Pair<Int,Double>>()
                curves.last().second+=hz to phase
            }
            return create(curves,source)
        }
        fun fromJson(value: JsonElement): ReflectionPhase {
            require(value is JsonObject && value["convention"]==JsonPrimitive("exp(+iwt);unwrapped-degrees")) {"Unsupported phase convention"}
            val source=value["source"]; require(source is JsonPrimitive && source.isString)
            val curves=value["curves"]; require(curves is JsonArray && curves.size in 1..19)
            return create(curves.map {c ->
                require(c is JsonObject); val angle=c["angleDegrees"]
                require(angle is JsonPrimitive && !angle.isString && angle.floatOrNull!=null)
                val points=c["points"]; require(points is JsonArray && points.size in 2..64)
                angle.float to points.map {p ->
                    require(p is JsonObject); val hz=p["hz"]; val phase=p["phaseDegrees"]
                    require(hz is JsonPrimitive && !hz.isString && hz.intOrNull!=null)
                    require(phase is JsonPrimitive && !phase.isString && phase.doubleOrNull!=null)
                    hz.int to phase.double
                }
            },source.content)
        }
    }
}
