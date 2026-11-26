package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidacoustic.pro.scene.SceneViewModel
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun CoverageComparisonInspector(vm: SceneViewModel) {
    val library by vm.designVariants.collectAsState()
    val selection by vm.coverageComparisonSelection.collectAsState()
    val result by vm.coverageDifference.collectAsState()
    val running by vm.coverageComparisonRunning.collectAsState()
    val error by vm.coverageComparisonError.collectAsState()
    var expanded by remember {mutableStateOf<Int?>(null)}
    var show by remember {mutableStateOf(false)}
    val a=library.variants.firstOrNull {it.id==selection?.baselineId}
    val b=library.variants.firstOrNull {it.id==selection?.candidateId}
    Text("Coverage difference maps",style=MaterialTheme.typography.titleSmall)
    listOf("Baseline A" to a,"Candidate B" to b).forEachIndexed {index,(label,variant) ->
        Box {
            TextButton(onClick={expanded=index},enabled=library.variants.isNotEmpty()) {Text("$label: ${variant?.name ?: "Choose design"}")}
            DropdownMenu(expanded=expanded==index,onDismissRequest={expanded=null}) {
                library.variants.forEach {v -> DropdownMenuItem(text={Text("${v.name} (#${v.id})")},onClick={
                    vm.selectCoverageComparison(if(index==0) v.id else a?.id ?: v.id,
                        if(index==1) v.id else b?.id ?: v.id)
                    expanded=null
                })}
            }
        }
    }
    Text("B − A in dB. Same audience mesh and analysis bands required. Saved designs are calculated without changing your open design.",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={vm.calculateCoverageComparison();show=true},enabled=a!=null && b!=null && !running) {
        Text(if(running) "Comparing designs…" else "Calculate difference map")
    }
    if(result!=null) TextButton(onClick={show=true}) {Text("View difference map")}
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    if(show) Dialog(onDismissRequest={show=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth(.9f).heightIn(max=850.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("Coverage difference: ${b?.name ?: "B"} − ${a?.name ?: "A"}",style=MaterialTheme.typography.titleLarge)
                if(running) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Calculating saved designs…")}
                error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                result?.let {map ->
                    map.convention?.let {c ->
                        Text("Bands: ${c.bands.joinToString()} Hz · ${c.widthOct} octave bandwidth · " +
                            if(c.bands.size==1) "unweighted band level" else "weighted broadband level",style=MaterialTheme.typography.bodySmall)
                    }
                    var surfaceKey by remember(map) {mutableStateOf<String?>(null)}
                    var surfaceMenu by remember {mutableStateOf(false)}
                    var projection by remember {mutableStateOf("3D")}
                    val mesh=map.geometry
                    val points=mesh.receivers()
                    val surfaces=mesh.surfaces()
                    Box {
                        TextButton(onClick={surfaceMenu=true}) {Text("Surface: ${surfaces.firstOrNull {it.surfaceKey==surfaceKey}?.name ?: "All"}")}
                        DropdownMenu(expanded=surfaceMenu,onDismissRequest={surfaceMenu=false}) {
                            DropdownMenuItem(text={Text("All")},onClick={surfaceKey=null;surfaceMenu=false})
                            surfaces.forEach {s ->DropdownMenuItem(text={Text(s.name ?: s.surfaceKey)},onClick={surfaceKey=s.surfaceKey;surfaceMenu=false})}
                        }
                    }
                    Row {listOf("3D","Plan","Front").forEach {view -> TextButton(onClick={projection=view}) {Text(if(view==projection) "• $view" else view)}}}
                    val indices=points.indices.filter {surfaceKey==null || points[it].surfaceKey==surfaceKey}
                    val span=max(1f,indices.map {abs(map.differenceAt(it))}.filter {it.isFinite()}.maxOrNull() ?: 1f)
                    fun color(value:Float): Color {
                        if(!value.isFinite()) return Color(0xFF656565)
                        val t=(abs(value)/span).coerceIn(0f,1f)
                        val end=if(value<0) Color(0xFF2979FF) else Color(0xFFFF8C24)
                        return androidx.compose.ui.graphics.lerp(Color(0xFFF0F0F0),end,t)
                    }
                    Canvas(Modifier.fillMaxWidth().height(370.dp)) {
                        fun projected(i:Int): Offset {
                            val p=points[i]
                            return when(projection) {
                                "Plan" ->Offset(p.x,p.z)
                                "Front" ->Offset(p.x,-p.surfaceY)
                                else ->Offset(p.x-.55f*p.z,.35f*p.z-p.surfaceY)
                            }
                        }
                        if(indices.isNotEmpty()) {
                            val positions=indices.associateWith {projected(it)}
                            val minX=positions.values.minOf {it.x};val maxX=positions.values.maxOf {it.x}
                            val minY=positions.values.minOf {it.y};val maxY=positions.values.maxOf {it.y}
                            val scale=minOf((size.width-24)/(maxX-minX).coerceAtLeast(.1f),(size.height-24)/(maxY-minY).coerceAtLeast(.1f))
                            fun position(i:Int): Offset {val p=projected(i);return Offset(size.width/2+(p.x-(minX+maxX)/2)*scale,size.height/2+(p.y-(minY+maxY)/2)*scale)}
                            mesh.triangles().filter {it.a in positions}.sortedBy {points[it.a].surfaceY}.forEach {f ->
                                val vertices=listOf(f.a,f.b,f.c)
                                val values=vertices.map {map.differenceAt(it)}
                                val value=if(values.all {it.isFinite()}) values.average().toFloat() else Float.NaN
                                val path=Path();vertices.forEachIndexed {index,i ->val p=position(i);if(index==0) path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y)};path.close()
                                drawPath(path,color(value))
                            }
                            val used=mesh.triangles().flatMap {listOf(it.a,it.b,it.c)}.toSet()
                            indices.filter {it !in used}.forEach {drawCircle(color(map.differenceAt(it)),4f,position(it))}
                        }
                    }
                    Text("Blue: −${"%.1f".format(span)} dB   White: 0 dB   Orange: +${"%.1f".format(span)} dB")
                    Text("Gray: unavailable or zero energy. Face colors show mean vertex difference. Select a surface to inspect overlapping floors or balconies.",style=MaterialTheme.typography.bodySmall)
                    val stats=map.statistics.overall
                    Text("All surfaces: area-weighted mean ${stats.meanDb?.let {"%+.2f dB".format(it)} ?: "unavailable"}; valid ${"%.1f".format(stats.validAreaPercent)}% of ${"%.1f".format(stats.totalAreaM2)} m²")
                    Text("Positive means B is louder, not necessarily better. Maps are recalculated after reopening; camera and current design are unchanged.",style=MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick={show=false}) {Text("Close")}
            }
        }
    }
}
