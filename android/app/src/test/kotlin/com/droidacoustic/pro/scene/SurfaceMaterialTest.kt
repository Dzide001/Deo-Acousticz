package com.droidacoustic.pro.scene

import com.droidacoustic.pro.geometry.AcousticBoundaryRole
import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import kotlin.math.*

/** Active global-ground material and project compatibility through production requests. */
@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceMaterialTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}.apply {
        setSignalType("BAND");setSignalDispersionEnabled(false);setStageBoundaryRole(AcousticBoundaryRole.NONE)
    }
    private fun power(v:SceneViewModel,hz:Int=1000)=v.capturePredictionRequest(PredictionTicket(v.revision.value,1,PredictionChannel.COVERAGE),
        listOf(PlacedSpeaker(0,0f,0f,heightM=0f,sensitivity=100f)),listOf(HeatCell(10f,0f,Float.NaN,renderY=0f)),
        bands=listOf(hz),selected=hz,retainBandPowers=true).calculate().totalPowerAt(0)
    @Test fun floorCurveReachesProductionGroundReflectionEnergy() {
        val v=model();v.setSurfaceMaterial("FLOOR",SurfaceMaterial.ACOUSTIC_PANEL.id)
        for(hz in listOf(125,1000,4000)) {
            v.setGroundReflectionEnabled(false);val free=power(v,hz)
            v.setGroundReflectionEnabled(true);val reflected=power(v,hz)
            val expected=(1+sqrt(1.0-SurfaceMaterial.ACOUSTIC_PANEL.alphaAt(hz))).pow(2)
            assertEquals(expected,reflected/free,1e-6)
        }
    }
    @Test fun unassignedRoomWallCeilingAndOldOrderSettingsDoNotAffectTheSolver() {
        val v=model();v.setGroundReflectionEnabled(true);val before=power(v)
        v.setSurfaceMaterial("WALL",SurfaceMaterial.BRICK.id);v.setSurfaceMaterial("CEILING",SurfaceMaterial.CURTAIN.id)
        v.setReflectionOrder(3);assertEquals(before,power(v),0.0)
        assertTrue(v.earlyReflections.value.isEmpty());assertNull(v.rt60Estimate.value);assertNull(v.stiEstimate.value)
    }
    @Test fun materialAndLegacyRoomSettingsSurviveDiskReopen() {
        val v=model();v.setGroundReflectionEnabled(true);v.setSurfaceMaterial("FLOOR",SurfaceMaterial.WOOD_FLOOR.id)
        v.setSurfaceMaterial("CEILING",SurfaceMaterial.OPEN_TRUSS.id);v.setSurfaceMaterial("WALL",SurfaceMaterial.CURTAIN.id)
        v.setReflectionOrder(3);val before=power(v)
        val store=LocalProjectStore(folder.root);store.save("materials",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("materials")!!.project))
        assertEquals(v.roomMaterials.value,fresh.roomMaterials.value);assertEquals(3,fresh.reflectionOrder.value)
        assertEquals(before,power(fresh),0.0)
        val report=JSONObject(fresh.exportProjectReportJson())
        for(key in listOf("rt60S","roomVolumeM3","sti","stiQuality","alconsPct")) assertFalse(report.has(key))
    }
    @Test fun oldScalarAbsorptionReopensAsTheSameFlatGroundCurve() {
        val v=model();val document=JSONObject(v.exportSceneJson(false));val material=document.getJSONObject("roomMaterials")
        material.remove("floorMaterial");material.remove("floorAlphaByBand");material.put("floorAlpha",.33)
        val fresh=model();assertTrue(fresh.importSceneJson(document.toString()))
        for(hz in SceneViewModel.SUPPORTED_BANDS_HZ) assertEquals(.33f,fresh.roomMaterials.value.floor.alphaAt(hz),.001f)
    }
    @Test fun customCurveSurvivesProjectRoundTrip() {
        val v=model();v.setFloorAbsorption(.5f)
        val fresh=model();assertTrue(fresh.importSceneJson(v.exportSceneJson(false)))
        assertEquals(v.roomMaterials.value.floor,fresh.roomMaterials.value.floor)
    }
    @Test fun materialEditsMarkTheSceneForSaving() {
        val v=model();val before=v.revision.value;v.setSurfaceMaterial("WALL",SurfaceMaterial.BRICK.id)
        assertNotEquals(before,v.revision.value)
    }
}
