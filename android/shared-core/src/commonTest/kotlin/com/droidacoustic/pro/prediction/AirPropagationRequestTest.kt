package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.Atmosphere
import kotlin.math.*
import kotlin.test.*

class AirPropagationRequestTest {
    private fun loss(distance:Float,hz:Int,humidity:Float=50f):Float {
        val level=FreeFieldRequests.request(listOf(FreeFieldRequests.source()),listOf(ReceiverSnapshot(distance,0f,0f)),hz,
            Atmosphere(20f,humidity)).calculate().levelAt(0)
        return 100f-20*log10(abs(distance).coerceAtLeast(.1f))-level
    }
    @Test fun referenceAtmosphereReachesPrediction() {
        // A narrow band retains the table's nominal-tone agreement within rounding tolerance.
        assertEquals(6.31746f,loss(60f,8000),.002f);assertEquals(.50f,loss(100f,1000,70f),.02f)
    }
    @Test fun arbitraryBandsHaveTheirOwnAbsorption()=assertTrue(loss(100f,3150)>loss(100f,1000))
    @Test fun airLossScalesWithPhysicalDistanceOnEitherSideOfSource() {
        assertEquals(2*loss(50f,4000),loss(100f,4000),.0001f)
        assertEquals(loss(50f,8000),loss(-50f,8000),.0001f)
    }
}
