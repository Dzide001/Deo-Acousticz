package com.droidacoustic.pro.geometry

/** A named, immutable set of original triangle indices. Membership is disjoint within a mesh. */
class MeshFaceGroup(val id: Int,name: String,faceIndices: Collection<Int>) {
    val name: String = name.trim()
    private val members = faceIndices.sorted()
    init {
        require(id>0) { "Invalid face group ID" }
        require(this.name.isNotEmpty() && this.name.length<=80 && this.name.none {it.code<32 || it.code==127}) {
            "Group name must contain 1–80 characters without control characters"
        }
        require(members.size<=AudienceMesh.MAX_TRIANGLES && members.all {it>=0} && members.distinct().size==members.size) {
            "Invalid or duplicate group face indices"
        }
    }
    fun faces(): Set<Int> = members.toSet()
    fun identity(areaId: Int,areaName: String)=AudienceSurfaceIdentity("area:$areaId:group:$id",areaId,"$areaName — $name")
    override fun equals(other: Any?) = other is MeshFaceGroup && id==other.id && name==other.name && members==other.members
    override fun hashCode() = 31*(31*id+name.hashCode())+members.hashCode()
}
