package com.droidacoustic.pro.geometry

import kotlin.math.*

/** Height offset above the surface's base/rake plane. Interior points are not acoustic receivers. */
data class AudienceElevationPoint(val x: Double, val z: Double, val offsetM: Float)
data class AudienceLineSegment(val x0: Float, val y0: Float, val z0: Float,
    val x1: Float, val y1: Float, val z1: Float)

/** Deterministic continuous triangulated height field: no vertical faces or overhangs within a surface. */
internal class SurfaceElevationMesh(val points: List<PlanPoint>, private val offsets: List<Float>,
    val triangles: List<SurfaceTriangle>) {
    fun offsetAt(face: SurfaceTriangle, p: PlanPoint): Double {
        val a=points[face.a]; val b=points[face.b]; val c=points[face.c]
        val area=PolygonGeometry.cross(a,b,c)
        val wb=PolygonGeometry.cross(a,p,c)/area
        val wc=PolygonGeometry.cross(a,b,p)/area
        return offsets[face.a]*(1-wb-wc)+offsets[face.b]*wb+offsets[face.c]*wc
    }
    fun faceAt(p: PlanPoint): SurfaceTriangle? = triangles.firstOrNull { t ->
        PolygonGeometry.cross(points[t.a],points[t.b],p)>=-1e-7 &&
            PolygonGeometry.cross(points[t.b],points[t.c],p)>=-1e-7 &&
            PolygonGeometry.cross(points[t.c],points[t.a],p)>=-1e-7
    }
    /** Split a line at facet edges and clip it to the outer domain. Duplicate shared-edge pieces are suppressed. */
    fun trace(a: PlanPoint,b: PlanPoint,planeHeight: (PlanPoint)->Double): List<AudienceLineSegment> {
        val parts=mutableListOf<AudienceLineSegment>()
        val seen=mutableSetOf<Pair<Long,Long>>()
        for (face in triangles) {
            var lo=0.0; var hi=1.0
            val corners=listOf(points[face.a],points[face.b],points[face.c])
            for (i in 0..2) {
                val edge0=corners[i]; val edge1=corners[(i+1)%3]
                val start=PolygonGeometry.cross(edge0,edge1,a)
                val end=PolygonGeometry.cross(edge0,edge1,b)
                val delta=end-start
                if (abs(delta)<1e-12) { if (start < -1e-9) hi=-1.0 }
                else if (delta>0) lo=max(lo,-start/delta) else hi=min(hi,-start/delta)
            }
            if (hi-lo<1e-9) continue
            if (!seen.add((lo*1e9).roundToLong() to (hi*1e9).roundToLong())) continue
            fun point(t: Double)=PlanPoint(a.x+(b.x-a.x)*t,a.z+(b.z-a.z)*t)
            val p=point(lo); val q=point(hi)
            parts+=AudienceLineSegment(p.x.toFloat(),(planeHeight(p)+offsetAt(face,p)).toFloat(),p.z.toFloat(),
                q.x.toFloat(),(planeHeight(q)+offsetAt(face,q)).toFloat(),q.z.toFloat())
        }
        return parts
    }
    companion object {
        fun build(outline: List<PlanPoint>, offsets: List<Float>, controls: List<AudienceElevationPoint>,
            breaklines: List<AudienceBreakline>, check: ()->Unit): SurfaceElevationMesh {
            require(offsets.isEmpty() || offsets.size==outline.size) { "Supply one elevation offset per boundary vertex" }
            require(offsets.all { it.isFinite() && abs(it)<=10000f }) { "Invalid boundary elevation offset" }
            require(controls.size<=256) { "At most 256 interior elevation points per surface" }
            require(controls.all { it.x.isFinite() && it.z.isFinite() && it.offsetM.isFinite() && abs(it.offsetM)<=10000f }) {
                "Invalid interior elevation point"
            }
            val explicit=offsets.isNotEmpty() || controls.isNotEmpty() || breaklines.isNotEmpty()
            val normalized=PolygonGeometry.normalized(outline,check,preserveCollinear=explicit)
            val byPoint=outline.indices.associate { outline[it] to (offsets.getOrNull(it) ?: 0f) }
            if (outline.first()==outline.last() && offsets.isNotEmpty())
                require(offsets.first()==offsets.last()) { "Closing vertex elevations disagree" }
            val points=normalized.toMutableList()
            val heights=points.map { byPoint.getValue(it) }.toMutableList()
            var faces=PolygonGeometry.triangulate(points,check)
            for (control in controls) {
                check()
                val p=PlanPoint(control.x,control.z)
                require(PolygonGeometry.contains(normalized,p) && normalized.indices.none { i ->
                    PolygonGeometry.onSegment(normalized[i],normalized[(i+1)%normalized.size],p)
                }) { "Elevation points must lie strictly inside the outer boundary" }
                require(points.none { hypot(it.x-p.x,it.z-p.z)<1e-5 }) { "Duplicate or coincident elevation points" }
                val newIndex=points.size
                val next=mutableListOf<SurfaceTriangle>()
                var inserted=false
                for (face in faces) {
                    check()
                    val corners=listOf(face.a,face.b,face.c)
                    val crosses=(0..2).map { PolygonGeometry.cross(points[corners[it]],points[corners[(it+1)%3]],p) }
                    if (crosses.any { it < -1e-9 }) { next+=face; continue }
                    inserted=true
                    // A point on an internal edge splits both incident faces, preserving continuity.
                    for (i in 0..2) if (crosses[i]>1e-9) next+=SurfaceTriangle(corners[i],corners[(i+1)%3],newIndex)
                }
                require(inserted) { "Could not locate elevation point on the surface" }
                points+=p; heights+=control.offsetM; faces=next
            }
            val constrained=BreaklineTriangulation.constrain(points,faces,outline.map { points.indexOf(it) },
                (normalized.size until points.size).toList(),breaklines,check)
            return SurfaceElevationMesh(points,heights,constrained)
        }
    }
}
