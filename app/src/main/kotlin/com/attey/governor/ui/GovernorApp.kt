package com.attey.governor.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attey.governor.core.GovernorViewModel
import com.attey.governor.core.PendingRevert
import com.attey.governor.core.UiState
import com.attey.governor.core.UsageAccess
import com.attey.governor.ui.screens.BatteryScreen
import com.attey.governor.ui.screens.CapabilityScreen
import com.attey.governor.ui.screens.CpuScreen
import com.attey.governor.ui.screens.GpuScreen
import com.attey.governor.ui.screens.IoScreen
import com.attey.governor.ui.screens.MeasureScreen
import com.attey.governor.ui.screens.MemoryScreen
import com.attey.governor.ui.screens.ProfilesScreen
import com.attey.governor.ui.screens.ThermalScreen
import java.io.File
import java.util.Locale

private enum class Area(val label: String, val icon: ImageVector) {
    TUNE("Tune", Icons.Filled.Build),
    MONITOR("Monitor", Icons.Filled.Favorite),
    AUTOMATE("Automate", Icons.Filled.Settings),
    EVIDENCE("Evidence", Icons.Filled.Search),
}

private data class Destination(val label: String, val area: Area)

private val DESTINATIONS = listOf(
    Destination("CPU", Area.TUNE),
    Destination("GPU", Area.TUNE),
    Destination("Battery", Area.MONITOR),
    Destination("Thermal", Area.MONITOR),
    Destination("I/O", Area.TUNE),
    Destination("Memory", Area.TUNE),
    Destination("Profiles", Area.AUTOMATE),
    Destination("Measure", Area.EVIDENCE),
    Destination("Capability", Area.EVIDENCE),
)

@Composable
fun GovernorApp(vm: GovernorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    LifecycleStartEffect(vm) {
        vm.onUiStarted()
        onStopOrDispose { vm.onUiStopped() }
    }
    when (val s = state) {
        is UiState.Loading -> LoadingScreen()
        is UiState.NoRoot -> NoRootScreen(message = s.message, onRetry = vm::refresh)
        is UiState.Ready -> ReadyScaffold(s, vm)
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("governor", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.padding(top = 18.dp))
        CircularProgressIndicator(strokeWidth = 3.dp)
        Spacer(modifier = Modifier.padding(top = 12.dp))
        Text(
            "reading kernel interfaces",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoRootScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("root unavailable", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(top = 16.dp))
        Button(
            onClick = onRetry,
            shape = MaterialTheme.shapes.extraSmall,
        ) { Text("Retry") }
    }
}

@Composable
private fun ReadyScaffold(state: UiState.Ready, vm: GovernorViewModel) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    val capabilities by vm.capabilities.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val triggers by vm.triggers.collectAsState()
    val measurement by vm.measurement.collectAsState()
    val lastResult by vm.lastResult.collectAsState()
    val moduleExport by vm.moduleExport.collectAsState()
    val apps by vm.installedApps.collectAsState()
    val hasUsageAccess by vm.hasUsageAccess.collectAsState()
    val context = LocalContext.current
    val destination = DESTINATIONS[selected]
    val area = destination.area
    val areaDestinations = DESTINATIONS.withIndex().filter { it.value.area == area }

    // Usage access is granted outside the app, so recheck on tab changes.
    LaunchedEffect(selected) { vm.recheckUsageAccess() }

    val rejection = state.lastRejection
    LaunchedEffect(rejection) {
        if (rejection != null) {
            snackbarHostState.showSnackbar(rejection)
            vm.dismissRejection()
        }
    }

    Scaffold(
        topBar = {
            AppHeader(
                title = if (areaDestinations.size == 1) destination.label else area.label,
                status = buildString {
                    append(state.device.rootProvider)
                    append(" · ")
                    append(state.device.totalCores)
                    append(" cores")
                    state.live.hottestZone?.let {
                        append(" · ")
                        append(String.format(Locale.US, "%.1f°C", it.second))
                    }
                    if (state.device.kernel.isNotEmpty()) {
                        append(" · ")
                        append(state.device.kernel)
                    }
                },
                destinations = areaDestinations,
                selected = selected,
                onSelect = { selected = it },
            )
        },
        bottomBar = {
            Column {
                state.pending?.let { PendingBar(it, vm::confirmPending, vm::revertPending) }
                AreaNavigation(
                    selected = area,
                    onSelect = { item ->
                        if (area != item) {
                            selected = DESTINATIONS.indexOfFirst { it.area == item }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> CpuScreen(
                    device = state.device,
                    live = state.live,
                    onSetFreq = vm::setPolicyFreq,
                    onSetGovernor = vm::setPolicyGovernor,
                    onSetTunable = vm::setTunable,
                    onSetCoreOnline = vm::setCoreOnline,
                )
                1 -> GpuScreen(
                    gpus = state.device.gpus,
                    live = state.live,
                    onSetFreq = vm::setGpuFreq,
                    onSetGovernor = vm::setGpuGovernor,
                )
                2 -> BatteryScreen(battery = state.device.battery, live = state.live)
                3 -> ThermalScreen(zones = state.device.thermalZones, live = state.live)
                4 -> IoScreen(
                    devices = state.device.blockDevices,
                    onSetScheduler = vm::setScheduler,
                    onSetReadAhead = vm::setReadAhead,
                    onSetNrRequests = vm::setNrRequests,
                )
                5 -> MemoryScreen(
                    device = state.device,
                    onSetVm = vm::setVmTunable,
                    onSetTunable = vm::setTunable,
                )
                6 -> ProfilesScreen(
                    profiles = profiles,
                    triggers = triggers,
                    apps = apps,
                    hasUsageAccess = hasUsageAccess,
                    exportPath = moduleExport,
                    onGrantUsageAccess = {
                        runCatching { context.startActivity(UsageAccess.settingsIntent()) }
                    },
                    onSave = vm::saveCurrentAsProfile,
                    onApply = vm::applyProfile,
                    onDelete = vm::deleteProfile,
                    onAddTrigger = vm::addTrigger,
                    onSetTriggerEnabled = vm::setTriggerEnabled,
                    onDeleteTrigger = vm::deleteTrigger,
                    onExportModule = vm::exportMagiskModule,
                    onShareModule = { path ->
                        val file = File(path)
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.files",
                            file,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Governor module"))
                    },
                )
                7 -> MeasureScreen(
                    device = state.device,
                    live = state.live,
                    profiles = profiles,
                    run = measurement,
                    result = lastResult,
                    onStart = vm::startMeasurement,
                    onStop = vm::stopMeasurement,
                )
                else -> CapabilityScreen(capabilities = capabilities)
            }
        }
    }
}

@Composable
private fun AppHeader(
    title: String,
    status: String,
    destinations: List<IndexedValue<Destination>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "governor",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (destinations.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            ) {
                destinations.forEach { entry ->
                    val active = selected == entry.index
                    Box(
                        modifier = Modifier
                            .widthIn(min = 88.dp)
                            .height(44.dp)
                            .clickable { onSelect(entry.index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = entry.value.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (active) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .width(28.dp)
                                    .height(2.dp)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AreaNavigation(selected: Area, onSelect: (Area) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
        ) {
            Area.entries.forEach { item ->
                val active = selected == item
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(item) }
                        .padding(top = 7.dp, bottom = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(2.dp)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainer,
                            ),
                    )
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingBar(pending: PendingRevert, onConfirm: () -> Unit, onRevert: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(pending.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                "reverting in ${pending.secondsLeft}s",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { pending.fractionLeft },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onRevert) { Text("Undo now") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) { Text("Keep") }
            }
        }
    }
}
