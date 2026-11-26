package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerCalibrationTest {
    private val models = mutableListOf<SceneViewModel>()
    @Before fun setup() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun cleanup() { models.forEach { it.cancelAnalysisJobs() }; Dispatchers.resetMain() }
    private fun vm() = SceneViewModel(predictionDispatcher = Dispatchers.Main).also { models += it }
    private fun data(levels: Map<Int, Float> = mapOf(1000 to 80f),
        calibration: ReferenceCalibration = ReferenceCalibration(),
        grid: MeasurementGrid = MeasurementGrid.THIRD_OCTAVE) = ClfData("point_small",
        patterns = levels.keys.map { hz -> ClfPolarPattern(hz, listOf(0f), listOf(-90f, 90f), arrayOf(floatArrayOf(0f, 0f))) },
        sensitivityDbByBand = levels, calibration = calibration, measurementGrid = grid)
    private fun install(v: SceneViewModel, d: ClfData): PlacedSpeaker {
        assertTrue(v.importClfJsonData(ClfParser.toJson(d)))
        v.setSpeakerPreset("point_small")
        v.addSpeaker(0f, 0f)
        return v.speakers.value.single()
    }
    private fun level(v: SceneViewModel, s: PlacedSpeaker, hz: Int = 1000) =
        v.predictBandDb(listOf(s), 10f, s.heightM, 0f, hz)

    @Test fun calibratedReferenceAndRelativeGainReachAndroidSolver() {
        val v = vm()
        val s = install(v, data(calibration = ReferenceCalibration(10.0, DriveUnit.VOLTS_RMS, 2.83, "Lab fixture")))
        assertEquals(100f, v.referenceLevelDb(s, 1000), 1e-5f)
        val base = level(v, s)
        assertTrue(base in 79f..81f)
        v.setSignalLevelDbu(6f)
        assertEquals(6f, level(v, s) - base, 1e-4f)
        assertTrue(v.predictionDataWarnings().any { "electrical reference declared, not independently calibrated" in it })
    }
    @Test fun responseAndDirectivityHolesDoNotBorrowOrUseScalarSensitivity() {
        val v = vm()
        val s = install(v, data(mapOf(1000 to 80f, 1600 to 90f)))
        assertTrue(level(v, s, 1250).isNaN())
        assertTrue(level(v, s, 8000).isNaN())
        assertTrue(v.importClfJsonData(ClfParser.toJson(data().copy(sensitivityDbByBand = emptyMap()))))
        assertTrue(level(v, s).isNaN())
        assertTrue(v.referenceLevelDb(s, 1000).isNaN())
    }
    @Test fun wideBandWithPartialDataIsUnavailableRatherThanUnderestimated() {
        val v = vm()
        val s = install(v, data())
        assertTrue(level(v, s).isFinite())
        v.setSignalBandwidthOct(1f)
        assertTrue(level(v, s).isNaN())
        assertTrue(v.predictionDataWarnings().any { "800, 1250" in it })
    }
    @Test fun calibrationAndMeasurementGridSurviveProjectRoundTrip() {
        val original = data(mapOf(500 to 70f, 1000 to 80f, 2000 to 90f),
            ReferenceCalibration(2.0, DriveUnit.WATTS, 1.0, "Test measurement"), MeasurementGrid.OCTAVE)
        val v = vm()
        val s = install(v, original)
        val copy = vm()
        assertTrue(copy.importSceneJson(v.exportSceneJson()))
        assertEquals(original, copy.getClfData("point_small"))
        assertEquals(level(v, s), level(copy, copy.speakers.value.single()), 1e-5f)
        assertTrue(copy.speakers.value.single().requiresMeasuredData)
    }
    @Test fun lostEmbeddedDataDoesNotBecomeGenericEvenWhenRegistryWasPreviouslyLoaded() {
        val v = vm()
        val s = install(v, data())
        val saved = JSONObject(v.exportSceneJson()).apply { remove("clfRegistry") }
        assertTrue(v.importSceneJson(saved.toString()))
        assertTrue(level(v, v.speakers.value.single()).isNaN())
        assertTrue(v.predictionDataWarnings().any { "Reimport" in it })
        assertTrue(v.exportProjectReportJson().contains("measured speaker data unavailable"))
    }
    @Test fun clearingMeasuredDataInvalidatesExistingSources() {
        val v = vm()
        install(v, data())
        v.clearClfData("point_small")
        assertTrue(level(v, v.speakers.value.single()).isNaN())
    }
    @Test fun provenanceAndDeclaredCalibrationSurviveReopenAndAreVisibleInReports() {
        val provenance=SpeakerProvenance(SpeakerDataOrigin.MANUFACTURER_DECLARED,"Test fixture","https://example.test/source",
            SpeakerProvenance.hash(byteArrayOf(1,2,3)),"fixture-v1","flat","File declaration, unverified")
        val original=data(calibration=ReferenceCalibration(1.0,DriveUnit.WATTS,1.0)).copy(provenance=provenance)
        val v=vm();install(v,original)
        val reopened=vm(); assertTrue(reopened.importSceneJson(v.exportSceneJson()))
        assertEquals(original,reopened.getClfData("point_small"))
        assertTrue(reopened.predictionDataWarnings().any {"not independently verified" in it})
        val report=JSONObject(reopened.exportProjectReportJson()).getJSONArray("speakerDataValidation").getJSONObject(0)
        assertEquals("DECLARED",report.getString("calibrationStatus"))
        assertEquals("pending",report.getString("externalValidation"))
        assertEquals(provenance.sourceSha256,report.getJSONObject("provenance").getString("sourceSha256"))
    }

    private fun waitForListener(v: SceneViewModel) {
        v.recalculateSignal()
        val deadline = System.currentTimeMillis() + 5000
        fun complete() = v.results.value.isNotEmpty() &&
            (v.results.value.first().splDb.isNaN() || v.combinedSplDb.value != null)
        while (!complete() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("listener calculation did not complete", complete())
    }
    @Test fun unavailableListenerDoesNotPublishANumberOrBreakJsonExport() {
        val v = vm()
        install(v, data().copy(sensitivityDbByBand = emptyMap()))
        waitForListener(v)
        assertTrue(v.results.value.single().splDb.isNaN())
        assertNull(v.combinedSplDb.value)
        assertFalse(JSONObject(v.exportProjectReportJson()).has("combinedSplDb"))
    }
    @Test fun losingDataClearsPreviouslyCalculatedResultsEvenInManualMode() {
        val v = vm()
        install(v, data())
        waitForListener(v)
        assertTrue(v.results.value.single().splDb.isFinite())
        assertFalse(v.signalAutoCalculate.value)
        v.clearClfData("point_small")
        assertTrue(v.results.value.isEmpty())
        assertNull(v.combinedSplDb.value)
        assertTrue(v.heatmap.value.isEmpty())
    }
    @Test fun legacyMetadataIsUnverifiedAndInvalidCalibrationIsRejected() {
        val json = JSONObject(ClfParser.toJson(data())).apply { remove("referenceCalibration"); remove("measurementGrid") }
        val legacy = ClfParser.parseJson(json.toString())!!
        assertFalse(legacy.calibration.electricalReferenceKnown)
        json.put("referenceCalibration", JSONObject().put("distanceM", -1).put("driveUnit", "WATTS").put("driveValue", 1))
        assertNull(ClfParser.parseJson(json.toString()))
    }
}
