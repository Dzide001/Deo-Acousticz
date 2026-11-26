package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import kotlin.test.*

class ClfMagnitudeTest {
    private fun document(sym:String="none",reversed:Boolean=false,sign:String="actual",format:Int=1):String {
        val step=if(format==1) 10 else 5
        val angles=when(sym) {
            "none" -> (0 until 360 step step).toList().let {if(reversed) listOf(0)+it.drop(1).reversed() else it}
            "horizontal" -> (0..180 step step).toList().let {if(reversed) it.reversed() else it}
            "vertical" -> ((270 until 360 step step)+(0..90 step step)).toList().let {if(reversed) it.reversed() else it}
            "full" -> (0..90 step step).toList().let {if(reversed) it.reversed() else it}
            else -> listOf(0)
        }
        return buildString {
            appendLine("<CLF$format>");appendLine("<BALLOON-SYMMETRY>\t<$sym>")
            appendLine("<BALLOON-ARC-ORDER>\t<${if(reversed) "reversed" else "default"}>")
            appendLine("<BALLOON-REF>\t<relative>\t<SIGN>\t<$sign>");appendLine("<BAND>\t1000")
            angles.forEach {phi->appendLine((0..180 step step).joinToString("\t") {theta->
                val magnitude=if(theta==0 || theta==180) 0 else -(phi+1)
                (if(sign=="reversed") -magnitude else magnitude).toString()
            })}
        }
    }
    @Test fun declaredSectorsAndTheirReversedEncodingsExpandBeforeRotation() {
        val expected=mapOf("none" to listOf(-1f,-91f,-181f,-271f),"horizontal" to listOf(-1f,-91f,-181f,-91f),
            "vertical" to listOf(-1f,-91f,-1f,-271f),"full" to listOf(-1f,-91f,-1f,-91f),"rotational" to List(4){-1f})
        for(format in listOf(1,2)) for((sym,values) in expected) for(reverse in listOf(false,true)) for(sign in listOf("actual","reversed")) {
            val band=ClfTabParser.parse(document(sym,reverse,sign,format)).bands.single()
            assertEquals(values,(0..270 step 90).map {band.at(60f,it.toFloat())},"$format $sym $reverse $sign")
        }
    }
    @Test fun conflictingDeclarationsUnsupportedReferencesAndNonfiniteSamplesFailExplicitly() {
        val text=document()
        for(bad in listOf(text.replace("<none>","<full>"),text.replace("<none>","<mystery>"),
            text.replace("<relative>","<absolute>"),text.replace("<relative>","<arbitrary>"),
            text.replace("<actual>","<mystery>"),text.replace("<default>","<mystery>"),
            text.replace("0\t-1", "0\tNaN"),text.replace("0\t-1", "0\tInfinity")))
            assertFailsWith<ClfTabParser.ParseException> {ClfTabParser.parse(bad)}
    }
    @Test fun v2AssetsAreFlaggedAndNativeDataSurvivesRecovery() {
        val data=ClfTabParser.toClfData(ClfTabParser.parse(document())).copy(provenance=SpeakerProvenance(importer="clf-subset-v2:TAB"))
        val reopened=assertNotNull(ClfParser.parseJson(ClfParser.toJson(data)))
        assertEquals(data.patterns,reopened.patterns)
        assertTrue(SpeakerDataChecks.warnings(reopened).any {"symmetry/sign" in it})
        assertFalse(SpeakerDataChecks.warnings(data.copy(provenance=SpeakerProvenance(importer="clf-subset-v3:TAB"))).any {"symmetry/sign" in it})
    }
}
