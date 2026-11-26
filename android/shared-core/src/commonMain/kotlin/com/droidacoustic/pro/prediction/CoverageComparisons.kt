package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.geometry.PreparedAudienceGeometry
import com.droidacoustic.pro.statistics.*
import kotlinx.serialization.json.*
import kotlin.math.log10

data class CoverageConvention(val bands: List<Int>, val widthOct: Float, val weightingDb: Map<Int,Float>)

/** Selection is persisted; derived maps are recalculated from immutable design snapshots. */
data class CoverageComparisonSelection(val baselineId: Int, val candidateId: Int) {
    init { require(baselineId >= 0 && candidateId >= 0) }
    fun toJsonString() = buildJsonObject {
        put("version",1);put("baselineId",baselineId);put("candidateId",candidateId)
    }.toString()
    companion object {
        fun fromJsonString(text: String): CoverageComparisonSelection {
            val root=Json.parseToJsonElement(text).jsonObject
            require(root.getValue("version").jsonPrimitive.int==1)
            return CoverageComparisonSelection(root.getValue("baselineId").jsonPrimitive.int,root.getValue("candidateId").jsonPrimitive.int)
        }
    }
}

class CoverageDifference(val geometry: PreparedAudienceGeometry, differences: FloatArray,
    val statistics: CoverageStatistics, val convention: CoverageConvention? = null) {
    private val values=differences.copyOf()
    fun differenceAt(index: Int): Float = values[index]
}

object CoverageComparisons {
    /** Exact mesh correspondence, including ears, physical face area and assignments.
     * Labels are cosmetic. No interpolation across holes, layers or changed topology. */
    fun requireCompatible(a: PreparedAudienceGeometry,b: PreparedAudienceGeometry) {
        require(a.receiverCount==b.receiverCount && a.triangles()==b.triangles() &&
            a.receivers().zip(b.receivers()).all {(x,y) ->
                x.copy(name=null)==y.copy(name=null)
            }) { "Incompatible audience geometry: use the same surfaces, exclusions, receiver heights and sampling profile" }
    }
    fun calculate(a: PredictionRequest,b: PredictionRequest,check: ()->Unit = {}): CoverageDifference {
        require(a.coverageConvention()==b.coverageConvention()) {
            "Incompatible analysis: use the same frequency bands, bandwidth and weighting"
        }
        val geometry=a.coverageGeometry(check)
        requireCompatible(geometry,b.coverageGeometry(check))
        check()
        val first=a.calculate(check);val second=b.calculate(check)
        return compare(geometry,DoubleArray(geometry.receiverCount) {first.totalPowerAt(it)},
            DoubleArray(geometry.receiverCount) {second.totalPowerAt(it)},check,a.coverageConvention())
    }
    fun compare(geometry: PreparedAudienceGeometry,a: DoubleArray,b: DoubleArray,check: ()->Unit = {},convention: CoverageConvention? = null): CoverageDifference {
        require(a.size==geometry.receiverCount && b.size==a.size)
        val delta=FloatArray(a.size) {i ->
            check()
            if(a[i].isFinite() && b[i].isFinite() && a[i]>0 && b[i]>0)
                (10*(log10(b[i])-log10(a[i]))).toFloat() else Float.NaN
        }
        return CoverageDifference(geometry,delta,CoverageStatisticsCalculator.calculate(geometry,delta,checkCancellation=check),convention)
    }
}
