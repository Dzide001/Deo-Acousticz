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
class ComparisonImportIntegrationTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
    private fun fixture(name: String)=File(System.getProperty("manufacturerValidationRoot"),"validation/comparison-import/fixtures/$name.json").readText()
    @Test fun importsSurviveUndoRedoDiskSaveAndFreshReopen() {
        val vm=model()
        assertTrue(vm.applyComparisonImport(ImportedComparison.parse(fixture("manufacturer")),true))
        val one=vm.comparisonWorkspace.value.toJsonString()
        assertTrue(vm.importComparisonDocument(fixture("engine"),false))
        val both=vm.comparisonWorkspace.value.toJsonString();val report=vm.comparisonWorkspace.value.reportJson()
        val review=vm.comparisonWorkspace.value.validationReportJson()
        val exported=File(folder.root,"comparison-report.json").apply {writeText(review)}
        val inputs=JSONObject(exported.readText()).getJSONObject("inputs")
        val restored=ComparisonWorkspace.empty().import(inputs.getString("referenceDocument"),true).import(inputs.getString("engineDocument"),false)
        assertEquals(review,restored.validationReportJson())
        assertTrue(vm.undoScene());assertEquals(one,vm.comparisonWorkspace.value.toJsonString())
        assertTrue(vm.redoScene());assertEquals(both,vm.comparisonWorkspace.value.toJsonString())
        assertEquals(review,vm.comparisonWorkspace.value.validationReportJson())
        val store=LocalProjectStore(folder.root);store.save("project_A",vm.exportSceneJson(false),vm::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("project_A")!!.project))
        assertEquals(both,fresh.comparisonWorkspace.value.toJsonString());assertEquals(report,fresh.comparisonWorkspace.value.reportJson())
        assertEquals(review,fresh.comparisonWorkspace.value.validationReportJson())
        assertEquals("NOT_COMPARABLE",JSONObject(fresh.exportProjectReportJson()).getJSONObject("importedComparison").getJSONObject("comparison").getString("status"))
        fresh.clearComparisonImports();assertNull(fresh.comparisonWorkspace.value.reference)
        assertTrue(fresh.undoScene());assertEquals(both,fresh.comparisonWorkspace.value.toJsonString())
    }
    @Test fun rejectedImportsAndTamperedProjectsAreAtomicAndLegacyScenesClearSnapshots() {
        val vm=model();assertTrue(vm.importComparisonDocument(fixture("measurement"),true))
        val before=vm.comparisonWorkspace.value.toJsonString()
        assertFalse(vm.importComparisonDocument("bad",false));assertEquals(before,vm.comparisonWorkspace.value.toJsonString())
        assertFalse(vm.importComparisonDocument(fixture("manufacturer"),false));assertEquals(before,vm.comparisonWorkspace.value.toJsonString())
        val bad=JSONObject(vm.exportSceneJson()).apply {getJSONObject("comparisonWorkspace").put("referenceDocument","bad")}
        assertFalse(vm.importSceneJson(bad.toString()));assertEquals(before,vm.comparisonWorkspace.value.toJsonString())
        val legacy=JSONObject(vm.exportSceneJson()).apply {remove("comparisonWorkspace");put("version",25)}
        assertTrue(vm.importSceneJson(legacy.toString()));assertNull(vm.comparisonWorkspace.value.reference)
    }
    @Test fun sceneChangesLeaveImportedSnapshotIdentityUnchangedAndClearIsUndoable() {
        val vm=model();assertTrue(vm.importComparisonDocument(fixture("manufacturer"),true))
        val before=vm.comparisonWorkspace.value.toJsonString()
        vm.setBandHz(4000);assertEquals(before,vm.comparisonWorkspace.value.toJsonString())
        vm.clearAll();assertNull(vm.comparisonWorkspace.value.reference)
        assertTrue(vm.undoScene());assertEquals(before,vm.comparisonWorkspace.value.toJsonString())
    }
}
