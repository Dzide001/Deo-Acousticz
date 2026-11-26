package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.scene.ClfData
import kotlinx.serialization.json.*
import kotlin.math.*

/** Sampling diagnostics, not a proof of directivity accuracy or a new interpolation policy. */
class AngularGridSupport internal constructor(azimuths: List<Float>,elevations: List<Float>,val valid: Boolean) {
    private val az=azimuths.toList()
    private val el=elevations.toList()
    // Above this advisory spacing, narrow lobes may fall between measurements. Even finer grids are not certified.
    val advisorySpacingDeg=5f
    private val gaps=if(az.size>1) az.indices.map {i -> if(i==az.lastIndex) az.first()+360-az[i] else az[i+1]-az[i]} else emptyList()
    val maximumAzimuthGapDeg=gaps.maxOrNull()
    val maximumElevationGapDeg=el.zipWithNext().maxOfOrNull {(a,b)->b-a}
    private val typicalGap=gaps.sorted().let {if(it.isEmpty()) 0f else it[(it.size-1)/2]}
    private fun possibleSector(gap: Float)=gap>max(10f,3*typicalGap)
    val issues: List<String> = if(!valid) listOf("INVALID_GRID") else buildList {
        if(az.size==1) add("SINGLE_AZIMUTH")
        if(el.size==1) add("SINGLE_ELEVATION")
        if(el.first()>-90f || el.last()<90f) add("LIMITED_ELEVATION")
        if(maximumAzimuthGapDeg?.let {it>advisorySpacingDeg}==true) add("SPARSE_AZIMUTH")
        if(maximumElevationGapDeg?.let {it>advisorySpacingDeg}==true) add("SPARSE_ELEVATION")
        if(gaps.any(::possibleSector)) add("POSSIBLE_MISSING_SECTOR")
    }
    /** Same angle normalization as PolarGrid. A knot is not independent evidence of accuracy. */
    fun at(azimuthDeg: Float,elevationDeg: Float): AngularSupportPoint {
        if(!valid) return AngularSupportPoint("INVALID_GRID",null,null)
        if(!azimuthDeg.isFinite() || !elevationDeg.isFinite() || elevationDeg !in -90f..90f)
            return AngularSupportPoint("INVALID_QUERY",null,null)
        if(elevationDeg<el.first() || elevationDeg>el.last()) return AngularSupportPoint("OUTSIDE_ELEVATION",null,null)
        if(abs(elevationDeg)==90f && elevationDeg in el) return AngularSupportPoint("STORED_POLE",0f,0f)
        val angle=((azimuthDeg%360f)+360f)%360f
        val measuredAz=angle in az;val measuredEl=el.any {it==elevationDeg}
        val ai=az.indexOfLast {it<=angle}.let {if(it<0) az.lastIndex else it}
        val azGap=if(measuredAz) 0f else gaps.getOrNull(ai)
        val ei=el.indexOfLast {it<=elevationDeg}
        val elGap=if(measuredEl) 0f else el[ei+1]-el[ei]
        val status=when {
            az.size==1 && !measuredAz -> "SINGLE_AZIMUTH_EXTRAPOLATION"
            measuredAz && measuredEl -> "STORED_KNOT"
            azGap!=null && possibleSector(azGap) -> "POSSIBLE_MISSING_SECTOR"
            (azGap ?: 0f)>advisorySpacingDeg || elGap>advisorySpacingDeg -> "SPARSE_INTERPOLATION"
            else -> "INTERPOLATED"
        }
        return AngularSupportPoint(status,azGap,elGap)
    }
    fun json(): JsonObject=buildJsonObject {
        put("valid",valid);put("azimuthCount",az.size);put("elevationCount",el.size)
        put("minimumElevationDeg",el.firstOrNull()?.let(::JsonPrimitive) ?: JsonNull)
        put("maximumElevationDeg",el.lastOrNull()?.let(::JsonPrimitive) ?: JsonNull)
        put("maximumAzimuthGapDeg",maximumAzimuthGapDeg?.let(::JsonPrimitive) ?: JsonNull)
        put("maximumElevationGapDeg",maximumElevationGapDeg?.let(::JsonPrimitive) ?: JsonNull)
        put("advisorySpacingDeg",advisorySpacingDeg);put("issues",JsonArray(issues.map(::JsonPrimitive)))
    }
}

data class AngularSupportPoint(val status: String,val azimuthBracketDeg: Float?,val elevationBracketDeg: Float?)

object SpeakerAngularSupport {
    private fun entries(data: ClfData): List<Triple<String,Int,PolarGrid>> =
        data.patterns.map {Triple("magnitude",it.frequencyHz,it.sharedGrid)}+
            data.complexResponse?.angularGrids().orEmpty().map {(hz,s)->Triple("complex",hz,s)}
    fun warnings(data: ClfData): List<String> {
        val groups=linkedMapOf<Pair<String,String>,MutableList<Int>>()
        entries(data).forEach {(kind,hz,grid)->
            val domain=grid.angularDomainSummary();val counts=domain.getValue("counts").jsonObject
            val flags=grid.angularSupport.issues+buildList {
                if(counts.getValue("UNDECLARED").jsonPrimitive.int>0) add("ORIGIN_UNDECLARED")
                if(counts.getValue("SYMMETRY").jsonPrimitive.int>0) add("ORIGIN_SYMMETRY")
                if(counts.getValue("INTERPOLATED").jsonPrimitive.int>0) add("ORIGIN_INTERPOLATED")
            }
            flags.forEach {code->groups.getOrPut(kind to code) {mutableListOf()}.add(hz)}
        }
        return groups.map {(key,frequencies)->
            val bands=frequencies.distinct().sorted();val labels=bands.take(8).joinToString()+if(bands.size>8) " … (${bands.size} bands)" else ""
            val reason=when(key.second) {
                "ORIGIN_UNDECLARED" -> "stored-knot origins are undeclared; measured acquisition is not assumed."
                "ORIGIN_SYMMETRY" -> "includes symmetry-derived samples; symmetry is a declared assumption, not an independent measurement."
                "ORIGIN_INTERPOLATED" -> "includes pre-interpolated stored samples; denser storage does not add measured angular resolution."
                "INVALID_GRID" -> "invalid angular grid."
                "SINGLE_AZIMUTH" -> "one stored azimuth only; existing interpolation repeats that column at other azimuths away from poles, which are unmeasured."
                "SINGLE_ELEVATION" -> "one stored elevation only; other elevations are unsupported."
                "LIMITED_ELEVATION" -> "limited stored elevation range; directions outside it are unavailable."
                "SPARSE_AZIMUTH" -> "azimuth gaps exceed the 5° sampling advisory; interpolation can miss narrow lobes/nulls."
                "SPARSE_ELEVATION" -> "elevation gaps exceed the 5° sampling advisory; interpolation can miss narrow lobes/nulls."
                else -> "unusually large cyclic azimuth gap; possible missing sector. Existing interpolation crosses it without measured coverage evidence."
            }
            "${key.first} angular support ($labels Hz): $reason"
        }
    }
    /** Exact stored frequency only; no frequency interpolation of provenance is invented. */
    fun at(data: ClfData,frequencyHz: Int,azimuth: Float,elevation: Float,response: String="magnitude"): AngularDomainPoint? {
        require(response in listOf("magnitude","complex"))
        return if(response=="complex") data.complexResponse?.angularDomainAt(frequencyHz,azimuth,elevation)
            else data.patternAt(frequencyHz)?.sharedGrid?.angularDomainAt(azimuth,elevation)
    }
    fun reportJson(data: ClfData): String=buildJsonObject {
        put("schema",2);put("scope","Stored sampling plus declared origins only; no accuracy guarantee or authenticated measurement evidence, including at 5° or finer spacing")
        put("policy","Diagnostics only; existing interpolation and prediction values unchanged. Large-gap missing-sector detection is heuristic, not a coverage declaration.")
        put("bands",JsonArray(entries(data).sortedWith(compareBy({it.first},{it.second})).map {(kind,hz,s)->buildJsonObject {
            put("response",kind);put("frequencyHz",hz);put("support",s.angularSupport.json());put("domain",s.angularDomainSummary())
        }}))
    }.toString()
}
