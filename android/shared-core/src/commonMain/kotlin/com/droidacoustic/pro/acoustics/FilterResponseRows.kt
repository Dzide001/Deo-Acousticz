package com.droidacoustic.pro.acoustics

/** Bounded transient responses. Null row means identity; null element means missing.
 * Equal immutable chains share values, but no angular/source response is cached. */
internal object FilterResponseRows {
    const val MAX_FREQUENCIES=32
    val IDENTITY=ComplexPressure(1.0,0.0)
    fun prepare(frequencies:DoubleArray,chains:List<DspFilterChain>):Array<Array<ComplexPressure?>?> {
        require(frequencies.size<=MAX_FREQUENCIES)
        val rows=arrayOfNulls<Array<ComplexPressure?>>(chains.size)
        val shared=HashMap<DspFilterChain,Array<ComplexPressure?>>()
        chains.forEachIndexed {i,chain ->
            if(!chain.isEmpty) rows[i]=shared.getOrPut(chain) {Array(frequencies.size) {chain.at(frequencies[it])}}
        }
        return rows
    }
}
