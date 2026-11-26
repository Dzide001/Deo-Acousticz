package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import com.droidacoustic.pro.traces.TraceBandComparison
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
import androidx.compose.material3.AlertDialog
import com.droidacoustic.pro.alignment.AlignmentDelayPreview
import com.droidacoustic.pro.traces.TraceComparisons
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.droidacoustic.pro.geometry.*
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.droidacoustic.pro.scene.elementAimsDeg
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
            key(selection) {
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

            // Per-box angles. "Splay" above sets every joint at once; this opens
            // them individually, which is the only way to hang a real array -
            // tight at the top for the far throw, opening downward for the near
            // field. The angle beside each box is its resulting absolute tilt,
            // which is the number that gets rigged, not the joint angle.
            var perBox by remember(spk.id) { mutableStateOf(false) }
            val aims = spk.elementAimsDeg()
            SmallAction(
                if (perBox) "Hide per-box angles" else "Per-box angles",
                Modifier.fillMaxWidth()
            ) { perBox = !perBox }

            if (perBox) {
                Text(
                    "Box 1 (top) ${"%+.1f".format(aims.first())}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = Instrument.InkDim
                )
                (0 until spk.arrayElements - 1).forEach { joint ->
                    val splay = spk.arraySplayByBoxDeg.getOrNull(joint)
                        ?: spk.arrayInterBoxSplayDeg
                    NumericField(
                        "Box ${joint + 2}  (${"%+.1f".format(aims[joint + 1])}°)",
                        splay,
                        { vm.setSpeakerArraySplayAt(spk.id, joint, it) },
                        unit = "°",
                        range = 0f..10f,
                        dragStep = 0.1f
                    )
                }
                Text(
                    "Each value is the angle between that box and the one above " +
                        "it. The array is re-centred on its stated tilt, so opening " +
                        "a joint spreads the fan rather than swinging it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Instrument.InkDim
                )
            }
        }
    }

    InspectorSection("Processing") {
        DspFilterControls(vm,spk.id,dsp.filters)
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

    if (zone.importedMesh!=null) InspectorSection("Imported audience mesh") {
        Readout("Vertices", "${zone.importedMesh.vertices().size}")
        Readout("Triangles", "${zone.importedMesh.triangles().size}")
        Readout("Boundary loops", "${zone.importedMesh.boundaryLoops().size}")
        Text("Assign faces to seated or standing audiences, follow the zone type, or exclude them from prediction. Excluded faces stay visible in gray and contribute no coverage area. Listening offsets are vertical. Plan cutouts apply to every assigned elevation in this zone.",
            style=MaterialTheme.typography.bodySmall)
    }
    if (zone.importedMesh!=null) InspectorSection("Face assignments") {
        val mesh=zone.importedMesh
        val count=mesh.triangles().size
        var first by remember(zone.id,count) {mutableStateOf(1)}
        var last by remember(zone.id,count) {mutableStateOf(1)}
        var assignment by remember(zone.id) {mutableStateOf(MeshFaceAssignment.FOLLOW_ZONE)}
        val selection=vm.importedFaceSelection.collectAsState().value?.takeIf {it.areaId==zone.id}?.faceIndices.orEmpty()
        DisposableEffect(zone.id) {onDispose {
            if (vm.importedFaceSelection.value?.areaId==zone.id) vm.clearImportedFaceSelection()
        } }
        fun label(role: MeshFaceAssignment)=when(role) {
            MeshFaceAssignment.FOLLOW_ZONE -> "Zone"
            MeshFaceAssignment.SEATED -> "Seated"
            MeshFaceAssignment.STANDING -> "Standing"
            MeshFaceAssignment.EXCLUDED -> "Excluded"
        }
        val tapMode=vm.facePickMode.collectAsState().value
        Text("Choose the Faces tool, then tap an imported face to highlight it. Orbit to reach another side. Tap empty space to clear the highlight.",style=MaterialTheme.typography.bodySmall)
        SegmentedControl(options=FacePickMode.entries.toList(),selected=tapMode,onSelect=vm::setFacePickMode,label={when(it) {
            FacePickMode.SINGLE -> "Single"
            FacePickMode.TOGGLE -> "Add/remove"
            FacePickMode.FLAT_PATCH -> "Flat patch"
            FacePickMode.GROUP -> "Group"
        }})
        Text("Or choose original OBJ triangle numbers (starting at 1), preview them in yellow, then apply. Cyan: follows zone; green: seated; purple: standing; gray: excluded.",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            NumericField("First face",first.toFloat(),{first=it.toInt().coerceIn(1,count)},Modifier.weight(1f),range=1f..count.toFloat(),decimals=0,dragStep=1f)
            NumericField("Last face",last.toFloat(),{last=it.toInt().coerceIn(1,count)},Modifier.weight(1f),range=1f..count.toFloat(),decimals=0,dragStep=1f)
        }
        SmallAction("Preview range") {vm.selectImportedFaces(zone.id,(minOf(first,last)-1 until maxOf(first,last)).toList())}
        SmallAction("Preview connected flat patch at first face") {vm.selectImportedFaces(zone.id,mesh.coplanarFaces(first-1))}
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            SmallAction("Select all",Modifier.weight(1f)) {vm.selectImportedFaces(zone.id,(0 until count).toList())}
            SmallAction("Clear highlight",Modifier.weight(1f)) {vm.clearImportedFaceSelection()}
        }
        Readout("Selected", "${selection.size} faces")
        if (selection.isNotEmpty()) Readout("Current",selection.map {label(mesh.faceAssignment(it))}.distinct().joinToString(", "))
        val groups=mesh.faceGroups()
        var groupId by remember(zone.id) {mutableStateOf<Int?>(null)}
        var groupName by remember(zone.id) {mutableStateOf("")}
        var groupsOpen by remember(zone.id) {mutableStateOf(false)}
        val group=groups.firstOrNull {it.id==groupId}
        Text("Named groups",style=MaterialTheme.typography.titleSmall)
        Text("Each face belongs to at most one group. Moving faces preserves their assignments. Group tap mode recalls a whole group; ungrouped faces select individually.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(value=groupName,onValueChange={groupName=it},label={Text("Group name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        SmallAction("Create group from selection") {
            if (vm.createImportedFaceGroup(zone.id,groupName,selection))
                groupId=vm.audienceAreas.value.firstOrNull {it.id==zone.id}?.importedMesh?.faceGroups()?.lastOrNull()?.id
        }
        if (groups.isNotEmpty()) {
            Box {
                SmallAction(group?.let {"Group: ${it.name} (${it.faces().size} faces)"} ?: "Choose a group") {groupsOpen=true}
                DropdownMenu(groupsOpen,{groupsOpen=false}) {
                    groups.forEach {g -> DropdownMenuItem(text={Text("${g.name} (${g.faces().size})")},onClick={
                        groupId=g.id; groupName=g.name; groupsOpen=false
                    })}
                }
            }
            if (group!=null) {
                SmallAction("Select group faces") {vm.selectImportedFaceGroup(zone.id,group.id)}
                SmallAction("Rename group") {vm.renameImportedFaceGroup(zone.id,group.id,groupName)}
                SmallAction("Move selected faces into group") {vm.addToImportedFaceGroup(zone.id,group.id,selection)}
                SmallAction("Replace group with selection") {vm.setImportedFaceGroupMembers(zone.id,group.id,selection)}
                SmallAction("Delete group (keep faces and assignments)") {
                    if (vm.removeImportedFaceGroup(zone.id,group.id)) {groupId=null; groupName=""}
                }
            }
            SmallAction("Remove selected faces from groups") {vm.ungroupImportedFaces(zone.id,selection)}
        }
        MeshFaceAssignment.entries.forEach {role -> Readout(label(role),"${mesh.faceAssignments().count {it==role}} faces")}
        SegmentedControl(options=MeshFaceAssignment.entries.toList(),selected=assignment,onSelect={assignment=it},label=::label)
        Text("Zone follows the zone's audience type. Seated uses +1.2 m and standing +1.6 m listening height. Exclusion is reversible and does not delete the face.",style=MaterialTheme.typography.bodySmall)
        SmallAction("Apply to selected faces") {if (selection.isNotEmpty()) vm.assignImportedFaces(zone.id,selection,assignment)}
        if (group!=null) SmallAction("Apply to group: ${group.name}") {vm.assignImportedFaceGroup(zone.id,group.id,assignment)}
        var boundaryRole by remember(zone.id) {mutableStateOf(AcousticBoundaryRole.NONE)}
        fun boundaryLabel(role: AcousticBoundaryRole)=when(role) {
            AcousticBoundaryRole.NONE -> "No boundary"
            AcousticBoundaryRole.OCCLUDING -> "Opaque blocker"
            AcousticBoundaryRole.RIGID_REFLECTOR -> "Rigid reflector"
        }
        Text("Acoustic boundary",style=MaterialTheme.typography.titleSmall)
        Text("Separate from audience assignments. Opaque blockers stop crossing paths. Rigid reflectors also add a finite first-order reflection. No diffraction, transmission or multiple bounces. Coverage cutouts do not cut physical boundaries; exclude faces from audience coverage separately. Red edges: blockers; blue edges: reflectors. At ground level, opaque patches suppress the ground bounce; rigid patches use the global ground absorption when enabled.",style=MaterialTheme.typography.bodySmall)
        if (selection.isNotEmpty()) Readout("Selected boundaries",selection.map {boundaryLabel(mesh.boundaryRole(it))}.distinct().joinToString(", "))
        BoundaryRoleControl(boundaryRole) {boundaryRole=it}
        SmallAction("Set boundary on selected faces") {if (selection.isNotEmpty()) vm.assignImportedBoundaryRole(zone.id,selection,boundaryRole)}
        if (group!=null) SmallAction("Set boundary on group: ${group.name}") {vm.assignImportedGroupBoundaryRole(zone.id,group.id,boundaryRole)}
        val selectedMaterials = selection.map {mesh.boundaryMaterial(it)}.distinct()
        var material by remember(zone.id,selection,selectedMaterials) {mutableStateOf(selectedMaterials.singleOrNull() ?: BoundaryMaterial.IDEAL_RIGID)}
        BoundaryMaterialControl(material) {material=it}
        if(selection.isNotEmpty()) Readout("Selected materials",selection.map {mesh.boundaryMaterial(it).label}.distinct().joinToString(", "))
        SmallAction("Set material on selected faces") {vm.setImportedMaterial(zone.id,selection,material)}
        if(group!=null) SmallAction("Set material on group: ${group.name}") {vm.setImportedMaterial(zone.id,group.faces(),material)}
        Text("These roles affect coverage and listener predictions. The experimental room-reflection list uses its separate room model.",style=MaterialTheme.typography.bodySmall)

        vm.audienceGeometryError.collectAsState().value?.let {Text(it,color=Instrument.Critical,style=MaterialTheme.typography.bodySmall)}
    }
    if (zone.importedMesh==null) InspectorSection("Surface elevations") {
        Text("Offsets add to the base height and rake. Add interior points for changes in slope.",
            style=MaterialTheme.typography.bodySmall)
        zone.vertices.forEachIndexed { i,p ->
            Text("Vertex ${i+1} · x %.2f, z %.2f m".format(p.first,p.second),style=MaterialTheme.typography.bodySmall)
            NumericField("Vertex ${i+1} offset",zone.vertexOffsetsM.getOrElse(i) {0f},
                { vm.setAudienceVertexOffset(zone.id,i,it) },unit="m",range=-100f..100f,decimals=2,dragStep=0.05f)
        }
        SmallAction("Add interior height point") { vm.beginAudienceElevationPoint(zone.id) }
        zone.elevationPoints.forEachIndexed { i,p ->
            Text("Point ${i+1} · x %.2f, z %.2f m".format(p.x,p.z),style=MaterialTheme.typography.bodySmall)
            NumericField("Point ${i+1} offset",p.offsetM,{ vm.setAudienceElevationPointOffset(zone.id,i,it) },
                unit="m",range=-100f..100f,decimals=2,dragStep=0.05f)
            SmallAction("Remove point ${i+1}") { vm.removeAudienceElevationPoint(zone.id,i) }
        }
        if (zone.vertexOffsetsM.isNotEmpty() || zone.elevationPoints.isNotEmpty() || zone.breaklines.isNotEmpty())
            SmallAction("Reset to base and rake") { vm.resetAudienceElevations(zone.id) }
    }

    if (zone.importedMesh==null) InspectorSection("Breaklines") {
        Text("Connect vertices or height points to preserve a ridge or change in slope. Crossing lines need a height point at the intersection. Removing a point removes its attached lines.",
            style=MaterialTheme.typography.bodySmall)
        val endpoints=zone.vertices.indices.map { AudienceVertexRef(AudienceVertexKind.BOUNDARY,it) }+
            zone.elevationPoints.indices.map { AudienceVertexRef(AudienceVertexKind.INTERIOR,it) }
        fun label(ref: AudienceVertexRef) = "${if (ref.kind==AudienceVertexKind.BOUNDARY) "Vertex" else "Point"} ${ref.index+1}"
        if (endpoints.size>=2) {
            var start by remember(zone.id,endpoints) { mutableStateOf(endpoints[0]) }
            var end by remember(zone.id,endpoints) { mutableStateOf(endpoints[1]) }
            var startOpen by remember { mutableStateOf(false) }
            var endOpen by remember { mutableStateOf(false) }
            Box {
                SmallAction("From: ${label(start)}") { startOpen=true }
                DropdownMenu(startOpen,{ startOpen=false }) {
                    endpoints.forEach { ref -> DropdownMenuItem(text={ Text(label(ref)) },onClick={ start=ref; startOpen=false }) }
                }
            }
            Box {
                SmallAction("To: ${label(end)}") { endOpen=true }
                DropdownMenu(endOpen,{ endOpen=false }) {
                    endpoints.forEach { ref -> DropdownMenuItem(text={ Text(label(ref)) },onClick={ end=ref; endOpen=false }) }
                }
            }
            SmallAction("Add breakline") { vm.addAudienceBreakline(zone.id,start,end) }
        }
        zone.breaklines.forEachIndexed { i,line ->
            Text("${label(line.start)} → ${label(line.end)}",style=MaterialTheme.typography.bodySmall)
            SmallAction("Remove breakline ${i+1}") { vm.removeAudienceBreakline(zone.id,i) }
        }
        vm.audienceGeometryError.collectAsState().value?.let {
            Text(it,color=Instrument.Critical,style=MaterialTheme.typography.bodySmall)
        }
    }

    InspectorSection("Excluded areas") {
        vm.audienceGeometryError.collectAsState().value?.let {
            Text(it,color=Instrument.Critical,style=MaterialTheme.typography.bodySmall)
        }
        Readout("Holes", "${zone.holes.size}")
        Readout("Exclusions", "${zone.exclusions.size}")
        Text(if (zone.importedMesh==null) "Holes stay inside this zone. Exclusions may cross its boundary. Other floors are unaffected."
            else "Plan cutouts remove every elevation in this imported zone, including vertical faces. Other zones are unaffected.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction("Draw hole", Modifier.weight(1f)) { vm.beginAudienceCutout(zone.id,true) }
            SmallAction("Draw exclusion", Modifier.weight(1f)) { vm.beginAudienceCutout(zone.id,false) }
        }
        zone.holes.indices.forEach { i ->
            SmallAction("Remove hole ${i+1}") { vm.removeAudienceCutout(zone.id,i,true) }
        }
        zone.exclusions.indices.forEach { i ->
            SmallAction("Remove exclusion ${i+1}") { vm.removeAudienceCutout(zone.id,i,false) }
        }
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

@Composable
private fun BoundaryMaterialControl(material: BoundaryMaterial,onSelect: (BoundaryMaterial)->Unit) {
    var editing by remember {mutableStateOf(false)}
    var scatteringEditing by remember {mutableStateOf(false)}
    var scatteringRows by remember {mutableStateOf("")}
    var scatteringSource by remember {mutableStateOf("")}
    var phaseEditing by remember {mutableStateOf(false)}
    var phaseRows by remember {mutableStateOf("")}
    var phaseSource by remember {mutableStateOf("")}
    var angular by remember {mutableStateOf(false)}
    var curveName by remember {mutableStateOf("")}
    var curvePoints by remember {mutableStateOf("")}
    var curveSource by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    var open by remember {mutableStateOf(false)}
    if(scatteringEditing) androidx.compose.material3.AlertDialog(
        onDismissRequest={scatteringEditing=false},title={Text("Material scattering")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Enter angle, Hz, scattering per line, grouped by increasing angle and frequency. Values 0–1 describe the fraction of reflected energy redirected away from the specular reflection, after absorption. One angle applies at all angles; 0° is normal incidence, 90° grazing. Use at least two frequencies per angle. Frequency interpolation is logarithmic, angle interpolation linear; endpoints are held. Scattered energy is propagated as first-bounce Lambertian diffuse sound, separately from specular reflections. This assumes a cosine distribution, not a measured scattering pattern. Multiple bounces and reverberant decay are not simulated. Use scattering coefficients, not diffusion coefficients.")
            OutlinedTextField(scatteringSource,{scatteringSource=it},label={Text("Scattering measurement / mounting notes")})
            OutlinedTextField(scatteringRows,{scatteringRows=it},label={Text("Angle, Hz, scattering (0–1)")},minLines=4)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={SmallAction("Apply scattering") {
            try {onSelect(material.withScattering(MaterialScattering.parse(scatteringRows,scatteringSource)));scatteringEditing=false}
            catch(e: IllegalArgumentException) {error=e.message ?: "Invalid scattering"}
            catch(e: IllegalStateException) {error=e.message ?: "Invalid scattering"}
        }},dismissButton={SmallAction("Cancel") {scatteringEditing=false}})
    if(phaseEditing) androidx.compose.material3.AlertDialog(
        onDismissRequest={phaseEditing=false},title={Text("Measured reflection phase")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Enter angle, Hz, unwrapped phase degrees per line. 0° incidence is normal; 90° is grazing. One angle applies at all angles. Use increasing angles and frequencies, at least two frequencies per angle. Phase uses exp(+iωt): a delay has negative phase. Unwrap measurements before entry (179° to 181°, not −179°). Linear interpolation preserves turns; endpoints are held. Use the surface reflection coefficient, excluding travel to the measurement microphone.")
            OutlinedTextField(phaseSource,{phaseSource=it},label={Text("Phase measurement / reference notes")})
            OutlinedTextField(phaseRows,{phaseRows=it},label={Text("Angle, Hz, phase degrees")},minLines=4)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={SmallAction("Apply phase") {
            try {onSelect(material.withReflectionPhase(ReflectionPhase.parse(phaseRows,phaseSource)));phaseEditing=false}
            catch(e: IllegalArgumentException) {error=e.message ?: "Invalid phase"}
            catch(e: IllegalStateException) {error=e.message ?: "Invalid phase"}
        }},dismissButton={SmallAction("Cancel") {phaseEditing=false}})
    if (editing) androidx.compose.material3.AlertDialog(
        onDismissRequest={editing=false}, title={Text(if(angular) "Angle-dependent absorption" else "Measured absorption curve")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(angular) Text("Use angle degrees, Hz, absorption on each line. Group rows by increasing angle and frequency. 0° is normal incidence; 90° is grazing. Enter at least two angles with two frequencies each. Absorption must be 0–1. Interpolation is linear in angle and logarithmic in frequency, with nearest-endpoint values outside the measured range.")
            else Text("Enter energy absorption coefficients, not percentages. Use one Hz, absorption pair per line in increasing frequency order. Values must be 0–1. Interpolation uses log frequency; values beyond your measurements use the nearest endpoint.")
            OutlinedTextField(curveName,{curveName=it},label={Text("Material name")})
            OutlinedTextField(curveSource,{curveSource=it},label={Text("Measurement source / mounting notes")})
            OutlinedTextField(curvePoints,{curvePoints=it},label={Text(if(angular) "Angle, Hz, absorption" else "Hz, absorption")},minLines=4)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},
        confirmButton={SmallAction("Apply curve") {
            try {onSelect((if(angular) BoundaryMaterial.parseAngular(curveName,curvePoints,curveSource) else BoundaryMaterial.parseMeasured(curveName,curvePoints,curveSource)).withReflectionPhase(material.reflectionPhase).withScattering(material.scattering)); editing=false}
            catch(e: IllegalArgumentException) {error=e.message ?: "Invalid curve"}
            catch(e: IllegalStateException) {error=e.message ?: "Invalid curve"}
        }}, dismissButton={SmallAction("Cancel") {editing=false}})
    Box {
        SmallAction("Material: ${material.label}") {open=true}
        DropdownMenu(open,{open=false}) {
            BoundaryMaterial.entries.forEach {m -> DropdownMenuItem(text={Text(m.label)},onClick={onSelect(m);open=false})}
            DropdownMenuItem(text={Text(if(material.isCustom && !material.isAngleDependent) "Edit measured curve…" else "Custom measured curve…")},onClick={
                angular=false
                curveName=if(material.isCustom) material.label else ""
                curveSource=material.source
                curvePoints=material.points().joinToString("\n") {"${it.first}, ${it.second}"}
                error=null; open=false; editing=true
            })
            DropdownMenuItem(text={Text(if(material.isAngleDependent) "Edit angle-dependent curve…" else "Angle-dependent curve…")},onClick={
                angular=true; curveName=if(material.isCustom) material.label else ""; curveSource=material.source
                val curves=if(material.isAngleDependent) material.angleCurves() else listOf(0f to material,90f to material)
                curvePoints=curves.flatMap {(angle,curve) -> curve.points().map {"$angle, ${it.first}, ${it.second}"}}.joinToString("\n")
                error=null; open=false; editing=true
            })
        }
    }
    SmallAction(if(material.reflectionPhase==null) "Add measured reflection phase…" else "Edit reflection phase…") {
        phaseSource=material.reflectionPhase?.source ?: ""
        phaseRows=material.reflectionPhase?.curves()?.flatMap {(a,p)->p.map {"$a, ${it.first}, ${it.second}"}}?.joinToString("\n") ?: "0, 125, 0\n0, 4000, 0"
        error=null;phaseEditing=true
    }
    if(material.reflectionPhase!=null) SmallAction("Remove measured phase") {onSelect(material.withReflectionPhase(null))}
    SmallAction(if(material.scattering==null) "Add material scattering…" else "Edit material scattering…") {
        scatteringSource=material.scattering?.source ?: ""
        scatteringRows=material.scattering?.curves()?.flatMap {(a,p)->p.map {"$a, ${it.first}, ${it.second}"}}?.joinToString("\n") ?: "0, 125, 0\n0, 4000, 0"
        error=null;scatteringEditing=true
    }
    if(material.scattering!=null) {
        SmallAction("Remove scattering") {onSelect(material.withScattering(null))}
        Text("Scattering adds first-bounce Lambertian diffuse sound. Adaptive sampling clips receiver shadows and refines near receivers and detected directivity changes. Measured speaker grids guide refinement for narrow lobes between probes. Features absent from the data and custom responses without angular bounds still need convergence checks. No multiple-bounce reverberation.",style=MaterialTheme.typography.bodySmall)
    }
    Text("Absorption affects Rigid reflections only. Presets are representative, not product measurements. Coincident materials use one response with the lowest specular reflected energy per band. Global ground absorption takes precedence at y=0 when enabled.",style=MaterialTheme.typography.bodySmall)
}

@Composable
private fun BoundaryRoleControl(role: AcousticBoundaryRole,onSelect: (AcousticBoundaryRole)->Unit) {
    SegmentedControl(options=AcousticBoundaryRole.entries.toList(),selected=role,onSelect=onSelect,label={when(it) {
        AcousticBoundaryRole.NONE -> "No boundary"
        AcousticBoundaryRole.OCCLUDING -> "Opaque"
        AcousticBoundaryRole.RIGID_REFLECTOR -> "Rigid"
    }})
}

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

    InspectorSection("Acoustic boundary") {
        BoundaryRoleControl(block.boundaryRole) {vm.setVenueBlockBoundaryRole(block.id,it)}
        BoundaryMaterialControl(block.boundaryMaterial) {vm.setVenueBlockMaterial(block.id,it)}
        Text("Uses the same boundary model as imported faces. No boundary is visual context only. Opaque stops crossing paths; rigid also reflects once. Block type is a label and does not choose its acoustic role. No diffraction or transmission. Coincident reflections count once; an opaque surface suppresses reflection where it overlaps a rigid one.",style=MaterialTheme.typography.bodySmall)
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
private fun AlignmentInspector(vm: SceneViewModel) {
    val references=vm.alignmentReferences.collectAsStateValue()
    val receiver=references.selected?.receiver
    val referenceId=references.selected?.referenceSourceId
    val speakers=vm.speakers.collectAsStateValue()
    val dsps=vm.dspMap.collectAsStateValue()
    val temperature=vm.temperatureC.collectAsStateValue()
    val humidity=vm.humidityPct.collectAsStateValue()
    val listener=vm.listener.collectAsStateValue()
    val probes=vm.audience.collectAsStateValue().filter {it.sourceAreaId==null}
    val allComparisons=remember(references,speakers,dsps,temperature,humidity) {vm.allAlignmentComparisons()}
    val comparisons=allComparisons.firstOrNull {it.receiver.id==references.selectedId}?.arrivals.orEmpty()
    var chooseReceiver by remember {mutableStateOf(false)}
    var showAll by remember {mutableStateOf(false)}
    var receiverName by remember(references.selectedId,receiver?.name) {mutableStateOf(receiver?.name.orEmpty())}
    var delayPreview by remember(references,speakers,dsps,temperature,humidity) {mutableStateOf<AlignmentDelayPreview?>(null)}
    delayPreview?.let {preview ->
        AlertDialog(onDismissRequest={delayPreview=null},
            title={Text("Preview delay change")},
            text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${speakers.firstOrNull {it.id==preview.sourceId}?.label}: %.2f → %.2f ms DSP delay".format(preview.previousDelayMs,preview.proposedDelayMs))
                Text("Before → after at each saved receiver. This is a timing comparison, not a coverage or phase prediction.")
                preview.before.zip(preview.after).forEach {(before,after)->
                    Text("${before.receiver.receiver.name} (#${before.receiver.id})",style=MaterialTheme.typography.titleSmall)
                    if(before.arrivals.isEmpty()) Text("Comparison unavailable: select an existing reference speaker.")
                    before.arrivals.zip(after.arrivals).forEach {(a,b)->
                        Text(speakers.firstOrNull {it.id==a.sourceId}?.label ?: "Speaker ${a.sourceId}")
                        Text("Arrival %.2f → %.2f ms; relative %+.2f → %+.2f ms".format(a.earliestMs,b.earliestMs,a.relativeArrivalMs,b.relativeArrivalMs))
                        if(a.sourceId!=before.receiver.referenceSourceId) {
                            val change=kotlin.math.abs(b.relativeArrivalMs)-kotlin.math.abs(a.relativeArrivalMs)
                            Text(if(change>0.001) "Timing mismatch increases by %.2f ms".format(change)
                                else if(change< -0.001) "Timing mismatch decreases by %.2f ms".format(-change) else "Timing mismatch unchanged")
                        }
                    }
                }
            }},
            confirmButton={TextButton(onClick={vm.applyAlignmentPreview(preview);delayPreview=null}) {Text("Apply delay")}},
            dismissButton={TextButton(onClick={delayPreview=null}) {Text("Cancel")}})
    }
    var chooseSource by remember {mutableStateOf(false)}
    var chooseProbe by remember {mutableStateOf(false)}
    InspectorSection("System alignment") {
        if(references.receivers.isNotEmpty()) SystemAdjustmentControls(vm)
        TextButton(onClick={vm.addAlignmentReceiver("Reference ${references.receivers.size+1}",listener.x,listener.earHeightM,listener.z)},enabled=references.receivers.size<64) {
            Text("Save listener as new reference")
        }
        if(probes.isNotEmpty() && references.receivers.size<64) Box {
            TextButton(onClick={chooseProbe=true}) {Text("Use named probe…")}
            DropdownMenu(expanded=chooseProbe,onDismissRequest={chooseProbe=false}) {
                probes.forEach {p->DropdownMenuItem(text={Text(p.name)},onClick={
                    vm.addAlignmentReceiver(p.name,p.x,p.earHeightM,p.z);chooseProbe=false
                })}
            }
        }
        if(receiver!=null) {
            Box {
                TextButton(onClick={chooseReceiver=true}) {Text("Saved receiver: ${receiver.name} (#${references.selectedId})")}
                DropdownMenu(expanded=chooseReceiver,onDismissRequest={chooseReceiver=false}) {
                    references.receivers.forEach {r->DropdownMenuItem(text={Text("${r.receiver.name} (#${r.id})")},onClick={
                        vm.selectAlignmentReceiver(r.id);chooseReceiver=false
                    })}
                }
            }
            OutlinedTextField(receiverName,{receiverName=it},label={Text("Receiver name")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row {
                TextButton(onClick={vm.renameAlignmentReceiver(references.selectedId!!,receiverName)},enabled=receiverName.isNotBlank() && receiverName!=receiver.name) {Text("Rename")}
                TextButton(onClick={vm.removeAlignmentReceiver(references.selectedId!!)}) {Text("Remove receiver")}
            }
            TextButton(onClick={showAll=!showAll}) {Text(if(showAll) "Hide receiver comparisons" else "Compare all saved receivers")}
            if(showAll) {
                Text("Delay changes affect every receiver. Suggestions below the comparison apply only to the selected receiver.",style=MaterialTheme.typography.bodySmall)
                allComparisons.forEach {result ->
                    Text("${result.receiver.receiver.name} (#${result.receiver.id})",style=MaterialTheme.typography.titleSmall)
                    if(result.arrivals.isEmpty()) Text("Choose an available reference speaker for this receiver.",style=MaterialTheme.typography.bodySmall)
                    result.arrivals.forEach {row ->
                        Readout(speakers.first {it.id==row.sourceId}.label,"%.2f ms · %+.2f ms relative".format(row.earliestMs,row.relativeArrivalMs))
                    }
                }
            }
            Readout(receiver.name,"X %.2f · Y %.2f · Z %.2f m".format(receiver.position.x,receiver.position.y,receiver.position.z))
            Text("Pinned position; moving the listener does not move this reference.",style=MaterialTheme.typography.bodySmall)
            val selected=speakers.firstOrNull {it.id==referenceId}
            Box {
                TextButton(onClick={chooseSource=true},enabled=speakers.isNotEmpty()) {Text("Reference speaker: "+(selected?.label ?: "None"))}
                DropdownMenu(expanded=chooseSource,onDismissRequest={chooseSource=false}) {
                    speakers.forEach {sp->DropdownMenuItem(text={Text(sp.label)},onClick={vm.setAlignmentSource(sp.id);chooseSource=false})}
                }
            }
            if(selected==null) Text("Choose a reference speaker to compare arrivals.",style=MaterialTheme.typography.bodySmall)
            comparisons.forEach {row ->
                val sp=speakers.first {it.id==row.sourceId}
                Readout(sp.label,"%.2f ms".format(row.earliestMs))
                if(row.latestMs-row.earliestMs>.001) Readout("Element arrivals","%.2f–%.2f ms".format(row.earliestMs,row.latestMs))
                Readout("Relative arrival","%+.2f ms (later +)".format(row.relativeArrivalMs))
                if(row.sourceId!=selected?.id) {
                    val proposed=row.proposedDelayMs
                    if(proposed==null) Text("Cannot match within 0–200 ms DSP delay.",style=MaterialTheme.typography.bodySmall)
                    else TextButton(onClick={delayPreview=vm.previewAlignmentDelay(row.sourceId)},enabled=kotlin.math.abs(row.delayChangeMs)>.001) {
                        Text("Preview delay %.2f ms (%+.2f ms)".format(proposed,row.delayChangeMs))
                    }
                }
            }
            Text("Geometric direct arrivals + DSP/steering delay. Reflections, blocked-path audibility, cabinet/filter phase and phase alignment are not evaluated here.",style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TraceOverlay(bands: List<TraceBandComparison>) {
    val levels=bands.flatMap {listOfNotNull(it.baselinePower,it.candidatePower)}.filter {it>0}.map {10*kotlin.math.log10(it)}
    if(levels.isEmpty() || bands.isEmpty()) return
    val low=levels.min()-3;val high=levels.max()+3
    val minX=kotlin.math.ln(bands.first().hz.toDouble());val maxX=kotlin.math.ln(bands.last().hz.toDouble())
    val aColor=Color(0xFF2196F3);val bColor=Color(0xFFFF9800)
    Text("A blue · B orange | %.1f–%.1f dB | %d–%d Hz (log scale)".format(low,high,bands.first().hz,bands.last().hz),style=MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        val inset=6.dp.toPx();val w=size.width-2*inset;val h=size.height-2*inset
        for(i in 0..4) {val y=inset+h*i/4;drawLine(Color.Gray.copy(alpha=.3f),Offset(inset,y),Offset(inset+w,y))}
        for(side in 0..1) {
            var previous: Offset?=null
            bands.forEach {band ->
                val power=if(side==0) band.baselinePower else band.candidatePower
                if(power==null || power<=0) previous=null else {
                    val x=inset+w*(if(maxX==minX) .5 else (kotlin.math.ln(band.hz.toDouble())-minX)/(maxX-minX)).toFloat()
                    val y=inset+h*((high-10*kotlin.math.log10(power))/(high-low)).toFloat()
                    val point=Offset(x,y);val color=if(side==0) aColor else bColor
                    previous?.let {drawLine(color,it,point,2.dp.toPx())}
                    drawCircle(color,3.dp.toPx(),point);previous=point
                }
            }
        }
    }
}

@Composable
private fun DesignVariantsInspector(vm: SceneViewModel) {
    val library=vm.designVariants.collectAsStateValue()
    var name by remember {mutableStateOf("Design 1")}
    var selectedId by remember {mutableStateOf<Int?>(null)}
    var choose by remember {mutableStateOf(false)}
    var restoreId by remember {mutableStateOf<Int?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    val selected=library.variants.firstOrNull {it.id==selectedId} ?: library.variants.lastOrNull()
    val restore=library.variants.firstOrNull {it.id==restoreId}
    if(restore!=null) AlertDialog(onDismissRequest={restoreId=null},title={Text("Restore ${restore.name}?")},
        text={Text("Restores speakers, DSP, geometry, analysis settings, receiver references and captured speaker data. Current edits can be recovered with Undo. Saved traces and the variant library are retained.")},
        confirmButton={TextButton(onClick={
            val ok=vm.restoreDesignVariant(restore.id);error=if(ok) null else vm.lastImportError.value;restoreId=null
        }) {Text("Restore design")}},dismissButton={TextButton(onClick={restoreId=null}) {Text("Cancel")}})
    InspectorSection("Saved design variants") {
        Text("Snapshots are not updated by later edits. Capture a new variant to preserve another design.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(name,{name=it},label={Text("New / renamed design")},singleLine=true,modifier=Modifier.fillMaxWidth())
        TextButton(onClick={
            val ok=vm.captureDesignVariant(name);error=if(ok) null else vm.lastImportError.value
            if(ok) selectedId=vm.designVariants.value.variants.last().id
        },enabled=name.isNotBlank() && name.length<=200 && library.variants.size<16) {Text("Capture new design variant")}
        Box {
            TextButton(onClick={choose=true},enabled=library.variants.isNotEmpty()) {Text("Saved snapshot: ${selected?.name ?: "None"}")}
            DropdownMenu(expanded=choose,onDismissRequest={choose=false}) {
                library.variants.forEach {v->DropdownMenuItem(text={Text("${v.name} (#${v.id})")},onClick={selectedId=v.id;choose=false})}
            }
        }
        if(selected!=null) {
            TextButton(onClick={restoreId=selected.id}) {Text("Restore selected design…")}
            Row {
                TextButton(onClick={vm.renameDesignVariant(selected.id,name)},enabled=name.isNotBlank() && name.length<=200 && name!=selected.name) {Text("Rename snapshot")}
                TextButton(onClick={vm.removeDesignVariant(selected.id)}) {Text("Delete snapshot")}
            }
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        CoverageComparisonInspector(vm)
    }
}

@Composable
private fun SavedTraceInspector(vm: SceneViewModel) {
    val saved=vm.savedTraces.collectAsStateValue()
    val current=vm.currentTrace.collectAsStateValue()
    var name by remember {mutableStateOf("Design A")}
    var baselineId by remember {mutableStateOf<Int?>(null)}
    var candidateId by remember {mutableStateOf<Int?>(null)}
    var picking by remember {mutableStateOf<String?>(null)}
    val references=vm.alignmentReferences.collectAsStateValue()
    val selected=vm.referenceTraceSelection.collectAsStateValue()
    val running=vm.referenceTraceRunning.collectAsStateValue()
    val message=vm.referenceTraceMessage.collectAsStateValue()
    var receiverFilter by remember {mutableStateOf<Int?>(null)}
    var filterMenu by remember {mutableStateOf(false)}
    val traces=saved.traces.filter {receiverFilter==null || it.metadata.referenceReceiverId==receiverFilter}
    LaunchedEffect(saved.inspectedSample) {
        saved.inspectedSample?.let {selection ->
            if(baselineId==null && traces.any {it.id==selection.baselineId}) baselineId=selection.baselineId
            if(candidateId==null && traces.any {it.id==selection.candidateId}) candidateId=selection.candidateId
        }
    }
    val a=traces.firstOrNull {it.id==(baselineId ?: saved.inspectedSample?.baselineId)} ?: traces.firstOrNull()
    val b=traces.firstOrNull {it.id==(candidateId ?: saved.inspectedSample?.candidateId)} ?: traces.lastOrNull()
    InspectorSection("Saved traces / design comparison") {
        Text("Historical responses. Recalculate before listener capture; Spectrum mode saves eight bands. Band levels are unweighted. Phase is shown separately when supported; no impulse response is inferred.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(name,{name=it},label={Text("Design / trace name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        TextButton(onClick={vm.captureListenerTrace(name)},enabled=current!=null && name.isNotBlank() && saved.traces.size<64) {Text("Capture current listener trace")}
        if(current==null) Text("Recalculate to capture a current result.",style=MaterialTheme.typography.bodySmall)
        Text("Capture saved reference receivers",style=MaterialTheme.typography.titleSmall)
        Text("Calculates the current design at each selected receiver. Captures are saved together; the listener stays in place.",style=MaterialTheme.typography.bodySmall)
        if(references.receivers.isEmpty()) Text("Save reference receivers in System alignment first.",style=MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick={vm.selectTraceReceivers(references.receivers.map {it.id})},enabled=references.receivers.isNotEmpty()) {Text("Select all")}
            TextButton(onClick={vm.selectTraceReceivers(emptyList())},enabled=selected.ids.isNotEmpty()) {Text("Clear selection")}
        }
        references.receivers.forEach {r ->
            Row(verticalAlignment=Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked=r.id in selected.ids,onCheckedChange={checked ->
                    vm.selectTraceReceivers(if(checked) selected.ids+r.id else selected.ids-r.id)
                })
                Text("${r.receiver.name} (#${r.id}) · X %.2f, Y %.2f, Z %.2f m".format(r.receiver.position.x,r.receiver.position.y,r.receiver.position.z),style=MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick={vm.captureReferenceTraces(name)},enabled=!running && selected.ids.isNotEmpty() && name.isNotBlank() && name.length<=200 && saved.traces.size+selected.ids.size<=64) {
            Text(if(running) "Capturing reference traces…" else "Capture ${selected.ids.size} selected reference traces")
        }
        Text("${saved.traces.size}/64 traces saved",style=MaterialTheme.typography.bodySmall)
        if(saved.traces.size+selected.ids.size>64) Text("Delete some saved traces or select fewer receivers to make room.")
        message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        Box {
            val label=saved.traces.firstOrNull {it.metadata.referenceReceiverId==receiverFilter}?.metadata?.referenceReceiverName
            TextButton(onClick={filterMenu=true}) {Text("Compare receiver: ${if(receiverFilter==null) "All traces" else "${label ?: "Reference"} (#$receiverFilter)"}")}
            DropdownMenu(expanded=filterMenu,onDismissRequest={filterMenu=false}) {
                DropdownMenuItem(text={Text("All traces")},onClick={receiverFilter=null;filterMenu=false;baselineId=null;candidateId=null})
                saved.traces.filter {it.metadata.referenceReceiverId!=null}.distinctBy {it.metadata.referenceReceiverId}.forEach {t ->
                    DropdownMenuItem(text={Text("${t.metadata.referenceReceiverName} (#${t.metadata.referenceReceiverId})")},onClick={
                        receiverFilter=t.metadata.referenceReceiverId;filterMenu=false;baselineId=null;candidateId=null
                    })
                }
            }
        }
        for(side in listOf("Baseline A","Candidate B")) Box {
            val trace=if(side=="Baseline A") a else b
            TextButton(onClick={picking=side},enabled=traces.isNotEmpty()) {Text("$side: ${trace?.name ?: "None"}")}
            DropdownMenu(expanded=picking==side,onDismissRequest={picking=null}) {
                traces.forEach {t->DropdownMenuItem(text={Text("${t.name} (#${t.id})")},onClick={
                    if(side=="Baseline A") baselineId=t.id else candidateId=t.id;picking=null
                })}
            }
        }
        if(a!=null && b!=null) {
            fun level(power: Double?): String=when {power==null->"Unavailable";power==0.0->"Zero energy";else->"%.2f dB".format(10*kotlin.math.log10(power))}
            for((label,t) in listOf("A" to a,"B" to b)) {
                val m=t.metadata
                Text("$label: ${t.name} · X %.2f, Y %.2f, Z %.2f m · %.2f oct · %.1f °C / %.0f %% RH".format(m.receiver.x,m.receiver.y,m.receiver.z,m.bandwidthOct,m.temperatureC,m.humidityPct),style=MaterialTheme.typography.bodySmall)
                if(m.sourceNotes.isNotBlank()) Text(m.sourceNotes,style=MaterialTheme.typography.bodySmall)
            }
            val comparison=TraceComparisons.compare(a,b)
            if(!comparison.compatible) Text("Cannot compare: ${comparison.reason}")
            else {
                TraceOverlay(comparison.bands)
                Text("Band: A → B; difference B − A",style=MaterialTheme.typography.bodySmall)
                comparison.bands.forEach {row ->
                    Text("${row.hz} Hz: ${level(row.baselinePower)} → ${level(row.candidatePower)}; "+(row.deltaDb?.let {"%+.2f dB".format(it)} ?: "Difference unavailable"),style=MaterialTheme.typography.bodySmall)
                }
            }
            TraceDelayReferenceControls(vm,a,b,saved.relativeToReference)
            val phase=TraceComparisons.comparePhase(a,b,saved.relativeToReference)
            Text("Phase response · wrapped degrees",style=MaterialTheme.typography.titleSmall)
            if(a.phaseConvention==com.droidacoustic.pro.acoustics.PhaseResponse.LEGACY_CONVENTION && b.phaseConvention==a.phaseConvention)
                Text("Legacy direct-path captures: reflected paths were not included.",style=MaterialTheme.typography.bodySmall)
            Text("Exact-frequency coherent phase; positive delay rotates positively. Includes valid direct and first-order specular paths, cabinet phase, DSP delay and polarity. No band-averaged phase or impulse response is inferred.",style=MaterialTheme.typography.bodySmall)
            if(!phase.compatible) Text("Phase unavailable: ${phase.reason}",style=MaterialTheme.typography.bodySmall)
            else {
                PhaseTraceOverlay(phase.bands)
                Text("${phase.bands.size} exact-frequency samples; numeric preview shows up to 64.",style=MaterialTheme.typography.bodySmall)
                phase.bands.take(64).forEach {row ->
                    fun display(p:com.droidacoustic.pro.acoustics.PhaseSample?)=p?.degrees?.let {"%+.1f°".format(it)} ?: "Unavailable"
                    Text("${row.hz} Hz: ${display(row.baseline)} → ${display(row.candidate)}; Δ " + (row.deltaDegrees?.let {"%+.1f°".format(it)} ?: "unavailable"),style=MaterialTheme.typography.bodySmall)
                }
                phase.bands.flatMap {listOfNotNull(it.baseline?.reason,it.candidate?.reason)}.distinct().forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
            }
            DerivedPhaseOverlay(a,b,saved.relativeToReference)
            TextButton(onClick={vm.removeSavedTrace(b.id)}) {Text("Delete candidate trace")}
        }
    }
}

@Composable
private fun ListenerInspector(vm: SceneViewModel) {
    val listener = vm.listener.collectAsStateValue()
    val results = vm.results.collectAsStateValue()
    val combined = vm.combinedSplDb.collectAsStateValue()
    val predictionState = vm.predictionState.collectAsStateValue()

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

    DesignVariantsInspector(vm)
    AlignmentInspector(vm)
    SavedTraceInspector(vm)

    InspectorSection("Level at this point") {
        Text(predictionStateLabel(predictionState), style = MaterialTheme.typography.bodySmall)
        Readout(
            "Combined",
            combined?.let { "%.1f dB".format(it) } ?: "—",
            valueColor = MaterialTheme.colorScheme.onSurface
        )
        SpeakerDataStatus(vm)
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
                        if (r.splDb.isFinite()) "   %.1f dB".format(r.splDb) else "   Unavailable",
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
            Readout("Nominal reference", "%.1f dB at 1 m (electrical reference unverified)".format(selected.sensitivityDb))
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

    InspectorSection("Venue envelope") {
        Text("Envelope walls and ceiling are visual context. Assign acoustic boundaries to Blocks or imported faces.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            "Visual ceiling height", venue.wallHeightM, { vm.setVenueWallHeight(it) },
            unit = "m", range = 2f..50f, decimals = 2, dragStep = 0.25f
        )
    }

    InspectorSection("Stage") {
        BoundaryRoleControl(venue.stageBoundaryRole,vm::setStageBoundaryRole)
        BoundaryMaterialControl(venue.stageBoundaryMaterial,vm::setStageMaterial)
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
