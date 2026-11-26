package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.*
import kotlin.math.*
import kotlin.test.*

class MaterialScatteringTest {
    private fun scattering(s: Float)=MaterialScattering.create(listOf(0f to listOf(125 to s,4000 to s)),"Lab")
    private fun block(i: Int,m: BoundaryMaterial)=VenueBlock(i,widthM=2f,depthM=40f,heightM=20f,blockHeightM=40f,
        boundaryRole=AcousticBoundaryRole.RIGID_REFLECTOR,boundaryMaterial=m)
    private fun mesh(m: BoundaryMaterial): AudienceMesh {
        val shape=VenueBoundaryGeometry.block(block(0,m))
        return AudienceMesh.create(shape.vertices.map {AudienceMeshVertex(it.x,it.y.toFloat(),it.z)},shape.triangles)
            .withBoundaryRoles(List(shape.triangles.size) {AcousticBoundaryRole.RIGID_REFLECTOR})
            .withBoundaryMaterials(List(shape.triangles.size) {m})
    }
    private fun geometry(ms: List<BoundaryMaterial>,face: BoundaryMaterial?=null)=BoundaryGeometry.prepare(
        if(face==null) emptyList() else listOf(AudienceSurface(0,"Wall","STAGE",emptyList(),importedMesh=mesh(face))),
        venue=VenueGeometry(stageHeightM=0f,blocks=ms.mapIndexed(::block)))
    private fun power(g: BoundaryGeometry,hz: Int=1000,z: Float=0f,reflectedOnly: Boolean=true): Double {
        val s=SoundSource(0,3f,0f,0f,180f,0f,Radiator {_,az,_->if(reflectedOnly && abs(az)>89f) -1000f else 100f})
        val result=DirectField().predictBand(listOf(s),4f,0f,z,Bands.segments(hz,0f),boundaries=g)
        return result.meanSquarePressure!!-result.diffuseMeanSquarePressure!!
    }
    @Test fun partitionsConserveIncidentEnergyAndSeparateScatteringFromAbsorption() {
        for(a in listOf(0f,.2f,.8f,1f)) for(s in listOf(0f,.3f,1f)) {
            val material=BoundaryMaterial.custom("Test",listOf(125 to a,4000 to a)).withScattering(scattering(s))
            val e=material.energyPartition(1000)
            assertEquals(1.0,e.absorbed+e.specular+e.scattered,1e-12)
            assertEquals(a.toDouble(),e.absorbed)
            assertEquals((1-a.toDouble())*(1-s.toDouble()),e.specular,1e-12)
            assertEquals((1-a.toDouble())*s,e.scattered,1e-12)
        }
    }
    @Test fun specularPowerUsesFrequencyAndAngleScatteringOnce() {
        val scatter=MaterialScattering.create(listOf(0f to listOf(125 to .1f,4000 to .8f),90f to listOf(125 to .9f,4000 to .2f)))
        val m=BoundaryMaterial.CARPET.withScattering(scatter)
        for(hz in listOf(125,1000,4000)) for(z in listOf(0f,5f,10f)) {
            val angle=(atan(z/5.0)*180/PI).toFloat()
            val rigid=power(geometry(listOf(BoundaryMaterial.IDEAL_RIGID)),hz,z)
            assertEquals(m.energyPartition(hz,angle).specular,power(geometry(listOf(m)),hz,z)/rigid,1e-6)
        }
        assertTrue(power(geometry(listOf(BoundaryMaterial.IDEAL_RIGID.withScattering(scattering(1f)))))<1e-80)
        val direct=power(geometry(emptyList()),reflectedOnly=false)
        assertEquals(direct,power(geometry(listOf(BoundaryMaterial.IDEAL_RIGID.withScattering(scattering(1f)))),reflectedOnly=false),direct*1e-7)
        assertEquals(power(geometry(listOf(BoundaryMaterial.CARPET))),power(geometry(listOf(BoundaryMaterial.CARPET.withScattering(scattering(0f))))),1e-6)
    }
    @Test fun overlapsSelectOneCompleteResponseIncludingPhaseAndScattering() {
        val phase=ReflectionPhase.create(listOf(0f to listOf(125 to 90.0,4000 to 90.0)))
        val lowSpecular=BoundaryMaterial.custom("Scatter",listOf(125 to .1f,4000 to .1f)).withScattering(scattering(.95f)).withReflectionPhase(phase)
        val highAbsorption=BoundaryMaterial.custom("Absorb",listOf(125 to .8f,4000 to .8f))
        val expected=power(geometry(listOf(lowSpecular)),reflectedOnly=false)
        for(g in listOf(geometry(listOf(lowSpecular,lowSpecular)),geometry(listOf(lowSpecular,highAbsorption)),
            geometry(listOf(highAbsorption,lowSpecular)),geometry(listOf(highAbsorption),lowSpecular),geometry(listOf(lowSpecular),highAbsorption))) {
            assertEquals(1,g.reflections(BoundaryPoint(3.0,0.0,0.0),BoundaryPoint(4.0,0.0,0.0)).size)
            assertEquals(expected,power(g,reflectedOnly=false),expected*1e-7)
        }
    }
    @Test fun scatteringRoundTripIsImmutableAndRetainsOtherMaterialProperties() {
        val points=mutableListOf(100 to .1f,10000 to .9f)
        val s=MaterialScattering.create(listOf(0f to points,90f to listOf(100 to .3f,10000 to .7f)),"Report")
        points.clear()
        assertEquals(.5f,s.coefficientAt(1000,45f),1e-6f)
        assertEquals(.1f,s.coefficientAt(50,0f));assertEquals(.7f,s.coefficientAt(20000,90f))
        assertEquals(s,MaterialScattering.parse("0,100,.1\n0,10000,.9\n90,100,.3\n90,10000,.7","Report"))
        val phase=ReflectionPhase.create(listOf(0f to listOf(125 to 90.0,4000 to 180.0)))
        val m=BoundaryMaterial.PANEL.withScattering(s).withReflectionPhase(phase)
        assertEquals(m,BoundaryMaterial.fromJsonString(m.toJsonString()))
        assertEquals(s,m.withReflectionPhase(null).scattering)
        assertEquals(phase,m.withScattering(null).reflectionPhase)
        val mesh=mesh(m).createFaceGroup("Wall",listOf(0,1))
        assertEquals(mesh,AudienceMeshJson.decode(AudienceMeshJson.encode(mesh)))
        assertEquals(mesh.boundaryMaterials(),mesh.mapCoordinates {PlanPoint(it.x+1,it.z)}.boundaryMaterials())
        assertNull(BoundaryMaterial.fromJsonString("\"PANEL\"").scattering)
        for(value in listOf(-.1f,1.1f,Float.NaN,Float.POSITIVE_INFINITY)) assertFails {scattering(value)}
        assertFails {MaterialScattering.create(listOf(0f to listOf(125 to .1f,125 to .2f)))}
        assertFails {MaterialScattering.create(listOf(91f to listOf(125 to .1f,1000 to .2f)))}
        assertFails {BoundaryMaterial.fromJsonString(m.toJsonString().replace("fraction-of-reflected-energy","diffusion"))}
    }
    @Test fun globalGroundPrecedenceDoesNotApplyScatteringTwice() {
        val m=BoundaryMaterial.IDEAL_RIGID.withScattering(scattering(1f))
        val floorMesh=AudienceMesh.create(listOf(AudienceMeshVertex(-20.0,0f,-20.0),AudienceMeshVertex(20.0,0f,-20.0),
            AudienceMeshVertex(20.0,0f,20.0),AudienceMeshVertex(-20.0,0f,20.0)),listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3)))
            .withBoundaryRoles(List(2) {AcousticBoundaryRole.RIGID_REFLECTOR}).withBoundaryMaterials(List(2) {m})
        val g=BoundaryGeometry.prepare(listOf(AudienceSurface(0,"Floor","STAGE",emptyList(),importedMesh=floorMesh)))
        val source=SoundSource(0,0f,2f,0f,0f,0f,Radiator {_,_,_->100f})
        val engine=DirectField();val segments=Bands.segments(1000,0f);val floor=FloorReflection {.3f}
        val expected=engine.bandPower(listOf(source),4f,2f,0f,segments,floor=floor)
        assertEquals(expected,engine.bandPower(listOf(source),4f,2f,0f,segments,floor=floor,boundaries=g),expected*1e-7)
        val missingBelow=SoundSource(0,0f,2f,0f,0f,0f,Radiator {_,_,el->if(el<-.1f) Float.NaN else 100f})
        val absorbedFloor=engine.predictBand(listOf(missingBelow),4f,2f,0f,segments,floor=FloorReflection {1f},boundaries=g)
        assertNotNull(absorbedFloor.meanSquarePressure)
        assertTrue(absorbedFloor.missingBands.isEmpty())
    }
}
