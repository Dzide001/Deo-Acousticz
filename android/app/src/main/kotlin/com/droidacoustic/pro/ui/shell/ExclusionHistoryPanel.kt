package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.traces.*

@Composable
internal fun ExclusionHistoryPanel(library:SavedTraces,a:Int,b:Int,onInspect:(MatchSampleSelection?,Boolean)->Unit,onApply:(ExclusionHistoryPreview)->Boolean) {
    val view=library.exclusionHistory(a,b)
    var expanded by remember(a,b) {mutableStateOf(false)}
    var preview by remember(a,b) {mutableStateOf<ExclusionHistoryPreview?>(null)}
    var error by remember(a,b) {mutableStateOf<String?>(null)}
    TextButton(onClick={expanded=!expanded}) {Text(if(expanded) "Hide exclusion history" else "Show exclusion history")}
    if(expanded) Column {
        Text("Exclusion edit history · this A/B pair")
        Text("Choose a state to preview its exclusions and fit. Apply explicitly to navigate history; Cancel leaves your saved state and drafts unchanged. Only available history is shown.")
        view.entries.forEach {entry ->
            val status=when {
                entry.isCurrent -> "Current"
                entry.position<view.currentPosition -> "Earlier"
                else -> "Redo available"
            }
            TextButton(enabled=!entry.isCurrent,onClick={
                error=null
                preview=runCatching {ExclusionHistoryPreviews.preview(library,view,entry.position)}.getOrElse {
                    error="History changed. Select a state again.";null
                }
            }) {
                Text("${entry.position} · $status · ${entry.description}\n${entry.sampleExclusions} sample exclusions · ${entry.rangeExclusions} range exclusions")
            }
        }
        if(preview==null) error?.let {Text(it)}
    }
    preview?.let {p ->
        AlertDialog(onDismissRequest={preview=null;error=null},title={Text("Preview exclusion history state")},text={
            Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState())) {
                Text("State ${p.current.position} → ${p.target.position}: ${p.target.description}")
                Text("Preview only · saved exclusions and history have not changed.")
                Text("Sample exclusions: ${p.current.sampleExclusions} → ${p.target.sampleExclusions}")
                Text("Range exclusions: ${p.current.rangeExclusions} → ${p.target.rangeExclusions}")
                fun frequencies(hz:Set<Int>)=if(hz.isEmpty()) "none" else hz.sorted().take(16).joinToString()+if(hz.size>16) " … (${hz.size} total)" else ""
                Text("Newly excluded captured Hz (full trace): ${frequencies(p.newlyExcludedHz)}")
                Text("Newly included captured Hz (full trace): ${frequencies(p.newlyIncludedHz)}")
                HistoryFitSummary("Current fit",p.currentFit)
                HistoryFitSummary("Previewed fit",p.proposedFit)
                val selected=library.inspectedSample?.takeIf {it.baselineId==a && it.candidateId==b}?.hz
                ExclusionHistoryFitOverlay(p.overlay,selected,library.overlayChangedOnly,
                    onSelect={hz ->onInspect(hz?.let {MatchSampleSelection(a,b,it)},library.overlayChangedOnly)},
                    onFilter={changedOnly ->
                        val visible=OverlaySampleInspection.resolve(p.overlay,selected,changedOnly)
                        onInspect(visible?.let {MatchSampleSelection(a,b,it.hz)},changedOnly)
                    })
                Text("Apply changes only this pair's saved exclusions/history and discards unsaved range drafts. It does not apply the proposed delay reference or change speaker delays. Scene undo is available.")
                error?.let {Text(it)}
            }
        },confirmButton={TextButton(onClick={
            if(onApply(p)) {preview=null;error=null} else error="Project traces or history changed. Cancel and preview again."
        }) {Text("Apply history state")}},dismissButton={TextButton(onClick={preview=null;error=null}) {Text("Cancel preview")}})
    }
}

@Composable
private fun HistoryFitSummary(label:String,fit:TraceReferenceMatch) {
    Text(label)
    Text(fit.frequencyRange?.let {"Matching window: ${it.minHz}–${it.maxHz} Hz"} ?: "Matching window: all captured frequencies")
    Text("${fit.usedSamples} retained · ${fit.rejectedFrequencies.size} rejected · ${fit.confidenceScore}/100 confidence")
    if(fit.available) Text("Matched B reference: %.3f ms".format(fit.proposedReferenceS!!*1000))
    else Text("Fit unavailable: ${fit.reason ?: "Insufficient data"}")
}
