package com.droidacoustic.pro.traces

import com.droidacoustic.pro.alignment.AlignmentReferences
import com.droidacoustic.pro.alignment.SavedAlignmentReceiver
import com.droidacoustic.pro.prediction.PredictionOutput
import kotlinx.serialization.json.*

/** Immutable selection; IDs, not names, distinguish colocated/duplicate-named receivers. */
class ReferenceTraceSelection(ids: List<Int> = emptyList()) {
    private val selected=ids.toList()
    val ids: List<Int> get()=selected.toList()
    init {require(selected.size<=64 && selected.all {it>=0} && selected.distinct().size==selected.size)}
    fun reconcile(references: AlignmentReferences)=ReferenceTraceSelection(selected.filter {id->references.receivers.any {it.id==id}})
    fun receivers(references: AlignmentReferences): List<SavedAlignmentReceiver> = selected.map {id->
        references.receivers.firstOrNull {it.id==id} ?: error("Selected reference receiver no longer exists")
    }
    fun toJsonString()=buildJsonObject {put("version",1);put("ids",buildJsonArray {selected.forEach {add(it)}})}.toString()
    companion object {
        fun fromJsonString(text:String):ReferenceTraceSelection {
            val root=Json.parseToJsonElement(text).jsonObject
            require(root.getValue("version").jsonPrimitive.int==1)
            return ReferenceTraceSelection(root.getValue("ids").jsonArray.map {it.jsonPrimitive.int})
        }
    }
}

/** Frozen batch identity and receiver order shared by request construction and result capture. */
class ReferenceTraceCapture(val designName: String, receivers: List<SavedAlignmentReceiver>,private val metadata: TraceMetadata) {
    private val selected=receivers.toList()
    val receivers: List<SavedAlignmentReceiver> get()=selected.toList()
    init {require(designName.isNotBlank() && designName.length<=200 && selected.isNotEmpty() && selected.size<=64 && selected.map {it.id}.distinct().size==selected.size)}
    fun capture(library: SavedTraces, output: PredictionOutput): SavedTraces {
        require(output.receiverCount==selected.size) {"Reference result count differs from capture request"}
        val captures=selected.mapIndexed {i,r ->
            val powers=output.bandPowersAt(i)
            require(powers.isNotEmpty()) {"Reference result is missing band powers"}
            SavedTrace(0,"$designName / ${r.receiver.name} (#${r.id})",metadata.copy(receiver=r.receiver.position,
                referenceReceiverId=r.id,referenceReceiverName=r.receiver.name),powers,output.phasesAt(i),delayReferenceS=output.delayReferenceAt(i))
        }
        return library.captureBatch(captures)
    }
}
