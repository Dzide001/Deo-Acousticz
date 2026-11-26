package com.droidacoustic.pro.scene
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.geometry.AcousticBoundaryRole
import com.droidacoustic.pro.traces.TraceComparisons
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SavedTraceIntegrationTest {
    private val scheduler=TestCoroutineScheduler()
    private val dispatcher=StandardTestDispatcher(scheduler)
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup() {Dispatchers.setMain(dispatcher)}
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};scheduler.runCurrent();Dispatchers.resetMain()}
    private fun vm()=SceneViewModel(PredictionRunner {it.calculate()},dispatcher).also {models+=it}
    private fun scene()=vm().also {v->
        v.setStageBoundaryRole(AcousticBoundaryRole.NONE);v.setGroundReflectionEnabled(false)
        v.addSpeaker(0f,0f);v.moveListener(10f,0f);v.setSignalType("SPECTRUM")
    }
    private fun calculate(v:SceneViewModel) {v.recalculateSignal();scheduler.runCurrent()}
    @Test fun capturedDesignsRemainFrozenAndCompareRealGainChangesAfterSaveReopen() {
        val v=scene();calculate(v);assertTrue(v.captureListenerTrace("Baseline"))
        val a=v.savedTraces.value.traces.single();assertEquals(8,a.powers.size)
        v.setGain(v.speakers.value.single().id,6f)
        assertFalse(v.captureListenerTrace("Stale"))
        calculate(v);assertTrue(v.captureListenerTrace("Plus six"))
        val b=v.savedTraces.value.traces.last()
        assertNotEquals(a.metadata.inputHash,b.metadata.inputHash)
        TraceComparisons.compare(a,b).bands.forEach {assertEquals(6.0,it.deltaDb!!,1e-4)}
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(false)))
        assertEquals(v.savedTraces.value.toJsonString(),fresh.savedTraces.value.toJsonString())
        assertNull(fresh.currentTrace.value)
        fresh.removeSavedTrace(a.id);assertEquals(1,fresh.savedTraces.value.traces.size)
        assertTrue(fresh.undoScene());assertEquals(2,fresh.savedTraces.value.traces.size)
    }
    @Test fun captureRejectsEmptyAndStaleResultsAndLegacyProjectsHaveNoTraces() {
        val v=scene();assertFalse(v.captureListenerTrace("No result"))
        calculate(v);v.moveListener(12f,0f);assertFalse(v.captureListenerTrace("Wrong position"))
        calculate(v);assertTrue(v.captureListenerTrace("Valid"))
        val legacy=org.json.JSONObject(v.exportSceneJson(false));legacy.remove("savedTraces")
        assertTrue(v.importSceneJson(legacy.toString()));assertTrue(v.savedTraces.value.traces.isEmpty())
    }
    @Test fun malformedTraceArchiveDoesNotModifyOpenProject() {
        val v=scene();val before=v.exportSceneJson(false)
        val invalid=org.json.JSONObject(before).put("savedTraces",org.json.JSONObject().put("version",999))
        assertFalse(v.importSceneJson(invalid.toString()));assertEquals(before,v.exportSceneJson(false))
    }
}
