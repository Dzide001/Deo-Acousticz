package com.droidacoustic.pro.geometry

import kotlin.math.abs
import kotlin.math.roundToLong

data class SurfaceContourSegment(val x0: Float, val y0: Float, val z0: Float,
    val x1: Float, val y1: Float, val z1: Float, val levelDb: Float, val surfaceKey: String,
    val normalX: Float=0f,val normalY: Float=1f,val normalZ: Float=0f)
data class ContourRibbonVertex(val x: Float,val y: Float,val z: Float)

/** Piecewise linear contours on the solver mesh, with interpolated physical surface elevation. */
object SurfaceContours {
    /** Surface-tangent ribbon, including vertical contour lines. Geometry is shared by renderers. */
    fun ribbon(segment: SurfaceContourSegment,halfWidth: Float,offset: Float=0.25f): List<ContourRibbonVertex> {
        require(halfWidth.isFinite() && halfWidth>0 && offset.isFinite())
        val a=MeshPoint(segment.x0.toDouble(),segment.y0.toDouble(),segment.z0.toDouble())
        val b=MeshPoint(segment.x1.toDouble(),segment.y1.toDouble(),segment.z1.toDouble())
        val normal=MeshPoint(segment.normalX.toDouble(),segment.normalY.toDouble(),segment.normalZ.toDouble())
        val side=(b-a).cross(normal); val length=side.length()
        if (length<1e-10) return emptyList()
        val width=side*(halfWidth/length); val shift=normal*offset.toDouble()
        return listOf(a-width,a+width,b+width,a-width,b+width,b-width).map {
            val p=it+shift; ContourRibbonVertex(p.x.toFloat(),p.y.toFloat(),p.z.toFloat())
        }
    }
    fun build(geometry: PreparedAudienceGeometry, levels: FloatArray, thresholds: List<Float>): List<SurfaceContourSegment> {
        require(levels.size == geometry.receiverCount)
        val segments = mutableListOf<SurfaceContourSegment>()
        val emitted = mutableSetOf<String>()
        data class Point(val x: Float, val y: Float, val z: Float) {
            fun key() = "${(x*1e5).roundToLong()}:${(y*1e5).roundToLong()}:${(z*1e5).roundToLong()}"
        }
        for (face in geometry.triangles()) {
            val indices = listOf(face.a,face.b,face.c)
            val values = indices.map { levels[it] }
            if (values.any { !it.isFinite() } || values.max()-values.min()<1e-6f) continue
            val physical=indices.map { geometry.receiver(it).let { p -> MeshPoint(p.x.toDouble(),p.surfaceY.toDouble(),p.z.toDouble()) } }
            var normal=(physical[1]-physical[0]).cross(physical[2]-physical[0])
            if (normal.length()<1e-12) continue
            normal=normal*(1/normal.length())
            if (normal.y<0 || (normal.y==0.0 && (normal.x<0 || (normal.x==0.0 && normal.z<0)))) normal=normal*(-1.0)
            for (threshold in thresholds.distinct().filter { it.isFinite() }) {
                if (threshold < values.min() || threshold > values.max()) continue
                val points = mutableListOf<Point>()
                for (i in 0..2) {
                    val j=(i+1)%3
                    val a=geometry.receiver(indices[i]); val b=geometry.receiver(indices[j])
                    val va=values[i]; val vb=values[j]
                    if (threshold < minOf(va,vb) || threshold > maxOf(va,vb)) continue
                    if (abs(vb-va)<1e-6f) {
                        points += Point(a.x,a.surfaceY,a.z); points += Point(b.x,b.surfaceY,b.z)
                    } else {
                        val t=(threshold-va)/(vb-va)
                        points += Point(a.x+(b.x-a.x)*t,a.surfaceY+(b.surfaceY-a.surfaceY)*t,a.z+(b.z-a.z)*t)
                    }
                }
                val unique = points.distinctBy { it.key() }
                if (unique.size != 2) continue
                val a=unique[0]; val b=unique[1]; val surface=geometry.receiver(face.a).surfaceKey
                val key="$surface:$threshold:"+listOf(a.key(),b.key()).sorted().joinToString("/")
                if (emitted.add(key)) segments += SurfaceContourSegment(a.x,a.y,a.z,b.x,b.y,b.z,threshold,surface,normal.x.toFloat(),normal.y.toFloat(),normal.z.toFloat())
            }
        }
        return segments
    }
}
