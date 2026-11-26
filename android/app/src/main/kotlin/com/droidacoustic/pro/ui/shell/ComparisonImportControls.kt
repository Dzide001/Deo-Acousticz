package com.droidacoustic.pro.ui.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.MainActivity
import com.droidacoustic.pro.acoustics.ImportedComparison
import com.droidacoustic.pro.scene.SceneViewModel
import com.droidacoustic.pro.ui.components.InspectorSection
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@Composable
internal fun ComparisonImportControls(vm: SceneViewModel, activity: MainActivity, onMessage: (String)->Unit) {
    val state by vm.comparisonWorkspace.collectAsState()
    val spectrum by vm.comparisonSourceSpectrum.collectAsState()
    var customSpectrum by remember(spectrum.canonical()) {mutableStateOf(if(spectrum.mode=="TOTAL_CUSTOM_BAND_POWER") spectrum.canonical() else """{"mode":"TOTAL_CUSTOM_BAND_POWER","relativeBandPowers":{"63":1,"125":1,"250":1,"500":1,"1000":1,"2000":1,"4000":1,"8000":1}}""")}
    val capturing by vm.engineCaptureRunning.collectAsState()
    val captureMessage by vm.engineCaptureMessage.collectAsState()
    val report=remember(state) {JSONObject(state.reportJson()).getJSONObject("comparison")}
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    var referenceSlot by remember {mutableStateOf(true)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri!=null) {
            val asReference=referenceSlot
            busy=true
            scope.launch {
                try {
                    val imported=withContext(Dispatchers.IO) {
                        val raw=activity.contentResolver.openInputStream(uri)?.use {input ->
                            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                            while(true) {
                                ensureActive()
                                val n=input.read(buffer);if(n<0) break
                                require(output.size()+n<=8*1024*1024) {"Comparison file exceeds 8 MiB UTF-8 limit"}
                                output.write(buffer,0,n)
                            }
                            output.toByteArray()
                        } ?: error("Could not open comparison file")
                        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString().removePrefix("\uFEFF")
                        ImportedComparison.parse(text)
                    }
                    onMessage(if(vm.applyComparisonImport(imported,asReference)) "Imported comparison snapshot"
                        else vm.lastImportError.value ?: "Comparison import failed")
                } catch(cancelled: CancellationException) {throw cancelled}
                catch(failure: Exception) {onMessage(failure.message ?: "Comparison import failed")}
                finally {busy=false}
            }
        }
    }
    InspectorSection("Imported comparisons") {
        Text("Import a comparison JSON package containing a CSV table or JSON samples and explicit test conditions. Native proprietary files are not decoded.",
            style=MaterialTheme.typography.bodySmall)
        Text("Broadband capture excitation: ${spectrum.mode}. This comparison setting does not change coverage maps.",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("PER_BAND_REFERENCE" to "Per band", "TOTAL_EQUAL_BAND_POWER" to "Equal total", "TOTAL_FLAT_PSD" to "Flat PSD").forEach {(mode,label) ->
                SmallAction(label,Modifier.weight(1f)) {vm.setComparisonSourceSpectrum("{\"mode\":\"$mode\"}")}
            }
        }
        OutlinedTextField(customSpectrum,{customSpectrum=it},label={Text("Custom band-drive JSON")},modifier=Modifier.fillMaxWidth())
        SmallAction("Apply custom spectrum") {
            onMessage(if(vm.setComparisonSourceSpectrum(customSpectrum)) "Applied comparison spectrum" else vm.lastImportError.value ?: "Invalid spectrum")
        }
        if(!busy) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            SmallAction("Import reference",Modifier.weight(1f)) {referenceSlot=true;picker.launch(arrayOf("*/*"))}
            SmallAction("Import engine snapshot",Modifier.weight(1f)) {referenceSlot=false;picker.launch(arrayOf("*/*"))}
        } else Text("Reading comparison data…",style=MaterialTheme.typography.bodySmall)
        if(state.reference!=null && !busy && !capturing) SmallAction("Capture current scene at reference samples") {vm.captureEngineComparison()}
        if(capturing) Text("Capturing scene snapshot…",style=MaterialTheme.typography.bodySmall)
        captureMessage?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        state.engine?.let {engine -> SmallAction("Copy engine snapshot JSON") {
            val clipboard=activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("engine comparison",engine.document))
            onMessage("Engine snapshot copied")
        }}
        state.reference?.let {reference ->
            Text("Level convention: ${reference.run.conditions.levelConvention}",style=MaterialTheme.typography.bodySmall)
            reference.broadband?.let {b -> Text("Reference: ${b.weighting}-weighted octave sum: ${b.bandCentersHz.joinToString()} Hz • ${b.sourceSpectrum?.mode ?: "PER_BAND_REFERENCE"}",style=MaterialTheme.typography.bodySmall)}
            reference.normalization?.let {n -> Text("Reference receiver: (${n.xM}, ${n.yM}, ${n.zM}) m • safety floor ${n.minimumReferenceLevelDb} dB",
                style=MaterialTheme.typography.bodySmall)}
        }
        Text("Reference: ${state.reference?.let {it.producer+" • "+it.fileName} ?: "—"}",style=MaterialTheme.typography.bodySmall)
        Text("Engine: ${state.engine?.let {it.producer+" • "+it.fileName} ?: "—"}",style=MaterialTheme.typography.bodySmall)
        state.engine?.broadband?.let {b -> Text("Engine snapshot: ${b.weighting}-weighted octave sum • ${b.bandCentersHz.size} bands • ${b.sourceSpectrum?.mode ?: "PER_BAND_REFERENCE"}",style=MaterialTheme.typography.bodySmall)}
        Text(report.getString("status"),style=MaterialTheme.typography.labelLarge)
        if(report.has("maxAbsoluteErrorDb")) Text("Maximum residual: %.4f dB • %d samples".format(
            report.getDouble("maxAbsoluteErrorDb"),report.getInt("sampleCount")),style=MaterialTheme.typography.bodySmall)
        report.optJSONArray("reasons")?.let {reasons -> for(i in 0 until reasons.length())
            Text(reasons.getString(i),style=MaterialTheme.typography.bodySmall)}
        Text("These are frozen snapshots. Capture derives engine conditions from the current scene; scene edits do not update saved results. Origin and conditions are declared, not authenticated; a pass applies only to these samples.",
            style=MaterialTheme.typography.bodySmall)
        ComparisonValidationPanel(state,activity,onMessage)
        if(state.reference!=null || state.engine!=null) SmallAction("Clear imported comparisons") {vm.clearComparisonImports()}
    }
}
