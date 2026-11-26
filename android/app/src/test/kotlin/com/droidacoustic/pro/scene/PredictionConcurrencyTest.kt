package com.droidacoustic.pro.scene

import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PredictionConcurrencyTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val jobs = mutableListOf<Pending>()
    private val models = mutableListOf<SceneViewModel>()
    private data class Pending(val request: PredictionRequest, val done: CompletableDeferred<PredictionOutput>)
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() {
        jobs.forEach { if (!it.done.isCompleted) it.done.complete(it.request.calculate()) }
        scheduler.runCurrent()
        models.forEach { it.cancelAnalysisJobs() }
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }
    private fun vm(): SceneViewModel = SceneViewModel(PredictionRunner { request ->
        val pending = Pending(request, CompletableDeferred())
        jobs += pending
        // Model a non-cooperative backend which finishes despite cancellation.
        withContext(NonCancellable) { pending.done.await() }
    }, dispatcher).also {
        models += it
        it.addSpeaker(0f,0f)
    }
    private fun finish(p: Pending) { p.done.complete(p.request.calculate()); scheduler.runCurrent() }
    private fun latest(channel: PredictionChannel) = jobs.last { it.request.ticket.channel == channel }

    @Test fun lateListenerAndCoverageCannotOverwriteNewSceneResults() {
        val v = vm()
        v.recalculateSignal(); scheduler.runCurrent()
        val old = jobs.toList()
        v.setSignalLevelDbu(10f)
        assertNull(v.combinedSplDb.value)
        assertTrue(v.heatmap.value.isEmpty())
        v.recalculateSignal(); scheduler.runCurrent()
        finish(latest(PredictionChannel.LISTENER)); finish(latest(PredictionChannel.COVERAGE))
        val level = v.combinedSplDb.value
        val map = v.heatmap.value
        val geometry = v.coverageGeometry.value
        val statistics = v.coverageStatistics.value
        val validity = v.predictionValidity.value
        old.forEach(::finish)
        assertEquals(level, v.combinedSplDb.value)
        assertEquals(map, v.heatmap.value)
        assertSame(geometry, v.coverageGeometry.value)
        assertSame(statistics, v.coverageStatistics.value)
        assertSame(validity, v.predictionValidity.value)
        assertEquals(PredictionState.CURRENT, v.predictionState.value)
        assertEquals(v.revision.value, JSONObject(v.exportProjectReportJson()).getLong("predictionRevision"))
    }
    @Test fun manualEditClearsPublishedValuesAndOldCompletionCannotReviveThem() {
        val v = vm()
        v.recalculateSignal(); scheduler.runCurrent()
        val oldMap = latest(PredictionChannel.COVERAGE)
        finish(latest(PredictionChannel.LISTENER))
        assertNotNull(v.combinedSplDb.value)
        v.setBandHz(4000)
        assertNull(v.predictionValidity.value)
        assertNull(v.combinedSplDb.value)
        assertTrue(v.results.value.isEmpty())
        assertEquals(PredictionState.STALE, v.predictionState.value)
        finish(oldMap)
        assertTrue(v.heatmap.value.isEmpty())
        assertNull(v.coverageGeometry.value)
        assertNull(v.coverageStatistics.value)
        val report = JSONObject(v.exportProjectReportJson())
        assertFalse(report.has("combinedSplDb"))
        assertFalse(report.has("predictionRevision"))
    }
    @Test fun sameRevisionRecalculationAndDisableRejectPendingWork() {
        val v = vm()
        v.recalculateSignal(); scheduler.runCurrent()
        val old = jobs.toList()
        v.recalculateSignal(); scheduler.runCurrent()
        finish(latest(PredictionChannel.LISTENER)); finish(latest(PredictionChannel.COVERAGE))
        val level = v.combinedSplDb.value
        old.forEach(::finish)
        assertEquals(level, v.combinedSplDb.value)
        v.recalculateSignal(); scheduler.runCurrent()
        val pending = jobs.filter { !it.done.isCompleted }
        v.setSignalSplEnabled(false)
        pending.forEach(::finish)
        assertNull(v.combinedSplDb.value)
        assertTrue(v.heatmap.value.isEmpty())
    }
    @Test fun requestRemainsStableAfterBandDriveAtmosphereAndGroundChanges() {
        val v = vm()
        v.recalculateSignal(); scheduler.runCurrent()
        val request = latest(PredictionChannel.LISTENER).request
        val before = request.calculate().levelAt(0)
        v.setSignalLevelDbu(12f); v.setBandHz(8000); v.setTemperatureC(40f)
        v.setHumidityPct(10f); v.setGroundReflectionEnabled(true); v.setSignalDispersionEnabled(false)
        assertEquals(before, request.calculate().levelAt(0), 0f)
    }
    @Test fun failedRunLeavesRetryableStateAndCannotPublishOldFailure() {
        val v = vm()
        v.setSignalCoverageEnabled(false)
        v.recalculateSignal(); scheduler.runCurrent()
        latest(PredictionChannel.LISTENER).done.completeExceptionally(IllegalStateException("backend failure"))
        scheduler.runCurrent()
        assertEquals(PredictionState.FAILED, v.predictionState.value)
        v.recalculateSignal(); scheduler.runCurrent()
        finish(latest(PredictionChannel.LISTENER))
        assertNotNull(v.combinedSplDb.value)
        assertEquals(PredictionState.CURRENT, v.predictionState.value)
    }
    @Test fun automaticModeRecomputesBothChannelsAfterListenerOnlyEdit() {
        val v = vm()
        v.setSignalAutoCalculate(true); scheduler.runCurrent()
        finish(latest(PredictionChannel.LISTENER)); finish(latest(PredictionChannel.COVERAGE))
        val oldCount = jobs.size
        v.moveListener(7f, 1f); scheduler.runCurrent()
        val newJobs = jobs.drop(oldCount)
        assertEquals(setOf(PredictionChannel.LISTENER, PredictionChannel.COVERAGE),
            newJobs.map { it.request.ticket.channel }.toSet())
        newJobs.forEach(::finish)
        assertEquals(PredictionState.CURRENT, v.predictionState.value)
        assertNotNull(v.combinedSplDb.value)
        assertTrue(v.heatmap.value.isNotEmpty())
    }
    @Test fun importingSceneRejectsJobsFromPreviousScene() {
        val v = vm()
        val saved = v.exportSceneJson()
        v.recalculateSignal(); scheduler.runCurrent()
        val old = jobs.toList()
        assertTrue(v.importSceneJson(saved)); scheduler.runCurrent()
        finish(latest(PredictionChannel.LISTENER)); finish(latest(PredictionChannel.COVERAGE))
        val before = v.exportProjectReportJson()
        old.forEach(::finish)
        assertEquals(before, v.exportProjectReportJson())
        assertEquals(PredictionState.CURRENT, v.predictionState.value)
    }

}
