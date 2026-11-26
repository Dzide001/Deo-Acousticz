package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PoweredReferenceRecoveryTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
    @Test fun referenceBasisAndCalibrationGuardsSurviveProductionImportAndDiskReopen() {
        val dir=File(System.getProperty("manufacturerValidationRoot"),"validation/powered-reference")
        val manifestFile=dir.resolve("fixtures/cases.json");val manifest=JSONObject(manifestFile.readText())
        val cases=manifest.getJSONArray("cases");val reports=JSONArray()
        for(i in 0 until cases.length()) {
            val row=cases.getJSONObject(i);val fixture=dir.resolve("fixtures/"+row.getString("file"))
            assertEquals(row.getString("sha256"),SpeakerProvenance.hash(fixture.readBytes()))
            val v=model();assertTrue(v.importClfTabText(fixture.readText(),"reference"));v.addSpeaker(0f,0f)
            v.setSignalType("BAND");v.setBandHz(1000);v.setSignalDispersionEnabled(true)
            val data=v.getClfData("reference")!!
            assertEquals(row.getString("basis"),data.levelReferenceBasis.name)
            assertEquals(row.getBoolean("electricalReferenceKnown"),data.calibration.electricalReferenceKnown)
            assertEquals(row.getDouble("levelDb").toFloat(),data.measuredRadiator().referenceLevelDb(1000)!!,0f)
            if(!data.calibration.electricalReferenceKnown) assertNull(data.calibration.gainForDrive(DriveUnit.WATTS,1.0))
            if(data.levelReferenceBasis==LevelReferenceBasis.AXIAL_SPECTRUM) {
                assertTrue(data.provenance.normalizationNotes.contains("Input setting unspecified"))
                assertFalse(data.provenance.normalizationNotes.contains("normalized to 1W"))
            }
            fun level(vm:SceneViewModel):Float=vm.capturePredictionRequest(PredictionTicket(vm.revision.value,1,PredictionChannel.COVERAGE),
                vm.speakers.value,listOf(HeatCell(10f,0f,Float.NaN,renderY=vm.speakers.value.single().heightM))).calculate().levelAt(0)
            val before=level(v);assertTrue(before.isFinite())
            val store=LocalProjectStore(folder.newFolder("case$i"));store.save("saved",v.exportSceneJson(false),v::resolveProjectAsset)
            val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("saved")!!.project))
            assertEquals(data,fresh.getClfData("reference"));assertEquals(before,level(fresh),0f)
            val rejected=dir.resolve("fixtures/powered-cf1-unsupported.tab")
            assertEquals(manifest.getString("unsupportedSha256"),SpeakerProvenance.hash(rejected.readBytes()))
            val saved=v.exportSceneJson();assertFalse(v.importClfTabText(rejected.readText(),"unsupported"))
            assertTrue(v.lastImportError.value!!.contains("powered CF1 axial-only conversion is unsupported"))
            assertEquals(saved,v.exportSceneJson())
            reports.put(JSONObject(row.toString()).put("predictionDb",before).put("reopenedPredictionDb",level(fresh))
                .put("report",JSONObject(SpeakerDataChecks.reportJson(data))).put("failedImportPreservedProject",true))
        }
        val out=File(System.getProperty("poweredReferenceReport"));out.parentFile.mkdirs()
        out.writeText(JSONObject().put("scope",manifest.getString("scope")).put("cases",reports).put("allPassed",true)
            .put("fixturesSha256",SpeakerProvenance.hash(manifestFile.readBytes())).toString(2)+"\n")
    }
}
