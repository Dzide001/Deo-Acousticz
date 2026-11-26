package com.droidacoustic.pro.geometry

import kotlin.math.*
import kotlinx.serialization.json.*
import com.droidacoustic.pro.acoustics.SpeakerProvenance
import com.droidacoustic.pro.scene.VenueGeometry

data class BoundaryPoint(val x: Double,val y: Double,val z: Double) {
    internal fun point()=MeshPoint(x,y,z)
}
data class BoundaryReflection(val imageReceiver: BoundaryPoint,val bounce: BoundaryPoint,val materials: Set<BoundaryMaterial> = setOf(BoundaryMaterial.IDEAL_RIGID),val incidenceDeg: Float = 0f)
private fun MeshPoint.boundaryPoint()=BoundaryPoint(x,y,z)

/** Per-calculation budget; throws rather than publishing a partially evaluated boundary field. */
class BoundaryWork(private val checkCancellation: ()->Unit = {},private val limit: Int=20_000_000) {
    private var work=0L
    fun check() {
        checkCancellation()
        require(++work<=limit) {"Boundary prediction exceeds work budget; simplify boundaries or increase receiver spacing"}
    }
    fun check(units:Int) {
        require(units>=0)
        checkCancellation()
        work+=units.toLong()
        require(work<=limit) {"Boundary prediction exceeds work budget; simplify boundaries or increase receiver spacing"}
    }
}

/** Immutable physical facets and a bounding-volume tree. Audience exclusions are measurement masks,
 * never holes in a physical boundary. Only explicit roles participate; triangle winding is irrelevant.
 */
class BoundaryGeometry private constructor(private val facets: List<Facet>,private val tree: Node?) {
    private data class Facet(val a: MeshPoint,val b: MeshPoint,val c: MeshPoint,val role: AcousticBoundaryRole,val material: BoundaryMaterial) {
        val e1=b-a; val e2=c-a
        val normal=e1.cross(e2).let {it*(1/it.length())}
        val lo=MeshPoint(minOf(a.x,b.x,c.x),minOf(a.y,b.y,c.y),minOf(a.z,b.z,c.z))
        val hi=MeshPoint(maxOf(a.x,b.x,c.x),maxOf(a.y,b.y,c.y),maxOf(a.z,b.z,c.z))
        fun contains(p: MeshPoint): Boolean {
            val d=p-a; val area=e1.cross(e2).dot(normal)
            val u=d.cross(e2).dot(normal)/area
            val v=e1.cross(d).dot(normal)/area
            return u>=-1e-9 && v>=-1e-9 && u+v<=1+1e-9
        }
    }
    private class Node(val lo: MeshPoint,val hi: MeshPoint,val faces: List<Facet>,val left: Node?=null,val right: Node?=null)
    private val reflectors=facets.filter {it.role==AcousticBoundaryRole.RIGID_REFLECTOR}
    val isEmpty get()=facets.isEmpty()

    /** Conservative canonical facet/material identity, independent of display-only venue bounds. */
    fun comparisonFingerprint(checkCancellation: () -> Unit = {}): String {
        val rows=facets.map {f -> checkCancellation(); buildJsonObject {
            put("vertices",JsonArray(listOf(f.a,f.b,f.c).map {p -> buildJsonArray {add(p.x);add(p.y);add(p.z)}}))
            put("role",f.role.name);put("material",f.material.toJson())
        }.toString()}.sorted()
        return SpeakerProvenance.hash(JsonArray(rows.map(::JsonPrimitive)).toString().encodeToByteArray())
    }

    private val hasScattering=reflectors.any {it.material.scattering?.curves()?.any {(_,p)->p.any {it.second>0f}}==true}
    private val diffuseLock=GeometryCacheLock()
    private val diffuseCache=linkedMapOf<Pair<BoundaryPoint,Int>,List<DiffuseSurfaceSample>>()

    private data class DiffusePatch(val polygon: List<MeshPoint>,val normal: MeshPoint,val materials: Set<BoundaryMaterial>)
    private val patchCache=linkedMapOf<BoundaryPoint,List<DiffusePatch>>()
    private fun sourcePatches(source: BoundaryPoint,work: BoundaryWork): List<DiffusePatch> {
        work.check();diffuseLock.withLock {patchCache[source]}?.let {return it}
        val s=source.point();val result=mutableListOf<DiffusePatch>()
        // Canonical geometry order makes overlapping patch ownership independent of input order.
        val ordered=facets.sortedBy {f -> listOf(f.a,f.b,f.c).map {"${it.x},${it.y},${it.z}"}.sorted().joinToString(";")}
        for((index,f) in ordered.withIndex()) {
            work.check()
            val ds=(s-f.a).dot(f.normal)
            if(abs(ds)<=COINCIDENCE_M || f.role!=AcousticBoundaryRole.RIGID_REFLECTOR) continue
            // A non-scattering facet can still own a coincident scattering material's geometry.
            if(f.material.scattering==null && ordered.none {other ->
                work.check();other.material.scattering!=null && abs(other.normal.dot(f.normal))>1-1e-10 &&
                    abs((other.a-f.a).dot(f.normal))<COINCIDENCE_M
            }) continue
            var patches=listOf(listOf(f.a,f.b,f.c))
            for((otherIndex,other) in ordered.withIndex()) {
                work.check();if(index==otherIndex || patches.isEmpty()) continue
                val otherSide=(s-other.a).dot(other.normal)
                if(abs(otherSide)<=COINCIDENCE_M) continue
                val coplanar=abs(other.normal.dot(f.normal))>1-1e-10 && abs((other.a-f.a).dot(f.normal))<COINCIDENCE_M
                if(coplanar && otherIndex>index && other.role==AcousticBoundaryRole.RIGID_REFLECTOR && other.material==f.material) continue
                val vertices=listOf(other.a,other.b,other.c)
                val planes=mutableListOf<DiffuseQuadrature.Plane>()
                for(i in 0..2) {
                    var n=(vertices[i]-s).cross(vertices[(i+1)%3]-s)
                    val length=n.length();if(length<=1e-18) continue
                    n=n*(1/length)
                    if(n.dot(vertices[(i+2)%3]-s)<0) n=n*(-1.0)
                    planes+=DiffuseQuadrature.Plane(s,n)
                }
                if(planes.size!=3) continue
                if(!coplanar) planes+=DiffuseQuadrature.Plane(other.a,other.normal*(if(otherSide>0) -1.0 else 1.0))
                val next=mutableListOf<List<MeshPoint>>()
                for(patch in patches) {
                    val (inside,outside)=DiffuseQuadrature.partition(patch,planes,work)
                    next+=outside
                    // Coplanar later owners split material regions but do not remove this owner.
                    if(coplanar && otherIndex>index && other.role!=AcousticBoundaryRole.OCCLUDING && inside.isNotEmpty()) next+=inside
                    require(next.size<=32768) {"Diffuse overlap partition exceeds limit; simplify boundaries"}
                }
                patches=next
            }
            val normal=if(ds>0) f.normal else f.normal*(-1.0)
            for(patch in patches) {
                work.check()
                val centre=patch.reduce {a,b->a+b}*(1.0/patch.size)
                if(opaqueAt(centre,f.normal,work)) continue
                val materials=ordered.filter {other ->
                    work.check()
                    other.role==AcousticBoundaryRole.RIGID_REFLECTOR && abs(other.normal.dot(f.normal))>1-1e-10 &&
                        abs((centre-other.a).dot(other.normal))<COINCIDENCE_M && other.contains(centre)
                }.map {it.material}.toSet()
                if(materials.none {it.scattering!=null}) continue
                require(result.size<32768) {"Diffuse patch count exceeds limit"}
                result+=DiffusePatch(patch,normal,materials)
            }
        }
        work.check()
        return diffuseLock.withLock {
            patchCache[source] ?: run {
                while(patchCache.isNotEmpty() && (patchCache.size>=32 || patchCache.values.sumOf {it.size}+result.size>32768))
                    patchCache.remove(patchCache.keys.first())
                result.toList().also {patchCache[source]=it}
            }
        }
    }

    private val receiverPatchCache=linkedMapOf<Pair<BoundaryPoint,BoundaryPoint>,List<DiffusePatch>>()
    private fun receiverPatches(source: BoundaryPoint,receiver: BoundaryPoint,work: BoundaryWork): List<DiffusePatch> {
        work.check();val key=source to receiver
        diffuseLock.withLock {receiverPatchCache[key]}?.let {return it}
        val r=receiver.point();val result=mutableListOf<DiffusePatch>()
        for(patch in sourcePatches(source,work)) {
            work.check()
            if((r-patch.polygon[0]).dot(patch.normal)<=COINCIDENCE_M) continue
            var visible=listOf(patch.polygon)
            run {
                for(other in facets) {
                    work.check();if(visible.isEmpty()) break
                    val side=(r-other.a).dot(other.normal)
                    if(abs(side)<=COINCIDENCE_M) continue
                    // Endpoint facets, including coincident duplicates, are not occluders.
                    if(abs(other.normal.dot(patch.normal))>1-1e-10 && abs((other.a-patch.polygon[0]).dot(patch.normal))<COINCIDENCE_M) continue
                    val vertices=listOf(other.a,other.b,other.c);val planes=mutableListOf<DiffuseQuadrature.Plane>()
                    for(i in 0..2) {
                        var n=(vertices[i]-r).cross(vertices[(i+1)%3]-r);val length=n.length()
                        if(length<=1e-18) continue
                        n=n*(1/length);if(n.dot(vertices[(i+2)%3]-r)<0) n=n*(-1.0)
                        planes+=DiffuseQuadrature.Plane(r,n)
                    }
                    if(planes.size!=3) continue
                    planes+=DiffuseQuadrature.Plane(other.a,other.normal*(if(side>0) -1.0 else 1.0))
                    visible=visible.flatMap {polygon -> DiffuseQuadrature.partition(polygon,planes,work).second}
                    require(visible.size<=32768) {"Diffuse receiver-shadow partition exceeds limit"}
                }
            }
            visible.forEach {polygon ->
                require(result.size<32768) {"Diffuse receiver patch count exceeds limit"}
                result+=DiffusePatch(polygon,patch.normal,patch.materials)
            }
        }
        work.check()
        return diffuseLock.withLock {
            receiverPatchCache[key] ?: run {
                while(receiverPatchCache.isNotEmpty() && (receiverPatchCache.size>=32 || receiverPatchCache.values.sumOf {it.size}+result.size>32768))
                    receiverPatchCache.remove(receiverPatchCache.keys.first())
                result.toList().also {receiverPatchCache[key]=it}
            }
        }
    }

    /** Source-visible patches are cached independently of receivers and acoustic profiles.
     * Receiver shadows are clipped geometrically before receiver/profile-aware quadrature. */
    fun diffuseSamples(source: BoundaryPoint,work: BoundaryWork=BoundaryWork(),rayCount: Int=DiffuseRadiation.DEFAULT_RAYS,
        receiver: BoundaryPoint?=null,
        levelAt: ((BoundaryPoint,Set<BoundaryMaterial>,Float)->List<Float>)?=null,
        directivityToleranceDb: Float=1f,excludeGround: Boolean=false,
        variationBound: ((List<BoundaryPoint>)->Float?)?=null): List<DiffuseSurfaceSample> {
        require(rayCount in 128..32768 && rayCount%2==0) {"Diffuse resolution must be even, 128–32768"}
        require(directivityToleranceDb.isFinite() && directivityToleranceDb>0)
        work.check();if(!hasScattering) return emptyList()
        val key=source to rayCount;val cacheable=receiver==null && levelAt==null && variationBound==null && !excludeGround
        fun snapshot(samples: List<DiffuseSurfaceSample>)=samples.map {it.copy(materials=it.materials.toSet())}
        if(cacheable) diffuseLock.withLock {diffuseCache[key]}?.let {return snapshot(it)}
        val s=source.point();val r=receiver?.point();val result=mutableListOf<DiffuseSurfaceSample>()
        val visiblePatches=if(receiver==null) sourcePatches(source,work) else receiverPatches(source,receiver,work)
        for(patch in visiblePatches) {
            work.check()
            if(excludeGround && abs(patch.polygon[0].y)<COINCIDENCE_M && abs(patch.normal.y)>1-1e-10) continue
            var visible=listOf(patch.polygon)
            fun incidence(p: MeshPoint): Float {
                val d=p-s;return (acos((abs(d.dot(patch.normal))/d.length()).coerceIn(0.0,1.0))*180/PI).toFloat()
            }
            val profileValues=mutableMapOf<MeshPoint,List<Float>>()
            val profile: ((MeshPoint)->List<Float>)?=levelAt?.let {fn -> {p: MeshPoint ->
                work.check()
                profileValues[p] ?: fn(p.boundaryPoint(),patch.materials.toSet(),incidence(p)).also {
                    require(profileValues.size<262144) {"Diffuse directivity probe limit exceeded"}
                    profileValues[p]=it.toList()
                }
            }}
            for(polygon in visible) DiffuseQuadrature.integrate(polygon,s,4*PI/rayCount,work,r,profile,directivityToleranceDb,
                variationBound?.let {fn -> {points -> work.check();fn(points.map {it.boundaryPoint()})}}) {bounce,omega ->
                work.check();val distance=(bounce-s).length()
                require(result.size<65536) {"Diffuse sampling exceeds 65536 samples; simplify boundaries"}
                result+=DiffuseSurfaceSample(bounce.boundaryPoint(),patch.normal.boundaryPoint(),distance,omega,incidence(bounce),patch.materials.toSet())
            }
        }
        work.check()
        if(!cacheable) return result
        return diffuseLock.withLock {
            diffuseCache[key]?.let(::snapshot) ?: run {
                while(diffuseCache.isNotEmpty() && (diffuseCache.size>=32 || diffuseCache.values.sumOf {it.size}+result.size>65536))
                    diffuseCache.remove(diffuseCache.keys.first())
                diffuseCache[key]=snapshot(result)
                result.toList()
            }
        }
    }

    /** One receiver-visible quadrature shared by all emitters. Partition every source shadow before
     * sampling, so a blocked emitter cannot leak into an illuminated patch or cancel a visible one. */
    internal fun illuminationSamples(sources:List<BoundaryPoint>,receiver:BoundaryPoint,work:BoundaryWork,
        excludeGround:Boolean,profile:(BoundaryPoint,BoundaryPoint,List<Int>,Set<BoundaryMaterial>)->List<Float>,
        bound:(List<BoundaryPoint>,List<Int>,Set<BoundaryMaterial>)->Float):List<IlluminationSample> {
        data class Region(val polygon:List<MeshPoint>,val visible:List<Int>)
        work.check();if(!hasScattering) return emptyList()
        val result=mutableListOf<IlluminationSample>();val r=receiver.point()
        for(patch in sourcePatches(receiver,work)) {
            work.check()
            if(excludeGround && abs(patch.polygon[0].y)<COINCIDENCE_M && abs(patch.normal.y)>1-1e-10) continue
            var regions=listOf(Region(patch.polygon,sources.indices.filter {(sources[it].point()-patch.polygon[0]).dot(patch.normal)>COINCIDENCE_M}))
            for(index in sources.indices) {
                val origin=sources[index].point()
                for(other in facets) {
                    work.check()
                    val side=(origin-other.a).dot(other.normal)
                    if(abs(side)<=COINCIDENCE_M) continue
                    if(abs(other.normal.dot(patch.normal))>1-1e-10 && abs((other.a-patch.polygon[0]).dot(patch.normal))<COINCIDENCE_M) continue
                    val vertices=listOf(other.a,other.b,other.c);val planes=mutableListOf<DiffuseQuadrature.Plane>()
                    for(i in 0..2) {
                        var n=(vertices[i]-origin).cross(vertices[(i+1)%3]-origin);val length=n.length()
                        if(length<=1e-18) continue
                        n=n*(1/length);if(n.dot(vertices[(i+2)%3]-origin)<0) n=n*(-1.0)
                        planes+=DiffuseQuadrature.Plane(origin,n)
                    }
                    if(planes.size!=3) continue
                    planes+=DiffuseQuadrature.Plane(other.a,other.normal*(if(side>0) -1.0 else 1.0))
                    regions=regions.flatMap {region ->
                        if(index !in region.visible) listOf(region) else {
                            val (inside,outside)=DiffuseQuadrature.partition(region.polygon,planes,work)
                            outside.map {Region(it,region.visible)} + if(inside.isEmpty()) emptyList() else listOf(Region(inside,region.visible-index))
                        }
                    }
                    require(regions.size<=32768) {"Coherent illumination shadow partition exceeds limit"}
                }
            }
            for(region in regions.filter {it.visible.isNotEmpty()}) {
                DiffuseQuadrature.integrate(region.polygon,r,4*PI/DiffuseRadiation.DEFAULT_RAYS,work,
                    profile={p->profile(p.boundaryPoint(),patch.normal.boundaryPoint(),region.visible,patch.materials)},
                    variationBound={points->bound(points.map {it.boundaryPoint()},region.visible,patch.materials)},highOrder=true,canonicalRefinement=true,refinementOrigins=region.visible.map {sources[it].point()}) {p,omega ->
                    work.check();require(result.size<262144) {"Coherent illumination exceeds 262144 samples"}
                    val distance=(p-r).length()
                    result+=IlluminationSample(p.boundaryPoint(),patch.normal.boundaryPoint(),distance,
                        omega/PI*(distance/max(distance,0.1)).pow(2),region.visible.toIntArray(),patch.materials.toSet())
                }
            }
        }
        return result
    }

    /** Two-sided segment intersections, excluding 1 micrometre at each endpoint. Coplanar paths pass. */
    /** A scattering curve has no coherent diffuse phase model, even when its specular part is known. */
    fun hasScatteringModel(work:BoundaryWork=BoundaryWork(),groundOwnsPlane:Boolean=false):Boolean = facets.any {
        work.check();it.role==AcousticBoundaryRole.RIGID_REFLECTOR && it.material.scattering!=null &&
            !(groundOwnsPlane && kotlin.math.abs(it.a.y)<1e-10 && kotlin.math.abs(it.b.y)<1e-10 && kotlin.math.abs(it.c.y)<1e-10)
    }

    fun blocked(from: BoundaryPoint,to: BoundaryPoint,work: BoundaryWork=BoundaryWork()): Boolean {
        val a=from.point(); val delta=to.point()-a; val distance=delta.length()
        if (distance<=2e-6) return false
        val d=delta*(1/distance)
        fun overlaps(node: Node): Boolean {
            var near=1e-6; var far=distance-1e-6
            for (axis in 0..2) {
                val origin=a.coordinate(axis); val direction=d.coordinate(axis)
                val lo=node.lo.coordinate(axis)-1e-8; val hi=node.hi.coordinate(axis)+1e-8
                if (abs(direction)<1e-15) {if (origin<lo || origin>hi) return false}
                else {
                    val x=(lo-origin)/direction; val y=(hi-origin)/direction
                    near=max(near,min(x,y)); far=min(far,max(x,y)); if (near>far) return false
                }
            }
            return true
        }
        fun hit(node: Node): Boolean {
            work.check(); if (!overlaps(node)) return false
            for (f in node.faces) {
                work.check(); val denominator=d.dot(f.normal)
                if (abs(denominator)<1e-12) continue
                val t=(f.a-a).dot(f.normal)/denominator
                if (t>1e-6 && t<distance-1e-6 && f.contains(a+d*t)) return true
            }
            return node.left?.let(::hit)==true || node.right?.let(::hit)==true
        }
        return tree?.let(::hit) ?: false
    }

    /** Valid first-order image paths: same side, finite bounce inside facet, both legs clear.
     * Duplicate paths at tessellation edges or coincident facets are emitted only once.
     */
    fun reflections(source: BoundaryPoint,receiver: BoundaryPoint,work: BoundaryWork=BoundaryWork()): List<BoundaryReflection> {
        val s=source.point(); val r=receiver.point(); val result=mutableListOf<BoundaryReflection>()
        val seen=mutableListOf<MeshPoint>()
        for (f in reflectors) {
            work.check()
            val ds=(s-f.a).dot(f.normal); val dr=(r-f.a).dot(f.normal)
            if (abs(ds)<=1e-6 || abs(dr)<=1e-6 || ds*dr<=0) continue
            val image=r-f.normal*(2*dr)
            val bounce=s+(image-s)*(ds/(ds+dr))
            if (!f.contains(bounce)) continue
            if (seen.any {(it-image).length()<COINCIDENCE_M}) continue
            val point=bounce.boundaryPoint()
            if (opaqueAt(bounce,f.normal,work)) continue
            if (blocked(source,point,work) || blocked(point,receiver,work)) continue
            require(result.size<128) {"More than 128 visible boundary reflections for one emitter/receiver"}
            // Conservative, order-independent overlap policy: lowest specular reflected energy per band.
            val materials=facets.filter {other ->
                work.check()
                other.role==AcousticBoundaryRole.RIGID_REFLECTOR && abs(other.normal.dot(f.normal))>1-1e-10 &&
                    abs((bounce-other.a).dot(other.normal))<COINCIDENCE_M && other.contains(bounce)
            }.map {it.material}.toSet()
            seen+=image; result+=BoundaryReflection(image.boundaryPoint(),point,materials,
                (acos((abs((s-bounce).dot(f.normal))/(s-bounce).length()).coerceIn(0.0,1.0))*180.0/PI).toFloat())
        }
        return result
    }
    /** Opaque wins only at a coincident coplanar bounce. Order, source kind and tessellation do not matter. */
    private fun opaqueAt(p: MeshPoint,normal: MeshPoint,work: BoundaryWork): Boolean {
        fun visit(node: Node): Boolean {
            work.check()
            if ((0..2).any {p.coordinate(it)<node.lo.coordinate(it)-COINCIDENCE_M || p.coordinate(it)>node.hi.coordinate(it)+COINCIDENCE_M}) return false
            for (f in node.faces) {
                work.check()
                if (f.role==AcousticBoundaryRole.OCCLUDING && abs(f.normal.dot(normal))>1-1e-10 &&
                    abs((p-f.a).dot(f.normal))<COINCIDENCE_M && f.contains(p)) return true
            }
            return node.left?.let(::visit)==true || node.right?.let(::visit)==true
        }
        return tree?.let(::visit) ?: false
    }
    fun suppressesGroundBounce(bounce: BoundaryPoint,work: BoundaryWork=BoundaryWork()): Boolean =
        opaqueAt(bounce.point(),MeshPoint(0.0,1.0,0.0),work)
    companion object {
        // One micrometre, matching endpoint exclusion and accommodating Float mesh heights.
        private const val COINCIDENCE_M=1e-6
        fun prepare(surfaces: List<AudienceSurface>,work: BoundaryWork=BoundaryWork(),venue: VenueGeometry?=null): BoundaryGeometry {
            val facets=mutableListOf<Facet>()
            fun append(a: MeshPoint,b: MeshPoint,c: MeshPoint,role: AcousticBoundaryRole,material: BoundaryMaterial) {
                work.check()
                if (role==AcousticBoundaryRole.NONE) return
                require(facets.size<32768) {"Too many acoustic boundary faces"}
                facets+=Facet(a,b,c,role,material)
            }
            fun append(shape: BoundaryShape,role: AcousticBoundaryRole,material: BoundaryMaterial) {
                shape.triangles.forEach {t -> append(shape.vertices[t.a].point(),shape.vertices[t.b].point(),shape.vertices[t.c].point(),role,material)}
            }
            if (venue!=null) {
                work.check()
                if (venue.stageBoundaryRole!=AcousticBoundaryRole.NONE) append(VenueBoundaryGeometry.stage(venue),venue.stageBoundaryRole,venue.stageBoundaryMaterial)
                for (block in venue.blocks) {
                    work.check()
                    if (block.boundaryRole!=AcousticBoundaryRole.NONE) append(VenueBoundaryGeometry.block(block),block.boundaryRole,block.boundaryMaterial)
                }
            }
            for (surface in surfaces) {
                work.check(); val mesh=surface.importedMesh ?: continue
                if (mesh.boundaryRoles().all {it==AcousticBoundaryRole.NONE}) continue
                require(surface.baseHeightM.isFinite() && surface.rakeDeg.isFinite() && abs(surface.rakeDeg)<89 &&
                    surface.rakeDirectionDeg.isFinite()) {"Invalid acoustic boundary base plane"}
                val points=ImportedSurfaceGeometry.points(surface)
                mesh.triangles().forEachIndexed {i,t ->
                    work.check(); val role=mesh.boundaryRole(i)
                    append(points[t.a],points[t.b],points[t.c],role,mesh.boundaryMaterial(i))
                }
            }
            fun build(faces: List<Facet>): Node? {
                work.check(); if (faces.isEmpty()) return null
                val lo=MeshPoint(faces.minOf {it.lo.x},faces.minOf {it.lo.y},faces.minOf {it.lo.z})
                val hi=MeshPoint(faces.maxOf {it.hi.x},faces.maxOf {it.hi.y},faces.maxOf {it.hi.z})
                if (faces.size<=8) return Node(lo,hi,faces)
                val axis=(0..2).maxBy {(hi-lo).coordinate(it)}
                val sorted=faces.sortedBy {(it.lo.coordinate(axis)+it.hi.coordinate(axis))/2}
                return Node(lo,hi,emptyList(),build(sorted.take(sorted.size/2)),build(sorted.drop(sorted.size/2)))
            }
            return BoundaryGeometry(facets,build(facets))
        }
    }
}
