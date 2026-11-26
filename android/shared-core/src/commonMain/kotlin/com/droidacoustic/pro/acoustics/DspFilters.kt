package com.droidacoustic.pro.acoustics

import kotlin.math.*
import kotlinx.serialization.json.*

enum class FilterKind { LOW_PASS, HIGH_PASS, PEAK }
/** RBJ/W3C digital biquad, explicit sample rate. Positive-delay convention conjugates H(exp(+jw)). */
data class DspFilter(val kind: FilterKind, val frequencyHz: Double, val q: Double=1/sqrt(2.0),
    val gainDb: Double=0.0, val sampleRateHz: Double=48000.0) {
    init {
        require(sampleRateHz.isFinite() && sampleRateHz in 32000.0..192000.0)
        require(frequencyHz.isFinite() && frequencyHz>=10 && frequencyHz<sampleRateHz*0.49)
        require(q.isFinite() && q in 0.1..20.0)
        require(gainDb.isFinite() && gainDb in -24.0..24.0 && (kind==FilterKind.PEAK || gainDb==0.0))
    }
    fun at(hz:Double):ComplexPressure? {
        if(!hz.isFinite() || hz<0 || hz>=sampleRateHz/2) return null
        val w=2*PI*frequencyHz/sampleRateHz;val c=cos(w);val alpha=sin(w)/(2*q);val a=10.0.pow(gainDb/40)
        val b0:Double;val b1:Double;val b2:Double;val a0:Double;val a1:Double;val a2:Double
        when(kind) {
            FilterKind.LOW_PASS -> {b0=(1-c)/2;b1=1-c;b2=b0;a0=1+alpha;a1=-2*c;a2=1-alpha}
            FilterKind.HIGH_PASS -> {b0=(1+c)/2;b1=-(1+c);b2=b0;a0=1+alpha;a1=-2*c;a2=1-alpha}
            FilterKind.PEAK -> {b0=1+alpha*a;b1=-2*c;b2=1-alpha*a;a0=1+alpha/a;a1=-2*c;a2=1-alpha/a}
        }
        val omega=2*PI*hz/sampleRateHz
        val nr=b0+b1*cos(omega)+b2*cos(2*omega);val ni=b1*sin(omega)+b2*sin(2*omega)
        val dr=a0+a1*cos(omega)+a2*cos(2*omega);val di=a1*sin(omega)+a2*sin(2*omega)
        val denominator=dr*dr+di*di
        return ComplexPressure((nr*dr+ni*di)/denominator,(ni*dr-nr*di)/denominator)
    }
}
class DspFilterChain(filters:List<DspFilter> = emptyList()) {
    private val saved=filters.toList()
    val filters get()=saved.toList()
    val isEmpty get()=saved.isEmpty()
    init {require(saved.size<=16)}
    fun at(hz:Double):ComplexPressure? {
        var result=ComplexPressure(1.0,0.0)
        for(filter in saved) result=result*(filter.at(hz) ?: return null)
        return result
    }
    override fun equals(other:Any?)=other is DspFilterChain && saved==other.saved
    override fun hashCode()=saved.hashCode()
    fun toJsonString()=buildJsonObject {
        put("version",1);put("filters",buildJsonArray {saved.forEach {f->add(buildJsonObject {
            put("kind",f.kind.name);put("frequencyHz",f.frequencyHz);put("q",f.q);put("gainDb",f.gainDb);put("sampleRateHz",f.sampleRateHz)
        })}})
    }.toString()
    companion object {
        fun fromJsonString(text:String):DspFilterChain {
            val root=Json.parseToJsonElement(text).jsonObject
            require(root.getValue("version").jsonPrimitive.int==1)
            val rows=root.getValue("filters").jsonArray;require(rows.size<=16)
            return DspFilterChain(rows.map {it.jsonObject.let {f->DspFilter(FilterKind.valueOf(f.getValue("kind").jsonPrimitive.content),
                f.getValue("frequencyHz").jsonPrimitive.double,f.getValue("q").jsonPrimitive.double,
                f.getValue("gainDb").jsonPrimitive.double,f.getValue("sampleRateHz").jsonPrimitive.double)}})
        }
    }
}
