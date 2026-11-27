package com.droidacoustic.pro.prediction

import kotlin.math.*
import kotlin.test.*

/** Generic-pattern regressions through shared source frames and complete requests. */
class DirectivityPredictionRequestTest {
    private fun level(az:Float=0f,el:Float=0f,yaw:Float=0f,tilt:Float=0f,dispersion:Boolean=true,elements:Int=1):Float {
        val a=az*PI/180;val e=el*PI/180
        return FreeFieldRequests.request(listOf(FreeFieldRequests.source(yaw=yaw,tilt=tilt,dispersion=dispersion,elements=elements)),
            listOf(ReceiverSnapshot((10*cos(e)*cos(a)).toFloat(),(10*sin(e)).toFloat(),(10*cos(e)*sin(a)).toFloat()))).calculate().levelAt(0)
    }
    @Test fun onAxisHasNoDirectivityLoss()=assertEquals(level(dispersion=false),level(),.001f)
    @Test fun horizontalHalfAngleCostsSixDb()=assertEquals(6f,level()-level(60f),.01f)
    @Test fun horizontalFalloffIsQuadratic()=assertEquals(24f,level()-level(120f),.01f)
    @Test fun rearLossIsCappedByTheActualGenericModel()=assertEquals(24f,level()-level(180f),.001f)
    @Test fun panPointsSourceAtReceiver() {
        assertTrue(level(90f,yaw=90f)-level(90f)>5f);assertEquals(level(),level(90f,yaw=90f),.001f)
    }
    @Test fun rotatingSourceAndReceiverTogetherPreservesDirectivity()=assertEquals(level(60f),level(150f,yaw=90f),.001f)
    @Test fun bypassingDispersionRemovesThePattern()=assertEquals(level(dispersion=false),level(180f,dispersion=false),.001f)
    @Test fun listenerBelowAnUntiltedBoxLosesLevel()=assertEquals(9.92f,level()-level(el=-45f),.05f)
    @Test fun mechanicalTiltPointsSourceDown()=assertEquals(level(),level(el=-45f,tilt=45f),.001f)
    @Test fun tiltingPastTheReceiverCostsLevel()=assertTrue(level(el=-45f,tilt=80f)<level(el=-45f,tilt=45f))
    @Test fun coincidentArrayElementsApplyTheirTiltedPatternOnce() {
        // Analytic control only: four coincident coherent elements isolate response application.
        assertEquals((20*log10(4.0)).toFloat(),level(el=-45f,tilt=45f,elements=4)-level(el=-45f,tilt=45f),.001f)
    }
    @Test fun panWrapsWithoutChangingThePrediction() {
        assertEquals(level(yaw=-10f),level(yaw=350f),.001f);assertEquals(level(yaw=-10f),level(yaw=710f),.001f)
    }
}
