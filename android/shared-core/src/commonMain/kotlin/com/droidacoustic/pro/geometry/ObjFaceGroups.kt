package com.droidacoustic.pro.geometry

/** OBJ group membership is a set, independent of object/material/smoothing state.
 * Partition by the complete active set instead of duplicating triangles into overlapping groups.
 * Only sets that own faces are emitted. Source names are case-sensitive; native display names are not.
 */
internal class ObjFaceGroups {
    private data class Partition(val id: Int,val name: String,val faces: MutableList<Int>)
    private var active: List<String> = emptyList()
    private val partitions=linkedMapOf<List<String>,Partition>()
    private val usedLabels=mutableSetOf<String>()

    fun setNames(names: List<String>,line: Int,check: ()->Unit) {
        require(names.size<=AudienceMesh.MAX_GROUPS) { "OBJ line $line: too many simultaneous group names" }
        for (name in names) {
            check()
            require(name.isNotEmpty() && name.length<=80 && name.none {it.code<32 || it.code==127}) {
                "OBJ line $line: group names must contain 1–80 characters without control characters"
            }
        }
        active=names.distinct().sorted()
    }
    fun addFace(faceIndex: Int,line: Int,check: ()->Unit) {
        check()
        if (active.isEmpty()) return
        val partition=partitions[active] ?: run {
            require(partitions.size<AudienceMesh.MAX_GROUPS) { "OBJ line $line: more than ${AudienceMesh.MAX_GROUPS} face-group combinations" }
            val base=active.joinToString(" + ")
            require(base.length<=80) { "OBJ line $line: combined group name exceeds 80 characters; shorten the source group names" }
            var label=base; var suffix=2
            while (label.lowercase() in usedLabels) {
                check(); label="$base (${suffix++})"
                require(label.length<=80) { "OBJ line $line: group name needs a unique suffix but exceeds 80 characters; shorten it" }
            }
            usedLabels+=label.lowercase()
            Partition(partitions.size+1,label,mutableListOf()).also {partitions[active]=it}
        }
        partition.faces+=faceIndex
    }
    fun build(check: ()->Unit): List<MeshFaceGroup> = partitions.values.map {
        check(); MeshFaceGroup(it.id,it.name,it.faces)
    }
}
