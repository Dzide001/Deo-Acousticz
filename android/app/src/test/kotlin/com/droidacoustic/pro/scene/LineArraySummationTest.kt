package com.droidacoustic.pro.scene

import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import kotlin.math.*

/** Physical array predictions through SceneViewModel.capturePredictionRequest. */
@OptIn(ExperimentalCoroutinesApi::class)
class LineArraySummationTest {
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun vm(band:Int=63)=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}.apply {
        setBandHz(band);setSignalType("BAND");setSignalDispersionEnabled(false)
        setSignalBandwidthOct(1f/12);setSignalInterferenceEnabled(true)
    }
    private fun array(n:Int=8,spacing:Float=.2f)=PlacedSpeaker(0,0f,0f,heightM=8f,sensitivity=100f,arrayElements=n,arraySpacingM=spacing)
    private fun output(v:SceneViewModel,sources:List<PlacedSpeaker>,x:Float=200f,y:Float=8f,
        dsp:Map<Int,SpeakerDsp> = emptyMap(),cells:List<HeatCell> = listOf(HeatCell(x,0f,Float.NaN,renderY=y)))=
        v.capturePredictionRequest(PredictionTicket(v.revision.value,1,PredictionChannel.COVERAGE),sources,cells,
            dsps=dsp,retainBandPowers=true).calculate()
    private fun level(v:SceneViewModel,s:PlacedSpeaker)=output(v,listOf(s)).levelAt(0)

    @Test fun farFieldArrayGainFollowsPressureSummation() {
        val v=vm();val one=level(v,array(1))
        for(n in listOf(2,3,4,6,8,12,16)) assertEquals("$n elements",(20*log10(n.toDouble())).toFloat(),level(v,array(n))-one,.01f)
    }
    @Test fun doublingTheBoxCountAddsSixDecibels() {
        val v=vm();assertEquals(6.0206f,level(v,array(8))-level(v,array(4)),.01f)
    }
    @Test fun onAxisGainIsMonotonicForThisLowFrequencyFarFieldFixture() {
        val v=vm();val levels=(1..16).map {level(v,array(it))}
        assertTrue(levels.zipWithNext().all {(a,b)->b>=a})
    }
    @Test fun taperAndGainReachEveryElement() {
        val v=vm();val s=array();val flat=level(v,s)
        assertTrue(level(v,s.copy(arrayEdgeTaperDb=6f))<flat-1)
        assertEquals(5f,output(v,listOf(s),dsp=mapOf(0 to SpeakerDsp(0,gainDb=5f))).levelAt(0)-flat,.0001f)
    }
    @Test fun wholeArrayPolarityAndCommonDelayDoNotChangeItsOwnEnergy() {
        val v=vm(1000);val s=array();val normal=level(v,s)
        for(dsp in listOf(SpeakerDsp(0,polarity=true),SpeakerDsp(0,delayMs=17f)))
            assertEquals(normal,output(v,listOf(s),dsp=mapOf(0 to dsp)).levelAt(0),.0001f)
    }
    @Test fun interSourceInterferenceOffDoesNotMakeArrayElementsIndependent() {
        val v=vm();val coherent=level(v,array());v.setSignalInterferenceEnabled(false)
        assertEquals(coherent,level(v,array()),.0001f)
    }
    @Test fun electronicSteeringPreservesPosesAndMechanicalAimMovesThem() {
        val s=array();assertEquals(s.elementPoses(),s.copy(arraySteerDeg=15f).elementPoses())
        assertNotEquals(s.elementPoses(),s.copy(arrayAimDeg=15f).elementPoses())
        val v=vm(1000)
        assertNotEquals(output(v,listOf(s),x=40f,y=1.2f).levelAt(0),output(v,listOf(s.copy(arraySteerDeg=15f)),x=40f,y=1.2f).levelAt(0))
    }
    @Test fun twinArrayCancellationAndDelayedBandResidualUseProductionRequests() {
        val v=vm(1000);val s=array();val twin=s.copy(id=1)
        val single=output(v,listOf(s)).totalPowerAt(0)
        val opposed=output(v,listOf(s,twin),dsp=mapOf(1 to SpeakerDsp(1,polarity=true))).totalPowerAt(0)
        val delayed=output(v,listOf(s,twin),dsp=mapOf(1 to SpeakerDsp(1,delayMs=.5f))).totalPowerAt(0)
        assertTrue(opposed<=single*1e-12);assertTrue(delayed>0 && delayed<single*.02)
    }
    @Test fun splayWithGenericDirectivityChangesCoverage() {
        val v=vm(1000);v.setSignalDispersionEnabled(true)
        val s=array();val straight=output(v,listOf(s),x=60f).levelAt(0)
        assertTrue(output(v,listOf(s.copy(arrayInterBoxSplayDeg=6f)),x=60f).levelAt(0)<straight)
    }
    @Test fun highFrequencyCoverageVariesMoreRapidlyForThisStraightArrayFixture() {
        val cells=(0..40).map {HeatCell(30f,0f,Float.NaN,renderY=.5f+it*.35f)}
        fun variation(hz:Int):Double {
            val result=output(vm(hz),listOf(array(12)),cells=cells)
            return (0 until 40).map {abs(result.levelAt(it+1)-result.levelAt(it)).toDouble()}.average()
        }
        assertTrue(variation(8000)>2*variation(63))
    }
    @Test fun nearArrayAndBandSizeSpacingMatrixRemainFinite() {
        assertTrue(output(vm(1000),listOf(array()),x=.001f).levelAt(0).isFinite())
        for(hz in SceneViewModel.SUPPORTED_BANDS_HZ) {
            val v=vm(hz)
            for(n in listOf(2,8,16)) for(spacing in listOf(.05f,.2f,.6f)) {
                val result=output(v,listOf(array(n,spacing)),cells=listOf(1f,25f,150f).map {HeatCell(it,0f,Float.NaN,renderY=1.2f)})
                for(i in 0 until result.receiverCount) assertTrue("$hz Hz, $n boxes, $spacing m, receiver $i",result.levelAt(i).isFinite())
            }
        }
    }
}
