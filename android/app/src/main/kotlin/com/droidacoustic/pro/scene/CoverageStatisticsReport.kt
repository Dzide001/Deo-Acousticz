package com.droidacoustic.pro.scene

import com.droidacoustic.pro.statistics.*
import org.json.JSONArray
import org.json.JSONObject

internal fun CoverageStatistics.toReportJson(): JSONObject {
    fun AreaStatistics.json() = JSONObject().put("totalAreaM2",totalAreaM2).put("validAreaM2",validAreaM2)
        .put("missingAreaM2",missingAreaM2).put("validAreaPercent",validAreaPercent)
        .put("meanDb",meanDb ?: JSONObject.NULL).put("standardDeviationDb",standardDeviationDb ?: JSONObject.NULL)
        .put("minDb",minDb ?: JSONObject.NULL).put("maxDb",maxDb ?: JSONObject.NULL)
        .put("p10Db",p10Db ?: JSONObject.NULL).put("p50Db",p50Db ?: JSONObject.NULL)
        .put("p90Db",p90Db ?: JSONObject.NULL).put("p95Db",p95Db ?: JSONObject.NULL)
        .put("targetMinDb",target?.minDb ?: JSONObject.NULL).put("targetMaxDb",target?.maxDb ?: JSONObject.NULL)
        .put("withinTargetAreaM2",withinTargetAreaM2 ?: JSONObject.NULL)
        .put("withinTargetPercent",withinTargetPercent ?: JSONObject.NULL)
    return JSONObject().put("areaConvention","physical-3d-surface-area")
        .put("levelConvention","piecewise-linear-db-arithmetic-spatial-mean")
        .put("percentileConvention","ascending-area-cdf-over-valid-triangles")
        .put("targetDenominator","total-requested-area-including-unavailable")
        .put("overall",overall.json()).put("surfaces",JSONArray().apply {
            surfaces().forEach { put(it.statistics.json().put("surfaceKey",it.surfaceKey)
                .put("areaId",it.areaId ?: JSONObject.NULL).put("name",it.name ?: JSONObject.NULL)) }
        })
}
