package com.droidacoustic.pro.scene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PublicMeasurementRecoveryTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
    @Test fun realMeasurementReportsAndReviewLineageSurviveUndoRedoDiskSaveAndFreshReopen() {
        val root=File(System.getProperty("manufacturerValidationRoot"),"validation/public-measurements/imports")
        for(step in listOf(20,10)) {
            val vm=model()
            assertTrue(vm.importComparisonDocument(root.resolve("prx635-${step}deg-reference.json").readText(),true))
            val reference=vm.comparisonWorkspace.value.toJsonString()
            assertTrue(vm.importComparisonDocument(root.resolve("prx635-${step}deg-engine.json").readText(),false))
            val workspace=vm.comparisonWorkspace.value;val report=workspace.validationReportJson()
            assertEquals("FAILED",JSONObject(report).getJSONObject("summary").getJSONObject("comparison").getString("status"))
            assertTrue(vm.undoScene());assertEquals(reference,vm.comparisonWorkspace.value.toJsonString())
            assertTrue(vm.redoScene());assertEquals(report,vm.comparisonWorkspace.value.validationReportJson())
            val store=LocalProjectStore(folder.root);store.save("project_$step",vm.exportSceneJson(false),vm::resolveProjectAsset)
            val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("project_$step")!!.project))
            assertEquals(report,fresh.comparisonWorkspace.value.validationReportJson())
            val audit=JSONObject(fresh.comparisonWorkspace.value.reference!!.document).getJSONObject("protocolReview")
            assertTrue(audit.getString("originalDocument").contains("CONTROLLED_MEASUREMENT"))
            assertEquals(6,audit.getJSONArray("fields").length())
            assertFalse(JSONObject(report).getJSONObject("summary").getBoolean("synthetic"))
        }
    }
}
