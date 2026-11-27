package com.droidacoustic.pro.acoustics

import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.min

/**
 * What one loudspeaker element puts out, as a function of frequency and
 * direction.
 *
 * Directions are in the box's own frame: azimuth to the side of the acoustic
 * axis and elevation above it, both zero dead ahead.
 */
fun interface Radiator {
    fun complexBandVariationDb(lowHz:Double,highHz:Double,az:Float,el:Float,radius:Float):Float? = null
    /** Opt in only for an exact Cartesian-linear response throughout the closed interval. */
    fun supportsLinearComplexBand(lowHz:Double,highHz:Double):Boolean = false
    fun hasComplexBands():Boolean = false
    fun complexBandAt(hz:Double,azimuthDeg:Float,elevationDeg:Float):ComplexPressure? = null
    /** Low endpoint and Cartesian delta for a previously declared linear interval.
     * Default preserves custom response semantics; false denotes missing data. */
    fun complexBandBasis(lowHz:Double,highHz:Double,az:Float,el:Float,out:DoubleArray,offset:Int):Boolean {
        val a=complexBandAt(lowHz,az,el) ?: return false
        val b=complexBandAt(highHz,az,el) ?: return false
        out[offset]=a.real;out[offset+1]=a.imaginary
        out[offset+2]=b.real-a.real;out[offset+3]=b.imaginary-a.imaginary
        return true
    }
    fun complexBandKnots():Set<Int> = emptySet()
    fun complexAt(hz:Int,azimuthDeg:Float,elevationDeg:Float):ComplexPressure? = null
    fun complexFrequencies(): Set<Int> = emptySet()
    fun complexReferenceDrive():String? = null
    /** Opt in only when variationDb supplies conservative bounds for the response. */
    fun hasVariationBounds(): Boolean = false

    /** Conservative dB range inside a spherical cap; null when no range metadata exists. */
    fun variationDb(dataHz: Int, azimuthDeg: Float, elevationDeg: Float, radiusDeg: Float): Float? = null

    /**
     * Level in dB SPL at 1 m for the reference drive, in the third-octave band
     * [dataHz], toward ([azimuthDeg], [elevationDeg]).
     *
     * NaN where the box has no data - below its lowest measured band, say. The
     * engine reports the requested prediction as unavailable; unknown response
     * is not silence and must not become a partial total or a 0 dB reading.
     */
    fun levelDb(dataHz: Int, azimuthDeg: Float, elevationDeg: Float): Float
}

/**
 * A generic stand-in for a box with no measured data.
 *
 * This is not a prediction of any real loudspeaker. It is a flat on-axis
 * response with a parabolic fall-off - 6 dB down at the stated half-angles,
 * rising as the square, capped - and exists so a layout can be sketched before
 * the real data is to hand. Anything that matters should be run with a measured
 * balloon.
 */
class SyntheticRadiator(
    private val sensitivityDb: Float,
    private val horizontalHalfDeg: Float,
    private val verticalHalfDeg: (dataHz: Int) -> Float
) : Radiator {

    override fun levelDb(dataHz: Int, azimuthDeg: Float, elevationDeg: Float): Float =
        sensitivityDb - lossDb(dataHz, azimuthDeg, elevationDeg)

    fun lossDb(dataHz: Int, azimuthDeg: Float, elevationDeg: Float): Float {
        val h = abs(azimuthDeg) / horizontalHalfDeg.coerceAtLeast(1f)
        val v = abs(elevationDeg) / verticalHalfDeg(dataHz).coerceAtLeast(1f)
        return min(6f * h * h, MAX_HORIZONTAL_DB) + min(6f * v * v, MAX_VERTICAL_DB)
    }

    companion object {
        const val MAX_HORIZONTAL_DB = 24f
        const val MAX_VERTICAL_DB = 18f

        /** Horizontal -6 dB half-angle for the three generic cabinet types. */
        fun horizontalHalfDeg(modelPackageId: String): Float = when (modelPackageId) {
            "line_array" -> 45f
            "point_source" -> 60f
            else -> 50f
        }

        /** Vertical -6 dB half-angle of a single generic box. */
        fun pointSourceVerticalHalfDeg(modelPackageId: String): Float = when (modelPackageId) {
            "point_source" -> 35f
            "line_array" -> 28f
            else -> 40f
        }

        /**
         * Vertical half-angle of one generic line-array element.
         *
         * An element narrows as frequency rises - that is what its waveguide is
         * for - and the array's own narrowing then comes from summing them, not
         * from this number. It used to shrink with the element count as well,
         * which counted the array's directivity twice.
         */
        fun arrayElementVerticalHalfDeg(dataHz: Int): Float {
            val k = (dataHz / 1000f).coerceAtLeast(0.025f)
            return (50f / k.toDouble().pow(0.6).toFloat()).coerceIn(5f, 80f)
        }
    }
}
