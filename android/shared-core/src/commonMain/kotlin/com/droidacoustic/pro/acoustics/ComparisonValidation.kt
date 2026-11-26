package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.prediction.PredictionModelScope
import kotlinx.serialization.json.*
import kotlin.math.*

/** Review diagnostics never relax the strict matching/qualification result. */
internal object ComparisonValidation {
    private val labels=linkedMapOf("modelRevision" to "Speaker data and calibration", "preset" to "DSP and source summation",
        "geometry" to "Source geometry and boundaries", "frequencyConvention" to "Frequency cells and level integration",
        "levelConvention" to "Level metric", "atmosphere" to "Atmosphere", "ground" to "Ground model",
        "driveUnit" to "Electrical drive unit", "driveValue" to "Electrical drive value", "normalization" to "Normalization receiver and safety floor",
        "broadband" to "Bands, weighting and excitation")
    private fun fields(c: SpeakerComparisonConditions) = linkedMapOf(
        "modelRevision" to c.modelRevision,"preset" to c.preset,"geometry" to c.geometry,"frequencyConvention" to c.frequencyConvention,
        "levelConvention" to c.levelConvention,"atmosphere" to c.atmosphere,"ground" to c.ground,"driveUnit" to c.driveUnit.name,
        "driveValue" to c.driveValue?.toString(),"normalization" to c.normalization,"broadband" to c.broadband)
    private fun structured(s: String?): JsonElement = s?.let {runCatching {Json.parseToJsonElement(it)}.getOrNull() ?: JsonPrimitive(it)} ?: JsonNull

    fun report(reference: ImportedComparison?,engine: ImportedComparison?,summary: JsonObject,includeDocuments: Boolean): String {
        val qualified=summary.getValue("comparison").jsonObject.getValue("status").jsonPrimitive.content in listOf("PASSED","FAILED")
        val a=reference?.run;val b=engine?.run
        val af=a?.let {fields(it.conditions)};val bf=b?.let {fields(it.conditions)}
        val differences=mutableListOf<JsonObject>();var differenceCount=0
        fun diff(path: String,x: JsonElement?,y: JsonElement?,kind: String="VALUE") {
            differenceCount++
            if(differences.size<512) differences+=buildJsonObject {
                put("path",path);put("kind",kind);put("reference",x ?: JsonNull);put("engine",y ?: JsonNull)
                put("referencePresent",x!=null);put("enginePresent",y!=null)
            }
        }
        fun walk(path: String,x: JsonElement?,y: JsonElement?) {
            if(x==y) return
            if(x is JsonObject && y is JsonObject) (x.keys+y.keys).sorted().forEach {walk("$path.$it",x[it],y[it])}
            else if(x is JsonArray && y is JsonArray) (0 until max(x.size,y.size)).forEach {walk("$path[$it]",x.getOrNull(it),y.getOrNull(it))}
            else diff(path,x,y)
        }
        val protocol=labels.map {(key,label)->
            val ready=af!=null && bf!=null;val equal=ready && af!![key]==bf!![key]
            if(ready && !equal) {
                val x=structured(af!![key]);val y=structured(bf!![key])
                if(x==y) diff(key,JsonPrimitive(af[key].orEmpty()),JsonPrimitive(bf[key].orEmpty()),"ENCODING_DIFFERENCE") else walk(key,x,y)
            }
            buildJsonObject {put("field",key);put("label",label);put("status",if(!ready) "WAITING" else if(equal) "MATCHED" else "MISMATCHED")}
        }
        val left=a?.samples.orEmpty();val right=b?.samples.orEmpty();val rightById=right.associateBy {it.id}
        val ids=left.map {it.id}.toSet()
        fun row(r: SpeakerComparisonSample?,e: SpeakerComparisonSample?,index: Int): JsonObject {
            val sameFrequency=r!=null && e!=null && r.hz==e.hz
            val samePosition=r!=null && e!=null && r.x==e.x && r.y==e.y && r.z==e.z
            val referenceLevel=r?.levelDb;val engineLevel=e?.levelDb
            val delta=if(sameFrequency && samePosition && referenceLevel!=null && engineLevel!=null)
                (engineLevel-referenceLevel).takeIf {it.isFinite()} else null
            val status=when {
                r==null -> "ENGINE_ONLY";e==null -> "REFERENCE_ONLY";!sameFrequency -> "FREQUENCY_MISMATCH";!samePosition -> "POSITION_MISMATCH"
                r.levelDb==null || e.levelDb==null -> "UNAVAILABLE";delta==null -> "NUMERICAL_OVERFLOW";else -> "PAIRED"
            }
            val p=r ?: e!!
            return buildJsonObject {
                put("sampleId",p.id);put("sampleOrder",index);put("frequencyHz",p.hz);put("xM",p.x);put("yM",p.y);put("zM",p.z)
                put("referenceLevelDb",r?.levelDb?.let(::JsonPrimitive) ?: JsonNull);put("engineLevelDb",e?.levelDb?.let(::JsonPrimitive) ?: JsonNull)
                put("residualDb",delta?.let(::JsonPrimitive) ?: JsonNull);put("status",status)
                e?.let {put("engineFrequencyHz",it.hz);put("enginePositionM",buildJsonArray {add(it.x);add(it.y);add(it.z)})}
            }
        }
        val rows=left.mapIndexed {i,r->row(r,rightById[r.id],i+1)}+right.filter {it.id !in ids}.mapIndexed {i,e->row(null,e,left.size+i+1)}
        val paired=rows.filter {it.getValue("residualDb")!=JsonNull}
        val deltas=paired.map {it.getValue("residualDb").jsonPrimitive.double}
        val scale=deltas.maxOfOrNull {abs(it)} ?: 0.0
        val bias=if(deltas.isEmpty()) null else if(scale==0.0) 0.0 else scale*(deltas.sumOf {it/scale}/deltas.size).coerceIn(-1.0,1.0)
        val rms=if(deltas.isEmpty()) null else if(scale==0.0) 0.0 else scale*sqrt((deltas.sumOf {(it/scale).pow(2)}/deltas.size).coerceIn(0.0,1.0))
        val tolerance=reference?.toleranceDb ?: 0.1
        fun plot(name: String,points: List<JsonObject>,frequency: Boolean): JsonObject {
            val xs=points.map {if(frequency) log2(it.getValue("frequencyHz").jsonPrimitive.double) else it.getValue("sampleOrder").jsonPrimitive.double}
            val lo=xs.minOrNull() ?: 0.0;val hi=xs.maxOrNull() ?: 1.0
            val range=max(tolerance,points.maxOfOrNull {abs(it.getValue("residualDb").jsonPrimitive.double)} ?: 0.0)
            return buildJsonObject {
                put("name",name);put("xScale",if(frequency) "log2-frequency" else "sample-order");put("residualScaleDb",range)
                put("toleranceNormalized",tolerance/range/2.2)
                put("points",JsonArray(points.mapIndexed {i,p->buildJsonObject {
                    put("sampleId",p.getValue("sampleId"));put("sampleOrder",p.getValue("sampleOrder"));put("frequencyHz",p.getValue("frequencyHz"));put("residualDb",p.getValue("residualDb"))
                    put("xNormalized",if(hi==lo) .5 else (xs[i]-lo)/(hi-lo))
                    put("yNormalized",.5-p.getValue("residualDb").jsonPrimitive.double/range/2.2)
                }}))
            }
        }
        val plots=mutableListOf(plot("All paired samples",paired,false))
        if(a?.conditions?.broadband==null && b?.conditions?.broadband==null) paired.filter {it.getValue("frequencyHz").jsonPrimitive.double>0}
            .groupBy {listOf(it.getValue("xM"),it.getValue("yM"),it.getValue("zM"))}.forEach {(position,points)->
                plots+=plot("Receiver (${position.joinToString()}) m",points.sortedBy {it.getValue("frequencyHz").jsonPrimitive.double},true)
            }
        return buildJsonObject {
            put("schema","droidacoustic.validation-report");put("version",1);put("summary",summary)
            put("declaredToleranceDb",reference?.toleranceDb?.let(::JsonPrimitive) ?: JsonNull)
            put("scope",if(qualified) "COMPARABLE_SAMPLES_ONLY" else "DIAGNOSTIC_ONLY_NOT_QUALIFIED")
            put("residualConvention","engine minus reference, dB; only identical sample ID/frequency/position pairs; no offset fitting")
            put("protocol",JsonArray(protocol));put("protocolDifferences",JsonArray(differences));put("protocolDifferenceCount",differenceCount)
            put("protocolDifferencesTruncated",differenceCount>differences.size)
            put("samples",JsonArray(rows));put("plots",JsonArray(plots))
            put("metrics",buildJsonObject {
                put("qualified",qualified);put("pairedCount",deltas.size);put("unpairedOrUnavailableCount",rows.size-deltas.size)
                put("maximumAbsoluteErrorDb",deltas.takeIf {it.isNotEmpty()}?.let {JsonPrimitive(scale)} ?: JsonNull)
                put("biasDb",bias?.let(::JsonPrimitive) ?: JsonNull);put("rmsErrorDb",rms?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("assumptions",JsonArray((PredictionModelScope.limitations+listOf("Imported origin/calibration/conditions are declarations, not authenticated.",
                "Diagnostic residuals can be shown for incompatible datasets; they never change qualification.",
                "Reordered rows may be paired by ID for inspection; strict comparison still rejects reordered datasets.")).map(::JsonPrimitive)))
            if(includeDocuments) put("inputs",buildJsonObject {
                reference?.let {put("referenceDocument",it.document)};engine?.let {put("engineDocument",it.document)}
            })
        }.toString()
    }
}
