package com.droidacoustic.pro.acoustics

import kotlinx.serialization.json.*

/** Relative mean-square input drive per source; not a claim of electrical watts or calibration. */
enum class BroadbandDrive(val label:String,val shortLabel:String,val convention:String) {
    TOTAL_EQUAL_BAND_POWER("Fixed total","total drive","fixed-total-drive-equal-octave-power"),
    PER_BAND_REFERENCE("Per-band reference","per-band drive","sum-eight-independent-octave-powers");

    fun fractions(bands:List<Int>):Map<Int,Double> =
        if(bands.size==1) emptyMap() else BroadbandSourceSpectrum.preset(name).fractions(bands)
    companion object {
        val DEFAULT=TOTAL_EQUAL_BAND_POWER
        /** An absent field always means the historical convention, regardless of app defaults. */
        fun fromScene(root:JsonObject):BroadbandDrive = root["broadbandDrive"]?.let {
            valueOf(it.jsonPrimitive.content)
        } ?: PER_BAND_REFERENCE
        fun fromStoredName(mode:String?):BroadbandDrive=mode?.let {valueOf(it)} ?: PER_BAND_REFERENCE
    }
}
