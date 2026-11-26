package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.*
import kotlin.math.*
import kotlin.test.*

class AngularMaterialTest {
    private fun curve(a: Float,b: Float)=BoundaryMaterial.angular("Measured angles",listOf(
        0f to listOf(100 to a,10000 to a), 90f to listOf(100 to b,10000 to b)),"Lab / mounting A")
    private fun mesh(material: BoundaryMaterial,reverse: Boolean=false): AudienceMesh {
        val t=if(reverse) listOf(SurfaceTriangle(2,1,0),SurfaceTriangle(3,2,0)) else listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3))
        return AudienceMesh.create(listOf(AudienceMeshVertex(1.0,-20f,-20.0),AudienceMeshVertex(1.0,20f,-20.0),
            AudienceMeshVertex(1.0,20f,20.0),AudienceMeshVertex(1.0,-20f,20.0)),t)
            .withBoundaryRoles(List(2) {AcousticBoundaryRole.RIGID_REFLECTOR}).withBoundaryMaterials(List(2) {material})
    }
    private fun geometry(material: BoundaryMaterial,reverse: Boolean=false,block: BoundaryMaterial?=null): BoundaryGeometry {
        val venue=VenueGeometry(stageHeightM=0f,blocks=if(block==null) emptyList() else listOf(VenueBlock(0,
            widthM=2f,depthM=40f,heightM=20f,blockHeightM=40f,boundaryRole=AcousticBoundaryRole.RIGID_REFLECTOR,boundaryMaterial=block)))
        return BoundaryGeometry.prepare(listOf(AudienceSurface(0,"Wall","STAGE",emptyList(),importedMesh=mesh(material,reverse))),venue=venue)
    }
    private fun power(g: BoundaryGeometry,z: Float,hz: Int=1000): Double {
        val s=SoundSource(0,3f,0f,0f,180f,0f,Radiator {_,az,_->if(abs(az)<89f) 100f else -1000f})
        return DirectField().bandPower(listOf(s),4f,0f,z,Bands.segments(hz,0f),boundaries=g)
    }
    @Test fun angleFromNormalControlsReflectionAndIgnoresWinding() {
        val m=curve(.1f,.9f)
        for(z in listOf(0f,5f,10f)) {
            val angle=(atan(z/5.0)*180/PI).toFloat()
            val g=geometry(m)
            assertEquals(angle,g.reflections(BoundaryPoint(3.0,0.0,0.0),BoundaryPoint(4.0,0.0,z.toDouble())).single().incidenceDeg,1e-5f)
            for(hz in listOf(125,1000,4000)) {
                val expected=1.0-m.absorptionAt(hz,angle)
                val rigid=power(geometry(BoundaryMaterial.IDEAL_RIGID),z,hz)
                assertEquals(expected,power(g,z,hz)/rigid,1e-6)
                assertEquals(expected,power(geometry(m,true),z,hz)/rigid,1e-6)
            }
        }
    }
    @Test fun mixedBlockFaceDuplicatesUseAngleSpecificMaximumOnlyOnce() {
        val a=curve(.1f,.9f); val b=curve(.8f,.2f)
        for(z in listOf(0f,5f,10f)) {
            val angle=(atan(z/5.0)*180/PI).toFloat()
            val rigid=power(geometry(BoundaryMaterial.IDEAL_RIGID),z)
            for((face,block) in listOf(a to b,b to a,a to a)) {
                val g=geometry(face,block=block)
                assertEquals(1,g.reflections(BoundaryPoint(3.0,0.0,0.0),BoundaryPoint(4.0,0.0,z.toDouble())).size)
                assertEquals(1.0-maxOf(face.absorptionAt(1000,angle),block.absorptionAt(1000,angle)),power(g,z)/rigid,1e-6)
            }
        }
    }
    @Test fun interpolationPersistenceAndImmutableInputs() {
        val pts=mutableListOf(100 to .1f,10000 to .5f)
        val curves=mutableListOf(20f to pts.toList(),60f to listOf(100 to .5f,10000 to .9f))
        val m=BoundaryMaterial.angular("Measured",curves,"Report")
        curves.clear();pts.clear()
        assertEquals(.5f,m.absorptionAt(1000,40f),1e-6f)
        assertEquals(.3f,m.absorptionAt(1000,0f),1e-6f)
        assertEquals(.7f,m.absorptionAt(1000,90f),1e-6f)
        assertEquals(m,BoundaryMaterial.fromJsonString(m.toJsonString()))
        assertEquals(m,BoundaryMaterial.parseAngular("Measured","20,100,0.1\n20,10000,0.5\n60,100,0.5\n60,10000,0.9","Report"))
        val mesh=mesh(m).createFaceGroup("Wall",listOf(0,1))
        assertEquals(mesh,AudienceMeshJson.decode(AudienceMeshJson.encode(mesh)))
        assertEquals(mesh.boundaryMaterials(),mesh.mapCoordinates {PlanPoint(it.x+2,it.z)}.boundaryMaterials())
        assertEquals(BoundaryMaterial.CARPET.absorptionAt(1000),BoundaryMaterial.CARPET.absorptionAt(1000,80f))
    }
    @Test fun invalidAnglesAndMalformedPayloadsAreRejected() {
        val p=listOf(125 to .2f,1000 to .8f)
        for(angles in listOf(listOf(0f),listOf(0f,0f),listOf(60f,30f),listOf(-1f,90f),listOf(0f,91f),listOf(0f,Float.NaN)))
            assertFails {BoundaryMaterial.angular("Bad",angles.map {it to p})}
        assertFails {curve(.1f,.9f).absorptionAt(1000,Float.NaN)}
        assertFails {BoundaryMaterial.fromJsonString(curve(.1f,.9f).toJsonString().replace("90.0","100.0"))}
        assertFails {BoundaryMaterial.parseAngular("Bad","0,125,.2\n60,125,.4\n0,1000,.5\n60,1000,.8")}
    }
}
