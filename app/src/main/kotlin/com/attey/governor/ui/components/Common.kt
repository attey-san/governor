package com.attey.governor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Card wrapper used everywhere. Title is a small label; subtitle a one-liner
 * of context (e.g. "cpu7"). Content fills the body. No fancy padding, no
 * images -- this is a tool, not a marketing page.
 */
@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
fun ValueRow(label: String, value: String, enabled: Boolean = true) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        Box(modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

/** Render a missing feature as text, never as silence. */
@Composable
fun NotExposed(label: String) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        Box(modifier = Modifier.weight(1f))
        Text(
            text = "not exposed by this kernel",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

/**
 * Discrete slider snapping to a sorted list of frequencies. kHz in, GHz to 2dp out.
 * Fires onChange only on drag end (Slider's onValueChangeFinished).
 */
@Composable
fun FreqSlider(
    label: String,
    steps: List<Long>,
    value: Long,
    enabled: Boolean,
    onChange: (Long) -> Unit,
) {
    if (steps.isEmpty()) {
        NotExposed(label)
        return
    }
    val sorted = remember(steps) { steps.sorted() }
    // Nearest step, not first-at-or-above. A value the kernel reports but does
    // not list -- a ceiling clamped between two table entries -- used to fall
    // through to index 0, which meant opening the app and nudging the slider
    // applied the *minimum* frequency. Silent, and exactly backwards.
    val currentIndex = sorted.indices.minByOrNull { kotlin.math.abs(sorted[it] - value) } ?: 0
    val maxIndex = (sorted.size - 1).toFloat().coerceAtLeast(1f)
    var dragIndex by remember { mutableStateOf(-1) }
    val displayIndex = if (dragIndex >= 0) dragIndex else currentIndex
    val displayValue = sorted[displayIndex.coerceIn(0, sorted.size - 1)]

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                text = displayValue.kHzToGHz(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
        Slider(
            value = displayIndex.toFloat().coerceIn(0f, maxIndex),
            onValueChange = { dragIndex = it.roundToInt().coerceIn(0, sorted.size - 1) },
            valueRange = 0f..maxIndex,
            // Discrete: one detent per entry in the frequency table.
            steps = (sorted.size - 2).coerceAtLeast(0),
            enabled = enabled,
            onValueChangeFinished = {
                if (dragIndex >= 0) {
                    onChange(sorted[dragIndex])
                    dragIndex = -1
                }
            },
            colors = SliderDefaults.colors(),
        )
    }
}

/**
 * Dropdown selector. The label is on the left, the current value on the right
 * acts as a button. No animations, no fancy chips.
 */
@Composable
fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val labelColor = if (enabled) MaterialTheme.colorScheme.onSurface
                     else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor,
        )
        Box(modifier = Modifier.weight(1f))
        Box {
            TextButton(
                onClick = { if (enabled) expanded = true },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                // The value carries the accent, not the label: a dropdown that
                // renders exactly like static text is a control nobody presses.
                Text(
                    text = selected,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.primary else labelColor,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else labelColor,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = {
                            expanded = false
                            onSelect(opt)
                        },
                    )
                }
            }
        }
    }
}

fun Long.kHzToGHz(): String = String.format(Locale.US, "%.2f GHz", this / 1_000_000.0)

fun Long.formatHz(): String = when {
    this >= 1_000_000_000L -> String.format(Locale.US, "%.2f GHz", this / 1_000_000_000.0)
    this >= 1_000_000L -> String.format(Locale.US, "%d MHz", this / 1_000_000L)
    this >= 1_000L -> String.format(Locale.US, "%d kHz", this / 1_000L)
    else -> "$this Hz"
}

/** Used for the capability report's small monospace paths. */
@Composable
fun MonoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}


/**
 * A live readout: the number large and monospaced, the unit small beside it.
 *
 * One string in a monospace headline puts a full character cell between the
 * number and its unit, which reads as a typo. Splitting them also stops the
 * layout jumping when a digit is added.
 */
@Composable
fun Readout(
    value: String,
    unit: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    caption: String? = null,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text = value, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(
            text = " $unit",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (caption != null) {
            Box(modifier = Modifier.weight(1f))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

/** The numeric half of [kHzToGHz], for use with [Readout]. */
fun Long.kHzValue(): String = String.format(Locale.US, "%.2f", this / 1_000_000.0)
