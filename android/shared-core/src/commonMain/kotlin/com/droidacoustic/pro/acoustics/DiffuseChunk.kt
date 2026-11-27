package com.droidacoustic.pro.acoustics

/** Miss-only scratch for at most 32 tones. Equal immutable chains share one row;
 * a null row means identity, whereas a null row element means unavailable DSP. */
internal class DiffuseChunk(val frequencies:DoubleArray,low:Double,high:Double,chains:List<DspFilterChain>) {
    init {require(frequencies.size<=32)}
    val fractions=DoubleArray(frequencies.size) {(frequencies[it]-low)/(high-low)}
    val filters=FilterResponseRows.prepare(frequencies,chains)
}
