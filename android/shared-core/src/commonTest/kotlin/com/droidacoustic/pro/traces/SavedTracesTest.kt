package com.droidacoustic.pro.traces
import com.droidacoustic.pro.geometry.BoundaryPoint
import com.droidacoustic.pro.prediction.*
import kotlin.test.*

class SavedTracesTest {
    private val meta=TraceMetadata(BoundaryPoint(10.0,1.2,0.0),1f,20f,50f,TraceComparisons.fingerprint("scene"),1234)
    @Test fun captureOwnsDataAndPreservesMissingAndZeroEnergyThroughReopen() {
        val powers=mutableMapOf<Int,Double?>(125 to 100.0,250 to null,500 to 0.0)
        val saved=SavedTraces.empty().capture("A",meta,powers)
        powers[125]=1.0;powers.clear()
        val reopened=SavedTraces.fromJsonString(saved.toJsonString())
        assertEquals(saved.toJsonString(),reopened.toJsonString())
        assertEquals(100.0,reopened.traces.single().powers[125])
        assertNull(reopened.traces.single().powers[250])
        assertEquals(0.0,reopened.traces.single().powers[500])
    }
    @Test fun comparisonUsesCandidateMinusBaselineAndNeverFabricatesMissingDifferences() {
        val a=SavedTrace(0,"A",meta,mapOf(125 to 100.0,250 to null,500 to 0.0))
        val b=SavedTrace(1,"B",meta,mapOf(125 to 1000.0,250 to 10.0,500 to 1.0,1000 to 10.0))
        val diff=TraceComparisons.compare(a,b)
        assertTrue(diff.compatible);assertEquals(10.0,diff.bands[0].deltaDb!!,1e-9)
        assertTrue(diff.bands.drop(1).all {it.deltaDb==null})
        assertEquals(-10.0,TraceComparisons.compare(b,a).bands[0].deltaDb!!,1e-9)
    }
    @Test fun incompatibleReceiverBandwidthOrConventionsAreRejected() {
        val a=SavedTrace(0,"A",meta,mapOf(1000 to 1.0))
        for(m in listOf(meta.copy(receiver=BoundaryPoint(11.0,1.2,0.0)),meta.copy(bandwidthOct=1f/3),meta.copy(solver="other")))
            assertFalse(TraceComparisons.compare(a,SavedTrace(1,"B",m,mapOf(1000 to 1.0))).compatible)
        assertTrue(TraceComparisons.compare(a,SavedTrace(1,"B",meta.copy(temperatureC=30f),mapOf(1000 to 1.0))).compatible)
    }
    @Test fun outputPowersAreDefensiveAndUnknownDataRemainsUnavailable() {
        val values=doubleArrayOf(10.0,Double.NaN,0.0)
        val out=PredictionOutput(PredictionTicket(1,1,PredictionChannel.LISTENER),FloatArray(3),emptyArray(),bandPowers=mapOf(1000 to values))
        values[0]=999.0
        assertEquals(10.0,out.bandPowersAt(0)[1000]);assertNull(out.bandPowersAt(1)[1000]);assertEquals(0.0,out.bandPowersAt(2)[1000])
    }
    @Test fun invalidDataAndUnsupportedVersionsFailExplicitly() {
        assertFails {SavedTrace(0,"",meta,mapOf(1000 to 1.0))}
        assertFails {SavedTrace(0,"A",meta,mapOf(1000 to Double.NaN))}
        assertFails {SavedTraces.fromJsonString("{\"version\":2}")}
        val saved=SavedTraces.empty().capture("A",meta,mapOf(1000 to 1.0)).remove(0).capture("B",meta,mapOf(1000 to 1.0))
        assertEquals(1,saved.traces.single().id)
    }
}
