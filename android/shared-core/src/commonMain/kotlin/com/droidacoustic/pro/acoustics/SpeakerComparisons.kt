package com.droidacoustic.pro.acoustics

import kotlinx.serialization.json.*
import kotlin.math.abs

/** Every string is an explicit protocol declaration, not an automatically inferred match. */
data class SpeakerComparisonConditions(
    val modelRevision: String, val preset: String, val geometry: String,
    val frequencyConvention: String, val levelConvention: String,
    val atmosphere: String, val ground: String,
    val driveUnit: DriveUnit = DriveUnit.UNKNOWN, val driveValue: Double? = null,
    val normalization: String? = null, val broadband: String? = null
) {
    internal fun complete() = listOf(modelRevision,preset,geometry,frequencyConvention,levelConvention,atmosphere,ground)
        .all {it.isNotBlank()} && levelConvention in listOf("ABSOLUTE_SPL", "RELATIVE_TO_ON_AXIS", "RELATIVE_TO_REFERENCE", "BROADBAND_SPL", "RELATIVE_BROADBAND_TO_REFERENCE") &&
        (driveUnit==DriveUnit.UNKNOWN && driveValue==null || driveUnit!=DriveUnit.UNKNOWN && driveValue!=null && driveValue.isFinite() && driveValue>0) &&
        (normalization?.let {text -> runCatching {
            RelativeResponseNormalization.parse(Json.parseToJsonElement(text).jsonObject).let {it.canonical()==text && it.method==if(levelConvention=="RELATIVE_BROADBAND_TO_REFERENCE") "weighted-broadband-level-difference" else "per-frequency-level-difference"}
        }.getOrDefault(false)} ?: true) &&
        (if(levelConvention in listOf("BROADBAND_SPL","RELATIVE_BROADBAND_TO_REFERENCE")) broadband?.let {text -> runCatching {BroadbandDefinition.parse(Json.parseToJsonElement(text).jsonObject).canonical()==text}.getOrDefault(false)}==true && (if(levelConvention=="BROADBAND_SPL") normalization==null else normalization!=null) else broadband==null)
}
enum class ComparisonEvidence { ENGINE, SYNTHETIC, MANUFACTURER_DATA, MANUFACTURER_SOFTWARE_EXPORT, CONTROLLED_MEASUREMENT }
data class SpeakerComparisonSample(val id: String, val hz: Double, val x: Double, val y: Double, val z: Double, val levelDb: Double?)
data class SpeakerComparisonRun(val conditions: SpeakerComparisonConditions, val evidence: ComparisonEvidence,
    val artifactSha256: String, val samples: List<SpeakerComparisonSample>)

object SpeakerComparisons {
    /** No best-fit offset, intersection-only matching or voltage/power guessing is allowed. */
    fun compare(reference: SpeakerComparisonRun, engine: SpeakerComparisonRun, toleranceDb: Double): String {
        require(toleranceDb.isFinite() && toleranceDb>0)
        val reasons = mutableListOf<String>()
        val a=reference.conditions; val b=engine.conditions
        if(!a.complete() || !b.complete()) reasons+="Incomplete comparison conditions"
        if(a!=b) reasons+="Operating conditions differ"
        if(a.levelConvention !in listOf("ABSOLUTE_SPL","BROADBAND_SPL") && (a.normalization==null || b.normalization==null) &&
            !(reference.evidence==ComparisonEvidence.MANUFACTURER_DATA && a.levelConvention=="RELATIVE_TO_ON_AXIS"))
            reasons+="Relative prediction/measurement comparison requires explicit normalization"
        if(a.levelConvention in listOf("ABSOLUTE_SPL","BROADBAND_SPL") && (a.driveUnit==DriveUnit.UNKNOWN || a.driveValue==null))
            reasons+="Absolute comparison requires an explicit electrical drive"
        if(reference.evidence in listOf(ComparisonEvidence.ENGINE,ComparisonEvidence.SYNTHETIC) || engine.evidence!=ComparisonEvidence.ENGINE)
            reasons+="Reference is not independent manufacturer/measurement evidence"
        if(listOf(reference,engine).any { !Regex("[0-9a-f]{64}").matches(it.artifactSha256) }) reasons+="Missing artifact identity"
        fun keys(run: SpeakerComparisonRun)=run.samples.map {listOf(it.id,it.hz,it.x,it.y,it.z)}
        if(reference.samples.isEmpty() || reference.samples.map {it.id}.distinct().size!=reference.samples.size ||
            engine.samples.map {it.id}.distinct().size!=engine.samples.size || keys(reference)!=keys(engine))
            reasons+="Samples are missing, duplicated, reordered or at different coordinates/frequencies"
        if((reference.samples+engine.samples).any {s -> s.id.isBlank() || !s.hz.isFinite() || (if(a.broadband!=null) s.hz!=0.0 else s.hz<=0) ||
                listOf(s.x,s.y,s.z).any {!it.isFinite()} || s.levelDb==null || !s.levelDb.isFinite()}) reasons+="Unavailable or invalid sample"
        val candidates = if(reasons.isEmpty()) reference.samples.zip(engine.samples).map { (r,e) -> e.levelDb!!-r.levelDb!! } else emptyList()
        if(candidates.any {!it.isFinite()}) reasons+="Residual difference overflows numerical range"
        val differences=if(reasons.isEmpty()) candidates else emptyList()
        val maxError = differences.maxOfOrNull {abs(it)}
        return buildJsonObject {
            put("status",if(reasons.isNotEmpty()) "NOT_COMPARABLE" else if(maxError!!<=toleranceDb) "PASSED" else "FAILED")
            put("evidenceScope",when(reference.evidence) {
                ComparisonEvidence.MANUFACTURER_DATA -> "DATA_CONSISTENCY_ONLY"
                ComparisonEvidence.MANUFACTURER_SOFTWARE_EXPORT -> "MATCHED_SOFTWARE_COMPARISON"
                ComparisonEvidence.CONTROLLED_MEASUREMENT -> "MATCHED_MEASUREMENT_COMPARISON"
                else -> "UNQUALIFIED"
            })
            put("referenceArtifactSha256",reference.artifactSha256);put("engineArtifactSha256",engine.artifactSha256)
            put("toleranceDb",toleranceDb);put("sampleCount",differences.size)
            maxError?.let {put("maxAbsoluteErrorDb",it)}
            put("differencesDb",JsonArray(differences.map(::JsonPrimitive)))
            put("reasons",JsonArray(reasons.map(::JsonPrimitive)))
        }.toString()
    }
}
