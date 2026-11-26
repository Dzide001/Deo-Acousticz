package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.statistics.CoverageStatistics
import kotlinx.serialization.json.*
import kotlin.math.max

enum class PredictionAvailability { AVAILABLE, PARTIAL, UNAVAILABLE }
data class MissingSourceBand(val sourceId: Int, val dataHz: Int)

/** Numerical data availability, not calibration, physical accuracy or manufacturer validation. */
class PredictionValidity private constructor(
    val availability: PredictionAvailability, val receiverCount: Int, val availableReceiverCount: Int,
    unavailableBands: Collection<Int>, missing: Collection<MissingSourceBand>,
    val totalAreaM2: Double?, val availableAreaM2: Double?
) {
    private val bands=unavailableBands.distinct().sorted()
    private val missing=missing.distinct().sortedWith(compareBy({it.sourceId},{it.dataHz}))
    fun unavailableBands()=bands.toList()
    fun missingSourceBands()=missing.toList()
    fun status(): String {
        val prefix=when(availability) {
            PredictionAvailability.AVAILABLE -> "Results available"
            PredictionAvailability.PARTIAL -> "Results partial"
            PredictionAvailability.UNAVAILABLE -> "Results unavailable"
        }
        if(availability==PredictionAvailability.AVAILABLE) return prefix
        if(receiverCount==0) return "$prefix • no receivers"
        val details=if(missing.isNotEmpty()) {
            val hz=missing.map {it.dataHz}.distinct().sorted()
            val ids=missing.map {it.sourceId}.distinct()
            "missing ${hz.take(3).joinToString(", ")}${if(hz.size>3) ", …" else ""} Hz (S${ids.first()}${if(ids.size>1) ", …" else ""})"
        } else "check response/processing"
        return "$prefix • $details"
    }
    fun toJsonString()=buildJsonObject {
        put("availability",availability.name);put("receiverCount",receiverCount);put("availableReceiverCount",availableReceiverCount)
        put("unavailableBandsHz",JsonArray(bands.map(::JsonPrimitive)))
        put("missingSourceBands",JsonArray(missing.map {buildJsonObject {put("sourceId",it.sourceId);put("dataHz",it.dataHz)}}))
        totalAreaM2?.let {put("totalAreaM2",it)};availableAreaM2?.let {put("availableAreaM2",it)}
    }.toString()
    companion object {
        fun summarize(levels: FloatArray, unavailableBands: Collection<Int> = emptyList(),
            missing: Collection<MissingSourceBand> = emptyList(), statistics: CoverageStatistics? = null): PredictionValidity {
            val finite=levels.count {it.isFinite()}
            val area=statistics?.overall
            val hasArea=area!=null && area.totalAreaM2>0
            val state=when {
                finite==0 || (hasArea && area!!.validAreaM2<=0) -> PredictionAvailability.UNAVAILABLE
                finite<levels.size || (hasArea && area!!.missingAreaM2>max(1e-8,area.totalAreaM2*1e-8)) -> PredictionAvailability.PARTIAL
                else -> PredictionAvailability.AVAILABLE
            }
            return PredictionValidity(state,levels.size,finite,unavailableBands,missing,area?.totalAreaM2,area?.validAreaM2)
        }
    }
}
