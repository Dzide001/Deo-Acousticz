package com.droidacoustic.pro.geometry

import kotlin.math.*

/** Imported faces are sampled in world XYZ grid cells, never projected into a single height field. */
internal object ImportedSurfaceGeometry {
    fun points(surface: AudienceSurface): List<MeshPoint> = surface.importedMesh!!.vertices().map {
        MeshPoint(it.x,it.y+surface.planeY(PlanPoint(it.x,it.z)),it.z)
    }
    fun prepare(surface: AudienceSurface,spacing: Float,maxReceivers: Int,check: ()->Unit): PreparedAudienceGeometry {
        val source=points(surface); val mesh=surface.importedMesh!!; val triangles=mesh.triangles()
        val cutters=AudienceCutouts.prepareImported(surface,check)
        val receivers=mutableListOf<SurfaceReceiver>(); val faces=mutableListOf<SurfaceTriangle>()
        val indices=mutableMapOf<Pair<Pair<Int?,MeshFaceAssignment>,Triple<Long,Long,Long>>,MutableList<Int>>()
        val published=mutableMapOf<Pair<Pair<Int?,MeshFaceAssignment>,Triple<Float,Float,Float>>,Int>()
        val owners=mutableListOf<MutableSet<Int>>()
        val exact=mutableListOf<MeshPoint>()
        fun index(p: MeshPoint,faceId: Int): Int {
            val assignment=mesh.faceAssignment(faceId)
            val membership=mesh.groupForFace(faceId)?.id to assignment
            val identity=mesh.faceIdentity(faceId,surface.id,surface.name)
            val key=membership to p.key()
            indices[key]?.firstOrNull { (exact[it]-p).length()<1e-8 }?.let { owners[it]+=faceId; return it }
            val floatKey=membership to Triple(p.x.toFloat(),p.y.toFloat(),p.z.toFloat())
            published[floatKey]?.let { existing ->
                val face=triangles[faceId]; val ids=setOf(face.a,face.b,face.c)
                fun atSharedTopology(other: Int): Boolean {
                    if (other==faceId) return true
                    val t=triangles[other]; val shared=ids.intersect(setOf(t.a,t.b,t.c)).toList()
                    if (shared.any { (p-source[it]).length()<1e-6 }) return true
                    if (shared.size!=2) return false
                    val a=source[shared[0]]; val edge=source[shared[1]]-a
                    val parameter=((p-a).dot(edge)/edge.dot(edge)).coerceIn(0.0,1.0)
                    return (p-(a+edge*parameter)).length()<1e-6
                }
                // Slab intersections can create sub-micrometre slivers after Float editor transforms.
                // Coalesce only on the same source facet or its shared topology, never across layers.
                require((exact[existing]-p).length()<1e-6 && owners[existing].any(::atSharedTopology)) {
                    "3D coverage vertices collapse at receiver precision; move the mesh closer to the origin"
                }
                owners[existing]+=faceId; return existing
            }
            require(receivers.size<maxReceivers) { "Audience mesh exceeds receiver budget; increase grid spacing" }
            receivers+=SurfaceReceiver(p.x.toFloat(),p.y.toFloat()+assignment.earOffset(surface.zoneType)!!,p.z.toFloat(),p.y.toFloat(),
                identity.surfaceKey,surface.id,identity.name,assignment.zoneType(surface.zoneType),surface.rakeDeg,surface.rakeDirectionDeg)
            exact+=p; owners+=mutableSetOf(faceId); published[floatKey]=receivers.lastIndex
            indices.getOrPut(key) {mutableListOf()}.add(receivers.lastIndex)
            return receivers.lastIndex
        }
        for ((faceId,t) in triangles.withIndex()) {
            check()
            if (mesh.faceAssignment(faceId).zoneType(surface.zoneType)==null) continue
            var pieces=listOf(listOf(source[t.a],source[t.b],source[t.c]))
            for (cutter in cutters) {
                check(); val result=mutableListOf<List<MeshPoint>>()
                for (piece in pieces) {
                    var inside=piece
                    for (i in cutter.points.indices) {
                        check(); if (inside.isEmpty()) break
                        val a=cutter.points[i]; val b=cutter.points[(i+1)%cutter.points.size]
                        fun distance(p: MeshPoint)=PolygonGeometry.cross(a,b,p.plan())
                        val outside=clipMeshPolygon(inside,::distance,false)
                        if (triangleArea3D(outside)>1e-10) result+=outside
                        inside=clipMeshPolygon(inside,::distance)
                    }
                }
                require(result.size<=4096) { "Audience cutout fragmentation exceeds budget" }; pieces=result
            }
            // Sequential slabs avoid iterating the entire volume of an inclined triangle's bounding box.
            for (axis in 0..2) {
                val split=mutableListOf<List<MeshPoint>>()
                for (piece in pieces) {
                    val start=floor(piece.minOf { it.coordinate(axis) }/spacing).toInt()
                    val end=max(start+1,ceil(piece.maxOf { it.coordinate(axis) }/spacing).toInt())
                    require(end.toLong()-start<=500000) { "Audience extent exceeds mesh budget" }
                    for (cell in start until end) {
                        check(); val lo=cell*spacing.toDouble(); val hi=lo+spacing
                        var clipped=clipMeshPolygon(piece,{ it.coordinate(axis)-lo })
                        clipped=clipMeshPolygon(clipped,{ hi-it.coordinate(axis) })
                        if (triangleArea3D(clipped)>1e-10) split+=clipped
                        require(split.size<=150000) { "Audience cell budget exceeded" }
                    }
                }
                pieces=split
            }
            for (piece in pieces) for (i in 1 until piece.lastIndex) {
                check()
                if ((piece[i]-piece[0]).cross(piece[i+1]-piece[0]).length()<1e-9) continue
                val a=index(piece[0],faceId); val b=index(piece[i],faceId); val c=index(piece[i+1],faceId)
                fun rounded(index: Int)=receivers[index].let { MeshPoint(it.x.toDouble(),it.surfaceY.toDouble(),it.z.toDouble()) }
                if (setOf(a,b,c).size<3 || (rounded(b)-rounded(a)).cross(rounded(c)-rounded(a)).length()<1e-10) {
                    require((piece[i]-piece[0]).cross(piece[i+1]-piece[0]).length()<1e-6) { "Coverage cells collapse at receiver precision; increase grid spacing" }
                    continue
                }
                require(faces.size<150000) { "Audience triangle budget exceeded" }
                faces+=SurfaceTriangle(a,b,c)
            }
        }
        // Do not turn vertices from discarded numerical slivers into independent probe markers.
        val used=faces.flatMap {listOf(it.a,it.b,it.c)}.toSet()
        val remap=IntArray(receivers.size) {-1}; val compact=mutableListOf<SurfaceReceiver>()
        receivers.forEachIndexed {i,r -> if (i in used) {remap[i]=compact.size; compact+=r} }
        return PreparedAudienceGeometry(compact,faces.map {SurfaceTriangle(remap[it.a],remap[it.b],remap[it.c])},
            mesh.coverageIdentities(surface.id,surface.name,surface.zoneType))
    }

    /** All discrete heights under a plan coordinate. Vertical facets make a scalar lookup ambiguous. */
    fun heights(surface: AudienceSurface,x: Float,z: Float,receiver: Boolean=false): List<Float>? {
        val source=points(surface); val p=PlanPoint(x.toDouble(),z.toDouble()); val heights=mutableListOf<Double>()
        for ((i,t) in surface.importedMesh!!.triangles().withIndex()) {
            val offset=surface.importedMesh.faceAssignment(i).earOffset(surface.zoneType) ?: continue
            val a=source[t.a]; val b=source[t.b]; val c=source[t.c]
            val area=PolygonGeometry.cross(a.plan(),b.plan(),c.plan())
            if (abs(area)<1e-10) {
                if (listOf(a to b,b to c,c to a).any { (u,v) -> PolygonGeometry.onSegment(u.plan(),v.plan(),p) }) return null
                continue
            }
            val wb=PolygonGeometry.cross(a.plan(),p,c.plan())/area
            val wc=PolygonGeometry.cross(a.plan(),b.plan(),p)/area
            if (wb>=-1e-7 && wc>=-1e-7 && wb+wc<=1+1e-7) heights+=a.y*(1-wb-wc)+b.y*wb+c.y*wc+if (receiver) offset else 0f
        }
        val unique=mutableListOf<Double>()
        for (height in heights.sorted()) if (unique.isEmpty() || abs(unique.last()-height)>1e-8) unique+=height
        return unique.map {it.toFloat()}
    }
    fun contains(mesh: AudienceMesh,x: Float,z: Float,pickTolerance: Double=0.0,includeFace: (Int)->Boolean={true}): Boolean {
        val nodes=mesh.vertices(); val p=PlanPoint(x.toDouble(),z.toDouble())
        return mesh.triangles().withIndex().any { (i,t) ->
            if (!includeFace(i)) return@any false
            val a=nodes[t.a].point().plan(); val b=nodes[t.b].point().plan(); val c=nodes[t.c].point().plan()
            val area=PolygonGeometry.cross(a,b,c)
            if (abs(area)>1e-10) {
                val u=PolygonGeometry.cross(a,p,c)/area; val v=PolygonGeometry.cross(a,b,p)/area
                u>=-1e-8 && v>=-1e-8 && u+v<=1+1e-8
            } else listOf(a to b,b to c,c to a).any { (u,v) ->
                val dx=v.x-u.x; val dz=v.z-u.z; val length=dx*dx+dz*dz
                val t0=if (length<1e-20) 0.0 else (((p.x-u.x)*dx+(p.z-u.z)*dz)/length).coerceIn(0.0,1.0)
                hypot(p.x-u.x-t0*dx,p.z-u.z-t0*dz)<=max(1e-8,pickTolerance)
            }
        }
    }
    /** Cross-section through every layer, bounded by the plan segment. Coplanar facets emit their edges. */
    fun trace(surface: AudienceSurface,a: PlanPoint,b: PlanPoint): List<AudienceLineSegment> {
        val dx=b.x-a.x; val dz=b.z-a.z; val length=dx*dx+dz*dz
        if (length<1e-20) return emptyList()
        val points=points(surface); val result=mutableListOf<AudienceLineSegment>(); val seen=mutableSetOf<String>()
        fun add(u: MeshPoint,v: MeshPoint) {
            fun parameter(p: MeshPoint)=((p.x-a.x)*dx+(p.z-a.z)*dz)/length
            val start=parameter(u); val end=parameter(v); val delta=end-start
            var lo=0.0; var hi=1.0
            if (abs(delta)<1e-12) { if (start< -1e-9 || start>1+1e-9) return }
            else { val t0=(0-start)/delta; val t1=(1-start)/delta; lo=max(lo,min(t0,t1)); hi=min(hi,max(t0,t1)) }
            if (hi<lo) return
            val p=u+(v-u)*lo; val q=u+(v-u)*hi
            if ((q-p).length()<1e-8) return
            val key=listOf(p.key().toString(),q.key().toString()).sorted().joinToString("/")
            if (seen.add(key)) result+=AudienceLineSegment(p.x.toFloat(),p.y.toFloat(),p.z.toFloat(),q.x.toFloat(),q.y.toFloat(),q.z.toFloat())
        }
        for (t in surface.importedMesh!!.triangles()) {
            val corners=listOf(points[t.a],points[t.b],points[t.c]); val d=corners.map { PolygonGeometry.cross(a,b,it.plan()) }
            if (d.all {abs(it)<1e-9}) { for (i in 0..2) add(corners[i],corners[(i+1)%3]); continue }
            val hits=mutableListOf<MeshPoint>()
            for (i in 0..2) {
                val j=(i+1)%3
                if (abs(d[i])<1e-9) hits+=corners[i]
                if (d[i]*d[j]<0) hits+=corners[i]+(corners[j]-corners[i])*(d[i]/(d[i]-d[j]))
            }
            val unique=hits.distinctBy {it.key()}
            if (unique.size==2) add(unique[0],unique[1])
        }
        return result
    }
}
