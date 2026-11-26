package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.droidacoustic.pro.scene.SceneViewModel
import com.droidacoustic.pro.traces.SavedTrace
import kotlin.math.abs

@Composable
internal fun TraceDelayReferenceControls(vm:SceneViewModel,a:SavedTrace,b:SavedTrace,relative:Boolean) {
    Text("Comparison delay references",style=MaterialTheme.typography.titleSmall)
    Text("Adjust saved-trace references in milliseconds. This changes phase interpretation, not speaker delays. Both plots use the selected total or relative display.",style=MaterialTheme.typography.bodySmall)
    Row {
        TextButton(onClick={vm.setTraceRelativeDisplay(false)}) {Text(if(!relative) "✓ Total" else "Total")}
        TextButton(onClick={vm.setTraceRelativeDisplay(true)}) {Text(if(relative) "✓ Relative to references" else "Relative to references")}
    }
    ReferenceControl("A",a,vm)
    ReferenceControl("B",b,vm)
    AutomaticReferenceControl(vm,a,b)
}

@Composable
private fun ReferenceControl(label:String,trace:SavedTrace,vm:SceneViewModel) {
    var editing by remember(trace.id) {mutableStateOf(false)}
    var text by remember(trace.id,trace.manualDelayReferenceS) {mutableStateOf((trace.effectiveDelayReferenceS*1000).toString())}
    val ms=text.toDoubleOrNull()
    val valid=ms!=null && ms.isFinite() && abs(ms)<=3600000
    TextButton(onClick={text=(trace.effectiveDelayReferenceS*1000).toString();editing=true}) {
        Text("$label reference: %.3f ms (%s) · Edit".format(trace.effectiveDelayReferenceS*1000,if(trace.manualDelayReferenceS==null) "automatic" else "manual"))
    }
    if(editing) AlertDialog(onDismissRequest={editing=false},title={Text("$label delay reference · ${trace.name}")},
        text={Column {
            Text("Captured automatic reference: %.3f ms. Negative references are allowed. Residual phase changes must remain below 180° between samples.".format((trace.delayReferenceS ?: 0.0)*1000))
            OutlinedTextField(value=text,onValueChange={text=it},label={Text("Reference (ms)")},singleLine=true,isError=!valid,modifier=Modifier.fillMaxWidth())
            if(!valid) Text("Enter a finite value within ±3600000 ms.")
            TextButton(onClick={vm.setTraceDelayReference(trace.id,null);editing=false}) {Text("Reset to automatic")}
        }},confirmButton={TextButton(enabled=valid,onClick={vm.setTraceDelayReference(trace.id,ms!!/1000);editing=false}) {Text("Apply reference")}},
        dismissButton={TextButton(onClick={editing=false}) {Text("Cancel")}})
}


@Composable
private fun AutomaticReferenceControl(vm:SceneViewModel,a:SavedTrace,b:SavedTrace) {
    val library=vm.savedTraces.collectAsState().value
    val range=library.matchingFrequencyRange
    MatchingRangeControl(vm,a,b,range)
    MatchingExclusionRanges(vm,a.id,b.id)
    var preview by remember(a,b,range) {mutableStateOf<com.droidacoustic.pro.traces.TraceReferenceMatch?>(null)}
    var historyRevision by remember(a,b,range) {mutableStateOf(0)}
    var error by remember(a,b,range) {mutableStateOf<String?>(null)}
    TextButton(onClick={preview=com.droidacoustic.pro.traces.TraceReferenceMatching.preview(library,a.id,b.id);error=null}) {Text("Match B reference to A…")}
    preview?.let {match ->
        AlertDialog(onDismissRequest={preview=null},title={Text("Match B reference to A")},text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState())) {
            Text(match.frequencyRange?.let {"Selected range: ${it.minHz}–${it.maxHz} Hz (inclusive)"} ?: "Selected range: all captured frequencies")
            match.usedMinHz?.let {Text("Fitted samples: $it–${match.usedMaxHz} Hz")}
            if(match.available) Text("B reference: %.3f → %.3f ms (change %+.3f ms)".format(b.effectiveDelayReferenceS*1000,match.proposedReferenceS!!*1000,match.correctionS!!*1000))
            Text("Confidence: ${match.confidenceLabel} (${match.confidenceScore}/100) · diagnostic score, not a probability")
            Text("${match.usedSamples} retained / ${match.eligibleSamples} eligible samples; ${match.rejectedFrequencies.size} rejected; ${match.fitSamples.count {it.status==com.droidacoustic.pro.traces.MatchSampleStatus.EXCLUDED}} manually excluded")
            if(match.rejectedFrequencies.isNotEmpty()) Text("Rejected Hz: "+match.rejectedFrequencies.take(12).joinToString()+if(match.rejectedFrequencies.size>12) " …" else "")
            match.reason?.let {Text(it)}
            match.residualRmsDegrees?.let {Text("${match.usedSamples} samples in ${match.segments} segments · %.2f° RMS fit error".format(it))}
            Column {
                TextButton(enabled=library.canUndoExclusion(a.id,b.id),onClick={
                    vm.undoMatchingExclusion(a.id,b.id);historyRevision++
                    preview=com.droidacoustic.pro.traces.TraceReferenceMatching.preview(vm.savedTraces.value,a.id,b.id);error=null
                }) {Text(library.undoExclusionDescription(a.id,b.id)?.let {"Undo: $it"} ?: "Undo exclusion edit")}
                TextButton(enabled=library.canRedoExclusion(a.id,b.id),onClick={
                    vm.undoMatchingExclusion(a.id,b.id,true);historyRevision++
                    preview=com.droidacoustic.pro.traces.TraceReferenceMatching.preview(vm.savedTraces.value,a.id,b.id);error=null
                }) {Text(library.redoExclusionDescription(a.id,b.id)?.let {"Redo: $it"} ?: "Redo exclusion edit")}
            }
            Text("Undo/Redo affects saved exclusions for this A/B pair. Unsaved range drafts are discarded.")
            ExclusionHistoryPanel(library,a.id,b.id,vm::setOverlayInspection) {historyPreview ->
                val applied=vm.applyExclusionHistoryPreview(historyPreview)
                if(applied) {
                    historyRevision++
                    preview=com.droidacoustic.pro.traces.TraceReferenceMatching.preview(vm.savedTraces.value,a.id,b.id);error=null
                }
                applied
            }
            key(historyRevision) { MatchingFitPlot(match,library.inspectedSample,vm::inspectMatchingSample,library.inspectedSample?.let(library::isRangeExcluded) ?: false,ranges=library.rangeExclusions,onRangeEdit={old,replacement ->
                val applied=vm.editMatchingRange(old,replacement)
                if(applied) {preview=com.droidacoustic.pro.traces.TraceReferenceMatching.preview(vm.savedTraces.value,a.id,b.id);error=null}
                applied
            },onRangeExclude={excludedRange ->
                vm.excludeMatchingRange(excludedRange,true)
                preview=com.droidacoustic.pro.traces.TraceReferenceMatching.preview(vm.savedTraces.value,a.id,b.id)
                error=null
            }) {sample,exclude ->
                vm.excludeMatchingSample(sample,exclude)
                preview=com.droidacoustic.pro.traces.TraceReferenceMatching.preview(vm.savedTraces.value,a.id,b.id)
                error=null
            }
            }
            Text("Matches the phase-difference slope near the current references. Constant phase offsets remain. Missing data is not bridged. Sparse samples can hide complete turns; inspect the result before using it.")
            match.residualAliasLimitMs?.let {Text("Residual difference must stay within ±%.3f ms between fitted samples.".format(it))}
            Text("Applies only B's saved-trace reference and switches to relative display. Speaker delays stay unchanged. Undo is available.")
            error?.let {Text(it)}
        }},confirmButton={TextButton(enabled=match.available,onClick={
            if(vm.applyTraceReferenceMatch(match)) preview=null else error="Traces changed. Close and preview again."
        }) {Text("Apply match")}},dismissButton={TextButton(onClick={preview=null}) {Text("Cancel")}})
    }
}


@Composable
private fun MatchingRangeControl(vm:SceneViewModel,a:SavedTrace,b:SavedTrace,range:com.droidacoustic.pro.traces.MatchingFrequencyRange?) {
    var editing by remember {mutableStateOf(false)}
    var low by remember {mutableStateOf("")};var high by remember {mutableStateOf("")}
    val parsed=runCatching {
        com.droidacoustic.pro.traces.MatchingFrequencyRange(low.toInt(),high.toInt())
    }.getOrNull()
    TextButton(onClick={
        low=(range?.minHz ?: (a.phases.keys+b.phases.keys).minOrNull() ?: 20).toString()
        high=(range?.maxHz ?: (a.phases.keys+b.phases.keys).maxOrNull() ?: 20000).toString()
        editing=true
    }) {Text("Matching range: "+(range?.let {"${it.minHz}–${it.maxHz} Hz"} ?: "All frequencies")+" · Edit")}
    if(editing) AlertDialog(onDismissRequest={editing=false},title={Text("Matching frequency range")},text={Column {
        Text("Inclusive limits in whole Hz. Only captured samples inside this range are fitted; endpoints are not interpolated. At least three consecutive valid common samples are needed.")
        OutlinedTextField(value=low,onValueChange={low=it},label={Text("Minimum Hz")},singleLine=true)
        OutlinedTextField(value=high,onValueChange={high=it},label={Text("Maximum Hz")},singleLine=true)
        if(parsed==null) Text("Enter positive whole-Hz limits with maximum greater than minimum.")
        TextButton(onClick={vm.setMatchingFrequencyRange(null);editing=false}) {Text("Use all frequencies")}
    }},confirmButton={TextButton(enabled=parsed!=null,onClick={vm.setMatchingFrequencyRange(parsed);editing=false}) {Text("Save range")}},
        dismissButton={TextButton(onClick={editing=false}) {Text("Cancel")}})
}

@Composable
private fun MatchingExclusionRanges(vm:SceneViewModel,a:Int,b:Int) {
    val library=vm.savedTraces.collectAsState().value
    var editing by remember(a,b) {mutableStateOf(false)}
    var low by remember(a,b) {mutableStateOf("")}
    var high by remember(a,b) {mutableStateOf("")}
    val parsed=runCatching {com.droidacoustic.pro.traces.MatchRangeExclusion(a,b,low.toInt(),high.toInt())}.getOrNull()
    Text("Excluded frequency ranges · this A/B comparison")
    library.rangeExclusions.filter {it.baselineId==a && it.candidateId==b}.sortedBy {it.minHz}.forEach {range ->
        TextButton(onClick={vm.excludeMatchingRange(range,false)}) {Text("${range.minHz}–${range.maxHz} Hz · Remove exclusion")}
    }
    TextButton(onClick={editing=true}) {Text("Exclude frequency range…")}
    if(editing) AlertDialog(onDismissRequest={editing=false},title={Text("Exclude frequency range")},text={Column {
        Text("Inclusive whole-Hz limits. Overlapping ranges combine; individual sample exclusions remain. Excluded samples split phase-unwrapping segments. Raw traces stay unchanged.")
        OutlinedTextField(value=low,onValueChange={low=it},label={Text("Minimum Hz")},singleLine=true)
        OutlinedTextField(value=high,onValueChange={high=it},label={Text("Maximum Hz")},singleLine=true)
        if(parsed==null) Text("Enter positive whole-Hz limits with maximum greater than minimum.")
    }},confirmButton={TextButton(enabled=parsed!=null && library.rangeExclusions.size<1024,onClick={vm.excludeMatchingRange(parsed!!,true);editing=false}) {Text("Save exclusion")}},
        dismissButton={TextButton(onClick={editing=false}) {Text("Cancel")}})
}
