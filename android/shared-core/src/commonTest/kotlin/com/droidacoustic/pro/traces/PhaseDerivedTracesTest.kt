package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class PhaseDerivedTracesTest {
    private val meta = TraceMetadata(BoundaryPoint(1.0, 1.2, 0.0), 1f, 20f, 50f, "a".repeat(64), 0)
    private fun trace(phases: Map<Int, Double?>, convention: String = PhaseResponse.CONVENTION) =
        SavedTrace(0, "Test", meta, phases.mapValues { 1.0 }, phases.mapValues {
            PhaseSample(it.value, if (it.value == null) "Missing complex data" else null)
        }, convention)

    @Test fun unwrapsBothDirectionsAndUsesLinearFrequencyForPositiveDelay() {
        val a = PhaseDerivedTraces.derive(trace(linkedMapOf(300 to -118.0, 100 to 170.0, 150 to -172.0)))
        assertEquals(listOf(170.0, 188.0, 242.0), a.points.map { it.degrees })
        a.delays.forEach { assertEquals(1.0, it.milliseconds!!, 1e-10) }
        val b = PhaseDerivedTraces.derive(trace(mapOf(100 to -170.0, 150 to 172.0, 300 to 118.0)))
        assertEquals(listOf(-170.0, -188.0, -242.0), b.points.map { it.degrees })
        b.delays.forEach { assertEquals(-1.0, it.milliseconds!!, 1e-10) }
        assertEquals(10.0, a.delays.first().aliasLimitMs, 1e-10)
    }
    @Test fun missingDataAndOmittedPhaseKeysBreakSegmentsWithoutBridging() {
        val original = trace(mapOf(100 to 170.0, 200 to null, 300 to -170.0, 400 to -150.0))
        for (t in listOf(original, SavedTrace(0, "Omitted", meta, original.powers, original.phases - 200))) {
            val result = PhaseDerivedTraces.derive(t)
            assertEquals(listOf(170.0, null, -170.0, -150.0), result.points.map { it.degrees })
            assertNull(result.delays[0].milliseconds); assertNull(result.delays[1].milliseconds)
            assertEquals(20.0 / 36, result.delays[2].milliseconds!!, 1e-10)
            assertNotEquals(result.points[0].segment, result.points[2].segment)
        }
    }
    @Test fun halfTurnIsAmbiguousAndSingletonCannotYieldDelay() {
        val result = PhaseDerivedTraces.derive(trace(mapOf(100 to 0.0, 200 to 180.0, 300 to -170.0)))
        assertNull(result.delays[0].milliseconds)
        assertTrue(result.delays[0].reason!!.contains("180"))
        assertNotEquals(result.points[0].segment, result.points[1].segment)
        assertEquals(10.0 / 36, result.delays[1].milliseconds!!, 1e-10)
        assertTrue(PhaseDerivedTraces.derive(trace(mapOf(100 to 10.0))).delays.isEmpty())
        assertTrue(PhaseDerivedTraces.derive(trace(mapOf(100 to null, 200 to null))).points.all { it.degrees == null })
    }
    @Test fun savedRawSamplesReproduceDerivedTracesAndComparisonsExactly() {
        val a = trace(mapOf(100 to 170.0, 150 to -172.0, 300 to null, 400 to 45.0))
        val b = trace(mapOf(100 to 160.0, 150 to -175.0, 300 to null, 400 to 55.0))
        val library = SavedTraces.empty().captureBatch(listOf(a, b))
        val reopened = SavedTraces.fromJsonString(library.toJsonString()).traces
        assertEquals(PhaseDerivedTraces.compare(a, b), PhaseDerivedTraces.compare(reopened[0], reopened[1]))
        assertEquals(library.toJsonString(), SavedTraces.fromJsonString(library.toJsonString()).toJsonString())
    }
    @Test fun incompatibleConventionsPositionsAndFrequencyGridsAreRejected() {
        val a = trace(mapOf(100 to 0.0, 200 to 20.0))
        assertFalse(PhaseDerivedTraces.compare(a, trace(mapOf(100 to 0.0))).compatible)
        val unknown = trace(mapOf(100 to 0.0, 200 to 20.0), "unknown")
        assertFalse(PhaseDerivedTraces.compare(a, unknown).compatible)
        assertTrue(PhaseDerivedTraces.derive(unknown).points.all { it.degrees == null })
        assertFalse(PhaseDerivedTraces.compare(a, SavedTrace(1, "Moved", meta.copy(receiver = BoundaryPoint(2.0, 1.2, 0.0)), a.powers, a.phases)).compatible)
    }
    @Test fun fullTurnsAreNotRecoverableAndOutputIsExplicitlyAnIntervalEstimate() {
        // 11 ms at 100 Hz spacing aliases to 1 ms: expose the 5 ms limit, never claim
        // an absolute arrival or reconstruct hidden turns from these identical samples.
        val result = PhaseDerivedTraces.derive(trace((1..3).associate { n ->
            n * 100 to PhaseResponse.wrapped(360 * n * 100 * .011)
        }))
        result.delays.forEach {
            assertEquals(1.0, it.milliseconds!!, 1e-10)
            assertEquals(5.0, it.aliasLimitMs, 1e-10)
        }
    }
}
