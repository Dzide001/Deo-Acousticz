package com.droidacoustic.pro.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.droidacoustic.pro.ui.ViewPreset
import com.droidacoustic.pro.ui.components.SegmentedControl
import com.droidacoustic.pro.ui.theme.Instrument
import kotlin.math.roundToInt

// =============================================================================
// Floating viewport controls
// =============================================================================
//
// These sit ON the 3D view rather than in a settings tab. The band you are
// looking at, and the scale the colours mean, have to be visible at the moment
// you are reading the result — in the old layout the band selector lived two
// taps away in Settings, and appeared twice with no indication the two copies
// were the same setting.
// =============================================================================

private val PanelShape = RoundedCornerShape(5.dp)

@Composable
private fun FloatingPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), PanelShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, PanelShape)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) { content() }
}

/** Third-octave band selector, pinned to the viewport. */
@Composable
fun BandOverlay(
    bands: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingPanel(modifier) {
        Column {
            Text(
                "BAND",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
            )
            SegmentedControl(
                options = bands,
                selected = selected,
                onSelect = onSelect,
                // Full label, never abbreviated — this is the control that used
                // to render "1000" as four stacked digits.
                label = { hz -> if (hz >= 1000) "${hz / 1000}k" else "$hz" }
            )
        }
    }
}

/**
 * SPL legend. Shows what the heatmap colours actually mean, which the old UI
 * only ever explained as a sentence of prose in the Results tab.
 */
@Composable
fun SplLegendOverlay(
    minDb: Float,
    maxDb: Float,
    modifier: Modifier = Modifier
) {
    FloatingPanel(modifier) {
        Column(Modifier.width(184.dp)) {
            Text(
                "SPL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
            )
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(9.dp)
            ) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(Instrument.Spl),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${minDb.roundToInt()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${maxDb.roundToInt()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Plan / Section / 3D framing, plus zoom-to-fit. */
@Composable
fun ViewControlsOverlay(
    preset: ViewPreset,
    onPreset: (ViewPreset) -> Unit,
    onFrameAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingPanel(modifier) {
        Column(horizontalAlignment = Alignment.End) {
            SegmentedControl(
                options = ViewPreset.entries.toList(),
                selected = preset,
                onSelect = onPreset,
                label = { it.label }
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(top = 5.dp)
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
                    .clickable(onClick = onFrameAll)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    "Fit to venue",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * Says what the numbers on screen actually mean — summation mode, bandwidth,
 * atmosphere. Engineers distrust prediction tools partly because each one picks
 * these conventions silently and they disagree as a result; stating them is
 * cheap and is the honest thing to do.
 */
@Composable
fun ConventionsOverlay(
    summation: String,
    bandwidth: String,
    temperatureC: Float,
    humidityPct: Float,
    modifier: Modifier = Modifier
) {
    FloatingPanel(modifier) {
        Column {
            Text(
                "$bandwidth · $summation",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = false
            )
            Text(
                "${temperatureC.roundToInt()} °C · ${humidityPct.roundToInt()} % RH",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = false
            )
        }
    }
}

/** Prompt describing what a tap on the floor will do right now. */
@Composable
fun ToolHintOverlay(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = Instrument.Accent
) {
    FloatingPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .background(tint, RoundedCornerShape(2.dp))
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
                softWrap = false
            )
        }
    }
}
