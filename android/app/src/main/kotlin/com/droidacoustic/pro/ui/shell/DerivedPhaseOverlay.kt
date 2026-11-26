package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.traces.*
import kotlin.math.ln

@Composable
internal fun DerivedPhaseOverlay(a: SavedTrace, b: SavedTrace, relativeToReference:Boolean=false) {
    val comparison = PhaseDerivedTraces.compare(a, b,relativeToReference)
    Text("Unwrapped phase · sampled estimate", style = MaterialTheme.typography.titleSmall)
    Text("Delay-referenced estimate: removes the selected reference before unwrapping. Total mode restores it; relative mode leaves it removed. Residual phase changes must stay below 180° between samples. Each segment starts at its wrapped residual phase; missing data breaks the trace.", style = MaterialTheme.typography.bodySmall)
    if (!comparison.compatible) {
        Text("Unavailable: ${comparison.reason}", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text("Reference A: %.3f ms · B: %.3f ms (selected references)".format(a.effectiveDelayReferenceS*1000,b.effectiveDelayReferenceS*1000), style = MaterialTheme.typography.bodySmall)
    val baseline = comparison.baseline!!; val candidate = comparison.candidate!!
    DerivedPlot(baseline.points.map { Triple(it.hz.toDouble(), it.degrees, it.segment) },
        candidate.points.map { Triple(it.hz.toDouble(), it.degrees, it.segment) }, "°")
    fun value(v: Double?, unit: String) = v?.let { "%+.3f $unit".format(it) } ?: "Unavailable"
    Text("${baseline.points.size} samples; full plots, first 64 numeric rows.", style = MaterialTheme.typography.bodySmall)
    baseline.points.zip(candidate.points).take(64).forEach { (left, right) ->
        Text("${left.hz} Hz: ${value(left.degrees, "°")} → ${value(right.degrees, "°")}", style = MaterialTheme.typography.bodySmall)
    }
    Text("Group delay · interval estimate", style = MaterialTheme.typography.titleSmall)
    Text("Positive values mean delay. Each value is the phase slope over its frequency interval, not an absolute arrival time. Valid only when the reference-removed phase change stays below 180°; the ± limit below is not a measured bound.", style = MaterialTheme.typography.bodySmall)
    if (baseline.delays.isEmpty()) Text("Unavailable: complex data at two or more frequencies is required.", style = MaterialTheme.typography.bodySmall)
    // Interval averages are dots: connecting them would imply an unmeasured continuous derivative.
    fun delayPoints(trace: DerivedPhaseTrace) = trace.delays.mapIndexed { i, d ->
        Triple((d.fromHz.toDouble() + d.toHz) / 2, d.milliseconds, i)
    }
    DerivedPlot(delayPoints(baseline), delayPoints(candidate), "ms")
    baseline.delays.zip(candidate.delays).take(64).forEach { (left, right) ->
        Text("${left.fromHz}–${left.toHz} Hz: ${value(left.milliseconds, "ms")} → ${value(right.milliseconds, "ms")}; residual alias limit ±%.3f ms".format(left.aliasLimitMs), style = MaterialTheme.typography.bodySmall)
    }
    (baseline.points + candidate.points).mapNotNull { it.reason }.distinct().forEach {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DerivedPlot(a: List<Triple<Double, Double?, Int?>>, b: List<Triple<Double, Double?, Int?>>, unit: String) {
    val values = (a + b).mapNotNull { it.second }
    if (values.isEmpty()) return
    val low = values.minOrNull()!! - 1.0; val high = values.maxOrNull()!! + 1.0
    val frequencies = (a + b).map { it.first }
    val lo = ln(frequencies.minOrNull()!!); val hi = ln(frequencies.maxOrNull()!!)
    Text("A blue · B orange · %.2f to %.2f %s · frequency increases left → right".format(low, high, unit), style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        listOf(a to Color(0xFF448AFF), b to Color(0xFFFF9800)).forEach { (rows, color) ->
            var previous: Offset? = null; var segment: Int? = null
            rows.forEach { row ->
                val p = row.second?.let { value -> Offset(
                    if (lo == hi) size.width / 2 else (8 + (size.width - 16) * (ln(row.first) - lo) / (hi - lo)).toFloat(),
                    (8 + (size.height - 16) * (high - value) / (high - low)).toFloat()) }
                if (p != null) {
                    if (previous != null && segment == row.third) drawLine(color, previous!!, p, 2.dp.toPx())
                    drawCircle(color, 3.dp.toPx(), p)
                }
                previous = p; segment = row.third
            }
        }
    }
}
