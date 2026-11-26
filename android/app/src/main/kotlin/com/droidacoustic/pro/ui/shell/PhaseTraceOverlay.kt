package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.traces.PhaseBandComparison
import kotlin.math.ln

/** Samples only: sparse octave samples cannot justify phase unwrapping or connecting across wraps. */
@Composable
internal fun PhaseTraceOverlay(bands:List<PhaseBandComparison>) {
    if(bands.none {it.baseline?.degrees!=null || it.candidate?.degrees!=null}) return
    Text("A blue · B orange · +180° top / −180° bottom · frequency increases left → right")
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        val lo=ln(bands.first().hz.toDouble());val hi=ln(bands.last().hz.toDouble())
        drawLine(Color.Gray,Offset(0f,size.height/2),Offset(size.width,size.height/2))
        bands.forEach {row ->
            val x=if(hi==lo) size.width/2 else (8+(size.width-16)*(ln(row.hz.toDouble())-lo)/(hi-lo)).toFloat()
            listOf(row.baseline?.degrees to Color(0xFF448AFF),row.candidate?.degrees to Color(0xFFFF9800)).forEach {(degrees,color) ->
                degrees?.let {drawCircle(color,4.dp.toPx(),Offset(x,((180-it)/360*(size.height-16)+8).toFloat()))}
            }
        }
    }
}
