package com.droidacoustic.pro.acoustics

import kotlinx.serialization.json.*
import kotlin.test.*

class ComparisonProtocolReviewTest {
    private fun reference(calibration: String="DECLARED"): ImportedComparison=ImportedComparison.parse(buildJsonObject {
        put("schema","droidacoustic.comparison-import");put("version",1);put("evidence","CONTROLLED_MEASUREMENT");put("synthetic",false)
        putJsonObject("source") {
            put("producer","TEST ONLY");put("version","1");put("fileName","test.csv");put("calibrationStatus",calibration)
            listOf("instrument","method","smoothing","timeWindow","calibrationReference").forEach {put(it,"test declaration")}
        }
        putJsonObject("conditions") {
            listOf("modelRevision","preset","geometry","frequencyConvention","atmosphere","ground").forEach {put(it,"original")}
            put("levelConvention","ABSOLUTE_SPL");put("driveUnit","WATTS");put("driveValue",1)
        }
        putJsonObject("units") {put("frequency","Hz");put("position","m");put("level","dB_SPL")}
        put("toleranceDb",.1);put("format","CSV");put("delimiter",",");put("payload","Hz,Level\n1000,80")
        putJsonObject("columns") {put("frequencyHz","Hz");put("levelDb","Level")}
        putJsonObject("receiver") {put("id","r");put("xM",1);put("yM",0);put("zM",0)}
    }.toString())
    private val field=ComparisonProtocolFieldReview("geometry","original","reviewed","Documented independent coordinate mapping","https://example.org/test-only")
    @Test fun annotationsPreserveOriginalArtifactLevelsCalibrationAndAuditAcrossReopen() {
        val original=reference();val reviewed=ComparisonProtocolReview.annotate(original,original.documentSha256,"test reviewer",listOf(field))
        assertEquals(original.run.samples,reviewed.run.samples);assertEquals(original.run.artifactSha256,reviewed.run.artifactSha256)
        assertEquals(original.run.conditions.copy(geometry="reviewed"),reviewed.run.conditions)
        val audit=Json.parseToJsonElement(reviewed.document).jsonObject.getValue("protocolReview").jsonObject
        assertEquals(original.document,audit.getValue("originalDocument").jsonPrimitive.content)
        assertNotEquals(original.documentSha256,reviewed.documentSha256)
        val w=ComparisonWorkspace.empty().withImported(reviewed,true)
        assertEquals(w.validationReportJson(),ComparisonWorkspace.parse(w.toJsonString()).validationReportJson())
    }
    @Test fun staleUnsafeDuplicateUndocumentedAndRepeatedMappingsAreRejected() {
        val r=reference()
        fun apply(fields: List<ComparisonProtocolFieldReview>,hash: String=r.documentSha256)=ComparisonProtocolReview.annotate(r,hash,"test",fields)
        assertFails {apply(listOf(field),"f".repeat(64))}
        assertFails {apply(listOf(field.copy(expectedOriginalValue="stale")))}
        assertFails {apply(listOf(field,field))}
        assertFails {apply(listOf(field.copy(rationale="")))}
        for(key in listOf("levelConvention","driveValue","driveUnit","normalization","broadband","payload","calibrationStatus"))
            assertFails {apply(listOf(field.copy(field=key)))}
        val reviewed=apply(listOf(field))
        assertFails {ComparisonProtocolReview.annotate(reviewed,reviewed.documentSha256,"test",listOf(field.copy(expectedOriginalValue="reviewed")))}
    }
    @Test fun reviewNeverCertifiesUnknownMeasurementCalibration() {
        val r=reference("UNKNOWN");val reviewed=ComparisonProtocolReview.annotate(r,r.documentSha256,"test",listOf(field))
        assertTrue(reviewed.blockingReasons().any {it.contains("calibration is unknown")})
    }
}
