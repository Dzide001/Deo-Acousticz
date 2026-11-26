package com.droidacoustic.pro.scene
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.geometry.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesignVariantIntegrationTest {
    private val scheduler=TestCoroutineScheduler()
    private val dispatcher=StandardTestDispatcher(scheduler)
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup() {Dispatchers.setMain(dispatcher)}
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};scheduler.runCurrent();Dispatchers.resetMain()}
    private fun vm()=SceneViewModel(PredictionRunner {it.calculate()},dispatcher).also {models+=it}
    private fun scene()=vm().also {v ->
        v.setStageBoundaryRole(AcousticBoundaryRole.NONE);v.addSpeaker(0f,0f);v.moveListener(10f,0f)
        v.setAlignmentReceiver("FOH",10f,1.2f,0f);v.addVenueBlock("OBSTACLE",4f,3f)
        v.setSignalType("SPECTRUM")
    }
    @Test fun captureRestoreAndUndoRestoreGeometryDspReferencesAndPredictions() {
        val v=scene();v.recalculateSignal();scheduler.runCurrent()
        val baseline=v.currentTrace.value!!.powers
        assertTrue(v.captureDesignVariant("A"))
        val speakers=v.speakers.value;val venue=v.venueGeometry.value;val listener=v.listener.value
        val refs=v.alignmentReferences.value.toJsonString()
        val id=v.speakers.value.single().id
        v.setGain(id,6f);v.setDelay(id,12f);v.setPolarity(id,true)
        v.setSpeakerPosition(id,2f,3f,4f);v.setVenueSize(60f,70f)
        v.moveListener(20f,5f);v.setAlignmentReceiver("Balcony",20f,8f,5f)
        assertTrue(v.captureDesignVariant("B"));val changed=v.speakers.value;val changedDsp=v.dspMap.value
        assertTrue(v.restoreDesignVariant(0));scheduler.runCurrent()
        assertEquals(speakers,v.speakers.value);assertEquals(venue,v.venueGeometry.value)
        assertEquals(listener,v.listener.value);assertEquals(refs,v.alignmentReferences.value.toJsonString())
        assertEquals(baseline,v.currentTrace.value!!.powers);assertEquals(2,v.designVariants.value.variants.size)
        assertTrue(v.undoScene());assertEquals(changed,v.speakers.value);assertEquals(changedDsp,v.dspMap.value)
        assertEquals(2,v.designVariants.value.variants.size)
        assertTrue(v.redoScene());assertEquals(speakers,v.speakers.value)
    }
    @Test fun coldReopenSwitchesVariantsWithoutLosingSavedTraces() {
        val v=scene();v.recalculateSignal();scheduler.runCurrent();assertTrue(v.captureListenerTrace("A response"))
        assertTrue(v.captureDesignVariant("A"));v.setDelay(v.speakers.value.single().id,20f)
        assertTrue(v.captureDesignVariant("B"));val traces=v.savedTraces.value.toJsonString()
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(false)))
        assertEquals(v.designVariants.value.toJsonString(),fresh.designVariants.value.toJsonString())
        assertTrue(fresh.restoreDesignVariant(0));assertEquals(0f,fresh.dspMap.value.values.firstOrNull()?.delayMs ?: 0f,0f)
        assertEquals(traces,fresh.savedTraces.value.toJsonString())
        assertTrue(fresh.restoreDesignVariant(1));assertEquals(20f,fresh.dspMap.value.values.single().delayMs,0f)
        assertEquals(traces,fresh.savedTraces.value.toJsonString())
        fresh.removeDesignVariant(0);assertTrue(fresh.undoScene());assertEquals(2,fresh.designVariants.value.variants.size)
    }
    @Test fun differentSpeakerDataRevisionsRecoverFromEachSnapshotAfterReopen() {
        fun data(level:Float)=ClfData("point_small",patterns=listOf(ClfPolarPattern(1000,listOf(0f),listOf(-90f,90f),arrayOf(floatArrayOf(0f,0f)))),sensitivityDbByBand=mapOf(1000 to level))
        val v=scene();assertTrue(v.importClfJsonData(ClfParser.toJson(data(93f))))
        assertTrue(v.captureDesignVariant("93 dB"))
        assertTrue(v.importClfJsonData(ClfParser.toJson(data(99f))))
        assertTrue(v.captureDesignVariant("99 dB"))
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(false)))
        assertTrue(fresh.restoreDesignVariant(0));assertEquals(93f,fresh.getClfData("point_small")!!.sensitivityDbByBand[1000]!!,0f)
        assertTrue(fresh.restoreDesignVariant(1));assertEquals(99f,fresh.getClfData("point_small")!!.sensitivityDbByBand[1000]!!,0f)
    }
    @Test fun invalidLibrariesDoNotChangeOpenDesignAndLegacyProjectsHaveNoVariants() {
        val v=scene();assertTrue(v.captureDesignVariant("A"));val before=v.exportSceneJson(false)
        assertFalse(v.restoreDesignVariant(99));assertEquals(before,v.exportSceneJson(false))
        val invalid=org.json.JSONObject(before).put("designVariants",org.json.JSONObject().put("version",99))
        assertFalse(v.importSceneJson(invalid.toString()));assertEquals(before,v.exportSceneJson(false))
        val legacy=org.json.JSONObject(before);legacy.remove("designVariants")
        assertTrue(v.importSceneJson(legacy.toString()));assertTrue(v.designVariants.value.variants.isEmpty())
    }
}
