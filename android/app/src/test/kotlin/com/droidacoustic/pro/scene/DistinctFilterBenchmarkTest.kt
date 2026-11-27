package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Explicit manual benchmark; excluded from normal tiers. No timing pass/fail threshold. */
@OptIn(ExperimentalCoroutinesApi::class)
class DistinctFilterBenchmarkTest {
    @Test fun independentArrayFiltersSurvivePredictionAndReopen() {
        val dispatcher=StandardTestDispatcher();Dispatchers.setMain(dispatcher)
        val models=mutableListOf<SceneViewModel>()
        fun model()=SceneViewModel(PredictionRunner {it.calculate()},dispatcher).also {models+=it}
        try {
            val input=File("../validation/diffuse-repeat/results/01-before/fixture/full-venue-diffuse.json")
                .let {if(it.isFile) it else File("../../validation/diffuse-repeat/results/01-before/fixture/full-venue-diffuse.json")}
            val vm=model();assertTrue(vm.importSceneJson(input.readText()))
            val filters=listOf(DspFilter(FilterKind.HIGH_PASS,80.0),DspFilter(FilterKind.LOW_PASS,8000.0))+
                listOf(125.0 to 2.0,250.0 to -1.0,500.0 to 1.5,1000.0 to -3.0,2000.0 to 2.0,4000.0 to -2.0)
                    .map {(hz,gain)->DspFilter(FilterKind.PEAK,hz,2.0,gain)}
            // Four independent gain/phase responses, including mixed section sample rates.
            vm.speakers.value.forEachIndexed {array,speaker ->
                val chain=filters.mapIndexed {section,f -> f.copy(
                    frequencyHz=f.frequencyHz,
                    q=f.q,
                    gainDb=if(f.kind==FilterKind.PEAK) f.gainDb+array*.5 else 0.0,
                    sampleRateHz=if(array==1 || (array==3 && section%2==1)) 96000.0 else 48000.0)}
                vm.setFilters(speaker.id,DspFilterChain(chain))
            }

            assertEquals(64,vm.speakers.value.sumOf {it.arrayElements})
            val output=File(requireNotNull(System.getenv("ACOUSTIC_COST_PROFILE_DIR")));output.mkdirs()
            val scene=vm.exportSceneJson(true);File(output,"fixture.json").writeText(scene)
            val result=JSONObject().put("filtersPerEmitter",8).put("emitters",64)
                .put("frequencyHz",1000).put("profilingEnabled",false)
            val listener=vm.listener.value
            val listenerRequest=vm.capturePredictionRequest(PredictionTicket(0,0,PredictionChannel.LISTENER),vm.speakers.value,
                listOf(HeatCell(listener.x,listener.z,Float.NaN,renderY=listener.earHeightM)),true)
            var start=System.nanoTime();val listened=listenerRequest.calculate()
            result.put("listenerSeconds",(System.nanoTime()-start)/1e9).put("listenerDb",listened.levelAt(0))
            val sourceLevels=JSONArray();vm.speakers.value.indices.forEach {i->
                assertTrue(listened.sourceLevelAt(0,i).isFinite());sourceLevels.put(listened.sourceLevelAt(0,i))}
            result.put("sourceLevelsDb",sourceLevels)
            val request=vm.capturePredictionRequest(PredictionTicket(0,0,PredictionChannel.COVERAGE),vm.speakers.value,
                emptyList(),coveragePlan=vm.captureCoveragePlan())
            start=System.nanoTime();val coverage=request.calculate()
            result.put("coverageSeconds",(System.nanoTime()-start)/1e9)
            assertEquals(333,coverage.receiverCount);assertEquals(100.0,coverage.statistics!!.overall.validAreaPercent,1e-9)
            val levels=JSONArray();val powers=JSONArray()
            for(i in 0 until coverage.receiverCount) {
                assertTrue(coverage.levelAt(i).isFinite());levels.put(coverage.levelAt(i));powers.put(coverage.totalPowerAt(i))
            }
            result.put("receiverCount",333).put("meanDb",coverage.statistics!!.overall.meanDb)
                .put("validAreaPercent",coverage.statistics!!.overall.validAreaPercent)
                .put("levelsDb",levels).put("powers",powers)
            // Native and JVM reopen the exact same portable project, including all DSP settings.
            val reopened=model();assertTrue(reopened.importSceneJson(scene))
            assertEquals(scene,reopened.exportSceneJson(true))
            File(output,"observation.json").writeText(result.toString(2))
            println("Distinct-filter 64-emitter listener=${result.getDouble("listenerSeconds")}s coverage=${result.getDouble("coverageSeconds")}s mean=${result.getDouble("meanDb")}dB")
        } finally {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    }
}
