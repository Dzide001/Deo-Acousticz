package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.*
import kotlin.test.*

class PredictionRequestTest {
    private fun ticket(seq: Long = 1) = PredictionTicket(1, seq, PredictionChannel.LISTENER)
    @Test fun requestOwnsPolarResponseEqLossWeightingAndReceiverCollections() {
        val rows = arrayOf(floatArrayOf(0f, 0f))
        val sensitivity = mutableMapOf(1000 to 100f)
        val response = FrozenResponse.measured(ClfData("test", patterns = listOf(
            ClfPolarPattern(1000, listOf(0f), listOf(-90f,90f), rows)), sensitivityDbByBand = sensitivity), true)
        val eq = mutableMapOf(1000 to 0f)
        val poses = ArrayGeometry.poses(1, 0.2f, 0f, emptyList()).toMutableList()
        val sources = mutableListOf(SourceSnapshot(0, 0f, 1f, 0f, 0f, poses, 0f, 0f, response, eqDb = eq))
        val losses = mutableMapOf(0 to 2f)
        val receivers = mutableListOf(ReceiverSnapshot(10f, 1f, 0f, mapOf(1000 to losses)))
        val bands = mutableListOf(1000)
        val floor = mutableMapOf(1000 to 0.5f)
        val request = PredictionRequest(ticket(), sources, receivers, 1000, bands, 1f/3,
            emptyMap(), true, Atmosphere(), floor, true)
        val before = request.calculate().levelAt(0)
        rows[0][0] = -80f; rows[0][1] = -80f
        sensitivity[1000] = 40f; eq[1000] = -30f; losses[0] = 55f; floor[1000] = 1f
        poses.clear(); sources.clear(); receivers.clear(); bands.clear()
        assertEquals(before, request.calculate().levelAt(0), 1e-5f)
        assertEquals(1, request.receiverCount)
        assertEquals(1, request.sourceCount)
    }
    @Test fun resultDoesNotExposeOrRetainMutableArrays() {
        val levels = floatArrayOf(90f)
        val source = arrayOf(floatArrayOf(80f))
        val output = PredictionOutput(ticket(), levels, source)
        levels[0] = 0f; source[0][0] = 0f
        assertEquals(90f, output.levelAt(0))
        assertEquals(80f, output.sourceLevelAt(0,0))
    }
    @Test fun olderCompletionCannotReplaceNewerEvenAtSameSceneRevision() {
        val gate = PredictionGate()
        val old = gate.begin(PredictionChannel.LISTENER, 3)
        val newest = gate.begin(PredictionChannel.LISTENER, 3)
        assertTrue(gate.complete(newest, 3))
        assertFalse(gate.complete(old, 3))
        assertFalse(gate.fail(old, 3))
        assertFalse(gate.complete(newest, 3)) // duplicate publication
        assertEquals(PredictionState.CURRENT, gate.state(setOf(PredictionChannel.LISTENER)))
    }
    @Test fun editOrExplicitInvalidationRejectsInFlightResults() {
        val gate = PredictionGate()
        val old = gate.begin(PredictionChannel.COVERAGE, 1)
        assertFalse(gate.complete(old, 2))
        gate.invalidate()
        assertFalse(gate.complete(old, 1))
        assertFalse(gate.fail(old, 1))
        assertEquals(PredictionState.STALE, gate.state(setOf(PredictionChannel.COVERAGE)))
    }
    @Test fun lanesCompleteIndependentlyButCannotMixSceneRevisions() {
        val gate = PredictionGate()
        val listener = gate.begin(PredictionChannel.LISTENER, 1)
        val coverage = gate.begin(PredictionChannel.COVERAGE, 1)
        assertTrue(gate.complete(listener, 1))
        assertEquals(PredictionState.RUNNING, gate.state(setOf(PredictionChannel.LISTENER, PredictionChannel.COVERAGE)))
        gate.invalidate()
        val next = gate.begin(PredictionChannel.LISTENER, 2)
        assertTrue(gate.complete(next, 2))
        assertFalse(gate.complete(coverage, 2))
        assertEquals(PredictionState.STALE, gate.state(setOf(PredictionChannel.LISTENER, PredictionChannel.COVERAGE)))
    }
    @Test fun failedLatestRequestCanBeRetriedAndOlderFailureIsIgnored() {
        val gate = PredictionGate()
        val old = gate.begin(PredictionChannel.LISTENER, 1)
        assertTrue(gate.fail(old, 1))
        val retry = gate.begin(PredictionChannel.LISTENER, 1)
        assertFalse(gate.fail(old, 1))
        assertTrue(gate.complete(retry, 1))
    }
    @Test fun broadbandWeightingIsCapturedAndCancellationReturnsNoPartialOutput() {
        val weighting = mutableMapOf(500 to 0f, 1000 to 0f)
        val request = PredictionRequest(ticket(), listOf(SourceSnapshot(0,0f,0f,0f,0f,
            ArrayGeometry.poses(1,0.2f,0f,emptyList()),0f,0f,
            FrozenResponse.generic(100f,"generic",false,false))),
            listOf(ReceiverSnapshot(10f,0f,0f)), 1000, listOf(500,1000), 1f,
            weighting, true, Atmosphere())
        val before = request.calculate().levelAt(0)
        weighting[500] = -50f
        assertEquals(before, request.calculate().levelAt(0), 0f)
        var checkpoints = 0
        assertFailsWith<IllegalStateException> { request.calculate {
            if (++checkpoints == 2) error("cancelled")
        } }
        assertEquals(before, request.calculate().levelAt(0), 0f)
    }

}
