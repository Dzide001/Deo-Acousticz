package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.statistics.*
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class AudienceBreaklinesTest {
    private val square=listOf(PlanPoint(0.0,0.0),PlanPoint(4.0,0.0),PlanPoint(4.0,4.0),PlanPoint(0.0,4.0))
    private fun b(i: Int)=AudienceVertexRef(AudienceVertexKind.BOUNDARY,i)
    private fun p(i: Int)=AudienceVertexRef(AudienceVertexKind.INTERIOR,i)
    private fun line(start: Int,end: Int)=AudienceBreakline(b(start),b(end))
    private fun surface(lines: List<AudienceBreakline> = listOf(line(0,2)),points: List<AudienceElevationPoint> = emptyList(),
        holes: List<List<PlanPoint>> = emptyList(),exclusions: List<List<PlanPoint>> = emptyList()) =
        AudienceSurface(0,"Ridge","AUDIENCE_SEATED",square,vertexOffsetsM=listOf(0f,0f,4f,0f),
            elevationPoints=points,breaklines=lines,holes=holes,exclusions=exclusions)
    private fun plan(s: AudienceSurface,spacing: Float=10f)=CoveragePlan(listOf(s),emptyList(),28f,28f,spacing)
    private fun stats(m: PreparedAudienceGeometry,level: (SurfaceReceiver)->Float={90f}) =
        CoverageStatisticsCalculator.calculate(m,FloatArray(m.receiverCount) { level(m.receiver(it)) },CoverageTarget(91.0,93.0)).overall
    private fun assertEdges(s: AudienceSurface) {
        val mesh=s.elevationMesh {}; val points=mesh.points
        val edges=mesh.triangles.flatMap { listOf(it.a to it.b,it.b to it.c,it.c to it.a) }
        for (line in s.breaklines()) {
            val a=s.vertexPosition(line.start); val b=s.vertexPosition(line.end)
            val chain=points.indices.filter { PolygonGeometry.onSegment(a,b,points[it]) }
                .sortedBy { (points[it].x-a.x)*(b.x-a.x)+(points[it].z-a.z)*(b.z-a.z) }
            for ((u,v) in chain.zipWithNext()) assertTrue(edges.any { it==(u to v) || it==(v to u) },"Missing edge $u–$v")
        }
    }
    @Test fun forcedDiagonalChangesTheHeightFieldAndPhysicalArea() {
        val natural=surface(emptyList()); val ridge=surface()
        assertEquals(0f,natural.surfaceY(2f,2f),1e-6f)
        assertEquals(2f,ridge.surfaceY(2f,2f),1e-6f)
        assertEquals(8*(1+sqrt(3.0)),stats(plan(natural).prepare()).totalAreaM2,1e-5)
        assertEquals(16*sqrt(2.0),stats(plan(ridge).prepare()).totalAreaM2,1e-5)
        assertEdges(ridge)
    }
    @Test fun coverageRetainsCreasesAndAnalyticalStatisticsAtEverySpacing() {
        for (spacing in listOf(10f,1f,0.4f)) {
            val m=plan(surface(),spacing).prepare()
            m.receivers().forEach { assertEquals(min(it.x,it.z),it.surfaceY,1e-5f) }
            m.triangles().forEach { t ->
                val points=listOf(t.a,t.b,t.c).map { m.receiver(it) }
                assertFalse(points.any { it.x-it.z>1e-5 } && points.any { it.x-it.z< -1e-5 })
            }
            val result=stats(m) { 90+it.x }
            assertEquals(16*sqrt(2.0),result.totalAreaM2,1e-5)
            assertEquals(92.0,result.meanDb!!,2e-6)
            assertEquals(4/sqrt(12.0),result.standardDeviationDb!!,2e-6)
            assertEquals(90.4,result.p10Db!!,2e-6); assertEquals(93.6,result.p90Db!!,2e-6)
            assertEquals(50.0,result.withinTargetPercent!!,2e-5)
        }
    }
    @Test fun missingFacetUsesItsTrueAreaAndNetTargetDenominator() {
        val m=plan(surface()).prepare()
        val result=CoverageStatisticsCalculator.calculate(m,FloatArray(m.receiverCount) {
            val r=m.receiver(it); if (r.x==4f && r.z==0f) Float.NaN else 92f
        },CoverageTarget(91.0,93.0)).overall
        assertEquals(8*sqrt(2.0),result.missingAreaM2,1e-5)
        assertEquals(50.0,result.withinTargetPercent!!,1e-5)
    }
    @Test fun collinearBoundaryAnchorsDefineAFlatAndSlopedStrip() {
        val ring=listOf(PlanPoint(0.0,0.0),PlanPoint(2.0,0.0),PlanPoint(4.0,0.0),
            PlanPoint(4.0,4.0),PlanPoint(2.0,4.0),PlanPoint(0.0,4.0))
        val s=AudienceSurface(0,"Strips","AUDIENCE_SEATED",ring,vertexOffsetsM=listOf(0f,0f,2f,2f,0f,0f),breaklines=listOf(line(1,4)))
        assertEdges(s)
        val m=plan(s,0.75f).prepare()
        m.receivers().forEach { assertEquals(max(0f,it.x-2),it.surfaceY,1e-5f) }
        assertEquals(8+8*sqrt(2.0),stats(m).totalAreaM2,1e-5)
        val result=stats(m) { 90+it.x }
        assertEquals((91+93*sqrt(2.0))/(1+sqrt(2.0)),result.meanDb!!,1e-5)
    }
    @Test fun cutoutsClipConstraintsCoverageAndStatisticsWithoutChangingUnderlyingHeight() {
        val hole=listOf(PlanPoint(1.0,1.0),PlanPoint(3.0,1.0),PlanPoint(3.0,3.0),PlanPoint(1.0,3.0))
        for (s in listOf(surface(holes=listOf(hole)),surface(exclusions=listOf(hole,hole)))) {
            val m=plan(s).prepare()
            assertEquals(12*sqrt(2.0),stats(m).totalAreaM2,1e-5)
            assertEquals(92.0,stats(m) {90+it.x}.meanDb!!,1e-5)
            assertTrue(m.receivers().none { it.x>1 && it.x<3 && it.z>1 && it.z<3 })
            val lines=s.breaklineSegments()
            assertEquals(2.0,lines.sumOf { abs(it.x1-it.x0).toDouble() },1e-5)
            lines.forEach { val mid=(it.x0+it.x1)/2; assertFalse(mid>1 && mid<3)
                assertEquals(it.x0,it.y0,1e-5f); assertEquals(it.x1,it.y1,1e-5f) }
            assertEquals(2f,s.surfaceY(2f,2f),1e-5f)
        }
    }
    @Test fun crossingsRequireSharedPointAndRetainItsHeight() {
        assertFailsWith<IllegalArgumentException> { surface(listOf(line(0,2),line(1,3))).validateGeometry() }
        val s=surface(listOf(line(0,2),line(1,3)),listOf(AudienceElevationPoint(2.0,2.0,5f)))
        assertEdges(s)
        assertEquals(5f,s.surfaceY(2f,2f),1e-5f)
        assertEquals(4,s.elevationMesh {}.triangles.size)
        val t=surface(listOf(line(0,2),AudienceBreakline(b(1),p(0))),listOf(AudienceElevationPoint(2.0,2.0,3f)))
        assertEdges(t)
    }
    @Test fun floatRoundedJunctionIsRetainedButNearbyUnconnectedCrossingIsRejected() {
        val s=surface(listOf(line(0,2),line(1,3)),listOf(AudienceElevationPoint(2.0000003,2.0,5f)))
        val m=plan(s).prepare()
        assertEquals(4,s.elevationMesh {}.triangles.size)
        assertEquals(5f,m.receivers().maxOf { it.surfaceY },1e-5f)
        assertTrue(s.breaklineSegments().any { it.y0==5f || it.y1==5f })
        assertFailsWith<IllegalArgumentException> {
            surface(listOf(line(0,2),line(1,3)),listOf(AudienceElevationPoint(2.001,2.0,5f))).validateGeometry()
        }
    }
    @Test fun reverseDuplicateAndOverlappingConstraintsAreHarmless() {
        val points=listOf(AudienceElevationPoint(1.0,1.0,1f),AudienceElevationPoint(3.0,3.0,3f))
        val s=surface(listOf(line(0,2),line(2,0),AudienceBreakline(p(0),p(1))),points)
        assertEdges(s); assertEquals(16*sqrt(2.0),stats(plan(s).prepare()).totalAreaM2,1e-5)
    }
    @Test fun clockwiseAndClosedBoundaryReferencesKeepOriginalMeaning() {
        val s=AudienceSurface(0,"Reverse","AUDIENCE_SEATED",square.reversed()+square.last(),
            vertexOffsetsM=listOf(0f,4f,0f,0f,0f),breaklines=listOf(line(3,1)))
        assertEdges(s); assertEquals(2f,s.surfaceY(2f,2f),1e-5f)
        assertEquals(16*sqrt(2.0),stats(plan(s).prepare()).totalAreaM2,1e-5)
    }
    @Test fun invalidReferencesAndPathsOutsideConcaveBoundaryFail() {
        for (line in listOf(line(0,0),line(-1,2),line(0,4),AudienceBreakline(b(0),p(0))))
            assertFailsWith<IllegalArgumentException> { surface(listOf(line)).validateGeometry() }
        val concave=listOf(PlanPoint(0.0,0.0),PlanPoint(4.0,0.0),PlanPoint(4.0,1.0),
            PlanPoint(1.0,1.0),PlanPoint(1.0,4.0),PlanPoint(0.0,4.0))
        assertFailsWith<IllegalArgumentException> {
            AudienceSurface(0,"L","AUDIENCE_SEATED",concave,breaklines=listOf(line(2,4))).validateGeometry()
        }
        assertFailsWith<IllegalArgumentException> { surface(List(257) {line(0,2)}).validateGeometry() }
    }
    @Test fun constraintSnapshotsAndCacheKeysAreIndependentOfMutableInputs() {
        val lines=mutableListOf(line(0,2)); val s=surface(lines); val cache=GeometryCache()
        val m=cache.prepare(plan(s)); lines.clear()
        assertSame(m,cache.prepare(plan(s)))
        assertNotSame(m,cache.prepare(plan(surface(lines))))
        assertEquals(listOf(line(0,2)),s.breaklines())
    }
    @Test fun pointRemovalDeletesIncidentConstraintsAndRemapsSurvivors() {
        assertNull(AudienceBreakline(b(0),p(1)).afterRemovingInterior(1))
        assertEquals(AudienceBreakline(p(1),p(0)),AudienceBreakline(p(2),p(0)).afterRemovingInterior(1))
        assertEquals(line(0,2),line(0,2).afterRemovingInterior(0))
        val junction=surface(listOf(line(0,2),line(1,3),line(0,1)),listOf(AudienceElevationPoint(2.0,2.0,3f)))
        assertEquals(listOf(line(0,1)),junction.breaklinesAfterRemovingInterior(0))
    }
    @Test fun recoveryIsCancellable() {
        val base=surface(emptyList()).elevationMesh {}; var calls=0
        assertFailsWith<IllegalStateException> {
            BreaklineTriangulation.constrain(base.points,base.triangles,listOf(0,1,2,3),emptyList(),listOf(line(0,2))) {
                if (++calls==10) error("cancelled")
            }
        }
        assertEquals(10,calls)
    }
    @Test fun variedInteriorFansRecoverAllConstraintsAndConserveArea() {
        repeat(40) { seed ->
            val random=Random(seed)
            val points=List(20) { AudienceElevationPoint(0.1+random.nextDouble()*3.8,0.1+random.nextDouble()*3.8,0f) }
            val constraints=(1 until points.size).map { AudienceBreakline(p(0),p(it)) }+
                (0..3).map { AudienceBreakline(p(0),b(it)) }
            val s=AudienceSurface(0,"Fan","AUDIENCE_SEATED",square,elevationPoints=points,breaklines=constraints)
            assertEdges(s)
            assertEquals(16.0,stats(plan(s).prepare()).totalAreaM2,2e-5)
        }
    }
}
