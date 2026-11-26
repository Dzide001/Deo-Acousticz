package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.*
import kotlin.test.*

class BoundaryMaterialTest {
    private fun block(material: BoundaryMaterial)=VenueBlock(0,widthM=2f,depthM=10f,heightM=5f,blockHeightM=10f,
        boundaryRole=AcousticBoundaryRole.RIGID_REFLECTOR,boundaryMaterial=material)
    private fun power(materials: List<BoundaryMaterial>,hz: Int): Double {
        val g=BoundaryGeometry.prepare(emptyList(),venue=VenueGeometry(stageHeightM=0f,blocks=materials.mapIndexed {i,m->block(m).copy(id=i)}))
        // Only the reflected departure has output; direct arrival is behind this radiator.
        val source=SoundSource(0,3f,0f,0f,180f,0f,Radiator {_,az,_->if(kotlin.math.abs(az)<1f) 100f else -1000f})
        return DirectField().bandPower(listOf(source),4f,0f,0f,Bands.segments(hz,0f),boundaries=g)
    }
    @Test fun customCurvesReflectInterpolateAndDeduplicate() {
        val a=BoundaryMaterial.custom("A",listOf(100 to .1f,10000 to .9f),"Measured")
        val b=BoundaryMaterial.custom("B",listOf(100 to .8f,10000 to .2f))
        assertEquals(.5f,a.absorptionAt(1000),1e-6f)
        assertEquals(.1f,a.absorptionAt(50)); assertEquals(.9f,a.absorptionAt(20000))
        for(hz in listOf(125,1000,4000)) {
            val rigid=power(listOf(BoundaryMaterial.IDEAL_RIGID),hz)
            assertEquals(1.0-a.absorptionAt(hz),power(listOf(a),hz)/rigid,1e-6)
            val expected=1.0-maxOf(a.absorptionAt(hz),b.absorptionAt(hz))
            for(ms in listOf(listOf(a,b),listOf(b,a),listOf(a,a,b)))
                assertEquals(expected,power(ms,hz)/rigid,1e-6)
        }
    }
    @Test fun customCurvesAreImmutableAndStrictlyValidated() {
        val points=mutableListOf(125 to .2f,1000 to .8f)
        val m=BoundaryMaterial.custom("Test",points,"Report")
        points[0]=125 to 1f
        assertEquals(.2f,m.absorptionAt(125))
        assertEquals(m,BoundaryMaterial.fromJson(m.toJson()))
        assertEquals(m,BoundaryMaterial.parseMeasured("Test","125, 0.2\n1000, 0.8","Report"))
        for(p in listOf(listOf(0 to .2f,1000 to .3f),listOf(125 to Float.NaN,1000 to .3f),
            listOf(125 to -.1f,1000 to .3f),listOf(125 to 1.1f,1000 to .3f),
            listOf(125 to .1f,125 to .3f),listOf(1000 to .1f,125 to .3f)))
            assertFails {BoundaryMaterial.custom("Invalid",p)}
        assertFails {BoundaryMaterial.custom("",listOf(125 to .1f,1000 to .2f))}
        assertFails {BoundaryMaterial.fromJsonString("{\"type\":\"custom\"}")}
    }
    @Test fun reflectionEnergyFollowsAbsorptionByBand() {
        for(hz in listOf(125,1000,4000)) {
            val rigid=power(listOf(BoundaryMaterial.IDEAL_RIGID),hz)
            assertEquals(1.0-BoundaryMaterial.CARPET.absorptionAt(hz),power(listOf(BoundaryMaterial.CARPET),hz)/rigid,1e-7)
        }
        assertTrue(power(listOf(BoundaryMaterial.ABSORBING),1000)<1e-80)
    }
    @Test fun overlapsUseOneArrivalAndStrongestAbsorptionRegardlessOfOrder() {
        val expected=power(listOf(BoundaryMaterial.PANEL),1000)
        for(ms in listOf(listOf(BoundaryMaterial.PANEL,BoundaryMaterial.PANEL),listOf(BoundaryMaterial.PANEL,BoundaryMaterial.CONCRETE),listOf(BoundaryMaterial.CONCRETE,BoundaryMaterial.PANEL)))
            assertEquals(expected,power(ms,1000),expected*1e-8)
    }
    @Test fun meshRoundTripGroupsAndTransformsPreserveMaterial() {
        val shape=VenueBoundaryGeometry.block(block(BoundaryMaterial.CARPET))
        val m=AudienceMesh.create(shape.vertices.map {AudienceMeshVertex(it.x,it.y.toFloat(),it.z)},shape.triangles)
            .createFaceGroup("Wall",listOf(0,1)).assignBoundaryMaterial(listOf(0,1),BoundaryMaterial.custom("Measured",listOf(125 to .1f,2000 to .9f)))
        assertEquals(m,AudienceMeshJson.decode(AudienceMeshJson.encode(m)))
        assertEquals(m.boundaryMaterials(),m.mapCoordinates {PlanPoint(it.x+1,it.z)}.boundaryMaterials())
        assertEquals(m.boundaryMaterials(),m.assignGroupBoundaryRole(1,AcousticBoundaryRole.RIGID_REFLECTOR).boundaryMaterials())
        assertFails {m.withBoundaryMaterials(emptyList())}
    }
    @Test fun importedAbsorbingDuplicateOverridesRigidBlockAndDoesNotStack() {
        val b=block(BoundaryMaterial.IDEAL_RIGID); val shape=VenueBoundaryGeometry.block(b)
        val mesh=AudienceMesh.create(shape.vertices.map {AudienceMeshVertex(it.x,it.y.toFloat(),it.z)},shape.triangles)
            .withBoundaryRoles(List(12) {AcousticBoundaryRole.RIGID_REFLECTOR})
            .withBoundaryMaterials(List(12) {BoundaryMaterial.custom("Absorber",listOf(125 to 1f,4000 to 1f))})
        val surface=AudienceSurface(0,"Absorber","STAGE",emptyList(),importedMesh=mesh)
        val g=BoundaryGeometry.prepare(listOf(surface),venue=VenueGeometry(stageHeightM=0f,blocks=listOf(b)))
        val paths=g.reflections(BoundaryPoint(3.0,0.0,0.0),BoundaryPoint(4.0,0.0,0.0))
        assertEquals(1,paths.size)
        assertEquals(1f,paths.single().materials.maxOf {it.absorptionAt(1000)})
        val old=AudienceMeshJson.encode(mesh).replace("\"version\":10","\"version\":5")
        val root=kotlinx.serialization.json.Json.parseToJsonElement(old) as kotlinx.serialization.json.JsonObject
        val legacy=kotlinx.serialization.json.JsonObject(root.filterKeys {it!="boundaryMaterials"})
        assertTrue(AudienceMeshJson.decode(legacy.toString()).boundaryMaterials().all {it==BoundaryMaterial.IDEAL_RIGID})
        assertFails {AudienceMeshJson.decode(AudienceMeshJson.encode(mesh).replace("custom","INVALID"))}
    }
    @Test fun interpolationAndEndpointsAreExplicit() {
        assertEquals(BoundaryMaterial.CARPET.absorptionAt(125),BoundaryMaterial.CARPET.absorptionAt(63))
        assertEquals(BoundaryMaterial.CARPET.absorptionAt(4000),BoundaryMaterial.CARPET.absorptionAt(8000))
        assertTrue(BoundaryMaterial.CARPET.absorptionAt(180) in .02f.. .06f)
    }
}
