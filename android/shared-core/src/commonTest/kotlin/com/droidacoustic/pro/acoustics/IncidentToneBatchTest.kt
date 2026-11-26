package com.droidacoustic.pro.acoustics

import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class IncidentToneBatchTest {
    private fun frequencies(count:Int=32,start:Double=7900.0,width:Double=17.0):DoubleArray =
        DoubleArray(count) {j ->start+(j/8+.5)*width+width*.5*IncidentBandSum.nodes[j%8]}

    /** Independent direct phasors, no recurrence and no production grouping scratch. */
    private fun reference(f:Double,times:DoubleArray,groups:IntArray,visible:IntArray,
        real:DoubleArray,imaginary:DoubleArray):Double = visible.groupBy {groups[it]}.values.sumOf {members ->
        val origin=members.minOf {times[it]}
        var r=0.0;var im=0.0
        for(i in members) {
            val a=2*PI*f*(times[i]-origin);val c=cos(a);val s=sin(a)
            r+=real[i]*c-imaginary[i]*s;im+=real[i]*s+imaginary[i]*c
        }
        r*r+im*im
    }

    @Test fun regularAndPartialChunksMatchIndependentAndScalarToneSums() {
        val random=Random(781)
        for(n in listOf(1,2,64,128)) for(groupCount in listOf(1,3,n)) for(count in listOf(1,7,16,23,32)) {
            val times=DoubleArray(n) {.03+it*.00013}
            val groups=IntArray(n) {it%groupCount};val visible=IntArray(n) {it}
            val batch=IncidentToneBatch(n);val fs=frequencies(count)
            batch.prepare(fs,times,groups,visible)
            assertEquals(count>=16,batch.usesRecurrence)
            assertEquals((if(count>=16) 9 else count).toLong()*n,batch.phaseEvaluations)
            val scalar=IncidentBandSum(n)
            for(j in fs.indices) {
                // Frequency-varying amplitudes include explicit missing/zero directions.
                val real=DoubleArray(n) {if((it+j)%11==0) 0.0 else random.nextDouble(-2.0,2.0)}
                val imaginary=DoubleArray(n) {if((it+j)%11==0) 0.0 else random.nextDouble(-2.0,2.0)}
                scalar.count=n
                for(i in 0 until n) {
                    scalar.real[i]=real[i];scalar.imaginary[i]=imaginary[i]
                    scalar.time[i]=times[i];scalar.group[i]=groups[i]
                }
                val expected=reference(fs[j],times,groups,visible,real,imaginary)
                val tolerance=max(1e-10,expected*1e-11)
                assertEquals(expected,batch.power(j,real,imaginary),tolerance)
                assertEquals(scalar.power(fs[j],0.0),batch.power(j,real,imaginary),tolerance)
            }
        }
    }

    @Test fun irregularAndExcessivelyRoundedLatticesFallBackToDirectPhases() {
        val n=64;val visible=IntArray(n) {it};val groups=IntArray(n)
        val times=DoubleArray(n) {it*.005};val real=DoubleArray(n) {cos(it.toDouble())}
        val imaginary=DoubleArray(n) {sin(it.toDouble())};val batch=IncidentToneBatch(n)
        val irregular=frequencies().also {it[19]+=.0001}
        val rounded=frequencies().also {it[19]+=1e-8}
        for(fs in listOf(irregular,rounded)) {
            batch.prepare(fs,times,groups,visible)
            assertFalse(batch.usesRecurrence);assertEquals(32L*n,batch.phaseEvaluations)
            for(j in fs.indices) assertEquals(reference(fs[j],times,groups,visible,real,imaginary),
                batch.power(j,real,imaginary),1e-10)
        }
    }

    @Test fun cancellationsAndCommonDelayPreserveTinyEnergy() {
        val n=128;val visible=IntArray(n) {it};val groups=IntArray(n)
        val times=DoubleArray(n) {(it%64)*.0001}
        val real=DoubleArray(n) {if(it<64) 1.0 else -1.0}
        val imaginary=DoubleArray(n);val fs=frequencies();val batch=IncidentToneBatch(n)
        batch.prepare(fs,times,groups,visible)
        for(j in fs.indices) assertEquals(0.0,batch.power(j,real,imaginary),1e-25)
        real[0]+=1e-6
        for(j in fs.indices) assertEquals(1e-12,batch.power(j,real,imaginary),1e-18)
        // A large common delay must be subtracted before forming phase. Compare against
        // independent sums using the same representable delayed input times.
        val shifted=DoubleArray(n) {times[it]+1000.0}
        batch.prepare(fs,shifted,groups,visible)
        for(j in fs.indices) {
            assertEquals(1e-12,batch.power(j,real,imaginary),1e-18)
            assertEquals(reference(fs[j],shifted,groups,visible,real,imaginary),
                batch.power(j,real,imaginary),1e-18)
        }
    }

    @Test fun reusedScratchHandlesChangedVisibilityGroupsAndCancellationCallbacks() {
        val n=64;val fs=frequencies();val batch=IncidentToneBatch(n)
        val times=DoubleArray(n) {it*.0003};val groups=IntArray(n) {it%3}
        val real=DoubleArray(n) {1.0};val imaginary=DoubleArray(n)
        batch.prepare(fs,times,groups,IntArray(n) {it})
        val visible=intArrayOf(63,1,17);groups[63]=63;groups[1]=17;groups[17]=17
        batch.prepare(fs,times,groups,visible)
        var accounted=0
        for(j in fs.indices) assertEquals(reference(fs[j],times,groups,visible,real,imaginary),
            batch.power(j,real,imaginary) {accounted+=it},1e-10)
        assertEquals(fs.size*visible.size,accounted)
        assertFailsWith<IllegalStateException> {batch.prepare(fs,times,groups,visible) {error("cancelled")}}
        batch.prepare(fs,times,groups,visible)
        assertFailsWith<IllegalStateException> {batch.power(0,real,imaginary) {error("cancelled")}}
        batch.prepare(fs,times,groups,intArrayOf())
        assertEquals(0.0,batch.power(31,real,imaginary))
        assertFailsWith<IllegalArgumentException> {batch.prepare(DoubleArray(33),times,groups,visible)}
    }
}
