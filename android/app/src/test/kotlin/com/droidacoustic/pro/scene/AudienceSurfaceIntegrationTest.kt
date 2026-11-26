package com.droidacoustic.pro.scene

import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.ui.HeatmapGlb
import com.droidacoustic.pro.ui.ContoursGlb
import com.droidacoustic.pro.ui.AudienceAreasGlb
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class AudienceSurfaceIntegrationTest {
    @get:Rule val folder=TemporaryFolder()
    private val scheduler=TestCoroutineScheduler()
    private val dispatcher=StandardTestDispatcher(scheduler)
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() { models.forEach { it.cancelAnalysisJobs() }; scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun vm(cache: GeometryCache = GeometryCache())=SceneViewModel(PredictionRunner { it.calculate() },dispatcher,cache).also { models+=it }
    private fun populate(v: SceneViewModel, high: Float=5f, rake: Float=0f) {
        v.addSpeaker(-8f,0f)
        val json=JSONObject(v.exportSceneJson())
        val areas=JSONArray()
        for (id in 0..1) {
            val vertices=JSONArray()
            listOf(0f to 0f,4f to 0f,4f to 4f,0f to 4f).forEach { (x,z) -> vertices.put(JSONObject().put("x",x).put("z",z)) }
            areas.put(JSONObject().put("id",id).put("name",if (id==0) "Floor" else "Balcony")
                .put("zoneType","AUDIENCE_SEATED").put("baseHeightM",if (id==0) 0f else high)
                .put("rakeDeg",if (id==0) 0f else rake).put("vertices",vertices))
        }
        json.put("audienceAreas",areas)
        assertTrue(v.importSceneJson(json.toString()))
    }
    @Test fun androidPublishesSeparateLayersAndAddingProbeDoesNotSuppressCoverage() {
        val v=vm(); populate(v); v.addAudiencePoint(12f,12f)
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(PredictionState.CURRENT,v.predictionState.value)
        val mesh=v.coverageGeometry.value!!; val cells=v.heatmap.value
        assertEquals(mesh.receiverCount,cells.size)
        assertEquals(setOf(0,1),cells.mapNotNull { it.sourceAreaId }.toSet())
        assertTrue(cells.any { it.surfaceKey?.startsWith("probe:")==true })
        val floor=cells.first { it.x==0f && it.z==0f && it.sourceAreaId==0 }
        val balcony=cells.first { it.x==0f && it.z==0f && it.sourceAreaId==1 }
        assertEquals(1.2f,floor.receiverY!!,0f); assertEquals(6.2f,balcony.receiverY!!,0f)
        assertEquals(0f,floor.renderY,0f); assertEquals(5f,balcony.renderY,0f)
        assertNotEquals(floor.splDb,balcony.splDb)
        v.setAudienceAreaBaseHeight(1,8f)
        assertNull(v.coverageGeometry.value); assertTrue(v.heatmap.value.isEmpty())
    }
    @Test fun saveAndReopenPreserveHighRakedSurfacesAndProbeHeight() {
        val original=vm(); populate(original,35f,10f)
        val json=JSONObject(original.exportSceneJson()).put("audience",JSONArray().put(JSONObject()
            .put("id",9).put("x",2f).put("z",2f).put("earHeightM",36.2f)))
        assertTrue(original.importSceneJson(json.toString()))
        val before=original.captureCoveragePlan().prepare()
        val store=LocalProjectStore(folder.root)
        store.save("balconies",original.exportSceneJson(false),original::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("balconies")!!.project))
        val after=fresh.captureCoveragePlan().prepare()
        assertEquals(before.receivers(),after.receivers()); assertEquals(before.triangles(),after.triangles())
        assertTrue(after.receivers().filter { it.areaId==1 }.all { it.y>30f })
        assertEquals(36.2f,after.receivers().last().y,0f)
    }
    @Test fun capturedCoverageRequestIsUnaffectedByLaterSurfaceEdits() {
        val v=vm(); populate(v)
        val request=v.capturePredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),v.speakers.value,
            emptyList(),coveragePlan=v.captureCoveragePlan())
        val before=request.calculate()
        v.setAudienceAreaBaseHeight(1,15f); v.removeAudienceArea(0)
        val after=request.calculate()
        assertEquals(before.geometry!!.receivers(),after.geometry!!.receivers())
        for (i in 0 until after.geometry!!.receiverCount) assertEquals(before.levelAt(i),after.levelAt(i),0f)
    }
    @Test fun invalidPolygonFailsCoverageWithoutPublishingMisleadingMesh() {
        val v=vm(); populate(v)
        val json=JSONObject(v.exportSceneJson())
        json.getJSONArray("audienceAreas").getJSONObject(0).put("vertices",JSONArray().put(JSONObject().put("x",0).put("z",0)))
        assertTrue(v.importSceneJson(json.toString()))
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(PredictionState.FAILED,v.predictionState.value)
        assertNull(v.coverageGeometry.value); assertTrue(v.heatmap.value.isEmpty())
    }
    @Test fun acousticEditsReuseGeometryButSurfaceAndSamplingEditsRebuildIt() {
        val cache=GeometryCache(); val v=vm(cache); populate(v)
        v.recalculateSignal(); scheduler.runCurrent()
        val first=v.coverageGeometry.value!!
        val initial=v.coverageStatistics.value!!.overall.meanDb!!
        val before=cache.stats()
        v.setSignalLevelDbu(6f); v.recalculateSignal(); scheduler.runCurrent()
        assertSame(first,v.coverageGeometry.value)
        assertEquals(initial+6,v.coverageStatistics.value!!.overall.meanDb!!,1e-4)
        assertTrue(cache.stats().hits>before.hits)
        v.setBandHz(2000); v.recalculateSignal(); scheduler.runCurrent()
        assertSame(first,v.coverageGeometry.value)
        v.setAudienceAreaBaseHeight(1,8f); v.recalculateSignal(); scheduler.runCurrent()
        val raised=v.coverageGeometry.value!!; assertNotSame(first,raised)
        v.setAnalysisProfile("Precision"); v.recalculateSignal(); scheduler.runCurrent()
        assertNotSame(raised,v.coverageGeometry.value)
        assertTrue(v.coverageGeometry.value!!.receiverCount>raised.receiverCount)
    }
    @Test fun statisticsReportAndContourReferenceUseSharedAreaResultsAndInvalidateTogether() {
        val v=vm(); populate(v); v.addAudiencePoint(40f,40f)
        v.recalculateSignal(); scheduler.runCurrent()
        val statistics=v.coverageStatistics.value!!
        assertEquals(32.0,statistics.overall.totalAreaM2,1e-5)
        assertEquals(2,statistics.surfaces().size)
        assertEquals(statistics.overall.p95Db!!.toFloat(),v.contourReferenceDb(v.heatmap.value)!!,0f)
        val report=JSONObject(v.exportProjectReportJson()).getJSONObject("coverageStatistics")
        assertEquals(statistics.overall.meanDb!!,report.getJSONObject("overall").getDouble("meanDb"),0.0)
        assertEquals(2,report.getJSONArray("surfaces").length())
        v.setSignalLevelDbu(2f)
        assertNull(v.coverageStatistics.value)
        assertFalse(JSONObject(v.exportProjectReportJson()).has("coverageStatistics"))
    }
    @Test fun targetWindowIsIndependentOfDisplaySpanAndReusesGeometry() {
        val v=vm(); populate(v); v.setSplTargetDb(90f)
        v.recalculateSignal(); scheduler.runCurrent()
        val first=v.coverageGeometry.value
        val statistics=v.coverageStatistics.value!!.overall
        assertEquals(87.0,statistics.target!!.minDb,0.0)
        assertEquals(93.0,statistics.target!!.maxDb,0.0)
        assertNotNull(statistics.withinTargetPercent)
        v.setSplSpanDb(30f); v.recalculateSignal(); scheduler.runCurrent()
        assertSame(first,v.coverageGeometry.value)
        assertEquals(statistics.withinTargetPercent!!,v.coverageStatistics.value!!.overall.withinTargetPercent!!,0.0)
    }
    private fun cutout()=listOf(1f to 1f,3f to 1f,3f to 3f,1f to 3f)
    @Test fun drawingHoleUpdatesCoverageAndDiskReopenPreservesCutouts() {
        val v=vm(); populate(v)
        v.beginAudienceCutout(0,true)
        cutout().forEach { (x,z) -> v.addAreaVertex(x,z) }
        v.closeAreaFromDraft()
        assertNull(v.cutoutTarget.value); assertTrue(v.areaDraft.value.isEmpty())
        assertEquals(listOf(cutout()),v.audienceAreas.value.first().holes)
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(28.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-5)
        val before=v.coverageGeometry.value!!
        val store=LocalProjectStore(folder.root)
        store.save("holes",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("holes")!!.project))
        assertEquals(v.audienceAreas.value,fresh.audienceAreas.value)
        val after=fresh.captureCoveragePlan().prepare()
        assertEquals(before.receivers(),after.receivers()); assertEquals(before.triangles(),after.triangles())
        assertEquals(SceneViewModel.SCENE_SCHEMA_VERSION,JSONObject(fresh.exportSceneJson()).getInt("version"))
    }
    @Test fun exclusionsInvalidateCacheAndParticipateInUndoRedoAndRemoval() {
        val v=vm(); populate(v); v.recalculateSignal(); scheduler.runCurrent()
        val original=v.coverageGeometry.value
        val crossing=listOf(-1f to -1f,1f to -1f,1f to 5f,-1f to 5f)
        assertTrue(v.addAudienceCutout(0,crossing,false))
        assertNull(v.coverageStatistics.value)
        v.recalculateSignal(); scheduler.runCurrent()
        assertNotSame(original,v.coverageGeometry.value)
        assertEquals(28.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-5)
        assertTrue(v.undoScene()); assertTrue(v.audienceAreas.value.first().exclusions.isEmpty())
        assertTrue(v.redoScene()); assertEquals(listOf(crossing),v.audienceAreas.value.first().exclusions)
        v.removeAudienceCutout(0,0,false); v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(32.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-5)
        assertSame(original,v.coverageGeometry.value)
    }
    @Test fun cutoutsMoveWithRotationMirroringAndDuplication() {
        val v=vm(); populate(v); assertTrue(v.addAudienceCutout(0,cutout(),true))
        v.setAudienceAreaRotation(0,90f)
        val rotated=v.audienceAreas.value.first()
        assertEquals(3f,rotated.holes.single().first().first,1e-5f)
        assertEquals(1f,rotated.holes.single().first().second,1e-5f)
        v.mirrorAudienceAreaX(0,null)
        val mirrored=v.audienceAreas.value.first()
        assertEquals(-3f,mirrored.holes.single().first().first,1e-5f)
        v.duplicateAudienceArea(0)
        val clone=v.audienceAreas.value.last()
        assertEquals(mirrored.holes.single().map { (x,z) -> x+1f to z+1f },clone.holes.single())
        val geometry=v.captureCoveragePlan().prepare()
        assertEquals(3,geometry.surfaces().size)
        assertEquals(40.0,com.droidacoustic.pro.statistics.CoverageStatisticsCalculator.calculate(
            geometry,FloatArray(geometry.receiverCount) {90f}).overall.totalAreaM2,1e-4)
    }
    @Test fun invalidCutoutEditOrImportPreservesExistingProject() {
        val v=vm(); populate(v)
        val before=v.exportSceneJson()
        assertFalse(v.addAudienceCutout(0,listOf(0f to 0f,2f to 0f,2f to 2f,0f to 2f),true))
        assertNotNull(v.audienceGeometryError.value)
        assertEquals(before,v.exportSceneJson())
        val bad=JSONObject(before)
        bad.getJSONArray("audienceAreas").getJSONObject(0).put("holes",JSONArray().put(JSONObject()
            .put("vertices",JSONArray().put(JSONObject().put("x",1).put("z",1)))))
        assertFalse(v.importSceneJson(bad.toString())); assertEquals(before,v.exportSceneJson())
        val old=JSONObject(before).put("version",8)
        for (i in 0 until old.getJSONArray("audienceAreas").length()) {
            old.getJSONArray("audienceAreas").getJSONObject(i).remove("holes")
            old.getJSONArray("audienceAreas").getJSONObject(i).remove("exclusions")
        }
        assertTrue(v.importSceneJson(old.toString()))
        assertTrue(v.audienceAreas.value.all { it.holes.isEmpty() && it.exclusions.isEmpty() })
    }
    @Test fun androidMeshesContoursAndOutlinesRetainHoleBoundaries() {
        val v=vm(); populate(v); assertTrue(v.addAudienceCutout(0,cutout(),true))
        val geometry=v.captureCoveragePlan().prepare()
        val values=geometry.receivers().mapIndexed { i,r -> HeatCell(r.x,r.z,100-r.x,
            sourceAreaId=r.areaId,renderY=r.surfaceY,sampleIndex=i,surfaceKey=r.surfaceKey) }
        val field=decode(HeatmapGlb.build(values,90f,100f,geometry=geometry)!!)
        assertEquals(geometry.triangleCount*3,field.positions.size)
        field.positions.chunked(3).filter { it.first()[1]<1f }.forEach { triangle ->
            val x=triangle.map { it[0] }.average(); val z=triangle.map { it[2] }.average()
            assertFalse(x>1 && x<3 && z>1 && z<3)
        }
        val contours=decode(ContoursGlb.build(values,listOf(98f),geometry=geometry)!!)
        assertTrue(contours.positions.filter { it[1]<1 }.all { it[2]<=1f || it[2]>=3f })
        val outline=decode(AudienceAreasGlb.build(v.audienceAreas.value,emptyList())!!)
        assertEquals(24,outline.positions.size)
        assertEquals(16,outline.positions.count { kotlin.math.abs(it[1]-0.06f)<1e-5f })
    }
    @Test fun auxiliarySamplingUsesSharedCutoutMembershipAndListeningHeight() {
        val v=vm(); populate(v,5f,10f); assertTrue(v.addAudienceCutout(1,cutout(),true))
        val area=v.audienceAreas.value.first { it.id==1 }
        val plane=area.sharedSurface()
        assertFalse(plane.containsAudiencePoint(2f,2f)); assertTrue(plane.containsAudiencePoint(0f,0f))
        val grid=com.droidacoustic.pro.optimizer.CoverageGridBuilder.buildGrid(emptyList(),listOf(area),1f)
        assertTrue(grid.isNotEmpty())
        grid.forEach { assertTrue(plane.containsAudiencePoint(it.x,it.z))
            assertEquals(plane.receiverY(it.x,it.z),it.earHeightM,0f) }
    }
    @Test fun interiorElevationPublishesNonplanarMeshAndAreaAndInvalidatesCache() {
        val v=vm(); populate(v); v.recalculateSignal(); scheduler.runCurrent()
        val flat=v.coverageGeometry.value
        v.beginAudienceElevationPoint(0)
        assertEquals(0,v.elevationTarget.value)
        assertTrue(v.addAudienceElevationPoint(0,2f,2f))
        assertNull(v.elevationTarget.value)
        assertTrue(v.setAudienceElevationPointOffset(0,0,2f))
        assertNull(v.coverageStatistics.value)
        v.recalculateSignal(); scheduler.runCurrent()
        val m=v.coverageGeometry.value!!
        assertNotSame(flat,m)
        val peak=v.heatmap.value.first { it.sourceAreaId==0 && it.x==2f && it.z==2f }
        assertEquals(2f,peak.renderY,1e-5f); assertEquals(3.2f,peak.receiverY!!,1e-5f)
        assertEquals(16+16*kotlin.math.sqrt(2.0),v.coverageStatistics.value!!.overall.totalAreaM2,1e-4)
        v.setSignalLevelDbu(3f); v.recalculateSignal(); scheduler.runCurrent()
        assertSame(m,v.coverageGeometry.value)
        v.resetAudienceElevations(0); v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(32.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-5)
    }
    @Test fun nonplanarPersistenceUndoAndLegacyMigrationPreserveHeights() {
        val v=vm(); populate(v)
        assertTrue(v.setAudienceVertexOffset(0,2,3f))
        assertTrue(v.addAudienceElevationPoint(0,1f,1f,2f))
        assertTrue(v.addAudienceCutout(0,cutout(),true))
        val before=v.captureCoveragePlan().prepare()
        val store=LocalProjectStore(folder.root)
        store.save("terrain",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("terrain")!!.project))
        assertEquals(v.audienceAreas.value,fresh.audienceAreas.value)
        assertEquals(before.receivers(),fresh.captureCoveragePlan().prepare().receivers())
        assertTrue(v.setAudienceVertexOffset(0,2,4f)); assertTrue(v.undoScene())
        assertEquals(3f,v.audienceAreas.value.first().vertexOffsetsM[2],0f)
        assertTrue(v.redoScene()); assertEquals(4f,v.audienceAreas.value.first().vertexOffsetsM[2],0f)
        val legacy=JSONObject(v.exportSceneJson()).put("version",9)
        for (i in 0 until legacy.getJSONArray("audienceAreas").length()) {
            legacy.getJSONArray("audienceAreas").getJSONObject(i).remove("vertexOffsetsM")
            legacy.getJSONArray("audienceAreas").getJSONObject(i).remove("elevationPoints")
        }
        assertTrue(fresh.importSceneJson(legacy.toString()))
        assertTrue(fresh.audienceAreas.value.all { it.vertexOffsetsM.isEmpty() && it.elevationPoints.isEmpty() })
    }
    @Test fun malformedElevationEditAndImportLeaveSceneUnchanged() {
        val v=vm(); populate(v); val before=v.exportSceneJson()
        assertFalse(v.addAudienceElevationPoint(0,8f,8f,1f))
        assertEquals(before,v.exportSceneJson()); assertNotNull(v.audienceGeometryError.value)
        val invalid=JSONObject(before)
        invalid.getJSONArray("audienceAreas").getJSONObject(0).put("vertexOffsetsM",JSONArray().put(1f))
        assertFalse(v.importSceneJson(invalid.toString())); assertEquals(before,v.exportSceneJson())
        assertFalse(v.setAudienceVertexOffset(0,0,Float.NaN)); assertEquals(before,v.exportSceneJson())
    }
    @Test fun transformsPreserveNonplanarElevationIncludingBaseRake() {
        val v=vm(); populate(v,5f,10f)
        assertTrue(v.setAudienceVertexOffset(1,2,2f)); assertTrue(v.addAudienceElevationPoint(1,1f,2f,3f))
        val original=v.audienceAreas.value.first { it.id==1 }; val originalPlane=original.sharedSurface()
        val before=original.vertices.map { originalPlane.surfaceY(it.first,it.second) }
        v.setAudienceAreaRotation(1,35f)
        val rotated=v.audienceAreas.value.first { it.id==1 }; val rotatedPlane=rotated.sharedSurface()
        rotated.vertices.forEachIndexed { i,p -> assertEquals(before[i],rotatedPlane.surfaceY(p.first,p.second),2e-5f) }
        v.mirrorAudienceAreaX(1,null); v.mirrorAudienceAreaZ(1,null)
        val mirrored=v.audienceAreas.value.first { it.id==1 }; val mirroredPlane=mirrored.sharedSurface()
        mirrored.vertices.forEachIndexed { i,p -> assertEquals(before[i],mirroredPlane.surfaceY(p.first,p.second),2e-5f) }
        v.duplicateAudienceArea(1)
        val clone=v.audienceAreas.value.last()
        assertEquals(mirrored.elevationPoints.single().x+1,clone.elevationPoints.single().x,1e-5)
        assertEquals(mirrored.elevationPoints.single().offsetM,clone.elevationPoints.single().offsetM,0f)
    }
    @Test fun rendererAndAuxiliarySamplingFollowNonplanarHeightField() {
        val v=vm(); populate(v); assertTrue(v.addAudienceElevationPoint(0,2f,2f,2f))
        assertTrue(v.addAudienceCutout(0,cutout(),true))
        val area=v.audienceAreas.value.first(); val shape=area.sharedSurface()
        val geometry=v.captureCoveragePlan().prepare()
        val values=geometry.receivers().mapIndexed { i,r -> HeatCell(r.x,r.z,100-r.x,
            sourceAreaId=r.areaId,renderY=r.surfaceY,sampleIndex=i,surfaceKey=r.surfaceKey) }
        val field=decode(HeatmapGlb.build(values,90f,100f,geometry=geometry)!!)
        field.positions.filter { it[1]<5f }.forEach { p -> assertEquals(shape.surfaceY(p[0],p[2])+0.03f,p[1],1e-5f) }
        val outlines=decode(AudienceAreasGlb.build(listOf(area),emptyList())!!)
        outlines.positions.chunked(2).forEach { line ->
            val a=line[0]; val b=line[1]
            assertEquals(shape.surfaceY((a[0]+b[0])/2,(a[2]+b[2])/2)+0.06f,(a[1]+b[1])/2,1e-5f)
        }
        val grid=com.droidacoustic.pro.optimizer.CoverageGridBuilder.buildGrid(emptyList(),listOf(area),1f)
        grid.forEach { assertEquals(shape.receiverY(it.x,it.z),it.earHeightM,1e-5f) }
    }
    private fun faceRay(x: Double=3.0,y: Double=10.0,z: Double=1.0,dx: Double=0.0,dy: Double= -1.0,dz: Double=0.0)=
        ViewportRay(PickPoint3D(x,y,z),PickPoint3D(dx,dy,dz))
    @Test fun viewportTapsSelectNearestOverhangThenAssignAndUndoWithoutChangingFaceIndices() {
        val v=vm(); v.addSpeaker(-8f,0f); v.addImportedAudienceMesh(overhangingMesh())
        v.recalculateSignal(); scheduler.runCurrent()
        val geometry=v.coverageGeometry.value; val before=v.exportSceneJson()
        val hit=v.pickImportedFace(faceRay())!!
        assertEquals(0,hit.areaId); assertEquals(5,hit.faceIndex)
        assertEquals(setOf(5),v.importedFaceSelection.value!!.faceIndices)
        assertEquals(before,v.exportSceneJson()); assertSame(geometry,v.coverageGeometry.value)
        assertEquals(PredictionState.CURRENT,v.predictionState.value)
        assertTrue(v.assignImportedFaces(hit.areaId,v.importedFaceSelection.value!!.faceIndices,MeshFaceAssignment.EXCLUDED))
        assertNull(v.coverageGeometry.value); assertNull(v.coverageStatistics.value)
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(36.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-6)
        // Exclusion must not cause taps to fall through to the floor.
        assertEquals(hit,v.pickImportedFace(faceRay()))
        assertTrue(v.undoScene()); assertEquals(MeshFaceAssignment.FOLLOW_ZONE,v.audienceAreas.value.single().importedMesh!!.faceAssignment(5))
        assertNull(v.importedFaceSelection.value)
        assertTrue(v.redoScene()); assertEquals(MeshFaceAssignment.EXCLUDED,v.audienceAreas.value.single().importedMesh!!.faceAssignment(5))
    }
    @Test fun viewportToggleAndPatchSelectionApplyOneBatchAndResetAcrossZones() {
        val v=vm(); v.addImportedAudienceMesh(overhangingMesh())
        v.setFacePickMode(FacePickMode.TOGGLE)
        v.pickImportedFace(faceRay()); v.pickImportedFace(faceRay(x=1.0,z=3.0))
        assertEquals(setOf(4,5),v.importedFaceSelection.value!!.faceIndices)
        v.pickImportedFace(faceRay()); assertEquals(setOf(4),v.importedFaceSelection.value!!.faceIndices)
        v.setFacePickMode(FacePickMode.FLAT_PATCH)
        val hit=v.pickImportedFace(faceRay(x=10.0,y=1.0,z=2.0,dx= -1.0,dy=0.0))!!
        assertEquals(setOf(2,3),v.importedFaceSelection.value!!.faceIndices)
        assertTrue(v.assignImportedFaces(hit.areaId,v.importedFaceSelection.value!!.faceIndices,MeshFaceAssignment.EXCLUDED))
        assertTrue(v.undoScene()); assertTrue(v.audienceAreas.value.single().importedMesh!!.faceAssignments().all {it==MeshFaceAssignment.FOLLOW_ZONE})
        v.duplicateAudienceArea(0); v.setAudienceAreaBaseHeight(1,5f)
        v.setFacePickMode(FacePickMode.TOGGLE); v.selectImportedFaces(0,setOf(0,1))
        assertEquals(1,v.pickImportedFace(faceRay(x=3.0,z=2.0))!!.areaId)
        assertEquals(1,v.importedFaceSelection.value!!.faceIndices.size)
        assertNull(v.pickImportedFace(faceRay(x=50.0))); assertNull(v.importedFaceSelection.value)
    }
    @Test fun viewportPickingTracksTransformsAndScreenProjectionWithoutCoverage() {
        val v=vm(); v.addImportedAudienceMesh(overhangingMesh())
        v.setAudienceAreaRotation(0,35f); v.mirrorAudienceAreaX(0,null); v.setAudienceAreaBaseHeight(0,4f)
        val surface=v.audienceAreas.value.single().sharedSurface()
        val mesh=surface.importedMesh!!; val tri=mesh.triangles()[5]; val nodes=mesh.vertices()
        val x=listOf(tri.a,tri.b,tri.c).map {nodes[it].x}.average()
        val z=listOf(tri.a,tri.b,tri.c).map {nodes[it].z}.average()
        val ray=ViewportRay.perspective(100.0,50.0,200.0,100.0,PickPoint3D(x,20.0,z),
            PickPoint3D(x,0.0,z),PickPoint3D(0.0,0.0,-1.0),45.0)!!
        val hit=v.pickImportedFace(ray)!!
        assertEquals(5,hit.faceIndex); assertEquals(7.0,hit.position.y,1e-6)
        assertNull(v.coverageGeometry.value)
        val original=mesh.faceAssignments()
        assertTrue(v.assignImportedFaces(0,v.importedFaceSelection.value!!.faceIndices,MeshFaceAssignment.STANDING))
        assertEquals(MeshFaceAssignment.STANDING,v.audienceAreas.value.single().importedMesh!!.faceAssignment(5))
        assertEquals(original.dropLast(1),v.audienceAreas.value.single().importedMesh!!.faceAssignments().dropLast(1))
    }
    @Test fun pickedAssignmentsSurviveReopenButFaceHighlightDoesNot() {
        val v=vm(); v.addImportedAudienceMesh(overhangingMesh()); v.pickImportedFace(faceRay())
        assertTrue(v.assignImportedFaces(0,v.importedFaceSelection.value!!.faceIndices,MeshFaceAssignment.SEATED))
        val store=LocalProjectStore(folder.root); store.save("picked",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); fresh.addImportedAudienceMesh(overhangingMesh()); fresh.pickImportedFace(faceRay())
        assertTrue(fresh.importPreparedProject(store.loadPrepared("picked")!!.project))
        assertNull(fresh.importedFaceSelection.value)
        assertEquals(MeshFaceAssignment.SEATED,fresh.audienceAreas.value.single().importedMesh!!.faceAssignment(5))
        assertEquals(5,fresh.pickImportedFace(faceRay())!!.faceIndex)
    }
    private fun groupedAudienceObj(): String {
        val m=overhangingMesh()
        return m.vertices().joinToString("\n") {"v ${it.x} ${it.y} ${it.z}"}+"\n"+
            m.triangles().mapIndexed {i,t ->
                val group=when(i) {0 -> "g Floor\n"; 2 -> "g Wall\n"; 4 -> "g Balcony\n"; else -> ""}
                group+"f ${t.a+1} ${t.b+1} ${t.c+1}"
            }.joinToString("\n")
    }
    @Test fun objGroupsImportAsSelectableAssignableGroupsWithIndependentAndroidStatistics() {
        val v=vm(); v.addSpeaker(-8f,0f); assertTrue(v.importAudienceObj(groupedAudienceObj()))
        val m=v.audienceAreas.value.single().importedMesh!!
        assertEquals(listOf("Floor","Wall","Balcony"),m.faceGroups().map {it.name})
        assertTrue(m.faceAssignments().all {it==MeshFaceAssignment.FOLLOW_ZONE})
        v.setFacePickMode(FacePickMode.GROUP); v.pickImportedFace(faceRay())
        assertEquals(setOf(4,5),v.importedFaceSelection.value!!.faceIndices)
        assertTrue(v.assignImportedFaceGroup(0,1,MeshFaceAssignment.SEATED))
        assertTrue(v.assignImportedFaceGroup(0,2,MeshFaceAssignment.EXCLUDED))
        assertTrue(v.assignImportedFaceGroup(0,3,MeshFaceAssignment.STANDING))
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(PredictionState.CURRENT,v.predictionState.value)
        assertEquals(32.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-6)
        assertEquals(listOf(16.0,0.0,16.0),v.coverageStatistics.value!!.surfaces().map {it.statistics.totalAreaM2})
        assertEquals(listOf(1.2f,4.6f),v.heatmap.value.filter {it.x==2f && it.z==2f}.map {it.receiverY!!}.sorted())
        val report=JSONObject(v.exportProjectReportJson()).getJSONObject("coverageStatistics").getJSONArray("surfaces")
        assertEquals(3,report.length()); assertTrue(report.getJSONObject(0).getString("name").endsWith("Floor"))
    }
    @Test fun objCombinedMembershipSelectsAndAssignsAllPartsWithoutDuplicatingArea() {
        val v=vm(); v.addSpeaker(-8f,0f)
        val obj=groupedAudienceObj().replace("g Floor","g Floor Left").replace("g Balcony","g Left Floor Floor")
        assertTrue(v.importAudienceObj(obj))
        val m=v.audienceAreas.value.single().importedMesh!!
        assertEquals(listOf("Floor + Left","Wall"),m.faceGroups().map {it.name})
        assertEquals(setOf(0,1,4,5),m.faceGroup(1).faces()); assertEquals(6,m.triangles().size)
        assertTrue(v.selectImportedFaceGroup(0,1)); assertEquals(setOf(0,1,4,5),v.importedFaceSelection.value!!.faceIndices)
        assertTrue(v.assignImportedFaceGroup(0,2,MeshFaceAssignment.EXCLUDED))
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(listOf(32.0,0.0),v.coverageStatistics.value!!.surfaces().map {it.statistics.totalAreaM2})
        assertEquals(32.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-6)
        assertTrue(v.assignImportedFaceGroup(0,1,MeshFaceAssignment.EXCLUDED))
        v.recalculateSignal(); scheduler.runCurrent(); assertTrue(v.heatmap.value.isEmpty())
        assertEquals(0.0,v.coverageStatistics.value!!.overall.totalAreaM2,0.0)
        assertTrue(v.undoScene()); assertEquals(setOf(0,1,4,5),v.audienceAreas.value.single().importedMesh!!.faceGroup(1).faces())
        assertEquals(MeshFaceAssignment.FOLLOW_ZONE,v.audienceAreas.value.single().importedMesh!!.faceAssignment(0))
    }
    @Test fun importedObjGroupsSurviveAssignmentRenameTransformsAndColdReopenWithoutSource() {
        val v=vm(); assertTrue(v.importAudienceObj(groupedAudienceObj()))
        assertTrue(v.assignImportedFaceGroup(0,2,MeshFaceAssignment.EXCLUDED))
        assertTrue(v.assignImportedFaceGroup(0,3,MeshFaceAssignment.STANDING))
        assertTrue(v.renameImportedFaceGroup(0,1,"Stalls"))
        v.setAudienceAreaRotation(0,35f); v.mirrorAudienceAreaX(0,null); v.duplicateAudienceArea(0)
        val before=v.captureCoveragePlan().prepare(); val store=LocalProjectStore(folder.root)
        store.save("obj-groups",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("obj-groups")!!.project))
        assertEquals(v.audienceAreas.value,fresh.audienceAreas.value)
        fresh.audienceAreas.value.forEach {assertEquals(listOf("Stalls","Wall","Balcony"),it.importedMesh!!.faceGroups().map {g -> g.name})}
        assertTrue(fresh.selectImportedFaceGroup(1,3)); assertEquals(setOf(4,5),fresh.importedFaceSelection.value!!.faceIndices)
        val after=fresh.captureCoveragePlan().prepare()
        assertEquals(before.receivers(),after.receivers()); assertEquals(before.triangles(),after.triangles())
        assertEquals(64.0,com.droidacoustic.pro.statistics.CoverageStatisticsCalculator.calculate(
            after,FloatArray(after.receiverCount) {90f}).overall.totalAreaM2,2e-5)
    }
    @Test fun invalidObjGroupsFailAtomicallyWithoutConsumingZoneIdsOrUndoHistory() {
        val v=vm(); assertTrue(v.importAudienceObj(groupedAudienceObj())); val before=v.exportSceneJson(); val originalAreas=v.audienceAreas.value.toList()
        for (group in listOf("X".repeat(81),"A".repeat(40)+" "+"B".repeat(40),"Bad\u0000Name")) {
            assertFalse(v.importAudienceObj(groupedAudienceObj().replace("g Floor","g $group")))
            assertTrue(v.audienceGeometryError.value!!.startsWith("OBJ line "))
            assertEquals(before,v.exportSceneJson())
        }
        assertTrue(v.importAudienceObj(groupedAudienceObj().replace("g Floor","g seats").replace("g Balcony","g Seats")))
        assertEquals(listOf(0,1),v.audienceAreas.value.map {it.id})
        assertEquals(listOf("seats","Wall","Seats (2)"),v.audienceAreas.value.last().importedMesh!!.faceGroups().map {it.name})
        assertTrue(v.undoScene()); assertEquals(originalAreas,v.audienceAreas.value)
        assertTrue(v.undoScene()); assertTrue(v.audienceAreas.value.isEmpty())
        assertTrue(v.redoScene()); assertEquals(originalAreas,v.audienceAreas.value)
        assertTrue(v.redoScene()); assertEquals(2,v.audienceAreas.value.size)
    }
    private fun boundaryWall(v: SceneViewModel) {
        v.addSpeaker(-2f,0f)
        val mesh=AudienceMesh.create(listOf(AudienceMeshVertex(0.0,-5f,-5.0),AudienceMeshVertex(0.0,-5f,5.0),
            AudienceMeshVertex(0.0,5f,5.0),AudienceMeshVertex(0.0,5f,-5.0)),listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3)))
            .createFaceGroup("Wall",listOf(0,1)).assignFaces(listOf(0,1),MeshFaceAssignment.EXCLUDED)
        v.addImportedAudienceMesh(mesh,"Boundary test")
    }
    @Test fun boundaryGroupAssignmentsReachAndroidPredictionAndFrozenRequests() {
        val v=vm(); boundaryWall(v)
        val free=v.predictBandDb(v.speakers.value,3f,0f,0f,1000); assertTrue(free>0f)
        val frozen=v.capturePredictionRequest(PredictionTicket(0,1,PredictionChannel.LISTENER),v.speakers.value,
            listOf(HeatCell(3f,0f,0f)),heights=listOf(0f),bands=listOf(1000),selected=1000)
        assertTrue(v.assignImportedGroupBoundaryRole(0,1,AcousticBoundaryRole.OCCLUDING))
        assertEquals(0f,v.predictBandDb(v.speakers.value,3f,0f,0f,1000),0f)
        assertEquals(free,frozen.calculate().levelAt(0),0f)
        assertTrue(v.undoScene()); assertEquals(free,v.predictBandDb(v.speakers.value,3f,0f,0f,1000),1e-4f)
        assertTrue(v.redoScene()); assertEquals(0f,v.predictBandDb(v.speakers.value,3f,0f,0f,1000),0f)
        val front=v.predictBandDb(v.speakers.value,-3f,0f,0f,1000)
        assertTrue(v.assignImportedGroupBoundaryRole(0,1,AcousticBoundaryRole.RIGID_REFLECTOR))
        assertTrue(kotlin.math.abs(front-v.predictBandDb(v.speakers.value,-3f,0f,0f,1000))>0.001f)
        assertEquals(0f,v.predictBandDb(v.speakers.value,3f,0f,0f,1000),0f)
        assertTrue(v.assignImportedBoundaryRole(0,listOf(0,1),AcousticBoundaryRole.NONE))
        assertEquals(free,v.predictBandDb(v.speakers.value,3f,0f,0f,1000),1e-4f)
    }
    @Test fun boundaryRolesSurviveTransformsGroupsAndColdSaveReopen() {
        val v=vm(); namedDecks(v)
        assertTrue(v.assignImportedGroupBoundaryRole(0,2,AcousticBoundaryRole.OCCLUDING))
        assertTrue(v.assignImportedBoundaryRole(0,listOf(4),AcousticBoundaryRole.RIGID_REFLECTOR))
        val original=v.audienceAreas.value.single().importedMesh!!.boundaryRoles()
        assertTrue(v.addToImportedFaceGroup(0,1,listOf(2,3))); assertTrue(v.removeImportedFaceGroup(0,3))
        v.setAudienceAreaRotation(0,35f); v.mirrorAudienceAreaX(0,null); v.duplicateAudienceArea(0)
        v.audienceAreas.value.forEach {assertEquals(original,it.importedMesh!!.boundaryRoles())}
        val store=LocalProjectStore(folder.root); store.save("boundaries",v.exportSceneJson(false),v::resolveProjectAsset)
        val reopened=vm(); assertTrue(reopened.importPreparedProject(store.loadPrepared("boundaries")!!.project))
        assertEquals(v.audienceAreas.value,reopened.audienceAreas.value)
        val mesh=reopened.captureCoveragePlan().prepare()
        assertEquals(64.0,com.droidacoustic.pro.statistics.CoverageStatisticsCalculator.calculate(mesh,FloatArray(mesh.receiverCount) {90f}).overall.totalAreaM2,2e-5)
    }
    @Test fun boundaryEditsInvalidateResultsWithoutChangingAudienceAreaAndRenderExplicitRoles() {
        val v=vm(); v.addSpeaker(-8f,0f); namedDecks(v); v.recalculateSignal(); scheduler.runCurrent()
        val area=v.coverageStatistics.value!!.overall.totalAreaM2
        assertTrue(v.assignImportedGroupBoundaryRole(0,2,AcousticBoundaryRole.OCCLUDING))
        assertNull(v.coverageStatistics.value); assertTrue(v.heatmap.value.isEmpty())
        assertTrue(v.assignImportedGroupBoundaryRole(0,3,AcousticBoundaryRole.RIGID_REFLECTOR))
        v.recalculateSignal(); scheduler.runCurrent(); assertEquals(PredictionState.CURRENT,v.predictionState.value)
        assertEquals(area,v.coverageStatistics.value!!.overall.totalAreaM2,0.0)
        val glb=decode(AudienceAreasGlb.build(v.audienceAreas.value,emptyList(),selectedAreaId=0,selectedFaces=setOf(2))!!)
        assertTrue(glb.colors.contains(listOf(1f,0.25f,0.2f,1f)))
        assertTrue(glb.colors.contains(listOf(0.25f,0.55f,1f,1f)))
        assertTrue(glb.colors.contains(listOf(1f,0.95f,0.1f,1f)))
    }
    @Test fun malformedBoundaryRolesFailAtomicallyAndOldProjectsStayTransparent() {
        val v=vm(); boundaryWall(v); val before=v.exportSceneJson()
        assertFalse(v.assignImportedBoundaryRole(0,listOf(2),AcousticBoundaryRole.OCCLUDING))
        assertFalse(v.assignImportedGroupBoundaryRole(0,99,AcousticBoundaryRole.OCCLUDING))
        assertEquals(before,v.exportSceneJson())
        for (value in listOf<Any?>(null,JSONObject.NULL,JSONArray(),JSONArray().put("UNKNOWN").put("NONE"))) {
            val json=JSONObject(before); val mesh=json.getJSONArray("audienceAreas").getJSONObject(0).getJSONObject("audienceMesh")
            if (value==null) mesh.remove("boundaryRoles") else mesh.put("boundaryRoles",value)
            assertFalse(v.importSceneJson(json.toString())); assertEquals(before,v.exportSceneJson())
        }
        val old=JSONObject(before).put("version",15)
        old.getJSONArray("audienceAreas").getJSONObject(0).getJSONObject("audienceMesh").apply {put("version",4); remove("boundaryRoles")}
        val reopened=vm(); assertTrue(reopened.importSceneJson(old.toString()))
        assertTrue(reopened.audienceAreas.value.single().importedMesh!!.boundaryRoles().all {it==AcousticBoundaryRole.NONE})
        assertTrue(reopened.predictBandDb(reopened.speakers.value,3f,0f,0f,1000)>0f)
    }
    private fun namedDecks(v: SceneViewModel) {
        v.addImportedAudienceMesh(overhangingMesh(),"Hall")
        assertTrue(v.createImportedFaceGroup(0,"Main floor",listOf(0,1)))
        assertTrue(v.createImportedFaceGroup(0,"Wall",listOf(2,3)))
        assertTrue(v.createImportedFaceGroup(0,"Balcony",listOf(4,5)))
        assertTrue(v.assignImportedFaceGroup(0,1,MeshFaceAssignment.SEATED))
        assertTrue(v.assignImportedFaceGroup(0,2,MeshFaceAssignment.EXCLUDED))
        assertTrue(v.assignImportedFaceGroup(0,3,MeshFaceAssignment.STANDING))
    }
    @Test fun namedGroupSelectionAndViewportRecallDoNotInvalidatePredictions() {
        val v=vm(); v.addSpeaker(-8f,0f); namedDecks(v); v.recalculateSignal(); scheduler.runCurrent()
        val geometry=v.coverageGeometry.value; val saved=v.exportSceneJson()
        assertTrue(v.selectImportedFaceGroup(0,1)); assertEquals(setOf(0,1),v.importedFaceSelection.value!!.faceIndices)
        v.setFacePickMode(FacePickMode.GROUP)
        assertEquals(5,v.pickImportedFace(faceRay())!!.faceIndex)
        assertEquals(setOf(4,5),v.importedFaceSelection.value!!.faceIndices)
        assertSame(geometry,v.coverageGeometry.value); assertEquals(saved,v.exportSceneJson())
        assertEquals(PredictionState.CURRENT,v.predictionState.value)
        assertTrue(v.assignImportedFaceGroup(0,3,MeshFaceAssignment.EXCLUDED)); assertNull(v.coverageGeometry.value)
        assertEquals(5,v.pickImportedFace(faceRay())!!.faceIndex)
        assertEquals(setOf(4,5),v.importedFaceSelection.value!!.faceIndices)
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(16.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-6)
        assertTrue(v.undoScene()); assertEquals(MeshFaceAssignment.STANDING,v.audienceAreas.value.single().importedMesh!!.faceAssignment(5))
        assertTrue(v.redoScene()); assertEquals(MeshFaceAssignment.EXCLUDED,v.audienceAreas.value.single().importedMesh!!.faceAssignment(5))
    }
    @Test fun namedGroupsReachAndroidAreaReportsAndRenderedCoverageWithoutDoubleCounting() {
        val v=vm(); v.addSpeaker(-8f,0f); namedDecks(v); v.recalculateSignal(); scheduler.runCurrent()
        val statistics=v.coverageStatistics.value!!
        assertEquals(32.0,statistics.overall.totalAreaM2,1e-6)
        assertEquals(listOf(16.0,0.0,16.0),statistics.surfaces().map {it.statistics.totalAreaM2})
        assertEquals(listOf("Hall — Main floor","Hall — Wall","Hall — Balcony"),statistics.surfaces().map {it.name})
        assertEquals(listOf(1.2f,4.6f),v.heatmap.value.filter {it.x==2f && it.z==2f}.map {it.receiverY!!}.sorted())
        val report=JSONObject(v.exportProjectReportJson()).getJSONObject("coverageStatistics")
        assertEquals(3,report.getJSONArray("surfaces").length())
        assertEquals("Hall — Main floor",report.getJSONArray("surfaces").getJSONObject(0).getString("name"))
        val g=v.coverageGeometry.value!!
        val glb=decode(HeatmapGlb.build(v.heatmap.value,80f,110f,geometry=g)!!)
        assertEquals(g.triangleCount*3,glb.positions.size)
        assertEquals(setOf(0.03f,3.03f),glb.positions.map {it[1]}.toSet())
    }
    @Test fun groupEditsUndoAtomicallyPreserveRolesAndRefreshCachedNames() {
        val cache=GeometryCache(); val v=vm(cache); v.addSpeaker(-8f,0f); namedDecks(v)
        v.recalculateSignal(); scheduler.runCurrent(); val original=v.coverageGeometry.value
        val frozen=v.capturePredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),v.speakers.value,
            emptyList(),coveragePlan=v.captureCoveragePlan())
        assertTrue(v.renameImportedFaceGroup(0,1,"Stalls")); assertNull(v.coverageStatistics.value)
        v.recalculateSignal(); scheduler.runCurrent(); val renamed=v.coverageGeometry.value
        assertNotSame(original,renamed); assertEquals("Hall — Stalls",v.coverageStatistics.value!!.surfaces().first().name)
        assertEquals("Hall — Main floor",frozen.calculate().geometry!!.surfaces().first().name)
        assertTrue(v.addToImportedFaceGroup(0,1,listOf(4,5)))
        var m=v.audienceAreas.value.single().importedMesh!!
        assertEquals(setOf(0,1,4,5),m.faceGroup(1).faces()); assertTrue(m.faceGroup(3).faces().isEmpty())
        assertEquals(MeshFaceAssignment.STANDING,m.faceAssignment(5))
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(listOf(32.0,0.0,0.0),v.coverageStatistics.value!!.surfaces().map {it.statistics.totalAreaM2})
        assertTrue(v.undoScene()); v.recalculateSignal(); scheduler.runCurrent(); assertSame(renamed,v.coverageGeometry.value)
        assertTrue(v.setImportedFaceGroupMembers(0,1,listOf(0)))
        assertNull(v.audienceAreas.value.single().importedMesh!!.groupForFace(1))
        assertTrue(v.ungroupImportedFaces(0,listOf(0))); assertTrue(v.selectImportedFaceGroup(0,1))
        assertTrue(v.importedFaceSelection.value!!.faceIndices.isEmpty())
        assertTrue(v.removeImportedFaceGroup(0,3)); m=v.audienceAreas.value.single().importedMesh!!
        assertNull(m.groupForFace(4)); assertEquals(MeshFaceAssignment.STANDING,m.faceAssignment(4))
    }
    @Test fun namedGroupTransformsDuplicationAndColdReopenPreserveMembershipAndArea() {
        val v=vm(); namedDecks(v); val original=v.audienceAreas.value.single().importedMesh!!.faceGroups()
        v.setAudienceAreaRotation(0,35f); v.mirrorAudienceAreaX(0,null); v.duplicateAudienceArea(0)
        v.audienceAreas.value.forEach {assertEquals(original,it.importedMesh!!.faceGroups())}
        v.selectImportedFaceGroup(0,3)
        val before=v.captureCoveragePlan().prepare(); val store=LocalProjectStore(folder.root)
        store.save("groups",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("groups")!!.project))
        assertEquals(v.audienceAreas.value,fresh.audienceAreas.value); assertNull(fresh.importedFaceSelection.value)
        assertTrue(fresh.selectImportedFaceGroup(1,3)); assertEquals(setOf(4,5),fresh.importedFaceSelection.value!!.faceIndices)
        val after=fresh.captureCoveragePlan().prepare()
        assertEquals(before.receivers(),after.receivers()); assertEquals(before.triangles(),after.triangles())
        val stats=com.droidacoustic.pro.statistics.CoverageStatisticsCalculator.calculate(after,FloatArray(after.receiverCount) {90f})
        assertEquals(64.0,stats.overall.totalAreaM2,2e-5); assertEquals(6,stats.surfaces().size)
        assertEquals(6,stats.surfaces().map {it.surfaceKey}.distinct().size)
    }
    @Test fun invalidGroupEditsAndImportsPreserveProjectAndOldMeshesOpenUngrouped() {
        val v=vm(); namedDecks(v); val before=v.exportSceneJson()
        assertFalse(v.createImportedFaceGroup(0,"Empty selection",emptyList()))
        assertFalse(v.renameImportedFaceGroup(0,1,"BALCONY")); assertFalse(v.setImportedFaceGroupMembers(0,1,listOf(6)))
        assertFalse(v.selectImportedFaceGroup(0,99)); assertEquals(before,v.exportSceneJson())
        for (value in listOf<Any?>(null,"bad",JSONArray().put(JSONObject().put("id",1).put("name","A").put("faces",JSONArray().put(9))),
                JSONArray().put(JSONObject().put("id",1).put("name","A").put("faces",JSONArray().put(0)))
                    .put(JSONObject().put("id",2).put("name","B").put("faces",JSONArray().put(0))))) {
            val json=JSONObject(before); val mesh=json.getJSONArray("audienceAreas").getJSONObject(0).getJSONObject("audienceMesh")
            if (value==null) mesh.remove("faceGroups") else mesh.put("faceGroups",value)
            assertFalse(v.importSceneJson(json.toString())); assertEquals(before,v.exportSceneJson())
        }
        val old=JSONObject(before).put("version",14)
        old.getJSONArray("audienceAreas").getJSONObject(0).getJSONObject("audienceMesh").apply {put("version",3); remove("faceGroups")}
        val fresh=vm(); assertTrue(fresh.importSceneJson(old.toString()))
        assertTrue(fresh.audienceAreas.value.single().importedMesh!!.faceGroups().isEmpty())
        val g=fresh.captureCoveragePlan().prepare()
        assertEquals(32.0,com.droidacoustic.pro.statistics.CoverageStatisticsCalculator.calculate(g,FloatArray(g.receiverCount) {90f}).overall.totalAreaM2,1e-6)
    }
    private fun assignDecks(v: SceneViewModel) {
        assertTrue(v.assignImportedFaces(0,listOf(0,1),MeshFaceAssignment.SEATED))
        assertTrue(v.assignImportedFaces(0,listOf(2,3),MeshFaceAssignment.EXCLUDED))
        assertTrue(v.assignImportedFaces(0,listOf(4,5),MeshFaceAssignment.STANDING))
    }
    @Test fun assignedFacesDriveAndroidCoverageReportsAndAuxiliaryListeningHeights() {
        val v=vm(); v.addSpeaker(-8f,0f); v.addImportedAudienceMesh(overhangingMesh()); assignDecks(v)
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(PredictionState.CURRENT,v.predictionState.value)
        val stats=v.coverageStatistics.value!!
        assertEquals(32.0,stats.overall.totalAreaM2,1e-6)
        assertEquals(0.0,stats.overall.missingAreaM2,0.0)
        assertEquals(setOf("area:0:seated","area:0:standing"),stats.surfaces().map {it.surfaceKey}.toSet())
        stats.surfaces().forEach {assertEquals(16.0,it.statistics.totalAreaM2,1e-6)}
        assertEquals(listOf(1.2f,4.6f),v.heatmap.value.filter {it.x==2f && it.z==2f}.map {it.receiverY!!}.sorted())
        assertTrue(v.heatmap.value.all {it.renderY==0f || it.renderY==3f})
        val auxiliary=com.droidacoustic.pro.optimizer.CoverageGridBuilder.buildGrid(emptyList(),v.audienceAreas.value,1f)
        assertEquals(listOf(1.2f,4.6f),auxiliary.filter {it.x==2f && it.z==2f}.map {it.earHeightM}.sorted())
        val report=JSONObject(v.exportProjectReportJson()).getJSONObject("coverageStatistics")
        assertEquals(32.0,report.getJSONObject("overall").getDouble("totalAreaM2"),1e-6)
        assertEquals(2,report.getJSONArray("surfaces").length())
    }
    @Test fun facePreviewIsTransientAndBatchAssignmentsUndoOnceAndInvalidatePredictions() {
        val cache=GeometryCache(); val v=vm(cache); v.addSpeaker(-8f,0f); v.addImportedAudienceMesh(overhangingMesh())
        v.recalculateSignal(); scheduler.runCurrent()
        val geometry=v.coverageGeometry.value!!; val saved=v.exportSceneJson()
        val captured=v.capturePredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),v.speakers.value,
            emptyList(),coveragePlan=v.captureCoveragePlan())
        val patch=v.audienceAreas.value.single().importedMesh!!.coplanarFaces(2)
        assertTrue(v.selectImportedFaces(0,patch)); assertEquals(setOf(2,3),v.importedFaceSelection.value!!.faceIndices)
        assertEquals(saved,v.exportSceneJson()); assertSame(geometry,v.coverageGeometry.value)
        assertEquals(PredictionState.CURRENT,v.predictionState.value)
        assertTrue(v.assignImportedFaces(0,patch,MeshFaceAssignment.EXCLUDED))
        assertNull(v.coverageGeometry.value); assertNull(v.coverageStatistics.value); assertTrue(v.heatmap.value.isEmpty())
        v.recalculateSignal(); scheduler.runCurrent()
        val assigned=v.coverageGeometry.value!!; assertNotSame(geometry,assigned)
        assertEquals(32.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-6)
        assertEquals(geometry.receivers(),captured.calculate().geometry!!.receivers())
        assertTrue(v.assignImportedFaces(0,patch,MeshFaceAssignment.EXCLUDED)); assertSame(assigned,v.coverageGeometry.value)
        v.setSignalLevelDbu(3f); v.recalculateSignal(); scheduler.runCurrent(); assertSame(assigned,v.coverageGeometry.value)
        // Undo the signal edit first, then the entire two-face assignment in one step.
        assertTrue(v.undoScene()); assertTrue(v.undoScene())
        assertTrue(v.audienceAreas.value.single().importedMesh!!.faceAssignments().all {it==MeshFaceAssignment.FOLLOW_ZONE})
        assertTrue(v.redoScene()); assertEquals(MeshFaceAssignment.EXCLUDED,v.audienceAreas.value.single().importedMesh!!.faceAssignment(2))
        v.recalculateSignal(); scheduler.runCurrent(); assertSame(assigned,v.coverageGeometry.value)
        val before=v.exportSceneJson(); assertFalse(v.assignImportedFaces(0,listOf(6),MeshFaceAssignment.SEATED))
        assertFalse(v.selectImportedFaces(0,listOf(-1))); assertEquals(before,v.exportSceneJson())
    }
    @Test fun assignmentsPersistAfterTransformsDuplicationAndColdReopen() {
        val v=vm(); v.addImportedAudienceMesh(overhangingMesh()); assignDecks(v)
        val roles=v.audienceAreas.value.single().importedMesh!!.faceAssignments()
        v.setAudienceAreaRotation(0,35f); v.mirrorAudienceAreaX(0,null); v.duplicateAudienceArea(0)
        v.audienceAreas.value.forEach {assertEquals(roles,it.importedMesh!!.faceAssignments())}
        assertTrue(v.selectImportedFaces(0,listOf(2,3)))
        val before=v.captureCoveragePlan().prepare(); val store=LocalProjectStore(folder.root)
        store.save("assigned",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("assigned")!!.project))
        assertEquals(v.audienceAreas.value,fresh.audienceAreas.value); assertNull(fresh.importedFaceSelection.value)
        val after=fresh.captureCoveragePlan().prepare()
        assertEquals(before.receivers(),after.receivers()); assertEquals(before.triangles(),after.triangles())
        val stats=com.droidacoustic.pro.statistics.CoverageStatisticsCalculator.calculate(after,FloatArray(after.receiverCount) {90f})
        assertEquals(64.0,stats.overall.totalAreaM2,2e-5); assertEquals(4,stats.surfaces().size)
    }
    @Test fun malformedAssignmentsFailImportAtomicallyAndOlderMeshesFollowZone() {
        val v=vm(); v.addImportedAudienceMesh(overhangingMesh()); assignDecks(v); val before=v.exportSceneJson()
        for (bad in listOf<Any?>(null,JSONArray(),JSONArray(List(6) {"UNKNOWN"}),JSONArray(List(6) {0}))) {
            val json=JSONObject(before); val mesh=json.getJSONArray("audienceAreas").getJSONObject(0).getJSONObject("audienceMesh")
            if (bad==null) mesh.remove("faceAssignments") else mesh.put("faceAssignments",bad)
            assertFalse(v.importSceneJson(json.toString())); assertEquals(before,v.exportSceneJson())
        }
        val legacy=JSONObject(before).put("version",13)
        legacy.getJSONArray("audienceAreas").getJSONObject(0).getJSONObject("audienceMesh").apply {
            put("version",2); remove("faceAssignments")
        }
        val fresh=vm(); assertTrue(fresh.importSceneJson(legacy.toString()))
        assertTrue(fresh.audienceAreas.value.single().importedMesh!!.faceAssignments().all {it==MeshFaceAssignment.FOLLOW_ZONE})
        val geometry=fresh.captureCoveragePlan().prepare()
        assertEquals(44.0,com.droidacoustic.pro.statistics.CoverageStatisticsCalculator.calculate(
            geometry,FloatArray(geometry.receiverCount) {90f}).overall.totalAreaM2,1e-6)
    }
    @Test fun excludedFacesRemainVisibleContextWithoutCoverageOrPreviewFloor() {
        val v=vm(); v.addSpeaker(-8f,0f); v.addImportedAudienceMesh(overhangingMesh())
        assertTrue(v.assignImportedFaces(0,(0..5).toList(),MeshFaceAssignment.EXCLUDED))
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(PredictionState.CURRENT,v.predictionState.value)
        assertTrue(v.heatmap.value.isEmpty()); assertEquals(0,v.coverageGeometry.value!!.triangleCount)
        assertEquals(0.0,v.coverageStatistics.value!!.overall.totalAreaM2,0.0)
        assertNull(HeatmapGlb.build(v.heatmap.value,90f,100f,geometry=v.coverageGeometry.value))
        val glb=decode(AudienceAreasGlb.build(v.audienceAreas.value,emptyList(),selectedAreaId=0,selectedFaces=setOf(2,3))!!)
        assertTrue(glb.colors.contains(listOf(0.45f,0.45f,0.45f,1f)))
        assertTrue(glb.colors.contains(listOf(1f,0.95f,0.1f,1f)))
        assertTrue(glb.positions.any {it[1]>3})
    }
    @Test fun assignmentColorsAndCoverageRenderingRetainOnlyActiveFaces() {
        val v=vm(); v.addImportedAudienceMesh(overhangingMesh()); assignDecks(v)
        val m=v.captureCoveragePlan().prepare(); val cells=m.receivers().mapIndexed {i,r -> HeatCell(r.x,r.z,90+r.z,
            sourceAreaId=r.areaId,renderY=r.surfaceY,sampleIndex=i,surfaceKey=r.surfaceKey)}
        val heat=decode(HeatmapGlb.build(cells,90f,94f,geometry=m)!!)
        assertEquals(m.triangleCount*3,heat.positions.size)
        assertEquals(setOf(0.03f,3.03f),heat.positions.map {it[1]}.toSet())
        val contours=decode(ContoursGlb.build(cells,listOf(92f),geometry=m)!!)
        assertEquals(setOf(0.25f,3.25f),contours.positions.map {it[1]}.toSet())
        val wires=decode(AudienceAreasGlb.build(v.audienceAreas.value,emptyList())!!)
        assertEquals(setOf(listOf(0.3f,0.8f,0.35f,1f),listOf(0.8f,0.45f,1f,1f),listOf(0.45f,0.45f,0.45f,1f)),wires.colors.toSet())
    }
    @Test fun parentZoneEditsAffectInheritedFacesButPreserveOverridesAndExclusions() {
        val v=vm(); v.addSpeaker(-8f,0f); v.addImportedAudienceMesh(overhangingMesh())
        assertTrue(v.assignImportedFaces(0,listOf(2,3),MeshFaceAssignment.EXCLUDED))
        assertTrue(v.assignImportedFaces(0,listOf(4,5),MeshFaceAssignment.SEATED))
        v.setAudienceAreaType(0,"AUDIENCE_STANDING"); v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(listOf(1.6f,4.2f),v.heatmap.value.filter {it.x==2f && it.z==2f}.map {it.receiverY!!}.sorted())
        v.setAudienceAreaType(0,"STAGE"); v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(16.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-6)
        assertTrue(v.heatmap.value.all {it.renderY==3f && it.receiverY==4.2f})
    }
    private fun overhangingMesh(): AudienceMesh {
        val nodes=listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(4.0,0f,0.0),
            AudienceMeshVertex(4.0,0f,4.0),AudienceMeshVertex(0.0,0f,4.0),
            AudienceMeshVertex(0.0,3f,0.0),AudienceMeshVertex(4.0,3f,0.0),
            AudienceMeshVertex(4.0,3f,4.0),AudienceMeshVertex(0.0,3f,4.0))
        return AudienceMesh.create(nodes,listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3),
            SurfaceTriangle(1,5,6),SurfaceTriangle(1,6,2),SurfaceTriangle(4,7,6),SurfaceTriangle(4,6,5)))
    }
    private val verticalObj="v 0 0 0\nv 0 0 4\nv 0 4 4\nv 0 4 0\nf 1 2 3\nf 1 3 4"
    @Test fun verticalObjImportsAndPublishesAndroidAreaAndCoverage() {
        val v=vm(); v.addSpeaker(-8f,0f); assertTrue(v.importAudienceObj(verticalObj))
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(PredictionState.CURRENT,v.predictionState.value)
        assertEquals(16.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-6)
        assertTrue(v.heatmap.value.all {it.x==0f && it.splDb.isFinite()})
        assertEquals(4f,v.heatmap.value.maxOf {it.renderY},0f)
        val shape=v.audienceAreas.value.single().sharedSurface()
        assertTrue(shape.surfaceY(0f,2f).isNaN()); assertTrue(shape.containsOutlinePoint(0.1f,2f))
    }
    @Test fun overhangPredictionAndAuxiliarySamplerKeepBothLayers() {
        val v=vm(); v.addSpeaker(-8f,0f); v.addImportedAudienceMesh(overhangingMesh())
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(44.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-6)
        val center=v.heatmap.value.filter {it.x==2f && it.z==2f}.sortedBy {it.renderY}
        assertEquals(listOf(0f,3f),center.map {it.renderY}); assertNotEquals(center[0].splDb,center[1].splDb)
        val area=v.audienceAreas.value.single()
        val auxiliary=com.droidacoustic.pro.optimizer.CoverageGridBuilder.buildGrid(emptyList(),listOf(area),1f)
        assertTrue(auxiliary.all {it.earHeightM.isFinite()})
        assertEquals(listOf(1.2f,4.2f),auxiliary.filter {it.x==2f && it.z==2f}.map {it.earHeightM}.sorted())
        val old=v.coverageGeometry.value
        v.setSignalLevelDbu(3f); v.recalculateSignal(); scheduler.runCurrent(); assertSame(old,v.coverageGeometry.value)
        v.setAudienceAreaBaseHeight(0,5f); assertNull(v.coverageGeometry.value)
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(listOf(5f,8f),v.heatmap.value.filter {it.x==2f && it.z==2f}.map {it.renderY}.sorted())
    }
    @Test fun overhangSaveReopenUndoAndTransformsPreserve3DTopology() {
        val v=vm(); v.addImportedAudienceMesh(overhangingMesh(),"Folded audience")
        val captured=v.captureCoveragePlan().prepare()
        v.setAudienceAreaRotation(0,35f); v.mirrorAudienceAreaX(0,null); v.duplicateAudienceArea(0)
        val before=v.captureCoveragePlan().prepare()
        val store=LocalProjectStore(folder.root)
        store.save("folded",v.exportSceneJson(false),v::resolveProjectAsset)
        val reopened=vm(); assertTrue(reopened.importPreparedProject(store.loadPrepared("folded")!!.project))
        assertEquals(v.audienceAreas.value,reopened.audienceAreas.value)
        assertEquals(before.receivers(),reopened.captureCoveragePlan().prepare().receivers())
        assertEquals(before.triangles(),reopened.captureCoveragePlan().prepare().triangles())
        val stats=com.droidacoustic.pro.statistics.CoverageStatisticsCalculator.calculate(before,FloatArray(before.receiverCount) {90f})
        assertEquals(88.0,stats.overall.totalAreaM2,2e-5)
        assertTrue(v.undoScene()); assertEquals(1,v.audienceAreas.value.size)
        assertTrue(v.redoScene()); assertEquals(2,v.audienceAreas.value.size)
        assertEquals(listOf(0f,3f),captured.receivers().filter {it.x==2f && it.z==2f}.map {it.surfaceY}.sorted())
    }
    @Test fun verticalContoursAndHeatmapsHaveNonzero3DRenderingGeometry() {
        val v=vm(); assertTrue(v.importAudienceObj(verticalObj))
        val m=v.captureCoveragePlan().prepare()
        val cells=m.receivers().mapIndexed {i,r -> HeatCell(r.x,r.z,90+r.z,sourceAreaId=r.areaId,
            renderY=r.surfaceY,sampleIndex=i,surfaceKey=r.surfaceKey)}
        val heat=decode(HeatmapGlb.build(cells,90f,94f,geometry=m)!!)
        assertEquals(m.triangleCount*3,heat.positions.size)
        heat.positions.chunked(3).forEach {tri -> assertTrue(tri.map {it[1]}.distinct().size>1) }
        val lines=decode(ContoursGlb.build(cells,listOf(92f),geometry=m)!!)
        assertTrue(lines.positions.all {kotlin.math.abs(it[0]-0.25f)<1e-5})
        assertEquals(0f,lines.positions.minOf {it[1]},1e-5f); assertEquals(4f,lines.positions.maxOf {it[1]},1e-5f)
        lines.positions.chunked(3).forEach {tri ->
            val a=tri[0]; val b=tri[1]; val c=tri[2]
            val cross=(b[1]-a[1])*(c[2]-a[2])-(b[2]-a[2])*(c[1]-a[1])
            assertTrue(kotlin.math.abs(cross)>1e-7)
        }
        val edges=decode(AudienceAreasGlb.build(v.audienceAreas.value,emptyList())!!)
        assertTrue(edges.positions.chunked(2).any {it[0][0]==it[1][0] && it[0][2]==it[1][2] && it[0][1]!=it[1][1]})
    }
    @Test fun overhangPlanCutoutsAffectEveryLayerAndRetainRemainingWallArea() {
        val v=vm(); v.addSpeaker(-8f,0f); v.addImportedAudienceMesh(overhangingMesh())
        assertTrue(v.addAudienceCutout(0,listOf(-1f to -1f,2f to -1f,2f to 5f,-1f to 5f),false))
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(28.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-6)
        assertTrue(v.heatmap.value.all {it.x>=2})
        assertTrue(v.heatmap.value.any {it.x==4f && it.renderY>0 && it.renderY<3})
        assertTrue(v.undoScene()); v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(44.0,v.coverageStatistics.value!!.overall.totalAreaM2,1e-6)
    }
    @Test fun previousMeshSchemaReopensAndCorrupt3DFacesFailAtomically() {
        val v=vm(); assertTrue(v.importAudienceObj(importedObj))
        val legacy=JSONObject(v.exportSceneJson()).put("version",12)
        legacy.getJSONArray("audienceAreas").getJSONObject(0).getJSONObject("audienceMesh").put("version",1)
        val reopened=vm(); assertTrue(reopened.importSceneJson(legacy.toString()))
        assertEquals(v.captureCoveragePlan().prepare().receivers(),reopened.captureCoveragePlan().prepare().receivers())
        v.addImportedAudienceMesh(overhangingMesh()); val before=v.exportSceneJson()
        val broken=JSONObject(before)
        val faces=broken.getJSONArray("audienceAreas").getJSONObject(1).getJSONObject("audienceMesh").getJSONArray("triangles")
        faces.put(JSONObject(faces.getJSONObject(0).toString()))
        assertFalse(v.importSceneJson(broken.toString())); assertEquals(before,v.exportSceneJson())
    }
    private val importedObj="v 0 0 0\nv 4 0 0\nv 4 4 4\nv 0 0 4\nf 1 2 3\nf 1 3 4"
    private fun importedRing(): AudienceMesh {
        val nodes=listOf(0.0 to 0.0,4.0 to 0.0,4.0 to 4.0,0.0 to 4.0,1.0 to 1.0,3.0 to 1.0,3.0 to 3.0,1.0 to 3.0)
            .map { AudienceMeshVertex(it.first,kotlin.math.min(it.first,it.second).toFloat(),it.second) }
        return AudienceMesh.create(nodes,(0..3).flatMap { i -> val j=(i+1)%4
            listOf(SurfaceTriangle(i,j,j+4),SurfaceTriangle(i,j+4,i+4)) })
    }
    @Test fun importedAudienceFeedsAndroidPredictionStatisticsAndCache() {
        val v=vm(); v.addSpeaker(-8f,0f)
        assertTrue(v.importAudienceObj(importedObj))
        assertEquals(1,v.audienceAreas.value.size)
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(PredictionState.CURRENT,v.predictionState.value)
        val m=v.coverageGeometry.value!!
        assertEquals(16*kotlin.math.sqrt(2.0),v.coverageStatistics.value!!.overall.totalAreaM2,1e-5)
        val center=v.heatmap.value.first { it.x==2f && it.z==2f }
        assertEquals(2f,center.renderY,1e-5f); assertEquals(3.2f,center.receiverY!!,1e-5f)
        v.setSignalLevelDbu(3f); v.recalculateSignal(); scheduler.runCurrent()
        assertSame(m,v.coverageGeometry.value)
        val captured=v.capturePredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),v.speakers.value,
            emptyList(),coveragePlan=v.captureCoveragePlan())
        v.setAudienceAreaRotation(0,35f); assertNull(v.coverageGeometry.value)
        assertEquals(m.receivers(),captured.calculate().geometry!!.receivers())
        v.recalculateSignal(); scheduler.runCurrent(); assertNotSame(m,v.coverageGeometry.value)
    }
    @Test fun importedTopologyAndVoidsSurviveSaveReopenAndUndoWithoutSourceFile() {
        val v=vm(); v.addImportedAudienceMesh(importedRing(),"Balcony mesh")
        val before=v.captureCoveragePlan().prepare()
        val store=LocalProjectStore(folder.root)
        store.save("imported",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("imported")!!.project))
        assertEquals(v.audienceAreas.value,fresh.audienceAreas.value)
        assertEquals(before.receivers(),fresh.captureCoveragePlan().prepare().receivers())
        assertEquals(before.triangles(),fresh.captureCoveragePlan().prepare().triangles())
        v.removeAudienceArea(0); assertTrue(v.undoScene()); assertEquals(importedRing(),v.audienceAreas.value.single().importedMesh)
        assertTrue(v.redoScene()); assertTrue(v.audienceAreas.value.isEmpty())
    }
    @Test fun malformedObjAndEmbeddedMeshCannotPartiallyReplaceScene() {
        val v=vm(); assertTrue(v.importAudienceObj(importedObj)); val before=v.exportSceneJson()
        assertFalse(v.importAudienceObj(importedObj.replace("f 1 2 3","f 1 2 3 4")))
        assertEquals(before,v.exportSceneJson()); assertNotNull(v.audienceGeometryError.value)
        val bad=JSONObject(before)
        bad.getJSONArray("audienceAreas").getJSONObject(0).getJSONObject("audienceMesh").getJSONArray("triangles")
            .getJSONObject(0).put("a",99)
        assertFalse(v.importSceneJson(bad.toString())); assertEquals(before,v.exportSceneJson())
        val missing=JSONObject(before)
        missing.getJSONArray("audienceAreas").getJSONObject(0).remove("audienceMesh")
        assertFalse(v.importSceneJson(missing.toString())); assertEquals(before,v.exportSceneJson())
        assertTrue(v.importAudienceObj(importedObj)); assertEquals(listOf(0,1),v.audienceAreas.value.map { it.id })
    }
    @Test fun importedMeshTransformsRetainHeightsFacesAndRake() {
        val v=vm(); v.addImportedAudienceMesh(importedRing())
        v.setAudienceAreaBaseHeight(0,5f); v.setAudienceAreaRake(0,10f)
        val original=v.audienceAreas.value.single(); val shape=original.sharedSurface()
        val heights=original.importedMesh!!.vertices().map { shape.surfaceY(it.x.toFloat(),it.z.toFloat()) }
        fun checkHeights() {
            val area=v.audienceAreas.value.first(); val s=area.sharedSurface()
            area.importedMesh!!.vertices().forEachIndexed { i,p -> assertEquals(heights[i],s.surfaceY(p.x.toFloat(),p.z.toFloat()),2e-5f) }
            s.validateGeometry(); v.captureCoveragePlan().prepare()
        }
        v.setAudienceAreaRotation(0,35f); checkHeights()
        v.mirrorAudienceAreaX(0,null); checkHeights()
        v.mirrorAudienceAreaZ(0,null); checkHeights()
        val roundTrip=vm(); assertTrue(roundTrip.importSceneJson(v.exportSceneJson()))
        assertEquals(v.audienceAreas.value,roundTrip.audienceAreas.value)
        v.duplicateAudienceArea(0)
        assertEquals(2,v.audienceAreas.value.size)
        val first=v.audienceAreas.value.first().importedMesh!!; val clone=v.audienceAreas.value.last().importedMesh!!
        first.vertices().zip(clone.vertices()).forEach { (a,b) -> assertEquals(a.x+1,b.x,1e-5); assertEquals(a.y,b.y,0f) }
        assertEquals(first.triangles(),clone.triangles())
    }
    @Test fun meshEditingPreservesTopologyAndCutoutsStillWork() {
        val v=vm(); v.addSpeaker(-8f,0f); v.addImportedAudienceMesh(importedRing())
        val before=v.exportSceneJson()
        assertFalse(v.setAudienceVertexOffset(0,0,1f)); assertEquals(before,v.exportSceneJson())
        assertFalse(v.addAudienceElevationPoint(0,0.5f,0.5f,1f)); assertEquals(before,v.exportSceneJson())
        assertFalse(v.addAudienceBreakline(0,boundary(0),boundary(2))); assertEquals(before,v.exportSceneJson())
        v.setAudienceAreaEdgeLength(0,0,5f); assertEquals(before,v.exportSceneJson())
        val cut=listOf(-1f to -1f,2f to -1f,2f to 5f,-1f to 5f)
        assertTrue(v.addAudienceCutout(0,cut,false)); v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(6*kotlin.math.sqrt(2.0),v.coverageStatistics.value!!.overall.totalAreaM2,1e-5)
        assertTrue(v.heatmap.value.all { it.x>=2 })
        val area=v.audienceAreas.value.first()
        val grid=com.droidacoustic.pro.optimizer.CoverageGridBuilder.buildGrid(emptyList(),listOf(area),1f)
        assertTrue(grid.isNotEmpty()); grid.forEach { assertTrue(area.sharedSurface().containsAudiencePoint(it.x,it.z)) }
    }
    @Test fun importedMeshRenderingKeepsHoleAndTriangleElevations() {
        val v=vm(); v.addImportedAudienceMesh(importedRing()); val area=v.audienceAreas.value.single()
        val mesh=v.captureCoveragePlan().prepare()
        val values=mesh.receivers().mapIndexed { i,r -> HeatCell(r.x,r.z,90+r.x,sourceAreaId=r.areaId,
            renderY=r.surfaceY,sampleIndex=i,surfaceKey=r.surfaceKey) }
        val field=decode(HeatmapGlb.build(values,90f,100f,geometry=mesh)!!)
        field.positions.chunked(3).forEach { tri -> val x=tri.map {it[0]}.average(); val z=tri.map {it[2]}.average()
            assertFalse(x>1 && x<3 && z>1 && z<3)
            tri.forEach { assertEquals(kotlin.math.min(it[0],it[2])+0.03f,it[1],1e-5f) } }
        val outline=decode(AudienceAreasGlb.build(listOf(area),emptyList())!!)
        val edges=outline.positions.indices.filter { outline.colors[it]==listOf(0.2f,0.9f,1f,1f) }.map { outline.positions[it] }
        assertTrue(edges.isNotEmpty())
        edges.forEach { assertEquals(kotlin.math.min(it[0],it[2])+0.07f,it[1],1e-5f) }
    }
    @Test fun preMeshSchemaStillReopensAuthoredBreaklineSurfaces() {
        val v=vm(); populate(v); assertTrue(v.setAudienceVertexOffset(0,2,4f))
        assertTrue(v.addAudienceBreakline(0,boundary(0),boundary(2)))
        val before=v.captureCoveragePlan().prepare(); val old=JSONObject(v.exportSceneJson()).put("version",11)
        for (i in 0 until old.getJSONArray("audienceAreas").length()) old.getJSONArray("audienceAreas").getJSONObject(i).remove("geometryType")
        val fresh=vm(); assertTrue(fresh.importSceneJson(old.toString()))
        assertEquals(before.receivers(),fresh.captureCoveragePlan().prepare().receivers())
        assertTrue(fresh.audienceAreas.value.all { it.importedMesh==null })
    }
    private fun boundary(index: Int)=AudienceVertexRef(AudienceVertexKind.BOUNDARY,index)
    private fun interior(index: Int)=AudienceVertexRef(AudienceVertexKind.INTERIOR,index)
    @Test fun breaklineEditUpdatesPredictionAreaAndPreservesCapturedRequest() {
        val v=vm(); populate(v); assertTrue(v.setAudienceVertexOffset(0,2,4f))
        val captured=v.capturePredictionRequest(PredictionTicket(0,1,PredictionChannel.COVERAGE),v.speakers.value,
            emptyList(),coveragePlan=v.captureCoveragePlan())
        v.recalculateSignal(); scheduler.runCurrent()
        val old=v.coverageGeometry.value!!
        assertTrue(v.addAudienceBreakline(0,boundary(0),boundary(2)))
        assertNull(v.coverageGeometry.value); assertNull(v.coverageStatistics.value)
        v.recalculateSignal(); scheduler.runCurrent()
        assertNotSame(old,v.coverageGeometry.value)
        assertEquals(16+16*kotlin.math.sqrt(2.0),v.coverageStatistics.value!!.overall.totalAreaM2,1e-5)
        val point=v.heatmap.value.first { it.sourceAreaId==0 && it.x==2f && it.z==2f }
        assertEquals(2f,point.renderY,1e-5f); assertEquals(3.2f,point.receiverY!!,1e-5f)
        assertEquals(old.receivers(),captured.calculate().geometry!!.receivers())
        v.removeAudienceBreakline(0,0); assertNull(v.coverageGeometry.value)
        v.recalculateSignal(); scheduler.runCurrent()
        assertEquals(16+8*(1+kotlin.math.sqrt(3.0)),v.coverageStatistics.value!!.overall.totalAreaM2,1e-5)
    }
    @Test fun breaklinesSurviveSaveReopenUndoRedoAndLegacyMigration() {
        val v=vm(); populate(v); assertTrue(v.setAudienceVertexOffset(0,2,4f))
        assertTrue(v.addAudienceBreakline(0,boundary(0),boundary(2)))
        assertTrue(v.addAudienceCutout(0,cutout(),true))
        val before=v.captureCoveragePlan().prepare()
        val store=LocalProjectStore(folder.root)
        store.save("ridge",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("ridge")!!.project))
        assertEquals(v.audienceAreas.value,fresh.audienceAreas.value)
        assertEquals(before.receivers(),fresh.captureCoveragePlan().prepare().receivers())
        assertEquals(before.triangles(),fresh.captureCoveragePlan().prepare().triangles())
        v.removeAudienceBreakline(0,0); assertTrue(v.audienceAreas.value.first().breaklines.isEmpty())
        assertTrue(v.undoScene()); assertEquals(1,v.audienceAreas.value.first().breaklines.size)
        assertTrue(v.redoScene()); assertTrue(v.audienceAreas.value.first().breaklines.isEmpty())
        val legacy=JSONObject(v.exportSceneJson()).put("version",10)
        for (i in 0 until legacy.getJSONArray("audienceAreas").length()) legacy.getJSONArray("audienceAreas").getJSONObject(i).remove("breaklines")
        assertTrue(fresh.importSceneJson(legacy.toString()))
        assertTrue(fresh.audienceAreas.value.all { it.breaklines.isEmpty() })
        assertEquals(4f,fresh.audienceAreas.value.first().vertexOffsetsM[2],0f)
    }
    @Test fun invalidBreaklineEditsAndImportsAreAtomic() {
        val v=vm(); populate(v); assertTrue(v.addAudienceBreakline(0,boundary(0),boundary(2)))
        val before=v.exportSceneJson()
        assertFalse(v.addAudienceBreakline(0,boundary(1),boundary(3)))
        assertNotNull(v.audienceGeometryError.value); assertEquals(before,v.exportSceneJson())
        assertFalse(v.addAudienceBreakline(0,boundary(-1),boundary(2))); assertEquals(before,v.exportSceneJson())
        assertFalse(v.addAudienceBreakline(0,boundary(2),boundary(0))); assertEquals(before,v.exportSceneJson())
        for (index in listOf<Any>(99,-1,0.5,"0")) {
            val bad=JSONObject(before)
            bad.getJSONArray("audienceAreas").getJSONObject(0).getJSONArray("breaklines").getJSONObject(0)
                .getJSONObject("start").put("index",index)
            assertFalse(v.importSceneJson(bad.toString())); assertEquals(before,v.exportSceneJson())
        }
    }
    @Test fun removingHeightPointsRemapsRemainingLinesAndResetClearsThem() {
        val v=vm(); populate(v)
        assertTrue(v.addAudienceElevationPoint(0,1f,1f,1f)); assertTrue(v.addAudienceElevationPoint(0,3f,3f,3f))
        assertTrue(v.addAudienceBreakline(0,boundary(0),interior(0)))
        assertTrue(v.addAudienceBreakline(0,boundary(2),interior(1)))
        v.removeAudienceElevationPoint(0,0)
        assertEquals(listOf(AudienceBreakline(boundary(2),interior(0))),v.audienceAreas.value.first().breaklines)
        v.captureCoveragePlan().prepare()
        assertTrue(v.undoScene()); assertEquals(2,v.audienceAreas.value.first().breaklines.size)
        v.resetAudienceElevations(0)
        assertTrue(v.audienceAreas.value.first().breaklines.isEmpty())
        assertTrue(v.audienceAreas.value.first().elevationPoints.isEmpty())
    }
    @Test fun breaklineTransformsPreserveSlopeAndPhysicalArea() {
        val v=vm(); populate(v); assertTrue(v.setAudienceVertexOffset(0,2,4f))
        assertTrue(v.addAudienceBreakline(0,boundary(0),boundary(2)))
        fun verify() {
            val area=v.audienceAreas.value.first(); val shape=area.sharedSurface()
            val a=area.vertices[0]; val b=area.vertices[2]
            assertEquals(2f,shape.surfaceY((a.first+b.first)/2,(a.second+b.second)/2),1e-5f)
            v.recalculateSignal(); scheduler.runCurrent()
            assertEquals(16+16*kotlin.math.sqrt(2.0),v.coverageStatistics.value!!.overall.totalAreaM2,1e-4)
        }
        v.setAudienceAreaRotation(0,35f); verify()
        v.mirrorAudienceAreaX(0,null); verify()
        v.mirrorAudienceAreaZ(0,null); verify()
        v.duplicateAudienceArea(0)
        assertEquals(v.audienceAreas.value.first().breaklines,v.audienceAreas.value.last().breaklines)
    }
    @Test fun rotatingSharedBreaklineJunctionKeepsConnectivity() {
        val v=vm(); populate(v)
        assertTrue(v.addAudienceElevationPoint(0,2f,2f,2f))
        assertTrue(v.addAudienceBreakline(0,boundary(0),boundary(2)))
        assertTrue(v.addAudienceBreakline(0,boundary(1),boundary(3)))
        for (angle in listOf(35f,70f,130f,200f,310f)) {
            v.setAudienceAreaRotation(0,angle)
            v.audienceAreas.value.first().sharedSurface().validateGeometry()
            v.captureCoveragePlan().prepare()
        }
        v.removeAudienceElevationPoint(0,0)
        assertTrue(v.audienceAreas.value.first().elevationPoints.isEmpty())
        assertTrue(v.audienceAreas.value.first().breaklines.isEmpty())
        v.captureCoveragePlan().prepare()
    }
    @Test fun rendererShowsElevatedBreaklinesClippedAtHoles() {
        val v=vm(); populate(v); assertTrue(v.setAudienceVertexOffset(0,2,4f))
        assertTrue(v.addAudienceBreakline(0,boundary(0),boundary(2)))
        assertTrue(v.addAudienceCutout(0,cutout(),true))
        val shape=v.audienceAreas.value.first()
        val glb=decode(AudienceAreasGlb.build(listOf(shape),emptyList())!!)
        val cyan=glb.positions.indices.filter { glb.colors[it]==listOf(0.2f,0.9f,1f,1f) }.map { glb.positions[it] }
        assertTrue(cyan.isNotEmpty())
        assertEquals(2.0,cyan.chunked(2).sumOf { kotlin.math.abs(it[1][0]-it[0][0]).toDouble() },1e-5)
        cyan.chunked(2).forEach { line ->
            val mid=(line[0][0]+line[1][0])/2
            assertFalse(mid>1f && mid<3f)
            line.forEach { assertEquals(it[0],it[2],1e-5f); assertEquals(it[0]+0.07f,it[1],1e-5f) }
        }
    }
    private fun mesh()=CoveragePlan(listOf(0,1).map { id -> AudienceSurface(id,"Layer $id","AUDIENCE_SEATED",
        listOf(PlanPoint(0.0,0.0),PlanPoint(4.0,0.0),PlanPoint(4.0,4.0),PlanPoint(0.0,4.0)),id*5f) },emptyList(),28f,28f,2f).prepare()
    private fun cells(mesh: PreparedAudienceGeometry)=mesh.receivers().mapIndexed { i,r ->
        HeatCell(r.x,r.z,100-r.x-r.surfaceY,sourceAreaId=r.areaId,renderY=r.surfaceY,sampleIndex=i,surfaceKey=r.surfaceKey) }
    private data class Glb(val positions: List<List<Float>>,val colors: List<List<Float>>)
    private fun decode(bytes: ByteArray): Glb {
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val jsonSize=b.getInt(12); val json=JSONObject(String(bytes,20,jsonSize,Charsets.UTF_8)); val start=28+jsonSize
        val count=json.getJSONArray("accessors").getJSONObject(0).getInt("count")
        val colorOffset=json.getJSONArray("bufferViews").getJSONObject(1).getInt("byteOffset")
        return Glb((0 until count).map { i -> (0..2).map { b.getFloat(start+i*12+it*4) } },
            (0 until count).map { i -> (0..3).map { b.getFloat(start+colorOffset+i*16+it*4) } })
    }
    @Test fun heatmapGlbRetainsBothHeightsAndTheirOwnLevels() {
        val mesh=mesh(); val cells=cells(mesh)
        val glb=decode(HeatmapGlb.build(cells,85f,100f,geometry=mesh)!!)
        assertEquals(mesh.triangles().size*3,glb.positions.size)
        assertEquals(setOf(0.03f,5.03f),glb.positions.map { it[1] }.toSet())
        glb.positions.chunked(3).forEach { assertEquals(1,it.map { p -> p[1] }.distinct().size) }
        val bottom=glb.positions.indexOfFirst { it[0]==0f && it[2]==0f && it[1]<1f }
        val top=glb.positions.indexOfFirst { it[0]==0f && it[2]==0f && it[1]>5f }
        assertNotEquals(glb.colors[bottom],glb.colors[top])
        // A missing sample removes its faces; it cannot bridge to the other layer.
        val missing=cells.filter { it.sampleIndex!=cells.first().sampleIndex }
        val partial=decode(HeatmapGlb.build(missing,85f,100f,geometry=mesh)!!)
        val expected=mesh.triangles().count { cells.first().sampleIndex !in listOf(it.a,it.b,it.c) }*3
        assertEquals(expected,partial.positions.size)
    }
    @Test fun contourGlbKeepsOverlappingFloorsSeparate() {
        val mesh=mesh(); val equalLevels=cells(mesh).map { it.copy(splDb=100-it.x) }
        val glb=decode(ContoursGlb.build(equalLevels,listOf(98f),geometry=mesh)!!)
        assertEquals(setOf(0.25f,5.25f),glb.positions.map { it[1] }.toSet())
        glb.positions.chunked(6).forEach { assertEquals(1,it.map { p -> p[1] }.distinct().size) }
        assertTrue(glb.positions.all { kotlin.math.abs(it[0]-2)<0.05 })
    }
}
