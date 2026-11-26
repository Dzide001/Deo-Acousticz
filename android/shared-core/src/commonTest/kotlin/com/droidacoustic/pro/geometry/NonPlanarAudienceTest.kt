package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.statistics.*
import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import kotlin.math.*
import kotlin.test.*

class NonPlanarAudienceTest {
    private val square=listOf(PlanPoint(0.0,0.0),PlanPoint(4.0,0.0),PlanPoint(4.0,4.0),PlanPoint(0.0,4.0))
    private fun surface(offsets: List<Float> = emptyList(),points: List<AudienceElevationPoint> = emptyList(),
        holes: List<List<PlanPoint>> = emptyList(),id: Int=0,height: Float=0f) =
        AudienceSurface(id,"Nonplanar","AUDIENCE_SEATED",square,height,holes=holes,vertexOffsetsM=offsets,elevationPoints=points)
    private fun pyramid(holes: List<List<PlanPoint>> = emptyList()) = surface(points=listOf(AudienceElevationPoint(2.0,2.0,2f)),holes=holes)
    private fun plan(s: AudienceSurface,spacing: Float=1f) = CoveragePlan(listOf(s),emptyList(),28f,28f,spacing)
    private fun stats(m: PreparedAudienceGeometry,level: (SurfaceReceiver)->Float = {90f}) =
        CoverageStatisticsCalculator.calculate(m,FloatArray(m.receiverCount) { level(m.receiver(it)) },CoverageTarget(91.0,93.0)).overall
    private fun roof(x: Float,z: Float) = min(min(x,4-x),min(z,4-z))

    @Test fun interiorPeakPreservesFacetsEvenWithCoarseGrid() {
        val m=plan(pyramid(),10f).prepare()
        assertEquals(4,m.triangleCount)
        assertTrue(m.receivers().any { it.x==2f && it.z==2f && it.surfaceY==2f })
        m.receivers().forEach { assertEquals(roof(it.x,it.z),it.surfaceY,1e-5f)
            assertEquals(it.surfaceY+1.2f,it.y,1e-5f) }
        m.triangles().forEach { t ->
            val p=listOf(t.a,t.b,t.c).map { m.receiver(it) }
            val x=p.sumOf { it.x.toDouble() }.toFloat()/3; val z=p.sumOf { it.z.toDouble() }.toFloat()/3
            assertEquals(roof(x,z),p.sumOf { it.surfaceY.toDouble() }.toFloat()/3,1e-5f)
        }
        assertEquals(16*sqrt(2.0),stats(m).totalAreaM2,1e-5)
    }
    @Test fun areaWeightedRampMatchesAnalyticalPyramidAndRefinement() {
        for (spacing in listOf(10f,1f,0.5f)) {
            val s=stats(plan(pyramid(),spacing).prepare()) { 90+it.x }
            assertEquals(16*sqrt(2.0),s.totalAreaM2,1e-5)
            assertEquals(92.0,s.meanDb!!,1e-6)
            assertEquals(4/sqrt(12.0),s.standardDeviationDb!!,1e-6)
            assertEquals(90.4,s.p10Db!!,1e-6); assertEquals(93.6,s.p90Db!!,1e-6)
            assertEquals(50.0,s.withinTargetPercent!!,1e-6)
        }
    }
    @Test fun warpedCornerWeightsFlatAndSlopedFacetsByTheirDifferentAreas() {
        val m=plan(surface(offsets=listOf(0f,0f,4f,0f))).prepare()
        val s=stats(m) { 100+it.surfaceY }
        val tilted=sqrt(3.0)/(1+sqrt(3.0)); val meanOffset=tilted*4/3
        assertEquals(8*(1+sqrt(3.0)),s.totalAreaM2,1e-5)
        assertEquals(100+meanOffset,s.meanDb!!,1e-5)
        assertEquals(sqrt(tilted*16/6-meanOffset*meanOffset),s.standardDeviationDb!!,1e-5)
    }
    @Test fun collinearBoundaryElevationVertexIsNotDiscarded() {
        val outline=listOf(PlanPoint(0.0,0.0),PlanPoint(2.0,0.0),PlanPoint(4.0,0.0),PlanPoint(4.0,4.0),PlanPoint(0.0,4.0))
        val s=AudienceSurface(0,"Edge break","AUDIENCE_SEATED",outline,vertexOffsetsM=listOf(0f,2f,0f,0f,0f))
        val m=plan(s,10f).prepare()
        assertEquals(2f,s.surfaceY(2f,0f),1e-5f)
        assertTrue(m.receivers().any { it.x==2f && it.z==0f && it.surfaceY==2f })
        assertTrue(stats(m).totalAreaM2>16)
    }
    @Test fun holesClipEveryFacetAndStatisticsUseRemainingPhysicalArea() {
        val hole=listOf(PlanPoint(1.0,1.0),PlanPoint(3.0,1.0),PlanPoint(3.0,3.0),PlanPoint(1.0,3.0))
        val s=pyramid(listOf(hole)); val m=plan(s,10f).prepare()
        assertEquals(12*sqrt(2.0),stats(m).totalAreaM2,1e-5)
        assertEquals(0.0,stats(m).missingAreaM2,1e-5)
        assertTrue(m.receivers().none { it.x>1 && it.x<3 && it.z>1 && it.z<3 })
        m.triangles().forEach { t ->
            val p=listOf(t.a,t.b,t.c).map { m.receiver(it) }
            val x=p.sumOf { it.x.toDouble() }.toFloat()/3; val z=p.sumOf { it.z.toDouble() }.toFloat()/3
            assertFalse(x>1 && x<3 && z>1 && z<3)
            assertEquals(roof(x,z),p.sumOf { it.surfaceY.toDouble() }.toFloat()/3,1e-5f)
        }
    }
    @Test fun outlineAndContourSegmentsFollowCreasesAndDoNotBridgeThePeak() {
        val s=pyramid(); val traced=s.traceSegment(PlanPoint(0.0,2.0),PlanPoint(4.0,2.0))
        assertEquals(4.0,traced.sumOf { abs(it.x1-it.x0).toDouble() },1e-5)
        assertTrue(traced.any { it.y0==2f || it.y1==2f })
        traced.forEach { assertEquals(roof(it.x0,it.z0),it.y0,1e-5f)
            assertEquals(roof(it.x1,it.z1),it.y1,1e-5f)
            assertEquals(roof((it.x0+it.x1)/2,(it.z0+it.z1)/2),(it.y0+it.y1)/2,1e-5f) }
        val m=plan(s).prepare()
        val lines=SurfaceContours.build(m,FloatArray(m.receiverCount) { 90+m.receiver(it).x },listOf(92f))
        assertTrue(lines.isNotEmpty())
        lines.forEach { assertEquals(roof(it.x0,it.z0),it.y0,1e-5f); assertEquals(roof(it.x1,it.z1),it.y1,1e-5f) }
    }
    @Test fun independentLayerRetainsItsOwnElevationAndArea() {
        val s=surface(points=listOf(AudienceElevationPoint(2.0,2.0,2f)))
        val m=CoveragePlan(listOf(s,surface(id=1,height=8f)),emptyList(),28f,28f,1f).prepare()
        assertEquals(16+16*sqrt(2.0),stats(m).totalAreaM2,1e-5)
        assertTrue(m.receivers().filter { it.areaId==1 }.all { it.surfaceY==8f && it.y==9.2f })
    }
    @Test fun invalidElevationsFailWithoutInventingHeights() {
        val invalid=listOf(surface(offsets=listOf(0f)),surface(offsets=listOf(0f,0f,Float.NaN,0f)),
            surface(points=listOf(AudienceElevationPoint(5.0,2.0,1f))),
            surface(points=listOf(AudienceElevationPoint(0.0,2.0,1f))),
            surface(points=List(2) {AudienceElevationPoint(2.0,2.0,1f)}),
            surface(points=List(257) {AudienceElevationPoint(2.0,2.0,1f)}))
        invalid.forEach { assertFailsWith<IllegalArgumentException> { plan(it).prepare() } }
        assertTrue(pyramid().surfaceY(5f,2f).isNaN())
    }
    @Test fun cacheSnapshotsIncludeBoundaryAndInteriorElevations() {
        val offsets=mutableListOf(0f,0f,0f,0f)
        val points=mutableListOf(AudienceElevationPoint(2.0,2.0,2f))
        val s=surface(offsets,points); val cache=GeometryCache(); val original=cache.prepare(plan(s))
        offsets[0]=10f; points.clear()
        assertSame(original,cache.prepare(plan(s)))
        assertNotSame(original,cache.prepare(plan(surface(offsets=listOf(0f,0f,1f,0f)))))
        assertNotSame(original,cache.prepare(plan(surface(points=listOf(AudienceElevationPoint(2.0,2.0,3f))))))
        assertEquals(2f,original.receivers().first { it.x==2f && it.z==2f }.surfaceY,0f)
    }
    @Test fun nonplanarReceiverHeightFeedsThePredictionEngine() {
        val source=SourceSnapshot(0,-10f,1.2f,2f,0f,ArrayGeometry.poses(1,0.2f,0f,emptyList()),0f,0f,
            FrozenResponse.generic(100f,"generic",false,false))
        fun result(s: AudienceSurface)=PredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),
            listOf(source),emptyList(),1000,listOf(1000),0f,emptyMap(),true,Atmosphere(),coveragePlan=plan(s)).calculate()
        val flat=result(surface()); val raised=result(pyramid())
        fun center(r: PredictionOutput)=r.geometry!!.receivers().indexOfFirst { it.x==2f && it.z==2f }
        val delta=flat.levelAt(center(flat))-raised.levelAt(center(raised))
        val d=sqrt(148.0)
        assertEquals((20*log10(d/12)+Atmosphere().absorptionDbPerMetre(1000.0)*(d-12)).toFloat(),delta,1e-4f)
    }
    @Test fun insertionAndMeshPreparationAreCancellable() {
        var calls=0
        assertFailsWith<IllegalStateException> { plan(pyramid()).prepare { if (++calls==20) error("cancelled") } }
    }
}
