package com.droidacoustic.pro.scene

import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.geometry.AcousticBoundaryRole
import com.droidacoustic.pro.traces.TraceComparisons
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReferenceTraceIntegrationTest {
    private val scheduler=TestCoroutineScheduler()
    private val dispatcher=StandardTestDispatcher(scheduler)
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup() {Dispatchers.setMain(dispatcher)}
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};scheduler.runCurrent();Dispatchers.resetMain()}
    private fun vm(runner:PredictionRunner=PredictionRunner {it.calculate()})=SceneViewModel(runner,dispatcher).also {models+=it}
    private fun scene(v:SceneViewModel=vm())=v.also {
        it.setVenueSize(8f,8f);it.setStageBoundaryRole(AcousticBoundaryRole.NONE);it.addSpeaker(0f,0f)
        it.setSignalType("SPECTRUM");it.moveListener(5f,0f)
        it.addAlignmentReceiver("FOH",10f,1.2f,0f);it.addAlignmentReceiver("Balcony",20f,4f,0f)
        it.selectTraceReceivers(listOf(1,0))
    }
    @Test fun selectedReceiversCaptureTogetherAndCompareDesignsAfterReopen() {
        val v=scene();val listener=v.listener.value
        v.captureReferenceTraces("A");scheduler.runCurrent()
        assertEquals(2,v.savedTraces.value.traces.size);assertFalse(v.referenceTraceRunning.value)
        assertEquals(listener,v.listener.value)
        v.setGain(v.speakers.value.single().id,6f);v.captureReferenceTraces("B");scheduler.runCurrent()
        assertEquals(4,v.savedTraces.value.traces.size)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(false)))
        assertEquals(listOf(1,0),fresh.referenceTraceSelection.value.ids)
        assertEquals(v.savedTraces.value.toJsonString(),fresh.savedTraces.value.toJsonString())
        val traces=fresh.savedTraces.value.traces
        for(i in 0..1) {
            assertEquals(8,traces[i].powers.size)
            TraceComparisons.compare(traces[i],traces[i+2]).bands.forEach {assertEquals(6.0,it.deltaDb!!,.001)}
        }
        assertFalse(TraceComparisons.compare(traces[0],traces[1]).compatible)
        assertTrue(v.undoScene());assertEquals(2,v.savedTraces.value.traces.size)
        assertTrue(v.redoScene());assertEquals(4,v.savedTraces.value.traces.size)
    }
    @Test fun batchUsesEachReceiversActualPositionAndHeight() {
        val v=scene();v.setSignalType("BAND");v.setBandHz(1000)
        v.captureReferenceTraces("Position check");scheduler.runCurrent()
        val captured=v.savedTraces.value.traces
        captured.forEach {trace ->
            val p=trace.metadata.receiver
            val expected=v.predictBandDb(v.speakers.value,p.x.toFloat(),p.y.toFloat(),p.z.toFloat(),1000)
            assertEquals(expected.toDouble(),10*kotlin.math.log10(trace.powers.getValue(1000)!!),.001)
        }
        assertNotEquals(captured[0].powers[1000],captured[1].powers[1000])
        v.setAlignmentReceiver("Moved",30f,7f,0f)
        v.selectTraceReceivers(listOf(1));v.captureReferenceTraces("Moved reference");scheduler.runCurrent()
        assertFalse(TraceComparisons.compare(captured[0],v.savedTraces.value.traces.last()).compatible)
    }
    @Test fun receiverDeletionPrunesSelectionButKeepsHistoricalTracesAndUndo() {
        val v=scene();v.captureReferenceTraces("A");scheduler.runCurrent()
        val history=v.savedTraces.value.toJsonString()
        v.removeAlignmentReceiver(1);assertEquals(listOf(0),v.referenceTraceSelection.value.ids)
        assertEquals(history,v.savedTraces.value.toJsonString())
        assertTrue(v.undoScene());assertEquals(listOf(1,0),v.referenceTraceSelection.value.ids)
        v.selectTraceReceivers(listOf(0));v.captureReferenceTraces("FOH only");scheduler.runCurrent()
        assertEquals(3,v.savedTraces.value.traces.size)
        assertEquals(0,v.savedTraces.value.traces.last().metadata.referenceReceiverId)
    }
    @Test fun lateResultAfterReceiverSelectionChangeIsDiscarded() {
        val release=CompletableDeferred<Unit>()
        val v=scene(vm(PredictionRunner {request ->
            if(request.receiverCount==2) withContext(NonCancellable) {release.await()}
            request.calculate()
        }))
        v.captureReferenceTraces("Old");scheduler.runCurrent()
        assertTrue(v.referenceTraceRunning.value)
        v.selectTraceReceivers(listOf(0));release.complete(Unit);scheduler.runCurrent()
        assertTrue(v.savedTraces.value.traces.isEmpty())
        v.captureReferenceTraces("New");scheduler.runCurrent()
        assertEquals(1,v.savedTraces.value.traces.size)
    }
    @Test fun invalidAndLegacySelectionsAndVariantRestoration() {
        val v=scene();assertTrue(v.captureDesignVariant("Selected receivers"))
        v.selectTraceReceivers(emptyList());v.captureReferenceTraces("Empty")
        assertTrue(v.savedTraces.value.traces.isEmpty());assertNotNull(v.referenceTraceMessage.value)
        assertTrue(v.restoreDesignVariant(0));assertEquals(listOf(1,0),v.referenceTraceSelection.value.ids)
        val before=v.exportSceneJson(false)
        val invalid=org.json.JSONObject(before).put("referenceTraceSelection",org.json.JSONObject().put("version",99))
        assertFalse(v.importSceneJson(invalid.toString()));assertEquals(before,v.exportSceneJson(false))
        val legacy=org.json.JSONObject(before);legacy.remove("referenceTraceSelection")
        assertTrue(v.importSceneJson(legacy.toString()));assertTrue(v.referenceTraceSelection.value.ids.isEmpty())
    }
}
