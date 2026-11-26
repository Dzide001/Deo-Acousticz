package com.droidacoustic.pro.scene

import com.droidacoustic.pro.prediction.PredictionModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PredictionModelCleanupTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<SceneViewModel>()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() {
        models.forEach { it.cancelAnalysisJobs() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }
    private fun model() = SceneViewModel(predictionDispatcher = dispatcher).also { models += it }

    @Test fun predictionsDoNotPublishGuessedRoomMetricsOrLegacyReflections() {
        val v = model()
        v.addSpeaker(0f, 0f)
        v.recalculateSignal()
        dispatcher.scheduler.runCurrent()
        assertNotNull(v.combinedSplDb.value)
        assertNotNull(v.coverageStatistics.value)
        assertTrue(v.earlyReflections.value.isEmpty())
        assertNull(v.rt60Estimate.value)
        assertNull(v.stiEstimate.value)
        val report = JSONObject(v.exportProjectReportJson())
        listOf("rt60S", "roomVolumeM3", "sti", "stiQuality", "alconsPct").forEach {
            assertFalse("Unsupported metric leaked: $it", report.has(it))
        }
        assertEquals(2, report.getInt("version"))
        val scope = report.getJSONObject("predictionModel")
        assertEquals(1, scope.getInt("maxReflectionOrder"))
        assertFalse(scope.getBoolean("roomMetrics"))
        assertFalse(scope.getBoolean("diffraction"))
        assertEquals("pending", scope.getString("externalValidation"))
    }

    @Test fun legacySettingsReopenWithoutChangingTheActualModelOrLevels() {
        val v = model()
        v.addSpeaker(0f, 0f)
        v.setReflectionOrder(3)
        v.setSurfaceMaterial("WALL", SurfaceMaterial.CATALOGUE.last().id)
        v.recalculateSignal()
        dispatcher.scheduler.runCurrent()
        val level = v.combinedSplDb.value!!
        val restored = model()
        assertTrue(restored.importSceneJson(v.exportSceneJson(false)))
        dispatcher.scheduler.runCurrent()
        assertEquals(3, restored.reflectionOrder.value)
        assertEquals(v.roomMaterials.value, restored.roomMaterials.value)
        assertEquals(level, restored.combinedSplDb.value!!, 0f)
        restored.setReflectionOrder(1)
        restored.recalculateSignal()
        dispatcher.scheduler.runCurrent()
        assertEquals(level, restored.combinedSplDb.value!!, 0f)
        assertNull(restored.rt60Estimate.value)
        assertNull(restored.stiEstimate.value)
        val scope = JSONObject(restored.exportProjectReportJson()).getJSONObject("predictionModel")
        assertEquals(PredictionModelScope.maxReflectionOrder, scope.getInt("maxReflectionOrder"))
        assertEquals(1, JSONObject(restored.exportProjectReportJson()).getInt("reflectionOrder"))
    }

    @Test fun unvalidatedOptimizerCannotRunOrApplyDspChanges() {
        val v = model()
        v.addSpeaker(0f, 0f); v.addSpeaker(2f, 0f)
        v.setDelay(v.speakers.value.first().id, 12f)
        val before = v.dspMap.value.toMap()
        v.runOptimization()
        v.setOptimizerEnabled(true)
        dispatcher.scheduler.runCurrent()
        assertFalse(v.optimizerEnabled.value)
        assertFalse(v.optimizerIsRunning.value)
        assertNull(v.optimizerResult.value)
        assertEquals(before, v.dspMap.value)
    }
}
