package com.droidacoustic.pro.scene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val models = mutableListOf<SceneViewModel>()
    @Before fun setup() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun cleanup() { models.forEach { it.cancelAnalysisJobs() }; Dispatchers.resetMain() }
    private fun vm() = SceneViewModel(predictionDispatcher = Dispatchers.Main).also { models += it }
    private fun data(level: Float = 93f) = ClfData("point_small", patterns = listOf(
        ClfPolarPattern(1000, listOf(0f), listOf(-90f, 90f), arrayOf(floatArrayOf(0f, 0f)))),
        sensitivityDbByBand = mapOf(1000 to level))
    private fun populated(): SceneViewModel = vm().apply {
        assertTrue(importClfJsonData(ClfParser.toJson(data())))
        setSpeakerPreset("point_small")
        addSpeaker(0f, 0f)
    }
    private fun level(v: SceneViewModel) = v.predictBandDb(v.speakers.value, 10f, 1.4f, 0f, 1000)

    @Test fun diskSaveAndFreshReopenRecoverCalibrationAndPrediction() {
        val original = populated()
        val before = level(original)
        LocalProjectStore(folder.root).save("project_A", original.exportSceneJson(false), original::resolveProjectAsset)
        val recovered = LocalProjectStore(folder.root).loadPrepared("project_A")!!
        val fresh = vm()
        assertTrue(fresh.importPreparedProject(recovered.project))
        assertEquals(data(), fresh.getClfData("point_small"))
        assertEquals(before, level(fresh), 1e-5f)
        assertTrue(fresh.assetRecoveryProblems.value.isEmpty())
    }
    @Test fun missingDiskAssetPreservesSceneAndReportsUnavailable() {
        val original = populated()
        val store = LocalProjectStore(folder.root)
        store.save("recovery", original.exportSceneJson(false), original::resolveProjectAsset)
        folder.root.resolve("assets").listFiles()!!.forEach { assertTrue(it.delete()) }
        val fresh = vm()
        assertTrue(fresh.importSceneJson(store.load("recovery")!!.first))
        assertEquals(original.speakers.value, fresh.speakers.value)
        assertTrue(level(fresh).isNaN())
        assertTrue(fresh.assetRecoveryProblems.value.isNotEmpty())
        assertFalse(fresh.importClfJsonData(ClfParser.toJson(data(120f))))
        assertTrue(level(fresh).isNaN())
        assertTrue(fresh.importClfJsonData(ClfParser.toJson(data())))
        assertEquals(level(original), level(fresh), 1e-5f)
    }
    @Test fun diskCorruptionUsesPreviousCheckpoint() {
        val v = populated()
        val store = LocalProjectStore(folder.root)
        store.save("recovery", v.exportSceneJson(false), v::resolveProjectAsset)
        v.moveListener(10f, 2f)
        store.save("recovery", v.exportSceneJson(false), v::resolveProjectAsset)
        folder.root.resolve("recovery.current").writeText("partial write")
        val result = LocalProjectStore(folder.root).load("recovery")!!
        assertTrue(result.second)
        val fresh = vm()
        assertTrue(fresh.importSceneJson(result.first))
        assertEquals(0f, fresh.listener.value.x, 0f)
        assertEquals(data(), fresh.getClfData("point_small"))
    }
    @Test fun undoAndRedoRetainExactMeasuredRevisions() {
        val v = populated()
        val original = level(v)
        v.moveListener(5f, 2f) // checkpoint at 93 dB reference
        assertTrue(v.importClfJsonData(ClfParser.toJson(data(103f))))
        v.moveListener(7f, 3f) // checkpoint at 103 dB reference
        assertTrue(v.undoScene())
        assertEquals(original + 10f, level(v), 1e-4f)
        assertTrue(v.undoScene())
        assertEquals(original, level(v), 1e-4f)
        assertTrue(v.redoScene())
        assertEquals(original + 10f, level(v), 1e-4f)
    }
    @Test fun malformedLoadLeavesSceneDataAndHistoryUnchanged() {
        val v = populated()
        val before = v.exportSceneJson()
        val broken = JSONObject(before).put("speakers", "invalid").put("temperatureC", 40)
        assertFalse(v.importSceneJson(broken.toString()))
        assertEquals(before, v.exportSceneJson())
        assertEquals(data(), v.getClfData("point_small"))
    }
    @Test fun tamperedEmbeddedAssetIsNotReplacedByPreviousRegistry() {
        val v = populated()
        val tampered = JSONObject(v.exportSceneJson())
        tampered.getJSONObject("clfRegistry").put("point_small", JSONObject(ClfParser.toJson(data(120f))))
        assertTrue(v.importSceneJson(tampered.toString()))
        assertNull(v.getClfData("point_small"))
        assertTrue(level(v).isNaN())
        assertTrue(v.assetRecoveryProblems.value.single().reason.contains("mismatched"))
    }
}
