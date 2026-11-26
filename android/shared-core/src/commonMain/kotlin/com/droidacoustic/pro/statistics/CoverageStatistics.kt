package com.droidacoustic.pro.statistics

import com.droidacoustic.pro.geometry.PreparedAudienceGeometry
import kotlin.math.*

/** Inclusive design window. It is independent of the display colour scale. */
data class CoverageTarget(val minDb: Double, val maxDb: Double) {
    init { require(minDb.isFinite() && maxDb.isFinite() && minDb <= maxDb) }
}

data class AreaStatistics(
    val totalAreaM2: Double, val validAreaM2: Double,
    val meanDb: Double?, val standardDeviationDb: Double?, val minDb: Double?, val maxDb: Double?,
    val p10Db: Double?, val p50Db: Double?, val p90Db: Double?, val p95Db: Double?,
    val target: CoverageTarget?, val withinTargetAreaM2: Double?
) {
    val missingAreaM2 get() = (totalAreaM2-validAreaM2).coerceAtLeast(0.0)
    val validAreaPercent get() = if (totalAreaM2 > 0) 100*validAreaM2/totalAreaM2 else 0.0
    /** Denominator is all requested area: missing predictions never count as meeting the target. */
    val withinTargetPercent get() = withinTargetAreaM2?.let { if (totalAreaM2 > 0) 100*it/totalAreaM2 else null }
}
data class SurfaceStatistics(val surfaceKey: String, val areaId: Int?, val name: String?, val statistics: AreaStatistics)
class CoverageStatistics(val overall: AreaStatistics, surfaces: List<SurfaceStatistics>) {
    private val surfaces = surfaces.toList()
    fun surfaces(): List<SurfaceStatistics> = surfaces.toList()
}

/** Spatial statistics of dB linearly interpolated over triangles, weighted by actual 3D surface area.
 * Mean is arithmetic mean dB, not an energy average or a sum of SPL. Probes carry zero area.
 * A triangle with any unavailable vertex is excluded from valid area and retained in missing area.
 * Percentiles integrate the continuous triangle distribution, avoiding mesh-density weighting.
 */
object CoverageStatisticsCalculator {
    private data class Patch(val area: Double, val a: Double, val b: Double, val c: Double) {
        fun below(level: Double, inclusive: Boolean = true): Double {
            if (a == c) return if (level > a || (inclusive && level == a)) 1.0 else 0.0
            if (level <= a) return 0.0
            if (level >= c) return 1.0
            return if (level < b) (level-a).pow(2)/((b-a)*(c-a))
                else 1-(c-level).pow(2)/((c-a)*(c-b))
        }
    }
    fun calculate(mesh: PreparedAudienceGeometry, levels: FloatArray, target: CoverageTarget? = null,
        checkCancellation: () -> Unit = {}): CoverageStatistics {
        require(levels.size == mesh.receiverCount)
        val patches = linkedMapOf<String, MutableList<Patch>>()
        val areas = linkedMapOf<String, Double>()
        val metadata = mutableMapOf<String, Pair<Int?,String?>>()
        mesh.surfaces().forEach {
            patches[it.surfaceKey]=mutableListOf(); areas[it.surfaceKey]=0.0
            metadata[it.surfaceKey]=it.areaId to it.name
        }
        for (face in mesh.triangles()) {
            checkCancellation()
            val a=mesh.receiver(face.a); val b=mesh.receiver(face.b); val c=mesh.receiver(face.c)
            require(a.surfaceKey==b.surfaceKey && a.surfaceKey==c.surfaceKey) { "Triangle spans multiple surfaces" }
            val ux=b.x.toDouble()-a.x; val uy=b.surfaceY.toDouble()-a.surfaceY; val uz=b.z.toDouble()-a.z
            val vx=c.x.toDouble()-a.x; val vy=c.surfaceY.toDouble()-a.surfaceY; val vz=c.z.toDouble()-a.z
            val area=sqrt((uy*vz-uz*vy).pow(2)+(uz*vx-ux*vz).pow(2)+(ux*vy-uy*vx).pow(2))/2
            require(area.isFinite())
            if (area <= 0) continue
            val key=a.surfaceKey
            metadata[key]=a.areaId to a.name
            areas[key]=(areas[key] ?: 0.0)+area
            val values=listOf(levels[face.a].toDouble(),levels[face.b].toDouble(),levels[face.c].toDouble()).sorted()
            val bucket=patches.getOrPut(key) { mutableListOf() }
            if (values.all { it.isFinite() }) bucket += Patch(area,values[0],values[1],values[2])
        }
        fun summary(total: Double, values: List<Patch>): AreaStatistics {
            checkCancellation()
            val valid=values.sumOf { it.area }
            if (valid == 0.0) return AreaStatistics(total,0.0,null,null,null,null,null,null,null,null,target,
                if (target == null) null else 0.0)
            val low=values.minOf { it.a }; val high=values.maxOf { it.c }
            val mean=values.sumOf { it.area*((it.a-low)+(it.b-low)+(it.c-low))/3 }/valid+low
            // Exact second central moment of a linear function on a triangle.
            val variance=values.sumOf {
                val a=it.a-mean; val b=it.b-mean; val c=it.c-mean
                it.area*(a*a+b*b+c*c+a*b+a*c+b*c)/6
            }/valid
            fun percentile(fraction: Double): Double {
                var lo=low; var hi=high
                repeat(40) {
                    checkCancellation()
                    val mid=(lo+hi)/2
                    val below=values.sumOf { it.area*it.below(mid) }
                    if (below >= fraction*valid) hi=mid else lo=mid
                }
                return hi
            }
            val within=target?.let { t -> values.sumOf {
                it.area*(it.below(t.maxDb)-it.below(t.minDb,inclusive=false)).coerceIn(0.0,1.0)
            } }
            return AreaStatistics(total,valid,mean,sqrt(variance.coerceAtLeast(0.0)),low,high,
                percentile(0.1),percentile(0.5),percentile(0.9),percentile(0.95),target,within)
        }
        val surfaces=areas.map { (key,area) -> SurfaceStatistics(key,metadata.getValue(key).first,
            metadata.getValue(key).second,summary(area,patches.getValue(key))) }
        return CoverageStatistics(summary(areas.values.sum(),patches.values.flatten()),surfaces)
    }
}
