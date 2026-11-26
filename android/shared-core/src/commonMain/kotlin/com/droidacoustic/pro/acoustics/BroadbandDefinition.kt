package com.droidacoustic.pro.acoustics

import kotlinx.serialization.json.*

/** Existing application octave-band offsets, not a continuous-frequency meter/filter certification. */
object OctaveWeighting {
    val bands=listOf(63,125,250,500,1000,2000,4000,8000)
    val tables: Map<String,Map<Int,Float>> = mapOf(
        "Z" to bands.associateWith {0f},
        "A" to bands.zip(listOf(-26.2f,-16.1f,-8.6f,-3.2f,0f,1.2f,1f,-1.1f)).toMap(),
        "C" to bands.zip(listOf(-.8f,-.2f,0f,0f,0f,-.2f,-.8f,-3f)).toMap())
}

class BroadbandDefinition private constructor(val weighting: String, centers: List<Int>, val sourceSpectrum: BroadbandSourceSpectrum? = null) {
    val bandCentersHz=centers.toList()
    val weightingDb=bandCentersHz.associateWith {OctaveWeighting.tables.getValue(weighting).getValue(it)}
    val driveFractions=sourceSpectrum?.fractions(bandCentersHz) ?: bandCentersHz.associateWith {1.0}
    fun json(): JsonObject = buildJsonObject {
        put("weighting",weighting);put("bandwidthOct",1.0)
        put("bandCentersHz",JsonArray(bandCentersHz.map(::JsonPrimitive)))
        put("weightingDb",buildJsonObject {weightingDb.forEach {(hz,w)->put(hz.toString(),w)}})
        put("summation","independent-band-powers")
        put("driveConvention",if(sourceSpectrum==null) "fixed-total-drive-per-band; sum-independent-band-powers" else "fixed-total-mean-square-drive-over-declared-bands")
        sourceSpectrum?.let {put("sourceSpectrum",it.json());put("driveFractions",buildJsonObject {driveFractions.forEach {(hz,p)->put(hz.toString(),p)}})}
    }
    fun canonical()=json().toString()
    companion object {
        fun create(weighting: String,centers: List<Int>,widthOct: Float,actualWeights: Map<Int,Float>, sourceSpectrum: BroadbandSourceSpectrum? = null): BroadbandDefinition {
            require(weighting in OctaveWeighting.tables && widthOct==1f) {"Broadband capture requires octave bands and explicit A/C/Z weighting"}
            require(centers.size in 2..8 && centers.distinct().size==centers.size && centers==centers.sorted() && centers.all {it in OctaveWeighting.bands}) {"Unsupported, duplicate or unordered broadband bands"}
            val result=BroadbandDefinition(weighting,centers,sourceSpectrum?.takeIf {it.mode!="PER_BAND_REFERENCE"})
            require(centers.all {actualWeights[it]==result.weightingDb[it]}) {"Weighting label and actual coefficients disagree"}
            return result
        }
        fun parse(value: JsonObject): BroadbandDefinition {
            val spectrum=value["sourceSpectrum"]?.let {BroadbandSourceSpectrum.parse(it.jsonObject)}
            require(spectrum==null || spectrum.mode!="PER_BAND_REFERENCE") {"Legacy drive must use its original definition"}
            require(value.keys==(setOf("weighting","bandwidthOct","bandCentersHz","weightingDb","summation","driveConvention") + if(spectrum==null) emptySet() else setOf("sourceSpectrum","driveFractions"))) {"Unsupported broadband definition fields"}
            require(value.getValue("summation").jsonPrimitive.content=="independent-band-powers" &&
                value.getValue("driveConvention").jsonPrimitive.content==if(spectrum==null) "fixed-total-drive-per-band; sum-independent-band-powers" else "fixed-total-mean-square-drive-over-declared-bands") {"Unsupported broadband power/drive convention"}
            return create(value.getValue("weighting").jsonPrimitive.content,value.getValue("bandCentersHz").jsonArray.map {it.jsonPrimitive.int},
                value.getValue("bandwidthOct").jsonPrimitive.float,value.getValue("weightingDb").jsonObject.map {it.key.toInt() to it.value.jsonPrimitive.float}.toMap(),spectrum).also {
                if(spectrum!=null) {
                    val declared=value.getValue("driveFractions").jsonObject
                    require(declared.keys==it.driveFractions.keys.map(Int::toString).toSet() && it.driveFractions.all {(hz,p)->
                        val v=declared.getValue(hz.toString()).jsonPrimitive.double
                        v.isFinite() && (if(p==0.0) v==0.0 else kotlin.math.abs(v-p)<=1e-12*p)
                    }) {"Declared drive fractions disagree with source spectrum"}
                }
                require(value.getValue("weightingDb").jsonObject.size==it.bandCentersHz.size) {"Unexpected weighting bands"}
            }
        }
    }
}
