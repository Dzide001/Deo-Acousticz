package com.droidacoustic.pro.scene

import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import kotlin.math.*

/** Android adapter checks run the same immutable request used by listener/maps. */
@OptIn(ExperimentalCoroutinesApi::class)
class SummationTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun vm()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}.apply {
        setBandHz(1000);setSignalType("BAND");setSignalDispersionEnabled(false)
        setSignalBandwidthOct(1f/12);setSignalInterferenceEnabled(true)
    }
    private fun source(id:Int=0,level:Float=100f)=PlacedSpeaker(id,0f,0f,heightM=15f,sensitivity=level)
    private fun output(v:SceneViewModel,sources:List<PlacedSpeaker>,dsp:Map<Int,SpeakerDsp> = emptyMap())=
        v.capturePredictionRequest(PredictionTicket(v.revision.value,1,PredictionChannel.COVERAGE),sources,
            listOf(HeatCell(10f,0f,Float.NaN,renderY=15f)),dsps=dsp,retainBandPowers=true).calculate()

    @Test fun emptyRequestHasZeroEnergy() {
        assertEquals(0.0,output(vm(),emptyList()).totalPowerAt(0),0.0)
    }
    @Test fun singleSourceGainReachesProductionResult() {
        val v=vm();val baseline=output(v,listOf(source())).levelAt(0)
        assertEquals(4f,output(v,listOf(source()),mapOf(0 to SpeakerDsp(0,gainDb=4f))).levelAt(0)-baseline,.0001f)
    }
    @Test fun identicalCoherentSourcesAddExactlySixDecibels() {
        val v=vm();val one=output(v,listOf(source())).levelAt(0)
        assertEquals(6.0206f,output(v,listOf(source(),source(1))).levelAt(0)-one,.0001f)
    }
    @Test fun invertedCoincidentTwinCancelsInTheProductionBand() {
        val v=vm();val one=output(v,listOf(source())).totalPowerAt(0)
        val opposed=output(v,listOf(source(),source(1)),mapOf(1 to SpeakerDsp(1,polarity=true))).totalPowerAt(0)
        assertTrue("Cancellation must be checked in energy, not against the display floor",opposed<=one*1e-12)
    }
    @Test fun interferenceOffAddsSeparateSourcePowersAcrossAllBands() {
        val v=vm();v.setSignalInterferenceEnabled(false)
        for(hz in SceneViewModel.SUPPORTED_BANDS_HZ) {
            v.setBandHz(hz);val one=output(v,listOf(source())).levelAt(0)
            assertEquals("$hz Hz",3.0103f,output(v,listOf(source(),source(1))).levelAt(0)-one,.0001f)
        }
    }
    @Test fun quieterCoherentSourceHasTheAnalyticalPressureGain() {
        val v=vm();val one=output(v,listOf(source())).levelAt(0)
        assertEquals((20*log10(1.01)).toFloat(),output(v,listOf(source(),source(1,60f))).levelAt(0)-one,.0001f)
    }
    @Test fun halfPeriodDelayHasFiniteBandResidualRatherThanAnInventedBlend() {
        val v=vm();val ratios=mutableListOf<Double>()
        for(width in listOf(1f/12,1f/3)) {
            v.setSignalBandwidthOct(width)
            val single=output(v,listOf(source())).totalPowerAt(0)
            val pair=output(v,listOf(source(),source(1)),mapOf(1 to SpeakerDsp(1,delayMs=.5f))).totalPowerAt(0)
            val h=2.0.pow(width.toDouble()/2);val low=1000/h;val high=1000*h
            val b=PI*(high-low)*.0005
            val expected=2+2*cos(2*PI*(low+high)/2*.0005)*sin(b)/b
            assertEquals(expected,pair/single,1e-6);ratios+=pair/single
        }
        assertTrue(ratios[1]>ratios[0])
    }
    @Test fun productionDspAndCoherenceSettingsSurviveSaveReopen() {
        val v=vm();v.addSpeaker(0f,0f);v.addSpeaker(0f,0f)
        val id=v.speakers.value.last().id;v.setGain(id,-4f);v.setDelay(id,.5f);v.setPolarity(id,true)
        val store=LocalProjectStore(folder.root)
        fun power(m:SceneViewModel)=m.capturePredictionRequest(PredictionTicket(m.revision.value,1,PredictionChannel.COVERAGE),m.speakers.value,
            listOf(HeatCell(10f,0f,Float.NaN,renderY=1.2f)),retainBandPowers=true).calculate().totalPowerAt(0)
        for(coherent in listOf(true,false)) {
            v.setSignalInterferenceEnabled(coherent);val before=power(v)
            store.save("design",v.exportSceneJson(false),v::resolveProjectAsset)
            val fresh=vm();assertTrue(fresh.importPreparedProject(store.loadPrepared("design")!!.project))
            assertEquals(v.dspMap.value,fresh.dspMap.value);assertEquals(before,power(fresh),0.0)
        }
    }
}
