package com.droidacoustic.pro.statistics

import com.droidacoustic.pro.geometry.*
import kotlin.math.*
import kotlin.test.*

class CoverageStatisticsTest {
    private fun surface(id: Int=0, width: Double=10.0, height: Float=0f, rake: Float=0f) =
        AudienceSurface(id,"Same name","AUDIENCE_SEATED",listOf(PlanPoint(0.0,0.0),PlanPoint(width,0.0),
            PlanPoint(width,10.0),PlanPoint(0.0,10.0)),height,rake)
    private fun mesh(vararg surfaces: AudienceSurface, spacing: Float=2f, probes: List<SurfaceReceiver> = emptyList()) =
        CoveragePlan(surfaces.toList(),probes,28f,28f,spacing).prepare()
    private fun calculate(mesh: PreparedAudienceGeometry, target: CoverageTarget?=null, level: (SurfaceReceiver)->Float) =
        CoverageStatisticsCalculator.calculate(mesh,FloatArray(mesh.receiverCount) { level(mesh.receiver(it)) },target)

    @Test fun linearRampHasAnalyticalAreaMeanDeviationPercentilesAndTargetFraction() {
        val stats=calculate(mesh(surface()),CoverageTarget(92.0,96.0)) { 90+it.x }.overall
        assertEquals(100.0,stats.totalAreaM2,1e-6)
        assertEquals(95.0,stats.meanDb!!,1e-6)
        assertEquals(10/sqrt(12.0),stats.standardDeviationDb!!,1e-6)
        assertEquals(91.0,stats.p10Db!!,1e-6); assertEquals(95.0,stats.p50Db!!,1e-6)
        assertEquals(99.0,stats.p90Db!!,1e-6); assertEquals(99.5,stats.p95Db!!,1e-6)
        assertEquals(40.0,stats.withinTargetAreaM2!!,1e-6)
        assertEquals(40.0,stats.withinTargetPercent!!,1e-6)
    }
    @Test fun resamplingSameLinearFieldDoesNotChangeStatistics() {
        val coarse=calculate(mesh(surface(),spacing=3f)) { 90+it.x }.overall
        val fine=calculate(mesh(surface(),spacing=0.5f)) { 90+it.x }.overall
        assertEquals(coarse.meanDb!!,fine.meanDb!!,1e-6)
        assertEquals(coarse.standardDeviationDb!!,fine.standardDeviationDb!!,1e-6)
        assertEquals(coarse.p10Db!!,fine.p10Db!!,1e-6); assertEquals(coarse.p95Db!!,fine.p95Db!!,1e-6)
    }
    @Test fun continuousTrianglePercentileMatchesQuadraticDistribution() {
        val p=listOf(SurfaceReceiver(0f,1.2f,0f,0f,"triangle"),SurfaceReceiver(2f,1.2f,0f,0f,"triangle"),
            SurfaceReceiver(0f,1.2f,2f,0f,"triangle"))
        val stats=CoverageStatisticsCalculator.calculate(PreparedAudienceGeometry(p,listOf(SurfaceTriangle(0,1,2))),
            floatArrayOf(0f,10f,10f)).overall
        assertEquals(20.0/3,stats.meanDb!!,1e-7)
        assertEquals(10*sqrt(0.5),stats.p50Db!!,1e-7)
        assertEquals(sqrt(100.0/18),stats.standardDeviationDb!!,1e-7)
    }
    @Test fun unequalOverlappingSurfacesWeightAreaAndKeepDuplicateNamesSeparate() {
        val stats=calculate(mesh(surface(0,1.0),surface(1,9.0,5f))) { if (it.areaId==0) 60f else 100f }
        assertEquals(96.0,stats.overall.meanDb!!,1e-6)
        assertEquals(12.0,stats.overall.standardDeviationDb!!,1e-6)
        assertEquals(2,stats.surfaces().size)
        assertEquals(setOf(0,1),stats.surfaces().map { it.areaId }.toSet())
        assertEquals(100.0,stats.overall.totalAreaM2,1e-6)
    }
    @Test fun rakedAreaUsesActualSurfaceSizeRatherThanHorizontalProjection() {
        val stats=calculate(mesh(surface(0),surface(1,height=5f,rake=60f))) { if (it.areaId==0) 60f else 90f }
        assertEquals(300.0,stats.overall.totalAreaM2,1e-4)
        assertEquals(80.0,stats.overall.meanDb!!,1e-5)
    }
    @Test fun independentExtremeProbeCannotBiasAreaStatistics() {
        val stats=calculate(mesh(surface(),probes=listOf(SurfaceReceiver(50f,2f,50f,2f,"probe:1")))) {
            if (it.surfaceKey.startsWith("probe")) 200f else 90f
        }
        assertEquals(90.0,stats.overall.meanDb!!,0.0)
        assertEquals(90.0,stats.overall.maxDb!!,0.0)
        assertEquals(1,stats.surfaces().size)
    }
    @Test fun missingTrianglesStayInDenominatorAndCannotPassCoverageTarget() {
        val stats=calculate(mesh(surface(0,5.0),surface(1,5.0,5f)),CoverageTarget(87.0,93.0)) {
            if (it.areaId==0) 90f else Float.NaN
        }
        assertEquals(50.0,stats.overall.validAreaM2,1e-5)
        assertEquals(50.0,stats.overall.missingAreaM2,1e-5)
        assertEquals(50.0,stats.overall.withinTargetPercent!!,1e-5)
        assertNull(stats.surfaces().first { it.areaId==1 }.statistics.meanDb)
    }
    @Test fun oneMissingVertexInvalidatesWholeTriangleAndFlatTargetsAreInclusive() {
        val m=PreparedAudienceGeometry(listOf(SurfaceReceiver(0f,1f,0f,0f,"a"),
            SurfaceReceiver(1f,1f,0f,0f,"a"),SurfaceReceiver(0f,1f,1f,0f,"a")),listOf(SurfaceTriangle(0,1,2)))
        val missing=CoverageStatisticsCalculator.calculate(m,floatArrayOf(90f,90f,Float.NaN)).overall
        assertEquals(0.5,missing.missingAreaM2,0.0); assertNull(missing.p95Db)
        val flat=CoverageStatisticsCalculator.calculate(m,floatArrayOf(90f,90f,90f),CoverageTarget(90.0,90.0)).overall
        assertEquals(100.0,flat.withinTargetPercent!!,0.0); assertEquals(0.0,flat.standardDeviationDb!!,0.0)
    }
    @Test fun emptyOrProbeOnlyGeometryHasNoAreaMetrics() {
        val stats=CoverageStatisticsCalculator.calculate(PreparedAudienceGeometry(
            listOf(SurfaceReceiver(0f,1f,0f,1f,"probe:1")),emptyList()),floatArrayOf(100f)).overall
        assertEquals(0.0,stats.totalAreaM2,0.0); assertNull(stats.meanDb); assertNull(stats.p50Db)
    }
    @Test fun statisticsCancellationReturnsNoPartialSummary() {
        val m=mesh(surface()); var calls=0
        assertFailsWith<IllegalStateException> {
            CoverageStatisticsCalculator.calculate(m,FloatArray(m.receiverCount) { 90f }) { if (++calls==5) error("cancelled") }
        }
    }
}
