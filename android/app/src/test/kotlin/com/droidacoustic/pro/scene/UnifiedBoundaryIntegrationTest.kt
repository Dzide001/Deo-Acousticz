package com.droidacoustic.pro.scene

import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.ui.VenueGeometryGlb
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedBoundaryIntegrationTest {
    @get:Rule val folder=TemporaryFolder()
    private val scheduler=TestCoroutineScheduler()
    private val dispatcher=StandardTestDispatcher(scheduler)
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()}; scheduler.runCurrent(); Dispatchers.resetMain()}
    private fun vm()=SceneViewModel(PredictionRunner {it.calculate()},dispatcher).also {models+=it}
    private fun setup(v: SceneViewModel) {
        v.setStageBoundaryRole(AcousticBoundaryRole.NONE); v.addSpeaker(-3f,0f)
        v.addVenueBlock("OBSTACLE",0f,0f); v.setVenueBlockSize(0,2f,10f)
        v.setVenueBlockHeight(0,5f); v.setVenueBlockThickness(0,10f)
    }
    private fun imported(block: VenueBlock): AudienceMesh {
        val s=VenueBoundaryGeometry.block(block)
        return AudienceMesh.create(s.vertices.map {AudienceMeshVertex(it.x,it.y.toFloat(),it.z)},s.triangles)
            .withBoundaryRoles(List(s.triangles.size) {block.boundaryRole})
            .withFaceAssignments(List(s.triangles.size) {MeshFaceAssignment.EXCLUDED})
    }
    private fun level(v: SceneViewModel,x: Float)=v.predictBandDb(v.speakers.value,x,1.2f,0f,1000)
    @Test fun blocksAndFacesShareVisibilityAndDuplicateReflectionsCountOnce() {
        val v=vm(); setup(v)
        assertEquals(0f,level(v,3f),0f)
        v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.NONE)
        assertTrue(level(v,3f)>0f)
        val clear=level(v,-4f)
        v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.RIGID_REFLECTOR)
        val reflected=level(v,-4f); assertTrue(kotlin.math.abs(reflected-clear)>0.001f)
        v.addImportedAudienceMesh(imported(v.venueGeometry.value.blocks.single()))
        assertEquals(reflected,level(v,-4f),1e-5f)
        assertEquals(0f,level(v,3f),0f)
    }
    @Test fun roleEditsInvalidateFreezeAndUndoWithoutAddingAudienceCoverage() {
        val v=vm(); setup(v)
        v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.NONE)
        val frozen=v.capturePredictionRequest(PredictionTicket(0,1,PredictionChannel.LISTENER),v.speakers.value,
            listOf(HeatCell(3f,0f,0f)),heights=listOf(1.2f),bands=listOf(1000),selected=1000)
        val free=frozen.calculate().levelAt(0)
        v.recalculateSignal(); scheduler.runCurrent(); val area=v.coverageStatistics.value!!.overall.totalAreaM2
        v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.OCCLUDING)
        assertTrue(v.heatmap.value.isEmpty()); assertNull(v.coverageStatistics.value)
        assertEquals(free,frozen.calculate().levelAt(0),0f); assertEquals(0f,level(v,3f),0f)
        assertTrue(v.undoScene()); assertEquals(AcousticBoundaryRole.NONE,v.venueGeometry.value.blocks.single().boundaryRole)
        assertTrue(v.redoScene()); assertEquals(AcousticBoundaryRole.OCCLUDING,v.venueGeometry.value.blocks.single().boundaryRole)
        v.recalculateSignal(); scheduler.runCurrent(); assertEquals(area,v.coverageStatistics.value!!.overall.totalAreaM2,0.0)
    }
    @Test fun materialsSurviveColdReopenAndVenueExchange() {
        val v=vm(); setup(v); v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.RIGID_REFLECTOR)
        v.setVenueBlockMaterial(0,BoundaryMaterial.CARPET); v.setStageMaterial(BoundaryMaterial.PANEL)
        v.addImportedAudienceMesh(imported(v.venueGeometry.value.blocks.single()))
        v.setImportedMaterial(0,listOf(0,1),BoundaryMaterial.GYPSUM)
        val store=LocalProjectStore(folder.root); store.save("materials",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("materials")!!.project))
        assertEquals(v.venueGeometry.value,fresh.venueGeometry.value)
        assertEquals(v.audienceAreas.value,fresh.audienceAreas.value)
        assertEquals(level(v,-4f),level(fresh,-4f),1e-4f)
        val exchange=vm(); assertTrue(exchange.importVenueJson(v.exportVenueJson()))
        assertEquals(v.venueGeometry.value,exchange.venueGeometry.value)
    }
    @Test fun customMaterialsSurviveColdReopenAndVenueExchange() {
        val v=vm(); setup(v); v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.RIGID_REFLECTOR)
        v.setVenueBlockMaterial(0,BoundaryMaterial.custom("Measured wall",listOf(100 to .1f,1000 to .8f),"Lab report / mounting A")); v.setStageMaterial(BoundaryMaterial.custom("Measured stage",listOf(125 to .2f,4000 to .9f)))
        v.addImportedAudienceMesh(imported(v.venueGeometry.value.blocks.single()))
        v.setImportedMaterial(0,listOf(0,1),BoundaryMaterial.custom("Measured face",listOf(125 to .3f,2000 to .7f)))
        val store=LocalProjectStore(folder.root); store.save("materials",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("materials")!!.project))
        assertEquals(v.venueGeometry.value,fresh.venueGeometry.value)
        assertEquals(v.audienceAreas.value,fresh.audienceAreas.value)
        assertEquals(level(v,-4f),level(fresh,-4f),1e-4f)
        val exchange=vm(); assertTrue(exchange.importVenueJson(v.exportVenueJson()))
        assertEquals(v.venueGeometry.value,exchange.venueGeometry.value)
    }
    @Test fun angularMaterialsSurviveColdReopenAndVenueExchange() {
        val v=vm(); setup(v); v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.RIGID_REFLECTOR)
        v.setVenueBlockMaterial(0,BoundaryMaterial.angular("Angular wall",listOf(0f to listOf(100 to .1f,1000 to .8f),75f to listOf(100 to .8f,1000 to .2f)),"Lab / mounting A")); v.setStageMaterial(BoundaryMaterial.angular("Angular stage",listOf(0f to listOf(125 to .2f,4000 to .9f),90f to listOf(125 to .6f,4000 to .3f))))
        v.addImportedAudienceMesh(imported(v.venueGeometry.value.blocks.single()))
        v.setImportedMaterial(0,listOf(0,1),BoundaryMaterial.angular("Angular face",listOf(0f to listOf(125 to .3f,2000 to .7f),60f to listOf(125 to .9f,2000 to .1f))))
        val store=LocalProjectStore(folder.root); store.save("materials",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("materials")!!.project))
        assertEquals(v.venueGeometry.value,fresh.venueGeometry.value)
        assertEquals(v.audienceAreas.value,fresh.audienceAreas.value)
        assertEquals(level(v,-4f),level(fresh,-4f),1e-4f)
        val exchange=vm(); assertTrue(exchange.importVenueJson(v.exportVenueJson()))
        assertEquals(v.venueGeometry.value,exchange.venueGeometry.value)
    }
    @Test fun reflectionPhaseSurvivesColdReopenAndVenueExchange() {
        val v=vm(); setup(v); v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.RIGID_REFLECTOR)
        v.setVenueBlockMaterial(0,BoundaryMaterial.angular("Angular wall",listOf(0f to listOf(100 to .1f,1000 to .8f),75f to listOf(100 to .8f,1000 to .2f)),"Lab / mounting A")); v.setStageMaterial(BoundaryMaterial.angular("Angular stage",listOf(0f to listOf(125 to .2f,4000 to .9f),90f to listOf(125 to .6f,4000 to .3f))))
        v.addImportedAudienceMesh(imported(v.venueGeometry.value.blocks.single()))
        v.setImportedMaterial(0,listOf(0,1),BoundaryMaterial.angular("Angular face",listOf(0f to listOf(125 to .3f,2000 to .7f),60f to listOf(125 to .9f,2000 to .1f))))
        val phase=com.droidacoustic.pro.geometry.ReflectionPhase.create(listOf(
            0f to listOf(125 to -90.0,4000 to -450.0),75f to listOf(125 to 180.0,4000 to 540.0)),"Lab phase reference")
        v.setVenueBlockMaterial(0,v.venueGeometry.value.blocks.single().boundaryMaterial.withReflectionPhase(phase))
        v.setStageMaterial(v.venueGeometry.value.stageBoundaryMaterial.withReflectionPhase(phase))
        v.setImportedMaterial(0,listOf(0,1),BoundaryMaterial.CARPET.withReflectionPhase(phase))
        val store=LocalProjectStore(folder.root); store.save("materials",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("materials")!!.project))
        assertEquals(v.venueGeometry.value,fresh.venueGeometry.value)
        assertEquals(v.audienceAreas.value,fresh.audienceAreas.value)
        assertEquals(level(v,-4f),level(fresh,-4f),1e-4f)
        val exchange=vm(); assertTrue(exchange.importVenueJson(v.exportVenueJson()))
        assertEquals(v.venueGeometry.value,exchange.venueGeometry.value)
    }
    @Test fun scatteringSurvivesColdReopenAndVenueExchange() {
        val v=vm(); setup(v); v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.RIGID_REFLECTOR)
        v.setVenueBlockMaterial(0,BoundaryMaterial.angular("Angular wall",listOf(0f to listOf(100 to .1f,1000 to .8f),75f to listOf(100 to .8f,1000 to .2f)),"Lab / mounting A")); v.setStageMaterial(BoundaryMaterial.angular("Angular stage",listOf(0f to listOf(125 to .2f,4000 to .9f),90f to listOf(125 to .6f,4000 to .3f))))
        v.addImportedAudienceMesh(imported(v.venueGeometry.value.blocks.single()))
        v.setImportedMaterial(0,listOf(0,1),BoundaryMaterial.angular("Angular face",listOf(0f to listOf(125 to .3f,2000 to .7f),60f to listOf(125 to .9f,2000 to .1f))))
        val phase=com.droidacoustic.pro.geometry.ReflectionPhase.create(listOf(
            0f to listOf(125 to -90.0,4000 to -450.0),75f to listOf(125 to 180.0,4000 to 540.0)),"Lab phase reference")
        v.setVenueBlockMaterial(0,v.venueGeometry.value.blocks.single().boundaryMaterial.withReflectionPhase(phase))
        v.setStageMaterial(v.venueGeometry.value.stageBoundaryMaterial.withReflectionPhase(phase))
        v.setImportedMaterial(0,listOf(0,1),BoundaryMaterial.CARPET.withReflectionPhase(phase))
        val scattering=com.droidacoustic.pro.geometry.MaterialScattering.create(listOf(
            0f to listOf(125 to .1f,4000 to .8f),75f to listOf(125 to .6f,4000 to .2f)),"Measured scattering")
        v.setVenueBlockMaterial(0,v.venueGeometry.value.blocks.single().boundaryMaterial.withScattering(scattering))
        v.setStageMaterial(v.venueGeometry.value.stageBoundaryMaterial.withScattering(scattering))
        v.setImportedMaterial(0,listOf(0,1),BoundaryMaterial.CARPET.withReflectionPhase(phase).withScattering(scattering))
        val store=LocalProjectStore(folder.root); store.save("materials",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("materials")!!.project))
        assertEquals(v.venueGeometry.value,fresh.venueGeometry.value)
        assertEquals(v.audienceAreas.value,fresh.audienceAreas.value)
        assertEquals(level(v,-4f),level(fresh,-4f),1e-4f)
        val exchange=vm(); assertTrue(exchange.importVenueJson(v.exportVenueJson()))
        assertEquals(v.venueGeometry.value,exchange.venueGeometry.value)
    }
    @Test fun coldReopenAndVenueExchangePreserveRolesAndTransforms() {
        val v=vm(); setup(v); v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.RIGID_REFLECTOR)
        v.setStageBoundaryRole(AcousticBoundaryRole.NONE); v.setVenueBlockRotation(0,25f); v.setVenueBlockSlope(0,10f)
        v.duplicateVenueBlock(0); assertTrue(v.venueGeometry.value.blocks.all {it.boundaryRole==AcousticBoundaryRole.RIGID_REFLECTOR})
        val store=LocalProjectStore(folder.root); store.save("boundaries",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=vm(); assertTrue(fresh.importPreparedProject(store.loadPrepared("boundaries")!!.project))
        assertEquals(v.venueGeometry.value,fresh.venueGeometry.value)
        assertEquals(level(v,-4f),level(fresh,-4f),1e-4f)
        val venue=vm(); assertTrue(venue.importVenueJson(v.exportVenueJson())); assertEquals(v.venueGeometry.value,venue.venueGeometry.value)
        assertNotNull(VenueGeometryGlb.build(venue.venueGeometry.value))
    }
    @Test fun oldProjectsMigrateToOpaqueAndMalformedRolesFailAtomically() {
        val v=vm(); setup(v); val saved=v.exportSceneJson()
        for (value in listOf<Any?>(null,JSONObject.NULL,"UNKNOWN",1)) {
            val root=JSONObject(saved); val block=root.getJSONObject("venueGeometry").getJSONArray("blocks").getJSONObject(0)
            if (value==null) block.remove("boundaryRole") else block.put("boundaryRole",value)
            assertFalse(v.importSceneJson(root.toString())); assertEquals(saved,v.exportSceneJson())
        }
        val bad=JSONObject(saved); bad.getJSONObject("venueGeometry").remove("stageBoundaryRole")
        assertFalse(v.importSceneJson(bad.toString())); assertEquals(saved,v.exportSceneJson())
        val old=JSONObject(saved).put("version",16); val vg=old.getJSONObject("venueGeometry")
        vg.remove("stageBoundaryRole"); vg.getJSONArray("blocks").getJSONObject(0).remove("boundaryRole")
        val fresh=vm(); assertTrue(fresh.importSceneJson(old.toString()))
        assertEquals(AcousticBoundaryRole.OCCLUDING,fresh.venueGeometry.value.stageBoundaryRole)
        assertEquals(AcousticBoundaryRole.OCCLUDING,fresh.venueGeometry.value.blocks.single().boundaryRole)
        val venue=JSONObject(v.exportVenueJson()); venue.getJSONObject("venueGeometry").getJSONArray("blocks").getJSONObject(0).put("boundaryRole","bad")
        assertFalse(v.importVenueJson(venue.toString())); assertEquals(saved,v.exportSceneJson())
    }
    @Test fun conflictingCoincidentRolesUseOpaqueAndExactBlockDuplicatesDoNotAddEnergy() {
        val v=vm(); setup(v); v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.RIGID_REFLECTOR)
        val expected=level(v,-4f)
        val json=JSONObject(v.exportSceneJson()); val blocks=json.getJSONObject("venueGeometry").getJSONArray("blocks")
        blocks.put(JSONObject(blocks.getJSONObject(0).toString()).put("id",1))
        assertTrue(v.importSceneJson(json.toString())); assertEquals(expected,level(v,-4f),1e-4f)
        v.addImportedAudienceMesh(imported(v.venueGeometry.value.blocks.first().copy(boundaryRole=AcousticBoundaryRole.OCCLUDING)))
        val opaque=level(v,-4f)
        v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.NONE); v.setVenueBlockBoundaryRole(1,AcousticBoundaryRole.NONE)
        assertEquals(opaque,level(v,-4f),1e-4f)
    }
}
