package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.scene.*
import kotlin.math.*
import kotlin.test.*

class GroundPhaseTest {
    private val air=Atmosphere()
    private fun phase(deg:Double)=ReflectionPhase.create(listOf(0f to listOf(100 to deg,10000 to deg)))
    private fun floor(deg:Double?=0.0,alpha:Float=.25f)=FloorReflection(deg?.let(::phase)){alpha}
    private fun source(missingDown:Boolean=false)=SoundSource(0,0f,1f,0f,0f,0f,object:Radiator {
        override fun levelDb(dataHz:Int,azimuthDeg:Float,elevationDeg:Float)=40f
        override fun complexReferenceDrive()="1 V rms"
        override fun complexAt(hz:Int,azimuthDeg:Float,elevationDeg:Float)=if(missingDown && elevationDeg<0) null else ComplexPressure(100.0,0.0)
    })
    private fun calculate(f:FloorReflection,geometry:BoundaryGeometry?=null,s:SoundSource=source())=
        PhaseResponse.calculate(listOf(s),4f,1f,0f,1000,air,geometry,floor=f)
    private fun plane(role:AcousticBoundaryRole=AcousticBoundaryRole.RIGID_REFLECTOR,material:BoundaryMaterial=BoundaryMaterial.IDEAL_RIGID):AudienceSurface {
        val mesh=AudienceMesh.create(listOf(AudienceMeshVertex(-10.0,0f,-10.0),AudienceMeshVertex(10.0,0f,-10.0),AudienceMeshVertex(10.0,0f,10.0),AudienceMeshVertex(-10.0,0f,10.0)),listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3)))
            .withBoundaryRoles(List(2){role}).withBoundaryMaterials(List(2){material})
        return AudienceSurface(0,"Ground","STAGE",emptyList(),importedMesh=mesh)
    }
    @Test fun groundInterferenceMatchesAnalyticComplexPressure() {
        fun amplitude(d:Double)=100/d*10.0.pow(-air.absorptionDbPerMetre(1000.0)*d/20)
        for(degrees in listOf(0.0,90.0,180.0,-90.0)) {
            val rd=sqrt(20.0);val a=2*PI*1000*4/air.speedOfSound;val b=2*PI*1000*rd/air.speedOfSound-degrees*PI/180
            val expected=ComplexPressure(amplitude(4.0)*cos(a)+sqrt(.75)*amplitude(rd)*cos(b),amplitude(4.0)*sin(a)+sqrt(.75)*amplitude(rd)*sin(b))
            val result=calculate(floor(degrees))
            assertEquals(expected.real,result.pressure!!.real,1e-6);assertEquals(expected.imaginary,result.pressure!!.imaginary,1e-6)
        }
    }
    @Test fun globalGroundOwnsCoincidentFacesAndBlockAndOpaqueFaceSuppressesBounce() {
        val single=calculate(floor(45.0))
        val face=plane(material=BoundaryMaterial.CONCRETE)
        val block=VenueBlock(0,centerX=0f,widthM=20f,depthM=20f,heightM=0f,blockHeightM=2f,type="BALCONY",boundaryRole=AcousticBoundaryRole.RIGID_REFLECTOR)
        val geometry=BoundaryGeometry.prepare(listOf(face),venue=VenueGeometry(stageHeightM=0f,blocks=listOf(block)))
        assertEquals(single,calculate(floor(45.0),geometry))
        val scattering=BoundaryMaterial.CONCRETE.withScattering(MaterialScattering.create(listOf(0f to listOf(100 to .5f,10000 to .5f))))
        assertEquals(single,calculate(floor(45.0),BoundaryGeometry.prepare(listOf(plane(material=scattering)))))
        val opaque=BoundaryGeometry.prepare(listOf(plane(AcousticBoundaryRole.OCCLUDING)))
        assertEquals(PhaseResponse.calculate(listOf(source()),4f,1f,0f,1000,air).pressure,calculate(floor(),opaque).pressure)
    }
    @Test fun missingComplexSamplesAndMissingPhaseNeverYieldPartialResult() {
        assertNull(calculate(floor(null)).phase.degrees)
        assertNull(calculate(floor(),s=source(true)).phase.degrees)
        assertNotNull(calculate(floor(null,1f),s=source(true)).phase.degrees)
        assertNotNull(calculate(floor(null,0f)).phase.degrees)
        assertNull(PhaseResponse.calculate(listOf(source()),4f,-1f,0f,1000,air,floor=floor()).phase.degrees)
    }
    @Test fun incidencePhaseAndMagnitudeUseTheSameGroundPaths() {
        val curve=ReflectionPhase.create(listOf(0f to listOf(100 to 0.0,10000 to 0.0),90f to listOf(100 to 90.0,10000 to 90.0)))
        val f=FloorReflection(curve){.25f}
        val angle=atan2(4.0,2.0)*180/PI
        val result=calculate(f)
        // Incidence is stored as Float in the shared geometry; allow its rounding error.
        assertEquals(calculate(floor(angle)).pressure!!.power,result.pressure!!.power,result.pressure!!.power*1e-7)
        val narrow=DirectField(air).bandPower(listOf(source()),4f,1f,0f,listOf(Bands.Segment(1000,999.999,1000.001)),floor=f)
        assertEquals(result.pressure!!.power,narrow,result.pressure!!.power*1e-6)
    }
}
