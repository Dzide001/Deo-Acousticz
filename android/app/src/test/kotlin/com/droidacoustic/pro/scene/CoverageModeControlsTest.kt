package com.droidacoustic.pro.scene
import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
@OptIn(ExperimentalCoroutinesApi::class)
class CoverageModeControlsTest {
 @get:Rule val folder=TemporaryFolder()
 private val dispatcher=StandardTestDispatcher()
 private val models=mutableListOf<SceneViewModel>()
 @Before fun setup()=Dispatchers.setMain(dispatcher)
 @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
 private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
 private fun output(v:SceneViewModel)=v.capturePredictionRequest(PredictionTicket(v.revision.value,1,PredictionChannel.COVERAGE),v.speakers.value,listOf(HeatCell(10f,0f,Float.NaN,renderY=1.2f)),retainBandPowers=true).calculate()
 @Test fun bandSelectsCoverageWhileSpectrumInspectionKeepsBroadbandLevel() {
  val v=model();v.addSpeaker(0f,0f)
  v.setBandHz(125);assertEquals(setOf(125),output(v).bandPowersAt(0).keys)
  v.setBandHz(8000);assertEquals(setOf(8000),output(v).bandPowersAt(0).keys)
  v.setSignalType("SPECTRUM");val high=output(v);v.setBandHz(125);val low=output(v)
  assertTrue(low.bandPowersAt(0).size>1);assertEquals(high.bandPowersAt(0),low.bandPowersAt(0));assertEquals(high.levelAt(0),low.levelAt(0),0f)
  v.setSignalType("BAND");assertEquals(125,v.selectedBandHz.value)
  assertEquals(setOf(125),output(v).bandPowersAt(0).keys)
 }
 @Test fun manualModeAndBandEditsClearOldCoverageAndRequireRecalculation() {
  val v=model();v.addSpeaker(0f,0f);v.recalculateSignal();dispatcher.scheduler.runCurrent()
  assertEquals(PredictionState.CURRENT,v.predictionState.value);assertTrue(v.heatmap.value.isNotEmpty())
  v.setSignalType("SPECTRUM");assertEquals(PredictionState.STALE,v.predictionState.value);assertTrue(v.heatmap.value.isEmpty())
  assertEquals("Recalculate",CoveragePresentation.describe(v.signalType.value,v.selectedBandHz.value,v.predictionState.value,false).action)
  v.recalculateSignal();dispatcher.scheduler.runCurrent();assertEquals(PredictionState.CURRENT,v.predictionState.value)
  v.setBandHz(8000);assertEquals(PredictionState.STALE,v.predictionState.value);assertTrue(v.heatmap.value.isEmpty())
 }
 @Test fun automaticModeChangesReturnToCurrent() {
  val v=model();v.addSpeaker(0f,0f);v.setSignalAutoCalculate(true);v.setSignalType("SPECTRUM");v.setSignalType("BAND");v.setBandHz(4000);dispatcher.scheduler.runCurrent()
  assertEquals(PredictionState.CURRENT,v.predictionState.value);assertEquals(setOf(4000),output(v).bandPowersAt(0).keys)
 }
 @Test fun switchingBackToManualStopsAutomaticUpdatesAndBothChoicesSurviveReopen() {
  val v=model();v.addSpeaker(0f,0f);v.setSignalAutoCalculate(true);dispatcher.scheduler.runCurrent()
  assertEquals(PredictionState.CURRENT,v.predictionState.value)
  v.setSignalAutoCalculate(false);v.setBandHz(8000);dispatcher.scheduler.runCurrent()
  assertEquals(PredictionState.STALE,v.predictionState.value);assertTrue(v.heatmap.value.isEmpty())
  v.recalculateSignal();dispatcher.scheduler.runCurrent();assertEquals(PredictionState.CURRENT,v.predictionState.value)
  for(auto in listOf(true,false)) {
   v.setSignalAutoCalculate(auto)
   val store=LocalProjectStore(folder.newFolder("auto-$auto"));store.save("recovery",v.exportSceneJson(false),v::resolveProjectAsset)
   val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("recovery")!!.project))
   assertEquals(auto,fresh.signalAutoCalculate.value)
  }
 }
 @Test fun diskReopenRestoresModeFrequencyAndShowsItsStartupCalculation() {
  for(mode in listOf("BAND","SPECTRUM")) {
   val v=model();v.addSpeaker(0f,0f);v.setSignalType(mode);v.setBandHz(8000)
   val store=LocalProjectStore(folder.newFolder(mode));store.save("recovery",v.exportSceneJson(false),v::resolveProjectAsset)
   val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("recovery")!!.project))
   assertEquals(mode,fresh.signalType.value);assertEquals(8000,fresh.selectedBandHz.value);assertFalse(fresh.signalAutoCalculate.value);assertTrue(fresh.heatmap.value.isEmpty())
   assertEquals(PredictionState.RUNNING,fresh.predictionState.value)
   val p=CoveragePresentation.describe(mode,8000,fresh.predictionState.value,false);assertFalse(p.actionEnabled);assertTrue(p.status.contains("not ready"))
   assertEquals(mode=="BAND",p.showFrequencyButtons)
   dispatcher.scheduler.runCurrent();assertEquals(PredictionState.CURRENT,fresh.predictionState.value);assertTrue(fresh.heatmap.value.isNotEmpty())
  }
 }
}
