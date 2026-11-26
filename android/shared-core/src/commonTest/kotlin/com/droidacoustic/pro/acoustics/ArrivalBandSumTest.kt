package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.geometry.BoundaryWork
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class ArrivalBandSumTest {
    // Original analytic finite-band formula, independently evaluated over all ordered pairs.
    private fun reference(t:DoubleArray,g:IntArray,a:DoubleArray,p:DoubleArray,s:DoubleArray,f:Double,w:Double):Double {
        var power=0.0
        for(i in t.indices) for(j in t.indices) if(g[i]==g[j]) {
            val delay=t[i]-t[j];val x=PI*w*(delay-(s[i]-s[j])/(2*PI))
            power+=a[i]*a[j]*cos(2*PI*f*delay-p[i]+p[j])*(if(abs(x)<1e-8) 1.0 else sin(x)/x)
        }
        return max(0.0,power)
    }
    @Test fun directAndReflectedPhaseSlopesMatchAnalyticIntegralAcrossGroupAndDelaySpans() {
        val random=Random(934)
        for(n in listOf(16,24,64,128)) for(groups in listOf(1,4)) for(span in listOf(.001,.01,.04)) {
            val t=DoubleArray(n) {.2+span*it/(n-1)};val g=IntArray(n) {it%groups}
            val a=DoubleArray(n) {random.nextDouble(-10000.0,10000.0)}
            val p=DoubleArray(n) {random.nextDouble(-4*PI,4*PI)}
            val slope=DoubleArray(n) {random.nextDouble(-.001,.001)}
            val expected=reference(t,g,a,p,slope,8000.0,100.0)
            val actual=ArrivalBandSum(t,g).power(a,p,slope,8000.0,100.0)
            assertEquals(expected,actual,max(1e-4,expected*1e-8),"n=$n groups=$groups span=$span")
        }
    }
    @Test fun effectiveDelayControlsFastPathAndWideSlopesRetainFallback() {
        val n=64;val a=DoubleArray(n) {1.0};val p=DoubleArray(n);val t=DoubleArray(n) {.1+it*.00001}
        val s=ArrivalBandSum(t,IntArray(n))
        s.power(a,p,DoubleArray(n),8000.0,100.0);assertEquals(8L*n,s.evaluations)
        val slope=DoubleArray(n) {it*.01}
        val expected=reference(t,IntArray(n),a,p,slope,8000.0,100.0)
        assertEquals(expected,s.power(a,p,slope,8000.0,100.0),1e-9)
        assertEquals(n.toLong()*(n-1)/2,s.evaluations)
    }
    @Test fun nullsCommonDelaysAndFrozenGeometryRemainStable() {
        val t=DoubleArray(64) {.1+(it%32)*.00002};val g=IntArray(64)
        val a=DoubleArray(64) {if(it<32) 10000.0 else -10000.0}
        val p=DoubleArray(64) {(it%32)*.02};val slope=DoubleArray(64) {(it%32)*.0001}
        val frozen=ArrivalBandSum(t,g)
        assertEquals(0.0,frozen.power(a,p,slope,8000.0,100.0),1e-17)
        a[0]+=.01
        val expected=frozen.power(a,p,slope,8000.0,100.0)
        assertEquals(.0001,expected,1e-10)
        for(i in t.indices) t[i]+=1000.0
        assertEquals(expected,ArrivalBandSum(t,g).power(a,p,slope,8000.0,100.0),1e-10)
        t.fill(0.0);g.fill(63)
        assertEquals(expected,frozen.power(a,p,slope,8000.0,100.0),1e-10)
    }
    @Test fun workAccountingCancellationAndOverflowRemainEnforced() {
        val sum=ArrivalBandSum(DoubleArray(64) {it*.00001},IntArray(64))
        val a=DoubleArray(64) {1.0};val p=DoubleArray(64)
        var units=0
        sum.power(a,p,p,8000.0,100.0,checkWork={units+=it})
        assertEquals(512,units)
        val limited=BoundaryWork(limit=511)
        assertFailsWith<IllegalArgumentException> {sum.power(a,p,p,8000.0,100.0,checkWork=limited::check)}
        val cancelled=BoundaryWork({error("cancelled")})
        assertFailsWith<IllegalStateException> {sum.power(a,p,p,8000.0,100.0,checkWork=cancelled::check)}
        val large=BoundaryWork(limit=Int.MAX_VALUE);large.check(Int.MAX_VALUE)
        assertFailsWith<IllegalArgumentException> {large.check()}
    }
}
