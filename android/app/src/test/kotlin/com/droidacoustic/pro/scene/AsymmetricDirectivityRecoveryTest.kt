package com.droidacoustic.pro.scene

import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
class AsymmetricDirectivityRecoveryTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
    private fun difference(v:SceneViewModel,yaw:Float,hz:Int):Float {
        v.setSpeakerPan(v.speakers.value.single().id,yaw);v.setBandHz(hz)
        val d=sqrt(50f);val y=v.speakers.value.single().heightM
        val cells=if(yaw==0f) listOf(HeatCell(d,-d,Float.NaN,renderY=y),HeatCell(d,d,Float.NaN,renderY=y))
            else listOf(HeatCell(d,d,Float.NaN,renderY=y),HeatCell(-d,d,Float.NaN,renderY=y))
        val result=v.capturePredictionRequest(PredictionTicket(v.revision.value,1,PredictionChannel.COVERAGE),v.speakers.value,cells).calculate()
        return result.levelAt(0)-result.levelAt(1)
    }
    @Test fun importedAsymmetricModelKeepsHandednessInAndroidCaptureAndDiskRecovery() {
        // JSON data attaches to an existing preset; TAB import separately creates presets.
        val v=model();val data=File("../../validation/asymmetric-directivity/speaker.json").readText()
            .replace("synthetic-asymmetric","point_small")
        assertTrue(v.importClfJsonData(data));v.setSpeakerPreset("point_small");v.addSpeaker(0f,0f)
        assertEquals("point_small",v.speakers.value.single().presetId)
        assertNotNull(v.getClfData("point_small"))
        v.setSignalType("BAND");v.setSignalDispersionEnabled(true)
        for(yaw in listOf(0f,90f)) for(hz in listOf(500,1000,4000)) assertEquals(15f,difference(v,yaw,hz),.002f)
        val store=LocalProjectStore(folder.root);store.save("asymmetric",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("asymmetric")!!.project))
        assertTrue(fresh.assetRecoveryProblems.value.isEmpty())
        for(yaw in listOf(0f,90f)) for(hz in listOf(500,1000,4000)) assertEquals(difference(v,yaw,hz),difference(fresh,yaw,hz),0f)
    }
}
