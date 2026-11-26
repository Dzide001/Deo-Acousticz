package com.droidacoustic.pro.scene

import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.geometry.PlanPoint
import com.droidacoustic.pro.geometry.AudienceElevationPoint
import org.json.JSONArray
import org.json.JSONObject

fun AudienceArea.sharedSurface(): AudienceSurface = AudienceSurface(id, name, zoneType,
    vertices.map { PlanPoint(it.first.toDouble(), it.second.toDouble()) }, baseHeightM, rakeDeg, rakeDirectionDeg,
    holes.map { ring -> ring.map { PlanPoint(it.first.toDouble(),it.second.toDouble()) } },
    exclusions.map { ring -> ring.map { PlanPoint(it.first.toDouble(),it.second.toDouble()) } },vertexOffsetsM,elevationPoints,breaklines,importedMesh)

internal fun AudienceArea.mapAudienceCoordinates(transform: (Pair<Float,Float>) -> Pair<Float,Float>): AudienceArea {
    val mesh=importedMesh?.mapCoordinates { p -> val (x,z)=transform(p.x.toFloat() to p.z.toFloat()); PlanPoint(x.toDouble(),z.toDouble()) }
    return copy(importedMesh=mesh,
        vertices=mesh?.boundary()?.map { it.x.toFloat() to it.z.toFloat() } ?: vertices.map(transform),
        holes=holes.map { it.map(transform) },exclusions=exclusions.map { it.map(transform) },
        elevationPoints=elevationPoints.map { p -> val (x,z)=transform(p.x.toFloat() to p.z.toFloat())
            p.copy(x=x.toDouble(),z=z.toDouble()) })
}

internal fun audienceRingsJson(rings: List<List<Pair<Float,Float>>>) = JSONArray().apply {
    rings.forEach { ring -> put(JSONObject().put("vertices",JSONArray().apply {
        ring.forEach { (x,z) -> put(JSONObject().put("x",x).put("z",z)) }
    })) }
}
internal fun audienceRingsFromJson(array: JSONArray?): List<List<Pair<Float,Float>>> =
    (0 until (array?.length() ?: 0)).map { i ->
        val vertices=array!!.getJSONObject(i).getJSONArray("vertices")
        (0 until vertices.length()).map { j -> val p=vertices.getJSONObject(j)
            p.getDouble("x").toFloat() to p.getDouble("z").toFloat() }
    }

internal fun audienceElevationPointsJson(points: List<AudienceElevationPoint>) = JSONArray().apply {
    points.forEach { put(JSONObject().put("x",it.x).put("z",it.z).put("offsetM",it.offsetM)) }
}
internal fun audienceElevationPointsFromJson(array: JSONArray?): List<AudienceElevationPoint> =
    (0 until (array?.length() ?: 0)).map { i -> val p=array!!.getJSONObject(i)
        AudienceElevationPoint(p.getDouble("x"),p.getDouble("z"),p.getDouble("offsetM").toFloat()) }

internal fun audienceBreaklinesJson(lines: List<AudienceBreakline>) = JSONArray().apply {
    fun ref(value: AudienceVertexRef) = JSONObject().put("kind",value.kind.name).put("index",value.index)
    lines.forEach { put(JSONObject().put("start",ref(it.start)).put("end",ref(it.end))) }
}
internal fun audienceBreaklinesFromJson(array: JSONArray?): List<AudienceBreakline> {
    fun ref(value: JSONObject) = AudienceVertexRef(AudienceVertexKind.valueOf(value.getString("kind")),value.getInt("index"))
    return (0 until (array?.length() ?: 0)).map { i -> val line=array!!.getJSONObject(i)
        AudienceBreakline(ref(line.getJSONObject("start")),ref(line.getJSONObject("end"))) }
}
