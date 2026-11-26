package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.persistence.*
import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class UnifiedBoundaryTest {
    private val wall=VenueBlock(1,widthM=2f,depthM=10f,heightM=5f,blockHeightM=10f,boundaryRole=AcousticBoundaryRole.RIGID_REFLECTOR)
    private fun venue(vararg blocks: VenueBlock)=VenueGeometry(stageHeightM=0f,blocks=blocks.toList())
    private fun mesh(b: VenueBlock,reverse: Boolean=false): AudienceSurface {
        val shape=VenueBoundaryGeometry.block(b)
        val mesh=AudienceMesh.create(shape.vertices.map {AudienceMeshVertex(it.x,it.y.toFloat(),it.z)},
            shape.triangles.map {if(reverse) SurfaceTriangle(it.c,it.b,it.a) else it})
            .withBoundaryRoles(List(shape.triangles.size) {b.boundaryRole})
        return AudienceSurface(0,"Imported block","AUDIENCE_SEATED",emptyList(),importedMesh=mesh)
    }
    private fun point(x: Double,y: Double=0.0,z: Double=0.0)=BoundaryPoint(x,y,z)
    private val source=SoundSource(0,3f,0f,0f,180f,0f,Radiator {_,_,_ ->100f})
    private fun power(venue: VenueGeometry?,surfaces: List<AudienceSurface> = emptyList(),floor: FloorReflection?=null)=
        DirectField().bandPower(listOf(source),4f,0f,0f,Bands.segments(1000,1f/3),floor=floor,
            boundaries=BoundaryGeometry.prepare(surfaces,venue=venue))

    @Test fun blocksAndImportedClosedMeshesProduceIdenticalPaths() {
        assertEquals(power(venue(wall)),power(null,listOf(mesh(wall))),1e-6)
        val g=BoundaryGeometry.prepare(emptyList(),venue=venue(wall))
        assertTrue(g.blocked(point(-3.0),point(3.0)))
        assertFalse(g.blocked(point(3.0),point(4.0)))
        assertEquals(1,g.reflections(point(3.0),point(4.0)).size)
        assertEquals(point(-2.0),g.reflections(point(3.0),point(4.0)).single().imageReceiver)
    }
    @Test fun exactDuplicatesAndReversedWindingCannotDoubleReflectionPressure() {
        val expected=power(venue(wall))
        assertEquals(expected,power(venue(wall,wall.copy(id=2))),1e-6)
        assertEquals(expected,power(venue(wall),listOf(mesh(wall),mesh(wall,true))),1e-6)
        assertEquals(expected,power(null,listOf(mesh(wall),mesh(wall,true))),1e-6)
    }
    @Test fun duplicateReflectionWithDifferentTriangulationIsCountedOnce() {
        val vertices=listOf(AudienceMeshVertex(1.0,-5f,-5.0),AudienceMeshVertex(1.0,-5f,5.0),
            AudienceMeshVertex(1.0,5f,5.0),AudienceMeshVertex(1.0,5f,-5.0))
        val m=AudienceMesh.create(vertices,listOf(SurfaceTriangle(0,1,3),SurfaceTriangle(1,2,3)))
            .withBoundaryRoles(List(2) {AcousticBoundaryRole.RIGID_REFLECTOR})
        assertEquals(power(venue(wall)),power(venue(wall),listOf(AudienceSurface(0,"Other tessellation","STAGE",emptyList(),importedMesh=m))),1e-6)
    }
    @Test fun opaqueWinsAtCoincidentBounceRegardlessOfSourceOrOrdering() {
        val opaque=wall.copy(boundaryRole=AcousticBoundaryRole.OCCLUDING)
        val direct=power(venue(opaque))
        assertEquals(direct,power(venue(wall,opaque.copy(id=2))),1e-6)
        assertEquals(direct,power(venue(opaque,wall.copy(id=2))),1e-6)
        assertEquals(direct,power(venue(wall),listOf(mesh(opaque))),1e-6)
        assertEquals(direct,power(venue(opaque),listOf(mesh(wall))),1e-6)
        assertEquals(direct,power(null,listOf(mesh(wall),mesh(opaque))),1e-6)
    }
    @Test fun partialOverlapOnlySuppressesBouncesInsideTheOpaquePatch() {
        val patch=wall.copy(widthM=2f,depthM=2f,centerZ=3f,boundaryRole=AcousticBoundaryRole.OCCLUDING)
        val g=BoundaryGeometry.prepare(emptyList(),venue=venue(wall,patch))
        assertEquals(1,g.reflections(point(3.0),point(4.0)).size)
        assertTrue(g.reflections(point(3.0,z=3.0),point(4.0,z=3.0)).isEmpty())
    }
    @Test fun nearbyButDistinctPlanesAreNotMerged() {
        val other=wall.copy(centerX=0.01f)
        // Only the nearer closed block can reflect: the other path crosses its front face.
        assertEquals(power(venue(other)),power(venue(wall,other)),1e-6)
    }
    @Test fun noBoundaryBlocksAreTransparentAndDoNotSuppressReflectors() {
        val transparent=wall.copy(boundaryRole=AcousticBoundaryRole.NONE)
        assertEquals(power(null),power(venue(transparent)),1e-6)
        assertEquals(power(venue(wall)),power(venue(wall,transparent)),1e-6)
    }
    @Test fun slopedRotatedBlocksMatchTheirPhysicalCornersAndPreserveUnderpasses() {
        val b=wall.copy(widthM=4f,depthM=8f,heightM=6f,blockHeightM=0.5f,slopeDeg=20f,rotationDeg=90f)
        val shape=VenueBoundaryGeometry.block(b)
        val front=shape.vertices[4]
        assertEquals(4.0,front.x,1e-6); assertEquals(-2.0,front.z,1e-6)
        assertEquals(6-4*tan(PI/9),front.y,1e-6)
        assertEquals(0.5,front.y-shape.vertices[0].y,1e-8)
        val g=BoundaryGeometry.prepare(emptyList(),venue=venue(b))
        assertFalse(g.blocked(point(-10.0,1.0),point(10.0,1.0)))
        assertTrue(g.blocked(point(0.0,5.75,-5.0),point(0.0,5.75,5.0)))
    }
    @Test fun rotatedSlopedImportedDuplicatePreservesPredictedPower() {
        val b=wall.copy(slopeDeg=13f,rotationDeg=27f)
        val expected=power(venue(b))
        assertEquals(expected,power(null,listOf(mesh(b))),expected*1e-6)
        assertEquals(expected,power(venue(b),listOf(mesh(b))),expected*1e-6)
        val above=SoundSource(0,0f,8f,0f,0f,0f,Radiator {_,_,_ ->100f})
        fun roof(surfaces: List<AudienceSurface>)=DirectField().bandPower(listOf(above),1f,9f,0f,Bands.segments(1000,1f/3),
            boundaries=BoundaryGeometry.prepare(surfaces,venue=venue(b)))
        assertEquals(roof(emptyList()),roof(listOf(mesh(b))),roof(emptyList())*1e-6)
        val opaque=b.copy(boundaryRole=AcousticBoundaryRole.OCCLUDING)
        val noBounce=DirectField().bandPower(listOf(above),1f,9f,0f,Bands.segments(1000,1f/3))
        assertEquals(noBounce,roof(listOf(mesh(opaque))),noBounce*1e-6)
    }
    @Test fun stageUsesSameGeometryAndIsNotCountedAgainAsAnEquivalentBlock() {
        val v=VenueGeometry(stageCenterX=0f,stageCenterZ=0f,stageWidthM=2f,stageDepthM=10f,stageHeightM=2f,
            stageBoundaryRole=AcousticBoundaryRole.RIGID_REFLECTOR)
        val b=wall.copy(heightM=2f,blockHeightM=2f)
        val s=SoundSource(0,3f,1f,0f,180f,0f,Radiator {_,_,_ ->100f})
        fun field(venue: VenueGeometry)=DirectField().bandPower(listOf(s),4f,1f,0f,Bands.segments(1000,1f/3),
            boundaries=BoundaryGeometry.prepare(emptyList(),venue=venue))
        assertEquals(field(v),field(v.copy(blocks=listOf(b))),1e-6)
        assertEquals(field(v),field(venue(b)),1e-6)
        assertTrue(VenueBoundaryGeometry.stage(v.copy(stageHeightM=0f)).triangles.isEmpty())
        val ramp=VenueBoundaryGeometry.stage(v.copy(stageHeightM=0f,stageSlopeDeg=20f))
        assertTrue(ramp.vertices.all {it.y>=0 && it.z>=0}); assertTrue(ramp.triangles.isNotEmpty())
    }
    @Test fun globalGroundHasOneArrivalAndOpaquePatchSuppressesIt() {
        val b=wall.copy(widthM=20f,depthM=20f,heightM=0f,blockHeightM=1f)
        val s=SoundSource(0,3f,2f,0f,180f,0f,Radiator {_,_,_ ->100f})
        fun field(v: VenueGeometry?,surfaces: List<AudienceSurface> = emptyList(),floor: FloorReflection? = FloorReflection {0.5f})=
            DirectField().bandPower(listOf(s),4f,2f,0f,Bands.segments(1000,1f/3),floor=floor,
                boundaries=BoundaryGeometry.prepare(surfaces,venue=v))
        assertEquals(field(null),field(venue(b),listOf(mesh(b))),1e-6)
        assertEquals(field(null,floor=null),field(venue(b.copy(boundaryRole=AcousticBoundaryRole.OCCLUDING))),1e-6)
    }
    @Test fun predictionSnapshotsBlocksAndHasNoLegacyNearEdgePenalty() {
        val blocks=mutableListOf(wall.copy(boundaryRole=AcousticBoundaryRole.OCCLUDING))
        fun request(v: VenueGeometry?)=PredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),
            listOf(SourceSnapshot(0,-3f,5.1f,0f,0f,ArrayGeometry.poses(1,0.2f,0f,emptyList()),0f,0f,
                FrozenResponse.generic(100f,"generic",false,false))),listOf(ReceiverSnapshot(3f,5.1f,0f)),
            1000,listOf(1000),1f/3,emptyMap(),true,Atmosphere(),venue=v)
        assertEquals(request(null).calculate().levelAt(0),request(venue(blocks[0])).calculate().levelAt(0),0f)
        val frozen=request(venue().copy(blocks=blocks)); val before=frozen.calculate().levelAt(0)
        blocks[0]=blocks[0].copy(heightM=10f); assertEquals(before,frozen.calculate().levelAt(0),0f)
        assertEquals(0f,request(venue().copy(blocks=blocks)).calculate().levelAt(0),0f)
    }
    @Test fun invalidGeometryAndRolesFailBeforeMutationAndLegacyRolesAreOpaque() {
        for (b in listOf(wall.copy(widthM=0f),wall.copy(blockHeightM=Float.NaN),wall.copy(slopeDeg=90f)))
            assertFails {VenueBoundaryGeometry.block(b)}
        assertFails {BoundaryGeometry.prepare(emptyList(),BoundaryWork(limit=1),venue(wall))}
        assertEquals(AcousticBoundaryRole.OCCLUDING,VenueBoundaryJson.role(null))
        for (value in listOf(JsonNull,JsonPrimitive("UNKNOWN"),JsonPrimitive(1))) assertFails {VenueBoundaryJson.role(value)}
        assertFails {ProjectArchive.validateScene("""{"version":17,"venueGeometry":{"blocks":[]}}""")}
        ProjectArchive.validateScene("""{"version":16,"venueGeometry":{"blocks":[{"id":0}]}}""")
        assertFails {VenueBoundaryJson.validatePackage("""{"schema":"droidacoustic.venue","version":8}""")}
    }
}
