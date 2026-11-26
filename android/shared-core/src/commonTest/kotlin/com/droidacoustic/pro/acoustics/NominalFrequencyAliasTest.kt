package com.droidacoustic.pro.acoustics
import kotlin.math.*
import kotlin.test.*
class NominalFrequencyAliasTest {
    @Test fun integerAliasesResolveBothWaysWithoutExtrapolating() {
        for((a,b) in listOf(31 to 32,63 to 64)) {
            assertEquals(90f,MeasuredBandTable(mapOf(a to 90f),MeasurementGrid.OCTAVE).valueAt(b){it})
            assertEquals(90f,MeasuredBandTable(mapOf(b to 90f),MeasurementGrid.OCTAVE).valueAt(a){it})
        }
        val t=MeasuredBandTable(mapOf(32 to 80f,64 to 90f),MeasurementGrid.OCTAVE)
        assertNull(t.valueAt(25){it});assertNull(t.valueAt(80){it})
    }
    @Test fun aliasEndpointsGiveIdenticalCanonicalLogFrequencyInterpolation() {
        for(low in listOf(31,32)) for(high in listOf(63,64)) {
            val t=MeasuredBandTable(mapOf(low to 80f,high to 90f),MeasurementGrid.OCTAVE)
            assertEquals((80+10*log2(40.0/31.25)).toFloat(),t.valueAt(40){it}!!,1e-5f)
            assertEquals((80+10*log2(50.0/31.25)).toFloat(),t.valueAt(50){it}!!,1e-5f)
        }
    }
    @Test fun aliasesNeverBridgeMissingCellsOrAcceptArbitraryOffGridSamples() {
        assertNull(MeasuredBandTable(mapOf(32 to 80f,125 to 90f),MeasurementGrid.OCTAVE).valueAt(50){it})
        assertNull(MeasuredBandTable(mapOf(33 to 80f,63 to 90f),MeasurementGrid.OCTAVE).valueAt(50){it})
        assertNull(MeasuredBandTable(mapOf(32 to Float.NaN,63 to 90f),MeasurementGrid.OCTAVE).valueAt(50){it})
        assertEquals(81f,MeasuredBandTable(mapOf(31 to 80f,32 to 81f),MeasurementGrid.OCTAVE).valueAt(32){it})
    }
}
