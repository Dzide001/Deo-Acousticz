package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.AcousticBoundaryRole
import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/** Android setters/persistence feed the same shared request used by coverage. Numeric laws live in common tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class DspChainTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun vm()=SceneViewModel(predictionDispatcher=dispatcher).also {
        models+=it;it.setStageBoundaryRole(AcousticBoundaryRole.NONE)
        it.setGroundReflectionEnabled(false);it.setSignalDispersionEnabled(false);it.setBandHz(1000)
        it.addSpeaker(0f,0f);it.setSpeakerPosition(it.speakers.value.single().id,0f,10f,0f)
    }
    private fun capture(v:SceneViewModel)=v.capturePredictionRequest(
        PredictionTicket(v.revision.value,0,PredictionChannel.COVERAGE),v.speakers.value,
        listOf(HeatCell(12f,0f,Float.NaN,renderY=10f)))
    private fun level(v:SceneViewModel)=capture(v).calculate().levelAt(0)
    @Test fun gainClampReachesProductionPrediction() {
        val v=vm();val id=v.speakers.value.single().id;val flat=level(v)
        v.setGain(id,40f);assertEquals(12f,v.dspMap.value.getValue(id).gainDb,0f);assertEquals(12f,level(v)-flat,.001f)
        v.setGain(id,-40f);assertEquals(-12f,v.dspMap.value.getValue(id).gainDb,0f);assertEquals(-12f,level(v)-flat,.001f)
    }
    @Test fun eqClampReachesProductionPrediction() {
        val v=vm();val id=v.speakers.value.single().id;val flat=level(v)
        v.setEqBand(id,1000,25f);assertEquals(6f,level(v)-flat,.001f)
        v.setEqBand(id,1000,-25f);assertEquals(-6f,level(v)-flat,.001f)
    }
    @Test fun changingOneEqEntryPreservesOtherEntriesAndUnspecifiedBandsStayNeutral() {
        val v=vm();val id=v.speakers.value.single().id;val flat=level(v)
        v.setEqBand(id,125,-4f);v.setEqBand(id,4000,5f);v.setEqBand(id,125,2f)
        assertEquals(mapOf(125 to 2f,4000 to 5f),v.dspMap.value.getValue(id).eqBands)
        assertEquals(flat,level(v),0f)
    }
    @Test fun setterEditsDoNotMutateAnAlreadyCapturedRequest() {
        val v=vm();val id=v.speakers.value.single().id;val frozen=capture(v);val flat=frozen.calculate().levelAt(0)
        v.setGain(id,3f);v.setEqBand(id,1000,-5f)
        assertEquals(flat,frozen.calculate().levelAt(0),0f);assertEquals(-2f,level(v)-flat,.001f)
    }
    @Test fun configuredDigitalFilterReachesTheRequest() {
        val v=vm();val id=v.speakers.value.single().id;val flat=level(v)
        v.setFilters(id,DspFilterChain(listOf(DspFilter(FilterKind.PEAK,1000.0,.7,6.0))))
        assertTrue(level(v)>flat+5f)
    }
    @Test fun dspSettingsAndPredictionsSurviveDiskSaveReopen() {
        val v=vm();val id=v.speakers.value.single().id
        v.setGain(id,3f);v.setEqBand(id,1000,-5f);v.setEqBand(id,4000,2f)
        v.setDelay(id,7.5f);v.setPolarity(id,true)
        v.setFilters(id,DspFilterChain(listOf(DspFilter(FilterKind.HIGH_PASS,80.0),DspFilter(FilterKind.PEAK,1000.0,2.0,4.0))))
        val before=level(v);val store=LocalProjectStore(folder.newFolder("dsp"))
        store.save("saved",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
        assertTrue(fresh.importPreparedProject(store.loadPrepared("saved")!!.project))
        assertEquals(v.dspMap.value,fresh.dspMap.value);assertEquals(before,level(fresh),0f)
    }
}
