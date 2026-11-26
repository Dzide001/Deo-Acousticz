package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class SourceSpectrumTest {
    private val targets=listOf(SpeakerComparisonSample("r",0.0,10.0,1.0,0.0,null))
    private fun capture(spectrum: BroadbandSourceSpectrum?,response: FrozenResponse=FrozenResponse.generic(100f,"generic",false,false))=
        PredictionRequest(PredictionTicket(1,1,PredictionChannel.LISTENER),listOf(SourceSnapshot(0,0f,1f,0f,0f,
            ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,response,
            comparisonData=ComparisonSourceData("TEST ONLY",null,true,false))),emptyList(),1000,OctaveWeighting.bands,1f,
            OctaveWeighting.tables.getValue("Z"),true,Atmosphere(),comparisonWeighting="Z",comparisonSpectrum=spectrum)
            .captureComparison(targets,1,levelConvention="BROADBAND_SPL",broadbandCapture=true)
    @Test fun totalDriveFractionsConservePowerAndHaveExplicitSpectralShapes() {
        for(mode in listOf("TOTAL_EQUAL_BAND_POWER","TOTAL_FLAT_PSD")) {
            val s=BroadbandSourceSpectrum.preset(mode);val f=s.fractions(OctaveWeighting.bands)
            assertEquals(1.0,f.values.sum(),1e-12)
            assertEquals(s.canonical(),BroadbandSourceSpectrum.parseJson(s.canonical()).canonical())
        }
        val flat=BroadbandSourceSpectrum.preset("TOTAL_FLAT_PSD").fractions(OctaveWeighting.bands)
        assertEquals(2.0,flat.getValue(2000)/flat.getValue(1000),1e-12)
        val raw=OctaveWeighting.bands.associateWith {1.0}.toMutableMap()
        val s=BroadbandSourceSpectrum.custom(raw);raw[1000]=100.0
        assertEquals(.125,s.fractions(OctaveWeighting.bands).getValue(1000),1e-12)
        val old=capture(null);val normalized=capture(s)
        assertEquals(-10*log10(8.0),normalized.run.samples.single().levelDb!!-old.run.samples.single().levelDb!!,1e-8)
        assertEquals(5,Json.parseToJsonElement(normalized.document).jsonObject.getValue("version").jsonPrimitive.int)
        assertNotEquals(old.run.conditions,normalized.run.conditions)
        val state=ComparisonWorkspace.empty().withImported(normalized,false)
        assertEquals(normalized.document,ComparisonWorkspace.parse(state.toJsonString()).engine!!.document)
    }
    @Test fun explicitZeroExcitationNeedsNoResponseButMissingActiveBandsStayUnavailable() {
        val frequencies=Bands.THIRD_OCTAVE_HZ.filter {it!=10000}
        val data=com.droidacoustic.pro.scene.ClfData("TEST",sensitivityDbByBand=frequencies.associateWith {100f},patterns=frequencies.map {
            com.droidacoustic.pro.scene.ClfPolarPattern(it,listOf(0f),listOf(-90f,90f),arrayOf(floatArrayOf(0f,0f)))})
        val response=FrozenResponse.measured(data,true)
        assertNull(capture(BroadbandSourceSpectrum.preset("TOTAL_EQUAL_BAND_POWER"),response).run.samples.single().levelDb)
        val custom=BroadbandSourceSpectrum.custom(OctaveWeighting.bands.associateWith {if(it==8000) 0.0 else 1.0})
        val result=capture(custom,response)
        assertNotNull(result.run.samples.single().levelDb)
        val band=Json.parseToJsonElement(result.document).jsonObject.getValue("bandDiagnostics").jsonArray.single().jsonObject.getValue("bands").jsonObject
        assertEquals(0.0,band.getValue("8000").jsonPrimitive.double)
        assertEquals(1.0,result.broadband!!.driveFractions.values.sum(),1e-12)
    }
    @Test fun invalidAmbiguousAndTamperedAllocationsAreRejected() {
        assertFails {BroadbandSourceSpectrum.custom(OctaveWeighting.bands.associateWith {0.0})}
        assertFails {BroadbandSourceSpectrum.custom(mapOf(1000 to -1.0))}
        assertFails {BroadbandSourceSpectrum.custom(mapOf(1000 to Double.NaN))}
        assertFails {BroadbandSourceSpectrum.custom(mapOf(1000 to 1.0)).fractions(OctaveWeighting.bands)}
        val result=capture(BroadbandSourceSpectrum.preset("TOTAL_FLAT_PSD"))
        val root=Json.parseToJsonElement(result.document).jsonObject.toMutableMap();root["version"]=JsonPrimitive(3)
        assertFails {ImportedComparison.parse(JsonObject(root).toString())}
        val definition=Json.parseToJsonElement(result.broadband!!.canonical()).jsonObject.toMutableMap()
        definition["driveFractions"]=buildJsonObject {OctaveWeighting.bands.forEach {put(it.toString(),1.0)}}
        assertFails {BroadbandDefinition.parse(JsonObject(definition))}
    }
}
