package com.droidacoustic.pro.geometry

import kotlinx.serialization.json.*

/** Portable embedded geometry; reopening never depends on the original OBJ file or URI. */
object AudienceMeshJson {
    fun encode(mesh: AudienceMesh): String = buildJsonObject {
        put("version",10)
        put("boundaryMaterials",JsonArray(mesh.boundaryMaterials().map {it.toJson()}))
        put("boundaryRoles",JsonArray(mesh.boundaryRoles().map {JsonPrimitive(it.name)}))
        put("faceGroups",JsonArray(mesh.faceGroups().map {g -> buildJsonObject {
            put("id",g.id); put("name",g.name); put("faces",JsonArray(g.faces().map {JsonPrimitive(it)}))
        } }))
        put("faceAssignments",JsonArray(mesh.faceAssignments().map {JsonPrimitive(it.name)}))
        put("vertices",JsonArray(mesh.vertices().map { p -> buildJsonObject { put("x",p.x); put("y",p.y); put("z",p.z) } }))
        put("triangles",JsonArray(mesh.triangles().map { t -> buildJsonObject { put("a",t.a); put("b",t.b); put("c",t.c) } }))
    }.toString()
    fun decode(text: String,checkCancellation: ()->Unit = {}): AudienceMesh {
        require(text.length<=AudienceMeshObj.MAX_CHARS) { "Embedded audience mesh exceeds size limit" }
        val root=Json.parseToJsonElement(text).jsonObject
        fun number(obj: JsonObject,key: String): JsonPrimitive {
            val n=obj.getValue(key).jsonPrimitive
            require(!n.isString && n.doubleOrNull?.isFinite()==true) { "Invalid mesh $key" }; return n
        }
        val version=number(root,"version").int
        require(version in 1..10) { "Unsupported audience mesh version" }
        val vertices=root.getValue("vertices").jsonArray
        val triangles=root.getValue("triangles").jsonArray
        require(vertices.size<=AudienceMesh.MAX_VERTICES && triangles.size<=AudienceMesh.MAX_TRIANGLES) { "Embedded mesh exceeds geometry limits" }
        var mesh=AudienceMesh.create(vertices.map { checkCancellation(); val p=it.jsonObject
            AudienceMeshVertex(number(p,"x").double,number(p,"y").float,number(p,"z").double) },
            triangles.map { checkCancellation(); val t=it.jsonObject
                SurfaceTriangle(number(t,"a").int,number(t,"b").int,number(t,"c").int) },checkCancellation)
        val assignments=root["faceAssignments"]
        if (version>=3) require(assignments is JsonArray) { "Missing imported face assignments" }
        if (assignments!=null) {
            require(assignments is JsonArray && assignments.size==triangles.size) { "Invalid face assignment count" }
            mesh=mesh.withFaceAssignments(assignments.map {
                checkCancellation(); val value=it.jsonPrimitive
                require(value.isString) { "Invalid face assignment" }
                MeshFaceAssignment.valueOf(value.content)
            })
        }
        val groups=root["faceGroups"]
        if (version>=4) require(groups is JsonArray) { "Missing imported face groups" }
        if (groups!=null) {
            require(groups is JsonArray && groups.size<=AudienceMesh.MAX_GROUPS) { "Invalid face groups" }
            mesh=mesh.withFaceGroups(groups.map {value ->
                checkCancellation(); val g=value.jsonObject; val name=g.getValue("name").jsonPrimitive
                require(name.isString) { "Invalid face group name" }
                val indices=g.getValue("faces").jsonArray
                require(indices.size<=triangles.size) { "Invalid group face count" }
                MeshFaceGroup(number(g,"id").int,name.content,indices.map {
                    checkCancellation(); val n=it.jsonPrimitive
                    require(!n.isString && n.intOrNull!=null) { "Invalid group face index" }; n.int
                })
            })
        }
        val boundaries=root["boundaryRoles"]
        if (version>=5) require(boundaries is JsonArray) { "Missing imported boundary roles" }
        if (boundaries!=null) {
            require(boundaries is JsonArray && boundaries.size==triangles.size) { "Invalid boundary role count" }
            mesh=mesh.withBoundaryRoles(boundaries.map {
                checkCancellation(); val value=it.jsonPrimitive
                require(value.isString) { "Invalid boundary role" }
                AcousticBoundaryRole.valueOf(value.content)
            })
        }
        val materials=root["boundaryMaterials"]
        if(version>=6) require(materials is JsonArray) {"Missing boundary materials"}
        if(materials!=null) {
            require(materials is JsonArray && materials.size==triangles.size)
            mesh=mesh.withBoundaryMaterials(materials.map {BoundaryMaterial.fromJson(it)})
        }
        return mesh
    }
}
