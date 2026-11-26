package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.prediction.FrozenResponse
import kotlin.test.*

class CustomDirectivityTest {
    @Test fun immutableCurvesAndBoundsSurviveCallerEditsAndMissingBands() {
        val points=mutableListOf(0f to 90f,.2f to 50f,180f to 50f)
        val bands=mutableMapOf(1000 to points)
        val radial=RadialDirectivity(359.9f,5f,bands)
        val frozen=FrozenResponse.custom(radial).radiator()
        points[0]=0f to 0f;bands.clear()
        assertEquals(90f,frozen.levelDb(1000,359.9f,5f),.001f)
        assertEquals(40f,frozen.variationDb(1000,-.1f,5f,.5f)!!,.001f)
        assertTrue(frozen.levelDb(2000,0f,0f).isNaN())
        assertNull(frozen.variationDb(2000,0f,0f,1f))
    }
    @Test fun capBoundsIncludeInteriorRingPeaksSeamsAndPoles() {
        val radial=RadialDirectivity(359f,89f,mapOf(1000 to listOf(0f to 50f,1f to 50f,1.1f to 90f,1.2f to 50f,180f to 50f)))
        val cap=AngularCap(-1f,89f,2f)
        assertEquals(AngularLevelRange(50f,90f),radial.rangeDb(1000,cap))
        assertEquals(40f,radial.variationDb(1000,0f,90f,180f)!!)
        assertEquals(50f,radial.levelDb(1000,179f,-89f),.001f)
        assertEquals(0f,radial.variationDb(1000,100f,0f,.1f)!!,.001f)
    }
    @Test fun callbackAdapterPreservesBoundsUnknownDataAndRejectsInvalidRanges() {
        val response=Radiator {_,_,_->80f}
        val custom=BoundedRadiator(response) {hz,_->if(hz==1000) AngularLevelRange(70f,90f) else null}
        assertTrue(custom.hasVariationBounds())
        assertEquals(20f,custom.variationDb(1000,0f,0f,1f))
        assertNull(custom.variationDb(2000,0f,0f,1f))
        assertEquals(80f,custom.levelDb(1000,0f,0f))
        assertFailsWith<IllegalArgumentException> {custom.variationDb(1000,0f,91f,1f)}
        for(pair in listOf(90f to 80f,Float.NaN to 90f,0f to Float.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> {AngularLevelRange(pair.first,pair.second)}
        assertFailsWith<IllegalArgumentException> {RadialDirectivity(0f,0f,mapOf(1000 to listOf(1f to 0f,180f to 0f)))}
    }
}
