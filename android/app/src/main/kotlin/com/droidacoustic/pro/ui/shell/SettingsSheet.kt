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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.MainActivity
import com.droidacoustic.pro.scene.SceneViewModel
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
                    String(bytes, Charsets.ISO_8859_1)
                }
            }
            importing = false
            outcome.onSuccess { text ->
                if (looksBinary(text)) {
                    onMessage("That looks like a CF1/CF2 binary. Import the .tab text file instead.")
                } else if (vm.importClfTabText(text)) {
                    val name = vm.speakerPresets.value.lastOrNull()?.name ?: "speaker"
                    onMessage("Imported measured directivity for $name")
                } else {
                    onMessage(vm.lastImportError.value ?: "Could not read that CLF file")
                }
            }.onFailure { onMessage(it.message ?: "Could not read that file") }
        }
    }

    val signalLevel by vm.signalLevelDbu.collectAsState()
    val signalType by vm.signalType.collectAsState()
    val bandwidthOct by vm.signalBandwidthOct.collectAsState()
    val resolution by vm.signalResolution.collectAsState()
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
    val reflectionOrder by vm.reflectionOrder.collectAsState()
    val clfStats by vm.clfIngestionStats.collectAsState()
    val clfRegistry by vm.clfRegistry.collectAsState()
    val aimRays by vm.aimRaysEnabled.collectAsState()

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

            // ── Signal ───────────────────────────────────────────────────────
            InspectorSection("Signal") {
                NumericField(
                    "Drive level", signalLevel, { vm.setSignalLevelDbu(it) },
                    unit = "dB", range = -20f..20f, dragStep = 0.5f
                )
                SectionLabel("Analysis type")
                SegmentedControl(
                    options = listOf("BAND", "SPECTRUM"),
                    selected = signalType,
                    onSelect = { vm.setSignalType(it) },
                    label = { if (it == "BAND") "Frequency band" else "Spectrum" }
                )
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
                    IntStepper(
                        "Spectrum resolution", resolution,
                        { vm.setSignalResolution(it) }, 3..96
                    )
                }
                SectionLabel("Low-frequency summation")
                SegmentedControl(
                    options = listOf(true, false),
                    selected = interference,
                    onSelect = { vm.setSignalInterferenceEnabled(it) },
                    label = { if (it) "Complex (phase)" else "Energy (incoherent)" }
                )
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
            InspectorSection("Surface absorption") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumericField(
                        "Floor α", materials.floorAlpha, { vm.setFloorAbsorption(it) },
                        Modifier.weight(1f), range = 0.01f..0.99f, decimals = 2, dragStep = 0.01f
                    )
                    NumericField(
                        "Ceiling α", materials.ceilingAlpha, { vm.setCeilingAbsorption(it) },
                        Modifier.weight(1f), range = 0.01f..0.99f, decimals = 2, dragStep = 0.01f
                    )
                    NumericField(
                        "Wall α", materials.wallAlpha, { vm.setWallAbsorption(it) },
                        Modifier.weight(1f), range = 0.01f..0.99f, decimals = 2, dragStep = 0.01f
                    )
                }
                Text(
                    "Single broadband coefficient per surface family. Per-band " +
                        "coefficients are planned; treat RT60 as indicative.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Analysis ─────────────────────────────────────────────────────
            InspectorSection("Analysis") {
                SectionLabel("Profile")
                SegmentedControl(
                    options = listOf("Fast", "Balanced", "Precision"),
                    selected = profile,
                    onSelect = { vm.setAnalysisProfile(it) },
                    // Full words. These rendered as "Balan" and "Preci" before.
                    label = { it }
                )
                SectionLabel("Reflection order")
                SegmentedControl(
                    options = listOf(1, 2, 3),
                    selected = reflectionOrder,
                    onSelect = { vm.setReflectionOrder(it) },
                    label = { n ->
                        when (n) {
                            1 -> "1st only"
                            2 -> "1st + 2nd"
                            else -> "1st + 2nd + 3rd"
                        }
                    }
                )
                SectionLabel("Compute")
                SegmentedControl(
                    options = listOf(true, false),
                    selected = autoCalc,
                    onSelect = { vm.setSignalAutoCalculate(it) },
                    label = { if (it) "Automatic" else "Manual" }
                )
                if (!autoCalc) {
                    SmallAction("Recalculate now", Modifier.fillMaxWidth(), Instrument.Accent) {
                        vm.recalculateSignal()
                        onMessage("Recalculated")
                    }
                }
            }

            // ── Layers ───────────────────────────────────────────────────────
            InspectorSection("Layers") {
                LayerToggle("Direct SPL", splEnabled) { vm.setSignalSplEnabled(it) }
                LayerToggle("Coverage heatmap", coverage) { vm.setSignalCoverageEnabled(it) }
                LayerToggle("Directivity", dispersion) { vm.setSignalDispersionEnabled(it) }
                LayerToggle("Aim rays", aimRays) { vm.setAimRaysEnabled(it) }
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
                when (splScaleMode) {
                    SceneViewModel.SPL_SCALE_TARGET -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NumericField(
                                "Target", splTarget, { vm.setSplTargetDb(it) },
                                Modifier.weight(1f), unit = "dB", range = 40f..140f, dragStep = 0.5f
                            )
                            NumericField(
                                "Span ±", splSpan, { vm.setSplSpanDb(it) },
                                Modifier.weight(1f), unit = "dB", range = 1f..40f, dragStep = 0.5f
                            )
                        }
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
                        "only. Import a CLF .tab file to replace one with real data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Instrument.Caution
                )
                SmallAction(
                    if (importing) "Reading CLF file..." else "Import CLF file (.tab)",
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
                    SmallAction("Save", Modifier.weight(1f)) {
                        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putString(slotKey(), vm.exportSceneJson())
                            .apply()
                        onMessage("Project saved")
                    }
                    SmallAction("Load", Modifier.weight(1f)) {
                        val json = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getString(slotKey(), null)
                        if (json.isNullOrBlank()) onMessage("Nothing saved yet")
                        else onMessage(
                            if (vm.importSceneJson(json)) "Project loaded"
                            else (vm.lastImportError.value ?: "Load failed")
                        )
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

private fun slotKey() = "scene_snapshot_A_v${SceneViewModel.SCENE_SCHEMA_VERSION}"

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** CLF TAB files run to a few hundred KB; anything far larger is not one. */
private const val MAX_CLF_BYTES = 32 * 1024 * 1024

/**
 * Catch the common mistake of picking a CF1/CF2 binary instead of the .tab
 * text, so the user gets a fixable message rather than a parse error.
 */
private fun looksBinary(text: String): Boolean =
    text.take(512).any { it == '\u0000' }
