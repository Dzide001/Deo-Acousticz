package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.scene.*
import com.droidacoustic.pro.persistence.ProjectArchive
import kotlinx.serialization.json.*
import kotlin.test.*

class SpeakerProvenanceTest {
    @Test fun metadataPersistsAndChangesAssetIdentityWithoutPromotingTrust() {
        val legacy=ClfData("test")
        val payload=ClfParser.toJson(legacy)
        assertFalse(Json.parseToJsonElement(payload).jsonObject.containsKey("provenance"))
        assertEquals(payload,ClfParser.toJson(ClfParser.parseJson(payload)!!))
        val declared=legacy.copy(provenance=SpeakerProvenance(SpeakerDataOrigin.MANUFACTURER_DECLARED,
            "source","https://example.test/source",SpeakerProvenance.hash(byteArrayOf(1)),"parser-v1","flat","declared"))
        assertEquals(declared,ClfParser.parseJson(ClfParser.toJson(declared)))
        assertNotEquals(ProjectArchive.asset(legacy).reference,ProjectArchive.asset(declared).reference)
        assertTrue(SpeakerDataChecks.warnings(declared).any {"not independently verified" in it})
        assertEquals("pending",Json.parseToJsonElement(SpeakerDataChecks.reportJson(declared)).jsonObject["externalValidation"]?.jsonPrimitive?.content)
        assertFails {SpeakerProvenance(sourceSha256="bad")}
    }
    @Test fun declaredNormalizationIsAppliedOnceWithoutInferringVoltagePowerConversion() {
        val c=SpeakerDataChecks.declaredClfCalibration("Measured at 4.000 m and 2.828 Vrms and normalized to 1W/1m")
        assertEquals(1.0,c.distanceM); assertEquals(DriveUnit.WATTS,c.driveUnit)
        assertEquals(96f,c.atOneMetre(96f))
        assertEquals(3.0103f,c.gainForDrive(DriveUnit.WATTS,2.0)!!,1e-4f)
        assertNull(c.gainForDrive(DriveUnit.VOLTS_RMS,2.828))
        for(note in listOf("", "2.83 V at 4m", "not normalized to 1W/1m", "Measured at 4 m and 2.83 Vrms", "Measured at 0 m and 0 Vrms and normalized to 1W/1m"))
            assertFalse(SpeakerDataChecks.declaredClfCalibration(note).electricalReferenceKnown)
    }
    private val conditions=SpeakerComparisonConditions("revision-1","flat","one point source at origin; yaw=0","tone-exact-Hz",
        "ABSOLUTE_SPL","20C/50%/101.325kPa","off",DriveUnit.WATTS,1.0)
    private val samples=listOf(SpeakerComparisonSample("r1",1000.0,10.0,0.0,0.0,80.0))
    private fun run(c: SpeakerComparisonConditions=conditions, s: List<SpeakerComparisonSample> = samples,
        evidence: ComparisonEvidence=ComparisonEvidence.MANUFACTURER_SOFTWARE_EXPORT)=SpeakerComparisonRun(c,evidence,"a".repeat(64),s)
    private fun status(a: SpeakerComparisonRun,b: SpeakerComparisonRun)=Json.parseToJsonElement(SpeakerComparisons.compare(a,b,0.1)).jsonObject
    @Test fun matchingCannotHideOffsetsMissingDataOrMismatchedConditions() {
        val engine=run(evidence=ComparisonEvidence.ENGINE)
        assertEquals("PASSED",status(run(),engine)["status"]?.jsonPrimitive?.content)
        assertEquals("FAILED",status(run(),engine.copy(samples=samples.map {it.copy(levelDb=81.0)}))["status"]?.jsonPrimitive?.content)
        val invalid=listOf(run(conditions.copy(preset="other")),run(conditions.copy(preset="")),
            run(conditions.copy(driveUnit=DriveUnit.UNKNOWN,driveValue=1.0)),run(conditions.copy(frequencyConvention="third-octave")),
            run(conditions.copy(ground="on")),run(conditions.copy(driveUnit=DriveUnit.UNKNOWN,driveValue=null)),
            run(s=emptyList()),run(s=samples+samples),run(s=samples.map {it.copy(x=11.0)}),
            run(s=samples.map {it.copy(levelDb=null)}),run(s=samples.map {it.copy(levelDb=Double.NaN)}),run(evidence=ComparisonEvidence.SYNTHETIC),
            run().copy(artifactSha256=""))
        invalid.forEach { assertEquals("NOT_COMPARABLE",status(it,engine)["status"]?.jsonPrimitive?.content) }
        val relative=conditions.copy(levelConvention="RELATIVE_TO_ON_AXIS",driveUnit=DriveUnit.UNKNOWN,driveValue=null)
        assertEquals("DATA_CONSISTENCY_ONLY",status(run(relative,evidence=ComparisonEvidence.MANUFACTURER_DATA),
            run(relative,evidence=ComparisonEvidence.ENGINE))["evidenceScope"]?.jsonPrimitive?.content)
    }
}
