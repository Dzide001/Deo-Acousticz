package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AngularSupportTest {
    private fun grid(az: List<Float>,el: List<Float>)=PolarGrid(az,el,az.map {el.map {0f}})
    @Test fun seamWrapUsesLocalBracketAndLargeGapIsOnlyAHeuristicWarning() {
        val s=grid(listOf(-10f,0f,10f),listOf(0f)).angularSupport
        assertEquals("SPARSE_INTERPOLATION",s.at(-5f,0f).status)
        assertEquals(s.at(-5f,0f),s.at(355f,0f))
        assertEquals(10f,s.at(355f,0f).azimuthBracketDeg)
        assertEquals("STORED_KNOT",s.at(360f,0f).status)
        assertEquals("POSSIBLE_MISSING_SECTOR",s.at(180f,0f).status)
        assertEquals(340f,s.maximumAzimuthGapDeg)
        assertTrue("POSSIBLE_MISSING_SECTOR" in s.issues)
    }
    @Test fun singleColumnsAndUnsupportedElevationsAreNotConfusedWithMeasuredDirections() {
        val one=grid(listOf(0f),listOf(0f));val s=one.angularSupport
        assertEquals("STORED_KNOT",s.at(0f,0f).status)
        assertEquals("SINGLE_AZIMUTH_EXTRAPOLATION",s.at(90f,0f).status)
        assertEquals("OUTSIDE_ELEVATION",s.at(0f,1f).status)
        assertEquals("INVALID_QUERY",s.at(Float.NaN,0f).status)
        assertEquals("INVALID_QUERY",s.at(0f,91f).status)
        assertEquals("STORED_POLE",grid(listOf(0f),listOf(-90f,90f)).angularSupport.at(123f,90f).status)
        assertTrue(one.at(0f,1f).isNaN())
        assertEquals(0f,one.at(90f,0f)) // Diagnostics do not silently alter legacy replication.
    }
    @Test fun fineAndNonuniformAxesReportActualLocalSpacingWithoutAccuracyCertification() {
        val fine=grid((0 until 360 step 5).map {it.toFloat()},listOf(-90f,-5f,0f,5f,90f)).angularSupport
        assertEquals("INTERPOLATED",fine.at(2.5f,2.5f).status)
        assertEquals("SPARSE_INTERPOLATION",fine.at(2.5f,45f).status)
        assertFalse("SPARSE_AZIMUTH" in fine.issues);assertTrue("SPARSE_ELEVATION" in fine.issues)
        val uneven=grid(listOf(0f,5f,20f,40f,180f),listOf(0f)).angularSupport
        assertEquals(5f,uneven.at(2f,0f).azimuthBracketDeg)
        assertEquals(15f,uneven.at(10f,0f).azimuthBracketDeg)
    }
    @Test fun equivalentEndpointsCollapseAndMalformedGridsRemainInvalid() {
        val s=grid(listOf(0f,180f,360f),listOf(0f)).angularSupport
        assertEquals(2,s.json().getValue("azimuthCount").jsonPrimitive.int)
        val bad=PolarGrid(listOf(0f,360f),listOf(0f),listOf(listOf(0f),listOf(1f))).angularSupport
        assertFalse(bad.valid);assertEquals("INVALID_GRID",bad.at(0f,0f).status)
        assertEquals("INVALID_GRID",PolarGrid(emptyList(),emptyList(),emptyList()).angularSupport.at(0f,0f).status)
    }
    @Test fun magnitudeAndComplexCoverageAreAssessedSeparatelyAndRoundTripIsStable() {
        val complex=ComplexSpeakerResponse.fromJsonString(buildJsonObject {
            put("version",1);put("convention",ComplexSpeakerResponse.CONVENTION);put("provenance","TEST ONLY");put("referenceDrive","test")
            putJsonArray("patterns") {add(buildJsonObject {
                put("frequency",1000);put("azimuths",JsonArray(listOf(0,20,40).map(::JsonPrimitive)))
                put("elevations",JsonArray(listOf(0).map(::JsonPrimitive)))
                put("real",JsonArray(List(3) {JsonArray(listOf(JsonPrimitive(1)))}))
                put("imaginary",JsonArray(List(3) {JsonArray(listOf(JsonPrimitive(0)))}))
            })}
        }.toString())
        val az=(0 until 360 step 5).map {it.toFloat()}
        val data=ClfData("test",patterns=listOf(ClfPolarPattern(1000,az,listOf(-90f,90f),Array(az.size) {floatArrayOf(0f,0f)})),
            sensitivityDbByBand=mapOf(1000 to 90f),complexResponse=complex)
        val warnings=SpeakerAngularSupport.warnings(data)
        assertTrue(warnings.any {it.startsWith("complex") && "azimuth gaps" in it})
        assertFalse(warnings.any {it.startsWith("magnitude") && "azimuth gaps" in it})
        assertTrue(warnings.any {"other elevations are unsupported" in it})
        assertTrue(SpeakerAngularSupport.reportJson(data).contains("no accuracy guarantee"))
        val serialized=ClfParser.toJson(data);val reopened=ClfParser.parseJson(serialized)!!
        assertFalse(serialized.contains("angularSupport")) // Derived diagnostics do not change asset identities.
        assertEquals(SpeakerDataChecks.reportJson(data),SpeakerDataChecks.reportJson(reopened))
    }
}
