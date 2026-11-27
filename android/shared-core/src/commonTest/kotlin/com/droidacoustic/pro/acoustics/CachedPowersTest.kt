package com.droidacoustic.pro.acoustics

import kotlin.random.Random
import kotlin.test.*

class CachedPowersTest {
    @Test fun allLengthsAndRawBitPatternsRoundTripExactly() {
        val random=Random(3847)
        for(size in 0..32) repeat(80) {
            val input=DoubleArray(size) {Double.fromBits(random.nextLong())}
            val stored=CachedPowers.prepare(input)
            assertTrue(stored.payloadBytes<=size*8)
            val output=stored.unpack()
            assertContentEquals(input.map {it.toRawBits()},output.map {it.toRawBits()})
        }
        assertFailsWith<IllegalArgumentException> {CachedPowers.prepare(DoubleArray(33))}
    }
    @Test fun smoothAndConstantSpectraPackWithoutQuantization() {
        for(size in 2..32) for(input in listOf(DoubleArray(size){1.0},DoubleArray(size){1000.0+it*.001})) {
            val stored=CachedPowers.prepare(input)
            assertTrue(stored.isPacked);assertTrue(stored.payloadBytes<input.size*8)
            assertContentEquals(input.map {it.toRawBits()},stored.unpack().map {it.toRawBits()})
        }
        val special=doubleArrayOf(0.0,-0.0,Double.MIN_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE)
        assertContentEquals(special.map {it.toRawBits()},CachedPowers.prepare(special).unpack().map {it.toRawBits()})
    }
    @Test fun incompressibleFallbackOwnsItsInputAndPackingOwnsItsDecodedBuffers() {
        val input=doubleArrayOf(1.0,-1.0);val stored=CachedPowers.prepare(input)
        assertFalse(stored.isPacked);input[0]=7.0;assertEquals(1.0,stored.unpack()[0])
        val packed=CachedPowers.prepare(DoubleArray(32){1.0});val decoded=packed.unpack()
        decoded[0]=7.0;assertEquals(1.0,packed.unpack()[0])
    }
}
