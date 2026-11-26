package com.droidacoustic.pro.geometry

import kotlin.math.abs

/** Subtract convex cutters from a convex cell. Emitted pieces form a non-overlapping partition.
 * Triangulated exclusion rings are applied successively, giving union semantics for overlaps.
 */
internal object AudienceCutouts {
    data class Cutter(val points: List<PlanPoint>) {
        val minX=points.minOf { it.x }; val maxX=points.maxOf { it.x }
        val minZ=points.minOf { it.z }; val maxZ=points.maxOf { it.z }
        fun overlaps(minX: Double,maxX: Double,minZ: Double,maxZ: Double) =
            this.maxX > minX && this.minX < maxX && this.maxZ > minZ && this.minZ < maxZ
    }
    fun prepare(surface: AudienceSurface, outer: List<PlanPoint>, check: () -> Unit): List<Cutter> {
        val holes=surface.holes(); val exclusions=surface.exclusions()
        require(holes.size+exclusions.size <= 64) { "At most 64 cutouts per audience surface" }
        require((holes+exclusions).sumOf { it.size } <= 4096) { "Audience cutout vertex budget exceeded" }
        val normalizedHoles=holes.map { PolygonGeometry.normalized(it,check) }
        fun edgesCross(a: List<PlanPoint>,b: List<PlanPoint>): Boolean {
            for (i in a.indices) for (j in b.indices) {
                check()
                if (PolygonGeometry.intersects(a[i],a[(i+1)%a.size],b[j],b[(j+1)%b.size])) return true
            }
            return false
        }
        normalizedHoles.forEachIndexed { i,hole ->
            require(!edgesCross(outer,hole) && PolygonGeometry.contains(outer,hole.first())) {
                "A hole must be strictly inside its audience surface; use an exclusion for boundary cuts"
            }
            for (j in 0 until i) {
                val other=normalizedHoles[j]
                require(!edgesCross(hole,other) && !PolygonGeometry.contains(hole,other.first()) &&
                    !PolygonGeometry.contains(other,hole.first())) { "Holes must be disjoint; use exclusions for overlapping cuts" }
            }
        }
        return (normalizedHoles+exclusions.map { PolygonGeometry.normalized(it,check) }).flatMap { ring ->
            PolygonGeometry.triangulate(ring,check).map { Cutter(listOf(ring[it.a],ring[it.b],ring[it.c])) }
        }
    }
    /** Plan masks for arbitrary 3D meshes remove a vertical prism from every facet. */
    fun prepareImported(surface: AudienceSurface,check: ()->Unit): List<Cutter> {
        val rings=surface.holes()+surface.exclusions()
        require(rings.size<=64 && rings.sumOf {it.size}<=4096) { "Audience cutout budget exceeded" }
        return rings.flatMap { ring ->
            val p=PolygonGeometry.normalized(ring,check)
            PolygonGeometry.triangulate(p,check).map { Cutter(listOf(p[it.a],p[it.b],p[it.c])) }
        }
    }
    fun subtract(polygon: List<PlanPoint>, cutter: Cutter): List<List<PlanPoint>> {
        var inside=polygon
        val outside=mutableListOf<List<PlanPoint>>()
        for (i in cutter.points.indices) {
            if (inside.size < 3) break
            val a=cutter.points[i]; val b=cutter.points[(i+1)%cutter.points.size]
            val piece=clip(inside,a,b,false)
            if (nonzero(piece)) outside+=piece
            inside=clip(inside,a,b,true)
        }
        return outside
    }
    private fun nonzero(p: List<PlanPoint>) = p.size >= 3 && abs(p.indices.sumOf {
        val a=p[it]; val b=p[(it+1)%p.size]; a.x*b.z-b.x*a.z
    }) > 1e-9
    private fun clip(input: List<PlanPoint>,a: PlanPoint,b: PlanPoint,keepInside: Boolean): List<PlanPoint> {
        if (input.isEmpty()) return input
        val output=mutableListOf<PlanPoint>()
        var prev=input.last(); var prevD=PolygonGeometry.cross(a,b,prev)
        for (next in input) {
            val nextD=PolygonGeometry.cross(a,b,next)
            val prevIn=if (keepInside) prevD>=0 else prevD<=0
            val nextIn=if (keepInside) nextD>=0 else nextD<=0
            if (prevIn != nextIn) {
                val t=prevD/(prevD-nextD)
                output+=PlanPoint(prev.x+(next.x-prev.x)*t,prev.z+(next.z-prev.z)*t)
            }
            if (nextIn) output+=next
            prev=next; prevD=nextD
        }
        return output
    }
}
