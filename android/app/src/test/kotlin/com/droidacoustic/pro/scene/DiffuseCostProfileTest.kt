package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.DiffuseDiagnostics
import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Explicit -PacousticCostProfile=true only: profile the real production requests. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiffuseCostProfileTest {
    @Test fun listenerCoverageAndIncidentCacheCosts() {
        val dispatcher=StandardTestDispatcher();Dispatchers.setMain(dispatcher)
        val vm=SceneViewModel(PredictionRunner {it.calculate()},dispatcher)
        try {
            val fixture=File("../validation/diffuse-repeat/results/01-before/fixture/full-venue-diffuse.json")
                .let {if(it.isFile) it else File("../../validation/diffuse-repeat/results/01-before/fixture/full-venue-diffuse.json")}
            assertTrue(vm.importSceneJson(fixture.readText()))
            val out=File(requireNotNull(System.getenv("ACOUSTIC_COST_PROFILE_DIR")));out.mkdirs()
            val result=JSONObject()
            for(channel in listOf(PredictionChannel.LISTENER,PredictionChannel.COVERAGE)) {
                val listener=vm.listener.value
                val request=if(channel==PredictionChannel.LISTENER)
                    vm.capturePredictionRequest(PredictionTicket(0,0,channel),vm.speakers.value,
                        listOf(HeatCell(listener.x,listener.z,Float.NaN,renderY=listener.earHeightM)),true)
                else vm.capturePredictionRequest(PredictionTicket(0,0,channel),vm.speakers.value,
                    emptyList(),coveragePlan=vm.captureCoveragePlan())
                val diagnostics=DiffuseDiagnostics();val start=System.nanoTime()
                val prediction=request.calculateProfiled(diagnostics)
                val seconds=(System.nanoTime()-start)/1e9
                val row=JSONObject(diagnostics.toJson()).put("totalSeconds",seconds)
                if(channel==PredictionChannel.COVERAGE) {
                    assertEquals(333,prediction.geometry!!.receivers().size)
                    assertEquals(56.71420139630636,prediction.statistics!!.overall.meanDb!!,1e-5)
                    row.put("meanDb",prediction.statistics!!.overall.meanDb)
                } else {
                    assertTrue(prediction.levelAt(0).isFinite())
                    row.put("levelDb",prediction.levelAt(0))
                }
                result.put(channel.name.lowercase(),row)
                println("$channel costs: $row")
            }
            File(out,"costs.json").writeText(result.toString(2))
        } finally {vm.cancelAnalysisJobs();dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    }
}
