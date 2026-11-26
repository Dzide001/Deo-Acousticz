package com.droidacoustic.pro.persistence

import com.droidacoustic.pro.alignment.AlignmentReferences
import kotlinx.serialization.json.*
import okio.ByteString.Companion.encodeUtf8

data class DesignVariant(val id: Int,val name: String,val sceneJson: String,val fingerprint: String)

/** Immutable acoustic-design snapshots. The project-level variant and trace libraries are
 * deliberately outside snapshots, preventing recursion and loss of comparison history. */
class DesignVariants private constructor(entries: List<DesignVariant>,private val nextId: Int) {
    private val saved=entries.toList()
    val variants: List<DesignVariant> get()=saved.toList()
    init {
        require(saved.size<=16 && nextId>=0 && saved.all {it.id>=0 && it.id<nextId})
        require(saved.map {it.id}.distinct().size==saved.size)
        require(saved.sumOf {it.sceneJson.length.toLong()}<=MAX_LIBRARY_CHARS)
    }
    fun capture(name: String,sceneJson: String,resolve: (String)->String? = {null}): DesignVariants {
        require(name.isNotBlank() && name.length<=200)
        require(saved.size<16 && nextId<Int.MAX_VALUE) {"At most 16 design variants are supported"}
        val stripped=ProjectArchive.validateScene(sceneJson).toMutableMap()
        stripped.remove("designVariants");stripped.remove("savedTraces");stripped.remove("coverageComparison")
        val opened=ProjectArchive.open(JsonObject(stripped).toString(),resolve)
        // Exact available asset revisions travel with the snapshot, even after replacement
        // by another design that uses the same speaker model ID.
        val payloads=opened.assets.values.associate {it.reference.hash!! to it.payload}
        val portable=ProjectArchive.encode(opened.sceneJson,opened.references,payloads::get,true)
        validateSnapshot(portable)
        val variant=DesignVariant(nextId,name,portable,hash(portable))
        return DesignVariants(saved+variant,nextId+1).also {require(it.toJsonString().length<=MAX_LIBRARY_CHARS) {"Design library exceeds 64 MiB"}}
    }
    fun remove(id: Int): DesignVariants {
        require(saved.any {it.id==id});return DesignVariants(saved.filter {it.id!=id},nextId)
    }
    fun rename(id: Int,name: String): DesignVariants {
        require(name.isNotBlank() && name.length<=200 && saved.any {it.id==id})
        return DesignVariants(saved.map {if(it.id==id) it.copy(name=name) else it},nextId)
    }
    /** Prepares a complete project for the normal atomic import path; does not mutate live state.
     * Preserve both libraries from the current project, not the snapshot's capture date. */
    fun prepareRestore(id: Int,currentSceneJson: String,resolve: (String)->String? = {null}): OpenedProject {
        val variant=saved.firstOrNull {it.id==id} ?: error("Unknown design variant")
        val root=ProjectArchive.validateScene(variant.sceneJson).toMutableMap()
        val current=ProjectArchive.validateScene(currentSceneJson)
        root["designVariants"]=Json.parseToJsonElement(toJsonString())
        current["savedTraces"]?.let {root["savedTraces"]=it}
        root.remove("coverageComparison")
        current["coverageComparison"]?.let {root["coverageComparison"]=it}
        return ProjectArchive.open(JsonObject(root).toString(),resolve)
    }
    fun toJsonString(): String=buildJsonObject {
        put("version",1);put("nextId",nextId)
        put("variants",buildJsonArray {saved.forEach {v ->add(buildJsonObject {
            put("id",v.id);put("name",v.name);put("sceneJson",v.sceneJson);put("sha256",v.fingerprint)
        })}})
    }.toString()
    companion object {
        private const val MAX_LIBRARY_CHARS=64*1024*1024
        fun empty()=DesignVariants(emptyList(),0)
        private fun hash(text:String)=text.encodeUtf8().sha256().hex()
        private fun validateSnapshot(text:String) {
            val root=ProjectArchive.validateScene(text)
            require("designVariants" !in root && "savedTraces" !in root) {"Nested design libraries are not supported"}
            root["groundReflectionPhase"]?.let {com.droidacoustic.pro.geometry.ReflectionPhase.fromJson(it)}
            root["alignmentReferences"]?.let {AlignmentReferences.fromJsonString(it.toString())}
            root["referenceTraceSelection"]?.let {com.droidacoustic.pro.traces.ReferenceTraceSelection.fromJsonString(it.toString())}
        }
        fun fromJsonString(text:String): DesignVariants {
            require(text.length<=MAX_LIBRARY_CHARS) {"Design library exceeds 64 MiB"}
            val root=Json.parseToJsonElement(text).jsonObject
            require(root.getValue("version").jsonPrimitive.int==1) {"Unsupported design library version"}
            val rows=root.getValue("variants").jsonArray;require(rows.size<=16)
            val entries=rows.map {item ->val v=item.jsonObject
                val scene=v.getValue("sceneJson").jsonPrimitive.content
                val digest=v.getValue("sha256").jsonPrimitive.content
                require(hash(scene)==digest) {"Design snapshot checksum mismatch"};validateSnapshot(scene)
                val name=v.getValue("name").jsonPrimitive.content;require(name.isNotBlank() && name.length<=200)
                DesignVariant(v.getValue("id").jsonPrimitive.int,name,scene,digest)
            }
            return DesignVariants(entries,root.getValue("nextId").jsonPrimitive.int)
        }
    }
}
