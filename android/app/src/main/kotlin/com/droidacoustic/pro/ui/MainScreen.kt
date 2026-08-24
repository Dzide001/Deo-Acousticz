package com.droidacoustic.pro.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.MainActivity
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidacoustic.pro.scene.AudienceArea
import com.droidacoustic.pro.scene.AudiencePoint
import com.droidacoustic.pro.scene.ClfIngestionStats
import com.droidacoustic.pro.scene.EarlyReflection
import com.droidacoustic.pro.scene.HeatCell
import com.droidacoustic.pro.scene.ListenerPos
import com.droidacoustic.pro.scene.PlacedSpeaker
import com.droidacoustic.pro.scene.RoomMaterials
import com.droidacoustic.pro.scene.Rt60Estimate
import com.droidacoustic.pro.scene.SceneViewModel
import com.droidacoustic.pro.scene.StiEstimate
import com.droidacoustic.pro.scene.SpeakerDsp
import com.droidacoustic.pro.scene.SpeakerModelPackage
import com.droidacoustic.pro.scene.SpeakerPreset
import com.droidacoustic.pro.scene.SpeakerResult
import com.droidacoustic.pro.scene.SpeakerSource
import com.droidacoustic.pro.scene.VenueGeometry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ─── Navigation destinations ─────────────────────────────────────────────────

enum class AppDestination(val label: String) {
    VENUE("Venue"),
    SPEAKERS("Speakers"),
    SPL("SPL"),
    RESULTS("Results"),
    REPORT("Report"),
    SETTINGS("Settings"),
}

// ─────────────────────────────────────────────────────────────────────────────
// Main screen — two-pane layout (NavigationRail + 3D Viewport)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(activity: MainActivity) {

    val scope           = rememberCoroutineScope()
    val snackbarState   = remember { SnackbarHostState() }

    var currentDest     by remember { mutableStateOf(AppDestination.VENUE) }
    var isCalculating   by remember { mutableStateOf(false) }
    var selectedSnapshotSlot by remember { mutableStateOf("A") }
    var snapshotSlotNames by remember {
        mutableStateOf(mapOf("A" to "Slot A", "B" to "Slot B", "C" to "Slot C"))
    }
    var snapshotSavedAtMs by remember { mutableStateOf(mapOf<String, Long>()) }
    var snapshotNameDraft by remember { mutableStateOf("Slot A") }
    var isSnapshotDirty by remember { mutableStateOf(false) }
    var recoverySavedAtMs by remember { mutableStateOf<Long?>(null) }
    var audienceScoreMode by remember { mutableStateOf("Speech") }
    var activeBlockType by remember { mutableStateOf("OBSTACLE") }
    var showHeatmap by remember { mutableStateOf(true) }
    var meshLoadedCount by remember { mutableStateOf(0) }
    var meshTotalCount by remember { mutableStateOf(0) }
    var bundledCatalogVersion by remember { mutableStateOf<Int?>(null) }
    var bundledCatalogPresetCount by remember { mutableStateOf(0) }
    var bundledCatalogPackageCount by remember { mutableStateOf(0) }
    var loadedCatalogVersion by remember { mutableStateOf<Int?>(null) }
    val logMessages     = remember { mutableStateListOf<String>() }

    val vm: SceneViewModel = viewModel()
    val audience           by vm.audience.collectAsState()
    val audienceAreas      by vm.audienceAreas.collectAsState()
    val areaDraft          by vm.areaDraft.collectAsState()
    val speakers           by vm.speakers.collectAsState()
    val results            by vm.results.collectAsState()
    val dspMap             by vm.dspMap.collectAsState()
    val combinedSplDb      by vm.combinedSplDb.collectAsState()
    val earlyReflections   by vm.earlyReflections.collectAsState()
    val rt60Estimate       by vm.rt60Estimate.collectAsState()
    val stiEstimate        by vm.stiEstimate.collectAsState()
    val selectedPresetId   by vm.selectedPresetId.collectAsState()
    val speakerPresets     by vm.speakerPresets.collectAsState()
    val speakerSources     by vm.speakerSources.collectAsState()
    val activeSpeakerSourceId by vm.activeSpeakerSourceId.collectAsState()
    val speakerModelPackages by vm.speakerModelPackages.collectAsState()
    val activeZoneType     by vm.activeZoneType.collectAsState()
    val activeZoneBaseHeightM by vm.activeZoneBaseHeightM.collectAsState()
    val activeZoneRakeDeg by vm.activeZoneRakeDeg.collectAsState()
    val activeZoneRakeDirectionDeg by vm.activeZoneRakeDirectionDeg.collectAsState()
    val selectedSpeakerModelPackageId by vm.selectedSpeakerModelPackageId.collectAsState()
    val heatmap            by vm.heatmap.collectAsState()
    val listener           by vm.listener.collectAsState()
    val selectedBandHz     by vm.selectedBandHz.collectAsState()
    val signalLevelDbu     by vm.signalLevelDbu.collectAsState()
    val signalType         by vm.signalType.collectAsState()
    val signalBandwidthOct by vm.signalBandwidthOct.collectAsState()
    val signalResolution   by vm.signalResolution.collectAsState()
    val signalInterferenceEnabled by vm.signalInterferenceEnabled.collectAsState()
    val signalAutoCalculate by vm.signalAutoCalculate.collectAsState()
    val signalSplEnabled   by vm.signalSplEnabled.collectAsState()
    val signalDispersionEnabled by vm.signalDispersionEnabled.collectAsState()
    val signalCoverageEnabled by vm.signalCoverageEnabled.collectAsState()
    val highestSplDb       by vm.highestSplDb.collectAsState()
    val temperatureC       by vm.temperatureC.collectAsState()
    val humidityPct        by vm.humidityPct.collectAsState()
    val roomMaterials      by vm.roomMaterials.collectAsState()
    val venueGeometry      by vm.venueGeometry.collectAsState()
    val reflectionOrder    by vm.reflectionOrder.collectAsState()
    val analysisProfile    by vm.analysisProfile.collectAsState()
    val engineReady        by vm.engineReady.collectAsState()
    val canUndo            by vm.canUndo.collectAsState()
    val canRedo            by vm.canRedo.collectAsState()
    val clfSourceStatus    by vm.clfSourceStatus.collectAsState()
    val clfBinaryAssets    by vm.clfBinaryAssets.collectAsState()
    val clfExternalAssets  by vm.clfExternalAssets.collectAsState()
    val clfIngestionStats  by vm.clfIngestionStats.collectAsState()
    val strictExtractedBinaryClfOnly by vm.strictExtractedBinaryClfOnly.collectAsState()

    val snapshotKeyFor: (String) -> String = { slot ->
        "scene_snapshot_${slot}_v${SceneViewModel.SCENE_SCHEMA_VERSION}"
    }
    val legacySnapshotKeyFor: (String) -> String = { slot ->
        "scene_snapshot_${slot}_v1"
    }
    val recoveryKey = "scene_recovery_latest_v${SceneViewModel.SCENE_SCHEMA_VERSION}"
    val legacyRecoveryKey = "scene_recovery_latest_v1"
    val recoverySavedAtKey = "scene_recovery_savedAt"
    val industryCatalogLoadedVersionKey = "industry_catalog_loaded_version"

    // ─── Initialise engine on first composition ───────────────────────────────
    LaunchedEffect(Unit) {
        vm.initEngine(activity.assets)
        val prefs = activity.getSharedPreferences("droidacoustic_project", Context.MODE_PRIVATE)
        snapshotSlotNames = mapOf(
            "A" to prefs.getString("scene_snapshot_name_A", "Slot A").orEmpty().ifBlank { "Slot A" },
            "B" to prefs.getString("scene_snapshot_name_B", "Slot B").orEmpty().ifBlank { "Slot B" },
            "C" to prefs.getString("scene_snapshot_name_C", "Slot C").orEmpty().ifBlank { "Slot C" }
        )
        snapshotSavedAtMs = buildMap {
            listOf("A", "B", "C").forEach { slot ->
                val key = "scene_snapshot_savedAt_${slot}"
                if (prefs.contains(key)) put(slot, prefs.getLong(key, 0L))
            }
        }
        recoverySavedAtMs = if (prefs.contains(recoverySavedAtKey)) {
            prefs.getLong(recoverySavedAtKey, 0L)
        } else {
            null
        }
        loadedCatalogVersion = if (prefs.contains(industryCatalogLoadedVersionKey)) {
            prefs.getInt(industryCatalogLoadedVersionKey, 0).takeIf { it > 0 }
        } else {
            null
        }
        snapshotNameDraft = snapshotSlotNames[selectedSnapshotSlot] ?: "Slot $selectedSnapshotSlot"
        logMessages.add("[INIT] Engine initialising…")
    }

    LaunchedEffect(engineReady) {
        if (!engineReady) return@LaunchedEffect
        vm.getBundledIndustryCatalogInfo()?.let { info ->
            bundledCatalogVersion = info.version
            bundledCatalogPresetCount = info.presetCount
            bundledCatalogPackageCount = info.modelPackageCount
        }
    }

    LaunchedEffect(selectedSnapshotSlot, snapshotSlotNames) {
        snapshotNameDraft = snapshotSlotNames[selectedSnapshotSlot] ?: "Slot $selectedSnapshotSlot"
    }

    LaunchedEffect(
        selectedSnapshotSlot,
        speakers,
        audience,
        audienceAreas,
        areaDraft,
        listener,
        selectedPresetId,
        activeZoneType,
        selectedSpeakerModelPackageId,
        selectedBandHz,
        signalLevelDbu,
        signalType,
        signalBandwidthOct,
        signalResolution,
        signalInterferenceEnabled,
        signalAutoCalculate,
        signalSplEnabled,
        signalDispersionEnabled,
        signalCoverageEnabled,
        temperatureC,
        humidityPct,
        roomMaterials,
        venueGeometry,
        reflectionOrder,
        analysisProfile,
        dspMap
    ) {
        val prefs = activity.getSharedPreferences("droidacoustic_project", Context.MODE_PRIVATE)
        val key = snapshotKeyFor(selectedSnapshotSlot)
        val legacyKey = legacySnapshotKeyFor(selectedSnapshotSlot)
        val saved = prefs.getString(key, null) ?: prefs.getString(legacyKey, null)
        val current = vm.exportSceneJson()
        isSnapshotDirty = saved.isNullOrBlank() || saved != current
    }

    LaunchedEffect(
        speakers,
        audience,
        audienceAreas,
        areaDraft,
        listener,
        selectedPresetId,
        activeZoneType,
        selectedSpeakerModelPackageId,
        selectedBandHz,
        signalLevelDbu,
        signalType,
        signalBandwidthOct,
        signalResolution,
        signalInterferenceEnabled,
        signalAutoCalculate,
        signalSplEnabled,
        signalDispersionEnabled,
        signalCoverageEnabled,
        temperatureC,
        humidityPct,
        roomMaterials,
        venueGeometry,
        reflectionOrder,
        analysisProfile,
        dspMap
    ) {
        delay(1200)
        val nowMs = System.currentTimeMillis()
        val prefs = activity.getSharedPreferences("droidacoustic_project", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(recoveryKey, vm.exportSceneJson())
            .putLong(recoverySavedAtKey, nowMs)
            .apply()
        recoverySavedAtMs = nowMs
    }

    val activeSlotLabel = snapshotSlotNames[selectedSnapshotSlot] ?: "Slot $selectedSnapshotSlot"

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarState) },
        topBar = {
            TopAppBar(
                title = { Text("DroidAcoustic Pro  ·  Phase 10") },
                actions = {
                    TextButton(
                        onClick = {
                            val ok = vm.undoScene()
                            if (!ok) scope.launch { snackbarState.showSnackbar("Nothing to undo") }
                        },
                        enabled = canUndo
                    ) {
                        Text("Undo")
                    }
                    TextButton(
                        onClick = {
                            val ok = vm.redoScene()
                            if (!ok) scope.launch { snackbarState.showSnackbar("Nothing to redo") }
                        },
                        enabled = canRedo
                    ) {
                        Text("Redo")
                    }
                    Text(
                        text = "Snapshot: ${selectedSnapshotSlot} · ${activeSlotLabel.take(14)}${if (isSnapshotDirty) " •" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    if (isCalculating) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            )
        }
    ) { paddingValues ->

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ─── Left rail: navigation + controls ────────────────────────────
            AppNavigationRail(
                currentDest = currentDest,
                onDestSelected = { currentDest = it }
            )

            // ─── Properties panel (right of rail, left of 3D view) ───────────
            PropertiesPanel(
                destination   = currentDest,
                engineReady   = engineReady,
                isCalculating = isCalculating,
                audience      = audience,
                audienceAreas = audienceAreas,
                areaDraft     = areaDraft,
                venueGeometry = venueGeometry,
                activeZoneType = activeZoneType,
                activeBlockType = activeBlockType,
                activeZoneBaseHeightM = activeZoneBaseHeightM,
                activeZoneRakeDeg = activeZoneRakeDeg,
                activeZoneRakeDirectionDeg = activeZoneRakeDirectionDeg,
                speakers      = speakers,
                speakerResults = results,
                dspMap        = dspMap,
                selectedPresetId = selectedPresetId,
                speakerPresets = speakerPresets,
                speakerSources = speakerSources,
                activeSpeakerSourceId = activeSpeakerSourceId,
                speakerModelPackages = speakerModelPackages,
                listener      = listener,
                heatmap       = heatmap,
                showHeatmap = showHeatmap,
                combinedSplDb = combinedSplDb,
                earlyReflections = earlyReflections,
                rt60Estimate = rt60Estimate,
                stiEstimate = stiEstimate,
                selectedBandHz = selectedBandHz,
                signalLevelDbu = signalLevelDbu,
                signalType = signalType,
                signalBandwidthOct = signalBandwidthOct,
                signalResolution = signalResolution,
                signalInterferenceEnabled = signalInterferenceEnabled,
                signalAutoCalculate = signalAutoCalculate,
                signalSplEnabled = signalSplEnabled,
                signalDispersionEnabled = signalDispersionEnabled,
                signalCoverageEnabled = signalCoverageEnabled,
                highestSplDb = highestSplDb,
                temperatureC = temperatureC,
                humidityPct = humidityPct,
                reflectionOrder = reflectionOrder,
                analysisProfile = analysisProfile,
                audienceScoreMode = audienceScoreMode,
                onRunGpuTest  = {
                    scope.launch {
                        isCalculating = true
                        logMessages.add("[GPU] Running distance test …")
                        vm.runGpuDistanceTest(
                            onLog  = { msg -> logMessages.add(msg) },
                            onDone = { isCalculating = false }
                        )
                    }
                },
                onUndoAreaVertex      = { vm.undoAreaVertex() },
                onClearAreaDraft      = { vm.clearAreaDraft() },
                onCloseAreaFromDraft  = { vm.closeAreaFromDraft() },
                onVenueSizeChanged    = { w, d -> vm.setVenueSize(w, d) },
                onVenueWallHeightChanged = { vm.setVenueWallHeight(it) },
                onStageCenterChanged  = { x, z -> vm.setStageCenter(x, z) },
                onStageSizeChanged    = { w, d -> vm.setStageSize(w, d) },
                onStageHeightChanged  = { vm.setStageHeight(it) },
                onStageSlopeChanged   = { vm.setStageSlope(it) },
                onActiveZoneTypeChanged = { vm.setActiveZoneType(it) },
                onActiveBlockTypeChanged = { activeBlockType = it },
                onActiveZoneBaseHeightChanged = { vm.setActiveZoneBaseHeight(it) },
                onActiveZoneRakeChanged = { vm.setActiveZoneRakeDeg(it) },
                onActiveZoneRakeDirectionChanged = { vm.setActiveZoneRakeDirectionDeg(it) },
                onAudienceAreaBaseHeightChanged = { id, h -> vm.setAudienceAreaBaseHeight(id, h) },
                onAudienceAreaRakeChanged = { id, r -> vm.setAudienceAreaRake(id, r) },
                onAudienceAreaRakeDirectionChanged = { id, d -> vm.setAudienceAreaRakeDirection(id, d) },
                onAudienceAreaNameChanged = { id, name -> vm.setAudienceAreaName(id, name) },
                onAudienceAreaTypeChanged = { id, zoneType -> vm.setAudienceAreaType(id, zoneType) },
                onAudienceAreaRotationChanged = { id, deg -> vm.setAudienceAreaRotation(id, deg) },
                onAudienceAreaEdgeLengthChanged = { id, edge, length -> vm.setAudienceAreaEdgeLength(id, edge, length) },
                onDuplicateAudienceArea = { id -> vm.duplicateAudienceArea(id) },
                onMirrorAudienceAreaX = { id, refId -> vm.mirrorAudienceAreaX(id, refId) },
                onMirrorAudienceAreaZ = { id, refId -> vm.mirrorAudienceAreaZ(id, refId) },
                onAudienceAreaLinkChanged = { id, linkedId -> vm.setAudienceAreaLink(id, linkedId) },
                onRemoveAudienceArea = { id -> vm.removeAudienceArea(id) },
                onCreateBlockFromArea = { id, remove -> vm.createVenueBlockFromArea(id, remove) },
                onAddVenueBlock = { vm.addVenueBlock(it) },
                onRemoveVenueBlock = { vm.removeVenueBlock(it) },
                onVenueBlockTypeChanged = { id, type -> vm.setVenueBlockType(id, type) },
                onVenueBlockCenterChanged = { id, x, z -> vm.setVenueBlockCenter(id, x, z) },
                onVenueBlockSizeChanged = { id, w, d -> vm.setVenueBlockSize(id, w, d) },
                onVenueBlockHeightChanged = { id, h -> vm.setVenueBlockHeight(id, h) },
                onVenueBlockThicknessChanged = { id, h -> vm.setVenueBlockThickness(id, h) },
                onVenueBlockSlopeChanged = { id, s -> vm.setVenueBlockSlope(id, s) },
                onVenueBlockRotationChanged = { id, r -> vm.setVenueBlockRotation(id, r) },
                onVenueBlockLabelChanged = { id, label -> vm.setVenueBlockLabel(id, label) },
                onDuplicateVenueBlock = { id -> vm.duplicateVenueBlock(id) },
                onMirrorVenueBlockX = { id, refId -> vm.mirrorVenueBlockX(id, refId) },
                onMirrorVenueBlockZ = { id, refId -> vm.mirrorVenueBlockZ(id, refId) },
                onSpeakerPresetSelected = { vm.setSpeakerPreset(it) },
                onAddSpeakerSource = { vm.addSpeakerSource() },
                onRemoveSpeakerSource = { vm.removeSpeakerSource(it) },
                onActiveSpeakerSourceChanged = { vm.setActiveSpeakerSource(it) },
                onSpeakerSourceNameChanged = { id, name -> vm.setSpeakerSourceName(id, name) },
                onSpeakerSourceRoleChanged = { id, role -> vm.setSpeakerSourceRole(id, role) },
                onSpeakerSourceBrandChanged = { id, brand -> vm.setSpeakerSourceBrand(id, brand) },
                onSpeakerSourceSeriesChanged = { id, series -> vm.setSpeakerSourceSeries(id, series) },
                onSpeakerSourceModelChanged = { id, presetId -> vm.setSpeakerSourceModel(id, presetId) },
                onSpeakerSourceLinkChanged = { id, linkedId -> vm.setSpeakerSourceLink(id, linkedId) },
                onSpeakerSourceLinkMotionModeChanged = { id, mode -> vm.setSpeakerSourceLinkMotionMode(id, mode) },
                onToggleSpeakerSourceCollapsed = { vm.toggleSpeakerSourceCollapsed(it) },
                bundledCatalogVersion = bundledCatalogVersion,
                bundledCatalogPresetCount = bundledCatalogPresetCount,
                bundledCatalogPackageCount = bundledCatalogPackageCount,
                loadedCatalogVersion = loadedCatalogVersion,
                selectedSpeakerModelPackageId = selectedSpeakerModelPackageId,
                onSelectedSpeakerModelPackageChanged = { vm.setSelectedSpeakerModelPackage(it) },
                onSpeakerGainChanged = { id, db -> vm.setGain(id, db) },
                onSpeakerDelayChanged = { id, ms -> vm.setDelay(id, ms) },
                onSpeakerPolarityChanged = { id, inverted -> vm.setPolarity(id, inverted) },
                onSpeakerEqChanged = { id, bandHz, db -> vm.setEqBand(id, bandHz, db) },
                onSpeakerArrayElementsChanged = { id, n -> vm.setSpeakerArrayElements(id, n) },
                onSpeakerArraySpacingChanged = { id, spacing -> vm.setSpeakerArraySpacing(id, spacing) },
                onSpeakerArraySplayChanged = { id, splay -> vm.setSpeakerArraySplay(id, splay) },
                onSpeakerArraySplayAtChanged = { id, joint, splay -> vm.setSpeakerArraySplayAt(id, joint, splay) },
                onSpeakerArrayAimChanged = { id, aim -> vm.setSpeakerArrayAim(id, aim) },
                onSpeakerPanChanged = { id, pan -> vm.setSpeakerPan(id, pan) },
                onSpeakerPositionChanged = { id, x, y, z -> vm.setSpeakerPosition(id, x, y, z) },
                onSpeakerLabelChanged = { id, label -> vm.setSpeakerLabel(id, label) },
                onDuplicateSpeaker = { id -> vm.duplicateSpeaker(id) },
                onMirrorSpeakerX = { id, refId -> vm.mirrorSpeakerX(id, refId) },
                onMirrorSpeakerY = { id, refId -> vm.mirrorSpeakerY(id, refId) },
                onSpeakerArraySteerChanged = { id, steer -> vm.setSpeakerArraySteer(id, steer) },
                onSpeakerArrayEdgeTaperChanged = { id, taper -> vm.setSpeakerArrayEdgeTaper(id, taper) },
                onSpeakerModelPackageChanged = { id, packageId -> vm.setSpeakerModelPackage(id, packageId) },
                selectedSnapshotSlot = selectedSnapshotSlot,
                snapshotSlotNames = snapshotSlotNames,
                snapshotSavedAtMs = snapshotSavedAtMs,
                recoverySavedAtMs = recoverySavedAtMs,
                isSnapshotDirty = isSnapshotDirty,
                snapshotNameDraft = snapshotNameDraft,
                canUndo = canUndo,
                canRedo = canRedo,
                onUndoScene = {
                    val ok = vm.undoScene()
                    if (!ok) scope.launch { snackbarState.showSnackbar("Nothing to undo") }
                },
                onRedoScene = {
                    val ok = vm.redoScene()
                    if (!ok) scope.launch { snackbarState.showSnackbar("Nothing to redo") }
                },
                onSelectedSnapshotSlotChanged = { selectedSnapshotSlot = it },
                onSnapshotNameDraftChanged = { snapshotNameDraft = it },
                onRenameSnapshotSlot = { slot, name ->
                    val clean = name.trim().ifBlank { "Slot $slot" }.take(24)
                    snapshotSlotNames = snapshotSlotNames + (slot to clean)
                    activity.getSharedPreferences("droidacoustic_project", Context.MODE_PRIVATE)
                        .edit()
                        .putString("scene_snapshot_name_${slot}", clean)
                        .apply()
                    scope.launch { snackbarState.showSnackbar("Renamed slot $slot to '$clean'") }
                },
                onSaveScene = { slot ->
                    val json = vm.exportSceneJson()
                    val key = snapshotKeyFor(slot)
                    val nowMs = System.currentTimeMillis()
                    activity.getSharedPreferences("droidacoustic_project", Context.MODE_PRIVATE)
                        .edit()
                        .putString(key, json)
                        .putLong("scene_snapshot_savedAt_${slot}", nowMs)
                        .apply()
                    snapshotSavedAtMs = snapshotSavedAtMs + (slot to nowMs)
                    if (slot == selectedSnapshotSlot) isSnapshotDirty = false
                    val label = snapshotSlotNames[slot] ?: "Slot $slot"
                    scope.launch { snackbarState.showSnackbar("Saved '$label' ($slot)") }
                },
                onLoadScene = { slot ->
                    val prefs = activity.getSharedPreferences("droidacoustic_project", Context.MODE_PRIVATE)
                    val saved = prefs.getString(snapshotKeyFor(slot), null)
                        ?: prefs.getString(legacySnapshotKeyFor(slot), null)
                    if (saved.isNullOrBlank()) {
                        val label = snapshotSlotNames[slot] ?: "Slot $slot"
                        scope.launch { snackbarState.showSnackbar("No snapshot in '$label' ($slot)") }
                    } else {
                        val ok = vm.importSceneJson(saved)
                        val label = snapshotSlotNames[slot] ?: "Slot $slot"
                        if (ok && slot == selectedSnapshotSlot) isSnapshotDirty = false
                        scope.launch {
                            snackbarState.showSnackbar(
                                if (ok) "Loaded '$label' ($slot)" else (vm.lastImportError.value ?: "Failed to load snapshot")
                            )
                        }
                    }
                },
                onLoadLatestScene = {
                    val latest = snapshotSavedAtMs.maxByOrNull { it.value }?.key
                    if (latest == null) {
                        scope.launch { snackbarState.showSnackbar("No saved snapshots available") }
                    } else {
                        selectedSnapshotSlot = latest
                        val prefs = activity.getSharedPreferences("droidacoustic_project", Context.MODE_PRIVATE)
                        val saved = prefs.getString(snapshotKeyFor(latest), null)
                            ?: prefs.getString(legacySnapshotKeyFor(latest), null)
                        if (saved.isNullOrBlank()) {
                            scope.launch { snackbarState.showSnackbar("Latest snapshot missing") }
                        } else {
                            val ok = vm.importSceneJson(saved)
                            if (ok) isSnapshotDirty = false
                            val label = snapshotSlotNames[latest] ?: "Slot $latest"
                            scope.launch {
                                snackbarState.showSnackbar(
                                    if (ok) "Loaded latest '$label' ($latest)" else (vm.lastImportError.value ?: "Failed to load latest snapshot")
                                )
                            }
                        }
                    }
                },
                onCopySceneJson = {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val (label, json, msg) = when (currentDest) {
                        AppDestination.VENUE -> Triple("droidacoustic_venue_json", vm.exportVenueJson(), "Venue JSON copied to clipboard")
                        AppDestination.SPEAKERS -> Triple("droidacoustic_speaker_model_packages_json", vm.exportSpeakerModelPackagesJson(), "Speaker model packages JSON copied to clipboard")
                        AppDestination.REPORT -> Triple("droidacoustic_project_report_json", vm.exportProjectReportJson(), "Project report JSON copied to clipboard")
                        else -> Triple("droidacoustic_scene_json", vm.exportSceneJson(), "Scene JSON copied to clipboard")
                    }
                    clipboard.setPrimaryClip(ClipData.newPlainText(label, json))
                    scope.launch { snackbarState.showSnackbar(msg) }
                },
                onImportSceneJson = {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).coerceToText(activity)?.toString()
                    } else null
                    if (text.isNullOrBlank()) {
                        scope.launch { snackbarState.showSnackbar("Clipboard has no scene JSON") }
                    } else {
                        val okScene = vm.importSceneJson(text)
                        val okVenue = if (!okScene) vm.importVenueJson(text) else false
                        val okSpeakerLib = if (!okScene && !okVenue) vm.importSpeakerLibraryJson(text) else false
                        val okSpeakerModels = if (!okScene && !okVenue && !okSpeakerLib) vm.importSpeakerModelPackagesJson(text) else false
                        val okClfJson = if (!okScene && !okVenue && !okSpeakerLib && !okSpeakerModels) vm.importClfJsonData(text) else false
                        val okClf = if (!okScene && !okVenue && !okSpeakerLib && !okSpeakerModels && !okClfJson) vm.importClfText(text) else false
                        val ok = okScene || okVenue || okSpeakerLib || okSpeakerModels || okClfJson || okClf
                        if (ok) isSnapshotDirty = true
                        scope.launch {
                            snackbarState.showSnackbar(
                                when {
                                    okScene -> "Imported scene JSON from clipboard"
                                    okVenue -> "Imported venue JSON from clipboard"
                                    okSpeakerLib -> "Imported speaker library JSON from clipboard"
                                    okSpeakerModels -> "Imported speaker model packages from clipboard"
                                    okClfJson -> "Imported CLF polar data from clipboard"
                                    okClf -> "Imported CLF speaker preset from clipboard"
                                    else -> (vm.lastImportError.value ?: "Failed to import clipboard data")
                                }
                            )
                        }
                    }
                },
                onRecoverAutosave = {
                    val prefs = activity.getSharedPreferences("droidacoustic_project", Context.MODE_PRIVATE)
                    val saved = prefs.getString(recoveryKey, null) ?: prefs.getString(legacyRecoveryKey, null)
                    if (saved.isNullOrBlank()) {
                        scope.launch { snackbarState.showSnackbar("No autosave recovery available") }
                    } else {
                        val ok = vm.importSceneJson(saved)
                        if (ok) isSnapshotDirty = true
                        scope.launch {
                            snackbarState.showSnackbar(
                                if (ok) "Recovered autosave scene" else (vm.lastImportError.value ?: "Failed to recover autosave")
                            )
                        }
                    }
                },
                onDuplicateScene = { fromSlot, toSlot ->
                    val prefs = activity.getSharedPreferences("droidacoustic_project", Context.MODE_PRIVATE)
                    val data = prefs.getString(snapshotKeyFor(fromSlot), null)
                        ?: prefs.getString(legacySnapshotKeyFor(fromSlot), null)
                    if (data.isNullOrBlank()) {
                        val label = snapshotSlotNames[fromSlot] ?: "Slot $fromSlot"
                        scope.launch { snackbarState.showSnackbar("No snapshot in '$label' ($fromSlot)") }
                    } else {
                        val nowMs = System.currentTimeMillis()
                        prefs.edit()
                            .putString(snapshotKeyFor(toSlot), data)
                            .putLong("scene_snapshot_savedAt_${toSlot}", nowMs)
                            .apply()
                        snapshotSavedAtMs = snapshotSavedAtMs + (toSlot to nowMs)
                        val fromLabel = snapshotSlotNames[fromSlot] ?: "Slot $fromSlot"
                        val toLabel = snapshotSlotNames[toSlot] ?: "Slot $toSlot"
                        scope.launch { snackbarState.showSnackbar("Copied '$fromLabel' → '$toLabel'") }
                    }
                },
                onClearScene = { slot ->
                    activity.getSharedPreferences("droidacoustic_project", Context.MODE_PRIVATE)
                        .edit()
                        .remove(snapshotKeyFor(slot))
                        .remove(legacySnapshotKeyFor(slot))
                        .remove("scene_snapshot_savedAt_${slot}")
                        .apply()
                    snapshotSavedAtMs = snapshotSavedAtMs - slot
                    if (slot == selectedSnapshotSlot) isSnapshotDirty = true
                    val label = snapshotSlotNames[slot] ?: "Slot $slot"
                    scope.launch { snackbarState.showSnackbar("Cleared '$label' ($slot)") }
                },
                onBandSelected        = { vm.setBandHz(it) },
                onSignalLevelDbuChanged = { vm.setSignalLevelDbu(it) },
                onSignalTypeChanged = { vm.setSignalType(it) },
                onSignalBandwidthChanged = { vm.setSignalBandwidthOct(it) },
                onSignalResolutionChanged = { vm.setSignalResolution(it) },
                onSignalInterferenceChanged = { vm.setSignalInterferenceEnabled(it) },
                onSignalAutoCalculateChanged = { vm.setSignalAutoCalculate(it) },
                onSignalSplEnabledChanged = { vm.setSignalSplEnabled(it) },
                onSignalDispersionEnabledChanged = { vm.setSignalDispersionEnabled(it) },
                onSignalCoverageEnabledChanged = { vm.setSignalCoverageEnabled(it) },
                onSignalRecalculate = { vm.recalculateSignal() },
                onTemperatureChanged  = { vm.setTemperatureC(it) },
                onHumidityChanged     = { vm.setHumidityPct(it) },
                onReflectionOrderChanged = { vm.setReflectionOrder(it) },
                onAnalysisProfileChanged = { vm.setAnalysisProfile(it) },
                onAudienceScoreModeChanged = { audienceScoreMode = it },
                onShowHeatmapChanged = { showHeatmap = it },
                clfSourceStatus = clfSourceStatus,
                clfBinaryAssets = clfBinaryAssets,
                clfExternalAssets = clfExternalAssets,
                clfIngestionStats = clfIngestionStats,
                strictExtractedBinaryClfOnly = strictExtractedBinaryClfOnly,
                onStrictExtractedBinaryClfOnlyChanged = { vm.setStrictExtractedBinaryClfOnly(it) },
                roomMaterials         = roomMaterials,
                onFloorAbsChanged     = { vm.setFloorAbsorption(it) },
                onCeilingAbsChanged   = { vm.setCeilingAbsorption(it) },
                onWallAbsChanged      = { vm.setWallAbsorption(it) },
                onRoomHeightChanged   = { vm.setRoomHeight(it) },
                onRemoveAudiencePoint = { id -> vm.removeAudiencePoint(id) },
                onClearAudience       = { vm.clearAudience() },
                onRemoveSpeaker = { id -> vm.removeSpeaker(id) },
                onClearAll      = { vm.clearAll() }
            )

            // ─── 3D Viewport — fills all remaining space ──────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                FilamentSurface(
                    modifier   = Modifier.fillMaxSize(),
                    venueGeometry = venueGeometry,
                    audienceAreas = audienceAreas,
                    areaDraft     = areaDraft,
                    activeZoneType = activeZoneType,
                    activeZoneBaseHeightM = activeZoneBaseHeightM,
                    activeZoneRakeDeg = activeZoneRakeDeg,
                    activeZoneRakeDirectionDeg = activeZoneRakeDirectionDeg,
                    audience   = audience,
                    speakers   = speakers,
                    speakerModelPackages = speakerModelPackages,
                    heatmap    = if (showHeatmap && signalCoverageEnabled) heatmap else emptyList(),
                    listener   = listener,
                    onSpeakerMeshStatsChanged = { loaded, total ->
                        meshLoadedCount = loaded
                        meshTotalCount = total
                    },
                    onFloorTap = { x, z ->
                        when (currentDest) {
                            AppDestination.VENUE    -> vm.addVenueBlock(activeBlockType, x, z)
                            AppDestination.SPEAKERS -> vm.addSpeaker(x, z)
                            AppDestination.RESULTS  -> vm.moveListener(x, z)
                            else                    -> {}
                        }
                    }
                )
                val tapHint = when (currentDest) {
                    AppDestination.VENUE    -> "Tap floor to place selected block"
                    AppDestination.SPEAKERS -> "Tap floor to place speaker"
                    AppDestination.RESULTS  -> "Tap floor to move listener"
                    else                    -> null
                }
                if (tapHint != null) {
                    Text(
                        text     = tapHint,
                        style    = MaterialTheme.typography.labelSmall,
                        color    = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    )
                }
                if (!showHeatmap || !signalCoverageEnabled) {
                    Text(
                        text = if (!signalCoverageEnabled) "Coverage OFF" else "Heatmap hidden",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                    )
                }
                if (meshTotalCount > 0) {
                    Text(
                        text = "Mesh: $meshLoadedCount/$meshTotalCount loaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Navigation Rail
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AppNavigationRail(
    currentDest: AppDestination,
    onDestSelected: (AppDestination) -> Unit
) {
    NavigationRail {
        Spacer(Modifier.weight(1f))
        AppDestination.entries.forEach { dest ->
            NavigationRailItem(
                selected  = dest == currentDest,
                onClick   = { onDestSelected(dest) },
                icon      = {
                    Icon(
                        imageVector = when (dest) {
                            AppDestination.VENUE     -> Icons.Default.LocationOn
                            AppDestination.SPEAKERS  -> Icons.Default.Speaker
                            AppDestination.SPL       -> Icons.Default.GraphicEq
                            AppDestination.RESULTS   -> Icons.Default.BarChart
                            AppDestination.REPORT    -> Icons.Default.PictureAsPdf
                            AppDestination.SETTINGS  -> Icons.Default.Settings
                        },
                        contentDescription = dest.label
                    )
                },
                label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Properties Panel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PropertiesPanel(
    destination:   AppDestination,
    engineReady:   Boolean,
    isCalculating: Boolean,
    audience:        List<AudiencePoint>,
    audienceAreas:   List<AudienceArea>,
    areaDraft:       List<Pair<Float, Float>>,
    venueGeometry:   VenueGeometry,
    activeZoneType:  String,
    activeBlockType: String,
    activeZoneBaseHeightM: Float,
    activeZoneRakeDeg: Float,
    activeZoneRakeDirectionDeg: Float,
    speakers:        List<PlacedSpeaker>,
    speakerResults:  List<SpeakerResult>,
    dspMap:          Map<Int, SpeakerDsp>,
    selectedPresetId: String,
    speakerPresets: List<SpeakerPreset>,
    speakerSources: List<SpeakerSource>,
    activeSpeakerSourceId: Int?,
    speakerModelPackages: List<SpeakerModelPackage>,
    listener:        ListenerPos,
    heatmap:         List<HeatCell>,
    showHeatmap:     Boolean,
    combinedSplDb:   Float?,
    earlyReflections: List<EarlyReflection>,
    rt60Estimate:    Rt60Estimate?,
    stiEstimate:     StiEstimate?,
    selectedBandHz:  Int,
    signalLevelDbu: Float,
    signalType: String,
    signalBandwidthOct: Float,
    signalResolution: Int,
    signalInterferenceEnabled: Boolean,
    signalAutoCalculate: Boolean,
    signalSplEnabled: Boolean,
    signalDispersionEnabled: Boolean,
    signalCoverageEnabled: Boolean,
    highestSplDb: Float?,
    temperatureC:    Float,
    humidityPct:     Float,
    reflectionOrder: Int,
    analysisProfile: String,
    audienceScoreMode: String,
    onRunGpuTest:    () -> Unit,
    onUndoAreaVertex:      () -> Unit,
    onClearAreaDraft:      () -> Unit,
    onCloseAreaFromDraft:  () -> Unit,
    onVenueSizeChanged: (Float, Float) -> Unit,
    onVenueWallHeightChanged: (Float) -> Unit,
    onStageCenterChanged: (Float, Float) -> Unit,
    onStageSizeChanged: (Float, Float) -> Unit,
    onStageHeightChanged: (Float) -> Unit,
    onStageSlopeChanged: (Float) -> Unit,
    onActiveZoneTypeChanged: (String) -> Unit,
    onActiveBlockTypeChanged: (String) -> Unit,
    onActiveZoneBaseHeightChanged: (Float) -> Unit,
    onActiveZoneRakeChanged: (Float) -> Unit,
    onActiveZoneRakeDirectionChanged: (Float) -> Unit,
    onAudienceAreaBaseHeightChanged: (Int, Float) -> Unit,
    onAudienceAreaRakeChanged: (Int, Float) -> Unit,
    onAudienceAreaRakeDirectionChanged: (Int, Float) -> Unit,
    onAudienceAreaNameChanged: (Int, String) -> Unit,
    onAudienceAreaTypeChanged: (Int, String) -> Unit,
    onAudienceAreaRotationChanged: (Int, Float) -> Unit,
    onAudienceAreaEdgeLengthChanged: (Int, Int, Float) -> Unit,
    onDuplicateAudienceArea: (Int) -> Unit,
    onMirrorAudienceAreaX: (Int, Int?) -> Unit,
    onMirrorAudienceAreaZ: (Int, Int?) -> Unit,
    onAudienceAreaLinkChanged: (Int, Int?) -> Unit,
    onRemoveAudienceArea: (Int) -> Unit,
    onCreateBlockFromArea: (Int, Boolean) -> Unit,
    onAddVenueBlock: (String) -> Unit,
    onRemoveVenueBlock: (Int) -> Unit,
    onVenueBlockTypeChanged: (Int, String) -> Unit,
    onVenueBlockCenterChanged: (Int, Float, Float) -> Unit,
    onVenueBlockSizeChanged: (Int, Float, Float) -> Unit,
    onVenueBlockHeightChanged: (Int, Float) -> Unit,
    onVenueBlockThicknessChanged: (Int, Float) -> Unit,
    onVenueBlockSlopeChanged: (Int, Float) -> Unit,
    onVenueBlockRotationChanged: (Int, Float) -> Unit,
    onVenueBlockLabelChanged: (Int, String) -> Unit,
    onDuplicateVenueBlock: (Int) -> Unit,
    onMirrorVenueBlockX: (Int, Int?) -> Unit,
    onMirrorVenueBlockZ: (Int, Int?) -> Unit,
    onSpeakerPresetSelected: (String) -> Unit,
    onAddSpeakerSource: () -> Unit,
    onRemoveSpeakerSource: (Int) -> Unit,
    onActiveSpeakerSourceChanged: (Int?) -> Unit,
    onSpeakerSourceNameChanged: (Int, String) -> Unit,
    onSpeakerSourceRoleChanged: (Int, String) -> Unit,
    onSpeakerSourceBrandChanged: (Int, String) -> Unit,
    onSpeakerSourceSeriesChanged: (Int, String) -> Unit,
    onSpeakerSourceModelChanged: (Int, String?) -> Unit,
    onSpeakerSourceLinkChanged: (Int, Int?) -> Unit,
    onSpeakerSourceLinkMotionModeChanged: (Int, String) -> Unit,
    onToggleSpeakerSourceCollapsed: (Int) -> Unit,
    bundledCatalogVersion: Int?,
    bundledCatalogPresetCount: Int,
    bundledCatalogPackageCount: Int,
    loadedCatalogVersion: Int?,
    selectedSpeakerModelPackageId: String,
    onSelectedSpeakerModelPackageChanged: (String) -> Unit,
    onSpeakerGainChanged:  (Int, Float) -> Unit,
    onSpeakerDelayChanged: (Int, Float) -> Unit,
    onSpeakerPolarityChanged: (Int, Boolean) -> Unit,
    onSpeakerEqChanged:    (Int, Int, Float) -> Unit,
    onSpeakerArrayElementsChanged: (Int, Int) -> Unit,
    onSpeakerArraySpacingChanged: (Int, Float) -> Unit,
    onSpeakerArraySplayChanged: (Int, Float) -> Unit,
    onSpeakerArraySplayAtChanged: (Int, Int, Float) -> Unit,
    onSpeakerArrayAimChanged: (Int, Float) -> Unit,
    onSpeakerPanChanged: (Int, Float) -> Unit,
    onSpeakerPositionChanged: (Int, Float, Float, Float) -> Unit,
    onSpeakerLabelChanged: (Int, String) -> Unit,
    onDuplicateSpeaker: (Int) -> Unit,
    onMirrorSpeakerX: (Int, Int?) -> Unit,
    onMirrorSpeakerY: (Int, Int?) -> Unit,
    onSpeakerArraySteerChanged: (Int, Float) -> Unit,
    onSpeakerArrayEdgeTaperChanged: (Int, Float) -> Unit,
    onSpeakerModelPackageChanged: (Int, String) -> Unit,
    selectedSnapshotSlot: String,
    snapshotSlotNames: Map<String, String>,
    snapshotSavedAtMs: Map<String, Long>,
    recoverySavedAtMs: Long?,
    isSnapshotDirty: Boolean,
    snapshotNameDraft: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndoScene: () -> Unit,
    onRedoScene: () -> Unit,
    onSelectedSnapshotSlotChanged: (String) -> Unit,
    onSnapshotNameDraftChanged: (String) -> Unit,
    onRenameSnapshotSlot: (String, String) -> Unit,
    onSaveScene: (String) -> Unit,
    onLoadScene: (String) -> Unit,
    onLoadLatestScene: () -> Unit,
    onCopySceneJson: () -> Unit,
    onImportSceneJson: () -> Unit,
    onRecoverAutosave: () -> Unit,
    onDuplicateScene: (String, String) -> Unit,
    onClearScene: (String) -> Unit,
    onBandSelected:        (Int) -> Unit,
    onSignalLevelDbuChanged: (Float) -> Unit,
    onSignalTypeChanged: (String) -> Unit,
    onSignalBandwidthChanged: (Float) -> Unit,
    onSignalResolutionChanged: (Int) -> Unit,
    onSignalInterferenceChanged: (Boolean) -> Unit,
    onSignalAutoCalculateChanged: (Boolean) -> Unit,
    onSignalSplEnabledChanged: (Boolean) -> Unit,
    onSignalDispersionEnabledChanged: (Boolean) -> Unit,
    onSignalCoverageEnabledChanged: (Boolean) -> Unit,
    onSignalRecalculate: () -> Unit,
    onTemperatureChanged:  (Float) -> Unit,
    onHumidityChanged:     (Float) -> Unit,
    onReflectionOrderChanged: (Int) -> Unit,
    onAnalysisProfileChanged: (String) -> Unit,
    onAudienceScoreModeChanged: (String) -> Unit,
    onShowHeatmapChanged: (Boolean) -> Unit,
    clfSourceStatus: Map<String, String>,
    clfBinaryAssets: Map<String, List<String>>,
    clfExternalAssets: Map<String, List<String>>,
    clfIngestionStats: ClfIngestionStats,
    strictExtractedBinaryClfOnly: Boolean,
    onStrictExtractedBinaryClfOnlyChanged: (Boolean) -> Unit,
    roomMaterials:         RoomMaterials,
    onFloorAbsChanged:     (Float) -> Unit,
    onCeilingAbsChanged:   (Float) -> Unit,
    onWallAbsChanged:      (Float) -> Unit,
    onRoomHeightChanged:   (Float) -> Unit,
    onRemoveAudiencePoint: (Int) -> Unit,
    onClearAudience:       () -> Unit,
    onRemoveSpeaker: (Int) -> Unit,
    onClearAll:      () -> Unit
) {
    Column(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text  = destination.label,
            style = MaterialTheme.typography.titleMedium
        )
        HorizontalDivider()

        when (destination) {
            AppDestination.VENUE -> VenuePanel(
                audience = audience,
                audienceAreas = audienceAreas,
                areaDraft = areaDraft,
                venueGeometry = venueGeometry,
                activeZoneType = activeZoneType,
                activeBlockType = activeBlockType,
                activeZoneBaseHeightM = activeZoneBaseHeightM,
                activeZoneRakeDeg = activeZoneRakeDeg,
                activeZoneRakeDirectionDeg = activeZoneRakeDirectionDeg,
                onUndoAreaVertex = onUndoAreaVertex,
                onClearAreaDraft = onClearAreaDraft,
                onCloseAreaFromDraft = onCloseAreaFromDraft,
                onVenueSizeChanged = onVenueSizeChanged,
                onVenueWallHeightChanged = onVenueWallHeightChanged,
                onStageCenterChanged = onStageCenterChanged,
                onStageSizeChanged = onStageSizeChanged,
                onStageHeightChanged = onStageHeightChanged,
                onStageSlopeChanged = onStageSlopeChanged,
                onActiveZoneTypeChanged = onActiveZoneTypeChanged,
                onActiveBlockTypeChanged = onActiveBlockTypeChanged,
                onActiveZoneBaseHeightChanged = onActiveZoneBaseHeightChanged,
                onActiveZoneRakeChanged = onActiveZoneRakeChanged,
                onActiveZoneRakeDirectionChanged = onActiveZoneRakeDirectionChanged,
                onAudienceAreaBaseHeightChanged = onAudienceAreaBaseHeightChanged,
                onAudienceAreaRakeChanged = onAudienceAreaRakeChanged,
                onAudienceAreaRakeDirectionChanged = onAudienceAreaRakeDirectionChanged,
                onAudienceAreaNameChanged = onAudienceAreaNameChanged,
                onAudienceAreaTypeChanged = onAudienceAreaTypeChanged,
                onAudienceAreaRotationChanged = onAudienceAreaRotationChanged,
                onAudienceAreaEdgeLengthChanged = onAudienceAreaEdgeLengthChanged,
                onDuplicateAudienceArea = onDuplicateAudienceArea,
                onMirrorAudienceAreaX = onMirrorAudienceAreaX,
                onMirrorAudienceAreaZ = onMirrorAudienceAreaZ,
                onAudienceAreaLinkChanged = onAudienceAreaLinkChanged,
                onRemoveAudienceArea = onRemoveAudienceArea,
                onCreateBlockFromArea = onCreateBlockFromArea,
                onAddVenueBlock = onAddVenueBlock,
                onRemoveVenueBlock = onRemoveVenueBlock,
                onVenueBlockTypeChanged = onVenueBlockTypeChanged,
                onVenueBlockCenterChanged = onVenueBlockCenterChanged,
                onVenueBlockSizeChanged = onVenueBlockSizeChanged,
                onVenueBlockHeightChanged = onVenueBlockHeightChanged,
                onVenueBlockThicknessChanged = onVenueBlockThicknessChanged,
                onVenueBlockSlopeChanged = onVenueBlockSlopeChanged,
                onVenueBlockRotationChanged = onVenueBlockRotationChanged,
                onVenueBlockLabelChanged = onVenueBlockLabelChanged,
                onDuplicateVenueBlock = onDuplicateVenueBlock,
                onMirrorVenueBlockX = onMirrorVenueBlockX,
                onMirrorVenueBlockZ = onMirrorVenueBlockZ,
                onRemoveAudiencePoint = onRemoveAudiencePoint,
                onClearAudience = onClearAudience
            )
            AppDestination.SPEAKERS -> SpeakersPanel(
                engineReady   = engineReady,
                isCalculating = isCalculating,
                speakers        = speakers,
                speakerResults   = speakerResults,
                dspMap          = dspMap,
                selectedPresetId = selectedPresetId,
                presets = speakerPresets,
                speakerSources = speakerSources,
                activeSpeakerSourceId = activeSpeakerSourceId,
                speakerModelPackages = speakerModelPackages,
                onSpeakerPresetSelected = onSpeakerPresetSelected,
                onAddSpeakerSource = onAddSpeakerSource,
                onRemoveSpeakerSource = onRemoveSpeakerSource,
                onActiveSpeakerSourceChanged = onActiveSpeakerSourceChanged,
                onSpeakerSourceNameChanged = onSpeakerSourceNameChanged,
                onSpeakerSourceRoleChanged = onSpeakerSourceRoleChanged,
                onSpeakerSourceBrandChanged = onSpeakerSourceBrandChanged,
                onSpeakerSourceSeriesChanged = onSpeakerSourceSeriesChanged,
                onSpeakerSourceModelChanged = onSpeakerSourceModelChanged,
                onSpeakerSourceLinkChanged = onSpeakerSourceLinkChanged,
                onSpeakerSourceLinkMotionModeChanged = onSpeakerSourceLinkMotionModeChanged,
                onToggleSpeakerSourceCollapsed = onToggleSpeakerSourceCollapsed,
                bundledCatalogVersion = bundledCatalogVersion,
                bundledCatalogPresetCount = bundledCatalogPresetCount,
                bundledCatalogPackageCount = bundledCatalogPackageCount,
                loadedCatalogVersion = loadedCatalogVersion,
                selectedSpeakerModelPackageId = selectedSpeakerModelPackageId,
                onSelectedSpeakerModelPackageChanged = onSelectedSpeakerModelPackageChanged,
                onSpeakerGainChanged = onSpeakerGainChanged,
                onSpeakerDelayChanged = onSpeakerDelayChanged,
                onSpeakerPolarityChanged = onSpeakerPolarityChanged,
                onSpeakerEqChanged = onSpeakerEqChanged,
                onSpeakerArrayElementsChanged = onSpeakerArrayElementsChanged,
                onSpeakerArraySpacingChanged = onSpeakerArraySpacingChanged,
                onSpeakerArraySplayChanged = onSpeakerArraySplayChanged,
                onSpeakerArraySplayAtChanged = onSpeakerArraySplayAtChanged,
                onSpeakerArrayAimChanged = onSpeakerArrayAimChanged,
                onSpeakerPanChanged = onSpeakerPanChanged,
                onSpeakerPositionChanged = onSpeakerPositionChanged,
                onSpeakerLabelChanged = onSpeakerLabelChanged,
                onDuplicateSpeaker = onDuplicateSpeaker,
                onMirrorSpeakerX = onMirrorSpeakerX,
                onMirrorSpeakerY = onMirrorSpeakerY,
                onSpeakerArraySteerChanged = onSpeakerArraySteerChanged,
                onSpeakerArrayEdgeTaperChanged = onSpeakerArrayEdgeTaperChanged,
                onSpeakerModelPackageChanged = onSpeakerModelPackageChanged,
                selectedSnapshotSlot = selectedSnapshotSlot,
                snapshotSlotNames = snapshotSlotNames,
                snapshotSavedAtMs = snapshotSavedAtMs,
                recoverySavedAtMs = recoverySavedAtMs,
                isSnapshotDirty = isSnapshotDirty,
                snapshotNameDraft = snapshotNameDraft,
                canUndo = canUndo,
                canRedo = canRedo,
                onUndoScene = onUndoScene,
                onRedoScene = onRedoScene,
                onSelectedSnapshotSlotChanged = onSelectedSnapshotSlotChanged,
                onSnapshotNameDraftChanged = onSnapshotNameDraftChanged,
                onRenameSnapshotSlot = onRenameSnapshotSlot,
                onSaveScene = onSaveScene,
                onLoadScene = onLoadScene,
                onLoadLatestScene = onLoadLatestScene,
                onCopySceneJson = onCopySceneJson,
                onImportSceneJson = onImportSceneJson,
                onRecoverAutosave = onRecoverAutosave,
                onDuplicateScene = onDuplicateScene,
                onClearScene = onClearScene,
                clfSourceStatus = clfSourceStatus,
                clfBinaryAssets = clfBinaryAssets,
                clfExternalAssets = clfExternalAssets,
                onRunGpuTest    = onRunGpuTest,
                onRemoveSpeaker = onRemoveSpeaker,
                onClearAll      = onClearAll
            )
            AppDestination.SPL -> SignalPanel(
                selectedBandHz = selectedBandHz,
                signalLevelDbu = signalLevelDbu,
                signalType = signalType,
                signalBandwidthOct = signalBandwidthOct,
                signalResolution = signalResolution,
                signalInterferenceEnabled = signalInterferenceEnabled,
                signalAutoCalculate = signalAutoCalculate,
                signalSplEnabled = signalSplEnabled,
                signalDispersionEnabled = signalDispersionEnabled,
                signalCoverageEnabled = signalCoverageEnabled,
                highestSplDb = highestSplDb,
                onBandSelected = onBandSelected,
                onSignalLevelDbuChanged = onSignalLevelDbuChanged,
                onSignalTypeChanged = onSignalTypeChanged,
                onSignalBandwidthChanged = onSignalBandwidthChanged,
                onSignalResolutionChanged = onSignalResolutionChanged,
                onSignalInterferenceChanged = onSignalInterferenceChanged,
                onSignalAutoCalculateChanged = onSignalAutoCalculateChanged,
                onSignalSplEnabledChanged = onSignalSplEnabledChanged,
                onSignalDispersionEnabledChanged = onSignalDispersionEnabledChanged,
                onSignalCoverageEnabledChanged = onSignalCoverageEnabledChanged,
                onSignalRecalculate = onSignalRecalculate
            )
            AppDestination.RESULTS -> ResultsPanel(
                speakers = speakerResults,
                listener = listener,
                combinedSplDb = combinedSplDb,
                earlyReflections = earlyReflections,
                rt60Estimate = rt60Estimate,
                stiEstimate = stiEstimate,
                heatmap = heatmap,
                audienceScoreMode = audienceScoreMode,
                reflectionOrder = reflectionOrder,
                analysisProfile = analysisProfile
            )
            AppDestination.REPORT -> ReportPanel(
                combinedSplDb = combinedSplDb,
                rt60Estimate = rt60Estimate,
                stiEstimate = stiEstimate,
                speakerCount = speakers.size,
                audiencePointCount = audience.size,
                audienceAreaCount = audienceAreas.size,
                onCopyReportJson = onCopySceneJson,
                onPasteDataJson = onImportSceneJson
            )
            AppDestination.SETTINGS -> SettingsPanel(
                selectedBandHz = selectedBandHz,
                temperatureC = temperatureC,
                humidityPct = humidityPct,
                onBandSelected = onBandSelected,
                onTemperatureChanged = onTemperatureChanged,
                onHumidityChanged = onHumidityChanged,
                reflectionOrder = reflectionOrder,
                onReflectionOrderChanged = onReflectionOrderChanged,
                analysisProfile = analysisProfile,
                onAnalysisProfileChanged = onAnalysisProfileChanged,
                roomMaterials = roomMaterials,
                onFloorAbsChanged = onFloorAbsChanged,
                onCeilingAbsChanged = onCeilingAbsChanged,
                onWallAbsChanged = onWallAbsChanged,
                onRoomHeightChanged = onRoomHeightChanged,
                selectedSnapshotSlot = selectedSnapshotSlot,
                snapshotSlotNames = snapshotSlotNames,
                snapshotSavedAtMs = snapshotSavedAtMs,
                recoverySavedAtMs = recoverySavedAtMs,
                isSnapshotDirty = isSnapshotDirty,
                snapshotNameDraft = snapshotNameDraft,
                canUndo = canUndo,
                canRedo = canRedo,
                onUndoScene = onUndoScene,
                onRedoScene = onRedoScene,
                onSelectedSnapshotSlotChanged = onSelectedSnapshotSlotChanged,
                onSnapshotNameDraftChanged = onSnapshotNameDraftChanged,
                onRenameSnapshotSlot = onRenameSnapshotSlot,
                onSaveScene = onSaveScene,
                onLoadScene = onLoadScene,
                onLoadLatestScene = onLoadLatestScene,
                onCopySceneJson = onCopySceneJson,
                onImportSceneJson = onImportSceneJson,
                onRecoverAutosave = onRecoverAutosave,
                onDuplicateScene = onDuplicateScene,
                onClearScene = onClearScene,
                audienceScoreMode = audienceScoreMode,
                onAudienceScoreModeChanged = onAudienceScoreModeChanged,
                showHeatmap = showHeatmap,
                onShowHeatmapChanged = onShowHeatmapChanged,
                clfIngestionStats = clfIngestionStats,
                strictExtractedBinaryClfOnly = strictExtractedBinaryClfOnly,
                onStrictExtractedBinaryClfOnlyChanged = onStrictExtractedBinaryClfOnlyChanged
            )
            else -> PlaceholderPanel(name = destination.label)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Panel implementations — Phase 0 stubs, replaced incrementally per phase
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VenuePanel(
    audience: List<AudiencePoint>,
    audienceAreas: List<AudienceArea>,
    areaDraft: List<Pair<Float, Float>>,
    venueGeometry: VenueGeometry,
    activeZoneType: String,
    activeBlockType: String,
    activeZoneBaseHeightM: Float,
    activeZoneRakeDeg: Float,
    activeZoneRakeDirectionDeg: Float,
    onUndoAreaVertex: () -> Unit,
    onClearAreaDraft: () -> Unit,
    onCloseAreaFromDraft: () -> Unit,
    onVenueSizeChanged: (Float, Float) -> Unit,
    onVenueWallHeightChanged: (Float) -> Unit,
    onStageCenterChanged: (Float, Float) -> Unit,
    onStageSizeChanged: (Float, Float) -> Unit,
    onStageHeightChanged: (Float) -> Unit,
    onStageSlopeChanged: (Float) -> Unit,
    onActiveZoneTypeChanged: (String) -> Unit,
    onActiveBlockTypeChanged: (String) -> Unit,
    onActiveZoneBaseHeightChanged: (Float) -> Unit,
    onActiveZoneRakeChanged: (Float) -> Unit,
    onActiveZoneRakeDirectionChanged: (Float) -> Unit,
    onAudienceAreaBaseHeightChanged: (Int, Float) -> Unit,
    onAudienceAreaRakeChanged: (Int, Float) -> Unit,
    onAudienceAreaRakeDirectionChanged: (Int, Float) -> Unit,
    onAudienceAreaNameChanged: (Int, String) -> Unit,
    onAudienceAreaTypeChanged: (Int, String) -> Unit,
    onAudienceAreaRotationChanged: (Int, Float) -> Unit,
    onAudienceAreaEdgeLengthChanged: (Int, Int, Float) -> Unit,
    onDuplicateAudienceArea: (Int) -> Unit,
    onMirrorAudienceAreaX: (Int, Int?) -> Unit,
    onMirrorAudienceAreaZ: (Int, Int?) -> Unit,
    onAudienceAreaLinkChanged: (Int, Int?) -> Unit,
    onRemoveAudienceArea: (Int) -> Unit,
    onCreateBlockFromArea: (Int, Boolean) -> Unit,
    onAddVenueBlock: (String) -> Unit,
    onRemoveVenueBlock: (Int) -> Unit,
    onVenueBlockTypeChanged: (Int, String) -> Unit,
    onVenueBlockCenterChanged: (Int, Float, Float) -> Unit,
    onVenueBlockSizeChanged: (Int, Float, Float) -> Unit,
    onVenueBlockHeightChanged: (Int, Float) -> Unit,
    onVenueBlockThicknessChanged: (Int, Float) -> Unit,
    onVenueBlockSlopeChanged: (Int, Float) -> Unit,
    onVenueBlockRotationChanged: (Int, Float) -> Unit,
    onVenueBlockLabelChanged: (Int, String) -> Unit,
    onDuplicateVenueBlock: (Int) -> Unit,
    onMirrorVenueBlockX: (Int, Int?) -> Unit,
    onMirrorVenueBlockZ: (Int, Int?) -> Unit,
    onRemoveAudiencePoint: (Int) -> Unit,
    onClearAudience: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Block placement type", style = MaterialTheme.typography.labelMedium)
            val blockButtons = listOf(
                "SEATING_BANK" to "Seating",
                "BALCONY" to "Balcony",
                "STAGE" to "Stage",
                "OBSTACLE" to "Obstacle",
                "WALL" to "Wall"
            )
            blockButtons.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { (type, label) ->
                        Button(
                            onClick = { onActiveBlockTypeChanged(type) },
                            enabled = activeBlockType != type,
                            modifier = Modifier.weight(1f)
                        ) { Text(label) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text(
                "Current: ${activeBlockType.replace('_', ' ')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Tap in 3D view to place a block of this type.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    var venueExpanded by remember { mutableStateOf(true) }
    var stageExpanded by remember { mutableStateOf(true) }
    val blockExpanded = remember { mutableStateMapOf<Int, Boolean>() }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { venueExpanded = !venueExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Venue geometry", style = MaterialTheme.typography.labelMedium)
                    Icon(
                        imageVector = if (venueExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (venueExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (venueExpanded) {
                    Text("Width: ${venueGeometry.widthM.f1} m", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = venueGeometry.widthM,
                        onValueChange = { onVenueSizeChanged(it, venueGeometry.depthM) },
                        valueRange = 8f..120f
                    )

                    Text("Depth: ${venueGeometry.depthM.f1} m", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = venueGeometry.depthM,
                        onValueChange = { onVenueSizeChanged(venueGeometry.widthM, it) },
                        valueRange = 8f..120f
                    )

                    Text("Wall/Ceiling height: ${venueGeometry.wallHeightM.f1} m", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = venueGeometry.wallHeightM,
                        onValueChange = onVenueWallHeightChanged,
                        valueRange = 3f..30f
                    )
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { stageExpanded = !stageExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Stage geometry", style = MaterialTheme.typography.labelMedium)
                    Icon(
                        imageVector = if (stageExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (stageExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (stageExpanded) {
                    val maxX = (venueGeometry.widthM * 0.5f).coerceAtLeast(4f)
                    val maxZ = (venueGeometry.depthM * 0.5f).coerceAtLeast(4f)

                    Text("Center X: ${venueGeometry.stageCenterX.f1} m", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = venueGeometry.stageCenterX,
                        onValueChange = { onStageCenterChanged(it, venueGeometry.stageCenterZ) },
                        valueRange = -maxX..maxX
                    )

                    Text("Center Z: ${venueGeometry.stageCenterZ.f1} m", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = venueGeometry.stageCenterZ,
                        onValueChange = { onStageCenterChanged(venueGeometry.stageCenterX, it) },
                        valueRange = -maxZ..maxZ
                    )

                    Text("Stage width: ${venueGeometry.stageWidthM.f1} m", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = venueGeometry.stageWidthM,
                        onValueChange = { onStageSizeChanged(it, venueGeometry.stageDepthM) },
                        valueRange = 1f..40f
                    )

                    Text("Stage depth: ${venueGeometry.stageDepthM.f1} m", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = venueGeometry.stageDepthM,
                        onValueChange = { onStageSizeChanged(venueGeometry.stageWidthM, it) },
                        valueRange = 1f..30f
                    )

                    Text("Stage height: ${venueGeometry.stageHeightM.f1} m", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = venueGeometry.stageHeightM,
                        onValueChange = onStageHeightChanged,
                        valueRange = 0f..8f
                    )

                    Text("Stage slope: ${venueGeometry.stageSlopeDeg.f1}°", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = venueGeometry.stageSlopeDeg,
                        onValueChange = onStageSlopeChanged,
                        valueRange = -12f..12f
                    )
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Placed blocks", style = MaterialTheme.typography.labelMedium)
                if (venueGeometry.blocks.isEmpty()) {
                    Text(
                        "No blocks yet. Use Block placement type and tap in 3D view.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    venueGeometry.blocks.forEach { block ->
                        val isExpanded = blockExpanded[block.id] ?: false
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { blockExpanded[block.id] = !isExpanded },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(block.label, style = MaterialTheme.typography.labelLarge)
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                if (isExpanded) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        TextButton(onClick = { onDuplicateVenueBlock(block.id) }, modifier = Modifier.weight(1f)) { Text("Dup") }
                                        TextButton(onClick = { onMirrorVenueBlockX(block.id, null) }, modifier = Modifier.weight(1f)) { Text("Mirror X") }
                                        TextButton(onClick = { onMirrorVenueBlockZ(block.id, null) }, modifier = Modifier.weight(1f)) { Text("Mirror Y") }
                                        TextButton(onClick = { onRemoveVenueBlock(block.id) }, modifier = Modifier.weight(1f)) { Text("Remove") }
                                    }

                                    val blockTypeButtons = listOf(
                                        "STAGE" to "Stage",
                                        "SEATING_BANK" to "Seating",
                                        "BALCONY" to "Balcony",
                                        "OBSTACLE" to "Obstacle",
                                        "WALL" to "Wall"
                                    )
                                    blockTypeButtons.chunked(3).forEach { typeRow ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                            typeRow.forEach { (type, label) ->
                                                Button(
                                                    onClick = { onVenueBlockTypeChanged(block.id, type) },
                                                    enabled = block.type != type,
                                                    modifier = Modifier.weight(1f)
                                                ) { Text(label) }
                                            }
                                            repeat(3 - typeRow.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }

                                    val maxXb = (venueGeometry.widthM * 0.5f).coerceAtLeast(4f)
                                    val maxZb = (venueGeometry.depthM * 0.5f).coerceAtLeast(4f)

                                    Text("X: ${block.centerX.f1} m", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = block.centerX,
                                        onValueChange = { onVenueBlockCenterChanged(block.id, it, block.centerZ) },
                                        valueRange = -maxXb..maxXb
                                    )
                                    Text("Z: ${block.centerZ.f1} m", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = block.centerZ,
                                        onValueChange = { onVenueBlockCenterChanged(block.id, block.centerX, it) },
                                        valueRange = -maxZb..maxZb
                                    )
                                    Text("Width: ${block.widthM.f1} m", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = block.widthM,
                                        onValueChange = { onVenueBlockSizeChanged(block.id, it, block.depthM) },
                                        valueRange = 0.2f..40f
                                    )
                                    Text("Depth: ${block.depthM.f1} m", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = block.depthM,
                                        onValueChange = { onVenueBlockSizeChanged(block.id, block.widthM, it) },
                                        valueRange = 0.2f..40f
                                    )
                                    Text("Height: ${block.blockHeightM.f1} m", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = block.blockHeightM,
                                        onValueChange = { onVenueBlockThicknessChanged(block.id, it) },
                                        valueRange = 0.1f..20f
                                    )
                                    Text("Base height: ${block.heightM.f1} m", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = block.heightM,
                                        onValueChange = { onVenueBlockHeightChanged(block.id, it) },
                                        valueRange = 0f..20f
                                    )
                                    Text("Rake: ${block.slopeDeg.f1}°", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = block.slopeDeg,
                                        onValueChange = { onVenueBlockSlopeChanged(block.id, it) },
                                        valueRange = -20f..20f
                                    )
                                    Text("Rotation: ${block.rotationDeg.f1}°", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = block.rotationDeg,
                                        onValueChange = { onVenueBlockRotationChanged(block.id, it) },
                                        valueRange = 0f..360f
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (audience.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Button(onClick = onClearAudience, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Clear, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Clear Audience (${audience.size})")
            }
        }
        Spacer(Modifier.height(6.dp))
        if (audience.isEmpty()) {
            Text(
                "No audience points yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            audience.forEach { p ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.labelLarge)
                            Text(
                                "(${p.x.f1} m, ${p.z.f1} m, y=${p.earHeightM.f1} m)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onRemoveAudiencePoint(p.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove audience point")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeakersPanel(
    engineReady:   Boolean,
    isCalculating: Boolean,
    speakers:        List<PlacedSpeaker>,
    speakerResults:  List<SpeakerResult>,
    dspMap:          Map<Int, SpeakerDsp>,
    selectedPresetId: String,
    selectedSpeakerModelPackageId: String,
    presets:         List<SpeakerPreset>,
    speakerSources: List<SpeakerSource>,
    activeSpeakerSourceId: Int?,
    speakerModelPackages: List<SpeakerModelPackage>,
    onSpeakerPresetSelected: (String) -> Unit,
    onAddSpeakerSource: () -> Unit,
    onRemoveSpeakerSource: (Int) -> Unit,
    onActiveSpeakerSourceChanged: (Int?) -> Unit,
    onSpeakerSourceNameChanged: (Int, String) -> Unit,
    onSpeakerSourceRoleChanged: (Int, String) -> Unit,
    onSpeakerSourceBrandChanged: (Int, String) -> Unit,
    onSpeakerSourceSeriesChanged: (Int, String) -> Unit,
    onSpeakerSourceModelChanged: (Int, String?) -> Unit,
    onSpeakerSourceLinkChanged: (Int, Int?) -> Unit,
    onSpeakerSourceLinkMotionModeChanged: (Int, String) -> Unit,
    onToggleSpeakerSourceCollapsed: (Int) -> Unit,
    bundledCatalogVersion: Int?,
    bundledCatalogPresetCount: Int,
    bundledCatalogPackageCount: Int,
    loadedCatalogVersion: Int?,
    onSelectedSpeakerModelPackageChanged: (String) -> Unit,
    onSpeakerGainChanged: (Int, Float) -> Unit,
    onSpeakerDelayChanged: (Int, Float) -> Unit,
    onSpeakerPolarityChanged: (Int, Boolean) -> Unit,
    onSpeakerEqChanged: (Int, Int, Float) -> Unit,
    onSpeakerArrayElementsChanged: (Int, Int) -> Unit,
    onSpeakerArraySpacingChanged: (Int, Float) -> Unit,
    onSpeakerArraySplayChanged: (Int, Float) -> Unit,
    onSpeakerArraySplayAtChanged: (Int, Int, Float) -> Unit,
    onSpeakerArrayAimChanged: (Int, Float) -> Unit,
    onSpeakerPanChanged: (Int, Float) -> Unit,
    onSpeakerPositionChanged: (Int, Float, Float, Float) -> Unit,
    onSpeakerLabelChanged: (Int, String) -> Unit,
    onDuplicateSpeaker: (Int) -> Unit,
    onMirrorSpeakerX: (Int, Int?) -> Unit,
    onMirrorSpeakerY: (Int, Int?) -> Unit,
    onSpeakerArraySteerChanged: (Int, Float) -> Unit,
    onSpeakerArrayEdgeTaperChanged: (Int, Float) -> Unit,
    onSpeakerModelPackageChanged: (Int, String) -> Unit,
    selectedSnapshotSlot: String,
    snapshotSlotNames: Map<String, String>,
    snapshotSavedAtMs: Map<String, Long>,
    recoverySavedAtMs: Long?,
    isSnapshotDirty: Boolean,
    snapshotNameDraft: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndoScene: () -> Unit,
    onRedoScene: () -> Unit,
    onSelectedSnapshotSlotChanged: (String) -> Unit,
    onSnapshotNameDraftChanged: (String) -> Unit,
    onRenameSnapshotSlot: (String, String) -> Unit,
    onSaveScene: (String) -> Unit,
    onLoadScene: (String) -> Unit,
    onLoadLatestScene: () -> Unit,
    onCopySceneJson: () -> Unit,
    onImportSceneJson: () -> Unit,
    onRecoverAutosave: () -> Unit,
    onDuplicateScene: (String, String) -> Unit,
    onClearScene: (String) -> Unit,
    clfSourceStatus: Map<String, String>,
    clfBinaryAssets: Map<String, List<String>>,
    clfExternalAssets: Map<String, List<String>>,
    onRunGpuTest:    () -> Unit,
    onRemoveSpeaker: (Int) -> Unit,
    onClearAll:      () -> Unit
) {
    var showClearSnapshotDialog by remember(selectedSnapshotSlot) { mutableStateOf(false) }
    var showOverwriteSaveDialog by remember(selectedSnapshotSlot) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    var roleMenuSourceId by remember { mutableStateOf<Int?>(null) }
    var brandMenuSourceId by remember { mutableStateOf<Int?>(null) }
    var seriesMenuSourceId by remember { mutableStateOf<Int?>(null) }
    var modelMenuSourceId by remember { mutableStateOf<Int?>(null) }
    val roleOptions = remember { SceneViewModel.SOURCE_ROLES }
    val brandOptions = remember(presets) {
        (presets.map { it.brand }.filter { it.isNotBlank() }.distinct().sorted() + "Other").distinct()
    }
    val seriesByBrand = remember(presets) {
        presets
            .filter { it.brand.isNotBlank() && it.series.isNotBlank() }
            .groupBy { it.brand }
            .mapValues { (_, list) -> (list.map { it.series }.distinct().sorted() + "Other").distinct() }
    }
    val modelsByBrandSeries = remember(presets) {
        presets.groupBy { it.brand to it.series }
    }
    val resultBySpeakerId = remember(speakerResults) { speakerResults.associateBy { it.speaker.id } }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text     = if (speakerSources.isEmpty()) "Add Source to begin" else "👆  Tap the 3D floor to place speaker from active source",
                style    = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sources", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Button(onClick = onAddSpeakerSource) { Text("Add Source") }
                }

                if (speakerSources.isEmpty()) {
                    Text(
                        "No sources yet. Add Source, pick Brand / Series / Speaker, then tap the floor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val speakersBySource = speakers.groupBy { it.sourceId }

                speakerSources.forEach { src ->
                    val sourceSpeakers = speakersBySource[src.id].orEmpty()
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { onActiveSpeakerSourceChanged(src.id) },
                                    enabled = activeSpeakerSourceId != src.id,
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (activeSpeakerSourceId == src.id) "Active" else "Set Active") }
                                IconButton(onClick = { onToggleSpeakerSourceCollapsed(src.id) }) {
                                    Icon(
                                        imageVector = if (src.collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                        contentDescription = if (src.collapsed) "Expand source" else "Collapse source"
                                    )
                                }
                                IconButton(onClick = { onRemoveSpeakerSource(src.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove source")
                                }
                            }

                            OutlinedTextField(
                                value = src.name,
                                onValueChange = { onSpeakerSourceNameChanged(src.id, it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Source name") }
                            )

                            val presetId = src.modelPresetId
                            val clfStatus = presetId?.let { clfSourceStatus[it] }
                            if (presetId != null && clfStatus != null) {
                                val binaryCount = clfBinaryAssets[presetId]?.size ?: 0
                                val externalCount = clfExternalAssets[presetId]?.size ?: 0
                                val statusColor = when {
                                    clfStatus.startsWith("Binary Extracted") || clfStatus == "JSON" -> MaterialTheme.colorScheme.primary
                                    clfStatus.startsWith("Binary Inferred") -> MaterialTheme.colorScheme.tertiary
                                    clfStatus.startsWith("Pending") || clfStatus.startsWith("External") -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Text(
                                    text = "CLF: $clfStatus",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor
                                )
                                Text(
                                    text = "Assets: binary=$binaryCount · external=$externalCount",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (!src.collapsed) {
                                Text("Source setup", style = MaterialTheme.typography.labelSmall)

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = { roleMenuSourceId = src.id },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Role: ${src.role}")
                                            Icon(Icons.Default.ExpandMore, contentDescription = null)
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = roleMenuSourceId == src.id,
                                        onDismissRequest = { roleMenuSourceId = null }
                                    ) {
                                        roleOptions.forEach { role ->
                                            DropdownMenuItem(
                                                text = { Text(role) },
                                                onClick = {
                                                    onSpeakerSourceRoleChanged(src.id, role)
                                                    roleMenuSourceId = null
                                                }
                                            )
                                        }
                                    }
                                }

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = { brandMenuSourceId = src.id },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Brand: ${src.brand}")
                                            Icon(Icons.Default.ExpandMore, contentDescription = null)
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = brandMenuSourceId == src.id,
                                        onDismissRequest = { brandMenuSourceId = null }
                                    ) {
                                        brandOptions.forEach { b ->
                                            DropdownMenuItem(
                                                text = { Text(b) },
                                                onClick = {
                                                    onSpeakerSourceBrandChanged(src.id, b)
                                                    brandMenuSourceId = null
                                                }
                                            )
                                        }
                                    }
                                }

                                val hasBrandSelection = src.brand.isNotBlank()
                                val seriesOptions = seriesByBrand[src.brand] ?: listOf("Other")
                                val hasSeriesSelection = src.series.isNotBlank()
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = { seriesMenuSourceId = src.id },
                                        enabled = hasBrandSelection,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Series: ${if (hasBrandSelection) src.series else "Select brand first"}")
                                            Icon(Icons.Default.ExpandMore, contentDescription = null)
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = seriesMenuSourceId == src.id,
                                        onDismissRequest = { seriesMenuSourceId = null }
                                    ) {
                                        seriesOptions.forEach { s ->
                                            DropdownMenuItem(
                                                text = { Text(s) },
                                                onClick = {
                                                    onSpeakerSourceSeriesChanged(src.id, s)
                                                    seriesMenuSourceId = null
                                                }
                                            )
                                        }
                                    }
                                }

                                val modelOptions = modelsByBrandSeries[src.brand to src.series].orEmpty()
                                val canPickModel = hasBrandSelection && hasSeriesSelection && modelOptions.isNotEmpty()
                                val modelLabel = presets.firstOrNull { it.id == src.modelPresetId }?.model ?: "Select"
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = { modelMenuSourceId = src.id },
                                        enabled = canPickModel,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Speaker: ${if (canPickModel) modelLabel else "Select brand/series first"}")
                                            Icon(Icons.Default.ExpandMore, contentDescription = null)
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = modelMenuSourceId == src.id,
                                        onDismissRequest = { modelMenuSourceId = null }
                                    ) {
                                        if (modelOptions.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text("No models for selected brand/series") },
                                                onClick = { modelMenuSourceId = null }
                                            )
                                        } else {
                                            modelOptions.forEach { p ->
                                                DropdownMenuItem(
                                                    text = { Text(p.model) },
                                                    onClick = {
                                                        onSpeakerSourceModelChanged(src.id, p.id)
                                                        modelMenuSourceId = null
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Text("Link", style = MaterialTheme.typography.labelSmall)
                                val linkTargets = speakerSources.filter { it.id != src.id }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = { onSpeakerSourceLinkMotionModeChanged(src.id, "SAME") },
                                        enabled = src.linkedMotionMode != "SAME",
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Move Same") }
                                    Button(
                                        onClick = { onSpeakerSourceLinkMotionModeChanged(src.id, "OPPOSITE") },
                                        enabled = src.linkedMotionMode != "OPPOSITE",
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Move Opp") }
                                }
                                if (linkTargets.isEmpty()) {
                                    Text("No other sources to link", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            onClick = { onSpeakerSourceLinkChanged(src.id, null) },
                                            enabled = src.linkedSourceId != null,
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Unlink") }
                                        linkTargets.forEach { target ->
                                            Button(
                                                onClick = { onSpeakerSourceLinkChanged(src.id, target.id) },
                                                enabled = src.linkedSourceId != target.id,
                                                modifier = Modifier.weight(1f)
                                            ) { Text(target.name.take(6)) }
                                        }
                                    }
                                }

                                HorizontalDivider()
                                Text("Dropped speakers (${sourceSpeakers.size})", style = MaterialTheme.typography.labelSmall)
                                if (sourceSpeakers.isEmpty()) {
                                    Text(
                                        "No speakers dropped for this source yet.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    sourceSpeakers.forEach { spk ->
                                        var labelDraft by remember(spk.id, spk.label) { mutableStateOf(spk.label) }
                                        val dsp = dspMap[spk.id] ?: SpeakerDsp(spk.id)
                                        val splText = resultBySpeakerId[spk.id]?.splDb?.f1 ?: "—"
                                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(spk.label, style = MaterialTheme.typography.labelMedium)
                                                Text(
                                                    "x=${spk.x.f1} m, y=${spk.heightM.f1} m, z=${spk.z.f1} m • ${splText} dB",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                                    OutlinedTextField(
                                                        value = labelDraft,
                                                        onValueChange = { labelDraft = it },
                                                        modifier = Modifier.weight(1f),
                                                        singleLine = true,
                                                        label = { Text("Speaker name") }
                                                    )
                                                    Button(
                                                        onClick = { onSpeakerLabelChanged(spk.id, labelDraft) },
                                                        modifier = Modifier.align(Alignment.CenterVertically)
                                                    ) { Text("Rename") }
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                                    TextButton(onClick = { onDuplicateSpeaker(spk.id) }) { Text("Dup") }
                                                    TextButton(onClick = { onMirrorSpeakerX(spk.id, null) }) { Text("Mirror X") }
                                                    TextButton(onClick = { onMirrorSpeakerY(spk.id, null) }) { Text("Mirror Y") }
                                                    IconButton(onClick = { onRemoveSpeaker(spk.id) }) {
                                                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                                                    }
                                                }

                                                Text("Location X: ${spk.x.f1} m", style = MaterialTheme.typography.bodySmall)
                                                Slider(
                                                    value = spk.x,
                                                    onValueChange = { onSpeakerPositionChanged(spk.id, it, spk.heightM, spk.z) },
                                                    valueRange = -200f..200f
                                                )

                                                Text("Location Y: ${spk.heightM.f1} m", style = MaterialTheme.typography.bodySmall)
                                                Slider(
                                                    value = spk.heightM,
                                                    onValueChange = { onSpeakerPositionChanged(spk.id, spk.x, it, spk.z) },
                                                    valueRange = 0f..40f
                                                )

                                                Text("Location Z: ${spk.z.f1} m", style = MaterialTheme.typography.bodySmall)
                                                Slider(
                                                    value = spk.z,
                                                    onValueChange = { onSpeakerPositionChanged(spk.id, spk.x, spk.heightM, it) },
                                                    valueRange = -200f..200f
                                                )

                                                Text("Pan: ${spk.panDeg.f1}°", style = MaterialTheme.typography.bodySmall)
                                                Slider(
                                                    value = spk.panDeg,
                                                    onValueChange = { onSpeakerPanChanged(spk.id, it) },
                                                    valueRange = -180f..180f
                                                )

                                                Text("Aim: ${spk.arrayAimDeg.f1}°", style = MaterialTheme.typography.bodySmall)
                                                Slider(
                                                    value = spk.arrayAimDeg,
                                                    onValueChange = { onSpeakerArrayAimChanged(spk.id, it) },
                                                    valueRange = -30f..30f
                                                )

                                                Text("Gain: ${dsp.gainDb.f1} dB", style = MaterialTheme.typography.bodySmall)
                                                Slider(
                                                    value = dsp.gainDb,
                                                    onValueChange = { onSpeakerGainChanged(spk.id, it) },
                                                    valueRange = -12f..12f
                                                )

                                                Text("Delay: ${dsp.delayMs.f1} ms", style = MaterialTheme.typography.bodySmall)
                                                Slider(
                                                    value = dsp.delayMs,
                                                    onValueChange = { onSpeakerDelayChanged(spk.id, it) },
                                                    valueRange = 0f..200f
                                                )

                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                                    Button(
                                                        onClick = { onSpeakerPolarityChanged(spk.id, false) },
                                                        enabled = dsp.polarity,
                                                        modifier = Modifier.weight(1f)
                                                    ) { Text("Pol +") }
                                                    Button(
                                                        onClick = { onSpeakerPolarityChanged(spk.id, true) },
                                                        enabled = !dsp.polarity,
                                                        modifier = Modifier.weight(1f)
                                                    ) { Text("Pol -") }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (sourceSpeakers.isNotEmpty()) {
                                Text(
                                    "${sourceSpeakers.size} speakers hidden (source collapsed)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Snapshot slot", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("A", "B", "C").forEach { slot ->
                val slotLabel = snapshotSlotNames[slot] ?: "Slot $slot"
                val dirtyMark = if (slot == selectedSnapshotSlot && isSnapshotDirty) "•" else ""
                Button(
                    onClick = { onSelectedSnapshotSlotChanged(slot) },
                    enabled = slot != selectedSnapshotSlot,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("$slot$dirtyMark: ${slotLabel.take(8)}")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = snapshotNameDraft,
            onValueChange = { onSnapshotNameDraftChanged(it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Slot ${selectedSnapshotSlot} name") }
        )
        Spacer(Modifier.height(4.dp))
        val savedAt = snapshotSavedAtMs[selectedSnapshotSlot]
        val savedAtLabel = savedAt?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        } ?: "—"
        Text(
            "Last saved: $savedAtLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        recoverySavedAtMs?.let {
            Text(
                "Autosave: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { onRenameSnapshotSlot(selectedSnapshotSlot, snapshotNameDraft) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Rename Slot ${selectedSnapshotSlot}")
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (snapshotSavedAtMs.containsKey(selectedSnapshotSlot)) {
                        showOverwriteSaveDialog = true
                    } else {
                        onSaveScene(selectedSnapshotSlot)
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save ${selectedSnapshotSlot}")
            }
            Button(onClick = { onLoadScene(selectedSnapshotSlot) }, modifier = Modifier.weight(1f)) {
                Text("Load ${selectedSnapshotSlot}")
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = onLoadLatestScene, modifier = Modifier.fillMaxWidth()) {
            Text("Load Latest")
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onRecoverAutosave, modifier = Modifier.weight(1f)) {
                Text("Recover")
            }
            Button(onClick = onUndoScene, enabled = canUndo, modifier = Modifier.weight(1f)) {
                Text("Undo")
            }
            Button(onClick = onRedoScene, enabled = canRedo, modifier = Modifier.weight(1f)) {
                Text("Redo")
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onCopySceneJson, modifier = Modifier.weight(1f)) {
                Text("Copy JSON")
            }
            Button(onClick = onImportSceneJson, modifier = Modifier.weight(1f)) {
                Text("Paste JSON")
            }
        }
        if (showOverwriteSaveDialog) {
            val slotLabel = snapshotSlotNames[selectedSnapshotSlot] ?: "Slot $selectedSnapshotSlot"
            AlertDialog(
                onDismissRequest = { showOverwriteSaveDialog = false },
                title = { Text("Overwrite snapshot?") },
                text = { Text("Replace existing data in '$slotLabel' (${selectedSnapshotSlot})?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showOverwriteSaveDialog = false
                            onSaveScene(selectedSnapshotSlot)
                        }
                    ) { Text("Overwrite") }
                },
                dismissButton = {
                    TextButton(onClick = { showOverwriteSaveDialog = false }) { Text("Cancel") }
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        val duplicateTargets = listOf("A", "B", "C").filter { it != selectedSnapshotSlot }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            duplicateTargets.forEach { target ->
                Button(
                    onClick = { onDuplicateScene(selectedSnapshotSlot, target) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Copy → $target")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = { showClearSnapshotDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Clear ${selectedSnapshotSlot}")
        }
        if (showClearSnapshotDialog) {
            val slotLabel = snapshotSlotNames[selectedSnapshotSlot] ?: "Slot $selectedSnapshotSlot"
            AlertDialog(
                onDismissRequest = { showClearSnapshotDialog = false },
                title = { Text("Clear snapshot?") },
                text = { Text("Delete saved data for '$slotLabel' (${selectedSnapshotSlot})?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearSnapshotDialog = false
                            onClearScene(selectedSnapshotSlot)
                        }
                    ) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearSnapshotDialog = false }) { Text("Cancel") }
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        if (speakers.isNotEmpty()) {
            Button(onClick = onClearAll, modifier = Modifier.fillMaxWidth()) {
                Text("Clear All  (${speakers.size})")
            }
            Spacer(Modifier.height(4.dp))
        }
        val speakersBySource = speakers.groupBy { it.sourceId }
        val unassigned = speakersBySource[null].orEmpty()
        if (unassigned.isNotEmpty()) {
            Text(
                "Unassigned (${unassigned.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        Button(
            onClick  = onRunGpuTest,
            enabled  = engineReady && !isCalculating,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isCalculating) "Calculating…" else "GPU Distance Test")
        }
        if (!engineReady) {
            Text(
                "Engine not ready — check Logcat",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun ResultsPanel(
    speakers: List<SpeakerResult>,
    listener: ListenerPos,
    combinedSplDb: Float?,
    earlyReflections: List<EarlyReflection>,
    rt60Estimate: Rt60Estimate?,
    stiEstimate: StiEstimate?,
    heatmap: List<HeatCell>,
    audienceScoreMode: String,
    reflectionOrder: Int,
    analysisProfile: String
) {
    val score = remember(heatmap, rt60Estimate, audienceScoreMode) {
        computeAudienceScore(heatmap = heatmap, rt60Estimate = rt60Estimate, mode = audienceScoreMode)
    }
    val zoneStats = remember(heatmap) {
        computeZoneStats(heatmap)
    }
    val reflectionLabel = when (reflectionOrder) {
        1 -> "1st order only"
        2 -> "1st+2nd order"
        else -> "1st+2nd+3rd order"
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("🎟️  Listener", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                "(${listener.x.f1} m, ${listener.z.f1} m)  •  ear: ${listener.earHeightM.f1} m",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Tap the 3D floor to reposition",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Floor heatmap tiles: blue→cyan→green→yellow→red across the audience plane",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (combinedSplDb != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Phase-aware combined SPL: ${combinedSplDb.f1} dB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = splColor(combinedSplDb)
                )
                Text(
                    "Includes propagation phase, per-speaker delay, and polarity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Audience score ($audienceScoreMode)", style = MaterialTheme.typography.labelLarge)
            Text(
                "${score.total.roundToInt()} / 100  •  ${score.label}",
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    score.total >= 80f -> MaterialTheme.colorScheme.primary
                    score.total >= 60f -> Color(0xFFFF9800)
                    else -> MaterialTheme.colorScheme.error
                }
            )
            Text(
                "Level ${score.level.f1}  •  Uniformity ${score.uniformity.f1}  •  RT ${score.rt.f1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Reflections: $reflectionLabel  •  Profile: $analysisProfile",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            stiEstimate?.let { sti ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "STI: ${sti.sti.f2}  •  ${sti.quality}  •  ALcons ${sti.alconsPct.f1}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        sti.sti >= 0.60f -> MaterialTheme.colorScheme.primary
                        sti.sti >= 0.45f -> Color(0xFFFF9800)
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
            if (zoneStats.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Zone breakdown", style = MaterialTheme.typography.labelSmall)
                zoneStats.forEach { (areaName, avgSpl, count) ->
                    Text(
                        "$areaName: avg ${avgSpl.f1} dB ($count points)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    if (speakers.isEmpty()) {
        Text(
            "No speakers placed yet — go to Speakers tab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(speakers) { result ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(result.speaker.label, style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${"%.1f".format(result.distanceM)} m away",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Air: -${"%.2f".format(result.airLossDb)} dB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${"%.1f".format(result.splDb)} dB",
                        style = MaterialTheme.typography.bodyLarge,
                        color = splColor(result.splDb)
                    )
                }
            }
        }
    }
    if (combinedSplDb != null) {
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Combined SPL", style = MaterialTheme.typography.labelLarge)
            Text(
                "${"%.1f".format(combinedSplDb)} dB",
                style = MaterialTheme.typography.titleMedium,
                color = splColor(combinedSplDb)
            )
        }
    }
    if (earlyReflections.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Early reflections", style = MaterialTheme.typography.labelLarge)
                Text(
                    "$reflectionLabel image-source paths, ranked by SPL. Loss from surface α settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                earlyReflections.forEach { reflection ->
                    Text(
                        "${reflection.speakerLabel} · ${reflection.surfaceName} · ${reflection.delayMs.f1} ms · ${reflection.splDb.f1} dB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    if (rt60Estimate != null) {
        Spacer(Modifier.height(8.dp))
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("RT60 estimate", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${rt60Estimate.widthM.f1} × ${rt60Estimate.depthM.f1} × ${rt60Estimate.heightM.f1} m  •  ${rt60Estimate.volumeM3.f1} m³",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Sabine mid-band RT60: ${rt60Estimate.rt60S.f2} s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (rt60Estimate.rt60S <= 1.2f) MaterialTheme.colorScheme.primary else Color(0xFFFF9800)
                )
                Text(
                    "Material-aware estimate (adjust surface α in Settings).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                stiEstimate?.let { sti ->
                    Text(
                        "Speech Transmission Index: ${sti.sti.f2} (${sti.quality})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderPanel(name: String) {
    Text("$name — coming in a future phase", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
}

@Composable
private fun ReportPanel(
    combinedSplDb: Float?,
    rt60Estimate: Rt60Estimate?,
    stiEstimate: StiEstimate?,
    speakerCount: Int,
    audiencePointCount: Int,
    audienceAreaCount: Int,
    onCopyReportJson: () -> Unit,
    onPasteDataJson: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Project report", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Speakers: $speakerCount  •  Audience points: $audiencePointCount  •  Areas: $audienceAreaCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                combinedSplDb?.let {
                    Text("Combined SPL: ${it.f1} dB", style = MaterialTheme.typography.bodyMedium, color = splColor(it))
                }
                rt60Estimate?.let {
                    Text(
                        "RT60: ${it.rt60S.f2} s  •  Volume: ${it.volumeM3.f1} m³",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                stiEstimate?.let {
                    Text(
                        "STI: ${it.sti.f2} (${it.quality})  •  ALcons ${it.alconsPct.f1}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tooling", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onCopyReportJson, modifier = Modifier.weight(1f)) {
                        Text("Copy Report JSON")
                    }
                    Button(onClick = onPasteDataJson, modifier = Modifier.weight(1f)) {
                        Text("Paste Data")
                    }
                }
                Text(
                    "Copy exports report JSON in Report tab, venue JSON in Venue tab, and full scene JSON elsewhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SignalPanel(
    selectedBandHz: Int,
    signalLevelDbu: Float,
    signalType: String,
    signalBandwidthOct: Float,
    signalResolution: Int,
    signalInterferenceEnabled: Boolean,
    signalAutoCalculate: Boolean,
    signalSplEnabled: Boolean,
    signalDispersionEnabled: Boolean,
    signalCoverageEnabled: Boolean,
    highestSplDb: Float?,
    onBandSelected: (Int) -> Unit,
    onSignalLevelDbuChanged: (Float) -> Unit,
    onSignalTypeChanged: (String) -> Unit,
    onSignalBandwidthChanged: (Float) -> Unit,
    onSignalResolutionChanged: (Int) -> Unit,
    onSignalInterferenceChanged: (Boolean) -> Unit,
    onSignalAutoCalculateChanged: (Boolean) -> Unit,
    onSignalSplEnabledChanged: (Boolean) -> Unit,
    onSignalDispersionEnabledChanged: (Boolean) -> Unit,
    onSignalCoverageEnabledChanged: (Boolean) -> Unit,
    onSignalRecalculate: () -> Unit
) {
    val vm: SceneViewModel = viewModel()
    val optimizerEnabled by vm.optimizerEnabled.collectAsState()
    val optimizerResult by vm.optimizerResult.collectAsState()
    val optimizerIsRunning by vm.optimizerIsRunning.collectAsState()

    val bandwidthOptions = listOf(
        (1f / 1f) to "1/1",
        (1f / 2f) to "1/2",
        (1f / 3f) to "1/3",
        (1f / 6f) to "1/6",
        (1f / 12f) to "1/12"
    )

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Level (dBu)", style = MaterialTheme.typography.labelMedium)
                Text("${signalLevelDbu.f1} dBu", style = MaterialTheme.typography.bodySmall)
                Slider(value = signalLevelDbu, onValueChange = onSignalLevelDbuChanged, valueRange = -24f..24f)
                Text(
                    "Global gain offset for all SPL calculations.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onSignalTypeChanged("BAND") },
                        enabled = signalType != "BAND",
                        modifier = Modifier.weight(1f)
                    ) { Text("Frequency band") }
                    Button(
                        onClick = { onSignalTypeChanged("SPECTRUM") },
                        enabled = signalType != "SPECTRUM",
                        modifier = Modifier.weight(1f)
                    ) { Text("Spectrum") }
                }

                if (signalType == "BAND") {
                    Text("Frequency (Hz)", style = MaterialTheme.typography.labelMedium)
                    val bands = SceneViewModel.SUPPORTED_BANDS_HZ
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (row in 0 until 2) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (col in 0 until 4) {
                                    val idx = row * 4 + col
                                    val band = bands[idx]
                                    Button(
                                        onClick = { onBandSelected(band) },
                                        modifier = Modifier.weight(1f),
                                        enabled = band != selectedBandHz
                                    ) { Text("$band") }
                                }
                            }
                        }
                    }
                    Text("Selected: $selectedBandHz Hz", style = MaterialTheme.typography.bodySmall)

                    Text("Bandwidth", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        bandwidthOptions.forEach { (oct, label) ->
                            Button(
                                onClick = { onSignalBandwidthChanged(oct) },
                                enabled = kotlin.math.abs(signalBandwidthOct - oct) > 1e-4f,
                                modifier = Modifier.weight(1f)
                            ) { Text(label) }
                        }
                    }
                } else {
                    Text("Frequency resolution", style = MaterialTheme.typography.labelMedium)
                    Text("$signalResolution samples", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = signalResolution.toFloat(),
                        onValueChange = { onSignalResolutionChanged(it.roundToInt()) },
                        valueRange = 3f..96f
                    )
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Interference ≤ 163 Hz", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onSignalInterferenceChanged(true) },
                        enabled = !signalInterferenceEnabled,
                        modifier = Modifier.weight(1f)
                    ) { Text("Enabled") }
                    Button(
                        onClick = { onSignalInterferenceChanged(false) },
                        enabled = signalInterferenceEnabled,
                        modifier = Modifier.weight(1f)
                    ) { Text("Disabled") }
                }
                Text(
                    "Disabling interference forces incoherent low-frequency summation for quicker, smoother LF previews.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Calculate mode", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onSignalAutoCalculateChanged(false) },
                        enabled = signalAutoCalculate,
                        modifier = Modifier.weight(1f)
                    ) { Text("Calculate") }
                    Button(
                        onClick = { onSignalAutoCalculateChanged(true) },
                        enabled = !signalAutoCalculate,
                        modifier = Modifier.weight(1f)
                    ) { Text("Autocalculate") }
                }
                Button(
                    onClick = onSignalRecalculate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !signalAutoCalculate
                ) { Text("Calculate now") }
                Text(
                    if (signalAutoCalculate) {
                        "Auto calculate keeps SPL, dispersion, and coverage in sync after edits."
                    } else {
                        "Manual calculate keeps the current solution static until you press Calculate again."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Highest SPL: ${highestSplDb?.f1 ?: "—"} dB",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Array Optimizer (Phase 9)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { vm.runOptimization() },
                        modifier = Modifier.weight(1f),
                        enabled = !optimizerIsRunning
                    ) { Text(if (optimizerIsRunning) "Optimizing..." else "Run Optimizer") }
                    Button(
                        onClick = { vm.clearOptimizer() },
                        modifier = Modifier.weight(1f),
                        enabled = optimizerEnabled && !optimizerIsRunning
                    ) { Text("Clear") }
                }
                val optResult = optimizerResult
                if (optResult != null) {
                    Text(
                        "Mode: ${optResult.optimizationMode}, " +
                        "Objective: ${"%.2f".format(optResult.objective)}, " +
                        "Iterations: ${optResult.iterationCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (optimizerEnabled) "✓ Optimizer active" else "Optimizer result available (apply to enable)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (optimizerEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsPanel(
    selectedBandHz: Int,
    temperatureC: Float,
    humidityPct: Float,
    onBandSelected: (Int) -> Unit,
    onTemperatureChanged: (Float) -> Unit,
    onHumidityChanged: (Float) -> Unit,
    reflectionOrder: Int,
    onReflectionOrderChanged: (Int) -> Unit,
    analysisProfile: String,
    onAnalysisProfileChanged: (String) -> Unit,
    roomMaterials: RoomMaterials,
    onFloorAbsChanged: (Float) -> Unit,
    onCeilingAbsChanged: (Float) -> Unit,
    onWallAbsChanged: (Float) -> Unit,
    onRoomHeightChanged: (Float) -> Unit,
    selectedSnapshotSlot: String,
    snapshotSlotNames: Map<String, String>,
    snapshotSavedAtMs: Map<String, Long>,
    recoverySavedAtMs: Long?,
    isSnapshotDirty: Boolean,
    snapshotNameDraft: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndoScene: () -> Unit,
    onRedoScene: () -> Unit,
    onSelectedSnapshotSlotChanged: (String) -> Unit,
    onSnapshotNameDraftChanged: (String) -> Unit,
    onRenameSnapshotSlot: (String, String) -> Unit,
    onSaveScene: (String) -> Unit,
    onLoadScene: (String) -> Unit,
    onLoadLatestScene: () -> Unit,
    onCopySceneJson: () -> Unit,
    onImportSceneJson: () -> Unit,
    onRecoverAutosave: () -> Unit,
    onDuplicateScene: (String, String) -> Unit,
    onClearScene: (String) -> Unit,
    audienceScoreMode: String,
    onAudienceScoreModeChanged: (String) -> Unit,
    showHeatmap: Boolean,
    onShowHeatmapChanged: (Boolean) -> Unit,
    clfIngestionStats: ClfIngestionStats,
    strictExtractedBinaryClfOnly: Boolean,
    onStrictExtractedBinaryClfOnlyChanged: (Boolean) -> Unit
) {
    var showClearSnapshotDialog by remember(selectedSnapshotSlot) { mutableStateOf(false) }
    var showOverwriteSaveDialog by remember(selectedSnapshotSlot) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Acoustics", style = MaterialTheme.typography.labelLarge)

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Band (Hz)", style = MaterialTheme.typography.labelMedium)
                val bands = SceneViewModel.SUPPORTED_BANDS_HZ
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in 0 until 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (col in 0 until 4) {
                                val idx = row * 4 + col
                                val band = bands[idx]
                                Button(
                                    onClick = { onBandSelected(band) },
                                    modifier = Modifier.weight(1f),
                                    enabled = band != selectedBandHz
                                ) {
                                    Text("$band")
                                }
                            }
                        }
                    }
                }
                Text(
                    "Selected: $selectedBandHz Hz",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Atmosphere", style = MaterialTheme.typography.labelMedium)
                Text("Temperature: ${temperatureC.f1} °C", style = MaterialTheme.typography.bodySmall)
                Slider(value = temperatureC, onValueChange = onTemperatureChanged, valueRange = -10f..45f)
                Text("Humidity: ${humidityPct.f1} %", style = MaterialTheme.typography.bodySmall)
                Slider(value = humidityPct, onValueChange = onHumidityChanged, valueRange = 5f..100f)
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Analysis profile", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    SceneViewModel.ANALYSIS_PROFILES.forEach { profile ->
                        Button(
                            onClick = { onAnalysisProfileChanged(profile) },
                            enabled = analysisProfile != profile,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(profile.take(5))
                        }
                    }
                }
                Text(
                    "Current: $analysisProfile (controls heatmap density + reflection list size)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Reflection order", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onReflectionOrderChanged(1) },
                        enabled = reflectionOrder != 1,
                        modifier = Modifier.weight(1f)
                    ) { Text("1st only") }
                    Button(
                        onClick = { onReflectionOrderChanged(2) },
                        enabled = reflectionOrder != 2,
                        modifier = Modifier.weight(1f)
                    ) { Text("1st+2nd") }
                    Button(
                        onClick = { onReflectionOrderChanged(3) },
                        enabled = reflectionOrder != 3,
                        modifier = Modifier.weight(1f)
                    ) { Text("1st+2nd+3rd") }
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Room acoustics  (Phase 8)", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Surface absorption coefficients (Sabine α). " +
                        "Higher values = more absorption = shorter RT60.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Floor α: ${"%.2f".format(roomMaterials.floorAlpha)}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = roomMaterials.floorAlpha,
                    onValueChange = onFloorAbsChanged,
                    valueRange = 0.01f..1f,
                    steps = 97
                )
                Text("Ceiling α: ${"%.2f".format(roomMaterials.ceilingAlpha)}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = roomMaterials.ceilingAlpha,
                    onValueChange = onCeilingAbsChanged,
                    valueRange = 0.01f..1f,
                    steps = 97
                )
                Text("Walls α: ${"%.2f".format(roomMaterials.wallAlpha)}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = roomMaterials.wallAlpha,
                    onValueChange = onWallAbsChanged,
                    valueRange = 0.01f..1f,
                    steps = 97
                )
                Text("Ceiling height: ${"%.1f".format(roomMaterials.roomHeightM)} m", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = roomMaterials.roomHeightM,
                    onValueChange = onRoomHeightChanged,
                    valueRange = 3f..30f
                )
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CLF binary ingestion", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Indexed: ${clfIngestionStats.indexedSpeakers} · Registry: ${clfIngestionStats.registrySpeakers}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "JSON: ${clfIngestionStats.parsedJsonSpeakers} · Binary: ${clfIngestionStats.parsedBinarySpeakers}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Extracted: ${clfIngestionStats.extractedBinarySpeakers} · Inferred: ${clfIngestionStats.inferredBinarySpeakers}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Pending binary: ${clfIngestionStats.pendingBinarySpeakers} · External unresolved: ${clfIngestionStats.unresolvedExternalSpeakers}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "Strict mode keeps only extracted matrix parses from binary CLF payloads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onStrictExtractedBinaryClfOnlyChanged(false) },
                        enabled = strictExtractedBinaryClfOnly,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Allow inferred")
                    }
                    Button(
                        onClick = { onStrictExtractedBinaryClfOnlyChanged(true) },
                        enabled = !strictExtractedBinaryClfOnly,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Extracted only")
                    }
                }
                Text(
                    "Mode: ${if (strictExtractedBinaryClfOnly) "EXTRACTED ONLY" else "EXTRACTED + INFERRED"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Snapshot Manager", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("A", "B", "C").forEach { slot ->
                        val slotLabel = snapshotSlotNames[slot] ?: "Slot $slot"
                        val dirtyMark = if (slot == selectedSnapshotSlot && isSnapshotDirty) "•" else ""
                        Button(
                            onClick = { onSelectedSnapshotSlotChanged(slot) },
                            enabled = slot != selectedSnapshotSlot,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("$slot$dirtyMark:${slotLabel.take(5)}")
                        }
                    }
                }
                OutlinedTextField(
                    value = snapshotNameDraft,
                    onValueChange = onSnapshotNameDraftChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Slot ${selectedSnapshotSlot} name") }
                )
                val savedAt = snapshotSavedAtMs[selectedSnapshotSlot]
                val savedAtLabel = savedAt?.let {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
                } ?: "—"
                Text(
                    "Last saved: $savedAtLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                recoverySavedAtMs?.let {
                    Text(
                        "Autosave: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onRenameSnapshotSlot(selectedSnapshotSlot, snapshotNameDraft) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Rename") }
                    Button(
                        onClick = {
                            if (snapshotSavedAtMs.containsKey(selectedSnapshotSlot)) {
                                showOverwriteSaveDialog = true
                            } else {
                                onSaveScene(selectedSnapshotSlot)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }
                    Button(
                        onClick = { onLoadScene(selectedSnapshotSlot) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Load") }
                }
                Button(
                    onClick = onLoadLatestScene,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Load Latest")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onRecoverAutosave,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Recover")
                    }
                    Button(
                        onClick = onUndoScene,
                        enabled = canUndo,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Undo")
                    }
                    Button(
                        onClick = onRedoScene,
                        enabled = canRedo,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Redo")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onCopySceneJson,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copy JSON")
                    }
                    Button(
                        onClick = onImportSceneJson,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Paste JSON")
                    }
                }
                if (showOverwriteSaveDialog) {
                    val slotLabel = snapshotSlotNames[selectedSnapshotSlot] ?: "Slot $selectedSnapshotSlot"
                    AlertDialog(
                        onDismissRequest = { showOverwriteSaveDialog = false },
                        title = { Text("Overwrite snapshot?") },
                        text = { Text("Replace existing data in '$slotLabel' (${selectedSnapshotSlot})?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showOverwriteSaveDialog = false
                                    onSaveScene(selectedSnapshotSlot)
                                }
                            ) { Text("Overwrite") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showOverwriteSaveDialog = false }) { Text("Cancel") }
                        }
                    )
                }
                val duplicateTargets = listOf("A", "B", "C").filter { it != selectedSnapshotSlot }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    duplicateTargets.forEach { target ->
                        Button(
                            onClick = { onDuplicateScene(selectedSnapshotSlot, target) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Copy→$target")
                        }
                    }
                }
                Button(
                    onClick = { showClearSnapshotDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear ${selectedSnapshotSlot}")
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Audience score mode", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onAudienceScoreModeChanged("Speech") },
                        enabled = audienceScoreMode != "Speech",
                        modifier = Modifier.weight(1f)
                    ) { Text("Speech") }
                    Button(
                        onClick = { onAudienceScoreModeChanged("Music") },
                        enabled = audienceScoreMode != "Music",
                        modifier = Modifier.weight(1f)
                    ) { Text("Music") }
                }
                Text(
                    "Scoring uses SPL level, uniformity and RT60 target for the selected content type.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Viewport overlays", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onShowHeatmapChanged(true) },
                        enabled = !showHeatmap,
                        modifier = Modifier.weight(1f)
                    ) { Text("Heatmap ON") }
                    Button(
                        onClick = { onShowHeatmapChanged(false) },
                        enabled = showHeatmap,
                        modifier = Modifier.weight(1f)
                    ) { Text("Heatmap OFF") }
                }
                Text(
                    "Current: ${if (showHeatmap) "Visible" else "Hidden"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (showClearSnapshotDialog) {
            val slotLabel = snapshotSlotNames[selectedSnapshotSlot] ?: "Slot $selectedSnapshotSlot"
            AlertDialog(
                onDismissRequest = { showClearSnapshotDialog = false },
                title = { Text("Clear snapshot?") },
                text = { Text("Delete saved data for '$slotLabel' (${selectedSnapshotSlot})?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearSnapshotDialog = false
                            onClearScene(selectedSnapshotSlot)
                        }
                    ) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearSnapshotDialog = false }) { Text("Cancel") }
                }
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

// Helpers

/** Format float to 1 decimal place. */
private val Float.f1 get() = "%.1f".format(this)
private val Float.f2 get() = "%.2f".format(this)

private fun polygonEdgeLength(vertices: List<Pair<Float, Float>>, edgeIndex: Int): Float {
    if (vertices.isEmpty() || edgeIndex !in vertices.indices) return 0f
    val a = vertices[edgeIndex]
    val b = vertices[(edgeIndex + 1) % vertices.size]
    val dx = b.first - a.first
    val dz = b.second - a.second
    return sqrt(dx * dx + dz * dz)
}

private data class AudienceScore(
    val total: Float,
    val level: Float,
    val uniformity: Float,
    val rt: Float,
    val label: String
)

private fun computeAudienceScore(
    heatmap: List<HeatCell>,
    rt60Estimate: Rt60Estimate?,
    mode: String
): AudienceScore {
    if (heatmap.isEmpty()) {
        return AudienceScore(total = 0f, level = 0f, uniformity = 0f, rt = 0f, label = "No data")
    }

    val isSpeech = mode.equals("Speech", ignoreCase = true)
    // Phase 8: Zone-aware weighting by audience intent + rake importance.
    val weights = heatmap.map { cell -> audienceCellWeight(cell, isSpeech) }
    val totalWeight = weights.sum()
    val weightedSplValues = heatmap.mapIndexed { i, cell -> cell.splDb * weights[i] }
    if (totalWeight <= 1e-4f) {
        return AudienceScore(total = 0f, level = 0f, uniformity = 0f, rt = 0f, label = "No weighted data")
    }

    val avg = if (totalWeight > 0f) weightedSplValues.sum() / totalWeight else heatmap.map { it.splDb }.average().toFloat()
    val variance = heatmap.mapIndexed { i, cell -> 
        weights[i] * (cell.splDb - avg) * (cell.splDb - avg)
    }.sum() / totalWeight
    val std = sqrt(variance)

    val targetSpl = if (isSpeech) 74f else 95f
    val targetRt = if (isSpeech) 0.9f else 1.6f

    val levelScore = (100f - abs(avg - targetSpl) * 2.2f).coerceIn(0f, 100f)
    val uniformityScore = (100f - std * 8.5f).coerceIn(0f, 100f)
    val rtScore = rt60Estimate?.let {
        (100f - abs(it.rt60S - targetRt) * 55f).coerceIn(0f, 100f)
    } ?: 60f

    val total = (levelScore * 0.45f + uniformityScore * 0.35f + rtScore * 0.20f).coerceIn(0f, 100f)
    val label = when {
        total >= 85f -> "Excellent"
        total >= 70f -> "Good"
        total >= 55f -> "Fair"
        else -> "Poor"
    }

    return AudienceScore(
        total = total,
        level = levelScore,
        uniformity = uniformityScore,
        rt = rtScore,
        label = label
    )
}

/** Colour-code SPL: green ≥94 dB, amber ≥85 dB, red below. */
@Composable
private fun splColor(spl: Float) = when {
    spl >= 94f -> MaterialTheme.colorScheme.primary
    spl >= 85f -> Color(0xFFFF9800)
    else       -> MaterialTheme.colorScheme.error
}
/**
 * Phase 8: Compute per-zone average SPL for zone-aware reporting.
 * Groups heatmap cells by sourceAreaId and returns (areaName, avgSpl, pointCount) tuples.
 */
private fun computeZoneStats(heatmap: List<HeatCell>): List<Triple<String, Float, Int>> {
    val zoneMap = mutableMapOf<String, MutableList<Float>>()
    heatmap.forEach { cell ->
        val label = when {
            !cell.sourceAreaName.isNullOrBlank() -> cell.sourceAreaName
            cell.sourceAreaId != null -> "Area ${cell.sourceAreaId}"
            !cell.sourceZoneType.isNullOrBlank() -> cell.sourceZoneType
            else -> "Auto-grid"
        }
        zoneMap.getOrPut(label) { mutableListOf() }.add(cell.splDb)
    }

    return zoneMap.entries
        .sortedBy { (label, _) -> if (label == "Auto-grid") "~" else label }
        .map { (label, values) ->
            Triple(label, values.average().toFloat(), values.size)
        }
}

private fun audienceCellWeight(cell: HeatCell, isSpeech: Boolean): Float {
    val zoneType = cell.sourceZoneType.orEmpty()
    val zoneWeight = when (zoneType) {
        "AUDIENCE_SEATED" -> if (isSpeech) 1.65f else 1.45f
        "AUDIENCE_STANDING" -> if (isSpeech) 1.35f else 1.25f
        "BALCONY" -> 1.30f
        "STAGE" -> 0.65f
        "OBSTACLE", "WALL" -> 0.35f
        else -> if (cell.sourceAreaId != null) 1.15f else 0.90f
    }
    val rakeFactor = 1f + (kotlin.math.abs(cell.sourceRakeDeg).coerceAtMost(20f) / 20f) * 0.15f
    return zoneWeight * rakeFactor
}