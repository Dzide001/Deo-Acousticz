package com.droidacoustic.pro.geometry

internal expect class GeometryCacheLock() {
    fun <T> withLock(block: () -> T): T
}

internal data class SurfaceGeometryKey(val id: Int, val name: String, val zone: String,
    val vertices: List<PlanPoint>, val height: Float, val rake: Float, val direction: Float,
    val holes: List<List<PlanPoint>>, val exclusions: List<List<PlanPoint>>,
    val vertexOffsetsM: List<Float>, val elevationPoints: List<AudienceElevationPoint>, val breaklines: List<AudienceBreakline>, val importedMesh: AudienceMesh?)
internal data class CoverageGeometryKey(val surfaces: List<SurfaceGeometryKey>, val probes: List<SurfaceReceiver>,
    val width: Float, val depth: Float, val spacing: Float, val maxReceivers: Int)
data class GeometryCacheStats(val entries: Int, val receivers: Int, val triangles: Int, val hits: Long, val misses: Long)

/** Thread-safe bounded LRU of immutable meshes. Keys include every input that affects geometry or
 * output labels; acoustic settings and request tickets are deliberately absent. Builds happen outside
 * the lock. Concurrent equal builds may duplicate work, but return one canonical cached value.
 */
class GeometryCache(private val maxEntries: Int = 3, private val maxReceivers: Int = 100000,
    private val maxTriangles: Int = 300000) {
    init { require(maxEntries > 0 && maxReceivers > 0 && maxTriangles > 0) }
    private val lock=GeometryCacheLock()
    private val entries=linkedMapOf<CoverageGeometryKey,PreparedAudienceGeometry>()
    private var receivers=0; private var triangles=0
    private var hits=0L; private var misses=0L; private var epoch=0L
    fun stats(): GeometryCacheStats = lock.withLock { GeometryCacheStats(entries.size,receivers,triangles,hits,misses) }
    fun clear() = lock.withLock { entries.clear(); receivers=0; triangles=0; epoch++ }
    fun prepare(plan: CoveragePlan, checkCancellation: () -> Unit = {}): PreparedAudienceGeometry {
        checkCancellation()
        val key=plan.cacheKey()
        val (found,generation)=lock.withLock {
            val found=entries.remove(key)
            if (found != null) { entries[key]=found; hits++ } else misses++
            found to epoch
        }
        if (found != null) { checkCancellation(); return found }
        val prepared=plan.prepare(checkCancellation)
        checkCancellation()
        return lock.withLock {
            if (generation != epoch || prepared.receiverCount > maxReceivers || prepared.triangleCount > maxTriangles) prepared
            else {
                val existing=entries.remove(key)
                if (existing != null) { entries[key]=existing; existing }
                else {
                    while (entries.isNotEmpty() && (entries.size >= maxEntries ||
                        receivers+prepared.receiverCount > maxReceivers || triangles+prepared.triangleCount > maxTriangles)) {
                        val first=entries.keys.first(); val removed=entries.remove(first)!!
                        receivers-=removed.receiverCount; triangles-=removed.triangleCount
                    }
                    entries[key]=prepared; receivers+=prepared.receiverCount; triangles+=prepared.triangleCount
                    prepared
                }
            }
        }
    }
}
