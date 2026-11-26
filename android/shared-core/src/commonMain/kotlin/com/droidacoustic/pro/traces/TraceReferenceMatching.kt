package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.PhaseResponse
import kotlin.math.*

/** Inclusive measured-frequency window. Null selection means all captured frequencies. */
data class MatchingFrequencyRange(val minHz:Int,val maxHz:Int) {
    init {require(minHz>0 && maxHz>minHz) {"Enter positive whole-Hz limits with maximum greater than minimum"}}
}

enum class MatchSampleStatus { RETAINED, REJECTED, EXCLUDED, MISSING, INSUFFICIENT }
data class MatchFitSample(val hz:Int,val observedDegrees:Double?,val fittedDegrees:Double?,
    val segment:Int?,val status:MatchSampleStatus) {
    val residualDegrees:Double? get()=if(observedDegrees!=null && fittedDegrees!=null) observedDegrees-fittedDegrees else null
}

/** Preview is tied to both immutable captures and their reference settings. */
class TraceReferenceMatch internal constructor(
    val baselineId:Int, val candidateId:Int,
    val proposedReferenceS:Double?, val correctionS:Double?,
    val usedSamples:Int, val segments:Int, val residualRmsDegrees:Double?,
    val residualAliasLimitMs:Double?, val reason:String?,
    internal val baselineFingerprint:String, internal val candidateFingerprint:String,
    val frequencyRange:MatchingFrequencyRange?=null, val usedMinHz:Int?=null,val usedMaxHz:Int?=null,
    val confidenceScore:Int=0,val rejectedFrequencies:List<Int> = emptyList(),val eligibleSamples:Int=0, fitSamples:List<MatchFitSample> = emptyList(), excludedFrequencies:Set<Int> = emptySet()
) {
    private val exclusions=excludedFrequencies.toSet()
    val excludedFrequencies:Set<Int> get()=exclusions.toSet()
    private val diagnosticSamples=fitSamples.toList()
    val fitSamples:List<MatchFitSample> get()=diagnosticSamples.toList()
    val confidenceLabel:String get()=when {confidenceScore>=80 -> "High";confidenceScore>=50 -> "Moderate";else -> "Low"}
    val available:Boolean get()=proposedReferenceS!=null && reason==null
}

/** Fits the slope of B-relative minus A-relative phase. Independent segment intercepts
 * absorb arbitrary turns/constant phase; missing samples are never bridged. Equal sample
 * weighting is deliberate: band powers are not exact-tone confidence weights.
 * Like phase unwrapping, this cannot identify hidden turns between sparse samples.
 */
object TraceReferenceMatching {
    private data class Point(val hz:Double,val degrees:Double)
    private fun fingerprint(t:SavedTrace)=TraceComparisons.fingerprint(SavedTraces.empty().captureBatch(listOf(t)).toJsonString())
    fun preview(library:SavedTraces,a:Int,b:Int):TraceReferenceMatch = preview(
        library.traces.first {it.id==a},library.traces.first {it.id==b},library.matchingFrequencyRange,library.excludedFrequencies(a,b))
    fun preview(a:SavedTrace,b:SavedTrace,frequencyRange:MatchingFrequencyRange?=null,excludedFrequencies:Set<Int> = emptySet()):TraceReferenceMatch {
        val excluded=excludedFrequencies.toSet()
        require(excluded.all {it in (a.phases.keys+b.phases.keys+a.powers.keys+b.powers.keys)}) {"Invalid excluded frequency"}
        val af=fingerprint(a);val bf=fingerprint(b)
        fun unavailable(reason:String)=TraceReferenceMatch(a.id,b.id,null,null,0,0,null,null,reason,af,bf,frequencyRange,excludedFrequencies=excluded)
        if(a.id==b.id) return unavailable("Choose two different saved traces")
        val comparison=TraceComparisons.comparePhase(a,b,true)
        if(!comparison.compatible) return unavailable(comparison.reason ?: "Incompatible traces")
        val rows=comparison.bands.associateBy {it.hz}
        val frequencies=(a.powers.keys+b.powers.keys+rows.keys).filter {frequencyRange==null || it in frequencyRange.minHz..frequencyRange.maxHz}.sorted()
        val segments=mutableListOf<List<Point>>()
        var points=mutableListOf<Point>();var previous:Double?=null;var unwrapped=0.0
        var limit=Double.POSITIVE_INFINITY
        fun finish() {if(points.size>=3) segments+=points.toList();points=mutableListOf();previous=null}
        for(hz in frequencies) {
            if(hz in excluded) {finish();continue}
            val row=rows[hz];val left=row?.baseline?.degrees;val right=row?.candidate?.degrees
            if(left==null || right==null) {finish();continue}
            val difference=PhaseResponse.wrapped(right-left)
            val previousPhase=previous
            if(previousPhase==null) unwrapped=difference else {
                val step=PhaseResponse.wrapped(difference-previousPhase)
                if(abs(abs(step)-180.0)<=1e-9) return unavailable("Ambiguous 180° phase-difference step")
                unwrapped+=step
            }
            points+=Point(hz.toDouble(),unwrapped);previous=difference
        }
        finish()
        fun diagnostics(fitted:List<List<Point>> = emptyList(),slope:Double?=null,rejected:Set<Int> = emptySet()):List<MatchFitSample> {
            val originals=segments.flatMapIndexed {index,segment -> segment.map {it.hz.toInt() to (index to it)}}.toMap()
            val retained=fitted.flatten().map {it.hz.toInt()}.toSet()
            val models=if(slope==null) emptyMap() else fitted.associate {segment ->
                val id=originals.getValue(segment.first().hz.toInt()).first
                id to (segment.map {it.hz}.average() to segment.map {it.degrees}.average())
            }
            return frequencies.map {hz ->
                val original=originals[hz]
                val row=rows[hz]
                val status=when {
                    hz in excluded -> MatchSampleStatus.EXCLUDED
                    row?.baseline?.degrees==null || row.candidate?.degrees==null -> MatchSampleStatus.MISSING
                    hz in retained -> MatchSampleStatus.RETAINED
                    hz in rejected -> MatchSampleStatus.REJECTED
                    else -> MatchSampleStatus.INSUFFICIENT
                }
                val model=original?.first?.let {models[it]}
                MatchFitSample(hz,original?.second?.degrees,
                    if(model!=null && slope!=null) model.second+slope*(hz-model.first) else null,original?.first,status)
            }
        }
        if(segments.isEmpty()) return TraceReferenceMatch(a.id,b.id,null,null,0,0,null,null,
            "Need at least three consecutive common valid phase samples",af,bf,frequencyRange,fitSamples=diagnostics(),excludedFrequencies=excluded)
        // Deterministic bounded Theil–Sen seed; independent offsets preserve missing-data gaps.
        fun median(values:List<Double>):Double {
            val v=values.sorted();val m=v.size/2
            return if(v.size%2==0) (v[m-1]+v[m])/2 else v[m]
        }
        val seedSlopes=segments.flatMap {segment ->
            val sample=if(segment.size<=64) segment else (0 until 64).map {segment[it*(segment.size-1)/63]}
            sample.indices.flatMap {i -> (i+1 until sample.size).map {j ->
                (sample[j].degrees-sample[i].degrees)/(sample[j].hz-sample[i].hz)
            }}
        }
        val seed=median(seedSlopes)
        val residuals=segments.map {segment ->
            val offset=median(segment.map {it.degrees-seed*it.hz})
            segment.map {it.degrees-seed*it.hz-offset}
        }
        val flat=residuals.flatten()
        val centre=median(flat)
        val sigma=1.4826*median(flat.map {abs(it-centre)})
        // A 5° floor avoids rejecting harmless numerical/measurement noise; the 15° cap
        // prevents a heavily contaminated trace from expanding its own acceptance threshold.
        val cutoff=max(5.0,min(15.0,3*sigma))
        val rejected=mutableListOf<Int>()
        val fitted=segments.mapIndexedNotNull {index,segment ->
            val inliers=segment.filterIndexed {i,p ->
                (abs(residuals[index][i])<=cutoff).also {if(!it) rejected+=p.hz.toInt()}
            }
            if(inliers.size>=3) inliers else {
                rejected+=inliers.map {it.hz.toInt()};null
            }
        }
        val eligible=segments.sumOf {it.size}
        if(fitted.isEmpty()) return TraceReferenceMatch(a.id,b.id,null,null,0,0,null,null,
            "Too few consistent samples after outlier rejection",af,bf,frequencyRange,
            confidenceScore=0,rejectedFrequencies=rejected.sorted().toList(),eligibleSamples=eligible,fitSamples=diagnostics(rejected=rejected.toSet()),excludedFrequencies=excluded)
        var xx=0.0;var xy=0.0
        for(segment in fitted) {
            val mx=segment.map {it.hz}.average();val my=segment.map {it.degrees}.average()
            segment.forEach {xx+=(it.hz-mx).pow(2);xy+=(it.hz-mx)*(it.degrees-my)}
            segment.zipWithNext().forEach {(p,q)->limit=min(limit,500/(q.hz-p.hz))}
        }
        val slope=xy/xx
        val count=fitted.sumOf {it.size}
        val error=fitted.sumOf {segment ->
            val mx=segment.map {it.hz}.average();val my=segment.map {it.degrees}.average()
            segment.sumOf {(it.degrees-my-slope*(it.hz-mx)).pow(2)}
        }
        val rms=sqrt(error/count)
        val correction=slope/360
        val proposed=b.effectiveDelayReferenceS+correction
        val reason=when {
            !proposed.isFinite() || abs(proposed)>3600 -> "Matched reference is outside the supported range"
            rejected.size.toDouble()/eligible>.25 -> "Too many inconsistent samples (more than 25% rejected)"
            rms>10.0 -> "Phase difference is not sufficiently linear (fit error exceeds 10° RMS)"
            else -> null
        }
        // Diagnostic quality index, NOT a probability or evidence against hidden phase turns.
        val support=min(1.0,count/12.0)
        val completeness=count.toDouble()/frequencies.size
        val retention=count.toDouble()/eligible
        val margin=(1-abs(correction*1000)/limit).coerceIn(0.0,1.0)
        val confidence=if(reason!=null) 0 else (100*support*completeness*retention*exp(-rms/10)*min(1.0,margin*4)).roundToInt().coerceIn(0,100)
        return TraceReferenceMatch(a.id,b.id,if(reason==null) proposed else null,correction,count,fitted.size,rms,limit,reason,af,bf,frequencyRange,fitted.minOf {it.first().hz.toInt()},fitted.maxOf {it.last().hz.toInt()},confidence,rejected.sorted().toList(),eligible,diagnostics(fitted,slope,rejected.toSet()),excluded)
    }
    /** Stale previews fail atomically; only B's display reference and relative mode change. */
    fun apply(library:SavedTraces,preview:TraceReferenceMatch):SavedTraces {
        require(preview.available) {preview.reason ?: "No matching reference"}
        require(library.matchingFrequencyRange==preview.frequencyRange) {"Matching frequency range changed; preview again"}
        require(library.excludedFrequencies(preview.baselineId,preview.candidateId)==preview.excludedFrequencies) {"Manual exclusions changed; preview again"}
        val a=library.traces.firstOrNull {it.id==preview.baselineId} ?: error("Baseline trace was removed")
        val b=library.traces.firstOrNull {it.id==preview.candidateId} ?: error("Candidate trace was removed")
        require(fingerprint(a)==preview.baselineFingerprint && fingerprint(b)==preview.candidateFingerprint) {"Traces or references changed; preview again"}
        return library.withDelayReference(b.id,preview.proposedReferenceS).withRelativeDisplay(true)
    }
}
