package com.droidacoustic.pro.prediction

import kotlin.test.*

/** Migrated from the retired Android element helper to the production request path. */
class DspPredictionRequestTest {
    @Test fun oneMetreRetainsReferenceLevelWithAirLoss()=assertEquals(100f,FreeFieldRequests.level(1f),.05f)
    @Test fun doublingDistanceIncludesSixDecibelsAndAir() {
        val drop=FreeFieldRequests.level(4f)-FreeFieldRequests.level(8f)
        assertTrue(drop in 6.02f..6.3f)
    }
    @Test fun tenfoldDistanceCostsTwentyDecibelsPlusAir() {
        assertTrue(FreeFieldRequests.level(1f)-FreeFieldRequests.level(10f) in 20f..20.5f)
    }
    @Test fun geometricDoublingsRemainScaleFree() {
        for(d in listOf(2f,8f,32f)) assertEquals(6.02f,FreeFieldRequests.level(d,63)-FreeFieldRequests.level(d*2,63),.1f)
    }
    @Test fun sensitivityPassesThrough()=assertEquals(9f,FreeFieldRequests.level(sensitivity=105f)-FreeFieldRequests.level(sensitivity=96f),.001f)
    @Test fun relativeGainPassesThrough() {
        val flat=FreeFieldRequests.level()
        for(gain in listOf(4.5f,-7f)) assertEquals(gain,FreeFieldRequests.level(gain=gain)-flat,.001f)
    }
    @Test fun analysedBandGetsItsEqEntry()=assertEquals(6f,FreeFieldRequests.level(eq=mapOf(1000 to 6f))-FreeFieldRequests.level(),.001f)
    @Test fun otherBandsDoNotLeakIntoSelectedBand()=assertEquals(FreeFieldRequests.level(),FreeFieldRequests.level(eq=mapOf(2000 to 6f,125 to -6f)),.0001f)
    @Test fun everyBandReadsItsOwnEntry() {
        val curve=FreeFieldRequests.octaveBands.zip(listOf(-6f,-3f,0f,2f,4f,6f,-4f,-6f)).toMap()
        for(band in FreeFieldRequests.octaveBands) assertEquals(curve.getValue(band),FreeFieldRequests.level(band=band,eq=curve)-FreeFieldRequests.level(band=band),.001f)
    }
    @Test fun absentEqEntriesAreNeutral()=assertEquals(FreeFieldRequests.level(band=4000),FreeFieldRequests.level(band=4000,eq=mapOf(1000 to 6f)),.0001f)
    @Test fun gainAndEqStack()=assertEquals(9f,FreeFieldRequests.level(gain=3f,eq=mapOf(1000 to 6f))-FreeFieldRequests.level(),.001f)
    @Test fun sourceCoincidenceUsesTheProductionDistanceGuard() {
        val near=FreeFieldRequests.level(.001f);assertTrue(near.isFinite())
        assertEquals(FreeFieldRequests.level(.1f),near,0f)
    }
    @Test fun everyBandFallsMonotonicallyWithDistance() {
        for(band in FreeFieldRequests.octaveBands) {
            val levels=listOf(1f,2f,5f,12f,30f,80f,200f).map {FreeFieldRequests.level(it,band)}
            assertTrue(levels.zipWithNext().all {(a,b)->a>b},"$band Hz")
        }
    }
    @Test fun polarityAndDelayReachActualCorrelatedSummation() {
        val r=listOf(ReceiverSnapshot(12f,0f,0f));val first=FreeFieldRequests.source()
        val single=FreeFieldRequests.request(listOf(first),r).calculate().totalPowerAt(0)
        val aligned=FreeFieldRequests.request(listOf(first,FreeFieldRequests.source(id=1)),r).calculate().totalPowerAt(0)
        val inverted=FreeFieldRequests.request(listOf(first,FreeFieldRequests.source(inverted=true,id=1)),r).calculate().totalPowerAt(0)
        val delayed=FreeFieldRequests.request(listOf(first,FreeFieldRequests.source(delay=.0005,id=1)),r).calculate().totalPowerAt(0)
        assertEquals(single*4,aligned,single*1e-9);assertTrue(inverted<=1e-10)
        assertTrue(delayed>0 && delayed<single,"Finite-band delay must retain bandwidth-dependent residual")
    }
}
