package com.droidacoustic.pro.prediction
import kotlin.test.*
class CoveragePresentationTest {
 @Test fun spectrumInspectionCannotRelabelBroadbandAsSelectedBand() {
  val low=CoveragePresentation.describe("SPECTRUM",125,PredictionState.CURRENT,false)
  assertEquals(low,CoveragePresentation.describe("SPECTRUM",8000,PredictionState.CURRENT,false))
  assertFalse(low.showFrequencyButtons)
  assertTrue(low.mapLabel.contains("broadband",ignoreCase=true))
  assertTrue(CoveragePresentation.describe("BAND",8000,PredictionState.CURRENT,false).mapLabel.contains("8000 Hz"))
  assertTrue(CoveragePresentation.describe("BAND",8000,PredictionState.CURRENT,false).showFrequencyButtons)
 }
 @Test fun staleAndFailedStatesOfferManualRecoveryInBothModes() {
  for(mode in listOf("BAND","SPECTRUM")) {
   val stale=CoveragePresentation.describe(mode,1000,PredictionState.STALE,false)
   assertTrue(stale.needsCalculation && stale.showAction && stale.actionEnabled);assertEquals("Recalculate",stale.action);assertTrue(stale.status.contains("required"))
   val failed=CoveragePresentation.describe(mode,1000,PredictionState.FAILED,true)
   assertTrue(failed.showAction && failed.actionEnabled && failed.needsCalculation);assertEquals("Retry calculation",failed.action)
  }
 }
 @Test fun automaticAndRunningStatesDoNotClaimResultsAreCurrent() {
  val pending=CoveragePresentation.describe("BAND",4000,PredictionState.STALE,true)
  assertTrue(pending.showAction);assertTrue(pending.status.contains("awaiting automatic"))
  for(auto in listOf(false,true)) {
   val p=CoveragePresentation.describe("BAND",4000,PredictionState.RUNNING,auto)
   assertFalse(p.actionEnabled);assertFalse(p.needsCalculation);assertTrue(p.status.contains("not ready"))
  }
  val p=CoveragePresentation.describe("BAND",4000,PredictionState.EMPTY,false)
  assertEquals("Calculate",p.action);assertTrue(p.needsCalculation)
 }
}
