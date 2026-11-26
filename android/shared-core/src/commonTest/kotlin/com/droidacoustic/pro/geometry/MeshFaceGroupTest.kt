package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.statistics.*
import com.droidacoustic.pro.persistence.*
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class MeshFaceGroupTest {
    private val nodes=listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(4.0,0f,0.0),
        AudienceMeshVertex(4.0,0f,4.0),AudienceMeshVertex(0.0,0f,4.0),
        AudienceMeshVertex(0.0,3f,0.0),AudienceMeshVertex(4.0,3f,0.0),
        AudienceMeshVertex(4.0,3f,4.0),AudienceMeshVertex(0.0,3f,4.0))
    private val faces=listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3),SurfaceTriangle(1,5,6),
        SurfaceTriangle(1,6,2),SurfaceTriangle(4,7,6),SurfaceTriangle(4,6,5))
    private fun mesh()=AudienceMesh.create(nodes,faces)
    private fun decks()=mesh().createFaceGroup("Main floor",listOf(0,1)).createFaceGroup("Wall",listOf(2,3))
        .createFaceGroup("Balcony",listOf(4,5)).assignFaceGroup(1,MeshFaceAssignment.SEATED)
        .assignFaceGroup(2,MeshFaceAssignment.EXCLUDED).assignFaceGroup(3,MeshFaceAssignment.STANDING)
    private fun surface(m: AudienceMesh)=AudienceSurface(7,"Hall","AUDIENCE_SEATED",emptyList(),importedMesh=m)
    private fun plan(m: AudienceMesh,spacing: Float=1f)=CoveragePlan(listOf(surface(m)),emptyList(),28f,28f,spacing)
    private fun stats(m: PreparedAudienceGeometry,levels: (SurfaceReceiver)->Float={90+it.z})=
        CoverageStatisticsCalculator.calculate(m,FloatArray(m.receiverCount) {levels(m.receiver(it))},CoverageTarget(91.0,93.0))
    @Test fun namedGroupsAreImmutableAndRenamingPreservesStableIdentity() {
        val members=mutableListOf(1,0); val groups=mutableListOf(MeshFaceGroup(9,"  Front stalls  ",members))
        val m=mesh().withFaceGroups(groups); members.clear(); groups.clear()
        assertEquals(setOf(0,1),m.faceGroup(9).faces()); assertEquals("Front stalls",m.faceGroup(9).name)
        val renamed=m.renameFaceGroup(9,"Rear stalls")
        assertEquals("Front stalls",m.faceGroup(9).name)
        assertEquals(m.faceIdentity(0,7,"Hall").surfaceKey,renamed.faceIdentity(0,7,"Hall").surfaceKey)
        assertEquals("Hall — Rear stalls",renamed.faceIdentity(0,7,"Hall").name)
        assertSame(renamed,renamed.renameFaceGroup(9,"Rear stalls"))
        assertEquals(m,m.withFaceGroups(listOf(MeshFaceGroup(9,"Front stalls",listOf(0,1)))))
    }
    @Test fun groupTapRecallsMembersWhileUngroupedFacesRemainIndividuallySelectable() {
        val m=mesh().createFaceGroup("Decks",listOf(0,1,4,5))
        assertEquals(setOf(0,1,4,5),ImportedMeshPicking.select(m,setOf(2),5,FacePickMode.GROUP))
        assertEquals(setOf(2),ImportedMeshPicking.select(m,setOf(0,1),2,FacePickMode.GROUP))
        val excluded=m.assignFaceGroup(1,MeshFaceAssignment.EXCLUDED)
        assertEquals(m.faceGroup(1),excluded.faceGroup(1))
        assertEquals(setOf(0,1,4,5),ImportedMeshPicking.select(excluded,emptySet(),0,FacePickMode.GROUP))
    }
    @Test fun membershipMovesRatherThanOverlapsAndDeletionDoesNotAlterAssignments() {
        val m=decks(); val merged=m.addToFaceGroup(1,listOf(4,5))
        assertEquals(setOf(0,1,4,5),merged.faceGroup(1).faces()); assertTrue(merged.faceGroup(3).faces().isEmpty())
        assertEquals(m.faceAssignments(),merged.faceAssignments())
        val moved=merged.setFaceGroupMembers(3,listOf(1,4))
        assertEquals(setOf(0,5),moved.faceGroup(1).faces()); assertEquals(setOf(1,4),moved.faceGroup(3).faces())
        val ungrouped=moved.ungroupFaces(listOf(4)); assertNull(ungrouped.groupForFace(4))
        val deleted=ungrouped.removeFaceGroup(1)
        assertNull(deleted.groupForFace(0)); assertNull(deleted.groupForFace(5))
        assertEquals(m.faceAssignments(),deleted.faceAssignments()); assertEquals(m.triangles(),deleted.triangles())
        assertEquals(setOf(0,1),m.faceGroup(1).faces())
    }
    @Test fun createFromExistingGroupsMovesFacesAndAssigningAGroupIsOneImmutableChange() {
        val m=decks(); val regrouped=m.createFaceGroup("Right side",listOf(1,5))
        assertEquals(4,regrouped.groupForFace(1)!!.id); assertEquals(setOf(0),regrouped.faceGroup(1).faces())
        val assigned=regrouped.assignFaceGroup(4,MeshFaceAssignment.SEATED)
        assertEquals(MeshFaceAssignment.SEATED,assigned.faceAssignment(5))
        assertEquals(MeshFaceAssignment.STANDING,assigned.faceAssignment(4))
        assertEquals(regrouped.faceGroups(),assigned.faceGroups())
        assertEquals(MeshFaceAssignment.STANDING,regrouped.faceAssignment(5))
    }
    @Test fun invalidNamesIdsMembershipAndOverlapsFailWithoutChangingTheMesh() {
        val m=decks()
        for (name in listOf("", "  ","a".repeat(81),"Bad\nname","Bad\u007fname"))
            assertFailsWith<IllegalArgumentException> {m.renameFaceGroup(1,name)}
        assertFails {m.renameFaceGroup(1," balcony ")}
        assertFails {m.createFaceGroup("Wall",listOf(0))}
        assertFails {m.setFaceGroupMembers(1,listOf(-1))}; assertFails {m.setFaceGroupMembers(1,listOf(6))}
        assertFails {m.setFaceGroupMembers(1,listOf(0,0))}
        assertFails {m.removeFaceGroup(99)}; assertFails {m.assignFaceGroup(99,MeshFaceAssignment.SEATED)}
        assertFails {m.withFaceGroups(listOf(MeshFaceGroup(0,"Bad",emptyList())))}
        assertFails {m.withFaceGroups(listOf(MeshFaceGroup(1,"A",listOf(0)),MeshFaceGroup(2,"B",listOf(0))))}
        assertFails {m.withFaceGroups(listOf(MeshFaceGroup(1,"A",listOf(0)),MeshFaceGroup(1,"B",listOf(1))))}
        assertFails {m.withFaceGroups((1..129).map {MeshFaceGroup(it,"G $it",emptyList())})}
        assertEquals(3,m.faceGroups().size); assertEquals(32.0,stats(plan(m).prepare()).overall.totalAreaM2,1e-6)
    }
    @Test fun groupStatisticsUseActualAreaAndRemainStableUnderRefinement() {
        for (spacing in listOf(10f,1f,0.5f)) {
            val geometry=plan(decks(),spacing).prepare(); val result=stats(geometry)
            assertEquals(32.0,result.overall.totalAreaM2,1e-6)
            assertEquals(92.0,result.overall.meanDb!!,1e-6); assertEquals(4/sqrt(12.0),result.overall.standardDeviationDb!!,1e-6)
            assertEquals(90.4,result.overall.p10Db!!,1e-6); assertEquals(93.6,result.overall.p90Db!!,1e-6)
            assertEquals(50.0,result.overall.withinTargetPercent!!,1e-6)
            assertEquals(setOf("Hall — Main floor","Hall — Wall","Hall — Balcony"),result.surfaces().map {it.name}.toSet())
            assertEquals(listOf(16.0,0.0,16.0),result.surfaces().map {it.statistics.totalAreaM2})
            assertEquals(result.overall.totalAreaM2,result.surfaces().sumOf {it.statistics.totalAreaM2},1e-6)
            geometry.triangles().forEach {t -> assertEquals(1,listOf(t.a,t.b,t.c).map {geometry.receiver(it).surfaceKey}.distinct().size)}
        }
    }
    @Test fun mixedAssignmentsShareAGroupSummaryButKeepIndependentListeningHeights() {
        val m=AudienceMesh.create(nodes.take(4),faces.take(2)).withFaceAssignments(listOf(MeshFaceAssignment.SEATED,MeshFaceAssignment.STANDING))
            .createFaceGroup("Mixed seating",listOf(0,1))
        val geometry=plan(m).prepare(); val center=geometry.receivers().filter {it.x==2f && it.z==2f}
        assertEquals(listOf(1.2f,1.6f),center.map {it.y}.sorted()); assertEquals(1,center.map {it.surfaceKey}.distinct().size)
        val result=stats(geometry) {100+it.y}
        assertEquals(1,result.surfaces().size); assertEquals(16.0,result.overall.totalAreaM2,1e-6)
        assertEquals(101.4,result.overall.meanDb!!,5e-6); assertEquals(0.2,result.overall.standardDeviationDb!!,5e-6)
        val missing=stats(geometry) {if (it.zoneType=="AUDIENCE_STANDING") Float.NaN else 92f}
        assertEquals(8.0,missing.surfaces().single().statistics.missingAreaM2,1e-6)
        assertEquals(50.0,missing.overall.withinTargetPercent!!,1e-6)
    }
    @Test fun adjacentGroupsDoNotShareReceiversOrContaminateEachOthersStatistics() {
        val m=AudienceMesh.create(nodes.take(4),faces.take(2)).createFaceGroup("A",listOf(0)).createFaceGroup("B",listOf(1))
        val g=plan(m).prepare(); val center=g.receivers().filter {it.x==2f && it.z==2f}
        assertEquals(2,center.size); assertEquals(2,center.map {it.surfaceKey}.distinct().size)
        val s=stats(g) {if (it.surfaceKey.endsWith(":1")) 90f else 100f}
        assertEquals(listOf(90.0,100.0),s.surfaces().map {it.statistics.meanDb})
        assertEquals(95.0,s.overall.meanDb!!,1e-6); assertEquals(5.0,s.overall.standardDeviationDb!!,1e-6)
    }
    @Test fun ungroupedAreaAndEmptyOrFullyExcludedGroupsStayAccountedForExactlyOnce() {
        val m=mesh().createFaceGroup("One triangle",listOf(0)).createFaceGroup("Empty",emptyList())
        val s=stats(plan(m).prepare())
        assertEquals(44.0,s.overall.totalAreaM2,1e-6)
        assertEquals(listOf(36.0,8.0,0.0),s.surfaces().map {it.statistics.totalAreaM2})
        val excluded=m.assignFaces(faces.indices.toList(),MeshFaceAssignment.EXCLUDED)
        val empty=plan(excluded).prepare()
        assertEquals(0,empty.receiverCount); assertEquals(0,empty.triangleCount)
        assertEquals(2,stats(empty).surfaces().size); assertEquals(0.0,stats(empty).overall.totalAreaM2,0.0)
    }
    @Test fun planCutoutsSubtractFromGroupsAndRetainZeroAreaNames() {
        val cut=listOf(PlanPoint(-1.0,-1.0),PlanPoint(2.0,-1.0),PlanPoint(2.0,5.0),PlanPoint(-1.0,5.0))
        val s=AudienceSurface(7,"Hall","AUDIENCE_SEATED",emptyList(),importedMesh=decks(),exclusions=listOf(cut))
        val g=CoveragePlan(listOf(s),emptyList(),28f,28f,1f).prepare()
        assertEquals(listOf(8.0,0.0,8.0),stats(g).surfaces().map {it.statistics.totalAreaM2})
    }
    @Test fun groupEditsInvalidateCacheAndCapturedPlansKeepOriginalNamesAndMembership() {
        val original=decks(); val frozen=plan(original); val cache=GeometryCache(); val before=cache.prepare(frozen)
        val renamed=original.renameFaceGroup(1,"Stalls")
        val after=cache.prepare(plan(renamed)); assertNotSame(before,after); assertSame(after,cache.prepare(plan(renamed)))
        assertEquals("Hall — Stalls",after.surfaces().first().name)
        assertEquals("Hall — Main floor",frozen.prepare().surfaces().first().name)
        val regrouped=cache.prepare(plan(renamed.addToFaceGroup(1,listOf(4,5))))
        assertEquals(listOf(32.0,0.0,0.0),stats(regrouped).surfaces().map {it.statistics.totalAreaM2})
        assertEquals(32.0,stats(before).overall.totalAreaM2,1e-6)
    }
    @Test fun transformsPreserveMembershipAndGroupAreaWithoutChangingOriginalIndices() {
        val m=decks(); val moved=m.mapCoordinates {PlanPoint(10-it.x,it.z+20)}
        assertEquals(m.faceGroups(),moved.faceGroups()); assertEquals(m.faceAssignments(),moved.faceAssignments())
        assertEquals(listOf(16.0,0.0,16.0),stats(plan(moved).prepare()).surfaces().map {it.statistics.totalAreaM2})
    }
    @Test fun meshAndPortableProjectRoundTripsPreserveGroupsWithoutSourceAssets() {
        val m=decks(); val payload=AudienceMeshJson.encode(m)
        assertEquals(m,AudienceMeshJson.decode(payload)); assertTrue(payload.contains("\"version\":10"))
        val scene="""{"version":${ProjectArchive.VERSION},"audienceAreas":[{"id":7,"geometryType":"IMPORTED_MESH","audienceMesh":$payload}]}"""
        val opened=ProjectArchive.open(ProjectArchive.encode(scene,emptyMap(),{null},true))
        assertTrue(opened.problems.isEmpty())
        ProjectArchive.validateScene(scene)
        for (version in 1..3) {
            val old=Json.parseToJsonElement(payload).jsonObject.toMutableMap().apply {
                put("version",JsonPrimitive(version)); remove("faceGroups")
                if (version<3) remove("faceAssignments")
            }
            val decoded=AudienceMeshJson.decode(JsonObject(old).toString()); assertTrue(decoded.faceGroups().isEmpty())
            assertEquals(if (version<3) 44.0 else 32.0,stats(plan(decoded).prepare()).overall.totalAreaM2,1e-6)
        }
    }
    @Test fun malformedGroupPayloadsFailBeforeAProjectCanLoseOrMergeMembership() {
        val root=Json.parseToJsonElement(AudienceMeshJson.encode(decks())).jsonObject
        val invalid=listOf<JsonElement?>(null,JsonNull,JsonPrimitive("bad"),
            Json.parseToJsonElement("""[{"id":1,"name":"A","faces":[0]},{"id":2,"name":"B","faces":[0]}]"""),
            Json.parseToJsonElement("""[{"id":1,"name":"A","faces":[6]}]"""),
            Json.parseToJsonElement("""[{"id":1,"name":"A","faces":[0,0]}]"""),
            Json.parseToJsonElement("""[{"id":1,"name":8,"faces":[0]}]"""),
            Json.parseToJsonElement("""[{"id":1,"name":"A","faces":["0"]}]"""),
            Json.parseToJsonElement("""[{"id":1.5,"name":"A","faces":[0]}]"""))
        for (bad in invalid) {
            val value=root.toMutableMap().apply {if (bad==null) remove("faceGroups") else put("faceGroups",bad)}
            assertFails {AudienceMeshJson.decode(JsonObject(value).toString())}
            val scene="""{"version":${ProjectArchive.VERSION},"audienceAreas":[{"id":7,"audienceMesh":${JsonObject(value)}}]}"""
            assertFails {ProjectArchive.validateScene(scene)}
        }
    }
}
