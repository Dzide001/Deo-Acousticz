package com.droidacoustic.pro.geometry

import kotlin.test.*

class GeometryCacheTest {
    private fun plan(id: Int=0, height: Float=0f, name: String="Zone", zone: String="AUDIENCE_SEATED",
        rake: Float=0f, direction: Float=0f, width: Double=4.0, spacing: Float=2f,
        venueWidth: Float=28f, probes: List<SurfaceReceiver> = emptyList(), budget: Int=50000) =
        CoveragePlan(listOf(AudienceSurface(id,name,zone,listOf(PlanPoint(0.0,0.0),PlanPoint(width,0.0),
            PlanPoint(width,4.0),PlanPoint(0.0,4.0)),height,rake,direction)),probes,venueWidth,28f,spacing,budget)
    @Test fun structuralKeysReuseEquivalentSnapshotsAndReturnImmutableMesh() {
        val cache=GeometryCache()
        val first=cache.prepare(plan()); val second=cache.prepare(plan())
        assertSame(first,second); assertEquals(1L,cache.stats().hits); assertEquals(1L,cache.stats().misses)
        (first.receivers() as MutableList<SurfaceReceiver>).clear()
        assertTrue(cache.prepare(plan()).receiverCount > 0)
    }
    @Test fun allGeometryAndMetadataChangesMissTheCache() {
        val cache=GeometryCache(maxEntries=20)
        val original=cache.prepare(plan())
        listOf(plan(id=1),plan(height=5f),plan(name="Renamed"),plan(zone="AUDIENCE_STANDING"),
            plan(rake=10f),plan(direction=90f),plan(width=5.0),plan(spacing=1f),plan(venueWidth=60f),
            plan(probes=listOf(SurfaceReceiver(0f,5f,0f,5f,"probe:1"))),plan(budget=1000)).forEach {
            assertNotSame(original,cache.prepare(it))
        }
        assertEquals(12L,cache.stats().misses)
    }
    @Test fun lruEvictsLeastRecentlyUsedAndStaysWithinCountBudgets() {
        val cache=GeometryCache(maxEntries=2)
        val a=cache.prepare(plan(0)); val b=cache.prepare(plan(1))
        assertSame(a,cache.prepare(plan(0)))
        cache.prepare(plan(2))
        assertEquals(2,cache.stats().entries)
        assertNotSame(b,cache.prepare(plan(1)))
        val bounded=GeometryCache(maxEntries=10,maxReceivers=a.receiverCount,maxTriangles=a.triangleCount)
        bounded.prepare(plan(0)); bounded.prepare(plan(1))
        assertEquals(1,bounded.stats().entries)
        assertTrue(bounded.stats().receivers <= a.receiverCount)
        assertTrue(bounded.stats().triangles <= a.triangleCount)
    }
    @Test fun oversizedMeshIsUsableButNotRetained() {
        val cache=GeometryCache(maxReceivers=1,maxTriangles=1)
        assertTrue(cache.prepare(plan()).receiverCount>1)
        assertEquals(0,cache.stats().entries)
    }
    @Test fun cancelledAndInvalidBuildsDoNotPopulateCacheAndHitsStillCheckCancellation() {
        val cache=GeometryCache(); var calls=0
        assertFailsWith<IllegalStateException> { cache.prepare(plan()) { if (++calls==3) error("cancelled") } }
        assertEquals(0,cache.stats().entries)
        assertFailsWith<IllegalArgumentException> { cache.prepare(plan(width=0.0)) }
        assertEquals(0,cache.stats().entries)
        cache.prepare(plan())
        assertFailsWith<IllegalStateException> { cache.prepare(plan()) { error("cancelled") } }
    }
    @Test fun clearingDuringBuildPreventsOldWorkFromRepopulatingCache() {
        val cache=GeometryCache(); var calls=0
        cache.prepare(plan()) { if (++calls==3) cache.clear() }
        assertEquals(0,cache.stats().entries)
        cache.prepare(plan()); assertEquals(1,cache.stats().entries)
        cache.clear(); assertEquals(0,cache.stats().receivers); assertEquals(0,cache.stats().triangles)
    }
}
