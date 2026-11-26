package com.droidacoustic.pro.acoustics

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The air between a loudspeaker and a listener.
 *
 * Absorption follows ISO 9613-1 exactly - the closed-form model built on the
 * relaxation of oxygen and nitrogen - rather than a table of per-band guesses.
 * It matters more than any other single term at distance: at 20 C and 50 %
 * humidity, 8 kHz loses about a tenth of a decibel per metre, so a 60 m throw
 * costs 6 dB on top of spreading. The dependence on temperature and humidity is
 * strong and not monotonic - dry air can absorb several times more than humid
 * air at the same temperature - which is why this cannot be approximated by
 * scaling a fixed curve.
 */
class Atmosphere(
    val temperatureC: Float = 20f,
    val humidityPct: Float = 50f,
    val pressureKPa: Float = 101.325f
) {
    /** Speed of sound in dry air at this temperature, m/s. */
    val speedOfSound: Double = 331.3 * sqrt(1.0 + temperatureC / 273.15)

    /** Stateless evaluation is safe across concurrent prediction jobs/platforms. */
    fun absorptionDbPerMetre(frequencyHz: Double): Double =
        iso9613(frequencyHz, temperatureC.toDouble(), humidityPct.toDouble(), pressureKPa.toDouble())

    companion object {
        private const val T0 = 293.15       // reference temperature, K
        private const val T01 = 273.16      // triple point of water, K
        private const val PR = 101.325      // reference pressure, kPa

        /** ISO 9613-1:1993, equations (3) to (5) and annex B. */
        fun iso9613(frequencyHz: Double, temperatureC: Double, humidityPct: Double, pressureKPa: Double = PR): Double {
            val t = temperatureC + 273.15
            val pRel = pressureKPa / PR
            val tRel = t / T0

            // Molar concentration of water vapour, in percent.
            val psatRel = 10.0.pow(-6.8346 * (T01 / t).pow(1.261) + 4.6151)
            val h = humidityPct.coerceIn(0.0, 100.0) * psatRel / pRel

            // Relaxation frequencies of oxygen and nitrogen.
            val frO = pRel * (24.0 + 4.04e4 * h * (0.02 + h) / (0.391 + h))
            val frN = pRel * tRel.pow(-0.5) *
                (9.0 + 280.0 * h * exp(-4.170 * (tRel.pow(-1.0 / 3.0) - 1.0)))

            val f2 = frequencyHz * frequencyHz
            return 8.686 * f2 * (
                1.84e-11 / pRel * sqrt(tRel) +
                    tRel.pow(-2.5) * (
                        0.01275 * exp(-2239.1 / t) / (frO + f2 / frO) +
                            0.1068 * exp(-3352.0 / t) / (frN + f2 / frN)
                        )
                )
        }
    }
}
