package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.*
import com.droidacoustic.pro.statistics.*
import kotlin.test.*

class PredictionValidityTest {
    private fun source(response:FrozenResponse)=SourceSnapshot(17,0f,1f,0f,0f,
        ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,response)
    private fun measured(elevations:List<Float> = listOf(-90f,90f))=FrozenResponse.measured(
        ClfData("fixture",patterns=listOf(ClfPolarPattern(1000,listOf(0f),elevations,arrayOf(FloatArray(elevations.size)))),
            sensitivityDbByBand=mapOf(1000 to 90f)),true)
    private fun request(response:FrozenResponse,width:Float=1f/3,receivers:List<ReceiverSnapshot> = listOf(ReceiverSnapshot(10f,1f,0f)))=
        PredictionRequest(PredictionTicket(1,1,PredictionChannel.COVERAGE),listOf(source(response)),receivers,
            1000,listOf(1000),width,emptyMap(),true,Atmosphere())
    @Test fun missingIntegrationCellsNameActualSourceAndDataFrequencies() {
        val output=request(measured(),1f).calculate();val v=output.validity
        assertTrue(output.levelAt(0).isNaN());assertEquals(PredictionAvailability.UNAVAILABLE,v.availability)
        assertEquals(listOf(1000),v.unavailableBands());assertEquals(listOf(MissingSourceBand(17,800),MissingSourceBand(17,1250)),v.missingSourceBands())
        assertTrue(v.status().contains("S17"));assertTrue(v.status().contains("800"))
        assertEquals(v.status(),CoveragePresentation.describe("BAND",1000,PredictionState.CURRENT,false,v).status)
    }
    @Test fun directionalMissingDataMakesOnlySomeReceiversAvailable() {
        val output=request(measured(listOf(-10f,10f)),receivers=listOf(ReceiverSnapshot(10f,1f,0f),ReceiverSnapshot(10f,11f,0f))).calculate()
        assertTrue(output.levelAt(0).isFinite());assertTrue(output.levelAt(1).isNaN())
        assertEquals(PredictionAvailability.PARTIAL,output.validity.availability)
        assertEquals(1,output.validity.availableReceiverCount);assertTrue(output.validity.status().contains("partial"))
    }
    @Test fun absentAssetIsUnavailableAndFreshBandResultIsAvailable() {
        assertEquals(PredictionAvailability.UNAVAILABLE,request(FrozenResponse.missing()).calculate().validity.availability)
        val v=request(measured()).calculate().validity
        assertEquals(PredictionAvailability.AVAILABLE,v.availability);assertTrue(v.missingSourceBands().isEmpty())
        assertEquals("Results available",v.status())
    }
    @Test fun zeroPowerIsAvailableButEmptyRequestsAreUnavailable() {
        assertEquals(PredictionAvailability.AVAILABLE,PredictionValidity.summarize(floatArrayOf(-300f)).availability)
        assertEquals(PredictionAvailability.UNAVAILABLE,PredictionValidity.summarize(floatArrayOf()).availability)
        assertTrue(PredictionValidity.summarize(floatArrayOf()).status().contains("no receivers"))
    }
    @Test fun availableProbesDoNotHideUnavailableOrPartiallyAvailableAudienceArea() {
        val area=AreaStatistics(10.0,0.0,null,null,null,null,null,null,null,null,null,null)
        val v=PredictionValidity.summarize(floatArrayOf(90f,Float.NaN),statistics=CoverageStatistics(area,emptyList()))
        assertEquals(PredictionAvailability.UNAVAILABLE,v.availability)
        assertEquals(PredictionAvailability.PARTIAL,PredictionValidity.summarize(floatArrayOf(90f,Float.NaN),statistics=CoverageStatistics(area.copy(validAreaM2=5.0),emptyList())).availability)
    }
    @Test fun warningsCannotOutliveTheRevisionOrAffectRetryInstructions() {
        val v=request(FrozenResponse.missing()).calculate().validity
        for(state in listOf(PredictionState.STALE,PredictionState.RUNNING,PredictionState.FAILED)) {
            assertEquals(CoveragePresentation.describe("BAND",1000,state,false),CoveragePresentation.describe("BAND",1000,state,false,v))
        }
        val bands=mutableListOf(1000);val issues=mutableListOf(MissingSourceBand(4,1000))
        val owned=PredictionValidity.summarize(floatArrayOf(Float.NaN),bands,issues);bands.clear();issues.clear()
        assertEquals(listOf(1000),owned.unavailableBands());assertEquals(4,owned.missingSourceBands().single().sourceId)
    }
    @Test fun unexcitedBandsDoNotCreateMissingDataWarnings() {
        val r=PredictionRequest(PredictionTicket(1,1,PredictionChannel.COVERAGE),listOf(source(measured())),listOf(ReceiverSnapshot(10f,1f,0f)),
            1000,listOf(1000,2000),1f/3,emptyMap(),true,Atmosphere(),retainPhase=false,bandDriveFractions=mapOf(1000 to 1.0,2000 to 0.0))
        val v=r.calculate().validity;assertEquals(PredictionAvailability.AVAILABLE,v.availability);assertTrue(v.missingSourceBands().isEmpty())
    }
}
