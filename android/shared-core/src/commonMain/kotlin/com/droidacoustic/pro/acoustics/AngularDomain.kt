package com.droidacoustic.pro.acoustics

import kotlinx.serialization.json.*
import kotlin.math.abs

/** Origin of stored samples. Declarations are not authenticated measurement evidence. */
enum class AngularSampleOrigin { UNDECLARED, MEASURED, SYMMETRY, INTERPOLATED }
data class AngularDomainPoint(val origin: String,val runtimeInterpolation: Boolean,
    val contributors: List<AngularSampleOrigin>,val samplingStatus: String)

/** Immutable provenance for Cartesian products of selected grid knots; never generates values. */
class AngularDomain private constructor(private val az: List<Float>,private val el: List<Float>,
    private val origins: ByteArray,private val serialized: String?) {
    fun toJsonString(): String?=serialized
    internal fun matches(a: List<Float>,e: List<Float>)=az==normalize(a) && el==e
    private val cachedSummary: JsonObject by lazy {buildJsonObject {
        put("state",if(serialized==null) "UNDECLARED" else if(origins.any {it==0.toByte()}) "DECLARED_PARTIAL" else "DECLARED")
        put("authenticated",false)
        put("counts",buildJsonObject {AngularSampleOrigin.entries.forEach {kind->put(kind.name,origins.count {it.toInt()==kind.ordinal})}})
        serialized?.let {put("declaration",Json.parseToJsonElement(it))}
    }}
    fun summary(): JsonObject=cachedSummary
    internal fun at(azimuth: Float,elevation: Float,sampling: AngularSupportPoint): AngularDomainPoint {
        if(sampling.status in listOf("INVALID_GRID","INVALID_QUERY","OUTSIDE_ELEVATION"))
            return AngularDomainPoint("UNAVAILABLE",false,emptyList(),sampling.status)
        val a=wrap(azimuth)
        val ai=az.indexOfLast {it<=a}.let {if(it<0) az.lastIndex else it}
        val ei=el.indexOfLast {it<=elevation}
        val ar=if(a in az || az.size==1) listOf(ai) else listOf(ai,(ai+1)%az.size)
        val er=if(el.any {it==elevation}) listOf(ei) else listOf(ei,ei+1)
        val kinds=ar.flatMap {i->er.map {j->AngularSampleOrigin.entries[origins[i*el.size+j].toInt()]}}.distinct().sortedBy {it.ordinal}
        val interpolate=ar.size>1 || er.size>1
        val origin=when {
            az.size==1 && a!=az.first() && abs(elevation)!=90f -> "AZIMUTH_REPLICATION"
            interpolate -> "INTERPOLATED"
            else -> kinds.single().name
        }
        return AngularDomainPoint(origin,interpolate,kinds,sampling.status)
    }
    companion object {
        private fun wrap(a: Float)=((a%360f)+360f)%360f
        private fun normalize(a: List<Float>)=a.map(::wrap).distinct().sorted()
        internal fun undeclared(a: List<Float>,e: List<Float>): AngularDomain {
            val az=normalize(a);return AngularDomain(az,e.toList(),ByteArray(az.size*e.size),null)
        }
        fun parse(text: String,azimuths: List<Float>,elevations: List<Float>,complex: Boolean=false): AngularDomain {
            val root=Json.parseToJsonElement(text).jsonObject
            require(root.keys==setOf("version","provenance","regions") && root.getValue("version").jsonPrimitive.int==1) {"Unsupported angular-domain schema"}
            val provenance=root.getValue("provenance").jsonPrimitive.content
            require(provenance.isNotBlank() && provenance.length<=4096)
            val az=normalize(azimuths);val el=elevations.toList()
            require(az.isNotEmpty() && az.all(Float::isFinite) && el.isNotEmpty() && el.all {it.isFinite() && it in -90f..90f} && el.zipWithNext().all {(a,b)->a<b})
            require(az.size.toLong()*el.size<=1_000_000) {"Angular declaration exceeds one million stored knots"}
            val nodes=ByteArray(az.size*el.size)
            val azIndex=az.withIndex().associate {it.value to it.index};val elIndex=el.withIndex().associate {it.value to it.index}
            val regions=root.getValue("regions").jsonArray;require(regions.size in 1..256)
            data class Block(val kind: AngularSampleOrigin,val a: List<Float>,val e: List<Float>,val operation: String?)
            val blocks=mutableListOf<Block>()
            val canonical=regions.map {v ->
                val r=v.jsonObject;val kind=AngularSampleOrigin.valueOf(r.getValue("origin").jsonPrimitive.content)
                require(kind!=AngularSampleOrigin.UNDECLARED) {"Omit undeclared knots instead of declaring them measured"}
                val extra=when(kind) {AngularSampleOrigin.SYMMETRY->setOf("operation");AngularSampleOrigin.INTERPOLATED->setOf("method");else->emptySet()}
                require(r.keys==setOf("origin","azimuths","elevations","basis")+extra) {"Unsupported angular region fields"}
                val basis=r.getValue("basis").jsonPrimitive.content;require(basis.isNotBlank() && basis.length<=4096)
                val rawA=r.getValue("azimuths").jsonArray.map {it.jsonPrimitive.float}
                val a=normalize(rawA);val e=r.getValue("elevations").jsonArray.map {it.jsonPrimitive.float}.sorted()
                require(a.isNotEmpty() && a.size==rawA.size && a.all {it.isFinite() && it in az} && e.isNotEmpty() && e.distinct().size==e.size && e.all {it in el}) {"Angular selectors must name distinct stored grid knots"}
                val operation=r["operation"]?.jsonPrimitive?.content
                if(kind==AngularSampleOrigin.SYMMETRY) require(operation in listOf("MIRROR_AZIMUTH_ZERO","MIRROR_ELEVATION_ZERO")) {"Unsupported symmetry operation"}
                if(kind==AngularSampleOrigin.INTERPOLATED) require(r.getValue("method").jsonPrimitive.content==if(complex) "CARTESIAN_LINEAR_ANGULAR" else "DB_LINEAR_ANGULAR") {"Interpolation declaration conflicts with response representation"}
                for(x in a) for(y in e) {
                    val index=azIndex.getValue(x)*el.size+elIndex.getValue(y)
                    require(nodes[index]==0.toByte()) {"Overlapping angular-domain declarations"};nodes[index]=kind.ordinal.toByte()
                }
                blocks+=Block(kind,a,e,operation)
                buildJsonObject {
                    put("origin",kind.name);put("azimuths",JsonArray(a.map(::JsonPrimitive)));put("elevations",JsonArray(e.map(::JsonPrimitive)));put("basis",basis)
                    operation?.let {put("operation",it)};r["method"]?.let {put("method",it)}
                }
            }
            for(b in blocks.filter {it.kind==AngularSampleOrigin.SYMMETRY}) for(x in b.a) for(y in b.e) {
                val sx=if(b.operation=="MIRROR_AZIMUTH_ZERO") wrap(-x) else x
                val sy=if(b.operation=="MIRROR_ELEVATION_ZERO") -y else y
                val i=azIndex[sx] ?: -1;val j=elIndex[sy] ?: -1
                require(i>=0 && j>=0 && nodes[i*el.size+j].toInt()==AngularSampleOrigin.MEASURED.ordinal) {"Symmetry must map directly to a declared measured stored knot; no missing sources or derivation cycles"}
            }
            return AngularDomain(az,el,nodes,buildJsonObject {
                put("version",1);put("provenance",provenance);put("regions",JsonArray(canonical))
            }.toString())
        }
    }
}
