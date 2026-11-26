package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.acoustics.*
import kotlin.math.*
import kotlin.test.*

class AdaptiveDiffuseTest {
    private val material=BoundaryMaterial.IDEAL_RIGID.withScattering(MaterialScattering.create(listOf(0f to listOf(125 to 1f,4000 to 1f))))
    private fun panel(x: Double,y0: Double,y1: Double,z0: Double,z1: Double,opaque: Boolean=false,split: Boolean=false): AudienceMesh {
        val vertices=mutableListOf(AudienceMeshVertex(x,y0.toFloat(),z0),AudienceMeshVertex(x,y1.toFloat(),z0),
            AudienceMeshVertex(x,y1.toFloat(),z1),AudienceMeshVertex(x,y0.toFloat(),z1))
        val triangles=if(!split) listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3)) else {
            vertices+=AudienceMeshVertex(x,((y0+y1)/2).toFloat(),(z0+z1)/2)
            listOf(SurfaceTriangle(0,1,4),SurfaceTriangle(1,2,4),SurfaceTriangle(2,3,4),SurfaceTriangle(3,0,4))
        }
        return AudienceMesh.create(vertices,triangles).withBoundaryRoles(List(triangles.size) {
            if(opaque) AcousticBoundaryRole.OCCLUDING else AcousticBoundaryRole.RIGID_REFLECTOR
        }).withBoundaryMaterials(List(triangles.size) {material})
    }
    private fun geometry(vararg meshes: AudienceMesh)=BoundaryGeometry.prepare(meshes.mapIndexed {i,m ->
        AudienceSurface(i,"Panel","STAGE",emptyList(),importedMesh=m)
    })
    private fun rectangleAngle(distance: Double,a: Double,b: Double)=4*atan(a*b/(distance*sqrt(distance*distance+a*a+b*b)))
    @Test fun distantPanelIsFoundEvenWhenNoFixedDirectionHitsIt() {
        val distance=100.0;val half=.01
        assertTrue(DiffuseRadiation.directions().none {d->
            d.x>0 && abs(d.y*distance/d.x)<half && abs(d.z*distance/d.x)<half
        })
        val g=geometry(panel(distance,-half,half,-half,half))
        val samples=g.diffuseSamples(BoundaryPoint(0.0,0.0,0.0))
        assertTrue(samples.isNotEmpty())
        val exact=rectangleAngle(distance,half,half)
        assertEquals(exact,samples.sumOf {it.solidAngle},exact*1e-6)
        val p=DirectField().predictBand(listOf(SoundSource(0,0f,0f,0f,0f,0f,Radiator {_,_,_->100f})),
            0f,1f,0f,Bands.segments(1000,0f),boundaries=g)
        assertTrue(p.diffuseMeanSquarePressure!!>0)
    }
    @Test fun partiallyHiddenSurfaceRetainsOnlyItsVisibleSolidAngle() {
        val reflecting=panel(10.0,-1.0,1.0,-1.0,1.0)
        val upperShadow=panel(5.0,0.0,.5,-.5,.5,opaque=true)
        val source=BoundaryPoint(0.0,0.0,0.0)
        val visible=geometry(reflecting,upperShadow).diffuseSamples(source)
        val expected=rectangleAngle(10.0,1.0,1.0)/2
        assertEquals(expected,visible.sumOf {it.solidAngle},expected*1e-9)
        assertTrue(visible.all {it.point.y<=1e-8})
        assertTrue(geometry(reflecting,panel(5.0,-.5,.5,-.5,.5,opaque=true)).diffuseSamples(source).isEmpty())
    }
    @Test fun overlapAndRetriangulationPreserveExactInterceptedEnergyAtEveryResolution() {
        val full=panel(10.0,-1.0,1.0,-1.0,1.0)
        val retessellated=panel(10.0,-1.0,1.0,-1.0,1.0,split=true)
        val partial=panel(10.0,0.0,1.0,-1.0,1.0,split=true)
        val expected=rectangleAngle(10.0,1.0,1.0)
        for(g in listOf(geometry(full),geometry(full,full),geometry(full,retessellated),geometry(partial,full),geometry(full,partial)))
            for(resolution in listOf(128,2048,8192)) {
                val samples=g.diffuseSamples(BoundaryPoint(0.0,0.0,0.0),rayCount=resolution)
                assertEquals(expected,samples.sumOf {it.solidAngle},expected*1e-9)
                val emitted=samples.sumOf {s -> s.solidAngle*BoundaryMaterial.reflectionMaterial(s.materials,1000,s.incidenceDeg)!!.energyPartition(1000,s.incidenceDeg).scattered}
                assertEquals(expected,emitted,expected*1e-9)
            }
    }
    @Test fun receiverEnergyConvergesAgainstIndependentAreaIntegration() {
        val g=geometry(panel(1.0,-2.0,2.0,-2.0,2.0))
        val source=BoundaryPoint(3.0,0.0,0.0);val receiver=BoundaryPoint(4.0,0.0,0.0)
        // Midpoint area integration of cos(incidence)/r1² * cos(exit)/(pi*r2²).
        val n=400;val step=4.0/n;var reference=0.0
        for(i in 0 until n) for(j in 0 until n) {
            val y=-2+(i+.5)*step;val z=-2+(j+.5)*step
            val r1=sqrt(4+y*y+z*z);val r2=sqrt(9+y*y+z*z)
            reference+=2/(r1*r1*r1)*3/(PI*r2*r2*r2)*step*step
        }
        val coarse=g.diffuseSamples(source,rayCount=128).sumOf {it.receiverFactor(receiver)}
        val fine=g.diffuseSamples(source,rayCount=8192).sumOf {it.receiverFactor(receiver)}
        assertTrue(abs(fine-reference)<abs(coarse-reference))
        assertEquals(reference,fine,reference*5e-4)
        assertEquals(rectangleAngle(2.0,2.0,2.0),g.diffuseSamples(source,rayCount=8192).sumOf {it.solidAngle},1e-10)
    }
    @Test fun resolutionCacheAndWorkLimitsDoNotReturnPartialSampling() {
        val g=geometry(panel(1.0,-2.0,2.0,-2.0,2.0));val source=BoundaryPoint(3.0,0.0,0.0)
        val coarse=g.diffuseSamples(source,rayCount=128)
        val fine=g.diffuseSamples(source,rayCount=8192)
        assertTrue(fine.size>coarse.size)
        assertEquals(coarse,g.diffuseSamples(source,rayCount=128))
        assertFails {g.diffuseSamples(source,BoundaryWork(limit=1),1024)}
        assertFails {g.diffuseSamples(source,rayCount=127)}
    }
}
