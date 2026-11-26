package com.droidacoustic.pro.acoustics

import kotlin.math.*

/** Speaker-local spherical cap in degrees. Longitude is cyclic; radius may cover the sphere. */
data class AngularCap(val azimuthDeg: Float, val elevationDeg: Float, val radiusDeg: Float) {
    init {
        require(azimuthDeg.isFinite() && elevationDeg.isFinite() && elevationDeg in -90f..90f)
        require(radiusDeg.isFinite() && radiusDeg in 0f..180f)
    }
}

/** Bounds on absolute reference level over every direction in a cap, not just sampled directions. */
data class AngularLevelRange(val minimumDb: Float, val maximumDb: Float) {
    init {
        require(minimumDb.isFinite() && maximumDb.isFinite() && maximumDb >= minimumDb)
        require((maximumDb-minimumDb).isFinite())
    }
    val variationDb: Float get() = maximumDb-minimumDb
}

/** Adapter for custom analytic responses. Callbacks must describe the same response and
 * remain stable during a calculation. A null range means bounds are unavailable (legacy
 * finite-probe behavior), not zero variation. Arbitrary callback correctness cannot be proven
 * by sampling; use a mathematically conservative range, never a maximum over a few probes.
 * This adapter deliberately cannot be installed in FrozenResponse: mutable closures cannot
 * be deep-copied. Immutable RadialDirectivity below provides a snapshot-safe custom model. */
class BoundedRadiator(
    private val response: Radiator,
    private val range: (Int, AngularCap) -> AngularLevelRange?
) : Radiator {
    override fun levelDb(dataHz: Int, azimuthDeg: Float, elevationDeg: Float) =
        response.levelDb(dataHz, azimuthDeg, elevationDeg)
    override fun hasVariationBounds() = true
    override fun variationDb(dataHz: Int, azimuthDeg: Float, elevationDeg: Float, radiusDeg: Float): Float? =
        range(dataHz, AngularCap(azimuthDeg,elevationDeg,radiusDeg))?.variationDb
}

/** Immutable custom axisymmetric response. Per-band knots specify absolute dB at 1 m
 * versus great-circle angle from the chosen axis. Linear dB interpolation allows narrow
 * lobes and nulls without resampling them onto a coarser balloon. Bands are exact: absent
 * bands remain unavailable. Each curve covers 0..180 degrees. */
class RadialDirectivity(
    val axisAzimuthDeg: Float,
    val axisElevationDeg: Float,
    bands: Map<Int, List<Pair<Float, Float>>>
) : Radiator {
    private val curves = bands.mapValues { (_, points) -> points.map {it.first to it.second} }.toMap()
    init {
        AngularCap(axisAzimuthDeg,axisElevationDeg,0f)
        require(curves.isNotEmpty())
        for((hz,points) in curves) {
            require(hz>0 && points.size>=2)
            require(points.first().first==0f && points.last().first==180f)
            require(points.all {it.first.isFinite() && it.second.isFinite()})
            require(points.zipWithNext().all {(a,b)->a.first<b.first})
            AngularLevelRange(points.minOf {it.second},points.maxOf {it.second})
        }
    }
    private fun distance(az: Float, el: Float): Double {
        val a=(az.toDouble()-axisAzimuthDeg)*PI/180
        val e=el*PI/180;val axis=axisElevationDeg*PI/180
        // atan2 form retains precision near the axis and opposite pole.
        val x=cos(e)*sin(a)
        val y=cos(axis)*sin(e)-sin(axis)*cos(e)*cos(a)
        val z=sin(axis)*sin(e)+cos(axis)*cos(e)*cos(a)
        return atan2(sqrt(x*x+y*y),z)*180/PI
    }
    private fun at(points: List<Pair<Float,Float>>, angle: Double): Float {
        val upper=points.indexOfFirst {it.first>=angle}.coerceAtLeast(0)
        if(upper==0) return points.first().second
        val (a,va)=points[upper-1];val (b,vb)=points[upper]
        return (va+(vb.toDouble()-va)*(angle-a)/(b-a)).toFloat()
    }
    override fun levelDb(dataHz: Int, azimuthDeg: Float, elevationDeg: Float): Float {
        if(!azimuthDeg.isFinite() || !elevationDeg.isFinite() || elevationDeg !in -90f..90f) return Float.NaN
        return curves[dataHz]?.let {at(it,distance(azimuthDeg,elevationDeg))} ?: Float.NaN
    }
    fun rangeDb(dataHz: Int, cap: AngularCap): AngularLevelRange? {
        val points=curves[dataHz] ?: return null
        val centre=distance(cap.azimuthDeg,cap.elevationDeg)
        val low=max(0.0,centre-cap.radiusDeg);val high=min(180.0,centre+cap.radiusDeg)
        val values=listOf(at(points,low),at(points,high))+points.filter {it.first>low && it.first<high}.map {it.second}
        return AngularLevelRange(values.min(),values.max())
    }
    override fun hasVariationBounds() = true
    override fun variationDb(dataHz: Int, azimuthDeg: Float, elevationDeg: Float, radiusDeg: Float): Float? =
        rangeDb(dataHz,AngularCap(azimuthDeg,elevationDeg,radiusDeg))?.variationDb
}
