package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.alignment.*
import com.droidacoustic.pro.scene.SceneViewModel
import com.droidacoustic.pro.scene.SpeakerDsp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SystemAdjustmentControls(vm: SceneViewModel) {
    val speakers by vm.speakers.collectAsState()
    val dsps by vm.dspMap.collectAsState()
    val undo by vm.canUndo.collectAsState()
    val redo by vm.canRedo.collectAsState()
    var sourceId by remember {mutableStateOf<Int?>(null)}
    val source=speakers.firstOrNull {it.id==sourceId} ?: speakers.firstOrNull()
    val dsp=source?.let {dsps[it.id] ?: SpeakerDsp(it.id)}
    var choose by remember {mutableStateOf(false)}
    var gain by remember(source?.id,dsp) {mutableStateOf(dsp?.gainDb?.toString() ?: "0")}
    var delay by remember(source?.id,dsp) {mutableStateOf(dsp?.delayMs?.toString() ?: "0")}
    var inverted by remember(source?.id,dsp) {mutableStateOf(dsp?.polarity ?: false)}
    var preview by remember {mutableStateOf<SystemAdjustmentPreview?>(null)}
    var running by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val proposed=runCatching {SystemDsp(gain.toFloat(),delay.toFloat(),inverted)}.getOrNull()
    Text("System adjustment preview",style=MaterialTheme.typography.titleSmall)
    Box {
        TextButton(onClick={choose=true},enabled=!running && speakers.isNotEmpty()) {Text("Adjust: ${source?.label ?: "No speaker"}")}
        DropdownMenu(choose,{choose=false}) {
            speakers.forEach {s -> DropdownMenuItem(text={Text(s.label)},onClick={sourceId=s.id;choose=false;message=null})}
        }
    }
    OutlinedTextField(gain,{gain=it;message=null},label={Text("Proposed gain (−12 to +12 dB)")},singleLine=true,enabled=!running,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(delay,{delay=it;message=null},label={Text("Proposed delay (0–200 ms)")},singleLine=true,enabled=!running,modifier=Modifier.fillMaxWidth())
    Row {Checkbox(inverted,{inverted=it;message=null},enabled=!running);Text("Invert polarity")}
    Text("Absolute settings for this speaker. Preview compares every saved receiver using the current band/spectrum, environment and boundary settings.",style=MaterialTheme.typography.bodySmall)
    TextButton(enabled=!running && source!=null && proposed!=null && dsp?.let {SystemDsp(it.gainDb,it.delayMs,it.polarity)}!=proposed,onClick={
        val id=source!!.id;val settings=proposed!!
        scope.launch {
            running=true;message=null
            try {
                preview=vm.previewSystemAdjustment(id,settings)
                if(preview==null) message="Preview unavailable: save a receiver, change a setting, or retry after scene edits."
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {message="Preview failed: ${e.message ?: "calculation unavailable"}"}
            finally {running=false}
        }
    }) {Text(if(running) "Calculating preview…" else "Preview gain, delay and polarity")}
    if(proposed==null) Text("Enter finite gain and delay values within the stated limits.")
    message?.let {Text(it)}
    Row {
        TextButton(onClick={vm.undoScene();message=null},enabled=undo && !running) {Text("Undo scene edit")}
        TextButton(onClick={vm.redoScene();message=null},enabled=redo && !running) {Text("Redo scene edit")}
    }
    preview?.let {p ->
        fun db(v:Double?)=v?.let {"%+.2f dB".format(it)} ?: "Unavailable"
        AlertDialog(onDismissRequest={preview=null},title={Text("Preview system adjustment")},text={
            Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                val a=p.adjustment.before;val b=p.adjustment.after
                Text(speakers.firstOrNull {it.id==p.adjustment.sourceId}?.label ?: "Speaker ${p.adjustment.sourceId}")
                Text("Gain ${a.gainDb} → ${b.gainDb} dB; delay ${a.delayMs} → ${b.delayMs} ms; polarity ${if(a.inverted) "inverted" else "normal"} → ${if(b.inverted) "inverted" else "normal"}")
                Text("Predicted band levels use the current coverage model. Generic/magnitude-only models are approximate; these comparisons do not establish measured phase alignment. Missing data and zero-power nulls remain unavailable. Differences are after minus before.")
                p.receivers.forEach {r ->
                    Text("${r.receiver.receiver.name} (#${r.receiver.id})",style=MaterialTheme.typography.titleSmall)
                    Text("Total: ${db(r.total.beforeDb)} → ${db(r.total.afterDb)}; change ${db(r.total.differenceDb)}")
                    r.bands.forEach {(hz,v)->Text("$hz Hz: ${db(v.beforeDb)} → ${db(v.afterDb)}; change ${db(v.differenceDb)}")}
                    val before=r.beforeArrivals.firstOrNull {it.sourceId==p.adjustment.sourceId}
                    val after=r.afterArrivals.firstOrNull {it.sourceId==p.adjustment.sourceId}
                    if(before==null || after==null) Text("Relative arrival unavailable: select an existing reference speaker.")
                    else Text("Direct arrival %.2f → %.2f ms; relative %+.2f → %+.2f ms. Geometric timing includes DSP; it does not establish audibility or phase alignment.".format(before.earliestMs,after.earliestMs,before.relativeArrivalMs,after.relativeArrivalMs))
                }
                Text("Apply updates all three DSP settings together. Undo/Redo restores the complete scene edit. Preview drafts are not saved; applied settings and receivers survive save/reopen.")
            }
        },confirmButton={TextButton(onClick={
            val applied=vm.applySystemAdjustment(p);preview=null
            message=if(applied) "System adjustment applied. Undo is available." else "Scene changed. Recalculate the preview before applying."
        }) {Text("Apply adjustment")}},dismissButton={TextButton(onClick={preview=null}) {Text("Cancel")}})
    }
}
