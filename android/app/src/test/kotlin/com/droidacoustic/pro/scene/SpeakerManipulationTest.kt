package com.droidacoustic.pro.scene

import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.geometry.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerManipulationTest {
    private val scheduler=TestCoroutineScheduler()
    private val dispatcher=StandardTestDispatcher(scheduler)
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup() {Dispatchers.setMain(dispatcher)}
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};scheduler.runCurrent();Dispatchers.resetMain()}
    private fun vm()=SceneViewModel(PredictionRunner {it.calculate()},dispatcher).also {models+=it}
    @Test fun previewNeverPersistsAndEachDragCommitsOneUndoableEditAcrossReopen() {
        val v=vm();v.addSpeaker(0f,0f)
        val id=v.speakers.value.single().id
        for(kind in SpeakerGizmoHandleKind.entries) {
            val before=v.speakers.value.single();val json=v.exportSceneJson(true)
            val transform=SpeakerGizmoTransform(before.x,before.heightM,before.z,before.panDeg,before.arrayAimDeg)
            val handle=SpeakerGizmoHandle(kind,GizmoPoint2(0f,0f),GizmoPoint2(1f,0f),10f)
            val drag=SpeakerGizmoDragSession.begin(transform,handle,GizmoPoint2(0f,0f),1f)!!.update(GizmoPoint2(20f,0f))
            val t=drag.preview
            val preview=v.previewSpeakerManipulation(id,t.x,t.y,t.z,t.panDeg,t.tiltDeg).single()
            assertEquals(before,v.speakers.value.single());assertEquals(json,v.exportSceneJson(true))
            assertNull(drag.cancel().commit());assertEquals(json,v.exportSceneJson(true))
            when(kind) {
                SpeakerGizmoHandleKind.X,SpeakerGizmoHandleKind.Y,SpeakerGizmoHandleKind.Z->v.setSpeakerPosition(id,t.x,t.y,t.z)
                SpeakerGizmoHandleKind.PAN->v.setSpeakerPan(id,t.panDeg)
                SpeakerGizmoHandleKind.TILT->v.setSpeakerArrayAim(id,t.tiltDeg)
            }
            assertEquals(preview,v.speakers.value.single())
            assertTrue(v.undoScene());assertEquals(before,v.speakers.value.single())
            assertTrue(v.redoScene());assertEquals(preview,v.speakers.value.single())
            val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
            assertEquals(v.speakers.value,fresh.speakers.value)
        }
    }
    @Test fun linkedPreviewsMatchFinalMotionInBothModes() {
        for(mode in listOf("SAME","OPPOSITE")) {
            val v=vm();v.addSpeakerSource();v.addSpeaker(1f,2f);v.addSpeakerSource();v.addSpeaker(4f,5f)
            val sources=v.speakerSources.value
            v.setSpeakerSourceLink(sources[0].id,sources[1].id)
            v.setSpeakerSourceLinkMotionMode(sources[0].id,mode)
            val sp=v.speakers.value.first()
            val preview=v.previewSpeakerManipulation(sp.id,2f,3f,4f,sp.panDeg,sp.arrayAimDeg)
            val before=v.speakers.value
            v.setSpeakerPosition(sp.id,2f,3f,4f);assertEquals(preview,v.speakers.value)
            assertTrue(v.undoScene());assertEquals(before,v.speakers.value)
            val pan=v.previewSpeakerManipulation(sp.id,sp.x,sp.heightM,sp.z,45f,sp.arrayAimDeg)
            v.setSpeakerPan(sp.id,45f);assertEquals(pan,v.speakers.value);assertTrue(v.undoScene())
            val tilt=v.previewSpeakerManipulation(sp.id,sp.x,sp.heightM,sp.z,sp.panDeg,15f)
            v.setSpeakerArrayAim(sp.id,15f);assertEquals(tilt,v.speakers.value)
        }
    }
    @Test fun sameSourceSpeakersMoveAndAimIndependentlyWithoutAnExplicitLink() {
        val v=vm();v.addSpeaker(1f,2f);v.addSpeaker(4f,5f)
        val anchor=v.speakers.value.first();val other=v.speakers.value.last()
        assertEquals(anchor.sourceId,other.sourceId)
        val preview=v.previewSpeakerManipulation(anchor.id,2f,3f,4f,20f,15f)
        assertEquals(other,preview.last())
        v.setSpeakerPosition(anchor.id,2f,3f,4f);assertEquals(other,v.speakers.value.last())
        v.setSpeakerPan(anchor.id,20f);assertEquals(other,v.speakers.value.last())
        v.setSpeakerArrayAim(anchor.id,15f);assertEquals(other,v.speakers.value.last())
        assertEquals(preview,v.speakers.value)
        assertTrue(v.undoScene());assertEquals(other,v.speakers.value.last())
        assertTrue(v.redoScene());assertEquals(preview,v.speakers.value)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)));assertEquals(preview,fresh.speakers.value)
    }
    @Test fun invalidPreviewCannotChangeGeometryOrHistory() {
        val v=vm();v.addSpeaker(2f,3f);val before=v.exportSceneJson(true);val sp=v.speakers.value.single()
        assertEquals(v.speakers.value,v.previewSpeakerManipulation(sp.id,Float.NaN,2f,3f,0f,0f))
        assertEquals(before,v.exportSceneJson(true))
    }
}
