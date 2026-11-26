package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AngularSupportRecoveryTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
    @Test fun realSparseDataWarningsAndDerivedReportsSurviveDiskReopenWithoutChangingAssetOrLevels() {
        val root=File(System.getProperty("manufacturerValidationRoot"),"validation/public-measurements/fixtures.json")
        val asset=JSONObject(root.readText()).getJSONArray("cases").getJSONObject(1).getJSONObject("asset")
        // Bind the measured test asset to an existing preset; raw JSON registration does not create catalog presets.
        asset.put("speakerId","point_small")
        val vm=model();assertTrue(vm.importClfJsonData(asset.toString()))
        vm.setSpeakerPreset(asset.getString("speakerId"));vm.addSpeaker(0f,0f)
        val source=vm.speakers.value.single();val data=vm.getClfData(source.presetId)!!
        val identity=SpeakerProvenance.hash(ClfParser.toJson(data).encodeToByteArray())
        val warnings=vm.predictionDataWarnings()
        assertTrue(warnings.any {"azimuth gaps" in it});assertTrue(warnings.any {"other elevations are unsupported" in it})
        val support=JSONObject(vm.exportProjectReportJson()).getJSONArray("speakerDataValidation").getJSONObject(0).getJSONObject("angularSupport").toString()
        val before=vm.predictBandDb(listOf(source),10f,source.heightM,0f,1000)
        assertTrue(before.isFinite())
        val store=LocalProjectStore(folder.root);store.save("project_A",vm.exportSceneJson(false),vm::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("project_A")!!.project))
        assertEquals(warnings,fresh.predictionDataWarnings())
        assertEquals(identity,SpeakerProvenance.hash(ClfParser.toJson(fresh.getClfData(source.presetId)!!).encodeToByteArray()))
        assertEquals(support,JSONObject(fresh.exportProjectReportJson()).getJSONArray("speakerDataValidation").getJSONObject(0).getJSONObject("angularSupport").toString())
        assertEquals(before,fresh.predictBandDb(fresh.speakers.value,10f,source.heightM,0f,1000),1e-5f)
    }
    @Test fun measuredSymmetryAndInterpolatedMagnitudeAndComplexDeclarationsSurviveRecovery() {
        val fixture=File(System.getProperty("manufacturerValidationRoot"),"validation/angular-domains/recovery-fixture.json").readText()
        val vm=model();assertTrue(vm.importClfJsonData(fixture));vm.setSpeakerPreset("point_small");vm.addSpeaker(0f,0f)
        val data=vm.getClfData("point_small")!!
        val identity=SpeakerProvenance.hash(ClfParser.toJson(data).encodeToByteArray())
        val warnings=vm.predictionDataWarnings();assertTrue(warnings.any {"symmetry-derived" in it});assertTrue(warnings.any {"pre-interpolated" in it})
        val report=SpeakerDataChecks.reportJson(data)
        val store=LocalProjectStore(folder.root);store.save("project_declared",vm.exportSceneJson(false),vm::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("project_declared")!!.project))
        val reopened=fresh.getClfData("point_small")!!
        assertEquals(identity,SpeakerProvenance.hash(ClfParser.toJson(reopened).encodeToByteArray()))
        assertEquals(report,SpeakerDataChecks.reportJson(reopened));assertEquals(warnings,fresh.predictionDataWarnings())
        for(kind in listOf("magnitude","complex")) {
            assertEquals("MEASURED",SpeakerAngularSupport.at(reopened,1000,0f,0f,kind)!!.origin)
            assertEquals("SYMMETRY",SpeakerAngularSupport.at(reopened,1000,350f,0f,kind)!!.origin)
            assertEquals("INTERPOLATED",SpeakerAngularSupport.at(reopened,1000,5f,0f,kind)!!.origin)
        }
        val level=vm.predictBandDb(vm.speakers.value,10f,vm.speakers.value.single().heightM,0f,1000)
        assertEquals(level,fresh.predictBandDb(fresh.speakers.value,10f,fresh.speakers.value.single().heightM,0f,1000),1e-5f)
        val invalid=JSONObject(fixture).apply {getJSONArray("patterns").getJSONObject(0).getJSONObject("angularDomain").put("version",99)}
        assertFalse(fresh.importClfJsonData(invalid.toString()))
        assertEquals(identity,SpeakerProvenance.hash(ClfParser.toJson(fresh.getClfData("point_small")!!).encodeToByteArray()))
    }

}
