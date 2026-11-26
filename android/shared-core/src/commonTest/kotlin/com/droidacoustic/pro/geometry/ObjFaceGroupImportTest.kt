package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.statistics.*
import com.droidacoustic.pro.persistence.*
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class ObjFaceGroupImportTest {
    private val vertices="""
        v 0 0 0
        v 4 0 0
        v 4 0 4
        v 0 0 4
        v 0 3 0
        v 4 3 0
        v 4 3 4
        v 0 3 4
    """.trimIndent()
    private val faces=listOf("f 1 2 3","f 1 3 4","f 2 6 7","f 2 7 3","f 5 8 7","f 5 7 6")
    private fun obj(names: List<String?>)=vertices+"\n"+faces.mapIndexed {i,f -> listOfNotNull(names[i],f).joinToString("\n")}.joinToString("\n")
    private fun decks()=obj(listOf("g Floor",null,"g Wall",null,"g Balcony",null))
    private fun parse(text: String=decks())=AudienceMeshObj.parse(text)
    private fun geometry(mesh: AudienceMesh,spacing: Float=1f)=CoveragePlan(
        listOf(AudienceSurface(7,"Hall","AUDIENCE_SEATED",emptyList(),importedMesh=mesh)),emptyList(),28f,28f,spacing).prepare()
    private fun stats(mesh: PreparedAudienceGeometry,level: (SurfaceReceiver)->Float={90+it.z})=
        CoverageStatisticsCalculator.calculate(mesh,FloatArray(mesh.receiverCount) {level(mesh.receiver(it))},CoverageTarget(91.0,93.0))

    @Test fun singleNamedGroupsMapOriginalFaceIndicesAndInitiallyFollowZone() {
        val m=parse()
        assertEquals(listOf("Floor","Wall","Balcony"),m.faceGroups().map {it.name})
        assertEquals(listOf(setOf(0,1),setOf(2,3),setOf(4,5)),m.faceGroups().map {it.faces()})
        assertEquals(listOf(1,2,3),m.faceGroups().map {it.id})
        assertTrue(m.faceAssignments().all {it==MeshFaceAssignment.FOLLOW_ZONE})
        assertEquals(parse(obj(List(6) {null})).triangles(),m.triangles())
        assertEquals(m,parse())
    }
    @Test fun repeatedDeclarationsAccumulateAcrossObjectMaterialAndSmoothingChanges() {
        val text=obj(listOf("g Seats","o second\nusemtl ignored\ns 3", "g Wall", "g Seats", "g Wall", "g Seats"))
        val m=parse("mtllib never-read.mtl\n"+text)
        assertEquals(listOf("Seats","Wall"),m.faceGroups().map {it.name})
        assertEquals(setOf(0,1,3,5),m.faceGroup(1).faces()); assertEquals(setOf(2,4),m.faceGroup(2).faces())
    }
    @Test fun absentAndBareGroupsStayUngroupedWhileExplicitDefaultIsPreserved() {
        assertTrue(parse(obj(List(6) {null})).faceGroups().isEmpty())
        val m=parse(obj(listOf(null,"g Seats", "g",null,"g default",null)))
        assertEquals(listOf("Seats","default"),m.faceGroups().map {it.name})
        assertEquals(setOf(1),m.faceGroup(1).faces()); assertEquals(setOf(4,5),m.faceGroup(2).faces())
        assertNull(m.groupForFace(0)); assertNull(m.groupForFace(2)); assertNull(m.groupForFace(3))
    }
    @Test fun multipleMembershipsFormCanonicalDisjointPartitionsWithoutDuplicatingTriangles() {
        val m=parse(obj(listOf("g A", "g B A A", "g A B", "g B", "g B A", "g")))
        assertEquals(listOf("A","A + B","B"),m.faceGroups().map {it.name})
        assertEquals(listOf(setOf(0),setOf(1,2,4),setOf(3)),m.faceGroups().map {it.faces()})
        assertEquals(6,m.triangles().size); assertNull(m.groupForFace(5))
        assertEquals(44.0,stats(geometry(m)).overall.totalAreaM2,1e-6)
        assertEquals(setOf(1,2,4),ImportedMeshPicking.select(m,emptySet(),2,FacePickMode.GROUP))
    }
    @Test fun caseDistinctSourceNamesStaySeparateWithDeterministicUniqueDisplayLabels() {
        val m=parse(obj(listOf("g Seats", "g seats", "g Seats", "g SEATS", "g seats", "g SEATS")))
        assertEquals(listOf("Seats","seats (2)","SEATS (3)"),m.faceGroups().map {it.name})
        assertEquals(listOf(setOf(0,2),setOf(1,4),setOf(3,5)),m.faceGroups().map {it.faces()})
        assertEquals(m,parse(obj(listOf("g Seats", "g seats", "g Seats", "g SEATS", "g seats", "g SEATS"))))
    }
    @Test fun unusedDeclarationsDoNotCreateEmptyGroupsOrConsumeIds() {
        val m=parse(obj(listOf("g Unused\ng\ng Last-unused\ng Floor", null,"g Wall",null,"g Balcony",null))+"\ng Trailing-unused")
        assertEquals(listOf("Floor","Wall","Balcony"),m.faceGroups().map {it.name})
        assertEquals(listOf(1,2,3),m.faceGroups().map {it.id})
        assertEquals(m,parse())
    }
    @Test fun whitespaceCommentsAndRelativeReferencesDoNotShiftGroupMembership() {
        val source=decks().replace("g Floor"," \tg\tFloor  # comment").replace("g Wall","g\tWall\t")
            .replace("f 1 2 3","f -8/1/1 -7/2/1 -6/3/1").replace("f 1 3 4","f 1//1 3//1 4//1")
        assertEquals(parse(),parse(source.replace("\n","\r\n")))
    }
    @Test fun unitsAxesAndWindingNormalizationPreserveGroupFaceNumbers() {
        val original=parse()
        val cm=AudienceMeshObj.parse(decks(),AudienceMeshImportOptions(0.01))
        assertEquals(original.faceGroups(),cm.faceGroups())
        assertEquals(0.0044,stats(geometry(cm)).overall.totalAreaM2,1e-9)
        val zUp=decks().lineSequence().joinToString("\n") {line ->
            if (!line.startsWith("v ")) line else {
                val p=line.split(' '); "v ${p[1]} ${-p[3].toDouble()} ${p[2]}"
            }
        }
        assertEquals(original,AudienceMeshObj.parse(zUp,AudienceMeshImportOptions(upAxis=AudienceMeshUpAxis.Z)))
        val reversed=parse(decks().replace("f 2 6 7","f 2 7 6"))
        assertEquals(original.faceGroups(),reversed.faceGroups())
        assertEquals(44.0,stats(geometry(reversed)).overall.totalAreaM2,1e-6)
    }
    @Test fun importedNamesNeverInferAssignmentsAndGroupEditsKeepMeshTopology() {
        val m=parse(obj(listOf("g EXCLUDED",null,"g STANDING",null,"g SEATED",null)))
        assertTrue(m.faceAssignments().all {it==MeshFaceAssignment.FOLLOW_ZONE})
        val assigned=m.assignFaceGroup(1,MeshFaceAssignment.SEATED).assignFaceGroup(2,MeshFaceAssignment.EXCLUDED)
            .assignFaceGroup(3,MeshFaceAssignment.STANDING)
        assertEquals(m.faceGroups(),assigned.faceGroups()); assertEquals(m.triangles(),assigned.triangles())
        assertEquals(32.0,stats(geometry(assigned)).overall.totalAreaM2,1e-6)
        assertEquals(listOf(1.2f,4.6f),geometry(assigned).receivers().filter {it.x==2f && it.z==2f}.map {it.y}.sorted())
    }
    @Test fun importedGroupAreaAndLinearFieldStatisticsRemainStableUnderRefinement() {
        val m=parse().assignFaceGroup(2,MeshFaceAssignment.EXCLUDED)
        for (spacing in listOf(10f,1f,0.5f)) {
            val s=stats(geometry(m,spacing))
            assertEquals(listOf(16.0,0.0,16.0),s.surfaces().map {it.statistics.totalAreaM2})
            assertEquals(32.0,s.overall.totalAreaM2,1e-6); assertEquals(92.0,s.overall.meanDb!!,1e-6)
            assertEquals(4/sqrt(12.0),s.overall.standardDeviationDb!!,1e-6)
            assertEquals(90.4,s.overall.p10Db!!,1e-6); assertEquals(93.6,s.overall.p90Db!!,1e-6)
            assertEquals(50.0,s.overall.withinTargetPercent!!,1e-6)
        }
    }
    @Test fun compositeGroupsAndVerticalAreasAreWeightedOnceIncludingMissingPredictions() {
        val m=parse(obj(listOf("g Floor",null,"g Wall Left",null,"g Balcony",null)))
        val g=geometry(m)
        val s=stats(g) {when(it.surfaceKey) {"area:7:group:1" -> 90f; "area:7:group:2" -> 120f; else -> 110f}}
        assertEquals(listOf(16.0,12.0,16.0),s.surfaces().map {it.statistics.totalAreaM2})
        assertEquals((16*90.0+12*120+16*110)/44,s.overall.meanDb!!,1e-6)
        val missing=stats(g) {if (it.surfaceKey=="area:7:group:2") Float.NaN else 92f}
        assertEquals(12.0,missing.overall.missingAreaM2,1e-6)
        assertEquals(100*32.0/44,missing.overall.withinTargetPercent!!,1e-6)
    }
    @Test fun mappedGroupsAssignmentsAndSubsequentRenamesSurvivePortableReopen() {
        val m=parse(obj(listOf("g Left Floor",null,"g Wall",null,"g Balcony",null)))
            .assignFaceGroup(2,MeshFaceAssignment.EXCLUDED).renameFaceGroup(1,"Front audience")
        val scene="""{"version":${ProjectArchive.VERSION},"audienceAreas":[{"id":7,"geometryType":"IMPORTED_MESH","audienceMesh":${AudienceMeshJson.encode(m)}}]}"""
        val opened=ProjectArchive.open(ProjectArchive.encode(scene,emptyMap(),{null},true))
        assertTrue(opened.problems.isEmpty())
        assertEquals(m,AudienceMeshJson.decode(AudienceMeshJson.encode(m)))
        assertEquals(32.0,stats(geometry(m)).overall.totalAreaM2,1e-6)
    }
    @Test fun overlongInvalidAndExcessGroupStateFailsWithLineInformation() {
        for (declaration in listOf("g "+"X".repeat(81),"g "+"A".repeat(40)+" "+"B".repeat(40),
                "g Bad\u0000name", "g "+(1..129).joinToString(" ") {"G$it"})) {
            val failure=assertFailsWith<IllegalArgumentException> {parse(obj(listOf(declaration,null,null,null,null,null)))}
            assertTrue(failure.message!!.startsWith("OBJ line "))
        }
        val collision=assertFailsWith<IllegalArgumentException> {
            parse(obj(listOf("g "+"A".repeat(80),"g "+"a".repeat(80),null,null,null,null)))
        }
        assertTrue(collision.message!!.contains("unique suffix"))
        fun fan(groups: Int): String {
            val n=129
            return "v 0 0 0\n"+(0 until n).joinToString("\n") {"v ${cos(2*PI*it/n)} 0 ${sin(2*PI*it/n)}"}+"\n"+
                (0 until n).joinToString("\n") {"g G${it%groups}\nf 1 ${it+2} ${(it+1)%n+2}"}
        }
        assertEquals(128,parse(fan(128)).faceGroups().size)
        assertTrue(assertFailsWith<IllegalArgumentException> {parse(fan(129))}.message!!.contains("face-group combinations"))
    }
    @Test fun cancellationDuringGroupStateMappingPropagatesWithoutPartialOutput() {
        val grouping=ObjFaceGroups()
        assertFailsWith<IllegalStateException> {grouping.setNames(listOf("Floor"),1) {error("cancel")}}
        grouping.setNames(listOf("Floor"),1) {}
        assertFailsWith<IllegalStateException> {grouping.addFace(0,2) {error("cancel")}}
        var calls=0
        assertFailsWith<IllegalStateException> {AudienceMeshObj.parse(decks()) {if (++calls==12) error("cancel")}}
    }
}
