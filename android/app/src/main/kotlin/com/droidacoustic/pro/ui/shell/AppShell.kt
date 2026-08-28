package com.droidacoustic.pro.ui.shell

import android.content.Context
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidacoustic.pro.MainActivity
import com.droidacoustic.pro.scene.SceneViewModel
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

/** What a tap on the floor does. */
enum class Tool(val label: String, val icon: ImageVector, val hint: String) {
    SELECT("Select", Icons.Default.CenterFocusStrong, "Tap an object to select it"),
    SPEAKER("Speaker", Icons.Default.Speaker, "Tap the floor to place a speaker"),
    ZONE("Zone", Icons.Default.Groups, "Tap to add zone corners, then close the shape"),
    BLOCK("Block", Icons.Default.Architecture, "Tap the floor to place a block"),
    LISTENER("Listener", Icons.Default.Casino, "Tap the floor to move the listener")
}

private val BANDS = listOf(63, 125, 250, 500, 1000, 2000, 4000, 8000)

private const val PREFS_NAME = "droidacoustic_project"
private val RECOVERY_KEY = "scene_recovery_latest_v${SceneViewModel.SCENE_SCHEMA_VERSION}"

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
    var analysisExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var blockType by remember { mutableStateOf("OBSTACLE") }

    // ── Scene state ──────────────────────────────────────────────────────────
    val speakers by vm.speakers.collectAsState()
    val zones by vm.audienceAreas.collectAsState()
    val areaDraft by vm.areaDraft.collectAsState()
    val audience by vm.audience.collectAsState()
    val venue by vm.venueGeometry.collectAsState()
    val heatmap by vm.heatmap.collectAsState()
    val listener by vm.listener.collectAsState()
    val aimRays by vm.aimRaysEnabled.collectAsState()
    val clfRegistryForRays by vm.clfRegistry.collectAsState()
    val dspMap by vm.dspMap.collectAsState()
    val combined by vm.combinedSplDb.collectAsState()
    val rt60 by vm.rt60Estimate.collectAsState()
    val sti by vm.stiEstimate.collectAsState()
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

    // ── Autosave and recovery ────────────────────────────────────────────────
    //
    // The old shell wrote a recovery snapshot after every change; dropping it
    // in the rewrite would have meant losing the scene whenever the process
    // was killed. Restore once on launch, then write debounced.
    // The autosave must not run until the restore has finished. Both effects
    // start at first composition, so on a slow launch - fresh install, dexopt, a
    // large scene to parse - the debounced write would fire while the view model
    // still held the empty default scene and persist THAT over the saved one,
    // destroying it. Gate the writer on the reader.
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val json = withContext(Dispatchers.IO) {
            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RECOVERY_KEY, null)
        }
        json?.takeIf { it.isNotBlank() }?.let {
            withContext(Dispatchers.Default) { vm.importSceneJson(it) }
        }
        restored = true
    }
    LaunchedEffect(
        restored, speakers, zones, venue, dspMap, listener, band,
        splScaleMode, splTarget, splSpan, splFixedMin, splFixedMax
    ) {
        if (!restored) return@LaunchedEffect
        delay(1200)
        // includeClfRegistry MUST stay false here. The registry holds the
        // decoded polar data for every speaker in the library; serialising it
        // on every edit — and parsing it back at launch — blocks the main
        // thread long enough for the system to kill the app for not
        // responding. Recovery only needs the scene.
        val json = withContext(Dispatchers.Default) {
            vm.exportSceneJson(includeClfRegistry = false)
        }
        withContext(Dispatchers.IO) {
            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(RECOVERY_KEY, json)
                .apply()
        }
    }

    val (splMin, splMax) = SceneViewModel.splScaleWindow(
        splScaleMode, splTarget, splSpan, splFixedMin, splFixedMax, heatmap
    )

    val contoursOn by vm.contoursEnabled.collectAsState()
    // Contour levels sit below a reference: the design target where one is set,
    // otherwise the loudest point. Recomputed with the map, not with the frame.
    val contourReference = remember(heatmap, contoursOn, splMin, splMax) {
        if (!contoursOn) null else vm.contourReferenceDb(heatmap)
    }
    val contourThresholds = remember(contourReference) {
        contourReference?.let { ref -> ContoursGlb.DEFAULT_STEPS_DB.map { ref + it } }.orEmpty()
    }


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
                autoCalc = autoCalc,
                hasResult = heatmap.isNotEmpty(),
                onCalculate = {
                    vm.recalculateSignal()
                    scope.launch { snackbar.showSnackbar("Calculating coverage…") }
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
                        tool = it
                        if (it != Tool.SELECT) selection = Selection.None
                    }
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outline)

                // ── Viewport column ──────────────────────────────────────────
                Column(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f)) {

                        FilamentSurface(
                            modifier = Modifier.fillMaxSize(),
                            viewPreset = viewPreset,
                            frameAllToken = frameToken,
                            venueGeometry = venue,
                            audienceAreas = zones,
                            areaDraft = areaDraft,
                            audience = audience,
                            speakers = speakers,
                            speakerModelPackages = modelPackages,
                            heatmap = heatmap,
                            splScaleMinDb = splMin,
                            splScaleMaxDb = splMax,
                            listener = listener,
                            aimRaysEnabled = aimRays,
                            coverageEdges = coverageEdges,
                            contourThresholds = contourThresholds,
                            contourEmphasisDb = contourReference?.minus(6f),
                            // Speakers are selectable by a true 3D ray test; a
                            // floor-plane hit test cannot reach a flown cabinet.
                            pickTargets = if (tool == Tool.SELECT) {
                                speakers.map {
                                    PickTarget(it.id, it.x, it.heightM, it.z, radius = 0.7f)
                                }
                            } else emptyList(),
                            onPickTarget = { id -> selection = Selection.Speaker(id) },
                            onFloorTap = { x, z ->
                                when (tool) {
                                    Tool.SELECT -> selection = nearestSelection(x, z, vm, speakers, zones, venue)
                                    Tool.SPEAKER -> vm.addSpeaker(x, z)
                                    Tool.ZONE -> vm.addAreaVertex(x, z)
                                    Tool.BLOCK -> vm.addVenueBlock(blockType, x, z)
                                    Tool.LISTENER -> vm.moveListener(x, z)
                                }
                            }
                        )

                        // ── Floating controls ────────────────────────────────
                        ToolHintOverlay(
                            text = tool.hint,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                        )
                        ViewControlsOverlay(
                            preset = viewPreset,
                            onPreset = { viewPreset = it },
                            onFrameAll = { frameToken++ },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                        )
                        BandOverlay(
                            bands = BANDS,
                            selected = band,
                            onSelect = { vm.setBandHz(it) },
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
                            ConventionsOverlay(
                                summation = if (interference) "complex sum" else "energy sum",
                                bandwidth = if (signalType == "BAND") {
                                    "1/${(1f / bandwidthOct).toInt().coerceAtLeast(1)} oct"
                                } else "spectrum",
                                temperatureC = temperatureC,
                                humidityPct = humidityPct
                            )
                            if (heatmap.isNotEmpty()) {
                                SplLegendOverlay(minDb = splMin, maxDb = splMax)
                            }
                        }

                        // Zone drawing needs an explicit finish action.
                        if (tool == Tool.ZONE && areaDraft.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(12.dp)
                            ) {
                                SmallAction("Undo point") { vm.undoAreaVertex() }
                                SmallAction("Close zone", tint = Instrument.Accent) {
                                    vm.closeAreaFromDraft()
                                }
                                SmallAction("Discard", tint = Instrument.Critical) {
                                    vm.clearAreaDraft()
                                }
                            }
                        }
                    }

                    AnalysisStrip(
                        heatmap = heatmap,
                        combinedSplDb = combined,
                        rt60 = rt60,
                        sti = sti,
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
    autoCalc: Boolean,
    hasResult: Boolean,
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

        if (!autoCalc) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .background(
                        if (hasResult) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable(onClick = onCalculate)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    if (hasResult) "Recalculate" else "Calculate",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hasResult) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onPrimary,
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

    zones.firstOrNull { zone -> pointInPolygon(x, z, zone.vertices) }
        ?.let { return Selection.Zone(it.id) }

    return Selection.None
}

private fun pointInPolygon(x: Float, z: Float, poly: List<Pair<Float, Float>>): Boolean {
    if (poly.size < 3) return false
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val (xi, zi) = poly[i]
        val (xj, zj) = poly[j]
        if ((zi > z) != (zj > z) &&
            x < (xj - xi) * (z - zi) / ((zj - zi).takeIf { it != 0f } ?: 1e-6f) + xi
        ) inside = !inside
        j = i
    }
    return inside
}
