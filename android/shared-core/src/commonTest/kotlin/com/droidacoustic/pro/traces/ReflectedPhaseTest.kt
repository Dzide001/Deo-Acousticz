package com.droidacoustic.pro.traces

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.scene.*
import kotlin.math.*
import kotlin.test.*

class ReflectedPhaseTest {
    private val air=Atmosphere()
    private fun material(degrees:Double)=BoundaryMaterial.custom("Measured",listOf(100 to .25f,10000 to .25f))
        .withReflectionPhase(ReflectionPhase.create(listOf(0f to listOf(100 to degrees,10000 to degrees)),"Fixture"))
    private fun block(id:Int,m:BoundaryMaterial)=VenueBlock(id,widthM=2f,depthM=20f,heightM=10f,blockHeightM=20f,
        boundaryRole=AcousticBoundaryRole.RIGID_REFLECTOR,boundaryMaterial=m)
    private fun geometry(materials:List<BoundaryMaterial>,face:BoundaryMaterial?=null):BoundaryGeometry {
        val surfaces=if(face==null) emptyList() else {
            val shape=VenueBoundaryGeometry.block(block(99,face))
            val mesh=AudienceMesh.create(shape.vertices.map {AudienceMeshVertex(it.x,it.y.toFloat(),it.z)},shape.triangles)
                .withBoundaryRoles(List(shape.triangles.size){AcousticBoundaryRole.RIGID_REFLECTOR})
                .withBoundaryMaterials(List(shape.triangles.size){face})
            listOf(AudienceSurface(0,"Imported wall","STAGE",emptyList(),importedMesh=mesh))
        }
        return BoundaryGeometry.prepare(surfaces,venue=VenueGeometry(stageHeightM=0f,blocks=materials.mapIndexed(::block)))
    }
    private fun source(missingReflection:Boolean=false)=SoundSource(0,3f,0f,0f,0f,0f,object:Radiator {
        override fun levelDb(dataHz:Int,azimuthDeg:Float,elevationDeg:Float)=40f
        override fun complexReferenceDrive()="1 V rms"
        override fun complexAt(hz:Int,azimuthDeg:Float,elevationDeg:Float)=
            if(missingReflection && abs(azimuthDeg)>90) null else ComplexPressure(100.0,0.0)
    })
    private fun calculate(g:BoundaryGeometry,s:SoundSource=source())=PhaseResponse.calculate(listOf(s),4f,0f,0f,1000,air,g)
    @Test fun measuredReflectionInterferenceMatchesIndependentComplexSum() {
        fun amplitude(d:Double)=100/d*10.0.pow(-air.absorptionDbPerMetre(1000.0)*d/20)
        for(degrees in listOf(0.0,90.0,180.0,-90.0)) {
            val directAngle=2*PI*1000/air.speedOfSound
            val reflectedAngle=2*PI*1000*5/air.speedOfSound-degrees*PI/180
            val expected=ComplexPressure(amplitude(1.0)*cos(directAngle)+sqrt(.75)*amplitude(5.0)*cos(reflectedAngle),
                amplitude(1.0)*sin(directAngle)+sqrt(.75)*amplitude(5.0)*sin(reflectedAngle))
            val result=calculate(geometry(listOf(material(degrees))))
            assertEquals(expected.real,result.pressure!!.real,1e-7)
            assertEquals(expected.imaginary,result.pressure!!.imaginary,1e-7)
            assertEquals(PhaseResponse.wrapped(atan2(expected.imaginary,expected.real)*180/PI),result.phase.degrees!!,1e-7)
        }
    }
    @Test fun importedFacesAndDuplicateBlocksCountOnePhysicalReflection() {
        val m=material(75.0)
        val single=calculate(geometry(listOf(m)))
        assertEquals(single,calculate(geometry(emptyList(),m)))
        assertEquals(single,calculate(geometry(listOf(m,m),m)))
    }
    @Test fun missingReflectedSpeakerOrMaterialPhaseDoesNotPublishPartialSum() {
        val missing=calculate(geometry(listOf(material(0.0))),source(true))
        assertNull(missing.phase.degrees);assertNull(missing.pressure)
        assertNull(calculate(geometry(listOf(BoundaryMaterial.CONCRETE))).phase.degrees)
        val absorbed=calculate(geometry(listOf(BoundaryMaterial.ABSORBING)),source(true))
        assertNotNull(absorbed.phase.degrees)
        assertEquals(PhaseResponse.calculate(listOf(source(true)),4f,0f,0f,1000,air).pressure,absorbed.pressure)
    }
    @Test fun opaquePathsAndDiffuseModelsNeverCreateCoherentPhase() {
        val opaque=BoundaryGeometry.prepare(emptyList(),venue=VenueGeometry(stageHeightM=0f,
            blocks=listOf(block(0,BoundaryMaterial.IDEAL_RIGID).copy(boundaryRole=AcousticBoundaryRole.OCCLUDING))))
        val behind=PhaseResponse.calculate(listOf(source()),-4f,0f,0f,1000,air,opaque)
        assertNull(behind.phase.degrees);assertEquals(0.0,behind.pressure!!.power)
        val scattered=material(0.0).withScattering(MaterialScattering.create(listOf(0f to listOf(100 to .5f,10000 to .5f))))
        assertTrue(calculate(geometry(listOf(scattered))).phase.reason!!.contains("scattering"))
    }
    @Test fun legacyPhaseTracesCompareOnlyWithinTheirOriginalModel() {
        val metadata=TraceMetadata(BoundaryPoint(4.0,0.0,0.0),1f,20f,50f,"a".repeat(64),0)
        fun trace(id:Int,convention:String)=SavedTrace(id,"Design",metadata,mapOf(1000 to 1.0),mapOf(1000 to PhaseSample(10.0)),convention)
        val old=trace(0,PhaseResponse.LEGACY_CONVENTION)
        assertTrue(TraceComparisons.comparePhase(old,old).compatible)
        assertFalse(TraceComparisons.comparePhase(old,trace(1,PhaseResponse.CONVENTION)).compatible)
        assertEquals(PhaseResponse.LEGACY_CONVENTION,SavedTraces.fromJsonString(SavedTraces.empty().captureBatch(listOf(old)).toJsonString()).traces.single().phaseConvention)
    }
}
