package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class ReceiverAngularSupportTest {
    private fun data():ClfData {
        val az=listOf(0f,90f,180f,270f);val el=listOf(-90f,0f,90f)
        val declaration=AngularDomain.parse("""{"version":1,"provenance":"TEST ONLY","regions":[{"origin":"MEASURED","azimuths":[0,90,180],"elevations":[-90,0,90],"basis":"test angular acquisition"},{"origin":"SYMMETRY","azimuths":[270],"elevations":[-90,0,90],"basis":"test symmetry assumption","operation":"MIRROR_AZIMUTH_ZERO"}]}""",az,el)
        return ClfData("test",patterns=listOf(ClfPolarPattern(1000,az,el,Array(4){FloatArray(3)},declaration)),sensitivityDbByBand=mapOf(1000 to 90f))
    }
    private fun request(response:FrozenResponse=FrozenResponse.measured(data(),true),yaw:Float=0f,tilt:Float=0f,steer:Float=0f,count:Int=1,band:Int=1000)=PredictionRequest(
        PredictionTicket(7,1,PredictionChannel.LISTENER),listOf(SourceSnapshot(42,0f,0f,0f,yaw,ArrayGeometry.poses(count,.3f,tilt,emptyList()),steer,0f,response)),
        emptyList(),band,listOf(band),1f/3,emptyMap(),true,Atmosphere())
    private fun receiver(id:String="r",x:Double=10.0,y:Double=0.0,z:Double=0.0)=AngularReceiver(id,id,BoundaryPoint(x,y,z))
    @Test fun transformedDirectRaysReportOriginsAndSparseInterpolationSeparately() {
        val report=request().receiverAngularSupport(listOf(receiver(),receiver("sym",0.0,0.0,-10.0),receiver("between",10.0,0.0,10.0)))
        assertEquals(listOf("MEASURED","SYMMETRY","INTERPOLATED"),report.receivers.map {it.origin})
        assertEquals(listOf("STORED","STORED","SPARSE"),report.receivers.map {it.support})
        assertEquals(42,report.receivers.first().rows.single().sourceId)
        assertEquals(7L,report.sceneRevision)
        val yaw=request(yaw=90f).receiverAngularSupport(listOf(receiver("yaw",0.0,0.0,10.0))).receivers.single().rows.single()
        assertEquals(0f,yaw.azimuthDeg,1e-4f)
    }
    @Test fun arrayGeometryAndMechanicalTiltRotateRaysButElectronicSteeringDoesNot() {
        val targets=listOf(receiver("tilted",10*cos(PI/6),-5.0,0.0))
        val mechanical=request(tilt=30f).receiverAngularSupport(targets).receivers.single().rows.single()
        assertEquals(0f,mechanical.elevationDeg,1e-4f)
        assertEquals(-30f,request().receiverAngularSupport(targets).receivers.single().rows.single().elevationDeg,1e-4f)
        val a=request(count=2,tilt=10f).receiverAngularSupport(targets)
        val b=request(count=2,tilt=10f,steer=20f).receiverAngularSupport(targets)
        assertEquals(a.toJsonString(),b.toJsonString())
        assertEquals(listOf(0,1),a.receivers.single().rows.map {it.elementIndex})
        assertNotEquals(a.receivers.single().rows[0].elevationDeg,a.receivers.single().rows[1].elevationDeg)
    }
    @Test fun missingGenericBypassedAndCoincidentDirectionsCannotBecomeMeasuredSuccess() {
        assertEquals("UNAVAILABLE",request(FrozenResponse.missing()).receiverAngularSupport(listOf(receiver())).receivers.single().support)
        assertEquals("UNASSESSED",request(FrozenResponse.generic(90f,"generic",false,true)).receiverAngularSupport(listOf(receiver())).receivers.single().support)
        val bypass=request(FrozenResponse.measured(data(),false)).receiverAngularSupport(listOf(receiver())).receivers.single()
        assertEquals("UNASSESSED",bypass.support);assertEquals("UNDECLARED",bypass.origin)
        assertEquals("UNAVAILABLE",request().receiverAngularSupport(listOf(receiver(x=0.0))).receivers.single().support)
        assertEquals("UNAVAILABLE",request(band=8000).receiverAngularSupport(listOf(receiver())).receivers.single().support)
    }
    @Test fun frozenResponsesMarkersAndReceiverIdentitiesRemainIndependentOfLiveInputs() {
        val data=data();val r=request(FrozenResponse.measured(data,true))
        val targets=mutableListOf(receiver(),receiver("same-position"))
        val before=r.receiverAngularSupport(targets)
        data.patterns[0].spl[0][1]=Float.NaN;targets.clear()
        assertEquals(before.toJsonString(),r.receiverAngularSupport(listOf(receiver(),receiver("same-position"))).toJsonString())
        val lines=before.markerLines();assertEquals(48,lines.size)
        assertTrue(lines.all {l->listOf(l.from.x,l.from.y,l.from.z,l.to.x,l.to.y,l.to.z).all {it.isFinite()}})
        assertEquals(10.46,lines.maxOf {it.from.x},1e-10)
        assertFails {r.receiverAngularSupport(listOf(receiver(),receiver()))}
        assertFails {r.receiverAngularSupport(List(66){receiver("$it")})}
        assertFails {request(count=320).receiverAngularSupport(List(65){receiver("$it")})}
        assertFails {r.receiverAngularSupport(listOf(receiver(x=1e12+.1)))}
        assertFails {r.receiverAngularSupport(listOf(receiver())) {error("cancelled")}}
    }
    @Test fun elevationBoundsAndComplexBandOriginsAreNotHiddenByMagnitudeData() {
        val az=listOf(0f,90f,180f,270f)
        val complex=ComplexSpeakerResponse.fromJsonString(buildJsonObject {
            put("version",2);put("maximumInterpolationGapHz",1000);put("frequencyInterpolation","cartesian-linear-hz")
            put("convention",ComplexSpeakerResponse.CONVENTION);put("provenance","TEST ONLY");put("referenceDrive","test")
            putJsonArray("patterns") {for(hz in listOf(800,1200)) add(buildJsonObject {
                put("frequency",hz);put("azimuths",JsonArray(az.map(::JsonPrimitive)));put("elevations",JsonArray(listOf(JsonPrimitive(0))))
                put("real",JsonArray(az.map {JsonArray(listOf(JsonPrimitive(1)))}));put("imaginary",JsonArray(az.map {JsonArray(listOf(JsonPrimitive(0)))}))
            })}
        }.toString())
        val source=data().copy(complexResponse=complex,sensitivityDbByBand=emptyMap())
        val good=request(FrozenResponse.measured(source,true)).receiverAngularSupport(listOf(receiver())).receivers.single()
        assertEquals("STORED",good.support);assertEquals("UNDECLARED",good.origin)
        assertEquals(listOf(800,1200),good.rows.filter {it.probe.required}.map {it.probe.frequencyHz})
        val bad=request(FrozenResponse.measured(source,true)).receiverAngularSupport(listOf(receiver(y=1.0))).receivers.single()
        assertEquals("UNAVAILABLE",bad.support)
        assertTrue(bad.rows.any {it.probe.point?.samplingStatus=="OUTSIDE_ELEVATION"})
    }
    @Test fun aMissingSecondSourceCannotBeHiddenByAnAvailableFirstSource() {
        val sources=listOf(SourceSnapshot(1,0f,0f,0f,0f,ArrayGeometry.poses(1,.3f,0f,emptyList()),0f,0f,FrozenResponse.measured(data(),true)),
            SourceSnapshot(2,0f,0f,0f,0f,ArrayGeometry.poses(1,.3f,0f,emptyList()),0f,0f,FrozenResponse.missing()))
        val r=PredictionRequest(PredictionTicket(8,1,PredictionChannel.LISTENER),sources,emptyList(),1000,listOf(1000),1f/3,emptyMap(),true,Atmosphere())
            .receiverAngularSupport(listOf(receiver())).receivers.single()
        assertEquals(listOf(1,2),r.rows.map {it.sourceId});assertEquals("UNAVAILABLE",r.support);assertEquals("UNDECLARED",r.origin)
    }

}
