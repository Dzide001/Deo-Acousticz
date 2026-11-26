package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.OctaveWeighting
import kotlin.test.*

/** Catalogue compatibility only; these are representative defaults, not product measurements. */
class SharedSurfaceMaterialTest {
    @Test fun carpetRetainsItsLegacyBandValues() {
        assertEquals(.02f,SurfaceMaterial.CARPET.alphaAt(125),.001f)
        assertEquals(.37f,SurfaceMaterial.CARPET.alphaAt(1000),.001f)
        assertEquals(.65f,SurfaceMaterial.CARPET.alphaAt(4000),.001f)
    }
    @Test fun gypsumRetainsItsLegacyBandValues() {
        assertEquals(.29f,SurfaceMaterial.GYPSUM.alphaAt(125),.001f)
        assertEquals(.04f,SurfaceMaterial.GYPSUM.alphaAt(1000),.001f)
    }
    @Test fun endpointHoldPreservesOldGroundPredictions() {
        for(m in SurfaceMaterial.CATALOGUE) {
            assertEquals(m.alphaAt(125),m.alphaAt(63),.0001f)
            assertEquals(m.alphaAt(4000),m.alphaAt(8000),.0001f)
        }
    }
    @Test fun catalogueCoefficientsStayInTheLegacySupportedRange() {
        for(m in SurfaceMaterial.CATALOGUE) for(hz in OctaveWeighting.bands) assertTrue(m.alphaAt(hz) in .01f..1f)
    }
    @Test fun customFlatCurvesRemainFlatAcrossSupportedBands() {
        val material=SurfaceMaterial.flat(.42f)
        for(hz in OctaveWeighting.bands) assertEquals(.42f,material.alphaAt(hz),.0001f)
        assertEquals(.42f,material.averageAlpha(),.0001f)
    }
    @Test fun unknownMaterialIdsRemainUnavailable() {
        assertNull(SurfaceMaterial.byId("shag_pile"));assertEquals(SurfaceMaterial.CARPET,SurfaceMaterial.byId("carpet"))
    }
}
