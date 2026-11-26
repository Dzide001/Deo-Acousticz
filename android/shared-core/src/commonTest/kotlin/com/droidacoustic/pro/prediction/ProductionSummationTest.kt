package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import kotlin.math.*
import kotlin.test.*

/** Analytical invariants at the immutable production request boundary, portable to iPad. */
class ProductionSummationTest {
    private fun source(id:Int,poses:List<ArrayGeometry.Pose> = listOf(ArrayGeometry.Pose(0f,0f,0f,0f)),
        delay:Double=0.0,inverted:Boolean=false,gain:Float=0f)=SourceSnapshot(id,0f,15f,0f,0f,poses,0f,0f,
        FrozenResponse.generic(100f,"generic",false,false),gain,delay,inverted)
    private fun power(sources:List<SourceSnapshot>,coherent:Boolean=true,width:Float=1f/12,hz:Int=1000)=PredictionRequest(
        PredictionTicket(1,1,PredictionChannel.COVERAGE),sources,listOf(ReceiverSnapshot(10f,15f,0f)),hz,listOf(hz),width,
        emptyMap(),coherent,Atmosphere(),retainBandPowers=true).calculate().totalPowerAt(0)

    @Test fun coincidentCoherentPowerIndependentPowerAndPolarityHaveExactLimits() {
        for(hz in listOf(63,1000,8000)) {
            val one=power(listOf(source(0)),hz=hz)
            assertEquals(4.0,power(listOf(source(0),source(1)),hz=hz)/one,1e-10)
            assertEquals(2.0,power(listOf(source(0),source(1)),coherent=false,hz=hz)/one,1e-10)
            assertTrue(power(listOf(source(0),source(1,inverted=true)),hz=hz)<=one*1e-12)
        }
        assertEquals(0.0,power(emptyList()),0.0)
    }
    @Test fun bandDelayFollowsTheIntegralOfPressureNotAnArbitraryCoherenceBlend() {
        for(width in listOf(1f/12,1f/3)) for(delay in listOf(.0005,.001,.04)) {
            val half=2.0.pow(width.toDouble()/2);val low=1000/half;val high=1000*half
            val b=PI*(high-low)*delay
            val expected=2+2*cos(2*PI*(low+high)/2*delay)*sin(b)/b
            val actual=power(listOf(source(0),source(1,delay=delay)),width=width)/power(listOf(source(0)),width=width)
            assertEquals(expected,actual,1e-6,"width=$width delay=$delay")
        }
    }
    @Test fun interGroupIncoherencePreservesWithinArrayCoherence() {
        // Explicit symmetric locations are equidistant from the receiver. Do not
        // use the production pose generator to manufacture the oracle geometry.
        val upper=ArrayGeometry.Pose(0f,1f,0f,0f);val lower=ArrayGeometry.Pose(0f,-1f,0f,0f)
        val single=power(listOf(source(0,listOf(upper))),coherent=false)
        assertEquals(4.0,power(listOf(source(0,listOf(upper,lower))),coherent=false)/single,1e-10)
        assertEquals(2.0,power(listOf(source(0,listOf(upper)),source(1,listOf(lower))),coherent=false)/single,1e-10)
    }
    @Test fun commonDelayPolarityAndGainHaveTheExpectedSingleArrayEffects() {
        val poses=listOf(ArrayGeometry.Pose(0f,1f,0f,0f),ArrayGeometry.Pose(0f,-1f,0f,0f))
        val baseline=power(listOf(source(0,poses)))
        assertEquals(1.0,power(listOf(source(0,poses,delay=.017,inverted=true)))/baseline,1e-10)
        assertEquals(10.0.pow(.5),power(listOf(source(0,poses,gain=5f)))/baseline,1e-5)
    }
}
