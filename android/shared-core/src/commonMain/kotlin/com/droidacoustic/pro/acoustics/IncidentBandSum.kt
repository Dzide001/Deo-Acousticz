package com.droidacoustic.pro.acoustics

import kotlin.math.*

/** Reusable per-calculation scratch. Inputs share one amplitude convention: incident
 * flux-normalized amplitudes or band-centre receiver pressure phasors.
 * Eight-point Gauss integration is used only for small bandwidth × delay span. Its
 * absolute error is bounded by 2e-10 × (sum |amplitude|)^2 at span <= 1 cycle.
 * Wider spans retain the analytic pairwise sinc integral, without dropping coherence. */
internal class IncidentBandSum(capacity: Int) {
    val real=DoubleArray(capacity)
    val imaginary=DoubleArray(capacity)
    val time=DoubleArray(capacity)
    val group=IntArray(capacity)
    var count=0
    var evaluations=0L
        private set
    // Dense correlation IDs are assigned by CoherentDiffuse. Preserve source/group order
    // without allocating boxed indices, iterators or per-group lists on every patch.
    private val heads=IntArray(capacity)
    private val tails=IntArray(capacity)
    private val next=IntArray(capacity)
    private val sizes=IntArray(capacity)
    private val active=IntArray(capacity)
    private val lows=DoubleArray(capacity)
    private val highs=DoubleArray(capacity)
    fun power(centreHz:Double,widthHz:Double,analytic:Boolean=false,checkWork:((Int)->Unit)?=null):Double {
        evaluations=0;heads.fill(-1)
        var groups=0
        for(i in 0 until count) {
            val g=group[i];next[i]=-1
            if(heads[g]<0) {
                active[groups++]=g;heads[g]=i;sizes[g]=1;lows[g]=time[i];highs[g]=time[i]
            } else {
                next[tails[g]]=i;sizes[g]++
                lows[g]=min(lows[g],time[i]);highs[g]=max(highs[g],time[i])
            }
            tails[g]=i
        }
        var total=0.0
        for(bucket in 0 until groups) {
            val g=active[bucket];val lo=lows[g];val hi=highs[g]
            if(widthHz==0.0) {
                checkWork?.invoke(sizes[g])
                var r=0.0;var im=0.0;var i=heads[g]
                while(i>=0) {
                    val phase=2*PI*centreHz*(time[i]-lo);val c=cos(phase);val s=sin(phase)
                    r+=real[i]*c-imaginary[i]*s;im+=real[i]*s+imaginary[i]*c
                    evaluations++;i=next[i]
                }
                total+=r*r+im*im
            } else if(!analytic && sizes[g]>=24 && widthHz*(hi-lo)<=1.0) {
                for(k in nodes.indices) {
                    checkWork?.invoke(sizes[g])
                    val frequency=centreHz+widthHz*.5*nodes[k]
                    var r=0.0;var im=0.0;var i=heads[g]
                    while(i>=0) {
                        val phase=2*PI*frequency*(time[i]-lo)
                        val c=cos(phase);val s=sin(phase)
                        r+=real[i]*c-imaginary[i]*s;im+=real[i]*s+imaginary[i]*c
                        evaluations++;i=next[i]
                    }
                    total+=weights[k]*(r*r+im*im)*.5
                }
            } else {
                checkWork?.invoke(sizes[g])
                var power=0.0;var i=heads[g]
                while(i>=0) {power+=real[i]*real[i]+imaginary[i]*imaginary[i];i=next[i]}
                i=heads[g];var preceding=0
                while(i>=0) {
                    checkWork?.invoke(preceding++)
                    var j=heads[g]
                    while(j!=i) {
                        val delay=time[i]-time[j];val angle=2*PI*centreHz*delay;val x=PI*widthHz*delay
                        val sinc=if(abs(x)<1e-8) 1.0 else sin(x)/x
                        power+=2*((real[i]*real[j]+imaginary[i]*imaginary[j])*cos(angle)-
                            (imaginary[i]*real[j]-real[i]*imaginary[j])*sin(angle))*sinc
                        evaluations++;j=next[j]
                    }
                    i=next[i]
                }
                total+=max(0.0,power)
            }
        }
        return total
    }
    companion object {
        internal val nodes=doubleArrayOf(-.9602898564975363,-.7966664774136267,-.5255324099163290,-.1834346424956498,
            .1834346424956498,.5255324099163290,.7966664774136267,.9602898564975363)
        internal val weights=doubleArrayOf(.1012285362903763,.2223810344533745,.3137066458778873,.3626837833783620,
            .3626837833783620,.3137066458778873,.2223810344533745,.1012285362903763)
    }
}
