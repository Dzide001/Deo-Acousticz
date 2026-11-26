package com.droidacoustic.pro.acoustics

import kotlinx.serialization.json.*

/** Common source excitation across emitters. Fractions allocate mean-square drive, not output SPL. */
class BroadbandSourceSpectrum private constructor(val mode: String, powers: Map<Int,Double>) {
    private val powers=powers.toMap()
    fun json(): JsonObject=buildJsonObject {
        put("mode",mode)
        if(mode=="TOTAL_CUSTOM_BAND_POWER") put("relativeBandPowers",buildJsonObject {powers.entries.sortedBy {it.key}.forEach {(hz,p)->put(hz.toString(),p)}})
    }
    fun canonical()=json().toString()
    fun fractions(bands: List<Int>): Map<Int,Double> {
        require(bands.size in 2..8 && bands.distinct().size==bands.size && bands.all {it in OctaveWeighting.bands}) {"Unsupported spectrum band set"}
        val raw=when(mode) {
            "PER_BAND_REFERENCE" -> return bands.associateWith {1.0}
            "TOTAL_EQUAL_BAND_POWER" -> bands.associateWith {1.0}
            "TOTAL_FLAT_PSD" -> bands.associateWith {hz -> Bands.segments(hz,1f).sumOf {it.widthHz}}
            "TOTAL_CUSTOM_BAND_POWER" -> {require(powers.keys==bands.toSet()) {"Custom spectrum must declare every actual band"};powers}
            else -> error("Unsupported source spectrum")
        }
        val maximum=raw.values.maxOrNull() ?: 0.0
        require(maximum.isFinite() && maximum>0 && raw.values.all {it.isFinite() && it>=0}) {"Spectrum must contain finite nonnegative powers and positive total drive"}
        val scaled=raw.mapValues {it.value/maximum};val sum=scaled.values.sum()
        return bands.associateWith {hz -> (scaled.getValue(hz)/sum).also {require(raw.getValue(hz)==0.0 || it>0) {"Spectrum dynamic range underflows a positive band"}}}
    }
    companion object {
        val modes=listOf("PER_BAND_REFERENCE","TOTAL_EQUAL_BAND_POWER","TOTAL_FLAT_PSD","TOTAL_CUSTOM_BAND_POWER")
        fun preset(mode: String): BroadbandSourceSpectrum {require(mode in modes && mode!="TOTAL_CUSTOM_BAND_POWER");return BroadbandSourceSpectrum(mode,emptyMap())}
        fun custom(powers: Map<Int,Double>): BroadbandSourceSpectrum {
            require(powers.isNotEmpty() && powers.keys.all {it in OctaveWeighting.bands} && powers.values.all {it.isFinite() && it>=0} && powers.values.any {it>0}) {"Invalid custom spectrum"}
            return BroadbandSourceSpectrum("TOTAL_CUSTOM_BAND_POWER",powers)
        }
        fun parse(value: JsonObject): BroadbandSourceSpectrum {
            val mode=value.getValue("mode").jsonPrimitive.content
            require(value.keys==if(mode=="TOTAL_CUSTOM_BAND_POWER") setOf("mode","relativeBandPowers") else setOf("mode")) {"Unexpected spectrum fields"}
            return if(mode=="TOTAL_CUSTOM_BAND_POWER") custom(value.getValue("relativeBandPowers").jsonObject.map {it.key.toInt() to it.value.jsonPrimitive.double}.toMap()) else preset(mode)
        }
        fun parseJson(text: String): BroadbandSourceSpectrum {require(text.length<=8192);return parse(Json.parseToJsonElement(text).jsonObject)}
    }
}
