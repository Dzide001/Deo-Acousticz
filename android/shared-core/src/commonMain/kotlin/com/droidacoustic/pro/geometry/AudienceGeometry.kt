package com.droidacoustic.pro.geometry

import kotlin.math.*

data class PlanPoint(val x: Double, val z: Double)
data class SurfaceTriangle(val a: Int, val b: Int, val c: Int)
data class SurfaceReceiver(
    val x: Float, val y: Float, val z: Float, val surfaceY: Float,
    val surfaceKey: String, val areaId: Int? = null, val name: String? = null,
    val zoneType: String? = null, val rakeDeg: Float = 0f, val rakeDirectionDeg: Float = 0f
)

/** World-space polygon. Rake direction is clockwise from +Z; base height is at the vertex mean.
 * The vertex-mean anchor preserves existing projects' plane definition.
 */
class AudienceSurface(val id: Int, val name: String, val zoneType: String,
    vertices: List<PlanPoint>, val baseHeightM: Float = 0f,
    val rakeDeg: Float = 0f, val rakeDirectionDeg: Float = 0f,
    holes: List<List<PlanPoint>> = emptyList(), exclusions: List<List<PlanPoint>> = emptyList(),
    vertexOffsetsM: List<Float> = emptyList(), elevationPoints: List<AudienceElevationPoint> = emptyList(),
    breaklines: List<AudienceBreakline> = emptyList(), val importedMesh: AudienceMesh? = null) {
    private val outline = importedMesh?.boundary() ?: vertices.toList()
    private val offsets = vertexOffsetsM.toList()
    private val controls = elevationPoints.toList()
    private val constraints = breaklines.toList()
    fun breaklines(): List<AudienceBreakline> = constraints.toList()
    fun vertexPosition(ref: AudienceVertexRef): PlanPoint = when (ref.kind) {
        AudienceVertexKind.BOUNDARY -> outline.getOrNull(ref.index)
        AudienceVertexKind.INTERIOR -> controls.getOrNull(ref.index)?.let { PlanPoint(it.x,it.z) }
    } ?: throw IllegalArgumentException("Invalid breakline endpoint")
    /** A junction may split a declared segment without being one of its declared endpoints. */
    fun breaklinesAfterRemovingInterior(index: Int): List<AudienceBreakline> {
        require(index in controls.indices) { "Invalid interior point" }
        val points=outline+controls.map { PlanPoint(it.x,it.z) }
        val removed=outline.size+index
        return constraints.filter { line ->
            removed !in breaklineChain(points,vertexPosition(line.start),vertexPosition(line.end)) {}
        }.mapNotNull { it.afterRemovingInterior(index) }
    }
    val isNonPlanar: Boolean get() = importedMesh!=null || offsets.isNotEmpty() || controls.isNotEmpty() || constraints.isNotEmpty()
    fun vertexOffsetsM(): List<Float> = offsets.toList()
    fun elevationPoints(): List<AudienceElevationPoint> = controls.toList()
    internal fun elevationMesh(check: () -> Unit): SurfaceElevationMesh {
        check()
        require(importedMesh==null) { "Imported geometry uses the 3D mesh path" }
        return SurfaceElevationMesh.build(outline,offsets,controls,constraints,check)
    }
    private val lookupMesh by lazy { elevationMesh {} }
    fun traceSegment(a: PlanPoint,b: PlanPoint): List<AudienceLineSegment> = if (importedMesh==null)
        lookupMesh.trace(a,b,::planeY) else ImportedSurfaceGeometry.trace(this,a,b)
    /** Visible constraints follow the triangulated height field and stop at cutouts. */
    fun breaklineSegments(): List<AudienceLineSegment> = visibleSegments(constraints.flatMap { line ->
        val points=lookupMesh.points
        val chain=breaklineChain(points,vertexPosition(line.start),vertexPosition(line.end)) {}
        chain.zipWithNext().flatMap { (a,b) -> traceSegment(points[a],points[b]) }
    })
    /** Original imported edges, with the same cutout clipping as authored breaklines. */
    fun importedEdgeSegments(faceIndices: Collection<Int>? = null,clipCutouts: Boolean = true): List<AudienceLineSegment> {
        if (importedMesh==null) return emptyList()
        val nodes=importedMesh.vertices()
        val triangles=importedMesh.triangles()
        val selection=faceIndices?.toSet() ?: triangles.indices.toSet()
        require(selection.all {it in triangles.indices}) { "Invalid imported face selection" }
        val edges=selection.flatMap {i -> val t=triangles[i]; listOf(t.a to t.b,t.b to t.c,t.c to t.a) }
            .map { min(it.first,it.second) to max(it.first,it.second) }.distinct()
        val segments=edges.map { (a,b) -> val p=PlanPoint(nodes[a].x,nodes[a].z); val q=PlanPoint(nodes[b].x,nodes[b].z)
            AudienceLineSegment(p.x.toFloat(),(planeY(p)+nodes[a].y).toFloat(),p.z.toFloat(),
                q.x.toFloat(),(planeY(q)+nodes[b].y).toFloat(),q.z.toFloat()) }
        return if (clipCutouts) visibleSegments(segments) else segments
    }
    private fun visibleSegments(segments: List<AudienceLineSegment>): List<AudienceLineSegment> =
        segments.flatMap segments@ { segment ->
            val a=PlanPoint(segment.x0.toDouble(),segment.z0.toDouble())
            val b=PlanPoint(segment.x1.toDouble(),segment.z1.toDouble())
            val dx=b.x-a.x; val dz=b.z-a.z; val length2=dx*dx+dz*dz
            if (length2<1e-20) return@segments if (isExcludedPoint(segment.x0,segment.z0)) emptyList() else listOf(segment)
            val cuts=mutableListOf(0.0,1.0)
            for (ring in innerRings+excludedRings) for (i in ring.indices) {
                val c=ring[i]; val d=ring[(i+1)%ring.size]
                val ex=d.x-c.x; val ez=d.z-c.z; val denominator=dx*ez-dz*ex
                if (abs(denominator)>1e-12) {
                    val t=((c.x-a.x)*ez-(c.z-a.z)*ex)/denominator
                    val u=((c.x-a.x)*dz-(c.z-a.z)*dx)/denominator
                    if (t>0 && t<1 && u>=0 && u<=1) cuts+=t
                } else {
                    for (p in listOf(c,d)) if (PolygonGeometry.onSegment(a,b,p))
                        cuts+=(((p.x-a.x)*dx+(p.z-a.z)*dz)/length2).coerceIn(0.0,1.0)
                }
            }
            cuts.distinct().sorted().zipWithNext().mapNotNull { (t,u) ->
                val mid=(t+u)/2
                if (u-t<1e-10 || isExcludedPoint((a.x+mid*dx).toFloat(),(a.z+mid*dz).toFloat())) null
                else AudienceLineSegment((a.x+t*dx).toFloat(),(segment.y0+t*(segment.y1-segment.y0)).toFloat(),
                    (a.z+t*dz).toFloat(),(a.x+u*dx).toFloat(),(segment.y0+u*(segment.y1-segment.y0)).toFloat(),(a.z+u*dz).toFloat())
            }
        }
    fun validateGeometry(checkCancellation: () -> Unit = {}) {
        require(baseHeightM.isFinite() && rakeDeg.isFinite() && abs(rakeDeg)<89 && rakeDirectionDeg.isFinite()) {
            "Invalid audience base plane"
        }
        if (importedMesh==null) elevationMesh(checkCancellation) else {
            checkCancellation()
            require(offsets.isEmpty() && controls.isEmpty() && constraints.isEmpty()) { "Imported faces cannot be combined with height controls or breaklines" }
        }
        validateCutouts(checkCancellation)
    }
    private val innerRings = holes.map { it.toList() }
    private val excludedRings = exclusions.map { it.toList() }
    fun holes(): List<List<PlanPoint>> = innerRings.map { it.toList() }
    fun exclusions(): List<List<PlanPoint>> = excludedRings.map { it.toList() }
    /** Validation without tessellation, for editors and project preflight. */
    fun validateCutouts(checkCancellation: () -> Unit = {}) {
        if (importedMesh==null) AudienceCutouts.prepare(this,PolygonGeometry.normalized(outline,checkCancellation),checkCancellation)
        else AudienceCutouts.prepareImported(this,checkCancellation)
    }
    fun vertices(): List<PlanPoint> = outline.toList()
    private val centerX = if (outline.isEmpty()) 0.0 else outline.map { it.x }.average()
    private val centerZ = if (outline.isEmpty()) 0.0 else outline.map { it.z }.average()
    private val direction = rakeDirectionDeg * PI / 180
    private val gradientX = tan(rakeDeg * PI / 180) * sin(direction)
    private val gradientZ = tan(rakeDeg * PI / 180) * cos(direction)
    val earOffset: Float get() = if (zoneType == "AUDIENCE_STANDING") 1.6f else 1.2f
    val isAudience: Boolean get() = zoneType == "AUDIENCE_SEATED" || zoneType == "AUDIENCE_STANDING" ||
        importedMesh?.faceAssignments()?.any {it.zoneType(zoneType)!=null}==true
    internal fun planeY(p: PlanPoint): Double = baseHeightM + gradientX*(p.x-centerX) + gradientZ*(p.z-centerZ)
    internal fun surfaceY(mesh: SurfaceElevationMesh,face: SurfaceTriangle,p: PlanPoint): Float =
        (planeY(p)+mesh.offsetAt(face,p)).toFloat()
    /** A scalar height is unavailable at overlapping layers or a vertical facet. */
    fun surfaceY(x: Float, z: Float): Float {
        if (importedMesh!=null) return ImportedSurfaceGeometry.heights(this,x,z)?.singleOrNull() ?: Float.NaN
        val p=PlanPoint(x.toDouble(),z.toDouble())
        if (!isNonPlanar) return planeY(p).toFloat()
        val face=lookupMesh.faceAt(p) ?: return Float.NaN
        return surfaceY(lookupMesh,face,p)
    }
    fun elevationOffsetAt(x: Float,z: Float): Float = surfaceY(x,z)-planeY(PlanPoint(x.toDouble(),z.toDouble())).toFloat()
    fun receiverY(x: Float, z: Float): Float = if (importedMesh==null) surfaceY(x,z)+earOffset
        else ImportedSurfaceGeometry.heights(this,x,z,receiver=true)?.singleOrNull() ?: Float.NaN
    /** Editor hit testing keeps fully excluded zones selectable so cutouts can be removed. */
    fun containsOutlinePoint(x: Float, z: Float): Boolean = if (importedMesh!=null)
        ImportedSurfaceGeometry.contains(importedMesh,x,z,pickTolerance=0.15)
        else PolygonGeometry.contains(outline,PlanPoint(x.toDouble(),z.toDouble()))
    /** Plan membership for auxiliary sampling. Cutout boundaries are excluded here;
     * coverage meshes retain boundary vertices solely to close the remaining triangles. */
    private fun isExcludedPoint(x: Float,z: Float): Boolean {
        val point=PlanPoint(x.toDouble(),z.toDouble())
        return (innerRings+excludedRings).any { PolygonGeometry.contains(it,point) }
    }
    fun containsAudiencePoint(x: Float, z: Float): Boolean = !isExcludedPoint(x,z) &&
        if (importedMesh!=null) ImportedSurfaceGeometry.contains(importedMesh,x,z) {importedMesh.faceAssignment(it).zoneType(zoneType)!=null}
        else PolygonGeometry.contains(outline,PlanPoint(x.toDouble(),z.toDouble()))

}

data class AudienceSurfaceIdentity(val surfaceKey: String, val areaId: Int?, val name: String?)

class PreparedAudienceGeometry(receivers: List<SurfaceReceiver>, triangles: List<SurfaceTriangle>,
    surfaces: List<AudienceSurfaceIdentity> = triangles.map { receivers[it.a] }
        .distinctBy { it.surfaceKey }.map { AudienceSurfaceIdentity(it.surfaceKey,it.areaId,it.name) }) {
    private val identities = surfaces.toList()
    fun surfaces(): List<AudienceSurfaceIdentity> = identities.toList()
    private val points = receivers.toList()
    private val faces = triangles.toList()
    val receiverCount get() = points.size
    val triangleCount get() = faces.size
    fun receiver(index: Int) = points[index]
    fun receivers(): List<SurfaceReceiver> = points.toList()
    fun triangles(): List<SurfaceTriangle> = faces.toList()
}

/** Immutable shared preprocessing: bounded world-space grid clipped to each triangulated polygon.
 * Audience surfaces replace the preview floor; explicit probes are added independently.
 */
class CoveragePlan(surfaces: List<AudienceSurface>, probes: List<SurfaceReceiver>,
    private val venueWidthM: Float, private val venueDepthM: Float, private val spacingM: Float,
    private val maxReceivers: Int = 50000) {
    private val surfaces = surfaces.toList()
    private val probes = probes.toList()
    internal fun cacheKey() = CoverageGeometryKey(surfaces.map { SurfaceGeometryKey(it.id,it.name,it.zoneType,
        it.vertices(),it.baseHeightM,it.rakeDeg,it.rakeDirectionDeg,it.holes(),it.exclusions(),it.vertexOffsetsM(),it.elevationPoints(),it.breaklines(),it.importedMesh) },probes.toList(),venueWidthM,venueDepthM,spacingM,maxReceivers)
    fun prepare(checkCancellation: () -> Unit = {}): PreparedAudienceGeometry {
        require(spacingM.isFinite() && spacingM >= 0.1f)
        require(venueWidthM.isFinite() && venueWidthM > 0 && venueDepthM.isFinite() && venueDepthM > 0)
        require(surfaces.map { it.id }.distinct().size == surfaces.size) { "Duplicate audience surface identifiers" }
        val selected = surfaces.filter { it.isAudience || it.importedMesh!=null }
        val active = if (selected.isNotEmpty()) selected else listOf(AudienceSurface(-1, "Venue floor", "AUDIENCE_SEATED",
            listOf(PlanPoint(-venueWidthM/2.0, -venueDepthM/2.0), PlanPoint(venueWidthM/2.0, -venueDepthM/2.0),
                PlanPoint(venueWidthM/2.0, venueDepthM/2.0), PlanPoint(-venueWidthM/2.0, venueDepthM/2.0))))
        val points = mutableListOf<SurfaceReceiver>()
        val identities = mutableListOf<AudienceSurfaceIdentity>()
        val faces = mutableListOf<SurfaceTriangle>()
        var visitedCells = 0
        var cutoutWork = 0
        var importedWork = 0
        for (surface in active) {
            checkCancellation()
            require(surface.baseHeightM.isFinite() && surface.rakeDeg.isFinite() && abs(surface.rakeDeg) < 89 && surface.rakeDirectionDeg.isFinite()) { "Invalid audience plane ${surface.name}" }
            if (surface.importedMesh!=null) {
                surface.validateGeometry(checkCancellation)
                val prepared=ImportedSurfaceGeometry.prepare(surface,spacingM,maxReceivers-points.size) {
                    checkCancellation(); require(++importedWork<=1000000) { "Imported coverage exceeds work budget" }
                }
                val offset=points.size
                require(faces.size+prepared.triangleCount<=150000) { "Audience triangle budget exceeded" }
                points+=prepared.receivers()
                identities+=prepared.surfaces()
                faces+=prepared.triangles().map { SurfaceTriangle(it.a+offset,it.b+offset,it.c+offset) }
                continue
            }
            val identityId=if (surface.id == -1 && selected.isEmpty()) null else surface.id
            identities+=AudienceSurfaceIdentity(if (identityId==null) "venue" else "area:$identityId",identityId,surface.name)
            val polygon = PolygonGeometry.normalized(surface.vertices(), checkCancellation)
            val elevation = surface.elevationMesh(checkCancellation)
            val triangles = elevation.triangles
            val cutters = AudienceCutouts.prepare(surface,polygon,checkCancellation)
            val vertexIndex = mutableMapOf<Pair<Long,Long>, Int>()
            fun index(p: PlanPoint,face: SurfaceTriangle): Int = vertexIndex.getOrPut((p.x*1e5).roundToLong() to (p.z*1e5).roundToLong()) {
                require(points.size < maxReceivers) { "Audience mesh exceeds $maxReceivers receivers; increase grid spacing" }
                val x = p.x.toFloat(); val z = p.z.toFloat()
                val area = if (surface.id == -1 && selected.isEmpty()) null else surface.id
                val y = surface.surfaceY(elevation,face,p)
                points += SurfaceReceiver(x, y+surface.earOffset, z, y,
                    if (area == null) "venue" else "area:$area", area, surface.name, surface.zoneType,
                    surface.rakeDeg, surface.rakeDirectionDeg)
                points.lastIndex
            }
            for (tri in triangles) {
                val base = listOf(elevation.points[tri.a], elevation.points[tri.b], elevation.points[tri.c])
                val x0 = floor(base.minOf { it.x } / spacingM).toInt()
                val x1 = ceil(base.maxOf { it.x } / spacingM).toInt()
                val z0 = floor(base.minOf { it.z } / spacingM).toInt()
                val z1 = ceil(base.maxOf { it.z } / spacingM).toInt()
                require((x1.toLong()-x0)*(z1.toLong()-z0) <= 500000) { "Audience extent exceeds mesh budget" }
                for (iz in z0 until z1) for (ix in x0 until x1) {
                    checkCancellation()
                    require(++visitedCells <= 1000000) { "Audience tessellation exceeds mesh budget" }
                    var clipped = base
                    val left = ix*spacingM.toDouble(); val bottom = iz*spacingM.toDouble()
                    clipped = clip(clipped, true, left, true)
                    clipped = clip(clipped, true, left+spacingM, false)
                    clipped = clip(clipped, false, bottom, true)
                    clipped = clip(clipped, false, bottom+spacingM, false)
                    if (clipped.size < 3) continue
                    var pieces=listOf(clipped)
                    for (cutter in cutters) {
                        checkCancellation()
                        require(++cutoutWork <= 4000000) { "Audience cutout processing exceeds budget" }
                        if (pieces.isEmpty()) break
                        if (!cutter.overlaps(left,left+spacingM,bottom,bottom+spacingM)) continue
                        pieces=pieces.flatMap { piece ->
                            checkCancellation()
                            require(++cutoutWork <= 4000000) { "Audience cutout processing exceeds budget" }
                            AudienceCutouts.subtract(piece,cutter)
                        }
                        require(pieces.size <= 4096) { "Audience cutout fragmentation exceeds budget" }
                    }
                    for (piece in pieces) for (i in 1 until piece.lastIndex) {
                        if (abs(PolygonGeometry.cross(piece[0],piece[i],piece[i+1])) < 1e-9) continue
                        require(faces.size < 150000) { "Audience triangle budget exceeded" }
                        faces += SurfaceTriangle(index(piece[0],tri),index(piece[i],tri),index(piece[i+1],tri))
                    }
                }
            }
        }
        probes.forEach { probe ->
            checkCancellation()
            require(listOf(probe.x,probe.y,probe.z,probe.surfaceY).all { it.isFinite() }) { "Invalid audience probe" }
            require(points.size < maxReceivers) { "Audience receiver budget exceeded" }
            points += probe
        }
        return PreparedAudienceGeometry(points, faces, identities)
    }
    private fun clip(input: List<PlanPoint>, xAxis: Boolean, boundary: Double, keepGreater: Boolean): List<PlanPoint> {
        if (input.isEmpty()) return input
        fun coord(p: PlanPoint) = if (xAxis) p.x else p.z
        fun inside(p: PlanPoint) = if (keepGreater) coord(p) >= boundary-1e-10 else coord(p) <= boundary+1e-10
        val output = mutableListOf<PlanPoint>()
        var prev = input.last()
        for (next in input) {
            if (inside(prev) != inside(next)) {
                val t = (boundary-coord(prev))/(coord(next)-coord(prev))
                output += PlanPoint(prev.x+(next.x-prev.x)*t, prev.z+(next.z-prev.z)*t)
            }
            if (inside(next)) output += next
            prev = next
        }
        return output
    }
}

object PolygonGeometry {
    internal fun cross(a: PlanPoint,b: PlanPoint,c: PlanPoint) = (b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x)
    internal fun onSegment(a: PlanPoint,b: PlanPoint,p: PlanPoint) = abs(cross(a,b,p)) < 1e-8 &&
        p.x >= min(a.x,b.x)-1e-8 && p.x <= max(a.x,b.x)+1e-8 && p.z >= min(a.z,b.z)-1e-8 && p.z <= max(a.z,b.z)+1e-8
    internal fun intersects(a: PlanPoint,b: PlanPoint,c: PlanPoint,d: PlanPoint): Boolean =
        (cross(a,b,c)*cross(a,b,d)<0 && cross(c,d,a)*cross(c,d,b)<0) ||
            onSegment(a,b,c) || onSegment(a,b,d) || onSegment(c,d,a) || onSegment(c,d,b)
    fun contains(polygon: List<PlanPoint>, p: PlanPoint): Boolean {
        var inside = false
        for (i in polygon.indices) {
            val a=polygon[i]; val b=polygon[(i+1)%polygon.size]
            if (onSegment(a,b,p)) return true
            if ((a.z>p.z)!=(b.z>p.z) && p.x < (b.x-a.x)*(p.z-a.z)/(b.z-a.z)+a.x) inside = !inside
        }
        return inside
    }
    fun normalized(vertices: List<PlanPoint>, checkCancellation: () -> Unit = {}, preserveCollinear: Boolean = false): List<PlanPoint> {
        require(vertices.size in 3..2048 && vertices.all { it.x.isFinite() && it.z.isFinite() && abs(it.x)<1e6 && abs(it.z)<1e6 }) { "Invalid audience polygon vertices" }
        val p=vertices.toMutableList()
        if (p.first()==p.last()) p.removeAt(p.lastIndex)
        var changed=true
        while (!preserveCollinear && changed && p.size>3) {
            checkCancellation()
            changed=false
            for (i in p.indices) if (onSegment(p[(i+p.size-1)%p.size],p[(i+1)%p.size],p[i])) {
                p.removeAt(i); changed=true; break
            }
        }
        require(p.size >= 3 && p.distinct().size == p.size) { "Duplicate polygon vertices" }
        for (i in p.indices) for (j in i+1 until p.size) {
            checkCancellation()
            if ((i+1)%p.size==j || (j+1)%p.size==i) continue
            require(!intersects(p[i],p[(i+1)%p.size],p[j],p[(j+1)%p.size])) { "Self-intersecting audience polygon" }
        }
        val area=p.indices.sumOf { i -> p[i].x*p[(i+1)%p.size].z-p[(i+1)%p.size].x*p[i].z }
        require(abs(area)>1e-8) { "Zero-area audience polygon" }
        return if (area>0) p else p.reversed()
    }
    internal fun triangulate(p: List<PlanPoint>, checkCancellation: () -> Unit = {}): List<SurfaceTriangle> {
        val remaining=p.indices.toMutableList(); val triangles=mutableListOf<SurfaceTriangle>()
        while (remaining.size>3) {
            val ear=remaining.indices.firstOrNull { i ->
                checkCancellation()
                val a=remaining[(i+remaining.size-1)%remaining.size]; val b=remaining[i]; val c=remaining[(i+1)%remaining.size]
                cross(p[a],p[b],p[c])>1e-10 && remaining.none { j -> j!=a && j!=b && j!=c &&
                    cross(p[a],p[b],p[j])>=-1e-10 && cross(p[b],p[c],p[j])>=-1e-10 && cross(p[c],p[a],p[j])>=-1e-10 }
            } ?: error("Audience polygon could not be triangulated")
            triangles += SurfaceTriangle(remaining[(ear+remaining.size-1)%remaining.size],remaining[ear],remaining[(ear+1)%remaining.size])
            remaining.removeAt(ear)
        }
        triangles += SurfaceTriangle(remaining[0],remaining[1],remaining[2])
        return triangles
    }
}
