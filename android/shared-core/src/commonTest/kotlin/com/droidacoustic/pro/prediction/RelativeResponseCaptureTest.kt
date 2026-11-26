package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import kotlinx.serialization.json.*
import kotlin.test.*

class RelativeResponseCaptureTest {
    private val targets=listOf(SpeakerComparisonSample("near",1000.0,10.0,1.0,0.0,999.0),
        SpeakerComparisonSample("far",1000.0,20.0,1.0,0.0,-999.0),SpeakerComparisonSample("far2k",2000.0,20.0,1.0,0.0,0.0))
    private val norm=RelativeResponseNormalization(10.0,1.0,0.0,-120.0)
    private fun source(x: Float=0f,inverted: Boolean=false,gain: Float=0f,response: FrozenResponse=FrozenResponse.generic(100f,"generic",false,false))=
        SourceSnapshot(0,x,1f,0f,0f,ArrayGeometry.poses(1,.2f,0f,emptyList()),0f,0f,response,gainDb=gain,inverted=inverted,
            comparisonData=ComparisonSourceData("TEST ONLY",null,true,false))
    private fun request(sources: List<SourceSnapshot> = listOf(source()))=PredictionRequest(PredictionTicket(1,1,PredictionChannel.LISTENER),
        sources,emptyList(),1000,listOf(1000),1f/3,emptyMap(),true,Atmosphere())
    @Test fun perFrequencyRatioHasZeroReferenceAndCancelsCommonGainWithoutFitting() {
        val a=request().captureComparison(targets,1,norm,"RELATIVE_TO_ON_AXIS")
        val b=request(listOf(source(gain=12f))).captureComparison(targets.map {it.copy(levelDb=12345.0)},1,norm,"RELATIVE_TO_ON_AXIS")
        assertEquals(0.0,a.run.samples.first().levelDb!!,1e-10)
        a.run.samples.zip(b.run.samples).forEach {(x,y)->assertEquals(x.levelDb!!,y.levelDb!!,1e-7)}
        assertTrue(a.run.samples[1].levelDb!! in -6.1..-6.0)
        assertNotEquals(a.run.samples[1].levelDb,a.run.samples[2].levelDb) // Each frequency has its own air-loss denominator.
        assertEquals(norm,a.normalization)
        assertEquals(norm.canonical(),a.run.conditions.normalization)
        assertEquals(2,Json.parseToJsonElement(a.document).jsonObject.getValue("version").jsonPrimitive.int)
        val restored=ComparisonWorkspace.parse(ComparisonWorkspace.empty().withImported(a,false).toJsonString())
        assertEquals(a.document,restored.engine!!.document)
        assertEquals(a.run.samples,restored.engine!!.run.samples)
    }
    @Test fun equalDistanceOnAxisNormalizationPreservesMeasuredOffAxisAttenuation() {
        val pattern=com.droidacoustic.pro.scene.ClfPolarPattern(1000,listOf(0f,90f,180f,270f),listOf(-90f,90f),
            arrayOf(floatArrayOf(0f,0f),floatArrayOf(-6f,-6f),floatArrayOf(-12f,-12f),floatArrayOf(-6f,-6f)))
        val data=com.droidacoustic.pro.scene.ClfData("TEST ONLY",patterns=listOf(pattern),sensitivityDbByBand=mapOf(1000 to 100f))
        val angular=request(listOf(source(response=FrozenResponse.measured(data,true))))
            .captureComparison(listOf(SpeakerComparisonSample("90-degrees",1000.0,0.0,1.0,10.0,null)),1,norm,"RELATIVE_TO_ON_AXIS")
        assertEquals(-6.0,angular.run.samples.single().levelDb!!,1e-7)
    }

    @Test fun missingNearNullAndBelowFloorDenominatorsRemainUnavailable() {
        val floor=norm.copy(minimumReferenceLevelDb=100.0)
        assertTrue(request().captureComparison(targets,1,floor,"RELATIVE_TO_REFERENCE").run.samples.all {it.levelDb==null})
        val cancelled=request(listOf(source(-1f),source(1f,inverted=true)))
        assertTrue(cancelled.captureComparison(targets,1).run.samples.any {it.levelDb!=null})
        val nullNorm=RelativeResponseNormalization(0.0,1.0,10.0,-60.0)
        assertTrue(cancelled.captureComparison(targets,1,nullNorm,"RELATIVE_TO_REFERENCE").run.samples.all {it.levelDb==null})
        val data=com.droidacoustic.pro.scene.ClfData("TEST",sensitivityDbByBand=mapOf(1000 to 100f),patterns=listOf(
            com.droidacoustic.pro.scene.ClfPolarPattern(1000,listOf(0f),listOf(-90f,90f),arrayOf(floatArrayOf(0f,0f)))))
        val partial=request(listOf(source(response=FrozenResponse.measured(data,true)))).captureComparison(targets,1,norm,"RELATIVE_TO_REFERENCE")
        assertNotNull(partial.run.samples[0].levelDb);assertNull(partial.run.samples[2].levelDb)
        val detail=Json.parseToJsonElement(partial.document).jsonObject.getValue("source").jsonObject.getValue("normalizationReferences").jsonArray
        assertEquals("UNAVAILABLE",detail.last().jsonObject.getValue("status").jsonPrimitive.content)
    }
    @Test fun onAxisIsVerifiedAndUnsupportedOrContradictoryNormalizationIsRejected() {
        assertFails {request().captureComparison(targets,1,norm.copy(zM=2.0),"RELATIVE_TO_ON_AXIS")}
        assertFails {request(listOf(source(),source(1f))).captureComparison(targets,1,norm,"RELATIVE_TO_ON_AXIS")}
        assertFails {request().captureComparison(targets,1,null,"RELATIVE_TO_REFERENCE")}
        assertFails {request().captureComparison(targets,1,norm,"ABSOLUTE_SPL")}
        assertFails {RelativeResponseNormalization(0.0,0.0,0.0,Double.NaN)}
        val a=request().captureComparison(targets,1,norm,"RELATIVE_TO_REFERENCE")
        val b=request().captureComparison(targets,1,norm.copy(xM=5.0),"RELATIVE_TO_REFERENCE")
        assertNotEquals(a.run.conditions,b.run.conditions)
        val reference=a.run.copy(evidence=ComparisonEvidence.MANUFACTURER_SOFTWARE_EXPORT)
        fun status(ref: SpeakerComparisonRun,engine: SpeakerComparisonRun)=Json.parseToJsonElement(SpeakerComparisons.compare(ref,engine,0.1))
            .jsonObject.getValue("status").jsonPrimitive.content
        assertEquals("NOT_COMPARABLE",status(reference,b.run))
        assertEquals("NOT_COMPARABLE",status(reference.copy(conditions=reference.conditions.copy(normalization=null)),
            a.run.copy(conditions=a.run.conditions.copy(normalization=null))))
        assertEquals("NOT_COMPARABLE",status(reference.copy(conditions=reference.conditions.copy(normalization="unsupported")),
            a.run.copy(conditions=a.run.conditions.copy(normalization="unsupported"))))
        val broken=Json.parseToJsonElement(a.document).jsonObject.toMutableMap();broken["version"]=JsonPrimitive(1)
        assertFails {ImportedComparison.parse(JsonObject(broken).toString())}
    }
}
