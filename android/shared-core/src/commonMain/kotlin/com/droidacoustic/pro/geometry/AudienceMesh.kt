package com.droidacoustic.pro.geometry

import kotlin.math.*

/** Metres, +Y up. Imported y is added to the zone's base/rake plane. */
data class AudienceMeshVertex(val x: Double, val y: Float, val z: Double)

/** Validated immutable connected, orientable manifold 3D mesh. Original triangle edges are retained.
 * Boundary loops come from topology, never from a convex hull or a replacement triangulation.
 */
class AudienceMesh private constructor(private val nodes: List<AudienceMeshVertex>,
    private val faces: List<SurfaceTriangle>, private val rings: List<List<Int>>,
    private val assignments: List<MeshFaceAssignment> = List(faces.size) {MeshFaceAssignment.FOLLOW_ZONE},
    private val groups: List<MeshFaceGroup> = emptyList(),
    private val boundaries: List<AcousticBoundaryRole> = List(faces.size) {AcousticBoundaryRole.NONE},
    private val materials: List<BoundaryMaterial> = List(faces.size) {BoundaryMaterial.IDEAL_RIGID}) {
    fun boundaryMaterials()=materials.toList()
    fun boundaryMaterial(index: Int)=materials[index]
    fun withBoundaryMaterials(values: List<BoundaryMaterial>): AudienceMesh {
        require(values.size==faces.size)
        return if(values==materials) this else AudienceMesh(nodes,faces,rings,assignments,groups,boundaries,values.toList())
    }
    fun assignBoundaryMaterial(indices: Collection<Int>,material: BoundaryMaterial): AudienceMesh {
        require(indices.all {it in faces.indices})
        val selected=indices.toSet()
        return withBoundaryMaterials(materials.mapIndexed {i,old -> if(i in selected) material else old})
    }
    fun boundaryRoles(): List<AcousticBoundaryRole> = boundaries.toList()
    fun boundaryRole(faceIndex: Int): AcousticBoundaryRole = boundaries[faceIndex]
    fun withBoundaryRoles(values: List<AcousticBoundaryRole>): AudienceMesh {
        require(values.size==faces.size) { "Supply one boundary role per imported face" }
        return if (values==boundaries) this else AudienceMesh(nodes,faces,rings,assignments,groups,values.toList(),materials)
    }
    fun assignBoundaryRoles(indices: Collection<Int>,role: AcousticBoundaryRole): AudienceMesh {
        require(indices.size<=faces.size && indices.all {it in faces.indices}) { "Invalid imported face selection" }
        val selected=indices.toSet()
        return withBoundaryRoles(boundaries.mapIndexed {i,old -> if (i in selected) role else old})
    }
    fun assignGroupBoundaryRole(id: Int,role: AcousticBoundaryRole)=assignBoundaryRoles(faceGroup(id).faces(),role)
    private val groupByFace: List<MeshFaceGroup?> = MutableList<MeshFaceGroup?>(faces.size) {null}.apply {
        groups.forEach {g -> g.faces().forEach {this[it]=g} }
    }
    fun faceGroups(): List<MeshFaceGroup> = groups.toList()
    fun faceGroup(id: Int): MeshFaceGroup = groups.firstOrNull {it.id==id}
        ?: throw IllegalArgumentException("Unknown face group")
    fun groupForFace(faceIndex: Int): MeshFaceGroup? = groupByFace[faceIndex]
    fun withFaceGroups(values: List<MeshFaceGroup>): AudienceMesh {
        require(values.size<=MAX_GROUPS) { "Imported mesh supports at most $MAX_GROUPS named groups" }
        require(values.map {it.id}.distinct().size==values.size) { "Duplicate face group IDs" }
        require(values.map {it.name.lowercase()}.distinct().size==values.size) { "Group names must be unique within the mesh" }
        val used=mutableSetOf<Int>()
        values.forEach {g -> g.faces().forEach { require(it in faces.indices && used.add(it)) { "Group faces are out of range or overlap" } } }
        val ordered=values.sortedBy {it.id}
        return if (ordered==groups) this else AudienceMesh(nodes,faces,rings,assignments,ordered,boundaries,materials)
    }
    /** Creating or replacing membership moves selected faces out of their previous groups. Roles stay intact. */
    fun createFaceGroup(name: String,indices: Collection<Int>): AudienceMesh {
        val last=groups.maxOfOrNull {it.id} ?: 0
        require(last<Int.MAX_VALUE) { "Face group ID limit reached" }
        val group=MeshFaceGroup(last+1,name,indices)
        val selected=group.faces()
        return withFaceGroups(groups.map {MeshFaceGroup(it.id,it.name,it.faces()-selected)}+group)
    }
    fun renameFaceGroup(id: Int,name: String): AudienceMesh {
        val old=faceGroup(id)
        return withFaceGroups(groups.map {if (it.id==id) MeshFaceGroup(id,name,old.faces()) else it})
    }
    fun setFaceGroupMembers(id: Int,indices: Collection<Int>): AudienceMesh {
        val replacement=MeshFaceGroup(id,faceGroup(id).name,indices); val selected=replacement.faces()
        return withFaceGroups(groups.map {if (it.id==id) replacement else MeshFaceGroup(it.id,it.name,it.faces()-selected)})
    }
    fun addToFaceGroup(id: Int,indices: Collection<Int>): AudienceMesh {
        require(indices.size<=faces.size && indices.all {it in faces.indices}) { "Invalid imported face selection" }
        return setFaceGroupMembers(id,faceGroup(id).faces()+indices)
    }
    fun removeFaceGroup(id: Int): AudienceMesh {
        faceGroup(id)
        return withFaceGroups(groups.filter {it.id!=id})
    }
    fun ungroupFaces(indices: Collection<Int>): AudienceMesh {
        require(indices.size<=faces.size && indices.all {it in faces.indices}) { "Invalid imported face selection" }
        val selected=indices.toSet()
        return withFaceGroups(groups.map {MeshFaceGroup(it.id,it.name,it.faces()-selected)})
    }
    fun assignFaceGroup(id: Int,assignment: MeshFaceAssignment)=assignFaces(faceGroup(id).faces(),assignment)
    fun faceIdentity(faceIndex: Int,areaId: Int,areaName: String): AudienceSurfaceIdentity =
        groupByFace[faceIndex]?.identity(areaId,areaName) ?: assignments[faceIndex].let {
            AudienceSurfaceIdentity(it.surfaceKey(areaId),areaId,it.surfaceName(areaName))
        }
    internal fun coverageIdentities(areaId: Int,areaName: String,zoneType: String): List<AudienceSurfaceIdentity> {
        val ungrouped=faces.indices.filter {groupByFace[it]==null}.map {assignments[it]}.distinct()
            .filter {it.zoneType(zoneType)!=null}.map {AudienceSurfaceIdentity(it.surfaceKey(areaId),areaId,it.surfaceName(areaName))}
        return (ungrouped+groups.map {it.identity(areaId,areaName)})
            .ifEmpty {listOf(AudienceSurfaceIdentity("area:$areaId",areaId,areaName))}
    }
    fun vertices(): List<AudienceMeshVertex> = nodes.toList()
    fun triangles(): List<SurfaceTriangle> = faces.toList()
    fun faceAssignments(): List<MeshFaceAssignment> = assignments.toList()
    fun faceAssignment(index: Int): MeshFaceAssignment = assignments[index]
    fun withFaceAssignments(values: List<MeshFaceAssignment>): AudienceMesh {
        require(values.size==faces.size) { "Supply one assignment per imported face" }
        return if (values==assignments) this else AudienceMesh(nodes,faces,rings,values.toList(),groups,boundaries,materials)
    }
    fun assignFaces(indices: Collection<Int>,assignment: MeshFaceAssignment): AudienceMesh {
        require(indices.size<=faces.size && indices.all {it in faces.indices}) { "Invalid imported face selection" }
        val selected=indices.toSet()
        return withFaceAssignments(assignments.mapIndexed {i,value -> if (i in selected) assignment else value})
    }
    /** Connected coplanar patch around a seed, bounded against the seed plane to avoid gradual drift. */
    fun coplanarFaces(seed: Int,checkCancellation: ()->Unit = {}): Set<Int> {
        require(seed in faces.indices) { "Invalid imported face selection" }
        val t=faces[seed]; val origin=nodes[t.a].point()
        val normal=(nodes[t.b].point()-origin).cross(nodes[t.c].point()-origin)
        val length=normal.length()
        val edges=mutableMapOf<Pair<Int,Int>,MutableList<Int>>()
        faces.forEachIndexed {i,face -> checkCancellation(); meshEdges(face).forEach { (a,b) -> edges.getOrPut(meshEdge(a,b)) {mutableListOf()}.add(i) } }
        val result=linkedSetOf(seed); val queue=ArrayDeque<Int>(); queue.addLast(seed)
        while (queue.isNotEmpty()) {
            checkCancellation(); val current=queue.removeFirst()
            for ((a,b) in meshEdges(faces[current])) for (next in edges.getValue(meshEdge(a,b))) {
                if (next in result) continue
                val face=faces[next]
                if (listOf(face.a,face.b,face.c).all {kotlin.math.abs((nodes[it].point()-origin).dot(normal))/length<=1e-5}) {
                    result+=next; queue.addLast(next)
                }
            }
        }
        return result
    }
    /** Legacy editor coordinates/anchor only; a 3D mesh has no single outer plan polygon. */
    fun boundary(): List<PlanPoint> = if (hasHeightFieldBoundary) rings.first().map { PlanPoint(nodes[it].x,nodes[it].z) }
        else nodes.map { PlanPoint(it.x,it.z) }
    fun boundaryLoops(): List<List<Int>> = rings.map { it.toList() }
    private val hasHeightFieldBoundary = rings.isNotEmpty() && faces.all {
        PolygonGeometry.cross(nodes[it.a].point().plan(),nodes[it.b].point().plan(),nodes[it.c].point().plan())>1e-8
    }
    fun voids(): List<List<PlanPoint>> = (if (hasHeightFieldBoundary) rings.drop(1) else emptyList()).map { ring -> ring.map { PlanPoint(nodes[it].x,nodes[it].z) } }
    fun mapCoordinates(transform: (PlanPoint)->PlanPoint): AudienceMesh = create(nodes.map {
        val p=transform(PlanPoint(it.x,it.z)); it.copy(x=p.x,z=p.z)
    },faces).withFaceAssignments(assignments).withFaceGroups(groups).withBoundaryRoles(boundaries).withBoundaryMaterials(materials)
    override fun equals(other: Any?) = other is AudienceMesh && nodes==other.nodes && faces==other.faces && assignments==other.assignments && groups==other.groups && boundaries==other.boundaries && materials==other.materials
    override fun hashCode() = 31*(31*(31*(31*nodes.hashCode()+faces.hashCode())+assignments.hashCode())+groups.hashCode())+boundaries.hashCode()+31*materials.hashCode()

    companion object {
        const val MAX_GROUPS=128
        const val MAX_VERTICES=4096
        const val MAX_TRIANGLES=8192
        fun create(vertices: List<AudienceMeshVertex>, triangles: List<SurfaceTriangle>,
            checkCancellation: ()->Unit = {}): AudienceMesh {
            var work=0
            fun check() { checkCancellation(); require(++work<=4000000) { "Imported mesh validation exceeds work budget" } }
            check()
            require(vertices.size in 3..MAX_VERTICES && triangles.size in 1..MAX_TRIANGLES) { "Mesh needs 3–4096 vertices and 1–8192 triangles" }
            val nodes=vertices.map { it.copy(x=if (it.x==0.0) 0.0 else it.x,
                y=if (it.y==0f) 0f else it.y,z=if (it.z==0.0) 0.0 else it.z) }
            require(nodes.all { check(); it.x.isFinite() && it.z.isFinite() && it.y.isFinite() &&
                abs(it.x)<1e6 && abs(it.z)<1e6 && abs(it.y)<=10000 }) { "Invalid imported mesh coordinates" }
            val validated=MeshValidation3D.validate(nodes,triangles,::check)
            return AudienceMesh(nodes,validated.faces,validated.loops)
        }
    }
}

enum class AudienceMeshUpAxis { Y, Z }
data class AudienceMeshImportOptions(val metresPerUnit: Double=1.0,val upAxis: AudienceMeshUpAxis=AudienceMeshUpAxis.Y)

/** Triangular OBJ subset. Each active set of `g` names maps to one disjoint named face group.
 * Materials, textures, normals and object names do not change geometry or audience assignments.
 * No referenced files are read. Quads/polygons must be triangulated by the exporter.
 */
object AudienceMeshObj {
    const val MAX_CHARS=8*1024*1024
    fun parse(text: String,options: AudienceMeshImportOptions=AudienceMeshImportOptions(),checkCancellation: ()->Unit = {}): AudienceMesh {
        require(text.length<=MAX_CHARS) { "Audience OBJ exceeds 8 MiB text limit" }
        require(options.metresPerUnit.isFinite() && options.metresPerUnit>0) { "Invalid mesh units" }
        val vertices=mutableListOf<AudienceMeshVertex>(); val faces=mutableListOf<SurfaceTriangle>()
        val grouping=ObjFaceGroups()
        text.lineSequence().forEachIndexed { index,line ->
            checkCancellation(); val tokens=line.substringBefore('#').trim().split(Regex("\\s+"))
            fun fail(message: String): Nothing = throw IllegalArgumentException("OBJ line ${index+1}: $message")
            when (tokens.first()) {
                "v" -> {
                    if (tokens.size!=4) fail("expected x y z vertex coordinates")
                    val xyz=tokens.drop(1).map { (it.toDoubleOrNull() ?: fail("invalid coordinate"))*options.metresPerUnit }
                    val p=if (options.upAxis==AudienceMeshUpAxis.Y) AudienceMeshVertex(xyz[0],xyz[1].toFloat(),xyz[2])
                        else AudienceMeshVertex(xyz[0],xyz[2].toFloat(),-xyz[1])
                    vertices+=p; if (vertices.size>AudienceMesh.MAX_VERTICES) fail("too many vertices")
                }
                "f" -> {
                    if (tokens.size!=4) fail("export triangulated faces (three indices per face)")
                    val ids=tokens.drop(1).map { token ->
                        val parts=token.split('/')
                        if (parts.size>3) fail("invalid face reference")
                        val value=parts.first().toIntOrNull() ?: fail("invalid vertex index")
                        val id=if (value>0) value-1 else vertices.size+value
                        if (value==0 || id !in vertices.indices) fail("vertex index out of range")
                        id
                    }
                    faces+=SurfaceTriangle(ids[0],ids[1],ids[2]); if (faces.size>AudienceMesh.MAX_TRIANGLES) fail("too many triangles")
                    grouping.addFace(faces.lastIndex,index+1,checkCancellation)
                }
                "g" -> grouping.setNames(tokens.drop(1),index+1,checkCancellation)
                "", "vt", "vn", "o", "s", "usemtl", "mtllib" -> Unit
                else -> fail("unsupported geometry directive '${tokens.first()}'")
            }
        }
        return AudienceMesh.create(vertices,faces,checkCancellation).withFaceGroups(grouping.build(checkCancellation))
    }
}
