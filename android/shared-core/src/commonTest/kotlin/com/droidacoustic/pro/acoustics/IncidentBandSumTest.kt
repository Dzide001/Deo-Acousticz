package com.droidacoustic.pro.acoustics

import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.measureTime

class IncidentBandSumTest {
    private fun fixture(n:Int,span:Double,groups:Int=1):IncidentBandSum {
        val random=Random(19)
        return IncidentBandSum(n).also {s ->
            s.count=n
            for(i in 0 until n) {
                s.real[i]=random.nextDouble(-2.0,2.0);s.imaginary[i]=random.nextDouble(-2.0,2.0)
                s.time[i]=.03+span*i/(n-1);s.group[i]=i%groups
            }
        }
    }
    // Independent analytic integral of every complex cross-product.
    private fun reference(s:IncidentBandSum,f:Double,w:Double):Double {
        var result=0.0
        for(i in 0 until s.count) for(j in 0 until s.count) if(s.group[i]==s.group[j]) {
            val dt=s.time[i]-s.time[j];val x=PI*w*dt
            val sinc=if(x==0.0) 1.0 else sin(x)/x
            result+=((s.real[i]*s.real[j]+s.imaginary[i]*s.imaginary[j])*cos(2*PI*f*dt)-
                (s.imaginary[i]*s.real[j]-s.real[i]*s.imaginary[j])*sin(2*PI*f*dt))*sinc
        }
        return result.coerceAtLeast(0.0)
    }
    @Test fun largeArraysMatchAnalyticIntegralAcrossFastPathBoundaryAndIndependentGroups() {
        for(n in listOf(23,24,32,128)) for(span in listOf(0.0,.001,.009999,.01,.010001,.05)) for(groups in listOf(1,3)) {
            val s=fixture(n,span,groups);val expected=reference(s,8000.0,100.0)
            assertEquals(expected,s.power(8000.0,100.0),max(1e-9,expected*1e-8),"n=$n span=$span groups=$groups")
        }
    }
    @Test fun exactNullAndNearNullStayNonnegativeAndCommonDelayCannotChangeEnergy() {
        val s=fixture(128,.004)
        for(i in 0 until 64) {
            s.real[i+64]=-s.real[i];s.imaginary[i+64]=-s.imaginary[i];s.time[i+64]=s.time[i]
        }
        assertEquals(0.0,s.power(1000.0,100.0),1e-22)
        s.real[0]+=.000001
        val before=s.power(1000.0,100.0)
        assertTrue(before>=0);assertEquals(1e-12,before,1e-19)
        for(i in 0 until s.count) s.time[i]+=1000.0
        assertEquals(before,s.power(1000.0,100.0),1e-17)
    }
    @Test fun fastWorkGrowsLinearlyAndWideDelaySpansRetainExactFallback() {
        for(n in listOf(32,64,128,256)) {
            val s=fixture(n,.005);s.power(1000.0,100.0)
            assertEquals(8L*n,s.evaluations)
        }
        val s=fixture(128,.1);s.power(1000.0,100.0)
        assertEquals(128L*127/2,s.evaluations)
        s.count=0;assertEquals(0.0,s.power(1000.0,100.0))
    }
    @Test fun reportLargeArrayKernelTimingAlongsideAccuracy() {
        val s=fixture(256,.005)
        repeat(20) {s.power(1000.0,100.0);s.power(1000.0,100.0,analytic=true)}
        var optimized=0.0;var analytic=0.0
        val fast=measureTime {repeat(100) {optimized+=s.power(1000.0,100.0)}}
        val slow=measureTime {repeat(100) {analytic+=s.power(1000.0,100.0,analytic=true)}}
        assertEquals(analytic,optimized,abs(analytic)*1e-8)
        println("256-emitter kernel, 100 repeats: optimized=$fast analytic pairwise baseline=$slow")
    }
    @Test fun spatialEnvelopeBoundsEveryPairAndKeepsIndependentGroupsSeparate() {
        val random=Random(23);val n=128;val radius=.2
        val positions=List(n) {com.droidacoustic.pro.geometry.BoundaryPoint(random.nextDouble(2.0,8.0),random.nextDouble(-3.0,3.0),random.nextDouble(-3.0,3.0))}
        val distances=positions.map {sqrt(it.x*it.x+it.y*it.y+it.z*it.z)}
        for(groups in listOf(1,4,128)) {
            val envelope=IncidentPhaseEnvelope(n)
            for(i in positions.indices) {val p=positions[i];val d=distances[i]
                envelope.add(i%groups,p,-p.x/d,-p.y/d,-p.z/d,1/(d-radius))
            }
            val span=envelope.maximumSpan(radius)
            for(i in positions.indices) for(j in 0 until i) if(i%groups==j%groups) {
                val a=positions[i];val b=positions[j];val da=distances[i];val db=distances[j]
                val gradient=sqrt((a.x/da-b.x/db).pow(2)+(a.y/da-b.y/db).pow(2)+(a.z/da-b.z/db).pow(2))
                val pair=2*radius*(gradient+radius*(1/(da-radius)+1/(db-radius)))
                assertTrue(span+1e-12>=pair)
            }
            assertEquals(n,envelope.additions)
            if(groups==n) assertEquals(0.0,span)
            envelope.clear();assertEquals(0.0,envelope.maximumSpan(radius));assertEquals(0,envelope.additions)
            repeat(n) {envelope.add(0,positions[0],1.0,0.0,0.0,1.0)}
            assertEquals(0.0,envelope.maximumSpan(radius))
        }
    }

    @Test fun reusedPrimitiveGroupsCannotRetainRemovedOrReassignedEmitters() {
        val s=fixture(128,.005,4)
        s.power(1000.0,100.0)
        s.count=29
        for(i in 0 until s.count) s.group[i]=if(i==0) 127 else 7
        assertEquals(reference(s,1000.0,100.0),s.power(1000.0,100.0),1e-8)
        s.count=1;s.group[0]=0
        assertEquals(s.real[0]*s.real[0]+s.imaginary[0]*s.imaginary[0],s.power(1000.0,100.0),1e-12)
        s.count=0;assertEquals(0.0,s.power(1000.0,100.0))
    }

    @Test fun exactToneUsesLinearWorkForEveryGroupSizeAndMatchesPairReference() {
        for(n in listOf(1,2,16,32,128)) {
            val s=fixture(max(2,n),.05,3);s.count=n
            val expected=reference(s,1000.0,0.0)
            assertEquals(expected,s.power(1000.0,0.0),max(1e-10,expected*1e-10))
            assertEquals(n.toLong(),s.evaluations)
        }
    }

}
