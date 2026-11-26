package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import kotlin.test.*

class ClfHandednessTest {
    @Test fun officialRearViewLabelsMapToNativeDirections() {
        for((az,el,theta,phi) in listOf(listOf(-60f,0f,60f,90f),listOf(60f,0f,60f,270f),
            listOf(0f,60f,60f,0f),listOf(0f,-60f,60f,180f))) {
            val actual=ClfTabParser.toAxisRelative(az,el)
            assertEquals(theta,actual.first,.0001f);assertEquals(phi,actual.second,.0001f)
        }
    }
    @Test fun declaredRotationOccursAfterFullArcOrderNormalization() {
        fun tab(angle:String)=buildString {
            appendLine("<CLF2>");appendLine("<BALLOON-SYMMETRY>\t<none>")
            appendLine("<BALLOON-ARC-ORDER>\t<default>\t<ROTATE>\t$angle");appendLine("<BAND>\t1000")
            repeat(72) {i->appendLine(List(37){if(it==0 || it==36) "0" else "${-i*5}"}.joinToString("\t"))}
        }
        val rotated=ClfTabParser.parse(tab("90")).bands.single()
        assertEquals(0f,ClfTabParser.attenuationDb(rotated,-45f,0f),.0001f) // old up moved to left
        assertEquals(-180f,ClfTabParser.attenuationDb(rotated,45f,0f),.0001f)
        for(angle in listOf("NaN","Infinity","2.5","bad")) assertFailsWith<ClfTabParser.ParseException> {ClfTabParser.parse(tab(angle))}
    }
    @Test fun legacyTabAssetsAreFlaggedWithoutSilentlyReorientingSavedNativeData() {
        val data=ClfData("legacy",patterns=listOf(ClfPolarPattern(1000,listOf(-45f,45f),listOf(0f),
            arrayOf(floatArrayOf(-3f),floatArrayOf(-18f)))),provenance=SpeakerProvenance(importer="clf-subset-v1:TAB"))
        val restored=assertNotNull(ClfParser.parseJson(ClfParser.toJson(data)))
        assertEquals(data.patterns,restored.patterns)
        assertTrue(SpeakerDataChecks.warnings(restored).any {"legacy TAB left/right" in it})
        for(importer in listOf("clf-subset-v2:TAB","clf-subset-v1:CF2",""))
            assertFalse(SpeakerDataChecks.warnings(data.copy(provenance=SpeakerProvenance(importer=importer))).any {"legacy TAB left/right" in it})
    }
}
