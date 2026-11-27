package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.DiffuseDiagnostics
import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.*
import java.io.File

/** Bounded opt-in diagnostic of the original full-size scene. Never a timing gate. */
@OptIn(ExperimentalCoroutinesApi::class)
class GeometryTraversalBenchmarkTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun matchSpacedReceiversAndOptionalDiskReopen() {
        val dispatcher=StandardTestDispatcher();Dispatchers.setMain(dispatcher)
        val vm=SceneViewModel(PredictionRunner {it.calculate()},dispatcher)
        try {
            val root=File("../validation").let {if(it.isDirectory) it else File("../../validation")}
            val out=File(requireNotNull(System.getenv("ACOUSTIC_COST_PROFILE_DIR")));out.mkdirs()
            val results=JSONObject()
            for((label,path) in listOf(
                "bounded" to "distinct-array-filters/results/01-before",
                "offset" to "distinct-array-filters/results/exploratory-offset-corners/01-before")) {
                val folder=File(root,path);assertTrue(vm.importSceneJson(File(folder,"fixture.json").readText()))
                val expected=JSONObject(File(folder,"observation.json").readText())
                val mesh=vm.captureCoveragePlan().prepare().receivers()
                assertEquals(333,mesh.size)
                val count=(System.getenv("ACOUSTIC_GEOMETRY_RECEIVERS") ?: "3").toInt()
                require(count==3 || count==33)
                val indices=List(count){it*332/(count-1)}
                val points=indices.map {i->val p=mesh[i];HeatCell(p.x,p.z,Float.NaN,renderY=p.y)}
                val request=vm.capturePredictionRequest(PredictionTicket(0,0,PredictionChannel.COVERAGE),vm.speakers.value,points)
                val diagnostics=if(System.getenv("ACOUSTIC_CACHE_PROFILE")=="true") DiffuseDiagnostics() else null;val start=System.nanoTime()
                val prediction=if(diagnostics==null) request.calculate() else request.calculateProfiled(diagnostics)
                val row=JSONObject(diagnostics?.toJson() ?: "{}").put("totalSeconds",(System.nanoTime()-start)/1e9).put("profilingEnabled",diagnostics!=null)
                    .put("indices",JSONArray(indices)).put("levelsDb",JSONArray()).put("powers",JSONArray())
                for(i in indices.indices) {
                    val index=indices[i];val level=prediction.levelAt(i);val power=prediction.totalPowerAt(i)
                    assertEquals(expected.getJSONArray("levelsDb").getDouble(index),level.toDouble(),.001)
                    val ref=expected.getJSONArray("powers").getDouble(index)
                    assertEquals(ref,power,maxOf(1e-10,kotlin.math.abs(ref)*1e-9))
                    row.getJSONArray("levelsDb").put(level);row.getJSONArray("powers").put(power)
                }
                if(System.getenv("ACOUSTIC_GEOMETRY_REOPEN")=="true") {
                    val store=LocalProjectStore(temporary.newFolder(label))
                    store.save("scene",vm.exportSceneJson(false),vm::resolveProjectAsset)
                    val fresh=SceneViewModel(PredictionRunner {it.calculate()},dispatcher)
                    try {
                        assertTrue(fresh.importPreparedProject(store.loadPrepared("scene")!!.project))
                        assertEquals(vm.dspMap.value,fresh.dspMap.value)
                        val reopened=fresh.capturePredictionRequest(PredictionTicket(0,0,PredictionChannel.COVERAGE),fresh.speakers.value,points).calculate()
                        for(i in indices.indices) {
                            assertEquals(prediction.levelAt(i),reopened.levelAt(i),0f)
                            assertEquals(prediction.totalPowerAt(i),reopened.totalPowerAt(i),0.0)
                        }
                        row.put("diskSaveReopenMatched",true)
                    } finally {fresh.cancelAnalysisJobs()}
                }
                results.put(label,row)
                File(out,"costs.json").writeText(results.toString(2))
                println("$label: $row")
            }
        } finally {vm.cancelAnalysisJobs();dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    }
}
