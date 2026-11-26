package com.droidacoustic.pro.prediction

import kotlin.test.*
import kotlinx.serialization.json.*

class PredictionModelScopeTest {
    @Test fun persistedProfilesHaveExplicitSpacingAndUnknownProfilesUseDefault() {
        assertEquals(3f, PredictionModelScope.coverageGridSpacingM("Fast"))
        assertEquals(2f, PredictionModelScope.coverageGridSpacingM("Balanced"))
        assertEquals(1f, PredictionModelScope.coverageGridSpacingM("Precision"))
        assertEquals(2f, PredictionModelScope.coverageGridSpacingM("old-profile"))
    }
    @Test fun portableReportMakesUnsupportedCapabilitiesExplicit() {
        val report = Json.parseToJsonElement(PredictionModelScope.reportJson()).jsonObject
        assertEquals(1, report.getValue("maxReflectionOrder").jsonPrimitive.int)
        listOf("diffraction", "transmission", "roomMetrics", "headroom", "automaticOptimization").forEach {
            assertFalse(report.getValue(it).jsonPrimitive.boolean)
        }
        assertEquals("pending", report.getValue("externalValidation").jsonPrimitive.content)
        assertTrue(report.getValue("limitations").jsonArray.isNotEmpty())
    }
}
