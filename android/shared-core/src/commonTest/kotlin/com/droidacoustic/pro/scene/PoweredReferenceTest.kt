package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.persistence.ProjectArchive
import kotlin.test.*

class PoweredReferenceTest {
    private val note="Measured at 4 m and 2.83 Vrms and normalized to 1W/1m"
    @Test fun onlyNonpoweredSensitivityMayUseItsOwnWattNormalizationDeclaration() {
        for(basis in LevelReferenceBasis.entries) for(powered in listOf(false,true)) {
            val calibration=SpeakerDataChecks.clfCalibration(basis,powered,note)
            assertEquals(!powered && basis==LevelReferenceBasis.SENSITIVITY,calibration.electricalReferenceKnown)
            if(!calibration.electricalReferenceKnown) assertNull(calibration.gainForDrive(DriveUnit.WATTS,1.0))
        }
    }
    @Test fun referenceBasisRoundTripsWithoutChangingLegacyDefaultAssetHashes() {
        val legacy=ClfData("legacy");val json=ClfParser.toJson(legacy)
        assertFalse(json.contains("levelReferenceBasis"))
        assertEquals(ProjectArchive.asset(legacy).reference,ProjectArchive.asset(ClfParser.parseJson(json)!!).reference)
        for(basis in LevelReferenceBasis.entries) {
            val data=legacy.copy(levelReferenceBasis=basis)
            assertEquals(data,ClfParser.parseJson(ClfParser.toJson(data)))
            if(basis!=LevelReferenceBasis.UNSPECIFIED) assertNotEquals(ProjectArchive.asset(legacy).reference,ProjectArchive.asset(data).reference)
        }
        assertNull(ClfParser.parseJson(json.dropLast(1)+",\"levelReferenceBasis\":\"unsupported\"}"))
    }
    @Test fun axialWarningsAndReportsNameTheActualReferenceBasis() {
        val data=ClfData("axial",levelReferenceBasis=LevelReferenceBasis.AXIAL_SPECTRUM)
        assertTrue(SpeakerDataChecks.warnings(data).any {"axial-spectrum level reference" in it})
        assertTrue(SpeakerDataChecks.reportJson(data).contains("\"levelReferenceBasis\":\"AXIAL_SPECTRUM\""))
    }
}
