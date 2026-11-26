package com.droidacoustic.pro.alignment

import com.droidacoustic.pro.acoustics.SoundSource
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.math.sqrt

/** Fixed physical reference, independent of the movable listener and coverage mesh. */
data class AlignmentReceiver(val name: String, val position: BoundaryPoint) {
    init { require(name.isNotBlank()); require(listOf(position.x,position.y,position.z).all {it.isFinite()}) }
}

/** Emitters must include the base delayMs plus their individual steering delays. */
data class AlignmentSource(val id: Int, val emitters: List<SoundSource>, val delayMs: Double)
data class ArrivalComparison(
    val sourceId: Int,
    val earliestMs: Double,
    val latestMs: Double,
    /** Positive: this source arrives later than the reference source. */
    val relativeArrivalMs: Double,
    /** Signed adjustment to this source's base DSP delay to match earliest arrivals. */
    val delayChangeMs: Double,
    /** Null when matching would require a negative delay or exceed the configured limit. */
    val proposedDelayMs: Double?
)

/** Geometric direct-path timing, including DSP and per-element steering delays.
 * This is not an impulse peak, audibility test or frequency-dependent phase alignment.
 * Arrays retain their arrival spread instead of being replaced by their centre. */
object SystemAlignment {
    fun compare(receiver: AlignmentReceiver, sources: List<AlignmentSource>, referenceSourceId: Int,
        speedOfSound: Double, maximumDelayMs: Double = 200.0, delayOverridesMs: Map<Int,Double> = emptyMap()): List<ArrivalComparison> {
        require(speedOfSound.isFinite() && speedOfSound>0)
        require(maximumDelayMs.isFinite() && maximumDelayMs>=0)
        require(sources.map {it.id}.distinct().size==sources.size)
        require(sources.any {it.id==referenceSourceId})
        require(delayOverridesMs.keys.all {id -> sources.any {it.id==id}})
        fun delay(source: AlignmentSource)=delayOverridesMs[source.id] ?: source.delayMs
        require(sources.all {delay(it).isFinite() && delay(it) in 0.0..maximumDelayMs})
        val arrivals=sources.associate {source ->
            require(source.delayMs.isFinite() && source.delayMs in 0.0..maximumDelayMs)
            require(source.emitters.isNotEmpty())
            source.id to source.emitters.map {e ->
                require(listOf(e.x,e.y,e.z).all {it.isFinite()} && e.delayS.isFinite() && e.delayS>=0)
                val dx=receiver.position.x-e.x;val dy=receiver.position.y-e.y;val dz=receiver.position.z-e.z
                ((sqrt(dx*dx+dy*dy+dz*dz)/speedOfSound+e.delayS+(delay(source)-source.delayMs)/1000)*1000).also {require(it.isFinite())}
            }
        }
        val reference=arrivals.getValue(referenceSourceId).min()
        return sources.map {s ->
            val times=arrivals.getValue(s.id);val first=times.min();val change=reference-first
            val proposed=delay(s)+change
            ArrivalComparison(s.id,first,times.max(),first-reference,change,
                if(proposed>=-1e-8 && proposed<=maximumDelayMs+1e-8) proposed.coerceIn(0.0,maximumDelayMs) else null)
        }
    }
}
