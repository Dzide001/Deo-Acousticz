package com.droidacoustic.pro.scene
import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import com.droidacoustic.pro.geometry.*
import org.junit.Test
import org.junit.Assert.*
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverDiffuseIntegrationTest {
 @Test fun scatteredStageWithSixShadowingBlocksFitsPredictionBudget() {
  val scheduler=TestCoroutineScheduler();val dispatcher=StandardTestDispatcher(scheduler)
  Dispatchers.setMain(dispatcher)
  val errors=mutableListOf<Throwable>()
  val v=SceneViewModel(PredictionRunner {r->try {r.calculate()} catch(t: Throwable) {errors+=t;throw t}},dispatcher)
  try {
   v.setVenueSize(28f,28f);v.setVenueWallHeight(7.75f)
   v.setStageCenter(0f,-10f);v.setStageSize(10f,4f);v.setStageHeight(1f)
   v.setStageBoundaryRole(AcousticBoundaryRole.RIGID_REFLECTOR)
   v.setStageMaterial(BoundaryMaterial.IDEAL_RIGID.withScattering(MaterialScattering.create(listOf(0f to listOf(125 to .5f,4000 to .5f)))))
   listOf(-3.7642643f to -3.818691f,1.1871142f to 3.3902957f,9.997512f to -11.462248f,10.578744f to -2.0743632f,-11.525745f to -2.7747993f,-7.193883f to 4.071162f).forEachIndexed {i,(x,z)->
    v.addVenueBlock("OBSTACLE",x,z);v.setVenueBlockSize(i,6f,3f)
    v.setVenueBlockHeight(i,1f);v.setVenueBlockThickness(i,1f)
   }
   v.setSpeakerPreset("point_small")
   v.addSpeaker(1.3f,4.1143765f);v.addSpeakerSource();v.addSpeaker(3.3014529f,4.1143765f)
   v.speakers.value.forEachIndexed {i,s->v.setSpeakerPosition(s.id,if(i==0) 1.3f else 3.3014529f,1.4f,4.1143765f);v.setSpeakerPan(s.id,if(i==0) -90f else 0f);v.setSpeakerArrayAim(s.id,if(i==0) 10f else 15f)}
   v.setSignalType("SPECTRUM");v.setSignalResolution(24);v.setBandHz(8000)
   v.setSignalBandwidthOct(1f/3);v.setGroundReflectionEnabled(false)
   v.recalculateSignal();scheduler.runCurrent()
   if(errors.isNotEmpty()) throw AssertionError("Prediction failure",errors.first())
   assertTrue(v.heatmap.value.isNotEmpty());assertNotNull(v.coverageStatistics.value)
  } finally {v.cancelAnalysisJobs();scheduler.runCurrent();Dispatchers.resetMain()}
 }
}
