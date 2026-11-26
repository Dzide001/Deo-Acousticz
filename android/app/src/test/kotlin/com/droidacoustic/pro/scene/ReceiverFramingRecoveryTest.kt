package com.droidacoustic.pro.scene

import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.alignment.SystemDsp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverFramingRecoveryTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
    private fun scene()=model().also {v->
        v.setSpeakerPreset("point_small");v.addSpeaker(0f,0f)
        v.addAlignmentReceiver("Balcony",15f,8f,-4f);v.selectAngularReceiver("reference:0");v.setReceiverAngularOverlayEnabled(true)
    }
    @Test fun framingIntentAndViewSurviveReopenWithoutChangingSceneAcoustics() {
        val v=scene();val revision=v.revision.value;val alignment=v.alignmentReferences.value.toJsonString()
        val s=v.speakers.value.single();val level=v.predictBandDb(v.speakers.value,10f,s.heightM,0f,1000)
        assertTrue(v.frameAngularReceiver("SECTION"));v.scaleReceiverFrame(2f)
        val focus=v.receiverCameraFocus.value!!;val target=v.receiverFrameTarget()!!
        val frame=ReceiverCameraFraming.fit(focus,target,1200,800)!!
        assertEquals(revision,v.revision.value);assertEquals(alignment,v.alignmentReferences.value.toJsonString())
        val token=v.receiverFrameToken.value;assertTrue(v.frameAngularReceiver("SECTION"));assertTrue(v.receiverFrameToken.value>token)
        val store=LocalProjectStore(folder.root);store.save("project_A",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("project_A")!!.project))
        assertEquals(focus,fresh.receiverCameraFocus.value);assertEquals(target,fresh.receiverFrameTarget())
        assertEquals(frame,ReceiverCameraFraming.fit(fresh.receiverCameraFocus.value!!,fresh.receiverFrameTarget()!!,1200,800))
        assertEquals(level,fresh.predictBandDb(fresh.speakers.value,10f,s.heightM,0f,1000),1e-5f)
        fresh.setReceiverFrameView("PLAN");assertEquals(ReceiverFrameView.PLAN,fresh.receiverCameraFocus.value!!.view)
        fresh.clearReceiverFrame();assertNull(fresh.receiverCameraFocus.value)
    }
    @Test fun removedLegacyAndInvalidTargetsCannotRestoreAnUnrelatedReceiver() {
        val v=scene();assertTrue(v.frameAngularReceiver("PERSPECTIVE"));v.removeAlignmentReceiver(0)
        assertNull(v.receiverCameraFocus.value);assertTrue(v.undoScene());assertEquals("reference:0",v.receiverCameraFocus.value!!.receiverId)
        assertTrue(v.redoScene());assertNull(v.receiverCameraFocus.value)
        val legacy=JSONObject(v.exportSceneJson()).apply {remove("receiverCameraFocus")}
        val fresh=model();assertTrue(fresh.importSceneJson(legacy.toString()));assertNull(fresh.receiverCameraFocus.value)
        legacy.put("receiverCameraFocus",JSONObject(ReceiverCameraFocus("reference:404",ReceiverFrameView.PLAN).toJsonString()))
        assertTrue(fresh.importSceneJson(legacy.toString()));assertNull(fresh.receiverCameraFocus.value)
        val before=fresh.exportSceneJson()
        legacy.getJSONObject("receiverCameraFocus").put("spanM",-1)
        assertFalse(fresh.importSceneJson(legacy.toString()));assertEquals(before,fresh.exportSceneJson())
        assertTrue(fresh.frameAngularReceiver("PLAN"));fresh.clearAll();assertNull(fresh.receiverCameraFocus.value)
    }
    @Test fun visualFramingAndInspectionDoNotInvalidateAnAdjustmentPreview() = runTest(dispatcher) {
        val v=scene();val source=v.speakers.value.single().id
        val preview=v.previewSystemAdjustment(source,SystemDsp(1f,2f,false))!!
        v.selectAngularReceiver("listener");assertTrue(v.frameAngularReceiver("PLAN"));v.scaleReceiverFrame(.5f)
        v.setReceiverAngularOverlayEnabled(false)
        assertTrue(v.applySystemAdjustment(preview))
    }
}
