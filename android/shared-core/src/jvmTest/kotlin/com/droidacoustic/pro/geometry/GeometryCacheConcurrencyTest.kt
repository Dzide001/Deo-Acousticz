package com.droidacoustic.pro.geometry

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.Assert.*

class GeometryCacheConcurrencyTest {
    @Test fun concurrentSameKeyBuildsReturnOneCanonicalMesh() {
        val cache=GeometryCache()
        val ready=CountDownLatch(2)
        val workers=Executors.newFixedThreadPool(2)
        try {
            val futures=(0..1).map { workers.submit<PreparedAudienceGeometry> {
                var checkpoints=0
                cache.prepare(CoveragePlan(emptyList(),emptyList(),8f,8f,1f)) {
                    // Both misses have happened; make the independent builds overlap deterministically.
                    if (++checkpoints==2) { ready.countDown(); check(ready.await(5,TimeUnit.SECONDS)) }
                }
            } }
            assertSame(futures[0].get(10,TimeUnit.SECONDS),futures[1].get(10,TimeUnit.SECONDS))
            assertEquals(1,cache.stats().entries)
            assertEquals(2L,cache.stats().misses)
        } finally { workers.shutdownNow() }
    }
}
