package com.droidacoustic.pro.alignment

import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlinx.serialization.json.*

data class SavedAlignmentReceiver(val id: Int, val receiver: AlignmentReceiver, val referenceSourceId: Int?)
data class ReceiverAlignmentComparison(val receiver: SavedAlignmentReceiver, val arrivals: List<ArrivalComparison>)

/** Immutable project state. Stable IDs distinguish receivers even when names or positions match. */
class AlignmentReferences private constructor(
    entries: List<SavedAlignmentReceiver>, val selectedId: Int?, private val nextId: Int
) {
    private val saved=entries.toList()
    val receivers: List<SavedAlignmentReceiver> get()=saved.toList()
    val selected: SavedAlignmentReceiver? get()=saved.firstOrNull {it.id==selectedId}
    init {
        require(saved.size<=64 && saved.map {it.id}.distinct().size==saved.size)
        require(saved.all {it.id>=0 && it.id<nextId})
        require(nextId>=0 && (selectedId==null || saved.any {it.id==selectedId}))
        require(saved.isEmpty()==(selectedId==null))
    }
    fun add(receiver: AlignmentReceiver, sourceId: Int?): AlignmentReferences {
        require(saved.size<64 && nextId<Int.MAX_VALUE) {"At most 64 alignment receivers are supported"}
        return AlignmentReferences(saved+SavedAlignmentReceiver(nextId,receiver,sourceId),nextId,nextId+1)
    }
    fun select(id: Int): AlignmentReferences {
        require(saved.any {it.id==id}) {"Unknown alignment receiver"}
        return AlignmentReferences(saved,id,nextId)
    }
    fun update(id: Int, receiver: AlignmentReceiver, sourceId: Int?): AlignmentReferences {
        require(saved.any {it.id==id})
        return AlignmentReferences(saved.map {if(it.id==id) it.copy(receiver=receiver,referenceSourceId=sourceId) else it},selectedId,nextId)
    }
    fun remove(id: Int): AlignmentReferences {
        require(saved.any {it.id==id})
        val remaining=saved.filter {it.id!=id}
        return AlignmentReferences(remaining,if(selectedId==id) remaining.firstOrNull()?.id else selectedId,nextId)
    }
    /** Missing speaker references yield no proposal, never a silently substituted speaker. */
    fun compare(sources: List<AlignmentSource>, speedOfSound: Double, delayOverridesMs: Map<Int,Double> = emptyMap()): List<ReceiverAlignmentComparison> = saved.map {r ->
        val sourceId=r.referenceSourceId
        ReceiverAlignmentComparison(r,if(sourceId==null || sources.none {it.id==sourceId}) emptyList()
            else SystemAlignment.compare(r.receiver,sources,sourceId,speedOfSound,delayOverridesMs=delayOverridesMs))
    }
    /** Pure before/after preview, rounded to the Float DSP value the Android editor stores. */
    fun previewDelay(sources: List<AlignmentSource>, speedOfSound: Double, sourceId: Int): AlignmentDelayPreview? {
        val before=compare(sources,speedOfSound)
        val selected=selected ?: return null
        if(selected.referenceSourceId==sourceId) return null
        val proposed=before.firstOrNull {it.receiver.id==selected.id}?.arrivals
            ?.firstOrNull {it.sourceId==sourceId}?.proposedDelayMs ?: return null
        val old=sources.firstOrNull {it.id==sourceId}?.delayMs ?: return null
        val applied=proposed.toFloat().toDouble()
        if(kotlin.math.abs(applied-old)<1e-6) return null
        return AlignmentDelayPreview(selected.id,sourceId,old,applied,before,compare(sources,speedOfSound,mapOf(sourceId to applied)))
    }
    fun toJsonString(): String = buildJsonObject {
        put("version",1);put("nextId",nextId);put("selectedId",selectedId?.let(::JsonPrimitive) ?: JsonNull)
        put("receivers",buildJsonArray {saved.forEach {r -> add(buildJsonObject {
            put("id",r.id);put("name",r.receiver.name)
            put("x",r.receiver.position.x);put("y",r.receiver.position.y);put("z",r.receiver.position.z)
            put("sourceId",r.referenceSourceId?.let(::JsonPrimitive) ?: JsonNull)
        })}})
    }.toString()
    companion object {
        fun empty()=AlignmentReferences(emptyList(),null,0)
        fun fromJsonString(text: String): AlignmentReferences {
            val root=Json.parseToJsonElement(text).jsonObject
            require(root.getValue("version").jsonPrimitive.int==1) {"Unsupported alignment reference version"}
            val rows=root.getValue("receivers").jsonArray
            require(rows.size<=64)
            val entries=rows.map {item -> val r=item.jsonObject
                SavedAlignmentReceiver(r.getValue("id").jsonPrimitive.int,
                    AlignmentReceiver(r.getValue("name").jsonPrimitive.content,BoundaryPoint(
                        r.getValue("x").jsonPrimitive.double,r.getValue("y").jsonPrimitive.double,r.getValue("z").jsonPrimitive.double)),
                    r["sourceId"]?.takeIf {it!=JsonNull}?.jsonPrimitive?.int)
            }
            return AlignmentReferences(entries,root["selectedId"]?.takeIf {it!=JsonNull}?.jsonPrimitive?.int,
                root.getValue("nextId").jsonPrimitive.int)
        }
    }
}

/** Derived preview only; never persisted as a calculation result or project input. */
data class AlignmentDelayPreview(
    val selectedReceiverId: Int, val sourceId: Int, val previousDelayMs: Double, val proposedDelayMs: Double,
    val before: List<ReceiverAlignmentComparison>, val after: List<ReceiverAlignmentComparison>
)
