package com.droidacoustic.pro.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.prediction.PredictionState
import com.droidacoustic.pro.prediction.PredictionModelScope
import com.droidacoustic.pro.scene.HeatCell
import com.droidacoustic.pro.ui.components.StatusChip
import com.droidacoustic.pro.ui.theme.Instrument
import com.droidacoustic.pro.statistics.CoverageStatistics

// =============================================================================
// Analysis strip
// =============================================================================
//
// The numbers get a permanent home along the bottom of the viewport instead of
// living behind a "Results" tab switch. You should be able to move a speaker
// and watch the average level change without navigating anywhere.
// =============================================================================

@Composable
fun AnalysisStrip(
    heatmap: List<HeatCell>,
    combinedSplDb: Float?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    predictionState: PredictionState = PredictionState.EMPTY,
    statusLabel: String? = null,
    coverageStatistics: CoverageStatistics? = null
) {
    val overall = coverageStatistics?.overall
    val avg = overall?.meanDb
    val dev = overall?.standardDeviationDb
    val zones = coverageStatistics?.surfaces().orEmpty()
    val worst = zones.filter { it.areaId != null && it.statistics.meanDb != null }
        .minByOrNull { it.statistics.meanDb!! }

    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(statusLabel ?: predictionStateLabel(predictionState), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            Metric("Area avg", avg?.let { "%.1f".format(it) } ?: "—", "dB")
            Metric("Area std dev", dev?.let { "±%.1f".format(it) } ?: "—", "dB")
            if (worst != null) {
                Metric(
                    "Worst zone",
                    worst.name ?: worst.surfaceKey,
                    "%.1f dB".format(worst.statistics.meanDb)
                )
            }
            Box(Modifier.weight(1f))
            Text(
                if (expanded) "Hide detail  ▾" else "Show detail  ▴",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = false
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(PredictionModelScope.summary,
                    style = MaterialTheme.typography.bodySmall)
                PredictionModelScope.limitations.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Instrument.Caution)
                }
                if (coverageStatistics == null) {
                    Text(
                        "No coverage data yet. Place a speaker and draw an audience zone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "COVERAGE BY ZONE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        "Area weighted over each audience surface; probes excluded.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (overall != null) {
                        Text("Valid area %.1f / %.1f m² • Unavailable %.1f m²".format(
                            overall.validAreaM2, overall.totalAreaM2, overall.missingAreaM2),
                            style = MaterialTheme.typography.bodySmall)
                        if (overall.p10Db != null && overall.p90Db != null)
                            Text("P10–P90: %.1f–%.1f dB".format(overall.p10Db,overall.p90Db),
                                style = MaterialTheme.typography.bodySmall)
                        overall.withinTargetPercent?.let { percent ->
                            Text("Within ±3 dB of target: %.1f%% of total area".format(percent),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (zones.isEmpty()) {
                        Text(
                            "Coverage is being sampled on the default grid. " +
                                "Draw audience zones to get per-zone figures.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val globalMin = overall?.minDb?.toFloat() ?: 0f
                        val globalMax = overall?.maxDb?.toFloat() ?: 1f
                        zones.forEach { zone ->
                            val stats = zone.statistics
                            val zoneMean = stats.meanDb
                            if (zoneMean != null) ZoneBar(
                                name = zone.name ?: zone.surfaceKey,
                                avgDb = zoneMean.toFloat(),
                                spreadDb = ((stats.p90Db ?: 0.0) - (stats.p10Db ?: 0.0)).toFloat(),
                                min = globalMin,
                                max = globalMax
                            ) else Text("${zone.name ?: zone.surfaceKey}: ${if (stats.totalAreaM2 == 0.0) "no audience area" else "unavailable"}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String) {
    Column {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false
            )
            if (unit.isNotEmpty()) {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun ZoneBar(name: String, avgDb: Float, spreadDb: Float, min: Float, max: Float) {
    val t = if (max > min) ((avgDb - min) / (max - min)).coerceIn(0f, 1f) else 0.5f
    val colour = splRampColour(t)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(120.dp),
            softWrap = false
        )
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(t)
                    .height(8.dp)
                    .background(colour, RoundedCornerShape(2.dp))
            )
        }
        Text(
            "  %.1f dB".format(avgDb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false
        )
        StatusChip(
            text = "P90−P10 %.1f dB".format(spreadDb),
            color = when {
                spreadDb <= 6f -> Instrument.Good
                spreadDb <= 12f -> Instrument.Caution
                else -> Instrument.Warning
            },
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** Samples the SPL ramp at t in [0,1]. */
fun splRampColour(t: Float): Color {
    val ramp = Instrument.Spl
    val x = (t.coerceIn(0f, 1f)) * (ramp.size - 1)
    val i = x.toInt().coerceAtMost(ramp.size - 2)
    val f = x - i
    val a = ramp[i]
    val b = ramp[i + 1]
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = 1f
    )
}

internal fun predictionStateLabel(state: PredictionState): String = when (state) {
    PredictionState.EMPTY -> "Not calculated"
    PredictionState.STALE -> "Scene changed — recalculate to update results"
    PredictionState.RUNNING -> "Calculating…"
    PredictionState.CURRENT -> "Results up to date"
    PredictionState.FAILED -> "Calculation failed — recalculate to retry"
}
