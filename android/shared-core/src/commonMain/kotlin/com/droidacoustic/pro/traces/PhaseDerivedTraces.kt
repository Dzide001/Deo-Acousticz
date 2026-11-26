package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.PhaseResponse
import kotlin.math.abs

/** A segment has an independent wrapped starting phase; its absolute turn count is unknown. */
data class UnwrappedPhasePoint(val hz: Int, val degrees: Double?, val segment: Int?, val reason: String?)
data class GroupDelayInterval(val fromHz: Int, val toHz: Int, val milliseconds: Double?, val reason: String?) {
    /** Unique nearest-turn reconstruction requires |true phase change| < 180 degrees. */
    val aliasLimitMs: Double get() = 500.0 / (toHz - fromHz)
}
data class DerivedPhaseTrace(val points: List<UnwrappedPhasePoint>, val delays: List<GroupDelayInterval>)
data class DerivedPhaseComparison(val compatible: Boolean, val reason: String?,
    val baseline: DerivedPhaseTrace?, val candidate: DerivedPhaseTrace?)

/** Nearest-turn sampled estimates, not a recovery of unobserved turns or an impulse response.
 * d(phi)/d(2*pi*f) is positive delay for this engine's conjugated, positive-delay phasors.
 * Intervals are secants in linear Hz, NOT derivatives with respect to log-frequency.
 * Missing samples and exactly half-turn steps break continuity. No endpoint extrapolation.
 */
object PhaseDerivedTraces {
    fun derive(trace: SavedTrace, relativeToReference:Boolean=false): DerivedPhaseTrace {
        val supported = trace.phaseConvention in setOf(PhaseResponse.CONVENTION,
            PhaseResponse.SPECULAR_CONVENTION, PhaseResponse.LEGACY_CONVENTION)
        var previousHz: Int? = null
        var previousWrapped: Double? = null
        var unwrapped = 0.0
        var segment = -1
        val points = mutableListOf<UnwrappedPhasePoint>()
        val delays = mutableListOf<GroupDelayInterval>()
        val reference=trace.effectiveDelayReferenceS
        val restoredReference=if(relativeToReference) 0.0 else reference
        val phases=trace.phases
        for (hz in (trace.powers.keys+phases.keys).sorted()) {
            val sample = phases[hz]
            val degrees = if (supported) sample?.degrees?.let {PhaseResponse.wrapped(it-360.0*hz*reference)} else null
            var reason = if (!supported) "Unsupported phase convention" else
                if (degrees == null) sample?.reason ?: "No complex phase sample" else null
            val step = if (degrees != null && previousWrapped != null)
                PhaseResponse.wrapped(degrees - previousWrapped) else null
            val ambiguous = step != null && abs(abs(step) - 180.0) <= 1e-9
            if (ambiguous) reason = "Ambiguous 180° phase step; new segment"
            previousHz?.let { from ->
                delays += GroupDelayInterval(from, hz,
                    if (step != null && !ambiguous) (restoredReference + step / (360.0 * (hz - from))) * 1000.0 else null,
                    if (step == null) reason ?: "Previous phase sample unavailable" else if (ambiguous) reason else null)
            }
            if (degrees != null) {
                if (step == null || ambiguous) { segment++; unwrapped = degrees }
                else unwrapped += step
            }
            points += UnwrappedPhasePoint(hz, if (degrees != null) unwrapped+360.0*hz*restoredReference else null,
                if (degrees != null) segment else null, reason)
            previousWrapped = degrees
            previousHz = hz
        }
        return DerivedPhaseTrace(points.toList(), delays.toList())
    }

    fun compare(a: SavedTrace, b: SavedTrace, relativeToReference:Boolean=false): DerivedPhaseComparison {
        val phase = TraceComparisons.comparePhase(a, b)
        val reason = phase.reason ?: if ((a.powers.keys+a.phases.keys) != (b.powers.keys+b.phases.keys))
            "Frequency grids differ; derived phase segments cannot be compared" else null
        return DerivedPhaseComparison(reason == null, reason,
            if (reason == null) derive(a,relativeToReference) else null, if (reason == null) derive(b,relativeToReference) else null)
    }
}
