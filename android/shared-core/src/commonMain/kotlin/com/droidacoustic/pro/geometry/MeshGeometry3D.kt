package com.droidacoustic.pro.geometry

import kotlin.math.*

internal data class MeshPoint(val x: Double,val y: Double,val z: Double) {
    operator fun plus(b: MeshPoint)=MeshPoint(x+b.x,y+b.y,z+b.z)
    operator fun minus(b: MeshPoint)=MeshPoint(x-b.x,y-b.y,z-b.z)
    operator fun times(s: Double)=MeshPoint(x*s,y*s,z*s)
    fun dot(b: MeshPoint)=x*b.x+y*b.y+z*b.z
    fun cross(b: MeshPoint)=MeshPoint(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x)
    fun length()=sqrt(dot(this))
    fun coordinate(axis: Int)=when(axis) {0->x; 1->y; else->z}
    fun plan()=PlanPoint(x,z)
    fun key()=Triple((x*1e5).roundToLong(),(y*1e5).roundToLong(),(z*1e5).roundToLong())
}
internal fun AudienceMeshVertex.point()=MeshPoint(x,y.toDouble(),z)
internal fun triangleArea3D(p: List<MeshPoint>) = if (p.size<3) 0.0 else
    (1 until p.lastIndex).sumOf { (p[it]-p[0]).cross(p[it+1]-p[0]).length()/2 }
internal fun meshEdges(t: SurfaceTriangle)=listOf(t.a to t.b,t.b to t.c,t.c to t.a)
internal fun meshEdge(a: Int,b: Int)=min(a,b) to max(a,b)

/** Clip a convex 3D polygon against a plane; signed distance is nonnegative inside.
 * Coplanar faces belong only to the inside. This matters for vertical cutout boundaries.
 */
internal fun clipMeshPolygon(input: List<MeshPoint>, distance: (MeshPoint)->Double,inside: Boolean=true): List<MeshPoint> {
    if (input.isEmpty()) return input
    val values=input.map(distance)
    if (values.all { abs(it)<1e-10 }) return if (inside) input else emptyList()
    val output=mutableListOf<MeshPoint>()
    var prev=input.last(); var pd=values.last()
    for (i in input.indices) {
        val next=input[i]; val nd=values[i]
        val a=if (inside) pd>=0 else pd<=0; val b=if (inside) nd>=0 else nd<=0
        if (a!=b) output+=prev+(next-prev)*(pd/(pd-nd))
        if (b) output+=next
        prev=next; pd=nd
    }
    return output
}

/** Returns the portion of a segment contacting a triangle, including coplanar segments. */
internal fun triangleContact(a: MeshPoint,b: MeshPoint,t: List<MeshPoint>): List<MeshPoint> {
    val n=(t[1]-t[0]).cross(t[2]-t[0]); val length=n.length()
    val da=(a-t[0]).dot(n)/length; val db=(b-t[0]).dot(n)/length
    val tolerance=1e-7
    if (da>tolerance && db>tolerance || da< -tolerance && db< -tolerance) return emptyList()
    val axis=if (abs(n.x)>=abs(n.y) && abs(n.x)>=abs(n.z)) 0 else if (abs(n.y)>=abs(n.z)) 1 else 2
    fun project(p: MeshPoint)=when(axis) {0->PlanPoint(p.y,p.z);1->PlanPoint(p.x,p.z);else->PlanPoint(p.x,p.y)}
    val tri=t.map(::project)
    val sign=if (PolygonGeometry.cross(tri[0],tri[1],tri[2])>0) 1 else -1
    fun inside(p: MeshPoint)=tri.indices.all { i -> sign*PolygonGeometry.cross(tri[i],tri[(i+1)%3],project(p))>=-1e-8 }
    if (abs(da)<=tolerance && abs(db)<=tolerance) {
        var lo=0.0; var hi=1.0
        for (i in tri.indices) {
            val s=sign*PolygonGeometry.cross(tri[i],tri[(i+1)%3],project(a))
            val e=sign*PolygonGeometry.cross(tri[i],tri[(i+1)%3],project(b)); val delta=e-s
            if (abs(delta)<1e-14) { if (s< -1e-8) return emptyList() }
            else if (delta>0) lo=max(lo,-s/delta) else hi=min(hi,-s/delta)
        }
        if (lo>hi+1e-10) return emptyList()
        return listOf(a+(b-a)*lo.coerceIn(0.0,1.0),a+(b-a)*hi.coerceIn(0.0,1.0))
    }
    val parameter=da/(da-db)
    if (parameter< -1e-10 || parameter>1+1e-10) return emptyList()
    val p=a+(b-a)*parameter.coerceIn(0.0,1.0)
    return if (inside(p)) listOf(p) else emptyList()
}

internal data class ValidatedMesh(val faces: List<SurfaceTriangle>,val loops: List<List<Int>>)
internal object MeshValidation3D {
    fun validate(nodes: List<AudienceMeshVertex>,triangles: List<SurfaceTriangle>,check: ()->Unit): ValidatedMesh {
        val p=nodes.map { it.point() }
        require(p.map { it.key() }.distinct().size==p.size) { "Coincident mesh vertices or detail below coverage precision" }
        val rounded=p.map { MeshPoint(it.x.toFloat().toDouble(),it.y,it.z.toFloat().toDouble()) }
        require(rounded.distinct().size==p.size) { "Mesh detail is below receiver coordinate precision; move the mesh closer to the origin" }
        val unique=mutableSetOf<List<Int>>(); val used=mutableSetOf<Int>()
        val incident=linkedMapOf<Pair<Int,Int>,MutableList<Int>>()
        val atVertex=List(p.size) { mutableListOf<Int>() }
        triangles.forEachIndexed { i,t ->
            check(); val ids=listOf(t.a,t.b,t.c)
            require(ids.all { it in p.indices } && ids.distinct().size==3) { "Invalid mesh triangle indices" }
            require(unique.add(ids.sorted())) { "Duplicate mesh triangle" }; used.addAll(ids)
            val n=(p[t.b]-p[t.a]).cross(p[t.c]-p[t.a])
            val rn=(rounded[t.b]-rounded[t.a]).cross(rounded[t.c]-rounded[t.a])
            require(n.length()>1e-8) { "Degenerate audience triangle" }
            require(rn.length()>1e-8 && rn.dot(n)>0) { "Triangle collapses at receiver coordinate precision; move the mesh closer to the origin" }
            ids.forEach { atVertex[it]+=i }
            meshEdges(t).forEach { (a,b) -> val list=incident.getOrPut(meshEdge(a,b)) {mutableListOf()}; list+=i
                require(list.size<=2) { "Non-manifold mesh edge" } }
        }
        require(used.size==p.size) { "Mesh contains unused vertices" }
        val neighbors=List(triangles.size) {mutableListOf<Pair<Int,Boolean>>()}
        incident.forEach { (_,list) -> if (list.size==2) {
            val a=list[0]; val b=list[1]
            val same=meshEdges(triangles[a]).any { it in meshEdges(triangles[b]) }
            neighbors[a]+=b to same; neighbors[b]+=a to same
        } }
        val flips=mutableMapOf(0 to false); val queue=ArrayDeque<Int>(); queue.addLast(0)
        while (queue.isNotEmpty()) {
            check(); val current=queue.removeFirst()
            for ((next,toggle) in neighbors[current]) {
                val flip=flips.getValue(current) xor toggle
                if (next in flips) require(flips[next]==flip) { "Non-orientable audience mesh" }
                else { flips[next]=flip; queue.addLast(next) }
            }
        }
        require(flips.size==triangles.size) { "Disconnected mesh; import each connected surface separately" }
        // Edge manifoldness alone does not exclude pinched/bow-tie vertices.
        for (v in p.indices) {
            check(); val local=atVertex[v].toSet(); val seen=mutableSetOf(local.first()); queue.addLast(local.first())
            while (queue.isNotEmpty()) {
                check(); val current=queue.removeFirst()
                for ((next,_) in neighbors[current]) if (next in local && seen.add(next)) queue.addLast(next)
            }
            require(seen.size==local.size) { "Non-manifold mesh vertex" }
        }
        fun reverse(t: SurfaceTriangle)=SurfaceTriangle(t.a,t.c,t.b)
        var faces=triangles.mapIndexed { i,t -> if (flips.getValue(i)) reverse(t) else t }
        // Preserve the former CCW-XZ convention for height fields; orientation has no acoustic sign.
        val projected=faces.sumOf { PolygonGeometry.cross(p[it.a].plan(),p[it.b].plan(),p[it.c].plan()) }
        if (projected< -1e-8) faces=faces.map(::reverse)
        data class Bounds(val i: Int,val min: MeshPoint,val max: MeshPoint)
        val boxes=faces.mapIndexed { i,t -> val q=listOf(p[t.a],p[t.b],p[t.c])
            Bounds(i,MeshPoint(q.minOf{it.x},q.minOf{it.y},q.minOf{it.z}),MeshPoint(q.maxOf{it.x},q.maxOf{it.y},q.maxOf{it.z})) }.sortedBy {it.min.x}
        for (i in boxes.indices) for (j in i+1 until boxes.size) {
            check(); val a=boxes[i]; val b=boxes[j]
            if (b.min.x>a.max.x+1e-7) break
            if (b.min.y>a.max.y+1e-7 || a.min.y>b.max.y+1e-7 || b.min.z>a.max.z+1e-7 || a.min.z>b.max.z+1e-7) continue
            val ta=faces[a.i]; val tb=faces[b.i]
            val shared=listOf(ta.a,ta.b,ta.c).intersect(setOf(tb.a,tb.b,tb.c)).toList()
            fun allowed(point: MeshPoint): Boolean {
                if (shared.any { (point-p[it]).length()<1e-7 }) return true
                if (shared.size!=2) return false
                val u=p[shared[0]]; val v=p[shared[1]]; val edge=v-u
                val t=(point-u).dot(edge)/edge.dot(edge)
                return t>=-1e-9 && t<=1+1e-9 && (point-(u+edge*t)).length()<1e-7
            }
            for ((source,target) in listOf(ta to tb,tb to ta)) {
                val targetPoints=listOf(p[target.a],p[target.b],p[target.c])
                for ((u,v) in meshEdges(source)) {
                    check()
                    require(triangleContact(p[u],p[v],targetPoints).all(::allowed)) { "Mesh faces intersect, overlap or meet without shared topology" }
                }
            }
        }
        val boundary=faces.flatMap { t -> meshEdges(t).filter { (a,b) -> incident.getValue(meshEdge(a,b)).size==1 } }
        require(boundary.map {it.first}.distinct().size==boundary.size && boundary.map {it.second}.distinct().size==boundary.size) { "Non-manifold boundary vertex" }
        val next=boundary.toMap(); val remaining=next.keys.toMutableSet(); val loops=mutableListOf<List<Int>>()
        while (remaining.isNotEmpty()) {
            val start=remaining.first(); var current=start; val loop=mutableListOf<Int>()
            do { check(); require(remaining.remove(current)) {"Invalid boundary loop"}; loop+=current
                current=next[current] ?: throw IllegalArgumentException("Open mesh boundary")
            } while (current!=start)
            loops+=loop
        }
        require(loops.size<=65) { "At most 65 mesh boundary loops" }
        return ValidatedMesh(faces,loops.sortedByDescending { r -> abs(r.indices.sumOf { i -> val a=p[r[i]]; val b=p[r[(i+1)%r.size]]; a.x*b.z-b.x*a.z }) })
    }
}
