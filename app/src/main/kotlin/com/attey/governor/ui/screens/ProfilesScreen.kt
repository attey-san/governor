package com.attey.governor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.attey.governor.core.Profile
import com.attey.governor.core.Trigger
import com.attey.governor.core.TriggerType
import com.attey.governor.ui.components.ChoiceRow
import com.attey.governor.ui.components.MonoText
import com.attey.governor.ui.components.SectionCard

@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    triggers: List<Trigger>,
    exportPath: String?,
    onSave: (String) -> Unit,
    onApply: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddTrigger: (TriggerType, Int, String) -> Unit,
    onSetTriggerEnabled: (Long, Boolean) -> Unit,
    onDeleteTrigger: (Long) -> Unit,
    onExportModule: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SaveCard(profiles, onSave) }

        if (profiles.isEmpty()) {
            item {
                SectionCard(title = "profiles") {
                    Text(
                        "A profile is the current frequency limits, governors and tunables " +
                            "saved under a name. Save one, then attach it to a trigger below " +
                            "so the phone applies it on its own.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(profiles.size) { i ->
                val p = profiles[i]
                SectionCard(title = p.name, subtitle = "${p.settingCount} settings") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = { onApply(p.name) }) { Text("Apply") }
                        OutlinedButton(onClick = { onExportModule(p.name) }) { Text("Module") }
                        Column(modifier = Modifier.weight(1f)) {}
                        TextButton(onClick = { pendingDelete = p.name }) { Text("Delete") }
                    }
                }
            }
        }

        item { TriggersCard(profiles, triggers, onAddTrigger, onSetTriggerEnabled, onDeleteTrigger) }

        if (exportPath != null) {
            item {
                SectionCard(title = "module written") {
                    MonoText(exportPath)
                    Text(
                        "Flash it in Magisk. It re-applies the profile 45 seconds after boot — " +
                            "vendor init overwrites cpufreq settings written any earlier.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"$name\"?") },
            text = { Text("Any trigger pointing at it is removed too, otherwise it would sit there never firing.") },
            confirmButton = {
                TextButton(onClick = { onDelete(name); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SaveCard(profiles: List<Profile>, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val duplicate = profiles.any { it.name.equals(name.trim(), true) }
    val valid = name.isNotBlank() && !duplicate

    SectionCard(title = "save current settings", subtitle = "captures frequencies, governors and tunables") {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = duplicate,
                label = { Text(if (duplicate) "that name is taken" else "name") },
            )
            Column(modifier = Modifier.width(8.dp)) {}
            Button(enabled = valid, onClick = { onSave(name.trim()); name = "" }) { Text("Save") }
        }
    }
}

@Composable
private fun TriggersCard(
    profiles: List<Profile>,
    triggers: List<Trigger>,
    onAdd: (TriggerType, Int, String) -> Unit,
    onSetEnabled: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var type by remember { mutableStateOf(TriggerType.UNPLUGGED) }
    var threshold by remember { mutableStateOf("30") }
    var target by remember { mutableStateOf("") }

    val names = profiles.map { it.name }
    if (target.isEmpty() && names.isNotEmpty()) target = names.first()

    SectionCard(title = "triggers", subtitle = "the phone applies these on its own") {
        triggers.forEach { t ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = t.description,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (t.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Switch(checked = t.enabled, onCheckedChange = { onSetEnabled(t.id, it) })
                TextButton(onClick = { onDelete(t.id) }) { Text("×") }
            }
        }

        ChoiceRow(
            label = "when",
            options = TriggerType.entries.map { it.label },
            selected = type.label,
            enabled = names.isNotEmpty(),
            onSelect = { label -> TriggerType.entries.firstOrNull { it.label == label }?.let { type = it } },
        )
        if (type.needsThreshold) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (type == TriggerType.BATTERY_BELOW) "percent" else "degrees C",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    modifier = Modifier.width(110.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
        ChoiceRow(
            label = "apply",
            options = names,
            selected = target,
            enabled = names.isNotEmpty(),
            onSelect = { target = it },
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (names.isEmpty()) {
                Text(
                    "save a profile first",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {}
            }
            Button(
                enabled = names.isNotEmpty() && target.isNotEmpty(),
                onClick = { onAdd(type, threshold.toIntOrNull() ?: 0, target) },
            ) { Text("Add trigger") }
        }
    }
}
