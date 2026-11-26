package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.statistics.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.persistence.*
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class MeshFaceAssignmentTest {
    private val nodes=listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(4.0,0f,0.0),
        AudienceMeshVertex(4.0,0f,4.0),AudienceMeshVertex(0.0,0f,4.0),
        AudienceMeshVertex(0.0,3f,0.0),AudienceMeshVertex(4.0,3f,0.0),
        AudienceMeshVertex(4.0,3f,4.0),AudienceMeshVertex(0.0,3f,4.0))
    private val faces=listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3),SurfaceTriangle(1,5,6),
        SurfaceTriangle(1,6,2),SurfaceTriangle(4,7,6),SurfaceTriangle(4,6,5))
    private fun mesh()=AudienceMesh.create(nodes,faces)
    private fun assigned()=mesh().assignFaces(listOf(0,1),MeshFaceAssignment.SEATED)
        .assignFaces(listOf(2,3),MeshFaceAssignment.EXCLUDED).assignFaces(listOf(4,5),MeshFaceAssignment.STANDING)
    private fun flat()=AudienceMesh.create(nodes.take(4),faces.take(2))
    private fun surface(mesh: AudienceMesh=assigned(),type: String="AUDIENCE_SEATED",cuts: List<List<PlanPoint>> = emptyList())=
        AudienceSurface(7,"Hall",type,emptyList(),importedMesh=mesh,exclusions=cuts)
    private fun plan(s: AudienceSurface=surface(),spacing: Float=1f)=CoveragePlan(listOf(s),emptyList(),28f,28f,spacing)
    private fun stats(m: PreparedAudienceGeometry,level: (SurfaceReceiver)->Float={90+it.z})=
        CoverageStatisticsCalculator.calculate(m,FloatArray(m.receiverCount) {level(m.receiver(it))},CoverageTarget(91.0,93.0))
    private fun cut(x0: Double,z0: Double,x1: Double,z1: Double)=listOf(PlanPoint(x0,z0),PlanPoint(x1,z0),PlanPoint(x1,z1),PlanPoint(x0,z1))

    @Test fun selectedFacesDefineAreaAndIndependentListeningHeights() {
        val m=plan().prepare()
        assertEquals(setOf("area:7:seated","area:7:standing"),m.surfaces().map {it.surfaceKey}.toSet())
        assertTrue(m.receivers().all {it.surfaceY==0f || it.surfaceY==3f})
        assertEquals(listOf(1.2f,4.6f),m.receivers().filter {it.x==2f && it.z==2f}.map {it.y}.sorted())
        val result=stats(m)
        assertEquals(32.0,result.overall.totalAreaM2,1e-6)
        result.surfaces().forEach {assertEquals(16.0,it.statistics.totalAreaM2,1e-6)}
        assertEquals(setOf("Hall — Seated","Hall — Standing"),result.surfaces().map {it.name}.toSet())
        m.triangles().forEach {t -> assertEquals(1,listOf(t.a,t.b,t.c).map {m.receiver(it).surfaceKey}.distinct().size)}
    }
    @Test fun areaMomentsAndPercentilesRemainStableUnderRefinement() {
        for (spacing in listOf(10f,1f,0.5f)) {
            val s=stats(plan(spacing=spacing).prepare()).overall
            assertEquals(32.0,s.totalAreaM2,1e-6); assertEquals(92.0,s.meanDb!!,1e-6)
            assertEquals(4/sqrt(12.0),s.standardDeviationDb!!,1e-6)
            assertEquals(90.4,s.p10Db!!,1e-6); assertEquals(93.6,s.p90Db!!,1e-6)
            assertEquals(50.0,s.withinTargetPercent!!,1e-6)
        }
    }
    @Test fun excludedAreaIsNotMissingAreaAndUnavailableAssignedFacesRemainInDenominator() {
        val result=stats(plan().prepare()) {if (it.zoneType=="AUDIENCE_STANDING") Float.NaN else 92f}
        assertEquals(32.0,result.overall.totalAreaM2,1e-6)
        assertEquals(16.0,result.overall.missingAreaM2,1e-6)
        assertEquals(50.0,result.overall.withinTargetPercent!!,1e-6)
        assertEquals(0.0,result.surfaces().first {it.surfaceKey.endsWith("seated")}.statistics.missingAreaM2,1e-6)
    }
    @Test fun differingAssignmentsDuplicateSharedBoundaryReceiversWithoutDoubleCountingArea() {
        val mesh=flat().withFaceAssignments(listOf(MeshFaceAssignment.SEATED,MeshFaceAssignment.STANDING))
        val s=surface(mesh); val m=plan(s).prepare()
        val center=m.receivers().filter {it.x==2f && it.z==2f}
        assertEquals(listOf(1.2f,1.6f),center.map {it.y}.sorted())
        assertEquals(0f,s.surfaceY(2f,2f),1e-6f); assertTrue(s.receiverY(2f,2f).isNaN())
        val result=stats(m) {100+it.y}
        assertEquals(16.0,result.overall.totalAreaM2,1e-6)
        assertEquals(101.4,result.overall.meanDb!!,5e-6)
        assertEquals(0.2,result.overall.standardDeviationDb!!,5e-6)
        m.triangles().forEach {t -> assertEquals(1,listOf(t.a,t.b,t.c).map {m.receiver(it).y}.distinct().size)}
    }
    @Test fun followZoneTracksParentButExplicitAssignmentsDoNot() {
        val mesh=flat().assignFaces(listOf(1),MeshFaceAssignment.SEATED)
        val m=plan(surface(mesh,"AUDIENCE_STANDING")).prepare()
        assertTrue(m.receivers().filter {it.surfaceKey=="area:7"}.all {it.y==1.6f})
        assertTrue(m.receivers().filter {it.surfaceKey=="area:7:seated"}.all {it.y==1.2f})
        val nonAudience=plan(surface(mesh,"STAGE")).prepare()
        assertEquals(8.0,stats(nonAudience).overall.totalAreaM2,1e-6)
        assertTrue(nonAudience.receivers().all {it.zoneType=="AUDIENCE_SEATED"})
    }
    @Test fun allExcludedMeshesStayEmptyAndDoNotRestoreThePreviewFloor() {
        for (type in listOf("AUDIENCE_SEATED","STAGE")) {
            val s=surface(mesh().assignFaces(faces.indices.toList(),MeshFaceAssignment.EXCLUDED),type)
            val probe=SurfaceReceiver(10f,1.2f,10f,0f,"probe:1")
            val m=CoveragePlan(listOf(s),listOf(probe),28f,28f,1f).prepare()
            assertEquals(0,m.triangleCount); assertEquals(1,m.receiverCount)
            assertEquals(0.0,stats(m).overall.totalAreaM2,0.0)
            assertEquals("area:7",m.surfaces().single().surfaceKey)
            assertTrue(s.containsOutlinePoint(2f,2f)); assertFalse(s.containsAudiencePoint(2f,2f))
            assertTrue(s.surfaceY(2f,2f).isNaN()); assertTrue(s.importedEdgeSegments().isNotEmpty())
        }
    }
    @Test fun aFaceCanBeRestoredWithoutChangingMeshTopologyOrOpeningASeam() {
        val original=mesh(); val excluded=original.assignFaces(listOf(2,3,4,5),MeshFaceAssignment.EXCLUDED)
        assertEquals(original.vertices(),excluded.vertices()); assertEquals(original.triangles(),excluded.triangles())
        assertEquals(16.0,stats(plan(surface(excluded)).prepare()).overall.totalAreaM2,1e-6)
        assertEquals(0f,surface(excluded).surfaceY(2f,2f),1e-6f)
        assertEquals(1.2f,surface(excluded).receiverY(4f,2f),1e-6f) // Excluded vertical faces cannot make lookup ambiguous.
        val restored=excluded.assignFaces(listOf(2,3,4,5),MeshFaceAssignment.FOLLOW_ZONE)
        assertEquals(original,restored)
        assertEquals(44.0,stats(plan(surface(restored)).prepare()).overall.totalAreaM2,1e-6)
    }
    @Test fun planCutsOnlySubtractFromAssignedFacesAndZeroAreaGroupsRemainIdentified() {
        val m=plan(surface(cuts=listOf(cut(-1.0,-1.0,2.0,5.0)))).prepare()
        assertEquals(16.0,stats(m).overall.totalAreaM2,1e-6)
        assertTrue(m.receivers().all {it.x>=2 && (it.surfaceY==0f || it.surfaceY==3f)})
        val empty=plan(surface(cuts=listOf(cut(-1.0,-1.0,5.0,5.0)))).prepare()
        assertEquals(0,empty.triangleCount); assertEquals(0,empty.receiverCount)
        assertEquals(2,stats(empty).surfaces().size)
        assertTrue(stats(empty).surfaces().all {it.statistics.totalAreaM2==0.0})
    }
    @Test fun assignmentSnapshotsAndCacheKeysIncludeFaceSemantics() {
        val values=MutableList(faces.size) {MeshFaceAssignment.FOLLOW_ZONE}
        val original=mesh().withFaceAssignments(values); val cache=GeometryCache()
        val before=cache.prepare(plan(surface(original)))
        values[0]=MeshFaceAssignment.EXCLUDED
        assertSame(before,cache.prepare(plan(surface(original))))
        val changed=original.withFaceAssignments(values)
        val after=cache.prepare(plan(surface(changed)))
        assertNotSame(before,after); assertSame(after,cache.prepare(plan(surface(changed))))
        assertEquals(36.0,stats(after).overall.totalAreaM2,1e-6)
        assertEquals(44.0,stats(before).overall.totalAreaM2,1e-6)
    }
    @Test fun selectedRangesAndConnectedPlanarPatchesKeepOriginalFaceNumbers() {
        val m=mesh()
        assertEquals(setOf(0,1),m.coplanarFaces(0)); assertEquals(setOf(2,3),m.coplanarFaces(2))
        assertEquals(setOf(4,5),m.coplanarFaces(4))
        val result=m.assignFaces(m.coplanarFaces(2),MeshFaceAssignment.EXCLUDED)
        assertEquals(listOf(MeshFaceAssignment.FOLLOW_ZONE,MeshFaceAssignment.FOLLOW_ZONE,MeshFaceAssignment.EXCLUDED,
            MeshFaceAssignment.EXCLUDED,MeshFaceAssignment.FOLLOW_ZONE,MeshFaceAssignment.FOLLOW_ZONE),result.faceAssignments())
        assertFailsWith<IllegalArgumentException> {m.assignFaces(listOf(-1),MeshFaceAssignment.EXCLUDED)}
        assertFailsWith<IllegalArgumentException> {m.assignFaces(listOf(6),MeshFaceAssignment.EXCLUDED)}
        assertFailsWith<IllegalArgumentException> {m.withFaceAssignments(emptyList())}
        assertFailsWith<IllegalArgumentException> {m.coplanarFaces(6)}
        assertFailsWith<IllegalStateException> {m.coplanarFaces(0) {error("cancelled")}}
    }
    @Test fun transformationsRetainOriginalAssignments() {
        val original=assigned(); val moved=original.mapCoordinates {PlanPoint(-it.x+10,it.z+2)}
        assertEquals(original.faceAssignments(),moved.faceAssignments())
        assertEquals(32.0,stats(plan(surface(moved)).prepare()).overall.totalAreaM2,1e-6)
        assertEquals(original.triangles().map {setOf(it.a,it.b,it.c)},moved.triangles().map {setOf(it.a,it.b,it.c)})
    }
    @Test fun portableProjectsRetainAssignmentsAndLegacyMeshesDefaultToFollowZone() {
        val original=assigned(); val payload=AudienceMeshJson.encode(original)
        assertEquals(original,AudienceMeshJson.decode(payload))
        val scene="""{"version":${ProjectArchive.VERSION},"audienceAreas":[{"id":7,"geometryType":"IMPORTED_MESH","audienceMesh":$payload}]}"""
        assertTrue(ProjectArchive.open(ProjectArchive.encode(scene,emptyMap(),{null},true)).problems.isEmpty())
        for (version in 1..2) {
            val old=Json.parseToJsonElement(payload).jsonObject.toMutableMap().apply {
                put("version",JsonPrimitive(version)); remove("faceAssignments")
            }
            val decoded=AudienceMeshJson.decode(JsonObject(old).toString())
            assertTrue(decoded.faceAssignments().all {it==MeshFaceAssignment.FOLLOW_ZONE})
            assertEquals(44.0,stats(plan(surface(decoded)).prepare()).overall.totalAreaM2,1e-6)
        }
    }
    @Test fun missingMalformedOrUnknownAssignmentsCannotSilentlyRestoreExcludedArea() {
        val root=Json.parseToJsonElement(AudienceMeshJson.encode(assigned())).jsonObject
        val invalid=listOf<JsonElement?>(null,JsonNull,JsonArray(emptyList()),JsonArray(List(6) {JsonPrimitive("UNKNOWN")}),
            JsonArray(List(6) {JsonPrimitive(0)}))
        for (value in invalid) {
            val bad=root.toMutableMap().apply {if (value==null) remove("faceAssignments") else put("faceAssignments",value)}
            assertFails {AudienceMeshJson.decode(JsonObject(bad).toString())}
            val scene="""{"version":${ProjectArchive.VERSION},"audienceAreas":[{"id":7,"audienceMesh":${JsonObject(bad)}}]}"""
            assertFails {ProjectArchive.validateScene(scene)}
        }
    }
    @Test fun immutablePredictionsUsePerFaceListeningOffsetsAndIgnoreExcludedFaces() {
        val source=SourceSnapshot(0,-10f,1.2f,2f,0f,ArrayGeometry.poses(1,0.2f,0f,emptyList()),0f,0f,
            FrozenResponse.generic(100f,"generic",false,false))
        val mesh=assigned(); val request=PredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),listOf(source),
            emptyList(),1000,listOf(1000),0f,emptyMap(),true,Atmosphere(),coveragePlan=plan(surface(mesh)))
        val out=request.calculate(); val points=out.geometry!!.receivers()
        val a=points.indexOfFirst {it.x==2f && it.z==2f && it.surfaceKey.endsWith("seated")}
        val b=points.indexOfFirst {it.x==2f && it.z==2f && it.surfaceKey.endsWith("standing")}
        val distance=sqrt(144+3.4*3.4)
        assertEquals((20*log10(distance/12)+Atmosphere().absorptionDbPerMetre(1000.0)*(distance-12)).toFloat(),out.levelAt(a)-out.levelAt(b),1e-4f)
        mesh.assignFaces(faces.indices.toList(),MeshFaceAssignment.EXCLUDED)
        assertEquals(points,request.calculate().geometry!!.receivers())
    }
}
