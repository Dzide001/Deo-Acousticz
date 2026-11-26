package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.persistence.*
import com.droidacoustic.pro.statistics.*
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class AcousticBoundaryTest {
    private fun wall(x: Double=0.0,y0: Float= -5f,y1: Float=5f,z0: Double= -5.0,z1: Double=5.0)=AudienceMesh.create(
        listOf(AudienceMeshVertex(x,y0,z0),AudienceMeshVertex(x,y0,z1),AudienceMeshVertex(x,y1,z1),AudienceMeshVertex(x,y1,z0)),
        listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3)))
    private fun floor(y: Float=0f)=AudienceMesh.create(listOf(AudienceMeshVertex(-10.0,y,-10.0),AudienceMeshVertex(10.0,y,-10.0),
        AudienceMeshVertex(10.0,y,10.0),AudienceMeshVertex(-10.0,y,10.0)),listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3)))
    private fun roles(m: AudienceMesh,role: AcousticBoundaryRole)=m.assignBoundaryRoles(m.triangles().indices.toList(),role)
    private fun surface(m: AudienceMesh,id: Int=0)=AudienceSurface(id,"Boundary","AUDIENCE_SEATED",emptyList(),importedMesh=m)
    private fun prepare(m: AudienceMesh)=BoundaryGeometry.prepare(listOf(surface(m)))
    private fun point(x: Double,y: Double=0.0,z: Double=0.0)=BoundaryPoint(x,y,z)
    private val air=Atmosphere()
    private val field=DirectField(air)
    private val bands=Bands.segments(1000,1f/3)
    private fun source(x: Float=2f,y: Float=0f,z: Float=0f,group: Int=0,radiator: Radiator=Radiator {_,_,_ -> 100f})=
        SoundSource(group,x,y,z,180f,0f,radiator)
    private fun power(m: AudienceMesh,s: SoundSource=source(),rx: Float=3f,ry: Float=0f,rz: Float=0f,
        segments: List<Bands.Segment> = bands)=field.bandPower(listOf(s),rx,ry,rz,segments,boundaries=prepare(m))

    @Test fun rolesAreImmutableIndependentOfAudienceAssignmentsAndGroupMembership() {
        val original=wall().createFaceGroup("Wall",listOf(0,1))
        val roles=mutableListOf(AcousticBoundaryRole.OCCLUDING,AcousticBoundaryRole.NONE)
        val changed=original.withBoundaryRoles(roles); roles[0]=AcousticBoundaryRole.NONE
        assertEquals(AcousticBoundaryRole.OCCLUDING,changed.boundaryRole(0))
        val grouped=changed.assignGroupBoundaryRole(1,AcousticBoundaryRole.RIGID_REFLECTOR)
            .assignFaceGroup(1,MeshFaceAssignment.EXCLUDED).renameFaceGroup(1,"Rear wall")
        assertTrue(grouped.boundaryRoles().all {it==AcousticBoundaryRole.RIGID_REFLECTOR})
        assertTrue(original.boundaryRoles().all {it==AcousticBoundaryRole.NONE})
        assertEquals(grouped.boundaryRoles(),grouped.removeFaceGroup(1).boundaryRoles())
        assertEquals(grouped.boundaryRoles(),grouped.mapCoordinates {PlanPoint(-it.x+2,it.z+1)}.boundaryRoles())
        assertFails {original.withBoundaryRoles(emptyList())}; assertFails {original.assignBoundaryRoles(listOf(2),AcousticBoundaryRole.NONE)}
        assertFails {original.assignGroupBoundaryRole(99,AcousticBoundaryRole.OCCLUDING)}
    }
    @Test fun transparentDefaultsDoNotChangeFreeFieldAndOpaqueFacesBlockOnlyCrossingPaths() {
        val free=field.bandPower(listOf(source(-2f)),3f,0f,0f,bands)
        assertEquals(free,power(wall(),source(-2f)),0.0)
        val m=roles(wall(),AcousticBoundaryRole.OCCLUDING)
        assertEquals(0.0,power(m,source(-2f)),0.0)
        assertEquals(field.bandPower(listOf(source()),3f,0f,0f,bands),power(m),0.0)
        assertFalse(prepare(m).blocked(point(-2.0,z=6.0),point(3.0,z=6.0)))
        assertTrue(prepare(m).blocked(point(3.0),point(-2.0)))
    }
    @Test fun endpointAndCoplanarContactsDoNotSelfOccludeButInteriorSeamsDo() {
        val g=prepare(roles(wall(),AcousticBoundaryRole.OCCLUDING))
        assertFalse(g.blocked(point(0.0),point(2.0))); assertFalse(g.blocked(point(2.0),point(0.0)))
        assertFalse(g.blocked(point(0.0,-2.0),point(0.0,2.0)))
        assertTrue(g.blocked(point(-2.0),point(2.0))) // Hits the shared diagonal.
    }
    @Test fun assignmentExclusionsAndPlanMasksDoNotRemovePhysicalBoundaries() {
        val mesh=roles(wall(),AcousticBoundaryRole.OCCLUDING).assignFaces(listOf(0,1),MeshFaceAssignment.EXCLUDED)
        val mask=listOf(PlanPoint(-1.0,-6.0),PlanPoint(1.0,-6.0),PlanPoint(1.0,6.0),PlanPoint(-1.0,6.0))
        val s=AudienceSurface(0,"Wall","STAGE",emptyList(),importedMesh=mesh,exclusions=listOf(mask))
        assertTrue(BoundaryGeometry.prepare(listOf(s)).blocked(point(-2.0),point(2.0)))
        val coverage=CoveragePlan(listOf(s),emptyList(),20f,20f,1f).prepare()
        assertEquals(0,coverage.receiverCount)
    }
    @Test fun finiteRigidFacetProducesOneSpecularPathIncludingFromItsBackSide() {
        val g=prepare(roles(wall(),AcousticBoundaryRole.RIGID_REFLECTOR))
        val path=g.reflections(point(2.0),point(3.0)).single()
        assertEquals(point(-3.0),path.imageReceiver); assertEquals(point(0.0),path.bounce)
        assertEquals(1,g.reflections(point(-2.0),point(-3.0)).size)
        assertTrue(g.reflections(point(-2.0),point(3.0)).isEmpty())
        assertTrue(g.reflections(point(2.0,z=9.0),point(3.0,z=9.0)).isEmpty())
        assertTrue(g.reflections(point(0.0),point(3.0)).isEmpty())
    }
    @Test fun reflectingLegsMustBeVisibleAndCoincidentReflectorsDoNotDuplicatePressure() {
        val reflector=roles(wall(),AcousticBoundaryRole.RIGID_REFLECTOR)
        val duplicate=BoundaryGeometry.prepare(listOf(surface(reflector),surface(reflector,1)))
        assertEquals(1,duplicate.reflections(point(2.0),point(3.0)).size)
        val blocker=roles(wall(1.0),AcousticBoundaryRole.OCCLUDING)
        val g=BoundaryGeometry.prepare(listOf(surface(reflector),surface(blocker,1)))
        assertTrue(g.reflections(point(2.0),point(3.0)).isEmpty())
        assertFalse(g.blocked(point(2.0),point(3.0)))
    }
    @Test fun rigidReflectionMatchesIndependentAnalyticBandInterference() {
        val m=roles(wall(),AcousticBoundaryRole.RIGID_REFLECTOR)
        val actual=power(m)
        val expected=bands.sumOf {seg ->
            fun amplitude(distance: Double)=10.0.pow((100-20*log10(distance)-air.absorptionDbPerMetre(seg.centreHz)*distance)/20)
            val a=amplitude(1.0); val b=amplitude(5.0); val dt=4/air.speedOfSound
            val x=PI*seg.widthHz*dt
            (a*a+b*b+2*a*b*cos(2*PI*seg.centreHz*dt)*sin(x)/x)*seg.widthHz
        }/bands.sumOf {it.widthHz}
        assertEquals(expected,actual,expected*1e-10)
    }
    @Test fun reflectionUsesDepartureDirectivityAndFullReflectedDistance() {
        val directional=source(radiator=Radiator {_,az,_ -> if (abs(az)<1f) 100f else 80f})
        // Source points toward -X: reflection departs on-axis; direct arrival is behind it.
        val actual=power(roles(wall(),AcousticBoundaryRole.RIGID_REFLECTOR),directional)
        val reflected=source(radiator=Radiator {_,_,_ -> 100f})
        val wrong=source(radiator=Radiator {_,_,_ -> 80f})
        assertTrue(actual>power(roles(wall(),AcousticBoundaryRole.RIGID_REFLECTOR),wrong))
        assertTrue(actual<power(roles(wall(),AcousticBoundaryRole.RIGID_REFLECTOR),reflected))
    }
    @Test fun occlusionIsPerArrayElementAndMissingDataIsCheckedOnlyForSurvivingPaths() {
        val m=roles(wall(0.0,-1f,1f),AcousticBoundaryRole.OCCLUDING)
        val low=source(-2f); val high=source(-2f,4f)
        val actual=field.bandPower(listOf(low,high),3f,0f,0f,bands,boundaries=prepare(m))
        val expected=field.bandPower(listOf(high),3f,0f,0f,bands)
        assertEquals(expected,actual,expected*1e-10)
        val unknown=source(-2f,radiator=Radiator {_,_,_ -> Float.NaN})
        assertEquals(0.0,power(m,unknown),0.0)
        val missingReflection=source(radiator=Radiator {_,az,_ -> if (abs(az)<1f) Float.NaN else 100f})
        assertTrue(power(roles(wall(),AcousticBoundaryRole.RIGID_REFLECTOR),missingReflection).isNaN())
    }
    @Test fun independentSourcesRemainCoherentWithTheirOwnReflections() {
        val g=prepare(roles(wall(),AcousticBoundaryRole.RIGID_REFLECTOR))
        val a=source(group=0); val b=source(group=1)
        val one=field.bandPower(listOf(a),3f,0f,0f,bands,coherent=false,boundaries=g)
        val both=field.bandPower(listOf(a,b),3f,0f,0f,bands,coherent=false,boundaries=g)
        assertEquals(2*one,both,one*1e-10)
        val coherent=field.bandPower(listOf(a,b),3f,0f,0f,bands,coherent=true,boundaries=g)
        assertEquals(4*one,coherent,one*1e-10)
    }
    @Test fun globalGroundAndFiniteGroundDoNotDoubleCountAndBlockersCheckGroundLegs() {
        val s=source(0f,2f); val rigid=prepare(roles(floor(),AcousticBoundaryRole.RIGID_REFLECTOR))
        val ground=FloorReflection {0.5f}
        val baseline=field.bandPower(listOf(s),6f,2f,0f,bands,floor=ground)
        assertEquals(baseline,field.bandPower(listOf(s),6f,2f,0f,bands,floor=ground,boundaries=rigid),baseline*1e-10)
        val lowWall=prepare(roles(wall(1.0,0f,1.7f),AcousticBoundaryRole.OCCLUDING))
        val direct=field.bandPower(listOf(s),6f,2f,0f,bands)
        assertEquals(direct,field.bandPower(listOf(s),6f,2f,0f,bands,floor=ground,boundaries=lowWall),direct*1e-10)
        val absorbing=prepare(roles(floor(),AcousticBoundaryRole.OCCLUDING))
        // Explicit non-reflecting ground patches suppress the global floor at their bounce.
        assertEquals(direct,field.bandPower(listOf(s),6f,2f,0f,bands,floor=ground,boundaries=absorbing),direct*1e-10)
    }
    @Test fun transformsAndRakeAffectBoundaryPositionWithoutChangingRoles() {
        val m=roles(floor(),AcousticBoundaryRole.OCCLUDING)
        val s=AudienceSurface(0,"Raised","AUDIENCE_SEATED",emptyList(),baseHeightM=3f,rakeDeg=10f,importedMesh=m)
        val g=BoundaryGeometry.prepare(listOf(s))
        assertTrue(g.blocked(point(0.0,1.0),point(0.0,5.0)))
        assertFalse(g.blocked(point(0.0,-1.0),point(0.0,1.0)))
        val moved=roles(wall(),AcousticBoundaryRole.OCCLUDING).mapCoordinates {PlanPoint(it.x+10,it.z)}
        assertFalse(prepare(moved).blocked(point(-2.0),point(2.0)))
        assertTrue(prepare(moved).blocked(point(8.0),point(12.0)))
    }
    @Test fun cancellationAndBudgetFailuresNeverReturnPartialBoundaryResults() {
        val m=roles(wall(),AcousticBoundaryRole.RIGID_REFLECTOR)
        assertFailsWith<IllegalStateException> {BoundaryGeometry.prepare(listOf(surface(m)),BoundaryWork({error("cancel")}))}
        assertFailsWith<IllegalArgumentException> {prepare(m).reflections(point(2.0),point(3.0),BoundaryWork(limit=1))}
        assertFailsWith<IllegalStateException> {prepare(m).blocked(point(-2.0),point(2.0),BoundaryWork({error("cancel")}))}
    }
    @Test fun portableRolesRoundTripAndLegacyMeshesStayAcousticallyTransparent() {
        val m=roles(wall(),AcousticBoundaryRole.RIGID_REFLECTOR).createFaceGroup("Rear wall",listOf(0,1))
        assertEquals(m,AudienceMeshJson.decode(AudienceMeshJson.encode(m)))
        for (version in 1..4) {
            val old=Json.parseToJsonElement(AudienceMeshJson.encode(m)).jsonObject.toMutableMap().apply {
                put("version",JsonPrimitive(version)); remove("boundaryRoles")
            }
            assertTrue(AudienceMeshJson.decode(JsonObject(old).toString()).boundaryRoles().all {it==AcousticBoundaryRole.NONE})
        }
        val root=Json.parseToJsonElement(AudienceMeshJson.encode(m)).jsonObject
        for (bad in listOf<JsonElement?>(null,JsonNull,JsonArray(emptyList()),JsonArray(List(2) {JsonPrimitive("UNKNOWN")}),JsonArray(List(2) {JsonPrimitive(0)}))) {
            val payload=JsonObject(root.toMutableMap().apply {if (bad==null) remove("boundaryRoles") else put("boundaryRoles",bad)})
            assertFails {AudienceMeshJson.decode(payload.toString())}
            assertFails {ProjectArchive.validateScene("""{"version":${ProjectArchive.VERSION},"audienceAreas":[{"id":0,"audienceMesh":$payload}]}""")}
        }
    }
    @Test fun completelyBlockedBroadbandDoesNotSumDisplayFloorsAsAcousticEnergy() {
        val snapshot=SourceSnapshot(0,-2f,0f,0f,0f,ArrayGeometry.poses(1,0.2f,0f,emptyList()),0f,0f,
            FrozenResponse.generic(100f,"generic",false,false))
        val request=PredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),listOf(snapshot),listOf(ReceiverSnapshot(3f,0f,0f)),
            1000,listOf(500,1000,2000),1f,emptyMap(),true,air,
            boundarySurfaces=listOf(surface(roles(wall(),AcousticBoundaryRole.OCCLUDING))))
        assertEquals(0f,request.calculate().levelAt(0),0f)
    }
    @Test fun immutableRequestsUseFrozenBoundaryRolesAndNoBoundaryMetadataChangesCoverageArea() {
        val m=wall().createFaceGroup("Wall",listOf(0,1)); val s=surface(m)
        val blocked=surface(roles(m,AcousticBoundaryRole.OCCLUDING))
        val snapshot=SourceSnapshot(0,-2f,0f,0f,0f,ArrayGeometry.poses(1,0.2f,0f,emptyList()),0f,0f,
            FrozenResponse.generic(100f,"generic",false,false))
        val surfaces=mutableListOf(s)
        val request=PredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),listOf(snapshot),listOf(ReceiverSnapshot(3f,0f,0f)),
            1000,listOf(1000),1f/3,emptyMap(),true,air,boundarySurfaces=surfaces)
        surfaces[0]=blocked
        assertTrue(request.calculate().levelAt(0)>0f)
        val current=PredictionRequest(PredictionTicket(1,2,PredictionChannel.COVERAGE),listOf(snapshot),listOf(ReceiverSnapshot(3f,0f,0f)),
            1000,listOf(1000),1f/3,emptyMap(),true,air,boundarySurfaces=surfaces)
        assertEquals(0f,current.calculate().levelAt(0),0f)
        for (surface in listOf(s,blocked)) {
            val g=CoveragePlan(listOf(surface),emptyList(),20f,20f,2f).prepare()
            assertEquals(100.0,CoverageStatisticsCalculator.calculate(g,FloatArray(g.receiverCount) {90f}).overall.totalAreaM2,1e-6)
        }
    }
}
