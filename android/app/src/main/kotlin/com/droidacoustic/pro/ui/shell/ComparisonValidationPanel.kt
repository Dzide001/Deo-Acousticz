package com.droidacoustic.pro.ui.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.MainActivity
import com.droidacoustic.pro.acoustics.ComparisonWorkspace
import kotlinx.coroutines.*
import org.json.JSONObject

/** Android renders shared diagnostics; it does not decide compatibility or compute residuals. */
@Composable
internal fun ComparisonValidationPanel(state: ComparisonWorkspace, activity: MainActivity, onMessage: (String)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    var pending by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri ->
        val frozen=pending;pending=null
        if(uri==null || frozen==null) busy=false else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    activity.contentResolver.openOutputStream(uri,"wt")?.use {it.write(frozen.toByteArray(Charsets.UTF_8))}
                        ?: error("Could not open report destination")
                }
                onMessage("Comparison report exported")
            } catch(c: CancellationException) {throw c}
            catch(e: Exception) {onMessage(e.message ?: "Report export failed")}
            finally {busy=false}
        }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        SmallAction(if(expanded) "Hide validation review" else "Review protocol and residuals",Modifier.weight(1f)) {expanded=!expanded}
        if(!busy) SmallAction("Export report",Modifier.weight(1f)) {
            val frozen=state;busy=true
            scope.launch {
                try {
                    pending=withContext(Dispatchers.Default) {frozen.validationReportJson()}
                    export.launch("acoustic-comparison.json")
                } catch(c: CancellationException) {throw c}
                catch(e: Exception) {pending=null;busy=false;onMessage(e.message ?: "Report preparation failed")}
            }
        }
    }
    if(busy) Text("Preparing or exporting report…",style=MaterialTheme.typography.bodySmall)
    if(!expanded) return
    var failure by remember(state) {mutableStateOf<String?>(null)}
    val review by produceState<JSONObject?>(null,state) {
        value=null
        try {value=withContext(Dispatchers.Default) {JSONObject(state.validationReportJson(false))}}
        catch(c: CancellationException) {throw c}
        catch(e: Exception) {failure=e.message ?: "Review failed"}
    }
    val report=review ?: run {Text(failure ?: "Preparing review…");return}
    Text(report.getString("scope").replace('_',' '),style=MaterialTheme.typography.labelLarge)
    Text("Residual = engine − reference. Diagnostic pairs do not override protocol or evidence checks.",style=MaterialTheme.typography.bodySmall)
    val metrics=report.getJSONObject("metrics")
    fun number(key: String)=if(metrics.isNull(key)) "—" else "%.4f".format(metrics.getDouble(key))
    Text("${metrics.getInt("pairedCount")} paired • ${metrics.getInt("unpairedOrUnavailableCount")} unavailable/unpaired\nMax ${number("maximumAbsoluteErrorDb")} • RMS ${number("rmsErrorDb")} • bias ${number("biasDb")} dB",style=MaterialTheme.typography.bodySmall)
    val protocol=report.getJSONArray("protocol")
    for(i in 0 until protocol.length()) {
        val p=protocol.getJSONObject(i)
        Text("${p.getString("label")}: ${p.getString("status")}",style=MaterialTheme.typography.bodySmall)
    }
    var diffPage by remember(state) {mutableIntStateOf(0)}
    val diffs=report.getJSONArray("protocolDifferences")
    if(diffs.length()>0) {
        Text("Protocol differences: ${report.getInt("protocolDifferenceCount")}"+
            if(report.getBoolean("protocolDifferencesTruncated")) " (first 512 retained; originals included in export)" else "")
        for(i in diffPage*8 until minOf(diffs.length(),(diffPage+1)*8)) {
            val d=diffs.getJSONObject(i)
            Text("${d.getString("path")} • ${d.getString("kind")}\nReference: ${d.opt("reference")?.toString().orEmpty().take(240)}\nEngine: ${d.opt("engine")?.toString().orEmpty().take(240)}",style=MaterialTheme.typography.bodySmall)
        }
        ReviewPages(diffPage,diffs.length(),8) {diffPage=it}
    }
    var plotIndex by remember(state) {mutableIntStateOf(0)}
    val plots=report.getJSONArray("plots");val plot=plots.getJSONObject(plotIndex)
    Text("${plot.getString("name")} • ${plot.getString("xScale")}",style=MaterialTheme.typography.bodySmall)
    Text("Residual scale ±${"%.3f".format(plot.getDouble("residualScaleDb"))} dB; shaded tolerance band",style=MaterialTheme.typography.bodySmall)
    val dots=plot.getJSONArray("points")
    val color=MaterialTheme.colorScheme.primary;val axis=MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val pad=8.dp.toPx();val w=(size.width-2*pad).coerceAtLeast(0f);val h=(size.height-2*pad).coerceAtLeast(0f)
        val band=plot.getDouble("toleranceNormalized").toFloat()*h
        drawRect(color.copy(alpha=.12f),Offset(pad,pad+h/2-band),androidx.compose.ui.geometry.Size(w,2*band))
        drawLine(axis,Offset(pad,pad+h/2),Offset(pad+w,pad+h/2))
        for(i in 0 until dots.length()) {
            val p=dots.getJSONObject(i)
            drawCircle(color,2.dp.toPx(),Offset(pad+w*p.getDouble("xNormalized").toFloat(),pad+h*p.getDouble("yNormalized").toFloat()))
        }
    }
    if(dots.length()==0) Text("No comparable sample pairs for plotting.",style=MaterialTheme.typography.bodySmall)
    ReviewPages(plotIndex,plots.length(),1) {plotIndex=it}
    var samplePage by remember(state) {mutableIntStateOf(0)}
    val samples=report.getJSONArray("samples")
    Text("Sample residuals",style=MaterialTheme.typography.labelLarge)
    for(i in samplePage*10 until minOf(samples.length(),(samplePage+1)*10)) {
        val s=samples.getJSONObject(i)
        Text("${s.getString("sampleId")} • ${s.getDouble("frequencyHz").let {if(it==0.0) "broadband" else "$it Hz"}} • (${s.opt("xM")}, ${s.opt("yM")}, ${s.opt("zM")}) m\nReference ${s.opt("referenceLevelDb")} • engine ${s.opt("engineLevelDb")} • Δ ${s.opt("residualDb")} dB • ${s.getString("status")}",style=MaterialTheme.typography.bodySmall)
    }
    ReviewPages(samplePage,samples.length(),10) {samplePage=it}
}

@Composable
private fun ReviewPages(page: Int,count: Int,size: Int,onPage: (Int)->Unit) {
    if(count<=size) return
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(page>0) SmallAction("Previous") {onPage(page-1)}
        Text("${page+1}/${(count+size-1)/size}",style=MaterialTheme.typography.bodySmall)
        if((page+1)*size<count) SmallAction("Next") {onPage(page+1)}
    }
}
