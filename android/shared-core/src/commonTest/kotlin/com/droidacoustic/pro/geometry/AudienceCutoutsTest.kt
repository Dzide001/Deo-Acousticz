package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.statistics.*
import kotlin.math.*
import kotlin.test.*

class AudienceCutoutsTest {
    private fun rect(x0: Double=0.0,z0: Double=0.0,x1: Double=10.0,z1: Double=10.0) =
        listOf(PlanPoint(x0,z0),PlanPoint(x1,z0),PlanPoint(x1,z1),PlanPoint(x0,z1))
    private fun surface(id: Int=0,holes: List<List<PlanPoint>> = emptyList(),
        exclusions: List<List<PlanPoint>> = emptyList(),height: Float=0f,rake: Float=0f,
        outer: List<PlanPoint> = rect()) =
        AudienceSurface(id,"Zone $id","AUDIENCE_SEATED",outer,height,rake,holes=holes,exclusions=exclusions)
    private fun mesh(vararg surfaces: AudienceSurface,spacing: Float=2f,probes: List<SurfaceReceiver> = emptyList()) =
        CoveragePlan(surfaces.toList(),probes,28f,28f,spacing).prepare()
    private fun stats(m: PreparedAudienceGeometry,target: CoverageTarget?=null,level: (SurfaceReceiver)->Float = {90f}) =
        CoverageStatisticsCalculator.calculate(m,FloatArray(m.receiverCount) { level(m.receiver(it)) },target)
    private fun triangleArea(a: PlanPoint,b: PlanPoint,c: PlanPoint) = abs(PolygonGeometry.cross(a,b,c))/2
    /** Independent rectangle/triangle intersection check: clip along rectangle axes and integrate area. */
    private fun intersectionArea(triangle: List<PlanPoint>,xmin: Double,zmin: Double,xmax: Double,zmax: Double): Double {
        var p=triangle
        for ((axis,bound,sign) in listOf(Triple(0,xmin,1),Triple(0,xmax,-1),Triple(1,zmin,1),Triple(1,zmax,-1))) {
            val output=mutableListOf<PlanPoint>()
            for (i in p.indices) {
                val a=p[i]; val b=p[(i+1)%p.size]
                val da=((if(axis==0) a.x else a.z)-bound)*sign
                val db=((if(axis==0) b.x else b.z)-bound)*sign
                if (da>=0) output+=a
                if ((da>0 && db<0)||(da<0 && db>0)) {
                    val t=da/(da-db); output+=PlanPoint(a.x+(b.x-a.x)*t,a.z+(b.z-a.z)*t)
                }
            }
            p=output
        }
        return if (p.size<3) 0.0 else (1 until p.lastIndex).sumOf { triangleArea(p[0],p[it],p[it+1]) }
    }
    private fun assertNoTriangleInRect(m: PreparedAudienceGeometry,x0: Double,z0: Double,x1: Double,z1: Double,areaId: Int=0) {
        m.triangles().filter { m.receiver(it.a).areaId==areaId }.forEach { t ->
            val triangle=listOf(t.a,t.b,t.c).map { val p=m.receiver(it); PlanPoint(p.x.toDouble(),p.z.toDouble()) }
            assertEquals(0.0,intersectionArea(triangle,x0,z0,x1,z1),1e-6)
        }
    }
    @Test fun holeRemovesExactAreaAndNoTriangleBridgesAcrossIt() {
        val m=mesh(surface(holes=listOf(rect(3.0,3.0,7.0,7.0))))
        assertEquals(84.0,stats(m).overall.totalAreaM2,1e-5)
        assertNoTriangleInRect(m,3.0,3.0,7.0,7.0)
        assertTrue(m.receivers().none { it.x>3 && it.x<7 && it.z>3 && it.z<7 })
    }
    @Test fun holeStatisticsMatchAnalyticalRampAndNetTargetDenominator() {
        val m=mesh(surface(holes=listOf(rect(3.0,3.0,7.0,7.0))))
        val s=stats(m,CoverageTarget(93.0,97.0)) { 90+it.x }.overall
        assertEquals(95.0,s.meanDb!!,1e-6)
        assertEquals(sqrt(29.0/3),s.standardDeviationDb!!,1e-6)
        assertEquals(90.84,s.p10Db!!,1e-6); assertEquals(99.16,s.p90Db!!,1e-6)
        assertEquals(24.0,s.withinTargetAreaM2!!,1e-6)
        assertEquals(100*24.0/84,s.withinTargetPercent!!,1e-6)
        assertEquals(0.0,s.missingAreaM2,1e-6)
    }
    @Test fun smallHoleSurvivesCoarseGridAndReversedWinding() {
        val hole=rect(1.1,1.2,1.3,1.4).reversed()
        val m=mesh(surface(holes=listOf(hole),outer=rect().reversed()),spacing=3f)
        assertEquals(99.96,stats(m).overall.totalAreaM2,1e-5)
        assertNoTriangleInRect(m,1.1,1.2,1.3,1.4)
    }
    @Test fun multipleHolesAndConcaveExclusionAreSupported() {
        val l=listOf(PlanPoint(5.0,5.0),PlanPoint(8.0,5.0),PlanPoint(8.0,6.0),
            PlanPoint(6.0,6.0),PlanPoint(6.0,8.0),PlanPoint(5.0,8.0))
        val m=mesh(surface(holes=listOf(rect(1.0,1.0,2.0,2.0),rect(3.0,1.0,4.0,2.0)),exclusions=listOf(l)))
        assertEquals(93.0,stats(m).overall.totalAreaM2,1e-5)
    }
    @Test fun overlappingExclusionsUseUnionAndDuplicateMasksAreIdempotent() {
        val a=rect(1.0,1.0,5.0,5.0); val b=rect(3.0,3.0,7.0,7.0)
        val m=mesh(surface(exclusions=listOf(a,b,a)))
        assertEquals(72.0,stats(m).overall.totalAreaM2,1e-5)
        assertNoTriangleInRect(m,1.0,1.0,5.0,5.0); assertNoTriangleInRect(m,3.0,3.0,7.0,7.0)
    }
    @Test fun boundaryCrossingCutCanSplitSurfaceIntoDisconnectedRegions() {
        val m=mesh(surface(exclusions=listOf(rect(4.0,-1.0,6.0,11.0))))
        assertEquals(80.0,stats(m).overall.totalAreaM2,1e-5)
        assertNoTriangleInRect(m,4.0,0.0,6.0,10.0)
        assertTrue(m.triangles().all { t -> val xs=listOf(t.a,t.b,t.c).map { m.receiver(it).x }
            xs.max()<=4f || xs.min()>=6f })
    }
    @Test fun entirelyExternalAndBoundaryTouchingExclusionsDoNotRemoveExtraArea() {
        val m=mesh(surface(exclusions=listOf(rect(20.0,20.0,22.0,22.0),rect(10.0,0.0,12.0,10.0))))
        assertEquals(100.0,stats(m).overall.totalAreaM2,1e-5)
    }
    @Test fun completeExclusionKeepsNamedSurfaceWithZeroNetArea() {
        val excluded=surface(exclusions=listOf(rect(-1.0,-1.0,11.0,11.0)))
        assertTrue(excluded.containsOutlinePoint(5f,5f))
        assertFalse(excluded.containsAudiencePoint(5f,5f))
        val m=mesh(excluded)
        assertEquals(0,m.receiverCount); assertEquals(0,m.triangleCount)
        val s=stats(m,CoverageTarget(80.0,100.0))
        assertEquals(1,s.surfaces().size); assertEquals("area:0",s.surfaces().single().surfaceKey)
        assertEquals(0.0,s.overall.totalAreaM2,0.0); assertEquals(0.0,s.overall.missingAreaM2,0.0)
        assertNull(s.overall.meanDb); assertNull(s.overall.withinTargetPercent)
    }
    @Test fun holeIsLocalToOneLayerAndRakedAreaUsesPhysicalSurface() {
        val m=mesh(surface(holes=listOf(rect(3.0,3.0,7.0,7.0))),surface(1,height=5f))
        assertEquals(184.0,stats(m).overall.totalAreaM2,1e-5)
        assertEquals(100.0,stats(m).surfaces().first { it.areaId==1 }.statistics.totalAreaM2,1e-5)
        val raked=mesh(surface(holes=listOf(rect(3.0,3.0,7.0,7.0)),rake=30f))
        assertEquals(84/cos(PI/6),stats(raked).overall.totalAreaM2,1e-4)
    }
    @Test fun manualProbeInHoleRemainsIndependentAndCannotRestoreArea() {
        val probe=SurfaceReceiver(5f,1.2f,5f,1.2f,"probe:1")
        val m=mesh(surface(holes=listOf(rect(3.0,3.0,7.0,7.0))),probes=listOf(probe))
        assertEquals(probe,m.receivers().last()); assertEquals(84.0,stats(m).overall.totalAreaM2,1e-5)
        val lines=SurfaceContours.build(m,FloatArray(m.receiverCount) { 90+m.receiver(it).x },listOf(95f))
        assertTrue(lines.all { max(it.z0,it.z1)<=3f || min(it.z0,it.z1)>=7f })
    }
    @Test fun invalidHolesAndMalformedExclusionsFailRatherThanFillingSilently() {
        for (hole in listOf(rect(-1.0,1.0,2.0,2.0),rect(0.0,1.0,2.0,2.0)))
            assertFailsWith<IllegalArgumentException> { mesh(surface(holes=listOf(hole))) }
        assertFailsWith<IllegalArgumentException> { mesh(surface(holes=listOf(rect(1.0,1.0,5.0,5.0),rect(2.0,2.0,3.0,3.0)))) }
        assertFailsWith<IllegalArgumentException> { mesh(surface(exclusions=listOf(listOf(PlanPoint(0.0,0.0),PlanPoint(1.0,1.0))))) }
        assertFailsWith<IllegalArgumentException> { mesh(surface(exclusions=List(65) { rect() })) }
    }
    @Test fun nestedListsAreFrozenAndCutoutEditsInvalidateCache() {
        val ring=rect(3.0,3.0,7.0,7.0).toMutableList(); val holes=mutableListOf<List<PlanPoint>>(ring)
        val original=surface(holes=holes); val cache=GeometryCache()
        fun plan(s: AudienceSurface)=CoveragePlan(listOf(s),emptyList(),28f,28f,2f)
        val before=cache.prepare(plan(original)); ring.clear(); holes.clear()
        assertSame(before,cache.prepare(plan(original)))
        assertNotSame(before,cache.prepare(plan(surface())))
        assertNotSame(before,cache.prepare(plan(surface(holes=listOf(rect(2.0,2.0,7.0,7.0))))))
        assertEquals(84.0,stats(before).overall.totalAreaM2,1e-5)
    }
    @Test fun cutoutProcessingIsCancellableAndDoesNotCachePartialGeometry() {
        val cache=GeometryCache(); var calls=0
        assertFailsWith<IllegalStateException> {
            cache.prepare(CoveragePlan(listOf(surface(exclusions=listOf(rect(1.0,1.0,9.0,9.0)))),emptyList(),28f,28f,1f)) {
                if (++calls==100) error("cancelled")
            }
        }
        assertEquals(0,cache.stats().entries)
    }
    @Test fun concaveOuterAndRotatedHoleRetainExactNetArea() {
        val l=listOf(PlanPoint(0.0,0.0),PlanPoint(4.0,0.0),PlanPoint(4.0,1.0),
            PlanPoint(1.0,1.0),PlanPoint(1.0,4.0),PlanPoint(0.0,4.0))
        val concave=mesh(surface(outer=l,holes=listOf(rect(0.25,0.25,0.75,0.75))),spacing=3f)
        assertEquals(6.75,stats(concave).overall.totalAreaM2,1e-5)
        val diamond=rect(3.0,3.0,7.0,7.0).map { p ->
            val x=p.x-5; val z=p.z-5; PlanPoint(5+x*cos(0.3)-z*sin(0.3),5+x*sin(0.3)+z*cos(0.3)) }
        assertEquals(84.0,stats(mesh(surface(holes=listOf(diamond)))).overall.totalAreaM2,1e-5)
    }
    @Test fun holesAndExclusionsShareUnionAndMissingAreaUsesNetDomain() {
        val m=mesh(surface(holes=listOf(rect(1.0,1.0,5.0,5.0)),exclusions=listOf(rect(3.0,3.0,7.0,7.0))))
        val s=stats(m) { Float.NaN }.overall
        assertEquals(72.0,s.totalAreaM2,1e-5); assertEquals(72.0,s.missingAreaM2,1e-5)
        assertEquals(0.0,s.validAreaM2,0.0)
    }

}
