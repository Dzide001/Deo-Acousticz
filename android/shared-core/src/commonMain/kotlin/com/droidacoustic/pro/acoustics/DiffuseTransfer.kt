package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.geometry.BoundaryWork
import kotlin.math.pow

/** One bounded cache-hit chunk. Charge every tone before writing any result;
 * cancellation is checked once per at most 32 cheap scalar transfers. */
internal object DiffuseTransfer {
    fun accumulate(totals:DoubleArray,indices:IntArray,powers:DoubleArray,
        absorptions:DoubleArray,distance:Double,weight:Double,work:BoundaryWork) {
        require(indices.size<=32)
        work.check(indices.size)
        var offset=0
        while(offset<indices.size) {
            val index=indices[offset]
            totals[index]+=powers[offset]*10.0.pow(-absorptions[index]*distance/10)*weight
            offset++
        }
    }
}
