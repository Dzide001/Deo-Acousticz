package com.droidacoustic.pro.acoustics

import kotlin.math.*

/** Finite-band direct/specular interference, with material phase slopes retained exactly.
 * At the band centre an arrival has phase 2π f t - φ. Its effective delay across the
 * band is t - φ'/2π. Moving the centre phasor into the complex amplitude lets the
 * shared bounded-error quadrature operate around zero frequency; wide spans retain
 * the analytic sinc integral. Independent groups never acquire cross terms.
 */
internal class ArrivalBandSum(times:DoubleArray,groups:IntArray) {
    private val times=times.copyOf()
    private val groups=groups.copyOf()
    private val references=DoubleArray(times.size) {Double.POSITIVE_INFINITY}
    private val sum=IncidentBandSum(times.size)
    val evaluations get()=sum.evaluations
    init {
        require(times.size==groups.size)
        for(i in times.indices) {
            require(times[i].isFinite() && groups[i] in times.indices)
            references[groups[i]]=min(references[groups[i]],times[i])
        }
    }
    fun power(amplitude:DoubleArray,phase:DoubleArray,slope:DoubleArray,centreHz:Double,widthHz:Double,
        analytic:Boolean=false,checkWork:((Int)->Unit)?=null):Double {
        sum.count=0
        for(i in times.indices) {
            if(amplitude[i]==0.0) continue
            val k=sum.count++;val relativeTime=times[i]-references[groups[i]]
            val angle=2*PI*centreHz*relativeTime-phase[i]
            sum.real[k]=amplitude[i]*cos(angle);sum.imaginary[k]=amplitude[i]*sin(angle)
            sum.time[k]=relativeTime-slope[i]/(2*PI);sum.group[k]=groups[i]
        }
        return sum.power(0.0,widthHz,analytic,checkWork)
    }
}
