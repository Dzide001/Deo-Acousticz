package com.droidacoustic.pro.scene

import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.ui.ReceiverAngularGlb
import com.droidacoustic.pro.acoustics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverAngularOverlayTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
    private fun scene():SceneViewModel=model().also {vm->
        val fixture=File(System.getProperty("manufacturerValidationRoot"),"validation/angular-domains/recovery-fixture.json").readText()
        assertTrue(vm.importClfJsonData(fixture));vm.setSpeakerPreset("point_small");vm.addSpeaker(0f,0f)
        val s=vm.speakers.value.single();vm.setSpeakerPosition(s.id,0f,vm.listener.value.earHeightM,0f)
        vm.moveListener(10f,0f);vm.addAlignmentReceiver("Same position",10f,vm.listener.value.earHeightM,0f)
        vm.addAlignmentReceiver("Above plane",10f,3f,0f);vm.setReceiverAngularOverlayEnabled(true)
    }
    private fun report(vm:SceneViewModel):ReceiverAngularReport {
        val (request,targets)=vm.captureReceiverAngularOverlay();return request.receiverAngularSupport(targets)
    }
    @Test fun overlayPreferenceReceiversOriginsAndDirectionsSurviveDiskReopenWithoutChangingLevels() {
        val vm=scene();val revision=vm.revision.value
        vm.setReceiverAngularOverlayEnabled(false);vm.setReceiverAngularOverlayEnabled(true);assertEquals(revision,vm.revision.value)
        val initial=report(vm)
        assertEquals(3,initial.receivers.size);assertEquals(3,initial.receivers.map {it.receiver.id}.distinct().size)
        assertEquals("MEASURED",initial.receivers.first().origin);assertEquals("UNAVAILABLE",initial.receivers.last().support)
        val s=vm.speakers.value.single();val level=vm.predictBandDb(vm.speakers.value,10f,s.heightM,0f,1000)
        val store=LocalProjectStore(folder.root);store.save("project_A",vm.exportSceneJson(false),vm::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("project_A")!!.project));assertTrue(fresh.receiverAngularOverlayEnabled.value)
        val reopened=report(fresh)
        // Scene revisions change on restore; physical diagnostic contents and receiver IDs do not.
        assertEquals(initial.receivers,reopened.receivers)
        assertEquals(level,fresh.predictBandDb(fresh.speakers.value,10f,s.heightM,0f,1000),1e-5f)
        val legacy=JSONObject(vm.exportSceneJson()).apply {remove("receiverAngularOverlayEnabled")}
        assertTrue(fresh.importSceneJson(legacy.toString()));assertFalse(fresh.receiverAngularOverlayEnabled.value)
    }
    @Test fun changedGeometryInvalidatesDisplayRevisionAndUndoRestoresDiagnostics() {
        val vm=scene();val (frozen,targets)=vm.captureReceiverAngularOverlay();val before=frozen.receiverAngularSupport(targets)
        val s=vm.speakers.value.single();vm.setSpeakerPan(s.id,90f)
        assertNotEquals(vm.revision.value,before.sceneRevision)
        assertEquals(before.toJsonString(),frozen.receiverAngularSupport(targets).toJsonString())
        assertNotEquals(before.receivers,report(vm).receivers)
        assertTrue(vm.undoScene());assertEquals(before.receivers,report(vm).receivers)
        assertTrue(vm.redoScene());assertNotEquals(before.receivers,report(vm).receivers)
    }
    @Test fun nativeMarkerGeometryContainsBothSupportAndOriginInEveryOrthogonalPlane() {
        val report=report(scene());val bytes=ReceiverAngularGlb.build(report)!!
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546c67,b.getInt(0));assertEquals(2,b.getInt(4));assertEquals(bytes.size,b.getInt(8))
        val length=b.getInt(12);val gltf=JSONObject(bytes.copyOfRange(20,20+length).toString(Charsets.UTF_8))
        assertEquals(report.markerLines().size*2,gltf.getJSONArray("accessors").getJSONObject(0).getInt("count"))
        for(r in report.receivers) {
            val lines=ReceiverAngularReport(report.sceneRevision,listOf(r)).markerLines()
            assertEquals(24,lines.size);assertEquals(setOf(r.supportColorRgb,r.originColorRgb),lines.map {it.colorRgb}.toSet())
            assertTrue(lines.any {it.from.x!=it.to.x});assertTrue(lines.any {it.from.y!=it.to.y});assertTrue(lines.any {it.from.z!=it.to.z})
        }
        assertNull(ReceiverAngularGlb.build(null))
    }
}
