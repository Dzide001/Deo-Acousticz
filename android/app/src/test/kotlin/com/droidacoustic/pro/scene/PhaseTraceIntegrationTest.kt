package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.traces.TraceComparisons
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.experimental.categories.Category

@OptIn(ExperimentalCoroutinesApi::class)
class PhaseTraceIntegrationTest {
    private val scheduler=TestCoroutineScheduler();private val dispatcher=StandardTestDispatcher(scheduler)
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup() {Dispatchers.setMain(dispatcher)}
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};scheduler.runCurrent();Dispatchers.resetMain()}
    private fun vm()=SceneViewModel(PredictionRunner {it.calculate()},dispatcher).also {models+=it}
    private fun scene(complex:Boolean=true)=vm().also {v ->
        v.setVenueSize(8f,8f);v.setStageBoundaryRole(AcousticBoundaryRole.NONE);v.setGroundReflectionEnabled(false)
        v.addSpeaker(0f,0f);v.moveListener(10f,0f);v.setSignalType("BAND");v.setBandHz(1000)
        val response=if(complex) ComplexSpeakerResponse.fromJsonString("""{"version":1,"convention":"${ComplexSpeakerResponse.CONVENTION}","referenceDrive":"1 V rms","provenance":"Test fixture","patterns":[{"frequency":1000,"azimuths":[0],"elevations":[-90,90],"real":[[100,100]],"imaginary":[[0,0]]}]}""") else null
        val data=ClfData("point_small",patterns=listOf(ClfPolarPattern(1000,listOf(0f),listOf(-90f,90f),arrayOf(floatArrayOf(0f,0f)))),sensitivityDbByBand=mapOf(1000 to 40f),complexResponse=response)
        assertTrue(v.importClfJsonData(ClfParser.toJson(data)))
    }
    private fun capture(v:SceneViewModel,name:String) {v.recalculateSignal();scheduler.runCurrent();assertTrue(v.captureListenerTrace(name))}
    @Test fun listenerAndReferencePhaseCompareDelayAndReopenWithExactSpeakerData() {
        val v=scene();capture(v,"A");val a=v.savedTraces.value.traces.single()
        assertNotNull(a.phases[1000]!!.degrees)
        v.setDelay(v.speakers.value.single().id,.25f);capture(v,"B")
        val b=v.savedTraces.value.traces.last()
        assertEquals(90.0,TraceComparisons.comparePhase(a,b).bands.single().deltaDegrees!!,.001)
        v.addAlignmentReceiver("FOH",10f,1.2f,0f);v.selectTraceReceivers(listOf(0));v.captureReferenceTraces("References");scheduler.runCurrent()
        assertNotNull(v.savedTraces.value.traces.last().phases[1000]!!.degrees)
        assertTrue(v.captureDesignVariant("Complex speaker"))
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(v.savedTraces.value.toJsonString(),fresh.savedTraces.value.toJsonString())
        assertTrue(fresh.restoreDesignVariant(0));assertNotNull(fresh.getClfData("point_small")!!.complexResponse)
        capture(fresh,"Reopened");assertEquals(b.phases[1000]!!.degrees!!,fresh.savedTraces.value.traces.last().phases[1000]!!.degrees!!,.001)
    }
    @Test fun magnitudeOnlyAndUnsupportedPropagationNeverInventPhase() {
        val magnitude=scene(false);capture(magnitude,"Magnitude only")
        assertNull(magnitude.savedTraces.value.traces.single().phases[1000]!!.degrees)
        val v=scene();v.setGroundReflectionEnabled(true);capture(v,"Ground")
        assertNull(v.savedTraces.value.traces.last().phases[1000]!!.degrees)
        assertTrue(v.savedTraces.value.traces.last().phases[1000]!!.reason!!.contains("ground"))
        v.setGroundReflectionEnabled(false);v.setStageBoundaryRole(AcousticBoundaryRole.OCCLUDING);capture(v,"Boundary")
        assertNotNull(v.savedTraces.value.traces.last().phases[1000]!!.degrees)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertNotNull(fresh.savedTraces.value.traces.last().phases[1000]!!.degrees)
    }
    @Test fun reflectedBlockAndImportedFacesSurviveReopenWithoutDoubleCounting() {
        val v=scene();val id=v.speakers.value.single().id
        v.setSpeakerPosition(id,3f,1.2f,0f);v.moveListener(4f,0f)
        v.addVenueBlock("OBSTACLE",0f,0f)
        val blockId=v.venueGeometry.value.blocks.single().id
        v.setVenueBlockSize(blockId,2f,20f);v.setVenueBlockHeight(blockId,10f);v.setVenueBlockThickness(blockId,20f)
        v.setVenueBlockBoundaryRole(blockId,AcousticBoundaryRole.NONE);capture(v,"Direct")
        val direct=v.savedTraces.value.traces.last().phases[1000]!!.degrees!!
        v.setVenueBlockBoundaryRole(blockId,AcousticBoundaryRole.RIGID_REFLECTOR)
        val material=BoundaryMaterial.IDEAL_RIGID.withReflectionPhase(ReflectionPhase.create(listOf(0f to listOf(100 to 90.0,10000 to 90.0))))
        v.setVenueBlockMaterial(blockId,material);capture(v,"Block")
        val a=v.savedTraces.value.traces.last();assertNotNull(a.phases[1000]!!.degrees)
        assertTrue(kotlin.math.abs(a.phases[1000]!!.degrees!!-direct)>.001)
        val shape=VenueBoundaryGeometry.block(v.venueGeometry.value.blocks.single())
        val mesh=AudienceMesh.create(shape.vertices.map {AudienceMeshVertex(it.x,it.y.toFloat(),it.z)},shape.triangles)
            .withBoundaryRoles(List(shape.triangles.size){AcousticBoundaryRole.RIGID_REFLECTOR})
            .withBoundaryMaterials(List(shape.triangles.size){material})
            .withFaceAssignments(List(shape.triangles.size){MeshFaceAssignment.EXCLUDED})
        v.addImportedAudienceMesh(mesh);capture(v,"Coincident face")
        assertEquals(a.phases[1000]!!.degrees!!,v.savedTraces.value.traces.last().phases[1000]!!.degrees!!,.0001)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(v.savedTraces.value.toJsonString(),fresh.savedTraces.value.toJsonString())
        capture(fresh,"Reopened")
        assertEquals(a.phases[1000]!!.degrees!!,fresh.savedTraces.value.traces.last().phases[1000]!!.degrees!!,.0001)
        fresh.setVenueBlockBoundaryRole(blockId,AcousticBoundaryRole.NONE);capture(fresh,"Face only")
        assertEquals(a.phases[1000]!!.degrees!!,fresh.savedTraces.value.traces.last().phases[1000]!!.degrees!!,.0001)
    }
    @Test fun missingMaterialPhaseRemainsUnavailableAfterReopen() {
        val v=scene();v.setSpeakerPosition(v.speakers.value.single().id,3f,1.2f,0f);v.moveListener(4f,0f)
        v.addVenueBlock("OBSTACLE",0f,0f)
        val id=v.venueGeometry.value.blocks.single().id
        v.setVenueBlockSize(id,2f,20f);v.setVenueBlockHeight(id,10f);v.setVenueBlockThickness(id,20f)
        v.setVenueBlockBoundaryRole(id,AcousticBoundaryRole.RIGID_REFLECTOR);v.setVenueBlockMaterial(id,BoundaryMaterial.CONCRETE)
        capture(v,"Missing reflection phase")
        assertNull(v.savedTraces.value.traces.last().phases[1000]!!.degrees)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(v.savedTraces.value.toJsonString(),fresh.savedTraces.value.toJsonString())
    }

    @Test fun groundPhaseChangesInterferenceAndSurvivesVariantsAndReopen() {
        val v=scene();v.setGroundReflectionEnabled(true)
        val phase=ReflectionPhase.create(listOf(0f to listOf(100 to 0.0,10000 to 0.0)))
        v.setGroundReflectionPhase(phase);capture(v,"Ground zero")
        val a=v.savedTraces.value.traces.last();assertNotNull(a.phases[1000]!!.degrees)
        assertTrue(v.captureDesignVariant("Ground baseline"))
        v.setGroundReflectionPhase(ReflectionPhase.create(listOf(0f to listOf(100 to 90.0,10000 to 90.0))))
        capture(v,"Ground 90")
        val b=v.savedTraces.value.traces.last();assertNotNull(b.phases[1000]!!.degrees)
        assertTrue(kotlin.math.abs(TraceComparisons.comparePhase(a,b).bands.single().deltaDegrees!!)>.001)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(v.groundReflectionPhase.value,fresh.groundReflectionPhase.value)
        assertEquals(v.savedTraces.value.toJsonString(),fresh.savedTraces.value.toJsonString())
        capture(fresh,"Reopened ground");assertEquals(b.phases[1000]!!.degrees!!,fresh.savedTraces.value.traces.last().phases[1000]!!.degrees!!,.0001)
        assertTrue(fresh.restoreDesignVariant(0));assertEquals(phase,fresh.groundReflectionPhase.value)
        fresh.setGroundReflectionPhase(null);assertTrue(fresh.undoScene());assertEquals(phase,fresh.groundReflectionPhase.value)
    }
    @Test fun groundPhaseImportValidationIsAtomicAndLegacySelectionIsEmpty() {
        val v=scene();v.setGroundReflectionPhase(ReflectionPhase.create(listOf(0f to listOf(100 to 0.0,10000 to 0.0))))
        val before=v.exportSceneJson(false)
        val invalid=org.json.JSONObject(before).put("groundReflectionPhase",org.json.JSONObject().put("convention","unknown"))
        assertFalse(v.importSceneJson(invalid.toString()));assertEquals(before,v.exportSceneJson(false))
        val legacy=org.json.JSONObject(before);legacy.remove("groundReflectionPhase")
        assertTrue(v.importSceneJson(legacy.toString()));assertNull(v.groundReflectionPhase.value)
    }

    @Test fun derivedPhaseAndMissingIntervalsSurviveProjectReopen() {
        val v=scene()
        v.setSignalType("SPECTRUM")
        capture(v,"Sparse complex")
        val a=v.savedTraces.value.traces.last()
        val derived=com.droidacoustic.pro.traces.PhaseDerivedTraces.derive(a)
        assertTrue(derived.points.size>1)
        assertTrue(derived.points.any {it.degrees!=null})
        assertTrue(derived.delays.all {it.milliseconds==null})
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(derived,com.droidacoustic.pro.traces.PhaseDerivedTraces.derive(fresh.savedTraces.value.traces.last()))
    }

    @Test fun denseListenerAndReferenceCapturesKeepDelayReferenceAfterReopen() {
        val v=scene()
        val frequencies=(900..1100 step 2).toList()
        val rows=frequencies.joinToString(",") {hz ->
            """{"frequency":$hz,"azimuths":[0],"elevations":[-90,90],"real":[[100,100]],"imaginary":[[0,0]]}"""
        }
        val response=ComplexSpeakerResponse.fromJsonString("""{"version":1,"convention":"${ComplexSpeakerResponse.CONVENTION}","referenceDrive":"1 V rms","provenance":"Dense test fixture","patterns":[$rows]}""")
        val original=v.getClfData("point_small")!!
        assertTrue(v.importClfJsonData(ClfParser.toJson(original.copy(complexResponse=response))))
        v.setDelay(v.speakers.value.single().id,120f)
        capture(v,"Dense listener")
        val a=v.savedTraces.value.traces.last()
        assertEquals(101,a.phases.size);assertNotNull(a.delayReferenceS)
        val derived=com.droidacoustic.pro.traces.PhaseDerivedTraces.derive(a)
        derived.delays.forEach {assertEquals(a.delayReferenceS!!*1000,it.milliseconds!!,.001)}
        v.addAlignmentReceiver("Dense FOH",10f,1.2f,0f)
        v.selectTraceReceivers(listOf(0));v.captureReferenceTraces("Dense reference");scheduler.runCurrent()
        val b=v.savedTraces.value.traces.last()
        assertEquals(a.delayReferenceS,b.delayReferenceS);assertEquals(a.phases,b.phases)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(v.savedTraces.value.toJsonString(),fresh.savedTraces.value.toJsonString())
        assertEquals(derived,com.droidacoustic.pro.traces.PhaseDerivedTraces.derive(fresh.savedTraces.value.traces.first()))
    }

    @Test fun adjustableReferencesPersistResetAndUndoWithoutChangingSpeakerDelay() {
        val v=scene();capture(v,"Reference edit")
        val original=v.savedTraces.value.traces.single()
        val speakers=v.speakers.value
        v.setTraceDelayReference(original.id,.015)
        v.setTraceRelativeDisplay(true)
        assertEquals(original.phases,v.savedTraces.value.traces.single().phases)
        assertEquals(speakers,v.speakers.value)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertTrue(fresh.savedTraces.value.relativeToReference)
        assertEquals(.015,fresh.savedTraces.value.traces.single().manualDelayReferenceS!!,0.0)
        fresh.setTraceDelayReference(original.id,null)
        assertNull(fresh.savedTraces.value.traces.single().manualDelayReferenceS)
        assertTrue(fresh.undoScene())
        assertEquals(.015,fresh.savedTraces.value.traces.single().manualDelayReferenceS!!,0.0)
        val before=fresh.exportSceneJson(false)
        val bad=org.json.JSONObject(before)
        bad.getJSONObject("savedTraces").getJSONArray("traces").getJSONObject(0).put("manualDelayReferenceS",3601)
        assertFalse(fresh.importSceneJson(bad.toString()));assertEquals(before,fresh.exportSceneJson(false))
    }

    @Test fun automaticReferenceMatchingAppliesAtomicallyAndSupportsUndoAndReopen() {
        val v=scene()
        val rows=(900..1100 step 5).joinToString(",") {hz ->
            """{"frequency":$hz,"azimuths":[0],"elevations":[-90,90],"real":[[100,100]],"imaginary":[[0,0]]}"""
        }
        val response=ComplexSpeakerResponse.fromJsonString("""{"version":1,"convention":"${ComplexSpeakerResponse.CONVENTION}","referenceDrive":"1 V rms","provenance":"Matching test","patterns":[$rows]}""")
        assertTrue(v.importClfJsonData(ClfParser.toJson(v.getClfData("point_small")!!.copy(complexResponse=response))))
        capture(v,"A");v.setDelay(v.speakers.value.single().id,3f);capture(v,"B")
        val original=v.savedTraces.value.traces
        v.setTraceDelayReference(original[1].id,original[0].effectiveDelayReferenceS)
        val traces=v.savedTraces.value.traces
        val selectedRange=com.droidacoustic.pro.traces.MatchingFrequencyRange(950,1050)
        v.setMatchingFrequencyRange(selectedRange)
        val preview=com.droidacoustic.pro.traces.TraceReferenceMatching.preview(traces[0],traces[1],selectedRange)
        assertTrue(preview.available)
        assertEquals(.003,preview.correctionS!!,.000001)
        val speakers=v.speakers.value
        assertTrue(v.applyTraceReferenceMatch(preview));assertEquals(speakers,v.speakers.value)
        assertTrue(v.savedTraces.value.relativeToReference)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(v.savedTraces.value.toJsonString(),fresh.savedTraces.value.toJsonString())
        assertTrue(v.undoScene());assertFalse(v.savedTraces.value.relativeToReference)
        v.setTraceDelayReference(traces[1].id,0.0)
        val before=v.exportSceneJson(false)
        assertFalse(v.applyTraceReferenceMatch(preview));assertEquals(before,v.exportSceneJson(false))
    }

    @Test fun matchingFrequencyRangePersistsResetsAndUndoesWithAtomicImportValidation() {
        val v=scene();capture(v,"Range settings")
        val range=com.droidacoustic.pro.traces.MatchingFrequencyRange(900,1100)
        v.setMatchingFrequencyRange(range)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(range,fresh.savedTraces.value.matchingFrequencyRange)
        fresh.setMatchingFrequencyRange(null);assertNull(fresh.savedTraces.value.matchingFrequencyRange)
        assertTrue(fresh.undoScene());assertEquals(range,fresh.savedTraces.value.matchingFrequencyRange)
        val before=fresh.exportSceneJson(false)
        val bad=org.json.JSONObject(before)
        bad.getJSONObject("savedTraces").getJSONObject("matchingFrequencyRange").put("maxHz",800)
        assertFalse(fresh.importSceneJson(bad.toString()));assertEquals(before,fresh.exportSceneJson(false))
    }

    @Test fun robustMatchingDiagnosticsAndAppliedReferenceSurviveProjectReopen() {
        val v=scene();capture(v,"Metadata")
        val meta=v.savedTraces.value.traces.single().metadata
        val hz=(900..1100 step 5).toList()
        fun trace(id:Int)=com.droidacoustic.pro.traces.SavedTrace(id,"Robust $id",meta,mapOf(1000 to 1.0),hz.associateWith {f ->
            if(f==1000) PhaseSample(null,"Missing test sample") else PhaseSample(PhaseResponse.wrapped(
                if(id==0) 0.0 else 360*f*.003+37+(if(f==950 || f==1050) 100 else 0)))
        },delayReferenceS=0.0)
        val library=com.droidacoustic.pro.traces.SavedTraces.empty().captureBatch(listOf(trace(0),trace(1)))
        val project=org.json.JSONObject(v.exportSceneJson(true)).put("savedTraces",org.json.JSONObject(library.toJsonString()))
        assertTrue(v.importSceneJson(project.toString()))
        val a=v.savedTraces.value.traces
        val preview=com.droidacoustic.pro.traces.TraceReferenceMatching.preview(a[0],a[1])
        assertTrue(preview.available);assertEquals(listOf(950,1050),preview.rejectedFrequencies)
        assertEquals(.003,preview.proposedReferenceS!!,.000001)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        val b=fresh.savedTraces.value.traces
        val reopened=com.droidacoustic.pro.traces.TraceReferenceMatching.preview(b[0],b[1])
        assertEquals(preview.confidenceScore,reopened.confidenceScore)
        assertEquals(preview.rejectedFrequencies,reopened.rejectedFrequencies)
        assertEquals(preview.fitSamples,reopened.fitSamples)
        assertTrue(fresh.applyTraceReferenceMatch(reopened))
        val saved=vm();assertTrue(saved.importSceneJson(fresh.exportSceneJson(true)))
        assertEquals(.003,saved.savedTraces.value.traces[1].manualDelayReferenceS!!,.000001)
        assertEquals(a[1].phases,saved.savedTraces.value.traces[1].phases)
    }

    @Test fun fitSampleSelectionPersistsAndInvalidImportIsAtomic() {
        val v=scene();capture(v,"A");capture(v,"B")
        val selection=com.droidacoustic.pro.traces.MatchSampleSelection(0,1,1000)
        v.inspectMatchingSample(selection)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(selection,fresh.savedTraces.value.inspectedSample)
        val before=fresh.exportSceneJson(false)
        val bad=org.json.JSONObject(before)
        bad.getJSONObject("savedTraces").getJSONObject("inspectedSample").put("hz",1001)
        assertFalse(fresh.importSceneJson(bad.toString()));assertEquals(before,fresh.exportSceneJson(false))
        fresh.removeSavedTrace(1);assertNull(fresh.savedTraces.value.inspectedSample)
        assertTrue(fresh.undoScene());assertEquals(selection,fresh.savedTraces.value.inspectedSample)
    }

    @Test fun manualExclusionsPersistUndoAndRejectInvalidImportsAtomically() {
        val v=scene();capture(v,"A");capture(v,"B")
        val sample=com.droidacoustic.pro.traces.MatchSampleSelection(0,1,1000)
        v.excludeMatchingSample(sample,true)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(setOf(sample),fresh.savedTraces.value.manualExclusions)
        val before=fresh.exportSceneJson(false)
        val bad=org.json.JSONObject(before)
        bad.getJSONObject("savedTraces").getJSONArray("manualExclusions").getJSONObject(0).put("hz",1001)
        assertFalse(fresh.importSceneJson(bad.toString()));assertEquals(before,fresh.exportSceneJson(false))
        fresh.excludeMatchingSample(sample,false);assertTrue(fresh.savedTraces.value.manualExclusions.isEmpty())
        assertTrue(fresh.undoScene());assertEquals(setOf(sample),fresh.savedTraces.value.manualExclusions)
    }

    @Test fun rangeExclusionsPersistUndoAndRejectInvalidImportsAtomically() {
        val v=scene();capture(v,"A");capture(v,"B")
        val range=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,900,1100)
        v.excludeMatchingRange(range,true)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(setOf(range),fresh.savedTraces.value.rangeExclusions)
        val before=fresh.exportSceneJson(false)
        val bad=org.json.JSONObject(before)
        bad.getJSONObject("savedTraces").getJSONArray("rangeExclusions").getJSONObject(0).put("maxHz",800)
        assertFalse(fresh.importSceneJson(bad.toString()));assertEquals(before,fresh.exportSceneJson(false))
        fresh.excludeMatchingRange(range,false);assertTrue(fresh.savedTraces.value.rangeExclusions.isEmpty())
        assertTrue(fresh.undoScene());assertEquals(setOf(range),fresh.savedTraces.value.rangeExclusions)
        fresh.removeSavedTrace(1);assertTrue(fresh.savedTraces.value.rangeExclusions.isEmpty())
    }

    @Test fun editingExclusionIsOneUndoStepAndSurvivesReopen() {
        val v=scene();capture(v,"A");capture(v,"B")
        val old=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,900,1100)
        val next=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,950,1050)
        v.excludeMatchingRange(old,true)
        assertTrue(v.editMatchingRange(old,next))
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(setOf(next),fresh.savedTraces.value.rangeExclusions)
        assertFalse(v.editMatchingRange(old,next))
        assertTrue(v.undoScene());assertEquals(setOf(old),v.savedTraces.value.rangeExclusions)
    }

    @Test fun movedRangePreservesWidthAcrossReopenAndUndo() {
        val v=scene();capture(v,"A");capture(v,"B")
        val old=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,940,960)
        val samples=listOf(900,1100).map {com.droidacoustic.pro.traces.MatchFitSample(it,null,null,null,com.droidacoustic.pro.traces.MatchSampleStatus.MISSING)}
        val geometry=com.droidacoustic.pro.traces.MatchPlotGeometry(samples,false,220.0,100.0,10.0)
        val bounds=geometry.move(com.droidacoustic.pro.traces.MatchingFrequencyRange(940,960),60.0,100.0)!!
        val moved=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,bounds.minHz,bounds.maxHz)
        v.excludeMatchingRange(old,true);assertTrue(v.editMatchingRange(old,moved))
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(setOf(moved),fresh.savedTraces.value.rangeExclusions)
        assertEquals(20,moved.maxHz-moved.minHz)
        assertTrue(v.undoScene());assertEquals(setOf(old),v.savedTraces.value.rangeExclusions)
    }

    @Test fun exclusionUndoRedoPersistsAcrossProjectReopen() {
        val v=scene();capture(v,"A");capture(v,"B")
        val range=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,940,960)
        val moved=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,970,990)
        v.excludeMatchingRange(range,true);assertTrue(v.editMatchingRange(range,moved))
        v.undoMatchingExclusion(0,1)
        assertEquals(setOf(range),v.savedTraces.value.rangeExclusions)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertTrue(fresh.savedTraces.value.canRedoExclusion(0,1))
        fresh.undoMatchingExclusion(0,1,true)
        assertEquals(setOf(moved),fresh.savedTraces.value.rangeExclusions)
        assertTrue(fresh.undoScene());assertEquals(setOf(range),fresh.savedTraces.value.rangeExclusions)
        val before=fresh.exportSceneJson(false)
        val bad=org.json.JSONObject(before)
        bad.getJSONObject("savedTraces").getJSONArray("exclusionRedo").getJSONObject(0).put("a",99)
        assertFalse(fresh.importSceneJson(bad.toString()));assertEquals(before,fresh.exportSceneJson(false))
    }

    @Test fun exclusionDescriptionsFollowUndoRedoAndProjectReopen() {
        val v=scene();capture(v,"A");capture(v,"B")
        val range=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,940,960)
        val moved=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,970,990)
        v.excludeMatchingRange(range,true);assertTrue(v.editMatchingRange(range,moved))
        assertEquals("Move range 940–960 Hz → 970–990 Hz",v.savedTraces.value.undoExclusionDescription(0,1))
        v.undoMatchingExclusion(0,1)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals("Exclude range 940–960 Hz",fresh.savedTraces.value.undoExclusionDescription(0,1))
        assertEquals("Move range 940–960 Hz → 970–990 Hz",fresh.savedTraces.value.redoExclusionDescription(0,1))
        fresh.undoMatchingExclusion(0,1,true)
        assertEquals("Move range 940–960 Hz → 970–990 Hz",fresh.savedTraces.value.undoExclusionDescription(0,1))
        assertNull(fresh.savedTraces.value.redoExclusionDescription(0,1))
    }

    @Test fun historyNavigationReopensAndIsOneSceneUndoStep() {
        val v=scene();capture(v,"A");capture(v,"B")
        val range=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,940,960)
        val moved=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,970,990)
        v.excludeMatchingRange(range,true);assertTrue(v.editMatchingRange(range,moved))
        val view=v.savedTraces.value.exclusionHistory(0,1)
        assertTrue(v.navigateMatchingExclusionHistory(view,0))
        assertTrue(v.savedTraces.value.rangeExclusions.isEmpty())
        assertFalse(v.navigateMatchingExclusionHistory(view,1))
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(0,fresh.savedTraces.value.exclusionHistory(0,1).currentPosition)
        assertTrue(fresh.navigateMatchingExclusionHistory(fresh.savedTraces.value.exclusionHistory(0,1),2))
        assertEquals(setOf(moved),fresh.savedTraces.value.rangeExclusions)
        assertTrue(v.undoScene());assertEquals(setOf(moved),v.savedTraces.value.rangeExclusions)
    }

    @Test fun historyPreviewDoesNotMutateUntilAppliedAndReopensWithUndo() {
        val v=scene();capture(v,"A");capture(v,"B")
        val range=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,940,960)
        val moved=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,970,990)
        v.excludeMatchingRange(range,true);assertTrue(v.editMatchingRange(range,moved))
        val before=v.exportSceneJson(false)
        val preview=com.droidacoustic.pro.traces.ExclusionHistoryPreviews.preview(v.savedTraces.value,v.savedTraces.value.exclusionHistory(0,1),0)
        assertEquals(before,v.exportSceneJson(false))
        assertTrue(v.applyExclusionHistoryPreview(preview));assertTrue(v.savedTraces.value.rangeExclusions.isEmpty())
        assertFalse(v.applyExclusionHistoryPreview(preview))
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(0,fresh.savedTraces.value.exclusionHistory(0,1).currentPosition)
        assertTrue(fresh.savedTraces.value.canRedoExclusion(0,1))
        assertTrue(v.undoScene());assertEquals(setOf(moved),v.savedTraces.value.rangeExclusions)
    }

    @Test fun overlayInspectionPreferencesPersistAndPreviewStillApplies() {
        val v=scene();capture(v,"A");capture(v,"B")
        val range=com.droidacoustic.pro.traces.MatchRangeExclusion(0,1,900,1100)
        v.excludeMatchingRange(range,true)
        val preview=com.droidacoustic.pro.traces.ExclusionHistoryPreviews.preview(v.savedTraces.value,v.savedTraces.value.exclusionHistory(0,1),0)
        val selection=com.droidacoustic.pro.traces.MatchSampleSelection(0,1,1000)
        v.setOverlayInspection(selection,true)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(selection,fresh.savedTraces.value.inspectedSample);assertTrue(fresh.savedTraces.value.overlayChangedOnly)
        assertTrue(fresh.applyExclusionHistoryPreview(preview))
        assertTrue(fresh.savedTraces.value.rangeExclusions.isEmpty())
        assertEquals(selection,fresh.savedTraces.value.inspectedSample);assertTrue(fresh.savedTraces.value.overlayChangedOnly)
        assertTrue(fresh.undoScene());assertEquals(setOf(range),fresh.savedTraces.value.rangeExclusions)
    }

    @Test fun complexBandAndFiltersPersistAndReproduceProductionCoverageAndPhase() {
        val v=scene();val id=v.speakers.value.single().id
        val old=v.getClfData("point_small")!!
        val rows=listOf(800,1000,1250).joinToString {hz -> """{"frequency":$hz,"azimuths":[0],"elevations":[-90,90],"real":[[100,100]],"imaginary":[[0,0]]}"""}
        val complex=ComplexSpeakerResponse.fromJsonString("""{"version":2,"convention":"${ComplexSpeakerResponse.CONVENTION}","referenceDrive":"1 V rms","provenance":"band fixture","frequencyInterpolation":"cartesian-linear-hz","maximumInterpolationGapHz":300,"patterns":[$rows]}""")
        assertTrue(v.importClfJsonData(ClfParser.toJson(old.copy(complexResponse=complex))))
        val before=v.predictBandDb(v.speakers.value,10f,1.2f,0f,1000)
        capture(v,"Unfiltered complex")
        val unfiltered=v.savedTraces.value.traces.last().phases[1000]!!.degrees!!
        val chain=DspFilterChain(listOf(DspFilter(FilterKind.LOW_PASS,1000.0)))
        v.setFilters(id,chain)
        val after=v.predictBandDb(v.speakers.value,10f,1.2f,0f,1000)
        assertTrue(after<before-2.8f && after>before-3.3f)
        capture(v,"Filtered complex")
        assertEquals(90.0,PhaseResponse.wrapped(v.savedTraces.value.traces.last().phases[1000]!!.degrees!!-unfiltered),.001)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(chain,fresh.dspMap.value.getValue(id).filters)
        assertEquals(after,fresh.predictBandDb(fresh.speakers.value,10f,1.2f,0f,1000),.001f)
        assertEquals(complex.toJsonString(),fresh.getClfData("point_small")!!.complexResponse!!.toJsonString())
        v.setFilters(id,DspFilterChain());assertTrue(v.undoScene());assertEquals(chain,v.dspMap.value.getValue(id).filters)
        assertTrue(v.redoScene());assertTrue(v.dspMap.value.getValue(id).filters.isEmpty)
    }
    @Test fun malformedFilterImportIsAtomicAndLegacyProjectsHaveNoFilters() {
        val v=scene();val id=v.speakers.value.single().id
        v.setFilters(id,DspFilterChain(listOf(DspFilter(FilterKind.HIGH_PASS,100.0))))
        val original=v.exportSceneJson(true)
        val bad=org.json.JSONObject(original)
        bad.getJSONArray("dsp").getJSONObject(0).getJSONObject("filters").getJSONArray("filters").getJSONObject(0).put("q",0)
        assertFalse(v.importSceneJson(bad.toString()));assertEquals(original,v.exportSceneJson(true))
        val legacy=org.json.JSONObject(original);legacy.put("version",22)
        legacy.getJSONArray("dsp").getJSONObject(0).remove("filters")
        val fresh=vm();assertTrue(fresh.importSceneJson(legacy.toString()))
        assertTrue(fresh.dspMap.value.getValue(id).filters.isEmpty)
    }

    private fun bandComplex(v:SceneViewModel, amplitude:Int=100) {
        val rows=listOf(800,1000,1250).joinToString {hz -> """{"frequency":$hz,"azimuths":[0],"elevations":[-90,90],"real":[[$amplitude,$amplitude]],"imaginary":[[0,0]]}"""}
        val r=ComplexSpeakerResponse.fromJsonString("""{"version":2,"convention":"${ComplexSpeakerResponse.CONVENTION}","referenceDrive":"1 V rms","provenance":"mixed diffuse fixture","frequencyInterpolation":"cartesian-linear-hz","maximumInterpolationGapHz":300,"patterns":[$rows]}""")
        assertTrue(v.importClfJsonData(ClfParser.toJson(v.getClfData("point_small")!!.copy(complexResponse=r))))
    }
    @Test fun mixedSourceEnergyAtOneKilohertzSurvivesReopenAndCoherentModeStaysUnavailable() {
        val v=scene();bandComplex(v)
        val first=v.speakers.value.single()
        v.setSpeakerPreset("point_large");v.addSpeakerSource();v.addSpeaker(3f,0f)
        // Use a different preset so the second source cannot inherit the first model's complex data.
        assertNotEquals(first.presetId,v.speakers.value.last().presetId)
        v.setSignalInterferenceEnabled(false)
        val expected=v.speakers.value.sumOf {sp ->Math.pow(10.0,v.predictBandDb(listOf(sp),10f,1.2f,0f,1000)/10.0)}
        val level=v.predictBandDb(v.speakers.value,10f,1.2f,0f,1000)
        assertEquals(10*kotlin.math.log10(expected),level.toDouble(),.001)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertFalse(fresh.signalInterferenceEnabled.value)
        assertEquals(level,fresh.predictBandDb(fresh.speakers.value,10f,1.2f,0f,1000),.001f)
        fresh.setSignalInterferenceEnabled(true)
        assertTrue(fresh.predictBandDb(fresh.speakers.value,10f,1.2f,0f,1000).isNaN())
        assertTrue(fresh.undoScene());assertFalse(fresh.signalInterferenceEnabled.value)
    }
    @Test fun complexDiffuseMaterialAndFilterPersistWithIdenticalProductionResults() {
        val v=scene();bandComplex(v)
        val id=v.speakers.value.single().id
        v.setSpeakerPosition(id,0f,3f,0f)
        v.setStageBoundaryRole(AcousticBoundaryRole.RIGID_REFLECTOR)
        val material=BoundaryMaterial.custom("Diffuse test",listOf(800 to .2f,1250 to .2f))
            .withScattering(MaterialScattering.create(listOf(0f to listOf(800 to 1f,1250 to 1f))))
        v.setStageMaterial(material)
        v.setFilters(id,DspFilterChain(listOf(DspFilter(FilterKind.LOW_PASS,1000.0))))
        val result=v.predictBandDb(v.speakers.value,5f,2f,0f,1000)
        assertTrue(result.isFinite())
        v.setStageBoundaryRole(AcousticBoundaryRole.NONE)
        val direct=v.predictBandDb(v.speakers.value,5f,2f,0f,1000)
        assertTrue(result>direct)
        assertTrue(v.undoScene())
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(result,fresh.predictBandDb(fresh.speakers.value,5f,2f,0f,1000),.001f)
        assertEquals(v.dspMap.value,fresh.dspMap.value)
        assertEquals(v.exportSceneJson(true),fresh.exportSceneJson(true))
    }

    @Test fun coherentScatteringInterferencePersistsAndPolarityUndoRestoresCoverage() {
        val v=scene();bandComplex(v)
        val first=v.speakers.value.single().id
        v.addSpeakerSource();v.addSpeaker(0f,0f)
        v.speakers.value.forEach {v.setSpeakerPosition(it.id,0f,3f,0f)}
        v.setSignalInterferenceEnabled(true)
        v.setStageBoundaryRole(AcousticBoundaryRole.RIGID_REFLECTOR)
        v.setStageMaterial(BoundaryMaterial.custom("Scattering",listOf(800 to .2f,1250 to .2f))
            .withScattering(MaterialScattering.create(listOf(0f to listOf(800 to 1f,1250 to 1f)))))
        fun level(m:SceneViewModel)=m.predictBandDb(m.speakers.value,5f,2f,0f,1000)
        val together=level(v);assertTrue(together>0f)
        v.setPolarity(first,true)
        assertEquals(0f,level(v),.001f)
        val json=v.exportSceneJson(true);val fresh=vm();assertTrue(fresh.importSceneJson(json))
        assertEquals(level(v),level(fresh),.001f)
        assertEquals(json,fresh.exportSceneJson(true))
        assertTrue(v.undoScene());assertEquals(together,level(v),.001f)
        assertTrue(v.redoScene());assertEquals(level(fresh),level(v),.001f)
    }

    @Test fun measuredThirtyTwoEmitterScatteringPersistsAndPolarityUndoRestoresResult() {
        val v=scene();bandComplex(v)
        v.addSpeakerSource();v.addSpeaker(0f,0f)
        for(s in v.speakers.value) {
            v.setSpeakerPosition(s.id,0f,3f,0f)
            v.setSpeakerArrayElements(s.id,16);v.setSpeakerArraySpacing(s.id,.05f)
        }
        v.setSignalInterferenceEnabled(true)
        v.setStageBoundaryRole(AcousticBoundaryRole.RIGID_REFLECTOR)
        v.setStageMaterial(BoundaryMaterial.custom("Array scattering",listOf(800 to .2f,1250 to .2f))
            .withScattering(MaterialScattering.create(listOf(0f to listOf(800 to 1f,1250 to 1f)))))
        fun level(m:SceneViewModel)=m.predictBandDb(m.speakers.value,5f,2f,0f,1000)
        val expected=level(v);assertTrue(expected.isFinite() && expected>0)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        assertEquals(32,fresh.speakers.value.sumOf {it.arrayElements})
        assertEquals(expected,level(fresh),.001f)
        fresh.setPolarity(fresh.speakers.value.first().id,true)
        assertEquals(0f,level(fresh),.001f)
        assertTrue(fresh.undoScene());assertEquals(expected,level(fresh),.001f)
        assertTrue(fresh.redoScene());assertEquals(0f,level(fresh),.001f)
        val reopened=vm();assertTrue(reopened.importSceneJson(fresh.exportSceneJson(true)))
        assertEquals(0f,level(reopened),.001f)
    }

    @Category(LargeSceneValidation::class)
    @Test fun wholeMeasuredArraySceneCompletesAndReopensWithIdenticalCoverage() {
        val v=scene();bandComplex(v,10000);v.addSpeakerSource();v.addSpeaker(0f,0f)
        for(sp in v.speakers.value) {
            v.setSpeakerPosition(sp.id,0f,3f,0f);v.setSpeakerArrayElements(sp.id,16);v.setSpeakerArraySpacing(sp.id,.05f)
        }
        v.setStageCenter(0f,0f);v.setStageSize(2f,2f);v.setStageHeight(.5f)
        v.setSignalInterferenceEnabled(true);v.setStageBoundaryRole(AcousticBoundaryRole.RIGID_REFLECTOR)
        v.setStageMaterial(BoundaryMaterial.custom("Whole-scene fixture",listOf(800 to .2f,1250 to .2f))
            .withScattering(MaterialScattering.create(listOf(0f to listOf(800 to 1f,1250 to 1f)))))
        fun calculate(m:SceneViewModel)=m.capturePredictionRequest(PredictionTicket(0,0,PredictionChannel.COVERAGE),
            m.speakers.value,emptyList(),coveragePlan=m.captureCoveragePlan()).calculate()
        val start=System.nanoTime();val result=calculate(v)
        println("32-emitter whole-scene coverage: "+(System.nanoTime()-start)/1e6+" ms")
        val points=result.geometry!!.receivers();assertTrue(points.size>8)
        assertNotNull(result.statistics)
        val fresh=vm();assertTrue(fresh.importSceneJson(v.exportSceneJson(true)))
        val reopened=calculate(fresh)
        for(i in points.indices) {assertTrue(result.levelAt(i).isFinite());assertEquals(result.levelAt(i),reopened.levelAt(i),.001f)}
        assertEquals(v.exportSceneJson(true),fresh.exportSceneJson(true))
        fresh.setPolarity(fresh.speakers.value.first().id, true)
        val cancelled=calculate(fresh)
        for(i in points.indices) assertEquals(0f,cancelled.levelAt(i),.001f)
        val restoredNull=vm();assertTrue(restoredNull.importSceneJson(fresh.exportSceneJson(true)))
        val reopenedNull=calculate(restoredNull)
        for(i in points.indices) assertEquals(cancelled.levelAt(i),reopenedNull.levelAt(i),.001f)
    }

    private fun largeVenueMeasuredFixture():SceneViewModel {
        val v=scene();v.setVenueSize(60f,40f);v.setAnalysisProfile("Fast")
        val az=listOf(0,8,10,12,14,18,45,90,180,270,342,348,358)
        val el=listOf(-90,-35,-12,-5,0,7,24,90)
        val patterns=listOf(875,1000,1125,3500,4000,4500,7000,8000,9000).joinToString {hz ->
            fun rows(imaginary:Boolean)=az.joinToString(prefix="[",postfix="]") {a ->
                el.joinToString(prefix="[",postfix="]") {e ->
                    val angle=minOf(a,360-a).toDouble()
                    val amplitude=10000*(.08+.7*kotlin.math.exp(-Math.pow(angle/40,2.0)-Math.pow(e/25.0,2.0))+(if(a==12) .4 else 0.0))*(1+.12*kotlin.math.sin(hz/900.0))
                    val phase=.4*kotlin.math.sin(a*Math.PI/180)+.003*e+.2*kotlin.math.cos(hz/1100.0)
                    (amplitude*if(imaginary) kotlin.math.sin(phase) else kotlin.math.cos(phase)).toString()
                }
            }
            """{"frequency":$hz,"azimuths":$az,"elevations":$el,"real":${rows(false)},"imaginary":${rows(true)}}"""
        }
        val response=ComplexSpeakerResponse.fromJsonString("""{"version":2,"convention":"${ComplexSpeakerResponse.CONVENTION}","referenceDrive":"1 V rms","provenance":"Synthetic nonuniform high-frequency benchmark","frequencyInterpolation":"cartesian-linear-hz","maximumInterpolationGapHz":1000,"patterns":[$patterns]}""")
        assertTrue(v.importClfJsonData(ClfParser.toJson(v.getClfData("point_small")!!.copy(complexResponse=response))))
        v.addSpeakerSource();v.addSpeaker(0f,0f)
        v.speakers.value.forEachIndexed {i,sp ->
            v.setSpeakerPosition(sp.id,-20f,6f,if(i==0) -6f else 6f)
            v.setSpeakerArrayElements(sp.id,16);v.setSpeakerArraySpacing(sp.id,.2f)
        }
        v.setSignalInterferenceEnabled(true)
        return v
    }
    @Category(LargeSceneValidation::class)
    @Test fun largeVenueNonuniformMeasuredArraysAtFourAndEightKilohertzReopenExactly() {
        val v=largeVenueMeasuredFixture()
        fun calculate(m:SceneViewModel)=m.capturePredictionRequest(PredictionTicket(0,0,PredictionChannel.COVERAGE),
            m.speakers.value,emptyList(),coveragePlan=m.captureCoveragePlan()).calculate()
        for((hz,count) in listOf(4000 to 32,8000 to 32,8000 to 64)) {
            if(count==64) {
                repeat(2) {v.addSpeakerSource();v.addSpeaker(0f,0f)}
                v.speakers.value.drop(2).forEachIndexed {i,sp ->
                    v.setSpeakerPosition(sp.id,-20f,6f,if(i==0) -12f else 12f)
                    v.setSpeakerArrayElements(sp.id,16);v.setSpeakerArraySpacing(sp.id,.2f)
                }
            }
            assertEquals(count,v.speakers.value.sumOf {it.arrayElements})
            v.setBandHz(hz)
            val start=System.nanoTime();val result=calculate(v)
            val points=result.geometry!!.receivers();assertTrue(points.size>=200)
            println("Large venue 60x40m, $count emitters, $hz Hz, ${points.size} receivers: ${(System.nanoTime()-start)/1e6} ms")
            assertNotNull(result.statistics)
            assertEquals(100.0,result.statistics!!.overall.validAreaPercent,1e-9)
            println("Large venue $hz Hz / $count emitters area mean: ${result.statistics!!.overall.meanDb} dB")
            val json=v.exportSceneJson(true);val fresh=vm();assertTrue(fresh.importSceneJson(json))
            val reopened=calculate(fresh)
            assertEquals(points.size,reopened.geometry!!.receivers().size)
            assertEquals(result.statistics!!.overall,reopened.statistics!!.overall)
            assertEquals(json,fresh.exportSceneJson(true))
            for(i in points.indices) {assertTrue(result.levelAt(i).isFinite());assertEquals(result.levelAt(i),reopened.levelAt(i),.001f)}
            System.getenv("ACOUSTIC_BENCHMARK_EXPORT_DIR")?.let {dir ->
                java.io.File(dir).mkdirs();java.io.File(dir,"large-venue-$hz-$count.json").writeText(json)
            }
        }
    }

    @Category(LargeSceneValidation::class)
    @Test fun fullVenueThirdOctaveDiffuseCoverageReopensWithAllReceiversAndStatistics() {
        val frequencies=System.getenv("ACOUSTIC_DIFFUSE_BENCH_HZ")?.split(',')?.map {it.trim().toInt()} ?: listOf(1000)
        for(hz in frequencies) {
        val v=largeVenueMeasuredFixture();v.setBandHz(hz)
        repeat(2) {v.addSpeakerSource();v.addSpeaker(0f,0f)}
        v.speakers.value.drop(2).forEachIndexed {i,sp ->
            v.setSpeakerPosition(sp.id,-20f,6f,if(i==0) -12f else 12f)
            v.setSpeakerArrayElements(sp.id,16);v.setSpeakerArraySpacing(sp.id,.2f)
        }
        assertEquals(64,v.speakers.value.sumOf {it.arrayElements})
        v.setStageCenter(-10f,0f);v.setStageSize(2f,2f);v.setStageHeight(.5f)
        v.setStageBoundaryRole(AcousticBoundaryRole.RIGID_REFLECTOR)
        v.setStageMaterial(BoundaryMaterial.custom("Full-venue diffuse",listOf(875 to .2f,9000 to .2f))
            .withScattering(MaterialScattering.create(listOf(0f to listOf(875 to 1f,9000 to 1f)))))
        val json=v.exportSceneJson(true)
        System.getenv("ACOUSTIC_BENCHMARK_EXPORT_DIR")?.let {dir ->
            java.io.File(dir).mkdirs();java.io.File(dir,"full-venue-diffuse-$hz.json").writeText(json)
            if(hz==1000) java.io.File(dir,"full-venue-diffuse.json").writeText(json)
        }
        fun calculate(m:SceneViewModel)=m.capturePredictionRequest(PredictionTicket(0,0,PredictionChannel.COVERAGE),
            m.speakers.value,emptyList(),coveragePlan=m.captureCoveragePlan()).calculate()
        val start=System.nanoTime();val result=calculate(v)
        println("Full-venue diffuse, 64 emitters, $hz Hz, ${result.geometry!!.receivers().size} receivers: ${(System.nanoTime()-start)/1e6} ms")
        assertTrue(result.geometry!!.receivers().size>=200)
        assertEquals(100.0,result.statistics!!.overall.validAreaPercent,1e-9)
        val fresh=vm();assertTrue(fresh.importSceneJson(json));val reopened=calculate(fresh)
        assertEquals(json,fresh.exportSceneJson(true));assertEquals(result.statistics!!.overall,reopened.statistics!!.overall)
        for(i in result.geometry!!.receivers().indices) assertEquals(result.levelAt(i),reopened.levelAt(i),.001f)
        println("Full-venue diffuse $hz Hz area mean: ${result.statistics!!.overall.meanDb} dB")
        if(hz==1000) assertEquals(56.71420139630636,result.statistics!!.overall.meanDb!!,1e-5)
        v.setStageBoundaryRole(AcousticBoundaryRole.NONE)
        val direct=calculate(v)
        assertTrue("diffuse energy must contribute",result.geometry!!.receivers().indices.any {result.levelAt(it)>direct.levelAt(it)+.01f})
        }
    }

}
