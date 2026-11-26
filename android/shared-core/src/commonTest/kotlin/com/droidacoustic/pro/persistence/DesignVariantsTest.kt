package com.droidacoustic.pro.persistence

import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesignVariantsTest {
    private fun scene(gain:Int)= """{"version":22,"schema":"droidacoustic.scene","speakers":[],"dsp":[{"speakerId":1,"gainDb":$gain}],"savedTraces":{"version":1,"nextId":0,"traces":[]}}"""
    @Test fun snapshotsAreIndependentAndSwitchingKeepsBothLibraries() {
        val a=DesignVariants.empty().capture("A",scene(0))
        val b=a.capture("B",scene(6))
        assertEquals(1,a.variants.size);assertEquals(2,b.variants.size)
        val restored=b.prepareRestore(0,scene(9))
        val root=Json.parseToJsonElement(restored.sceneJson).jsonObject
        assertEquals(0,root.getValue("dsp").jsonArray[0].jsonObject.getValue("gainDb").jsonPrimitive.int)
        assertEquals(2,DesignVariants.fromJsonString(root.getValue("designVariants").toString()).variants.size)
        assertTrue("savedTraces" in root)
        val snapshot=Json.parseToJsonElement(b.variants[0].sceneJson).jsonObject
        assertFalse("designVariants" in snapshot);assertFalse("savedTraces" in snapshot)
        assertEquals(b.toJsonString(),DesignVariants.fromJsonString(b.toJsonString()).toJsonString())
    }
    @Test fun snapshotsRetainExactSpeakerAssetsWithoutAnExternalResolver() {
        val data=ClfData("measured",patterns=listOf(ClfPolarPattern(1000,listOf(0f),listOf(-90f,90f),arrayOf(floatArrayOf(0f,0f)))),sensitivityDbByBand=mapOf(1000 to 93f))
        val asset=ProjectArchive.asset(data)
        val base="""{"version":22,"speakers":[{"presetId":"measured","requiresMeasuredData":true}]}"""
        val external=ProjectArchive.encode(base,mapOf("measured" to asset.reference),{null},false)
        val saved=DesignVariants.empty().capture("Measured",external) {if(it==asset.reference.hash) asset.payload else null}
        val restored=DesignVariants.fromJsonString(saved.toJsonString()).prepareRestore(0,scene(0))
        assertTrue(restored.problems.isEmpty());assertEquals(93f,restored.speakers.getValue("measured").sensitivityDbByBand[1000])
        val missing=DesignVariants.empty().capture("Missing",external).prepareRestore(0,scene(0))
        assertEquals(asset.reference,missing.references["measured"]);assertTrue(missing.problems.isNotEmpty())
    }
    @Test fun namesIdsAndLimitsRemainStableAndCorruptionIsRejected() {
        val saved=DesignVariants.empty().capture("A",scene(0)).capture("B",scene(1)).remove(0).rename(1,"Renamed")
        assertEquals(1,saved.variants.single().id)
        assertEquals("Renamed",saved.variants.single().name)
        assertFails {saved.prepareRestore(0,scene(0))}
        assertFails {DesignVariants.fromJsonString(saved.toJsonString().replace("speakerId", "speakerXd"))}
        assertFails {DesignVariants.fromJsonString("{\"version\":99}")}
        var full=DesignVariants.empty();repeat(16) {full=full.capture("$it",scene(it))}
        assertFails {full.capture("Overflow",scene(0))};assertEquals(16,full.variants.size)
    }
}
