package com.droidacoustic.pro.scene

import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.ui.ReceiverAngularGlb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverMarkerRecoveryTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
    private fun scene()=model().also {v->
        v.setSpeakerPreset("point_small");v.addSpeaker(0f,0f);v.moveListener(0f,0f)
        v.addAlignmentReceiver("Duplicate",0f,v.listener.value.earHeightM,0f)
        v.addAlignmentReceiver("Duplicate",0f,v.listener.value.earHeightM,0f)
        v.setReceiverAngularOverlayEnabled(true)
    }
    @Test fun markerAndListSelectionShareIdentityAndPersistWithoutAcousticOrAlignmentChanges() {
        val vm=scene();val revision=vm.revision.value;val alignment=vm.alignmentReferences.value.toJsonString()
        val (request,targets)=vm.captureReceiverAngularOverlay();val report=request.receiverAngularSupport(targets)
        val camera=GizmoCamera(GizmoPoint3(0f,20f,0f),GizmoPoint3(0f,0f,0f),GizmoPoint3(0f,0f,-1f),800,600)
        val layout=ReceiverMarkers.layout(targets,camera,1f,"listener") {GizmoPoint2(130f,16f)}
        val target=layout.markers.first {it.id=="reference:0"};val label=target.label!!
        val picked=layout.pick(GizmoPoint2(label.x+label.width/2,label.y+label.height/2),"listener")!!
        assertTrue(vm.selectAngularReceiver(picked));assertEquals("reference:0",vm.receiverAngularSelection.value)
        assertEquals(revision,vm.revision.value);assertEquals(alignment,vm.alignmentReferences.value.toJsonString())
        val (same,sameTargets)=vm.captureReceiverAngularOverlay();assertEquals(report.toJsonString(),same.receiverAngularSupport(sameTargets).toJsonString())
        val store=LocalProjectStore(folder.root);store.save("project_A",vm.exportSceneJson(false),vm::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("project_A")!!.project))
        assertTrue(fresh.receiverAngularOverlayEnabled.value);assertEquals(picked,fresh.receiverAngularSelection.value)
        assertEquals(targets,fresh.captureReceiverAngularOverlay().second)
        assertEquals(layout.markers,ReceiverMarkers.layout(fresh.captureReceiverAngularOverlay().second,camera,1f,"listener") {GizmoPoint2(130f,16f)}.markers)
        assertFalse(fresh.selectAngularReceiver("reference:999"));assertEquals(picked,fresh.receiverAngularSelection.value)
        val bytes=ReceiverAngularGlb.build(report,picked)!!;assertTrue(bytes.isNotEmpty())
        assertEquals(report.markerLines().size+12,report.markerLines(picked).size)
        assertEquals(12,report.markerLines(picked).count {it.colorRgb==0xffffff})
    }
    @Test fun deletionUndoRedoAndLegacyReopenReconcileStaleSelection() {
        val vm=scene();assertTrue(vm.selectAngularReceiver("reference:0"))
        vm.removeAlignmentReceiver(0);assertEquals("listener",vm.receiverAngularSelection.value)
        assertTrue(vm.undoScene());assertEquals("reference:0",vm.receiverAngularSelection.value)
        assertTrue(vm.redoScene());assertEquals("listener",vm.receiverAngularSelection.value)
        assertTrue(vm.selectAngularReceiver("reference:1"))
        val old=JSONObject(vm.exportSceneJson()).apply {remove("receiverAngularSelection")}
        val fresh=model();assertTrue(fresh.importSceneJson(old.toString()));assertEquals("listener",fresh.receiverAngularSelection.value)
        old.put("receiverAngularSelection","reference:404")
        assertTrue(fresh.importSceneJson(old.toString()));assertEquals("listener",fresh.receiverAngularSelection.value)
        vm.clearAll();assertEquals("listener",vm.receiverAngularSelection.value);assertFalse(vm.receiverAngularOverlayEnabled.value)
    }
}
