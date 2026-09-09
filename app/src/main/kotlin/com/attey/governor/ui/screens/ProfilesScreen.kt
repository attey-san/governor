package com.attey.governor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.attey.governor.core.InstalledApp
import com.attey.governor.core.Profile
import com.attey.governor.core.Trigger
import com.attey.governor.core.TriggerType
import com.attey.governor.ui.components.ChoiceRow
import com.attey.governor.ui.components.CompactToggle
import com.attey.governor.ui.components.MonoText
import com.attey.governor.ui.components.SectionCard

@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    triggers: List<Trigger>,
    apps: List<InstalledApp>,
    hasUsageAccess: Boolean,
    exportPath: String?,
    onGrantUsageAccess: () -> Unit,
    onSave: (String) -> Unit,
    onApply: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddTrigger: (TriggerType, Int, String, String, String) -> Unit,
    onSetTriggerEnabled: (Long, Boolean) -> Unit,
    onDeleteTrigger: (Long) -> Unit,
    onExportModule: (String) -> Unit,
    onShareModule: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SaveCard(profiles, onSave) }

        if (profiles.isEmpty()) {
            item {
                SectionCard(title = "profiles") {
                    Text(
                        "A profile is the current frequency limits, governors and tunables " +
                            "saved under a name. Triggers can apply saved profiles automatically.",
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
                        Button(
                            onClick = { onApply(p.name) },
                            shape = MaterialTheme.shapes.extraSmall,
                        ) { Text("Apply") }
                        OutlinedButton(
                            onClick = { onExportModule(p.name) },
                            shape = MaterialTheme.shapes.extraSmall,
                        ) { Text("Module") }
                        Spacer(modifier = Modifier.weight(1f))
                        if (p.name != "as found") {
                            TextButton(onClick = { pendingDelete = p.name }) { Text("Delete") }
                        }
                    }
                }
            }
        }

        item {
            TriggersCard(
                profiles, triggers, apps, hasUsageAccess,
                onAddTrigger, onSetTriggerEnabled, onDeleteTrigger, onGrantUsageAccess,
            )
        }

        if (exportPath != null) {
            item {
                SectionCard(title = "module written") {
                    MonoText(exportPath)
                    OutlinedButton(
                        onClick = { onShareModule(exportPath) },
                        shape = MaterialTheme.shapes.extraSmall,
                    ) { Text("Share or save") }
                    Text(
                        "The module applies this profile 45 seconds after boot, following late " +
                            "vendor initialization.",
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
            text = { Text("Triggers assigned to this profile will also be deleted.") },
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
    val valid = name.isNotBlank() && name.trim().length <= 80 && !duplicate

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
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                enabled = valid,
                onClick = { onSave(name.trim()); name = "" },
                shape = MaterialTheme.shapes.extraSmall,
            ) { Text("Save") }
        }
    }
}

@Composable
private fun TriggersCard(
    profiles: List<Profile>,
    triggers: List<Trigger>,
    apps: List<InstalledApp>,
    hasUsageAccess: Boolean,
    onAdd: (TriggerType, Int, String, String, String) -> Unit,
    onSetEnabled: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onGrantUsageAccess: () -> Unit,
) {
    var type by remember { mutableStateOf(TriggerType.UNPLUGGED) }
    var threshold by remember { mutableStateOf("30") }
    var chosen by remember { mutableStateOf<String?>(null) }
    var chosenApp by remember { mutableStateOf<InstalledApp?>(null) }

    val names = profiles.map { it.name }
    val appOptions = apps.associateBy { "${it.label} · ${it.packageName}" }
    // Do not repair selection by writing Compose state during composition.
    val target = chosen?.takeIf { it in names } ?: names.firstOrNull().orEmpty()

    SectionCard(title = "triggers", subtitle = "automatic profile changes") {
        triggers.forEach { t ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = t.description,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (t.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                CompactToggle(
                    checked = t.enabled,
                    onCheckedChange = { onSetEnabled(t.id, it) },
                )
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
        if (type.needsApp) {
            if (!hasUsageAccess) {
                Text(
                    "App triggers require usage access. Foreground-app polling runs only " +
                        "while an app trigger is enabled and the screen is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onGrantUsageAccess,
                    shape = MaterialTheme.shapes.extraSmall,
                ) { Text("Open usage access settings") }
            } else {
                ChoiceRow(
                    label = "app",
                    options = appOptions.keys.toList(),
                    selected = chosenApp?.let { "${it.label} · ${it.packageName}" }
                        ?: appOptions.keys.firstOrNull().orEmpty(),
                    enabled = apps.isNotEmpty(),
                    onSelect = { option -> chosenApp = appOptions[option] },
                )
                Text(
                    "The settings in place when the app opens are put back when you leave it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
            onSelect = { chosen = it },
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
                Spacer(modifier = Modifier.weight(1f))
            }
            val app = chosenApp ?: apps.firstOrNull()
            val level = threshold.toIntOrNull()
            val thresholdValid = when (type) {
                TriggerType.BATTERY_BELOW -> level != null && level in 1..100
                TriggerType.TEMP_ABOVE -> level != null && level in 0..120
                else -> true
            }
            Button(
                enabled = names.isNotEmpty() && target.isNotEmpty() &&
                    (!type.needsThreshold || thresholdValid) &&
                    (!type.needsApp || (hasUsageAccess && app != null)),
                onClick = {
                    onAdd(
                        type,
                        level ?: 0,
                        target,
                        if (type.needsApp) app?.packageName.orEmpty() else "",
                        if (type.needsApp) app?.label.orEmpty() else "",
                    )
                },
                shape = MaterialTheme.shapes.extraSmall,
            ) { Text("Add trigger") }
        }
    }
}
