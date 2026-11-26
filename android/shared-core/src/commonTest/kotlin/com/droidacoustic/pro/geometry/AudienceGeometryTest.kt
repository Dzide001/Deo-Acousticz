package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.scene.*
import kotlin.math.*
import kotlin.test.*

class AudienceGeometryTest {
    private fun rectangle(x0: Double = 0.0, z0: Double = 0.0, x1: Double = 4.0, z1: Double = 4.0) =
        listOf(PlanPoint(x0,z0),PlanPoint(x1,z0),PlanPoint(x1,z1),PlanPoint(x0,z1))
    private fun surface(id: Int = 0, height: Float = 0f, vertices: List<PlanPoint> = rectangle()) =
        AudienceSurface(id,"Floor $id","AUDIENCE_SEATED",vertices,height)
    private fun plan(vararg surfaces: AudienceSurface, spacing: Float = 2f) =
        CoveragePlan(surfaces.toList(),emptyList(),28f,28f,spacing)

    @Test fun coincidentFloorAndBalconyKeepIndependentVerticesAndListeningHeights() {
        val mesh=plan(surface(),surface(1,5f)).prepare()
        val grouped=mesh.receivers().groupBy { it.areaId }
        assertEquals(grouped[0]!!.map { it.x to it.z },grouped[1]!!.map { it.x to it.z })
        assertTrue(grouped[0]!!.all { it.y == 1.2f && it.surfaceY == 0f })
        assertTrue(grouped[1]!!.all { it.y == 6.2f && it.surfaceY == 5f })
        mesh.triangles().forEach { face ->
            assertEquals(1,listOf(face.a,face.b,face.c).map { mesh.receiver(it).surfaceKey }.distinct().size)
        }
    }
    @Test fun fullVenuePreviewAndRemoteAreasAreNotLimitedToOldGrid() {
        val preview=CoveragePlan(emptyList(),emptyList(),60f,80f,3f).prepare().receivers()
        assertEquals(-30f,preview.minOf { it.x }); assertEquals(30f,preview.maxOf { it.x })
        assertEquals(-40f,preview.minOf { it.z }); assertEquals(40f,preview.maxOf { it.z })
        val remote=plan(surface(vertices=rectangle(40.0,50.0,45.0,55.0))).prepare()
        assertEquals(40f,remote.receivers().minOf { it.x }); assertEquals(55f,remote.receivers().maxOf { it.z })
        assertTrue(remote.receivers().all { it.areaId == 0 })
    }
    @Test fun concavePolygonIsClippedWithoutBridgingItsNotch() {
        val poly=listOf(PlanPoint(0.0,0.0),PlanPoint(4.0,0.0),PlanPoint(4.0,1.0),
            PlanPoint(1.0,1.0),PlanPoint(1.0,4.0),PlanPoint(0.0,4.0))
        val mesh=plan(surface(vertices=poly)).prepare()
        val area=mesh.triangles().sumOf { t ->
            val a=mesh.receiver(t.a); val b=mesh.receiver(t.b); val c=mesh.receiver(t.c)
            assertTrue(PolygonGeometry.contains(poly,PlanPoint((a.x+b.x+c.x)/3.0,(a.z+b.z+c.z)/3.0)))
            abs((b.x-a.x).toDouble()*(c.z-a.z)-(b.z-a.z)*(c.x-a.x))/2
        }
        assertEquals(7.0,area,1e-5)
    }
    @Test fun narrowAndRotatedPolygonsSurviveCoarseSampling() {
        val poly=rectangle(0.1,0.2,0.3,0.4).map { PlanPoint(it.x-it.z,it.x+it.z) }
        val mesh=plan(surface(vertices=poly),spacing=3f).prepare()
        assertTrue(mesh.triangles().isNotEmpty())
        // Output uses Float coordinates; check centroids and area, not exact Double boundary equality.
        val area=mesh.triangles().sumOf { t ->
            val a=mesh.receiver(t.a); val b=mesh.receiver(t.b); val c=mesh.receiver(t.c)
            assertTrue(PolygonGeometry.contains(poly,PlanPoint((a.x+b.x+c.x)/3.0,(a.z+b.z+c.z)/3.0)))
            abs((b.x-a.x).toDouble()*(c.z-a.z)-(b.z-a.z)*(c.x-a.x))/2
        }
        assertEquals(0.08,area,1e-6)
    }
    @Test fun rakedStandingSurfaceUsesWorldDirectionAndUnclampedElevation() {
        val slope=AudienceSurface(0,"High balcony","AUDIENCE_STANDING",rectangle(),35f,10f,90f)
        val mesh=plan(slope).prepare()
        mesh.receivers().forEach { r ->
            val expected=35f+tan(10*PI/180).toFloat()*(r.x-2)
            assertEquals(expected,r.surfaceY,1e-5f)
            assertEquals(expected+1.6f,r.y,1e-5f)
            assertEquals(slope.receiverY(r.x,r.z),r.y)
        }
    }
    @Test fun manualProbesAddToSurfacesAndNonAudienceZonesDoNotBecomeReceivers() {
        val probe=SurfaceReceiver(10f,9f,10f,9f,"probe:8")
        val stage=AudienceSurface(3,"Stage","STAGE",rectangle())
        val mesh=CoveragePlan(listOf(surface(),stage),listOf(probe),28f,28f,2f).prepare()
        assertEquals(probe,mesh.receivers().last())
        assertTrue(mesh.receivers().any { it.areaId==0 })
        assertFalse(mesh.receivers().any { it.areaId==3 })
        assertTrue(mesh.triangles().all { mesh.receiverCount-1 !in listOf(it.a,it.b,it.c) })
    }
    @Test fun invalidPolygonsAndExceededBudgetsFailWithoutPartialMesh() {
        assertFailsWith<IllegalArgumentException> { plan(surface(vertices=listOf(
            PlanPoint(0.0,0.0),PlanPoint(4.0,4.0),PlanPoint(4.0,0.0),PlanPoint(0.0,4.0)))).prepare() }
        assertFailsWith<IllegalArgumentException> { plan(surface(vertices=listOf(
            PlanPoint(0.0,0.0),PlanPoint(1.0,0.0),PlanPoint(2.0,0.0)))).prepare() }
        assertFailsWith<IllegalArgumentException> { CoveragePlan(listOf(surface()),emptyList(),28f,28f,1f,3).prepare() }
        assertFailsWith<IllegalArgumentException> { plan(surface(),surface()).prepare() }
    }
    @Test fun preparationOwnsCallerCollectionsAndSupportsCancellation() {
        val vertices=rectangle().toMutableList(); val surfaces=mutableListOf(surface(vertices=vertices))
        val plan=CoveragePlan(surfaces,emptyList(),28f,28f,1f)
        val before=plan.prepare().receivers()
        vertices.clear(); surfaces.clear()
        assertEquals(before,plan.prepare().receivers())
        var checkpoints=0
        assertFailsWith<IllegalStateException> { plan.prepare { if (++checkpoints == 3) error("cancelled") } }
        assertEquals(before,plan.prepare().receivers())
    }
    @Test fun contoursRemainOnSeparateRakedSurfaces() {
        val balcony=AudienceSurface(1,"Balcony","AUDIENCE_SEATED",rectangle(),5f,10f,0f)
        val mesh=plan(surface(),balcony).prepare()
        val contours=SurfaceContours.build(mesh,FloatArray(mesh.receiverCount) { 100-mesh.receiver(it).x },listOf(98f))
        assertEquals(setOf("area:0","area:1"),contours.map { it.surfaceKey }.toSet())
        contours.forEach { line ->
            assertEquals(2f,line.x0,1e-5f); assertEquals(2f,line.x1,1e-5f)
            val s=if (line.surfaceKey=="area:1") balcony else surface()
            assertEquals(s.surfaceY(line.x0,line.z0),line.y0,1e-5f)
            assertEquals(s.surfaceY(line.x1,line.z1),line.y1,1e-5f)
        }
        assertEquals(8f,contours.sumOf { abs(it.z1-it.z0).toDouble() }.toFloat(),1e-5f)
    }
    @Test fun contoursDoNotInterpolateAcrossUnavailableSamples() {
        val mesh=plan(surface()).prepare()
        assertTrue(SurfaceContours.build(mesh,FloatArray(mesh.receiverCount) { Float.NaN },listOf(90f)).isEmpty())
        assertTrue(SurfaceContours.build(mesh,FloatArray(mesh.receiverCount) { 90f },listOf(90f)).isEmpty())
    }
    @Test fun predictionUsesEachLayersPhysicalReceiverHeight() {
        val plan=plan(surface(),surface(1,5f))
        val source=SourceSnapshot(0,-10f,1.2f,0f,0f,ArrayGeometry.poses(1,0.2f,0f,emptyList()),0f,0f,
            FrozenResponse.generic(100f,"generic",false,false))
        val request=PredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),listOf(source),emptyList(),
            1000,listOf(1000),0f,emptyMap(),true,Atmosphere(),coveragePlan=plan)
        val output=request.calculate(); val mesh=output.geometry!!
        val floor=mesh.receivers().indexOfFirst { it.x==0f && it.z==0f && it.areaId==0 }
        val upper=mesh.receivers().indexOfFirst { it.x==0f && it.z==0f && it.areaId==1 }
        val expected=20*log10(sqrt(125.0)/10)+Atmosphere().absorptionDbPerMetre(1000.0)*(sqrt(125.0)-10)
        assertEquals(expected.toFloat(),output.levelAt(floor)-output.levelAt(upper),1e-4f)
    }
    @Test fun obstructionSnapshotOwnsBlocksAndRespectsElevation() {
        val blocks=mutableListOf(VenueBlock(1,centerX=0f,centerZ=0f,widthM=2f,depthM=4f,heightM=3f,blockHeightM=3f))
        val obstruction=BoundaryGeometry.prepare(emptyList(),venue=VenueGeometry(stageHeightM=0f,blocks=blocks))
        val before=obstruction.blocked(BoundaryPoint(-5.0,1.0,0.0),BoundaryPoint(5.0,1.0,0.0))
        assertTrue(before)
        assertFalse(obstruction.blocked(BoundaryPoint(-5.0,6.0,0.0),BoundaryPoint(5.0,6.0,0.0)))
        blocks.clear()
        assertEquals(before,obstruction.blocked(BoundaryPoint(-5.0,1.0,0.0),BoundaryPoint(5.0,1.0,0.0)))
    }
    @Test fun elevatedBalconySolidDoesNotBlockClearPathUnderneath() {
        val block=VenueBlock(1,centerX=0f,centerZ=0f,widthM=4f,depthM=4f,heightM=6f,blockHeightM=0.5f)
        val geometry=BoundaryGeometry.prepare(emptyList(),venue=VenueGeometry(stageHeightM=0f,blocks=listOf(block)))
        assertFalse(geometry.blocked(BoundaryPoint(-5.0,1.2,0.0),BoundaryPoint(5.0,1.2,0.0)))
        assertTrue(geometry.blocked(BoundaryPoint(-5.0,5.75,0.0),BoundaryPoint(5.0,5.75,0.0)))
    }

}
