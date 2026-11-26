package com.droidacoustic.pro.ui.shell

import android.content.Context
import com.droidacoustic.pro.geometry.SpeakerGizmoTransform
import com.droidacoustic.pro.ui.SceneTransformTarget
import com.droidacoustic.pro.ui.SceneTransformPreview
import com.droidacoustic.pro.scene.sharedSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.droidacoustic.pro.geometry.ReceiverLabelRect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidacoustic.pro.MainActivity
import com.droidacoustic.pro.persistence.ProjectArchive
import com.droidacoustic.pro.persistence.ProjectStore
import com.droidacoustic.pro.scene.LocalProjectStore
import java.io.File
import com.droidacoustic.pro.scene.SceneViewModel
import com.droidacoustic.pro.prediction.CoveragePresentation
import com.droidacoustic.pro.ui.ContoursGlb
import com.droidacoustic.pro.ui.FilamentSurface
import com.droidacoustic.pro.ui.PickTarget
import com.droidacoustic.pro.ui.ViewPreset
import com.droidacoustic.pro.ui.components.StatusChip
import com.droidacoustic.pro.ui.theme.Instrument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// =============================================================================
// App shell
// =============================================================================
//
// The organising idea: the 3D view is the document, not a preview. Everything
// else is instrumentation around it.
//
// What this replaces: six destination tabs (Venue / Speakers / SPL / Results /
// Report / Settings) that were really six modes of one document, each opening a
// long scrolling column of every control for that tab regardless of what you
// were actually working on.
//
//   ┌────────────────────────────────────────────────────────┐
//   │ top bar — document, dirty state, undo/redo, settings   │
//   ├──────┬──────────────────────────────────┬──────────────┤
//   │ tool │   viewport (+ floating controls) │  inspector   │
//   │ rail ├──────────────────────────────────┤  (selection) │
//   │      │   analysis strip                 │              │
//   └──────┴──────────────────────────────────┴──────────────┘
// =============================================================================

/** Active viewport editing tool. */
enum class Tool(val label: String, val icon: ImageVector, val hint: String) {
    SELECT("Select", Icons.Default.CenterFocusStrong, "Tap an object to select it"),
    FACES("Faces", Icons.Default.SelectAll, "Tap an imported face; choose Single, Add/remove, Flat patch or Group in Face assignments"),
    SPEAKER("Speaker", Icons.Default.Speaker, "Tap the floor to place a speaker"),
    ZONE("Zone", Icons.Default.Groups, "Tap to add zone corners, then close the shape"),
    BLOCK("Block", Icons.Default.Architecture, "Tap the floor to place a block"),
    LISTENER("Listener", Icons.Default.Casino, "Tap the floor to move the listener")
}

private val BANDS = listOf(63, 125, 250, 500, 1000, 2000, 4000, 8000)

private const val PREFS_NAME = "droidacoustic_project"


@Composable
fun AppShell(activity: MainActivity) {

    val vm: SceneViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // ── UI-only state ────────────────────────────────────────────────────────
    var tool by remember { mutableStateOf(Tool.SELECT) }
    var selection by remember { mutableStateOf<Selection>(Selection.None) }
    var viewPreset by remember { mutableStateOf(ViewPreset.PERSPECTIVE) }
    var frameToken by remember { mutableIntStateOf(0) }
    var viewportOrigin by remember {mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)}
    var receiverPanelRect by remember {mutableStateOf<ReceiverLabelRect?>(null)}
    var analysisExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var blockType by remember { mutableStateOf("OBSTACLE") }

    // ── Scene state ──────────────────────────────────────────────────────────
    val speakers by vm.speakers.collectAsState()
    val zones by vm.audienceAreas.collectAsState()
    val selectedMeshFaces by vm.importedFaceSelection.collectAsState()
    val areaDraft by vm.areaDraft.collectAsState()
    val cutoutTarget by vm.cutoutTarget.collectAsState()
    val elevationTarget by vm.elevationTarget.collectAsState()
    val audienceGeometryError by vm.audienceGeometryError.collectAsState()
    LaunchedEffect(cutoutTarget,elevationTarget) { if (cutoutTarget != null || elevationTarget != null) tool = Tool.ZONE }
    val audience by vm.audience.collectAsState()
    val venue by vm.venueGeometry.collectAsState()
    val heatmap by vm.heatmap.collectAsState()
    val coverageGeometry by vm.coverageGeometry.collectAsState()
    val coverageStatistics by vm.coverageStatistics.collectAsState()
    val listener by vm.listener.collectAsState()
    val receiverAngular=rememberReceiverAngularOverlay(vm)
    val receiverAngularEnabled by vm.receiverAngularOverlayEnabled.collectAsState()
    val receiverAngularSelection by vm.receiverAngularSelection.collectAsState()
    val receiverFocus by vm.receiverCameraFocus.collectAsState()
    val receiverFrameToken by vm.receiverFrameToken.collectAsState()
    val receiverReferences by vm.alignmentReferences.collectAsState()
    val receiverFrameTarget=remember(receiverFocus,listener,receiverReferences) {vm.receiverFrameTarget()}
    val receiverFrameObstructions=receiverPanelRect?.let {listOf(it.copy(x=it.x-viewportOrigin.x,y=it.y-viewportOrigin.y))}.orEmpty()
    val effectiveViewPreset=receiverFocus?.view?.name?.let {ViewPreset.valueOf(it)} ?: viewPreset
    LaunchedEffect(receiverFocus?.view) {receiverFocus?.view?.let {viewPreset=ViewPreset.valueOf(it.name)}}
    val aimRays by vm.aimRaysEnabled.collectAsState()
    val clfRegistryForRays by vm.clfRegistry.collectAsState()
    val dspMap by vm.dspMap.collectAsState()
    val combined by vm.combinedSplDb.collectAsState()
    val band by vm.selectedBandHz.collectAsState()
    // Measured coverage edges are per band, so they follow the band selector.
    // Skipped entirely when the overlay is off - it is the only consumer.
    val coverageEdges = remember(speakers, band, clfRegistryForRays, aimRays) {
        if (!aimRays) emptyMap() else speakers.associate { it.id to vm.coverageEdgesFor(it) }
    }
    val modelPackages by vm.speakerModelPackages.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val temperatureC by vm.temperatureC.collectAsState()
    val humidityPct by vm.humidityPct.collectAsState()
    val signalType by vm.signalType.collectAsState()
    val broadbandDrive by vm.broadbandDrive.collectAsState()
    val bandwidthOct by vm.signalBandwidthOct.collectAsState()
    val interference by vm.signalInterferenceEnabled.collectAsState()
    val autoCalc by vm.signalAutoCalculate.collectAsState()

    LaunchedEffect(Unit) { vm.initEngine(activity.assets) }

    // One window, read by both the mesh and the legend beside it. Collected as
    // state rather than read off the flows, so changing the scale recomposes.
    val splScaleMode by vm.splScaleMode.collectAsState()
    val splTarget by vm.splTargetDb.collectAsState()
    val splSpan by vm.splSpanDb.collectAsState()
    val splFixedMin by vm.splFixedMinDb.collectAsState()
    val splFixedMax by vm.splFixedMaxDb.collectAsState()

    // Assets commit first; a small checksum-protected checkpoint follows atomically.
    // Do not overwrite a failed recovery with the empty startup scene.
    val projectStore = remember(activity) { LocalProjectStore(File(activity.filesDir, "projects")) }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching {
            val saved = withContext(Dispatchers.IO) { projectStore.loadPrepared("recovery") }
            val legacy = if (saved == null) withContext(Dispatchers.IO) {
                val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ProjectStore.legacyKeys("recovery").firstNotNullOfOrNull {
                    prefs.getString(it, null)?.takeIf(String::isNotBlank)
                }
            } else null
            val prepared = saved?.project ?: legacy?.let { withContext(Dispatchers.Default) { ProjectArchive.open(it) } }
            if (prepared != null) check(vm.importPreparedProject(prepared)) { vm.lastImportError.value ?: "Recovery failed" }
            restored = true
            if (saved?.usedBackup == true) snackbar.showSnackbar("Recovered the previous valid checkpoint.")
        }.onFailure {
            snackbar.showSnackbar("Recovery failed; autosave paused. Existing files preserved. ${it.message}")
        }
    }
    val predictionState by vm.predictionState.collectAsState()
    val predictionValidity by vm.predictionValidity.collectAsState()
    val coveragePresentation=CoveragePresentation.describe(signalType,band,predictionState,autoCalc,predictionValidity)
    val revision by vm.revision.collectAsState()
    LaunchedEffect(restored, revision, receiverAngularEnabled, receiverAngularSelection, receiverFocus, autoCalc) {
        if (!restored) return@LaunchedEffect
        delay(1200)
        val json = vm.exportSceneJson(includeClfRegistry = false)
        val assetPayloads = vm.projectAssetPayloads()
        runCatching {
            withContext(Dispatchers.IO) { projectStore.save("recovery", json, assetPayloads::get) }
        }.onFailure { snackbar.showSnackbar("Autosave failed; previous checkpoint preserved. ${it.message}") }
    }

    val (splMin, splMax) = SceneViewModel.splScaleWindow(
        splScaleMode, splTarget, splSpan, splFixedMin, splFixedMax, heatmap
    )

    val contourMode by vm.contourMode.collectAsState()
    val weighting by vm.weighting.collectAsState()
    // Iso-levels sit below a reference: the design target where one is set,
    // otherwise the 95th percentile. Recomputed with the map, not with the frame.
    val contourReference = remember(heatmap, contourMode, splMin, splMax) {
        if (contourMode == SceneViewModel.CONTOUR_OFF) null else vm.contourReferenceDb(heatmap)
    }
    val contourThresholds = remember(contourReference, contourMode) {
        if (contourMode != SceneViewModel.CONTOUR_LINES) emptyList()
        else contourReference?.let { ref -> ContoursGlb.DEFAULT_STEPS_DB.map { ref + it } }.orEmpty()
    }
    val bandStep = if (contourMode == SceneViewModel.CONTOUR_BANDS) SceneViewModel.CONTOUR_STEP_DB else null


    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {

            TopBar(
                canUndo = canUndo,
                canRedo = canRedo,
                speakerCount = speakers.size,
                zoneCount = zones.size,
                presentation = coveragePresentation,
                autoCalculate=autoCalc,
                onAutoCalculate=vm::setSignalAutoCalculate,
                onCalculate = {
                    vm.recalculateSignal()
                },
                onUndo = {
                    if (!vm.undoScene()) scope.launch { snackbar.showSnackbar("Nothing to undo") }
                },
                onRedo = {
                    if (!vm.redoScene()) scope.launch { snackbar.showSnackbar("Nothing to redo") }
                },
                onSettings = { showSettings = true }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Row(Modifier.weight(1f)) {

                ToolRail(
                    tool = tool,
                    onTool = {
                        if (it != Tool.ZONE && (cutoutTarget != null || elevationTarget != null)) vm.clearAreaDraft()
                        tool = it
                        if (it == Tool.LISTENER) selection = Selection.Listener
                        else if (it != Tool.SELECT && it != Tool.FACES) selection = Selection.None
                    }
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outline)

                // ── Viewport column ──────────────────────────────────────────
                Column(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f)) {

                        val objectTarget = if(tool != Tool.SELECT) null else when(val picked=selection) {
                            is Selection.Block -> venue.blocks.firstOrNull {it.id==picked.id}?.let { b ->
                                SceneTransformTarget("BLOCK",b.id,SpeakerGizmoTransform(b.centerX,b.heightM,b.centerZ,b.rotationDeg,0f),b)
                            }
                            is Selection.Zone -> zones.firstOrNull {it.id==picked.id && it.vertices.isNotEmpty()}?.let { a ->
                                SceneTransformTarget("ZONE",a.id,SpeakerGizmoTransform(a.vertices.map {it.first}.average().toFloat(),a.baseHeightM,a.vertices.map {it.second}.average().toFloat(),a.rotationDeg,0f),a)
                            }
                            else -> null
                        }
                        FilamentSurface(
                            modifier = Modifier.fillMaxSize().onGloballyPositioned {viewportOrigin=it.positionInRoot()},
                            viewPreset = effectiveViewPreset,
                            receiverCameraFocus=receiverFocus,receiverFrameTarget=receiverFrameTarget,receiverFrameToken=receiverFrameToken,
                            receiverFrameObstructions=receiverFrameObstructions,onReceiverFrameMessage=vm::receiverFrameMessage,
                            frameAllToken = frameToken,
                            venueGeometry = venue,
                            audienceAreas = zones,
                            selectedMeshAreaId = selectedMeshFaces?.areaId?.takeIf { selection==Selection.Zone(it) },
                            selectedMeshFaces = selectedMeshFaces?.faceIndices.orEmpty(),
                            areaDraft = areaDraft,
                            draftSurface = zones.firstOrNull { it.id == cutoutTarget?.areaId },
                            audience = audience,
                            speakers = speakers,
                            selectedObject = objectTarget,
                            previewObject = { obj,t -> if(obj.kind=="BLOCK")
                                SceneTransformPreview(venue=vm.previewVenueBlockManipulation(obj.id,t.x,t.y,t.z,t.panDeg))
                                else SceneTransformPreview(areas=vm.previewAudienceAreaManipulation(obj.id,t.x,t.y,t.z,t.panDeg))
                            },
                            commitObject = {obj,t -> if(obj.kind=="BLOCK") vm.commitVenueBlockManipulation(obj.id,t.x,t.y,t.z,t.panDeg)
                                else vm.commitAudienceAreaManipulation(obj.id,t.x,t.y,t.z,t.panDeg)
                            },
                            selectedSpeaker = if (tool == Tool.SELECT) speakers.firstOrNull { it.id == (selection as? Selection.Speaker)?.id } else null,
                            previewSpeaker = { sp -> vm.previewSpeakerManipulation(sp.id,sp.x,sp.heightM,sp.z,sp.panDeg,sp.arrayAimDeg) },
                            onMoveSpeaker = { id, x, y, z -> vm.setSpeakerPosition(id, x, y, z) },
                            onRotateSpeaker = { id, pan -> vm.setSpeakerPan(id, pan) },
                            onTiltSpeaker = { id, tilt -> vm.setSpeakerArrayAim(id, tilt) },
                            speakerModelPackages = modelPackages,
                            heatmap = heatmap,
                            coverageGeometry = coverageGeometry,
                            splScaleMinDb = splMin,
                            splScaleMaxDb = splMax,
                            listener = listener,
                            receiverAngularReport=receiverAngular.report,
                            selectedReceiverMarkerId=receiverAngularSelection,
                            receiverMarkerPickingEnabled=tool==Tool.SELECT,
                            onReceiverMarkerSelected=vm::selectAngularReceiver,
                            aimRaysEnabled = aimRays,
                            coverageEdges = coverageEdges,
                            contourThresholds = contourThresholds,
                            contourEmphasisDb = contourReference?.minus(6f),
                            bandStepDb = bandStep,
                            bandReferenceDb = contourReference,
                            // Speakers are selectable by a true 3D ray test; a
                            // floor-plane hit test cannot reach a flown cabinet.
                            pickTargets = if (tool == Tool.SELECT) {
                                speakers.map {
                                    PickTarget(it.id, it.x, it.heightM, it.z, radius = 0.7f)
                                }
                            } else emptyList(),
                            onPickTarget = { id -> vm.clearImportedFaceSelection(); selection = Selection.Speaker(id) },
                            onSceneRayTap = { ray ->
                                if (tool!=Tool.FACES) false else {
                                    val hit=vm.pickImportedFace(ray)
                                    if (hit!=null) selection=Selection.Zone(hit.areaId)
                                    true // A miss clears the face highlight and never becomes a floor action.
                                }
                            },
                            onFloorTap = { x, z ->
                                when (tool) {
                                    Tool.SELECT -> {
                                        vm.clearImportedFaceSelection()
                                        selection = nearestSelection(x, z, vm, speakers, zones, venue)
                                    }
                                    Tool.FACES -> Unit
                                    Tool.SPEAKER -> vm.addSpeaker(x, z)
                                    Tool.ZONE -> {
                                        val target=elevationTarget
                                        if (target == null) vm.addAreaVertex(x,z)
                                        else if (vm.addAudienceElevationPoint(target,x,z)) {
                                            tool=Tool.SELECT; selection=Selection.Zone(target)
                                        }
                                    }
                                    Tool.BLOCK -> vm.addVenueBlock(blockType, x, z)
                                    Tool.LISTENER -> vm.moveListener(x, z)
                                }
                            }
                        )

                        if(receiverFocus==null) ReceiverAngularOverlay(vm,receiverAngular,Modifier.align(Alignment.CenterEnd).padding(10.dp),
                            onFrame={vm.frameAngularReceiver(effectiveViewPreset.name)},
                            onBounds={receiverPanelRect=it})

                        // ── Floating controls ────────────────────────────────
                        ToolHintOverlay(
                            text = tool.hint,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                        )
                        ViewControlsOverlay(
                            preset = effectiveViewPreset,
                            onPreset = { viewPreset = it;vm.setReceiverFrameView(it.name) },
                            onFrameAll = { viewPreset=effectiveViewPreset;vm.clearReceiverFrame();frameToken++ },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                        )
                        BandOverlay(
                            bands = BANDS,
                            selected = band,
                            onSelect = { vm.setBandHz(it) },
                            signalType=signalType,
                            onSignalType=vm::setSignalType,
                            presentation=coveragePresentation,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                        )
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp)
                        ) {
                            if(receiverFocus!=null) ReceiverAngularOverlay(vm,receiverAngular,
                                onFrame={vm.frameAngularReceiver(effectiveViewPreset.name)},
                                onBounds={receiverPanelRect=it})
                            ConventionsOverlay(
                                summation = if (interference) "complex sum" else "energy sum",
                                bandwidth = if (signalType == "BAND") {
                                    "$band Hz • 1/${(1f / bandwidthOct).toInt().coerceAtLeast(1)} oct"
                                } else {
                                    // The weighting has to travel with the number.
                                    // A dB(A) map that does not say it is A-weighted
                                    // is a figure waiting to be quoted as if it were
                                    // unweighted.
                                    when (weighting) {
                                        SceneViewModel.WEIGHTING_A -> "broadband dB(A)"
                                        SceneViewModel.WEIGHTING_C -> "broadband dB(C)"
                                        else -> "broadband"
                                    }+" • ${broadbandDrive.shortLabel}"
                                },
                                temperatureC = temperatureC,
                                humidityPct = humidityPct
                            )
                            if (heatmap.isNotEmpty()) {
                                SplLegendOverlay(minDb = splMin, maxDb = splMax)
                            }
                        }

                        // Zone drawing needs an explicit finish action.
                        if (tool == Tool.ZONE && elevationTarget != null) {
                            Column(Modifier.align(Alignment.TopCenter).padding(12.dp)
                                .background(MaterialTheme.colorScheme.surface)) {
                                Text("Tap inside the zone to add a height point",style=MaterialTheme.typography.bodySmall)
                                audienceGeometryError?.let { Text(it,color=Instrument.Critical,style=MaterialTheme.typography.bodySmall) }
                                SmallAction("Cancel") { vm.clearAreaDraft(); tool=Tool.SELECT }
                            }
                        }
                        if (tool == Tool.ZONE && (areaDraft.isNotEmpty() || cutoutTarget != null)) {
                            Column(Modifier.align(Alignment.TopCenter).padding(12.dp)
                                .background(MaterialTheme.colorScheme.surface)) {
                                cutoutTarget?.let { target ->
                                    val name=zones.firstOrNull { it.id == target.areaId }?.name ?: "Zone"
                                    Text("Draw ${if (target.hole) "hole" else "exclusion"} for $name",
                                        style=MaterialTheme.typography.bodySmall)
                                }
                                audienceGeometryError?.let { Text(it,color=Instrument.Critical,
                                    style=MaterialTheme.typography.bodySmall) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SmallAction("Undo point") { vm.undoAreaVertex() }
                                    SmallAction(if (cutoutTarget == null) "Close zone" else "Apply cutout", tint = Instrument.Accent) {
                                        vm.closeAreaFromDraft()
                                    }
                                    SmallAction("Discard", tint = Instrument.Critical) { vm.clearAreaDraft() }
                                }
                            }
                        }
                    }

                    AnalysisStrip(
                        heatmap = heatmap,
                        coverageStatistics = coverageStatistics,
                        combinedSplDb = combined,
                        predictionState = predictionState,
                        statusLabel = coveragePresentation.status,
                        expanded = analysisExpanded,
                        onToggle = { analysisExpanded = !analysisExpanded }
                    )
                }

                VerticalDivider(color = MaterialTheme.colorScheme.outline)

                Inspector(
                    vm = vm,
                    selection = selection,
                    onSelect = { selection = it },
                    speakers = speakers,
                    zones = zones,
                    venue = venue,
                    dspMap = dspMap,
                    tool = tool,
                    modifier = Modifier.width(320.dp)
                )
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            vm = vm,
            activity = activity,
            blockType = blockType,
            onBlockType = { blockType = it },
            onDismiss = { showSettings = false },
            onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    speakerCount: Int,
    zoneCount: Int,
    presentation: CoveragePresentation,
    autoCalculate: Boolean,
    onAutoCalculate: (Boolean) -> Unit,
    onCalculate: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        // Project identity, not build state. "Phase 10" was developer
        // bookkeeping sitting where the document name belongs.
        Text(
            "Deo Acousticz",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false
        )
        Spacer(Modifier.width(14.dp))
        StatusChip("$speakerCount spk", Instrument.Accent)
        Spacer(Modifier.width(6.dp))
        StatusChip("$zoneCount zones", Instrument.InkDim)

        Spacer(Modifier.weight(1f))

        SmallAction(if(autoCalculate) "Auto" else "Manual") {onAutoCalculate(!autoCalculate)}
        Spacer(Modifier.width(8.dp))
        if (presentation.showAction) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .background(
                        if (presentation.needsCalculation) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable(enabled=presentation.actionEnabled,onClick = onCalculate)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    presentation.action,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (presentation.needsCalculation) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false
                )
            }
            Spacer(Modifier.width(10.dp))
        }

        IconAction(Icons.AutoMirrored.Filled.Undo, "Undo", canUndo, onUndo)
        IconAction(Icons.AutoMirrored.Filled.Redo, "Redo", canRedo, onRedo)
        Spacer(Modifier.width(6.dp))
        IconAction(Icons.Default.Tune, "Settings", true, onSettings)
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tool rail
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ToolRail(tool: Tool, onTool: (Tool) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxHeight()
            .width(72.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 10.dp)
    ) {
        // Top-aligned rather than vertically centred: the old rail floated its
        // items in the middle with large dead gaps above and below.
        Tool.entries.forEach { t ->
            val selected = t == tool
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        RoundedCornerShape(5.dp)
                    )
                    .clickable { onTool(t) }
                    .padding(vertical = 8.dp)
            ) {
                Icon(
                    t.icon,
                    contentDescription = t.label,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
                Text(
                    t.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                    softWrap = false
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hit testing
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Picks the nearest selectable object to a floor tap.
 *
 * This is a 2D proximity test on the floor plane rather than a true 3D ray
 * pick — the viewport reports taps as floor coordinates, so that is the
 * information available without reaching into Filament's picking API.
 */
private fun nearestSelection(
    x: Float,
    z: Float,
    vm: SceneViewModel,
    speakers: List<com.droidacoustic.pro.scene.PlacedSpeaker>,
    zones: List<com.droidacoustic.pro.scene.AudienceArea>,
    venue: com.droidacoustic.pro.scene.VenueGeometry
): Selection {
    val pickRadius = 1.5f

    speakers.minByOrNull { s -> (s.x - x) * (s.x - x) + (s.z - z) * (s.z - z) }
        ?.let { s ->
            val d2 = (s.x - x) * (s.x - x) + (s.z - z) * (s.z - z)
            if (d2 <= pickRadius * pickRadius) return Selection.Speaker(s.id)
        }

    venue.blocks.firstOrNull { b ->
        abs(x - b.centerX) <= b.widthM * 0.5f && abs(z - b.centerZ) <= b.depthM * 0.5f
    }?.let { return Selection.Block(it.id) }

    zones.firstOrNull { zone -> zone.sharedSurface().containsOutlinePoint(x,z) }
        ?.let { return Selection.Zone(it.id) }

    return Selection.None
}
