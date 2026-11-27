package com.droidacoustic.pro.acoustics

import kotlin.time.TimeSource
import kotlinx.serialization.json.*

/** Opt-in, request-local worker diagnostics. Never share between simultaneous jobs.
 * Spectrum time includes miss/hit time; sampling is separate. Timed runs incur
 * clock overhead and must not be used as unprofiled performance observations. */
class DiffuseDiagnostics {
    internal enum class Stage { SAMPLING, SPECTRUM, MISS, HIT }
    private val nanos=LongArray(Stage.entries.size)
    internal var cacheHits=0L
    internal var cacheMisses=0L
    internal var hitTones=0L
    internal var missTones=0L
    internal var geometryEvaluations=0L
    internal var surfaceSamples=0L
    internal var integrationCalls=0L
    internal var baseIntervals=0L
    internal var refinementPasses=0L
    internal var quadratureTones=0L
    internal var maximumTones=0L
    internal var cachePayloadBytesPeak=0L
    internal var cachePackedEntriesPeak=0L
    internal var cacheRejectedPuts=0L
    internal var cacheValuesPeak=0L
    internal var cacheEntriesPeak=0L
    internal var cacheKeyBytesPeak=0L
    internal fun add(stage:Stage,value:Long) {nanos[stage.ordinal]+=value}
    fun toJson():String=buildJsonObject {
        put("schema","droidacoustic.diffuse-costs.v1")
        Stage.entries.forEach {put(it.name.lowercase()+"Seconds",nanos[it.ordinal]/1e9)}
        put("cacheHits",cacheHits);put("cacheMisses",cacheMisses)
        put("hitTones",hitTones);put("missTones",missTones)
        put("geometryEvaluations",geometryEvaluations);put("surfaceSamples",surfaceSamples)
        put("integrationCalls",integrationCalls);put("baseIntervals",baseIntervals)
        put("refinementPasses",refinementPasses);put("quadratureTones",quadratureTones)
        put("maximumTones",maximumTones);put("cacheValuesPeak",cacheValuesPeak)
        put("cachePayloadBytesPeak",cachePayloadBytesPeak)
        put("cachePackedEntriesPeak",cachePackedEntriesPeak);put("cacheRejectedPuts",cacheRejectedPuts)
        put("cacheEntriesPeak",cacheEntriesPeak);put("cacheKeyBytesPeak",cacheKeyBytesPeak)
    }.toString()
}

internal inline fun <T> DiffuseDiagnostics?.timed(stage:DiffuseDiagnostics.Stage,block:()->T):T {
    // Keep a single inline copy of the numerical block. A separate early-return
    // block duplicates nested kernels and can exceed the JVM's hot-method limit.
    val start=if(this==null) null else TimeSource.Monotonic.markNow()
    try {return block()} finally {
        if(start!=null) this?.add(stage,start.elapsedNow().inWholeNanoseconds)
    }
}
