package com.droidacoustic.pro.acoustics

import kotlinx.serialization.json.*

/** Explicit total-field level difference at a frequency or after weighted broadband summation. No fitted offset or distance/phase correction. */
data class RelativeResponseNormalization(val xM: Double,val yM: Double,val zM: Double,
    val minimumReferenceLevelDb: Double, val method: String = "per-frequency-level-difference") {
    init {
        require(method in listOf("per-frequency-level-difference","weighted-broadband-level-difference")) {"Unsupported normalization method"}
        require(listOf(xM,yM,zM).all {it.isFinite()}) {"Invalid normalization receiver"}
        require(minimumReferenceLevelDb.isFinite() && minimumReferenceLevelDb in -300.0..200.0) {"Reference safety floor must be -300..200 dB"}
    }
    fun json(): JsonObject = buildJsonObject {
        put("method",method);put("xM",xM);put("yM",yM);put("zM",zM)
        put("minimumReferenceLevelDb",minimumReferenceLevelDb)
    }
    fun canonical()=json().toString()
    companion object {
        fun parse(value: JsonObject): RelativeResponseNormalization {
            require(value.keys==setOf("method","xM","yM","zM","minimumReferenceLevelDb")) {"Unsupported or missing normalization fields"}
            return RelativeResponseNormalization(value.getValue("xM").jsonPrimitive.double,value.getValue("yM").jsonPrimitive.double,
                value.getValue("zM").jsonPrimitive.double,value.getValue("minimumReferenceLevelDb").jsonPrimitive.double,value.getValue("method").jsonPrimitive.content)
        }
    }
}
