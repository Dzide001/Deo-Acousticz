package com.droidacoustic.pro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// =============================================================================
// Shared control primitives
// =============================================================================
//
// Two rules the previous UI broke, encoded here so they cannot be broken again:
//
//  1. NOTHING HAS A FIXED WIDTH THAT ITS LABEL MUST FIT INSIDE. The old chips
//     were sized independently of their content, which is why "1000" rendered
//     as a vertical stack of single digits, "Balanced" became "Balan", and the
//     Bandwidth row rendered as five entirely blank pills. Every control here
//     sizes to its text, with a minimum touch target rather than a maximum.
//
//  2. EVERY NUMBER CAN BE TYPED. Sliders alone cannot express "28.0 m", which
//     is disqualifying in a design tool. NumericField takes typed input and
//     also supports drag-on-the-field for coarse adjustment.
// =============================================================================

private val FieldShape = RoundedCornerShape(4.dp)

/** Minimum comfortable touch target on a tablet. */
private val TouchMin = 40.dp

// ─────────────────────────────────────────────────────────────────────────────
// Numeric entry
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A typed numeric field with a unit suffix.
 *
 * Commits on Done or on focus loss; reverts to [value] if the text will not
 * parse, so a half-typed entry can never write a garbage value into the scene.
 * Horizontal drag adjusts by [dragStep] per 12dp travelled.
 */
@Composable
fun NumericField(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    unit: String = "",
    range: ClosedFloatingPointRange<Float> = -1e6f..1e6f,
    decimals: Int = 1,
    dragStep: Float = 0.1f,
    enabled: Boolean = true
) {
    val focus = LocalFocusManager.current
    val fmt = remember(decimals) { "%.${decimals}f" }

    var text by remember(value) { mutableStateOf(String.format(fmt, value)) }
    var focused by remember { mutableStateOf(false) }

    fun commit() {
        val parsed = text.trim().replace(',', '.').toFloatOrNull()
        if (parsed != null) {
            onValueChange(parsed.coerceIn(range.start, range.endInclusive))
        } else {
            text = String.format(fmt, value)
        }
    }

    Column(modifier = modifier) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.width(0.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 3.dp)
                .fillMaxWidth()
                .heightIn(min = TouchMin)
                .background(
                    if (enabled) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                    FieldShape
                )
                .border(
                    1.dp,
                    if (focused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    FieldShape
                )
                .then(
                    if (enabled) Modifier.pointerInput(value, dragStep) {
                        var acc = 0f
                        detectHorizontalDragGestures(
                            onDragEnd = { acc = 0f }
                        ) { change, drag ->
                            change.consume()
                            acc += drag
                            val steps = (acc / 12f).toInt()
                            if (steps != 0) {
                                acc -= steps * 12f
                                val next = (value + steps * dragStep)
                                    .coerceIn(range.start, range.endInclusive)
                                onValueChange(next)
                            }
                        }
                    } else Modifier
                )
                .padding(horizontal = 10.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.labelLarge.copy(
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    commit()
                    focus.clearFocus()
                }),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { st ->
                        if (focused && !st.isFocused) commit()
                        focused = st.isFocused
                    }
            )
            if (unit.isNotEmpty()) {
                Text(
                    unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** Three numeric fields on one row — the position editor. */
@Composable
fun Vector3Field(
    label: String,
    x: Float, y: Float, z: Float,
    onChange: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    unit: String = "m",
    labels: Triple<String, String, String> = Triple("X", "Y", "Z")
) {
    Column(modifier) {
        SectionLabel(label)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumericField(labels.first, x, { onChange(it, y, z) }, Modifier.weight(1f), unit, decimals = 2)
            NumericField(labels.second, y, { onChange(x, it, z) }, Modifier.weight(1f), unit, decimals = 2)
            NumericField(labels.third, z, { onChange(x, y, it) }, Modifier.weight(1f), unit, decimals = 2)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Segmented control
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A row of mutually exclusive options.
 *
 * Sizes to content and scrolls horizontally when the options do not fit, which
 * is what the old fixed-width chip rows should have done. Labels are never
 * wrapped or ellipsised.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    enabled: Boolean = true
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .horizontalScroll(scroll)
            .background(MaterialTheme.colorScheme.surface, FieldShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, FieldShape)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { opt ->
            val isSel = opt == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    .defaultMinSize(minWidth = 44.dp)
                    .background(
                        if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        RoundedCornerShape(3.dp)
                    )
                    .clickable(enabled = enabled) { onSelect(opt) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    label(opt),
                    // No maxLines, no ellipsis, no fixed width — the box grows.
                    softWrap = false,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        isSel -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Structure
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 5.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** A titled group inside the inspector. */
@Composable
fun InspectorSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            trailing?.invoke()
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/** A compact labelled readout, for values the user cannot edit. */
@Composable
fun Readout(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            softWrap = false
        )
    }
}

/** Small status pill. Sizes to content. */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            softWrap = false
        )
    }
}

/** Integer stepper for counts (array elements, reflection order). */
@Composable
fun IntStepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        SectionLabel(label)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .heightIn(min = TouchMin)
                .background(MaterialTheme.colorScheme.surfaceVariant, FieldShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, FieldShape)
        ) {
            StepButton("−", enabled = value > range.first) {
                onValueChange((value - 1).coerceIn(range.first, range.last))
            }
            Text(
                value.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                softWrap = false,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            StepButton("+", enabled = value < range.last) {
                onValueChange((value + 1).coerceIn(range.first, range.last))
            }
        }
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(38.dp)
            .heightIn(min = TouchMin)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

/** Formats a float without trailing noise, for readouts. */
fun fmt(value: Float, decimals: Int = 1): String = String.format("%.${decimals}f", value)

/** Rounds to a sensible display integer. */
fun fmtInt(value: Float): String = value.roundToInt().toString()
