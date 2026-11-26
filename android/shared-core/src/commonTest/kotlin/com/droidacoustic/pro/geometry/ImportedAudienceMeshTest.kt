package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.statistics.*
import com.droidacoustic.pro.persistence.*
import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class ImportedAudienceMeshTest {
    private val nodes=listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(4.0,0f,0.0),
        AudienceMeshVertex(4.0,4f,4.0),AudienceMeshVertex(0.0,0f,4.0))
    private val faces=listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3))
    private val obj="""
        # raised corner with a prescribed ridge diagonal
        v 0 0 0
        v 4 0 0
        v 4 4 4
        v 0 0 4
        f 1 2 3
        f 1 3 4
    """.trimIndent()
    private fun mesh()=AudienceMesh.create(nodes,faces)
    private fun surface(mesh: AudienceMesh=mesh(),exclusions: List<List<PlanPoint>> = emptyList(),id: Int=0,base: Float=0f)=
        AudienceSurface(id,"Imported","AUDIENCE_SEATED",emptyList(),baseHeightM=base,importedMesh=mesh,exclusions=exclusions)
    private fun plan(s: AudienceSurface=surface(),spacing: Float=10f)=CoveragePlan(listOf(s),emptyList(),28f,28f,spacing)
    private fun stats(m: PreparedAudienceGeometry,level: (SurfaceReceiver)->Float={90+it.x}) =
        CoverageStatisticsCalculator.calculate(m,FloatArray(m.receiverCount) { level(m.receiver(it)) },CoverageTarget(91.0,93.0)).overall
    private fun ringMesh(): AudienceMesh {
        val p=nodes+listOf(AudienceMeshVertex(1.0,1f,1.0),AudienceMeshVertex(3.0,1f,1.0),
            AudienceMeshVertex(3.0,3f,3.0),AudienceMeshVertex(1.0,1f,3.0))
        val t=(0..3).flatMap { i -> val j=(i+1)%4; listOf(SurfaceTriangle(i,j,j+4),SurfaceTriangle(i,j+4,i+4)) }
        return AudienceMesh.create(p,t)
    }
    @Test fun importedTopologyRetainsDiagonalAndHeightsAtCoarseAndFineSpacing() {
        for (spacing in listOf(10f,1f,0.4f)) {
            val s=surface(); val m=plan(s,spacing).prepare()
            assertEquals(2f,s.surfaceY(2f,2f),1e-5f)
            m.receivers().forEach { assertEquals(min(it.x,it.z),it.surfaceY,1e-5f); assertEquals(it.surfaceY+1.2f,it.y,1e-5f) }
            m.triangles().forEach { t -> val p=listOf(t.a,t.b,t.c).map { m.receiver(it) }
                assertFalse(p.any { it.x-it.z>1e-5 } && p.any { it.x-it.z< -1e-5 }) }
            val st=stats(m)
            assertEquals(16*sqrt(2.0),st.totalAreaM2,1e-5)
            assertEquals(92.0,st.meanDb!!,2e-6); assertEquals(4/sqrt(12.0),st.standardDeviationDb!!,2e-6)
            assertEquals(90.4,st.p10Db!!,2e-6); assertEquals(93.6,st.p90Db!!,2e-6)
            assertEquals(50.0,st.withinTargetPercent!!,2e-5)
        }
        assertEquals(2,plan().prepare().triangleCount)
    }
    @Test fun unequalSlopeAreasWeightStatisticsAndMissingFacetsCorrectly() {
        val p=listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(2.0,0f,0.0),AudienceMeshVertex(4.0,2f,0.0),
            AudienceMeshVertex(4.0,2f,4.0),AudienceMeshVertex(2.0,0f,4.0),AudienceMeshVertex(0.0,0f,4.0))
        val t=listOf(SurfaceTriangle(0,1,4),SurfaceTriangle(0,4,5),SurfaceTriangle(1,2,3),SurfaceTriangle(1,3,4))
        val m=plan(surface(AudienceMesh.create(p,t))).prepare()
        val st=stats(m)
        assertEquals(8+8*sqrt(2.0),st.totalAreaM2,1e-5)
        assertEquals((91+93*sqrt(2.0))/(1+sqrt(2.0)),st.meanDb!!,1e-5)
        val missing=stats(m) { if (it.x>2) Float.NaN else 92f }
        assertEquals(8*sqrt(2.0),missing.missingAreaM2,1e-5)
        assertEquals(100/(1+sqrt(2.0)),missing.withinTargetPercent!!,1e-5)
    }
    @Test fun intrinsicHolesRemainEmptyInCoverageMembershipContoursAndEdges() {
        val s=surface(ringMesh()); val m=plan(s).prepare()
        assertEquals(1,s.importedMesh!!.voids().size)
        assertFalse(s.containsAudiencePoint(2f,2f)); assertTrue(s.surfaceY(2f,2f).isNaN())
        assertEquals(12*sqrt(2.0),stats(m).totalAreaM2,1e-5); assertEquals(92.0,stats(m).meanDb!!,1e-5)
        m.triangles().forEach { t -> val p=listOf(t.a,t.b,t.c).map { m.receiver(it) }
            val x=p.map { it.x }.average(); val z=p.map { it.z }.average()
            assertFalse(x>1 && x<3 && z>1 && z<3) }
        val contours=SurfaceContours.build(m,FloatArray(m.receiverCount) {90+m.receiver(it).x},listOf(92f))
        assertTrue(contours.isNotEmpty())
        contours.forEach { assertTrue(it.z0<=1 || it.z0>=3); assertTrue(it.z1<=1 || it.z1>=3) }
        s.importedEdgeSegments().forEach { val x=(it.x0+it.x1)/2; val z=(it.z0+it.z1)/2
            assertFalse(x>1 && x<3 && z>1 && z<3) }
    }
    @Test fun additionalExclusionsUnionWithIntrinsicHolesAndKeepPhysicalArea() {
        val cut=listOf(PlanPoint(-1.0,-1.0),PlanPoint(2.0,-1.0),PlanPoint(2.0,5.0),PlanPoint(-1.0,5.0))
        val s=surface(ringMesh(),listOf(cut,cut)); val m=plan(s,0.5f).prepare()
        assertEquals(6*sqrt(2.0),stats(m).totalAreaM2,1e-5)
        assertEquals(90+19.0/6,stats(m).meanDb!!,1e-5)
        assertTrue(m.receivers().all { it.x>=2 })
        assertTrue(s.importedEdgeSegments().all { it.x0>=2 && it.x1>=2 })
    }
    @Test fun concaveImportedBoundaryDoesNotBecomeItsConvexHull() {
        val p=listOf(0.0 to 0.0,4.0 to 0.0,4.0 to 1.0,1.0 to 1.0,1.0 to 4.0,0.0 to 4.0)
        val vertices=p.map { AudienceMeshVertex(it.first,0f,it.second) }
        val t=listOf(SurfaceTriangle(0,1,3),SurfaceTriangle(1,2,3),SurfaceTriangle(0,3,5),SurfaceTriangle(3,4,5))
        val s=surface(AudienceMesh.create(vertices,t)); val m=plan(s).prepare()
        assertEquals(7.0,stats(m).totalAreaM2,1e-5)
        assertFalse(s.containsAudiencePoint(2f,2f)); assertTrue(s.surfaceY(2f,2f).isNaN())
    }
    @Test fun mixedWindingNormalizesWithoutReplacingTriangles() {
        val m=AudienceMesh.create(nodes,listOf(faces[0],SurfaceTriangle(0,3,2)))
        assertEquals(faces,m.triangles()); assertEquals(mesh(),m)
        assertEquals(16*sqrt(2.0),stats(plan(surface(m)).prepare()).totalAreaM2,1e-5)
    }
    @Test fun invalidIndicesCoordinatesDuplicateAndDegenerateFacesFail() {
        for (bad in listOf(SurfaceTriangle(-1,1,2),SurfaceTriangle(0,1,99),SurfaceTriangle(0,0,1)))
            assertFailsWith<IllegalArgumentException> { AudienceMesh.create(nodes,listOf(bad)) }
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(nodes,faces+SurfaceTriangle(2,1,0)) }
        for (p in listOf(nodes[0].copy(x=Double.NaN),nodes[0].copy(y=Float.POSITIVE_INFINITY),nodes[0].copy(y=10001f)))
            assertFailsWith<IllegalArgumentException> { AudienceMesh.create(listOf(p)+nodes.drop(1),faces) }
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(nodes+nodes[0],faces+SurfaceTriangle(0,1,4)) }
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(listOf(nodes[0],nodes[1],AudienceMeshVertex(2.0,0f,0.0)),listOf(SurfaceTriangle(0,1,2))) }
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(nodes+AudienceMeshVertex(6.0,0f,6.0),faces) }
    }
    @Test fun disconnectedNonManifoldAndNonconformingMeshesFail() {
        val extra=listOf(AudienceMeshVertex(6.0,0f,0.0),AudienceMeshVertex(8.0,0f,0.0),AudienceMeshVertex(6.0,0f,2.0))
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(nodes+extra,faces+SurfaceTriangle(4,5,6)) }
        val p=listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(4.0,0f,0.0),AudienceMeshVertex(2.0,0f,2.0),
            AudienceMeshVertex(2.0,0f,-2.0),AudienceMeshVertex(1.0,0f,3.0))
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(p,listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(1,0,3),SurfaceTriangle(0,1,4))) }
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(nodes+AudienceMeshVertex(2.0,2f,2.0),
            listOf(faces[0],SurfaceTriangle(0,4,3),SurfaceTriangle(4,2,3))) }
    }
    @Test fun projectedOverlapIsAllowedButCoplanarOverlapFails() {
        val p=listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(4.0,0f,0.0),AudienceMeshVertex(4.0,0f,4.0),
            AudienceMeshVertex(0.0,0f,4.0),AudienceMeshVertex(3.0,2f,1.0))
        assertEquals(3,AudienceMesh.create(p,faces+SurfaceTriangle(0,3,4)).triangles().size)
        // A second face lies on the same side of their common edge.
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(p.take(3)+p.last().copy(y=0f),listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,1,3))) }
    }
    @Test fun immutableMeshAndTopologyArePartOfCacheIdentity() {
        val p=nodes.toMutableList(); val t=faces.toMutableList(); val mesh=AudienceMesh.create(p,t)
        val cache=GeometryCache(); val original=cache.prepare(plan(surface(mesh)))
        p[2]=p[2].copy(y=8f); t.clear()
        assertEquals(4f,mesh.vertices()[2].y); assertEquals(faces,mesh.triangles())
        assertSame(original,cache.prepare(plan(surface(AudienceMesh.create(nodes,faces)))))
        assertNotSame(original,cache.prepare(plan(surface(AudienceMesh.create(p,faces)))))
        assertNotSame(original,cache.prepare(plan(surface(AudienceMesh.create(nodes,listOf(SurfaceTriangle(3,0,1),SurfaceTriangle(1,2,3)))))))
    }
    @Test fun frozenRequestsAndSeparateLayersKeepImportedHeightAndArea() {
        val m=CoveragePlan(listOf(surface(),surface(id=1,base=8f)),emptyList(),28f,28f,1f).prepare()
        assertEquals(32*sqrt(2.0),stats(m).totalAreaM2,1e-5)
        assertTrue(m.receivers().filter { it.areaId==1 }.all { abs(it.surfaceY-8-min(it.x,it.z))<1e-5 })
        val source=SourceSnapshot(0,-10f,1.2f,2f,0f,ArrayGeometry.poses(1,0.2f,0f,emptyList()),0f,0f,
            FrozenResponse.generic(100f,"generic",false,false))
        val request=PredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),listOf(source),emptyList(),
            1000,listOf(1000),0f,emptyMap(),true,Atmosphere(),coveragePlan=plan(spacing=1f))
        val output=request.calculate(); val i=output.geometry!!.receivers().indexOfFirst { it.x==2f && it.z==2f }
        assertEquals(3.2f,output.geometry!!.receiver(i).y,1e-5f)
        val distance=sqrt(148.0)
        assertEquals((100-20*log10(distance)-Atmosphere().absorptionDbPerMetre(1000.0)*distance).toFloat(),output.levelAt(i),1e-4f)
    }
    @Test fun importedFacesCannotBeSilentlyReplacedByElevationControls() {
        assertFailsWith<IllegalArgumentException> { AudienceSurface(0,"Invalid","AUDIENCE_SEATED",emptyList(),
            importedMesh=mesh(),elevationPoints=listOf(AudienceElevationPoint(2.0,2.0,1f))).validateGeometry() }
    }
    @Test fun parserHandlesTriangleReferencesUnitsAndAxes() {
        assertEquals(mesh(),AudienceMeshObj.parse(obj))
        val references=obj.replace("f 1 2 3","f -4/1/1 -3/2/1 -2/3/1").replace("f 1 3 4","f 1//1 3//1 4//1")
        assertEquals(mesh(),AudienceMeshObj.parse(references))
        val cm=AudienceMeshObj.parse(obj,AudienceMeshImportOptions(0.01))
        assertEquals(0.04,cm.vertices()[2].x,1e-10); assertEquals(0.04f,cm.vertices()[2].y,1e-7f)
        val zUp="v 0 0 0\nv 4 0 0\nv 4 -4 4\nv 0 -4 0\nf 1 2 3\nf 1 3 4"
        assertEquals(mesh(),AudienceMeshObj.parse(zUp,AudienceMeshImportOptions(upAxis=AudienceMeshUpAxis.Z)))
        assertEquals(mesh().createFaceGroup("seats",listOf(0,1)),AudienceMeshObj.parse("mtllib ignored.mtl\no seating\ng seats\ns off\n"+obj))
    }
    @Test fun parserRejectsAmbiguousOrUnsupportedGeometry() {
        for (text in listOf(obj.replace("f 1 2 3","f 1 2 3 4"),obj.replace("f 1 2 3","f 0 2 3"),
            obj.replace("f 1 2 3","f 1 2 9"),obj.replace("v 0 0 0","v NaN 0 0"),obj+"\nl 1 2", ""))
            assertFailsWith<IllegalArgumentException> { AudienceMeshObj.parse(text) }
        assertFailsWith<IllegalArgumentException> { AudienceMeshObj.parse(obj,AudienceMeshImportOptions(0.0)) }
        assertFailsWith<IllegalArgumentException> { AudienceMeshObj.parse(" ".repeat(AudienceMeshObj.MAX_CHARS+1)) }
    }
    @Test fun jsonAndProjectRoundTripsAreIndependentOfExternalAssets() {
        val original=ringMesh(); val json=AudienceMeshJson.encode(original)
        assertEquals(original,AudienceMeshJson.decode(json))
        val scene="""{"version":${ProjectArchive.VERSION},"audienceAreas":[{"id":0,"vertices":[],"audienceMesh":$json}]}"""
        val encoded=ProjectArchive.encode(scene,emptyMap(),{error("unexpected external asset")},false)
        val opened=ProjectArchive.open(encoded) {error("unexpected external asset")}
        val recovered=Json.parseToJsonElement(opened.sceneJson).jsonObject.getValue("audienceAreas").jsonArray[0].jsonObject.getValue("audienceMesh")
        assertEquals(original,AudienceMeshJson.decode(recovered.toString()))
        assertEquals(plan(surface(original)).prepare().receivers(),plan(surface(AudienceMeshJson.decode(recovered.toString()))).prepare().receivers())
        for (bad in listOf(json.replace("\"a\":0","\"a\":0.5"),json.replace("\"version\":10","\"version\":109"),
            json.replace("\"a\":0","\"a\":\"0\""))) assertFails { AudienceMeshJson.decode(bad) }
    }
    @Test fun geometryThatCollapsesAtReceiverPrecisionIsRejected() {
        val small=listOf(AudienceMeshVertex(100000.0,0f,100000.0),AudienceMeshVertex(100000.001,0f,100000.0),
            AudienceMeshVertex(100000.0,0f,100000.001))
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(small,listOf(SurfaceTriangle(0,1,2))) }
        val local=small.map { it.copy(x=it.x-100000,z=it.z-100000) }
        assertEquals(1,AudienceMesh.create(local,listOf(SurfaceTriangle(0,1,2))).triangles().size)
    }
    @Test fun fullExclusionKeepsAnEmptyImportedSurfaceWithoutPreviewFloor() {
        val cut=listOf(PlanPoint(-1.0,-1.0),PlanPoint(5.0,-1.0),PlanPoint(5.0,5.0),PlanPoint(-1.0,5.0))
        val m=plan(surface(ringMesh(),listOf(cut))).prepare()
        assertEquals(0,m.triangleCount); assertEquals(0,m.receiverCount)
        assertEquals(0.0,stats(m).totalAreaM2,0.0)
        assertEquals(0,m.surfaces().single().areaId)
    }
    @Test fun validationAndCoverageHaveCancellationAndSizeBudgets() {
        var calls=0
        assertFailsWith<IllegalStateException> { AudienceMesh.create(nodes,faces) { if (++calls==10) error("cancelled") } }
        assertEquals(10,calls)
        assertFailsWith<IllegalStateException> { AudienceMeshObj.parse(obj) { error("cancelled") } }
        assertFailsWith<IllegalStateException> { plan().prepare { error("cancelled") } }
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(List(4097) {nodes[0]},faces) }
        val huge=mesh().mapCoordinates { PlanPoint(it.x*1000,it.z*1000) }
        assertFailsWith<IllegalArgumentException> { plan(surface(huge),0.1f).prepare() }
    }
}
