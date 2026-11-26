package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.SceneViewModel

@Composable
internal fun DspFilterControls(vm:SceneViewModel,id:Int,chain:DspFilterChain) {
    var kind by remember(id) {mutableStateOf(FilterKind.LOW_PASS)}
    var hz by remember(id) {mutableStateOf("1000")}
    var q by remember(id) {mutableStateOf("0.7071067811865476")}
    var gain by remember(id) {mutableStateOf("0")}
    var choosing by remember {mutableStateOf(false)}
    Text("Digital filters · 48 kHz")
    Text("Validated biquad magnitude and phase. Legacy band EQ remains magnitude-only.",style=MaterialTheme.typography.bodySmall)
    chain.filters.forEachIndexed {i,f ->
        Text("${i+1}. ${f.kind.name.replace('_',' ')} · ${f.frequencyHz} Hz · Q ${f.q} · ${f.gainDb} dB · ${f.sampleRateHz.toInt()} Hz sample rate")
        TextButton(onClick={vm.setFilters(id,DspFilterChain(chain.filters.filterIndexed {j,_->i!=j}))}) {Text("Remove filter ${i+1}")}
    }
    Box {
        TextButton(onClick={choosing=true}) {Text("Add ${kind.name.replace('_',' ')}")}
        DropdownMenu(choosing,{choosing=false}) {FilterKind.entries.forEach {k ->
            DropdownMenuItem(text={Text(k.name.replace('_',' '))},onClick={kind=k;choosing=false})
        }}
    }
    OutlinedTextField(hz,{hz=it},label={Text("Frequency (Hz)")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(q,{q=it},label={Text("Q (0.1–20)")},singleLine=true,modifier=Modifier.fillMaxWidth())
    if(kind==FilterKind.PEAK) OutlinedTextField(gain,{gain=it},label={Text("Gain (−24 to +24 dB)")},singleLine=true,modifier=Modifier.fillMaxWidth())
    val filter=runCatching {DspFilter(kind,hz.toDouble(),q.toDouble(),if(kind==FilterKind.PEAK) gain.toDouble() else 0.0)}.getOrNull()
    TextButton(enabled=filter!=null && chain.filters.size<16,onClick={vm.setFilters(id,DspFilterChain(chain.filters+filter!!))}) {Text("Add filter")}
    if(filter==null) Text("Enter valid filter values; frequency must be 10–23520 Hz (upper bound excluded).")
}
