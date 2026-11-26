package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.droidacoustic.pro.geometry.ReflectionPhase
import com.droidacoustic.pro.scene.SceneViewModel

@Composable
internal fun GroundPhaseEditor(vm:SceneViewModel) {
    val phase by vm.groundReflectionPhase.collectAsState()
    var editing by remember {mutableStateOf(false)}
    var rows by remember {mutableStateOf("")}
    var source by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    Text(if(phase==null) "No measured ground phase: absorption alone cannot supply phase traces. Magnitude uses the existing real-coefficient approximation."
        else "Measured ground phase applies to the global y=0 bounce and overrides coincident rigid Faces/Blocks.",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={
        rows=phase?.curves()?.flatMap {(angle,points)->points.map {(hz,degrees)->"$angle $hz $degrees"}}?.joinToString("\n").orEmpty()
        source=phase?.source.orEmpty();error=null;editing=true
    }) {Text(if(phase==null) "Add ground reflection phase…" else "Edit ground reflection phase…")}
    if(phase!=null) TextButton(onClick={vm.setGroundReflectionPhase(null)}) {Text("Remove ground phase")}
    if(editing) AlertDialog(onDismissRequest={editing=false},title={Text("Ground reflection phase")},text={Column {
        Text("One row: angle from normal (degrees), Hz, unwrapped phase degrees. Use the exp(+iωt) convention; two frequency samples per angle. Curves interpolate and hold endpoints. Enter measured values, not phase inferred from absorption.")
        OutlinedTextField(rows,{rows=it},label={Text("Angle Hz phase°")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(source,{source=it},label={Text("Measurement source / mounting")},modifier=Modifier.fillMaxWidth())
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={runCatching {ReflectionPhase.parse(rows,source)}.onSuccess {vm.setGroundReflectionPhase(it);editing=false}.onFailure {error=it.message}}) {Text("Apply phase")}},dismissButton={TextButton(onClick={editing=false}) {Text("Cancel")}})
}
