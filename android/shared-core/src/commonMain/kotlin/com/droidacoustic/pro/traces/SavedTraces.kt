package com.droidacoustic.pro.traces

import com.droidacoustic.pro.geometry.BoundaryPoint
import com.droidacoustic.pro.acoustics.PhaseSample
import com.droidacoustic.pro.acoustics.PhaseResponse
import kotlinx.serialization.json.*
import okio.ByteString.Companion.encodeUtf8
import kotlin.math.log10

data class TraceMetadata(val receiver: BoundaryPoint, val bandwidthOct: Float, val temperatureC: Float,
    val humidityPct: Float, val inputHash: String, val capturedAtMillis: Long,
    val sourceNotes: String = "", val solver: String = "direct-field-v1", val quantity: String = "unweighted-band-power-fixed-total-drive",
    val referenceReceiverId: Int? = null, val referenceReceiverName: String? = null) {
    init {
        require(listOf(receiver.x,receiver.y,receiver.z).all {it.isFinite()})
        require(bandwidthOct.isFinite() && bandwidthOct>0 && temperatureC.isFinite() && humidityPct.isFinite())
        require(inputHash.matches(Regex("[a-f0-9]{64}")) && capturedAtMillis>=0)
        require(referenceReceiverId==null || referenceReceiverId>=0)
        require((referenceReceiverId==null)==(referenceReceiverName==null))
        require(referenceReceiverName==null || referenceReceiverName.isNotBlank())
        require(solver.isNotBlank() && quantity.isNotBlank())
    }
}
class SavedTrace(val id: Int, val name: String, val metadata: TraceMetadata, powers: Map<Int,Double?>, phases:Map<Int,PhaseSample> = emptyMap(), val phaseConvention:String=PhaseResponse.CONVENTION, val delayReferenceS:Double?=null, val manualDelayReferenceS:Double?=null) {
    val effectiveDelayReferenceS:Double get()=manualDelayReferenceS ?: delayReferenceS ?: 0.0
    fun withDelayReference(seconds:Double?)=SavedTrace(id,name,metadata,powers,phases,phaseConvention,delayReferenceS,seconds)
    private val phaseData=phases.toMap()
    val phases:Map<Int,PhaseSample> get()=phaseData.toMap()
    private val data=powers.toList().sortedBy {it.first}.toMap()
    val powers: Map<Int,Double?> get()=data.toMap()
    init {
        require(delayReferenceS==null || delayReferenceS.isFinite())
        require(manualDelayReferenceS==null || manualDelayReferenceS.isFinite() && kotlin.math.abs(manualDelayReferenceS)<=3600) {"Reference must be within ±3600000 ms"}
        require(phaseConvention.isNotBlank() && phaseData.size<=4096 && phaseData.keys.all {it>0})
        require(id>=0 && name.isNotBlank() && data.isNotEmpty() && data.size<=128)
        require(data.all {(hz,p)->hz>0 && (p==null || p.isFinite() && p>=0)})
    }
}
data class TraceBandComparison(val hz: Int,val baselinePower: Double?,val candidatePower: Double?) {
    val deltaDb: Double? get()=if(baselinePower!=null && candidatePower!=null && baselinePower>0 && candidatePower>0)
        10*(log10(candidatePower)-log10(baselinePower)) else null
}
data class TraceComparison(val compatible: Boolean,val reason: String?,val bands: List<TraceBandComparison>)
data class PhaseBandComparison(val hz:Int,val baseline:PhaseSample?,val candidate:PhaseSample?) {
    val deltaDegrees:Double? get()=if(baseline?.degrees!=null && candidate?.degrees!=null)
        PhaseResponse.wrapped(candidate.degrees-baseline.degrees) else null
}
data class PhaseTraceComparison(val compatible:Boolean,val reason:String?,val bands:List<PhaseBandComparison>)
object TraceComparisons {
    fun comparePhase(a:SavedTrace,b:SavedTrace,relativeToReference:Boolean=false):PhaseTraceComparison {
        val reason=when {
            a.metadata.receiver!=b.metadata.receiver -> "Receiver positions differ"
            a.metadata.solver!=b.metadata.solver || a.metadata.quantity!=b.metadata.quantity -> "Prediction conventions differ"
            a.phaseConvention!=b.phaseConvention || a.phaseConvention !in setOf(PhaseResponse.CONVENTION,PhaseResponse.SPECULAR_CONVENTION,PhaseResponse.LEGACY_CONVENTION) -> "Phase conventions differ or are unsupported"
            a.phases.isEmpty() || b.phases.isEmpty() -> "No complex phase data was captured"
            else -> null
        }
        val aPhases=a.phases;val bPhases=b.phases
        fun sample(t:SavedTrace,phases:Map<Int,PhaseSample>,hz:Int):PhaseSample? {
            val p=phases[hz] ?: return null
            return if(relativeToReference && p.degrees!=null) PhaseSample(PhaseResponse.wrapped(p.degrees-360.0*hz*t.effectiveDelayReferenceS)) else p
        }
        return PhaseTraceComparison(reason==null,reason,if(reason!=null) emptyList() else
            (aPhases.keys+bPhases.keys).sorted().map {PhaseBandComparison(it,sample(a,aPhases,it),sample(b,bPhases,it))})
    }
    fun compare(a: SavedTrace,b: SavedTrace): TraceComparison {
        val reason=when {
            a.metadata.receiver!=b.metadata.receiver -> "Receiver positions differ"
            a.metadata.bandwidthOct!=b.metadata.bandwidthOct -> "Analysis bandwidths differ"
            a.metadata.quantity!=b.metadata.quantity || a.metadata.solver!=b.metadata.solver -> "Prediction conventions differ"
            else -> null
        }
        return TraceComparison(reason==null,reason,if(reason!=null) emptyList() else
            (a.powers.keys+b.powers.keys).sorted().map {TraceBandComparison(it,a.powers[it],b.powers[it])})
    }
    fun fingerprint(inputs: String)=inputs.encodeUtf8().sha256().hex()
}
/** Saved results are historical snapshots, never substituted for current predictions. */
/** Inclusive range scoped to an ordered saved-trace comparison. */
data class MatchRangeExclusion(val baselineId:Int,val candidateId:Int,val minHz:Int,val maxHz:Int) {
    init {require(baselineId>=0 && candidateId>=0 && baselineId!=candidateId);MatchingFrequencyRange(minHz,maxHz)}
}

class SavedTraces private constructor(entries: List<SavedTrace>,private val nextId: Int, val relativeToReference:Boolean=false,val matchingFrequencyRange:MatchingFrequencyRange?=null,val inspectedSample:MatchSampleSelection?=null, exclusions:Set<MatchSampleSelection> = emptySet(), ranges:Set<MatchRangeExclusion> = emptySet(), private val exclusionUndo:List<ExclusionEdit> = emptyList(),private val exclusionRedo:List<ExclusionEdit> = emptyList(),val overlayChangedOnly:Boolean=false) {
    private val excludedRanges=ranges.toSet()
    val rangeExclusions:Set<MatchRangeExclusion> get()=excludedRanges.toSet()
    init {require(excludedRanges.size<=1024);excludedRanges.forEach {e ->
        require(entries.any {it.id==e.baselineId} && entries.any {it.id==e.candidateId}) {"Invalid excluded range trace"}
    }}
    fun isRangeExcluded(sample:MatchSampleSelection)=excludedRanges.any {it.baselineId==sample.baselineId && it.candidateId==sample.candidateId && sample.hz in it.minHz..it.maxHz}

    private val excluded=exclusions.toSet()
    val manualExclusions:Set<MatchSampleSelection> get()=excluded.toSet()
    fun excludedFrequencies(a:Int,b:Int):Set<Int> {
        val pair=data.filter {it.id==a || it.id==b}
        val frequencies=pair.flatMap {it.phases.keys+it.powers.keys}.toSet()
        return excluded.filter {it.baselineId==a && it.candidateId==b}.map {it.hz}.toSet()+
            frequencies.filter {hz ->excludedRanges.any {it.baselineId==a && it.candidateId==b && hz in it.minHz..it.maxHz}}
    }
    init {excluded.forEach {e ->
        val a=entries.firstOrNull {it.id==e.baselineId};val b=entries.firstOrNull {it.id==e.candidateId}
        require(a!=null && b!=null && e.hz in (a.phases.keys+b.phases.keys+a.powers.keys+b.powers.keys)) {"Invalid excluded sample"}
    }}
    private val data=entries.toList()
    val traces: List<SavedTrace> get()=data.toList()
    init {require(data.size<=64 && nextId>=0 && data.map {it.id}.distinct().size==data.size && data.all {it.id<nextId})}
    init {inspectedSample?.let {selection ->
        val a=data.firstOrNull {it.id==selection.baselineId};val b=data.firstOrNull {it.id==selection.candidateId}
        require(a!=null && b!=null && selection.hz in (a.phases.keys+b.phases.keys+a.powers.keys+b.powers.keys)) {"Invalid inspected sample"}
        require(matchingFrequencyRange==null || selection.hz in matchingFrequencyRange.minHz..matchingFrequencyRange.maxHz)
    }}
    fun capture(name: String,metadata: TraceMetadata,powers: Map<Int,Double?>,phases:Map<Int,PhaseSample> = emptyMap(),phaseConvention:String=PhaseResponse.CONVENTION,delayReferenceS:Double?=null): SavedTraces {
        require(data.size<64 && nextId<Int.MAX_VALUE)
        return SavedTraces(data+SavedTrace(nextId,name,metadata,powers,phases,phaseConvention,delayReferenceS),nextId+1,relativeToReference,matchingFrequencyRange,inspectedSample,excluded,excludedRanges,exclusionUndo,exclusionRedo,overlayChangedOnly)
    }
    /** Atomic batch: capacity and all entries are validated before returning a new library. */
    fun captureBatch(captures: List<SavedTrace>): SavedTraces {
        require(captures.isNotEmpty() && data.size+captures.size<=64 && nextId.toLong()+captures.size<=Int.MAX_VALUE) {"Not enough space: at most 64 saved traces are supported"}
        return SavedTraces(data+captures.mapIndexed {i,t->SavedTrace(nextId+i,t.name,t.metadata,t.powers,t.phases,t.phaseConvention,t.delayReferenceS,t.manualDelayReferenceS)},nextId+captures.size,relativeToReference,matchingFrequencyRange,inspectedSample,excluded,excludedRanges,exclusionUndo,exclusionRedo,overlayChangedOnly)
    }
    fun remove(id: Int): SavedTraces {require(data.any {it.id==id});return SavedTraces(data.filter {it.id!=id},nextId,relativeToReference,matchingFrequencyRange,inspectedSample?.takeUnless {it.baselineId==id || it.candidateId==id},excluded.filterNot {it.baselineId==id || it.candidateId==id}.toSet(),excludedRanges.filterNot {it.baselineId==id || it.candidateId==id}.toSet(),exclusionUndo.filterNot {it.a==id || it.b==id},exclusionRedo.filterNot {it.a==id || it.b==id},overlayChangedOnly)}
    fun withDelayReference(id:Int,seconds:Double?):SavedTraces {
        require(data.any {it.id==id}) {"Unknown trace"}
        return SavedTraces(data.map {if(it.id==id) it.withDelayReference(seconds) else it},nextId,relativeToReference,matchingFrequencyRange,inspectedSample,excluded,excludedRanges,exclusionUndo,exclusionRedo,overlayChangedOnly)
    }
    fun withRelativeDisplay(relative:Boolean)=SavedTraces(data,nextId,relative,matchingFrequencyRange,inspectedSample,excluded,excludedRanges,exclusionUndo,exclusionRedo,overlayChangedOnly)
    fun withMatchingFrequencyRange(range:MatchingFrequencyRange?)=SavedTraces(data,nextId,relativeToReference,range,inspectedSample?.takeIf {range==null || it.hz in range.minHz..range.maxHz},excluded,excludedRanges,exclusionUndo,exclusionRedo,overlayChangedOnly)
    fun withInspectedSample(selection:MatchSampleSelection?)=SavedTraces(data,nextId,relativeToReference,matchingFrequencyRange,selection,excluded,excludedRanges,exclusionUndo,exclusionRedo,overlayChangedOnly)
    private fun state(a:Int,b:Int)=ExclusionState(excluded.filter {it.baselineId==a && it.candidateId==b}.toSet(),excludedRanges.filter {it.baselineId==a && it.candidateId==b}.toSet())
    private fun restore(a:Int,b:Int,target:ExclusionState,undo:List<ExclusionEdit>,redo:List<ExclusionEdit>)=SavedTraces(data,nextId,relativeToReference,matchingFrequencyRange,inspectedSample,
        excluded.filterNot {it.baselineId==a && it.candidateId==b}.toSet()+target.samples,
        excludedRanges.filterNot {it.baselineId==a && it.candidateId==b}.toSet()+target.ranges,undo,redo,overlayChangedOnly)
    private fun record(a:Int,b:Int,target:ExclusionState):SavedTraces {
        val before=state(a,b)
        if(before==target) return this
        return restore(a,b,target,(exclusionUndo+ExclusionEdit(a,b,before,target)).takeLast(64),exclusionRedo.filterNot {it.a==a && it.b==b})
    }
    fun exclusionHistory(a:Int,b:Int):ExclusionHistoryView {
        require(a!=b && data.any {it.id==a} && data.any {it.id==b}) {"Choose two saved traces"}
        return ExclusionHistoryView(a,b,exclusionUndo.filter {it.a==a && it.b==b},exclusionRedo.filter {it.a==a && it.b==b},state(a,b))
    }
    /** Return one immutable result; stale or invalid targets never partially apply. */
    fun navigateExclusionHistory(view:ExclusionHistoryView,position:Int):SavedTraces {
        val current=exclusionHistory(view.baselineId,view.candidateId)
        require(current.undo==view.undo && current.redo==view.redo && current.current==view.current) {"Exclusion history changed; select again"}
        require(position in view.entries.indices) {"Invalid history position"}
        var updated=this
        if(position<view.currentPosition) repeat(view.currentPosition-position) {updated=updated.undoExclusion(view.baselineId,view.candidateId)}
        else repeat(position-view.currentPosition) {updated=updated.redoExclusion(view.baselineId,view.candidateId)}
        return updated
    }
    fun undoExclusionDescription(a:Int,b:Int):String?=exclusionUndo.lastOrNull {it.a==a && it.b==b}?.description()
    fun redoExclusionDescription(a:Int,b:Int):String?=exclusionRedo.lastOrNull {it.a==a && it.b==b}?.description()
    fun canUndoExclusion(a:Int,b:Int)=exclusionUndo.any {it.a==a && it.b==b}
    fun canRedoExclusion(a:Int,b:Int)=exclusionRedo.any {it.a==a && it.b==b}
    fun undoExclusion(a:Int,b:Int):SavedTraces {
        val index=exclusionUndo.indexOfLast {it.a==a && it.b==b};if(index<0) return this
        val edit=exclusionUndo[index];require(state(a,b)==edit.after)
        return restore(a,b,edit.before,exclusionUndo.filterIndexed {i,_->i!=index},(exclusionRedo+edit).takeLast(64))
    }
    fun redoExclusion(a:Int,b:Int):SavedTraces {
        val index=exclusionRedo.indexOfLast {it.a==a && it.b==b};if(index<0) return this
        val edit=exclusionRedo[index];require(state(a,b)==edit.before)
        return restore(a,b,edit.after,(exclusionUndo+edit).takeLast(64),exclusionRedo.filterIndexed {i,_->i!=index})
    }
    init {
        require(exclusionUndo.size<=64 && exclusionRedo.size<=64)
        (exclusionUndo+exclusionRedo).forEach {edit ->listOf(edit.before,edit.after).forEach {s ->
            SavedTraces(entries,nextId,exclusions=s.samples,ranges=s.ranges)
            require(entries.any {it.id==edit.a} && entries.any {it.id==edit.b})
        }}
        fun checkChain(list:List<ExclusionEdit>,undo:Boolean) {
            val states=mutableMapOf<Pair<Int,Int>,ExclusionState>()
            list.asReversed().forEach {e ->val key=e.a to e.b
                val current=states[key] ?: state(e.a,e.b)
                require(current==if(undo) e.after else e.before) {"Invalid exclusion history"}
                states[key]=if(undo) e.before else e.after
            }
        }
        checkChain(exclusionUndo,true);checkChain(exclusionRedo,false)
    }
    fun withOverlayChangedOnly(changedOnly:Boolean)=SavedTraces(data,nextId,relativeToReference,matchingFrequencyRange,inspectedSample,excluded,excludedRanges,exclusionUndo,exclusionRedo,changedOnly)
    fun withSampleExcluded(sample:MatchSampleSelection,exclude:Boolean):SavedTraces {
        val current=state(sample.baselineId,sample.candidateId)
        return record(sample.baselineId,sample.candidateId,current.copy(samples=if(exclude) current.samples+sample else current.samples-sample))
    }
    fun replaceRangeExclusion(original:MatchRangeExclusion,replacement:MatchRangeExclusion):SavedTraces {
        require(original in excludedRanges) {"Exclusion changed; select it again"}
        require(original.baselineId==replacement.baselineId && original.candidateId==replacement.candidateId)
        val current=state(original.baselineId,original.candidateId)
        return record(original.baselineId,original.candidateId,current.copy(ranges=(current.ranges-original)+replacement))
    }
    fun withRangeExcluded(range:MatchRangeExclusion,exclude:Boolean):SavedTraces {
        val current=state(range.baselineId,range.candidateId)
        return record(range.baselineId,range.candidateId,current.copy(ranges=if(exclude) current.ranges+range else current.ranges-range))
    }
    fun toJsonString(): String=buildJsonObject {
        put("overlayChangedOnly",overlayChangedOnly)
        put("exclusionUndo",historyJson(exclusionUndo));put("exclusionRedo",historyJson(exclusionRedo))
        put("version",1);put("nextId",nextId);put("relativeToReference",relativeToReference)
        matchingFrequencyRange?.let {range ->put("matchingFrequencyRange",buildJsonObject {put("minHz",range.minHz);put("maxHz",range.maxHz)})}
        inspectedSample?.let {selection ->put("inspectedSample",buildJsonObject {
            put("baselineId",selection.baselineId);put("candidateId",selection.candidateId);put("hz",selection.hz)
        })}
        put("rangeExclusions",buildJsonArray {excludedRanges.sortedWith(compareBy<MatchRangeExclusion> {it.baselineId}.thenBy {it.candidateId}.thenBy {it.minHz}.thenBy {it.maxHz}).forEach {e ->add(buildJsonObject {
            put("baselineId",e.baselineId);put("candidateId",e.candidateId);put("minHz",e.minHz);put("maxHz",e.maxHz)
        })}})
        put("manualExclusions",buildJsonArray {excluded.sortedWith(compareBy<MatchSampleSelection> {it.baselineId}.thenBy {it.candidateId}.thenBy {it.hz}).forEach {e ->add(buildJsonObject {
            put("baselineId",e.baselineId);put("candidateId",e.candidateId);put("hz",e.hz)
        })}})
        put("traces",buildJsonArray {data.forEach {t ->add(buildJsonObject {
            put("id",t.id);put("name",t.name)
            t.delayReferenceS?.let {put("delayReferenceS",it)}
            t.manualDelayReferenceS?.let {put("manualDelayReferenceS",it)}
            val m=t.metadata
            put("x",m.receiver.x);put("y",m.receiver.y);put("z",m.receiver.z)
            put("widthOct",m.bandwidthOct);put("temperatureC",m.temperatureC);put("humidityPct",m.humidityPct)
            put("inputHash",m.inputHash);put("capturedAtMillis",m.capturedAtMillis);put("sourceNotes",m.sourceNotes);put("solver",m.solver);put("quantity",m.quantity)
            m.referenceReceiverId?.let {put("referenceReceiverId",it);put("referenceReceiverName",m.referenceReceiverName)}
            if(t.phases.isNotEmpty()) {
                put("phaseConvention",t.phaseConvention)
                put("phases",buildJsonObject {t.phases.toList().sortedBy {it.first}.forEach {(hz,p)->put(hz.toString(),buildJsonObject {
                    put("degrees",p.degrees?.let(::JsonPrimitive) ?: JsonNull)
                    p.reason?.let {put("reason",it)}
                })}})
            }
            put("powers",buildJsonObject {t.powers.forEach {(hz,p)->put(hz.toString(),p?.let(::JsonPrimitive) ?: JsonNull)}})
        })}})
    }.toString()
    companion object {
        fun empty()=SavedTraces(emptyList(),0)
        fun fromJsonString(text: String): SavedTraces {
            val r=Json.parseToJsonElement(text).jsonObject
            require(r.getValue("version").jsonPrimitive.int==1)
            val rows=r.getValue("traces").jsonArray;require(rows.size<=64)
            val entries=rows.map {item ->val o=item.jsonObject
                fun number(key:String)=o.getValue(key).jsonPrimitive.double
                fun string(key:String)=o.getValue(key).jsonPrimitive.content
                val powers=o.getValue("powers").jsonObject;require(powers.size<=128)
                require(powers.keys.all {it.toInt().toString()==it})
                require((o["phases"]?.jsonObject?.size ?: 0)<=4096)
                val phases=o["phases"]?.jsonObject?.map { (key,value) ->
                    require(key.toInt()>0 && key.toInt().toString()==key)
                    val p=value.jsonObject
                    key.toInt() to PhaseSample(p.getValue("degrees").takeIf {it!=JsonNull}?.jsonPrimitive?.double,p["reason"]?.jsonPrimitive?.content)
                }?.toMap().orEmpty()
                SavedTrace(o.getValue("id").jsonPrimitive.int,string("name"),TraceMetadata(
                    BoundaryPoint(number("x"),number("y"),number("z")),number("widthOct").toFloat(),number("temperatureC").toFloat(),number("humidityPct").toFloat(),
                    string("inputHash"),o.getValue("capturedAtMillis").jsonPrimitive.long,o["sourceNotes"]?.jsonPrimitive?.content.orEmpty(),string("solver"),string("quantity"),
                    o["referenceReceiverId"]?.jsonPrimitive?.int,o["referenceReceiverName"]?.jsonPrimitive?.content),
                    powers.mapKeys {it.key.toInt()}.mapValues {(_,v)->if(v==JsonNull) null else v.jsonPrimitive.double},phases,
                    o["phaseConvention"]?.jsonPrimitive?.content ?: if(phases.isEmpty()) PhaseResponse.CONVENTION else error("Missing phase convention"),
                    o["delayReferenceS"]?.jsonPrimitive?.double,o["manualDelayReferenceS"]?.jsonPrimitive?.double)
            }
            return SavedTraces(entries,r.getValue("nextId").jsonPrimitive.int,r["relativeToReference"]?.jsonPrimitive?.boolean ?: false,
                r["matchingFrequencyRange"]?.jsonObject?.let {MatchingFrequencyRange(it.getValue("minHz").jsonPrimitive.int,it.getValue("maxHz").jsonPrimitive.int)},
                r["inspectedSample"]?.jsonObject?.let {MatchSampleSelection(it.getValue("baselineId").jsonPrimitive.int,it.getValue("candidateId").jsonPrimitive.int,it.getValue("hz").jsonPrimitive.int)},
                r["manualExclusions"]?.jsonArray?.map {item ->val e=item.jsonObject
                    MatchSampleSelection(e.getValue("baselineId").jsonPrimitive.int,e.getValue("candidateId").jsonPrimitive.int,e.getValue("hz").jsonPrimitive.int)
                }?.toSet().orEmpty(),
                r["rangeExclusions"]?.jsonArray?.also {require(it.size<=1024)}?.map {item ->val e=item.jsonObject
                    MatchRangeExclusion(e.getValue("baselineId").jsonPrimitive.int,e.getValue("candidateId").jsonPrimitive.int,e.getValue("minHz").jsonPrimitive.int,e.getValue("maxHz").jsonPrimitive.int)
                 }?.toSet().orEmpty(),parseHistory(r["exclusionUndo"]),parseHistory(r["exclusionRedo"]),r["overlayChangedOnly"]?.jsonPrimitive?.boolean ?: false)
        }
    }
}
