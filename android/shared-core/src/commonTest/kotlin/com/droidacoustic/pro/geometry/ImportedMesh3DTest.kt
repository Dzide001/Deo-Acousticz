package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.statistics.*
import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.persistence.*
import kotlin.math.*
import kotlin.test.*

class ImportedMesh3DTest {
    private val nodes=listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(4.0,0f,0.0),
        AudienceMeshVertex(4.0,0f,4.0),AudienceMeshVertex(0.0,0f,4.0),
        AudienceMeshVertex(0.0,3f,0.0),AudienceMeshVertex(4.0,3f,0.0),
        AudienceMeshVertex(4.0,3f,4.0),AudienceMeshVertex(0.0,3f,4.0))
    private val overhangFaces=listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3),
        SurfaceTriangle(1,5,6),SurfaceTriangle(1,6,2),SurfaceTriangle(4,7,6),SurfaceTriangle(4,6,5))
    private fun overhang()=AudienceMesh.create(nodes,overhangFaces)
    private fun wall()=AudienceMesh.create(listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(0.0,0f,4.0),
        AudienceMeshVertex(0.0,4f,4.0),AudienceMeshVertex(0.0,4f,0.0)),listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3)))
    private fun surface(mesh: AudienceMesh=overhang(),cuts: List<List<PlanPoint>> = emptyList(),base: Float=0f,rake: Float=0f) =
        AudienceSurface(0,"3D audience","AUDIENCE_SEATED",emptyList(),baseHeightM=base,rakeDeg=rake,importedMesh=mesh,exclusions=cuts)
    private fun plan(s: AudienceSurface=surface(),spacing: Float=1f)=CoveragePlan(listOf(s),emptyList(),28f,28f,spacing)
    private fun stats(m: PreparedAudienceGeometry,level: (SurfaceReceiver)->Float={90+it.z},target: CoverageTarget=CoverageTarget(91.0,93.0))=
        CoverageStatisticsCalculator.calculate(m,FloatArray(m.receiverCount) {level(m.receiver(it))},target).overall
    private fun cut(x0: Double,z0: Double,x1: Double,z1: Double)=listOf(PlanPoint(x0,z0),PlanPoint(x1,z0),PlanPoint(x1,z1),PlanPoint(x0,z1))

    @Test fun verticalFacesRetainPhysicalAreaAndRefinementIndependentStatistics() {
        for (spacing in listOf(10f,1f,0.5f)) {
            val m=plan(surface(wall()),spacing).prepare(); val st=stats(m)
            assertEquals(16.0,st.totalAreaM2,1e-5); assertEquals(92.0,st.meanDb!!,1e-6)
            assertEquals(4/sqrt(12.0),st.standardDeviationDb!!,1e-6)
            assertEquals(90.4,st.p10Db!!,1e-6); assertEquals(93.6,st.p90Db!!,1e-6)
            assertEquals(50.0,st.withinTargetPercent!!,1e-6)
            assertTrue(m.receivers().all {it.x==0f && abs(it.y-it.surfaceY-1.2f)<1e-6})
            assertEquals(0f,m.receivers().minOf {it.surfaceY}); assertEquals(4f,m.receivers().maxOf {it.surfaceY})
            assertTrue(m.triangles().all {t -> val p=listOf(t.a,t.b,t.c).map {m.receiver(it)}
                p.map {it.surfaceY}.distinct().size>1 })
        }
    }
    @Test fun overhangingLayersSharePlanCoordinatesWithoutMergingReceivers() {
        val m=plan().prepare(); val centers=m.receivers().filter {it.x==2f && it.z==2f}
        assertEquals(listOf(0f,3f),centers.map {it.surfaceY}.sorted())
        assertEquals(listOf(1.2f,4.2f),centers.map {it.y}.sorted())
        for (t in m.triangles()) {
            val p=listOf(t.a,t.b,t.c).map {m.receiver(it)}
            assertTrue(p.all {it.surfaceY==0f} || p.all {it.surfaceY==3f} || p.all {it.x==4f})
        }
        val st=stats(m)
        assertEquals(44.0,st.totalAreaM2,1e-5); assertEquals(92.0,st.meanDb!!,1e-6)
    }
    @Test fun floorCeilingAndWallUseTheirActualAreasInDistributionAndMissingCoverage() {
        for (spacing in listOf(10f,1f,0.5f)) {
            val m=plan(spacing=spacing).prepare()
            val s=stats(m,{90+it.surfaceY},CoverageTarget(91.0,92.0))
            assertEquals(91.5,s.meanDb!!,1e-6); assertEquals(sqrt(81.0/44),s.standardDeviationDb!!,1e-6)
            assertEquals(90.0,s.p10Db!!,1e-6); assertEquals(91.5,s.p50Db!!,1e-6); assertEquals(93.0,s.p90Db!!,1e-6)
            assertEquals(4.0,s.withinTargetAreaM2!!,1e-6); assertEquals(100.0/11,s.withinTargetPercent!!,1e-6)
        }
        val missing=stats(plan(spacing=10f).prepare(),{if (it.surfaceY==3f) Float.NaN else 92f})
        assertEquals(28.0,missing.missingAreaM2,1e-6); assertEquals(16.0,missing.validAreaM2,1e-6)
        assertEquals(100*16.0/44,missing.withinTargetPercent!!,1e-6)
    }
    @Test fun closedManifoldMeshesHaveCoverageWithoutAnOuterPlanBoundary() {
        val closed=overhangFaces+listOf(SurfaceTriangle(0,3,7),SurfaceTriangle(0,7,4),
            SurfaceTriangle(0,4,5),SurfaceTriangle(0,5,1),SurfaceTriangle(3,2,6),SurfaceTriangle(3,6,7))
        val mesh=AudienceMesh.create(nodes,closed)
        assertTrue(mesh.boundaryLoops().isEmpty())
        assertEquals(80.0,stats(plan(surface(mesh)).prepare()).totalAreaM2,1e-6)
    }
    @Test fun realIntersectionsAndCoplanarOverlapsStillFail3DValidation() {
        val crossed=nodes.take(4)+listOf(AudienceMeshVertex(2.0,-1f,2.0),AudienceMeshVertex(2.0,1f,1.0))
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(crossed,overhangFaces.take(2)+
            listOf(SurfaceTriangle(0,3,4),SurfaceTriangle(3,4,5))) }
        val overlap=nodes.take(3)+AudienceMeshVertex(3.0,0f,1.0)
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(overlap,listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,1,3))) }
        val degenerate=listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(0.0,1f,0.0),AudienceMeshVertex(0.0,2f,0.0))
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(degenerate,listOf(SurfaceTriangle(0,1,2))) }
    }
    @Test fun windingNormalizationPreserves3DFacesAndClosedTopology() {
        val mixed=overhangFaces.mapIndexed { i,t -> if (i%2==0) SurfaceTriangle(t.a,t.c,t.b) else t }
        val mesh=AudienceMesh.create(nodes,mixed)
        assertEquals(44.0,stats(plan(surface(mesh)).prepare()).totalAreaM2,1e-6)
        assertEquals(overhangFaces.map {setOf(it.a,it.b,it.c)}.toSet(),mesh.triangles().map {setOf(it.a,it.b,it.c)}.toSet())
        val nonOrientable=listOf(SurfaceTriangle(0,1,3),SurfaceTriangle(0,3,2),SurfaceTriangle(2,3,5),
            SurfaceTriangle(2,5,4),SurfaceTriangle(4,5,0),SurfaceTriangle(4,0,1))
        assertFailsWith<IllegalArgumentException> { AudienceMesh.create(nodes.take(6),nonOrientable) }
    }
    @Test fun planCutoutsRemovePrismsFromEveryLayerAndFromVerticalFaces() {
        val removedLeft=surface(cuts=listOf(cut(-1.0,-1.0,2.0,5.0)))
        assertEquals(28.0,stats(plan(removedLeft).prepare()).totalAreaM2,1e-6)
        val removedWall=surface(cuts=listOf(cut(3.0,-1.0,5.0,5.0)))
        assertEquals(24.0,stats(plan(removedWall).prepare()).totalAreaM2,1e-6)
        val onBoundary=surface(cuts=listOf(cut(4.0,-1.0,5.0,5.0)))
        assertEquals(32.0,stats(plan(onBoundary).prepare()).totalAreaM2,1e-6)
        val wallCut=surface(wall(),listOf(cut(-1.0,1.0,1.0,3.0)))
        assertEquals(8.0,stats(plan(wallCut).prepare()).totalAreaM2,1e-6)
        assertTrue(wallCut.importedEdgeSegments().all { val z=(it.z0+it.z1)/2; z<=1 || z>=3 })
    }
    @Test fun ambiguousHeightLookupNeverChoosesAnArbitraryLayer() {
        val s=surface(); assertTrue(s.surfaceY(2f,2f).isNaN()); assertTrue(s.receiverY(2f,2f).isNaN())
        assertTrue(s.surfaceY(4f,2f).isNaN()); assertTrue(s.surfaceY(8f,2f).isNaN())
        assertTrue(s.containsAudiencePoint(2f,2f)); assertFalse(s.containsAudiencePoint(8f,2f))
        assertTrue(surface(wall()).containsOutlinePoint(0.1f,2f))
        assertTrue(surface(wall()).surfaceY(0f,2f).isNaN())
        val single=AudienceMesh.create(nodes.take(4),overhangFaces.take(2))
        assertEquals(0f,surface(single).surfaceY(2f,2f),1e-6f)
    }
    @Test fun verticalContoursProduceNonzeroSharedRibbonsAndCrossSectionsKeepAllLayers() {
        val m=plan(surface(wall())).prepare()
        val segments=SurfaceContours.build(m,FloatArray(m.receiverCount) {90+m.receiver(it).z},listOf(92f))
        assertTrue(segments.isNotEmpty())
        assertEquals(4.0,segments.sumOf {abs(it.y1-it.y0).toDouble()},1e-6)
        segments.forEach { line -> val ribbon=SurfaceContours.ribbon(line,0.05f)
            assertEquals(6,ribbon.size); assertTrue(ribbon.all {abs(it.x-0.25f)<1e-6})
            val points=ribbon.map {MeshPoint(it.x.toDouble(),it.y.toDouble(),it.z.toDouble())}
            assertTrue(triangleArea3D(points.take(3))>0)
        }
        val traced=surface().traceSegment(PlanPoint(0.0,2.0),PlanPoint(4.0,2.0))
        assertEquals(11.0,traced.sumOf {sqrt((it.x1-it.x0).toDouble().pow(2)+(it.y1-it.y0).toDouble().pow(2)+(it.z1-it.z0).toDouble().pow(2))},1e-6)
        assertTrue(surface(wall()).importedEdgeSegments().any {it.x0==it.x1 && it.z0==it.z1 && it.y0!=it.y1})
    }
    @Test fun layerReceiverHeightsFeedIndependentAcousticPredictions() {
        val source=SourceSnapshot(0,-10f,1.2f,2f,0f,ArrayGeometry.poses(1,0.2f,0f,emptyList()),0f,0f,
            FrozenResponse.generic(100f,"generic",false,false))
        val output=PredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),listOf(source),emptyList(),1000,
            listOf(1000),0f,emptyMap(),true,Atmosphere(),coveragePlan=plan()).calculate()
        val r=output.geometry!!.receivers()
        val floor=r.indexOfFirst {it.x==2f && it.z==2f && it.surfaceY==0f}
        val ceiling=r.indexOfFirst {it.x==2f && it.z==2f && it.surfaceY==3f}
        val distance=sqrt(153.0)
        assertEquals((20*log10(distance/12)+Atmosphere().absorptionDbPerMetre(1000.0)*(distance-12)).toFloat(),
            output.levelAt(floor)-output.levelAt(ceiling),1e-4f)
    }
    @Test fun transformsRakeCacheAndPersistenceRetain3DGeometry() {
        val cache=GeometryCache(); val source=overhang(); val original=cache.prepare(plan(surface(source)))
        assertSame(original,cache.prepare(plan(surface(AudienceMeshJson.decode(AudienceMeshJson.encode(source))))))
        val moved=source.mapCoordinates {PlanPoint(-it.x+10,it.z+2)}
        assertNotSame(original,cache.prepare(plan(surface(moved))))
        assertEquals(44.0,stats(plan(surface(moved)).prepare()).totalAreaM2,1e-6)
        assertEquals(32*sqrt(2.0)+12,stats(plan(surface(rake=45f)).prepare()).totalAreaM2,1e-5)
        val payload=AudienceMeshJson.encode(source)
        assertTrue(payload.contains("\"version\":10"))
        val scene="""{"version":${ProjectArchive.VERSION},"audienceAreas":[{"id":0,"geometryType":"IMPORTED_MESH","audienceMesh":$payload}]}"""
        assertTrue(ProjectArchive.open(scene).problems.isEmpty())
        val legacy=AudienceMeshJson.encode(AudienceMesh.create(nodes.take(4),overhangFaces.take(2))).replace("\"version\":10","\"version\":1")
        assertEquals(2,AudienceMeshJson.decode(legacy).triangles().size)
    }
    @Test fun nearbyLayersKeepSeparateReceivers() {
        val thin=nodes.mapIndexed {i,p -> if (i<4) p else p.copy(x=p.x+0.1,y=0.000004f)}
        val s=surface(AudienceMesh.create(thin,overhangFaces))
        assertTrue(s.surfaceY(1f,1f).isNaN())
        val m=plan(s).prepare()
        assertEquals(listOf(0f,0.000004f),m.receivers().filter {it.x==1f && it.z==1f}.map {it.surfaceY}.sorted())
        assertEquals(32+4*sqrt(0.1*0.1+0.000004*0.000004),stats(m).totalAreaM2,2e-6)
    }
    @Test fun cancellationAndReceiverLimitsApplyToVerticalSampling() {
        var calls=0
        assertFailsWith<IllegalStateException> { plan(surface(wall()),0.1f).prepare {if (++calls==40) error("cancelled")} }
        assertEquals(40,calls)
        assertFailsWith<IllegalArgumentException> { plan(surface(base=100000000f)).prepare() }
        assertFailsWith<IllegalArgumentException> { CoveragePlan(listOf(surface(wall())),emptyList(),28f,28f,1f,maxReceivers=4).prepare() }
    }
}
