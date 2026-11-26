package com.droidacoustic.pro.geometry

import kotlin.math.*

enum class AudienceVertexKind { BOUNDARY, INTERIOR }
data class AudienceVertexRef(val kind: AudienceVertexKind, val index: Int)
data class AudienceBreakline(val start: AudienceVertexRef, val end: AudienceVertexRef) {
    /** Removing a control removes incident lines and keeps other references attached to the same point. */
    fun afterRemovingInterior(index: Int): AudienceBreakline? {
        if (listOf(start,end).any { it.kind==AudienceVertexKind.INTERIOR && it.index==index }) return null
        fun shift(ref: AudienceVertexRef) = if (ref.kind==AudienceVertexKind.INTERIOR && ref.index>index)
            ref.copy(index=ref.index-1) else ref
        return AudienceBreakline(shift(start),shift(end))
    }
}

/** Endpoint coordinates can arrive from Float-based platform editors. Accept their rounding
 * envelope when splitting a constraint at an existing point; keep that point's actual position
 * and height. This tolerance never invents an intersection or an elevation.
 */
internal fun breaklineChain(points: List<PlanPoint>, a: PlanPoint, b: PlanPoint, check: ()->Unit): List<Int> {
    val dx=b.x-a.x; val dz=b.z-a.z; val length2=dx*dx+dz*dz
    val tolerance=max(1e-8,maxOf(abs(a.x),abs(a.z),abs(b.x),abs(b.z))*2e-7)
    return points.indices.filter { i ->
        check()
        val p=points[i]; val projection=((p.x-a.x)*dx+(p.z-a.z)*dz)/length2
        projection>=0 && projection<=1 && abs(PolygonGeometry.cross(a,b,p))<=tolerance*sqrt(length2)
    }.sortedBy { ((points[it].x-a.x)*dx+(points[it].z-a.z)*dz)/length2 }
}

/** Recover non-crossing constraints by flipping intersected unconstrained edges. All height values
 * remain attached to original vertices. No triangulation result is returned unless every constraint
 * is present. Work is bounded and cancellable, including validation and edge-recovery loops.
 */
internal object BreaklineTriangulation {
    private data class Edge(val a: Int,val b: Int)
    private fun edge(a: Int,b: Int) = Edge(min(a,b),max(a,b))
    fun constrain(points: List<PlanPoint>, original: List<SurfaceTriangle>, boundary: List<Int>, controls: List<Int>,
        lines: List<AudienceBreakline>, check: ()->Unit): List<SurfaceTriangle> {
        require(lines.size<=256) { "At most 256 breakline segments per surface" }
        if (lines.isEmpty()) return original
        var work=0
        fun checkpoint() { check(); require(++work<=2000000) { "Breakline processing exceeds work budget" } }
        fun resolve(ref: AudienceVertexRef): Int {
            val source=if (ref.kind==AudienceVertexKind.BOUNDARY) boundary else controls
            return source.getOrNull(ref.index) ?: throw IllegalArgumentException("Invalid breakline endpoint")
        }
        fun crosses(a: Int,b: Int,c: Int,d: Int): Boolean {
            val abC=PolygonGeometry.cross(points[a],points[b],points[c])
            val abD=PolygonGeometry.cross(points[a],points[b],points[d])
            val cdA=PolygonGeometry.cross(points[c],points[d],points[a])
            val cdB=PolygonGeometry.cross(points[c],points[d],points[b])
            return ((abC>1e-9 && abD< -1e-9)||(abC< -1e-9 && abD>1e-9)) &&
                ((cdA>1e-9 && cdB< -1e-9)||(cdA< -1e-9 && cdB>1e-9))
        }
        val constraints=linkedSetOf<Edge>()
        val outer=boundary.distinct().map { points[it] }
        for (line in lines) {
            checkpoint()
            val a=resolve(line.start); val b=resolve(line.end)
            require(a!=b) { "Breakline endpoints must be different" }
            val p=points[a]; val q=points[b]
            // Split at all existing collinear vertices: T junctions, intersections and shared endpoints
            // then have a single elevation and true mesh connectivity.
            val chain=breaklineChain(points,p,q,::checkpoint)
            for ((u,v) in chain.zipWithNext()) {
                checkpoint()
                val mid=PlanPoint((points[u].x+points[v].x)/2,(points[u].z+points[v].z)/2)
                require(PolygonGeometry.contains(outer,mid)) { "Breakline leaves the outer audience boundary" }
                for (i in boundary.indices) {
                    checkpoint()
                    require(!crosses(u,v,boundary[i],boundary[(i+1)%boundary.size])) { "Breakline crosses the outer audience boundary" }
                }
                constraints+=edge(u,v)
                require(constraints.size<=4096) { "Too many subdivided breakline segments" }
            }
        }
        val ordered=constraints.toList()
        for (i in ordered.indices) for (j in 0 until i) {
            checkpoint(); val a=ordered[i]; val b=ordered[j]
            require(!crosses(a.a,a.b,b.a,b.b)) { "Crossing breaklines need a shared height point at the intersection" }
        }
        val faces=original.toMutableList()
        val adjacency=mutableMapOf<Edge,MutableSet<Int>>()
        fun edges(t: SurfaceTriangle)=listOf(edge(t.a,t.b),edge(t.b,t.c),edge(t.c,t.a))
        faces.forEachIndexed { i,t -> edges(t).forEach { adjacency.getOrPut(it) { mutableSetOf() }.add(i) } }
        fun replace(index: Int,t: SurfaceTriangle) {
            edges(faces[index]).forEach { e -> adjacency[e]?.let { it.remove(index); if (it.isEmpty()) adjacency.remove(e) } }
            faces[index]=t
            edges(t).forEach { adjacency.getOrPut(it) { mutableSetOf() }.add(index) }
        }
        fun triangle(a: Int,b: Int,c: Int) = if (PolygonGeometry.cross(points[a],points[b],points[c])>0)
            SurfaceTriangle(a,b,c) else SurfaceTriangle(b,a,c)
        val locked=mutableSetOf<Edge>()
        for (constraint in ordered) {
            checkpoint()
            if (constraint !in adjacency) {
                val queue=ArrayDeque<Edge>()
                adjacency.keys.filter { checkpoint(); crosses(constraint.a,constraint.b,it.a,it.b) }.forEach { queue.addLast(it) }
                var stalled=0
                while (constraint !in adjacency && queue.isNotEmpty()) {
                    checkpoint()
                    val crossing=queue.removeFirst()
                    val incident=adjacency[crossing]?.toList().orEmpty()
                    if (incident.size!=2 || !crosses(constraint.a,constraint.b,crossing.a,crossing.b)) continue
                    require(crossing !in locked) { "Breakline conflicts with an existing constraint" }
                    fun opposite(t: SurfaceTriangle)=listOf(t.a,t.b,t.c).single { it!=crossing.a && it!=crossing.b }
                    val c=opposite(faces[incident[0]]); val d=opposite(faces[incident[1]])
                    if (!crosses(crossing.a,crossing.b,c,d)) {
                        queue.addLast(crossing); stalled++
                        require(stalled<=queue.size) { "Cannot recover breakline in degenerate geometry" }
                        continue
                    }
                    stalled=0
                    replace(incident[0],triangle(c,d,crossing.a))
                    replace(incident[1],triangle(d,c,crossing.b))
                    if (crosses(constraint.a,constraint.b,c,d)) queue.addLast(edge(c,d))
                }
                require(constraint in adjacency) { "Could not recover the complete breakline" }
            }
            locked+=constraint
        }
        require(locked.all { it in adjacency }) { "Breakline preservation failed" }
        return faces
    }
}
