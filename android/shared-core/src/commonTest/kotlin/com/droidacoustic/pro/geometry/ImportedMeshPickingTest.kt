package com.droidacoustic.pro.geometry

import kotlin.math.*
import kotlin.test.*

class ImportedMeshPickingTest {
    private fun flat()=AudienceMesh.create(listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(4.0,0f,0.0),
        AudienceMeshVertex(4.0,0f,4.0),AudienceMeshVertex(0.0,0f,4.0)),listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3)))
    private fun surface(id: Int=0,height: Float=0f,mesh: AudienceMesh=flat(),rake: Float=0f)=
        AudienceSurface(id,"Mesh $id","AUDIENCE_SEATED",emptyList(),baseHeightM=height,rakeDeg=rake,importedMesh=mesh)
    private fun ray(x: Double=3.0,y: Double=10.0,z: Double=1.0,dx: Double=0.0,dy: Double= -1.0,dz: Double=0.0)=
        ViewportRay(PickPoint3D(x,y,z),PickPoint3D(dx,dy,dz))
    private fun pick(s: AudienceSurface=surface(),ray: ViewportRay=ray())=ImportedMeshPicking.pick(listOf(s),ray)
    @Test fun tapsReturnOriginalTriangleNumbersAndPhysicalHitPositions() {
        val first=pick()!!; val second=pick(ray=ray(x=1.0,z=3.0))!!
        assertEquals(0,first.faceIndex); assertEquals(1,second.faceIndex)
        assertEquals(PickPoint3D(3.0,0.0,1.0),first.position); assertEquals(10.0,first.distanceM,1e-9)
        assertEquals(first,pick(ray=ray(dy= -7.0)))
    }
    @Test fun nearestLayerWinsRegardlessOfIterationOrderOrAssignment() {
        val top=surface(7,3f,flat().assignFaces(listOf(0,1),MeshFaceAssignment.EXCLUDED))
        val bottom=surface(2)
        for (list in listOf(listOf(top,bottom),listOf(bottom,top))) {
            val hit=ImportedMeshPicking.pick(list,ray())!!
            assertEquals(7,hit.areaId); assertEquals(7.0,hit.distanceM,1e-9)
        }
        assertEquals(2,ImportedMeshPicking.pick(listOf(top,bottom),ray(y= -2.0,dy=1.0))!!.areaId)
        assertEquals(7,ImportedMeshPicking.pick(listOf(top,bottom),ray(y=1.0,dy=1.0))!!.areaId)
    }
    @Test fun sharedEdgesVerticesAndCoincidentMeshesHaveStableTieBreaks() {
        for (r in listOf(ray(x=2.0,z=2.0),ray(x=0.0,z=0.0),ray(x=4.0,z=4.0))) {
            assertEquals(0,pick(ray=r)!!.faceIndex)
            for (surfaces in listOf(listOf(surface(8),surface(3)),listOf(surface(3),surface(8)))) {
                val h=ImportedMeshPicking.pick(surfaces,r)!!
                assertEquals(3,h.areaId); assertEquals(0,h.faceIndex)
            }
        }
    }
    @Test fun verticalFacesAndBothWindingsArePickableFromEitherSide() {
        val mesh=AudienceMesh.create(listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(0.0,0f,4.0),
            AudienceMeshVertex(0.0,4f,4.0),AudienceMeshVertex(0.0,4f,0.0)),flat().triangles())
        for (sign in listOf(-1.0,1.0)) {
            val hit=pick(surface(mesh=mesh),ray(x=sign*10,y=1.0,z=3.0,dx= -sign,dy=0.0))!!
            assertEquals(PickPoint3D(0.0,1.0,3.0),hit.position); assertEquals(0,hit.faceIndex)
        }
    }
    @Test fun baseRakeAndMirroredCoordinatesUseTheVisiblePhysicalGeometry() {
        val moved=flat().mapCoordinates {PlanPoint(10-it.x,it.z+20)}
        val s=surface(height=5f,mesh=moved,rake=30f)
        val h=pick(s,ray(x=7.0,y=20.0,z=21.0))!!
        assertEquals(0,h.faceIndex)
        assertEquals(5-tan(PI/6),h.position.y,1e-6)
        assertEquals(20-h.position.y,h.distanceM,1e-6)
    }
    @Test fun parallelCoplanarBehindOutsideAndInvalidRaysMiss() {
        for (r in listOf(ray(dy=1.0),ray(dx=1.0,dy=0.0),ray(y=0.0,dx=1.0,dy=0.0),
                ray(x=5.0),ray(dy=0.0),ray(x=Double.NaN),ray(dy=Double.POSITIVE_INFINITY),
                ray().copy(minDistance= -1.0),ray().copy(maxDistance=Double.NaN),ray().copy(minDistance=20.0,maxDistance=10.0))) {
            assertNull(pick(ray=r))
        }
    }
    @Test fun clippingDistancesDoNotPickInvisibleNearOrFarFaces() {
        assertNull(pick(ray=ray().copy(minDistance=10.1)))
        assertNull(pick(ray=ray().copy(maxDistance=9.9)))
        assertNotNull(pick(ray=ray().copy(minDistance=10.0,maxDistance=10.0)))
    }
    @Test fun intrinsicHolesMissButPlanMasksKeepSourceFacesEditable() {
        val ring=AudienceMesh.create(listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(4.0,0f,0.0),
            AudienceMeshVertex(4.0,0f,4.0),AudienceMeshVertex(0.0,0f,4.0),AudienceMeshVertex(1.0,0f,1.0),
            AudienceMeshVertex(3.0,0f,1.0),AudienceMeshVertex(3.0,0f,3.0),AudienceMeshVertex(1.0,0f,3.0)),
            listOf(SurfaceTriangle(0,1,5),SurfaceTriangle(0,5,4),SurfaceTriangle(1,2,6),SurfaceTriangle(1,6,5),
                SurfaceTriangle(2,3,7),SurfaceTriangle(2,7,6),SurfaceTriangle(3,0,4),SurfaceTriangle(3,4,7)))
        assertNull(pick(surface(mesh=ring),ray(x=2.0,z=2.0)))
        assertNotNull(pick(surface(mesh=ring),ray(x=0.5,z=2.0)))
        val mask=listOf(PlanPoint(1.0,1.0),PlanPoint(3.0,1.0),PlanPoint(3.0,3.0),PlanPoint(1.0,3.0))
        val masked=AudienceSurface(0,"Masked","AUDIENCE_SEATED",emptyList(),importedMesh=flat(),holes=listOf(mask))
        assertNotNull(pick(masked,ray(x=2.0,z=2.0)))
    }
    @Test fun pickingSupportsCancellationAndDoesNotTreatAuthoredPolygonsAsImportedFaces() {
        assertFailsWith<IllegalStateException> {ImportedMeshPicking.pick(listOf(surface()),ray()) {error("cancel")}}
        assertNull(ImportedMeshPicking.pick(emptyList(),ray()))
        val authored=AudienceSurface(0,"Floor","AUDIENCE_SEATED",flat().boundary())
        assertNull(pick(authored))
    }
    @Test fun singleToggleAndFlatPatchSelectionPreserveOriginalIndicesWithoutMutatingInputs() {
        val m=flat(); val initial=setOf(0)
        assertEquals(setOf(1),ImportedMeshPicking.select(m,initial,1,FacePickMode.SINGLE))
        val both=ImportedMeshPicking.select(m,initial,1,FacePickMode.TOGGLE)
        assertEquals(setOf(0,1),both); assertEquals(setOf(0),initial)
        assertEquals(setOf(1),ImportedMeshPicking.select(m,both,0,FacePickMode.TOGGLE))
        assertEquals(emptySet(),ImportedMeshPicking.select(m,initial,0,FacePickMode.TOGGLE))
        assertEquals(setOf(0,1),ImportedMeshPicking.select(m,emptySet(),1,FacePickMode.FLAT_PATCH))
        val assigned=m.assignFaces(both,MeshFaceAssignment.EXCLUDED)
        assertEquals(m.triangles(),assigned.triangles()); assertTrue(assigned.faceAssignments().all {it==MeshFaceAssignment.EXCLUDED})
        assertTrue(m.faceAssignments().all {it==MeshFaceAssignment.FOLLOW_ZONE})
        assertFailsWith<IllegalArgumentException> {ImportedMeshPicking.select(m,setOf(2),0,FacePickMode.SINGLE)}
        assertFailsWith<IllegalArgumentException> {ImportedMeshPicking.select(m,emptySet(),-1,FacePickMode.TOGGLE)}
    }
    private fun screen(x: Double=100.0,y: Double=50.0,eye: PickPoint3D=PickPoint3D(2.0,10.0,2.0),
        target: PickPoint3D=PickPoint3D(2.0,0.0,2.0),up: PickPoint3D=PickPoint3D(0.0,0.0,-1.0))=
        ViewportRay.perspective(x,y,200.0,100.0,eye,target,up,90.0)
    @Test fun sharedProjectionMapsPlanSectionAndPerspectiveTapsToGeometry() {
        val center=screen()!!
        assertEquals(PickPoint3D(0.0,-1.0,0.0),center.direction)
        assertEquals(0,pick(ray=center)!!.faceIndex)
        val upperRight=screen(110.0,45.0)!!
        assertEquals(3.9999999999999996,pick(ray=upperRight)!!.position.x,1e-8)
        assertEquals(1.0,pick(ray=upperRight)!!.position.z,1e-8)
        val perspective=screen(eye=PickPoint3D(3.0,10.0,11.0),target=PickPoint3D(3.0,0.0,1.0),up=PickPoint3D(0.0,1.0,0.0))!!
        assertEquals(PickPoint3D(3.0,0.0,1.0),pick(ray=perspective)!!.position)
        val section=screen(eye=PickPoint3D(10.0,1.0,2.0),target=PickPoint3D(0.0,1.0,2.0),up=PickPoint3D(0.0,1.0,0.0))!!
        assertEquals(PickPoint3D(-1.0,0.0,0.0),section.direction)
    }
    @Test fun perspectiveClippingUsesCameraDepthAtScreenEdges() {
        val r=screen(200.0,0.0)!!
        assertEquals(0.1,r.minDistance* -r.direction.y,1e-9)
        assertEquals(200.0,r.maxDistance* -r.direction.y,1e-9)
        assertNull(screen(-1.0)); assertNull(screen(y=101.0))
        assertNull(screen(target=PickPoint3D(2.0,10.0,2.0)))
        assertNull(screen(up=PickPoint3D(0.0,1.0,0.0)))
        assertNull(ViewportRay.perspective(0.0,0.0,0.0,10.0,PickPoint3D(0.0,0.0,0.0),PickPoint3D(0.0,0.0,-1.0),PickPoint3D(0.0,1.0,0.0),45.0))
    }
}
