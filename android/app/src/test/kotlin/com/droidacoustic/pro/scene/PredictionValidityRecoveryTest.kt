package com.droidacoustic.pro.scene

import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PredictionValidityRecoveryTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
    private fun populated(partial:Boolean=false)=model().apply {
        val e=if(partial) listOf(-10f,10f) else listOf(-90f,90f)
        assertTrue(importClfJsonData(ClfParser.toJson(ClfData("point_small",patterns=listOf(
            ClfPolarPattern(1000,listOf(0f),e,arrayOf(floatArrayOf(0f,0f)))),sensitivityDbByBand=mapOf(1000 to 90f)))))
        setSpeakerPreset("point_small");addSpeaker(0f,0f);setSpeakerPosition(speakers.value.single().id,0f,2f,0f)
        setAnalysisProfile("Fast");setBandHz(1000)
    }
    private fun calculate(v:SceneViewModel) {v.recalculateSignal();dispatcher.scheduler.runCurrent();assertEquals(PredictionState.CURRENT,v.predictionState.value)}
    @Test fun availablePartialAndUnavailableAreRecomputedOnDiskReopen() {
        for(state in PredictionAvailability.entries) {
            val v=populated(state==PredictionAvailability.PARTIAL)
            if(state==PredictionAvailability.UNAVAILABLE) v.setSignalBandwidthOct(1f)
            calculate(v);assertEquals(state,v.predictionValidity.value!!.availability)
            val store=LocalProjectStore(folder.newFolder(state.name));store.save("recovery",v.exportSceneJson(false),v::resolveProjectAsset)
            assertFalse(JSONObject(v.exportSceneJson()).has("coverageValidity"))
            val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("recovery")!!.project))
            dispatcher.scheduler.runCurrent();assertEquals(state,fresh.predictionValidity.value!!.availability)
            assertEquals(state.name,JSONObject(fresh.exportProjectReportJson()).getJSONObject("coverageValidity").getString("availability"))
            assertEquals(v.predictionValidity.value!!.toJsonString(),fresh.predictionValidity.value!!.toJsonString())
        }
    }
    @Test fun missingAssetIsUnavailableAndWarningsClearOnEditAndRecovery() {
        val v=populated();val store=LocalProjectStore(folder.root);store.save("recovery",v.exportSceneJson(false),v::resolveProjectAsset)
        folder.root.resolve("assets").listFiles()!!.forEach {assertTrue(it.delete())}
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("recovery")!!.project));dispatcher.scheduler.runCurrent()
        assertEquals(PredictionAvailability.UNAVAILABLE,fresh.predictionValidity.value!!.availability)
        assertTrue(fresh.predictionValidity.value!!.missingSourceBands().isNotEmpty())
        fresh.setBandHz(2000);assertNull(fresh.predictionValidity.value)
        assertFalse(JSONObject(fresh.exportProjectReportJson()).has("coverageValidity"))
        calculate(v);assertEquals(PredictionAvailability.AVAILABLE,v.predictionValidity.value!!.availability)
        v.setBandHz(2000);calculate(v);assertEquals(PredictionAvailability.UNAVAILABLE,v.predictionValidity.value!!.availability)
        v.setBandHz(1000);calculate(v);assertEquals(PredictionAvailability.AVAILABLE,v.predictionValidity.value!!.availability)
    }
    @Test fun listenerAvailabilityIsUsedWhenCoverageIsDisabled() {
        val v=populated();v.setSignalCoverageEnabled(false);calculate(v)
        assertEquals(PredictionAvailability.AVAILABLE,v.predictionValidity.value!!.availability)
        v.setBandHz(2000);calculate(v);assertEquals(PredictionAvailability.UNAVAILABLE,v.predictionValidity.value!!.availability)
        assertFalse(JSONObject(v.exportProjectReportJson()).has("coverageValidity"))
    }
}
