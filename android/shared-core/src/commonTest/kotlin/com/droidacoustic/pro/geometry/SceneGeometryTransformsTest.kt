package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.scene.VenueBlock
import kotlin.math.abs
import kotlin.test.*

class SceneGeometryTransformsTest {
    private fun square(a: Double,b: Double)=listOf(PlanPoint(a,a),PlanPoint(b,a),PlanPoint(b,b),PlanPoint(a,b))
    @Test fun rigidYawAndTranslationPreserveHeightsCutoutsAndIndexedConstraints() {
        val line=AudienceBreakline(AudienceVertexRef(AudienceVertexKind.BOUNDARY,0),AudienceVertexRef(AudienceVertexKind.INTERIOR,0))
        val original=AudienceSurface(1,"Terrace","AUDIENCE_SEATED",square(0.0,10.0),3f,12f,30f,
            holes=listOf(square(1.0,2.0)),exclusions=listOf(square(7.0,8.0)),
            vertexOffsetsM=listOf(0f,1f,2f,1f),elevationPoints=listOf(AudienceElevationPoint(5.0,5.0,2f)),breaklines=listOf(line))
        val transformed=SceneGeometryTransforms.transform(original,2f,4f,-3f,90f)
        assertEquals(12.0,transformed.vertices().first().x,1e-10)
        assertEquals(-3.0,transformed.vertices().first().z,1e-10)
        assertEquals(original.vertexOffsetsM(),transformed.vertexOffsetsM())
        assertEquals(original.breaklines(),transformed.breaklines())
        assertEquals(7.0,SceneGeometryTransforms.center(transformed).x,1e-10)
        assertEquals(2.0,SceneGeometryTransforms.center(transformed).z,1e-10)
        assertEquals(7f,transformed.baseHeightM)
        assertEquals(-60f,transformed.rakeDirectionDeg)
        for (p in listOf(PlanPoint(3.0,4.0),PlanPoint(6.0,5.0))) {
            val x=(12-p.z).toFloat(); val z=(p.x-3).toFloat()
            assertEquals(original.surfaceY(p.x.toFloat(),p.z.toFloat())+4f,transformed.surfaceY(x,z),1e-4f)
        }
        val restored=SceneGeometryTransforms.transform(transformed,-2f,-4f,3f,-90f)
        original.vertices().zip(restored.vertices()).forEach {(a,b) -> assertTrue(abs(a.x-b.x)<1e-10 && abs(a.z-b.z)<1e-10)}
        original.holes().flatten().zip(restored.holes().flatten()).forEach {(a,b) -> assertEquals(a.x,b.x,1e-10);assertEquals(a.z,b.z,1e-10)}
        original.exclusions().flatten().zip(restored.exclusions().flatten()).forEach {(a,b) -> assertEquals(a.x,b.x,1e-10);assertEquals(a.z,b.z,1e-10)}
    }
    @Test fun verticalAndOverhangingMeshPreservesTopologyAndFaceSemantics() {
        val mesh=AudienceMesh.create(listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(2.0,0f,0.0),
            AudienceMeshVertex(0.0,3f,0.0),AudienceMeshVertex(0.0,3f,2.0)),
            listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(2,1,3)))
            .assignFaces(listOf(0),MeshFaceAssignment.EXCLUDED)
            .createFaceGroup("Wall",listOf(0))
            .assignBoundaryRoles(listOf(0,1),AcousticBoundaryRole.OCCLUDING)
        val original=AudienceSurface(1,"Import","AUDIENCE_SEATED",mesh.boundary(),importedMesh=mesh)
        val moved=SceneGeometryTransforms.transform(original,2f,5f,3f,90f)
        val next=moved.importedMesh!!
        assertEquals(mesh.triangles(),next.triangles());assertEquals(mesh.boundaryLoops(),next.boundaryLoops())
        assertEquals(mesh.faceAssignments(),next.faceAssignments());assertEquals(mesh.faceGroups(),next.faceGroups())
        assertEquals(mesh.boundaryRoles(),next.boundaryRoles());assertEquals(mesh.boundaryMaterials(),next.boundaryMaterials())
        assertEquals(mesh.vertices().map {it.y},next.vertices().map {it.y})
        assertEquals(5f,moved.baseHeightM)
        mesh.vertices().zip(next.vertices()).forEach {(a,b) -> assertEquals(a.y+5,moved.baseHeightM+b.y)}
    }
    @Test fun blockElevationPreservesThicknessAndInvalidTransformsFail() {
        val block=VenueBlock(4,heightM=5f,blockHeightM=2f,widthM=8f,depthM=4f,slopeDeg=12f)
        val moved=SceneGeometryTransforms.blockAt(block,4f,8f,6f,450f)
        assertEquals(block.copy(centerX=4f,heightM=8f,centerZ=6f,rotationDeg=90f),moved)
        assertFailsWith<IllegalArgumentException> { SceneGeometryTransforms.blockAt(block,Float.NaN,0f,0f,0f) }
        val surface=AudienceSurface(0,"Floor","AUDIENCE_SEATED",square(0.0,4.0))
        assertFailsWith<IllegalArgumentException> { SceneGeometryTransforms.transform(surface,yawDeg=Float.POSITIVE_INFINITY) }
    }
}
