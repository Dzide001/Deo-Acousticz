package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import kotlin.math.*

@OptIn(ExperimentalCoroutinesApi::class)
class DriveAndEqRecoveryTest {
 @get:Rule val folder=TemporaryFolder()
 private val dispatcher=StandardTestDispatcher()
 private val models=mutableListOf<SceneViewModel>()
 @Before fun setup()=Dispatchers.setMain(dispatcher)
 @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
 private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
 private fun populated()=model().apply {addSpeaker(0f,0f);setSignalDispersionEnabled(false);setSignalType("SPECTRUM")}
 private fun level(v:SceneViewModel,hz:Int?=null):Float=v.capturePredictionRequest(PredictionTicket(v.revision.value,1,PredictionChannel.LISTENER),v.speakers.value,
  listOf(HeatCell(10f,0f,Float.NaN,renderY=1.2f)),bands=hz?.let {listOf(it)} ?: v.analysisBands()).calculate().levelAt(0)
 @Test fun newDefaultAndLegacyMigrationHaveExplicitDifferentDrive() {
  val v=populated();assertEquals(BroadbandDrive.DEFAULT,v.broadbandDrive.value)
  val json=JSONObject(v.exportSceneJson()).apply {put("version",30);remove("broadbandDrive")}
  val old=model();assertTrue(old.importSceneJson(json.toString()));assertEquals(BroadbandDrive.PER_BAND_REFERENCE,old.broadbandDrive.value)
  assertEquals((10*log10(8.0)).toFloat(),level(old)-level(v),3e-5f)
  assertEquals("PER_BAND_REFERENCE",JSONObject(old.exportSceneJson()).getString("broadbandDrive"))
  val before=old.exportSceneJson();json.put("broadbandDrive","bad");assertFalse(old.importSceneJson(json.toString()));assertEquals(before,old.exportSceneJson())
 }
 @Test fun bothPoliciesAndSparseEqSurviveDiskReopen() {
  for(mode in BroadbandDrive.entries) {
   val v=populated();v.setBroadbandDrive(mode);v.setEqBand(v.speakers.value.single().id,1000,6f)
   val before=level(v);val at125=level(v,125);val at1000=level(v,1000)
   val store=LocalProjectStore(folder.newFolder(mode.name));store.save("recovery",v.exportSceneJson(false),v::resolveProjectAsset)
   val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("recovery")!!.project))
   assertEquals(mode,fresh.broadbandDrive.value);assertEquals(before,level(fresh),0f)
   assertEquals(at125,level(fresh,125),0f);assertEquals(at1000,level(fresh,1000),0f)
   assertEquals(mapOf(1000 to 6f),fresh.dspMap.value.values.single().eqBands)
  }
 }
 @Test fun legacyEqProjectKeepsOffsetsButUnsetBandsAreNeutral() {
  val v=populated();v.setSignalType("BAND");val baseline=level(v,125);val at1000=level(v,1000)
  v.setEqBand(v.speakers.value.single().id,1000,6f)
  val legacy=JSONObject(v.exportSceneJson()).apply {put("version",30);remove("broadbandDrive")}
  val fresh=model();assertTrue(fresh.importSceneJson(legacy.toString()))
  assertEquals(baseline,level(fresh,125),2e-5f);assertEquals(6f,level(fresh,1000)-at1000,2e-5f)
 }
 @Test fun changingDriveInvalidatesResultsAndSupportsUndoRedo() {
  val v=populated();val fixed=level(v)
  v.recalculateSignal();dispatcher.scheduler.runCurrent();assertEquals(PredictionState.CURRENT,v.predictionState.value)
  v.setBroadbandDrive(BroadbandDrive.PER_BAND_REFERENCE);assertEquals(PredictionState.STALE,v.predictionState.value);assertTrue(v.heatmap.value.isEmpty())
  val legacy=level(v);assertTrue(v.undoScene());assertEquals(BroadbandDrive.DEFAULT,v.broadbandDrive.value);assertEquals(fixed,level(v),0f)
  assertTrue(v.redoScene());assertEquals(BroadbandDrive.PER_BAND_REFERENCE,v.broadbandDrive.value);assertEquals(legacy,level(v),0f)
  v.clearAll();assertEquals(BroadbandDrive.DEFAULT,v.broadbandDrive.value)
 }
}
