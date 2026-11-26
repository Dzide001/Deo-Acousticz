package com.droidacoustic.pro.scene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class FloorBounceTest {
    private val models = mutableListOf<SceneViewModel>()
    @Before fun setup() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun cleanup() { models.forEach { it.cancelAnalysisJobs() }; Dispatchers.resetMain() }
    private fun vm() = SceneViewModel(predictionDispatcher = Dispatchers.Main).also { models += it }
    private val box = PlacedSpeaker(0, 0f, 0f, heightM = 0f)

    @Test fun groundIsExplicitAndPersists() {
        val v = vm()
        assertFalse(v.groundReflectionEnabled.value)
        v.setGroundReflectionEnabled(true)
        val copy = vm()
        assertTrue(copy.importSceneJson(v.exportSceneJson()))
        assertTrue(copy.groundReflectionEnabled.value)
    }

    @Test fun productionGroundUsesUnclippedImagePressure() {
        val v = vm()
        v.setFloorAbsorption(0.01f)
        val free = v.predictBandDb(listOf(box), 10f, 0f, 0f, 1000)
        v.setGroundReflectionEnabled(true)
        val ground = v.predictBandDb(listOf(box), 10f, 0f, 0f, 1000)
        assertEquals(6.0f, ground - free, 0.03f)
        v.setGroundReflectionEnabled(false)
        assertEquals(free, v.predictBandDb(listOf(box), 10f, 0f, 0f, 1000), 0.001f)
    }

    @Test fun productionBandwidthIntegratesDelayedInterference() {
        val v = vm()
        val pair = listOf(box, box.copy(id = 1))
        val dsp = mapOf(0 to SpeakerDsp(0), 1 to SpeakerDsp(1, delayMs = 0.5f))
        v.setSignalBandwidthOct(1f / 12f)
        val narrow = v.predictBandDb(pair, 10f, 0f, 0f, 1000, dsp)
        v.setSignalBandwidthOct(1f)
        val wide = v.predictBandDb(pair, 10f, 0f, 0f, 1000, dsp)
        assertTrue("A wider band fills the 1 kHz cancellation: $narrow vs $wide", wide > narrow + 10f)
    }

    @Test fun olderProjectsDefaultToFreeField() {
        val v = vm()
        val json = org.json.JSONObject(v.exportSceneJson()).apply { remove("groundReflectionEnabled") }
        v.setGroundReflectionEnabled(true)
        assertTrue(v.importSceneJson(json.toString()))
        assertFalse(v.groundReflectionEnabled.value)
    }

    @Test fun displayResolutionDoesNotChangePhysicalResult() {
        val v = vm()
        val speakers = listOf(box, box.copy(id = 1, x = 2f))
        v.setSignalResolution(3)
        val low = v.predictBandDb(speakers, 10f, 1f, 0f, 1000)
        v.setSignalResolution(96)
        assertEquals(low, v.predictBandDb(speakers, 10f, 1f, 0f, 1000), 0.0001f)
    }
}
