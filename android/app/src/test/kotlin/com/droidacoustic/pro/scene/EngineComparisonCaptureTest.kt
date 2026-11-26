package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.AcousticBoundaryRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class EngineComparisonCaptureTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
    private fun ready(origin: SpeakerDataOrigin=SpeakerDataOrigin.UNSPECIFIED,fullSpectrum: Boolean=false): SceneViewModel {
        val v=model()
        val measuredBands=if(fullSpectrum) Bands.THIRD_OCTAVE_HZ.toList() else listOf(1000,2000)
        val d=ClfData("point_small",patterns=measuredBands.map {ClfPolarPattern(it,listOf(0f),listOf(-90f,90f),arrayOf(floatArrayOf(0f,0f)))},
            sensitivityDbByBand=measuredBands.associateWith {100f},calibration=ReferenceCalibration(1.0,DriveUnit.WATTS,1.0,"TEST ONLY"),provenance=SpeakerProvenance(origin=origin))
        assertTrue(v.importClfJsonData(ClfParser.toJson(d)));v.setSpeakerPreset("point_small");v.addSpeaker(0f,0f)
        v.setStageBoundaryRole(AcousticBoundaryRole.NONE)
        val ref=File(System.getProperty("manufacturerValidationRoot"),"validation/comparison-import/fixtures/manufacturer.json").readText()
        assertTrue(v.importComparisonDocument(ref,true));return v
    }
    @Test fun captureDerivesSceneProtocolAndSurvivesUndoRedoAndDiskReopen() {
        val v=ready();val reference=v.comparisonWorkspace.value.reference!!
        v.captureEngineComparison();dispatcher.scheduler.runCurrent()
        assertFalse(v.engineCaptureRunning.value)
        val engine=v.comparisonWorkspace.value.engine!!
        assertEquals(ComparisonEvidence.ENGINE,engine.run.evidence)
        assertNotEquals(reference.run.conditions.geometry,engine.run.conditions.geometry)
        assertNotEquals(reference.run.conditions.modelRevision,engine.run.conditions.modelRevision)
        assertEquals(DriveUnit.WATTS,engine.run.conditions.driveUnit)
        assertTrue(engine.run.samples.all {it.levelDb!=null})
        val document=engine.document;val report=v.comparisonWorkspace.value.reportJson()
        assertTrue(v.undoScene());assertNull(v.comparisonWorkspace.value.engine)
        assertTrue(v.redoScene());assertEquals(document,v.comparisonWorkspace.value.engine!!.document)
        val store=LocalProjectStore(folder.root);store.save("project_A",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("project_A")!!.project))
        assertEquals(document,fresh.comparisonWorkspace.value.engine!!.document)
        assertEquals(report,fresh.comparisonWorkspace.value.reportJson())
        assertEquals(SceneViewModel.SCENE_SCHEMA_VERSION,JSONObject(fresh.exportSceneJson()).getInt("version"))
    }
    @Test fun relativeCapturePersistsNormalizationDiagnosticsAndRejectsStaleChanges() {
        val v=ready()
        val relative=File(System.getProperty("manufacturerValidationRoot"),"validation/comparison-import/fixtures/relative-reference.json").readText()
        assertTrue(v.importComparisonDocument(relative,true))
        v.captureEngineComparison();dispatcher.scheduler.runCurrent()
        val captured=v.comparisonWorkspace.value.engine!!
        assertEquals("RELATIVE_TO_REFERENCE",captured.run.conditions.levelConvention)
        assertEquals(0.0,captured.run.samples.first().levelDb!!,1e-10)
        assertNotNull(captured.normalization)
        assertTrue(JSONObject(captured.document).getJSONObject("source").getJSONArray("normalizationReferences").length()>0)
        val document=captured.document;val store=LocalProjectStore(folder.root)
        store.save("relative",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("relative")!!.project))
        assertEquals(document,fresh.comparisonWorkspace.value.engine!!.document)
        assertEquals(captured.normalization,fresh.comparisonWorkspace.value.engine!!.normalization)
        assertTrue(v.undoScene());assertNull(v.comparisonWorkspace.value.engine)
        assertTrue(v.redoScene());assertEquals(document,v.comparisonWorkspace.value.engine!!.document)
        val pending=v.prepareEngineCapture();v.setSignalLevelDbu(3f)
        assertFalse(v.finishEngineCapture(pending,pending.request.captureComparison(pending.targets,pending.ticket.sceneRevision,
            pending.normalization,pending.levelConvention)))
    }

    @Test fun broadbandCaptureUsesSceneWeightingAndPersistsRawBandsThroughReopen() {
        val v=ready(fullSpectrum=true)
        val file=File(System.getProperty("manufacturerValidationRoot"),"validation/comparison-import/fixtures/broadband-reference.json")
        assertTrue(v.importComparisonDocument(file.readText(),true))
        v.captureEngineComparison();dispatcher.scheduler.runCurrent();assertNull(v.comparisonWorkspace.value.engine)
        v.setSignalType("SPECTRUM");v.setWeighting("C")
        v.captureEngineComparison();dispatcher.scheduler.runCurrent()
        val captured=v.comparisonWorkspace.value.engine!!
        assertEquals("C",captured.broadband!!.weighting) // Imported reference says A; never copy its weighting.
        assertEquals(0.0,captured.run.samples.single().hz,0.0);assertNotNull(captured.run.samples.single().levelDb)
        assertTrue(JSONObject(captured.document).getJSONArray("bandDiagnostics").length()>0)
        val document=captured.document;val store=LocalProjectStore(folder.root)
        store.save("broadband",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("broadband")!!.project))
        assertEquals(document,fresh.comparisonWorkspace.value.engine!!.document)
        assertTrue(v.undoScene());assertNull(v.comparisonWorkspace.value.engine)
        assertTrue(v.redoScene());assertEquals(document,v.comparisonWorkspace.value.engine!!.document)
        v.captureEngineComparison();v.setWeighting("A");dispatcher.scheduler.runCurrent()
        assertEquals(document,v.comparisonWorkspace.value.engine!!.document)
    }

    @Test fun relativeBroadbandCaptureKeepsBasisDiagnosticsAndSurvivesDiskRecovery() {
        val v=ready(fullSpectrum=true)
        val path=File(System.getProperty("manufacturerValidationRoot"),"validation/comparison-import/fixtures/relative-broadband-reference.json")
        assertTrue(v.importComparisonDocument(path.readText(),true));v.setSignalType("SPECTRUM");v.setWeighting("A")
        v.captureEngineComparison();dispatcher.scheduler.runCurrent()
        val captured=v.comparisonWorkspace.value.engine!!;val document=captured.document
        assertEquals("RELATIVE_BROADBAND_TO_REFERENCE",captured.run.conditions.levelConvention)
        assertEquals(0.0,captured.run.samples.single().levelDb!!,1e-10)
        assertEquals("weighted-broadband-level-difference",captured.normalization!!.method)
        assertTrue(JSONObject(document).getJSONObject("referenceBandDiagnostics").length()==8)
        val store=LocalProjectStore(folder.root);store.save("relative-weighted",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("relative-weighted")!!.project))
        assertEquals(document,fresh.comparisonWorkspace.value.engine!!.document)
        assertTrue(v.undoScene());assertNull(v.comparisonWorkspace.value.engine)
        assertTrue(v.redoScene());assertEquals(document,v.comparisonWorkspace.value.engine!!.document)
        v.captureEngineComparison();v.setWeighting("C");dispatcher.scheduler.runCurrent()
        assertEquals(document,v.comparisonWorkspace.value.engine!!.document)
    }

    @Test fun explicitTotalDriveSpectrumIsIndependentOfReferenceAndSurvivesUndoAndReopen() {
        val v=ready(fullSpectrum=true);v.setSignalType("SPECTRUM")
        val path=File(System.getProperty("manufacturerValidationRoot"),"validation/comparison-import/fixtures/broadband-reference.json")
        assertTrue(v.importComparisonDocument(path.readText(),true))
        v.captureEngineComparison();dispatcher.scheduler.runCurrent()
        val legacy=v.comparisonWorkspace.value.engine!!
        val ordinary=v.predictBandDb(v.speakers.value,10f,1f,0f,1000)
        assertTrue(v.setComparisonSourceSpectrum("""{"mode":"TOTAL_EQUAL_BAND_POWER"}"""))
        assertEquals(ordinary,v.predictBandDb(v.speakers.value,10f,1f,0f,1000),1e-6f)
        v.captureEngineComparison();dispatcher.scheduler.runCurrent()
        val total=v.comparisonWorkspace.value.engine!!
        assertEquals(-9.03089987,total.run.samples.single().levelDb!!-legacy.run.samples.single().levelDb!!,1e-6)
        assertEquals("TOTAL_EQUAL_BAND_POWER",total.broadband!!.sourceSpectrum!!.mode)
        assertEquals(5,JSONObject(total.document).getInt("version"))
        assertTrue(v.undoScene());assertEquals(legacy.document,v.comparisonWorkspace.value.engine!!.document)
        assertTrue(v.redoScene());assertEquals(total.document,v.comparisonWorkspace.value.engine!!.document)
        val store=LocalProjectStore(folder.root);store.save("total-drive",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("total-drive")!!.project))
        assertEquals("TOTAL_EQUAL_BAND_POWER",fresh.comparisonSourceSpectrum.value.mode)
        assertEquals(total.document,fresh.comparisonWorkspace.value.engine!!.document)
        val before=fresh.comparisonSourceSpectrum.value.canonical()
        assertFalse(fresh.setComparisonSourceSpectrum("""{"mode":"TOTAL_CUSTOM_BAND_POWER","relativeBandPowers":{"1000":-1}}"""))
        assertEquals(before,fresh.comparisonSourceSpectrum.value.canonical())
        val custom="""{"mode":"TOTAL_CUSTOM_BAND_POWER","relativeBandPowers":{"63":0,"125":0,"250":0,"500":0,"1000":1,"2000":0,"4000":0,"8000":0}}"""
        assertTrue(fresh.setComparisonSourceSpectrum(custom))
        store.save("custom-drive",fresh.exportSceneJson(false),fresh::resolveProjectAsset)
        val customReopened=model();assertTrue(customReopened.importPreparedProject(store.loadPrepared("custom-drive")!!.project))
        assertEquals(fresh.comparisonSourceSpectrum.value.canonical(),customReopened.comparisonSourceSpectrum.value.canonical())
        assertEquals(total.document,customReopened.comparisonWorkspace.value.engine!!.document)
        v.captureEngineComparison();assertTrue(v.setComparisonSourceSpectrum("""{"mode":"TOTAL_FLAT_PSD"}"""));dispatcher.scheduler.runCurrent()
        assertEquals(total.document,v.comparisonWorkspace.value.engine!!.document)
    }

    @Test fun frozenRequestsAndLateCapturesCannotOverwriteSceneEditsOrNewerCaptures() {
        val v=ready();val old=v.prepareEngineCapture()
        val before=old.request.captureComparison(old.targets,old.ticket.sceneRevision)
        v.setSignalLevelDbu(6f)
        assertEquals(before.document,old.request.captureComparison(old.targets,old.ticket.sceneRevision).document)
        assertFalse(v.finishEngineCapture(old,before));assertNull(v.comparisonWorkspace.value.engine)
        val first=v.prepareEngineCapture();val second=v.prepareEngineCapture()
        val newer=second.request.captureComparison(second.targets,second.ticket.sceneRevision)
        assertTrue(v.finishEngineCapture(second,newer))
        assertFalse(v.finishEngineCapture(first,first.request.captureComparison(first.targets,first.ticket.sceneRevision)))
        assertEquals(newer.document,v.comparisonWorkspace.value.engine!!.document)
        val pending=v.prepareEngineCapture();v.clearComparisonImports()
        assertFalse(v.finishEngineCapture(pending,pending.request.captureComparison(pending.targets,pending.ticket.sceneRevision)))
        assertNull(v.comparisonWorkspace.value.engine)
    }
    @Test fun missingDataStaysUnavailableAndQueuedCaptureIsCancelledByEdits() {
        val v=ready();v.captureEngineComparison();v.setBandHz(4000);dispatcher.scheduler.runCurrent()
        assertNull(v.comparisonWorkspace.value.engine)
        assertFalse(v.engineCaptureRunning.value)
        v.clearClfData("point_small");v.captureEngineComparison();dispatcher.scheduler.runCurrent()
        val missing=v.comparisonWorkspace.value.engine!!
        assertEquals(DriveUnit.UNKNOWN,missing.run.conditions.driveUnit)
        assertTrue(missing.run.samples.all {it.levelDb==null})
        val generic=ready(SpeakerDataOrigin.GENERIC_ESTIMATE)
        generic.captureEngineComparison();dispatcher.scheduler.runCurrent()
        assertTrue(generic.comparisonWorkspace.value.engine!!.synthetic)
    }
}
