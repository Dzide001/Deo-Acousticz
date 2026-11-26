package com.droidacoustic.pro.geometry

import kotlin.math.*

/** Unit integral over an outgoing hemisphere. Scattered energy is incoherent. */
object DiffuseRadiation {
    const val DEFAULT_RAYS = 2048
    fun density(cosine: Double): Double {
        require(cosine.isFinite())
        return cosine.coerceIn(0.0,1.0)/PI
    }
    /** Equal solid-angle, deterministic antipodal Fibonacci samples; independent of mesh tessellation. */
    fun directions(count: Int=DEFAULT_RAYS): List<BoundaryPoint> {
        require(count in 128..32768 && count%2==0) {"Diffuse ray count must be even, 128–32768"}
        val half=count/2
        return (0 until half).flatMap {i ->
            val y=(i+.5)/half;val radius=sqrt(1-y*y);val phi=i*PI*(3-sqrt(5.0))
            val p=BoundaryPoint(radius*cos(phi),y,radius*sin(phi))
            listOf(p,BoundaryPoint(-p.x,-p.y,-p.z))
        }
    }
}

/** An intercepted source solid-angle cell. Its energy is assigned to one physical surface only. */
data class DiffuseSurfaceSample(val point: BoundaryPoint,val normalTowardSource: BoundaryPoint,
    val sourceDistance: Double,val solidAngle: Double,val incidenceDeg: Float,val materials: Set<BoundaryMaterial>) {
    fun receiverFactor(receiver: BoundaryPoint,minDistance: Double=0.25): Double {
        require(minDistance>0 && minDistance.isFinite())
        val dx=receiver.x-point.x;val dy=receiver.y-point.y;val dz=receiver.z-point.z
        val distance=sqrt(dx*dx+dy*dy+dz*dz)
        if(distance<=1e-6) return 0.0
        val cosine=(dx*normalTowardSource.x+dy*normalTowardSource.y+dz*normalTowardSource.z)/distance
        return solidAngle*DiffuseRadiation.density(cosine)/max(distance,minDistance).pow(2)
    }
}

/** Common surface cell: Lambertian outgoing area/distance weight, with no incident source factor. */
internal data class IlluminationSample(val point:BoundaryPoint,val normal:BoundaryPoint,val receiverDistance:Double,
    val outgoingWeight:Double,val visibleEmitters:IntArray,val materials:Set<BoundaryMaterial>)
