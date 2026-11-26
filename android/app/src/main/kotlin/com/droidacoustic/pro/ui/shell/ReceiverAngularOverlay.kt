package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.droidacoustic.pro.geometry.ReceiverLabelRect
import com.droidacoustic.pro.prediction.ReceiverAngularReport
import com.droidacoustic.pro.scene.SceneViewModel
import kotlinx.coroutines.*

internal data class ReceiverAngularPresentation(val report:ReceiverAngularReport?=null,val message:String?=null,val revision:Long=-1)

@Composable
internal fun rememberReceiverAngularOverlay(vm:SceneViewModel):ReceiverAngularPresentation {
    val revision by vm.revision.collectAsState()
    val enabled by vm.receiverAngularOverlayEnabled.collectAsState()
    val state by produceState(ReceiverAngularPresentation(),revision,enabled) {
        value=ReceiverAngularPresentation(revision=revision)
        if(enabled) try {
            val (request,targets)=vm.captureReceiverAngularOverlay()
            val context=currentCoroutineContext()
            val report=withContext(Dispatchers.Default) {request.receiverAngularSupport(targets) {context.ensureActive()}}
            if(vm.revision.value==revision && report.sceneRevision==revision && vm.receiverAngularOverlayEnabled.value)
                value=ReceiverAngularPresentation(report,revision=revision)
        } catch(c:CancellationException) {throw c}
        catch(e:Exception) {value=ReceiverAngularPresentation(message=e.message ?: "Support inspection failed",revision=revision)}
    }
    // Clear old markers synchronously, before the producer starts its next worker.
    return if(enabled && state.revision==revision) state else ReceiverAngularPresentation(revision=revision)
}

@Composable
internal fun ReceiverAngularOverlay(vm:SceneViewModel,state:ReceiverAngularPresentation,modifier:Modifier=Modifier,onFrame:()->Unit={},onBounds:(ReceiverLabelRect)->Unit={}) {
    val enabled by vm.receiverAngularOverlayEnabled.collectAsState()
    val band by vm.selectedBandHz.collectAsState()
    val speakers by vm.speakers.collectAsState()
    val selected by vm.receiverAngularSelection.collectAsState()
    val focus by vm.receiverCameraFocus.collectAsState()
    val frameMessage by vm.receiverFrameMessage.collectAsState()
    var details by remember {mutableStateOf(true)}
    LaunchedEffect(focus?.receiverId) {if(focus!=null) details=false}
    val scroll=rememberScrollState()
    LaunchedEffect(selected) {scroll.scrollTo(0)}
    var page by remember(selected,state.report) {mutableIntStateOf(0)}
    Column(modifier.widthIn(max=300.dp).onGloballyPositioned {c->val p=c.positionInRoot();onBounds(ReceiverLabelRect(p.x,p.y,c.size.width.toFloat(),c.size.height.toFloat()))}.background(MaterialTheme.colorScheme.surface.copy(alpha=.96f)).padding(8.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            SmallAction(if(enabled) "Hide" else "Show receiver support") {vm.setReceiverAngularOverlayEnabled(!enabled)}
            if(enabled) {
                SmallAction(if(details) "Frame selected" else "Frame") {details=false;onFrame()}
                SmallAction(if(details) "Less detail" else "Details") {details=!details}
            }
        }
        frameMessage?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        if(enabled && details) Column(Modifier.heightIn(max=280.dp).verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            focus?.let {f->
                Text("Frame target ${f.receiverId} • ${f.spanM} m context",style=MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {SmallAction("Closer") {vm.scaleReceiverFrame(.5f)};SmallAction("Wider") {vm.scaleReceiverFrame(2f)}}
            }
            Text("Direct-ray angular support • $band Hz band",style=MaterialTheme.typography.labelLarge)
            state.report?.widthOct?.let {Text("%.3f octave width; selected band only".format(it),style=MaterialTheme.typography.labelSmall)}
            Text("Select tool: tap markers or labels. Coincident marker taps cycle receivers. Labels are screen annotations and can show through geometry. Crowding may hide labels; the list shows all receivers.",style=MaterialTheme.typography.labelSmall)
            Text("Committed scene. Outer diamond: support. Inner: origin. Not SPL/phase accuracy; reflected and diffuse paths are not assessed.",style=MaterialTheme.typography.bodySmall)
            Text("Support: red unavailable • amber gap/sparse • blue interpolation • teal stored • grey unassessed\nOrigin: teal declared measured • purple symmetry • pink interpolated • gold mixed • grey undeclared",style=MaterialTheme.typography.labelSmall)
            val report=state.report
            if(report==null) Text(state.message ?: "Inspecting receiver directions…",style=MaterialTheme.typography.bodySmall)
            else {
                val r=report.receivers.firstOrNull {it.receiver.id==selected} ?: report.receivers.firstOrNull()
                r?.let {
                    Text("${com.droidacoustic.pro.geometry.ReceiverMarkerSelection.label(r.receiver)} (${r.receiver.position.x}, ${r.receiver.position.y}, ${r.receiver.position.z}) m",style=MaterialTheme.typography.labelSmall)
                    if(r.rows.isEmpty()) Text("No source directions to inspect.",style=MaterialTheme.typography.bodySmall)
                    r.rows.drop(page*12).take(12).forEach {row->
                        val name=speakers.firstOrNull {it.id==row.sourceId}?.label ?: "Source ${row.sourceId}"
                        val p=row.probe
                        Text("$name • element ${row.elementIndex+1} • ${row.dataHz} Hz → ${p.frequencyHz} Hz ${p.response}${if(!p.required) " (information only)" else ""}\n"+
                            "az %.2f°, el %.2f° • ".format(row.azimuthDeg,row.elevationDeg)+
                            (p.issue ?: "${p.point?.samplingStatus} / ${p.point?.origin}; contributors ${p.point?.contributors?.joinToString()}") ,style=MaterialTheme.typography.labelSmall)
                    }
                    if(r.rows.size>12) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(page>0) SmallAction("Previous") {page--}
                        Text("${page+1}/${(r.rows.size+11)/12}",style=MaterialTheme.typography.labelSmall)
                        if((page+1)*12<r.rows.size) SmallAction("Next") {page++}
                    }
                }
                Text("Receivers",style=MaterialTheme.typography.labelLarge)
                report.receivers.forEach {entry->
                    val label=com.droidacoustic.pro.geometry.ReceiverMarkerSelection.label(entry.receiver)
                    Text("$label: ${entry.support} / ${entry.origin}",color=Color(0xff000000L or entry.supportColorRgb.toLong()),
                        style=MaterialTheme.typography.bodySmall,modifier=Modifier.fillMaxWidth().clickable {vm.selectAngularReceiver(entry.receiver.id)})
                }
            }
        }
    }
}
