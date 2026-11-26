package com.droidacoustic.pro.scene

import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.geometry.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CoverageComparisonIntegrationTest {
    private val scheduler=TestCoroutineScheduler()
    private val dispatcher=StandardTestDispatcher(scheduler)
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup() {Dispatchers.setMain(dispatcher)}
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};scheduler.runCurrent();Dispatchers.resetMain()}
    private fun vm()=SceneViewModel(PredictionRunner {it.calculate()},dispatcher).also {models+=it}
    private fun scene()=vm().also {
        it.setVenueSize(8f,8f);it.setStageBoundaryRole(AcousticBoundaryRole.NONE)
        it.addSpeaker(0f,0f);it.setSignalType("BAND");it.setBandHz(1000)
    }
    private fun variants(v:SceneViewModel) {
        assertTrue(v.captureDesignVariant("A"));v.setGain(v.speakers.value.single().id,6f)
        assertTrue(v.captureDesignVariant("B"));v.selectCoverageComparison(0,1)
    }
    @Test fun savedDesignsCompareWithoutChangingLiveDesignAndReopenRecomputes() {
        val v=scene();variants(v);scheduler.runCurrent()
        val before=v.exportSceneJson(false)
        v.calculateCoverageComparison();scheduler.runCurrent()
        assertNull(v.coverageComparisonError.value)
        val result=v.coverageDifference.value!!
        repeat(result.geometry.receiverCount) {assertEquals(6f,result.differenceAt(it),.001f)}
        assertEquals(before,v.exportSceneJson(false))
        val fresh=vm();assertTrue(fresh.importSceneJson(before));scheduler.runCurrent()
        assertNull(fresh.coverageDifference.value)
        assertEquals(v.coverageComparisonSelection.value,fresh.coverageComparisonSelection.value)
        fresh.calculateCoverageComparison();scheduler.runCurrent()
        assertEquals(result.statistics.overall.meanDb!!,fresh.coverageDifference.value!!.statistics.overall.meanDb!!,1e-8)
        assertTrue(fresh.restoreDesignVariant(0));assertNotNull(fresh.coverageComparisonSelection.value)
        fresh.removeDesignVariant(0);assertNull(fresh.coverageComparisonSelection.value)
        assertTrue(fresh.undoScene());assertNotNull(fresh.coverageComparisonSelection.value)
    }
    @Test fun incompatibleVenueAndAnalysisAreRejectedWithoutRestoration() {
        val v=scene();assertTrue(v.captureDesignVariant("A"));v.setVenueSize(10f,8f)
        assertTrue(v.captureDesignVariant("Different floor"));v.selectCoverageComparison(0,1)
        v.calculateCoverageComparison();scheduler.runCurrent()
        assertNull(v.coverageDifference.value);assertTrue(v.coverageComparisonError.value!!.contains("geometry"))
        v.setVenueSize(8f,8f);v.setBandHz(500);assertTrue(v.captureDesignVariant("Different band"))
        v.selectCoverageComparison(0,2);v.calculateCoverageComparison();scheduler.runCurrent()
        assertNull(v.coverageDifference.value);assertTrue(v.coverageComparisonError.value!!.contains("analysis"))
    }
    @Test fun reverseIdenticalAndSelectionChangesCannotPublishOldMap() {
        val v=scene();variants(v);scheduler.runCurrent()
        v.calculateCoverageComparison();v.selectCoverageComparison(1,0);scheduler.runCurrent()
        assertNull(v.coverageDifference.value)
        v.calculateCoverageComparison();scheduler.runCurrent()
        assertEquals(-6.0,v.coverageDifference.value!!.statistics.overall.meanDb!!,.001)
        v.selectCoverageComparison(1,1);v.calculateCoverageComparison();scheduler.runCurrent()
        assertEquals(0.0,v.coverageDifference.value!!.statistics.overall.meanDb!!,.001)
    }
    @Test fun savedDesignComparisonWorksWithAnEmptyOpenDesign() {
        val v=scene();variants(v)
        val current=org.json.JSONObject(v.exportSceneJson(false)).put("speakers",org.json.JSONArray())
        assertTrue(v.importSceneJson(current.toString()))
        v.calculateCoverageComparison();scheduler.runCurrent()
        assertNotNull(v.coverageDifference.value)
        assertTrue(v.speakers.value.isEmpty())
    }
    @Test fun missingMeasuredDataStaysUnavailableAfterReopen() {
        val v=scene()
        val json=org.json.JSONObject(v.exportSceneJson(false))
        json.getJSONArray("speakers").getJSONObject(0).put("requiresMeasuredData",true).put("presetId","missing-model")
        assertTrue(v.importSceneJson(json.toString()));variants(v)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(false)))
        fresh.calculateCoverageComparison();scheduler.runCurrent()
        assertNull(fresh.coverageComparisonError.value)
        val result=fresh.coverageDifference.value!!
        repeat(result.geometry.receiverCount) {assertTrue(result.differenceAt(it).isNaN())}
        assertEquals(0.0,result.statistics.overall.validAreaM2,0.0)
    }
    @Test fun bandwidthAndBroadbandWeightingMismatchAreRejected() {
        val v=scene();assertTrue(v.captureDesignVariant("Narrow"))
        v.setSignalBandwidthOct(1f);assertTrue(v.captureDesignVariant("Wide"))
        v.selectCoverageComparison(0,1);v.calculateCoverageComparison();scheduler.runCurrent()
        assertNull(v.coverageDifference.value);assertTrue(v.coverageComparisonError.value!!.contains("analysis"))
        v.setSignalType("SPECTRUM");v.setWeighting("A");assertTrue(v.captureDesignVariant("A-weighted"))
        v.setWeighting("C");assertTrue(v.captureDesignVariant("C-weighted"))
        v.selectCoverageComparison(2,3);v.calculateCoverageComparison();scheduler.runCurrent()
        assertNull(v.coverageDifference.value);assertTrue(v.coverageComparisonError.value!!.contains("analysis"))
    }
    @Test fun corruptedSelectionRejectsImportAndLegacyLoadsEmpty() {
        val v=scene();variants(v);val before=v.exportSceneJson(false)
        val invalid=org.json.JSONObject(before).put("coverageComparison",org.json.JSONObject().put("version",99))
        assertFalse(v.importSceneJson(invalid.toString()));assertEquals(before,v.exportSceneJson(false))
        val legacy=org.json.JSONObject(before);legacy.remove("coverageComparison")
        assertTrue(v.importSceneJson(legacy.toString()));assertNull(v.coverageComparisonSelection.value)
    }
}
