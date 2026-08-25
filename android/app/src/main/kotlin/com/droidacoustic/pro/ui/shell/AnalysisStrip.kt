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
import com.droidacoustic.pro.scene.HeatCell
import com.droidacoustic.pro.scene.Rt60Estimate
import com.droidacoustic.pro.scene.StiEstimate
import com.droidacoustic.pro.ui.components.StatusChip
import com.droidacoustic.pro.ui.theme.Instrument
import kotlin.math.sqrt

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
    rt60: Rt60Estimate?,
    sti: StiEstimate?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val levels = heatmap.map { it.splDb }
    val avg = levels.average().takeIf { levels.isNotEmpty() }?.toFloat()
    val dev = if (levels.size > 1 && avg != null) {
        sqrt(levels.map { (it - avg) * (it - avg) }.average()).toFloat()
    } else null
    val worst = heatmap
        .filter { it.sourceAreaName != null }
        .groupBy { it.sourceAreaName!! }
        .minByOrNull { (_, cells) -> cells.map { it.splDb }.average() }

    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            Metric("Avg audience", avg?.let { "%.1f".format(it) } ?: "—", "dB")
            Metric("Uniformity", dev?.let { "±%.1f".format(it) } ?: "—", "dB")
            Metric("RT60", rt60?.let { "%.2f".format(it.rt60S) } ?: "—", "s")
            Metric(
                "Intelligibility",
                sti?.let { "%.2f".format(it.sti) } ?: "—",
                sti?.quality ?: ""
            )
            if (worst != null) {
                Metric(
                    "Worst zone",
                    worst.key,
                    "%.1f dB".format(worst.value.map { it.splDb }.average())
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
                if (heatmap.isEmpty()) {
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
                    val zones = heatmap
                        .filter { it.sourceAreaName != null }
                        .groupBy { it.sourceAreaName!! }
                    if (zones.isEmpty()) {
                        Text(
                            "Coverage is being sampled on the default grid. " +
                                "Draw audience zones to get per-zone figures.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val globalMin = levels.minOrNull() ?: 0f
                        val globalMax = levels.maxOrNull() ?: 1f
                        zones.forEach { (name, cells) ->
                            ZoneBar(
                                name = name,
                                avgDb = cells.map { it.splDb }.average().toFloat(),
                                spreadDb = (cells.maxOf { it.splDb } - cells.minOf { it.splDb }),
                                min = globalMin,
                                max = globalMax
                            )
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
            text = "±%.1f".format(spreadDb / 2f),
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
