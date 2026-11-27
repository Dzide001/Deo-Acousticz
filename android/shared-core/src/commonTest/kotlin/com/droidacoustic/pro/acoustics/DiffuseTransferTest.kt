package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.geometry.BoundaryWork
import kotlin.math.pow
import kotlin.test.*

class DiffuseTransferTest {
    @Test fun chunksChargeEveryToneAndKeepScalarOrderForInterleavedIndices() {
        val indices=IntArray(32) {it*2};val power=DoubleArray(32) {it+.5}
        val alpha=DoubleArray(64) {it*.001};val expected=DoubleArray(64) {3.0}
        for(i in indices.indices) {val n=indices[i];expected[n]+=power[i]*10.0.pow(-alpha[n]*43.5/10)*.17}
        var checks=0;val work=BoundaryWork({checks++},limit=32)
        val actual=DoubleArray(64) {3.0}
        DiffuseTransfer.accumulate(actual,indices,power,alpha,43.5,.17,work)
        assertContentEquals(expected,actual);assertEquals(1,checks)
        assertFailsWith<IllegalArgumentException> {work.check()}
        val untouched=DoubleArray(64)
        assertFailsWith<IllegalArgumentException> {
            DiffuseTransfer.accumulate(untouched,indices,power,alpha,43.5,.17,BoundaryWork(limit=31))
        }
        assertContentEquals(DoubleArray(64),untouched)
    }
    @Test fun cancellationAndOversizedChunksNeverPublishAnyTransfers() {
        val totals=DoubleArray(33)
        assertFailsWith<IllegalStateException> {
            DiffuseTransfer.accumulate(totals,intArrayOf(0),doubleArrayOf(1.0),totals,1.0,1.0,BoundaryWork({error("cancelled")}))
        }
        assertContentEquals(DoubleArray(33),totals)
        assertFailsWith<IllegalArgumentException> {
            DiffuseTransfer.accumulate(totals,IntArray(33){it},DoubleArray(33){1.0},totals,1.0,1.0,BoundaryWork())
        }
        assertContentEquals(DoubleArray(33),totals)
    }
}
