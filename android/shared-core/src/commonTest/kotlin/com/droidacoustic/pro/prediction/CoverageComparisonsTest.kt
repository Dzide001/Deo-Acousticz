package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.geometry.*
import kotlin.test.*

class CoverageComparisonsTest {
    private fun mesh(height:Float=0f)=PreparedAudienceGeometry(listOf(
        SurfaceReceiver(0f,height+1.2f,0f,height,"floor"),
        SurfaceReceiver(2f,height+1.2f,0f,height,"floor"),
        SurfaceReceiver(0f,height+1.2f,2f,height,"floor")),listOf(SurfaceTriangle(0,1,2)))
    @Test fun differencesUsePhysicalPowerWithCorrectSignAndAreaWeights() {
        val a=doubleArrayOf(1.0,2.0,4.0);val b=a.map {it*4}.toDoubleArray()
        val result=CoverageComparisons.compare(mesh(),a,b)
        a[0]=1000.0;b[1]=0.0
        repeat(3) {assertEquals(6.0206f,result.differenceAt(it),.0001f)}
        assertEquals(2.0,result.statistics.overall.totalAreaM2,1e-8)
        assertEquals(6.0206,result.statistics.overall.meanDb!!,.0001)
        val reverse=CoverageComparisons.compare(mesh(),doubleArrayOf(4.0,8.0,16.0),doubleArrayOf(1.0,2.0,4.0))
        assertEquals(-6.0206f,reverse.differenceAt(0),.0001f)
    }
    @Test fun zeroAndUnknownDoNotCreateFalseFiniteDifferences() {
        for(value in listOf(0.0,Double.NaN,Double.POSITIVE_INFINITY)) {
            val result=CoverageComparisons.compare(mesh(),doubleArrayOf(value,1.0,1.0),doubleArrayOf(value,1.0,1.0))
            assertTrue(result.differenceAt(0).isNaN())
            assertEquals(0.0,result.statistics.overall.validAreaM2)
            assertEquals(2.0,result.statistics.overall.missingAreaM2)
        }
        // Values below the SPL display floor must still have a meaningful ratio.
        assertEquals(10f,CoverageComparisons.compare(mesh(),DoubleArray(3){1e-30},DoubleArray(3){1e-29}).differenceAt(0),.0001f)
    }
    @Test fun receiverHeightTopologyAssignmentsAndLayersMustMatch() {
        val a=mesh()
        CoverageComparisons.requireCompatible(a,mesh())
        CoverageComparisons.requireCompatible(a,PreparedAudienceGeometry(a.receivers().map {it.copy(name="Renamed")},a.triangles()))
        assertFailsWith<IllegalArgumentException> {CoverageComparisons.requireCompatible(a,mesh(3f))}
        for(points in listOf(a.receivers().map {it.copy(y=it.y+.1f)},a.receivers().map {it.copy(surfaceKey="balcony")},a.receivers().reversed())) {
            assertFailsWith<IllegalArgumentException> {CoverageComparisons.requireCompatible(a,PreparedAudienceGeometry(points,a.triangles()))}
        }
        assertFailsWith<IllegalArgumentException> {CoverageComparisons.requireCompatible(a,PreparedAudienceGeometry(a.receivers(),emptyList()))}
    }
    @Test fun verticalAreaAndNonuniformTrianglesUseActualArea() {
        val points=listOf(SurfaceReceiver(0f,0f,0f,0f,"wall"),SurfaceReceiver(2f,0f,0f,0f,"wall"),SurfaceReceiver(0f,2f,0f,2f,"wall"),
            SurfaceReceiver(0f,0f,3f,0f,"floor"),SurfaceReceiver(4f,0f,3f,0f,"floor"),SurfaceReceiver(0f,0f,5f,0f,"floor"))
        val geometry=PreparedAudienceGeometry(points,listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(3,4,5)))
        val result=CoverageComparisons.compare(geometry,DoubleArray(6){1.0},DoubleArray(6){if(it<3) 10.0 else 1.0})
        assertEquals(6.0,result.statistics.overall.totalAreaM2,1e-8)
        assertEquals(10.0/3,result.statistics.overall.meanDb!!,1e-6)
    }
    @Test fun holesExclusionsAndSamplingCannotBeComparedByArrayIndex() {
        fun ring(size:Double)=listOf(PlanPoint(-size,-size),PlanPoint(size,-size),PlanPoint(size,size),PlanPoint(-size,size))
        fun plan(holes:List<List<PlanPoint>> = emptyList(),spacing:Float=2f)=CoveragePlan(
            listOf(AudienceSurface(0,"Floor","AUDIENCE_SEATED",ring(5.0),holes=holes)),emptyList(),10f,10f,spacing).prepare()
        val original=plan()
        assertFailsWith<IllegalArgumentException> {CoverageComparisons.requireCompatible(original,plan(listOf(ring(1.0))))}
        assertFailsWith<IllegalArgumentException> {CoverageComparisons.requireCompatible(original,plan(spacing=1f))}
        val cut=plan(listOf(ring(1.0)))
        CoverageComparisons.requireCompatible(cut,plan(listOf(ring(1.0))))
        val difference=CoverageComparisons.compare(cut,DoubleArray(cut.receiverCount){1.0},DoubleArray(cut.receiverCount){10.0})
        assertEquals(96.0,difference.statistics.overall.totalAreaM2,1e-5)
        assertEquals(10.0,difference.statistics.overall.meanDb!!,1e-5)
    }
    @Test fun importedFaceExclusionsChangeComparisonDomain() {
        val mesh=AudienceMesh.create(listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(4.0,0f,0.0),
            AudienceMeshVertex(4.0,0f,4.0),AudienceMeshVertex(0.0,0f,4.0)),listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3)))
        fun prepare(m:AudienceMesh)=CoveragePlan(listOf(AudienceSurface(0,"Imported","AUDIENCE_SEATED",emptyList(),importedMesh=m)),emptyList(),10f,10f,2f).prepare()
        CoverageComparisons.requireCompatible(prepare(mesh),prepare(mesh))
        assertFailsWith<IllegalArgumentException> {CoverageComparisons.requireCompatible(prepare(mesh),prepare(mesh.assignFaces(listOf(0),MeshFaceAssignment.EXCLUDED)))}
    }
    @Test fun selectionRoundTripAndInvalidVersion() {
        val choice=CoverageComparisonSelection(2,5)
        assertEquals(choice,CoverageComparisonSelection.fromJsonString(choice.toJsonString()))
        assertFails {CoverageComparisonSelection.fromJsonString("""{"version":2,"baselineId":2,"candidateId":5}""")}
        assertFails {CoverageComparisonSelection(-1,0)}
    }
}
