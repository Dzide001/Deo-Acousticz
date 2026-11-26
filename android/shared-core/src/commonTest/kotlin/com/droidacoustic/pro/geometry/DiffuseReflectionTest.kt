package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.*
import kotlin.math.*
import kotlin.test.*

class DiffuseReflectionTest {
    private fun material(s: Float=1f,a: Float=0f)=BoundaryMaterial.custom("Diffuse",listOf(125 to a,4000 to a))
        .withScattering(MaterialScattering.create(listOf(0f to listOf(125 to s,4000 to s))))
    private fun wall(m: BoundaryMaterial)=VenueBlock(0,widthM=2f,depthM=20f,heightM=10f,blockHeightM=20f,
        boundaryRole=AcousticBoundaryRole.RIGID_REFLECTOR,boundaryMaterial=m)
    private fun mesh(block: VenueBlock,split: Boolean=false,reverse: Boolean=false): AudienceMesh {
        val shape=VenueBoundaryGeometry.block(block)
        val vertices=shape.vertices.map {AudienceMeshVertex(it.x,it.y.toFloat(),it.z)}.toMutableList()
        val faces=if(!split) shape.triangles else shape.triangles.flatMap {t ->
            val a=vertices[t.a];val b=vertices[t.b];val c=vertices[t.c];val i=vertices.size
            vertices+=AudienceMeshVertex((a.x+b.x+c.x)/3,(a.y+b.y+c.y)/3,(a.z+b.z+c.z)/3)
            listOf(SurfaceTriangle(t.a,t.b,i),SurfaceTriangle(t.b,t.c,i),SurfaceTriangle(t.c,t.a,i))
        }
        return AudienceMesh.create(vertices,faces.map {if(reverse) SurfaceTriangle(it.c,it.b,it.a) else it})
            .withBoundaryRoles(List(faces.size) {block.boundaryRole}).withBoundaryMaterials(List(faces.size) {block.boundaryMaterial})
    }
    private fun geometry(blocks: List<VenueBlock>,meshes: List<AudienceMesh> = emptyList())=BoundaryGeometry.prepare(
        meshes.mapIndexed {i,m->AudienceSurface(i,"Wall","STAGE",emptyList(),importedMesh=m)},
        venue=VenueGeometry(stageHeightM=0f,blocks=blocks))
    private fun predict(g: BoundaryGeometry,radiator: Radiator=Radiator {_,_,_->100f},inverted: Boolean=false): BandPrediction {
        val source=SoundSource(0,3f,0f,0f,180f,0f,radiator,inverted=inverted)
        return DirectField().predictBand(listOf(source),4f,0f,0f,Bands.segments(1000,0f),boundaries=g)
    }
    @Test fun normalizedHemisphereAndClosedEnclosureConserveEnergy() {
        val directions=DiffuseRadiation.directions()
        assertEquals(1.0,directions.sumOf {DiffuseRadiation.density(it.y)*4*PI/directions.size},1e-12)
        val b=wall(material(.6f,.2f)).copy(widthM=10f,depthM=10f,heightM=5f,blockHeightM=10f)
        val samples=geometry(listOf(b)).diffuseSamples(BoundaryPoint(0.0,0.0,0.0))
        assertTrue(samples.size>=DiffuseRadiation.DEFAULT_RAYS)
        assertEquals(4*PI,samples.sumOf {it.solidAngle},1e-10)
        var outgoing=0.0
        for(sample in samples) {
            val e=BoundaryMaterial.reflectionMaterial(sample.materials,1000,sample.incidenceDeg)!!.energyPartition(1000,sample.incidenceDeg)
            outgoing+=sample.solidAngle*(e.absorbed+e.specular+e.scattered)
        }
        assertEquals(4*PI,outgoing,1e-10)
        val patch=DiffuseSurfaceSample(BoundaryPoint(0.0,0.0,0.0),BoundaryPoint(0.0,1.0,0.0),1.0,.1,0f,setOf(material()))
        for(radius in listOf(1.0,10.0)) {
            val integral=directions.sumOf {d->patch.receiverFactor(BoundaryPoint(d.x*radius,d.y*radius,d.z*radius))*radius*radius*4*PI/directions.size}
            assertEquals(patch.solidAngle,integral,1e-12)
        }
    }
    @Test fun diffuseEnergyReachesReceiverAndDoesNotUseCoherentPhase() {
        val b=wall(material())
        val result=predict(geometry(listOf(b)))
        assertTrue(result.diffuseMeanSquarePressure!!>0)
        assertTrue(result.meanSquarePressure!!>result.diffuseMeanSquarePressure!!)
        val phase=ReflectionPhase.create(listOf(0f to listOf(125 to 180.0,4000 to 180.0)))
        val phased=predict(geometry(listOf(b.copy(boundaryMaterial=b.boundaryMaterial.withReflectionPhase(phase)))),inverted=true)
        assertEquals(result.diffuseMeanSquarePressure!!,phased.diffuseMeanSquarePressure!!,result.diffuseMeanSquarePressure!!*1e-10)
        assertEquals(0.0,predict(geometry(listOf(wall(material(0f))))).diffuseMeanSquarePressure)
        assertEquals(0.0,predict(geometry(listOf(wall(material(1f,1f))))).diffuseMeanSquarePressure)
        val half=predict(geometry(listOf(wall(material(.5f))))).diffuseMeanSquarePressure!!
        assertEquals(result.diffuseMeanSquarePressure!!/2,half,half*1e-10)
    }
    @Test fun overlappingRetriangulatedAndReversedFacesDoNotDuplicateEnergy() {
        val b=wall(material());val base=geometry(listOf(b));val expected=predict(base).meanSquarePressure!!
        for(g in listOf(geometry(listOf(b,b.copy(id=1))),geometry(listOf(b),listOf(mesh(b,true,true))),
            geometry(emptyList(),listOf(mesh(b,true))),geometry(emptyList(),listOf(mesh(b),mesh(b,true,true))))) {
            assertEquals(expected,predict(g).meanSquarePressure!!,expected*2e-4)
            assertEquals(base.diffuseSamples(BoundaryPoint(3.0,0.0,0.0)).sumOf {it.solidAngle},
                g.diffuseSamples(BoundaryPoint(3.0,0.0,0.0)).sumOf {it.solidAngle},1e-10)
        }
    }
    @Test fun partialOverlapDoesNotDoubleInterceptedEnergy() {
        val b=wall(material());val partial=b.copy(id=1,depthM=10f)
        val expected=predict(geometry(listOf(b))).meanSquarePressure!!
        for(g in listOf(geometry(listOf(b,partial)),geometry(listOf(partial,b)),geometry(listOf(b),listOf(mesh(partial,true)))))
            assertEquals(expected,predict(g).meanSquarePressure!!,expected*2e-4)
    }
    @Test fun opaqueSourceAndReceiverOcclusionSuppressDiffusePaths() {
        val b=wall(material());val opaque=b.copy(id=1,boundaryRole=AcousticBoundaryRole.OCCLUDING)
        assertEquals(0.0,predict(geometry(listOf(b,opaque))).diffuseMeanSquarePressure)
        val screen=opaque.copy(centerX=3.5f,widthM=.1f,depthM=40f,heightM=20f,blockHeightM=40f)
        assertEquals(0.0,predict(geometry(listOf(b,screen))).diffuseMeanSquarePressure)
    }
    @Test fun sampledSolidAngleConvergesAndCachedSamplesAreIsolated() {
        val primary=wall(material())
        val g=geometry(listOf(primary,primary.copy(id=1,boundaryMaterial=material(.5f))))
        val source=BoundaryPoint(3.0,0.0,0.0)
        // Exact solid angle of the visible 20 m square at 2 m distance.
        val exact=4*atan(100.0/(2*sqrt(204.0)))
        val samples=g.diffuseSamples(source)
        assertEquals(exact,samples.sumOf {it.solidAngle},.04)
        assertEquals(exact,g.diffuseSamples(source,rayCount=8192).sumOf {it.solidAngle},.02)
        val before=predict(g).meanSquarePressure!!
        // A caller can mutate its snapshot without corrupting the retained source quadrature.
        assertEquals(2,samples.first().materials.size)
        (samples.first().materials as MutableSet<BoundaryMaterial>).clear()
        assertEquals(before,predict(g).meanSquarePressure!!,before*1e-12)
    }
    @Test fun missingDiffuseDirectionDataAndWorkLimitsNeverPublishPartialResults() {
        val g=geometry(listOf(wall(material())))
        val p=predict(g,Radiator {_,az,_->if(abs(az)>1f && abs(az)<80f) Float.NaN else 100f})
        assertNull(p.meanSquarePressure);assertNull(p.diffuseMeanSquarePressure);assertTrue(p.missingBands.isNotEmpty())
        assertFails {g.diffuseSamples(BoundaryPoint(2.0,0.0,0.0),BoundaryWork(limit=1))}
    }
}
