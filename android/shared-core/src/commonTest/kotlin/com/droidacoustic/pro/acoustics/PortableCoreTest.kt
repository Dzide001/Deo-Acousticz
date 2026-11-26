package com.droidacoustic.pro.acoustics

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.math.abs

class PortableCoreTest {
    @Test fun referenceAirLoss() {
        assertTrue(abs(Atmosphere().absorptionDbPerMetre(8000.0) - 0.105291) < 0.00001)
    }
    @Test fun mechanicalAimChangesGeometry() {
        val upright = ArrayGeometry.poses(4, 0.3f, 0f, emptyList())
        val tilted = ArrayGeometry.poses(4, 0.3f, 30f, emptyList())
        assertTrue(abs(tilted.first().forwardM - upright.first().forwardM) > 0.1f)
    }
}
