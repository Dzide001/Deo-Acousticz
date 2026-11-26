package com.droidacoustic.pro.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.prediction.PredictionModelScope
import com.droidacoustic.pro.acoustics.BroadbandDrive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.MainActivity
import com.droidacoustic.pro.persistence.ProjectArchive
import com.droidacoustic.pro.persistence.ProjectStore
import com.droidacoustic.pro.scene.LocalProjectStore
import java.io.File
import com.droidacoustic.pro.scene.ClfBinaryReader
import com.droidacoustic.pro.scene.elementPoses
import com.droidacoustic.pro.scene.SceneViewModel
import com.droidacoustic.pro.scene.SurfaceMaterial
import com.droidacoustic.pro.ui.components.InspectorSection
import com.droidacoustic.pro.ui.components.IntStepper
import com.droidacoustic.pro.ui.components.NumericField
import com.droidacoustic.pro.ui.components.Readout
import com.droidacoustic.pro.ui.components.SectionLabel
import com.droidacoustic.pro.ui.components.SegmentedControl
import com.droidacoustic.pro.ui.theme.Instrument

// =============================================================================
// Settings sheet
// =============================================================================
//
// Everything that is a property of the CALCULATION or the PROJECT rather than
// of a selected object. Keeping these out of the inspector is the point: the
// old Speakers tab opened with Save / Load / Recover / Copy JSON above the
// actual speakers, which put project management in the middle of a design task.
// =============================================================================

private const val PREFS = "droidacoustic_project"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    vm: SceneViewModel,
    activity: MainActivity,
    blockType: String,
    onBlockType: (String) -> Unit,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── CLF file import ──────────────────────────────────────────────────────
    // The app ships no manufacturer measurements; the user brings their own
    // file. TAB is the published text half of the format, so this path needs no
    // reverse engineering and redistributes nothing.
    val scope = rememberCoroutineScope()
    val projectStore = remember(activity) { LocalProjectStore(File(activity.filesDir, "projects")) }
    var projectBusy by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val clfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("Could not open the selected file")
                    if (bytes.size > MAX_CLF_BYTES) {
                        throw java.io.IOException(
                            "File is ${bytes.size / 1_000_000} MB; the limit is " +
                                "${MAX_CLF_BYTES / 1_000_000} MB"
                        )
                    }
                    // CLF TAB files are Latin-1, and manufacturer names carry
                    // accented characters that UTF-8 decoding would mangle.
                    bytes to String(bytes, Charsets.ISO_8859_1)
                }
            }
            importing = false
            outcome.onSuccess { (bytes, text) ->
                if (isClfBinary(bytes)) {
                    if (vm.importClfBinary(bytes)) {
                        val name = vm.speakerPresets.value.lastOrNull()?.name ?: "speaker"
                        onMessage("Imported measured directivity for $name")
                    } else {
                        onMessage(vm.lastImportError.value ?: "Could not read that CLF file")
                    }
                } else if (vm.importClfTabText(text)) {
                    val name = vm.speakerPresets.value.lastOrNull()?.name ?: "speaker"
                    onMessage("Imported measured directivity for $name")
                } else {
                    onMessage(vm.lastImportError.value ?: "Could not read that CLF file")
                }
            }.onFailure { onMessage(it.message ?: "Could not read that file") }
        }
    }

    var meshBusy by remember { mutableStateOf(false) }
    var meshUnits by remember { mutableStateOf(1.0) }
    var meshUp by remember { mutableStateOf(AudienceMeshUpAxis.Y) }
    var meshOptions by remember { mutableStateOf(AudienceMeshImportOptions()) }
    val meshPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri!=null) {
            meshBusy=true
            scope.launch {
                try {
                    val mesh=withContext(Dispatchers.IO) {
                        val text=activity.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                            val result=StringBuilder(); val buffer=CharArray(8192)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count=reader.read(buffer); if (count<0) break
                                require(result.length+count<=AudienceMeshObj.MAX_CHARS) { "Audience OBJ exceeds 8 MiB text limit" }
                                result.append(buffer,0,count)
                            }
                            result.toString()
                        } ?: error("Could not open the selected mesh")
                        AudienceMeshObj.parse(text,meshOptions) { coroutineContext.ensureActive() }
                    }
                    vm.addImportedAudienceMesh(mesh)
                    onMessage("Imported audience mesh: ${mesh.triangles().size} triangles, ${mesh.faceGroups().size} named groups")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { onMessage(failure.message ?: "Could not import audience mesh") }
                finally { meshBusy=false }
            }
        }
    }

    val signalLevel by vm.signalLevelDbu.collectAsState()
    val signalType by vm.signalType.collectAsState()
    val weighting by vm.weighting.collectAsState()
    val selectedBand by vm.selectedBandHz.collectAsState()
    val broadbandDrive by vm.broadbandDrive.collectAsState()
    val bandwidthOct by vm.signalBandwidthOct.collectAsState()
    val resolution by vm.signalResolution.collectAsState()
    val groundSources by vm.speakers.collectAsState()
    val groundReflection by vm.groundReflectionEnabled.collectAsState()
    val interference by vm.signalInterferenceEnabled.collectAsState()
    val autoCalc by vm.signalAutoCalculate.collectAsState()
    val splEnabled by vm.signalSplEnabled.collectAsState()
    val dispersion by vm.signalDispersionEnabled.collectAsState()
    val coverage by vm.signalCoverageEnabled.collectAsState()
    val splScaleMode by vm.splScaleMode.collectAsState()
    val splTarget by vm.splTargetDb.collectAsState()
    val splSpan by vm.splSpanDb.collectAsState()
    val splFixedMin by vm.splFixedMinDb.collectAsState()
    val splFixedMax by vm.splFixedMaxDb.collectAsState()
    val temperatureC by vm.temperatureC.collectAsState()
    val humidityPct by vm.humidityPct.collectAsState()
    val materials by vm.roomMaterials.collectAsState()
    val profile by vm.analysisProfile.collectAsState()
    val clfStats by vm.clfIngestionStats.collectAsState()
    val clfRegistry by vm.clfRegistry.collectAsState()
    val aimRays by vm.aimRaysEnabled.collectAsState()
    val contourMode by vm.contourMode.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Calculation & project",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "These settings apply to the whole scene. They are stamped on every export.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 18.dp)
            )

            ComparisonImportControls(vm,activity,onMessage)

            InspectorSection("Import audience mesh") {
                Text("Triangulated OBJ: one connected 3D surface, including vertical faces, overhangs and holes. Choose the file's units and upward axis. OBJ groups become named face groups; multiple names on a group line form one combined group. Faces initially follow the zone type. Assign or exclude faces or groups in the zone inspector; listening points are offset vertically.",
                    style=MaterialTheme.typography.bodySmall)
                SectionLabel("File units")
                SegmentedControl(options=listOf(1.0,0.01,0.001,0.3048),selected=meshUnits,onSelect={meshUnits=it},
                    label={ when(it) { 1.0 -> "m"; 0.01 -> "cm"; 0.001 -> "mm"; else -> "ft" } })
                SectionLabel("Upward axis")
                SegmentedControl(options=AudienceMeshUpAxis.entries.toList(),selected=meshUp,onSelect={meshUp=it},label={"${it.name} up"})
                SmallAction(if (meshBusy) "Importing mesh…" else "Choose audience OBJ") {
                    if (!meshBusy) {
                        meshOptions=AudienceMeshImportOptions(meshUnits,meshUp)
                        meshPicker.launch(arrayOf("*/*"))
                    }
                }
            }

            // ── Signal ───────────────────────────────────────────────────────
            InspectorSection("Signal") {
                NumericField(
                    "Relative drive gain", signalLevel, { vm.setSignalLevelDbu(it) },
                    unit = "dB", range = -20f..20f, dragStep = 0.5f
                )
                Text("Gain relative to the model reference, not electrical input dBu. Unverified references do not establish absolute calibration.",
                    style = MaterialTheme.typography.bodySmall)
                SpeakerDataStatus(vm)
                SectionLabel("Coverage mode")
                SegmentedControl(
                    options = listOf("BAND", "SPECTRUM"),
                    selected = signalType,
                    onSelect = { vm.setSignalType(it) },
                    label = { if (it == "BAND") "Band" else "Broadband SPL" }
                )
                if(signalType=="SPECTRUM") {
                    SectionLabel("Inspection frequency — rays/support (Hz)")
                    SegmentedControl(options=SceneViewModel.SUPPORTED_BANDS_HZ,selected=selectedBand,
                        onSelect=vm::setBandHz,label={if(it>=1000) "${it/1000}k" else "$it"})
                }
                if (signalType == "BAND") {
                    SectionLabel("Bandwidth")
                    SegmentedControl(
                        options = listOf(1f, 1f / 3f, 1f / 6f, 1f / 12f),
                        selected = bandwidthOct,
                        onSelect = { vm.setSignalBandwidthOct(it) },
                        // Written out in full. These were the five entirely
                        // blank pills in the old SPL panel.
                        label = { oct ->
                            when {
                                oct >= 0.99f -> "1 octave"
                                oct >= 0.32f -> "1/3 octave"
                                oct >= 0.16f -> "1/6 octave"
                                else -> "1/12 octave"
                            }
                        }
                    )
                } else {
                    SectionLabel("Weighting")
                    SegmentedControl(
                        options = SceneViewModel.WEIGHTINGS,
                        selected = weighting,
                        onSelect = { vm.setWeighting(it) },
                        label = {
                            when (it) {
                                SceneViewModel.WEIGHTING_A -> "A"
                                SceneViewModel.WEIGHTING_C -> "C"
                                else -> "Z (none)"
                            }
                        }
                    )
                    Text(
                        when (weighting) {
                            SceneViewModel.WEIGHTING_A ->
                                "dB(A). The curve almost every noise limit and licence " +
                                    "condition is written in. It discounts the low end " +
                                    "heavily, so it flatters a bass-shy system."
                            SceneViewModel.WEIGHTING_C ->
                                "dB(C). Nearly flat, rolling off only at the extremes - " +
                                    "the honest one for music at level. The gap between " +
                                    "a C and an A reading is a direct measure of how much " +
                                    "low end the system is putting out."
                            else ->
                                "Unweighted. What the model actually produced, summed " +
                                    "across 63 Hz to 8 kHz."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Broadband sums all eight bands using the selected source summation mode within each band, " +
                            "as power between them. The range stops at 8 kHz, so a " +
                            "figure here is not directly comparable with a meter that " +
                            "reaches 20 kHz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Instrument.Caution
                    )
                    IntStepper(
                        "Spectrum resolution", resolution,
                        { vm.setSignalResolution(it) }, 3..96
                    )
                }
                if(signalType=="SPECTRUM") {
                    SectionLabel("Broadband relative drive")
                    SegmentedControl(options=BroadbandDrive.entries,selected=broadbandDrive,
                        onSelect=vm::setBroadbandDrive,label={it.label})
                }
                SectionLabel("Ground model")
                SegmentedControl(
                    options = listOf(false, true), selected = groundReflection,
                    onSelect = { vm.setGroundReflectionEnabled(it) },
                    label = { if (it) "Ground on" else "Ground off" }
                )
                Text("Reflecting floor: flat y=0 surface, amplitude from absorption and optional measured phase; no impedance or diffraction model.",
                    style = MaterialTheme.typography.bodySmall)
                if(groundReflection) GroundPhaseEditor(vm)
                if (groundReflection && groundSources.any { speaker ->
                    speaker.elementPoses().any { speaker.heightM + it.upM < 0f }
                }) {
                    Text("Ground reflection is unavailable while a cabinet centre is below y=0. Predictions use free field until the array is raised.",
                        style = MaterialTheme.typography.bodySmall, color = Instrument.Caution)
                }
                SectionLabel("Source summation · all frequencies")
                SegmentedControl(
                    options = listOf(true, false),
                    selected = interference,
                    onSelect = { vm.setSignalInterferenceEnabled(it) },
                    label = { if (it) "Complex (phase)" else "Energy (incoherent)" }
                )
                Text("Energy mode sums independent speaker groups at every frequency and supports mixed magnitude/complex data. Array elements and each source's reflected paths remain correlated. Complex mode requires compatible phase data when any band-capable complex source contributes. Diffuse sound contributes energy, not a phase trace.",style=MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Box(Modifier.heightIn(min = 14.dp))

            // ── Atmosphere ───────────────────────────────────────────────────
            InspectorSection("Atmosphere") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumericField(
                        "Temperature", temperatureC, { vm.setTemperatureC(it) },
                        Modifier.weight(1f), unit = "°C", range = -20f..50f, dragStep = 0.5f
                    )
                    NumericField(
                        "Humidity", humidityPct, { vm.setHumidityPct(it) },
                        Modifier.weight(1f), unit = "%", range = 0f..100f, dragStep = 1f
                    )
                }
            }

            // ── Surfaces ─────────────────────────────────────────────────────
            InspectorSection("Ground absorption") {
                listOf(
                    Triple("Ground floor", "FLOOR", materials.floor)
                ).forEach { (label, key, current) ->
                    SectionLabel("$label — ${current.name}")
                    SegmentedControl(
                        options = SurfaceMaterial.CATALOGUE.map { it.id },
                        selected = current.id,
                        onSelect = { vm.setSurfaceMaterial(key, it) },
                        label = { id -> SurfaceMaterial.byId(id)?.name ?: id }
                    )
                    Readout(
                        "α at ${if (selectedBand >= 1000) "${selectedBand / 1000} kHz" else "$selectedBand Hz"}",
                        "%.2f".format(current.alphaAt(selectedBand))
                    )
                }
                Text(
                    "This curve affects the optional global ground reflection only, when Ground is on. " +
                        "Assign materials to Blocks and imported faces in their inspectors. " +
                        "Saved wall and ceiling settings are retained but do not affect coverage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "These describe a type of construction, not a specific product, " +
                        "and the published tables run 125 Hz to 4 kHz - the 63 Hz and " +
                        "8 kHz bands hold the nearest measured value. A room that " +
                        "matters should be measured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Instrument.Caution
                )
            }

            // ── Analysis ─────────────────────────────────────────────────────
            InspectorSection("Analysis") {
                SectionLabel(PredictionModelScope.profileLabel)
                SegmentedControl(
                    options = listOf("Fast", "Balanced", "Precision"),
                    selected = profile,
                    onSelect = { vm.setAnalysisProfile(it) },
                    label = { "${PredictionModelScope.coverageGridSpacingM(it).toInt()} m" }
                )
                Readout("Reflections", PredictionModelScope.reflectionLabel)
                Text("Grid spacing controls receiver density, not acoustic model accuracy. Audience meshes may refine it further.",
                    style = MaterialTheme.typography.bodySmall)
                Text(PredictionModelScope.summary,
                    style = MaterialTheme.typography.bodySmall)
                PredictionModelScope.limitations.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Instrument.Caution)
                }
                SectionLabel("Calculation")
                SegmentedControl(
                    options = listOf(true, false),
                    selected = autoCalc,
                    onSelect = { vm.setSignalAutoCalculate(it) },
                    label = { if (it) "Automatic" else "Manual" }
                )
                if (!autoCalc) {
                    SmallAction("Recalculate now", Modifier.fillMaxWidth(), Instrument.Accent) {
                        vm.recalculateSignal()
                        onMessage("Calculation requested")
                    }
                }
            }

            // ── Layers ───────────────────────────────────────────────────────
            InspectorSection("Layers") {
                LayerToggle("Predicted SPL", splEnabled) { vm.setSignalSplEnabled(it) }
                LayerToggle("Coverage heatmap", coverage) { vm.setSignalCoverageEnabled(it) }
                LayerToggle("Directivity", dispersion) { vm.setSignalDispersionEnabled(it) }
                LayerToggle("Aim rays", aimRays) { vm.setAimRaysEnabled(it) }
                SectionLabel("Level contours")
                SegmentedControl(
                    options = SceneViewModel.CONTOUR_MODES,
                    selected = contourMode,
                    onSelect = { vm.setContourMode(it) },
                    label = {
                        when (it) {
                            SceneViewModel.CONTOUR_BANDS -> "Shaded bands"
                            SceneViewModel.CONTOUR_LINES -> "Lines"
                            else -> "Off"
                        }
                    }
                )
                Text(
                    "Iso-levels are measured down from the design target, or from the " +
                        "95th percentile when no target is set. Shaded bands step the " +
                        "ramp every 3 dB so each colour change is itself a contour; " +
                        "Lines draws -3, -6, -9 and -12 dB on top, with -6 dB heaviest " +
                        "as the conventional edge of coverage. Either way the point is " +
                        "the same: a smooth ramp has no threshold, so colour alone " +
                        "cannot say where coverage stops. Bands read best against a " +
                        "target or fixed window - Auto can stretch over 60 dB, and " +
                        "twenty steps of a six-colour ramp look like no steps at all.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Aim rays draw each box's acoustic axis and coverage edges out to the floor. An array fans one set per element, so splay is visible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Colour scale ─────────────────────────────────────────────────
            InspectorSection("SPL colour scale") {
                SectionLabel("Scale")
                SegmentedControl(
                    options = SceneViewModel.SPL_SCALE_MODES,
                    selected = splScaleMode,
                    onSelect = { vm.setSplScaleMode(it) },
                    label = {
                        when (it) {
                            SceneViewModel.SPL_SCALE_TARGET -> "Target ± span"
                            SceneViewModel.SPL_SCALE_FIXED -> "Fixed window"
                            else -> "Auto"
                        }
                    }
                )
                // The target is a property of the design, not of the display, so
                // it stays visible whichever scale is showing. Editing it while
                // on Auto switches to Target - Auto cannot be compared between
                // runs, and a stated target is a request to be able to.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumericField(
                        "Target", splTarget, { vm.setSplTargetDb(it) },
                        Modifier.weight(1f), unit = "dB", range = 40f..140f, dragStep = 0.5f
                    )
                    if (splScaleMode == SceneViewModel.SPL_SCALE_TARGET) {
                        NumericField(
                            "Span ±", splSpan, { vm.setSplSpanDb(it) },
                            Modifier.weight(1f), unit = "dB", range = 1f..40f, dragStep = 0.5f
                        )
                    } else {
                        Box(Modifier.weight(1f))
                    }
                }
                when (splScaleMode) {
                    SceneViewModel.SPL_SCALE_TARGET -> {
                        Text(
                            "Ramp spans ${"%.1f".format(splTarget - splSpan)} to " +
                                "${"%.1f".format(splTarget + splSpan)} dB. Mid-ramp is on target.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SceneViewModel.SPL_SCALE_FIXED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NumericField(
                                "Minimum", splFixedMin, { vm.setSplFixedMinDb(it) },
                                Modifier.weight(1f), unit = "dB", range = 0f..160f, dragStep = 1f
                            )
                            NumericField(
                                "Maximum", splFixedMax, { vm.setSplFixedMaxDb(it) },
                                Modifier.weight(1f), unit = "dB", range = 1f..161f, dragStep = 1f
                            )
                        }
                    }
                    else -> {
                        Text(
                            "Colours rescale to each calculation, so they cannot be " +
                                "compared between runs. Pick an absolute scale to hold " +
                                "the ramp still.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Placement ────────────────────────────────────────────────────
            InspectorSection("Block placement type") {
                SegmentedControl(
                    options = listOf("OBSTACLE", "WALL", "STAGE", "BALCONY", "SEATING"),
                    selected = blockType,
                    onSelect = onBlockType,
                    label = { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Box(Modifier.heightIn(min = 14.dp))

            // ── Speaker library ──────────────────────────────────────────────
            InspectorSection("Speaker library") {
                Readout(
                    "Indexed speakers",
                    "${clfStats.indexedSpeakers}"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Imported counts anything in the registry carrying real
                    // polar data, which is what a user's own CLF file produces.
                    Readout(
                        "Imported",
                        "${clfRegistry.count { it.value.patterns.isNotEmpty() }}",
                        Modifier.weight(1f)
                    )
                    Readout("Inferred", "${clfStats.inferredBinarySpeakers}", Modifier.weight(1f))
                    Readout("Pending", "${clfStats.pendingBinarySpeakers}", Modifier.weight(1f))
                }
                Text(
                    "\"Inferred\" means the polar pattern was synthesized from the " +
                        "model name, not measured. Those predictions are indicative " +
                        "only. Import a manufacturer CLF file - .cf2 or .tab - to " +
                        "replace one with measured data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Instrument.Caution
                )
                SmallAction(
                    if (importing) "Reading CLF file..." else "Import CLF file (.cf2 / .tab)",
                    Modifier.fillMaxWidth()
                ) {
                    if (!importing) {
                        // No reliable MIME type for .tab, so accept anything and
                        // validate by content.
                        clfPicker.launch(arrayOf("*/*"))
                    }
                }
                SmallAction("Load bundled catalogue", Modifier.fillMaxWidth()) {
                    val r = vm.loadBundledIndustryCatalog()
                    onMessage(
                        if (r.ok) "Loaded ${r.presetsAdded} presets"
                        else (r.message ?: "Catalogue failed to load")
                    )
                }
            }

            // ── Project ──────────────────────────────────────────────────────
            InspectorSection("Project") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallAction(if (projectBusy) "Working…" else "Save", Modifier.weight(1f)) {
                        if (!projectBusy) {
                            projectBusy = true
                            val json = vm.exportSceneJson(includeClfRegistry = false)
                            val assetPayloads = vm.projectAssetPayloads()
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { projectStore.save("project_A", json, assetPayloads::get) }
                                    onMessage("Project saved")
                                } catch (e: Exception) { onMessage("Save failed: ${e.message}") }
                                finally { projectBusy = false }
                            }
                        }
                    }
                    SmallAction("Load", Modifier.weight(1f)) {
                        if (!projectBusy) {
                            projectBusy = true
                            scope.launch {
                                try {
                                    val saved = withContext(Dispatchers.IO) { projectStore.loadPrepared("project_A") }
                                    val legacy = if (saved == null) withContext(Dispatchers.IO) {
                                        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                        ProjectStore.legacyKeys("project_A").firstNotNullOfOrNull {
                                            prefs.getString(it, null)?.takeIf(String::isNotBlank)
                                        }
                                    } else null
                                    val prepared = saved?.project ?: legacy?.let { withContext(Dispatchers.Default) { ProjectArchive.open(it) } }
                                    onMessage(if (prepared == null) "Nothing saved yet"
                                        else if (!vm.importPreparedProject(prepared)) vm.lastImportError.value ?: "Load failed"
                                        else if (saved?.usedBackup == true) "Loaded previous valid project checkpoint"
                                        else "Project loaded")
                                } catch (e: Exception) { onMessage("Load failed: ${e.message}") }
                                finally { projectBusy = false }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallAction("Copy scene JSON", Modifier.weight(1f)) {
                        copyToClipboard(activity, "scene", vm.exportSceneJson())
                        onMessage("Scene JSON copied")
                    }
                    SmallAction("Copy report JSON", Modifier.weight(1f)) {
                        copyToClipboard(activity, "report", vm.exportProjectReportJson())
                        onMessage("Report JSON copied")
                    }
                }
                SmallAction("Clear scene", Modifier.fillMaxWidth(), Instrument.Critical) {
                    vm.clearAll()
                    onMessage("Scene cleared")
                }
            }
        }
    }
}

@Composable
private fun LayerToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        SegmentedControl(
            options = listOf(true, false),
            selected = on,
            onSelect = onChange,
            label = { if (it) "On" else "Off" }
        )
    }
}


private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** CLF TAB files run to a few hundred KB; anything far larger is not one. */
private const val MAX_CLF_BYTES = 32 * 1024 * 1024

/** CLF binaries announce themselves: CF2 is 0x000ABD41, CF1 one less. */
private fun isClfBinary(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    val magic = (bytes[0].toInt() and 0xFF) or
        ((bytes[1].toInt() and 0xFF) shl 8) or
        ((bytes[2].toInt() and 0xFF) shl 16) or
        ((bytes[3].toInt() and 0xFF) shl 24)
    return magic == ClfBinaryReader.MAGIC_CF2 || magic == ClfBinaryReader.MAGIC_CF1
}
