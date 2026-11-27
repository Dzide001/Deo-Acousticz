package com.droidacoustic.pro.acoustics

import kotlin.test.*

class DiffuseChunkTest {
    @Test fun identityChainsHaveNoRowsAndEqualNonemptyChainsShareResponses() {
        val chain=DspFilterChain(listOf(DspFilter(FilterKind.PEAK,1000.0,2.0,6.0)))
        val equal=DspFilterChain(chain.filters)
        val hz=doubleArrayOf(990.0,1010.0,995.0,1005.0)
        val chunk=DiffuseChunk(hz,980.0,1020.0,listOf(DspFilterChain(),chain,equal,DspFilterChain()))
        assertNull(chunk.filters[0]);assertNull(chunk.filters[3])
        assertSame(chunk.filters[1],chunk.filters[2])
        for(i in hz.indices) {
            assertEquals((hz[i]-980)/40,chunk.fractions[i])
            assertEquals(chain.at(hz[i]),chunk.filters[1]!![i])
        }
        assertTrue(chunk.filters[1]!!.any {it!!.imaginary!=0.0})
    }
    @Test fun missingFilterResponseIsNeverTreatedAsAnIdentityRow() {
        val a=DspFilterChain(listOf(DspFilter(FilterKind.LOW_PASS,1000.0,sampleRateHz=48000.0)))
        val b=DspFilterChain(listOf(DspFilter(FilterKind.LOW_PASS,1000.0,sampleRateHz=96000.0)))
        val chunk=DiffuseChunk(doubleArrayOf(1000.0,30000.0),900.0,31000.0,listOf(a,b,DspFilterChain()))
        assertNotNull(chunk.filters[0]);assertNull(chunk.filters[0]!![1])
        assertNotNull(chunk.filters[1]!![1]);assertNull(chunk.filters[2])
        assertNotSame(chunk.filters[0],chunk.filters[1])
    }
    @Test fun scratchIsBoundedToOneChunk() {
        assertFailsWith<IllegalArgumentException> {DiffuseChunk(DoubleArray(33),900.0,1100.0,emptyList())}
        val chunk=DiffuseChunk(DoubleArray(32){1000.0},900.0,1100.0,List(64){DspFilterChain()})
        assertTrue(chunk.filters.all {it==null});assertEquals(32,chunk.fractions.size)
    }
}
