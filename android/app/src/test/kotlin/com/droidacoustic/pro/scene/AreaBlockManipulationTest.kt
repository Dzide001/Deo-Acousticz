package com.droidacoustic.pro.scene

import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.prediction.PredictionRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AreaBlockManipulationTest {
    private val scheduler=TestCoroutineScheduler()
    private val dispatcher=StandardTestDispatcher(scheduler)
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup() {Dispatchers.setMain(dispatcher)}
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};scheduler.runCurrent();Dispatchers.resetMain()}
    private fun vm()=SceneViewModel(PredictionRunner {it.calculate()},dispatcher).also {models+=it}

    @Test fun blockDragPreviewIsIsolatedAndCombinedMoveYawHasOneUndoCheckpoint() {
        val v=vm();v.addVenueBlock("BALCONY")
        val block=v.venueGeometry.value.blocks.single()
        val before=v.exportSceneJson(true);val revision=v.revision.value
        val preview=v.previewVenueBlockManipulation(block.id,3f,7f,-4f,35f)
        repeat(10) {v.previewVenueBlockManipulation(block.id,it.toFloat(),7f,-4f,35f)}
        assertEquals(before,v.exportSceneJson(true));assertEquals(revision,v.revision.value)
        assertEquals(block.blockHeightM,preview.blocks.single().blockHeightM,0f)
        assertTrue(v.commitVenueBlockManipulation(block.id,3f,7f,-4f,35f))
        assertEquals(preview,v.venueGeometry.value)
        assertTrue(v.undoScene());assertEquals(before,v.exportSceneJson(true))
        assertTrue(v.redoScene());assertEquals(preview,v.venueGeometry.value)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(preview,fresh.venueGeometry.value)
        assertTrue(v.undoScene());assertTrue(v.undoScene());assertTrue(v.venueGeometry.value.blocks.isEmpty())
    }

    @Test fun importedZonePreviewCommitUndoRedoAndReopenRetainFullMesh() {
        val v=vm()
        val mesh=AudienceMesh.create(listOf(AudienceMeshVertex(0.0,0f,0.0),AudienceMeshVertex(4.0,0f,0.0),
            AudienceMeshVertex(0.0,3f,0.0),AudienceMeshVertex(0.0,3f,4.0)),
            listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(2,1,3)))
            .createFaceGroup("Wall",listOf(0))
            .assignBoundaryRoles(listOf(0),AcousticBoundaryRole.OCCLUDING)
        val id=v.addImportedAudienceMesh(mesh)
        val original=v.audienceAreas.value;val before=v.exportSceneJson(true);val revision=v.revision.value
        val preview=v.previewAudienceAreaManipulation(id,8f,5f,9f,90f)
        assertEquals(before,v.exportSceneJson(true));assertEquals(revision,v.revision.value)
        assertEquals(original,v.audienceAreas.value)
        assertTrue(v.commitAudienceAreaManipulation(id,8f,5f,9f,90f))
        assertEquals(preview,v.audienceAreas.value)
        assertTrue(v.undoScene());assertEquals(original,v.audienceAreas.value)
        assertTrue(v.redoScene());assertEquals(preview,v.audienceAreas.value)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(preview,fresh.audienceAreas.value)
        assertEquals(mesh.triangles(),fresh.audienceAreas.value.single().importedMesh!!.triangles())
        assertTrue(v.undoScene());assertTrue(v.undoScene());assertTrue(v.audienceAreas.value.isEmpty())
    }

    @Test fun authoredZoneKeepsCutoutsElevationsBreaklinesAndDimensionsAcrossTransforms() {
        val v=vm()
        listOf(0f to 0f,10f to 0f,10f to 10f,0f to 10f).forEach {(x,z) -> v.addAreaVertex(x,z)}
        v.closeAreaFromDraft()
        val id=v.audienceAreas.value.single().id
        assertTrue(v.addAudienceCutout(id,listOf(1f to 1f,2f to 1f,2f to 2f,1f to 2f),true))
        assertTrue(v.addAudienceElevationPoint(id,5f,5f,2f))
        assertTrue(v.addAudienceBreakline(id,AudienceVertexRef(AudienceVertexKind.BOUNDARY,1),
            AudienceVertexRef(AudienceVertexKind.INTERIOR,0)))
        val original=v.audienceAreas.value.single()
        val before=v.exportSceneJson(true)
        val preview=v.previewAudienceAreaManipulation(id,7f,4f,2f,90f)
        assertEquals(before,v.exportSceneJson(true))
        assertTrue(v.commitAudienceAreaManipulation(id,7f,4f,2f,90f))
        val moved=v.audienceAreas.value.single()
        assertEquals(preview,v.audienceAreas.value)
        assertEquals(original.breaklines,moved.breaklines)
        assertEquals(original.vertexOffsetsM,moved.vertexOffsetsM)
        assertEquals(original.elevationPoints.single().offsetM,moved.elevationPoints.single().offsetM,0f)
        assertEquals(1,moved.holes.size)
        moved.sharedSurface().validateGeometry()
        assertTrue(v.undoScene());assertEquals(original,v.audienceAreas.value.single())
        assertTrue(v.redoScene());assertEquals(moved,v.audienceAreas.value.single())
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(moved,fresh.audienceAreas.value.single())
        val revision=v.revision.value
        assertFalse(v.commitAudienceAreaManipulation(id,Float.NaN,4f,2f,90f))
        assertEquals(revision,v.revision.value)
    }

    @Test fun invalidTargetsNeverChangePersistedStateRevisionOrHistory() {
        val v=vm();v.addVenueBlock("STAGE")
        val id=v.venueGeometry.value.blocks.single().id
        val before=v.exportSceneJson(true);val revision=v.revision.value
        assertFalse(v.commitVenueBlockManipulation(id,Float.NaN,1f,0f,0f))
        assertFalse(v.commitAudienceAreaManipulation(99,1f,1f,1f,Float.POSITIVE_INFINITY))
        assertEquals(before,v.exportSceneJson(true));assertEquals(revision,v.revision.value)
        assertTrue(v.undoScene());assertTrue(v.venueGeometry.value.blocks.isEmpty())
    }
}
