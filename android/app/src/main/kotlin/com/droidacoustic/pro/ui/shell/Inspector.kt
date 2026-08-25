package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.scene.AudienceArea
import com.droidacoustic.pro.scene.PlacedSpeaker
import com.droidacoustic.pro.scene.SceneViewModel
import com.droidacoustic.pro.scene.SpeakerDsp
import com.droidacoustic.pro.scene.VenueBlock
import com.droidacoustic.pro.scene.VenueGeometry
import com.droidacoustic.pro.ui.components.InspectorSection
import com.droidacoustic.pro.ui.components.IntStepper
import com.droidacoustic.pro.ui.components.NumericField
import com.droidacoustic.pro.ui.components.Readout
import com.droidacoustic.pro.ui.components.SectionLabel
import com.droidacoustic.pro.ui.components.SegmentedControl
import com.droidacoustic.pro.ui.components.StatusChip
import com.droidacoustic.pro.ui.components.Vector3Field
import com.droidacoustic.pro.ui.theme.Instrument

// =============================================================================
// Selection model
// =============================================================================
//
// Purely a UI concern, so it lives here rather than in the ViewModel.
// =============================================================================

sealed interface Selection {
    data object None : Selection
    data class Speaker(val id: Int) : Selection
    data class Zone(val id: Int) : Selection
    data class Block(val id: Int) : Selection
    data object Listener : Selection
}

// =============================================================================
// Inspector
// =============================================================================
//
// Replaces the old properties panel, which showed every control for the current
// tab regardless of what you were working on — including project save/load,
// which had no business being in a design panel.
//
// This shows exactly one thing: the selection.
// =============================================================================

@Composable
fun Inspector(
    vm: SceneViewModel,
    selection: Selection,
    onSelect: (Selection) -> Unit,
    speakers: List<PlacedSpeaker>,
    zones: List<AudienceArea>,
    venue: VenueGeometry,
    dspMap: Map<Int, SpeakerDsp>,
    tool: Tool,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val speaker = (selection as? Selection.Speaker)?.let { s -> speakers.find { it.id == s.id } }
        val zone = (selection as? Selection.Zone)?.let { s -> zones.find { it.id == s.id } }
        val block = (selection as? Selection.Block)?.let { s -> venue.blocks.find { it.id == s.id } }

        InspectorHeader(
            title = when {
                speaker != null -> speaker.label
                zone != null -> zone.name
                block != null -> block.label
                selection is Selection.Listener -> "Listener"
                else -> "Venue"
            },
            subtitle = when {
                speaker != null -> "Loudspeaker"
                zone != null -> zone.zoneType.replace('_', ' ').lowercase()
                block != null -> block.type.lowercase()
                selection is Selection.Listener -> "Measurement point"
                else -> "Nothing selected"
            },
            onClear = if (selection != Selection.None) {
                { onSelect(Selection.None) }
            } else null
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            when {
                speaker != null -> SpeakerInspector(vm, speaker, dspMap[speaker.id] ?: SpeakerDsp(speaker.id))
                zone != null -> ZoneInspector(vm, zone)
                block != null -> BlockInspector(vm, block)
                selection is Selection.Listener -> ListenerInspector(vm)
                // With a placement tool active and nothing selected, the
                // inspector describes what is about to be placed.
                tool == Tool.SPEAKER -> SpeakerPickerInspector(vm)
                tool == Tool.ZONE -> ZonePickerInspector(vm)
                else -> VenueInspector(vm, venue)
            }
        }
    }
}

@Composable
private fun InspectorHeader(
    title: String,
    subtitle: String,
    onClear: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        if (onClear != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    "Deselect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Speaker
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SpeakerInspector(vm: SceneViewModel, spk: PlacedSpeaker, dsp: SpeakerDsp) {

    InspectorSection("Position") {
        Vector3Field(
            label = "",
            x = spk.x, y = spk.heightM, z = spk.z,
            onChange = { x, y, z -> vm.setSpeakerPosition(spk.id, x, y, z) }
        )
        NumericField(
            "Pan", spk.panDeg, { vm.setSpeakerPan(spk.id, it) },
            unit = "°", range = -180f..180f, decimals = 1, dragStep = 1f
        )
    }

    InspectorSection("Aim") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // "Mechanical" is rigging vocabulary; this is the box's vertical tilt,
            // and people scanning the panel for tilt did not recognise it. Range
            // matches what setSpeakerArrayAim actually enforces - the field used to
            // offer +/-90 and silently clamp to +/-30.
            NumericField(
                "Tilt", spk.arrayAimDeg, { vm.setSpeakerArrayAim(spk.id, it) },
                Modifier.weight(1f), unit = "°", range = -30f..30f, dragStep = 0.5f
            )
            // Electronic steering only exists across a multi-element array. On a
            // single box the summation path never reads it, so the control moved
            // behind the same guard as spacing and splay.
            if (spk.arrayElements > 1) {
                NumericField(
                    "Steering", spk.arraySteerDeg, { vm.setSpeakerArraySteer(spk.id, it) },
                    Modifier.weight(1f), unit = "°", range = -30f..30f, dragStep = 0.5f
                )
            } else {
                Box(Modifier.weight(1f))
            }
        }
    }

    InspectorSection("Array") {
        IntStepper(
            "Elements", spk.arrayElements,
            { vm.setSpeakerArrayElements(spk.id, it) }, 1..24
        )
        if (spk.arrayElements > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumericField(
                    "Spacing", spk.arraySpacingM, { vm.setSpeakerArraySpacing(spk.id, it) },
                    Modifier.weight(1f), unit = "m", range = 0.05f..2f, decimals = 2, dragStep = 0.01f
                )
                NumericField(
                    "Splay", spk.arrayInterBoxSplayDeg, { vm.setSpeakerArraySplay(spk.id, it) },
                    Modifier.weight(1f), unit = "°", range = 0f..15f, dragStep = 0.1f
                )
            }
            NumericField(
                "Edge taper", spk.arrayEdgeTaperDb, { vm.setSpeakerArrayEdgeTaper(spk.id, it) },
                unit = "dB", range = 0f..12f, dragStep = 0.5f
            )
        }
    }

    InspectorSection("Processing") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(
                "Gain", dsp.gainDb, { vm.setGain(spk.id, it) },
                Modifier.weight(1f), unit = "dB", range = -12f..12f, dragStep = 0.5f
            )
            NumericField(
                "Delay", dsp.delayMs, { vm.setDelay(spk.id, it) },
                Modifier.weight(1f), unit = "ms", range = 0f..200f, decimals = 2, dragStep = 0.1f
            )
        }
        SectionLabel("Polarity")
        SegmentedControl(
            options = listOf(false, true),
            selected = dsp.polarity,
            onSelect = { vm.setPolarity(spk.id, it) },
            label = { if (it) "Inverted (180°)" else "Normal (0°)" }
        )
    }

    InspectorSection("Transform") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction("Duplicate", Modifier.weight(1f)) { vm.duplicateSpeaker(spk.id) }
            SmallAction("Mirror X", Modifier.weight(1f)) { vm.mirrorSpeakerX(spk.id, null) }
            SmallAction("Mirror Z", Modifier.weight(1f)) { vm.mirrorSpeakerY(spk.id, null) }
        }
        SmallAction("Remove speaker", Modifier.fillMaxWidth(), Instrument.Critical) {
            vm.removeSpeaker(spk.id)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Zone
// ─────────────────────────────────────────────────────────────────────────────

private val ZONE_TYPES = listOf(
    "AUDIENCE_SEATED" to "Seated",
    "AUDIENCE_STANDING" to "Standing"
)

@Composable
private fun ZoneInspector(vm: SceneViewModel, zone: AudienceArea) {

    InspectorSection("Type") {
        SegmentedControl(
            options = ZONE_TYPES.map { it.first },
            selected = zone.zoneType,
            onSelect = { vm.setAudienceAreaType(zone.id, it) },
            label = { key -> ZONE_TYPES.first { it.first == key }.second }
        )
    }

    InspectorSection("Geometry") {
        NumericField(
            "Base height", zone.baseHeightM, { vm.setAudienceAreaBaseHeight(zone.id, it) },
            unit = "m", range = -5f..30f, decimals = 2, dragStep = 0.05f
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(
                "Rake", zone.rakeDeg, { vm.setAudienceAreaRake(zone.id, it) },
                Modifier.weight(1f), unit = "°", range = 0f..45f, dragStep = 0.5f
            )
            NumericField(
                "Rake dir", zone.rakeDirectionDeg,
                { vm.setAudienceAreaRakeDirection(zone.id, it) },
                Modifier.weight(1f), unit = "°", range = 0f..360f, dragStep = 1f
            )
        }
        NumericField(
            "Rotation", zone.rotationDeg, { vm.setAudienceAreaRotation(zone.id, it) },
            unit = "°", range = 0f..360f, dragStep = 1f
        )
        Readout("Vertices", "${zone.vertices.size}")
    }

    InspectorSection("Transform") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction("Duplicate", Modifier.weight(1f)) { vm.duplicateAudienceArea(zone.id) }
            SmallAction("Mirror X", Modifier.weight(1f)) { vm.mirrorAudienceAreaX(zone.id, null) }
            SmallAction("Mirror Z", Modifier.weight(1f)) { vm.mirrorAudienceAreaZ(zone.id, null) }
        }
        SmallAction("Convert to venue block", Modifier.fillMaxWidth()) {
            vm.createVenueBlockFromArea(zone.id, false)
        }
        SmallAction("Remove zone", Modifier.fillMaxWidth(), Instrument.Critical) {
            vm.removeAudienceArea(zone.id)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Block
// ─────────────────────────────────────────────────────────────────────────────

private val BLOCK_TYPES = listOf("STAGE", "OBSTACLE", "WALL", "BALCONY", "SEATING")

@Composable
private fun BlockInspector(vm: SceneViewModel, block: VenueBlock) {

    InspectorSection("Type") {
        SegmentedControl(
            options = BLOCK_TYPES,
            selected = block.type,
            onSelect = { vm.setVenueBlockType(block.id, it) },
            label = { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        )
    }

    InspectorSection("Placement") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(
                "Centre X", block.centerX,
                { vm.setVenueBlockCenter(block.id, it, block.centerZ) },
                Modifier.weight(1f), unit = "m", decimals = 2, dragStep = 0.1f
            )
            NumericField(
                "Centre Z", block.centerZ,
                { vm.setVenueBlockCenter(block.id, block.centerX, it) },
                Modifier.weight(1f), unit = "m", decimals = 2, dragStep = 0.1f
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(
                "Width", block.widthM,
                { vm.setVenueBlockSize(block.id, it, block.depthM) },
                Modifier.weight(1f), unit = "m", range = 0.1f..100f, decimals = 2, dragStep = 0.1f
            )
            NumericField(
                "Depth", block.depthM,
                { vm.setVenueBlockSize(block.id, block.widthM, it) },
                Modifier.weight(1f), unit = "m", range = 0.1f..100f, decimals = 2, dragStep = 0.1f
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(
                "Elevation", block.heightM, { vm.setVenueBlockHeight(block.id, it) },
                Modifier.weight(1f), unit = "m", range = -5f..30f, decimals = 2, dragStep = 0.05f
            )
            NumericField(
                "Thickness", block.blockHeightM, { vm.setVenueBlockThickness(block.id, it) },
                Modifier.weight(1f), unit = "m", range = 0.05f..30f, decimals = 2, dragStep = 0.05f
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(
                "Slope", block.slopeDeg, { vm.setVenueBlockSlope(block.id, it) },
                Modifier.weight(1f), unit = "°", range = -45f..45f, dragStep = 0.5f
            )
            NumericField(
                "Rotation", block.rotationDeg, { vm.setVenueBlockRotation(block.id, it) },
                Modifier.weight(1f), unit = "°", range = 0f..360f, dragStep = 1f
            )
        }
    }

    InspectorSection("Transform") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction("Duplicate", Modifier.weight(1f)) { vm.duplicateVenueBlock(block.id) }
            SmallAction("Mirror X", Modifier.weight(1f)) { vm.mirrorVenueBlockX(block.id, null) }
            SmallAction("Mirror Z", Modifier.weight(1f)) { vm.mirrorVenueBlockZ(block.id, null) }
        }
        SmallAction("Remove block", Modifier.fillMaxWidth(), Instrument.Critical) {
            vm.removeVenueBlock(block.id)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Listener
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ListenerInspector(vm: SceneViewModel) {
    val listener = vm.listener.collectAsStateValue()
    val results = vm.results.collectAsStateValue()
    val combined = vm.combinedSplDb.collectAsStateValue()

    InspectorSection("Position") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(
                "X", listener.x, { vm.moveListener(it, listener.z) },
                Modifier.weight(1f), unit = "m", decimals = 2, dragStep = 0.1f
            )
            NumericField(
                "Z", listener.z, { vm.moveListener(listener.x, it) },
                Modifier.weight(1f), unit = "m", decimals = 2, dragStep = 0.1f
            )
        }
        Readout("Ear height", "%.2f m".format(listener.earHeightM))
    }

    InspectorSection("Level at this point") {
        Readout(
            "Combined",
            combined?.let { "%.1f dB".format(it) } ?: "—",
            valueColor = MaterialTheme.colorScheme.onSurface
        )
        if (results.isEmpty()) {
            Text(
                "No speakers contributing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            results.sortedByDescending { it.splDb }.forEach { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                ) {
                    Text(
                        r.speaker.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Text(
                        "%.1f m".format(r.distanceM),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = false
                    )
                    Text(
                        "   %.1f dB".format(r.splDb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Placement pickers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Chooses which loudspeaker the next floor tap will place.
 *
 * Cascading brand → series → model, because the bundled library runs to
 * hundreds of boxes across seven manufacturers and a flat list is unusable on
 * a touch screen.
 */
@Composable
private fun SpeakerPickerInspector(vm: SceneViewModel) {
    val presets = vm.speakerPresets.collectAsStateValue()
    val selectedId = vm.selectedPresetId.collectAsStateValue()
    val selected = presets.firstOrNull { it.id == selectedId }

    if (presets.isEmpty()) {
        Text(
            "No speaker presets loaded. Open Settings and load the bundled catalogue.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val brands = presets.map { it.brand }.distinct().sorted()
    val brand = selected?.brand ?: brands.first()
    val seriesList = presets.filter { it.brand == brand }.map { it.series }.distinct().sorted()
    val series = selected?.series?.takeIf { seriesList.contains(it) } ?: seriesList.first()
    val models = presets.filter { it.brand == brand && it.series == series }.sortedBy { it.name }

    InspectorSection("Speaker to place") {
        SectionLabel("Brand")
        SegmentedControl(
            options = brands,
            selected = brand,
            onSelect = { b ->
                presets.firstOrNull { it.brand == b }?.let { vm.setSpeakerPreset(it.id) }
            },
            label = { it }
        )
        SectionLabel("Series")
        SegmentedControl(
            options = seriesList,
            selected = series,
            onSelect = { s ->
                presets.firstOrNull { it.brand == brand && it.series == s }
                    ?.let { vm.setSpeakerPreset(it.id) }
            },
            label = { it }
        )
        SectionLabel("Model")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            models.forEach { p ->
                val isSel = p.id == selectedId
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSel) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { vm.setSpeakerPreset(p.id) }
                        .padding(horizontal = 10.dp, vertical = 9.dp)
                ) {
                    Column {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "%.0f dB @1m · %d element%s".format(
                                p.sensitivityDb,
                                p.arrayElements,
                                if (p.arrayElements == 1) "" else "s"
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (selected != null) {
        InspectorSection("Selected") {
            Readout("Model", selected.name)
            Readout("Sensitivity", "%.1f dB SPL @ 1 W / 1 m".format(selected.sensitivityDb))
            Readout("Default height", "%.2f m".format(selected.heightM))
        }
    }
}

/** Zone type and rake for the zone about to be drawn. */
@Composable
private fun ZonePickerInspector(vm: SceneViewModel) {
    val zoneType = vm.activeZoneType.collectAsStateValue()
    val baseHeight = vm.activeZoneBaseHeightM.collectAsStateValue()
    val rake = vm.activeZoneRakeDeg.collectAsStateValue()
    val rakeDir = vm.activeZoneRakeDirectionDeg.collectAsStateValue()

    InspectorSection("Zone to draw") {
        SectionLabel("Type")
        SegmentedControl(
            options = ZONE_TYPES.map { it.first },
            selected = zoneType,
            onSelect = { vm.setActiveZoneType(it) },
            label = { key -> ZONE_TYPES.first { it.first == key }.second }
        )
        NumericField(
            "Base height", baseHeight, { vm.setActiveZoneBaseHeight(it) },
            unit = "m", range = -5f..30f, decimals = 2, dragStep = 0.05f
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(
                "Rake", rake, { vm.setActiveZoneRakeDeg(it) },
                Modifier.weight(1f), unit = "°", range = 0f..45f, dragStep = 0.5f
            )
            NumericField(
                "Rake dir", rakeDir, { vm.setActiveZoneRakeDirectionDeg(it) },
                Modifier.weight(1f), unit = "°", range = 0f..360f, dragStep = 1f
            )
        }
        Text(
            "Tap the floor to add corners. Close the shape from the buttons above the viewport.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Venue (the no-selection default)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VenueInspector(vm: SceneViewModel, venue: VenueGeometry) {

    Text(
        "Select something in the 3D view to edit it, or adjust the room below.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 14.dp)
    )

    InspectorSection("Room") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(
                "Width", venue.widthM,
                { vm.setVenueSize(it, venue.depthM) },
                Modifier.weight(1f), unit = "m", range = 4f..200f, decimals = 2, dragStep = 0.5f
            )
            NumericField(
                "Depth", venue.depthM,
                { vm.setVenueSize(venue.widthM, it) },
                Modifier.weight(1f), unit = "m", range = 4f..200f, decimals = 2, dragStep = 0.5f
            )
        }
        NumericField(
            "Ceiling height", venue.wallHeightM, { vm.setVenueWallHeight(it) },
            unit = "m", range = 2f..50f, decimals = 2, dragStep = 0.25f
        )
    }

    InspectorSection("Stage") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(
                "Centre X", venue.stageCenterX,
                { vm.setStageCenter(it, venue.stageCenterZ) },
                Modifier.weight(1f), unit = "m", decimals = 2, dragStep = 0.25f
            )
            NumericField(
                "Centre Z", venue.stageCenterZ,
                { vm.setStageCenter(venue.stageCenterX, it) },
                Modifier.weight(1f), unit = "m", decimals = 2, dragStep = 0.25f
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(
                "Width", venue.stageWidthM,
                { vm.setStageSize(it, venue.stageDepthM) },
                Modifier.weight(1f), unit = "m", range = 0.5f..100f, decimals = 2, dragStep = 0.25f
            )
            NumericField(
                "Depth", venue.stageDepthM,
                { vm.setStageSize(venue.stageWidthM, it) },
                Modifier.weight(1f), unit = "m", range = 0.5f..100f, decimals = 2, dragStep = 0.25f
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(
                "Height", venue.stageHeightM, { vm.setStageHeight(it) },
                Modifier.weight(1f), unit = "m", range = 0f..10f, decimals = 2, dragStep = 0.05f
            )
            NumericField(
                "Slope", venue.stageSlopeDeg, { vm.setStageSlope(it) },
                Modifier.weight(1f), unit = "°", range = -15f..15f, dragStep = 0.5f
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun SmallAction(
    label: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: () -> Unit
) {
    val content = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 38.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            softWrap = false
        )
    }
}

/** Small helper so inspectors can read flows without ceremony. */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateValue(): T {
    val state = this.collectAsState()
    return state.value
}
