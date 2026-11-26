package com.droidacoustic.pro.scene

import com.droidacoustic.pro.prediction.PredictionRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SystemAlignmentIntegrationTest {
    private val scheduler=TestCoroutineScheduler()
    private val dispatcher=StandardTestDispatcher(scheduler)
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup() {Dispatchers.setMain(dispatcher)}
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};scheduler.runCurrent();Dispatchers.resetMain()}
    private fun vm()=SceneViewModel(PredictionRunner {it.calculate()},dispatcher).also {models+=it}
    private fun scene(): SceneViewModel = vm().also {v ->
        v.setSpeakerPreset("point_small");v.addSpeaker(10f,0f);v.addSpeakerSource();v.addSpeaker(3f,0f)
        v.setAlignmentReceiver("FOH",0f,1.4f,0f);v.setAlignmentSource(v.speakers.value.first().id)
    }
    @Test fun referenceIsPinnedAndSurvivesSaveReopenAndUndo() {
        val v=scene();val original=v.alignmentReceiver.value
        v.moveListener(20f,20f)
        assertEquals(original,v.alignmentReceiver.value)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(false)))
        assertEquals(original,fresh.alignmentReceiver.value)
        assertEquals(v.alignmentSourceId.value,fresh.alignmentSourceId.value)
        assertEquals(v.alignmentComparisons(),fresh.alignmentComparisons())
        v.setAlignmentReceiver("Balcony",1f,8f,2f)
        assertTrue(v.undoScene());assertEquals(original,v.alignmentReceiver.value)
    }
    @Test fun applyingDelayRecomputesAfterMovementAndCanBeUndone() {
        val v=scene();val target=v.speakers.value.last().id
        val old=v.alignmentComparisons().last().proposedDelayMs!!
        v.setSpeakerPosition(target,6f,1.4f,0f)
        val current=v.alignmentComparisons().last().proposedDelayMs!!
        assertTrue(current<old)
        assertTrue(v.applyAlignmentDelay(target))
        assertEquals(current,v.dspMap.value.getValue(target).delayMs.toDouble(),1e-5)
        assertEquals(0.0,v.alignmentComparisons().last().relativeArrivalMs,1e-5)
        assertTrue(v.undoScene())
        assertEquals(0f,v.dspMap.value[target]?.delayMs ?: 0f,0f)
    }
    @Test fun deletedReferenceDoesNotSilentlySelectAnotherSpeaker() {
        val v=scene();val reference=v.alignmentSourceId.value!!;val target=v.speakers.value.last().id
        v.removeSpeaker(reference)
        assertTrue(v.alignmentComparisons().isEmpty());assertFalse(v.applyAlignmentDelay(target))
        v.setAlignmentSource(target);assertEquals(1,v.alignmentComparisons().size)
    }
    @Test fun multipleReceiversKeepTheirSpeakerSelectionAcrossSaveReopenAndUndo() {
        val v=scene();val first=v.alignmentReferences.value.selectedId!!
        val firstSpeaker=v.alignmentSourceId.value!!
        v.addAlignmentReceiver("Balcony",8f,7f,2f)
        val second=v.alignmentReferences.value.selectedId!!
        val secondSpeaker=v.speakers.value.last().id
        v.setAlignmentSource(secondSpeaker)
        v.selectAlignmentReceiver(first)
        assertEquals(firstSpeaker,v.alignmentSourceId.value)
        v.renameAlignmentReceiver(first,"FOH renamed")
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(false)))
        assertEquals(v.alignmentReferences.value.toJsonString(),fresh.alignmentReferences.value.toJsonString())
        assertEquals(v.allAlignmentComparisons(),fresh.allAlignmentComparisons())
        fresh.selectAlignmentReceiver(second);assertEquals(secondSpeaker,fresh.alignmentSourceId.value)
        fresh.removeAlignmentReceiver(second);assertEquals(first,fresh.alignmentReferences.value.selectedId)
        assertTrue(fresh.undoScene());assertEquals(second,fresh.alignmentReferences.value.selectedId)
        assertEquals(2,fresh.alignmentReferences.value.receivers.size)
    }
    @Test fun delayApplicationUsesNewlySelectedReceiverAndLegacyReferenceMigrates() {
        val v=scene();val first=v.alignmentReferences.value.selectedId!!;val target=v.speakers.value.last().id
        val original=v.alignmentComparisons().last().proposedDelayMs!!
        v.addAlignmentReceiver("Near main",8f,1.4f,0f)
        assertFalse(v.applyAlignmentDelay(target))
        v.selectAlignmentReceiver(first);assertTrue(v.applyAlignmentDelay(target))
        assertEquals(original,v.dspMap.value.getValue(target).delayMs.toDouble(),1e-5)
        val legacy=org.json.JSONObject(v.exportSceneJson(false));legacy.remove("alignmentReferences")
        val fresh=vm();assertTrue(fresh.importSceneJson(legacy.toString()))
        assertEquals(1,fresh.alignmentReferences.value.receivers.size)
        assertEquals(v.alignmentReceiver.value,fresh.alignmentReceiver.value)
        assertEquals(v.alignmentComparisons(),fresh.alignmentComparisons())
    }
    @Test fun invalidSavedSelectionRejectsImportBeforeChangingTheScene() {
        val v=scene();val before=v.exportSceneJson(false)
        val bad=org.json.JSONObject(before)
        bad.getJSONObject("alignmentReferences").put("selectedId",99999)
        assertFalse(v.importSceneJson(bad.toString()))
        assertEquals(before,v.exportSceneJson(false))
    }
    @Test fun previewDoesNotEditProjectApplyMatchesPreviewAndUndoRestoresAllComparisons() {
        val v=scene();val first=v.alignmentReferences.value.selectedId!!;val target=v.speakers.value.last().id
        v.addAlignmentReceiver("Other",8f,1.4f,0f);v.selectAlignmentReceiver(first)
        val original=v.exportSceneJson(false);val before=v.allAlignmentComparisons()
        val preview=v.previewAlignmentDelay(target)!!
        assertEquals(original,v.exportSceneJson(false));assertEquals(before,v.allAlignmentComparisons())
        assertTrue(v.applyAlignmentPreview(preview))
        assertEquals(preview.after,v.allAlignmentComparisons())
        assertFalse(v.applyAlignmentPreview(preview))
        assertTrue(v.undoScene());assertEquals(before,v.allAlignmentComparisons())
        assertEquals(original,v.exportSceneJson(false))
    }
    @Test fun sceneEditsAndReceiverSwitchRejectOldPreviewWithoutEditingDsp() {
        val v=scene();val target=v.speakers.value.last().id
        val preview=v.previewAlignmentDelay(target)!!
        v.setSpeakerPosition(target,6f,1.4f,0f)
        val dsp=v.dspMap.value
        assertFalse(v.applyAlignmentPreview(preview));assertEquals(dsp,v.dspMap.value)
        val fresh=v.previewAlignmentDelay(target)!!
        v.addAlignmentReceiver("Elsewhere",8f,1.4f,0f)
        assertFalse(v.applyAlignmentPreview(fresh));assertEquals(dsp,v.dspMap.value)
    }
    @Test fun legacySceneClearsReferenceAndImpossibleDelayDoesNotMutateDsp() {
        val v=scene();val target=v.speakers.value.last().id
        v.setSpeakerPosition(target,30f,1.4f,0f)
        val before=v.dspMap.value
        assertFalse(v.applyAlignmentDelay(target));assertEquals(before,v.dspMap.value)
        val legacy=org.json.JSONObject(v.exportSceneJson(false));legacy.remove("alignmentReference");legacy.remove("alignmentReferences")
        assertTrue(v.importSceneJson(legacy.toString()));assertNull(v.alignmentReceiver.value)
        assertTrue(v.alignmentComparisons().isEmpty())
        assertFalse(v.applyAlignmentDelay(target))
    }
    @Test fun combinedAdjustmentIsPureAtomicUndoRedoAndSurvivesReopen() = runTest(dispatcher) {
        val v=scene();val target=v.speakers.value.last().id
        v.addAlignmentReceiver("Balcony",3f,7f,9f)
        val original=v.exportSceneJson(false)
        val settings=com.droidacoustic.pro.alignment.SystemDsp(-3f,8f,true)
        val preview=v.previewSystemAdjustment(target,settings)!!
        assertEquals(original,v.exportSceneJson(false));assertEquals(2,preview.receivers.size)
        assertTrue(v.applySystemAdjustment(preview));assertFalse(v.applySystemAdjustment(preview))
        val applied=v.exportSceneJson(false)
        val dsp=v.dspMap.value.getValue(target)
        assertEquals(-3f,dsp.gainDb,0f);assertEquals(8f,dsp.delayMs,0f);assertTrue(dsp.polarity)
        preview.receivers.forEach {r -> r.bands.forEach {(hz,level)->
            val pos=r.receiver.receiver.position
            val actual=v.predictBandDb(v.speakers.value,pos.x.toFloat(),pos.y.toFloat(),pos.z.toFloat(),hz)
            assertEquals(actual.toDouble(),level.afterDb!!,1e-3)
        }}
        assertTrue(v.undoScene());assertEquals(original,v.exportSceneJson(false))
        assertTrue(v.redoScene());assertEquals(applied,v.exportSceneJson(false))
        val fresh=vm();assertTrue(fresh.importSceneJson(applied))
        assertEquals(v.dspMap.value,fresh.dspMap.value)
        val revert=fresh.previewSystemAdjustment(target,preview.adjustment.before)!!
        preview.receivers.zip(revert.receivers).forEach {(a,b)->
            assertEquals(a.total.beforeDb!!,b.total.afterDb!!,1e-8)
            assertEquals(a.total.afterDb!!,b.total.beforeDb!!,1e-8)
        }
    }
    @Test fun systemAdjustmentRejectsStaleSceneAndReceiverChanges() = runTest(dispatcher) {
        val v=scene();val target=v.speakers.value.last().id
        val settings=com.droidacoustic.pro.alignment.SystemDsp(2f,4f,true)
        val p=v.previewSystemAdjustment(target,settings)!!
        v.setSpeakerPosition(target,6f,1.4f,0f)
        val before=v.exportSceneJson(false)
        assertFalse(v.applySystemAdjustment(p));assertEquals(before,v.exportSceneJson(false))
        val p2=v.previewSystemAdjustment(target,settings)!!
        v.addAlignmentReceiver("New",1f,2f,3f)
        assertFalse(v.applySystemAdjustment(p2))
        assertNull(v.previewSystemAdjustment(99999,settings))
        assertNull(v.previewSystemAdjustment(target,com.droidacoustic.pro.alignment.SystemDsp(0f,0f,false)))
    }
    @Test fun polarityAndDelayPreviewChangeInterferenceAtReceivers() = runTest(dispatcher) {
        val v=scene();val target=v.speakers.value.last().id
        val first=v.speakers.value.first()
        v.setSpeakerPosition(target,first.x,first.heightM,first.z)
        val polarity=v.previewSystemAdjustment(target,com.droidacoustic.pro.alignment.SystemDsp(0f,0f,true))!!
        // Equal colocated sources cancel; unavailable exact nulls must not become a finite zero-dB difference.
        val row=polarity.receivers.first()
        assertTrue(row.total.afterDb==null || row.total.afterDb!!<row.total.beforeDb!!-60)
        val delayed=v.previewSystemAdjustment(target,com.droidacoustic.pro.alignment.SystemDsp(0f,1f,false))!!
        assertNotEquals(delayed.receivers.first().total.beforeDb,delayed.receivers.first().total.afterDb)
    }

}
