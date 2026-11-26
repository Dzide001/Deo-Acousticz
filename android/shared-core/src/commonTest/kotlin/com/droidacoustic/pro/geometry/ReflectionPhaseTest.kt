package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.*
import kotlin.math.*
import kotlin.test.*

class ReflectionPhaseTest {
    private val engine=DirectField()
    private fun material(phase: Double)=BoundaryMaterial.IDEAL_RIGID.withReflectionPhase(
        ReflectionPhase.create(listOf(0f to listOf(100 to phase,10000 to phase)),"Measured"))
    private fun block(i: Int,m: BoundaryMaterial)=VenueBlock(i,widthM=2f,depthM=20f,heightM=10f,blockHeightM=20f,
        boundaryRole=AcousticBoundaryRole.RIGID_REFLECTOR,boundaryMaterial=m)
    private fun geometry(ms: List<BoundaryMaterial>,face: BoundaryMaterial?=null): BoundaryGeometry {
        val surfaces=if(face==null) emptyList() else {
            val shape=VenueBoundaryGeometry.block(block(99,face))
            val mesh=AudienceMesh.create(shape.vertices.map {AudienceMeshVertex(it.x,it.y.toFloat(),it.z)},shape.triangles)
                .withBoundaryRoles(List(shape.triangles.size) {AcousticBoundaryRole.RIGID_REFLECTOR})
                .withBoundaryMaterials(List(shape.triangles.size) {face})
            listOf(AudienceSurface(0,"Wall","STAGE",emptyList(),importedMesh=mesh))
        }
        return BoundaryGeometry.prepare(surfaces,venue=VenueGeometry(stageHeightM=0f,blocks=ms.mapIndexed(::block)))
    }
    private fun power(g: BoundaryGeometry,seg: Bands.Segment,mode: Int=0): Double {
        val source=SoundSource(0,3f,0f,0f,180f,0f,Radiator {_,az,_->
            if((mode==1 && abs(az)<90f)||(mode==2 && abs(az)>90f)) -1000f else 100f})
        return engine.bandPower(listOf(source),4f,0f,0f,listOf(seg),coherent=false,boundaries=g)
    }
    @Test fun phaseControlsInterferenceWithCorrectSignAndBandwidth() {
        val seg=Bands.Segment(1000,980.0,1020.0)
        val rigid=geometry(listOf(BoundaryMaterial.IDEAL_RIGID))
        val d=power(rigid,seg,1);val r=power(rigid,seg,2)
        val tau=4.0/engine.atmosphere.speedOfSound
        val x=PI*seg.widthHz*tau;val sinc=sin(x)/x
        for(degrees in listOf(0.0,90.0,180.0,-90.0,450.0)) {
            val expected=d+r+2*sqrt(d*r)*cos(2*PI*seg.centreHz*tau-degrees*PI/180)*sinc
            val actual=power(geometry(listOf(material(degrees))),seg)
            assertEquals(expected,actual,expected*1e-7)
            assertEquals(r,power(geometry(listOf(material(degrees))),seg,2),r*1e-7)
        }
        val p0=power(geometry(listOf(material(0.0))),seg)
        val p180=power(geometry(listOf(material(180.0))),seg)
        assertEquals(2*(d+r),p0+p180,(d+r)*1e-7)
    }
    @Test fun linearPhaseSlopeIsIntegratedRatherThanFrozenAtCentre() {
        val seg=Bands.Segment(1000,900.0,1100.0)
        val tau=4.0/engine.atmosphere.speedOfSound
        // Synthetic phase cancels path delay across this whole band.
        val phase=ReflectionPhase.create(listOf(0f to listOf(100 to 360*100*tau,10000 to 360*10000*tau)))
        val m=BoundaryMaterial.IDEAL_RIGID.withReflectionPhase(phase)
        val g=geometry(listOf(m));val d=power(g,seg,1);val r=power(g,seg,2)
        assertEquals(d+r+2*sqrt(d*r),power(g,seg),(d+r)*1e-7)
    }
    @Test fun phaseKnotsInsideBandAreIntegratedPiecewise() {
        val phase=ReflectionPhase.create(listOf(0f to listOf(900 to 0.0,1000 to 180.0,1100 to 0.0)))
        val g=geometry(listOf(BoundaryMaterial.IDEAL_RIGID.withReflectionPhase(phase)))
        val tau=4.0/engine.atmosphere.speedOfSound
        var expected=0.0
        for((centre,slope) in listOf(950.0 to PI/100,1050.0 to -PI/100)) {
            val alpha=engine.atmosphere.absorptionDbPerMetre(centre)
            val d=10.0.pow((100-alpha)/20)
            val r=10.0.pow((100-20*log10(5.0)-alpha*5)/20)
            val x=PI*100*(tau-slope/(2*PI))
            expected+=(d*d+r*r+2*d*r*cos(2*PI*centre*tau-PI/2)*sin(x)/x)/2
        }
        assertEquals(expected,power(g,Bands.Segment(1000,900.0,1100.0)),expected*1e-7)
    }
    @Test fun overlapsKeepAbsorptionAndPhasePairedWithoutStacking() {
        val a=material(90.0);val b=BoundaryMaterial.CARPET.withReflectionPhase(material(-90.0).reflectionPhase)
        val seg=Bands.Segment(1000,980.0,1020.0)
        val expected=power(geometry(listOf(b)),seg)
        for(g in listOf(geometry(listOf(a,b)),geometry(listOf(b,a)),geometry(listOf(b,b)),geometry(listOf(a),b),geometry(listOf(b),a)))
            assertEquals(expected,power(g,seg),expected*1e-7)
        val tie1=power(geometry(listOf(a,material(-90.0))),seg)
        assertEquals(tie1,power(geometry(listOf(material(-90.0),a)),seg),tie1*1e-7)
    }
    @Test fun unwrappedInterpolationAngleAndPersistence() {
        val pts=mutableListOf(100 to 179.0,200 to 181.0)
        val phase=ReflectionPhase.create(listOf(0f to pts,90f to listOf(100 to 539.0,200 to 541.0)),"Report")
        pts.clear()
        assertEquals(180.0,phase.degreesAt(150.0,0f))
        assertEquals(360.0,phase.degreesAt(150.0,45f))
        assertEquals(179.0,phase.degreesAt(50.0,0f))
        val m=BoundaryMaterial.PANEL.withReflectionPhase(phase)
        assertEquals(m,BoundaryMaterial.fromJsonString(m.toJsonString()))
        assertEquals(phase,ReflectionPhase.parse("0,100,179\n0,200,181\n90,100,539\n90,200,541","Report"))
        val shape=VenueBoundaryGeometry.block(block(0,m))
        val mesh=AudienceMesh.create(shape.vertices.map {AudienceMeshVertex(it.x,it.y.toFloat(),it.z)},shape.triangles)
            .createFaceGroup("Wall",listOf(0,1)).assignBoundaryMaterial(listOf(0,1),m)
        assertEquals(mesh,AudienceMeshJson.decode(AudienceMeshJson.encode(mesh)))
        assertEquals(mesh.boundaryMaterials(),mesh.mapCoordinates {PlanPoint(it.x+2,it.z)}.boundaryMaterials())
        assertNull(BoundaryMaterial.fromJsonString("\"PANEL\"").reflectionPhase)
        assertFails {ReflectionPhase.create(listOf(0f to listOf(100 to Double.NaN,200 to 1.0)))}
        assertFails {ReflectionPhase.create(listOf(0f to listOf(100 to 0.0,100 to 1.0)))}
        assertFails {BoundaryMaterial.fromJsonString(m.toJsonString().replace("exp(+iwt)","unknown"))}
    }
}
