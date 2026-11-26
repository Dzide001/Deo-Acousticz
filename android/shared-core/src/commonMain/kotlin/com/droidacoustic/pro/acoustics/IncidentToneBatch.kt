package com.droidacoustic.pro.acoustics

import kotlin.math.*

/** Exact-tone scratch for one surface and at most 32 frequencies. Amplitudes are source-indexed;
 * a source unavailable at one tone must have zero real and imaginary amplitudes at that tone.
 * Eight Gauss lanes may share a phase step. Irregular/poorly rounded frequencies use direct
 * phases instead. Every prepare re-anchors all phases; no recurrence crosses a chunk. */
internal class IncidentToneBatch(private val capacity:Int) {
    private val phasesReal=DoubleArray(32*capacity)
    private val phasesImaginary=DoubleArray(32*capacity)
    private val heads=IntArray(capacity)
    private val tails=IntArray(capacity)
    private val next=IntArray(capacity)
    private val sizes=IntArray(capacity)
    private val active=IntArray(capacity)
    private val lows=DoubleArray(capacity)
    private val highs=DoubleArray(capacity)
    private var groups=0
    private var tones=0
    internal var phaseEvaluations=0L
        private set
    internal var usesRecurrence=false
        private set

    fun prepare(frequencies:DoubleArray,times:DoubleArray,groupIds:IntArray,visible:IntArray,
        checkWork:((Int)->Unit)?=null) {
        require(frequencies.size<=32 && frequencies.all {it.isFinite()})
        require(times.size>=capacity && groupIds.size>=capacity)
        tones=frequencies.size;groups=0;phaseEvaluations=0;heads.fill(-1)
        for((slot,i) in visible.withIndex()) {
            if(slot%32==0) checkWork?.invoke(min(32,visible.size-slot))
            require(i in 0 until capacity && times[i].isFinite())
            val g=groupIds[i];require(g in 0 until capacity)
            next[i]=-1
            if(heads[g]<0) {
                active[groups++]=g;heads[g]=i;sizes[g]=1;lows[g]=times[i];highs[g]=times[i]
            } else {
                next[tails[g]]=i;sizes[g]++
                lows[g]=min(lows[g],times[i]);highs[g]=max(highs[g],times[i])
            }
            tails[g]=i
        }
        val span=(0 until groups).maxOfOrNull {highs[active[it]]-lows[active[it]]} ?: 0.0
        val step=if(tones>8) frequencies[8]-frequencies[0] else 0.0
        // Check actual supplied frequencies, including their rounding, rather than assuming
        // every caller passes a complete regular lattice after interval/chunk partitioning.
        usesRecurrence=tones>=16 && step.isFinite() && (8 until tones).all {j ->
            val expected=frequencies[j%8]+(j/8)*step
            val error=abs(frequencies[j]-expected)
            error<=max(1e-12,abs(frequencies[j])*2e-15) && 2*PI*error*span<=1e-11
        }
        for((slot,i) in visible.withIndex()) {
            if(slot%32==0) checkWork?.invoke(min(32,visible.size-slot))
            val delay=times[i]-lows[groupIds[i]]
            var stepReal=0.0;var stepImaginary=0.0
            if(usesRecurrence) {
                val angle=2*PI*step*delay
                stepReal=cos(angle);stepImaginary=sin(angle);phaseEvaluations++
            }
            for(j in frequencies.indices) {
                val offset=j*capacity+i
                if(usesRecurrence && j>=8) {
                    val previous=(j-8)*capacity+i
                    val r=phasesReal[previous];val im=phasesImaginary[previous]
                    phasesReal[offset]=r*stepReal-im*stepImaginary
                    phasesImaginary[offset]=r*stepImaginary+im*stepReal
                } else {
                    val angle=2*PI*frequencies[j]*delay
                    phasesReal[offset]=cos(angle);phasesImaginary[offset]=sin(angle);phaseEvaluations++
                }
            }
        }
    }

    fun power(jobOffset:Int,real:DoubleArray,imaginary:DoubleArray,checkWork:((Int)->Unit)?=null):Double {
        require(jobOffset in 0 until tones && real.size>=capacity && imaginary.size>=capacity)
        val offset=jobOffset*capacity
        var total=0.0
        for(bucket in 0 until groups) {
            val g=active[bucket];checkWork?.invoke(sizes[g])
            var r=0.0;var im=0.0;var i=heads[g]
            while(i>=0) {
                val c=phasesReal[offset+i];val s=phasesImaginary[offset+i]
                r+=real[i]*c-imaginary[i]*s;im+=real[i]*s+imaginary[i]*c
                i=next[i]
            }
            total+=r*r+im*im
        }
        return total
    }
}
