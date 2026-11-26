package com.droidacoustic.pro.acoustics

import kotlin.math.*

/** Narrowband pressure, with exp(+j omega t) propagation convention throughout. */
data class ComplexPressure(val real: Double, val imaginary: Double) {
    val power: Double get() = real * real + imaginary * imaginary
    val levelDb: Float get() = (10.0 * log10(power.coerceAtLeast(1e-18))).toFloat()
    operator fun times(other: ComplexPressure) = ComplexPressure(real*other.real-imaginary*other.imaginary,real*other.imaginary+imaginary*other.real)
    operator fun plus(other: ComplexPressure) = ComplexPressure(real + other.real, imaginary + other.imaginary)
    fun gain(db: Double): ComplexPressure {
        val a = 10.0.pow(db / 20.0)
        return ComplexPressure(real * a, imaginary * a)
    }
    companion object {
        val ZERO = ComplexPressure(0.0, 0.0)
        fun arrival(levelDb: Float, timeS: Double, hz: Double, inverted: Boolean = false): ComplexPressure {
            val phase = 2.0 * PI * hz * timeS
            val amplitude = 10.0.pow(levelDb / 20.0) * if (inverted) -1.0 else 1.0
            return ComplexPressure(amplitude * cos(phase), amplitude * sin(phase))
        }
    }
}

/** Retains the phase of every physical emitter; no centroid phase reconstruction. */
fun tonePressure(sources: List<SoundSource>, x: Float, y: Float, z: Float,
                 hz: Int, air: Atmosphere): ComplexPressure {
    val direction = DoubleArray(3)
    var sum = ComplexPressure.ZERO
    for (s in sources) {
        s.toward(x, y, z, direction)
        val distance = direction[0].coerceAtLeast(DirectField.MIN_DISTANCE_M)
        val level = s.radiator.levelDb(hz, direction[1].toFloat(), direction[2].toFloat())
        if (!level.isFinite() && !s.radiator.hasComplexBands()) return ComplexPressure(Double.NaN, Double.NaN)
        val db = level + s.gainDb + s.eqDb(hz) - 20.0 * log10(distance) -
            air.absorptionDbPerMetre(hz.toDouble()) * distance
        val filter=s.filters.at(hz.toDouble()) ?: return ComplexPressure(Double.NaN,Double.NaN)
        if(s.radiator.hasComplexBands()) {
            val intrinsic=s.radiator.complexBandAt(hz.toDouble(),direction[1].toFloat(),direction[2].toFloat())
                ?: return ComplexPressure(Double.NaN,Double.NaN)
            val propagation=ComplexPressure.arrival((s.gainDb+s.eqDb(hz)-20*log10(distance)-air.absorptionDbPerMetre(hz.toDouble())*distance).toFloat(),direction[0]/air.speedOfSound+s.delayS,hz.toDouble(),s.inverted)
            sum+=intrinsic*filter*propagation
        } else sum += ComplexPressure.arrival(db.toFloat(), direction[0] / air.speedOfSound + s.delayS,
            hz.toDouble(), s.inverted)*filter
    }
    return sum
}
